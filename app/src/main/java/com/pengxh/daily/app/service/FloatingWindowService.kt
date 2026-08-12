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
import androidx.appcompat.view.ContextThemeWrapper
import com.pengxh.daily.app.databinding.WindowFloatingBinding
import com.pengxh.daily.app.utils.AppRuntimeConfig
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.FloatingWindowController
import com.pengxh.daily.app.utils.MessageDispatcher
import com.pengxh.daily.app.utils.StatusReporter
import com.pengxh.daily.app.widget.DesktopPetController
import com.pengxh.daily.app.widget.DesktopPetView
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
    // 悬浮窗可见性由两个独立维度决定：
    // 1) floatSessionActive —— 是否处于「被控端主动跳到目标 App」的操作会话中（由 openApplication 统一 start、各操作结束 stop）
    // 2) visibilityAllowed —— 蒙层是否未遮挡（由 show/hide 控制，蒙层显示时临时隐藏避免截到黑屏）
    // 形态切换：会话中显示完整倒计时卡片；空闲（非打卡、蒙层未遮挡）显示桌面小宠物，
    // 保证悬浮窗窗口常驻可见可拖动，为安卓 15+ 后台跳转提供可见窗口豁免；蒙层遮挡时整体隐藏。
    private var floatSessionActive = false
    private var visibilityAllowed = true

    /**
     * 桌面宠物交互控制器（拖拽 / 左右贴边 / 点击反馈）。
     * 由 DesktopPetController 内部管理 idlePetView 的位置/尺寸/状态切换与触摸事件，
     * 本 service 不再处理 onTouchListener 与贴边计算。
     */
    private var petController: DesktopPetController? = null

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
        // 倒计时数字只负责刷新文本；是否可见由 recomputeVisibility() 统一决定（打卡中且蒙层未遮挡）
        launch {
            FloatingWindowController.timeTick.collect { tick ->
                binding.timeView.text = "${tick}s"
            }
        }
        launch {
            FloatingWindowController.overtime.collect { seconds ->
                binding.timeView.text = "${seconds}s"
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
                    binding.timeView.text = "${time}s"
                } else {
                    binding.timeView.text = "0s"
                    binding.waveProgressView.stopWaveAnimation()
                }
                // 打卡会话联动宠物：开始→COUNTDOWN 停动画/计时器；结束→恢复 blink + 重启计时器
                petController?.onCountdownChanged(active)
                recomputeVisibility()
            }
        }
        // 伪息屏开关变更不直接驱动宠物；DIMMED 由蒙层可见性（visibility）驱动
        launch {
            AppRuntimeConfig.powerSaveMode.collect {
                restartMemoryMonitoring()
                applyWaveAnimation()
            }
        }

        // 初始状态：空闲（非打卡、蒙层未遮挡）显示桌面小宠物；窗口默认参数由 controller 接管
        binding.countdownCardView.visibility = View.GONE
        binding.idlePetView.visibility = View.VISIBLE
        binding.root.visibility = View.VISIBLE
        binding.root.alpha = 1.0f
        binding.timeView.text = "0s"
        floatViewParams?.let { params ->
            // DesktopPetView 是 binding.idlePetView 的实际类型（自定义 FrameLayout），
            // 此处创建 controller 接管触摸/位置/状态/动画，service 仅负责可见性与生命周期。
            // 注意：updateViewLayout 必须作用于 addView 的窗口根视图（binding.root），不能传子 View。
            petController = DesktopPetController(
                context = this,
                windowManager = windowManager,
                petView = binding.idlePetView as DesktopPetView,
                windowView = binding.root,
                params = params
            )
            // 补同步：collect 块在 controller 创建前注册，若开关/会话已是激活态，
            // replay 不会在 controller 就绪后重发——这里手动拉齐一次当前状态。
            petController?.onCountdownChanged(floatSessionActive)
            petController?.onDimmedChanged(!visibilityAllowed)
        }

        restartMemoryMonitoring()
        applyWaveAnimation()
    }

    private fun applyWaveAnimation() {
        if (!::binding.isInitialized) return
        if (floatSessionActive && visibilityAllowed && !AppRuntimeConfig.isPowerSaveMode()) {
            binding.waveProgressView.startWaveAnimation()
        } else {
            binding.waveProgressView.stopWaveAnimation()
        }
    }

    // 统一计算悬浮窗可见性与形态：
    // · 蒙层遮挡时整体隐藏（用 View.GONE：GONE 的窗口不参与绘制与 hit-test，触摸事件穿透到下层 App）；
    // · 打卡会话中展开完整倒计时卡片；
    // · 空闲（非打卡、蒙层未遮挡）时显示桌面小宠物，窗口保持常驻可见可拖动，
    //   为安卓 15+ 后台跳转提供可见窗口豁免。
    private fun recomputeVisibility() {
        if (!visibilityAllowed) {
            binding.root.visibility = View.GONE
            binding.root.alpha = 0.0f
            binding.waveProgressView.stopWaveAnimation()
            return
        }
        if (floatSessionActive) {
            binding.idlePetView.visibility = View.GONE
            binding.countdownCardView.visibility = View.VISIBLE
            binding.root.visibility = View.VISIBLE
            binding.root.alpha = 1.0f
            applyWaveAnimation()
        } else {
            binding.countdownCardView.visibility = View.GONE
            binding.idlePetView.visibility = View.VISIBLE
            binding.root.visibility = View.VISIBLE
            binding.root.alpha = 1.0f
            binding.waveProgressView.stopWaveAnimation()
        }
        // WRAP_CONTENT 窗口在宠物/卡片形态切换后需重新布局以匹配新尺寸
        floatViewParams?.let { windowManager.updateViewLayout(binding.root, it) }
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