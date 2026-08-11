package com.pengxh.daily.app.service

import com.pengxh.daily.app.R
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.view.ContextThemeWrapper
import com.pengxh.daily.app.databinding.WindowFloatingBinding
import com.pengxh.daily.app.utils.AppRuntimeConfig
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.FloatingWindowController
import com.pengxh.daily.app.utils.MessageDispatcher
import com.pengxh.daily.app.utils.StatusReporter
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
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var memoryMonitorJob: Job? = null
    private var lastMemoryAlertAt = 0L
    // 悬浮窗可见性由两个独立维度决定：
    // 1) floatSessionActive —— 是否处于「被控端主动跳到目标 App」的操作会话中（由 openApplication 统一 start、各操作结束 stop）
    // 2) visibilityAllowed —— 蒙层是否未遮挡（由 show/hide 控制，蒙层显示时临时隐藏避免截到黑屏）
    // 形态切换：会话中显示完整倒计时卡片；空闲（非打卡、蒙层未遮挡）显示桌面小宠物，
    // 保证悬浮窗窗口常驻可见可拖动，为安卓 15+ 后台跳转提供可见窗口豁免；蒙层遮挡时整体隐藏。
    private var floatSessionActive = false
    private var visibilityAllowed = true

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        // 浮动窗口由 Service 上下文 inflate；Service 不会自动套用 App 的 Material 主题，
        // 必须用 ContextThemeWrapper 显式包一层 Theme.DailyTask，否则 MaterialCardView/MaterialTextView 会 inflate 崩溃。
        binding = WindowFloatingBinding.inflate(LayoutInflater.from(ContextThemeWrapper(this, R.style.Theme_DailyTask)))
        // 默认贴右边缘、垂直居中显示。
        // 用 Gravity.END 锚定右缘，不依赖视图测量宽度——
        // 避免 addView 后首帧未测量导致 width=0、被推到屏幕外不可见的旧 bug。
        // x 为相对 END 锚点的偏移：负值=向左留间隙，窗口整体贴边且完全可见。
        val edgeMarginPx = edgeMarginPx()
        floatViewParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = -edgeMarginPx
        }.also {
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
                recomputeVisibility()
            }
        }
        launch {
            AppRuntimeConfig.powerSaveMode.collect {
                restartMemoryMonitoring()
                applyWaveAnimation()
            }
        }

        // 初始状态：空闲（非打卡、蒙层未遮挡）显示桌面小宠物，默认贴右边缘、垂直居中停靠。
        // 宠物极小巧（emoji），常驻可见但不明显遮挡交互，为安卓 15+ 后台跳转提供常驻可见窗口豁免；
        // 可拖动到任意位置（松手即停），打卡会话开始后由 recomputeVisibility() 自动展开为完整倒计时卡片。
        binding.countdownCardView.visibility = View.GONE
        binding.idlePetView.visibility = View.VISIBLE
        binding.root.visibility = View.VISIBLE
        binding.root.alpha = 1.0f
        binding.timeView.text = "0s"
        dockPetToEdge()

        // 移动悬浮窗（宠物与卡片都可拖动到任意位置）
        onDragMove()

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

    private fun edgeMarginPx() = (10 * resources.displayMetrics.density).toInt()

    // 桌面宠物初始化停靠：贴右边缘、垂直居中
    private fun dockPetToEdge() {
        floatViewParams?.let {
            it.gravity = Gravity.END or Gravity.CENTER_VERTICAL
            it.x = -edgeMarginPx()
            it.y = 0
            windowManager.updateViewLayout(binding.root, it)
        }
    }

    // 松手自动吸附到较近的屏幕边缘（保留纵向位置），保证宠物始终贴边停靠。
    private fun dockPetToNearestEdge() {
        val params = floatViewParams ?: return
        val screenWidth = resources.displayMetrics.widthPixels
        val width = binding.root.width.coerceAtLeast(1)
        // END 锚点下 x 为窗口右缘相对屏幕右缘的偏移：负=屏内留边距，正=越出屏幕右侧
        val windowRight = screenWidth + params.x
        val windowLeft = windowRight - width
        val distRight = (screenWidth - windowRight).coerceAtLeast(0)
        val distLeft = windowLeft.coerceAtLeast(0)
        // 记录当前纵向偏移，贴边时保留（CENTER_VERTICAL 下 y 为相对垂直居中的偏移）
        val keepY = params.y
        if (distRight <= distLeft) {
            params.gravity = Gravity.END or Gravity.CENTER_VERTICAL
            params.x = -edgeMarginPx()
        } else {
            params.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            params.x = edgeMarginPx()
        }
        params.y = keepY
        windowManager.updateViewLayout(binding.root, params)
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

    @SuppressLint("ClickableViewAccessibility")
    private fun onDragMove() {
        binding.root.setOnTouchListener(object : View.OnTouchListener {
            override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                event ?: return false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = floatViewParams?.x ?: 0
                        initialY = floatViewParams?.y ?: 0
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        floatViewParams?.let {
                            it.x = initialX + (event.rawX - initialTouchX).toInt()
                            it.y = initialY + (event.rawY - initialTouchY).toInt()
                            windowManager.updateViewLayout(binding.root, it)
                        }
                        return true
                    }

                    // 松手自动吸附到较近的屏幕边缘（宠物始终贴边停靠）
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        dockPetToNearestEdge()
                        return true
                    }

                    else -> return false
                }
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        memoryMonitorJob?.cancel()
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
