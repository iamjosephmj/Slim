package io.simdkt.slim

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Direct-memory `ByteArray` substitute for hot-path [slim] kernels
 * operating on byte lanes (`.16b`/`.8b`).
 *
 * Same shape and semantics as [Floats], just `Byte` instead of `Float`,
 * 1 byte per element. See [Floats] for the design notes — when to use
 * which, memory model, threading, construction cost.
 *
 * Common workloads:
 *
 *   - **Image manipulation in packed pixel formats**: RGBA8888, RGB565
 *     (after unpacking), YUV planes. The 16-byte vector is one full
 *     `.16b` register-load.
 *   - **Masking / bit-twiddling**: bitwise AND/OR/XOR with constants,
 *     byte-wise compare-and-select.
 *   - **String / sequence search**: vectorized strchr/memchr style
 *     kernels.
 *   - **Saturating byte arithmetic**: brightness/contrast adjustment
 *     directly on byte channels with `sqadd`/`uqadd`/`uqsub`.
 *
 * ```
 * val pixels = Bytes(width * height * 4)        // zero-filled, RGBA bytes
 * val pixels = Bytes(myByteArray)               // copy from heap
 *
 * pixels[0] = 0xFF.toByte()
 *
 * slim(pixels) {
 *     // NEON byte-lane kernel — load 16 bytes per iteration with `ld1 V0, X1, B16`
 * }
 *
 * val out: ByteArray = pixels.toByteArray()
 * ```
 *
 * @see Floats — sibling for 32-bit float data.
 * @see Ints — sibling for 32-bit int data.
 * @see slim — dispatch entry point.
 */
class Bytes private constructor(
    @JvmField internal val buf: ByteBuffer,
    /** Number of byte elements. The native buffer is exactly [size] bytes. */
    val size: Int,
) {
    /**
     * Read the byte at [index].
     *
     * @throws IllegalArgumentException if [index] is out of range.
     */
    operator fun get(index: Int): Byte {
        require(index in 0 until size) { "index $index out of range [0, $size)" }
        return buf.get(index)
    }

    /**
     * Write [value] at [index].
     *
     * @throws IllegalArgumentException if [index] is out of range.
     */
    operator fun set(index: Int, value: Byte) {
        require(index in 0 until size) { "index $index out of range [0, $size)" }
        buf.put(index, value)
    }

    /** Overwrite every element with `init(i)`. Inlined; no boxing. */
    inline fun fill(init: (Int) -> Byte) {
        for (i in 0 until size) this[i] = init(i)
    }

    /**
     * Bulk copy from a heap [ByteArray] into this buffer.
     *
     * @throws IllegalArgumentException if [count] exceeds [size].
     */
    fun loadFrom(src: ByteArray, srcOffset: Int = 0, count: Int = src.size - srcOffset) {
        require(count <= size) { "count $count > capacity $size" }
        buf.position(0)
        buf.put(src, srcOffset, count)
    }

    /** Snapshot to a fresh heap [ByteArray]. */
    fun toByteArray(): ByteArray {
        val out = ByteArray(size)
        buf.position(0)
        buf.get(out)
        return out
    }

    companion object {
        /** Zero-filled. */
        operator fun invoke(size: Int): Bytes {
            val b = ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN)
            return Bytes(b, size)
        }

        /** Generator-filled. */
        operator fun invoke(size: Int, init: (Int) -> Byte): Bytes =
            invoke(size).apply { fill(init) }

        /** Pre-filled from an existing [ByteArray]. */
        operator fun invoke(src: ByteArray): Bytes =
            invoke(src.size).apply { loadFrom(src) }
    }
}

/**
 * Run a kernel on [Bytes]. Zero-copy dispatch.
 *
 * @return `true` on successful dispatch.
 * @throws IllegalStateException if [Slim.initialize] hasn't succeeded.
 */
suspend fun slim(
    data: Bytes,
    dispatcher: kotlin.coroutines.CoroutineContext = kotlinx.coroutines.Dispatchers.Default,
    body: SlimScope.() -> Unit,
): Boolean = slim(data.buf, dispatcher, body)
