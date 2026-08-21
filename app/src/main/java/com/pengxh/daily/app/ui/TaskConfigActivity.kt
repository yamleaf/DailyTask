package com.pengxh.daily.app.ui

import com.pengxh.daily.app.UiInsets
import android.os.Bundle
import android.view.View
import android.widget.EditText
import com.pengxh.daily.app.R
import com.pengxh.daily.app.databinding.ActivityTaskConfigBinding
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


class TaskConfigActivity : KotlinBaseActivity<ActivityTaskConfigBinding>() {

    private val kTag = "TaskConfigActivity"
    private val context = this
    private val hourArray = arrayListOf("0", "1", "2", "3", "4", "5", "6", "自定义（单位：时）")
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

    }

    override fun initEvent() {
        binding.resetTimeLayout.setOnClickListener {
            BottomActionSheet.Builder()
                .setContext(this)
                .setActionItemTitle(hourArray)
                .setItemTextColor(R.color.md_primary.convertColor(this))
                .setOnActionSheetListener(object : BottomActionSheet.OnActionSheetListener {
                    override fun onActionItemClick(position: Int) {
                        setHourByPosition(position)
                    }
                }).build().show()
        }

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

    private fun setHourByPosition(position: Int) {
        if (position == hourArray.size - 1) {
            val editText = EditText(this).apply { hint = "直接输入整数时间即可，如：6" }
            UnifiedDialogKit.showForm(
                this, editText,
                title = "设置重置时间",
                positiveText = "确定",
                negativeText = "取消",
                onConfirm = {
                    val value = editText.text.toString().trim()
                    if (value.isEmpty()) {
                        "输入错误，请检查！".show(this)
                        false
                    } else if (value.isNumber()) {
                        updateResetHour(value.toInt())
                        true
                    } else {
                        "直接输入整数时间即可".show(this)
                        false
                    }
                }
            )
        } else {
            updateResetHour(hourArray[position].toInt())
        }
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
