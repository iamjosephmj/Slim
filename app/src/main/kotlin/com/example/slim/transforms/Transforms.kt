package com.example.slim.transforms

import io.simdkt.nativekt.KernelTemplate
import io.simdkt.nativekt.compileTemplate
import io.simdkt.nativekt.engine.Arm64

/**
 * A single image transformation expressed as both:
 *   - a NEON kernel template (compiled and dispatched by Slim), and
 *   - a scalar Kotlin reference (used as a correctness oracle and as
 *     the timing baseline displayed in the demo HUD).
 *
 * Transforms are *pure data*: stateless, no compiled artifacts. The
 * pipeline is responsible for compiling templates into [io.simdkt.nativekt.KernelHandle]s
 * and managing their lifecycle.
 *
 * The contract for both implementations:
 *   - input and output are the same N-byte plane (in-place rewrite)
 *   - N is always a multiple of 16 (single 16-byte vector lane width)
 *   - no allocations on the hot path beyond what the kernel itself does
 */
interface ImageTransform {
    /** Short name used in the demo HUD and in pipeline labels. */
    val name: String

    /** Build a NEON kernel template sized for an [n]-byte plane. */
    fun buildTemplate(n: Int): KernelTemplate

    /** Apply the same transform on the CPU as a correctness reference. */
    fun applyScalar(buf: ByteArray, n: Int)
}

// ─────────────────────────────────────────────────────────────────────
// Concrete transforms
// ─────────────────────────────────────────────────────────────────────

/** y' = 255 − y. */
object Invert : ImageTransform {
    override val name: String = "invert"

    override fun buildTemplate(n: Int): KernelTemplate = compileTemplate {
        placeholderDataPtr()
        add(Arm64.loadImm32(Arm64.W3, n))
        add(Arm64.mov(Arm64.X1, Arm64.X0))
        add(Arm64.movz(Arm64.W4, 0xFF))
        add(Arm64.dup(Arm64.V1, Arm64.X4, Arm64.VArr.B16))     // V1 = 0xFF × 16

        val loop = bindLabel()
        add(Arm64.ld1(Arm64.V0, Arm64.X1, Arm64.VArr.B16))
        add(Arm64.subVec(Arm64.V0, Arm64.V1, Arm64.V0, Arm64.VArr.B16))
        add(Arm64.st1(Arm64.V0, Arm64.X1, Arm64.VArr.B16))
        add(Arm64.addImm(Arm64.X1, Arm64.X1, 16))
        add(Arm64.subImm(Arm64.W3, Arm64.W3, 16))
        cbnz(Arm64.W3, loop)

        add(Arm64.ret())
    }

    override fun applyScalar(buf: ByteArray, n: Int) {
        for (i in 0 until n) buf[i] = (255 - (buf[i].toInt() and 0xFF)).toByte()
    }
}

/**
 * y' = clamp(2·y − 128, 0, 255).
 *
 * Implementation note: subtract first, then double. The reverse order
 * (`uqadd` first) saturates `2y` to 255 *before* the subtraction, which
 * pins all bright pixels to 127 — a real bug we hit and fixed.
 */
object Contrast : ImageTransform {
    override val name: String = "contrast"

    override fun buildTemplate(n: Int): KernelTemplate = compileTemplate {
        placeholderDataPtr()
        add(Arm64.loadImm32(Arm64.W3, n))
        add(Arm64.mov(Arm64.X1, Arm64.X0))
        add(Arm64.movz(Arm64.W4, 64))
        add(Arm64.dup(Arm64.V1, Arm64.X4, Arm64.VArr.B16))     // V1 = 64 × 16

        val loop = bindLabel()
        add(Arm64.ld1(Arm64.V0, Arm64.X1, Arm64.VArr.B16))
        add(Arm64.uqsub(Arm64.V0, Arm64.V0, Arm64.V1, Arm64.VArr.B16))   // max(y − 64, 0)
        add(Arm64.uqadd(Arm64.V0, Arm64.V0, Arm64.V0, Arm64.VArr.B16))   // min(2·prev, 255)
        add(Arm64.st1(Arm64.V0, Arm64.X1, Arm64.VArr.B16))
        add(Arm64.addImm(Arm64.X1, Arm64.X1, 16))
        add(Arm64.subImm(Arm64.W3, Arm64.W3, 16))
        cbnz(Arm64.W3, loop)

        add(Arm64.ret())
    }

    override fun applyScalar(buf: ByteArray, n: Int) {
        for (i in 0 until n) {
            val y = buf[i].toInt() and 0xFF
            buf[i] = ((y * 2 - 128).coerceIn(0, 255)).toByte()
        }
    }
}

/** y' = min(y + delta, 255), saturating add. */
class Brighten(private val delta: Int) : ImageTransform {
    init { require(delta in 0..255) { "delta=$delta must be in [0, 255]" } }
    override val name: String = "brighten($delta)"

    override fun buildTemplate(n: Int): KernelTemplate = compileTemplate {
        placeholderDataPtr()
        add(Arm64.loadImm32(Arm64.W3, n))
        add(Arm64.mov(Arm64.X1, Arm64.X0))
        add(Arm64.movz(Arm64.W4, delta))
        add(Arm64.dup(Arm64.V1, Arm64.X4, Arm64.VArr.B16))     // V1 = delta × 16

        val loop = bindLabel()
        add(Arm64.ld1(Arm64.V0, Arm64.X1, Arm64.VArr.B16))
        add(Arm64.uqadd(Arm64.V0, Arm64.V0, Arm64.V1, Arm64.VArr.B16))
        add(Arm64.st1(Arm64.V0, Arm64.X1, Arm64.VArr.B16))
        add(Arm64.addImm(Arm64.X1, Arm64.X1, 16))
        add(Arm64.subImm(Arm64.W3, Arm64.W3, 16))
        cbnz(Arm64.W3, loop)

        add(Arm64.ret())
    }

    override fun applyScalar(buf: ByteArray, n: Int) {
        for (i in 0 until n) {
            val y = buf[i].toInt() and 0xFF
            buf[i] = (y + delta).coerceAtMost(255).toByte()
        }
    }
}

/** y' = max(y − delta, 0), saturating sub. */
class Darken(private val delta: Int) : ImageTransform {
    init { require(delta in 0..255) { "delta=$delta must be in [0, 255]" } }
    override val name: String = "darken($delta)"

    override fun buildTemplate(n: Int): KernelTemplate = compileTemplate {
        placeholderDataPtr()
        add(Arm64.loadImm32(Arm64.W3, n))
        add(Arm64.mov(Arm64.X1, Arm64.X0))
        add(Arm64.movz(Arm64.W4, delta))
        add(Arm64.dup(Arm64.V1, Arm64.X4, Arm64.VArr.B16))

        val loop = bindLabel()
        add(Arm64.ld1(Arm64.V0, Arm64.X1, Arm64.VArr.B16))
        add(Arm64.uqsub(Arm64.V0, Arm64.V0, Arm64.V1, Arm64.VArr.B16))
        add(Arm64.st1(Arm64.V0, Arm64.X1, Arm64.VArr.B16))
        add(Arm64.addImm(Arm64.X1, Arm64.X1, 16))
        add(Arm64.subImm(Arm64.W3, Arm64.W3, 16))
        cbnz(Arm64.W3, loop)

        add(Arm64.ret())
    }

    override fun applyScalar(buf: ByteArray, n: Int) {
        for (i in 0 until n) {
            val y = buf[i].toInt() and 0xFF
            buf[i] = (y - delta).coerceAtLeast(0).toByte()
        }
    }
}
