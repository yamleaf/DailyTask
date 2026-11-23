package com.pengxh.daily.app.ui

import com.pengxh.daily.app.UiInsets
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.github.gzuliyujiang.wheelpicker.widget.TimeWheelLayout
import com.pengxh.daily.app.R
import com.pengxh.daily.app.adapter.DailyTaskAdapter
import com.pengxh.daily.app.databinding.FragmentTaskBinding
import com.pengxh.daily.app.databinding.SheetTaskActionsBinding
import com.pengxh.daily.app.extensions.convertToTimeEntity
import com.pengxh.daily.app.extensions.format
import com.pengxh.daily.app.extensions.resolveRandomTimeRange
import com.pengxh.daily.app.service.KeepAliveReceiver
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.sqlite.bean.DailyTaskBean
import com.pengxh.daily.app.utils.ChinaHolidayManager
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.CustomWorkdayManager
import com.pengxh.daily.app.utils.DailyTask
import com.pengxh.daily.app.utils.TaskScheduler
import com.pengxh.daily.app.utils.WatermarkDrawable
import com.pengxh.daily.app.widget.ChainLineDecoration
import com.pengxh.daily.app.widget.SwipeRevealLayout
import com.pengxh.kt.lite.base.KotlinBaseFragment
import com.pengxh.kt.lite.extensions.convertColor
import com.pengxh.kt.lite.extensions.show
import com.pengxh.kt.lite.utils.SaveKeyValues
import com.pengxh.kt.lite.widget.dialog.BottomActionSheet
import com.pengxh.daily.app.utils.DialogCardBuilder
import com.yample.mqttprotocol.dialog.UnifiedDialogKit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Date
import java.util.Locale

/**
 * 任务 Tab：任务列表 / 增删改 / 导入导出 / 执行状态展示 / 顶部时钟与节日提示。
 */
class TaskFragment : KotlinBaseFragment<FragmentTaskBinding>() {

    private val kTag = "TaskFragment"
    private val ctx by lazy { requireContext() }
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val taskDataManager by lazy { com.pengxh.daily.app.utils.TaskDataManager() }

    private var taskBeans: MutableList<DailyTaskBean> = ArrayList()

    /** 上次已更新的标题日期（跨天才重算星期+节假日标题） */
    private var lastTitleDate: String? = null

    private val dailyTaskAdapter by lazy {
        DailyTaskAdapter(taskBeans).apply {
            // 左滑展开互斥需要按位置反查 SwipeRevealLayout，避免 Adapter 直接持有 View
            openLayoutForPosition = { pos ->
                binding.recyclerView.findViewHolderForAdapterPosition(pos)?.itemView
                    as? SwipeRevealLayout
            }
            setOnItemClickListener(object : DailyTaskAdapter.OnItemClickListener {
                // 点击卡片仍是「修改任务」（DT 的使用习惯），左滑提供同一入口的快捷方式
                override fun onItemClick(item: DailyTaskBean) = itemClick(item)

                // 长按改为「任务详情」弹层：展示全量信息 + 复制时间 + 编辑/删除
                override fun onItemLongClick(item: DailyTaskBean) = showTaskActions(item)

                override fun onActionEdit(item: DailyTaskBean) = itemClick(item)

                override fun onActionDelete(item: DailyTaskBean) = confirmDeleteTask(item)
            })
        }
    }

    /** 链式时间轴装饰器；需在视图销毁时 release，否则无限动画会一直跑并持有 RecyclerView */
    private var chainDecoration: ChainLineDecoration? = null

    /** 顶部时钟 + 节日/工作日标签（整秒刷新）；伪息屏蒙层展示期间仅跳过更新，不中断调度 */
    private val timeUpdateRunnable = object : Runnable {
        override fun run() {
            val activity = activity
            if (activity !is MainActivity || !activity.isMaskVisible()) {
            // 日期拆分：2026年08月07日 15:30:22 星期五 → [日期, 时间, 星期]
            val parts = Date().format("yyyy年MM月dd日 HH:mm:ss EEEE").split(" ")
            if (parts.size >= 3) {
                // 星期+节假日标题只在日期跨天时更新（避免每秒重算节假日/重绘 title）
                if (parts[0] != lastTitleDate) {
                    lastTitleDate = parts[0]
                    val today = LocalDate.now()
                    val dayType = when {
                        ChinaHolidayManager.isHoliday(today) -> "节假日"
                        ChinaHolidayManager.isWorkday(today) -> "补班日"
                        CustomWorkdayManager.isWeekdayRestDay(today) -> "休息日"
                        else -> "工作日"
                    }
                    binding.toolbar.title = "${parts[2]}（$dayType）"
                }
                binding.toolbar.subtitle = "${parts[0]} ${parts[1]}"
            }
                // 调度中：过点后自动隐藏「实际」小字
                if (TaskScheduler.isRunning()) {
                    dailyTaskAdapter.refreshActualHintVisibility()
                }
            }
            mainHandler.postDelayed(this, 1000L)
        }
    }

    override fun initViewBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentTaskBinding =
        FragmentTaskBinding.inflate(inflater, container, false)

    override fun setupTopBarLayout() {
        UiInsets.applyStatusBarPadding(requireActivity(), binding.toolbar)
        mainHandler.post(timeUpdateRunnable)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_command_history -> {
                    val intent = Intent(requireActivity(), CommandHistoryActivity::class.java)
                    startActivity(intent)
                }
                R.id.menu_log_viewer -> {
                    val intent = Intent(requireActivity(), LogViewerActivity::class.java)
                    startActivity(intent)
                }
            }
            true
        }
    }

    override fun initOnCreate(savedInstanceState: Bundle?) {
        // 任务页内容区水印背景（防截屏溯源）
        binding.contentView.background = WatermarkDrawable(requireActivity(), DailyTask.getWatermarkText())

        binding.recyclerView.adapter = dailyTaskAdapter
        // 链式任务轨道：节点连线 + 流动光点。
        // 卡片化后 item 自带 6dp 上下外边距提供间距，不再需要 RecyclerViewItemBorder
        // （否则间距叠加成 20dp，且会和卡片描边视觉冲突）。
        chainDecoration = ChainLineDecoration(requireContext()).also {
            binding.recyclerView.addItemDecoration(it)
        }

        binding.fabAddTask.setOnClickListener { showAddTaskSheet() }

        binding.btnPunchRecord.setOnClickListener {
            val intent = Intent(requireActivity(), PunchRecordActivity::class.java)
            startActivity(intent)
        }

        updateStatusExtraInfo()

        // 每日重置后的重复周期提示（repeatTimeView）
        viewLifecycleOwner.lifecycleScope.launch {
            com.pengxh.daily.app.service.ForegroundRunningService.Companion.resetTickTime.collect {
                binding.repeatTimeView.text = it.replace("后刷新每日任务", "后刷新")
            }
        }
        // 任务运行状态 → 启动/停止按钮与提示；实际时间小字仅调度中展示
        viewLifecycleOwner.lifecycleScope.launch {
            TaskScheduler.isRunning.collectLatest { running ->
                bindSchedulerState(running)
                if (!running) {
                    dailyTaskAdapter.updateCurrentTaskState(-1)
                    dailyTaskAdapter.setActualHintState(false, emptyMap())
                    binding.tipsView.text = "点击按钮后开始执行任务"
                    binding.execDetailView.visibility = View.GONE
                    binding.executeTaskButton.setIconResource(R.drawable.ic_start)
                    binding.executeTaskButton.setIconTintResource(R.color.md_success)
                    binding.executeTaskButton.text = "启动"
                    updateStatusExtraInfo()
                } else {
                    binding.executeTaskButton.setIconResource(R.drawable.ic_stop)
                    binding.executeTaskButton.setIconTintResource(R.color.md_error)
                    binding.executeTaskButton.text = "停止"
                    refreshActualHintsFromSchedule()
                    updateStatusExtraInfo()
                }
            }
        }
        // 任务执行提示事件（跳过/执行中/完成）
        viewLifecycleOwner.lifecycleScope.launch {
            TaskScheduler.tipsEvent.collectLatest { event ->
                when (event) {
                    is com.pengxh.daily.app.utils.TipsEvent.Skip -> {
                        dailyTaskAdapter.updateCurrentTaskState(-1)
                        binding.tipsView.text = "本次任务已跳过，等待下一次"
                        binding.execDetailView.visibility = View.GONE
                        refreshActualHintsFromSchedule()
                    }

                    is com.pengxh.daily.app.utils.TipsEvent.Executing -> {
                        binding.tipsView.text =
                            "正在执行${event.index}/${event.total}个任务"
                        val detail = if (event.name.isBlank()) {
                            event.actualTime
                        } else {
                            "${event.actualTime}  ${event.name}"
                        }
                        // 执行时间加粗 + 品牌主色，备注保持常规灰
                        val span = SpannableString(detail)
                        span.setSpan(
                            StyleSpan(Typeface.BOLD),
                            0, event.actualTime.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        span.setSpan(
                            ForegroundColorSpan(
                                ContextCompat.getColor(requireContext(), R.color.md_primary)
                            ),
                            0, event.actualTime.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        binding.execDetailView.text = span
                        binding.execDetailView.visibility = View.VISIBLE
                        refreshActualHintsFromSchedule()
                    }

                    is com.pengxh.daily.app.utils.TipsEvent.Completed -> {
                        dailyTaskAdapter.updateCurrentTaskState(-1)
                        binding.tipsView.text = "今日任务已全部执行完毕，等待下次任务"
                        binding.execDetailView.visibility = View.GONE
                        dailyTaskAdapter.setActualHintState(true, emptyMap())
                        updateStatusExtraInfo()
                    }
                }
            }
        }
        // 省电模式 → 刷新时钟频率
        viewLifecycleOwner.lifecycleScope.launch {
            com.pengxh.daily.app.utils.AppRuntimeConfig.powerSaveMode.collect {
                mainHandler.removeCallbacks(timeUpdateRunnable)
                mainHandler.post(timeUpdateRunnable)
            }
        }

        refreshTaskListFromDb()
    }

    override fun observeRequestState() {
    }

    override fun initEvent() {
        binding.executeTaskButton.setOnClickListener {
            // 「暂停使用」开启时禁止手动启动任务（暂停期间基本功能不可用）
            if (KeepAliveReceiver.isPaused()) {
                "暂停使用中，请先关闭「暂停使用」开关恢复功能".show(requireContext())
                return@setOnClickListener
            }
            if (TaskScheduler.isRunning()) {
                (activity as? MainActivity)?.doStopTask()
            } else {
                viewLifecycleOwner.lifecycleScope.launch {
                    TaskScheduler.startTask()
                }
            }
        }
    }

    /** 添加任务：底部时间选择弹层 */
    /** 顶部状态点：运行中=绿色，已停止=中性灰；只表达调度状态，不参与执行逻辑 */
    private fun bindSchedulerState(running: Boolean) {
        binding.schedulerStatusDotView.background?.mutate()?.setTint(
            ContextCompat.getColor(
                requireContext(),
                if (running) R.color.md_success else R.color.md_onSurfaceVariant
            )
        )
    }

    /** 状态卡附加信息：只展示偏离默认的异常态（循环关闭/跳过节假日/自定义工作日），默认配置不噪音展示 */
    private fun updateStatusExtraInfo() {
        val parts = mutableListOf<String>()
        val autoRecycle = SaveKeyValues.loadBoolean(Constant.TASK_AUTO_RECYCLE_KEY, true)
        val skipHoliday = SaveKeyValues.loadBoolean(Constant.SKIP_HOLIDAY_KEY, false)
        val customWorkday = SaveKeyValues.loadString(Constant.CUSTOM_WORKDAYS_KEY, "").isNotBlank()
        if (!autoRecycle) parts.add("循环关闭")
        if (skipHoliday) parts.add("跳过节假日")
        if (customWorkday) parts.add("自定义工作日")
        if (parts.isEmpty()) {
            binding.statusExtraView.visibility = View.GONE
        } else {
            binding.statusExtraView.text = parts.joinToString(" · ")
            binding.statusExtraView.visibility = View.VISIBLE
        }
    }

    private fun showAddTaskSheet() {
        if (TaskScheduler.isRunning()) {
            "任务进行中，无法添加".show(ctx)
        } else if (taskBeans.isEmpty()) {
            BottomActionSheet.Builder()
                .setContext(ctx)
                .setActionItemTitle(arrayListOf("添加任务", "导入任务"))
                .setItemTextColor(R.color.md_primary.convertColor(ctx))
                .setOnActionSheetListener(object : BottomActionSheet.OnActionSheetListener {
                    override fun onActionItemClick(position: Int) {
                        if (position == 0) createTask() else importTask()
                    }
                })
                .build()
                .show()
        } else {
            createTask()
        }
    }

    private fun createTask() {
        val view = layoutInflater.inflate(R.layout.bottom_sheet_layout_select_time, null)
        val dialog = BottomSheetDialog(requireContext())
        dialog.setContentView(view)
        view.findViewById<com.google.android.material.textview.MaterialTextView>(R.id.titleView).text = "添加任务"
        view.findViewById<android.widget.ImageView>(R.id.btnClose).setOnClickListener { dialog.dismiss() }
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.cancelButton).setOnClickListener { dialog.dismiss() }
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.saveButton).setOnClickListener {
            val timePicker = view.findViewById<TimeWheelLayout>(R.id.timePicker)
            val time = String.format(
                Locale.getDefault(), "%02d:%02d:%02d",
                timePicker.selectedHour, timePicker.selectedMinute, timePicker.selectedSecond
            )
            val name = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.nameEditText)
                .text?.toString()?.trim().orEmpty()
            viewLifecycleOwner.lifecycleScope.launch {
                // IO：查重 → 不重复则插入 → 重载列表
                val exists = withContext(Dispatchers.IO) { DatabaseWrapper.isTaskTimeExist(time) }
                if (exists) {
                    "任务时间点已存在".show(ctx)
                } else {
                    val bean = DailyTaskBean().apply {
                        this.time = time
                        this.name = name
                    }
                    withContext(Dispatchers.IO) {
                        DatabaseWrapper.insert(bean)
                        taskBeans = DatabaseWrapper.loadAllTask()
                    }
                    dailyTaskAdapter.refresh(taskBeans)
                    binding.recyclerView.visibility = View.VISIBLE
                    binding.emptyView.visibility = View.GONE
                    com.pengxh.daily.app.utils.ConfigImportSignal.notifyRemoteChanged(ctx)
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    /** 点击任务：修改任务时间 */
    private fun itemClick(item: DailyTaskBean) {
        if (TaskScheduler.isRunning()) {
            "任务进行中，无法修改".show(ctx)
            return
        }
        val view = layoutInflater.inflate(R.layout.bottom_sheet_layout_select_time, null)
        val dialog = BottomSheetDialog(requireContext())
        dialog.setContentView(view)
        view.findViewById<com.google.android.material.textview.MaterialTextView>(R.id.titleView).text = "修改任务时间"
        view.findViewById<android.widget.ImageView>(R.id.btnClose).setOnClickListener { dialog.dismiss() }
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.cancelButton).setOnClickListener { dialog.dismiss() }
        val timePicker = view.findViewById<TimeWheelLayout>(R.id.timePicker)
        // 布局完成后再回填，避免 TimeWheel 尚未 measure 时 setDefaultValue 不生效
        val defaultEntity = item.convertToTimeEntity()
        timePicker.post { timePicker.setDefaultValue(defaultEntity) }
        view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.nameEditText).setText(item.name)
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.saveButton).setOnClickListener {
            val time = String.format(
                Locale.getDefault(), "%02d:%02d:%02d",
                timePicker.selectedHour, timePicker.selectedMinute, timePicker.selectedSecond
            )
            val name = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.nameEditText)
                .text?.toString()?.trim().orEmpty()
            viewLifecycleOwner.lifecycleScope.launch {
                // 用新 Bean 实例写库，避免原地改 item（适配器当前列表持有同一对象）导致
                // 后续 submitList 的 DiffUtil 比对时新旧 time 相同 → 判定无变化 → UI 不刷新
                val updated = DailyTaskBean().apply {
                    id = item.id
                    this.time = time
                    this.name = name
                }
                withContext(Dispatchers.IO) {
                    DatabaseWrapper.updateTask(updated)
                    taskBeans = DatabaseWrapper.loadAllTask()
                }
                dailyTaskAdapter.refresh(taskBeans)
                binding.recyclerView.visibility = View.VISIBLE
                binding.emptyView.visibility = View.GONE
                com.pengxh.daily.app.utils.ConfigImportSignal.notifyRemoteChanged(ctx)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    /**
     * 长按任务：任务详情弹层（与左滑的「快捷操作」分工）。
     *
     * 上半部分是只读详情——完整打卡时间（卡片上已完整，可一键复制）、随机窗口、
     * 实际执行、完整备注（卡片上单行省略）；下半部分才是编辑、删除。
     * 弹层内部一律用 Fragment 的 [ctx] 懒加载属性，避免显示期间 detach 后 requireContext() 抛异常。
     */
    private fun showTaskActions(item: DailyTaskBean) {
        val sheetBinding = SheetTaskActionsBinding.inflate(layoutInflater)

        // 标题右侧状态胶囊：与列表同一套配色
        val (label, textColorRes, bgColorRes) = dailyTaskAdapter.stateBadge(item)
        sheetBinding.tvActionStatus.text = label
        sheetBinding.tvActionStatus.setTextColor(ContextCompat.getColor(ctx, textColorRes))
        sheetBinding.tvActionStatus.background?.mutate()
            ?.setTint(ContextCompat.getColor(ctx, bgColorRes))

        // 详情只读区
        sheetBinding.tvDetailTime.text = item.time
        val randomEnabled = SaveKeyValues.loadBoolean(Constant.RANDOM_TIME_KEY, true)
        val minuteRange = SaveKeyValues.loadInt(Constant.TIME_RANGE_KEY, Constant.DEFAULT_TIME_RANGE)
        sheetBinding.tvDetailRandomWindow.text =
            item.resolveRandomTimeRange(randomEnabled, minuteRange) ?: "未启用"
        sheetBinding.tvDetailActual.text = dailyTaskAdapter.actualTimeText(item)
        sheetBinding.tvDetailName.text = item.name.orEmpty().ifBlank { "无备注" }

        val sheet = BottomSheetDialog(ctx)
        sheet.setContentView(sheetBinding.root)

        // 复制打卡时间：左滑面板没有的能力，让长按有独立价值
        sheetBinding.btnCopyTime.setOnClickListener {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("打卡时间", item.time))
            // Android 13+ 系统自带剪贴板浮层提示，不再重复弹 Toast
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                "已复制打卡时间 ${item.time}".show(ctx)
            }
        }

        sheetBinding.cardActionEdit.setOnClickListener {
            sheet.dismiss()
            itemClick(item)
        }
        sheetBinding.cardActionDelete.setOnClickListener {
            sheet.dismiss()
            confirmDeleteTask(item)
        }
        sheetBinding.btnActionCancel.setOnClickListener { sheet.dismiss() }
        sheet.show()
    }

    /** 左滑「删除」/ 详情弹层「删除任务」共用的二次确认 */
    private fun confirmDeleteTask(item: DailyTaskBean) {
        if (TaskScheduler.isRunning()) {
            "任务进行中，无法删除".show(ctx)
            return
        }
        DialogCardBuilder.show(
            ctx,
            "删除任务",
            DialogCardBuilder.CardSpec(
                paragraphs = listOf("确定要删除这个任务吗？"),
                notice = "此操作不可恢复" to DialogCardBuilder.NoticeKind.DANGER
            ),
            positiveText = "确定",
            danger = true,
            onConfirm = {
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            DatabaseWrapper.deleteTask(item)
                            taskBeans = DatabaseWrapper.loadAllTask()
                        }
                        dailyTaskAdapter.refresh(taskBeans)
                        binding.recyclerView.visibility = if (taskBeans.isEmpty()) View.GONE else View.VISIBLE
                        binding.emptyView.visibility = if (taskBeans.isEmpty()) View.VISIBLE else View.GONE
                        com.pengxh.daily.app.utils.ConfigImportSignal.notifyRemoteChanged(ctx)
                    } catch (e: Exception) {
                        android.util.Log.e(kTag, "刷新任务列表越界", e)
                    }
                }
            }
        )
    }

    /** 导入任务：粘贴文本解析 */
    private fun importTask() {
        val editText = EditText(requireContext()).apply {
            hint = "请将导出的任务粘贴到这里"
            isSingleLine = false
            minLines = 4
            gravity = android.view.Gravity.START or android.view.Gravity.TOP
        }
        UnifiedDialogKit.showForm(
            requireContext(), editText,
            title = "导入任务",
            positiveText = "确定",
            negativeText = "取消",
            onConfirm = {
                val value = editText.text.toString().trim()
                if (value.isEmpty()) {
                    "输入错误，请检查！".show(ctx)
                    false
                } else {
                    viewLifecycleOwner.lifecycleScope.launch {
                        // 解析导入文本并写入数据库（每行一个时间 HH:mm:ss）
                        val times = value.split("\n").map { it.trim() }.filter {
                            it.matches(Regex("\\d{1,2}:\\d{2}:\\d{2}"))
                        }
                        for (t in times) {
                            if (!DatabaseWrapper.isTaskTimeExist(t)) {
                                DatabaseWrapper.insert(DailyTaskBean().apply { time = t })
                            }
                        }
                        taskBeans = withContext(Dispatchers.IO) { DatabaseWrapper.loadAllTask() }
                        dailyTaskAdapter.refresh(taskBeans)
                        binding.recyclerView.visibility = if (taskBeans.isEmpty()) View.GONE else View.VISIBLE
                        binding.emptyView.visibility = if (taskBeans.isEmpty()) View.VISIBLE else View.GONE
                        com.pengxh.daily.app.utils.ConfigImportSignal.notifyRemoteChanged(ctx)
                    }
                    true
                }
            }
        )
    }

    /** 从数据库加载任务列表并刷新 UI */
    fun refreshTaskListFromDb() {
        viewLifecycleOwner.lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) { DatabaseWrapper.loadAllTask() }
            taskBeans.clear()
            taskBeans.addAll(rows)
            dailyTaskAdapter.refresh(taskBeans)
            binding.recyclerView.visibility = if (taskBeans.isEmpty()) View.GONE else View.VISIBLE
            binding.emptyView.visibility = if (taskBeans.isEmpty()) View.VISIBLE else View.GONE
            if (TaskScheduler.isRunning()) {
                refreshActualHintsFromSchedule()
            }
        }
    }

    /**
     * 调度已启动时：用今日计划全量驱动列表状态（待执行小字 + 已过点琥珀节点）。
     * 传全量（含已过点），Adapter 内部按 millis 区分「待执行 / 已过点 / 非今日任务」，
     * 小字展示仍只在未过点时显示（bindActualHint 内判 millis > now）。
     */
    private fun refreshActualHintsFromSchedule() {
        if (!TaskScheduler.isRunning()) {
            dailyTaskAdapter.setActualHintState(false, emptyMap())
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val plans = withContext(Dispatchers.IO) {
                TaskScheduler.loadTodayTaskPlans(usePersisted = true)
            }
            val allPlans = plans.associate { plan ->
                plan.task.id to DailyTaskAdapter.PendingActualHint(
                    displayTime = plan.actualTime,
                    millis = plan.actualTimeMillis
                )
            }
            dailyTaskAdapter.setActualHintState(true, allPlans)
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            // 重新可见：恢复每秒时钟刷新（隐藏期间已停，避免后台 Tab 空转浪费 CPU）
            mainHandler.removeCallbacks(timeUpdateRunnable)
            mainHandler.post(timeUpdateRunnable)
            chainDecoration?.resume()
            refreshTaskListFromDb()
        } else {
            // 隐藏：停止时钟 Runnable，避免隐藏 Tab 每秒空转执行日期格式化/节假日查询/adapter 刷新
            mainHandler.removeCallbacks(timeUpdateRunnable)
            // 同时暂停链式脉冲：列表已是 GONE、不再重绘，但无限 ValueAnimator 仍会每帧 invalidate
            chainDecoration?.pause()
        }
    }

    override fun onDestroyView() {
        mainHandler.removeCallbacksAndMessages(null)
        // 必须 release：装饰器里的循环动画否则会一直跑，并通过 host 持有 RecyclerView / 整套视图
        chainDecoration?.release()
        chainDecoration = null
        super.onDestroyView()
    }
}
