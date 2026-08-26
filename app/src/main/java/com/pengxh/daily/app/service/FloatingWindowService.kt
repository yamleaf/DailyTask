package com.pengxh.daily.app.service

import com.pengxh.daily.app.R
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.Log
import android.view.LayoutInflater
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import androidx.appcompat.view.ContextThemeWrapper
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import com.pengxh.daily.app.databinding.WindowFloatingBinding
import com.pengxh.daily.app.utils.AppRuntimeConfig
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.FloatingWindowController
import com.pengxh.daily.app.utils.MessageDispatcher
import com.pengxh.daily.app.utils.StatusReporter
import com.pengxh.daily.app.widget.DesktopPetController
import com.pengxh.daily.app.widget.IdleBallController
import com.pengxh.kt.lite.utils.SaveKeyValues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FloatingWindowService : Service(), CoroutineScope by CoroutineScope(Dispatchers.Main) {

    companion object {
        @Volatile
        var isRunning: Boolean = false
            private set

        /** 与预截图触发对齐（NotificationMonitorService / TaskScheduler：tick <= 5） */
        private const val SCREENSHOT_AT_SECONDS = 5
        private const val COUNTDOWN_FADE_MIN_ALPHA = 0.04f
        /** 截图时刻（剩 5s）的透明度锚点：12% 透明度实测不影响截图识别，全周期曲线平滑收敛到此处 */
        private const val COUNTDOWN_ALPHA_AT_SCREENSHOT = 0.12f
        /** 与倒计时 tick 间隔对齐，连续收淡 */
        private const val COUNTDOWN_FADE_STEP_MS = 920L
    }

    private val kTag = "FloatingWindowService"
    private val windowManager by lazy { getSystemService(WindowManager::class.java) }
    private val activityManager by lazy { getSystemService(ActivityManager::class.java) }
    private lateinit var binding: WindowFloatingBinding
    private var floatViewParams: WindowManager.LayoutParams? = null
    private var memoryMonitorJob: Job? = null
    private var lastMemoryAlertAt = 0L
    // 悬浮窗可见性由三个独立维度决定：
    // 1) floatSessionActive —— 是否处于「被控端主动跳到目标 App」的操作会话中（由 openApplication 统一 start、各操作结束 stop）
    // 2) visibilityAllowed —— 蒙层是否未遮挡（由 show/hide 控制，蒙层显示时临时隐藏避免截到黑屏）
    // 3) hiddenForScreenshot —— 截屏流水线进行中强制 GONE，避免倒计时/贴边宠物进图
    // 形态切换：会话中显示完整倒计时卡片；空闲显示悬浮小球或桌宠（由桌宠开关决定），
    // 保证悬浮窗窗口常驻可见可拖动，为安卓 15+ 后台跳转提供可见窗口豁免；蒙层遮挡时整体隐藏。
    private var floatSessionActive = false
    private var visibilityAllowed = true
    private var hiddenForScreenshot = false
    /** 末段渐隐期间窗口透传触摸，避免挡关键信息点击 / 截图操作区 */
    private var countdownTouchPassthrough = false
    /** 本次倒计时总时长（秒）：渐隐曲线横跨整个周期 */
    private var countdownTotal = 0
    /** 退场波浪动画句柄：复位/重入时取消，避免残留回调改写透明度 */
    private var countdownWaveAnimator: ValueAnimator? = null

    /** 桌宠控制器（开关打开时）；小球控制器（默认 / 开关关闭时）。二者互斥。 */
    private var petController: DesktopPetController? = null
    private var ballController: IdleBallController? = null

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        isRunning = true
        // 浮动窗口由 Service 上下文 inflate；Service 不会自动套用 App 的 Material 主题，
        // 必须用 ContextThemeWrapper 显式包一层 Theme.DailyTask，否则 MaterialCardView/MaterialTextView 会 inflate 崩溃。
        binding = WindowFloatingBinding.inflate(LayoutInflater.from(ContextThemeWrapper(this, R.style.Theme_DailyTask)))
        // 用 WRAP_CONTENT 起步；尺寸由 DesktopPetController 按 WINDOW_*_DP / 贴边头宽同步。
        floatViewParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_TOUCH_MODAL：热区外点击可落到下层；配合 controller 对透明边的穿透处理
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).also {
            windowManager.addView(binding.root, it)
        }

        // 收集悬浮窗控制事件
        // 倒计时数字刷新 + 末段渐隐；是否可见由 recomputeVisibility() 统一决定（打卡中且蒙层未遮挡）
        launch {
            FloatingWindowController.timeTick.collect { tick ->
                applyCountdownTick(tick)
            }
        }
        launch {
            FloatingWindowController.overtime.collect { seconds ->
                applyCountdownTick(seconds)
            }
        }
        launch {
            FloatingWindowController.visibility.collect { allowed ->
                visibilityAllowed = allowed
                // 蒙层遮挡 → DIMMED：取消随机/离场计时，避免 GONE 期间仍离场贴边
                petController?.onDimmedChanged(!allowed)
                ballController?.onDimmedChanged(!allowed)
                recomputeVisibility()
            }
        }
        launch {
            // 系统亮/灭屏：暂停/恢复空闲动画播放（伪息屏 / 灭屏省电）
            FloatingWindowController.screenOn.collect { on ->
                petController?.onScreenStateChanged(on)
                ballController?.onScreenStateChanged(on)
            }
        }
        launch {
            FloatingWindowController.floatSessionActive.collect { active ->
                floatSessionActive = active
                if (active) {
                    val time = SaveKeyValues.loadInt(
                        Constant.STAY_OVERTIME_KEY, Constant.DEFAULT_OVER_TIME
                    )
                    // 渐隐横跨整个倒计时周期：记录总时长供曲线计算
                    countdownTotal = time
                    resetCountdownPresentation()
                    applyCountdownTick(time)
                    playCountdownEntrance() // 先复位再入场，避免入场被 tick 复位取消
                } else {
                    countdownTotal = 0
                    binding.timeView.text = "0s"
                    resetCountdownPresentation()
                }
                // 打卡会话联动桌宠：开始→COUNTDOWN；结束→恢复 blink（小球无此状态机）
                petController?.onCountdownChanged(active)
                recomputeVisibility()
            }
        }
        launch {
            FloatingWindowController.hiddenForScreenshot.collect { hide ->
                hiddenForScreenshot = hide
                recomputeVisibility()
            }
        }
        launch {
            AppRuntimeConfig.powerSaveMode.collect {
                restartMemoryMonitoring()
            }
        }
        launch {
            AppRuntimeConfig.desktopPetEnabled.collect {
                applyIdleController()
                recomputeVisibility()
            }
        }

        // 初始：倒计时隐藏；空闲 chrome 由 applyIdleController + recomputeVisibility 决定
        binding.countdownCardView.visibility = View.GONE
        binding.idlePetView.visibility = View.GONE
        binding.idleBallView.visibility = View.GONE
        binding.root.visibility = View.VISIBLE
        binding.root.alpha = 1.0f
        binding.timeView.text = "0s"
        applyIdleController()
        recomputeVisibility()

        restartMemoryMonitoring()
    }

    /** 按桌宠开关创建/销毁 pet 或 ball 控制器（互斥）。 */
    private fun applyIdleController() {
        val params = floatViewParams ?: return
        val wantPet = AppRuntimeConfig.isDesktopPetEnabled()
        if (wantPet) {
            ballController?.destroy()
            ballController = null
            if (petController == null) {
                FloatingWindowController.syncScreenOnFromSystem(this)
                petController = DesktopPetController(
                    context = this,
                    windowManager = windowManager,
                    petView = binding.idlePetView,
                    windowView = binding.root,
                    params = params
                )
                petController?.onCountdownChanged(floatSessionActive)
                petController?.onDimmedChanged(!visibilityAllowed)
                petController?.onScreenStateChanged(FloatingWindowController.screenOn.value)
            }
        } else {
            petController?.destroy()
            petController = null
            if (ballController == null) {
                // 悬浮窗可能早于 FGS 拉起：先对齐系统亮灭屏再创建小球
                FloatingWindowController.syncScreenOnFromSystem(this)
                ballController = IdleBallController(
                    context = this,
                    windowManager = windowManager,
                    ballView = binding.idleBallView,
                    windowView = binding.root,
                    params = params
                )
                ballController?.syncAnimationGates(
                    dimming = !visibilityAllowed,
                    screenOn = FloatingWindowController.screenOn.value
                )
            }
        }
    }

    /** 刷新倒计时文案；仅卡片背景沿全周期曲线渐隐，文字/图标保持不透明确保可读 */
    private fun applyCountdownTick(tick: Int) {
        if (!::binding.isInitialized) return
        binding.timeView.text = "${tick}s"
        // 时间数字随每次 tick 轻微脉动，增强跳秒感
        if (floatSessionActive) pulseTimeView()
        if (!floatSessionActive || !visibilityAllowed) return
        val card = binding.countdownCardView
        if (tick >= countdownTotal) {
            // 倒计时起点：背景满透明度基准态
            countdownWaveAnimator?.cancel()
            card.animate().cancel()
            setCountdownBackgroundAlpha(1f)
            card.scaleX = 1f
            card.scaleY = 1f
            card.translationY = 0f
            setCountdownTouchPassthrough(false)
            return
        }
        setCountdownTouchPassthrough(true)
        animateCountdownExit(countdownAlphaFor(tick))
    }

    /** 背景渐隐只作用于 drawable，文字/图标不参与，保证倒计时全程可读 */
    private fun setCountdownBackgroundAlpha(alpha: Float) {
        binding.countdownContent.background?.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
    }

    private fun currentCountdownBackgroundAlpha(): Float =
        (binding.countdownContent.background?.alpha ?: 255) / 255f

    /** 时间数字脉动：每次跳秒轻微放大回弹，增强视觉反馈 */
    private fun pulseTimeView() {
        val tv = binding.timeView
        tv.animate().cancel()
        tv.scaleX = 1.08f
        tv.scaleY = 1.08f
        tv.animate()
            .scaleX(1f).scaleY(1f)
            .setDuration(260L)
            .setInterpolator(PathInterpolator(0.2f, 0f, 0.3f, 1f))
            .start()
    }

    /** 倒计时入场：卡片从 0.86 缩放到 1 弹入 */
    private fun playCountdownEntrance() {
        val card = binding.countdownCardView
        card.animate().cancel()
        card.scaleX = 0.86f
        card.scaleY = 0.86f
        card.alpha = 1f
        card.animate()
            .scaleX(1f).scaleY(1f)
            .setDuration(320L)
            .setInterpolator(PathInterpolator(0.2f, 0f, 0.25f, 1f))
            .start()
    }

    /**
     * 全周期双锚点渐隐曲线（单调连续、无截断断点）：
     * - 起点（tick=总时长）：1.0
     * - 锚点（tick=5s，截图时刻）：0.12，实测不影响截图识别
     * - 终点（tick=0）：0.04
     * 两段以幂曲线（1.5 次幂）平滑衔接，透明度全程缓降，末 5s 不再骤减。
     */
    private fun countdownAlphaFor(tick: Int): Float {
        val total = countdownTotal.coerceAtLeast(1)
        val remain = tick.coerceIn(0, total)
        return if (remain <= SCREENSHOT_AT_SECONDS) {
            // 截图段：0.12 → 0.04 线性收尾
            val f = remain.toFloat() / SCREENSHOT_AT_SECONDS
            COUNTDOWN_FADE_MIN_ALPHA +
                (COUNTDOWN_ALPHA_AT_SCREENSHOT - COUNTDOWN_FADE_MIN_ALPHA) * f
        } else {
            // 主段：1.0 → 0.12 幂曲线缓降
            val span = (total - SCREENSHOT_AT_SECONDS).coerceAtLeast(1)
            val p = (remain - SCREENSHOT_AT_SECONDS).toFloat() / span
            COUNTDOWN_ALPHA_AT_SCREENSHOT + (1f - COUNTDOWN_ALPHA_AT_SCREENSHOT) * p.pow(1.5f)
        }
    }

    /** 倒计时退场：波浪式渐隐——背景透明度向目标收敛时正弦起伏，卡片轻微上下漂浮如浮于水面 */
    private fun animateCountdownExit(target: Float) {
        val card = binding.countdownCardView
        countdownWaveAnimator?.cancel()
        val startAlpha = currentCountdownBackgroundAlpha()
        countdownWaveAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = COUNTDOWN_FADE_STEP_MS
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val frac = anim.animatedValue as Float
                // 背景透明度：从当前值向目标收敛 + 正弦波浪调制（文字不受影响，保持可读）
                val base = startAlpha + (target - startAlpha) * frac
                val wave = 0.5f + 0.5f * sin((frac * 3 * PI).toFloat())
                setCountdownBackgroundAlpha(base * (0.85f + 0.15f * wave))
                // 整卡轻微上下漂浮，幅度随渐隐收窄
                card.translationY = 5f * wave * (1f - frac)
            }
            addListener(object : AnimatorListenerAdapter() {
                private var canceled = false
                override fun onAnimationCancel(animation: Animator) { canceled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (!canceled) {
                        setCountdownBackgroundAlpha(target)
                        card.translationY = 0f
                    }
                }
            })
            start()
        }
    }

    private fun resetCountdownPresentation() {
        if (!::binding.isInitialized) return
        countdownWaveAnimator?.cancel()
        countdownWaveAnimator = null
        binding.countdownCardView.animate().cancel()
        setCountdownBackgroundAlpha(1f)
        binding.countdownCardView.translationY = 0f
        binding.countdownCardView.scaleX = 1f
        binding.countdownCardView.scaleY = 1f
        binding.timeView.animate().cancel()
        binding.timeView.scaleX = 1f
        binding.timeView.scaleY = 1f
        setCountdownTouchPassthrough(false)
    }

    private fun setCountdownTouchPassthrough(passthrough: Boolean) {
        if (passthrough == countdownTouchPassthrough) return
        countdownTouchPassthrough = passthrough
        val params = floatViewParams ?: return
        params.flags = if (passthrough) {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        if (!::binding.isInitialized || !binding.root.isAttachedToWindow) return
        try {
            windowManager.updateViewLayout(binding.root, params)
        } catch (e: IllegalArgumentException) {
            Log.d(kTag, "setCountdownTouchPassthrough skipped: ${e.message}")
        }
    }

    // 统一计算悬浮窗可见性与形态：
    // · 蒙层遮挡或截屏临时隐藏时整体 GONE；
    // · 打卡会话中展开倒计时卡片；
    // · 空闲时：桌宠开→宠物，关→悬浮小球（窗口常驻，供安卓 15+ 后台跳转豁免）。
    private fun recomputeVisibility() {
        if (!visibilityAllowed || hiddenForScreenshot) {
            binding.root.visibility = View.GONE
            binding.root.alpha = 0.0f
            setCountdownTouchPassthrough(false)
            return
        }
        if (floatSessionActive) {
            // 倒计时只显示卡片（贴屏幕左缘），宠物/小球空闲态才出现
            binding.idlePetView.visibility = View.GONE
            binding.idleBallView.visibility = View.GONE
            binding.countdownCardView.visibility = View.VISIBLE
            binding.root.visibility = View.VISIBLE
            binding.root.alpha = 1.0f
            // 小球模式窗口为固定小尺寸，进倒计时需放开为 WRAP_CONTENT 以免裁切卡片
            floatViewParams?.let {
                it.width = WindowManager.LayoutParams.WRAP_CONTENT
                it.height = WindowManager.LayoutParams.WRAP_CONTENT
            }
        } else {
            binding.countdownCardView.visibility = View.GONE
            val petOn = AppRuntimeConfig.isDesktopPetEnabled()
            binding.idlePetView.visibility = if (petOn) View.VISIBLE else View.GONE
            binding.idleBallView.visibility = if (petOn) View.GONE else View.VISIBLE
            binding.root.visibility = View.VISIBLE
            binding.root.alpha = 1.0f
            resetCountdownPresentation()
            if (!petOn) ballController?.ensureLayout()
        }
        floatViewParams?.let {
            try {
                windowManager.updateViewLayout(binding.root, it)
            } catch (e: IllegalArgumentException) {
                Log.d(kTag, "recomputeVisibility update skipped: ${e.message}")
            }
        }
    }

    private fun restartMemoryMonitoring() {
        memoryMonitorJob?.cancel()
        val interval = if (AppRuntimeConfig.isPowerSaveMode()) {
            60_000L
        } else {
            30_000L
        }
        memoryMonitorJob = launch {
            // 立即更新一次
            updateMemoryInfo()

            while (isActive) {
                delay(interval)
                updateMemoryInfo()
            }
        }
    }

    private suspend fun updateMemoryInfo() {
        withContext(Dispatchers.IO) {
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)

            val totalMem = memoryInfo.totalMem
            val availMem = memoryInfo.availMem
            val usedMem = totalMem - availMem
            val usagePercent = ((usedMem * 100.0) / totalMem).toInt()

            withContext(Dispatchers.Main) {
                if (usagePercent >= 90) {
                    val now = System.currentTimeMillis()
                    if (now - lastMemoryAlertAt >= 30 * 60 * 1000L) {
                        lastMemoryAlertAt = now
                        MessageDispatcher.sendMessage(
                            "内存使用预警",
                            StatusReporter.buildMemoryAlertHtml(),
                            appendMeta = false
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        memoryMonitorJob?.cancel()
        petController?.destroy()
        petController = null
        ballController?.destroy()
        ballController = null
        cancel()
        if (::binding.isInitialized && binding.root.isAttachedToWindow) {
            try {
                windowManager.removeViewImmediate(binding.root)
            } catch (e: IllegalArgumentException) {
                Log.w(kTag, "View not attached to window manager", e)
            }
        }
        Log.d(kTag, "onDestroy: FloatingWindowService")
        isRunning = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 「暂停使用」开启时自停：防御任意路径（广播/回调）在暂停期间拉起本服务
        if (KeepAliveReceiver.isPaused()) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }
}