package com.example.slim

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.simdkt.nativekt.KernelHandle
import io.simdkt.nativekt.KernelTemplate
import io.simdkt.nativekt.NativeKt
import io.simdkt.nativekt.compileTemplate
import io.simdkt.nativekt.engine.Arm64
import io.simdkt.slim.Bytes
import io.simdkt.slim.Slim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Live camera benchmark.
 *
 * Pipes 30 fps `YUV_420_888` frames from CameraX through a NEON kernel
 * via the Slim runtime, side-by-side with a Kotlin scalar reference.
 *
 * Two kernels:
 *   - **invert**:   y' = 255 - y                      (photo negative)
 *   - **contrast**: y' = clamp(2·y - 128, 0, 255)     (high-contrast mono)
 *
 * Both are byte-lane SIMD (`.16b`) — 16 pixels per loop iteration.
 *
 * **Performance note**: the camera analyzer runs on a dedicated worker
 * thread, ~30 calls/sec. We use the lower-level `NativeKt.compileKernel`
 * + `KernelHandle.run` API rather than the high-level `slim {}` because:
 *   1. The kernel is the same every frame — compile once, dispatch many.
 *   2. `slim {}` defaults to `Dispatchers.Default` and would thread-hop
 *      twice per call, costing ~200-500 µs we don't want.
 *
 * That's the SDK design intent: `slim {}` for one-shot ergonomic calls,
 * `KernelHandle` for hot paths where you control the thread.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var preview: PreviewView
    private lateinit var processed: ImageView
    private lateinit var stats: TextView
    private lateinit var kernelToggle: Button
    private lateinit var sourceToggle: Button
    private lateinit var openBench: Button

    private val analyzerExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null

    @Volatile
    private var kernel: Kernel = Kernel.Invert

    @Volatile
    private var showProcessed: Boolean = true

    private val timing = TimingAccumulator()

    /** Compiled-once kernels, keyed by frame size. */
    private var invertHandle: KernelHandle? = null
    private var contrastHandle: KernelHandle? = null
    private var compiledForSize: Int = 0

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else stats.text = "Camera permission denied."
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        preview = findViewById(R.id.preview)
        processed = findViewById(R.id.processed)
        stats = findViewById(R.id.stats)
        kernelToggle = findViewById(R.id.kernelToggle)
        sourceToggle = findViewById(R.id.sourceToggle)
        openBench = findViewById(R.id.openBench)

        kernelToggle.setOnClickListener {
            kernel = when (kernel) {
                Kernel.Invert -> Kernel.Contrast
                Kernel.Contrast -> Kernel.Invert
            }
            kernelToggle.text = "Kernel: ${kernel.label}"
            timing.reset()
        }
        sourceToggle.setOnClickListener {
            showProcessed = !showProcessed
            sourceToggle.text = "View: ${if (showProcessed) "processed" else "preview"}"
            processed.visibility = if (showProcessed) View.VISIBLE else View.GONE
        }
        openBench.setOnClickListener {
            startActivity(Intent(this, BenchmarkActivity::class.java))
        }

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
        invertHandle?.close()
        contrastHandle?.close()
    }

    // ----------------------------------------------------------------
    // Camera setup
    // ----------------------------------------------------------------

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

        val previewUseCase = Preview.Builder()
            .setResolutionSelector(resolution)
            .build()
            .also { it.surfaceProvider = preview.surfaceProvider }

        val analyzerUseCase = ImageAnalysis.Builder()
            .setResolutionSelector(resolution)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also { it.setAnalyzer(analyzerExecutor, ::analyze) }

        provider.unbindAll()
        provider.bindToLifecycle(
            this,
            CameraSelector.DEFAULT_BACK_CAMERA,
            previewUseCase,
            analyzerUseCase,
        )
    }

    // ----------------------------------------------------------------
    // Per-frame processing — runs on analyzerExecutor's thread
    // ----------------------------------------------------------------

    private var ySlim: Bytes? = null
    private var scratchScalar: ByteArray? = null
    private var outBmp: Bitmap? = null
    private var outPixels: IntArray? = null

    private fun analyze(image: ImageProxy) {
        val frame = image.planes[0]
        val width = image.width
        val height = image.height
        val rowStride = frame.rowStride
        val pixelStride = frame.pixelStride

        val n = width * height
        // Lazy allocate buffers + compile kernels at the first frame's size.
        if (n != compiledForSize) {
            ySlim?.let { /* swap out */ }
            invertHandle?.close()
            contrastHandle?.close()
            ySlim = Bytes(n)
            scratchScalar = ByteArray(n)
            outPixels = IntArray(n)
            invertHandle = NativeKt.compileKernel(buildInvertTemplate(n))
            contrastHandle = NativeKt.compileKernel(buildContrastTemplate(n))
            compiledForSize = n
        }
        val slim = ySlim!!
        val scalar = scratchScalar!!

        // Copy Y plane out of the camera frame. Most cameras produce
        // tightly-packed Y (rowStride == width, pixelStride == 1).
        val src = frame.buffer
        if (rowStride == width && pixelStride == 1) {
            val packed = ByteArray(n)
            src.position(0)
            src.get(packed, 0, n)
            slim.loadFrom(packed)
            System.arraycopy(packed, 0, scalar, 0, n)
        } else {
            val row = ByteArray(width)
            for (y in 0 until height) {
                src.position(y * rowStride)
                if (pixelStride == 1) src.get(row, 0, width)
                else for (x in 0 until width) {
                    row[x] = src.get(y * rowStride + x * pixelStride)
                }
                System.arraycopy(row, 0, scalar, y * width, width)
            }
            slim.loadFrom(scalar)
        }
        image.close()

        val k = kernel

        val scalarStart = System.nanoTime()
        when (k) {
            Kernel.Invert -> invertScalar(scalar, n)
            Kernel.Contrast -> contrastScalar(scalar, n)
        }
        val scalarNs = System.nanoTime() - scalarStart

        val handle = when (k) {
            Kernel.Invert -> invertHandle!!
            Kernel.Contrast -> contrastHandle!!
        }
        val slimStart = System.nanoTime()
        handle.run(slim)
        val slimNs = System.nanoTime() - slimStart

        val processedBytes = slim.toByteArray()
        val mismatch = countMismatches(scalar, processedBytes, sampleSize = 256)
        val bmp = renderGrayscale(processedBytes, width, height)

        timing.add(scalarNs, slimNs)

        runOnUiThread {
            if (showProcessed) processed.setImageBitmap(bmp)
            stats.text = formatStats(width, height, mismatch)
        }
    }

    // ----------------------------------------------------------------
    // Kernel templates — compiled once per frame size
    // ----------------------------------------------------------------

    private fun buildInvertTemplate(n: Int): KernelTemplate = compileTemplate {
        // x0 = data ptr (auto-prologue inside compileTemplate handles this)
        placeholderDataPtr()
        add(Arm64.loadImm32(Arm64.W3, n))                // count
        add(Arm64.mov(Arm64.X1, Arm64.X0))               // walking ptr
        add(Arm64.movz(Arm64.W4, 0xFF))
        add(Arm64.dup(Arm64.V1, Arm64.X4, Arm64.VArr.B16))   // v1 = 0xFF × 16

        val loop = bindLabel()
        add(Arm64.ld1(Arm64.V0, Arm64.X1, Arm64.VArr.B16))   // load 16 bytes
        add(Arm64.subVec(Arm64.V0, Arm64.V1, Arm64.V0, Arm64.VArr.B16)) // v0 = 255 - v0
        add(Arm64.st1(Arm64.V0, Arm64.X1, Arm64.VArr.B16))   // store
        add(Arm64.addImm(Arm64.X1, Arm64.X1, 16))
        add(Arm64.subImm(Arm64.W3, Arm64.W3, 16))
        cbnz(Arm64.W3, loop)

        add(Arm64.ret())
    }

    private fun buildContrastTemplate(n: Int): KernelTemplate = compileTemplate {
        placeholderDataPtr()
        add(Arm64.loadImm32(Arm64.W3, n))
        add(Arm64.mov(Arm64.X1, Arm64.X0))
        add(Arm64.movz(Arm64.W4, 128))
        add(Arm64.dup(Arm64.V1, Arm64.X4, Arm64.VArr.B16))   // v1 = 128 × 16

        val loop = bindLabel()
        add(Arm64.ld1(Arm64.V0, Arm64.X1, Arm64.VArr.B16))
        // y' = clamp(2*y - 128, 0, 255), saturating byte arithmetic
        add(Arm64.uqadd(Arm64.V0, Arm64.V0, Arm64.V0, Arm64.VArr.B16))   // 2y, saturating
        add(Arm64.uqsub(Arm64.V0, Arm64.V0, Arm64.V1, Arm64.VArr.B16))   // -128, saturating
        add(Arm64.st1(Arm64.V0, Arm64.X1, Arm64.VArr.B16))
        add(Arm64.addImm(Arm64.X1, Arm64.X1, 16))
        add(Arm64.subImm(Arm64.W3, Arm64.W3, 16))
        cbnz(Arm64.W3, loop)

        add(Arm64.ret())
    }

    // ----------------------------------------------------------------
    // Scalar references
    // ----------------------------------------------------------------

    private fun invertScalar(buf: ByteArray, n: Int) {
        for (i in 0 until n) buf[i] = (255 - (buf[i].toInt() and 0xFF)).toByte()
    }

    private fun contrastScalar(buf: ByteArray, n: Int) {
        for (i in 0 until n) {
            val y = buf[i].toInt() and 0xFF
            buf[i] = ((y * 2 - 128).coerceIn(0, 255)).toByte()
        }
    }

    private fun countMismatches(a: ByteArray, b: ByteArray, sampleSize: Int): Int {
        var bad = 0
        val step = max(1, a.size / sampleSize)
        var i = 0
        while (i < a.size) { if (a[i] != b[i]) bad++; i += step }
        return bad
    }

    // ----------------------------------------------------------------
    // Rendering
    // ----------------------------------------------------------------

    private fun renderGrayscale(y: ByteArray, w: Int, h: Int): Bitmap {
        val bmp = outBmp?.takeIf { it.width == w && it.height == h }
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { outBmp = it }
        val pixels = outPixels!!
        for (i in pixels.indices) {
            val v = y[i].toInt() and 0xFF
            pixels[i] = Color.argb(255, v, v, v)
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }

    // ----------------------------------------------------------------
    // Stats
    // ----------------------------------------------------------------

    private fun formatStats(w: Int, h: Int, mismatch: Int): String {
        val pixels = w * h
        val (scalarMs, slimMs, fps) = timing.snapshot()
        val mb = pixels / (1024.0 * 1024.0)
        val sScalar = mb / (scalarMs / 1000.0)
        val sSlim = mb / max(slimMs / 1000.0, 1e-9)
        val speedup = scalarMs / max(slimMs, 1e-9)
        return buildString {
            appendLine("init: ok | ${kernel.label} | ${w}×${h} (${pixels / 1000} K px) | ${"%.1f".format(fps)} fps")
            appendLine()
            appendLine("Kotlin scalar  ${"%6.2f".format(scalarMs)} ms  (${"%.0f".format(sScalar)} MB/s)")
            appendLine("slim handle    ${"%6.2f".format(slimMs)} ms  (${"%.0f".format(sSlim)} MB/s)")
            appendLine()
            append("speedup        ${"%5.2fx".format(speedup)}")
            if (mismatch > 0) append("   ⚠ $mismatch / 256 mismatches")
        }
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    enum class Kernel(val label: String) {
        Invert("invert"),
        Contrast("contrast"),
    }

    private class TimingAccumulator {
        @Volatile private var scalarMsEMA = 0.0
        @Volatile private var slimMsEMA = 0.0
        @Volatile private var fpsEMA = 0.0
        @Volatile private var lastTickNs = 0L

        @Synchronized
        fun add(scalarNs: Long, slimNs: Long) {
            val scalarMs = scalarNs / 1_000_000.0
            val slimMs = slimNs / 1_000_000.0
            val now = System.nanoTime()
            val dt = if (lastTickNs == 0L) 0.033 else (now - lastTickNs) / 1e9
            lastTickNs = now
            val instantFps = if (dt > 0.0) 1.0 / dt else 0.0
            val a = 0.15
            scalarMsEMA = if (scalarMsEMA == 0.0) scalarMs else (1 - a) * scalarMsEMA + a * scalarMs
            slimMsEMA = if (slimMsEMA == 0.0) slimMs else (1 - a) * slimMsEMA + a * slimMs
            fpsEMA = if (fpsEMA == 0.0) instantFps else (1 - a) * fpsEMA + a * instantFps
        }

        @Synchronized
        fun snapshot(): Triple<Double, Double, Double> = Triple(scalarMsEMA, slimMsEMA, fpsEMA)

        @Synchronized
        fun reset() {
            scalarMsEMA = 0.0; slimMsEMA = 0.0; fpsEMA = 0.0; lastTickNs = 0L
        }
    }

    companion object { private const val TAG = "Slim" }
}
