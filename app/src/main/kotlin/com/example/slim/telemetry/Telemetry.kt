package com.example.slim.telemetry

import kotlin.math.max

/**
 * Per-frame correctness diagnostic — compares the SIMD output against
 * the scalar reference and summarizes any divergence.
 */
data class MismatchDiag(
    val total: Int,
    val first: Int,
    val last: Int,
    val histogram: IntArray,                          // 8 octants
    val sampledPairs: List<Triple<Int, Int, Int>>,    // (idx, scalar, neon)
) {
    val ok: Boolean get() = total == 0

    companion object {
        val OK: MismatchDiag = MismatchDiag(0, -1, -1, IntArray(8), emptyList())
    }
}

/**
 * Walk the full N-byte buffer once, collecting:
 *   - total number of mismatches
 *   - first and last mismatched index (helps locate misbehaving regions)
 *   - per-octant histogram (where in the buffer the failures sit)
 *   - up to 5 (index, scalar, neon) triples sampled at evenly-spaced
 *     positions, useful for spotting "always 127" or "always original"
 *     patterns at a glance
 */
fun compareDiag(scalar: ByteArray, neon: ByteArray, n: Int): MismatchDiag {
    var total = 0
    var first = -1
    var last = -1
    val hist = IntArray(8)
    val band = max(1, n / 8)
    for (i in 0 until n) {
        if (scalar[i] != neon[i]) {
            if (first < 0) first = i
            last = i
            total++
            hist[(i / band).coerceAtMost(7)]++
        }
    }
    if (total == 0) return MismatchDiag.OK

    val pairs = buildList(5) {
        val step = max(1, n / 256)
        var i = 0
        var shown = 0
        while (i < n && shown < 5) {
            if (scalar[i] != neon[i]) {
                add(Triple(i, scalar[i].toInt() and 0xFF, neon[i].toInt() and 0xFF))
                shown++
            }
            i += step
        }
    }
    return MismatchDiag(total, first, last, hist, pairs)
}

/**
 * Exponential-moving-average accumulator for scalar/SIMD frame times
 * and frame rate. Single-writer; callers must serialize updates if they
 * fire from multiple threads (`@Synchronized` already guards each call).
 */
class TimingAccumulator {
    @Volatile private var scalarMsEMA: Double = 0.0
    @Volatile private var simdMsEMA: Double = 0.0
    @Volatile private var fpsEMA: Double = 0.0
    @Volatile private var lastTickNs: Long = 0L

    @Synchronized
    fun add(scalarNs: Long, simdNs: Long) {
        val scalarMs = scalarNs / 1_000_000.0
        val simdMs = simdNs / 1_000_000.0
        val now = System.nanoTime()
        val dt = if (lastTickNs == 0L) 0.033 else (now - lastTickNs) / 1e9
        lastTickNs = now
        val instantFps = if (dt > 0.0) 1.0 / dt else 0.0
        val a = 0.15
        scalarMsEMA = if (scalarMsEMA == 0.0) scalarMs else (1 - a) * scalarMsEMA + a * scalarMs
        simdMsEMA = if (simdMsEMA == 0.0) simdMs else (1 - a) * simdMsEMA + a * simdMs
        fpsEMA = if (fpsEMA == 0.0) instantFps else (1 - a) * fpsEMA + a * instantFps
    }

    data class Snapshot(val scalarMs: Double, val simdMs: Double, val fps: Double)

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(scalarMsEMA, simdMsEMA, fpsEMA)

    @Synchronized
    fun reset() {
        scalarMsEMA = 0.0
        simdMsEMA = 0.0
        fpsEMA = 0.0
        lastTickNs = 0L
    }
}
