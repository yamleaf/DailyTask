package com.pengxh.daily.app.ui

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.ClipData
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.text.InputFilter
import android.text.InputType
import android.view.View
import java.io.File
import android.webkit.WebView
import android.widget.Toast
import android.app.Dialog
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.view.LayoutInflater
import android.view.ViewGroup
import android.content.pm.ResolveInfo
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pengxh.daily.app.BuildConfig
import com.pengxh.daily.app.R
import com.pengxh.daily.app.databinding.ActivitySettingsBinding
import com.pengxh.daily.app.extensions.notificationEnable
import com.pengxh.daily.app.extensions.openApplication
import com.pengxh.daily.app.service.AutoProjectionAccessibilityService
import com.pengxh.daily.app.service.CaptureImageService
import com.pengxh.daily.app.service.FloatingWindowService
import com.pengxh.daily.app.service.NotificationMonitorService
import com.pengxh.daily.app.service.ForegroundRunningService
import com.pengxh.daily.app.service.KeepAliveReceiver
import com.pengxh.daily.app.utils.AppRuntimeConfig
import com.pengxh.daily.app.utils.ChinaHolidayManager
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.DailyTask
import com.pengxh.daily.app.utils.DiagnosticReporter
import com.pengxh.daily.app.utils.ConfigImportSignal
import com.pengxh.daily.app.utils.ConfigStore
import com.pengxh.daily.app.utils.EmailSecureConfig
import com.pengxh.daily.app.utils.LogFileManager
import com.pengxh.daily.app.utils.MessageDispatcher
import com.pengxh.daily.app.utils.StatusReporter
import com.pengxh.daily.app.utils.ProjectionEvent
import com.pengxh.daily.app.utils.ProjectionSession
import com.pengxh.daily.app.utils.WatermarkDrawable
import com.pengxh.kt.lite.base.KotlinBaseActivity
import com.pengxh.kt.lite.extensions.convertColor
import com.pengxh.kt.lite.extensions.navigatePageTo
import com.pengxh.kt.lite.extensions.show
import com.pengxh.kt.lite.utils.LoadingDialog
import com.pengxh.kt.lite.utils.SaveKeyValues
import com.pengxh.kt.lite.widget.dialog.BottomActionSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : KotlinBaseActivity<ActivitySettingsBinding>() {

    private val kTag = "SettingsActivity"
    private val context = this
    /** 内置目标应用图标（固定顺序，与 Constant.getBuiltInTargets() 对应） */
    private val builtInIcons by lazy {
        listOf(
            R.drawable.ic_ding_ding,
            R.drawable.ic_wei_xin,
            R.drawable.ic_fei_shu,
            R.mipmap.ic_mobile_m3
        )
    }
    /** 目标应用显示名：内置 4 项 + 末尾“自定义应用”入口（本地化） */
    private fun targetAppLabels(): List<String> =
        Constant.getBuiltInTargets().map { it.second } + listOf(getString(R.string.settings_custom_app_entry))
    private val channels = arrayListOf("QQ邮箱", "企业微信")
    private val resultSources = arrayListOf("通知", "截屏", "无障碍")
    private val feedbackModes = arrayListOf("截屏反馈", "文本反馈")
    private val permissionContract by lazy { ActivityResultContracts.StartActivityForResult() }
    private val notificationContract by lazy { ActivityResultContracts.StartActivityForResult() }
    private val projectionContract by lazy { ActivityResultContracts.StartActivityForResult() }
    private val mpr by lazy { getSystemService(MediaProjectionManager::class.java) }
    private var syncingSwitchState = false
    /** 远程控制端修改设置后，前台设置页即时刷新开关与数值（无需二次进入） */
    private val remoteConfigReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == ConfigImportSignal.ACTION_REMOTE_CONFIG_CHANGED) {
                ConfigImportSignal.pendingSettingsRefresh = false
                syncSettingsUiFromStore()
                applyTargetAppIcon()
            }
        }
    }

    override fun initViewBinding(): ActivitySettingsBinding {
        return ActivitySettingsBinding.inflate(layoutInflater)
    }

    /** 版本信息：点击设置页「当前版本」行弹出，列出构建元数据，支持一键复制 */
    private fun showVersionInfo() {
        val appInfo = packageManager.getApplicationInfo(packageName, 0)
        val targetSdk = appInfo.targetSdkVersion
        val minSdk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) appInfo.minSdkVersion else 0

        val rows = linkedMapOf(
            "版本号" to BuildConfig.VERSION_NAME,
            "Version Code" to BuildConfig.VERSION_CODE.toString(),
            "构建来源" to BuildConfig.BUILD_SOURCE,
            "Git 提交" to BuildConfig.GIT_SHA,
            "构建时间" to BuildConfig.BUILD_TIME,
            "基线版本" to BuildConfig.BASELINE_VERSION,
            "包名" to BuildConfig.APPLICATION_ID,
            "Target SDK" to targetSdk.toString(),
            "Min SDK" to minSdk.toString()
        )

        val sb = StringBuilder()
        rows.forEach { (k, v) -> sb.append("$k：$v\n") }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 8)
        }
        rows.forEach { (k, v) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 8, 0, 8)
            }
            row.addView(TextView(this).apply {
                text = k
                textSize = 13f
                setTextColor(resources.getColor(R.color.text_hint_color, theme))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.4f)
            })
            row.addView(TextView(this).apply {
                text = v
                textSize = 13f
                setTextColor(resources.getColor(R.color.text_default_color, theme))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            container.addView(row)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("版本信息")
            .setView(container)
            .setPositiveButton("复制全部") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("版本信息", sb.toString().trimEnd()))
                "已复制全部版本信息到剪贴板".show(this)
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    override fun setupTopBarLayout() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { view, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.setPadding(0, statusBarHeight, 0, 0)
            insets
        }
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    override fun initOnCreate(savedInstanceState: Bundle?) {
        applyTargetAppIcon()

        binding.appVersion.text = BuildConfig.VERSION_NAME
        binding.versionRow.setOnClickListener { showVersionInfo() }
        if (notificationEnable()) {
            turnOnNotificationMonitorService()
        }

        val watermark = DailyTask.getWatermarkText()
        binding.contentView.background = WatermarkDrawable(this, watermark)

        lifecycleScope.launch {
            ChinaHolidayManager.syncResult.collect { result ->
                when (result) {
                    is ChinaHolidayManager.SyncResult.Success -> {
                        LoadingDialog.dismiss()
                        result.content.show(context)
                    }

                    is ChinaHolidayManager.SyncResult.Error -> {
                        LoadingDialog.dismiss()
                        result.message.show(context)
                    }
                }
            }
        }

        // 监听通知服务状态
        lifecycleScope.launch {
            NotificationMonitorService.listenerState.collect { connected ->
                if (connected) {
                    binding.noticeSwitch.isChecked = true
                    binding.noticeTipsView.visibility = View.GONE
                } else {
                    binding.noticeTipsView.text = "服务未开启，无法监听打卡结果和接收远程指令"
                    binding.noticeTipsView.setTextColor(Color.RED)
                    binding.noticeSwitch.isChecked = false
                    binding.noticeTipsView.visibility = View.VISIBLE
                }
            }
        }

        // 监听截屏服务状态（MediaProjection 授权结果回传）
        lifecycleScope.launch {
            CaptureImageService.projectionEvents.collect { event ->
                when (event) {
                    ProjectionEvent.Ready -> {
                        binding.captureSwitch.isChecked = true
                        binding.captureTipsView.visibility = View.GONE
                        val sourceType = SaveKeyValues.loadInt(
                            Constant.RESULT_SOURCE_KEY, Constant.DEFAULT_INDEX
                        )
                        if (sourceType == 1) {
                            updateResultSourceView()
                        }
                    }

                    ProjectionEvent.Failed -> {
                        binding.captureSwitch.isChecked = false
                        binding.captureTipsView.text = "截屏服务未开启，无法获取打卡结果"
                        binding.captureTipsView.setTextColor(Color.RED)
                        binding.captureTipsView.visibility = View.VISIBLE
                        val targetApp = SaveKeyValues.loadInt(Constant.TARGET_APP_KEY, 0)
                        if (notificationEnable() && targetApp == 0) {
                            SaveKeyValues.saveInt(Constant.RESULT_SOURCE_KEY, 0)
                            updateResultSourceView()
                            "截屏服务已断开，已切换到通知模式".show(context)
                        } else {
                            updateResultSourceView()
                        }
                    }
                }
            }
        }
    }

    override fun observeRequestState() {

    }

    override fun initEvent() {
        // P1 底部悬浮导航：默认选中「设置」
        setupBottomNav(R.id.nav_settings)
        binding.targetAppLayout.setOnClickListener {
            val labels = targetAppLabels()
            val builtInCount = Constant.getBuiltInTargets().size
            BottomActionSheet.Builder()
                .setContext(this)
                .setActionItemTitle(labels)
                .setItemTextColor(R.color.theme_color.convertColor(this))
                .setOnActionSheetListener(object : BottomActionSheet.OnActionSheetListener {
                    override fun onActionItemClick(position: Int) {
                        // “自定义应用”入口：打开管理弹窗，不直接选中
                        if (position == builtInCount) {
                            showCustomAppManagerDialog()
                            return
                        }

                        val oldPosition = Constant.getTargetAppPosition()

                        if (oldPosition == position) {
                            applyTargetAppIcon()
                            return
                        }

                        if (position == 0) {
                            // 钉钉：默认通知监听，通知未开则降级截屏，两者都未开则降级无障碍
                            if (binding.noticeSwitch.isChecked) {
                                SaveKeyValues.saveInt(Constant.RESULT_SOURCE_KEY, 0)
                            } else if (binding.captureSwitch.isChecked) {
                                SaveKeyValues.saveInt(Constant.RESULT_SOURCE_KEY, 1)
                            } else if (binding.accessibilitySwitch.isChecked) {
                                SaveKeyValues.saveInt(Constant.RESULT_SOURCE_KEY, 2)
                            } else {
                                "请先打开通知监听、截屏服务或无障碍服务".show(context)
                                return
                            }
                        } else {
                            // 企业微信、飞书、移动办公M3：只能截屏或无障碍
                            if (binding.captureSwitch.isChecked) {
                                SaveKeyValues.saveInt(Constant.RESULT_SOURCE_KEY, 1)
                            } else if (binding.accessibilitySwitch.isChecked) {
                                SaveKeyValues.saveInt(Constant.RESULT_SOURCE_KEY, 2)
                            } else {
                                "请先打开截屏服务或无障碍服务".show(context)
                                return
                            }
                        }

                        // 保存选择：内置写索引，并清空自定义选中态
                        SaveKeyValues.saveInt(Constant.TARGET_APP_KEY, position)
                        SaveKeyValues.saveString(Constant.CUSTOM_TARGET_SELECTED_KEY, "")
                        applyTargetAppIcon()
                        updateResultSourceView()
                        ConfigImportSignal.notifyRemoteChanged(context)
                    }
                }).build().show()
        }

        binding.msgChannelLayout.setOnClickListener {
            navigatePageTo<MessageChannelActivity>()
        }

        // 结果来源：点击弹出选择
        binding.resultSourceLayout.setOnClickListener {
            BottomActionSheet.Builder()
                .setContext(this)
                .setActionItemTitle(resultSources)
                .setItemTextColor(R.color.theme_color.convertColor(this))
                .setOnActionSheetListener(object : BottomActionSheet.OnActionSheetListener {
                    override fun onActionItemClick(position: Int) {
                        when (position) {
                            0 -> {
                                val targetApp = SaveKeyValues.loadInt(Constant.TARGET_APP_KEY, 0)
                                if (targetApp != 0) {
                                    "通知监听仅支持钉钉打卡".show(context)
                                    return
                                }
                                if (!binding.noticeSwitch.isChecked) {
                                    "请先打开通知监听".show(context)
                                    return
                                }
                                SaveKeyValues.saveInt(Constant.RESULT_SOURCE_KEY, 0)
                                updateResultSourceView()
                                binding.accessibilityFeedbackLayout.visibility = View.GONE
                                binding.accessibilityFeedbackDivider.visibility = View.GONE
                            }

                            1 -> {
                                if (!binding.captureSwitch.isChecked) {
                                    "请先打开截屏服务".show(context)
                                    return
                                }
                                SaveKeyValues.saveInt(Constant.RESULT_SOURCE_KEY, 1)
                                updateResultSourceView()
                                binding.accessibilityFeedbackLayout.visibility = View.GONE
                                binding.accessibilityFeedbackDivider.visibility = View.GONE
                            }

                            2 -> {
                                if (!binding.accessibilitySwitch.isChecked) {
                                    "请先打开无障碍服务".show(context)
                                    return
                                }
                                // 选择无障碍模式
                                SaveKeyValues.saveInt(Constant.RESULT_SOURCE_KEY, 2)
                                // 系统版本低于 Android 14：无障碍截屏 API 不可用，
                                // 自动切到“文本反馈”，留在无障碍模式（不回退通知）
                                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                                    SaveKeyValues.saveInt(Constant.ACCESSIBILITY_FEEDBACK_MODE_KEY, 1)
                                }
                                updateResultSourceView()
                                binding.accessibilityFeedbackLayout.visibility = View.VISIBLE
                                binding.accessibilityFeedbackDivider.visibility = View.VISIBLE
                                updateAccessibilityFeedbackView()
                            }
                        }
                        ConfigImportSignal.notifyRemoteChanged(context)
                    }
                }).build().show()
        }

        // 反馈方式：点击弹出选择
        binding.accessibilityFeedbackLayout.setOnClickListener {
            // 系统版本低于 Android 14：无障碍截屏不可用，只能选“文本反馈”
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                SaveKeyValues.saveInt(Constant.ACCESSIBILITY_FEEDBACK_MODE_KEY, 1)
                updateAccessibilityFeedbackView()
                "当前系统版本过低（需 Android 14+），不支持无障碍截屏，已切换为文本反馈".show(this)
                return@setOnClickListener
            }
            BottomActionSheet.Builder()
                .setContext(this)
                .setActionItemTitle(feedbackModes)
                .setItemTextColor(R.color.theme_color.convertColor(this))
                .setOnActionSheetListener(object : BottomActionSheet.OnActionSheetListener {
                    override fun onActionItemClick(position: Int) {
                        SaveKeyValues.saveInt(Constant.ACCESSIBILITY_FEEDBACK_MODE_KEY, position)
                        updateAccessibilityFeedbackView()
                        ConfigImportSignal.notifyRemoteChanged(context)
                    }
                }).build().show()
        }

        binding.taskConfigLayout.setOnClickListener {
            navigatePageTo<TaskConfigActivity>()
        }

        binding.updateHolidayLayout.setOnClickListener {
            LoadingDialog.show(this, "更新中，请稍后...")
            ChinaHolidayManager.updateChinaHolidayData()
        }

        binding.floatingSwitch.setOnClickListener {
            if (Settings.canDrawOverlays(this)) {
                "核心服务，无法关闭".show(this)
                binding.floatingSwitch.isChecked = true
                return@setOnClickListener
            }
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            overlayPermissionLauncher.launch(intent)
        }

        binding.noticeSwitch.setOnClickListener {
            if (notificationEnable()) {
                "核心服务，无法关闭".show(this)
                binding.noticeSwitch.isChecked = true
                return@setOnClickListener
            }
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            notificationSettingLauncher.launch(intent)
        }

        // 截屏服务开关：点击时拉起 MediaProjection 授权
        binding.captureSwitch.setOnClickListener {
            if (ProjectionSession.isStateActive()) {
                // 当前已开启 → 关闭
                stopService(Intent(this, CaptureImageService::class.java))
                ProjectionSession.clear()
                SaveKeyValues.saveInt(Constant.RESULT_SOURCE_KEY, 0)
                binding.captureSwitch.isChecked = false
                binding.captureTipsView.text = "截屏服务未开启，无法获取打卡结果"
                binding.captureTipsView.setTextColor(Color.RED)
                binding.captureTipsView.visibility = View.VISIBLE
                updateResultSourceView()
                ConfigImportSignal.notifyRemoteChanged(context)
                "截屏服务已关闭".show(this)
            } else {
                // 当前未开启 → 先弹窗提醒，确认后再拉起 MediaProjection 授权
                binding.captureSwitch.isChecked = false
                showCaptureEnableWarning {
                    projectionLauncher.launch(mpr.createScreenCaptureIntent())
                }
            }
        }

        // 无障碍开关：仅作为系统无障碍状态的镜像与入口。
        // 点击跳转系统设置，由用户在系统中真正开启/关闭无障碍服务；
        // 返回后 onResume() 会按系统实际状态自动刷新开关与提示。
        binding.accessibilitySwitch.setOnClickListener {
            val systemEnabled = AutoProjectionAccessibilityService.isEnabled(this)
            // 撤销 Switch 自带的状态翻转，避免返回前视觉抖动
            binding.accessibilitySwitch.isChecked = systemEnabled
            if (!systemEnabled) {
                // 即将开启无障碍服务：先弹窗提醒用户开启后手动验证打卡是否正常
                showAccessibilityEnableWarning()
            } else {
                // 已开启 → 前往系统设置关闭（无需提醒）
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        binding.commandLayout.setOnClickListener {
            navigatePageTo<CommandActivity>()
        }

        binding.downloadRow.setOnClickListener {
            val url = BuildConfig.CTRL_DOWNLOAD_URL.trim()
            if (url.isNotEmpty()) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } else {
                MaterialAlertDialogBuilder(this)
                    .setTitle("获取控制端 DailyController")
                    .setMessage("当前未配置控制端下载地址。控制端安装包由分发方通过构建参数注入，请向提供者获取安装方式。")
                    .setPositiveButton("知道了", null)
                    .show()
            }
        }

        binding.keepAliveSwitch.setOnClickListener {
            val on = binding.keepAliveSwitch.isChecked
            SaveKeyValues.saveBoolean(Constant.BACKGROUND_KEEP_ALIVE_KEY, on)
            if (on) {
                // 立即拉起前台服务并设置保活闹钟
                startForegroundService(Intent(this, ForegroundRunningService::class.java))
                "已开启开机自启/后台保活".show(this)
            } else {
                // 取消保活闹钟（前台服务仍在运行时不会重新调度）
                KeepAliveReceiver.cancel(this)
                "已关闭开机自启/后台保活".show(this)
            }
            ConfigImportSignal.notifyRemoteChanged(context)
        }

        // 通知转移开关已移至设置页“通知监听”下方
        // 通知转移：复用现有消息渠道（企业微信/邮箱）转发目标打卡应用通知到目标手机。
        // 开启时按当前渠道校验配置是否齐全。
        binding.transferSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (syncingSwitchState) {
                return@setOnCheckedChangeListener
            }
            SaveKeyValues.saveBoolean(Constant.NOTIFICATION_TRANSFER_KEY, isChecked)
            if (isChecked) {
                val channel = SaveKeyValues.loadInt(Constant.MSG_CHANNEL_KEY, Constant.DEFAULT_INDEX)
                when (channel) {
                    0 -> { // 邮箱
                        val obj = ConfigStore.get().load(Constant.EMAIL_CONFIG_KEY)
                        val inbox =
                            if (!obj.isEmpty && obj.has("inbox")) obj.get("inbox").asString else ""
                        if (inbox.isBlank() || EmailSecureConfig.loadAuthCode().isBlank()) {
                            "通知转移依赖邮箱配置，请先完善邮箱与授权码".show(this)
                        }
                    }

                    1 -> { // 企业微信
                        val wxKey = SaveKeyValues.loadString(Constant.WX_WEB_HOOK_KEY, "")
                        if (wxKey.isBlank()) {
                            "通知转移依赖企业微信配置，请先填写企业微信 Webhook".show(this)
                        }
                    }

                    else -> "请先在设置中配置消息渠道（邮箱/企业微信）".show(this)
                }
            }
            ConfigImportSignal.notifyRemoteChanged(context)
        }

        binding.openTestLayout.setOnClickListener {
            openApplication()
        }

        binding.captureTestLayout.setOnClickListener {
            val source = SaveKeyValues.loadInt(
                Constant.RESULT_SOURCE_KEY, Constant.DEFAULT_INDEX
            )
            if (source == 2) {
                // 无障碍模式：用 takeScreenshot
                if (!AutoProjectionAccessibilityService.canTakeScreenshot(this)) {
                    "无障碍服务未开启或系统版本过低(需 Android 14+)，无法截屏".show(this)
                    return@setOnClickListener
                }
            } else {
                // 截屏模式：优先 MediaProjection，未就绪时回退无障碍截屏
                if (ProjectionSession.isStateActive()) {
                    // 已就绪，直接使用 MediaProjection
                } else if (AutoProjectionAccessibilityService.canTakeScreenshot(this)) {
                    // MediaProjection 未就绪，回退无障碍截屏
                } else {
                    binding.captureSwitch.isChecked = false
                    "截屏服务未开启且无障碍截屏不可用，请检查设置".show(this)
                    return@setOnClickListener
                }
            }

            // 触发截屏并等待截屏结果：截屏服务模式优先 MediaProjection，
            // 若其未就绪但有无障碍截屏能力，则回退到无障碍截屏
            lifecycleScope.launch {
                val useAccessibility = if (source == 2) {
                    true
                } else {
                    !ProjectionSession.isStateActive()
                            && AutoProjectionAccessibilityService.canTakeScreenshot(this@SettingsActivity)
                }
                val imagePath = if (useAccessibility) {
                    AutoProjectionAccessibilityService.requestScreenshot()?.await()
                } else {
                    CaptureImageService.requestCaptureScreen().await()
                }
                if (imagePath.isNullOrEmpty()) {
                    "截图失败，请检查服务状态".show(context)
                    return@launch
                }

                LoadingDialog.show(context, "消息发送中，请稍后...")
                MessageDispatcher.sendAttachmentMessage(
                    "邮箱测试", StatusReporter.buildTestEmailHtml(), imagePath,
                    onSuccess = {
                        LoadingDialog.dismiss()
                        "发送成功，请注意查收".show(context)
                    },
                    onFailure = {
                        LoadingDialog.dismiss()
                        "发送失败：$it".show(context)
                    })
            }
        }

        // 状态查询：生成 HTML 状态报告并以尽量大的 WebView 弹窗展示
        binding.statusQueryLayout.setOnClickListener {
            lifecycleScope.launch {
                val html = runCatching {
                    StatusReporter.buildStatusReportHtml(this@SettingsActivity, false)
                }.getOrElse { e ->
                    Toast.makeText(
                        this@SettingsActivity,
                        "状态查询生成失败：${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }
                showStatusReportDialog(html)
            }
        }

        binding.gestureDetectSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (syncingSwitchState) {
                return@setOnCheckedChangeListener
            }
            SaveKeyValues.saveBoolean(Constant.GESTURE_DETECTOR_KEY, isChecked)
            ConfigImportSignal.notifyRemoteChanged(context)
        }

        binding.backToHomeSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (syncingSwitchState) {
                return@setOnCheckedChangeListener
            }
            SaveKeyValues.saveBoolean(Constant.BACK_TO_HOME_KEY, isChecked)
            ConfigImportSignal.notifyRemoteChanged(context)
        }

        binding.powerSaveSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (syncingSwitchState) {
                return@setOnCheckedChangeListener
            }
            AppRuntimeConfig.setPowerSaveMode(isChecked)
            ConfigImportSignal.notifyRemoteChanged(context)
        }

        // 强制伪息屏 / 伪息屏增强 总开关：两者状态完全一致，双向同步
        val applyForcePseudoMask = { checked: Boolean ->
            if (checked) {
                AppRuntimeConfig.setForcePseudoMask(true)
                "强制伪息屏已开启".show(this)
            } else {
                AppRuntimeConfig.setForcePseudoMask(false)
            }
            // 同步两个开关的显示状态（防止监听器回环）
            syncingSwitchState = true
            binding.forcePseudoMaskSwitch.isChecked = checked
            binding.pseudoMaskGroupSwitch.isChecked = checked
            syncingSwitchState = false
            ConfigImportSignal.notifyRemoteChanged(context)
        }

        binding.forcePseudoMaskSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (syncingSwitchState) {
                return@setOnCheckedChangeListener
            }
            if (isChecked) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("开启强制伪息屏？")
                    .setMessage(
                        "开启后：\n\n" +
                                "1. 离开本软件超过设定秒数（可在下方设置，默认 60 秒），将主动进入伪息屏模式\n" +
                                "2. 可能打断你正在使用的其它 App（微信、浏览器等）\n" +
                                "3. 离开期间会尽量阻止系统自动灭屏（透明保亮）\n" +
                                "4. 打卡等待窗口内不会盖黑屏\n\n" +
                                "适合无人值守挂机；若白天还要操作手机，建议关闭。"
                    )
                    .setNegativeButton("取消") { _, _ ->
                        syncingSwitchState = true
                        binding.forcePseudoMaskSwitch.isChecked = false
                        binding.pseudoMaskGroupSwitch.isChecked = false
                        syncingSwitchState = false
                    }
                    .setPositiveButton("确认开启") { _, _ -> applyForcePseudoMask(true) }
                    .setCancelable(false)
                    .show()
            } else {
                applyForcePseudoMask(false)
            }
        }

        // 「伪息屏增强」标题行右侧总开关：与强制伪息屏开关共用同一份状态
        binding.pseudoMaskGroupSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (syncingSwitchState) {
                return@setOnCheckedChangeListener
            }
            if (isChecked) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("开启强制伪息屏？")
                    .setMessage(
                        "开启后：\n\n" +
                                "1. 离开本软件超过设定秒数（可在下方设置，默认 60 秒），将主动进入伪息屏模式\n" +
                                "2. 可能打断你正在使用的其它 App（微信、浏览器等）\n" +
                                "3. 离开期间会尽量阻止系统自动灭屏（透明保亮）\n" +
                                "4. 打卡等待窗口内不会盖黑屏\n\n" +
                                "适合无人值守挂机；若白天还要操作手机，建议关闭。"
                    )
                    .setNegativeButton("取消") { _, _ ->
                        syncingSwitchState = true
                        binding.forcePseudoMaskSwitch.isChecked = false
                        binding.pseudoMaskGroupSwitch.isChecked = false
                        syncingSwitchState = false
                    }
                    .setPositiveButton("确认开启") { _, _ -> applyForcePseudoMask(true) }
                    .setCancelable(false)
                    .show()
            } else {
                applyForcePseudoMask(false)
            }
        }

        // 伪息屏隐藏时钟：开启后伪息屏只显示黑屏，不显示时钟（省电）
        binding.pseudoMaskNoClockSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (syncingSwitchState) {
                return@setOnCheckedChangeListener
            }
            SaveKeyValues.saveBoolean(Constant.PSEUDO_MASK_NO_CLOCK_KEY, isChecked)
            LogFileManager.writeLog("伪息屏隐藏时钟：${if (isChecked) "开启" else "关闭"}")
            ConfigImportSignal.notifyRemoteChanged(context)
        }

        // 强制伪息屏延时（秒）：离开本软件超过该秒数进入伪息屏（10~3600，默认 60）
        // 折叠为可点击行，点击弹出对话框设置，避免内联输入框占用空间
        val delaySec = SaveKeyValues.loadInt(Constant.IDLE_PSEUDO_MASK_TIMEOUT_KEY, 60)
            .coerceIn(10, 3600)
        binding.pseudoMaskDelayValueText.text =
            getString(R.string.settings_pseudo_mask_delay_value, delaySec)
        binding.pseudoMaskDelayLayout.setOnClickListener {
            // 每次点击都重新读取最新已存值，避免改完一次后再打开显示旧值
            val current = SaveKeyValues.loadInt(Constant.IDLE_PSEUDO_MASK_TIMEOUT_KEY, 60)
                .coerceIn(10, 3600)
            showPseudoMaskDelayDialog(current)
        }

        // 「伪息屏增强」分组：点击标题行（开关除外）展开/收起（箭头旋转动画），默认折叠
        var pseudoGroupExpanded = false
        binding.pseudoMaskGroupHeader.setOnClickListener {
            pseudoGroupExpanded = !pseudoGroupExpanded
            binding.pseudoMaskGroupContent.visibility =
                if (pseudoGroupExpanded) View.VISIBLE else View.GONE
            binding.pseudoMaskGroupArrow.animate()
                .rotation(if (pseudoGroupExpanded) -90f else 90f)
                .setDuration(200)
                .start()
        }

        binding.introduceLayout.setOnClickListener {
            navigatePageTo<QuestionAndAnswerActivity>()
        }

        // 一键诊断日志导出：生成报告并经由系统分享面板导出
        binding.diagnosticLayout.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                val file = DiagnosticReporter.exportToFile(this@SettingsActivity)
                withContext(Dispatchers.Main) {
                    file?.let { reportFile ->
                        val authority = BuildConfig.APPLICATION_ID + ".fileprovider"
                        val uri = FileProvider.getUriForFile(this@SettingsActivity, authority, reportFile)
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        try {
                            startActivity(Intent.createChooser(shareIntent, "导出诊断日志"))
                        } catch (e: Exception) {
                            "导出失败：${e.message}".show(context)
                        }
                    } ?: run {
                        "诊断日志导出失败".show(context)
                    }
                }
            }
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(permissionContract) {
        if (Settings.canDrawOverlays(this)) {
            Intent(this, FloatingWindowService::class.java).apply {
                startService(this)
            }
        }
    }

    private val notificationSettingLauncher = registerForActivityResult(notificationContract) {
        if (notificationEnable()) {
            turnOnNotificationMonitorService()
        }
    }

    /**
     * MediaProjection 授权回调
     */
    private val projectionLauncher = registerForActivityResult(projectionContract) {
        if (it.resultCode != RESULT_OK) {
            "用户拒绝授权".show(this)
            return@registerForActivityResult
        }

        val data = it.data ?: run {
            "授权失败".show(this)
            return@registerForActivityResult
        }

        if (ProjectionSession.isStateActive()) {
            Log.d(kTag, "MediaProjection already active, skipping creation")
            return@registerForActivityResult
        }

        Intent(this, CaptureImageService::class.java).apply {
            putExtra("resultCode", it.resultCode)
            putExtra("data", data)
            startForegroundService(this)
        }
    }

    /**
     * 伪息屏延时设置对话框：折叠在「伪息屏延时」行内，点击弹出输入。
     * 取值区间 10~3600 秒（默认 60），保存后刷新右侧显示值。
     */
    private fun showPseudoMaskDelayDialog(currentSec: Int) {
        val editText = EditText(this@SettingsActivity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(currentSec.toString())
            filters = arrayOf(InputFilter.LengthFilter(4))
            gravity = Gravity.END
        }
        MaterialAlertDialogBuilder(this@SettingsActivity)
            .setTitle(R.string.settings_pseudo_mask_delay_title)
            .setMessage(R.string.settings_pseudo_mask_delay_tip)
            .setView(editText)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val v = editText.text.toString().toIntOrNull()?.coerceIn(10, 3600) ?: 60
                SaveKeyValues.saveInt(Constant.IDLE_PSEUDO_MASK_TIMEOUT_KEY, v)
                binding.pseudoMaskDelayValueText.text =
                    getString(R.string.settings_pseudo_mask_delay_value, v)
                ConfigImportSignal.notifyRemoteChanged(context)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * 根据 RESULT_SOURCE_KEY 更新结果来源显示文字
     */
    private fun updateResultSourceView() {
        val source = SaveKeyValues.loadInt(Constant.RESULT_SOURCE_KEY, Constant.DEFAULT_INDEX)
        val text = when (source) {
            0 -> "通知"
            1 -> "截屏"
            2 -> "无障碍"
            else -> "通知"
        }
        binding.resultSourceView.text = text
        binding.resultSourceView.setTextColor(R.color.theme_color.convertColor(this))
    }

    /** 按当前选中的目标应用刷新「目标应用」图标：内置用固定图标，自定义用真实 App 图标 */
    private fun applyTargetAppIcon() {
        val pkg = Constant.getTargetApp()
        val builtinIndex = Constant.getBuiltInTargets().indexOfFirst { it.first == pkg }
        if (builtinIndex >= 0) {
            binding.iconView.setBackgroundResource(builtInIcons.getOrElse(builtinIndex) { builtInIcons[0] })
        } else {
            val drawable = loadAppIcon(pkg)
            if (drawable != null) binding.iconView.background = drawable
            else binding.iconView.setBackgroundResource(R.drawable.ic_custom_app)
        }
    }

    private fun refreshTargetAppIcon() {
        applyTargetAppIcon()
    }

    /** 加载已安装 App 的图标，失败返回 null */
    private fun loadAppIcon(pkg: String): Drawable? = try {
        packageManager.getApplicationIcon(pkg)
    } catch (e: Exception) { null }

    /** 解析已安装 App 的显示名，失败回退包名 */
    private fun resolveAppLabel(pkg: String): String = try {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }

    /** 将多行/逗号/分号/空白混合的文本解析为包名列表 */
    private fun parsePackageList(text: String): List<String> =
        text.split(Regex("[\n,，;；\\s]+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

    /** 校验 Android 包名格式：至少两段，仅含字母数字下划线与点 */
    private fun isValidPackageName(pkg: String): Boolean =
        Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+\$").matches(pkg)

    // ============ 自定义打卡应用：从已安装 App 列表选择 ============

    private var customAppDialog: Dialog? = null

    /** 自定义打卡应用管理：使用自定义圆角卡片弹窗，列出已添加项（真实图标+名称+包名），支持移除，并可从已安装应用选择/手动输入 */
    private fun showCustomAppManagerDialog() {
        val customApps = Constant.getCustomTargetApps()
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_custom_app_manager, null)
        val contentContainer = view.findViewById<FrameLayout>(R.id.contentContainer)
        val closeBtn = view.findViewById<ImageView>(R.id.closeBtn)
        val pickBtn = view.findViewById<TextView>(R.id.btnPickFromInstalled)
        val manualBtn = view.findViewById<TextView>(R.id.btnManualInput)

        if (customApps.isEmpty()) {
            val emptyView = TextView(this).apply {
                text = getString(R.string.settings_custom_app_empty)
                setTextColor(Color.parseColor("#9E9E9E"))
                textSize = 13f
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ).apply { gravity = Gravity.CENTER }
            }
            contentContainer.addView(emptyView)
        } else {
            val list = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            }
            val inflater = LayoutInflater.from(this)
            customApps.forEachIndexed { idx, pkg ->
                if (idx > 0) {
                    list.addView(View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            resources.getDimensionPixelSize(R.dimen.dividerLine)
                        )
                        setBackgroundColor(Color.parseColor("#EEEEEE"))
                    })
                }
                val row = inflater.inflate(R.layout.dialog_custom_app_item, list, false) as ViewGroup
                val icon = row.findViewById<ImageView>(R.id.appIcon)
                val name = row.findViewById<TextView>(R.id.appName)
                val pkgView = row.findViewById<TextView>(R.id.appPkg)
                val remove = row.findViewById<TextView>(R.id.btnRemove)
                val drawable = loadAppIcon(pkg)
                if (drawable != null) icon.setImageDrawable(drawable) else icon.setImageResource(R.drawable.ic_custom_app)
                name.text = resolveAppLabel(pkg)
                pkgView.text = pkg
                remove.setOnClickListener { removeCustomApp(pkg) }
                list.addView(row)
            }
            contentContainer.addView(list)
        }

        customAppDialog = Dialog(this).apply {
            setContentView(view)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val width = (resources.displayMetrics.widthPixels * 0.88).toInt()
            window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            setCanceledOnTouchOutside(true)
            show()
        }

        closeBtn.setOnClickListener { customAppDialog?.dismiss() }
        pickBtn.setOnClickListener {
            customAppDialog?.dismiss()
            showAppPickerDialog()
        }
        manualBtn.setOnClickListener {
            customAppDialog?.dismiss()
            showCustomAppTextDialog()
        }
    }

    /** 从已安装（拥有桌面图标）的 App 列表中直接选择，列表带真实图标，使用自定义圆角卡片弹窗 */
    private fun showAppPickerDialog() {
        val pm = packageManager
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launchIntent, 0)
            .filter { it.activityInfo.packageName != BuildConfig.APPLICATION_ID }
            .sortedBy { it.loadLabel(pm).toString() }
        if (apps.isEmpty()) {
            getString(R.string.settings_custom_app_no_apps).show(this)
            return
        }

        val view = LayoutInflater.from(this).inflate(R.layout.dialog_app_picker, null)
        val listView = view.findViewById<ListView>(R.id.appListView)
        val closeBtn = view.findViewById<ImageView>(R.id.closeBtn)
        val cancelBtn = view.findViewById<TextView>(R.id.btnCancel)

        val maxH = (320 * resources.displayMetrics.density).toInt()
        listView.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            maxH
        )
        listView.divider = null
        listView.selector = getDrawable(android.R.color.transparent)
        listView.adapter = object : ArrayAdapter<ResolveInfo>(this, 0, apps) {
            private val inflater = LayoutInflater.from(this@SettingsActivity)
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val item = convertView
                    ?: inflater.inflate(R.layout.dialog_app_picker_item, parent, false)
                val ri = getItem(position)
                item.findViewById<ImageView>(R.id.appIcon).setImageDrawable(ri?.loadIcon(pm))
                item.findViewById<TextView>(R.id.appLabel).text = ri?.loadLabel(pm).toString()
                return item
            }
        }

        val dialog = Dialog(this).apply {
            setContentView(view)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val width = (resources.displayMetrics.widthPixels * 0.88).toInt()
            window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            setCanceledOnTouchOutside(true)
            show()
        }

        listView.setOnItemClickListener { _, _, which, _ ->
            dialog.dismiss()
            addCustomApp(apps[which].activityInfo.packageName)
        }
        closeBtn.setOnClickListener { dialog.dismiss() }
        cancelBtn.setOnClickListener { dialog.dismiss() }
    }

    /** 添加并直接选中一个自定义打卡应用 */
    private fun addCustomApp(pkg: String) {
        val list = Constant.getCustomTargetApps().toMutableList()
        if (!list.contains(pkg)) list.add(pkg)
        SaveKeyValues.saveString(Constant.CUSTOM_TARGET_APPS_KEY, list.joinToString(","))
        SaveKeyValues.saveInt(Constant.TARGET_APP_KEY, Constant.CUSTOM_TARGET_INDEX)
        SaveKeyValues.saveString(Constant.CUSTOM_TARGET_SELECTED_KEY, pkg)
        applyTargetAppIcon()
        ConfigImportSignal.notifyRemoteChanged(context)
        getString(R.string.settings_pick_app_added, resolveAppLabel(pkg)).show(this)
    }

    /** 移除自定义打卡应用；若当前正选中该项则回退钉钉 */
    private fun removeCustomApp(pkg: String) {
        val list = Constant.getCustomTargetApps().toMutableList()
        list.remove(pkg)
        SaveKeyValues.saveString(Constant.CUSTOM_TARGET_APPS_KEY, list.joinToString(","))
        if (SaveKeyValues.loadInt(Constant.TARGET_APP_KEY, 0) == Constant.CUSTOM_TARGET_INDEX
            && SaveKeyValues.loadString(Constant.CUSTOM_TARGET_SELECTED_KEY, "") == pkg
        ) {
            SaveKeyValues.saveInt(Constant.TARGET_APP_KEY, 0)
            SaveKeyValues.saveString(Constant.CUSTOM_TARGET_SELECTED_KEY, "")
        }
        applyTargetAppIcon()
        ConfigImportSignal.notifyRemoteChanged(context)
        customAppDialog?.dismiss()
        showCustomAppManagerDialog()
    }

    /** 手动输入包名的兜底入口（少数无桌面图标的 App 可能需要） */
    private fun showCustomAppTextDialog() {
        val current = Constant.getCustomTargetApps().joinToString("\n")
        val input = EditText(this).apply {
            setText(current)
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
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_custom_target_app)
            .setMessage(R.string.settings_custom_app_manual_hint)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val pkgs = parsePackageList(input.text.toString())
                val invalid = pkgs.filter { !isValidPackageName(it) }
                if (invalid.isNotEmpty()) {
                    "无效的包名格式：${invalid.joinToString()}".show(this)
                    return@setPositiveButton
                }
                SaveKeyValues.saveString(Constant.CUSTOM_TARGET_APPS_KEY, pkgs.joinToString(","))
                val selected = SaveKeyValues.loadString(Constant.CUSTOM_TARGET_SELECTED_KEY, "")
                if (SaveKeyValues.loadInt(Constant.TARGET_APP_KEY, 0) == Constant.CUSTOM_TARGET_INDEX
                    && !pkgs.contains(selected)
                ) {
                    SaveKeyValues.saveInt(Constant.TARGET_APP_KEY, 0)
                    SaveKeyValues.saveString(Constant.CUSTOM_TARGET_SELECTED_KEY, "")
                }
                applyTargetAppIcon()
                ConfigImportSignal.notifyRemoteChanged(context)
                "已保存自定义打卡应用".show(this)
            }
            .show()
    }

    /**
     * 根据 ACCESSIBILITY_FEEDBACK_MODE_KEY 更新反馈方式显示文字
     */
    private fun updateAccessibilityFeedbackView() {
        val mode = SaveKeyValues.loadInt(Constant.ACCESSIBILITY_FEEDBACK_MODE_KEY, 0)
        binding.accessibilityFeedbackView.text = if (mode == 1) "文本反馈" else "截屏反馈"
        binding.accessibilityFeedbackView.setTextColor(R.color.theme_color.convertColor(this))
    }

    private fun showAccessibilityRequiredDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("需要开启无障碍服务")
            .setMessage(
                "无障碍功能需要先在系统设置中开启无障碍服务。\n\n" +
                        "请前往：设置 → 无障碍 → ${resources.getString(R.string.app_name)} 并开启。\n\n" +
                        "开启后返回本页面，再打开无障碍开关即可。"
            )
            .setNegativeButton("取消") { _, _ -> }
            .setPositiveButton("前往设置") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setCancelable(false)
            .show()
    }

    /**
     * 开启无障碍服务前的提醒：部分打卡应用会检测无障碍开关，
     * 开启后需手动验证打卡是否正常，异常则关闭。确认后跳转系统设置开启。
     */
    private fun showAccessibilityEnableWarning() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_accessibility_enable_warning_title)
            .setMessage(R.string.settings_accessibility_enable_warning_msg)
            .setNegativeButton("取消") { _, _ -> }
            .setPositiveButton("前往开启") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setCancelable(false)
            .show()
    }

    /**
     * 开启截屏服务前的提醒：截屏依赖系统屏幕录制授权，开启后部分安全类应用可能受限，
     * 需手动验证打卡是否正常，异常则关闭。确认后执行 [onConfirm]（拉起 MediaProjection 授权）。
     */
    private fun showCaptureEnableWarning(onConfirm: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_capture_enable_warning_title)
            .setMessage(R.string.settings_capture_enable_warning_msg)
            .setNegativeButton("取消") { _, _ -> }
            .setPositiveButton("继续开启") { _, _ -> onConfirm() }
            .setCancelable(false)
            .show()
    }

    private fun setupBottomNav(currentTab: Int) {
        binding.bottomNavBar.bottomNav.selectedItemId = currentTab
        binding.bottomNavBar.bottomNav.setOnItemSelectedListener { item ->
            if (item.itemId == currentTab) return@setOnItemSelectedListener true
            val target = when (item.itemId) {
                R.id.nav_task -> MainActivity::class.java
                R.id.nav_remote -> RemoteControlActivity::class.java
                R.id.nav_settings -> SettingsActivity::class.java
                else -> null
            }
            target?.let {
                startActivity(Intent(this, it).apply { flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT })
                finish()
                // P4：底部导航切换 200ms 淡入淡出；系统开启"减少动态效果"时跳过动画
                if (android.provider.Settings.Global.getFloat(contentResolver, android.provider.Settings.Global.TRANSITION_ANIMATION_SCALE, 1f) != 0f) {
                    overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
                }
            }
            true
        }
    }

    override fun onResume() {
        super.onResume()
        // 注册远程配置变更广播：控制端改设置后，前台设置页即时刷新
        ContextCompat.registerReceiver(
            this, remoteConfigReceiver,
            IntentFilter(ConfigImportSignal.ACTION_REMOTE_CONFIG_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        if (Settings.canDrawOverlays(this)) {
            binding.floatingSwitch.isChecked = true
            binding.floatingTipsView.visibility = View.GONE
        } else {
            binding.floatingSwitch.isChecked = false
            binding.floatingTipsView.visibility = View.VISIBLE
            binding.floatingTipsView.text = "服务未开启，打完卡无法自动跳回本软件"
        }

        val type = SaveKeyValues.loadInt(Constant.MSG_CHANNEL_KEY, Constant.DEFAULT_INDEX)
        if (type in 0..channels.lastIndex) {
            binding.channelView.text = channels[type]
            binding.channelView.setTextColor(R.color.theme_color.convertColor(this))
        } else {
            binding.channelView.text = "未配置"
            binding.channelView.setTextColor(R.color.red.convertColor(this))
        }

        // 从配置导入页返回后，目标应用可能已被导入修改，同步刷新其图标（仅导入成功后触发一次）
        if (ConfigImportSignal.pendingSettingsRefresh) {
            ConfigImportSignal.pendingSettingsRefresh = false
            applyTargetAppIcon()
        }

        // 同步通知服务 UI
        if (notificationEnable()) {
            binding.noticeTipsView.text = "服务状态查询中，请稍后..."
            binding.noticeTipsView.setTextColor(R.color.theme_color.convertColor(this))
            lifecycleScope.launch(Dispatchers.Main) {
                delay(500)
                if (notificationEnable()) {
                    binding.noticeSwitch.isChecked = true
                    binding.noticeTipsView.visibility = View.GONE
                }
            }
        } else {
            binding.noticeTipsView.text = "服务未开启，无法监听打卡结果和接收远程指令"
            binding.noticeTipsView.setTextColor(Color.RED)
            binding.noticeSwitch.isChecked = false
            binding.noticeTipsView.visibility = View.VISIBLE
        }

        // 同步截屏服务 UI（根据 ProjectionSession 实际状态）
        if (ProjectionSession.isStateActive()) {
            binding.captureSwitch.isChecked = true
            binding.captureTipsView.visibility = View.GONE
        } else {
            binding.captureTipsView.text = "截屏服务未开启，无法获取打卡结果"
            binding.captureTipsView.setTextColor(Color.RED)
            binding.captureSwitch.isChecked = false
            binding.captureTipsView.visibility = View.VISIBLE
        }

        // 同步无障碍服务 UI：开关显示状态以系统无障碍实际开关为准
        val source = SaveKeyValues.loadInt(Constant.RESULT_SOURCE_KEY, Constant.DEFAULT_INDEX)
        val a11yEnabled = AutoProjectionAccessibilityService.isEnabled(this)
        binding.accessibilitySwitch.isChecked = a11yEnabled

        if (a11yEnabled && source == 2) {
            binding.accessibilityTipsView.visibility = View.GONE
            binding.accessibilityFeedbackLayout.visibility = View.VISIBLE
            binding.accessibilityFeedbackDivider.visibility = View.VISIBLE
            // 系统版本低于 Android 14：无障碍截屏不可用 → 自动切到“文本反馈”，留在无障碍模式
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                if (SaveKeyValues.loadInt(Constant.ACCESSIBILITY_FEEDBACK_MODE_KEY, 0) == 0) {
                    SaveKeyValues.saveInt(Constant.ACCESSIBILITY_FEEDBACK_MODE_KEY, 1)
                }
            }
            updateAccessibilityFeedbackView()
        } else {
            if (source == 2 && !a11yEnabled) {
                // 无障碍服务未开启，但结果来源仍是无障碍 → 回退到通知模式（版本过低的情况留在上面处理）
                SaveKeyValues.saveInt(Constant.RESULT_SOURCE_KEY, 0)
                updateResultSourceView()
                "无障碍服务未开启，已切回通知模式".show(context)
            }
            binding.accessibilityTipsView.text = "无障碍服务未开启，无障碍模式无法获取打卡结果"
            binding.accessibilityTipsView.setTextColor(Color.RED)
            binding.accessibilityTipsView.visibility = if (a11yEnabled) View.GONE else View.VISIBLE
            binding.accessibilityFeedbackLayout.visibility = View.GONE
            binding.accessibilityFeedbackDivider.visibility = View.GONE
        }

        updateResultSourceView()

        syncSettingsUiFromStore()
    }

    override fun onPause() {
        super.onPause()
        runCatching { unregisterReceiver(remoteConfigReceiver) }
    }

    /** 从持久化存储同步所有开关与数值到 UI（远程变更广播 / onResume 复用，避免二次进入才刷新） */
    private fun syncSettingsUiFromStore() {
        syncingSwitchState = true
        try {
            binding.gestureDetectSwitch.isChecked =
                SaveKeyValues.loadBoolean(Constant.GESTURE_DETECTOR_KEY, true)
            binding.backToHomeSwitch.isChecked =
                SaveKeyValues.loadBoolean(Constant.BACK_TO_HOME_KEY, false)
            binding.powerSaveSwitch.isChecked = AppRuntimeConfig.isPowerSaveMode()
            binding.forcePseudoMaskSwitch.isChecked = AppRuntimeConfig.isForcePseudoMask()
            binding.pseudoMaskGroupSwitch.isChecked = AppRuntimeConfig.isForcePseudoMask()
            val delaySec = SaveKeyValues.loadInt(Constant.IDLE_PSEUDO_MASK_TIMEOUT_KEY, 60)
                .coerceIn(10, 3600)
            binding.pseudoMaskDelayValueText.text =
                getString(R.string.settings_pseudo_mask_delay_value, delaySec)
            binding.pseudoMaskNoClockSwitch.isChecked =
                SaveKeyValues.loadBoolean(Constant.PSEUDO_MASK_NO_CLOCK_KEY, false)
            binding.keepAliveSwitch.isChecked =
                SaveKeyValues.loadBoolean(Constant.BACKGROUND_KEEP_ALIVE_KEY, true)
            binding.transferSwitch.isChecked =
                SaveKeyValues.loadBoolean(Constant.NOTIFICATION_TRANSFER_KEY, false)
        } finally {
            syncingSwitchState = false
        }
    }

    private fun turnOnNotificationMonitorService() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (!isActive) return@launch

                val componentName = ComponentName(context, NotificationMonitorService::class.java)
                val currentState = context.packageManager.getComponentEnabledSetting(componentName)

                if (currentState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                    context.packageManager.setComponentEnabledSetting(
                        componentName,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                    delay(500)
                    if (!isActive) return@launch
                }

                context.packageManager.setComponentEnabledSetting(
                    componentName,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
            } catch (e: Exception) {
                Log.e(kTag, "启用组件失败", e)
            }
        }
    }

    /**
     * 以尽量大且紧凑的 WebView 弹窗渲染 HTML 状态报告。
     * - 无对话框标题栏（避免与 HTML 内容重复，节省顶部空间）。
     * - 容器仅保留左右内边距，WebView 在垂直方向贴满内容区，减少上下空白。
     * Dialog 关闭时清理 WebView，避免内存泄漏。
     */
    private fun showStatusReportDialog(html: String) {
        val dm = resources.displayMetrics
        val sidePad = (12 * dm.density).toInt()
        val webView = WebView(this).apply {
            settings.javaScriptEnabled = false
            settings.defaultTextEncodingName = "UTF-8"
            setBackgroundColor(Color.WHITE)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 仅左右内边距：上下不额外加 padding，让 WebView 充分利用对话框内容区高度
            setPadding(sidePad, 0, sidePad, 0)
            addView(
                webView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (dm.heightPixels * 0.90).toInt()
                )
            )
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(null) // 不显示对话框标题（HTML 内已不含顶部 header，去掉重复）
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
        // 让对话框宽度贴近屏幕宽度
        dialog.window?.setLayout(dm.widthPixels - 2 * sidePad, ViewGroup.LayoutParams.WRAP_CONTENT)
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }
}
