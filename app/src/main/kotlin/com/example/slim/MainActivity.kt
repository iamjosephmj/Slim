package com.example.slim

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.slim.pipeline.FrameResult
import com.example.slim.pipeline.SlimImageAnalyzer
import com.example.slim.pipeline.TransformPipeline
import com.example.slim.render.GrayscaleRenderer
import com.example.slim.telemetry.MismatchDiag
import com.example.slim.telemetry.TimingAccumulator
import com.example.slim.transforms.Brighten
import com.example.slim.transforms.Contrast
import com.example.slim.transforms.Darken
import com.example.slim.transforms.ImageTransform
import com.example.slim.transforms.Invert
import io.simdkt.slim.Slim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Live camera SIMD demo.
 *
 * Architecture:
 *   - [TransformPipeline] / [SlimImageAnalyzer] do all of the heavy
 *     lifting: kernel compilation, per-frame dispatch, scalar
 *     reference, mismatch diagnostic. See `pipeline/Pipeline.kt`.
 *   - [GrayscaleRenderer] handles the byte-plane → bitmap path.
 *   - [TimingAccumulator] smooths the per-kernel timings.
 *   - This activity is the camera/permission/UI plumbing on top.
 *
 * The kernel-toggle button cycles through a fixed list of pipeline
 * presets (`PIPELINE_PRESETS`). Each preset is one or more
 * [ImageTransform]s applied in order, demonstrating both single
 * kernels and chained pipelines (`darken → contrast`, etc.).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var processedView: ImageView
    private lateinit var stats: TextView
    private lateinit var pipelineToggle: Button

    private val analyzerExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var analyzer: SlimImageAnalyzer? = null

    private val renderer = GrayscaleRenderer()
    private val timing = TimingAccumulator()

    @Volatile private var presetIndex: Int = 0

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else stats.text = "Camera permission denied."
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        processedView = findViewById(R.id.processed)
        stats = findViewById(R.id.stats)
        pipelineToggle = findViewById(R.id.kernelToggle)

        applyEdgeToEdgeInsets()
        wireControls()

        pipelineToggle.text = "Kernel: ${pipelineNameAt(presetIndex)}"
        stats.text = "init: …"

        lifecycleScope.launch {
            val statusLine = withContext(Dispatchers.Default) {
                val ok = Slim.initialize(this@MainActivity)
                if (ok) "init: ok" else "init: FAILED — ${Slim.lastError ?: "(unknown)"}"
            }
            stats.text = "$statusLine\nawaiting camera permission…"
            ensureCameraPermission()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        analyzerExecutor.shutdown()
        cameraProvider?.unbindAll()
        analyzer?.close()
    }

    // ── UI wiring ────────────────────────────────────────────────────

    private fun wireControls() {
        pipelineToggle.setOnClickListener {
            presetIndex = (presetIndex + 1) % PIPELINE_PRESETS.size
            val newPipeline = TransformPipeline(PIPELINE_PRESETS[presetIndex])
            pipelineToggle.text = "Kernel: ${newPipeline.name}"
            timing.reset()
            analyzer?.setPipeline(newPipeline)
        }
    }

    private fun applyEdgeToEdgeInsets() {
        val titleView = findViewById<View>(R.id.title)
        val bottomBar = findViewById<View>(R.id.bottomBar)
        val titlePadV = titleView.paddingTop
        val titlePadH = titleView.paddingLeft
        val barPadV = bottomBar.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            titleView.setPadding(
                titlePadH, bars.top + titlePadV,
                titlePadH, titleView.paddingBottom,
            )
            bottomBar.setPadding(
                bottomBar.paddingLeft, bottomBar.paddingTop,
                bottomBar.paddingRight, bars.bottom + barPadV,
            )
            insets
        }
    }

    // ── Camera ───────────────────────────────────────────────────────

    private fun ensureCameraPermission() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        if (granted) startCamera() else requestPermission.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            bindUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindUseCases() {
        val provider = cameraProvider ?: return
        val resolution = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    android.util.Size(640, 480),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                )
            ).build()

        val initialPipeline = TransformPipeline(PIPELINE_PRESETS[presetIndex])
        val a = SlimImageAnalyzer(initialPipeline, ::handleFrame)
        analyzer = a

        val analyzerUseCase = ImageAnalysis.Builder()
            .setResolutionSelector(resolution)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also { it.setAnalyzer(analyzerExecutor, a) }

        provider.unbindAll()
        // No Preview use-case bound: the SIMD-tampered ImageView *is* the
        // preview. CameraX delivers every frame to ImageAnalysis only.
        provider.bindToLifecycle(
            this,
            CameraSelector.DEFAULT_BACK_CAMERA,
            analyzerUseCase,
        )
    }

    // ── Per-frame callback ───────────────────────────────────────────

    @Volatile private var lastDiagLogNs: Long = 0L

    private fun handleFrame(r: FrameResult) {
        timing.add(r.scalarNs, r.simdNs)
        if (!r.diag.ok) logDiagThrottled(r)
        val bmp = renderer.render(r.processed, r.width, r.height, r.rotationDegrees)
        runOnUiThread {
            processedView.setImageBitmap(bmp)
            stats.text = formatStats(r.width, r.height, r.diag)
        }
    }

    private fun logDiagThrottled(r: FrameResult) {
        val now = System.nanoTime()
        if (now - lastDiagLogNs < 1_000_000_000L) return
        lastDiagLogNs = now
        val name = analyzer?.activePipelineName ?: "?"
        Log.w(
            DIAG_TAG,
            "$name  bad=${r.diag.total}/${r.width * r.height} " +
                    "first=${r.diag.first} last=${r.diag.last}  " +
                    "q=${r.diag.histogram.joinToString(",")}"
        )
        if (r.diag.sampledPairs.isNotEmpty()) {
            val pairs = r.diag.sampledPairs.joinToString("  ") {
                "[${it.first} s=${it.second} n=${it.third}]"
            }
            Log.w(DIAG_TAG, "  $pairs")
        }
    }

    private fun formatStats(w: Int, h: Int, diag: MismatchDiag): String {
        val pixels = w * h
        val (scalarMs, simdMs, fps) = timing.snapshot()
        val mb = pixels / (1024.0 * 1024.0)
        val sScalar = mb / (scalarMs / 1000.0)
        val sSimd = mb / max(simdMs / 1000.0, 1e-9)
        val speedup = scalarMs / max(simdMs, 1e-9)
        val name = analyzer?.activePipelineName ?: pipelineNameAt(presetIndex)
        return buildString {
            appendLine("init: ok | $name | ${w}×${h} (${pixels / 1000} K px) | ${"%.1f".format(fps)} fps")
            appendLine()
            appendLine("Kotlin scalar  ${"%6.2f".format(scalarMs)} ms  (${"%.0f".format(sScalar)} MB/s)")
            appendLine("slim handle    ${"%6.2f".format(simdMs)} ms  (${"%.0f".format(sSimd)} MB/s)")
            appendLine()
            append("speedup        ${"%5.2fx".format(speedup)}")
            if (!diag.ok) {
                appendLine()
                appendLine()
                appendLine("⚠ mismatches: ${diag.total} / $pixels  first=${diag.first}  last=${diag.last}")
                if (diag.sampledPairs.isNotEmpty()) {
                    append(diag.sampledPairs.joinToString("  ") {
                        "[${it.first} s=${it.second} n=${it.third}]"
                    })
                }
            }
        }
    }

    private fun pipelineNameAt(i: Int): String =
        PIPELINE_PRESETS[i].joinToString(" → ") { it.name }

    companion object {
        private const val DIAG_TAG = "SlimDiag"

        /** Cycle of pipeline presets exposed via the kernel-toggle button. */
        private val PIPELINE_PRESETS: List<List<ImageTransform>> = listOf(
            listOf(Invert),
            listOf(Contrast),
            listOf(Brighten(48)),
            listOf(Darken(48)),
            listOf(Darken(32), Contrast),
            listOf(Invert, Brighten(40)),
            listOf(Contrast, Invert),
        )
    }
}
