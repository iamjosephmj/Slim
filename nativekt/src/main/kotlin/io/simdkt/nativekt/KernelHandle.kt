package io.simdkt.nativekt

import io.simdkt.nativekt.engine.MemoryExecutor
import java.nio.ByteBuffer

/**
 * A long-lived, compile-once kernel image whose memfd dual-map region
 * stays alive across many invocations.
 *
 * ## Why this exists
 *
 * `slim {}` and [NativeKt.executeTemplate] take a fresh [KernelTemplate]
 * per call: each dispatch allocates a memfd, writes the bytes, and frees
 * everything when the call returns. For workloads that invoke the *same*
 * kernel many times, that mmap/munmap traffic dominates.
 *
 * `KernelHandle` keeps the memfd region (and its RW + RX mappings) alive
 * until [close]. Per-call work shrinks to:
 *
 *   1. Patch four `imm16` slots in the live RW mapping with the data
 *      pointer (4 × 4-byte writes via `peek`/`poke`).
 *   2. Acquire a probe slot from the engine's pool.
 *   3. Patch `entry_point_from_quick_compiled_code`, invoke, restore.
 *   4. Return the probe slot.
 *
 * Total ~3 µs of dispatch overhead on a Cortex-X4, dominated by the EP
 * patch/unpatch via `Unsafe.peekLong/pokeLong`. For kernels that take
 * 100s of µs (typical of NEON image processing), that's <1% overhead;
 * for sub-µs kernels, the overhead is the dominant cost and you may
 * want to use the lower-level `Trampoline.callAndCheck` directly.
 *
 * ## Lifecycle
 *
 * Compile via [NativeKt.compileKernel] (or `Slim.compileKernel` in V3 if
 * exposed). Use [run] freely. [close] releases the underlying memfd
 * region and unmaps both the RW and RX views; subsequent [run] calls
 * throw.
 *
 * Implements [AutoCloseable], so:
 *
 * ```
 * NativeKt.compileKernel(template).use { handle ->
 *     for (frame in stream) handle.run(frame)
 * }
 * ```
 *
 * Forgetting to close leaks one direct buffer + one fd per template
 * until process death. The leak is bounded (compile sites are typically
 * fixed at app startup) but worth catching in CI for non-trivial apps.
 *
 * ## Threading
 *
 * `KernelHandle` is **single-writer** by contract. The slot patch in
 * [run] writes to a shared 16-byte region in the RW mapping; concurrent
 * calls would race the writes and produce mixed results.
 *
 * For parallel workloads, give each worker its own handle (compiled
 * from the same [KernelTemplate]). The engine's probe pool (8 slots)
 * lets up to 8 such handles dispatch concurrently before threads block
 * on probe-slot acquisition.
 *
 * The high-level `slim {}` API works around this by wrapping each
 * cached handle in a `Mutex` — concurrent callers serialize on the
 * same kernel, parallelize across kernels.
 *
 * ## Memory layout
 *
 * Backed by a `memfd_create`'d page (rounded up to 4 KB) mapped twice
 * via the engine's [MemoryExecutor.LibcoreOs] reflection wrapper:
 *
 *   - RW mapping at [region.rwAddr] — slot patches happen here.
 *   - RX mapping at [region.rxAddr] — ART jumps to this address.
 *
 * Same physical page is shared between the two mappings, so RW writes
 * are visible at RX without explicit cache flushes (the order
 * `mmap RW` → `write code` → `mmap RX` → `execute` dodges I-cache
 * staleness because the RX VA is fresh at execute time).
 *
 * @see NativeKt.compileKernel — produces instances.
 * @see KernelTemplate — the input to compilation.
 * @see io.simdkt.slim.slim — the high-level API; uses handles internally
 *   via an LRU cache.
 */
class KernelHandle internal constructor(
    @JvmField internal val region: MemoryExecutor.Region,
    @JvmField internal val dataPtrSlots: IntArray,
) : AutoCloseable {

    @Volatile
    private var closed: Boolean = false

    /** Number of bytes occupied by the kernel image (post-page-rounding). */
    val regionSize: Long get() = region.size

    /**
     * Native address of the executable mapping. Useful for diagnostics
     * (printing kernel locations) or for callers using
     * `Trampoline.callAndCheck` directly. Don't write to this address
     * yourself — it's read-only-execute mapped.
     */
    val executableAddress: Long get() = region.rxAddr

    /**
     * Patch the data-pointer slots and dispatch the kernel.
     *
     * The dispatch path is the same as [NativeKt.executeTemplate]: pick a
     * probe method from the pool, hijack its
     * `entry_point_from_quick_compiled_code` to point at this handle's
     * RX address, invoke the probe via reflection, restore the EP.
     *
     * Returns whatever the EP-hijack dispatch reports — in practice
     * always `true` on a working device.
     *
     * @param dataPtr a native pointer that will be in `x0` on entry to
     *   the kernel. Pass [io.simdkt.nativekt.engine.MemoryExecutor.directBufferAddress]
     *   of a [ByteBuffer], or the result of `ByteBuffer.address` for
     *   direct buffers, or any natively-allocated address.
     * @return `true` if dispatch succeeded.
     * @throws IllegalStateException if the handle is closed.
     */
    fun run(dataPtr: Long): Boolean {
        check(!closed) { "kernel handle is closed" }
        return MemoryExecutor.runHandle(this, dataPtr)
    }

    /**
     * Convenience overload: extract the direct buffer's native address
     * and dispatch.
     *
     * @param data a *direct* (off-heap) [ByteBuffer]. Heap-backed
     *   buffers are rejected — they have no stable native address.
     * @return `true` if dispatch succeeded.
     * @throws IllegalArgumentException if [data] is not direct.
     * @throws IllegalStateException if the handle is closed.
     */
    fun run(data: ByteBuffer): Boolean {
        require(data.isDirect) { "data must be a direct ByteBuffer" }
        return run(MemoryExecutor.directBufferAddress(data))
    }

    /**
     * Synchronous zero-copy dispatch against a [io.simdkt.slim.Floats].
     * No coroutine machinery, no thread hops — runs on the calling
     * thread. Use for hot-path kernels where you've already decided
     * which thread you want.
     */
    fun run(data: io.simdkt.slim.Floats): Boolean = run(data.buf)

    /** Synchronous zero-copy dispatch against an [io.simdkt.slim.Ints]. */
    fun run(data: io.simdkt.slim.Ints): Boolean = run(data.buf)

    /** Synchronous zero-copy dispatch against a [io.simdkt.slim.Bytes]. */
    fun run(data: io.simdkt.slim.Bytes): Boolean = run(data.buf)

    /**
     * Free the underlying memfd region. Idempotent; calling [close]
     * twice is a no-op. Subsequent [run] calls throw.
     *
     * The unmap is best-effort — if the underlying `munmap` fails (rare;
     * indicates the kernel mapping was already torn down externally) the
     * exception is swallowed.
     */
    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        region.close()
    }
}
