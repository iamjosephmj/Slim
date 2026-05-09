package com.example.slim.render

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.createBitmap

/**
 * Reusable Y-plane → ARGB bitmap renderer with rotation baked in.
 *
 * The camera typically emits frames in **sensor orientation** (most
 * Android phones report 90° on the back camera in portrait), so we
 * have to rotate the byte plane to a portrait-up bitmap. We do the
 * rotation directly during the byte→pixel write rather than as a
 * post-step `Bitmap.createBitmap(src, …, matrix)` so we don't
 * allocate a fresh bitmap every frame.
 *
 * The bitmap and pixel buffer are reused across frames as long as the
 * dimensions are stable — which is the common case once the camera
 * has settled on a resolution and orientation.
 *
 * Single-threaded by contract — the analyzer thread is the only writer.
 */
class GrayscaleRenderer {

    private var bitmap: Bitmap? = null
    private var pixels: IntArray? = null

    fun render(y: ByteArray, srcW: Int, srcH: Int, rotationDegrees: Int): Bitmap {
        val r = ((rotationDegrees % 360) + 360) % 360
        val (outW, outH) = when (r) {
            90, 270 -> srcH to srcW
            else -> srcW to srcH
        }

        val bmp = bitmap?.takeIf { it.width == outW && it.height == outH }
            ?: createBitmap(outW, outH).also { bitmap = it }
        val px = pixels?.takeIf { it.size == outW * outH }
            ?: IntArray(outW * outH).also { pixels = it }

        when (r) {
            0 -> writeDirect(y, px, srcW, srcH)
            90 -> writeRotated90(y, px, srcW, srcH)
            180 -> writeRotated180(y, px, srcW, srcH)
            270 -> writeRotated270(y, px, srcW, srcH)
        }
        bmp.setPixels(px, 0, outW, 0, 0, outW, outH)
        return bmp
    }

    private fun writeDirect(y: ByteArray, px: IntArray, w: Int, h: Int) {
        val n = w * h
        for (i in 0 until n) {
            val v = y[i].toInt() and 0xFF
            px[i] = Color.argb(255, v, v, v)
        }
    }

    /**
     * 90° clockwise: source `(sx, sy)` lands at output `(srcH-1-sy, sx)`.
     * Output dimensions are `(srcH, srcW)`.
     */
    private fun writeRotated90(y: ByteArray, px: IntArray, srcW: Int, srcH: Int) {
        val outW = srcH
        for (sy in 0 until srcH) {
            val ox = srcH - 1 - sy
            val rowBase = sy * srcW
            for (sx in 0 until srcW) {
                val v = y[rowBase + sx].toInt() and 0xFF
                px[sx * outW + ox] = Color.argb(255, v, v, v)
            }
        }
    }

    private fun writeRotated180(y: ByteArray, px: IntArray, w: Int, h: Int) {
        val n = w * h
        for (i in 0 until n) {
            val v = y[n - 1 - i].toInt() and 0xFF
            px[i] = Color.argb(255, v, v, v)
        }
    }

    /**
     * 270° clockwise (= 90° counter-clockwise): source `(sx, sy)` lands
     * at output `(sy, srcW-1-sx)`. Output dimensions are `(srcH, srcW)`.
     */
    private fun writeRotated270(y: ByteArray, px: IntArray, srcW: Int, srcH: Int) {
        val outW = srcH
        for (sy in 0 until srcH) {
            val rowBase = sy * srcW
            for (sx in 0 until srcW) {
                val v = y[rowBase + sx].toInt() and 0xFF
                val oy = srcW - 1 - sx
                px[oy * outW + sy] = Color.argb(255, v, v, v)
            }
        }
    }
}
