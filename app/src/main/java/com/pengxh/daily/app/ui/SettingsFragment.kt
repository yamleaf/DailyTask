package com.pengxh.daily.app.ui

import android.app.Activity
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
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.pengxh.daily.app.BuildConfig
import com.pengxh.daily.app.R
import com.pengxh.daily.app.databinding.DialogSliderBinding
import com.pengxh.daily.app.databinding.FragmentSettingsBinding
import com.pengxh.daily.app.extensions.isApplicationExist
import com.pengxh.daily.app.extensions.notificationEnable
import com.pengxh.daily.app.service.AutoProjectionAccessibilityService
import com.pengxh.daily.app.service.CaptureImageService
import com.pengxh.daily.app.service.FloatingWindowService
import com.pengxh.daily.app.service.NotificationMonitorService
import com.pengxh.daily.app.utils.AppRuntimeConfig
import com.pengxh.daily.app.utils.ChinaHolidayManager
import com.pengxh.daily.app.utils.ConfigImportSignal
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.DailyTask
import com.pengxh.daily.app.utils.DiagnosticReporter
import com.pengxh.daily.app.utils.LogFileManager
import com.pengxh.daily.app.utils.MessageDispatcher
import com.pengxh.daily.app.utils.ProjectionEvent
import com.pengxh.daily.app.utils.ProjectionSession
import com.pengxh.daily.app.utils.StatusReporter
import com.pengxh.daily.app.utils.WatermarkDrawable
import com.pengxh.kt.lite.base.KotlinBaseFragment
import com.pengxh.kt.lite.extensions.convertColor
import com.pengxh.kt.lite.extensions.show
import com.pengxh.kt.lite.utils.LoadingDialog
import com.pengxh.kt.lite.utils.SaveKeyValues
import com.pengxh.kt.lite.widget.dialog.BottomActionSheet
import com.yample.mqttprotocol.ThemeManager
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
            if (Settings.canDrawOverlays(ctx)) {
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
        binding.appVersion.text = BuildConfig.VERSION_NAME
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
        // 截屏服务事件 → 失败时回切通知源
        lifecycleScope.launch {
            CaptureImageService.projectionEvents.collect { event ->
                when (event) {
                    is ProjectionEvent.Ready -> Unit
                    is ProjectionEvent.Failed -> {
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
                        val inbox = cfg?.get("inbox")?.asString ?: ""
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
        binding.chainStartRow.setOnClickListener { checkChainStartPermission() }
        // 电量预警分组
        binding.batteryAlertGroupSwitch.setOnCheckedChangeListener { _, checked ->
            if (syncingSwitchState) return@setOnCheckedChangeListener
            SaveKeyValues.saveBoolean(Constant.BATTERY_SMART_ALERT_ENABLED_KEY, checked)
            com.pengxh.daily.app.service.KeepAliveReceiver.scheduleBatteryAlert(ctx)
        }
        binding.batterySmartAlertSwitch.setOnCheckedChangeListener { _, checked ->
            if (syncingSwitchState) return@setOnCheckedChangeListener
            SaveKeyValues.saveBoolean(Constant.BATTERY_SMART_ALERT_ENABLED_KEY, checked)
            binding.batteryAlertGroupSwitch.isChecked = checked
            com.pengxh.daily.app.service.KeepAliveReceiver.scheduleBatteryAlert(ctx)
        }
        binding.batteryWarningTimeRow.setOnClickListener { showBatteryWarningTimePicker() }
        binding.batteryAlertRangeRow.setOnClickListener { showBatteryAlertRangePicker() }
        binding.batteryThresholdRow.setOnClickListener { showBatteryThresholdPicker() }
        binding.batteryStageCountRow.setOnClickListener { showBatteryStageCountPicker() }
        var batteryGroupExpanded = false
        binding.batteryAlertGroupHeader.setOnClickListener {
            batteryGroupExpanded = !batteryGroupExpanded
            binding.batteryAlertGroupContent.visibility = if (batteryGroupExpanded) View.VISIBLE else View.GONE
            binding.batteryAlertGroupArrow.animate()
                .rotation(if (batteryGroupExpanded) 180f else 0f)
                .setDuration(200)
                .start()
        }
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
                    val html = StatusReporter.buildStatusReportHtml(ctx, NotificationMonitorService.isListenerConnected())
                    withContext(Dispatchers.Main) { showStatusReportDialog(html) }
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
                UnifiedDialogKit.showWarning(
                    ctx,
                    "开启手势识别？",
                    "开启后，双指在屏幕上滑动可开启/关闭伪熄屏：双指下滑开启伪熄屏，双指上滑关闭。\n\n" +
                        "单指滑动不受影响，可正常操作本软件。",
                    confirmText = "确认开启",
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
        binding.powerSaveSwitch.setOnCheckedChangeListener { _, checked ->
            if (syncingSwitchState) return@setOnCheckedChangeListener
            AppRuntimeConfig.setPowerSaveMode(checked)
            ConfigImportSignal.notifyRemoteChanged(ctx)
        }
        // 强制伪息屏主开关 + 分组开关（联动 + 确认弹窗）
        val applyForceMask = { enabled: Boolean ->
            AppRuntimeConfig.setForcePseudoMask(enabled)
            if (enabled) "伪熄屏已开启".show(ctx)
            syncingSwitchState = true
            binding.forcePseudoMaskSwitch.isChecked = enabled
            binding.pseudoMaskGroupSwitch.isChecked = enabled
            syncingSwitchState = false
            ConfigImportSignal.notifyRemoteChanged(ctx)
        }
        val confirmForceMask = { enabled: Boolean, onConfirm: (Boolean) -> Unit ->
            if (!enabled) {
                onConfirm(false)
            } else {
                UnifiedDialogKit.showWarning(
                    ctx,
                    "开启伪熄屏？",
                    "离开本软件超过设定时间（默认 60 秒）后，以及在本软件前台无操作超过设定时间，都将自动进入伪熄屏模式。\n\n" +
                        "⚠ 可能打断其他 App 使用；适合无人值守挂机，白天操作手机建议关闭。",
                    confirmText = "确认开启",
                    cancelable = false,
                    onCancel = {
                        syncingSwitchState = true
                        binding.forcePseudoMaskSwitch.isChecked = false
                        binding.pseudoMaskGroupSwitch.isChecked = false
                        syncingSwitchState = false
                    },
                    onConfirm = { onConfirm(true) }
                )
            }
        }
        binding.forcePseudoMaskSwitch.setOnCheckedChangeListener { _, checked ->
            if (syncingSwitchState) return@setOnCheckedChangeListener
            confirmForceMask(checked, applyForceMask)
        }
        binding.pseudoMaskGroupSwitch.setOnCheckedChangeListener { _, checked ->
            if (syncingSwitchState) return@setOnCheckedChangeListener
            confirmForceMask(checked, applyForceMask)
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
        // 伪息屏分组折叠
        var groupExpanded = false
        binding.pseudoMaskGroupHeader.setOnClickListener {
            groupExpanded = !groupExpanded
            binding.pseudoMaskGroupContent.visibility = if (groupExpanded) View.VISIBLE else View.GONE
            binding.pseudoMaskGroupArrow.animate()
                .rotation(if (groupExpanded) 180f else 0f)
                .setDuration(200)
                .start()
        }
        binding.introduceLayout.setOnClickListener {
            ctx.startActivity(Intent(ctx, QuestionAndAnswerActivity::class.java))
        }
        binding.diagnosticLayout.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                val report = DiagnosticReporter.buildReport(ctx)
                withContext(Dispatchers.Main) { showStatusReportDialog(report) }
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
    }

    /** 同步各开关到持久化存储（避免 UI 与状态不一致） */
    private fun syncSettingsUiFromStore() {
        syncingSwitchState = true
        try {
            binding.gestureDetectSwitch.isChecked = SaveKeyValues.loadBoolean(Constant.GESTURE_DETECTOR_KEY, true)
            binding.backToHomeSwitch.isChecked = SaveKeyValues.loadBoolean(Constant.BACK_TO_HOME_KEY, false)
            binding.powerSaveSwitch.isChecked = AppRuntimeConfig.isPowerSaveMode()
            binding.forcePseudoMaskSwitch.isChecked = AppRuntimeConfig.isForcePseudoMask()
            binding.pseudoMaskGroupSwitch.isChecked = AppRuntimeConfig.isForcePseudoMask()
            val delay = SaveKeyValues.loadInt(Constant.IDLE_PSEUDO_MASK_TIMEOUT_KEY, 60).coerceIn(10, 3600)
            binding.pseudoMaskDelayValueText.text = getString(R.string.settings_pseudo_mask_delay_value, delay)
            binding.pseudoMaskNoClockSwitch.isChecked = SaveKeyValues.loadBoolean(Constant.PSEUDO_MASK_NO_CLOCK_KEY, false)
            binding.transferSwitch.isChecked = SaveKeyValues.loadBoolean(Constant.NOTIFICATION_TRANSFER_KEY, false)
            // 链式启动权限状态初始显示
            binding.chainStartStatusView.text = "未检测"
            binding.chainStartStatusView.setTextColor(R.color.md_onSurfaceVariant.convertColor(ctx))
            // 电量智能预警
            val alertEnabled = SaveKeyValues.loadBoolean(Constant.BATTERY_SMART_ALERT_ENABLED_KEY, false)
            binding.batteryAlertGroupSwitch.isChecked = alertEnabled
            binding.batterySmartAlertSwitch.isChecked = alertEnabled
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

    /**
     * 链式启动权限检测：判断本应用能否从后台拉起其他应用（打卡应用）。
     *
     * Android 12+（API 31+）引入了 START_ACTIVITIES_FROM_BACKGROUND 权限限制后台启动 Activity。
     * 国产 ROM（MIUI/ColorOS/EMUI 等）还有额外的「链式启动/关联启动」系统级开关，无标准 API 可查。
     *
     * 点击此行：先检测 API 31+ 权限，再弹引导对话框，提供「尝试启动」按钮直接拉起目标应用验证；
     * 拉起失败时再引导去系统设置授权。
     */
    private fun checkChainStartPermission() {
        val targetApp = Constant.getTargetApp()
        if (targetApp.isBlank()) {
            "请先选择目标打卡应用".show(ctx)
            return
        }

        // API 31+ 检查 START_ACTIVITIES_FROM_BACKGROUND 权限
        var granted = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                granted = ctx.checkSelfPermission("android.permission.START_ACTIVITIES_FROM_BACKGROUND") ==
                    PackageManager.PERMISSION_GRANTED
            } catch (_: Exception) {
                granted = false
            }
        }

        val statusText: String
        val statusColor: Int
        val tipText: String
        if (granted) {
            statusText = "已授权"
            statusColor = R.color.md_tertiary
            tipText = "系统已授予后台启动权限，可正常拉起打卡应用"
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            statusText = "旧系统"
            statusColor = R.color.md_onSurfaceVariant
            tipText = "系统低于 Android 12，无后台启动限制；部分国产 ROM 仍需手动开启「链式启动」"
        } else {
            statusText = "待授权"
            statusColor = R.color.md_warning
            tipText = "Android 12+ 需授权后台启动；国产 ROM 可能还有「链式启动/关联启动」开关"
        }
        binding.chainStartStatusView.text = statusText
        binding.chainStartStatusView.setTextColor(statusColor.convertColor(ctx))
        binding.chainStartTipsView.text = tipText
        binding.chainStartTipsView.setTextColor(R.color.md_onSurfaceVariant.convertColor(ctx))

        // 已授权：直接尝试拉起一次，验证可正常启动
        if (granted) {
            tryStartTargetApp()
            return
        }

        // 待授权 / 旧系统：弹引导对话框，提供「尝试启动」验证；失败时再引导前往设置
        UnifiedDialogKit.showForm(
            ctx = ctx,
            contentView = TextView(ctx).apply {
                text = "链式启动权限用于本应用在后台拉起打卡应用（$targetApp）。\n\n" +
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                        "系统当前未授予「后台启动其它应用」权限，尝试启动可能被系统拦截。若启动失败，请前往系统设置开启「后台启动其它应用」。"
                    else
                        "系统低于 Android 12 无此限制，但部分国产 ROM 仍需在「自启动 / 关联启动」中允许本应用。\n\n当前状态：$statusText"
                setPadding(24, 16, 24, 16)
            },
            title = "链式启动权限",
            positiveText = "尝试启动",
            negativeText = "知道了",
            onConfirm = {
                tryStartTargetApp()
                true
            }
        )
    }

    /** 尝试拉起目标打卡应用（链式启动验证）；失败时提示并引导前往系统设置 */
    private fun tryStartTargetApp() {
        val targetApp = Constant.getTargetApp()
        if (targetApp.isBlank()) {
            "请先选择目标打卡应用".show(ctx)
            return
        }
        if (!ctx.isApplicationExist(targetApp)) {
            "未安装目标应用：$targetApp".show(ctx)
            return
        }
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
        if (activities.isEmpty()) {
            openChainStartFailDialog("未找到目标应用的启动入口（包名：$targetApp）")
            return
        }
        val info = activities.first()
        intent.component = ComponentName(info.activityInfo.packageName, info.activityInfo.name)
        try {
            ctx.startActivity(intent)
            "已尝试启动 $targetApp，3 秒后自动返回当前应用".show(ctx)
            // 成功拉起目标应用后，3s 自动返回当前应用，作为链式启动权限生效的验证
            lifecycleScope.launch {
                delay(3000)
                if (!isAdded) return@launch
                try {
                    val back = Intent(ctx, MainActivity::class.java).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        )
                    }
                    ctx.startActivity(back)
                    "链式启动权限已生效，已自动返回当前应用".show(ctx)
                } catch (e: Exception) {
                    "自动返回当前应用失败：${e.message}".show(ctx)
                }
            }
        } catch (e: Exception) {
            openChainStartFailDialog("启动失败：${e.message}")
        }
    }

    /** 链式启动尝试失败提示：说明原因并提供「前往设置」入口 */
    private fun openChainStartFailDialog(message: String) {
        UnifiedDialogKit.showForm(
            ctx = ctx,
            contentView = TextView(ctx).apply {
                text = "$message\n\n可能被系统限制后台启动。请前往本应用的系统权限页，开启「后台启动其它应用」（国产 ROM 另需允许「链式启动/关联启动」）。"
                setPadding(24, 16, 24, 16)
            },
            title = "启动失败",
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
        val closeBtn = view.findViewById<ImageView>(R.id.closeBtn)
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
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setView(view)
            .setCancelable(true)
            .create()
        dialog.show()
        customAppDialog = dialog
        closeBtn.setOnClickListener { dialog.dismiss() }
        btnPickFromInstalled.setOnClickListener {
            dialog.dismiss()
            showAppPickerDialog()
        }
        btnManualInput.setOnClickListener {
            dialog.dismiss()
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
        val dialog = MaterialAlertDialogBuilder(ctx).setView(view).setCancelable(true).create()
        dialog.show()
        listView.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            dialog.dismiss()
            addCustomApp(allApps[position].activityInfo.packageName)
        }
        view.findViewById<ImageView>(R.id.closeBtn).setOnClickListener { dialog.dismiss() }
        view.findViewById<TextView>(R.id.btnCancel).setOnClickListener { dialog.dismiss() }
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
        UnifiedDialogKit.showWarning(
            ctx,
            getString(R.string.settings_capture_enable_warning_title),
            getString(R.string.settings_capture_enable_warning_msg),
            confirmText = "继续开启",
            cancelable = false,
            onConfirm = onConfirm
        )
    }

    /** 开启无障碍服务提醒 */
    private fun showAccessibilityEnableWarning() {
        UnifiedDialogKit.showWarning(
            ctx,
            getString(R.string.settings_accessibility_enable_warning_title),
            getString(R.string.settings_accessibility_enable_warning_msg),
            confirmText = "前往开启",
            cancelable = false,
            onConfirm = {
                ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        )
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

    /** 状态报告展示（WebView 渲染 HTML） */
    private fun showStatusReportDialog(html: String) {
        val density = resources.displayMetrics.density
        val pad = (12 * density).toInt()
        val webView = android.webkit.WebView(ctx).apply {
            settings.javaScriptEnabled = false
            settings.defaultTextEncodingName = "UTF-8"
            setBackgroundColor(ctx.getColor(R.color.md_surface))
        }
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 0, pad, 0)
            addView(
                webView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (resources.displayMetrics.heightPixels * 0.6).toInt()
                )
            )
        }
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(null)
            .setView(container)
            .setPositiveButton("关闭", null)
            .create()
        dialog.setOnDismissListener {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        dialog.show()
        dialog.window?.setLayout(resources.displayMetrics.widthPixels - pad * 2, ViewGroup.LayoutParams.WRAP_CONTENT)
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    /** 版本信息 */
    private fun showVersionInfo() {
        val appInfo = ctx.packageManager.getApplicationInfo(ctx.packageName, 0)
        val info = linkedMapOf(
            "版本号" to BuildConfig.VERSION_NAME,
            "Version Code" to BuildConfig.VERSION_CODE.toString(),
            "构建来源" to BuildConfig.BUILD_SOURCE,
            "Git 提交" to BuildConfig.GIT_SHA,
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
                    setTextColor(resources.getColor(R.color.text_hint_color, theme))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.5f)
                })
                addView(TextView(ctx).apply {
                    text = value
                    textSize = 14f
                    setTextColor(resources.getColor(R.color.text_default_color, theme))
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
