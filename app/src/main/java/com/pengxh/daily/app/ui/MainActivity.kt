package com.pengxh.daily.app.ui

import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
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
import com.pengxh.daily.app.extensions.notificationEnable
import com.pengxh.daily.app.service.AutoProjectionAccessibilityService
import com.pengxh.daily.app.service.CaptureImageService
import com.pengxh.daily.app.service.FloatingWindowService
import com.pengxh.daily.app.service.ForegroundRunningService
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
import com.pengxh.daily.app.utils.StatusReporter
import com.pengxh.daily.app.utils.TaskScheduler
import com.pengxh.kt.lite.base.KotlinBaseActivity
import com.pengxh.kt.lite.extensions.dp2px
import com.pengxh.kt.lite.extensions.show
import com.pengxh.kt.lite.utils.SaveKeyValues
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    /** 三个 Tab 常驻 Fragment（一次性 add，hide/show 切换，与控制端交互一致） */
    private val taskFragment by lazy { TaskFragment() }
    private val remoteControlFragment by lazy { RemoteControlFragment() }
    private val settingsFragment by lazy { SettingsFragment() }
    private val allFragments: List<Fragment> by lazy {
        listOf(taskFragment, remoteControlFragment, settingsFragment)
    }
    private var currentTab = R.id.nav_task
    private var ignoreNavSelection = false

    /** 远程控制端修改设置/任务后，前台主界面即时刷新任务列表（无需二次进入） */
    private val remoteConfigReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == ConfigImportSignal.ACTION_REMOTE_CONFIG_CHANGED) {
                ConfigImportSignal.pendingMainActivityRefresh = false
                taskFragment.refreshTaskListFromDb()
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
        // 禁止系统自动息屏，保持常亮 + 伪息屏策略
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 磨砂玻璃悬浮导航：模糊其下方的全部 Tab 内容
        binding.bottomNavBar.root.setupWith(binding.rootView)
            .setBlurRadius(24f)
            .setOverlayColor(android.graphics.Color.TRANSPARENT)

        // 先同步 BottomNavigationView 选中状态（默认任务页），再设置监听器，避免递归
        binding.bottomNavBar.bottomNav.selectedItemId = R.id.nav_task

        // 一次性添加三个 Tab（隐藏非默认 Tab），后续切换只做 hide/show
        supportFragmentManager.beginTransaction()
            .add(R.id.fragmentContainer, taskFragment, TAG_TASK)
            .add(R.id.fragmentContainer, remoteControlFragment, TAG_REMOTE)
            .add(R.id.fragmentContainer, settingsFragment, TAG_SETTINGS)
            .hide(remoteControlFragment)
            .hide(settingsFragment)
            .commitNow()

        if (Settings.canDrawOverlays(this)) {
            Intent(this, FloatingWindowService::class.java).apply { startService(this) }
        } else {
            // 悬浮窗权限并显示悬浮窗
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            overlayPermissionLauncher.launch(intent)
        }

        // 前台服务（保活 + 托管 TaskScheduler 协程作用域 + 每日重置）
        Intent(this, ForegroundRunningService::class.java).apply { startForegroundService(this) }

        // 订阅通知监听事件（远程指令；单条异常不得取消整个订阅）
        lifecycleScope.launch(CoroutineExceptionHandler { _, e ->
            Log.e(kTag, "通知事件订阅协程异常", e)
            LogFileManager.writeLog("通知事件订阅协程异常: ${e.message}")
        }) {
            NotificationMonitorService.events
                .collect { event ->
                    try {
                        handleMonitorEvent(event)
                    } catch (e: Exception) {
                        Log.e(kTag, "处理通知事件失败，已跳过: $event", e)
                        LogFileManager.writeLog("处理通知事件失败: ${e.message}")
                    }
                }
        }

        // 订阅超时回主页信号（宿主负责，与具体 Tab 无关）
        lifecycleScope.launch {
            TaskScheduler.returnToApp.collectLatest {
                backToMainActivity()
            }
        }

        // 省电模式热更新：由 TaskFragment 调整任务页时钟刷新频率

        // 兜底检查是否有错过的每日重置
        checkMissedReset()

        // 首次启动（含覆盖安装/清除数据/卸载重装）弹出使用须知
        binding.rootView.post { maybeShowUsageNotice() }

        // 处理外部拉起指定 Tab（如 MQTT 通知点击进入「远程」）
        applyTabFromIntent(intent)
        ignoreNavSelection = true
        binding.bottomNavBar.bottomNav.selectedItemId = R.id.nav_task
        ignoreNavSelection = false
    }

    override fun initEvent() {
        setupBottomNav()
    }

    private fun setupBottomNav() {
        binding.bottomNavBar.bottomNav.setOnItemSelectedListener { item ->
            if (!ignoreNavSelection) switchTab(item.itemId)
            true
        }
    }

    /**
     * 单 Activity 内 Fragment 切换：hide/show + MaterialFadeThrough 交叉淡入（与控制端一致）。
     */
    private fun switchTab(itemId: Int) {
        if (itemId == currentTab) return
        val target = when (itemId) {
            R.id.nav_task -> taskFragment
            R.id.nav_remote -> remoteControlFragment
            R.id.nav_settings -> settingsFragment
            else -> return
        }
        currentTab = itemId
        ignoreNavSelection = true
        binding.bottomNavBar.bottomNav.selectedItemId = itemId
        ignoreNavSelection = false
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

    /** 供 Fragment 回切到「任务」Tab（如远程页工具栏返回按钮） */
    fun switchToTaskTab() {
        switchTab(R.id.nav_task)
    }

    private fun applyTabFromIntent(intent: Intent?) {
        val tab = intent?.getStringExtra(EXTRA_TAB) ?: return
        intent.removeExtra(EXTRA_TAB)
        when (tab) {
            TAB_REMOTE -> switchTab(R.id.nav_remote)
            TAB_SETTINGS -> switchTab(R.id.nav_settings)
            else -> switchTab(R.id.nav_task)
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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyMaskCommandFromIntent(intent)
        // 打卡动作完成返回本 App 时，立即进入伪息屏（若未因 Intent 指令显式控制蒙层）
        maybeShowPunchReturnMask()
        if (MaskOverlayHelper.isShowing() && !maskViewController.isMaskVisible()) {
            maskViewController.showMaskView()
        }
        if (!maskViewController.isMaskVisible() && !MaskOverlayHelper.isShowing()) {
            resetIdleMaskTimer()
        }
        if (!Settings.canDrawOverlays(this)) {
            "悬浮窗权限未开启，部分功能可能无法正常使用".show(this)
        }
        runStartupSelfCheck()
    }

    override fun onPause() {
        runCatching { unregisterReceiver(remoteConfigReceiver) }
        stopIdleMaskTimer()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        LogFileManager.writeLog("onNewIntent: ${packageName} 回到前台")

        if (ProjectionSession.isStateActive()) {
            LogFileManager.writeLog("截屏服务正常：MediaProjection 有效")
        } else {
            LogFileManager.writeLog("截屏服务异常：MediaProjection 已失效")
            if (SaveKeyValues.loadInt(Constant.RESULT_SOURCE_KEY, Constant.DEFAULT_INDEX) == 1) {
                "截屏服务已断开，请重新授权".show(this)
                SaveKeyValues.saveInt(Constant.RESULT_SOURCE_KEY, 0)
            }
        }

        if (!applyMaskCommandFromIntent(intent)) {
            if (IdlePseudoMaskController.wasAppInBackground()) {
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
        intent?.removeExtra(Constant.EXTRA_MASK_COMMAND)
        when (action) {
            1 -> {
                MaskOverlayHelper.show(this)
                if (!maskViewController.isMaskVisible()) {
                    maskViewController.showMaskView()
                }
            }

            0 -> {
                MaskOverlayHelper.hide(this)
                if (maskViewController.isMaskVisible()) {
                    maskViewController.hideMaskView()
                }
            }
        }
        return true
    }

    override fun onBackPressed() {
        // 单 Activity 无页面栈，返回键统一最小化应用（与原「返回即退出」语义一致）
        moveTaskToBack(true)
    }

    override fun onDestroy() {
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
                backToMainActivity(true)
            }

            is MonitorEvent.StartTaskCommand -> {
                if (!TaskScheduler.isRunning()) {
                    TaskScheduler.startTask()
                }
                MqttAgentService.pushTaskIncrement() // 任务调度启动 → 推送状态变化
            }

            is MonitorEvent.StopTaskCommand -> {
                doStopTask()
                MqttAgentService.pushTaskIncrement() // 任务调度停止 → 推送状态变化
            }

            is MonitorEvent.ShowMaskCommand -> {
                MaskOverlayHelper.show(this)
                if (!maskViewController.isMaskVisible()) {
                    maskViewController.showMaskView()
                }
            }

            is MonitorEvent.HideMaskCommand -> {
                MaskOverlayHelper.hide(this)
                if (maskViewController.isMaskVisible()) {
                    maskViewController.hideMaskView()
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
        // 仅「打卡动作完成」引发的返回才标记「返回即息屏」
        if (isPunchReturn) {
            IdlePseudoMaskController.requestPunchReturnMask()
        }
        switchToTaskTab()
        if (SaveKeyValues.loadBoolean(Constant.BACK_TO_HOME_KEY, false)) {
            // 模拟点击Home键
            startActivity(Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) })
        }
    }

    /**
     * 消费「打卡返回即息屏」请求：打卡动作完成后 DailyTask 回到前台时立即进入伪息屏。
     */
    private fun maybeShowPunchReturnMask() {
        if (!IdlePseudoMaskController.consumePunchReturnMask()) return
        if (!AppRuntimeConfig.isForcePseudoMask()) return
        if (TaskScheduler.isInActivePunch()) return
        if (MaskOverlayHelper.isShowing() || maskViewController.isMaskVisible()) return
        LogFileManager.writeLog("打卡动作完成返回本 App，立即进入伪息屏")
        IdlePseudoMaskController.releaseKeepAwakeForPunch(this)
        maskViewController.showMaskView()
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

        LogFileManager.writeLog("检测到今日尚未重置，执行重置操作")
        SaveKeyValues.saveString(Constant.LAST_RESET_DATE_KEY, today)

        if (SaveKeyValues.loadBoolean(Constant.TASK_AUTO_RECYCLE_KEY, true)) {
            TaskScheduler.startTask()
        }
    }

    /**
     * 启动自检：核心权限缺失时主动引导用户。
     */
    private fun runStartupSelfCheck() {
        if (!notificationEnable()) {
            "通知监听未开启，无法接收远程指令，请到设置页开启".show(this)
        }
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

    /**
     * 使用须知弹窗：仅在「本版本尚未选择不再提醒」时弹出。
     */
    private fun maybeShowUsageNotice() {
        val ackVersion = SaveKeyValues.loadInt(USAGE_NOTICE_ACK_VERSION_KEY, 0)
        if (ackVersion == BuildConfig.VERSION_CODE) {
            return
        }
        UnifiedDialogKit.showSuccess(
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
        if (Settings.canDrawOverlays(this)) {
            Intent(this, FloatingWindowService::class.java).apply {
                startService(this)
            }
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
}
