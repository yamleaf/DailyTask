package com.pengxh.daily.app.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Build
import android.os.PowerManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.github.gzuliyujiang.wheelpicker.widget.TimeWheelLayout
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textview.MaterialTextView
import com.pengxh.daily.app.R
import com.pengxh.daily.app.adapter.DailyTaskAdapter
import com.pengxh.daily.app.databinding.ActivityMainBinding
import com.pengxh.daily.app.extensions.convertToTimeEntity
import com.pengxh.daily.app.extensions.notificationEnable
import com.pengxh.daily.app.service.AutoProjectionAccessibilityService
import com.pengxh.daily.app.service.CaptureImageService
import com.pengxh.daily.app.service.FloatingWindowService
import com.pengxh.daily.app.service.ForegroundRunningService
import com.pengxh.daily.app.service.NotificationMonitorService
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.sqlite.bean.DailyTaskBean
import com.pengxh.daily.app.sqlite.bean.NotificationBean
import com.pengxh.daily.app.utils.AppRuntimeConfig
import com.pengxh.daily.app.utils.ChinaHolidayManager
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.CustomWorkdayManager
import com.pengxh.daily.app.utils.DailyTask
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
import com.pengxh.daily.app.utils.ConfigImportSignal
import com.pengxh.daily.app.utils.TaskDataManager
import com.pengxh.daily.app.utils.TaskScheduler
import com.pengxh.daily.app.utils.TipsEvent
import com.pengxh.daily.app.utils.WatermarkDrawable
import com.pengxh.kt.lite.base.KotlinBaseActivity
import com.pengxh.kt.lite.divider.RecyclerViewItemBorder
import com.pengxh.kt.lite.extensions.convertColor
import com.pengxh.kt.lite.extensions.dp2px
import com.pengxh.kt.lite.extensions.navigatePageTo
import com.pengxh.kt.lite.extensions.show
import com.pengxh.kt.lite.extensions.toJson
import com.pengxh.kt.lite.extensions.timestampToCompleteDate
import com.pengxh.kt.lite.utils.SaveKeyValues
import com.pengxh.kt.lite.widget.dialog.AlertInputDialog
import com.pengxh.kt.lite.widget.dialog.BottomActionSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Date
import java.util.Locale

import com.pengxh.daily.app.extensions.format

class MainActivity : KotlinBaseActivity<ActivityMainBinding>() {

    private val kTag = "MainActivity"
    private val context by lazy { this }
    private val marginOffset by lazy { 16.dp2px(this) }
    private val permissionContract by lazy { ActivityResultContracts.StartActivityForResult() }
    private val taskDataManager by lazy { TaskDataManager() }

    private val insetsController by lazy {
        WindowCompat.getInsetsController(window, binding.rootView)
    }
    private val maskViewController: MaskViewController by lazy {
        MaskViewController(this, binding, insetsController) { visible ->
            if (visible) {
                mainHandler.removeCallbacks(timeUpdateRunnable)
                stopIdleMaskTimer()
            } else {
                mainHandler.removeCallbacks(timeUpdateRunnable)
                mainHandler.post(timeUpdateRunnable)
                resetIdleMaskTimer()
            }
        }
    }
    private val gestureController by lazy { GestureController(this, maskViewController) }
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** 无操作 1 分钟后自动进入伪息屏 */
    private val idleMaskRunnable = Runnable {
        if (!maskViewController.isMaskVisible() && !MaskOverlayHelper.isShowing()) {
            LogFileManager.writeLog("无操作 1 分钟，自动进入伪息屏")
            maskViewController.showMaskView()
        }
    }

    private var taskBeans = mutableListOf<DailyTaskBean>()
    /** 任务列表是否已做过首次加载（避免每次 onResume 都无谓查询 DB） */
    private var taskListLoaded = false
    /** 电池优化引导对话框是否已在本次生命周期提示过（避免 onResume 反复弹窗） */
    private var batteryOptimizationPrompted = false
    private val dailyTaskAdapter by lazy {
        DailyTaskAdapter(taskBeans).apply {
            setOnItemClickListener(object : DailyTaskAdapter.OnItemClickListener {
                override fun onItemClick(position: Int) {
                    itemClick(position)
                }

                override fun onItemLongClick(position: Int) {
                    itemLongClick(position)
                }
            })
        }
    }

    /**
     * 每秒刷新 toolbar 时间和日期标签
     * */
    private val timeUpdateRunnable: Runnable = object : Runnable {
        override fun run() {
            if (maskViewController.isMaskVisible()) {
                return
            }
            val currentTime = Date().format("yyyy年MM月dd日 HH:mm:ss EEEE")
            val parts = currentTime.split(" ")
            val now = LocalDate.now()
            val flag = when {
                // 法定节假日（如国庆、春节等，含调休放假，不含普通周末）
                ChinaHolidayManager.isHoliday(now) -> "节假日"

                // 调休补班日（如周末上班补假期）
                ChinaHolidayManager.isWorkday(now) -> "补班日"

                // 普通日期：按自定义工作日判定休息日/工作日
                else -> {
                    when {
                        CustomWorkdayManager.isWeekdayRestDay(now) -> "休息日"
                        else -> "工作日"
                    }
                }
            }
            binding.toolbar.apply {
                title = "${parts[2]}（$flag）"
                subtitle = "${parts[0]} ${parts[1]}"
            }
            val interval = if (AppRuntimeConfig.isPowerSaveMode()) 30_000L else 1_000L
            mainHandler.postDelayed(this, interval)
        }
    }

    override fun observeRequestState() {

    }

    override fun initViewBinding(): ActivityMainBinding {
        return ActivityMainBinding.inflate(layoutInflater)
    }

    override fun setupTopBarLayout() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { view, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.setPadding(0, statusBarHeight, 0, 0)
            insets
        }

        mainHandler.post(timeUpdateRunnable)

        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.menu_add_task -> {
                    if (TaskScheduler.isRunning()) {
                        "任务进行中，无法添加".show(this)
                        return@setOnMenuItemClickListener true
                    }

                    if (taskBeans.isNotEmpty()) {
                        createTask()
                    } else {
                        BottomActionSheet.Builder()
                            .setContext(this)
                            .setActionItemTitle(arrayListOf("添加任务", "导入任务"))
                            .setItemTextColor(R.color.theme_color.convertColor(this))
                            .setOnActionSheetListener(object :
                                BottomActionSheet.OnActionSheetListener {
                                override fun onActionItemClick(position: Int) {
                                    when (position) {
                                        0 -> createTask()
                                        1 -> importTask()
                                    }
                                }
                            }).build().show()
                    }
                }

                R.id.menu_settings -> {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("使用须知")
                        .setMessage("本软件完全免费！仅供内部使用！严禁商用或者用作其他非法用途！\r\n近期发现有人在咸鱼私自倒卖本软件，请勿购买！如有购买，请联系卖家退款！")
                        .setCancelable(false)
                        .setPositiveButton("知道了") { _, _ -> navigatePageTo<SettingsActivity>() }
                        .show()
                }
            }
            true
        }
    }

    override fun initOnCreate(savedInstanceState: Bundle?) {
        // 禁止系统自动息屏，保持常亮 + 伪息屏策略
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding.contentView.background = WatermarkDrawable(this, DailyTask.getWatermarkText())

        // 任务列表适配器与分隔线只需初始化一次；数据加载放到 onResume
        // （从配置页导入配置/任务后返回、或其它进程修改 DB 时，需刷新主界面列表）
        binding.recyclerView.adapter = dailyTaskAdapter
        binding.recyclerView.addItemDecoration(
            RecyclerViewItemBorder(
                marginOffset, marginOffset shr 1, marginOffset, marginOffset shr 1
            )
        )

        if (Settings.canDrawOverlays(this)) {
            Intent(this, FloatingWindowService::class.java).apply { startService(this) }
        } else {
            // 悬浮窗权限并显示悬浮窗
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            overlayPermissionLauncher.launch(intent)
        }

        // 前台服务（保活 + 托管 TaskScheduler 协程作用域 + 每日重置）
        Intent(this, ForegroundRunningService::class.java).apply { startForegroundService(this) }

        // 每个 lifecycleScope.launch 都是独立的协程，互斥，不能为了省事把协程合并，否则只会执行第一个协程的业务，其他的业务被挂起

        // 订阅每日重置时间倒计时
        lifecycleScope.launch {
            ForegroundRunningService.resetTickTime.collect { text ->
                binding.repeatTimeView.text = text
            }
        }

        // 订阅通知监听事件
        // P0：单条事件处理异常不得取消整个订阅（否则所有远程指令失效）
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

        // 订阅调度器运行状态 → 按钮 UI
        lifecycleScope.launch {
            TaskScheduler.isRunning.collectLatest { running ->
                if (running) {
                    binding.executeTaskButton.setIconResource(R.mipmap.ic_stop)
                    binding.executeTaskButton.setIconTintResource(R.color.red)
                    binding.executeTaskButton.text = "停止"
                } else {
                    dailyTaskAdapter.updateCurrentTaskState(-1)
                    binding.tipsView.text = ""
                    binding.executeTaskButton.setIconResource(R.mipmap.ic_start)
                    binding.executeTaskButton.setIconTintResource(R.color.ios_green)
                    binding.executeTaskButton.text = "启动"
                }
            }
        }

        // 订阅超时回主页信号
        lifecycleScope.launch {
            TaskScheduler.returnToApp.collectLatest {
                backToMainActivity()
            }
        }

        // 订阅 TipsEvent → tipsView + adapter 高亮
        lifecycleScope.launch {
            TaskScheduler.tipsEvent.collectLatest { event ->
                when (event) {
                    is TipsEvent.Skip -> {
                        binding.tipsView.text = "今日为周末，跳过任务"
                        binding.tipsView.setTextColor(R.color.ios_green.convertColor(this@MainActivity))
                        MessageDispatcher.sendMessage(
                            "任务跳过通知",
                            StatusReporter.buildSkipContentHtml(),
                            appendMeta = false
                        )
                    }

                    is TipsEvent.Executing -> {
                        binding.tipsView.text = "准备执行第 ${event.index} 个任务"
                        binding.tipsView.setTextColor(R.color.theme_color.convertColor(this@MainActivity))
                        dailyTaskAdapter.updateCurrentTaskState(event.index - 1, event.actualTime)

                        val content = StatusReporter.buildTaskExecutingContentHtml(
                            event.index, event.total, event.plannedTime, event.actualTime
                        )
                        MessageDispatcher.sendMessage(
                            "任务执行通知", content, appendMeta = false
                        )
                    }

                    is TipsEvent.Completed -> {
                        dailyTaskAdapter.updateCurrentTaskState(-1)
                        binding.tipsView.text = "今日任务已全部执行完毕，等待下次任务"
                        binding.tipsView.setTextColor(R.color.ios_green.convertColor(this@MainActivity))
                        val content = StatusReporter.buildTaskCompletedContentHtml()
                        MessageDispatcher.sendMessage(
                            "任务状态通知", content, appendMeta = false
                        )
                    }
                }
            }
        }

        // 兜底检查是否有错过的每日重置
        checkMissedReset()

        // 省电模式热更新：调整主界面时钟刷新频率
        lifecycleScope.launch {
            AppRuntimeConfig.powerSaveMode.collect {
                if (!maskViewController.isMaskVisible()) {
                    mainHandler.removeCallbacks(timeUpdateRunnable)
                    mainHandler.post(timeUpdateRunnable)
                }
            }
        }
    }

    override fun initEvent() {
        binding.executeTaskButton.setOnClickListener {
            if (TaskScheduler.isRunning()) {
                doStopTask()
            } else {
                lifecycleScope.launch {
                    val isEmpty = withContext(Dispatchers.IO) {
                        DatabaseWrapper.loadAllTask().isEmpty()
                    }
                    if (isEmpty) {
                        "循环任务启动失败，请先添加任务时间点".show(context)
                        return@launch
                    }
                    TaskScheduler.startTask()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 首次进入（含首次启动）必须加载一次任务列表；
        // 配置导入成功后也需刷新一次。其余 onResume 不再无谓查询 DB。
        if (ConfigImportSignal.pendingMainActivityRefresh || !taskListLoaded) {
            ConfigImportSignal.pendingMainActivityRefresh = false
            taskListLoaded = true
            refreshTaskListFromDb()
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        IdlePseudoMaskController.onAppForegrounded(this)
        applyMaskCommandFromIntent(intent)
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
        stopIdleMaskTimer()
        IdlePseudoMaskController.onAppBackgrounded(this)
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
            // 从目标 App 返回等场景：默认恢复伪息屏
            if (!maskViewController.isMaskVisible()) {
                maskViewController.showMaskView()
            }
            MaskOverlayHelper.show(this)
        }
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

    override fun onDestroy() {
        stopIdleMaskTimer()
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        maskViewController.destroy()
    }

    // NotificationMonitorService 状态观察 → UI 更新

    /**
     * 根据 MonitorEvent 驱动 UI 变化
     */
    private fun handleMonitorEvent(event: MonitorEvent) {
        when (event) {
            is MonitorEvent.ClockInSuccess -> {
                TaskScheduler.notifyClockIn() // 通知 TaskScheduler：打卡成功，取消超时等待分支
                backToMainActivity()
            }

            is MonitorEvent.StartTaskCommand -> {
                if (!TaskScheduler.isRunning()) {
                    TaskScheduler.startTask()
                }
            }

            is MonitorEvent.StopTaskCommand -> doStopTask()

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
                 *   5. 跳回 MainActivity
                 *   6. 发送通知
                 */
                lifecycleScope.launch {
                    // 倒计时 10 秒，更新悬浮窗
                    val countdownTarget = SystemClock.elapsedRealtime() + 10_000L
                    while (isActive) {
                        val remaining = countdownTarget - SystemClock.elapsedRealtime()
                        if (remaining <= 0) break
                        FloatingWindowController.updateTime((remaining / 1000).toInt())
                        delay(minOf(1000L, remaining).coerceAtLeast(1))
                    }

                    // 触发截屏并等待截屏结果
                    // 截屏服务模式优先 MediaProjection；若其未就绪但有无障碍截屏能力，则回退到无障碍截屏
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

    // 用户交互

    /**
     * 列表项单击
     * */
    private fun itemClick(position: Int) {
        if (TaskScheduler.isRunning()) {
            "任务进行中，无法修改".show(this)
            return
        }
        val item = taskBeans[position]
        val view = layoutInflater.inflate(R.layout.bottom_sheet_layout_select_time, null)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(view)
        val titleView = view.findViewById<MaterialTextView>(R.id.titleView)
        titleView.text = "修改任务时间"
        val timePicker = view.findViewById<TimeWheelLayout>(R.id.timePicker)
        timePicker.setDefaultValue(item.convertToTimeEntity())
        view.findViewById<MaterialButton>(R.id.saveButton).setOnClickListener {
            val time = String.format(
                Locale.getDefault(),
                "%02d:%02d:%02d",
                timePicker.selectedHour,
                timePicker.selectedMinute,
                timePicker.selectedSecond
            )

            lifecycleScope.launch {
                item.time = time
                withContext(Dispatchers.IO) {
                    DatabaseWrapper.updateTask(item)
                }
                taskBeans = withContext(Dispatchers.IO) {
                    DatabaseWrapper.loadAllTask()
                }
                dailyTaskAdapter.refresh(taskBeans)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    /**
     * 列表项长按
     * */
    private fun itemLongClick(position: Int) {
        if (TaskScheduler.isRunning()) {
            "任务进行中，无法删除".show(this)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("删除任务")
            .setMessage("确定要删除这个任务吗？")
            .setCancelable(false) // 禁止点击外部关闭
            .setPositiveButton("确定") { _, _ ->
                try {
                    lifecycleScope.launch {
                        val item = taskBeans[position]
                        withContext(Dispatchers.IO) {
                            DatabaseWrapper.deleteTask(item)
                        }

                        // 为了确保数据一致性，重新从数据库加载数据
                        taskBeans = withContext(Dispatchers.IO) {
                            DatabaseWrapper.loadAllTask()
                        }
                        dailyTaskAdapter.refresh(taskBeans)

                        if (taskBeans.isEmpty()) {
                            binding.recyclerView.visibility = View.GONE
                            binding.emptyView.visibility = View.VISIBLE
                        } else {
                            binding.recyclerView.visibility = View.VISIBLE
                            binding.emptyView.visibility = View.GONE
                        }
                    }
                } catch (e: IndexOutOfBoundsException) {
                    Log.e(kTag, "刷新任务列表越界", e)
                }
            }.setNegativeButton("取消", null).show()
    }

    /**
     * 从数据库重新加载任务列表并刷新主界面。
     * onResume 每次回到前台都会调用，覆盖从配置页导入配置/任务后返回、
     * 或其它进程（如导入任务对话框）修改了数据库但主界面内存列表未同步的场景。
     */
    private fun refreshTaskListFromDb() {
        lifecycleScope.launch {
            taskBeans = withContext(Dispatchers.IO) {
                DatabaseWrapper.loadAllTask()
            }
            if (taskBeans.isEmpty()) {
                binding.recyclerView.visibility = View.GONE
                binding.emptyView.visibility = View.VISIBLE
            } else {
                binding.recyclerView.visibility = View.VISIBLE
                binding.emptyView.visibility = View.GONE
            }
            dailyTaskAdapter.refresh(taskBeans)
        }
    }

    private fun createTask() {
        val view = layoutInflater.inflate(R.layout.bottom_sheet_layout_select_time, null)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(view)
        val titleView = view.findViewById<MaterialTextView>(R.id.titleView)
        titleView.text = "添加任务"
        val timePicker = view.findViewById<TimeWheelLayout>(R.id.timePicker)
        view.findViewById<MaterialButton>(R.id.saveButton).setOnClickListener {
            val time = String.format(
                Locale.getDefault(),
                "%02d:%02d:%02d",
                timePicker.selectedHour,
                timePicker.selectedMinute,
                timePicker.selectedSecond
            )

            lifecycleScope.launch {
                val exist = withContext(Dispatchers.IO) {
                    DatabaseWrapper.isTaskTimeExist(time)
                }
                if (exist) {
                    "任务时间点已存在".show(context)
                    return@launch
                }
                binding.recyclerView.visibility = View.VISIBLE
                binding.emptyView.visibility = View.GONE
                val bean = DailyTaskBean().apply {
                    this.time = time
                }
                withContext(Dispatchers.IO) {
                    DatabaseWrapper.insert(bean)
                }
                taskBeans = withContext(Dispatchers.IO) {
                    DatabaseWrapper.loadAllTask()
                }
                dailyTaskAdapter.refresh(taskBeans)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun importTask() {
        AlertInputDialog.Builder()
            .setContext(this)
            .setTitle("导入任务")
            .setHintMessage("请将导出的任务粘贴到这里")
            .setNegativeButton("取消")
            .setPositiveButton("确定")
            .setOnDialogButtonClickListener(object :
                AlertInputDialog.OnDialogButtonClickListener {
                override fun onConfirmClick(value: String) {
                    // 同一个业务，可以使用同一个协程作用域，避免重复创建
                    lifecycleScope.launch {
                        val result = withContext(Dispatchers.IO) {
                            taskDataManager.importTasks(value)
                        }
                        when (result) {
                            is TaskDataManager.ImportResult.Success -> {
                                if (result.count > 0) {
                                    taskBeans = withContext(Dispatchers.IO) {
                                        DatabaseWrapper.loadAllTask()
                                    }
                                    dailyTaskAdapter.refresh(taskBeans)
                                    binding.recyclerView.visibility = View.VISIBLE
                                    binding.emptyView.visibility = View.GONE
                                }
                                "任务导入成功".show(context)
                            }

                            is TaskDataManager.ImportResult.Error -> {
                                result.message.show(context)
                            }
                        }
                    }
                }

                override fun onCancelClick() {}
            }).build().show()
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
            stopIdleMaskTimer()
            return
        }
        mainHandler.removeCallbacks(idleMaskRunnable)
        mainHandler.postDelayed(idleMaskRunnable, 60_000L)
    }

    private fun stopIdleMaskTimer() {
        mainHandler.removeCallbacks(idleMaskRunnable)
    }

    // 辅助方法

    private fun doStopTask() {
        if (!TaskScheduler.isRunning()) return
        TaskScheduler.stopTask()
        MessageDispatcher.sendMessage(
            "停止任务通知", StatusReporter.buildStopTaskHtml(), appendMeta = false
        )
    }

    private fun backToMainActivity() {
        if (SaveKeyValues.loadBoolean(Constant.BACK_TO_HOME_KEY, false)) {
            //模拟点击Home键
            startActivity(Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) })
            lifecycleScope.launch(Dispatchers.IO) {
                delay(1000)
                withContext(Dispatchers.Main) {
                    navigatePageTo<MainActivity>()
                }
            }
        } else {
            navigatePageTo<MainActivity>()
        }
    }

    /**
     * 兜底检查：覆盖前台服务重启/进程被杀后，定时链未触发每日重置的场景
     * */
    private fun checkMissedReset() {
        val lastResetDate = SaveKeyValues.loadString(Constant.LAST_RESET_DATE_KEY, "")
        val today = Date().format("yyyy-MM-dd")

        // 今天已重置，跳过（防止重复执行）
        if (lastResetDate == today) {
            return
        }

        // 今天还未重置，执行重置（覆盖服务异常退出导致未重置的场景）
        LogFileManager.writeLog("检测到今日尚未重置，执行重置操作")
        SaveKeyValues.saveString(Constant.LAST_RESET_DATE_KEY, today)

        if (SaveKeyValues.loadBoolean(Constant.TASK_AUTO_RECYCLE_KEY, true)) {
            TaskScheduler.startTask()
        }
    }

    /**
     * P1 启动自检：核心权限缺失时主动引导用户。
     * - 通知监听未授权：提示去设置开启（远程指令依赖它）
     * - 电池优化未豁免：弹一次引导对话框，跳转豁免设置（避免后台被杀）
     */
    private fun runStartupSelfCheck() {
        if (!notificationEnable()) {
            "通知监听未开启，无法接收远程指令，请到设置页开启".show(this)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !batteryOptimizationPrompted) {
            val powerManager = getSystemService(PowerManager::class.java)
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                batteryOptimizationPrompted = true
                MaterialAlertDialogBuilder(this)
                    .setTitle("建议关闭电池优化")
                    .setMessage(
                        "本应用需长时间后台运行以监听打卡结果与远程指令。" +
                            "若被系统电池优化限制，锁屏后可能被杀掉导致指令失效。" +
                            "建议将本应用设为“不受电池优化限制”。"
                    )
                    .setNegativeButton("暂不") { _, _ -> }
                    .setPositiveButton("去设置") { _, _ ->
                        try {
                            startActivity(
                                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                    .apply { data = Uri.parse("package:$packageName") }
                            )
                        } catch (_: Exception) {
                            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }
                    }
                    .setCancelable(false)
                    .show()
            }
        }
    }

    /**
     * 悬浮窗权限启动器
     * */
    private val overlayPermissionLauncher = registerForActivityResult(permissionContract) {
        if (Settings.canDrawOverlays(this)) {
            Intent(this, FloatingWindowService::class.java).apply {
                startService(this)
            }
        }
    }
}
