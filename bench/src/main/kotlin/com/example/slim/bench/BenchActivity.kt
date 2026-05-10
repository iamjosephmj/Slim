package com.example.slim.bench

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.system.Os
import android.system.OsConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import io.simdkt.nativekt.NativeKt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class BenchActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var deviceInfo: TextView
    private lateinit var runBtn: MaterialButton
    private lateinit var copyBtn: MaterialButton
    private lateinit var progress: ProgressBar
    private lateinit var resultsContainer: LinearLayout

    private var lastCsv: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bench)

        applyEdgeToEdgeInsets()

        status = findViewById(R.id.status)
        deviceInfo = findViewById(R.id.deviceInfo)
        runBtn = findViewById(R.id.runBtn)
        copyBtn = findViewById(R.id.copyBtn)
        progress = findViewById(R.id.progress)
        resultsContainer = findViewById(R.id.resultsContainer)

        NativeKt.init(filesDir)
        deviceInfo.text = describeDevice()

        runBtn.setOnClickListener { startRun() }
        copyBtn.setOnClickListener { copyCsv() }
    }

    private fun applyEdgeToEdgeInsets() {
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sysBars.left, sysBars.top, sysBars.right, sysBars.bottom)
            insets
        }
    }

    private fun startRun() {
        if (!NativeKt.isReady) {
            status.text = "NativeKt init failed: ${NativeKt.lastError ?: "(unknown)"}"
            return
        }
        runBtn.isEnabled = false
        copyBtn.isEnabled = false
        resultsContainer.removeAllViews()
        progress.visibility = View.VISIBLE
        progress.progress = 0
        status.text = getString(R.string.status_running)

        val runner = BenchRunner()

        // Progress cells:
        //   baseline: 2 (JNI + Slim)
        //   warmup: 3 sizes × 2 backends = 6
        //   measure: 3 sweeps × 3 sizes × 2 backends = 18
        val totalCells = 2 + 6 + 18
        progress.max = totalCells
        var completed = 0

        lifecycleScope.launch {
            try {
                val results = withContext(Dispatchers.Default) {
                    runner.run { label, sweep, totalSweeps ->
                        completed += 1
                        runOnUiThread {
                            progress.progress = completed
                            status.text = if (sweep == 0) label
                            else "sweep $sweep/$totalSweeps · $label"
                        }
                    }
                }
                lastCsv = resultsToCsv(results)
                renderResults(results)
                status.text = getString(R.string.status_done)
                copyBtn.isEnabled = true
            } catch (t: Throwable) {
                status.text = "ERROR: ${t.message}"
                val err = TextView(this@BenchActivity).apply {
                    text = t.stackTraceToString()
                    typeface = android.graphics.Typeface.MONOSPACE
                    textSize = 11f
                }
                resultsContainer.removeAllViews()
                resultsContainer.addView(err)
            } finally {
                runBtn.isEnabled = true
                progress.visibility = View.GONE
            }
        }
    }

    private fun copyCsv() {
        if (lastCsv.isEmpty()) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("slim-bench", lastCsv))
        Toast.makeText(this, R.string.copy_toast, Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- UI

    private fun renderResults(results: FullResults) {
        resultsContainer.removeAllViews()
        addBaselineCard(results.baseline)

        val bySize = results.cells.groupBy { it.size }
        for (size in WorkloadSize.entries) {
            val cells = bySize[size] ?: continue
            val jni = cells.firstOrNull { it.backend == Backend.JNI } ?: continue
            val slim = cells.firstOrNull { it.backend == Backend.SLIM } ?: continue
            addSizeCard(size, jni, slim)
        }
    }

    private val inflater: LayoutInflater by lazy { LayoutInflater.from(this) }

    private fun addBaselineCard(b: BaselineResult) {
        val view = inflater.inflate(R.layout.card_baseline, resultsContainer, false)
        val jniBar = view.findViewById<ProgressBar>(R.id.jniBar)
        val slimBar = view.findViewById<ProgressBar>(R.id.slimBar)
        val jniValue = view.findViewById<TextView>(R.id.jniValue)
        val slimValue = view.findViewById<TextView>(R.id.slimValue)
        val deltaText = view.findViewById<TextView>(R.id.deltaText)

        tintBar(jniBar, R.color.jni_color)
        tintBar(slimBar, R.color.slim_color)

        val maxNs = maxOf(b.jniMedianNs, b.slimMedianNs).coerceAtLeast(1L)
        jniBar.progress = ((b.jniMedianNs.toDouble() / maxNs) * 1000).toInt()
        slimBar.progress = ((b.slimMedianNs.toDouble() / maxNs) * 1000).toInt()

        jniValue.text = formatLatency(b.jniMedianNs)
        slimValue.text = formatLatency(b.slimMedianNs)

        val ratio = b.slimMedianNs.toDouble() / b.jniMedianNs.toDouble().coerceAtLeast(1.0)
        deltaText.text = "Slim adds ${formatLatency(b.deltaNs)} per call · " +
            "%.1f×".format(Locale.US, ratio) + " JNI overhead"

        resultsContainer.addView(view)
    }

    private fun addSizeCard(size: WorkloadSize, jni: CellResult, slim: CellResult) {
        val view = inflater.inflate(R.layout.card_size_result, resultsContainer, false)

        view.findViewById<TextView>(R.id.sizeLabel).text = size.label
        view.findViewById<TextView>(R.id.sizeBytes).text = formatBytes(size.bytes)

        val ratio = slim.medianNs.toDouble() / jni.medianNs.toDouble().coerceAtLeast(1.0)
        val (chipText, chipTier) = chipFor(ratio)
        val chip = view.findViewById<TextView>(R.id.statusChip)
        chip.text = chipText
        applyChipTint(chip, chipTier)

        val jniBar = view.findViewById<ProgressBar>(R.id.jniBar)
        val slimBar = view.findViewById<ProgressBar>(R.id.slimBar)
        tintBar(jniBar, R.color.jni_color)
        tintBar(slimBar, R.color.slim_color)

        val maxNs = maxOf(jni.medianNs, slim.medianNs).coerceAtLeast(1L)
        jniBar.progress = ((jni.medianNs.toDouble() / maxNs) * 1000).toInt()
        slimBar.progress = ((slim.medianNs.toDouble() / maxNs) * 1000).toInt()

        view.findViewById<TextView>(R.id.jniTime).text = "%.2f ms".format(Locale.US, jni.medianMs)
        view.findViewById<TextView>(R.id.slimTime).text = "%.2f ms".format(Locale.US, slim.medianMs)

        view.findViewById<TextView>(R.id.jniDetails).text = formatDetails(jni)
        view.findViewById<TextView>(R.id.slimDetails).text = formatDetails(slim)

        resultsContainer.addView(view)
    }

    private fun tintBar(bar: ProgressBar, colorRes: Int) {
        val c = ContextCompat.getColor(this, colorRes)
        bar.progressTintList = ColorStateList.valueOf(c)
    }

    private enum class ChipTier { TIE, CLOSE, SLOW }

    private fun chipFor(ratio: Double): Pair<String, ChipTier> = when {
        ratio <= 1.05 -> "TIE" to ChipTier.TIE
        ratio <= 1.25 -> "%.0f%% slower".format(Locale.US, (ratio - 1.0) * 100.0) to ChipTier.CLOSE
        else -> "%.1f× slower".format(Locale.US, ratio) to ChipTier.SLOW
    }

    private fun applyChipTint(chip: TextView, tier: ChipTier) {
        val (textColor, bgColor) = when (tier) {
            ChipTier.TIE -> R.color.chip_tie to R.color.chip_tie_bg
            ChipTier.CLOSE -> R.color.chip_close to R.color.chip_close_bg
            ChipTier.SLOW -> R.color.chip_slow to R.color.chip_slow_bg
        }
        chip.setTextColor(ContextCompat.getColor(this, textColor))
        val bg = chip.background as? GradientDrawable
        bg?.setColor(ContextCompat.getColor(this, bgColor))
    }

    private fun formatLatency(ns: Long): String {
        val absNs = kotlin.math.abs(ns)
        return when {
            absNs < 1_000 -> "$ns ns"
            absNs < 1_000_000 -> "%.2f µs".format(Locale.US, ns / 1_000.0)
            else -> "%.2f ms".format(Locale.US, ns / 1_000_000.0)
        }
    }

    private fun formatBytes(bytes: Int): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%d KB".format(Locale.US, bytes / 1024)
        else -> "%.1f MB".format(Locale.US, bytes / (1024.0 * 1024.0))
    }

    private fun formatDetails(c: CellResult): String =
        "p95 %.2f · p99 %.2f · %.0f MP/s".format(
            Locale.US, c.p95Ms, c.p99Ms, c.mpPerSec)

    // ---------------------------------------------------------------- device info

    private fun describeDevice(): String {
        val cores = Runtime.getRuntime().availableProcessors()
        val pageSize = try {
            Os.sysconf(OsConstants._SC_PAGESIZE).toInt()
        } catch (_: Throwable) {
            4096
        }
        val flags = NativeKt.flags
        return buildString {
            append("device    ").append(android.os.Build.MODEL)
                .append(" / ").append(android.os.Build.SOC_MODEL).append('\n')
            append("cores     ").append(cores)
                .append("    page ").append(pageSize).append(" B").append('\n')
            append("nativekt  ep=0x").append(flags.epIndex.toString(16))
                .append("   trigger=").append(flags.trigger)
                .append("   trampoline=").append(flags.trampoline).append('\n')
            append("status    ").append(if (NativeKt.isReady) "ready" else "FAILED — ${NativeKt.lastError}")
        }
    }
}
