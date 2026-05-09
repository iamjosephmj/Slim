package io.simdkt.nativekt

import io.simdkt.nativekt.engine.Arm64
import io.simdkt.nativekt.engine.Asm

/**
 * A compiled kernel byte image with reserved slots for runtime patching.
 *
 * ## Why this exists
 *
 * ART's quick-dispatch ABI puts `ArtMethod*` in `x0` on entry to the
 * hijacked method — **not** the caller's data pointer. Every kernel
 * that wants the data pointer in a register must overwrite `x0` first.
 *
 * The naive approach — rebuild the entire byte array per call with
 * `loadImm64 x0, #ptr` re-encoded against each new pointer — re-runs
 * the encoder on every dispatch. For tight kernels invoked thousands of
 * times that's wasted work.
 *
 * [KernelTemplate] compiles the kernel **once** with a 4-instruction
 * `movz/movk/movk/movk` placeholder where the data pointer's four
 * 16-bit chunks would normally go. At dispatch time the engine patches
 * just the four `imm16` fields with the actual pointer's chunks; the
 * surrounding kernel is unchanged.
 *
 * ## Lifecycle
 *
 * Build with [compileTemplate]; pass to either:
 *
 *   - [NativeKt.executeTemplate] — fresh memfd region per call (simple
 *     but allocates).
 *   - [NativeKt.compileKernel] returning a [KernelHandle] — region kept
 *     alive across calls, cheaper for repeated dispatch.
 *
 * Templates are immutable; the same instance can be passed to many
 * dispatch sites.
 *
 * ## Linking with other kernels
 *
 * For multi-kernel images (`bl` to a shared subroutine), use
 * [compileLinkable] + [link] instead. The result is a `KernelTemplate`
 * with all `bl` placeholders resolved.
 *
 * @see compileTemplate — primary builder.
 * @see NativeKt.executeTemplate — single-shot dispatch.
 * @see KernelHandle — long-lived dispatch path.
 * @see compileLinkable — for kernels that call others.
 */
class KernelTemplate internal constructor(
    internal val bytes: ByteArray,
    /** Byte offsets of the 4 movz/movk placeholder instructions. */
    internal val dataPtrSlots: IntArray,
) {
    /** Size of the kernel byte image in bytes. */
    val size: Int get() = bytes.size

    /**
     * Hex dump of the compiled kernel image, one 32-bit instruction per
     * line as little-endian bytes (`AA BB CC DD`).
     *
     * Useful for offline disassembly with `llvm-objdump`:
     *
     * ```
     * # paste the bytes (no spaces) into a file
     * echo -n "AABBCCDD..." | xxd -r -p > kernel.bin
     * llvm-objdump -D -b binary -m aarch64 kernel.bin
     * ```
     *
     * The resulting disassembly should match the assembly emitted in the
     * [compileTemplate] block. Mismatches are encoder bugs.
     */
    fun toHex(): String = buildString {
        var i = 0
        while (i < bytes.size) {
            val end = (i + 4).coerceAtMost(bytes.size)
            for (j in i until end) {
                if (j > i) append(' ')
                append("%02X".format(bytes[j].toInt() and 0xFF))
            }
            append('\n')
            i = end
        }
    }

    /**
     * One contiguous lowercase hex string of the kernel bytes — the form
     * `xxd -r -p` expects on stdin.
     */
    fun toHexCompact(): String = buildString(bytes.size * 2) {
        for (b in bytes) append("%02x".format(b.toInt() and 0xFF))
    }
}

/**
 * Build a [KernelTemplate]. Inside the block you can [TemplateScope.add]
 * raw [Arm64] opcodes, use labeled branches via [TemplateScope.b] etc., and
 * — at most once — call [TemplateScope.placeholderDataPtr] to reserve a
 * data-pointer slot.
 *
 * Example:
 * ```
 * val saxpyTemplate = compileTemplate {
 *     placeholderDataPtr()                              // x0 = dataPtr
 *     add(Arm64.mov(Arm64.X1, Arm64.X0))                // y_ptr
 *     add(Arm64.addImm(Arm64.X2, Arm64.X0, 64))         // x_ptr
 *     add(Arm64.addImm(Arm64.X4, Arm64.X0, 128))        // a_ptr
 *     add(Arm64.ld1r(Arm64.V0, Arm64.X4, Arm64.VArr.S4))
 *     add(Arm64.ldrW(Arm64.W3, Arm64.X0, 132))
 *     val loop = bindLabel()
 *     add(Arm64.ld1(Arm64.V1, Arm64.X1, Arm64.VArr.S4))
 *     // ...
 *     cbnz(Arm64.W3, loop)
 *     add(Arm64.ret())
 * }
 * NativeKt.executeTemplate(saxpyTemplate, buffer)
 * ```
 */
fun compileTemplate(block: TemplateScope.() -> Unit): KernelTemplate {
    val scope = TemplateScope()
    scope.block()
    return scope.build()
}

/**
 * Receiver inside a [compileTemplate] block.
 *
 * Mirrors [io.simdkt.nativekt.engine.Asm]'s surface (label-driven
 * branches, instruction emission via [add]) and adds the
 * [placeholderDataPtr] pseudo-instruction that the engine recognizes
 * for runtime data-pointer patching.
 *
 * For most users, [io.simdkt.slim.SlimScope] (the receiver inside
 * `slim {}`) is the better starting point — it auto-injects the
 * data-pointer prologue and trailing `ret`, and exposes every ARM64
 * register/instruction directly without `Arm64.` prefixes. This class
 * is the manual control surface used by callers who want explicit
 * lifecycle management.
 *
 * @see compileTemplate
 * @see io.simdkt.slim.SlimScope — the higher-level alternative.
 */
class TemplateScope internal constructor() {
    private val asm = Asm()
    private var dataPtrSlots: IntArray? = null

    /**
     * Reserve [reg] for the runtime-patched data pointer. Emits four
     * `movz/movk` placeholder instructions that the dispatch path
     * overwrites with the caller-supplied address before each invocation.
     *
     * Must be called at most once; usually first, before any instruction
     * that reads from the chosen register.
     */
    fun placeholderDataPtr(reg: Arm64.X = Arm64.X0) {
        check(dataPtrSlots == null) { "placeholderDataPtr already emitted" }
        val baseByte = asm.size() * 4
        val slots = IntArray(4) { i -> baseByte + i * 4 }
        // Imm16=0 in each slot — patcher overwrites these with the caller's
        // dataPtr at executeTemplate time.
        asm.add(Arm64.movz(reg, 0, shift = 0))
        asm.add(Arm64.movk(reg, 0, shift = 16))
        asm.add(Arm64.movk(reg, 0, shift = 32))
        asm.add(Arm64.movk(reg, 0, shift = 48))
        dataPtrSlots = slots
    }

    // Mirror Asm's surface so users don't need a separate import.
    fun add(opcode: Int) { asm.add(opcode) }
    fun add(opcodes: List<Int>) { asm.add(opcodes) }
    fun add(vararg opcodes: Int) { asm.add(*opcodes) }

    fun label(): Asm.Label = asm.label()
    fun bind(label: Asm.Label): Asm.Label = asm.bind(label)
    fun bindLabel(): Asm.Label = asm.bindLabel()

    fun b(target: Asm.Label) = asm.b(target)
    fun bl(target: Asm.Label) = asm.bl(target)
    fun bCond(cond: Arm64.Cond, target: Asm.Label) = asm.bCond(cond, target)
    fun cbz(rt: Arm64.X, target: Asm.Label) = asm.cbz(rt, target)
    fun cbz(rt: Arm64.W, target: Asm.Label) = asm.cbz(rt, target)
    fun cbnz(rt: Arm64.X, target: Asm.Label) = asm.cbnz(rt, target)
    fun cbnz(rt: Arm64.W, target: Asm.Label) = asm.cbnz(rt, target)
    fun tbz(rt: Arm64.X, bit: Int, target: Asm.Label) = asm.tbz(rt, bit, target)
    fun tbnz(rt: Arm64.X, bit: Int, target: Asm.Label) = asm.tbnz(rt, bit, target)

    internal fun build(): KernelTemplate =
        KernelTemplate(asm.assemble(), dataPtrSlots ?: IntArray(0))
}
