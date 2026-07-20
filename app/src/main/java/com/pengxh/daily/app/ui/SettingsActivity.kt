package com.pengxh.daily.app.ui

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
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
        applyTargetAppIcon()

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
                                "部分打卡软件可能会检测无障碍开关，开启后请先验证打卡功能是否正常".show(context)
                            }
                        }
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
            binding.keepAliveSwitch.isChecked =
                SaveKeyValues.loadBoolean(Constant.BACKGROUND_KEEP_ALIVE_KEY, true)
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
