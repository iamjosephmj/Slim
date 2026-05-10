package com.example.slim.bench

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Runs the fused JNI-vs-Slim sweep plus a dispatch baseline, returning
 * percentile timings for each.
 *
 * Two phases:
 *
 *  1. **Dispatch baseline** — both backends called with an empty kernel
 *     on a 16-byte buffer. Higher iter count (5000) since each call is
 *     ~50 ns (JNI) or ~5 µs (Slim).
 *  2. **Fused pipeline sweep** — three sizes × two backends × three
 *     interleaved sweeps, 200 iters per cell. Median, p95, p99 reported.
 *
 * Both phases run on the bench thread — no thread pool, no coroutine
 * fan-out. The only variable per row is the dispatch mechanism.
 */
internal class BenchRunner(
    private val warmupIters: Int = 30,
    private val measureIters: Int = 200,
    private val sweeps: Int = 3,
    private val baselineWarmup: Int = 200,
    private val baselineIters: Int = 5_000,
) {

    fun interface Progress {
        fun onCell(label: String, sweep: Int, totalSweeps: Int)
    }

    /**
     * Returns the dispatch baseline plus one [CellResult] per (size,
     * backend) cell, ordered by size outer, backend inner.
     *
     * @throws IllegalStateException on correctness mismatch.
     */
    fun run(progress: Progress): FullResults {
        val workloads = WorkloadSize.entries.associateWith { Workload(it) }
        val slim = WorkloadSize.entries.associateWith { SlimFusedPipeline(it.bytes) }
        val slimEmpty = SlimEmptyPipeline()

        try {
            correctnessGate(workloads, slim)

            // Phase 1: dispatch baseline.
            progress.onCell("dispatch baseline", 0, sweeps)
            val baseline = measureBaseline(slimEmpty, progress)

            // Phase 2: fused pipeline samples.
            val samples = WorkloadSize.entries.associateWith {
                Backend.entries.associateWith { mutableListOf<Long>() }
            }

            // Warmup, interleaved across cells.
            for (size in WorkloadSize.entries) {
                val w = workloads[size]!!
                w.reset()
                for (backend in Backend.entries) {
                    progress.onCell("warmup ${size.label} ${backend.label}", 0, sweeps)
                    repeat(warmupIters) { runOne(backend, w, slim[size]!!) }
                }
            }

            // Measurement, 3 sweeps.
            for (sweep in 1..sweeps) {
                for (size in WorkloadSize.entries) {
                    val w = workloads[size]!!
                    for (backend in Backend.entries) {
                        progress.onCell("${size.label} ${backend.label}", sweep, sweeps)
                        val bucket = samples[size]!![backend]!!
                        repeat(measureIters) {
                            val t0 = System.nanoTime()
                            runOne(backend, w, slim[size]!!)
                            bucket.add(System.nanoTime() - t0)
                        }
                    }
                }
            }

            val cells = mutableListOf<CellResult>()
            for (size in WorkloadSize.entries) {
                for (backend in Backend.entries) {
                    val bucket = samples[size]!![backend]!!
                    cells.add(CellResult(
                        size = size,
                        backend = backend,
                        sampleCount = bucket.size,
                        medianNs = bucket.percentile(0.50),
                        p95Ns = bucket.percentile(0.95),
                        p99Ns = bucket.percentile(0.99),
                        minNs = bucket.min(),
                        maxNs = bucket.max(),
                    ))
                }
            }
            return FullResults(baseline = baseline, cells = cells)
        } finally {
            for (p in slim.values) p.close()
            slimEmpty.close()
        }
    }

    private fun runOne(backend: Backend, w: Workload, slim: SlimFusedPipeline) {
        when (backend) {
            Backend.JNI -> JniPipeline.runFused(w.buffer)
            Backend.SLIM -> slim.run(w.buffer)
        }
    }

    /**
     * Time JNI empty + Slim empty calls on a 16-byte direct buffer.
     * Returns medians and p95s; the consumer can compute Slim/JNI delta
     * to isolate per-call dispatch overhead from kernel quality.
     */
    private fun measureBaseline(
        slimEmpty: SlimEmptyPipeline,
        progress: Progress,
    ): BaselineResult {
        val buf: ByteBuffer = ByteBuffer.allocateDirect(16).order(ByteOrder.LITTLE_ENDIAN)

        // Warmup
        repeat(baselineWarmup) { JniPipeline.runEmpty(buf) }
        repeat(baselineWarmup) { slimEmpty.run(buf) }

        progress.onCell("baseline JNI", 0, sweeps)
        val jniSamples = ArrayList<Long>(baselineIters)
        repeat(baselineIters) {
            val t0 = System.nanoTime()
            JniPipeline.runEmpty(buf)
            jniSamples.add(System.nanoTime() - t0)
        }

        progress.onCell("baseline Slim", 0, sweeps)
        val slimSamples = ArrayList<Long>(baselineIters)
        repeat(baselineIters) {
            val t0 = System.nanoTime()
            slimEmpty.run(buf)
            slimSamples.add(System.nanoTime() - t0)
        }

        return BaselineResult(
            jniMedianNs = jniSamples.percentile(0.50),
            jniP95Ns = jniSamples.percentile(0.95),
            slimMedianNs = slimSamples.percentile(0.50),
            slimP95Ns = slimSamples.percentile(0.95),
            sampleCount = baselineIters,
        )
    }

    /**
     * Run both backends once on freshly-reset workloads and compare
     * outputs byte-for-byte. JNI is the reference; Slim must match.
     */
    private fun correctnessGate(
        workloads: Map<WorkloadSize, Workload>,
        slim: Map<WorkloadSize, SlimFusedPipeline>,
    ) {
        for (size in WorkloadSize.entries) {
            val w = workloads[size]!!

            w.reset()
            JniPipeline.runFused(w.buffer)
            val ref = w.snapshot()

            w.reset()
            slim[size]!!.run(w.buffer)
            val got = w.snapshot()

            check(got.contentEquals(ref)) {
                "correctness mismatch on ${size.label} / Slim: " +
                    "first diverging byte at index ${firstDiff(ref, got)}"
            }
        }
    }

    private fun firstDiff(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) if (a[i] != b[i]) return i
        return -1
    }
}
