package com.pengxh.daily.app.ui

import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.Manifest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Build
import android.os.PowerManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.transition.TransitionManager
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import android.widget.ImageView
import android.content.res.ColorStateList
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.transition.MaterialFadeThrough
import com.pengxh.daily.app.BuildConfig
import com.pengxh.daily.app.R
import com.yample.mqttprotocol.dialog.UnifiedDialogKit
import com.pengxh.daily.app.databinding.ActivityMainBinding
import com.pengxh.daily.app.extensions.bringDailyTaskToFront
import com.pengxh.daily.app.extensions.isAutostartGranted
import com.pengxh.daily.app.extensions.notificationEnable
import com.pengxh.daily.app.service.AutoProjectionAccessibilityService
import com.pengxh.daily.app.service.CaptureImageService
import com.pengxh.daily.app.service.FloatingWindowService
import com.pengxh.daily.app.service.ForegroundRunningService
import com.pengxh.daily.app.service.KeepAliveReceiver
import com.pengxh.daily.app.service.MqttAgentService
import com.pengxh.daily.app.service.NotificationMonitorService
import com.pengxh.daily.app.utils.AppRuntimeConfig
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.ConfigImportSignal
import com.pengxh.daily.app.utils.FloatingWindowController
import com.pengxh.daily.app.utils.GestureController
import com.pengxh.daily.app.utils.IdlePseudoMaskController
import com.pengxh.daily.app.utils.LogFileManager
import com.pengxh.daily.app.utils.MaskOverlayHelper
import com.pengxh.daily.app.utils.MaskViewController
import com.pengxh.daily.app.utils.MessageDispatcher
import com.pengxh.daily.app.utils.MonitorEvent
import com.pengxh.daily.app.utils.ProjectionSession
import com.pengxh.daily.app.utils.RomDetector
import com.pengxh.daily.app.utils.StatusReporter
import com.pengxh.daily.app.utils.TaskScheduler
import com.pengxh.kt.lite.base.KotlinBaseActivity
import com.pengxh.kt.lite.extensions.dp2px
import com.pengxh.kt.lite.extensions.show
import com.pengxh.kt.lite.utils.SaveKeyValues
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Date

import com.pengxh.daily.app.extensions.format

/**
 * 单 Activity 宿主：承载 任务 / 远程 / 设置 三个 Tab Fragment + 底部磨砂悬浮导航。
 * 伪息屏蒙层、手势、前台服务、远程指令等应用级逻辑保留在宿主。
 */
class MainActivity : KotlinBaseActivity<ActivityMainBinding>() {

    companion object {
        private const val USAGE_NOTICE_ACK_VERSION_KEY = "usage_notice_ack_version"

        const val EXTRA_TAB = "extra_tab"
        const val TAB_TASK = "tab_task"
        const val TAB_REMOTE = "tab_remote"
        const val TAB_SETTINGS = "tab_settings"

        private const val TAG_TASK = "task"
        private const val TAG_REMOTE = "remote"
        private const val TAG_SETTINGS = "settings"
        private const val KEY_CURRENT_TAB = "current_tab"
    }

    private val kTag = "MainActivity"
    private val context by lazy { this }
    private val marginOffset by lazy { 16.dp2px(this) }
    private val permissionContract by lazy { ActivityResultContracts.StartActivityForResult() }

    private val insetsController by lazy {
        WindowCompat.getInsetsController(window, binding.rootView)
    }
    private val maskViewController: MaskViewController by lazy {
        MaskViewController(this, binding, insetsController) { visible ->
            if (visible) {
                stopIdleMaskTimer()
            } else {
                resetIdleMaskTimer()
            }
        }
    }
    private val gestureController by lazy { GestureController(this, maskViewController) }
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** 供 TaskFragment 时钟等判断是否处于伪息屏蒙层状态 */
    fun isMaskVisible(): Boolean = maskViewController.isMaskVisible()

    /** 电池优化引导对话框是否已在本次生命周期提示过（避免 onResume 反复弹窗） */
    private var batteryOptimizationPrompted = false

    /** 通知权限引导是否已在本次生命周期提示过（避免 onResume 反复弹窗） */
    private var notificationPermissionPrompted = false

    /** 自启动权限引导是否已在本次生命周期提示过（避免 onResume 反复弹窗） */
    private var autostartPermissionPrompted = false

    /** 三个 Tab 常驻 Fragment（一次性 add，hide/show 切换，与控制端交互一致） */
    private lateinit var taskFragment: TaskFragment
    private lateinit var remoteControlFragment: RemoteControlFragment
    private lateinit var settingsFragment: SettingsFragment
    private val allFragments: List<Fragment>
        get() = listOf(taskFragment, remoteControlFragment, settingsFragment)
    private var currentTabTag = TAG_TASK

    /** 远程控制端修改设置/任务后，前台主界面即时刷新任务列表（无需二次进入） */
    private val remoteConfigReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == ConfigImportSignal.ACTION_REMOTE_CONFIG_CHANGED) {
                ConfigImportSignal.pendingMainActivityRefresh = false
                taskFragment.refreshTaskListFromDb()
                applyForegroundScreenPolicy()
            }
        }
    }

    override fun observeRequestState() {
    }

    override fun initViewBinding(): ActivityMainBinding {
        return ActivityMainBinding.inflate(layoutInflater)
    }

    override fun setupTopBarLayout() {
    }

    override fun initOnCreate(savedInstanceState: Bundle?) {
        // 前台亮灭屏策略：伪息屏开 / 屏幕模式 0·2 → 常亮；屏幕模式 1 → 允许系统自然灭屏
        applyForegroundScreenFlags()

        // 手动打开 App 时确保前台服务存活：FGS onCreate 会向 TaskScheduler 注入协程作用域，
        // 缺席时任务页「启动」按钮会因 scope 未初始化而静默失效（不受「暂停使用」开关限制）
        if (!ForegroundRunningService.isRunning) {
            startForegroundService(Intent(this, ForegroundRunningService::class.java))
        }

        // 悬浮窗服务：已授权且非「暂停使用」时随 App 打卡拉起（与原工程行为一致；
        // 未授权时由 onResume 的权限门禁跳转系统授权页，授权返回后经 launcher 回调拉起）
        if (!KeepAliveReceiver.isPaused()) {
            KeepAliveReceiver.ensureFloatingWindow(this)
        }

        // 悬浮蒙层上滑解除时，同步卸掉 Activity 内蒙层（一次上滑同时出控制界面）
        MaskOverlayHelper.activityMaskHider = {
            if (maskViewController.isMaskVisible()) {
                maskViewController.hideMaskView(syncOverlay = false)
                true
            } else {
                false
            }
        }

        // 磨砂玻璃悬浮导航：模糊其下方的全部 Tab 内容
        try {
            binding.bottomNavBar.blurView.setupWith(binding.rootView)
                .setBlurRadius(24f)
                .setOverlayColor(android.graphics.Color.TRANSPARENT)
        } catch (e: Exception) {
            LogFileManager.error("BlurView 初始化失败: ${e.message}")
        }

        // 一次性添加三个 Tab（隐藏非默认 Tab），后续切换只做 hide/show。
        // 重建（recreate，如切换主题）时 FragmentManager 已恢复旧实例，必须按 tag 复用，
        // 否则会重复 add 新实例导致内容与 BottomNavigationView 高亮脱节（如内容=任务页、tab=设置）。
        val ft = supportFragmentManager.beginTransaction()
        taskFragment = supportFragmentManager.findFragmentByTag(TAG_TASK) as? TaskFragment
            ?: TaskFragment().also { ft.add(R.id.fragmentContainer, it, TAG_TASK) }
        remoteControlFragment = supportFragmentManager.findFragmentByTag(TAG_REMOTE) as? RemoteControlFragment
            ?: RemoteControlFragment().also { ft.add(R.id.fragmentContainer, it, TAG_REMOTE) }
        settingsFragment = supportFragmentManager.findFragmentByTag(TAG_SETTINGS) as? SettingsFragment
            ?: SettingsFragment().also { ft.add(R.id.fragmentContainer, it, TAG_SETTINGS) }
        if (savedInstanceState == null) {
            // 首次创建：只显示默认任务页
            ft.hide(remoteControlFragment).hide(settingsFragment)
        } else {
            // 重建：FragmentManager 已恢复各实例隐藏/显示状态，无需再 hide；
            // 重新同步当前 tab，与恢复后的 BottomNavigationView 高亮保持一致
currentTabTag = savedInstanceState.getString(KEY_CURRENT_TAB, TAG_TASK) ?: TAG_TASK
        }
ft.commitNow()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_CURRENT_TAB, currentTabTag)
        super.onSaveInstanceState(outState)
    }

    override fun initEvent() {
        setupBottomNav()
    }

private fun setupBottomNav() {
        try {
            binding.bottomNavBar.navRemote.setOnClickListener { switchTab(TAG_REMOTE) }
            binding.bottomNavBar.navTask.setOnClickListener { switchTab(TAG_TASK) }
            binding.bottomNavBar.navSettings.setOnClickListener { switchTab(TAG_SETTINGS) }
            updateNavSelection(currentTabTag)
        } catch (e: Exception) {
            LogFileManager.error("导航加载失败: ${e.message}")
        }
    }

    private fun switchTab(tag: String) {
        if (tag == currentTabTag) return
        val target = when (tag) {
            TAG_TASK -> taskFragment
            TAG_REMOTE -> remoteControlFragment
            TAG_SETTINGS -> settingsFragment
            else -> return
        }
        if (supportFragmentManager.isStateSaved) {
            LogFileManager.writeLog("Activity 状态已保存，跳过 Tab 切换（tag=$tag）")
            return
        }
        currentTabTag = tag
        updateNavSelection(tag)
        val ft = supportFragmentManager.beginTransaction()
        allFragments.forEach { ft.hide(it) }
        ft.show(target)
        val reduceMotion = Settings.Global.getFloat(
            contentResolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f
        ) == 0f
        if (reduceMotion) {
            ft.commitNow()
        } else {
            TransitionManager.beginDelayedTransition(
                findViewById<ViewGroup>(R.id.fragmentContainer),
                MaterialFadeThrough()
            )
            ft.commitNow()
        }
    }

fun switchToTaskTab() {
        switchTab(TAG_TASK)
    }

    /** 更新导航选中态：两侧 Tab 图标/文字蓝色 vs 灰色，中心凸起按钮两态 */
    private fun updateNavSelection(activeTag: String) {
        val activeColor = ContextCompat.getColor(this, R.color.md_primary)
        val inactiveColor = ContextCompat.getColor(this, R.color.md_onSurfaceVariant)

        fun setSideState(icon: ImageView, label: TextView, isActive: Boolean) {
            val c = if (isActive) activeColor else inactiveColor
            icon.imageTintList = ColorStateList.valueOf(c)
            label.setTextColor(c)
        }

        setSideState(binding.bottomNavBar.iconRemote, binding.bottomNavBar.labelRemote, activeTag == TAG_REMOTE)
        setSideState(binding.bottomNavBar.iconSettings, binding.bottomNavBar.labelSettings, activeTag == TAG_SETTINGS)

        val taskActive = activeTag == TAG_TASK
        binding.bottomNavBar.navTask.setBackgroundResource(
            if (taskActive) R.drawable.bg_brand_gradient_circle else R.drawable.bg_nav_raised_silent)
        binding.bottomNavBar.iconTask.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this,
                if (taskActive) R.color.on_header else R.color.brand_purple))
    }

private fun applyTabFromIntent(intent: Intent?) {
        val tab = intent?.getStringExtra(EXTRA_TAB) ?: return
        intent.removeExtra(EXTRA_TAB)
        when (tab) {
            TAB_REMOTE -> switchTab(TAG_REMOTE)
            TAB_SETTINGS -> switchTab(TAG_SETTINGS)
            else -> switchTab(TAG_TASK)
        }
    }

    override fun onResume() {
        super.onResume()
        // 注册远程配置变更广播：控制端改设置/任务后，前台主界面即时刷新
        ContextCompat.registerReceiver(
            this, remoteConfigReceiver,
            IntentFilter(ConfigImportSignal.ACTION_REMOTE_CONFIG_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // 首次进入（含首次启动）必须加载一次任务列表；
        // 配置导入成功后也需刷新一次。其余 onResume 不再无谓查询 DB。
        if (ConfigImportSignal.pendingMainActivityRefresh) {
            ConfigImportSignal.pendingMainActivityRefresh = false
            taskFragment.refreshTaskListFromDb()
        }
        applyForegroundScreenFlags()
        applyMaskCommandFromIntent(intent)
        // 伪息屏蒙层（保亮）需补盖 Activity 蒙层实现「彻底黑」；真息屏不保亮蒙层
        // 是纯 overlay 过渡层（等系统超时灭屏），补盖会导致 SCREEN_OFF 摘 overlay 后
        // Activity 蒙层残留、下次亮屏仍是黑屏，故跳过。
        if (MaskOverlayHelper.isShowing() &&
            !MaskOverlayHelper.isNoKeepAwakeMask() &&
            !maskViewController.isMaskVisible()
        ) {
            maskViewController.showMaskView()
        }
        if (!maskViewController.isMaskVisible() && !MaskOverlayHelper.isShowing()) {
            resetIdleMaskTimer()
        }
        if (!Settings.canDrawOverlays(this)) {
            // 悬浮窗权限门禁：未授权直接跳系统授权页；返回仍未授权会在下次前台再次拉起，
            // 不授权不允许进入主界面。「暂停使用」状态下不强制。
            if (!KeepAliveReceiver.isPaused()) {
                overlayPermissionLauncher.launch(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
                return
            }
            "悬浮窗权限未开启，部分功能可能无法正常使用".show(this)
        }
        runStartupSelfCheck()
    }

    override fun onPause() {
        runCatching { unregisterReceiver(remoteConfigReceiver) }
        stopIdleMaskTimer()
        // 离开前台：交还系统亮灭屏管理
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 回前台不再打印截屏状态日志；权限失效的权威日志在 ProjectionSession.markStoppedNeedAuth()
        // （系统回收 MediaProjection 时）打印，此处仅保留面向用户的 toast 提示。
        if (!ProjectionSession.isStateActive()) {
            if (SaveKeyValues.loadInt(Constant.RESULT_SOURCE_KEY, Constant.DEFAULT_INDEX) == 1) {
                "截屏服务已断开，请重新授权".show(this)
                SaveKeyValues.saveInt(Constant.RESULT_SOURCE_KEY, 0)
            }
        }

        if (!applyMaskCommandFromIntent(intent)) {
            // 从后台返回恢复蒙层：受「强制伪息屏」开关控制（机制约定）；暂停使用时不恢复
            if (!KeepAliveReceiver.isPaused()
                && AppRuntimeConfig.isForcePseudoMask() && IdlePseudoMaskController.consumeReturnFromBackground()
            ) {
                if (!maskViewController.isMaskVisible()) {
                    maskViewController.showMaskView()
                }
                MaskOverlayHelper.show(this)
            }
        }
        applyTabFromIntent(intent)
    }

    /**
     * @return true 表示 Intent 携带了息屏/亮屏指令并已处理
     */
    private fun applyMaskCommandFromIntent(intent: Intent?): Boolean {
        val action = intent?.getIntExtra(Constant.EXTRA_MASK_COMMAND, -1) ?: -1
        if (action < 0) return false
        // 「暂停使用」开启：忽略息屏/亮屏蒙层指令
        if (KeepAliveReceiver.isPaused()) {
            intent?.removeExtra(Constant.EXTRA_MASK_COMMAND)
            return true
        }
        intent?.removeExtra(Constant.EXTRA_MASK_COMMAND)
        when (action) {
            1 -> {
                // 伪息屏指令：伪息屏开 → 保亮蒙层（原语义）；伪息屏关 → 不保亮黑蒙层，
                // 屏幕即刻黑、系统按超时自然灭屏锁屏（不持保亮锁，避免微亮常驻不锁屏）
                if (AppRuntimeConfig.isForcePseudoMask()) {
                    MaskOverlayHelper.show(this)
                    if (!maskViewController.isMaskVisible()) {
                        maskViewController.showMaskView()
                    }
                } else {
                    MaskOverlayHelper.show(this, keepAwake = false)
                    LogFileManager.writeLog("伪息屏指令：伪息屏关，盖不保亮黑蒙层")
                }
            }

            0 -> {
                // Activity 蒙层优先走 hideMaskView（内部 SYNC 卸 overlay）；仅 overlay 时用户解锁语义拉起界面
                if (maskViewController.isMaskVisible()) {
                    maskViewController.hideMaskView()
                } else if (!MaskOverlayHelper.isNoKeepAwakeMask()) {
                    MaskOverlayHelper.hide(this, MaskOverlayHelper.HideReason.USER_UNLOCK)
                } else {
                    // 真息屏不保亮黑蒙层（打卡返回场景）：不可被本指令摘除。
                    // 该蒙层由 ForegroundRunningService 在 SCREEN_OFF/SCREEN_ON 时统一摘除；
                    // 若在此摘除，打卡结束刚盖的黑蒙层会被 onNewIntent 附带指令竞态摘掉（真机 33ms 内复现）。
                    LogFileManager.writeLog("亮屏指令：当前为真息屏不保亮黑蒙层，跳过摘除（由系统灭屏/亮屏统一处理）")
                }
            }
        }
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 单 Activity 无页面栈，返回键统一最小化应用（与原「返回即退出」语义一致）
        moveTaskToBack(true)
    }

    override fun onDestroy() {
        if (MaskOverlayHelper.activityMaskHider != null) {
            MaskOverlayHelper.activityMaskHider = null
        }
        stopIdleMaskTimer()
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        maskViewController.destroy()
    }

    private fun handleMonitorEvent(event: MonitorEvent) {
        when (event) {
            is MonitorEvent.ClockInSuccess -> {
                TaskScheduler.notifyClockIn() // 通知 TaskScheduler：打卡成功，取消超时等待分支
                MqttAgentService.pushTaskIncrement() // 打卡完成 → 增量推送控制端刷新日历/任务
                // 末段截屏可能仍藏着悬浮窗；回跳由 TaskScheduler.returnAfterPunch 统一处理
                FloatingWindowController.restoreAfterScreenshot()
                FloatingWindowController.stopFloatSession()
                switchToTaskTab()
            }

            is MonitorEvent.StartTaskCommand -> {
                if (!KeepAliveReceiver.isPaused() && !TaskScheduler.isRunning()) {
                    TaskScheduler.startTask()
                }
                MqttAgentService.pushTaskIncrement() // 任务调度启动 → 推送状态变化
            }

            is MonitorEvent.StopTaskCommand -> {
                doStopTask()
                MqttAgentService.pushTaskIncrement() // 任务调度停止 → 推送状态变化
            }

            is MonitorEvent.ShowMaskCommand -> {
                // 「暂停使用」开启：不执行远程息屏/伪息屏蒙层指令
                if (!KeepAliveReceiver.isPaused()) {
                    MaskOverlayHelper.show(this)
                    if (!maskViewController.isMaskVisible()) {
                        maskViewController.showMaskView()
                    }
                }
            }

            is MonitorEvent.HideMaskCommand -> {
                if (maskViewController.isMaskVisible()) {
                    maskViewController.hideMaskView()
                } else {
                    MaskOverlayHelper.hide(this, MaskOverlayHelper.HideReason.USER_UNLOCK)
                }
                resetIdleMaskTimer()
            }

            is MonitorEvent.AppOpenedForScreenshot -> {
                /**
                 * 遥控"截屏"指令完整流程：
                 *   1. 由 NotificationMonitorService 触发 openApplication
                 *   2. 等待 10 秒让目标 App 界面稳定（需要把目标APP的启动动画耗时加上）
                 *   3. 触发截屏
                 *   4. 等待截屏结果（在跳转之前，避免 lifecycle 问题）
                 *   5. 回到主界面
                 *   6. 发送通知
                 */
                lifecycleScope.launch {
                    try {
                    val countdownTarget = SystemClock.elapsedRealtime() + 10_000L
                    while (isActive) {
                        val remaining = countdownTarget - SystemClock.elapsedRealtime()
                        if (remaining <= 0) break
                        FloatingWindowController.updateTime((remaining / 1000).toInt())
                        delay(minOf(1000L, remaining).coerceAtLeast(1))
                    }

                    // 触发截屏并等待截屏结果
                    val source = SaveKeyValues.loadInt(
                        Constant.RESULT_SOURCE_KEY, Constant.DEFAULT_INDEX
                    )
                    val useAccessibility = if (source == 2) {
                        true
                    } else {
                        !ProjectionSession.isStateActive()
                                && AutoProjectionAccessibilityService.canTakeScreenshot(this@MainActivity)
                    }
                    val imagePath = if (useAccessibility) {
                        AutoProjectionAccessibilityService.requestScreenshot()?.await()
                    } else {
                        CaptureImageService.requestCaptureScreen().await()
                    }

                    // 回到主界面
                    backToMainActivity()
                    if (imagePath.isNullOrEmpty()) {
                        MessageDispatcher.sendMessage(
                            "截屏状态通知",
                            StatusReporter.buildScreenshotResultHtml(false, "截图完成，但无法获取截图"),
                            appendMeta = false
                        )
                    } else {
                        MessageDispatcher.sendAttachmentMessage(
                            "截屏状态通知",
                            StatusReporter.buildScreenshotResultHtml(true, "截图已发送，请查看附件"),
                            imagePath
                        )
                    }
                    } finally {
                        // 遥控截屏会话结束：无论正常完成还是异常，统一收起悬浮窗倒计时
                        FloatingWindowController.stopFloatSession()
                        val restoreMask = NotificationMonitorService.pendingScreenshotMaskRestore
                        val releaseWake = NotificationMonitorService.pendingScreenshotKeepAwakeRelease
                        NotificationMonitorService.pendingScreenshotMaskRestore = false
                        NotificationMonitorService.pendingScreenshotKeepAwakeRelease = false
                        if (restoreMask) {
                            MaskOverlayHelper.show(this@MainActivity)
                        }
                        if (releaseWake) {
                            IdlePseudoMaskController.releaseKeepAwakeForPunch(this@MainActivity)
                        }
                    }
                }
            }
        }
    }

    fun doStopTask() {
        if (!TaskScheduler.isRunning()) return
        TaskScheduler.stopTask()
        MessageDispatcher.sendMessage(
            "停止任务通知", StatusReporter.buildStopTaskHtml(), appendMeta = false
        )
    }

    fun backToMainActivity(isPunchReturn: Boolean = false) {
        switchToTaskTab()
        // 打卡返回行为：
        // - 伪息屏开启：回到桌面（App 转入后台，由后台伪息屏倒计时接管）
        // - 伪息屏关闭且开启「返回桌面」：先回桌面，再从桌面拉起本 App 正常界面（任务页）
        // - 伪息屏关闭且关闭「返回桌面」：不操作
        if (isPunchReturn) {
            if (AppRuntimeConfig.isForcePseudoMask()) {
                // 模拟点击Home键
                startActivity(Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) })
            } else if (SaveKeyValues.loadBoolean(Constant.BACK_TO_HOME_KEY, Constant.BACK_TO_HOME_DEFAULT)) {
                // 模拟点击Home键
                startActivity(Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) })
                // 等桌面切换稳定后再从桌面拉起本 App，避免直接从打卡软件跳回造成界面闪烁。
                // 注意：不可再判断 isInActivePunch()——打卡刚结束时 runningDetail 仍含「等待打卡」，
                // 会把本次回跳误拦掉，表现为倒计时结束后停在目标 App/桌面、不回被控端。
                mainHandler.postDelayed({
                    bringDailyTaskToFront()
                }, 800L)
            }
            return
        }
        if (SaveKeyValues.loadBoolean(Constant.BACK_TO_HOME_KEY, Constant.BACK_TO_HOME_DEFAULT)) {
            // 模拟点击Home键
            startActivity(Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) })
        }
    }

    /**
     * 开机自动调度兜底：开关已开且未在跑时，打开 App 补启（覆盖开机广播未落到调度的情况）。
     */
    private fun ensureBootAutoScheduleIfNeeded() {
        if (KeepAliveReceiver.isPaused()) return
        if (!SaveKeyValues.loadBoolean(Constant.BOOT_AUTO_SCHEDULE_KEY, false)) return
        if (TaskScheduler.isRunning()) return
        lifecycleScope.launch {
            kotlinx.coroutines.delay(1500)
            if (KeepAliveReceiver.isPaused() || TaskScheduler.isRunning()) return@launch
            val tasks = runCatching {
                withContext(Dispatchers.IO) {
                    com.pengxh.daily.app.sqlite.DatabaseWrapper.loadAllTask()
                }
            }.getOrElse { emptyList() }
            if (tasks.isEmpty()) {
                LogFileManager.action("开机自动调度兜底：任务列表为空，跳过")
                return@launch
            }
            KeepAliveReceiver.ensureFloatingWindow(this@MainActivity)
            LogFileManager.action("开机自动调度兜底：打开 App 补启调度，任务 ${tasks.size} 个")
            TaskScheduler.startTask()
        }
    }

    /**
     * 兜底检查：覆盖前台服务重启/进程被杀后，定时链未触发每日重置的场景
     */
    private fun checkMissedReset() {
        val lastResetDate = SaveKeyValues.loadString(Constant.LAST_RESET_DATE_KEY, "")
        val today = Date().format("yyyy-MM-dd")

        if (lastResetDate == today) {
            return
        }

        val resetHour = SaveKeyValues.loadInt(Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR)
        val nowHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        // 未到今日重置点：不抢先标记，留给精确闹钟 / TIME_TICK
        if (nowHour < resetHour) {
            LogFileManager.writeLog("今日尚未到重置点（${resetHour}点），跳过补重置")
            return
        }

        LogFileManager.writeLog("检测到今日尚未重置，执行重置操作")

        if (SaveKeyValues.loadBoolean(Constant.TASK_AUTO_RECYCLE_KEY, true)
            && !KeepAliveReceiver.isPaused()
        ) {
            // 仅在启动成功（或已在跑）后落库日期，避免 scope 未就绪导致整日漏调度
            if (TaskScheduler.isRunning()) {
                SaveKeyValues.saveString(Constant.LAST_RESET_DATE_KEY, today)
            } else {
                TaskScheduler.startTask()
                if (TaskScheduler.isRunning()) {
                    SaveKeyValues.saveString(Constant.LAST_RESET_DATE_KEY, today)
                } else {
                    LogFileManager.error("补重置启动任务失败（scope 可能未就绪），不标记今日已重置")
                }
            }
        } else {
            SaveKeyValues.saveString(Constant.LAST_RESET_DATE_KEY, today)
        }
    }

    /**
     * 启动自检：核心权限缺失时主动引导用户。
     * 引导顺序（串行，避免弹窗叠放）：通知权限 → 自启动权限 → 电池优化。
     */
    private fun runStartupSelfCheck() {
        if (!notificationEnable()) {
            "通知监听未开启，无法接收远程指令，请到设置页开启".show(this)
        }
        // 1) Android 13+ 通知权限（POST_NOTIFICATIONS）：冷启动引导授权，保证结果通知可正常弹出
        // （悬浮窗权限为硬门禁，已在 onResume 单独拦截，不进入本串行链）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !notificationPermissionPrompted &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionPrompted = true
            UnifiedDialogKit.showPermission(
                this,
                "开启通知权限",
                "开启通知权限后，任务执行结果、打卡提醒等消息才能正常显示。",
                grantText = "去开启",
                denyText = "暂不",
                cancelable = false,
                onGrant = {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            )
            return
        }
        // 2) 国产 ROM 自启动权限：冷启动引导，保证后台常驻/开机自启不被系统拦截
        if (!autostartPermissionPrompted && isAutostartGranted() == false) {
            autostartPermissionPrompted = true
            showAutostartGuide()
            return
        }
        // 3) 电池优化豁免（原有逻辑）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !batteryOptimizationPrompted) {
            val powerManager = getSystemService(PowerManager::class.java)
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                batteryOptimizationPrompted = true
                UnifiedDialogKit.showPermission(
                    this,
                    "建议关闭电池优化",
                    "本应用需长时间后台运行以监听打卡结果与远程指令。" +
                        "若被系统电池优化限制，锁屏后可能被杀掉导致指令失效。" +
                        "建议将本应用设为“不受电池优化限制”。",
                    grantText = "去设置",
                    denyText = "暂不",
                    cancelable = false
                ) {
                    try {
                        startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                .apply { data = Uri.parse("package:$packageName") }
                        )
                    } catch (_: Exception) {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                }
            }
        }
    }

    /** 自启动权限引导：按厂商给出去系统设置的路径 */
    private fun showAutostartGuide() {
        val guide = when {
            RomDetector.isMiui() ->
                "1. 设置 → 应用设置 → 应用管理 → 本应用 → 自启动，设为允许；\n" +
                    "2. 或到「安全中心 → 应用管理 → 权限」中允许自启动。"
            RomDetector.isHuawei() || RomDetector.isHonor() ->
                "1. 手机管家 → 应用启动管理，将本应用改为「手动管理」并允许自启动。"
            RomDetector.isVivo() ->
                "1. i管家 → 应用管理 → 权限管理 → 自启动，设为允许。"
            RomDetector.isOppo() ->
                "1. 设置 → 应用管理 → 本应用 → 自启动，设为允许。"
            else ->
                "1. 设置 → 应用 → 本应用 → 电池/后台运行，允许自启动。"
        }
        UnifiedDialogKit.showForm(
            ctx = this,
            contentView = TextView(this).apply {
                text = "本应用需常驻后台运行以定时打卡、接收远程指令。" +
                    "若未开启自启动，重启手机后应用可能不会自动拉起。\n\n" +
                    "请前往系统设置为本应用开启自启动：\n" + guide
                setPadding(24, 16, 24, 16)
                textSize = 14f
            },
            title = "建议开启自启动",
            positiveText = "前往设置",
            negativeText = "暂不",
            cancelable = false,
            onConfirm = {
                try {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                    )
                } catch (_: Exception) {
                    "无法打开系统设置".show(this)
                }
                true
            }
        )
    }

    /**
     * 使用须知弹窗：仅在「本版本尚未选择不再提醒」时弹出。
     */
    private fun maybeShowUsageNotice() {
        val ackVersion = SaveKeyValues.loadInt(USAGE_NOTICE_ACK_VERSION_KEY, 0)
        if (ackVersion == BuildConfig.VERSION_CODE) {
            return
        }
        UnifiedDialogKit.showConfirm(
            this,
            "使用须知",
            "本软件完全免费！仅供内部使用！严禁商用或者用作其他非法用途！\r\n" +
                "近期发现有人在咸鱼私自倒卖本软件，请勿购买！如有购买，请联系卖家退款！",
            confirmText = "知道了",
            cancelText = "不再提醒",
            cancelable = false,
            onCancel = {
                SaveKeyValues.saveInt(USAGE_NOTICE_ACK_VERSION_KEY, BuildConfig.VERSION_CODE)
            }
        )
    }

    /**
     * 悬浮窗权限启动器
     */
    private val overlayPermissionLauncher = registerForActivityResult(permissionContract) {
        if (Settings.canDrawOverlays(this) && !KeepAliveReceiver.isPaused()) {
            Intent(this, FloatingWindowService::class.java).apply {
                startService(this)
            }
        }
        // 授权返回后继续串行引导链（自启动/电池优化）
        runStartupSelfCheck()
    }

    /** Android 13+ 通知权限（POST_NOTIFICATIONS）运行时请求 */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                LogFileManager.writeLog("通知权限已授权")
            } else {
                LogFileManager.writeLog("用户拒绝了通知权限")
            }
        }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (maskViewController.isMaskVisible()) {
                maskViewController.hideMaskView()
            } else {
                maskViewController.showMaskView()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev?.actionMasked == MotionEvent.ACTION_DOWN) {
            resetIdleMaskTimer()
        }
        ev?.let {
            gestureController.onTouchEvent(it)
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun resetIdleMaskTimer() {
        if (maskViewController.isMaskVisible() || MaskOverlayHelper.isShowing()) {
            IdlePseudoMaskController.stopIdleMask()
            return
        }
        IdlePseudoMaskController.startIdleMask(this)
    }

    private fun stopIdleMaskTimer() {
        IdlePseudoMaskController.stopIdleMask()
    }

    /**
     * 按伪息屏总开关 + 屏幕模式设置 Activity KEEP_SCREEN_ON。
     * 伪息屏开 / 模式 0·2 → 常亮；伪息屏关且模式 1 → 允许系统自然灭屏。
     */
    private fun applyForegroundScreenFlags() {
        val keepOn = when {
            AppRuntimeConfig.isForcePseudoMask() -> true
            AppRuntimeConfig.getScreenMode() == Constant.SCREEN_MODE_OFF -> false
            else -> true
        }
        if (keepOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /** 旗标 + 前台无操作计时一并按当前配置收敛 */
    private fun applyForegroundScreenPolicy() {
        applyForegroundScreenFlags()
        if (maskViewController.isMaskVisible() || MaskOverlayHelper.isShowing()) {
            IdlePseudoMaskController.stopIdleMask()
        } else {
            resetIdleMaskTimer()
        }
    }
}
