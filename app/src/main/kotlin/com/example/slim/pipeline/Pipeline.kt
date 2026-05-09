package com.example.slim.pipeline

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.example.slim.telemetry.MismatchDiag
import com.example.slim.telemetry.compareDiag
import com.example.slim.transforms.ImageTransform
import io.simdkt.nativekt.KernelHandle
import io.simdkt.nativekt.NativeKt
import io.simdkt.slim.Bytes
import java.util.concurrent.atomic.AtomicReference

// ─────────────────────────────────────────────────────────────────────
// Pipeline
// ─────────────────────────────────────────────────────────────────────

/**
 * An ordered list of [ImageTransform]s, applied in sequence to the same
 * byte plane. Output of transform N is the input of transform N+1.
 *
 * Owns a [KernelHandle] per transform, compiled lazily for the buffer
 * size requested by [ensureCompiledFor]. When the buffer size changes
 * (e.g., the camera resolution flips), the old handles are closed and
 * fresh ones are compiled.
 *
 * Lifecycle: [close] releases all underlying memfd regions. After close,
 * further dispatch throws.
 *
 * Threading: single-writer. The analyzer thread is the only legitimate
 * caller of [runSimd], [runScalar] and [ensureCompiledFor]. UI threads
 * should construct a *new* pipeline and hand it to
 * [SlimImageAnalyzer.setPipeline] rather than mutate an in-flight one.
 */
class TransformPipeline(
    val transforms: List<ImageTransform>,
) : AutoCloseable {

    val name: String = if (transforms.isEmpty()) "passthrough"
    else transforms.joinToString(" → ") { it.name }

    private val handles: MutableMap<ImageTransform, KernelHandle> = LinkedHashMap()
    private var compiledForSize: Int = -1
    private var closed: Boolean = false

    /** Compile or recompile every transform's kernel for an [n]-byte buffer. */
    fun ensureCompiledFor(n: Int) {
        check(!closed) { "pipeline is closed" }
        if (n == compiledForSize) return
        closeHandles()
        for (t in transforms) {
            val template = t.buildTemplate(n)
            dumpKernelOnce(t.name, n, template.size, template.toHexCompact())
            handles[t] = NativeKt.compileKernel(template)
        }
        compiledForSize = n
    }

    /** Apply every transform's NEON kernel in order, in place on [data]. */
    fun runSimd(data: Bytes) {
        for (t in transforms) handles[t]!!.run(data)
    }

    /** Apply every transform's scalar reference in order, in place on [buf]. */
    fun runScalar(buf: ByteArray, n: Int) {
        for (t in transforms) t.applyScalar(buf, n)
    }

    private fun closeHandles() {
        handles.values.forEach { it.close() }
        handles.clear()
    }

    override fun close() {
        if (closed) return
        closed = true
        closeHandles()
    }

    private fun dumpKernelOnce(name: String, n: Int, size: Int, hexCompact: String) {
        Log.i(KERNEL_TAG, "compiled '$name'  n=$n  bytes=$size")
        Log.i(KERNEL_TAG, "  $hexCompact")
    }

    companion object {
        private const val KERNEL_TAG = "SlimKernel"
    }
}

// ─────────────────────────────────────────────────────────────────────
// Frame processor
// ─────────────────────────────────────────────────────────────────────

/**
 * The output of one processed frame.
 *
 * `processed` and `scalar` are both fully-populated N-byte planes — the
 * caller can pass either to a renderer. `scalarNs` and `simdNs` are
 * monotonic-clock deltas measured around the kernel invocation only
 * (no copy-in / copy-out). `diag` summarizes correctness.
 */
data class FrameResult(
    val processed: ByteArray,
    val scalar: ByteArray,
    val width: Int,
    val height: Int,
    /** Sensor → display rotation in degrees, from `ImageInfo.rotationDegrees`. */
    val rotationDegrees: Int,
    val scalarNs: Long,
    val simdNs: Long,
    val diag: MismatchDiag,
)

/**
 * Owns the per-buffer-size scratch state ([Bytes] for SIMD, [ByteArray]
 * for the scalar reference) and routes a Y plane through:
 *
 *   1. SIMD pipeline — in place on [Bytes.buf].
 *   2. Scalar pipeline — in place on a separate [ByteArray].
 *   3. [compareDiag] — measures any divergence.
 *
 * Per-frame allocation is bounded:
 *   - one [ByteArray] return from `slim.toByteArray()` (snapshot for
 *     the renderer)
 *   - one fresh `MismatchDiag.histogram` IntArray
 * Everything else is pre-allocated at first call and reused.
 *
 * Single-threaded by contract.
 */
class FrameProcessor(private val pipeline: TransformPipeline) {
    private var slim: Bytes? = null
    private var scalar: ByteArray? = null
    private var bufferSize: Int = -1

    fun process(yPlane: ByteArray, width: Int, height: Int, rotationDegrees: Int): FrameResult {
        val n = width * height
        if (n != bufferSize) {
            slim = Bytes(n)
            scalar = ByteArray(n)
            bufferSize = n
            pipeline.ensureCompiledFor(n)
        }
        val s = slim!!
        val sc = scalar!!

        s.loadFrom(yPlane, 0, n)
        System.arraycopy(yPlane, 0, sc, 0, n)

        val scalarStart = System.nanoTime()
        pipeline.runScalar(sc, n)
        val scalarNs = System.nanoTime() - scalarStart

        val simdStart = System.nanoTime()
        pipeline.runSimd(s)
        val simdNs = System.nanoTime() - simdStart

        val processed = s.toByteArray()
        val diag = compareDiag(sc, processed, n)
        return FrameResult(processed, sc, width, height, rotationDegrees, scalarNs, simdNs, diag)
    }
}

// ─────────────────────────────────────────────────────────────────────
// CameraX analyzer
// ─────────────────────────────────────────────────────────────────────

/**
 * CameraX [ImageAnalysis.Analyzer] that:
 *   - extracts the Y plane from each `YUV_420_888` frame
 *   - hands it to a [FrameProcessor]
 *   - delivers the result to [onFrame] (called on the analyzer thread)
 *
 * The pipeline can be hot-swapped from any thread via [setPipeline].
 * The swap is observed at the next frame boundary; the previous
 * pipeline is closed at that point. Frames already in flight always
 * complete against the pipeline they started with.
 */
class SlimImageAnalyzer(
    initialPipeline: TransformPipeline,
    private val onFrame: (FrameResult) -> Unit,
) : ImageAnalysis.Analyzer, AutoCloseable {

    private var current: TransformPipeline = initialPipeline
    private var processor: FrameProcessor = FrameProcessor(current)
    private val pending = AtomicReference<TransformPipeline?>(null)

    /** Public observation point for HUDs — the *current* pipeline's name. */
    val activePipelineName: String get() = current.name

    /** Queue a new pipeline. Replaces any previously queued one. */
    fun setPipeline(p: TransformPipeline) {
        pending.getAndSet(p)?.close()  // discard any earlier still-pending swap
    }

    override fun analyze(image: ImageProxy) {
        pending.getAndSet(null)?.let { newP ->
            current.close()
            current = newP
            processor = FrameProcessor(current)
        }

        val width = image.width
        val height = image.height
        val rotationDegrees = image.imageInfo.rotationDegrees
        val plane = image.planes[0]
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val n = width * height

        val packed = ByteArray(n)
        val src = plane.buffer
        if (rowStride == width && pixelStride == 1) {
            src.position(0)
            src.get(packed, 0, n)
        } else {
            val row = ByteArray(width)
            for (y in 0 until height) {
                src.position(y * rowStride)
                if (pixelStride == 1) {
                    src.get(row, 0, width)
                } else {
                    for (x in 0 until width) row[x] = src.get(y * rowStride + x * pixelStride)
                }
                System.arraycopy(row, 0, packed, y * width, width)
            }
        }
        image.close()

        onFrame(processor.process(packed, width, height, rotationDegrees))
    }

    override fun close() {
        pending.getAndSet(null)?.close()
        current.close()
    }
}
