package com.pengxh.daily.app.ui

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
import com.pengxh.daily.app.utils.AppRuntimeConfig
import com.pengxh.daily.app.utils.ChinaHolidayManager
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.DailyTask
import com.pengxh.daily.app.utils.MessageDispatcher
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

class SettingsActivity : KotlinBaseActivity<ActivitySettingsBinding>() {

    private val kTag = "SettingsActivity"
    private val context = this
    private val apps by lazy {
        listOf(
            "钉钉",
            "企业微信",
            "飞书",
            "移动办公M3"
        )
    }
    private val icons by lazy {
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
    private val mpr by lazy { getSystemService(MediaProjectionManager::class.java) }
    private var syncingSwitchState = false

    override fun initViewBinding(): ActivitySettingsBinding {
        return ActivitySettingsBinding.inflate(layoutInflater)
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
        val index = (SaveKeyValues.loadInt(Constant.TARGET_APP_KEY, 0)).coerceIn(0, icons.lastIndex)
        binding.iconView.setBackgroundResource(icons[index])

        binding.appVersion.text = BuildConfig.VERSION_NAME
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
        binding.targetAppLayout.setOnClickListener {
            BottomActionSheet.Builder()
                .setContext(this)
                .setActionItemTitle(apps)
                .setItemTextColor(R.color.theme_color.convertColor(this))
                .setOnActionSheetListener(object : BottomActionSheet.OnActionSheetListener {
                    override fun onActionItemClick(position: Int) {
                        val oldPosition = SaveKeyValues.loadInt(Constant.TARGET_APP_KEY, 0)

                        if (oldPosition == position) {
                            binding.iconView.setBackgroundResource(icons[position])
                            return
                        }

                        when (position) {
                            0 -> {
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
                            }

                            1, 2, 3 -> {
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
                        }

                        binding.iconView.setBackgroundResource(icons[position])
                        SaveKeyValues.saveInt(Constant.TARGET_APP_KEY, position)
                        updateResultSourceView()
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
                                SaveKeyValues.saveInt(Constant.RESULT_SOURCE_KEY, 2)
                                updateResultSourceView()
                                binding.accessibilityFeedbackLayout.visibility = View.VISIBLE
                                binding.accessibilityFeedbackDivider.visibility = View.VISIBLE
                                updateAccessibilityFeedbackView()
                            }
                        }
                    }
                }).build().show()
        }

        // 反馈方式：点击弹出选择
        binding.accessibilityFeedbackLayout.setOnClickListener {
            BottomActionSheet.Builder()
                .setContext(this)
                .setActionItemTitle(feedbackModes)
                .setItemTextColor(R.color.theme_color.convertColor(this))
                .setOnActionSheetListener(object : BottomActionSheet.OnActionSheetListener {
                    override fun onActionItemClick(position: Int) {
                        SaveKeyValues.saveInt(Constant.ACCESSIBILITY_FEEDBACK_MODE_KEY, position)
                        updateAccessibilityFeedbackView()
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
                "截屏服务已关闭".show(this)
            } else {
                // 当前未开启 → 拉起 MediaProjection 授权
                binding.captureSwitch.isChecked = false
                projectionLauncher.launch(mpr.createScreenCaptureIntent())
            }
        }

        // 无障碍开关：仅作为系统无障碍状态的镜像与入口。
        // 点击跳转系统设置，由用户在系统中真正开启/关闭无障碍服务；
        // 返回后 onResume() 会按系统实际状态自动刷新开关与提示。
        binding.accessibilitySwitch.setOnClickListener {
            val systemEnabled = AutoProjectionAccessibilityService.isEnabled(this)
            // 撤销 Switch 自带的状态翻转，避免返回前视觉抖动
            binding.accessibilitySwitch.isChecked = systemEnabled
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.commandLayout.setOnClickListener {
            navigatePageTo<CommandActivity>()
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
                if (!AutoProjectionAccessibilityService.isEnabled(this)) {
                    "无障碍服务未开启，无法截屏".show(this)
                    return@setOnClickListener
                }
            } else {
                // 截屏模式：用 MediaProjection
                if (!binding.captureSwitch.isChecked) {
                    "请先打开截屏服务".show(this)
                    return@setOnClickListener
                }
                if (!ProjectionSession.isStateActive()) {
                    binding.captureSwitch.isChecked = false
                    "截屏授权已失效，请重新授权".show(this)
                    return@setOnClickListener
                }
            }

            // 触发截屏并等待截屏结果
            lifecycleScope.launch {
                val imagePath = if (source == 2) {
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
                    "邮箱测试", "这是一封测试邮件，不必关注", imagePath,
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

        binding.gestureDetectSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (syncingSwitchState) {
                return@setOnCheckedChangeListener
            }
            SaveKeyValues.saveBoolean(Constant.GESTURE_DETECTOR_KEY, isChecked)
        }

        binding.backToHomeSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (syncingSwitchState) {
                return@setOnCheckedChangeListener
            }
            SaveKeyValues.saveBoolean(Constant.BACK_TO_HOME_KEY, isChecked)
        }

        binding.powerSaveSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (syncingSwitchState) {
                return@setOnCheckedChangeListener
            }
            AppRuntimeConfig.setPowerSaveMode(isChecked)
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
                                "1. 离开本软件超过 60 秒，将主动进入伪息屏模式\n" +
                                "2. 可能打断你正在使用的其它 App（微信、浏览器等）\n" +
                                "3. 离开期间会尽量阻止系统自动灭屏（透明保亮）\n" +
                                "4. 打卡等待窗口内不会盖黑屏\n\n" +
                                "适合无人值守挂机；若白天还要操作手机，建议关闭。"
                    )
                    .setNegativeButton("取消") { _, _ ->
                        syncingSwitchState = true
                        binding.forcePseudoMaskSwitch.isChecked = false
                        syncingSwitchState = false
                    }
                    .setPositiveButton("确认开启") { _, _ ->
                        AppRuntimeConfig.setForcePseudoMask(true)
                        "强制伪息屏已开启".show(this)
                    }
                    .setCancelable(false)
                    .show()
            } else {
                AppRuntimeConfig.setForcePseudoMask(false)
            }
        }

        binding.introduceLayout.setOnClickListener {
            navigatePageTo<QuestionAndAnswerActivity>()
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

    override fun onResume() {
        super.onResume()
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
            updateAccessibilityFeedbackView()
        } else {
            if (source == 2 && !a11yEnabled) {
                // 系统无障碍已关闭，但结果来源仍是无障碍 → 回退到通知模式，避免去用不可用的服务
                SaveKeyValues.saveInt(Constant.RESULT_SOURCE_KEY, 0)
                updateResultSourceView()
            }
            binding.accessibilityTipsView.text = "无障碍服务未开启，无障碍模式无法获取打卡结果"
            binding.accessibilityTipsView.setTextColor(Color.RED)
            binding.accessibilityTipsView.visibility = if (a11yEnabled) View.GONE else View.VISIBLE
            binding.accessibilityFeedbackLayout.visibility = View.GONE
            binding.accessibilityFeedbackDivider.visibility = View.GONE
        }

        // 更新结果来源显示文字
        updateResultSourceView()

        syncingSwitchState = true
        try {
            binding.gestureDetectSwitch.isChecked =
                SaveKeyValues.loadBoolean(Constant.GESTURE_DETECTOR_KEY, true)
            binding.backToHomeSwitch.isChecked =
                SaveKeyValues.loadBoolean(Constant.BACK_TO_HOME_KEY, false)
            binding.powerSaveSwitch.isChecked = AppRuntimeConfig.isPowerSaveMode()
            binding.forcePseudoMaskSwitch.isChecked = AppRuntimeConfig.isForcePseudoMask()
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
                e.printStackTrace()
            }
        }
    }
}
