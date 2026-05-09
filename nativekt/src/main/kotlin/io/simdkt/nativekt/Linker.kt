package io.simdkt.nativekt

import io.simdkt.nativekt.engine.Arm64
import io.simdkt.nativekt.engine.Asm

/**
 * A compiled kernel that may export named entry points and reference
 * external symbols via `bl` / `b`. Multiple [LinkableKernel]s combine
 * into a single [KernelTemplate] via [link].
 *
 * ## What this enables
 *
 * Without linking, every `slim {}` block is a self-contained leaf
 * function — no `bl` to other kernels, no shared subroutines. For
 * libraries of kernels that share helpers (e.g., a `softmax` calling
 * `exp_approx`, a frame pipeline calling `rgb_to_yuv`), each kernel
 * would need to inline the helper, bloating the byte image and
 * defeating I-cache locality.
 *
 * Linking solves this. Each kernel compiles to bytes plus a relocation
 * table of `bl <symbol>` and `b <symbol>` sites. [link] takes a list of
 * compiled kernels, lays them out sequentially in one byte image, and
 * patches each branch immediate with the computed relative offset to
 * the target symbol.
 *
 * ## Entry point
 *
 * The **first** kernel in the list passed to [link] is the entry: its
 * dispatch happens at byte offset 0 of the image, and its data-pointer
 * placeholder slots become the slots of the resulting [KernelTemplate].
 * Other kernels are subroutines callable via `bl <name>` or `bl <export>`.
 *
 * ## Symbol space
 *
 * Each kernel registers its `name` as a symbol pointing to its base
 * address. Additional symbols can be exported at arbitrary offsets via
 * [LinkableScope.export]. Same-address aliasing (e.g. a kernel named
 * `helper` with `export("helper")` at offset 0) is allowed; collisions
 * at different addresses throw at link time.
 *
 * ## Example
 *
 * ```
 * val main = compileLinkable("main") {
 *     placeholderDataPtr()
 *     ldrW(W0, X0, 0)         // load element
 *     bl("square")            // call helper, w0 = w0 * w0
 *     strW(W0, X0, 0)         // store result
 * }
 * val helper = compileLinkable("square") {
 *     export("square")
 *     mul(W0, W0, W0)
 * }
 *
 * val template = link(listOf(main, helper))
 * NativeKt.executeTemplate(template, buffer)
 * ```
 *
 * @see compileLinkable — builder.
 * @see link — resolver.
 * @see LinkableScope — DSL inside the builder block.
 */
class LinkableKernel internal constructor(
    /**
     * The kernel's name, registered as a global symbol pointing to its
     * base offset in the linked image.
     */
    val name: String,
    internal val bytes: ByteArray,
    internal val dataPtrSlots: IntArray,
    /** Local symbol → byte offset within [bytes]. */
    internal val exports: Map<String, Int>,
    /** Sites that need to be patched at link time. */
    internal val externalRefs: List<ExternalRef>,
) {
    /** Size of the kernel's byte image in bytes (pre-link). */
    val size: Int get() = bytes.size
}

/** A `bl`/`b` site whose target is resolved at link time. */
internal class ExternalRef(
    val byteOffset: Int,
    val target: String,
    /** Given a resolved relative byte offset, return the patched 32-bit instruction. */
    val patcher: (Int) -> Int,
)

/**
 * Compile a kernel that can be linked with other kernels. Same DSL as
 * [compileTemplate] plus:
 *  - [LinkableScope.export] — bind a named symbol at the current position.
 *  - [LinkableScope.bl] / [LinkableScope.b] (with `String` target) — emit a
 *    relative branch placeholder resolved at link time.
 */
fun compileLinkable(name: String, block: LinkableScope.() -> Unit): LinkableKernel {
    val scope = LinkableScope(name)
    scope.block()
    return scope.build()
}

class LinkableScope internal constructor(val name: String) {
    private val asm = Asm()
    private var dataPtrSlots: IntArray? = null
    private val exports = mutableMapOf<String, Int>()
    private val externalRefs = mutableListOf<ExternalRef>()

    fun placeholderDataPtr(reg: Arm64.X = Arm64.X0) {
        check(dataPtrSlots == null) { "placeholderDataPtr already emitted" }
        val baseByte = asm.size() * 4
        val slots = IntArray(4) { i -> baseByte + i * 4 }
        asm.add(Arm64.movz(reg, 0, shift = 0))
        asm.add(Arm64.movk(reg, 0, shift = 16))
        asm.add(Arm64.movk(reg, 0, shift = 32))
        asm.add(Arm64.movk(reg, 0, shift = 48))
        dataPtrSlots = slots
    }

    /** Bind [symbol] at the current byte offset in this kernel. */
    fun export(symbol: String) {
        require(symbol !in exports) { "duplicate export '$symbol' in kernel '$name'" }
        exports[symbol] = asm.size() * 4
    }

    /** Emit a `bl <symbol>` whose immediate is patched at link time. */
    fun bl(target: String) {
        externalRefs += ExternalRef(
            byteOffset = asm.size() * 4,
            target = target,
            patcher = { rel -> Arm64.bl(rel) },
        )
        asm.add(0) // placeholder
    }

    /** Emit a `b <symbol>` (unconditional branch, no link). */
    fun b(target: String) {
        externalRefs += ExternalRef(
            byteOffset = asm.size() * 4,
            target = target,
            patcher = { rel -> Arm64.b(rel) },
        )
        asm.add(0) // placeholder
    }

    // Mirror Asm's surface for ergonomics.
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

    internal fun build(): LinkableKernel = LinkableKernel(
        name = name,
        bytes = asm.assemble(),
        dataPtrSlots = dataPtrSlots ?: IntArray(0),
        exports = exports.toMap(),
        externalRefs = externalRefs.toList(),
    )
}

/**
 * Link [parts] sequentially into a single [KernelTemplate] image.
 *
 * ## Layout
 *
 * Kernels are placed back-to-back in the order given. Instructions are
 * always 4-byte aligned by construction, so no padding is inserted
 * between kernels. The total size of the linked image is the sum of
 * each kernel's [LinkableKernel.size].
 *
 * The **first** kernel in [parts] is the entry. Dispatch starts at byte
 * offset 0 of the linked image (the first instruction of the first
 * kernel). The entry's data-pointer placeholder slots become the slots
 * of the resulting template. Trailing kernels are subroutines reachable
 * via `bl`/`b` from any kernel in the list.
 *
 * ## Symbol resolution
 *
 * Each kernel registers two kinds of symbols in a shared global table:
 *
 *   - Its own [LinkableKernel.name], pointing to its base offset.
 *   - Each [LinkableScope.export] declared in its body, at the local
 *     offset where `export()` was called.
 *
 * Same-address aliasing is fine (e.g. a kernel named `square` whose
 * body starts with `export("square")` registers two symbols both at the
 * same address). Different-address collisions throw.
 *
 * After layout, every `bl`/`b` site in any kernel is patched by
 * computing `target_offset - call_site_offset` and re-encoding the
 * branch with that relative immediate. Out-of-range branches (`b/bl`:
 * ±128 MB) throw at link time, surfacing the issue before runtime.
 *
 * ## Errors
 *
 * Throws `IllegalStateException` for:
 *
 *   - Empty [parts] list.
 *   - Duplicate kernel names (different addresses).
 *   - Duplicate exports (different addresses).
 *   - Unresolved symbols (a `bl("foo")` with no `foo` export anywhere).
 *   - Out-of-range branches.
 *
 * @param parts the kernels to link, in layout order. First is the entry.
 * @return a [KernelTemplate] ready for [NativeKt.executeTemplate] or
 *   [NativeKt.compileKernel].
 * @throws IllegalArgumentException if [parts] is empty.
 * @throws IllegalStateException for symbol or branch errors.
 */
fun link(parts: List<LinkableKernel>): KernelTemplate {
    require(parts.isNotEmpty()) { "link requires at least one kernel" }

    // Layout: parts in given order, no padding (instructions are 4-byte
    // aligned by construction, so any concat of kernel byte arrays stays
    // aligned).
    val partBase = HashMap<String, Int>()
    var pos = 0
    for (p in parts) {
        partBase[p.name] = pos
        pos += p.size
    }
    val total = ByteArray(pos)
    for (p in parts) {
        System.arraycopy(p.bytes, 0, total, partBase[p.name]!!, p.size)
    }

    // Global symbol table: kernel.name → kernel.base, plus each export.
    // Same-address aliasing (e.g. kernel "k" with export("k") at offset 0)
    // is allowed; collisions at different addresses are an error.
    val symbols = HashMap<String, Int>()
    fun bindSymbol(sym: String, address: Int) {
        val existing = symbols[sym]
        if (existing != null && existing != address) {
            error("duplicate symbol '$sym' (at 0x${existing.toString(16)} and 0x${address.toString(16)})")
        }
        symbols[sym] = address
    }
    for (p in parts) {
        val base = partBase[p.name]!!
        bindSymbol(p.name, base)
        for ((sym, localOff) in p.exports) {
            bindSymbol(sym, base + localOff)
        }
    }

    // Patch external refs.
    for (p in parts) {
        val base = partBase[p.name]!!
        for (ref in p.externalRefs) {
            val targetOff = symbols[ref.target]
                ?: error("unresolved symbol '${ref.target}' in kernel '${p.name}'")
            val callSite = base + ref.byteOffset
            val rel = targetOff - callSite
            val instr = ref.patcher(rel) // throws if rel is out of range
            writeInstrLE(total, callSite, instr)
        }
    }

    // Entry is parts[0] at offset 0; its dataPtrSlots are valid as-is.
    return KernelTemplate(total, parts[0].dataPtrSlots.copyOf())
}

private fun writeInstrLE(bytes: ByteArray, byteOffset: Int, instr: Int) {
    bytes[byteOffset] = (instr and 0xFF).toByte()
    bytes[byteOffset + 1] = ((instr ushr 8) and 0xFF).toByte()
    bytes[byteOffset + 2] = ((instr ushr 16) and 0xFF).toByte()
    bytes[byteOffset + 3] = ((instr ushr 24) and 0xFF).toByte()
}
