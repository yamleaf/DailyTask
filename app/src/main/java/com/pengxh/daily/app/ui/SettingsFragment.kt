package com.pengxh.daily.app.ui

import android.app.Activity
import android.app.AppOpsManager
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.pengxh.daily.app.BuildConfig
import com.pengxh.daily.app.DailyTaskApplication
import com.pengxh.daily.app.R
import com.pengxh.daily.app.databinding.DialogSliderBinding
import com.pengxh.daily.app.databinding.FragmentSettingsBinding
import com.pengxh.daily.app.extensions.isApplicationExist
import com.pengxh.daily.app.extensions.isAutostartGranted
import com.pengxh.daily.app.extensions.notificationEnable
import com.pengxh.daily.app.service.AutoProjectionAccessibilityService
import com.pengxh.daily.app.service.CaptureImageService
import com.pengxh.daily.app.service.FloatingWindowService
import com.pengxh.daily.app.service.KeepAliveReceiver
import com.pengxh.daily.app.service.NotificationMonitorService
import com.pengxh.daily.app.utils.AppRuntimeConfig
import com.pengxh.daily.app.utils.ChinaHolidayManager
import com.pengxh.daily.app.utils.ConfigImportSignal
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.DailyTask
import com.pengxh.daily.app.utils.DiagnosticReporter
import com.pengxh.daily.app.utils.FloatingWindowController
import com.pengxh.daily.app.utils.LogFileManager
import com.pengxh.daily.app.utils.MessageDispatcher
import com.pengxh.daily.app.utils.ProjectionEvent
import com.pengxh.daily.app.utils.ProjectionSession
import com.pengxh.daily.app.utils.RomDetector
import com.pengxh.daily.app.utils.StatusReporter
import com.pengxh.daily.app.utils.WatermarkDrawable
import com.pengxh.kt.lite.base.KotlinBaseFragment
import com.pengxh.kt.lite.extensions.convertColor
import com.pengxh.kt.lite.extensions.show
import com.pengxh.kt.lite.utils.LoadingDialog
import com.pengxh.kt.lite.utils.SaveKeyValues
import com.pengxh.kt.lite.widget.dialog.BottomActionSheet
import com.yample.mqttprotocol.ThemeManager
import com.pengxh.daily.app.utils.DialogCardBuilder
import com.yample.mqttprotocol.dialog.UnifiedDialogKit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置 Tab：外观 / 打卡设置 / 系统权限 / 伪息屏 / 高级功能 / 关于。
 */
class SettingsFragment : KotlinBaseFragment<FragmentSettingsBinding>() {

    private val kTag = "SettingsFragment"

    private val ctx by lazy { requireContext() }
    private val theme by lazy { ctx.theme }

    private val builtInIcons by lazy {
        listOf(
            R.drawable.ic_ding_ding,
            R.drawable.ic_wei_xin,
            R.drawable.ic_fei_shu,
            R.mipmap.ic_mobile_m3
        )
    }

    private val channels = arrayListOf("QQ邮箱", "企业微信")
    private val resultSources = arrayListOf("通知", "截屏", "无障碍")
    private val feedbackModes = arrayListOf("截屏反馈", "文本反馈")

    private val permissionContract by lazy { ActivityResultContracts.StartActivityForResult() }
    private val notificationContract by lazy { ActivityResultContracts.StartActivityForResult() }
    private val projectionContract by lazy { ActivityResultContracts.StartActivityForResult() }
    private val mpr by lazy { ctx.getSystemService(MediaProjectionManager::class.java) }

    /** 远程配置变更 → 刷新设置 UI */
    private val remoteConfigReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ConfigImportSignal.ACTION_REMOTE_CONFIG_CHANGED) {
                ConfigImportSignal.pendingSettingsRefresh = false
                syncSettingsUiFromStore()
                applyTargetAppIcon()
            }
        }
    }

    private val overlayPermissionLauncher =
        registerForActivityResult(permissionContract) {
            // 「暂停使用」开启时不拉起悬浮窗服务，避免授权后意外恢复
            if (Settings.canDrawOverlays(ctx) && !KeepAliveReceiver.isPaused()) {
                ctx.startService(Intent(ctx, FloatingWindowService::class.java))
            }
        }
    private val notificationSettingLauncher =
        registerForActivityResult(notificationContract) {
            if (ctx.notificationEnable()) turnOnNotificationMonitorService()
        }
    private val projectionLauncher =
        registerForActivityResult(projectionContract) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data = result.data
                if (data != null) {
                    if (!ProjectionSession.isStateActive()) {
                        val intent = Intent(ctx, CaptureImageService::class.java)
                            .putExtra("resultCode", result.resultCode)
                            .putExtra("data", data)
                        ctx.startForegroundService(intent)
                    } else {
                        Log.d(kTag, "MediaProjection already active, skipping creation")
                    }
                    // 授权成功：立即同步开关与提示，避免 UI 显示"未开启"导致用户重复点击误关闭服务
                    binding.captureSwitch.isChecked = true
                    binding.captureTipsView.visibility = View.GONE
                } else {
                    "授权失败".show(ctx)
                }
            } else {
                "用户拒绝授权".show(ctx)
            }
        }

    /** 避免代码同步开关状态时误触发监听器 */
    private var syncingSwitchState = false

    private var customAppDialog: Dialog? = null

    override fun initViewBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentSettingsBinding =
        FragmentSettingsBinding.inflate(inflater, container, false)

    override fun setupTopBarLayout() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { view, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.setPadding(0, statusBarHeight, 0, 0)
            insets
        }
        binding.toolbar.setNavigationOnClickListener {
            (activity as? MainActivity)?.switchToTaskTab()
        }
    }

    override fun initOnCreate(savedInstanceState: Bundle?) {
        applyTargetAppIcon()
        binding.appVersion.text = BuildConfig.GIT_SHA
        binding.versionRow.setOnClickListener { showVersionInfo() }
        if (ctx.notificationEnable()) {
            turnOnNotificationMonitorService()
        }
        binding.contentView.background = WatermarkDrawable(ctx, DailyTask.getWatermarkText())

        // 节假日同步结果 → 关闭加载框
        lifecycleScope.launch {
            ChinaHolidayManager.syncResult.collect { result ->
                LoadingDialog.dismiss()
                when (result) {
                    is ChinaHolidayManager.SyncResult.Success ->
                        "节假日数据已更新".show(ctx)

                    is ChinaHolidayManager.SyncResult.Error ->
                        "节假日数据更新失败：${result.message}".show(ctx)
                }
            }
        }
        // 通知监听服务状态 → 刷新提示
        lifecycleScope.launch {
            NotificationMonitorService.listenerState.collect { connected ->
                if (!connected) {
                    binding.noticeTipsView.text = "服务未开启，无法监听打卡结果和接收远程指令"
                    binding.noticeTipsView.setTextColor(android.graphics.Color.RED)
                    binding.noticeSwitch.isChecked = false
                    binding.noticeTipsView.visibility = View.VISIBLE
                }
            }
        }
        // 截屏服务事件 → Ready 同步开关打开（服务真正初始化完成后，避免授权回调过早被 refreshUi 覆盖）；
        //                   Failed 时回切通知源并同步开关关闭
        lifecycleScope.launch {
            CaptureImageService.projectionEvents.collect { event ->
                when (event) {
                    is ProjectionEvent.Ready -> {
                        binding.captureSwitch.isChecked = true
                        binding.captureTipsView.visibility = View.GONE
                    }

                    is ProjectionEvent.Failed -> {
                        binding.captureSwitch.isChecked = false
                        binding.captureTipsView.visibility = View.VISIBLE
                        binding.captureTipsView.text = "截屏服务未开启，无法获取打卡结果"
                        binding.captureTipsView.setTextColor(android.graphics.Color.RED)
                        if (SaveKeyValues.loadInt(Constant.RESULT_SOURCE_KEY, -1) == 1) {
                            SaveKeyValues.saveInt(Constant.RESULT_SOURCE_KEY, 0)
                            updateResultSourceView()
                            "截屏服务启动失败，已回切通知模式".show(ctx)
                        }
                    }
                }
            }
        }
    }

    override fun observeRequestState() {
    }

    override fun initEvent() {
        // 提前置位：本 Fragment 经 recreate（如切换主题）重建时，视图系统会在 onViewCreated 之后、
        // onResume 之前「恢复」各 Switch 的勾选状态（默认值与已存值不同时会触发监听器）。
        // 此时 syncSettingsUiFromStore 尚未执行、syncingSwitchState 仍为 false，会导致恢复动作
        // 被误判为用户操作而弹「开启手势识别？」等确认框。这里先置 true，覆盖重建后的状态恢复窗口，
        // 待 onResume → refreshUi → syncSettingsUiFromStore 在 finally 中复位为 false。
        syncingSwitchState = true
        // ── 目标应用 ──
        binding.targetAppLayout.setOnClickListener {
            BottomActionSheet.Builder()
                .setContext(ctx)
                .setActionItemTitle(targetAppLabels())
                .setItemTextColor(R.color.theme_color.convertColor(ctx))
                .setOnActionSheetListener(object : BottomActionSheet.OnActionSheetListener {
                    override fun onActionItemClick(position: Int) {
                        val builtInCount = Constant.getBuiltInTargets().size
                        if (position == builtInCount) {
                            showCustomAppManagerDialog()
                            return
                        }
                        if (Constant.getTargetAppPosition() != position) {
                            if (position == 0) {
                                if (!binding.noticeSwitch.isChecked &&
                                    !binding.captureSwitch.isChecked &&
                                    !binding.accessibilitySwitch.isChecked
                                ) {
                                    "请先打开通知监听、截屏服务或无障碍服务".show(ctx)
                                    return
                                }
                                SaveKeyValues.saveInt(Constant.RESULT_SOURCE_KEY, 0)
                            } else {
                                // 飞书/企业微信/M3 不再强制要求截屏或无障碍服务；
                                // 仅在已开启对应能力时设置结果来源，否则保留现有配置。
                                val source = when {
                                    binding.captureSwitch.isChecked -> 1
                                    binding.accessibilitySwitch.isChecked -> 2
                                    else -> SaveKeyValues.loadInt(Constant.RESULT_SOURCE_KEY, 0)
                                }
                                SaveKeyValues.saveInt(Constant.RESULT_SOURCE_KEY, source)
                            }
                            Constant.getTargetAppPackageByPosition(position)?.let { pkg ->
                                SaveKeyValues.saveInt(Constant.TARGET_APP_KEY, position)
                                SaveKeyValues.saveString(Constant.CUSTOM_TARGET_SELECTED_KEY, pkg)
                            }
                            applyTargetAppIcon()
                            ConfigImportSignal.notifyRemoteChanged(ctx)
                        }
                    }
                })
                .build()
                .show()
        }
        binding.msgChannelLayout.setOnClickListener {
            ctx.startActivity(Intent(ctx, MessageChannelActivity::class.java))
        }
        binding.resultSourceLayout.setOnClickListener {
            BottomActionSheet.Builder()
                .setContext(ctx)
                .setActionItemTitle(resultSources)
                .setItemTextColor(R.color.theme_color.convertColor(ctx))
                .setOnActionSheetListener(object : BottomActionSheet.OnActionSheetListener {
                    override fun onActionItemClick(position: Int) {
                        when (position) {
                            0 -> {
                                if (SaveKeyValues.loadInt(Constant.TARGET_APP_KEY, 0) == 0) {
                                    if (binding.noticeSwitch.isChecked) {
                                        SaveKeyValues.saveInt(Constant.RESULT_SOURCE_KEY, 0)
                                        updateResultSourceView()
                                        binding.accessibilityFeedbackLayout.visibility = View.GONE
                                        binding.accessibilityFeedbackDivider.visibility = View.GONE
                                    } else {
                                        "请先打开通知监听".show(ctx)
                                    }
                                } else {
                                    "通知监听仅支持钉钉打卡".show(ctx)
                                }
                            }

                            1 -> {
                                if (binding.captureSwitch.isChecked) {
                                    SaveKeyValues.saveInt(Constant.RESULT_SOURCE_KEY, 1)
                                    updateResultSourceView()
                                    binding.accessibilityFeedbackLayout.visibility = View.GONE
                                    binding.accessibilityFeedbackDivider.visibility = View.GONE
                                } else {
                                    "请先打开截屏服务".show(ctx)
                                }
                            }

                            2 -> {
                                if (binding.accessibilitySwitch.isChecked) {
                                    SaveKeyValues.saveInt(Constant.RESULT_SOURCE_KEY, 2)
                                    updateResultSourceView()
                                    binding.accessibilityFeedbackLayout.visibility = View.VISIBLE
                                    binding.accessibilityFeedbackDivider.visibility = View.VISIBLE
                                } else {
                                    "请先打开无障碍服务".show(ctx)
                                }
                            }
                        }
                    }
                })
                .build()
                .show()
        }
        binding.accessibilityFeedbackLayout.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 34) {
                BottomActionSheet.Builder()
                    .setContext(ctx)
                    .setActionItemTitle(feedbackModes)
                    .setItemTextColor(R.color.theme_color.convertColor(ctx))
                    .setOnActionSheetListener(object : BottomActionSheet.OnActionSheetListener {
                        override fun onActionItemClick(position: Int) {
                            SaveKeyValues.saveInt(Constant.ACCESSIBILITY_FEEDBACK_MODE_KEY, position)
                            updateAccessibilityFeedbackView()
                        }
                    })
                    .build()
                    .show()
            } else {
                SaveKeyValues.saveInt(Constant.ACCESSIBILITY_FEEDBACK_MODE_KEY, 1)
                "当前系统版本过低（需 Android 14+），不支持无障碍截屏，已切换为文本反馈".show(ctx)
                updateAccessibilityFeedbackView()
            }
        }
        binding.taskConfigLayout.setOnClickListener {
            ctx.startActivity(Intent(ctx, TaskConfigActivity::class.java))
        }
        binding.updateHolidayLayout.setOnClickListener {
            LoadingDialog.show(requireActivity(), "更新中，请稍后...")
            ChinaHolidayManager.updateChinaHolidayData()
        }
        binding.floatingSwitch.setOnClickListener {
            if (!Settings.canDrawOverlays(ctx)) {
                overlayPermissionLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            } else {
                "核心服务，无法关闭".show(ctx)
                binding.floatingSwitch.isChecked = true
            }
        }
        binding.noticeSwitch.setOnClickListener {
            if (!ctx.notificationEnable()) {
                notificationSettingLauncher.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } else {
                "核心服务，无法关闭".show(ctx)
                binding.noticeSwitch.isChecked = true
            }
        }
        binding.captureSwitch.setOnClickListener {
            if (!ProjectionSession.isStateActive()) {
                binding.captureSwitch.isChecked = false
                showCaptureEnableWarning {
                    projectionLauncher.launch(mpr.createScreenCaptureIntent())
                }
            } else {
                ctx.stopService(Intent(ctx, CaptureImageService::class.java))
                ProjectionSession.clear()
                SaveKeyValues.saveInt(Constant.RESULT_SOURCE_KEY, 0)
                binding.captureSwitch.isChecked = false
                binding.captureTipsView.text = "截屏服务未开启，无法获取打卡结果"
                binding.captureTipsView.setTextColor(android.graphics.Color.RED)
                binding.captureTipsView.visibility = View.VISIBLE
                updateResultSourceView()
                ConfigImportSignal.notifyRemoteChanged(ctx)
                "截屏服务已关闭".show(ctx)
            }
        }
        binding.accessibilitySwitch.setOnClickListener {
            val enabled = AutoProjectionAccessibilityService.isEnabled(ctx)
            binding.accessibilitySwitch.isChecked = enabled
            if (enabled) {
                ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } else {
                showAccessibilityEnableWarning()
            }
        }
        binding.commandLayout.setOnClickListener {
            ctx.startActivity(Intent(ctx, CommandActivity::class.java))
        }
        binding.downloadRow.setOnClickListener {
            val url = BuildConfig.CTRL_DOWNLOAD_URL.trim()
            if (url.isEmpty()) {
                UnifiedDialogKit.showInfo(
                    ctx,
                    "获取控制端 DailyController",
                    "当前未配置控制端下载地址。控制端安装包由分发方通过构建参数注入，请向提供者获取安装方式。"
                )
            } else {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        }
        binding.themeValueView.text = ThemeManager.labelOf(ThemeManager.getMode(ctx))
        binding.themeRow.setOnClickListener {
            val current = ThemeManager.getMode(ctx)
            UnifiedDialogKit.showSingleChoice(
                ctx,
                "主题外观",
                ThemeManager.LABELS.toList(),
                current
            ) { which ->
                if (which != current) {
                    ThemeManager.setMode(ctx, which)
                    requireActivity().recreate()
                }
            }
        }
        binding.transferSwitch.setOnCheckedChangeListener { _, checked ->
            if (syncingSwitchState) return@setOnCheckedChangeListener
            SaveKeyValues.saveBoolean(Constant.NOTIFICATION_TRANSFER_KEY, checked)
            if (checked) {
                when (SaveKeyValues.loadInt(Constant.MSG_CHANNEL_KEY, -1)) {
                    0 -> {
                        val cfg = com.pengxh.daily.app.utils.ConfigStore.get().load("emailConfig")
                        val inbox = cfg.get("inbox")?.asString ?: ""
                        if (inbox.isBlank() || com.pengxh.daily.app.utils.EmailSecureConfig.loadAuthCode().isBlank()) {
                            "通知转发依赖邮箱配置，请先完善邮箱与授权码".show(ctx)
                        }
                    }

                    1 -> {
                        if (SaveKeyValues.loadString(Constant.WX_WEB_HOOK_KEY, "").isBlank()) {
                            "通知转发依赖企业微信配置，请先填写企业微信 Webhook".show(ctx)
                        }
                    }

                    else -> "请先在设置中配置消息渠道（邮箱/企业微信）".show(ctx)
                }
            }
            ConfigImportSignal.notifyRemoteChanged(ctx)
        }
        binding.setupCheckRow.setOnClickListener { showSetupSelfCheck() }
        // 电量预警分组：分组开关仅控制子项展开/收起，功能开关保留在子项内
        val applyBatteryGroupExpand = { expanded: Boolean ->
            binding.batteryAlertGroupContent.visibility = if (expanded) View.VISIBLE else View.GONE
            binding.batteryAlertGroupArrow.animate()
                .rotation(if (expanded) 180f else 0f)
                .setDuration(200)
                .start()
            SaveKeyValues.saveBoolean(Constant.BATTERY_ALERT_GROUP_EXPANDED_KEY, expanded)
        }
        binding.batteryAlertGroupSwitch.setOnCheckedChangeListener { _, checked ->
            if (syncingSwitchState) return@setOnCheckedChangeListener
            applyBatteryGroupExpand(checked)
        }
        binding.batteryAlertGroupHeader.setOnClickListener {
            val expand = !binding.batteryAlertGroupSwitch.isChecked
            syncingSwitchState = true
            binding.batteryAlertGroupSwitch.isChecked = expand
            syncingSwitchState = false
            applyBatteryGroupExpand(expand)
        }
        binding.batterySmartAlertSwitch.setOnCheckedChangeListener { _, checked ->
            if (syncingSwitchState) return@setOnCheckedChangeListener
            SaveKeyValues.saveBoolean(Constant.BATTERY_SMART_ALERT_ENABLED_KEY, checked)
            com.pengxh.daily.app.service.KeepAliveReceiver.scheduleBatteryAlert(ctx)
        }
        binding.logRecordSwitch.setOnCheckedChangeListener { _, checked ->
            if (syncingSwitchState) return@setOnCheckedChangeListener
            val wasEnabled = SaveKeyValues.loadBoolean(Constant.LOG_ENABLED_KEY, true)
            SaveKeyValues.saveBoolean(Constant.LOG_ENABLED_KEY, checked)
            // 运行日志关闭→再开启：允许无障碍关键字节点再 dump 一次
            if (!wasEnabled && checked) {
                AutoProjectionAccessibilityService.resetNodeDumpFlag()
            }
        }
        binding.batteryWarningTimeRow.setOnClickListener { showBatteryWarningTimePicker() }
        binding.batteryAlertRangeRow.setOnClickListener { showBatteryAlertRangePicker() }
        binding.batteryThresholdRow.setOnClickListener { showBatteryThresholdPicker() }
        binding.batteryStageCountRow.setOnClickListener { showBatteryStageCountPicker() }
        binding.captureTestLayout.setOnClickListener {
            val source = SaveKeyValues.loadInt(Constant.RESULT_SOURCE_KEY, -1)
            lifecycleScope.launch {
                // 按结果来源选择截图通道：无障碍(2) 优先；否则截屏服务(1) 或兜底
                val imagePath: String? = if (source == 2) {
                    if (AutoProjectionAccessibilityService.canTakeScreenshot(ctx)) {
                        AutoProjectionAccessibilityService.requestScreenshot()?.await()
                    } else {
                        "无障碍服务未开启或系统版本过低（需 Android 14+），无法截图".show(ctx)
                        null
                    }
                } else {
                    if (ProjectionSession.isStateActive() || AutoProjectionAccessibilityService.canTakeScreenshot(ctx)) {
                        CaptureImageService.requestCaptureScreen().await()
                    } else {
                        "截屏服务未开启且无障碍截屏不可用，请检查设置".show(ctx)
                        null
                    }
                }
                if (imagePath.isNullOrEmpty()) {
                    "截图失败，请检查服务状态".show(ctx)
                    return@launch
                }
                LoadingDialog.show(requireActivity(), "消息发送中，请稍后...")
                MessageDispatcher.sendAttachmentMessage(
                    "邮箱测试",
                    StatusReporter.buildTestEmailHtml(),
                    imagePath,
                    onSuccess = {
                        LoadingDialog.dismiss()
                        "发送成功，请注意查收".show(ctx)
                    },
                    onFailure = { msg ->
                        LoadingDialog.dismiss()
                        "发送失败：$msg".show(ctx)
                    }
                )
            }
        }
        binding.statusQueryLayout.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val report = StatusReporter.buildStatusReport(
                        ctx,
                        NotificationMonitorService.isListenerConnected()
                    )
                    withContext(Dispatchers.Main) { showStatusReportDialog(report) }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Snackbar.make(binding.root, "状态查询生成失败：${e.message}", Snackbar.LENGTH_SHORT).show()
                    }
                }
            }
        }
        binding.gestureDetectSwitch.setOnCheckedChangeListener { _, checked ->
            if (syncingSwitchState) return@setOnCheckedChangeListener
            if (!checked) {
                SaveKeyValues.saveBoolean(Constant.GESTURE_DETECTOR_KEY, false)
                ConfigImportSignal.notifyRemoteChanged(ctx)
            } else {
                DialogCardBuilder.show(
                    ctx,
                    "开启手势识别？",
                    DialogCardBuilder.CardSpec(
                        paragraphs = listOf(
                            "开启后，在屏幕上滑动即可控制伪息屏：\n· 双指下滑：开启伪息屏\n· 单指 / 双指上滑：关闭伪息屏\n"
                        ),
                        notice = "单指下滑不受影响，可正常操作本软件。" to DialogCardBuilder.NoticeKind.WARN
                    ),
                    positiveText = "确认开启",
                    cancelable = false,
                    onCancel = {
                        syncingSwitchState = true
                        binding.gestureDetectSwitch.isChecked = false
                        syncingSwitchState = false
                    },
                    onConfirm = {
                        SaveKeyValues.saveBoolean(Constant.GESTURE_DETECTOR_KEY, true)
                        ConfigImportSignal.notifyRemoteChanged(ctx)
                    }
                )
            }
        }
        binding.backToHomeSwitch.setOnCheckedChangeListener { _, checked ->
            if (syncingSwitchState) return@setOnCheckedChangeListener
            SaveKeyValues.saveBoolean(Constant.BACK_TO_HOME_KEY, checked)
            ConfigImportSignal.notifyRemoteChanged(ctx)
        }
        binding.keepAliveSwitch.setOnCheckedChangeListener { _, checked ->
            if (syncingSwitchState) return@setOnCheckedChangeListener
            // 反转语义：开关 = 「暂停使用」。checked=true 表示暂停，checked=false 表示恢复。
            if (checked) {
                // 开启暂停：双层确认弹窗，防止误操作。
                // 第一层说明后果；用户点「确认暂停」后再弹第二层，再次确认后才固化暂停状态。
                DialogCardBuilder.show(
                    ctx,
                    getString(R.string.settings_keep_alive_confirm_title),
                    DialogCardBuilder.CardSpec(
                        paragraphs = listOf(getString(R.string.settings_keep_alive_confirm_tip)),
                        notice = "暂停期间不会执行打卡、远程指令与保活，请确认已无需本软件运行。" to DialogCardBuilder.NoticeKind.WARN
                    ),
                    positiveText = "确认暂停",
                    cancelable = false,
                    onCancel = {
                        syncingSwitchState = true
                        binding.keepAliveSwitch.isChecked = false
                        syncingSwitchState = false
                    },
                    onConfirm = {
                        // 第二层确认：再次询问是否真的确定暂停，而不是误操作
                        DialogCardBuilder.show(
                            ctx,
                            getString(R.string.settings_keep_alive_confirm_second_title),
                            DialogCardBuilder.CardSpec(
                                paragraphs = listOf(getString(R.string.settings_keep_alive_confirm_second_tip))
                            ),
                            positiveText = "确定暂停",
                            cancelable = false,
                            onCancel = {
                                syncingSwitchState = true
                                binding.keepAliveSwitch.isChecked = false
                                syncingSwitchState = false
                            },
                            onConfirm = {
                                SaveKeyValues.saveBoolean(Constant.KEEP_ALIVE_ENABLED_KEY, false)
                                KeepAliveReceiver.pauseAllServices(ctx)
                                "已暂停使用".show(ctx)
                                ConfigImportSignal.notifyRemoteChanged(ctx)
                            }
                        )
                    }
                )
            } else {
                // 关闭暂停：恢复所有服务
                SaveKeyValues.saveBoolean(Constant.KEEP_ALIVE_ENABLED_KEY, true)
                KeepAliveReceiver.resumeAllServices(ctx)
                "已恢复使用，服务与闹钟已重新启动".show(ctx)
                ConfigImportSignal.notifyRemoteChanged(ctx)
            }
        }
        binding.desktopPetSwitch.setOnCheckedChangeListener { _, checked ->
            if (syncingSwitchState) return@setOnCheckedChangeListener
            AppRuntimeConfig.setDesktopPetEnabled(checked)
        }
        binding.powerSaveSwitch.setOnCheckedChangeListener { _, checked ->
            if (syncingSwitchState) return@setOnCheckedChangeListener
            AppRuntimeConfig.setPowerSaveMode(checked)
            ConfigImportSignal.notifyRemoteChanged(ctx)
        }
        binding.bootAutoScheduleSwitch.setOnCheckedChangeListener { _, checked ->
            if (syncingSwitchState) return@setOnCheckedChangeListener
            // commit 同步落盘，避免刚开开关就重启时 apply 尚未写入
            SaveKeyValues.saveBoolean(Constant.BOOT_AUTO_SCHEDULE_KEY, checked, commit = true)
            LogFileManager.action(
                if (checked) "已开启开机自动调度" else "已关闭开机自动调度"
            )
            if (checked) {
                "已开启开机自动调度：重启后若有任务将自动启动调度".show(requireContext())
            }
        }
        // 强制伪息屏功能开关（子项内）：确认弹窗 + 应用状态
        val applyForceMask = { enabled: Boolean ->
            AppRuntimeConfig.setForcePseudoMask(enabled)
            if (enabled) "伪熄屏已开启".show(ctx)
            syncingSwitchState = true
            binding.forcePseudoMaskSwitch.isChecked = enabled
            syncingSwitchState = false
            refreshScreenModeRow()
            ConfigImportSignal.notifyRemoteChanged(ctx)
        }
        val confirmForceMask = { enabled: Boolean, onConfirm: (Boolean) -> Unit ->
            if (!enabled) {
                onConfirm(false)
            } else {
                DialogCardBuilder.show(
                    ctx,
                    "开启伪息屏？",
                    DialogCardBuilder.CardSpec(
                        paragraphs = listOf(
                            "后台运行或前台无操作超过设定时间，将自动进入伪息屏。",
                            "手势识别已开启，可随时退出：\n· 进入伪息屏：双指下滑\n· 退出伪息屏：单指 / 双指上滑\n"
                        ),
                        notice = "后台伪息屏可能打断其他 App 使用，适合无人值守挂机；白天操作手机时建议关闭。" to DialogCardBuilder.NoticeKind.WARN
                    ),
                    positiveText = "确认开启",
                    cancelable = false,
                    onCancel = {
                        syncingSwitchState = true
                        binding.forcePseudoMaskSwitch.isChecked = false
                        syncingSwitchState = false
                    },
                    onConfirm = {
                        // 开启伪熄屏时联动打开手势识别，保证有便捷退出途径
                        if (!SaveKeyValues.loadBoolean(Constant.GESTURE_DETECTOR_KEY, true)) {
                            SaveKeyValues.saveBoolean(Constant.GESTURE_DETECTOR_KEY, true)
                            ConfigImportSignal.notifyRemoteChanged(ctx)
                            syncingSwitchState = true
                            binding.gestureDetectSwitch.isChecked = true
                            syncingSwitchState = false
                        }
                        onConfirm(true)
                    }
                )
            }
        }
        binding.forcePseudoMaskSwitch.setOnCheckedChangeListener { _, checked ->
            if (syncingSwitchState) return@setOnCheckedChangeListener
            confirmForceMask(checked, applyForceMask)
        }
        binding.screenModeLayout.setOnClickListener {
            if (AppRuntimeConfig.isForcePseudoMask()) {
                getString(R.string.settings_screen_mode_tip_disabled).show(ctx)
                return@setOnClickListener
            }
            showScreenModeDialog()
        }
        refreshScreenModeRow()
        // 伪息屏增强分组开关：仅控制子项展开/收起，功能开关保留在子项内（forcePseudoMaskSwitch）
        val applyPseudoMaskGroupExpand = { expanded: Boolean ->
            binding.pseudoMaskGroupContent.visibility = if (expanded) View.VISIBLE else View.GONE
            binding.pseudoMaskGroupArrow.animate()
                .rotation(if (expanded) 180f else 0f)
                .setDuration(200)
                .start()
            SaveKeyValues.saveBoolean(Constant.PSEUDO_MASK_GROUP_EXPANDED_KEY, expanded)
        }
        binding.pseudoMaskGroupSwitch.setOnCheckedChangeListener { _, checked ->
            if (syncingSwitchState) return@setOnCheckedChangeListener
            applyPseudoMaskGroupExpand(checked)
        }
        binding.pseudoMaskGroupHeader.setOnClickListener {
            val expand = !binding.pseudoMaskGroupSwitch.isChecked
            syncingSwitchState = true
            binding.pseudoMaskGroupSwitch.isChecked = expand
            syncingSwitchState = false
            applyPseudoMaskGroupExpand(expand)
        }
        binding.pseudoMaskNoClockSwitch.setOnCheckedChangeListener { _, checked ->
            if (syncingSwitchState) return@setOnCheckedChangeListener
            SaveKeyValues.saveBoolean(Constant.PSEUDO_MASK_NO_CLOCK_KEY, checked)
            LogFileManager.writeLog("伪息屏隐藏时钟：${if (checked) "开启" else "关闭"}")
            ConfigImportSignal.notifyRemoteChanged(ctx)
        }
        val delay = SaveKeyValues.loadInt(Constant.IDLE_PSEUDO_MASK_TIMEOUT_KEY, 60).coerceIn(10, 3600)
        binding.pseudoMaskDelayValueText.text = getString(R.string.settings_pseudo_mask_delay_value, delay)
        binding.pseudoMaskDelayLayout.setOnClickListener {
            showPseudoMaskDelayDialog(SaveKeyValues.loadInt(Constant.IDLE_PSEUDO_MASK_TIMEOUT_KEY, 60).coerceIn(10, 3600))
        }
        binding.introduceLayout.setOnClickListener {
            ctx.startActivity(Intent(ctx, QuestionAndAnswerActivity::class.java))
        }
        // 一键诊断：写 Documents/diagnostic_*.txt，经系统分享面板导出（Fragment 化时曾误改为弹窗预览）
        binding.diagnosticLayout.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                val file = DiagnosticReporter.exportToFile(ctx)
                withContext(Dispatchers.Main) {
                    file?.let { reportFile ->
                        val authority = BuildConfig.APPLICATION_ID + ".fileprovider"
                        val uri = FileProvider.getUriForFile(ctx, authority, reportFile)
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        try {
                            startActivity(Intent.createChooser(shareIntent, "导出诊断日志"))
                        } catch (e: Exception) {
                            "导出失败：${e.message}".show(ctx)
                        }
                    } ?: run {
                        "诊断日志导出失败".show(ctx)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isHidden) refreshUi()
        // 兜底复位：无论 refreshUi 是否完整执行 syncSettingsUiFromStore，离开重建窗口后都解除同步锁，
        // 避免用户后续手动切换开关被误吞
        syncingSwitchState = false
    }

    override fun onPause() {
        super.onPause()
        runCatching { ctx.unregisterReceiver(remoteConfigReceiver) }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && isAdded) refreshUi()
        // 兜底复位（同 onResume 说明）
        syncingSwitchState = false
    }

    // ═══════════════════════ 私有方法 ═══════════════════════

    /** 刷新设置页全部状态（悬浮/通知/截屏/无障碍开关与提示） */
    private fun refreshUi() {
        ContextCompat.registerReceiver(
            ctx, remoteConfigReceiver,
            IntentFilter(ConfigImportSignal.ACTION_REMOTE_CONFIG_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // 悬浮窗
        if (!Settings.canDrawOverlays(ctx)) {
            binding.floatingSwitch.isChecked = false
            binding.floatingTipsView.visibility = View.VISIBLE
            binding.floatingTipsView.text = "服务未开启，打完卡无法自动跳回本软件"
        } else {
            binding.floatingSwitch.isChecked = true
            binding.floatingTipsView.visibility = View.GONE
        }
        // 消息渠道
        val channel = SaveKeyValues.loadInt(Constant.MSG_CHANNEL_KEY, -1)
        if (channel < 0 || channel > channels.lastIndex) {
            binding.channelView.text = "未配置"
            binding.channelView.setTextColor(R.color.red.convertColor(ctx))
        } else {
            binding.channelView.text = channels[channel]
            binding.channelView.setTextColor(R.color.theme_color.convertColor(ctx))
        }
        if (ConfigImportSignal.pendingSettingsRefresh) {
            ConfigImportSignal.pendingSettingsRefresh = false
            applyTargetAppIcon()
        }
        // 通知监听
        if (!ctx.notificationEnable()) {
            binding.noticeTipsView.text = "服务未开启，无法监听打卡结果和接收远程指令"
            binding.noticeTipsView.setTextColor(android.graphics.Color.RED)
            binding.noticeSwitch.isChecked = false
            binding.noticeTipsView.visibility = View.VISIBLE
        } else {
            binding.noticeTipsView.text = "服务状态查询中，请稍后..."
            binding.noticeTipsView.setTextColor(R.color.theme_color.convertColor(ctx))
            lifecycleScope.launch {
                delay(500)
                if (ctx.notificationEnable()) {
                    binding.noticeSwitch.isChecked = true
                    binding.noticeTipsView.visibility = View.GONE
                }
            }
        }
        // 截屏服务
        if (!ProjectionSession.isStateActive()) {
            binding.captureTipsView.text = "截屏服务未开启，无法获取打卡结果"
            binding.captureTipsView.setTextColor(android.graphics.Color.RED)
            binding.captureSwitch.isChecked = false
            binding.captureTipsView.visibility = View.VISIBLE
        } else {
            binding.captureSwitch.isChecked = true
            binding.captureTipsView.visibility = View.GONE
        }
        // 无障碍服务
        val a11yEnabled = AutoProjectionAccessibilityService.isEnabled(ctx)
        val resultSource = SaveKeyValues.loadInt(Constant.RESULT_SOURCE_KEY, -1)
        binding.accessibilitySwitch.isChecked = a11yEnabled
        if (!a11yEnabled || resultSource != 2) {
            if (resultSource == 2 && !a11yEnabled) {
                SaveKeyValues.saveInt(Constant.RESULT_SOURCE_KEY, 0)
                updateResultSourceView()
                "无障碍服务未开启，已切回通知模式".show(ctx)
            }
            binding.accessibilityTipsView.text = "无障碍服务未开启，无障碍模式无法获取打卡结果"
            binding.accessibilityTipsView.setTextColor(android.graphics.Color.RED)
            binding.accessibilityTipsView.visibility = if (a11yEnabled) View.GONE else View.VISIBLE
            binding.accessibilityFeedbackLayout.visibility = View.GONE
            binding.accessibilityFeedbackDivider.visibility = View.GONE
        } else {
            binding.accessibilityTipsView.visibility = View.GONE
            binding.accessibilityFeedbackLayout.visibility = View.VISIBLE
            binding.accessibilityFeedbackDivider.visibility = View.VISIBLE
            if (Build.VERSION.SDK_INT < 34 && SaveKeyValues.loadInt(Constant.ACCESSIBILITY_FEEDBACK_MODE_KEY, 0) == 0) {
                SaveKeyValues.saveInt(Constant.ACCESSIBILITY_FEEDBACK_MODE_KEY, 1)
            }
            updateAccessibilityFeedbackView()
        }
        updateResultSourceView()
        syncSettingsUiFromStore()
        updateSetupCheckStatus()
    }

    /** 刷新「被控端就绪自检」入口的状态摘要 */
    private fun updateSetupCheckStatus() {
        val targetApp = Constant.getTargetApp()
        val statusView = binding.setupCheckStatusView
        when {
            targetApp.isBlank() -> {
                statusView.text = "未配置目标"
                statusView.setTextColor(R.color.md_warning.convertColor(ctx))
            }

            !ctx.notificationEnable() -> {
                statusView.text = "通知未授权"
                statusView.setTextColor(R.color.md_error.convertColor(ctx))
            }

            !NotificationMonitorService.isListenerConnected() -> {
                statusView.text = "通知未连接"
                statusView.setTextColor(R.color.md_error.convertColor(ctx))
            }

            else -> {
                statusView.text = "基本正常"
                statusView.setTextColor(R.color.md_tertiary.convertColor(ctx))
            }
        }
    }

    /** 同步各开关到持久化存储（避免 UI 与状态不一致） */
    private fun syncSettingsUiFromStore() {
        syncingSwitchState = true
        try {
            binding.gestureDetectSwitch.isChecked = SaveKeyValues.loadBoolean(Constant.GESTURE_DETECTOR_KEY, true)
            binding.backToHomeSwitch.isChecked = SaveKeyValues.loadBoolean(Constant.BACK_TO_HOME_KEY, Constant.BACK_TO_HOME_DEFAULT)
            binding.desktopPetSwitch.isChecked = AppRuntimeConfig.isDesktopPetEnabled()
            binding.powerSaveSwitch.isChecked = AppRuntimeConfig.isPowerSaveMode()
            binding.bootAutoScheduleSwitch.isChecked =
                SaveKeyValues.loadBoolean(Constant.BOOT_AUTO_SCHEDULE_KEY, false)
            binding.keepAliveSwitch.isChecked = KeepAliveReceiver.isPaused()
            binding.forcePseudoMaskSwitch.isChecked = AppRuntimeConfig.isForcePseudoMask()
            refreshScreenModeRow()
            // 分组开关仅控制展开；内容显隐随持久化的展开状态
            val pseudoMaskGroupExpanded = SaveKeyValues.loadBoolean(Constant.PSEUDO_MASK_GROUP_EXPANDED_KEY, true)
            binding.pseudoMaskGroupSwitch.isChecked = pseudoMaskGroupExpanded
            binding.pseudoMaskGroupContent.visibility = if (pseudoMaskGroupExpanded) View.VISIBLE else View.GONE
            binding.pseudoMaskGroupArrow.rotation = if (pseudoMaskGroupExpanded) 180f else 0f
            val delay = SaveKeyValues.loadInt(Constant.IDLE_PSEUDO_MASK_TIMEOUT_KEY, 60).coerceIn(10, 3600)
            binding.pseudoMaskDelayValueText.text = getString(R.string.settings_pseudo_mask_delay_value, delay)
            binding.pseudoMaskNoClockSwitch.isChecked = SaveKeyValues.loadBoolean(Constant.PSEUDO_MASK_NO_CLOCK_KEY, false)
            binding.transferSwitch.isChecked = SaveKeyValues.loadBoolean(Constant.NOTIFICATION_TRANSFER_KEY, false)
            // 电量智能预警
            val alertEnabled = SaveKeyValues.loadBoolean(Constant.BATTERY_SMART_ALERT_ENABLED_KEY, false)
            binding.batterySmartAlertSwitch.isChecked = alertEnabled
            // 运行时日志总开关（默认开启）
            binding.logRecordSwitch.isChecked = SaveKeyValues.loadBoolean(Constant.LOG_ENABLED_KEY, true)
            // 分组开关仅控制展开；内容显隐随持久化的展开状态
            val batteryGroupExpanded = SaveKeyValues.loadBoolean(Constant.BATTERY_ALERT_GROUP_EXPANDED_KEY, true)
            binding.batteryAlertGroupSwitch.isChecked = batteryGroupExpanded
            binding.batteryAlertGroupContent.visibility = if (batteryGroupExpanded) View.VISIBLE else View.GONE
            binding.batteryAlertGroupArrow.rotation = if (batteryGroupExpanded) 180f else 0f
            val warningMinute = SaveKeyValues.loadInt(Constant.BATTERY_WARNING_HOUR_KEY, 20 * 60).coerceIn(0, 1439)
            binding.batteryWarningTimeValue.text = String.format("%02d:%02d", warningMinute / 60, warningMinute % 60)
            val rangeStart = SaveKeyValues.loadInt(Constant.BATTERY_ALERT_DETECTION_START_KEY, 20).coerceIn(0, 23)
            val rangeDuration = SaveKeyValues.loadInt(Constant.BATTERY_ALERT_DETECTION_DURATION_KEY, Constant.DEFAULT_BATTERY_ALERT_DURATION).coerceIn(1, 24)
            binding.batteryAlertRangeValue.text = batteryAlertRangeText(rangeStart, rangeDuration)
            val threshold = SaveKeyValues.loadInt(Constant.LOW_BATTERY_THRESHOLD_KEY, Constant.DEFAULT_LOW_BATTERY_THRESHOLD).coerceIn(10, 80)
            binding.batteryThresholdValue.text = "${threshold}%"
            val maxStages = SaveKeyValues.loadInt(Constant.BATTERY_ALERT_MAX_STAGES_KEY, 3).coerceIn(0, 3)
            binding.batteryStageCountValue.text = "$maxStages"
        } finally {
            syncingSwitchState = false
        }
    }

    private fun updateResultSourceView() {
        val label = when (SaveKeyValues.loadInt(Constant.RESULT_SOURCE_KEY, -1)) {
            0 -> "通知"
            1 -> "截屏"
            2 -> "无障碍"
            else -> "通知"
        }
        binding.resultSourceView.text = label
        binding.resultSourceView.setTextColor(R.color.theme_color.convertColor(ctx))
    }

    private fun updateAccessibilityFeedbackView() {
        val label = if (SaveKeyValues.loadInt(Constant.ACCESSIBILITY_FEEDBACK_MODE_KEY, 0) == 1) "文本反馈" else "截屏反馈"
        binding.accessibilityFeedbackView.text = label
        binding.accessibilityFeedbackView.setTextColor(R.color.theme_color.convertColor(ctx))
    }

    // ═══════════════════════ 权限自检 ═══════════════════════

    private enum class BackgroundStartState { ALLOWED, DENIED, UNKNOWN }

    /** 反射调用 AppOpsManager.checkOpNoThrow(int, int, String)，失败返回 MODE_DEFAULT */
    private fun checkOpNoThrowInt(appOps: AppOpsManager, op: Int): Int {
        return try {
            val method = AppOpsManager::class.java.getMethod(
                "checkOpNoThrow",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java
            )
            method.invoke(appOps, op, Process.myUid(), ctx.packageName) as? Int
                ?: AppOpsManager.MODE_DEFAULT
        } catch (e: Exception) {
            AppOpsManager.MODE_DEFAULT
        }
    }

    /**
     * 程序化检测「后台弹出界面」权限（后台拉起目标 App 的能力），按厂商分派：
     * - 原生/标准 Android：op 字符串 android:background_activity_start（Android Q+）；
     * - 小米 MIUI/HyperOS：自定义 op 10021（后台弹出界面），旧版本为 10001；
     * - 华为/荣耀：checkHwOpNoThrow(AppOps, 100000)（com.huawei.android.app.AppOpsManagerEx）；
     * - vivo：内容提供方 content://com.vivo.permissionmanager.provider.permission/start_bg_activity；
     * - OPPO/一加：无公开 appops，用悬浮窗权限近似（有悬浮窗时通常允许后台弹出）。
     */
    private fun queryBackgroundStartOp(): BackgroundStartState {
        val appOps = ctx.getSystemService(AppOpsManager::class.java)
            ?: return BackgroundStartState.UNKNOWN
        return try {
            when {
                RomDetector.isMiui() -> {
                    // 10021 = 后台弹出界面；旧版 MIUI 用 10001。任一 allow 即已授予
                    val m21 = checkOpNoThrowInt(appOps, 10021)
                    val m01 = checkOpNoThrowInt(appOps, 10001)
                    when {
                        m21 == AppOpsManager.MODE_ALLOWED || m01 == AppOpsManager.MODE_ALLOWED ->
                            BackgroundStartState.ALLOWED
                        m21 == AppOpsManager.MODE_IGNORED || m21 == AppOpsManager.MODE_ERRORED ||
                            m01 == AppOpsManager.MODE_IGNORED || m01 == AppOpsManager.MODE_ERRORED ->
                            BackgroundStartState.DENIED
                        else -> BackgroundStartState.UNKNOWN
                    }
                }
                RomDetector.isHuawei() || RomDetector.isHonor() -> {
                    val mode = try {
                        val cls = Class.forName("com.huawei.android.app.AppOpsManagerEx")
                        val method = cls.getDeclaredMethod(
                            "checkHwOpNoThrow",
                            AppOpsManager::class.java,
                            Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType,
                            String::class.java
                        )
                        method.invoke(
                            cls.getDeclaredConstructor().newInstance(), appOps, 100000,
                            Process.myUid(), ctx.packageName
                        ) as? Int ?: AppOpsManager.MODE_DEFAULT
                    } catch (e: Exception) {
                        AppOpsManager.MODE_DEFAULT
                    }
                    when (mode) {
                        AppOpsManager.MODE_ALLOWED -> BackgroundStartState.ALLOWED
                        AppOpsManager.MODE_IGNORED, AppOpsManager.MODE_ERRORED -> BackgroundStartState.DENIED
                        else -> BackgroundStartState.UNKNOWN
                    }
                }
                RomDetector.isVivo() -> {
                    val state = try {
                        val uri = Uri.parse(
                            "content://com.vivo.permissionmanager.provider.permission/start_bg_activity"
                        )
                        ctx.contentResolver.query(
                            uri, null, "pkgname = ?",
                            arrayOf(ctx.packageName), null
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                cursor.getInt(cursor.getColumnIndexOrThrow("currentstate"))
                            } else 1
                        } ?: 1
                    } catch (e: Exception) {
                        1
                    }
                    // 0=已开启，1=未开启
                    if (state == 0) BackgroundStartState.ALLOWED else BackgroundStartState.DENIED
                }
                RomDetector.isOppo() -> {
                    // ColorOS 无公开 appops；悬浮窗允许一般伴随后台弹出可用
                    if (Settings.canDrawOverlays(ctx)) BackgroundStartState.ALLOWED
                    else BackgroundStartState.UNKNOWN
                }
                else -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val mode = try {
                            appOps.checkOpNoThrow(
                                "android:background_activity_start",
                                Process.myUid(), ctx.packageName
                            )
                        } catch (e: Exception) {
                            AppOpsManager.MODE_DEFAULT
                        }
                        when (mode) {
                            AppOpsManager.MODE_ALLOWED -> BackgroundStartState.ALLOWED
                            AppOpsManager.MODE_IGNORED, AppOpsManager.MODE_ERRORED ->
                                BackgroundStartState.DENIED
                            else -> BackgroundStartState.UNKNOWN
                        }
                    } else {
                        BackgroundStartState.UNKNOWN
                    }
                }
            }
        } catch (e: Exception) {
            LogFileManager.error("检测后台弹出界面权限失败: ${e.message}")
            BackgroundStartState.UNKNOWN
        }
    }

    /**
     * 程序化检测「自启动」权限（开机自启/后台常驻），按厂商分派：
     * - 原生 Android：RECEIVE_BOOT_COMPLETED 权限已声明即视为允许（无独立自启开关）；
     * - 小米 MIUI/HyperOS：自定义 op 10008（自启动），allow=已授予；
     * - 华为/OPPO/vivo：无公开 appops，返回 UNKNOWN 由用户手动确认。
     */
    private fun queryAutostartState(): Boolean? = ctx.isAutostartGranted()

    /**
     * 权限自检：检查 App 运行所需的基本权限（不含截屏/无障碍等功能权限），在配置阶段提前暴露并引导解决——
     * 1) 通知监听：区分「未授权」与「已授权但服务未连接」（国产 ROM 自启动拦截绑定，远程指令无响应）。
     * 2) 后台弹出界面：App 在后台拉起打卡应用所需的系统权限（前台验证必然成功，需真实退后台验证）。
     * 3) 悬浮窗权限：打卡结果提示 / 伪息屏蒙层。
     * 4) 电池优化豁免：后台保活。
     */
    private fun showSetupSelfCheck() {
        val targetApp = Constant.getTargetApp()
        val noticeAuthorized = ctx.notificationEnable()
        val noticeConnected = NotificationMonitorService.isListenerConnected()
        val overlayGranted = Settings.canDrawOverlays(ctx)
        val batteryExempt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = ctx.getSystemService(PowerManager::class.java)
            pm.isIgnoringBatteryOptimizations(ctx.packageName)
        } else true

        val sb = StringBuilder()
        sb.append("① 通知监听（远程指令接收）\n")
        when {
            !noticeAuthorized -> sb.append("    ✗ 未授权，无法接收远程指令\n")
            !noticeConnected -> sb.append("    ⚠ 已授权但服务未连接（国产 ROM 可能需要开启自启动权限）\n")
            else -> sb.append("    ✓ 已连接，可接收远程指令\n")
        }
        sb.append("\n② 后台弹出界面（后台拉起打卡 App）\n")
        sb.append("    [${RomDetector.displayName()}]\n")
        when (queryBackgroundStartOp()) {
            BackgroundStartState.ALLOWED -> sb.append("    ✓ 已授予，后台可拉起目标 App\n")
            BackgroundStartState.DENIED -> sb.append(
                "    ✗ 未授予，后台拉起目标 App / 伪息屏蒙层会被系统拦截\n"
            )
            BackgroundStartState.UNKNOWN -> sb.append(
                "    ⓘ 系统未暴露该权限状态，请用下方「后台验证」实测确认\n"
            )
        }
        sb.append("\n③ 悬浮窗权限（结果提示/蒙层）\n")
        sb.append(if (overlayGranted) "    ✓ 已获取\n" else "    ✗ 未获取\n")
        sb.append("\n④ 电池白名单（电池优化豁免，后台保活）\n")
        sb.append(if (batteryExempt) "    ✓ 已加入白名单\n" else "    ✗ 未豁免，锁屏后可能被杀\n")
        sb.append("\n⑤ 自启动权限（开机自启/后台常驻）\n")
        when (val auto = queryAutostartState()) {
            true -> sb.append("    ✓ 已允许自启动（MIUI/HyperOS）\n")
            false -> sb.append("    ✗ 已禁用自启动，重启后 App 不会自动拉起\n")
            null -> sb.append(
                "    ${if (RomDetector.isMiui()) "ⓘ 无法读取，请到系统设置确认" else "✓ 系统默认允许（原生 Android 无独立自启开关）"}\n"
            )
        }

        val contentView = ScrollView(ctx).apply {
            addView(TextView(ctx).apply {
                text = sb.toString()
                setPadding(24, 16, 24, 16)
                textSize = 14f
            })
        }
        UnifiedDialogKit.showForm(
            ctx = ctx,
            contentView = contentView,
            title = "权限自检",
            positiveText = "后台验证",
            negativeText = if (noticeAuthorized && !noticeConnected) "去修复" else "关闭",
            onConfirm = {
                verifyBackgroundLaunch(targetApp)
                true
            },
            onCancel = {
                if (noticeAuthorized && !noticeConnected) {
                    showNotificationListenerFixGuide()
                    false
                } else {
                    true
                }
            }
        )
    }

    /**
     * 通知监听未连接引导：说明国产 ROM 自启动拦截，引导去系统设置开启「自启动」。
     */
    private fun showNotificationListenerFixGuide() {
        val guide = when {
            RomDetector.isMiui() ->
                "1. 设置 → 应用设置 → 应用管理 → 本应用 → 自启动，设为允许；\n" +
                    "2. 或到「安全中心 → 应用管理 → 权限」中允许自启动；\n"
            RomDetector.isHuawei() || RomDetector.isHonor() ->
                "1. 手机管家 → 应用启动管理，将本应用改为「手动管理」并允许自启动；\n"
            RomDetector.isVivo() ->
                "1. i管家 → 应用管理 → 权限管理 → 自启动，设为允许；\n"
            RomDetector.isOppo() ->
                "1. 设置 → 应用管理 → 本应用 → 自启动，设为允许；\n"
            else ->
                "1. 设置 → 应用 → 本应用 → 电池/后台运行，允许自启动；\n"
        }
        UnifiedDialogKit.showForm(
            ctx = ctx,
            contentView = TextView(ctx).apply {
                text = "通知监听已授权但服务未连接，远程指令会无响应。\n\n" +
                    "常见原因：国产 ROM 自启动管理拦截了通知监听服务绑定。\n\n" +
                    "解决步骤：\n" + guide +
                    "2. 修改后重启手机，或在此页重新开关通知监听，确认状态变为「已连接」。"
                setPadding(24, 16, 24, 16)
                textSize = 14f
            },
            title = "通知监听未连接",
            positiveText = "前往设置",
            negativeText = "知道了",
            onConfirm = {
                openAppDetailSettings(ctx.packageName)
                true
            }
        )
    }

    /**
     * 后台弹出界面真实验证：先退到后台，再从后台启动目标 App，验证系统后台启动限制是否放行。
     * 无障碍已启用时自动判定目标是否进入前台；否则由用户目视确认后选择。
     */
    private fun verifyBackgroundLaunch(targetApp: String) {
        if (targetApp.isBlank()) {
            "请先选择目标打卡应用".show(ctx)
            return
        }
        if (!ctx.isApplicationExist(targetApp)) {
            "未安装目标应用：$targetApp".show(ctx)
            return
        }
        val a11yEnabled = AutoProjectionAccessibilityService.isEnabled(ctx)
        UnifiedDialogKit.showForm(
            ctx = ctx,
            contentView = TextView(ctx).apply {
                text = buildString {
                    append("本应用将跳转到「$targetApp」（悬浮窗倒计时 5 秒）→ 退回桌面 → 从桌面返回本应用。\n\n")
                    append("请观察屏幕：\n")
                    append("· 目标应用正常弹出并自动返回 → 权限正常\n")
                    append("· 无法拉起或无法自动返回 → 被系统拦截，需开启「启动应用」权限，小米手机额外开启「后台弹出界面」权限\n\n")
                    if (a11yEnabled) append("已检测到无障碍服务，结果将自动判定。") else append("未启用无障碍服务，稍后需您手动确认结果。")
                }
                setPadding(24, 16, 24, 16)
                textSize = 14f
            },
            title = "后台弹出验证",
            positiveText = "开始验证",
            negativeText = "取消",
            onConfirm = {
                performBackgroundLaunchCheck(targetApp, a11yEnabled)
                true
            }
        )
    }

    /**
     * 执行后台验证动作序列：本 App 前台跳转目标（悬浮窗 5s 倒计时）→ 退回桌面 → 从桌面返回本 App → 弹结果。
     */
    private fun performBackgroundLaunchCheck(targetApp: String, a11yEnabled: Boolean) {
        val mainHandler = Handler(Looper.getMainLooper())
        // 开启悬浮窗会话：验证期间在目标 App 上显示 5s 倒计时
        FloatingWindowController.startFloatSession()
        FloatingWindowController.updateTime(5)
        // 本 App 前台直接拉起目标 App
        try {
            val intent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                setPackage(targetApp)
            }
            val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                ctx.packageManager.queryIntentActivities(intent, 0)
            }
            if (activities.isNotEmpty()) {
                val info = activities.first()
                intent.component = ComponentName(info.activityInfo.packageName, info.activityInfo.name)
            }
            ctx.startActivity(intent)
        } catch (e: Exception) {
            LogFileManager.error("后台验证启动目标失败: ${e.message}")
        }
        // 5s 倒计时悬浮窗
        var countdown = 5
        val tickRunnable = object : Runnable {
            override fun run() {
                FloatingWindowController.updateTime(countdown)
                countdown--
                if (countdown > 0) {
                    mainHandler.postDelayed(this, 1000L)
                }
            }
        }
        mainHandler.postDelayed(tickRunnable, 1000L)
        // 5s 后判定目标是否弹出 → 退回桌面 → 从桌面返回本 App
        mainHandler.postDelayed({
            FloatingWindowController.stopFloatSession()
            val launched = if (a11yEnabled) {
                AutoProjectionAccessibilityService.lastForegroundPackage() == targetApp
            } else null
            // 从目标 App 退回桌面
            try {
                ctx.startActivity(Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (e: Exception) {
                LogFileManager.error("后台验证退回桌面失败: ${e.message}")
            }
            // 等桌面切换稳定后，从桌面返回本 App 并展示结果
            mainHandler.postDelayed({
                showBackgroundVerifyResult(targetApp, launched, a11yEnabled, mainHandler)
            }, 800L)
        }, 5000L)
    }

    /** 后台验证结果展示：自动判定或用户确认；失败时引导去系统设置开启「后台弹出界面」 */
    private fun showBackgroundVerifyResult(
        targetApp: String,
        launched: Boolean?,
        a11yEnabled: Boolean,
        mainHandler: Handler
    ) {
        // 回到前台再弹结果，避免在后台弹窗。
        // 带 EXTRA_MASK_COMMAND=0 退出蒙层：否则 consumeReturnFromBackground() 会因「强制伪息屏」
        // 在返回时恢复黑屏蒙层，用户得先取消伪息屏才能看到验证结果弹窗。
        val back = Intent(ctx, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
            putExtra(Constant.EXTRA_MASK_COMMAND, 0)
        }
        mainHandler.postDelayed({
            try { ctx.startActivity(back) } catch (_: Exception) { }
            // MIUI 等系统的「后台弹出界面」限制会静默拦截 startActivity 拉起 MainActivity，
            // 界面回不来导致结果弹窗永不显示（真机实测：MIUIOP(10021) 记录 rejectTime）。
            // 轮询应用前台状态：3s 内回到前台则走正常弹窗；超时改用悬浮窗 overlay 展示结果，
            // 并提示可能需要的权限，避免验证流程卡死在目标 App 页。
            val start = SystemClock.elapsedRealtime()
            val poll = object : Runnable {
                override fun run() {
                    if (DailyTaskApplication.isAppForeground) {
                        mainHandler.postDelayed({
                            showBackgroundVerifyResultDialog(targetApp, launched, a11yEnabled)
                        }, 300L)
                    } else if (SystemClock.elapsedRealtime() - start < 3000L) {
                        mainHandler.postDelayed(this, 200L)
                    } else {
                        LogFileManager.error("后台验证：MainActivity 未在 3s 内回前台，改用悬浮窗展示结果")
                        showBackgroundVerifyResultOverlay(targetApp, launched)
                    }
                }
            }
            mainHandler.postDelayed(poll, 200L)
        }, 400L)
    }

    /** MainActivity 正常回前台时的结果弹窗 */
    private fun showBackgroundVerifyResultDialog(targetApp: String, launched: Boolean?, a11yEnabled: Boolean) {
        if (launched == true) {
            UnifiedDialogKit.showSuccess(
                ctx,
                "验证通过",
                "「$targetApp」已从后台成功拉起，后台弹出界面权限正常。",
                confirmText = "知道了",
                cancelText = null
            )
        } else if (launched == false) {
            openBackgroundStartFailDialog(targetApp)
        } else {
            // 无无障碍：用户目视确认
            UnifiedDialogKit.showForm(
                ctx = ctx,
                contentView = TextView(ctx).apply {
                    text = "刚才是否看到「$targetApp」从后台弹出？"
                    setPadding(24, 16, 24, 16)
                    textSize = 14f
                },
                title = "后台弹出验证",
                positiveText = "看到了",
                negativeText = "没看到",
                onConfirm = {
                    "后台弹出界面权限正常".show(ctx)
                    true
                },
                onCancel = {
                    openBackgroundStartFailDialog(targetApp)
                    false
                }
            )
        }
    }

    /**
     * 悬浮窗 overlay 兜底展示验证结果：MainActivity 被系统「后台弹出界面」限制拦截、无法回前台时，
     * 直接用已获授权的悬浮窗在目标 App 之上弹出结果，并引导开启所需权限。
     * 布局复用 dialog_unified_content（与 App 内弹窗一致的 M3 风格），但按钮背景/文字色显式指定，
     * 因为 overlay 环境不依赖 MaterialAlertDialog 主题、MaterialButton 的 backgroundTint 不会生效。
     */
    private fun showBackgroundVerifyResultOverlay(targetApp: String, launched: Boolean?) {
        if (!Settings.canDrawOverlays(ctx)) {
            LogFileManager.error("后台验证悬浮窗兜底失败：无悬浮窗权限")
            "后台验证：本应用回前台被系统拦截，且无悬浮窗权限，无法显示结果".show(ctx)
            return
        }
        val appCtx = ctx.applicationContext
        val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        // 与应用内弹窗同主题 inflate，保证颜色资源可正确解析
        val themedCtx = ContextThemeWrapper(appCtx, R.style.Theme_DailyTask)
        val density = appCtx.resources.displayMetrics.density
        val dip = { v: Int -> (v * density).toInt() }

        // 无法返回本应用 = 「后台弹出界面」权限未开全，无论目标是否弹出都引导去权限页（精简文案）
        val title = "后台弹出界面未授权"
        val message = when (launched) {
            true -> "「$targetApp」已弹出，但本应用无法自动返回。\n请开启「后台弹出界面」权限。"
            false -> "「$targetApp」未能弹出。\n请开启「后台弹出界面」权限后重试。"
            else -> "本应用无法自动返回。\n请开启「后台弹出界面」权限。"
        }

        val content = LayoutInflater.from(themedCtx).inflate(R.layout.dialog_unified_content, null)
        content.findViewById<ImageView>(R.id.ivDialogIcon).apply {
            backgroundTintList = ContextCompat.getColorStateList(themedCtx, R.color.md_errorContainer)
            setImageResource(R.drawable.ic_dialog_permission)
            imageTintList = ContextCompat.getColorStateList(themedCtx, R.color.md_error)
        }
        content.findViewById<TextView>(R.id.tvDialogTitle).text = title
        content.findViewById<TextView>(R.id.tvDialogMessage).text = message

        // 按钮：显式指定背景/文字色（overlay 下 MaterialButton backgroundTint 不生效）
        val btnPositive = content.findViewById<Button>(R.id.btnPositive)
        val btnNegative = content.findViewById<Button>(R.id.btnNegative)
        val btnBar = content.findViewById<LinearLayout>(R.id.btnBar)
        val primaryColor = ContextCompat.getColor(themedCtx, R.color.md_primary)
        val onPrimaryColor = ContextCompat.getColor(themedCtx, R.color.md_onPrimary)
        val onSurfaceVariantColor = ContextCompat.getColor(themedCtx, R.color.md_onSurfaceVariant)
        val outlineVariantColor = ContextCompat.getColor(themedCtx, R.color.md_outlineVariant)
        fun pillBackground(fill: Int?, stroke: Int?) = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dip(22).toFloat()
            if (fill != null) setColor(fill)
            if (stroke != null) setStroke(dip(1), stroke)
        }

        btnPositive.apply {
            text = "前往设置"
            background = pillBackground(primaryColor, null)
            setTextColor(onPrimaryColor)
            visibility = View.VISIBLE
            setOnClickListener {
                openAppDetailSettings(ctx.packageName)
                runCatching { wm.removeView(content) }
            }
        }
        if (launched == false) {
            // 目标未弹出：保留「知道了」，用户可先关掉弹窗
            btnNegative.apply {
                text = "知道了"
                background = pillBackground(null, outlineVariantColor)
                setTextColor(onSurfaceVariantColor)
                visibility = View.VISIBLE
                setOnClickListener {
                    runCatching { wm.removeView(content) }
                }
            }
            btnBar.gravity = Gravity.CENTER
        } else {
            // 单按钮：居中自然宽度（与 UnifiedDialogKit 行为一致）
            val lp = btnPositive.layoutParams as LinearLayout.LayoutParams
            lp.width = LinearLayout.LayoutParams.WRAP_CONTENT
            lp.weight = 0f
            lp.marginStart = 0
            btnPositive.layoutParams = lp
            btnBar.gravity = Gravity.CENTER
        }

        // 28dp 大圆角 + tonal 抬升表面，复刻 MaterialAlertDialog 外观
        val bg = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dip(28).toFloat()
            setColor(ContextCompat.getColor(themedCtx, R.color.md_surfaceContainerHigh))
        }
        content.setBackground(bg)
        content.elevation = dip(8).toFloat()

        val cardParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            width = dip(320)
        }
        try {
            wm.addView(content, cardParams)
        } catch (e: Exception) {
            LogFileManager.error("后台验证悬浮窗展示失败: ${e.message}")
        }
    }

    /** 后台启动失败引导：说明原因并提供「前往系统设置」入口（按厂商给对应路径） */
    private fun openBackgroundStartFailDialog(targetApp: String) {
        val guide = when {
            RomDetector.isMiui() ->
                "设置 → 应用设置 → 应用管理 → 本应用 → 权限管理 → 后台弹出界面（允许）"
            RomDetector.isHuawei() || RomDetector.isHonor() ->
                "设置 → 应用 → 权限管理 → 后台弹出界面（允许）"
            RomDetector.isVivo() ->
                "i管家 → 应用管理 → 权限管理 → 后台弹出界面（允许）"
            RomDetector.isOppo() ->
                "设置 → 应用管理 → 本应用 → 允许后台弹出界面（允许）"
            else ->
                "设置 → 应用 → 本应用 → 电池/后台运行，允许后台启动 Activity"
        }
        UnifiedDialogKit.showForm(
            ctx = ctx,
            contentView = TextView(ctx).apply {
                text = "「$targetApp」未能从后台弹出，可能被系统后台启动限制拦截。\n\n" +
                    "请前往系统设置，为本应用开启「后台弹出界面」权限：\n" +
                    guide
                setPadding(24, 16, 24, 16)
                textSize = 14f
            },
            title = "后台弹出被拦截",
            positiveText = "前往设置",
            negativeText = "知道了",
            onConfirm = {
                openAppDetailSettings(ctx.packageName)
                true
            }
        )
    }

    /** 打开指定应用的应用详情/权限设置页 */
    private fun openAppDetailSettings(pkg: String) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$pkg")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        } catch (e: Exception) {
            "无法打开系统设置：${e.message}".show(ctx)
        }
    }

    /** 电量智能预警时间选择器（精确到分钟） */
    private fun showBatteryWarningTimePicker() {
        val currentMinute = SaveKeyValues.loadInt(Constant.BATTERY_WARNING_HOUR_KEY, 20 * 60).coerceIn(0, 1439)
        val threshold = SaveKeyValues.loadInt(Constant.LOW_BATTERY_THRESHOLD_KEY, Constant.DEFAULT_LOW_BATTERY_THRESHOLD).coerceIn(10, 80)
        val dialogView = com.github.gzuliyujiang.wheelpicker.widget.TimeWheelLayout(ctx).apply {
            val cal = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, currentMinute / 60)
                set(java.util.Calendar.MINUTE, currentMinute % 60)
                set(java.util.Calendar.SECOND, 0)
            }
            setDefaultValue(com.github.gzuliyujiang.wheelpicker.entity.TimeEntity.target(cal.time))
        }
        UnifiedDialogKit.showForm(
            ctx = ctx,
            contentView = dialogView,
            title = "最晚预警时间",
            message = "若预测电量在此时之后降至 $threshold% 以下，将在此时前发送预警，避免夜间低电量关机",
            positiveText = "确定",
            negativeText = "取消",
            onConfirm = {
                val minute = dialogView.selectedHour * 60 + dialogView.selectedMinute
                SaveKeyValues.saveInt(Constant.BATTERY_WARNING_HOUR_KEY, minute.coerceIn(0, 1439))
                binding.batteryWarningTimeValue.text = String.format("%02d:%02d", minute / 60, minute % 60)
                // 本地变更后重新调度预警闹钟
                try {
                    com.pengxh.daily.app.service.KeepAliveReceiver.scheduleBatteryAlert(ctx)
                } catch (_: Exception) {}
                true
            }
        )
    }

    /** 预警检测区间选择器（起始时间滑块 + 区间时长滑块，实时预览完整检测区间） */
    private fun showBatteryAlertRangePicker() {
        val start = SaveKeyValues.loadInt(Constant.BATTERY_ALERT_DETECTION_START_KEY, 20).coerceIn(0, 23)
        val duration = SaveKeyValues.loadInt(Constant.BATTERY_ALERT_DETECTION_DURATION_KEY, Constant.DEFAULT_BATTERY_ALERT_DURATION).coerceIn(1, 24)
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }
        val startLabel = android.widget.TextView(ctx).apply { text = "起始时间" }
        val startValue = android.widget.TextView(ctx).apply {
            text = "%02d:00".format(start)
            setTextColor(R.color.md_primary.convertColor(ctx))
        }
        val startSlider = com.google.android.material.slider.Slider(ctx).apply {
            valueFrom = 0f; valueTo = 23f; stepSize = 1f; value = start.toFloat()
        }
        container.addView(startLabel)
        container.addView(startSlider)
        container.addView(startValue)

        val durationLabel = android.widget.TextView(ctx).apply { text = "区间时长"; setPadding(0, 20, 0, 0) }
        val durationValue = android.widget.TextView(ctx).apply {
            text = "%d 小时".format(duration)
            setTextColor(R.color.md_primary.convertColor(ctx))
        }
        val durationSlider = com.google.android.material.slider.Slider(ctx).apply {
            valueFrom = 1f; valueTo = 24f; stepSize = 1f; value = duration.toFloat()
        }
        container.addView(durationLabel)
        container.addView(durationSlider)
        container.addView(durationValue)

        val preview = android.widget.TextView(ctx).apply {
            setPadding(0, 20, 0, 0)
            setTextColor(R.color.md_onSurfaceVariant.convertColor(ctx))
            textSize = 13f
        }
        val updatePreview = {
            val s = startSlider.value.toInt()
            val d = durationSlider.value.toInt().coerceIn(1, 24)
            startValue.text = "%02d:00".format(s)
            durationValue.text = "%d 小时".format(d)
            preview.text = "检测区间：${batteryAlertRangePreview(s, d)}"
        }
        startSlider.addOnChangeListener { _, _, _ -> updatePreview() }
        durationSlider.addOnChangeListener { _, _, _ -> updatePreview() }
        updatePreview()
        container.addView(preview)

        UnifiedDialogKit.showForm(
            ctx = ctx, contentView = container, title = "预警检测区间",
            message = "预测耗尽时间落在此区间时才触发预警，避免白天频繁误报",
            positiveText = "确定", negativeText = "取消",
            onConfirm = {
                val s = startSlider.value.toInt().coerceIn(0, 23)
                val d = durationSlider.value.toInt().coerceIn(1, 24)
                SaveKeyValues.saveInt(Constant.BATTERY_ALERT_DETECTION_START_KEY, s)
                SaveKeyValues.saveInt(Constant.BATTERY_ALERT_DETECTION_DURATION_KEY, d)
                binding.batteryAlertRangeValue.text = batteryAlertRangeText(s, d)
                true
            }
        )
    }

    /** 区间行展示文案：起始时间 + 时长 */
    private fun batteryAlertRangeText(start: Int, duration: Int): String =
        "%02d:00起 · %d小时".format(start, duration)

    /** 弹窗预览文案：完整的检测时间区间（跨天自动标注次日） */
    private fun batteryAlertRangePreview(start: Int, duration: Int): String {
        val end = (start + duration) % 24
        val nextDay = start + duration >= 24
        return "%02d:00 至%s%02d:00".format(start, if (nextDay) "次日" else "", end)
    }

    /** 低电量告警阈值选择器（10~80%，实时展示当前值与三段分段边界） */
    private fun showBatteryThresholdPicker() {
        val current = SaveKeyValues.loadInt(Constant.LOW_BATTERY_THRESHOLD_KEY, Constant.DEFAULT_LOW_BATTERY_THRESHOLD).coerceIn(10, 80)
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }
        val valueText = android.widget.TextView(ctx).apply {
            text = "${current}%"
            textSize = 28f
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setTextColor(R.color.md_primary.convertColor(ctx))
        }
        val slider = com.google.android.material.slider.Slider(ctx).apply {
            valueFrom = 10f; valueTo = 80f; stepSize = 5f; value = current.toFloat()
        }
        val stagePreview = android.widget.TextView(ctx).apply {
            setPadding(0, 12, 0, 0)
            setTextColor(R.color.md_onSurfaceVariant.convertColor(ctx))
            textSize = 13f
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }
        val updatePreview = {
            val v = slider.value.toInt().coerceIn(10, 80)
            valueText.text = "${v}%"
            stagePreview.text = "分段：$v% → ${(v * 2 / 3).coerceAtLeast(1)}% → ${(v * 1 / 3).coerceAtLeast(1)}%"
        }
        slider.addOnChangeListener { _, _, _ -> updatePreview() }
        updatePreview()
        container.addView(valueText)
        container.addView(slider)
        container.addView(stagePreview)
        UnifiedDialogKit.showForm(
            ctx = ctx, contentView = container, title = "低电量告警阈值",
            message = "电量低于阈值时按比例分段告警（实际段数受「低电量告警次数」控制）",
            positiveText = "确定", negativeText = "取消",
            onConfirm = {
                val v = slider.value.toInt().coerceIn(10, 80)
                SaveKeyValues.saveInt(Constant.LOW_BATTERY_THRESHOLD_KEY, v)
                // 阈值变更时清零三段告警标记，允许重新计数
                SaveKeyValues.saveBoolean(Constant.LOW_BATTERY_STAGE1_KEY, false)
                SaveKeyValues.saveBoolean(Constant.LOW_BATTERY_STAGE2_KEY, false)
                SaveKeyValues.saveBoolean(Constant.LOW_BATTERY_STAGE3_KEY, false)
                binding.batteryThresholdValue.text = "${v}%"
                true
            }
        )
    }

    /** 低电量告警次数选择器（0~3，四选项全部展示，无需滑动） */
    private fun showBatteryStageCountPicker() {
        val current = SaveKeyValues.loadInt(Constant.BATTERY_ALERT_MAX_STAGES_KEY, 3).coerceIn(0, 3)
        val options = listOf(
            "0 · 关闭低电量告警",
            "1 · 仅阈值段（如 30%）",
            "2 · 阈值 → 2/3 阈值（30% → 20%）",
            "3 · 阈值 → 2/3 → 1/3（30% → 20% → 10%，推荐）"
        )
        UnifiedDialogKit.showSingleChoice(
            ctx = ctx,
            title = "低电量告警次数",
            items = options,
            selectedIndex = current,
            onSelect = { index ->
                SaveKeyValues.saveInt(Constant.BATTERY_ALERT_MAX_STAGES_KEY, index)
                binding.batteryStageCountValue.text = "$index"
            }
        )
    }

    /** 目标应用标签列表（内置 + 自定义入口） */
    private fun targetAppLabels(): List<String> =
        Constant.getBuiltInTargets().map { it.second } + getString(R.string.settings_custom_app_entry)

    /** 应用目标图标 */
    private fun applyTargetAppIcon() {
        val target = Constant.getTargetApp()
        val builtInIdx = Constant.getBuiltInTargets().indexOfFirst { it.first == target }
        if (builtInIdx >= 0) {
            binding.iconView.setBackgroundResource(builtInIcons.getOrElse(builtInIdx) { builtInIcons[0] })
        } else {
            val icon = loadAppIcon(target)
            if (icon == null) {
                binding.iconView.setBackgroundResource(R.drawable.ic_custom_app)
            } else {
                binding.iconView.background = icon
            }
        }
    }

    private fun loadAppIcon(pkg: String): Drawable? = try {
        ctx.packageManager.getApplicationIcon(pkg)
    } catch (e: Exception) {
        null
    }

    private fun resolveAppLabel(pkg: String): String = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    private fun isValidPackageName(name: String): Boolean =
        Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$").matches(name)

    /** 解析多行/逗号/分号分隔的包名列表 */
    private fun parsePackageList(raw: String): List<String> =
        raw.split(Regex("[\n,\uff0c;\uff1b\\s]+")).map { it.trim() }.filter { it.isNotBlank() }

    /** 新增自定义应用（从已安装应用选择） */
    private fun addCustomApp(pkg: String) {
        val list = Constant.getCustomTargetApps().toMutableList()
        if (!list.contains(pkg)) list.add(pkg)
        SaveKeyValues.saveString(Constant.CUSTOM_TARGET_APPS_KEY, list.joinToString(","))
        SaveKeyValues.saveInt(Constant.TARGET_APP_KEY, Constant.CUSTOM_TARGET_INDEX)
        SaveKeyValues.saveString(Constant.CUSTOM_TARGET_SELECTED_KEY, pkg)
        applyTargetAppIcon()
        ConfigImportSignal.notifyRemoteChanged(ctx)
        getString(R.string.settings_pick_app_added, resolveAppLabel(pkg)).show(ctx)
    }

    /** 移除自定义应用 */
    private fun removeCustomApp(pkg: String) {
        val list = Constant.getCustomTargetApps().toMutableList()
        list.remove(pkg)
        SaveKeyValues.saveString(Constant.CUSTOM_TARGET_APPS_KEY, list.joinToString(","))
        if (SaveKeyValues.loadInt(Constant.TARGET_APP_KEY, 0) == Constant.CUSTOM_TARGET_INDEX &&
            SaveKeyValues.loadString(Constant.CUSTOM_TARGET_SELECTED_KEY, "") == pkg
        ) {
            SaveKeyValues.saveInt(Constant.TARGET_APP_KEY, 0)
            SaveKeyValues.saveString(Constant.CUSTOM_TARGET_SELECTED_KEY, "")
        }
        applyTargetAppIcon()
        ConfigImportSignal.notifyRemoteChanged(ctx)
        customAppDialog?.dismiss()
        showCustomAppManagerDialog()
    }

    /** 自定义应用管理对话框 */
    private fun showCustomAppManagerDialog() {
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_custom_app_manager, null)
        val contentContainer = view.findViewById<FrameLayout>(R.id.contentContainer)
        val btnPickFromInstalled = view.findViewById<TextView>(R.id.btnPickFromInstalled)
        val btnManualInput = view.findViewById<TextView>(R.id.btnManualInput)
        val customApps = Constant.getCustomTargetApps()
        if (customApps.isNotEmpty()) {
            val list = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            }
            customApps.forEachIndexed { index, pkg ->
                if (index > 0) {
                    list.addView(View(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            resources.getDimensionPixelSize(R.dimen.dividerLine)
                        )
                        setBackgroundColor(ctx.getColor(R.color.md_outlineVariant))
                    })
                }
                val item = LayoutInflater.from(ctx).inflate(R.layout.dialog_custom_app_item, list, false)
                val icon = loadAppIcon(pkg)
                item.findViewById<ImageView>(R.id.appIcon).apply {
                    if (icon == null) setImageResource(R.drawable.ic_custom_app) else setImageDrawable(icon)
                }
                item.findViewById<TextView>(R.id.appName).text = resolveAppLabel(pkg)
                item.findViewById<TextView>(R.id.appPkg).text = pkg
                item.findViewById<TextView>(R.id.btnRemove).setOnClickListener { removeCustomApp(pkg) }
                list.addView(item)
            }
            contentContainer.addView(list)
        } else {
            contentContainer.addView(TextView(ctx).apply {
                text = getString(R.string.settings_custom_app_empty)
                setTextColor(ctx.getColor(R.color.md_onSurfaceVariant))
                textSize = 14f
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            })
        }
        customAppDialog = UnifiedDialogKit.showForm(
            ctx,
            view,
            title = getString(R.string.settings_custom_target_app),
            positiveText = "完成",
            negativeText = null
        )
        btnPickFromInstalled.setOnClickListener {
            customAppDialog?.dismiss()
            showAppPickerDialog()
        }
        btnManualInput.setOnClickListener {
            customAppDialog?.dismiss()
            showCustomAppTextDialog()
        }
    }

    /** 从已安装应用中选择（应用列表弹窗） */
    private fun showAppPickerDialog() {
        val pm = ctx.packageManager
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val allApps = pm.queryIntentActivities(launchIntent, 0)
            .filter { it.activityInfo.packageName != "com.pengxh.daily.app" }
            .sortedBy { resolveAppLabel(it.activityInfo.packageName) }
        if (allApps.isEmpty()) {
            getString(R.string.settings_custom_app_no_apps).show(ctx)
            return
        }
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_app_picker, null)
        val listView = view.findViewById<ListView>(R.id.appListView)
        listView.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (320 * resources.displayMetrics.density).toInt()
        )
        listView.divider = null
        listView.selector = ctx.getDrawable(android.R.drawable.list_selector_background)
        listView.adapter = object : android.widget.BaseAdapter() {
            override fun getCount() = allApps.size
            override fun getItem(position: Int) = allApps[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val item = LayoutInflater.from(ctx).inflate(R.layout.dialog_app_picker_item, parent, false)
                val info = allApps[position]
                item.findViewById<ImageView>(R.id.appIcon).setImageDrawable(info.activityInfo.loadIcon(pm))
                item.findViewById<TextView>(R.id.appLabel).text = info.activityInfo.loadLabel(pm)
                return item
            }
        }
        val dialog = UnifiedDialogKit.showForm(
            ctx,
            view,
            title = getString(R.string.settings_pick_app_title),
            positiveText = getString(android.R.string.cancel),
            negativeText = null
        )
        listView.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            dialog.dismiss()
            addCustomApp(allApps[position].activityInfo.packageName)
        }
    }

    /** 手动输入自定义应用包名 */
    private fun showCustomAppTextDialog() {
        val editText = EditText(ctx).apply {
            setText(Constant.getCustomTargetApps().joinToString("\n"))
            hint = "每行一个包名，例如：\ncom.example.punchapp"
            isSingleLine = false
            minLines = 3
            gravity = Gravity.START or Gravity.TOP
            setPadding(
                resources.getDimensionPixelSize(R.dimen.dp_16),
                resources.getDimensionPixelSize(R.dimen.dp_12),
                resources.getDimensionPixelSize(R.dimen.dp_16),
                resources.getDimensionPixelSize(R.dimen.dp_12)
            )
        }
        UnifiedDialogKit.showForm(
            ctx, editText,
            title = getString(R.string.settings_custom_target_app),
            message = getString(R.string.settings_custom_app_manual_hint),
            positiveText = getString(android.R.string.ok),
            negativeText = getString(android.R.string.cancel)
        ) { dialog ->
            val packages = parsePackageList(editText.text.toString())
            val invalid = packages.filter { !isValidPackageName(it) }
            if (invalid.isEmpty()) {
                SaveKeyValues.saveString(Constant.CUSTOM_TARGET_APPS_KEY, packages.joinToString(","))
                val selected = SaveKeyValues.loadString(Constant.CUSTOM_TARGET_SELECTED_KEY, "")
                if (SaveKeyValues.loadInt(Constant.TARGET_APP_KEY, 0) == Constant.CUSTOM_TARGET_INDEX && !packages.contains(selected)) {
                    SaveKeyValues.saveInt(Constant.TARGET_APP_KEY, 0)
                    SaveKeyValues.saveString(Constant.CUSTOM_TARGET_SELECTED_KEY, "")
                }
                applyTargetAppIcon()
                ConfigImportSignal.notifyRemoteChanged(ctx)
                "已保存自定义打卡应用".show(ctx)
                true
            } else {
                "无效的包名格式：${invalid.joinToString()}".show(ctx)
                false
            }
        }
    }

    /** 开启截屏服务提醒 */
    private fun showCaptureEnableWarning(onConfirm: () -> Unit) {
        DialogCardBuilder.show(
            ctx,
            getString(R.string.settings_capture_enable_warning_title),
            DialogCardBuilder.CardSpec(
                notice = getString(R.string.settings_capture_enable_warning_msg) to DialogCardBuilder.NoticeKind.WARN
            ),
            positiveText = "继续开启",
            cancelable = false,
            onConfirm = onConfirm
        )
    }

    /** 开启无障碍服务提醒 */
    private fun showAccessibilityEnableWarning() {
        DialogCardBuilder.show(
            ctx,
            getString(R.string.settings_accessibility_enable_warning_title),
            DialogCardBuilder.CardSpec(
                notice = getString(R.string.settings_accessibility_enable_warning_msg) to DialogCardBuilder.NoticeKind.WARN
            ),
            positiveText = "前往开启",
            cancelable = false,
            onConfirm = {
                ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        )
    }

    private fun screenModeLabel(mode: Int): String = when (mode) {
        Constant.SCREEN_MODE_OFF -> getString(R.string.settings_screen_mode_off)
        Constant.SCREEN_MODE_KEEP_ON -> getString(R.string.settings_screen_mode_keep_on)
        else -> getString(R.string.settings_screen_mode_pseudo)
    }

    private fun refreshScreenModeRow() {
        val forceOn = AppRuntimeConfig.isForcePseudoMask()
        binding.screenModeLayout.isEnabled = !forceOn
        binding.screenModeLayout.alpha = if (forceOn) 0.45f else 1f
        binding.screenModeTipText.setText(
            if (forceOn) R.string.settings_screen_mode_tip_disabled
            else R.string.settings_screen_mode_tip
        )
        binding.screenModeValueText.text = screenModeLabel(AppRuntimeConfig.getScreenMode())
    }

    private fun showScreenModeDialog() {
        val current = AppRuntimeConfig.getScreenMode()
            .coerceIn(Constant.SCREEN_MODE_PSEUDO, Constant.SCREEN_MODE_KEEP_ON)
        val items = listOf(
            "${getString(R.string.settings_screen_mode_pseudo)}：${getString(R.string.settings_screen_mode_pseudo_desc)}",
            "${getString(R.string.settings_screen_mode_off)}：${getString(R.string.settings_screen_mode_off_desc)}",
            "${getString(R.string.settings_screen_mode_keep_on)}：${getString(R.string.settings_screen_mode_keep_on_desc)}"
        )
        UnifiedDialogKit.showSingleChoice(
            ctx,
            getString(R.string.settings_screen_mode_dialog_title),
            items,
            current
        ) { which ->
            val applyMode = {
                AppRuntimeConfig.setScreenMode(which)
                refreshScreenModeRow()
                ConfigImportSignal.notifyRemoteChanged(ctx)
            }
            // 息屏模式：高风险切换。先弹提示确认（三步前置设置 + 风险声明），确认后才应用，取消则保持原模式
            if (which == Constant.SCREEN_MODE_OFF) {
                UnifiedDialogKit.showConfirm(
                    ctx,
                    getString(R.string.settings_screen_mode_off_confirm_title),
                    getString(R.string.settings_screen_mode_off_confirm_msg),
                    confirmText = getString(R.string.settings_screen_mode_off_confirm_positive),
                    cancelText = getString(R.string.settings_screen_mode_off_confirm_negative),
                    icon = UnifiedDialogKit.IconType.WARNING,
                    onConfirm = { applyMode() }
                )
            } else {
                applyMode()
            }
        }
    }

    /** 伪息屏延迟设置滑杆（非线性档位：时间越大档位间隔越大，缩短滑块行程） */
    private fun showPseudoMaskDelayDialog(current: Int) {
        // 离散档位：10~50 步进5，60~100 步进10，120~180 步进30，之后间隔逐级放大直至 3600
        val options = intArrayOf(
            10, 15, 20, 25, 30, 35, 40, 45, 50,
            60, 70, 80, 90, 100, 120, 150, 180,
            240, 300, 420, 600, 900, 1200, 1800, 2700, 3600
        )
        val bindingDlg = DialogSliderBinding.inflate(LayoutInflater.from(ctx))
        val index = nearestIndex(options, current)
        bindingDlg.tvSliderValue.text = "${options[index]} 秒"
        bindingDlg.slider.valueFrom = 0f
        bindingDlg.slider.valueTo = (options.size - 1).toFloat()
        bindingDlg.slider.stepSize = 1f
        bindingDlg.slider.value = index.toFloat()
        bindingDlg.slider.addOnChangeListener { _, value, _ ->
            bindingDlg.tvSliderValue.text = "${options[value.toInt()]} 秒"
        }
        UnifiedDialogKit.showForm(
            ctx,
            bindingDlg.root,
            title = getString(R.string.settings_pseudo_mask_delay_title),
            message = getString(R.string.settings_pseudo_mask_delay_tip),
            positiveText = getString(android.R.string.ok),
            negativeText = getString(android.R.string.cancel),
            onConfirm = {
                val delay = options[bindingDlg.slider.value.toInt().coerceIn(0, options.lastIndex)]
                SaveKeyValues.saveInt(Constant.IDLE_PSEUDO_MASK_TIMEOUT_KEY, delay)
                binding.pseudoMaskDelayValueText.text = getString(R.string.settings_pseudo_mask_delay_value, delay)
                ConfigImportSignal.notifyRemoteChanged(ctx)
                true
            }
        )
    }

    /** 在离散档位数组中返回最接近 value 的下标 */
    private fun nearestIndex(options: IntArray, value: Int): Int {
        var best = 0
        var bestDiff = Int.MAX_VALUE
        for (i in options.indices) {
            val diff = kotlin.math.abs(options[i] - value)
            if (diff < bestDiff) {
                bestDiff = diff
                best = i
            }
        }
        return best
    }

    /** 状态报告：统一弹窗 + 可滚动纯文本（不再用 WebView/HTML） */
    private fun showStatusReportDialog(report: String) {
        val density = resources.displayMetrics.density
        val padH = (8 * density).toInt()
        val textView = TextView(ctx).apply {
            text = report
            textSize = 13f
            setTextIsSelectable(true)
            setTextColor(ContextCompat.getColor(ctx, R.color.md_onSurface))
            setLineSpacing(2 * density, 1f)
            setPadding(padH, 0, padH, 0)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val scroll = ScrollView(ctx).apply {
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            isFillViewport = true
            addView(
                textView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.55).toInt()
            )
        }
        UnifiedDialogKit.showForm(
            ctx = ctx,
            contentView = scroll,
            title = "状态查询",
            positiveText = "关闭",
            negativeText = null
        )
    }

    /** 版本信息 */
    private fun showVersionInfo() {
        val appInfo = ctx.packageManager.getApplicationInfo(ctx.packageName, 0)
        val info = linkedMapOf(
            "Git 提交" to BuildConfig.GIT_SHA,
            "版本号" to BuildConfig.VERSION_NAME,
            "Version Code" to BuildConfig.VERSION_CODE.toString(),
            "构建来源" to BuildConfig.BUILD_SOURCE,
            "构建时间" to BuildConfig.BUILD_TIME,
            "基线版本" to BuildConfig.BASELINE_VERSION,
            "包名" to ctx.packageName,
            "Target SDK" to appInfo.targetSdkVersion.toString(),
            "Min SDK" to appInfo.minSdkVersion.toString()
        )
        val versionText = info.entries.joinToString("\n") { "${it.key}：${it.value}" }
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 8)
        }
        info.forEach { (key, value) ->
            container.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 8, 0, 8)
                addView(TextView(ctx).apply {
                    text = key
                    textSize = 14f
                    setTextColor(resources.getColor(R.color.md_onSurfaceVariant, theme))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.5f)
                })
                addView(TextView(ctx).apply {
                    text = value
                    textSize = 14f
                    setTextColor(resources.getColor(R.color.md_onSurface, theme))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
            })
        }
        UnifiedDialogKit.showForm(
            ctx,
            container,
            title = "版本信息",
            positiveText = "复制全部",
            negativeText = "关闭",
            onConfirm = {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("版本信息", versionText.trimEnd()))
                "已复制全部版本信息到剪贴板".show(ctx)
                true
            }
        )
    }

    /** 开启通知监听服务（启用组件 + 周期性检查） */
    private fun turnOnNotificationMonitorService() {
        lifecycleScope.launch(Dispatchers.IO) {
            val cn = android.content.ComponentName(ctx, NotificationMonitorService::class.java)
            try {
                ctx.packageManager.setComponentEnabledSetting(
                    cn, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP
                )
                while (true) {
                    delay(500)
                    if (!isActive) break
                }
            } catch (e: Exception) {
                Log.e(kTag, "开启通知监听服务失败", e)
            }
        }
    }
}
