package com.pengxh.daily.app.utils

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.Animation
import android.view.animation.ScaleAnimation
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import com.pengxh.daily.app.databinding.ActivityMainBinding
import com.pengxh.kt.lite.extensions.setScreenBrightness
import com.pengxh.kt.lite.utils.SaveKeyValues
import java.util.Random

class MaskViewController(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val insetsController: WindowInsetsControllerCompat,
    private val onMaskVisibilityChanged: ((Boolean) -> Unit)? = null
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentAnimation: Animation? = null
    private val random = Random()
    private var clockAnimationRunnable: Runnable? = null

    fun showMaskView() {
        FloatingWindowController.hide()

        insetsController.apply {
            hide(WindowInsetsCompat.Type.statusBars())
            hide(WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        binding.maskView.visibility = View.VISIBLE
        currentAnimation?.cancel()
        currentAnimation = ScaleAnimation(1.0f, 1.0f, 0.0f, 1.0f).apply {
            duration = 500
        }
        binding.maskView.startAnimation(currentAnimation)

        activity.window.setScreenBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF)

        binding.rootView.visibility = View.GONE

        // 伪息屏隐藏时钟：开启后只显示黑屏，不显示/不启动时钟动画（省电）
        if (SaveKeyValues.loadBoolean(Constant.PSEUDO_MASK_NO_CLOCK_KEY, false)) {
            binding.clockView.visibility = View.GONE
            LogFileManager.writeLog("伪息屏：已隐藏时钟（仅黑屏）")
        } else {
            binding.clockView.visibility = View.VISIBLE
            startClockAnimation()
        }
        MaskOverlayHelper.show(activity)
        onMaskVisibilityChanged?.invoke(true)
    }

    fun hideMaskView() {
        FloatingWindowController.show()

        stopClockAnimation()

        insetsController.apply {
            show(WindowInsetsCompat.Type.statusBars())
            show(WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        }

        currentAnimation?.cancel()
        currentAnimation = ScaleAnimation(1.0f, 1.0f, 1.0f, 0.0f).apply {
            duration = 500
        }
        binding.maskView.startAnimation(currentAnimation)

        activity.window.setScreenBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)

        binding.maskView.visibility = View.GONE
        binding.rootView.visibility = View.VISIBLE
        MaskOverlayHelper.hide(activity)
        onMaskVisibilityChanged?.invoke(false)
    }

    fun isMaskVisible(): Boolean = binding.maskView.isVisible

    private fun startClockAnimation() {
        stopClockAnimation()
        val interval = if (AppRuntimeConfig.isPowerSaveMode()) 120_000L else 30_000L
        clockAnimationRunnable = object : Runnable {
            override fun run() {
                if (!binding.maskView.isVisible) return
                if (binding.maskView.width == 0 || binding.maskView.height == 0) return

                binding.clockView.measure(
                    View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED
                )
                val clockWidth = binding.clockView.measuredWidth
                val clockHeight = binding.clockView.measuredHeight

                val maxX = binding.maskView.width - clockWidth
                val maxY = binding.maskView.height - clockHeight

                if (maxX > 0 && maxY > 0) {
                    val newX = random.nextInt(maxX.coerceAtLeast(1))
                    val newY = random.nextInt(maxY.coerceAtLeast(1))

                    binding.clockView.animate()
                        .x(newX.toFloat())
                        .y(newY.toFloat())
                        .setDuration(1000)
                        .setInterpolator(AccelerateDecelerateInterpolator())
                        .start()
                }

                val next = if (AppRuntimeConfig.isPowerSaveMode()) 120_000L else 30_000L
                mainHandler.postDelayed(this, next)
            }
        }
        mainHandler.postDelayed(clockAnimationRunnable!!, interval)
    }

    private fun stopClockAnimation() {
        clockAnimationRunnable?.let {
            mainHandler.removeCallbacks(it)
            clockAnimationRunnable = null
        }
    }

    fun destroy() {
        stopClockAnimation()
        currentAnimation?.cancel()
        currentAnimation = null
    }
}