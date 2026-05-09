package io.simdkt.slim

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A direct-memory `FloatArray` substitute, designed for hot-path use with
 * [slim].
 *
 * ## Why this exists
 *
 * The convenience [slim] overload that accepts a plain [FloatArray] does
 * the obvious thing: copy heap → direct memory, dispatch the kernel,
 * copy direct → heap. That's two array copies per call — about 2 ms per
 * 16 MB on a Cortex-X4, which can easily dominate the kernel itself.
 *
 * For workloads that call into `slim {}` repeatedly against the same
 * data, [Floats] holds the data in direct memory permanently. Dispatch
 * is **zero-copy** — the kernel reads and writes the same bytes the
 * caller sees through `data[i]`.
 *
 * ## When to use which
 *
 * | API | Per-call cost | Best for |
 * |---|---|---|
 * | `slim(myFloatArray) { ... }` | 2 array copies | One-shot transformations, small data |
 * | `slim(myFloats) { ... }` (this) | Zero copies | Hot paths, repeated calls, large buffers |
 *
 * Construction has a one-time cost — `Floats(myFloatArray)` does one
 * heap → direct copy, just like the convenience overload. The savings
 * accrue across multiple kernel calls.
 *
 * ## API
 *
 * Looks and feels like [FloatArray]:
 *
 * ```
 * val data = Floats(1024)                       // zero-filled, direct
 * val data = Floats(1024) { i -> i.toFloat() }  // generator-filled
 * val data = Floats(myFloatArray)               // copy from heap (one-time)
 *
 * data[0] = 1.0f
 * val x = data[42]
 * data.fill { i -> i.toFloat() * 0.5f }
 *
 * slim(data) {                                  // zero-copy dispatch
 *     // NEON kernel reads/writes data's underlying memory directly
 * }
 *
 * val out: FloatArray = data.toFloatArray()     // optional: read out as heap array
 * ```
 *
 * ## Memory model
 *
 * Backed by a [ByteBuffer.allocateDirect] in little-endian order, sized
 * to `size * 4` bytes. The native memory stays live until the [Floats]
 * instance is garbage-collected; there's no explicit `close()`. Direct
 * buffers are freed by the JVM's `Cleaner` mechanism on the next GC after
 * the last reference disappears.
 *
 * Element access uses the buffer's bulk endianness, so `data[i]` reads
 * exactly the same bits the kernel reads from `[X0 + i*4]`.
 *
 * ## Threading
 *
 * Element-level reads and writes are not synchronized. Concurrent
 * mutation from multiple threads — even non-overlapping indices — is
 * undefined under the JMM. If you need parallel writes, partition the
 * buffer and have each thread own its slice.
 *
 * Concurrent [slim] dispatch on the *same* `Floats` is serialized by the
 * runtime's per-handle `Mutex` (kernels patch shared slot bytes, so they
 * must run one at a time per handle). Dispatch on *different* `Floats`
 * runs in parallel.
 *
 * ## Construction cost
 *
 * `Floats(size)` allocates `size * 4` bytes of native memory. On
 * Android this is an `mmap` syscall under the hood — sub-millisecond
 * regardless of size.
 *
 * `Floats(floatArray)` is the same plus one bulk array copy.
 *
 * @see slim — the dispatch entry point.
 * @see FloatArray — the heap-backed array this substitutes for.
 * @see Ints — sibling type for int data (`.4s`/`.2s` lanes).
 * @see Bytes — sibling type for byte data (`.16b`/`.8b` lanes).
 */
class Floats private constructor(
    @JvmField internal val buf: ByteBuffer,
    /** Number of float elements. The native buffer is `size * 4` bytes. */
    val size: Int,
) {
    /**
     * Read the float at [index]. Equivalent to [FloatArray.get].
     *
     * @param index zero-based index, must be in `[0, size)`.
     * @throws IllegalArgumentException if [index] is out of range.
     */
    operator fun get(index: Int): Float {
        require(index in 0 until size) { "index $index out of range [0, $size)" }
        return buf.getFloat(index * 4)
    }

    /**
     * Write [value] at [index]. Equivalent to [FloatArray.set].
     *
     * @param index zero-based index, must be in `[0, size)`.
     * @param value the float to store.
     * @throws IllegalArgumentException if [index] is out of range.
     */
    operator fun set(index: Int, value: Float) {
        require(index in 0 until size) { "index $index out of range [0, $size)" }
        buf.putFloat(index * 4, value)
    }

    /**
     * Overwrite every element with the value produced by [init].
     *
     * Inlined; the lambda is invoked once per index with no boxing
     * overhead for the float result.
     *
     * @param init function from index to value.
     */
    inline fun fill(init: (Int) -> Float) {
        for (i in 0 until size) this[i] = init(i)
    }

    /**
     * Bulk copy from a heap [FloatArray] into this buffer.
     *
     * Faster than per-element [set] in a loop — uses
     * [java.nio.FloatBuffer.put] which delegates to a native memcpy.
     *
     * @param src source heap array.
     * @param srcOffset starting index in [src]; default 0.
     * @param count number of floats to copy; default `src.size - srcOffset`.
     * @throws IllegalArgumentException if [count] exceeds this buffer's [size].
     */
    fun loadFrom(src: FloatArray, srcOffset: Int = 0, count: Int = src.size - srcOffset) {
        require(count <= size) { "count $count > capacity $size" }
        val view = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        view.position(0)
        view.put(src, srcOffset, count)
    }

    /**
     * Snapshot the contents into a fresh heap [FloatArray].
     *
     * Allocates a new array of length [size] and bulk-copies the direct
     * buffer into it. The caller's `Floats` is unaffected.
     *
     * @return a new heap array containing the current values.
     */
    fun toFloatArray(): FloatArray {
        val out = FloatArray(size)
        val view = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        view.position(0)
        view.get(out)
        return out
    }

    companion object {
        /**
         * Allocate a [Floats] of [size] elements, zero-filled.
         *
         * @param size number of float elements.
         * @return a fresh, zero-filled instance.
         */
        operator fun invoke(size: Int): Floats {
            val b = ByteBuffer.allocateDirect(size * 4).order(ByteOrder.LITTLE_ENDIAN)
            return Floats(b, size)
        }

        /**
         * Allocate a [Floats] of [size] elements, then [Floats.fill] it.
         *
         * @param size number of float elements.
         * @param init function from index to value.
         * @return a fresh, generator-filled instance.
         */
        operator fun invoke(size: Int, init: (Int) -> Float): Floats =
            invoke(size).apply { fill(init) }

        /**
         * Allocate a [Floats] sized to [src] and copy its contents in.
         *
         * Same end state as `Floats(src.size).also { it.loadFrom(src) }`.
         * One-time heap → direct copy at construction; subsequent [slim]
         * calls are zero-copy.
         *
         * @param src source heap array.
         * @return a fresh instance pre-filled from [src].
         */
        operator fun invoke(src: FloatArray): Floats =
            invoke(src.size).apply { loadFrom(src) }
    }
}

/**
 * Run a kernel on a [Floats]. Zero-copy dispatch — the kernel operates
 * directly on the underlying direct buffer's memory.
 *
 * @param data the buffer to operate on. Mutated in place by the kernel's
 *   writes.
 * @param dispatcher coroutine context. Defaults to
 *   [kotlinx.coroutines.Dispatchers.Default].
 * @param body the kernel. See [SlimScope] for what's in scope.
 * @return `true` on successful dispatch.
 * @throws IllegalStateException if [Slim.initialize] hasn't succeeded.
 */
suspend fun slim(
    data: Floats,
    dispatcher: kotlin.coroutines.CoroutineContext = kotlinx.coroutines.Dispatchers.Default,
    body: SlimScope.() -> Unit,
): Boolean = slim(data.buf, dispatcher, body)
