package com.pengxh.daily.app.ui

import com.pengxh.daily.app.UiInsets
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.pengxh.daily.app.R
import com.pengxh.daily.app.databinding.ActivityTaskConfigBinding
import com.pengxh.daily.app.utils.ChinaHolidayManager
import com.pengxh.daily.app.utils.ConfigImportSignal
import com.pengxh.daily.app.service.ForegroundRunningService
import com.pengxh.daily.app.service.KeepAliveReceiver
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.CustomWorkdayManager
import com.pengxh.daily.app.utils.FloatingWindowController
import com.pengxh.daily.app.utils.TaskScheduler
import com.pengxh.kt.lite.base.KotlinBaseActivity
import com.pengxh.kt.lite.extensions.convertColor
import com.pengxh.kt.lite.extensions.isNumber
import com.pengxh.kt.lite.extensions.show
import com.pengxh.kt.lite.utils.SaveKeyValues
import com.pengxh.kt.lite.widget.dialog.BottomActionSheet
import com.yample.mqttprotocol.dialog.UnifiedDialogKit
import java.time.DayOfWeek
import java.time.LocalDate


class TaskConfigActivity : KotlinBaseActivity<ActivityTaskConfigBinding>() {

    private val kTag = "TaskConfigActivity"
    private val context = this
    private val timeArray = arrayListOf("15", "30", "45", "自定义（单位：秒）")
    override fun initViewBinding(): ActivityTaskConfigBinding {
        return ActivityTaskConfigBinding.inflate(layoutInflater)
    }

    override fun observeRequestState() {

    }

    override fun setupTopBarLayout() {
        UiInsets.applyStatusBarPadding(this, binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    override fun initOnCreate(savedInstanceState: Bundle?) {
        reloadSettingsUI()
    }

    /**
     * 从 SP 重新加载并刷新所有设置项 UI。导入配置成功后调用，
     * 确保界面实时反映导入结果（否则用户会误以为“导入不生效”）。
     */
    private fun reloadSettingsUI() {
        val hour = SaveKeyValues.loadInt(Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR)
        binding.resetTimeView.text = "每天${hour}点"

        val time = SaveKeyValues.loadInt(Constant.STAY_OVERTIME_KEY, Constant.DEFAULT_OVER_TIME)
        binding.timeoutTextView.text = "${time}s"

        binding.keyTextView.text = SaveKeyValues.loadString(Constant.REMOTE_COMMAND_KEY, "打卡")

        val punchKeywords = SaveKeyValues.loadString(Constant.PUNCH_RESULT_KEYWORDS_KEY, "")
        binding.punchKeywordView.text = if (punchKeywords.isBlank()) "默认" else punchKeywords

        updateCustomWorkdaySummary(CustomWorkdayManager.loadWorkdays())

        binding.autoTaskSwitch.isChecked =
            SaveKeyValues.loadBoolean(Constant.TASK_AUTO_RECYCLE_KEY, true)

        binding.skipHolidaySwitch.isChecked =
            SaveKeyValues.loadBoolean(Constant.SKIP_HOLIDAY_KEY, true)

        val needRandom = SaveKeyValues.loadBoolean(Constant.RANDOM_TIME_KEY, true)
        binding.randomTimeSwitch.isChecked = needRandom
        if (needRandom) {
            binding.minuteRangeLayout.visibility = View.VISIBLE
            val value = SaveKeyValues.loadInt(Constant.TIME_RANGE_KEY, Constant.DEFAULT_TIME_RANGE)
            binding.minuteRangeView.text = "${value}分钟"
        } else {
            binding.minuteRangeLayout.visibility = View.GONE
        }

        bindHolidayInfo()
    }

    override fun initEvent() {
        binding.resetTimeLayout.setOnClickListener { showResetHourSlider() }

        binding.timeoutLayout.setOnClickListener {
            BottomActionSheet.Builder()
                .setContext(this)
                .setActionItemTitle(timeArray)
                .setItemTextColor(R.color.md_primary.convertColor(this))
                .setOnActionSheetListener(object : BottomActionSheet.OnActionSheetListener {
                    override fun onActionItemClick(position: Int) {
                        setTimeByPosition(position)
                    }
                }).build().show()
        }

        binding.keyLayout.setOnClickListener {
            val editText = EditText(this).apply { hint = "请输入打卡口令，如：打卡" }
            UnifiedDialogKit.showForm(
                this, editText,
                title = "设置打卡口令",
                positiveText = "确定",
                negativeText = "取消",
                onConfirm = {
                    val value = editText.text.toString().trim()
                    if (value.isEmpty()) {
                        "输入错误，请检查！".show(this)
                        false
                    } else {
                        SaveKeyValues.saveString(Constant.REMOTE_COMMAND_KEY, value)
                        binding.keyTextView.text = value
                        ConfigImportSignal.notifyRemoteChanged(context)
                        true
                    }
                }
            )
        }

        binding.punchKeywordLayout.setOnClickListener {
            val current = SaveKeyValues.loadString(Constant.PUNCH_RESULT_KEYWORDS_KEY, "")
            val editText = EditText(this).apply {
                hint = "多个关键字用逗号分隔，如：上班打卡成功,打卡完成"
                setText(current)
                setSelection(current.length)
            }
            UnifiedDialogKit.showForm(
                this, editText,
                title = "设置打卡结果关键字",
                positiveText = "确定",
                negativeText = "取消",
                onConfirm = {
                    val value = editText.text.toString().trim()
                    val normalized = value.split(",", "，").map { it.trim() }
                        .filter { it.isNotEmpty() }.distinct().joinToString(",")
                    if (normalized.isEmpty()) {
                        SaveKeyValues.saveString(Constant.PUNCH_RESULT_KEYWORDS_KEY, "")
                        binding.punchKeywordView.text = "默认"
                        "已清除自定义关键字，恢复默认".show(this)
                        true
                    } else {
                        SaveKeyValues.saveString(Constant.PUNCH_RESULT_KEYWORDS_KEY, normalized)
                        binding.punchKeywordView.text = normalized
                        true
                    }
                }
            )
        }

        binding.workdayLayout.setOnClickListener {
            showWorkdaySelector()
        }

        binding.randomTimeSwitch.setOnCheckedChangeListener { _, isChecked ->
            SaveKeyValues.saveBoolean(Constant.RANDOM_TIME_KEY, isChecked)
            if (isChecked) {
                binding.minuteRangeLayout.visibility = View.VISIBLE
                val value =
                    SaveKeyValues.loadInt(Constant.TIME_RANGE_KEY, Constant.DEFAULT_TIME_RANGE)
                binding.minuteRangeView.text = "${value}分钟"
            } else {
                binding.minuteRangeLayout.visibility = View.GONE
            }
            ConfigImportSignal.notifyRemoteChanged(context)
        }

        binding.autoTaskSwitch.setOnCheckedChangeListener { _, isChecked ->
            SaveKeyValues.saveBoolean(Constant.TASK_AUTO_RECYCLE_KEY, isChecked)
            if (isChecked) {
                KeepAliveReceiver.scheduleResetAlarm(this)
            } else {
                KeepAliveReceiver.cancelResetAlarm(this)
            }
            ConfigImportSignal.notifyRemoteChanged(context)
        }

        binding.skipHolidaySwitch.setOnCheckedChangeListener { _, isChecked ->
            SaveKeyValues.saveBoolean(Constant.SKIP_HOLIDAY_KEY, isChecked)
            ConfigImportSignal.notifyRemoteChanged(context)
        }

        binding.minuteRangeLayout.setOnClickListener {
            val editText = EditText(this).apply { hint = "请输入整数，如：30" }
            UnifiedDialogKit.showForm(
                this, editText,
                title = "设置随机时间范围",
                positiveText = "确定",
                negativeText = "取消",
                onConfirm = {
                    val value = editText.text.toString().trim()
                    if (value.isEmpty()) {
                        "输入错误，请检查！".show(this)
                        false
                    } else if (value.isNumber()) {
                        updateRandomMinuteRange(value.toInt())
                        true
                    } else {
                        "直接输入整数时间即可".show(this)
                        false
                    }
                }
            )
        }

    }

    /**
     * 绑定「节假日数据」信息块：有缓存 / 无缓存两种状态都展示（纯 UI 展示，不修改任何持久化数据）。
     * - 状态 badge：已同步（绿 md_success）｜未同步（橙 md_warning）
     * - 第 2 行：覆盖年份 · 节假日/补班天数；有缓存未解析时提示"已缓存"；无缓存给"暂无数据"说明
     * - 第 3 行：今日类型 + 是否影响任务；无内存数据时给同步操作指引
     * - 第 4 行：缓存年份过期给黄色提示；正常显示数据来源；无缓存隐藏
     * 所有数据读取均 runCatching / ?: 兜底，异常路径返回默认占位，绝不向调用方抛错。
     */
    private fun bindHolidayInfo() {
        val mgr = ChinaHolidayManager
        binding.holidayInfoLayout.visibility = View.VISIBLE

        val holidayCount = mgr.getHolidayCount()
        val workdayCount = mgr.getWorkdayCount()
        val cachedYear = mgr.getCachedYear()
        val currentYear = LocalDate.now().year
        val hasInMemory = holidayCount > 0 || workdayCount > 0
        val hasCache = cachedYear != null
        val ready = hasInMemory || hasCache

        // 状态 badge（标题行右侧）
        if (ready) {
            binding.holidayStatusView.text = "已同步"
            binding.holidayStatusView.setTextColor(R.color.md_success.convertColor(this))
        } else {
            binding.holidayStatusView.text = "未同步"
            binding.holidayStatusView.setTextColor(R.color.md_warning.convertColor(this))
        }

        // 第 2 行：覆盖数据
        binding.holidayCoverageView.text = when {
            hasInMemory -> "覆盖：${cachedYear ?: currentYear}年  ·  节假日 ${holidayCount} 天  ·  补班 ${workdayCount} 天"
            hasCache -> "覆盖：${cachedYear}年 · 已缓存，等待加载"
            else -> "暂无节假日数据，节假日/补班判断暂不生效"
        }

        // 第 3 行：今日类型（有内存数据才可判断；否则给同步指引）
        if (hasInMemory) {
            val today = LocalDate.now()
            val todayKind = when {
                mgr.isHoliday(today) -> "法定节假日"
                mgr.isWorkday(today) -> "调休补班日"
                today.dayOfWeek.value >= 6 -> "周末"
                else -> "工作日"
            }
            val skipOn = SaveKeyValues.loadBoolean(Constant.SKIP_HOLIDAY_KEY, true)
            val impact = when {
                mgr.isHoliday(today) && skipOn -> "开启跳过，不打卡"
                mgr.isHoliday(today) && !skipOn -> "未开跳过，仍打卡"
                else -> "不影响任务"
            }
            binding.holidayTodayView.text = "今日：${todayKind} · ${impact}"
            binding.holidayTodayView.visibility = View.VISIBLE
        } else {
            binding.holidayTodayView.text =
                if (hasCache) "数据已缓存，重启 App 后生效" else "前往「设置」→「更新节假日数据」同步后生效"
            binding.holidayTodayView.visibility = View.VISIBLE
        }

        // 第 4 行：过期提示 / 来源 / 隐藏
        if (hasCache && cachedYear != currentYear) {
            binding.holidayUpdateView.text = "缓存为 ${cachedYear} 年数据，建议在设置页更新"
            binding.holidayUpdateView.setTextColor(R.color.md_warning.convertColor(this))
            binding.holidayUpdateView.visibility = View.VISIBLE
        } else if (ready) {
            binding.holidayUpdateView.text = "数据来源：远程缓存"
            binding.holidayUpdateView.setTextColor(R.color.md_onSurfaceVariant.convertColor(this))
            binding.holidayUpdateView.visibility = View.VISIBLE
        } else {
            binding.holidayUpdateView.visibility = View.GONE
        }
    }

    /**
     * 从设置页同步完节假日数据返回时（如点过"更新节假日数据"），任务配置页可见时需刷新
     * 该信息块。onResume 是最简的统一触发点：Activity 入栈/出栈回到前台都会走到这里，
     * 幂等无副作用，不影响其他 setting 控件状态。
     */
    override fun onResume() {
        super.onResume()
        bindHolidayInfo()
    }

    private fun showWorkdaySelector() {
        val orderedDays = CustomWorkdayManager.getOrderedDays()
        val selectedDays = CustomWorkdayManager.loadWorkdays().toMutableSet()
        val labels = orderedDays.map { CustomWorkdayManager.getDayLabel(it) }.toList()
        val checkedItems = orderedDays.map { it in selectedDays }.toBooleanArray()

        UnifiedDialogKit.showMultiChoice(
            this,
            "选择工作日",
            labels,
            checkedItems,
            onConfirm = { checked ->
                val current = orderedDays.filterIndexed { index, _ -> checked[index] }.toSet()
                if (current.isEmpty()) {
                    "至少保留一天为工作日".show(this)
                    false
                } else {
                    val normalized = orderedDays.filter { it in current }.toSet()
                    CustomWorkdayManager.saveWorkdays(normalized)
                    updateCustomWorkdaySummary(normalized)
                    ConfigImportSignal.notifyRemoteChanged(context)
                    true
                }
            }
        )
    }

    private fun updateCustomWorkdaySummary(workdays: Set<DayOfWeek>) {
        binding.workdayValueView.text = CustomWorkdayManager.formatWorkdays(workdays)
    }

    /** 重置点设置：滑块样式（对齐控制端）——大号当前值 + 0~23 小时滑块 */
    private fun showResetHourSlider() {
        val dp = { v: Float -> (v * resources.displayMetrics.density + 0.5f).toInt() }
        val current = SaveKeyValues.loadInt(Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR)
        val valueView = TextView(this).apply {
            text = "每天 ${current} 点"
            gravity = Gravity.CENTER
            setTextColor(R.color.md_primary.convertColor(this@TaskConfigActivity))
            textSize = 36f
            isAllCaps = false
            setPadding(0, dp(16f), 0, dp(8f))
        }
        val slider = com.google.android.material.slider.Slider(this).apply {
            valueFrom = 0f
            valueTo = 23f
            stepSize = 1f
            value = current.toFloat().coerceIn(0f, 23f)
            addOnChangeListener { _, value, _ ->
                valueView.text = "每天 ${value.toInt()} 点"
            }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f), dp(8f), dp(24f), dp(8f))
            addView(valueView)
            addView(slider)
        }
        UnifiedDialogKit.showForm(
            ctx = this,
            contentView = content,
            title = "设置重置时间",
            positiveText = "保存",
            negativeText = "取消",
            onConfirm = {
                updateResetHour(slider.value.toInt())
                true
            }
        )
    }

    private fun updateResetHour(hour: Int) {
        if (hour !in 0..23) {
            "重置时间必须在0到23点之间".show(this)
            return
        }
        binding.resetTimeView.text = "每天${hour}点"
        setTaskResetTime(hour)
    }

    private fun setTaskResetTime(hour: Int) {
        SaveKeyValues.saveInt(Constant.RESET_TIME_KEY, hour)
        ForegroundRunningService.emitResetTaskTime()
        // 通知调度器：重置时间已修改，立即按新时间重算每日等待（下一分钟级生效，不重启任务）
        TaskScheduler.notifyResetTimeChanged()
        // 重新调度每日重置精确闹钟，使自定义重置点即时生效
        KeepAliveReceiver.scheduleResetAlarm(this, hour)
        ConfigImportSignal.notifyRemoteChanged(context)
    }

    private fun setTimeByPosition(position: Int) {
        if (position == timeArray.size - 1) {
            val editText = EditText(this).apply { hint = "直接输入整数时间即可，如：60" }
            UnifiedDialogKit.showForm(
                this, editText,
                title = "设置超时时间",
                positiveText = "确定",
                negativeText = "取消",
                onConfirm = {
                    val value = editText.text.toString().trim()
                    if (value.isEmpty()) {
                        "输入错误，请检查！".show(this)
                        false
                    } else if (value.isNumber()) {
                        updateTimeout(value.toInt())
                        true
                    } else {
                        "直接输入整数时间即可".show(this)
                        false
                    }
                }
            )
        } else {
            updateTimeout(timeArray[position].toInt())
        }
    }

    private fun updateTimeout(time: Int) {
        if (time <= 0) {
            "超时时间必须大于0秒".show(this)
            return
        }
        binding.timeoutTextView.text = "${time}s"
        SaveKeyValues.saveInt(Constant.STAY_OVERTIME_KEY, time)
        FloatingWindowController.setOvertime(time)
        ConfigImportSignal.notifyRemoteChanged(context)
    }

    private fun updateRandomMinuteRange(value: Int) {
        if (value < 0) {
            "随机时间范围不能小于0分钟".show(this)
            return
        }
        binding.minuteRangeView.text = "${value}分钟"
        SaveKeyValues.saveInt(Constant.TIME_RANGE_KEY, value)
        ConfigImportSignal.notifyRemoteChanged(context)
    }
}
