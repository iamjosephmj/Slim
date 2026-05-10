package com.example.slim.bench

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The image sizes the bench sweeps over. Byte counts are deliberately
 * round multiples of 128 (= 8 tiles * 16-byte NEON lane), so any tile
 * count up to 8 cleanly divides the buffer with 16-byte tile alignment.
 *
 * Labels approximate single-channel Y-plane sizes:
 *   - SMALL  (480 KB) ≈ 854x480 Y plane
 *   - MEDIUM (2 MB)   ≈ 1920x1080 Y plane
 *   - LARGE  (8 MB)   ≈ 3840x2160 Y plane
 */
internal enum class WorkloadSize(val label: String, val bytes: Int) {
    SMALL("480p", 480 * 1024),
    MEDIUM("1080p", 2 * 1024 * 1024),
    LARGE("4K", 8 * 1024 * 1024),
}

/**
 * A direct ByteBuffer pre-filled with deterministic pseudo-random bytes
 * (xorshift64, seeded by [SEED]). Both backends operate on this buffer
 * during a measurement cell. [reset] re-fills it for correctness checks.
 *
 * Direct allocation is required so the JNI and Slim paths can both read
 * the same native pointer.
 */
internal class Workload(val size: WorkloadSize) {

    val buffer: ByteBuffer = ByteBuffer
        .allocateDirect(size.bytes)
        .order(ByteOrder.LITTLE_ENDIAN)

    init {
        reset()
    }

    /** Re-fill the buffer with the deterministic byte stream. */
    fun reset() {
        var s = SEED
        val n = size.bytes
        // Generate 8 bytes per xorshift step, then store little-endian.
        var i = 0
        while (i < n) {
            s = s xor (s shl 13)
            s = s xor (s ushr 7)
            s = s xor (s shl 17)
            // Always have room for 8 bytes — sizes are multiples of 8.
            buffer.putLong(i, s)
            i += 8
        }
        buffer.position(0)
        buffer.limit(n)
    }

    /** Snapshot the buffer to a fresh ByteArray (used for correctness gate). */
    fun snapshot(): ByteArray {
        val out = ByteArray(size.bytes)
        buffer.position(0)
        buffer.get(out)
        return out
    }

    companion object {
        const val SEED: Long = 0xC0DECAFEL
    }
}
