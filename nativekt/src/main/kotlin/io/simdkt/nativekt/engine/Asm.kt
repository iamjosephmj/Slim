package io.simdkt.nativekt.engine

/**
 * Two-pass assembler over the raw [Arm64] encoder. Adds label-driven
 * branches on top of the otherwise-pure encoding helpers.
 *
 * ## Why this exists
 *
 * The [Arm64] encoder takes byte offsets directly: `Arm64.cbnz(W3, -28)`
 * means "branch back 28 bytes". For trivial loops this is fine. For
 * anything more complex — multi-way branches, forward jumps, nested
 * loops — counting bytes by hand is error-prone and brittle to changes.
 *
 * `Asm` adds named [Label]s. Mark a position with [bind] / [bindLabel];
 * branch to it from anywhere; the actual byte offset is computed at
 * [assemble] time. Forward and backward branches both work; out-of-range
 * offsets throw clearly at assemble time rather than silently emitting
 * garbage.
 *
 * ## Usage
 *
 * ```
 * val asm = Asm()
 * asm.add(Arm64.movz(Arm64.W3, 16))            // count = 16
 * val loop = asm.bindLabel()                    // loop:
 * asm.add(Arm64.subImm(Arm64.W3, Arm64.W3, 1))  //   count -= 1
 * asm.cbnz(Arm64.W3, loop)                      //   if count != 0, jump to loop
 * asm.add(Arm64.ret())
 * val bytes = asm.assemble()                    // resolves all branches, returns LE byte image
 * ```
 *
 * Forward branches:
 *
 * ```
 * val asm = Asm()
 * val skip = asm.label()                        // declare unbound
 * asm.add(Arm64.cmpImm(Arm64.X0, 0))
 * asm.bCond(Arm64.Cond.EQ, skip)                // branch if zero...
 * asm.add(Arm64.movz(Arm64.W1, 1))              //   ... skip this
 * asm.bind(skip)                                // skip:
 * asm.add(Arm64.ret())
 * val bytes = asm.assemble()
 * ```
 *
 * ## Mixing with the raw encoder
 *
 * `Asm` and `Arm64.assemble(List<Int>)` produce byte-compatible output
 * — under the hood [assemble] just calls `Arm64.assemble` after
 * resolving fixups. Use [Asm] when branches are involved; the raw
 * `Arm64.assemble(List<Int>)` path is fine for one-shot leaf kernels
 * where hand-counting the offset is trivial.
 *
 * ## Errors
 *
 * - `IllegalStateException` at [assemble] if any [Label] was used as a
 *   branch target but never [bind]'d.
 * - `IllegalArgumentException` at [assemble] if a computed branch
 *   offset overflows the instruction's immediate range:
 *   - `b/bl`: ±128 MB.
 *   - `b.cond/cbz/cbnz`: ±1 MB.
 *   - `tbz/tbnz`: ±32 KB.
 * - `IllegalStateException` at [bind] if the label is already bound.
 *
 * ## Threading
 *
 * Not thread-safe. `Asm` is intended as a transient builder: create,
 * fill, [assemble], discard. Sharing instances across threads requires
 * external synchronization.
 *
 * @see Arm64 — the underlying pure encoder.
 * @see Arm64.assemble — the byte-packing primitive used internally.
 */
class Asm {
    /**
     * A target site for a branch.
     *
     * Created by [label]; bound to a position in the instruction stream
     * by [bind] (or in one step via [bindLabel]). A label can be:
     *
     *   - **Forward-declared**: created via [label], referenced by a
     *     branch helper, then [bind]'d later when the target site is
     *     reached.
     *   - **Backward target**: created and bound at the same point via
     *     [bindLabel], then referenced later (e.g., a loop top).
     *
     * Each label can be [bind]'d at most once. Referencing it from
     * multiple branch sites is fine — they'll all resolve to the same
     * offset. Referencing it without ever binding throws at [assemble]
     * time.
     *
     * Labels carry no public state. The constructor is internal — you
     * always go through [Asm.label] / [Asm.bindLabel].
     */
    class Label internal constructor() {
        /** Byte offset within the assembled image; -1 until bound. */
        internal var byteOffset: Int = -1

        internal val isBound: Boolean get() = byteOffset >= 0

        internal fun bindAt(offset: Int) {
            check(!isBound) { "label already bound at byte offset $byteOffset" }
            byteOffset = offset
        }
    }

    private class Fixup(
        val siteByteOffset: Int,
        val target: Label,
        /** Patches the placeholder using the resolved relative byte offset. */
        val patcher: (Int) -> Int,
    )

    private val instrs = mutableListOf<Int>()
    private val fixups = mutableListOf<Fixup>()

    private val byteOffset: Int get() = instrs.size * 4

    // -----------------------------------------------------------------
    // Label management
    // -----------------------------------------------------------------

    fun label(): Label = Label()

    /** Mark [label] as the current position in the instruction stream. */
    fun bind(label: Label): Label {
        label.bindAt(byteOffset)
        return label
    }

    /** Convenience: create a label, bind it here, return it. */
    fun bindLabel(): Label = bind(Label())

    // -----------------------------------------------------------------
    // Plain instruction emission
    // -----------------------------------------------------------------

    /** Emit a single 32-bit opcode. */
    fun add(opcode: Int) { instrs += opcode }

    /** Emit a sequence (e.g. the result of [Arm64.loadImm64]). */
    fun add(opcodes: List<Int>) { instrs += opcodes }

    /** Convenience that mirrors [Arm64.assemble]'s vararg form. */
    fun add(vararg opcodes: Int) { for (op in opcodes) instrs += op }

    // -----------------------------------------------------------------
    // Branches with deferred fixup
    // -----------------------------------------------------------------

    fun b(target: Label) = emitFixup(target) { off -> Arm64.b(off) }
    fun bl(target: Label) = emitFixup(target) { off -> Arm64.bl(off) }
    fun bCond(cond: Arm64.Cond, target: Label) =
        emitFixup(target) { off -> Arm64.bCond(cond, off) }

    fun cbz(rt: Arm64.X, target: Label) = emitFixup(target) { off -> Arm64.cbz(rt, off) }
    fun cbz(rt: Arm64.W, target: Label) = emitFixup(target) { off -> Arm64.cbz(rt, off) }
    fun cbnz(rt: Arm64.X, target: Label) = emitFixup(target) { off -> Arm64.cbnz(rt, off) }
    fun cbnz(rt: Arm64.W, target: Label) = emitFixup(target) { off -> Arm64.cbnz(rt, off) }

    fun tbz(rt: Arm64.X, bit: Int, target: Label) =
        emitFixup(target) { off -> Arm64.tbz(rt, bit, off) }
    fun tbnz(rt: Arm64.X, bit: Int, target: Label) =
        emitFixup(target) { off -> Arm64.tbnz(rt, bit, off) }

    private fun emitFixup(target: Label, patcher: (Int) -> Int) {
        fixups += Fixup(byteOffset, target, patcher)
        instrs += 0 // placeholder; patched in assemble()
    }

    // -----------------------------------------------------------------
    // Finalize
    // -----------------------------------------------------------------

    /**
     * Resolve all labels, patch fixups, and return the little-endian byte
     * image. Throws if any label is unbound or any branch is out of range
     * for its instruction.
     */
    fun assemble(): ByteArray {
        for (fx in fixups) {
            check(fx.target.isBound) {
                "branch at byte 0x${fx.siteByteOffset.toString(16)} targets unbound label"
            }
            val rel = fx.target.byteOffset - fx.siteByteOffset
            // patcher will throw if rel is out of range for this branch kind
            instrs[fx.siteByteOffset / 4] = fx.patcher(rel)
        }
        return Arm64.assemble(instrs)
    }

    /** Number of instructions emitted so far (handy for sanity-checking). */
    fun size(): Int = instrs.size
}
