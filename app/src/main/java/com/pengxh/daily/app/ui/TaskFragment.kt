package com.pengxh.daily.app.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.github.gzuliyujiang.wheelpicker.widget.TimeWheelLayout
import com.pengxh.daily.app.R
import com.pengxh.daily.app.adapter.DailyTaskAdapter
import com.pengxh.daily.app.databinding.FragmentTaskBinding
import com.pengxh.daily.app.extensions.convertToTimeEntity
import com.pengxh.daily.app.extensions.format
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.sqlite.bean.DailyTaskBean
import com.pengxh.daily.app.utils.ChinaHolidayManager
import com.pengxh.daily.app.utils.CustomWorkdayManager
import com.pengxh.daily.app.utils.DailyTask
import com.pengxh.daily.app.utils.TaskScheduler
import com.pengxh.daily.app.utils.WatermarkDrawable
import com.pengxh.kt.lite.base.KotlinBaseFragment
import com.pengxh.kt.lite.divider.RecyclerViewItemBorder
import com.pengxh.kt.lite.extensions.convertColor
import com.pengxh.kt.lite.extensions.dp2px
import com.pengxh.kt.lite.extensions.show
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
    private val marginOffset by lazy { 16.dp2px(ctx) }
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val taskDataManager by lazy { com.pengxh.daily.app.utils.TaskDataManager() }

    private var taskBeans: MutableList<DailyTaskBean> = ArrayList()

    private val dailyTaskAdapter by lazy {
        DailyTaskAdapter(taskBeans).apply {
            setOnItemClickListener(object : DailyTaskAdapter.OnItemClickListener {
                override fun onItemClick(position: Int) = itemClick(position)
                override fun onItemLongClick(position: Int) = itemLongClick(position)
            })
        }
    }

    /** 顶部时钟 + 节日/工作日标签（整秒刷新）；伪息屏蒙层展示期间仅跳过更新，不中断调度 */
    private val timeUpdateRunnable = object : Runnable {
        override fun run() {
            val activity = activity
            if (activity !is MainActivity || !activity.isMaskVisible()) {
                // 日期拆分：2026年08月07日 15:30:22 星期五 → [日期, 时间, 星期]
                val parts = Date().format("yyyy年MM月dd日 HH:mm:ss EEEE").split(" ")
                if (parts.size >= 3) {
                    val today = LocalDate.now()
                    val dayType = when {
                        ChinaHolidayManager.isHoliday(today) -> "节假日"
                        ChinaHolidayManager.isWorkday(today) -> "补班日"
                        CustomWorkdayManager.isWeekdayRestDay(today) -> "休息日"
                        else -> "工作日"
                    }
                    binding.toolbar.title = "${parts[2]}（$dayType）"
                    binding.toolbar.subtitle = "${parts[0]} ${parts[1]}"
                }
            }
            mainHandler.postDelayed(this, 1000L)
        }
    }

    override fun initViewBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentTaskBinding =
        FragmentTaskBinding.inflate(inflater, container, false)

    override fun setupTopBarLayout() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { view, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.setPadding(0, statusBarHeight, 0, 0)
            insets
        }
        mainHandler.post(timeUpdateRunnable)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_add_task -> {
                    if (TaskScheduler.isRunning()) {
                        "任务进行中，无法添加".show(ctx)
                    } else if (taskBeans.isEmpty()) {
                        // 空列表：弹出「添加任务 / 导入任务」选择
                        BottomActionSheet.Builder()
                            .setContext(ctx)
                            .setActionItemTitle(arrayListOf("添加任务", "导入任务"))
                            .setItemTextColor(R.color.theme_color.convertColor(ctx))
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
            }
            true
        }
    }

    override fun initOnCreate(savedInstanceState: Bundle?) {
        // 任务页内容区水印背景（防截屏溯源）
        binding.contentView.background = WatermarkDrawable(requireActivity(), DailyTask.getWatermarkText())

        binding.recyclerView.adapter = dailyTaskAdapter
        binding.recyclerView.addItemDecoration(
            RecyclerViewItemBorder(marginOffset, marginOffset / 4, marginOffset, marginOffset / 4)
        )

        // 每日重置后的重复周期提示（repeatTimeView）
        lifecycleScope.launch {
            com.pengxh.daily.app.service.ForegroundRunningService.Companion.resetTickTime.collect {
                binding.repeatTimeView.text = it
            }
        }
        // 任务运行状态 → 启动/停止按钮与提示
        lifecycleScope.launch {
            TaskScheduler.isRunning.collectLatest { running ->
                if (!running) {
                    dailyTaskAdapter.updateCurrentTaskState(-1)
                    binding.tipsView.text = ""
                    binding.executeTaskButton.setIconResource(R.mipmap.ic_start)
                    binding.executeTaskButton.setIconTintResource(R.color.ios_green)
                    binding.executeTaskButton.text = "启动"
                } else {
                    binding.executeTaskButton.setIconResource(R.mipmap.ic_stop)
                    binding.executeTaskButton.setIconTintResource(R.color.red)
                    binding.executeTaskButton.text = "停止"
                }
            }
        }
        // 任务执行提示事件（跳过/执行中/完成）
        lifecycleScope.launch {
            TaskScheduler.tipsEvent.collectLatest { event ->
                when (event) {
                    is com.pengxh.daily.app.utils.TipsEvent.Skip -> {
                        dailyTaskAdapter.updateCurrentTaskState(-1)
                        binding.tipsView.text = "本次任务已跳过，等待下一次"
                    }

                    is com.pengxh.daily.app.utils.TipsEvent.Executing -> {
                        binding.tipsView.text =
                            "正在执行第 ${event.index}/${event.total} 个任务（${event.actualTime}）"
                    }

                    is com.pengxh.daily.app.utils.TipsEvent.Completed -> {
                        dailyTaskAdapter.updateCurrentTaskState(-1)
                        binding.tipsView.text = "今日任务已全部执行完毕，等待下次任务"
                    }
                }
            }
        }
        // 省电模式 → 刷新时钟频率
        lifecycleScope.launch {
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
            if (TaskScheduler.isRunning()) {
                (activity as? MainActivity)?.doStopTask()
            } else {
                lifecycleScope.launch {
                    TaskScheduler.startTask()
                }
            }
        }
    }

    /** 添加任务：底部时间选择弹层 */
    private fun createTask() {
        val view = layoutInflater.inflate(R.layout.bottom_sheet_layout_select_time, null)
        val dialog = BottomSheetDialog(requireContext())
        dialog.setContentView(view)
        view.findViewById<com.google.android.material.textview.MaterialTextView>(R.id.titleView).text = "添加任务"
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.saveButton).setOnClickListener {
            val timePicker = view.findViewById<TimeWheelLayout>(R.id.timePicker)
            val time = String.format(
                Locale.getDefault(), "%02d:%02d:%02d",
                timePicker.selectedHour, timePicker.selectedMinute, timePicker.selectedSecond
            )
            val name = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.nameEditText)
                .text?.toString()?.trim().orEmpty()
            lifecycleScope.launch {
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
    private fun itemClick(position: Int) {
        if (TaskScheduler.isRunning()) {
            "任务进行中，无法修改".show(ctx)
            return
        }
        val item = taskBeans[position]
        val view = layoutInflater.inflate(R.layout.bottom_sheet_layout_select_time, null)
        val dialog = BottomSheetDialog(requireContext())
        dialog.setContentView(view)
        view.findViewById<com.google.android.material.textview.MaterialTextView>(R.id.titleView).text = "修改任务时间"
        val timePicker = view.findViewById<TimeWheelLayout>(R.id.timePicker)
        timePicker.setDefaultValue(item.convertToTimeEntity())
        view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.nameEditText).setText(item.name)
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.saveButton).setOnClickListener {
            val time = String.format(
                Locale.getDefault(), "%02d:%02d:%02d",
                timePicker.selectedHour, timePicker.selectedMinute, timePicker.selectedSecond
            )
            val name = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.nameEditText)
                .text?.toString()?.trim().orEmpty()
            lifecycleScope.launch {
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

    /** 长按任务：删除确认 */
    private fun itemLongClick(position: Int) {
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
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            DatabaseWrapper.deleteTask(taskBeans[position])
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
                    lifecycleScope.launch {
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
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) { DatabaseWrapper.loadAllTask() }
            taskBeans.clear()
            taskBeans.addAll(rows)
            dailyTaskAdapter.refresh(taskBeans)
            binding.recyclerView.visibility = if (taskBeans.isEmpty()) View.GONE else View.VISIBLE
            binding.emptyView.visibility = if (taskBeans.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) refreshTaskListFromDb()
    }

    override fun onDestroyView() {
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroyView()
    }
}
