package com.pengxh.daily.app.widget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.TaskScheduler
import com.pengxh.kt.lite.utils.SaveKeyValues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max

/**
 * 七彩状态球：空闲贴边露 [PEEK_WIDTH_DP] 色条；按下展开后可自由拖动，松手再贴边。
 */
class IdleBallController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val ballView: IdleStatusBallView,
    private val windowView: android.view.View,
    private val params: WindowManager.LayoutParams
) {
    private enum class Dock { LEFT, RIGHT }

    private val density = context.resources.displayMetrics.density
    private val sizePx = (BALL_SIZE_DP * density).toInt()
    private val peekPx = (PEEK_WIDTH_DP * density).toInt().coerceAtLeast(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var windowX = 0
    private var windowY = 0
    private var dock = Dock.RIGHT
    private var destroyed = false
    private var dragging = false
    private var expanded = false
    private var totalMove = 0f
    private var initialWindowX = 0
    private var initialWindowY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var dockAnimator: ValueAnimator? = null
    private var rainbowAnimator: ValueAnimator? = null
    private var labelJob: Job? = null
    private var cachedLabel = "·"

    private val labelRefreshRunnable = object : Runnable {
        override fun run() {
            if (destroyed) return
            refreshLabel()
            mainHandler.postDelayed(this, LABEL_REFRESH_MS)
        }
    }

    init {
        params.gravity = Gravity.START or Gravity.TOP
        ballView.animate().cancel()
        ballView.scaleX = 1f
        ballView.scaleY = 1f
        // 必须 START 对齐：center + translation 会把 8dp 窗口裁到球身外 → 完全看不见
        (ballView.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let {
            it.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            ballView.layoutParams = it
        }
        dock = Dock.RIGHT
        val screenH = screenSize().second
        windowY = (screenH * 0.38f).toInt().coerceIn(0, (screenH - sizePx).coerceAtLeast(0))
        ballView.setOnTouchListener { _, event -> onTouch(event) }
        startRainbow()
        // 等窗口真正 attach 后再贴边，避免 init 阶段 updateViewLayout 被跳过
        mainHandler.post {
            if (!destroyed) {
                applyPeekLayout()
                refreshLabel()
            }
        }
        mainHandler.postDelayed(labelRefreshRunnable, LABEL_REFRESH_MS)
    }

    fun destroy() {
        destroyed = true
        dockAnimator?.cancel()
        dockAnimator = null
        rainbowAnimator?.cancel()
        rainbowAnimator = null
        labelJob?.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        ballView.animate().cancel()
        ballView.setOnTouchListener(null)
        scope.cancel()
    }

    fun ensureLayout() {
        if (destroyed) return
        ballView.animate().cancel()
        ballView.scaleX = 1f
        ballView.scaleY = 1f
        if (dragging || expanded) {
            applyExpandedLayout()
        } else {
            applyPeekLayout()
        }
        refreshLabel()
    }

    private fun startRainbow() {
        rainbowAnimator?.cancel()
        rainbowAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 14_000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                if (!destroyed) ballView.setSweepDegrees(it.animatedValue as Float)
            }
            start()
        }
    }

    private fun refreshLabel() {
        if (destroyed) return
        labelJob?.cancel()
        labelJob = scope.launch {
            val label = withContext(Dispatchers.IO) { resolveStatusLabel() }
            if (destroyed) return@launch
            cachedLabel = label
            ballView.text = if (expanded || dragging) cachedLabel else ""
        }
    }

    private suspend fun resolveStatusLabel(): String {
        return try {
            if (TaskScheduler.isRunning()) {
                val now = System.currentTimeMillis()
                val next = TaskScheduler.loadTodayTaskPlans(usePersisted = true)
                    .firstOrNull { it.actualTimeMillis > now }
                when {
                    next != null -> next.actualTime.take(5)
                    else -> "完"
                }
            } else {
                val ot = SaveKeyValues.loadInt(
                    Constant.STAY_OVERTIME_KEY, Constant.DEFAULT_OVER_TIME
                )
                "${ot}s"
            }
        } catch (_: Exception) {
            "·"
        }
    }

    private fun onTouch(event: MotionEvent): Boolean {
        if (destroyed) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dockAnimator?.cancel()
                ballView.animate().cancel()
                ballView.scaleX = 1f
                ballView.scaleY = 1f
                dragging = true
                totalMove = 0f
                expandForDrag()
                initialWindowX = windowX
                initialWindowY = windowY
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY
                totalMove = abs(dx) + abs(dy)
                val screen = screenSize()
                windowX = (initialWindowX + dx.toInt()).coerceIn(0, screen.first - sizePx)
                windowY = (initialWindowY + dy.toInt()).coerceIn(0, screen.second - sizePx)
                applyExpandedLayout()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return false
                dragging = false
                snapToPeek()
                return true
            }
        }
        return false
    }

    private fun expandForDrag() {
        val screen = screenSize()
        if (!expanded || params.width < sizePx) {
            windowX = when (dock) {
                Dock.LEFT -> 0
                Dock.RIGHT -> (screen.first - sizePx).coerceAtLeast(0)
            }
        }
        expanded = true
        ballView.text = cachedLabel
        applyExpandedLayout()
    }

    private fun snapToPeek() {
        val screen = screenSize()
        val centerX = windowX + sizePx / 2
        dock = if (centerX < screen.first / 2) Dock.LEFT else Dock.RIGHT
        val targetX = peekX(screen.first)
        val startX = windowX
        val startW = params.width.coerceAtLeast(1)
        dockAnimator?.cancel()
        dockAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                if (destroyed) return@addUpdateListener
                val t = it.animatedValue as Float
                windowX = (startX + (targetX - startX) * t).toInt()
                val w = (startW + (peekPx - startW) * t).toInt().coerceAtLeast(1)
                applyWindow(w, sizePx)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (destroyed) return
                    expanded = false
                    windowX = targetX
                    applyPeekLayout()
                }
            })
            start()
        }
    }

    private fun peekX(screenW: Int): Int = when (dock) {
        Dock.LEFT -> 0
        Dock.RIGHT -> (screenW - peekPx).coerceAtLeast(0)
    }

    private fun applyPeekLayout() {
        val screen = screenSize()
        expanded = false
        windowX = peekX(screen.first)
        applyWindow(peekPx, sizePx)
        ballView.text = ""
    }

    private fun applyExpandedLayout() {
        expanded = true
        applyWindow(sizePx, sizePx)
    }

    private fun applyWindow(width: Int, height: Int) {
        if (destroyed) return
        params.width = width
        params.height = height
        params.x = windowX
        params.y = windowY
        // START 对齐后：
        // · 左边贴边：translationX = peek-size，露出球右侧（朝向屏幕内）
        // · 右边贴边：translationX = 0，露出球左侧（朝向屏幕内）
        ballView.translationX = when {
            width >= sizePx -> 0f
            dock == Dock.LEFT -> (width - sizePx).toFloat()
            else -> 0f
        }
        if (!windowView.isAttachedToWindow) return
        try {
            windowManager.updateViewLayout(windowView, params)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "applyWindow failed: ${e.message}")
        }
    }

    /** 悬浮窗坐标用整屏像素，不要扣 systemBars，否则右边贴边会算偏/出屏 */
    private fun screenSize(): Pair<Int, Int> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = windowManager.currentWindowMetrics.bounds
                bounds.width() to bounds.height()
            } else {
                val dm = DisplayMetrics()
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(dm)
                dm.widthPixels to dm.heightPixels
            }
        } catch (_: Exception) {
            val dm = context.resources.displayMetrics
            dm.widthPixels to max(dm.heightPixels, 1)
        }
    }

    companion object {
        private const val TAG = "IdleBallController"
        const val BALL_SIZE_DP = 32f
        const val PEEK_WIDTH_DP = 8f
        private const val LABEL_REFRESH_MS = 30_000L
    }
}
