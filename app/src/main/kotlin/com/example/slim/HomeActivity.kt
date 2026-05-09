package com.example.slim

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Entry-point launcher screen.
 *
 * Two demos hang off this screen:
 *
 *   - **Live SIMD camera filter** ([MainActivity]) — `ImageAnalysis`
 *     feeds every frame through the Slim NEON pipeline; the result is
 *     drawn full-screen as the live preview surface.
 *   - **Static bitmap benchmark** ([BenchmarkActivity]) — synthetic
 *     1024×1024 SAXPY workload comparing Kotlin scalar against
 *     `slim { }`, with a concurrency stress test layered on.
 */
class HomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_home)

        applyEdgeToEdgeInsets()

        findViewById<View>(R.id.cardLive).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        findViewById<View>(R.id.cardBench).setOnClickListener {
            startActivity(Intent(this, BenchmarkActivity::class.java))
        }
    }

    private fun applyEdgeToEdgeInsets() {
        val content = findViewById<View>(R.id.content)
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
    }
}
