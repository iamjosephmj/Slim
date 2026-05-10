package com.example.slim.bench

/**
 * One row of the results table: a (size, backend) cell summarized by its
 * percentile latencies and derived throughput.
 *
 * `medianNs`, `p95Ns`, `p99Ns` are computed across all measurement
 * iterations from all sweeps. `mpPerSec` is `(size_bytes / median_seconds)
 * / 1e6` — bytes treated as monochannel pixels for a tidy headline.
 */
internal data class CellResult(
    val size: WorkloadSize,
    val backend: Backend,
    val sampleCount: Int,
    val medianNs: Long,
    val p95Ns: Long,
    val p99Ns: Long,
    val minNs: Long,
    val maxNs: Long,
) {
    val medianMs: Double get() = medianNs / 1_000_000.0
    val p95Ms: Double get() = p95Ns / 1_000_000.0
    val p99Ms: Double get() = p99Ns / 1_000_000.0

    val mpPerSec: Double
        get() = if (medianNs == 0L) 0.0
        else (size.bytes.toDouble() / (medianNs / 1e9)) / 1_000_000.0
}

internal enum class Backend(val label: String) {
    JNI("JNI"),
    SLIM("Slim"),
    ;
}

/**
 * Dispatch-cost-only measurement: an empty kernel timed on both backends.
 * Lets the consumer subtract pure dispatch overhead from the main
 * fused-pipeline cells to isolate kernel quality from call overhead.
 */
internal data class BaselineResult(
    val jniMedianNs: Long,
    val jniP95Ns: Long,
    val slimMedianNs: Long,
    val slimP95Ns: Long,
    val sampleCount: Int,
) {
    val deltaNs: Long get() = slimMedianNs - jniMedianNs
}

internal data class FullResults(
    val baseline: BaselineResult,
    val cells: List<CellResult>,
)

internal fun List<Long>.percentile(fraction: Double): Long {
    require(isNotEmpty()) { "no samples" }
    val sorted = sorted()
    val idx = ((sorted.size - 1) * fraction).toInt().coerceIn(0, sorted.size - 1)
    return sorted[idx]
}

/**
 * Build a CSV blob from a finished result set. Two sections: dispatch
 * baseline at top, then one row per (size, backend) cell. Locale.US
 * enforced so decimal separators are dots regardless of device locale.
 */
internal fun resultsToCsv(results: FullResults): String = buildString {
    val L = java.util.Locale.US

    appendLine("# dispatch baseline (empty kernel)")
    appendLine("backend,samples,median_ns,p95_ns")
    append("JNI,").append(results.baseline.sampleCount).append(',')
        .append(results.baseline.jniMedianNs).append(',')
        .append(results.baseline.jniP95Ns).append('\n')
    append("Slim,").append(results.baseline.sampleCount).append(',')
        .append(results.baseline.slimMedianNs).append(',')
        .append(results.baseline.slimP95Ns).append('\n')

    appendLine()
    appendLine("# fused-pipeline measurements")
    appendLine("size,backend,samples,median_ms,p95_ms,p99_ms,min_ms,max_ms,mp_per_sec")
    for (r in results.cells) {
        append(r.size.label).append(',')
        append(r.backend.label).append(',')
        append(r.sampleCount).append(',')
        append("%.4f".format(L, r.medianMs)).append(',')
        append("%.4f".format(L, r.p95Ms)).append(',')
        append("%.4f".format(L, r.p99Ms)).append(',')
        append("%.4f".format(L, r.minNs / 1e6)).append(',')
        append("%.4f".format(L, r.maxNs / 1e6)).append(',')
        append("%.1f".format(L, r.mpPerSec)).append('\n')
    }
}
