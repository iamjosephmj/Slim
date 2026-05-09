package com.example.slim

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import io.simdkt.slim.Floats
import io.simdkt.slim.Slim
import io.simdkt.slim.slim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import androidx.core.graphics.createBitmap

/**
 * Bitmap-manipulation benchmark using the high-level [Slim] API.
 *
 * The user-facing surface is two things:
 *   - [Slim.initialize] — once at app startup.
 *   - [slim] — a suspend function whose body is the NEON kernel.
 *
 * No `ByteBuffer`, no `placeholderDataPtr`, no `Arm64.X0` qualifiers, no
 * trailing `ret` — the engine handles all of that.
 */
class BenchmarkActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var origImage: ImageView
    private lateinit var procImage: ImageView
    private lateinit var resultsText: TextView
    private lateinit var runButton: Button

    private val imageSize = 1024
    private val pixelCount = imageSize * imageSize
    private val floatCount = pixelCount * 4 // RGBA

    private val a = 0.5f
    private val b = 0.0f

    private lateinit var bitmap: Bitmap

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_benchmark)

        statusText = findViewById(R.id.status)
        origImage = findViewById(R.id.origImage)
        procImage = findViewById(R.id.procImage)
        resultsText = findViewById(R.id.resultsText)
        runButton = findViewById(R.id.runButton)

        val content = findViewById<android.view.View>(R.id.content)
        val basePadL = content.paddingLeft
        val basePadT = content.paddingTop
        val basePadR = content.paddingRight
        val basePadB = content.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(
                basePadL + bars.left,
                basePadT + bars.top,
                basePadR + bars.right,
                basePadB + bars.bottom,
            )
            insets
        }

        runButton.isEnabled = false
        runButton.setOnClickListener { runBenchmark() }

        statusText.text = "init: …"

        lifecycleScope.launch {
            val (statusLine, originalBmp) = withContext(Dispatchers.Default) {
                val ok = Slim.initialize(this@BenchmarkActivity)
                val s = if (ok) "init: ok" else "init: FAILED — ${Slim.lastError ?: "(unknown)"}"
                val bmp = makeGradientBitmap(imageSize)
                s to bmp
            }
            bitmap = originalBmp
            statusText.text = "$statusLine\n" +
                    "buffer: ${imageSize}×${imageSize} RGBA = $floatCount floats (${floatCount * 4 / 1024} KB)\n" +
                    "kernel: y[i] = $a · x[i] + $b"
            origImage.setImageBitmap(originalBmp)
            runButton.isEnabled = Slim.isReady
        }
    }

    // ------------------------------------------------------------------
    // The kernel — operates on a FloatArray directly.
    // ------------------------------------------------------------------

    private suspend fun brightenWithSlim(data: Floats): Boolean {
        val aBits = java.lang.Float.floatToRawIntBits(a)
        val bBits = java.lang.Float.floatToRawIntBits(b)
        val n = data.size
        return slim(data) {
            // x0 holds the buffer's address (auto-prologue).
            loadImm32(W4, aBits)
            dup(V0, X4, S4)            // v0 = a × 4 (broadcast)
            loadImm32(W4, bBits)
            dup(V1, X4, S4)            // v1 = b × 4
            loadImm32(W3, n)           // w3 = element count
            mov(X1, X0)                // x1 walks the buffer

            val loop = bindLabel()
            ld1(V2, X1, S4)
            fmul(V2, V2, V0, S4)       // v2 *= a
            fadd(V2, V2, V1, S4)       // v2 += b
            st1(V2, X1, S4)
            add(X1, X1, 16)            // advance 4 floats
            sub(W3, W3, 4)
            cbnz(W3, loop)
        }
    }

    // ------------------------------------------------------------------
    // Benchmark
    // ------------------------------------------------------------------

    private fun runBenchmark() {
        runButton.isEnabled = false
        resultsText.text = "Running benchmark…"
        lifecycleScope.launch {
            val (report, outBmp) = withContext(Dispatchers.Default) {
                try {
                    doBenchmark()
                } catch (t: Throwable) {
                    Log.e(TAG, "benchmark failed", t)
                    "FAILED: ${t::class.java.simpleName}: ${t.message}" to null
                }
            }
            outBmp?.let { procImage.setImageBitmap(it) }
            resultsText.text = report
            runButton.isEnabled = true
        }
    }

    private suspend fun doBenchmark(): Pair<String, Bitmap?> {
        val warmups = 3
        val iters = 10

        val original = bitmapToFloats(bitmap)
        val n = original.size

        val scalarOut = FloatArray(n)
        val slimData = Floats(original)            // direct-buffer-backed, zero-copy

        repeat(warmups) {
            brightenScalar(original, scalarOut, a, b)
            slimData.loadFrom(original)
            brightenWithSlim(slimData)
        }

        var kotlinNs = Long.MAX_VALUE
        repeat(iters) {
            val start = System.nanoTime()
            brightenScalar(original, scalarOut, a, b)
            kotlinNs = min(kotlinNs, System.nanoTime() - start)
        }

        var slimNs = Long.MAX_VALUE
        repeat(iters) {
            slimData.loadFrom(original)            // reset (still cheaper than full copy-in/out)
            val start = System.nanoTime()
            brightenWithSlim(slimData)
            slimNs = min(slimNs, System.nanoTime() - start)
        }

        for (j in 0 until 1024) {
            if (kotlin.math.abs(scalarOut[j] - slimData[j]) > 1e-6f) {
                return "MISMATCH @ $j: kotlin=${scalarOut[j]} slim=${slimData[j]}" to null
            }
        }

        val kotlinMs = kotlinNs / 1_000_000.0
        val slimMs = slimNs / 1_000_000.0
        val mb = (n * 4) / (1024.0 * 1024.0)
        val speedup = kotlinMs / max(slimMs, 1e-9)
        val concurrentReport = runConcurrentSlimTest()
        val outBmp = floatsToBitmap(slimData.toFloatArray(), imageSize, imageSize)

        val report = buildString {
            appendLine("buffer:    ${imageSize}×${imageSize} RGBA  (${"%.1f".format(mb)} MB)")
            appendLine("kernel:    y[i] = ${a} · x[i] + ${b}   (in-place)")
            appendLine("warmup:    $warmups iters     samples: $iters")
            appendLine()
            appendLine("Kotlin scalar  ${"%7.2f".format(kotlinMs)} ms   (${"%.0f".format(mb / (kotlinMs / 1000.0))} MB/s)")
            appendLine("slim { }       ${"%7.2f".format(slimMs)} ms   (${"%.0f".format(mb / (slimMs / 1000.0))} MB/s)")
            appendLine()
            appendLine("speedup        ${"%5.2fx".format(speedup)}    (Floats — zero-copy on the kernel)")
            appendLine()
            appendLine(concurrentReport)
        }
        return report to outBmp
    }

    private suspend fun runConcurrentSlimTest(): String {
        val workers = 4
        val callsPerWorker = 50
        val n = 256
        val errors = java.util.concurrent.atomic.AtomicInteger(0)
        val nBits = n
        val aBits = java.lang.Float.floatToRawIntBits(a)
        val bBits = java.lang.Float.floatToRawIntBits(b)

        suspend fun runOne(buf: Floats): Boolean = slim(buf) {
            loadImm32(W4, aBits)
            dup(V0, X4, S4)
            loadImm32(W4, bBits)
            dup(V1, X4, S4)
            loadImm32(W3, nBits)
            mov(X1, X0)
            val loop = bindLabel()
            ld1(V2, X1, S4)
            fmul(V2, V2, V0, S4)
            fadd(V2, V2, V1, S4)
            st1(V2, X1, S4)
            add(X1, X1, 16)
            sub(W3, W3, 4)
            cbnz(W3, loop)
        }

        val start = System.nanoTime()
        coroutineScope {
            repeat(workers) { tid ->
                launch(Dispatchers.Default) {
                    val arr = Floats(n)
                    repeat(callsPerWorker) { call ->
                        val seed = (tid * 1000 + call).toFloat()
                        arr.fill { i -> seed + i }
                        if (!runOne(arr)) errors.incrementAndGet()
                        for (i in 0 until n) {
                            val expected = a * (seed + i) + b
                            if (kotlin.math.abs(arr[i] - expected) > 1e-4f) {
                                errors.incrementAndGet()
                                return@repeat
                            }
                        }
                    }
                }
            }
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
        val total = workers * callsPerWorker
        return if (errors.get() == 0) {
            "concurrency: $workers × $callsPerWorker slim {} calls   ok " +
                    "(${"%.1f".format(elapsedMs)} ms, ${"%.0f".format(total / (elapsedMs / 1000.0))} calls/s)"
        } else {
            "concurrency: FAILED (${errors.get()} errors)"
        }
    }

    // ------------------------------------------------------------------
    // Bitmap <-> FloatArray
    // ------------------------------------------------------------------

    private fun makeGradientBitmap(size: Int): Bitmap {
        val bmp = createBitmap(size, size)
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            val gFrac = y.toFloat() / (size - 1)
            for (x in 0 until size) {
                val rFrac = x.toFloat() / (size - 1)
                val r = (rFrac * 255f).toInt()
                val g = (gFrac * 255f).toInt()
                val bb = ((1f - rFrac * 0.5f - gFrac * 0.5f).coerceIn(0f, 1f) * 255f).toInt()
                pixels[y * size + x] = Color.argb(255, r, g, bb)
            }
        }
        bmp.setPixels(pixels, 0, size, 0, 0, size, size)
        return bmp
    }

    private fun bitmapToFloats(bmp: Bitmap): FloatArray {
        val w = bmp.width; val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = FloatArray(w * h * 4)
        var dst = 0
        for (p in pixels) {
            out[dst++] = ((p ushr 16) and 0xFF) / 255f
            out[dst++] = ((p ushr 8) and 0xFF) / 255f
            out[dst++] = (p and 0xFF) / 255f
            out[dst++] = ((p ushr 24) and 0xFF) / 255f
        }
        return out
    }

    private fun floatsToBitmap(data: FloatArray, w: Int, h: Int): Bitmap {
        val pixels = IntArray(w * h)
        var src = 0
        for (i in pixels.indices) {
            val r = (data[src++] * 255f).toInt().coerceIn(0, 255)
            val g = (data[src++] * 255f).toInt().coerceIn(0, 255)
            val bb = (data[src++] * 255f).toInt().coerceIn(0, 255)
            val a = (data[src++] * 255f).toInt().coerceIn(0, 255)
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or bb
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }

    private fun brightenScalar(input: FloatArray, output: FloatArray, a: Float, b: Float) {
        for (i in input.indices) output[i] = a * input[i] + b
    }

    companion object { private const val TAG = "Slim" }
}
