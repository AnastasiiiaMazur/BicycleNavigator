package com.bccle.navigator

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max

class SplashActivity : AppCompatActivity() {

    private lateinit var root: FrameLayout

    private lateinit var bannerImage: ImageView
    private lateinit var percentage: TextView
    private lateinit var progressImage: ImageView

    private val progressHandler = Handler(Looper.getMainLooper())
    private var progressValue = 0

    private var hasNavigated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_splash)

        root = findViewById(R.id.splashLayout)
        bannerImage = findViewById(R.id.bannerImageView)
        percentage = findViewById(R.id.percentage)
        progressImage = findViewById(R.id.progressImage)

        applyFullscreen(root)

        startFakeLoadingAnimation(
            durationMillis = 15_000L,
            block = {
                delay(8_000)
                goToMainIfNotAlready()

            },
            onFinished = { /* no-op */ }
        )
    }

    private fun startFakeLoadingAnimation(durationMillis: Long, block: suspend () -> Unit, onFinished: () -> Unit) {
        progressValue = 0
        val imageChangeEveryPercent = 5

        progressHandler.post(object : Runnable {
            var lastSwapAt = 0
            var showFilled = false

            override fun run() {
                if (progressValue < 100) {
                    progressValue++

                    if (progressValue - lastSwapAt >= imageChangeEveryPercent) {
                        lastSwapAt = progressValue
                        showFilled = !showFilled

                        progressImage.setImageResource(
                            if (showFilled) R.drawable.star_filled else R.drawable.star_empty
                        )

                        progressImage.animate()
                            .rotationBy(45f)
                            .setDuration(200L)
                            .start()
                    }

                    percentage.text = "$progressValue%"
                    progressHandler.postDelayed(this, durationMillis / 100)
                } else {
                    onFinished()
                }
            }
        })

        lifecycleScope.launch { block() }
    }

    private fun goToMainIfNotAlready() {
        if (!hasNavigated) {
            hasNavigated = true
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }
}
