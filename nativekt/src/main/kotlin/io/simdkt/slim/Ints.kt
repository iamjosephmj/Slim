package io.simdkt.slim

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Direct-memory `IntArray` substitute for hot-path [slim] kernels
 * operating on int lanes (`.4s`/`.2s`).
 *
 * Same shape and semantics as [Floats], just `Int` instead of `Float`,
 * 4 bytes per element. See [Floats] for the design notes — when to use
 * which, memory model, threading, construction cost.
 *
 * Common workloads:
 *
 *   - **Pixel ops**: ARGB ints, packed RGB565, integer-valued image
 *     filters.
 *   - **Index buffers**: lookup tables, gather/scatter indices.
 *   - **Fixed-point math**: Q-format DSP where the data lives in 32-bit
 *     ints (e.g., audio sample values, precomputed sin/cos tables).
 *
 * ```
 * val pixels = Ints(width * height)             // zero-filled
 * val pixels = Ints(width * height) { it }      // 0, 1, 2, ...
 * val pixels = Ints(myIntArray)                 // copy from heap
 *
 * pixels[0] = 0xFF112233.toInt()
 *
 * slim(pixels) {
 *     // NEON int-lane kernel
 * }
 *
 * val out: IntArray = pixels.toIntArray()
 * ```
 *
 * @see Floats — sibling for 32-bit float data.
 * @see Bytes — sibling for 8-bit byte data.
 * @see slim — dispatch entry point.
 */
class Ints private constructor(
    @JvmField internal val buf: ByteBuffer,
    /** Number of int elements. The native buffer is `size * 4` bytes. */
    val size: Int,
) {
    /**
     * Read the int at [index].
     *
     * @throws IllegalArgumentException if [index] is out of range.
     */
    operator fun get(index: Int): Int {
        require(index in 0 until size) { "index $index out of range [0, $size)" }
        return buf.getInt(index * 4)
    }

    /**
     * Write [value] at [index].
     *
     * @throws IllegalArgumentException if [index] is out of range.
     */
    operator fun set(index: Int, value: Int) {
        require(index in 0 until size) { "index $index out of range [0, $size)" }
        buf.putInt(index * 4, value)
    }

    /** Overwrite every element with `init(i)`. Inlined; no boxing. */
    inline fun fill(init: (Int) -> Int) {
        for (i in 0 until size) this[i] = init(i)
    }

    /**
     * Bulk copy from a heap [IntArray] into this buffer.
     *
     * @throws IllegalArgumentException if [count] exceeds [size].
     */
    fun loadFrom(src: IntArray, srcOffset: Int = 0, count: Int = src.size - srcOffset) {
        require(count <= size) { "count $count > capacity $size" }
        val view = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        view.position(0)
        view.put(src, srcOffset, count)
    }

    /** Snapshot to a fresh heap [IntArray]. */
    fun toIntArray(): IntArray {
        val out = IntArray(size)
        val view = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        view.position(0)
        view.get(out)
        return out
    }

    companion object {
        /** Zero-filled. */
        operator fun invoke(size: Int): Ints {
            val b = ByteBuffer.allocateDirect(size * 4).order(ByteOrder.LITTLE_ENDIAN)
            return Ints(b, size)
        }

        /** Generator-filled. */
        operator fun invoke(size: Int, init: (Int) -> Int): Ints =
            invoke(size).apply { fill(init) }

        /** Pre-filled from an existing [IntArray]. */
        operator fun invoke(src: IntArray): Ints =
            invoke(src.size).apply { loadFrom(src) }
    }
}

/**
 * Run a kernel on [Ints]. Zero-copy dispatch.
 *
 * @return `true` on successful dispatch.
 * @throws IllegalStateException if [Slim.initialize] hasn't succeeded.
 */
suspend fun slim(
    data: Ints,
    dispatcher: kotlin.coroutines.CoroutineContext = kotlinx.coroutines.Dispatchers.Default,
    body: SlimScope.() -> Unit,
): Boolean = slim(data.buf, dispatcher, body)
