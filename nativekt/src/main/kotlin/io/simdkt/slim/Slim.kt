package io.simdkt.slim

import android.content.Context
import io.simdkt.nativekt.KernelHandle
import io.simdkt.nativekt.KernelTemplate
import io.simdkt.nativekt.NativeKt
import io.simdkt.nativekt.engine.Arm64
import io.simdkt.nativekt.engine.Asm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.CoroutineContext

/**
 * Front door for the Slim runtime. Holds process-wide initialization state
 * and the few status flags consumers look at to decide whether kernels can
 * run.
 *
 * ## Lifecycle
 *
 * Exactly one [initialize] call is required before any [slim] block can
 * dispatch. The natural place is `Application.onCreate`:
 *
 * ```
 * class MyApplication : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         Slim.initialize(this)
 *     }
 * }
 * ```
 *
 * `initialize` is idempotent — repeated calls are no-ops. It does not
 * throw on failure; it returns `false` and stashes the diagnostic in
 * [lastError]. Call sites typically don't need to check the return value
 * unless they want to fall back to a non-NEON code path on unsupported
 * devices.
 *
 * ## What initialize actually does
 *
 * The first successful call performs four startup tasks:
 *
 *   1. **Loads `libnktrampoline.so`**, the small JNI helper used by the
 *      hidden-API bypass and the optional fallback dispatch path.
 *   2. **Disables ART's hidden-API enforcement** for this process via a
 *      four-tier cascade (meta-reflection → direct → Java-side targetSdk
 *      Unsafe poke → ELF-parsed `art::Runtime::hidden_api_policy_` flip).
 *      The discovered policy-field offset is cached at
 *      `<context.cacheDir>/nk_policy.bin` so subsequent cold starts skip
 *      the 8 KB probe.
 *   3. **Probes the ART entry-point offset** in `ArtMethod`. Tries 0x18
 *      first (stable across AOSP arm64 since API 28) and falls back
 *      across a small candidate list. Result cached at
 *      `<context.cacheDir>/nk_ep.bin`.
 *   4. **Builds the probe-method pool** of 8 reflective methods used as
 *      EP-hijack dispatch slots. Concurrency above 8 in-flight kernels
 *      blocks on slot acquisition.
 *
 * Cold-start cost on a Samsung S24 / Android 16 with warm caches: ~3 ms.
 * First-ever launch (uncached): ~10 ms.
 *
 * ## Failure modes
 *
 * `initialize` returns `false` if any of the following happen:
 *
 *   - The hidden-API bypass exhausted all four techniques. Inspect
 *     `lastError` and logcat (tag `nk` / `nk-jni`) for which tier failed.
 *   - The ART entry-point probe found no working offset. Indicates an
 *     unusually patched libart (e.g. Magisk modules altering ART internals).
 *   - `libnktrampoline.so` failed to load. Typically means the APK was
 *     built without `arm64-v8a` ABI or stripped of native libs.
 *
 * On any failure, [slim] calls throw `IllegalStateException`.
 *
 * ## Threading
 *
 * `initialize` performs file I/O (the offset-cache files) and reflective
 * lookups; safe to call from any thread, but blocks for a few ms cold and
 * sub-millisecond once warmed. The [slim] entry points are the routine
 * call surface — they're suspending and route their blocking work to the
 * [CoroutineContext] you provide (defaults to [Dispatchers.Default]).
 *
 * @see slim — the kernel entry point.
 * @see io.simdkt.nativekt.NativeKt — the lower-level API surface, exposed
 *   for advanced cases.
 */
object Slim {

    /**
     * One-time runtime initialization.
     *
     * Probes ART internals, disables hidden-API enforcement, loads the JNI
     * helper, builds the probe pool, and warms cache files. See the
     * class-level KDoc on [Slim] for the full sequence.
     *
     * Safe to call multiple times — subsequent calls return immediately
     * without re-running the probes. Safe to call from any thread, but
     * the call blocks until init completes; for cold starts that's a few
     * milliseconds.
     *
     * @param context any [Context]; only its [Context.getCacheDir] is used
     *   (for offset-cache persistence). An `Application` context is
     *   conventional but any subclass works.
     * @return `true` if the runtime is ready to dispatch kernels. `false`
     *   means at least one bypass or probe step failed; consult
     *   [lastError] and logcat for diagnostics. After a `false` return,
     *   [slim] calls will throw.
     */
    fun initialize(context: Context): Boolean = NativeKt.init(context.cacheDir)

    /**
     * `true` once [initialize] has completed successfully. Reset to
     * `false` only by process death.
     *
     * Useful for guarding optional NEON paths:
     *
     * ```
     * val pixels = if (Slim.isReady) {
     *     slim(input) { /* fast NEON path */ }
     *     input
     * } else {
     *     applyBrightnessKotlin(input)
     * }
     * ```
     */
    val isReady: Boolean get() = NativeKt.isReady

    /**
     * Diagnostic message from the most recent failed [initialize]. `null`
     * if init has not been attempted, or succeeded.
     *
     * Format: `"<ExceptionType>: <message>"`, e.g.
     * `"IllegalStateException: art::Runtime instance_ symbol not found"`.
     */
    val lastError: String? get() = NativeKt.lastError
}

// ---------------------------------------------------------------------------
// Top-level slim { } entry point
// ---------------------------------------------------------------------------

/**
 * Compile and run a NEON kernel against the native memory backing
 * [buffer].
 *
 * ## What you write in the body
 *
 * Inside the lambda you write **only ARM64 NEON instructions**. Registers
 * (`X0..X30`, `W0..W30`, `V0..V31`, `XZR`, `SP`, `WZR`, `WSP`), vector
 * arrangements (`B8`, `B16`, `H4`, `H8`, `S2`, `S4`, `D1`, `D2`),
 * condition codes (`EQ`, `NE`, `CS`, ..., `LE`), and ~150 instruction
 * helpers are all in scope. See [Arm64Emitter] for the full list.
 *
 * Two pieces of boilerplate are auto-injected by the engine:
 *
 *   - **Prologue**: a `placeholderDataPtr()` that materializes the
 *     buffer's native address into `x0` before your code runs. ART's
 *     quick-dispatch ABI puts `ArtMethod*` in `x0` on entry to the
 *     hijacked method; the prologue replaces it with the buffer's
 *     pointer.
 *   - **Epilogue**: a trailing `ret()`. You don't write the return.
 *
 * Inside, your code is the kernel body — typically: read parameters from
 * the data buffer, set up a vector loop, process elements, store back.
 *
 * ## Example: brighten a float buffer in place
 *
 * ```
 * val pixels: ByteBuffer = ByteBuffer.allocateDirect(N * 4)
 *     .order(ByteOrder.LITTLE_ENDIAN)
 * pixels.asFloatBuffer().put(input)
 *
 * slim(pixels) {
 *     loadImm32(W3, N)                  // count
 *     mov(X1, X0)                       // walking ptr
 *     val loop = bindLabel()
 *     ld1(V0, X1, S4)                   // load 4 floats
 *     fmul(V0, V0, V0, S4)              // square them
 *     st1(V0, X1, S4)                   // store back
 *     add(X1, X1, 16)                   // advance 4 floats
 *     sub(W3, W3, 4)
 *     cbnz(W3, loop)
 * }
 * ```
 *
 * ## Caching
 *
 * The kernel body is hashed by its assembled byte content. The first call
 * with new bytes compiles a fresh [KernelHandle] (memfd region + RX
 * mapping) and inserts it into a 32-entry LRU cache. Subsequent calls
 * with the same body bytes reuse that handle directly — only the data
 * pointer changes per invocation.
 *
 * **Practical implication**: kernels parameterized by *runtime* values
 * (e.g., `loadImm32(W3, count)` where `count` varies) recompile on every
 * unique value and churn the cache. Bake compile-time constants into the
 * kernel via the `body` closure capturing local vals; reserve runtime
 * variation for the data buffer or for parameters loaded *from* memory
 * inside the kernel.
 *
 * ## Concurrency
 *
 * Multiple coroutines can call `slim {}` concurrently. The probe pool
 * serves up to 8 in-flight dispatches before threads block on slot
 * acquisition. The kernel-handle cache is internally synchronized; each
 * cached handle has a [Mutex] that serializes calls against the same
 * kernel (because slot patches are per-handle state). Different kernels
 * dispatch in parallel.
 *
 * ## Cancellation
 *
 * Coroutine cancellation cannot interrupt a kernel that's already
 * running — once ART jumps through the patched entry point, control
 * returns only via the kernel's `ret`. Cancellation is honored on the
 * coroutine's *next* suspension point after the kernel returns.
 *
 * @param buffer a *direct* (off-heap) [ByteBuffer]. The kernel reads from
 *   and writes to its native-memory window. Heap-backed buffers are
 *   rejected — they would copy on every call. For convenient `FloatArray`
 *   / `IntArray` / `ByteArray` input see the typed overloads; for
 *   zero-copy reuse across many calls see [Floats], [Ints], [Bytes].
 * @param dispatcher coroutine context to run the dispatch on. Defaults
 *   to [Dispatchers.Default] (CPU-bound thread pool). Pass a custom
 *   context to dispatch on your own executor.
 * @param body the kernel itself. Receives a [SlimScope]; emit ARM64
 *   instructions and use [SlimScope.label] / [SlimScope.bindLabel] /
 *   `cbnz` / `b` for control flow.
 * @return `true` if the dispatch completed via the EP-hijack path.
 *   Returns the underlying handle's status; in practice always `true` on
 *   a working device once [Slim.initialize] has succeeded.
 * @throws IllegalArgumentException if [buffer] is not direct.
 * @throws IllegalStateException if [Slim.initialize] hasn't succeeded.
 *
 * @see Slim.initialize
 * @see Floats — zero-copy alternative for repeated calls.
 * @see SlimScope — the receiver type detailing what's in scope.
 */
suspend fun slim(
    buffer: ByteBuffer,
    dispatcher: CoroutineContext = Dispatchers.Default,
    body: SlimScope.() -> Unit,
): Boolean {
    require(buffer.isDirect) { "slim() requires a direct ByteBuffer" }
    val cached = compileAndCache(body)
    return cached.mutex.withLock {
        withContext(dispatcher) { cached.handle.run(buffer) }
    }
}

/**
 * Convenience [slim] overload that operates on a plain [FloatArray].
 *
 * The engine rents a direct-memory buffer from an internal pool, copies
 * `data` in, dispatches the kernel, copies the (possibly mutated) bytes
 * back into `data`, and returns the buffer to the pool. The [FloatArray]
 * is mutated in place — after a successful return, `data[i]` reflects
 * the kernel's writes.
 *
 * ## Cost
 *
 * Each call pays **two memory copies** of size `data.size * 4`:
 *
 *   - heap → direct (before dispatch)
 *   - direct → heap (after dispatch)
 *
 * On a Cortex-X4 these run at ~16 GB/s. For a 16 MB buffer that's roughly
 * 2 ms of overhead — comparable to the kernel itself. For one-shot or
 * cold paths the convenience usually wins; for repeated calls on the same
 * buffer wrap the data in [Floats] once and use the zero-copy overload.
 *
 * The buffer pool is bounded (16 entries) and shared across threads.
 * Concurrent calls each rent their own buffer; sequential calls of the
 * same size reuse the most recently released one.
 *
 * @param data a [FloatArray] of any non-zero length. Mutated in place by
 *   the kernel's writes.
 * @param dispatcher coroutine context for the dispatch. Defaults to
 *   [Dispatchers.Default].
 * @param body the kernel. See the [ByteBuffer] overload's KDoc for the
 *   in-scope DSL.
 * @return `true` on successful dispatch.
 * @throws IllegalStateException if [Slim.initialize] hasn't succeeded.
 *
 * @see Floats for the zero-copy alternative on hot paths.
 */
suspend fun slim(
    data: FloatArray,
    dispatcher: CoroutineContext = Dispatchers.Default,
    body: SlimScope.() -> Unit,
): Boolean {
    val buf = ScratchBuffers.rent(data.size * 4)
    try {
        buf.asFloatBuffer().put(data)
        val ok = slim(buf, dispatcher, body)
        buf.position(0)
        buf.asFloatBuffer().get(data)
        return ok
    } finally {
        ScratchBuffers.release(buf)
    }
}

/**
 * Convenience [slim] overload for [IntArray]. Same cost model as the
 * [FloatArray] overload: two heap↔direct copies of `data.size * 4` bytes
 * per call. Mutated in place.
 *
 * Useful for kernels operating on int lanes (`.4s`/`.2s`) — pixel data,
 * indices, fixed-point math.
 *
 * @param data an [IntArray]. Mutated in place.
 * @param dispatcher coroutine context. Defaults to [Dispatchers.Default].
 * @param body the kernel.
 * @return `true` on successful dispatch.
 * @throws IllegalStateException if [Slim.initialize] hasn't succeeded.
 *
 * @see Ints for the zero-copy alternative.
 */
suspend fun slim(
    data: IntArray,
    dispatcher: CoroutineContext = Dispatchers.Default,
    body: SlimScope.() -> Unit,
): Boolean {
    val buf = ScratchBuffers.rent(data.size * 4)
    try {
        buf.asIntBuffer().put(data)
        val ok = slim(buf, dispatcher, body)
        buf.position(0)
        buf.asIntBuffer().get(data)
        return ok
    } finally {
        ScratchBuffers.release(buf)
    }
}

/**
 * Convenience [slim] overload for [ByteArray]. One heap↔direct copy of
 * `data.size` bytes in each direction per call. Mutated in place.
 *
 * Useful for byte-lane kernels (`.16b`/`.8b`) — packed pixel formats
 * (RGBA8888, YUV planes), masking, bit-twiddling, byte-level saturation.
 *
 * @param data a [ByteArray]. Mutated in place.
 * @param dispatcher coroutine context. Defaults to [Dispatchers.Default].
 * @param body the kernel.
 * @return `true` on successful dispatch.
 * @throws IllegalStateException if [Slim.initialize] hasn't succeeded.
 *
 * @see Bytes for the zero-copy alternative.
 */
suspend fun slim(
    data: ByteArray,
    dispatcher: CoroutineContext = Dispatchers.Default,
    body: SlimScope.() -> Unit,
): Boolean {
    val buf = ScratchBuffers.rent(data.size)
    try {
        buf.put(data)
        val ok = slim(buf, dispatcher, body)
        buf.position(0)
        buf.get(data)
        return ok
    } finally {
        ScratchBuffers.release(buf)
    }
}

/**
 * Concurrent direct-buffer pool for the array-typed [slim] overloads.
 * Each rent returns a buffer that the caller exclusively owns until
 * release; concurrent callers each get their own (allocated on demand,
 * pooled on release). Bounded so abandoned buffers don't leak.
 */
private object ScratchBuffers {
    private const val MAX_POOL = 16
    private val pool = java.util.concurrent.ConcurrentLinkedDeque<ByteBuffer>()

    fun rent(sizeBytes: Int): ByteBuffer {
        // LIFO traversal to find the most recently released buffer of
        // sufficient size. We pop at most one — undersized candidates are
        // dropped (they'll GC) since a smaller buffer in the pool isn't
        // useful for the current caller.
        var cand = pool.pollFirst()
        if (cand != null && cand.capacity() < sizeBytes) cand = null
        val buf = cand ?: ByteBuffer.allocateDirect(sizeBytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.clear()
        buf.limit(sizeBytes)
        return buf
    }

    fun release(buf: ByteBuffer) {
        if (pool.size < MAX_POOL) pool.offerFirst(buf)
    }
}

/**
 * Run a kernel against a raw native pointer — the zero-copy escape hatch
 * for callers managing their own memory. The pointer is what the kernel
 * sees in `x0` after the auto-prologue.
 *
 * Use when:
 *
 *   - The data already lives at a known native address (allocated via
 *     `Unsafe.allocateMemory`, `mmap`, JNI, or `ByteBuffer.address`).
 *   - You want to avoid even the `Floats`/`Ints`/`Bytes` wrapper for
 *     measurement reasons.
 *
 * The caller is responsible for keeping the pointed-to memory alive,
 * properly aligned, and writable for the duration of the kernel. Slim
 * does no validation.
 *
 * @param dataPtr a native address. Will be the value of `x0` inside the
 *   kernel.
 * @param dispatcher coroutine context. Defaults to [Dispatchers.Default].
 * @param body the kernel.
 * @return `true` on successful dispatch.
 * @throws IllegalStateException if [Slim.initialize] hasn't succeeded.
 */
suspend fun slim(
    dataPtr: Long,
    dispatcher: CoroutineContext = Dispatchers.Default,
    body: SlimScope.() -> Unit,
): Boolean {
    val cached = compileAndCache(body)
    return cached.mutex.withLock {
        withContext(dispatcher) { cached.handle.run(dataPtr) }
    }
}

private fun compileAndCache(body: SlimScope.() -> Unit): CachedSlimKernel {
    check(NativeKt.isReady) { "Slim.initialize must be called before slim {}" }
    val scope = SlimScope()
    scope.installPrologue()       // x0 = data pointer (4× movz/movk placeholders)
    scope.body()                  // user's NEON code
    scope.installEpilogue()       // ret
    return SlimCache.getOrCompile(scope.toTemplate())
}

// ---------------------------------------------------------------------------
// SlimScope — the DSL inside slim { }
// ---------------------------------------------------------------------------

/**
 * Receiver inside a [slim] block — the entire user-facing NEON DSL.
 *
 * Inherits ~150 instruction helpers from [Arm64Emitter] and adds
 * label-driven branches that compose with the encoder's two-pass fixup.
 * The data-pointer prologue (4× `movz/movk` materializing the buffer
 * address into `x0`) and the trailing `ret` are auto-injected by the
 * surrounding [slim] function — you never call [installPrologue] or
 * [installEpilogue] yourself.
 *
 * ## What's in scope
 *
 * From [Arm64Emitter]:
 *
 *   - **Registers**: `X0..X30`, `W0..W30`, `V0..V31`, `XZR`, `SP`, `WZR`,
 *     `WSP`. ARM64's r31 is encoded as either zero-register or stack
 *     pointer depending on the instruction; `XZR`/`WZR` and `SP`/`WSP`
 *     map to the right encoding for each helper.
 *   - **Vector arrangements**: `B8`, `B16`, `H4`, `H8`, `S2`, `S4`, `D1`,
 *     `D2`. Pass these as the last argument of vector ops to specify lane
 *     count and width.
 *   - **Conditions**: `EQ`, `NE`, `CS`, `CC`, `MI`, `PL`, `VS`, `VC`,
 *     `HI`, `LS`, `GE`, `LT`, `GT`, `LE`, `AL`. Plus `HS` (alias for `CS`)
 *     and `LO` (alias for `CC`).
 *   - **Index extensions**: `UXTW`, `LSL`, `SXTW`, `SXTX` for register-
 *     offset loads.
 *   - **Instructions**: every encoder helper from `io.simdkt.nativekt.engine.Arm64`
 *     is forwarded as an auto-emit method. `add`, `sub`, `cmp`, `and`,
 *     `orr`, `eor`, `mul` are unified via overloads to accept both
 *     register and immediate operands; their vector forms (e.g.,
 *     `add(V0, V1, V2, S4)`) are the same name. FP forms keep their
 *     `f`-prefixed names (`fadd`, `fmul`, `fmla`, ...) since they're
 *     architecturally distinct.
 *
 * Added on this class:
 *
 *   - [label] / [bind] / [bindLabel] for declaring branch targets.
 *   - [b], [bl], [bCond], [cbz], [cbnz], [tbz], [tbnz] taking a
 *     [Asm.Label] — the byte offset is computed during assembly.
 *
 * For instructions not yet bound to the scope (rare specialized ops),
 * use [Arm64Emitter.raw] with the underlying `Arm64.foo(...)` Int.
 *
 * ## Branch usage
 *
 * Labels can be created up front (forward branch) or bound at the current
 * position (backward branch / loop top):
 *
 * ```
 * slim(data) {
 *     val end = label()                // forward declaration
 *     val loop = bindLabel()           // bind at current position
 *     // ... body ...
 *     cbz(W3, end)                     // exit if zero
 *     sub(W3, W3, 1)
 *     b(loop)                          // jump back to top
 *     bind(end)                        // resolve the forward label
 *     // ... post-loop ...
 * }
 * ```
 *
 * Out-of-range branches (cbz/cbnz: ±1 MB; b/bl: ±128 MB) throw at
 * assemble time, not at run time.
 *
 * ## You don't construct this directly
 *
 * Instances are created and wired up by the [slim] function. The class
 * is `internal`-constructed; users only ever see it as the receiver type
 * inside a block.
 *
 * @see Arm64Emitter — the instruction surface.
 * @see io.simdkt.nativekt.engine.Asm.Label — branch target type.
 */
class SlimScope internal constructor() : Arm64Emitter() {
    private val asm = Asm()
    private var dataPtrSlots: IntArray? = null

    override fun emit(opcode: Int) { asm.add(opcode) }
    override fun emit(opcodes: List<Int>) { asm.add(opcodes) }

    /**
     * Create an unbound [Asm.Label]. Use [bind] to attach it to a position
     * in the instruction stream later (forward branch).
     *
     * @return a fresh, unbound label.
     */
    fun label(): Asm.Label = asm.label()

    /**
     * Bind [label] at the current emission position. Used to resolve a
     * label previously created by [label].
     *
     * @param label a previously-created label, must be unbound.
     * @return the same label, now bound.
     * @throws IllegalStateException if [label] is already bound.
     */
    fun bind(label: Asm.Label): Asm.Label = asm.bind(label)

    /**
     * Convenience: create + bind a label in one step. Use this for
     * backward branches (loop tops):
     *
     * ```
     * val loop = bindLabel()     // here
     * // ... body ...
     * cbnz(W3, loop)             // jumps back to `bindLabel()` site
     * ```
     *
     * @return a fresh label, bound at the current position.
     */
    fun bindLabel(): Asm.Label = asm.bindLabel()

    /** Unconditional branch to [target]. Range: ±128 MB. */
    fun b(target: Asm.Label) { asm.b(target) }

    /** Branch with link to [target]. Saves return address in `x30`. */
    fun bl(target: Asm.Label) { asm.bl(target) }

    /** Conditional branch on [cond]. Range: ±1 MB. */
    fun bCond(cond: Arm64.Cond, target: Asm.Label) { asm.bCond(cond, target) }

    /** Compare-and-branch if zero. Range: ±1 MB. */
    fun cbz(rt: Arm64.X, target: Asm.Label) { asm.cbz(rt, target) }
    fun cbz(rt: Arm64.W, target: Asm.Label) { asm.cbz(rt, target) }

    /** Compare-and-branch if non-zero. Range: ±1 MB. */
    fun cbnz(rt: Arm64.X, target: Asm.Label) { asm.cbnz(rt, target) }
    fun cbnz(rt: Arm64.W, target: Asm.Label) { asm.cbnz(rt, target) }

    /** Test-bit-and-branch if zero. Range: ±32 KB. [bit] in 0..63. */
    fun tbz(rt: Arm64.X, bit: Int, target: Asm.Label) { asm.tbz(rt, bit, target) }

    /** Test-bit-and-branch if non-zero. Range: ±32 KB. [bit] in 0..63. */
    fun tbnz(rt: Arm64.X, bit: Int, target: Asm.Label) { asm.tbnz(rt, bit, target) }

    /**
     * Auto-injected by [slim]. Emits 4× `movz`/`movk` placeholders that
     * the engine patches with the buffer's native address before each
     * dispatch.
     */
    internal fun installPrologue(reg: Arm64.X = X0) {
        check(dataPtrSlots == null) { "prologue already installed" }
        val baseByte = asm.size() * 4
        val slots = IntArray(4) { i -> baseByte + i * 4 }
        asm.add(Arm64.movz(reg, 0, shift = 0))
        asm.add(Arm64.movk(reg, 0, shift = 16))
        asm.add(Arm64.movk(reg, 0, shift = 32))
        asm.add(Arm64.movk(reg, 0, shift = 48))
        dataPtrSlots = slots
    }

    /** Auto-injected by [slim]. Emits a trailing `ret`. */
    internal fun installEpilogue() {
        asm.add(Arm64.ret())
    }

    internal fun toTemplate(): KernelTemplate =
        KernelTemplate(asm.assemble(), dataPtrSlots ?: IntArray(0))
}

// ---------------------------------------------------------------------------
// Internal LRU cache — reuses KernelHandle for identical kernel bodies
// ---------------------------------------------------------------------------

internal class CachedSlimKernel(
    val handle: KernelHandle,
    /** Serializes runs against the same handle (handle is single-writer). */
    val mutex: Mutex = Mutex(),
)

/** Wrap ByteArray for use as a HashMap key (content equality + hash). */
private class KernelKey(val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is KernelKey && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = bytes.contentHashCode()
}

private object SlimCache {
    private const val CAPACITY = 32

    private val map = object : LinkedHashMap<KernelKey, CachedSlimKernel>(
        16, 0.75f, /* accessOrder = */ true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<KernelKey, CachedSlimKernel>?,
        ): Boolean {
            if (size > CAPACITY) {
                eldest?.value?.handle?.close()
                return true
            }
            return false
        }
    }

    @Synchronized
    fun getOrCompile(template: KernelTemplate): CachedSlimKernel {
        val key = KernelKey(template.bytes)
        val existing = map[key]
        if (existing != null) return existing
        val handle = NativeKt.compileKernel(template)
        val cached = CachedSlimKernel(handle)
        map[key] = cached
        return cached
    }
}
