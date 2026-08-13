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
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator
import androidx.appcompat.view.ContextThemeWrapper
import kotlin.math.abs
import kotlin.math.pow
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

    companion object {
        /** 与预截图触发对齐（NotificationMonitorService / TaskScheduler：tick <= 5） */
        private const val SCREENSHOT_AT_SECONDS = 5
        /** 更早开始渐隐，秒级目标之间用近 1s 动画衔接，避免跳变 */
        private const val COUNTDOWN_FADE_START = 10
        private const val COUNTDOWN_FADE_MIN_ALPHA = 0.04f
        /** 预截图时刻目标透明度（由曲线平滑落到此附近，不再瞬间跳变） */
        private const val COUNTDOWN_ALPHA_AT_SCREENSHOT = 0.14f
        /** 与倒计时 tick 间隔对齐，连续收淡 */
        private const val COUNTDOWN_FADE_STEP_MS = 920L
    }

    /** 桌宠控制器（开关打开时）；小球控制器（默认 / 开关关闭时）。二者互斥。 */
    private var petController: DesktopPetController? = null
    private var ballController: IdleBallController? = null

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
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
                recomputeVisibility()
            }
        }
        launch {
            FloatingWindowController.floatSessionActive.collect { active ->
                floatSessionActive = active
                if (active) {
                    val time = SaveKeyValues.loadInt(
                        Constant.STAY_OVERTIME_KEY, Constant.DEFAULT_OVER_TIME
                    )
                    resetCountdownPresentation()
                    applyCountdownTick(time)
                } else {
                    binding.timeView.text = "0s"
                    binding.waveProgressView.stopWaveAnimation()
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
                applyWaveAnimation()
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
        applyWaveAnimation()
    }

    /** 按桌宠开关创建/销毁 pet 或 ball 控制器（互斥）。 */
    private fun applyIdleController() {
        val params = floatViewParams ?: return
        val wantPet = AppRuntimeConfig.isDesktopPetEnabled()
        if (wantPet) {
            ballController?.destroy()
            ballController = null
            if (petController == null) {
                petController = DesktopPetController(
                    context = this,
                    windowManager = windowManager,
                    petView = binding.idlePetView,
                    windowView = binding.root,
                    params = params
                )
                petController?.onCountdownChanged(floatSessionActive)
                petController?.onDimmedChanged(!visibilityAllowed)
            }
        } else {
            petController?.destroy()
            petController = null
            if (ballController == null) {
                ballController = IdleBallController(
                    context = this,
                    windowManager = windowManager,
                    ballView = binding.idleBallView,
                    windowView = binding.root,
                    params = params
                )
            }
        }
    }

    private fun applyWaveAnimation() {
        if (!::binding.isInitialized) return
        if (floatSessionActive && visibilityAllowed && !AppRuntimeConfig.isPowerSaveMode()) {
            binding.waveProgressView.startWaveAnimation()
        } else {
            binding.waveProgressView.stopWaveAnimation()
        }
    }

    /** 刷新倒计时文案；末段从 10s 起按曲线平滑渐隐（每秒目标用近 1s 动画衔接）。 */
    private fun applyCountdownTick(tick: Int) {
        if (!::binding.isInitialized) return
        binding.timeView.text = "${tick}s"
        if (!floatSessionActive || !visibilityAllowed) return
        val card = binding.countdownCardView
        if (tick > COUNTDOWN_FADE_START) {
            card.animate().cancel()
            card.alpha = 1f
            setCountdownTouchPassthrough(false)
            return
        }
        setCountdownTouchPassthrough(true)
        animateCountdownAlpha(countdownAlphaFor(tick))
    }

    /**
     * 幂曲线：10s≈不透明 → 5s≈0.14 → 0s≈min。
     * 相邻 tick 差值小，再配合 [COUNTDOWN_FADE_STEP_MS] 动画，观感连续不跳。
     */
    private fun countdownAlphaFor(tick: Int): Float {
        if (tick <= 0) return COUNTDOWN_FADE_MIN_ALPHA
        if (tick >= COUNTDOWN_FADE_START) return 1f
        val t = tick.toFloat() / COUNTDOWN_FADE_START
        // 略加速收淡，保证截屏秒已接近 COUNTDOWN_ALPHA_AT_SCREENSHOT
        val shaped = t.toDouble().pow(1.65).toFloat()
        val alpha = COUNTDOWN_FADE_MIN_ALPHA + (1f - COUNTDOWN_FADE_MIN_ALPHA) * shaped
        // 截屏窗口内再夹到上限，避免曲线偶发偏高
        return if (tick <= SCREENSHOT_AT_SECONDS) {
            alpha.coerceAtMost(COUNTDOWN_ALPHA_AT_SCREENSHOT)
        } else {
            alpha
        }
    }

    private fun animateCountdownAlpha(target: Float) {
        val card = binding.countdownCardView
        card.animate().cancel()
        if (abs(card.alpha - target) < 0.012f) {
            card.alpha = target
            return
        }
        // 快进慢收：前半段跟手，后半段柔和落稳，避免生硬跳档
        val ease = PathInterpolator(0.33f, 0f, 0.20f, 1f)
        card.animate()
            .alpha(target)
            .setDuration(COUNTDOWN_FADE_STEP_MS)
            .setInterpolator(ease)
            .start()
    }

    private fun resetCountdownPresentation() {
        if (!::binding.isInitialized) return
        binding.countdownCardView.animate().cancel()
        binding.countdownCardView.alpha = 1f
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
            binding.waveProgressView.stopWaveAnimation()
            setCountdownTouchPassthrough(false)
            return
        }
        if (floatSessionActive) {
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
            applyWaveAnimation()
        } else {
            binding.countdownCardView.visibility = View.GONE
            val petOn = AppRuntimeConfig.isDesktopPetEnabled()
            binding.idlePetView.visibility = if (petOn) View.VISIBLE else View.GONE
            binding.idleBallView.visibility = if (petOn) View.GONE else View.VISIBLE
            binding.root.visibility = View.VISIBLE
            binding.root.alpha = 1.0f
            binding.waveProgressView.stopWaveAnimation()
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
                binding.waveProgressView.setProgress(usagePercent)
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