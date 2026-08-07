package com.pengxh.daily.app.ui

import android.app.Activity
import android.app.Dialog
import android.content.BroadcastReceiver
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
import com.pengxh.daily.app.extensions.notificationEnable
import com.pengxh.daily.app.extensions.openApplication
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
import com.pengxh.daily.app.utils.UnifiedDialogKit
import com.pengxh.daily.app.utils.WatermarkDrawable
import com.pengxh.kt.lite.base.KotlinBaseFragment
import com.pengxh.kt.lite.extensions.convertColor
import com.pengxh.kt.lite.extensions.show
import com.pengxh.kt.lite.utils.LoadingDialog
import com.pengxh.kt.lite.utils.SaveKeyValues
import com.pengxh.kt.lite.widget.dialog.BottomActionSheet
import com.yample.mqttprotocol.ThemeManager
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
                                if (!binding.captureSwitch.isChecked &&
                                    !binding.accessibilitySwitch.isChecked
                                ) {
                                    "请先打开截屏服务或无障碍服务".show(ctx)
                                    return
                                }
                                SaveKeyValues.saveInt(Constant.RESULT_SOURCE_KEY, if (binding.captureSwitch.isChecked) 1 else 2)
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
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("获取控制端 DailyController")
                    .setMessage("当前未配置控制端下载地址。控制端安装包由分发方通过构建参数注入，请向提供者获取安装方式。")
                    .setPositiveButton("知道了", null)
                    .show()
            } else {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        }
        binding.themeValueView.text = ThemeManager.labelOf(ThemeManager.getMode(ctx))
        binding.themeRow.setOnClickListener {
            val current = ThemeManager.getMode(ctx)
            MaterialAlertDialogBuilder(ctx)
                .setTitle("主题外观")
                .setSingleChoiceItems(ThemeManager.LABELS, current) { dialog, which ->
                    dialog.dismiss()
                    if (which != current) {
                        ThemeManager.setMode(ctx, which)
                        requireActivity().recreate()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
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
                            "通知转移依赖邮箱配置，请先完善邮箱与授权码".show(ctx)
                        }
                    }

                    1 -> {
                        if (SaveKeyValues.loadString(Constant.WX_WEB_HOOK_KEY, "").isBlank()) {
                            "通知转移依赖企业微信配置，请先填写企业微信 Webhook".show(ctx)
                        }
                    }

                    else -> "请先在设置中配置消息渠道（邮箱/企业微信）".show(ctx)
                }
            }
            ConfigImportSignal.notifyRemoteChanged(ctx)
        }
        binding.openTestLayout.setOnClickListener {
            ctx.openApplication()
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
            SaveKeyValues.saveBoolean(Constant.GESTURE_DETECTOR_KEY, checked)
            ConfigImportSignal.notifyRemoteChanged(ctx)
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
            if (enabled) "强制伪息屏已开启".show(ctx)
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
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("开启强制伪息屏？")
                    .setMessage(
                        "离开本软件超过设定时间（默认 60 秒）后将自动进入伪息屏模式。\n\n" +
                            "⚠ 可能打断其他 App 使用；适合无人值守挂机，白天操作手机建议关闭。"
                    )
                    .setNegativeButton("取消") { dialog, _ ->
                        syncingSwitchState = true
                        binding.forcePseudoMaskSwitch.isChecked = false
                        binding.pseudoMaskGroupSwitch.isChecked = false
                        syncingSwitchState = false
                        dialog.dismiss()
                    }
                    .setPositiveButton("确认开启") { _, _ -> onConfirm(true) }
                    .setCancelable(false)
                    .show()
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
    }

    override fun onPause() {
        super.onPause()
        runCatching { ctx.unregisterReceiver(remoteConfigReceiver) }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && isAdded) refreshUi()
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
                item.findViewById<TextView>(R.id.appPkg).text = info.activityInfo.packageName
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
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.settings_capture_enable_warning_title)
            .setMessage(R.string.settings_capture_enable_warning_msg)
            .setNegativeButton("取消", null)
            .setPositiveButton("继续开启") { _, _ -> onConfirm() }
            .setCancelable(false)
            .show()
    }

    /** 开启无障碍服务提醒 */
    private fun showAccessibilityEnableWarning() {
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.settings_accessibility_enable_warning_title)
            .setMessage(R.string.settings_accessibility_enable_warning_msg)
            .setNegativeButton("取消", null)
            .setPositiveButton("前往开启") { _, _ ->
                ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setCancelable(false)
            .show()
    }

    /** 伪息屏延迟设置滑杆 */
    private fun showPseudoMaskDelayDialog(current: Int) {
        val bindingDlg = DialogSliderBinding.inflate(LayoutInflater.from(ctx))
        bindingDlg.tvSliderValue.text = "$current 秒"
        bindingDlg.slider.valueFrom = 10f
        bindingDlg.slider.valueTo = 3600f
        bindingDlg.slider.stepSize = 10f
        bindingDlg.slider.value = current.toFloat().coerceIn(10f, 3600f)
        bindingDlg.slider.addOnChangeListener { _, value, _ ->
            bindingDlg.tvSliderValue.text = "${value.toInt()} 秒"
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.settings_pseudo_mask_delay_title)
            .setMessage(R.string.settings_pseudo_mask_delay_tip)
            .setView(bindingDlg.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val delay = bindingDlg.slider.value.toInt().coerceIn(10, 3600)
                SaveKeyValues.saveInt(Constant.IDLE_PSEUDO_MASK_TIMEOUT_KEY, delay)
                binding.pseudoMaskDelayValueText.text = getString(R.string.settings_pseudo_mask_delay_value, delay)
                ConfigImportSignal.notifyRemoteChanged(ctx)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
        MaterialAlertDialogBuilder(ctx)
            .setTitle("版本信息")
            .setView(container)
            .setPositiveButton("复制全部") { _, _ ->
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("版本信息", versionText.trimEnd()))
                "已复制全部版本信息到剪贴板".show(ctx)
            }
            .setNegativeButton("关闭", null)
            .show()
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
