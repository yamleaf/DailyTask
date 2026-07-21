package com.pengxh.daily.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pengxh.daily.app.R
import com.pengxh.daily.app.databinding.ActivityTaskConfigBinding
import com.pengxh.daily.app.utils.ConfigCipher
import com.pengxh.daily.app.utils.TaskDataManager
import com.pengxh.daily.app.model.ExportDataModel
import com.pengxh.daily.app.service.ForegroundRunningService
import com.pengxh.daily.app.service.KeepAliveReceiver
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.sqlite.bean.DailyTaskBean
import com.pengxh.daily.app.utils.ConfigStore
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.CustomWorkdayManager
import com.pengxh.daily.app.utils.EmailSecureConfig
import com.pengxh.daily.app.utils.FloatingWindowController
import com.pengxh.daily.app.utils.TaskScheduler
import com.pengxh.kt.lite.base.KotlinBaseActivity
import com.pengxh.kt.lite.extensions.convertColor
import com.pengxh.kt.lite.extensions.isNumber
import com.pengxh.kt.lite.extensions.show
import com.pengxh.kt.lite.extensions.toJson
import com.pengxh.kt.lite.utils.SaveKeyValues
import com.pengxh.kt.lite.widget.dialog.AlertInputDialog
import com.pengxh.kt.lite.widget.dialog.BottomActionSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.pengxh.daily.app.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TaskConfigActivity : KotlinBaseActivity<ActivityTaskConfigBinding>() {

    private val kTag = "TaskConfigActivity"
    private val context = this
    private val hourArray = arrayListOf("0", "1", "2", "3", "4", "5", "6", "自定义（单位：时）")
    private val timeArray = arrayListOf("15", "30", "45", "自定义（单位：秒）")
    private val taskDataManager by lazy { TaskDataManager() }

    /** 配置导入：系统文件选择器（SAF）选取导出的 .json 配置文件 */
    private val pickConfigLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            lifecycleScope.launch(Dispatchers.IO) {
                val json = runCatching {
                    this@TaskConfigActivity.contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.use { it.readText() }
                }.getOrNull().orEmpty()
                if (json.isBlank()) {
                    withContext(Dispatchers.Main) { "读取配置文件失败".show(context) }
                    return@launch
                }
                when (val result = taskDataManager.importTasks(json)) {
                    is TaskDataManager.ImportResult.Success -> {
                        withContext(Dispatchers.Main) {
                            // 刷新界面开关显示
                            binding.skipHolidaySwitch.isChecked =
                                SaveKeyValues.loadBoolean(Constant.SKIP_HOLIDAY_KEY, true)
                            binding.randomTimeSwitch.isChecked =
                                SaveKeyValues.loadBoolean(Constant.RANDOM_TIME_KEY, true)
                            binding.autoTaskSwitch.isChecked =
                                SaveKeyValues.loadBoolean(Constant.TASK_AUTO_RECYCLE_KEY, true)
                            "配置导入成功（含邮箱授权码自动填充）".show(context)
                        }
                    }
                    is TaskDataManager.ImportResult.Error -> {
                        withContext(Dispatchers.Main) { result.message.show(context) }
                    }
                }
            }
        }

    override fun initViewBinding(): ActivityTaskConfigBinding {
        return ActivityTaskConfigBinding.inflate(layoutInflater)
    }

    override fun observeRequestState() {

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
        val hour = SaveKeyValues.loadInt(Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR)
        binding.resetTimeView.text = "每天${hour}点"

        val time = SaveKeyValues.loadInt(Constant.STAY_OVERTIME_KEY, Constant.DEFAULT_OVER_TIME)
        binding.timeoutTextView.text = "${time}s"

        binding.keyTextView.text = SaveKeyValues.loadString(Constant.REMOTE_COMMAND_KEY, "打卡")

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
                .setItemTextColor(R.color.theme_color.convertColor(this))
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
                .setItemTextColor(R.color.theme_color.convertColor(this))
                .setOnActionSheetListener(object : BottomActionSheet.OnActionSheetListener {
                    override fun onActionItemClick(position: Int) {
                        setTimeByPosition(position)
                    }
                }).build().show()
        }

        binding.keyLayout.setOnClickListener {
            AlertInputDialog.Builder()
                .setContext(this)
                .setTitle("设置打卡口令")
                .setHintMessage("请输入打卡口令，如：打卡")
                .setNegativeButton("取消")
                .setPositiveButton("确定")
                .setOnDialogButtonClickListener(object :
                    AlertInputDialog.OnDialogButtonClickListener {
                    override fun onConfirmClick(value: String) {
                        SaveKeyValues.saveString(Constant.REMOTE_COMMAND_KEY, value)
                        binding.keyTextView.text = value
                    }

                    override fun onCancelClick() {}
                }).build().show()
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
        }

        binding.autoTaskSwitch.setOnCheckedChangeListener { _, isChecked ->
            SaveKeyValues.saveBoolean(Constant.TASK_AUTO_RECYCLE_KEY, isChecked)
            if (isChecked) {
                KeepAliveReceiver.scheduleResetAlarm(this)
            } else {
                KeepAliveReceiver.cancelResetAlarm(this)
            }
        }

        binding.skipHolidaySwitch.setOnCheckedChangeListener { _, isChecked ->
            SaveKeyValues.saveBoolean(Constant.SKIP_HOLIDAY_KEY, isChecked)
        }

        binding.minuteRangeLayout.setOnClickListener {
            AlertInputDialog.Builder()
                .setContext(this)
                .setTitle("设置随机时间范围")
                .setHintMessage("请输入整数，如：30")
                .setNegativeButton("取消")
                .setPositiveButton("确定")
                .setOnDialogButtonClickListener(object :
                    AlertInputDialog.OnDialogButtonClickListener {
                    override fun onConfirmClick(value: String) {
                        if (value.isNumber()) {
                            updateRandomMinuteRange(value.toInt())
                        } else {
                            "直接输入整数时间即可".show(context)
                        }
                    }

                    override fun onCancelClick() {}
                }).build().show()
        }

        binding.exportLayout.setOnClickListener {
            val exportData = ExportDataModel()

            // Int
            exportData.resetTime =
                SaveKeyValues.loadInt(Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR)
            exportData.overtime =
                SaveKeyValues.loadInt(Constant.STAY_OVERTIME_KEY, Constant.DEFAULT_OVER_TIME)
            exportData.timeRange =
                SaveKeyValues.loadInt(Constant.TIME_RANGE_KEY, Constant.DEFAULT_TIME_RANGE)
            exportData.msgChannel =
                SaveKeyValues.loadInt(Constant.MSG_CHANNEL_KEY, Constant.DEFAULT_INDEX)
            exportData.targetApp = SaveKeyValues.loadInt(Constant.TARGET_APP_KEY, 0)
            exportData.targetAppPackage = Constant.getTargetApp()

            // String
            exportData.remoteCommand = SaveKeyValues.loadString(Constant.REMOTE_COMMAND_KEY, "打卡")
            exportData.msgTitle =
                SaveKeyValues.loadString(Constant.MESSAGE_TITLE_KEY, "打卡结果通知")
            exportData.wxKey = SaveKeyValues.loadString(Constant.WX_WEB_HOOK_KEY, "")
            exportData.customWorkdays = CustomWorkdayManager.serializeWorkdays(
                CustomWorkdayManager.loadWorkdays()
            )

            // Boolean
            exportData.isDetectGesture =
                SaveKeyValues.loadBoolean(Constant.GESTURE_DETECTOR_KEY, true)
            exportData.isBackToHome = SaveKeyValues.loadBoolean(Constant.BACK_TO_HOME_KEY, false)
            exportData.isAutoRecycle =
                SaveKeyValues.loadBoolean(Constant.TASK_AUTO_RECYCLE_KEY, true)
            exportData.isRandomTime = SaveKeyValues.loadBoolean(Constant.RANDOM_TIME_KEY, true)
            exportData.isSkipHoliday = SaveKeyValues.loadBoolean(Constant.SKIP_HOLIDAY_KEY, true)
            exportData.isSavePower =
                SaveKeyValues.loadBoolean(Constant.POWER_SAVE_MODE_KEY, false)

            // 邮箱：收发箱照常导出；授权码以 AES 加密形式写入文件，导入时自动解密填充（避免明文/脱敏泄露）
            val obj = ConfigStore.get().load(Constant.EMAIL_CONFIG_KEY)
            if (!obj.isEmpty) {
                val outbox = if (obj.has("outbox")) obj.get("outbox").asString else ""
                val inbox = if (obj.has("inbox")) obj.get("inbox").asString else ""
                if (outbox.isNotBlank() && inbox.isNotBlank()) {
                    val rawAuth = EmailSecureConfig.loadAuthCode()
                    exportData.emailConfig = Triple(outbox, "", inbox)
                    exportData.emailAuthEncrypted =
                        if (rawAuth.isNotBlank()) ConfigCipher.encrypt(rawAuth) else ""
                }
            }

            // TaskBeans
            lifecycleScope.launch {
                val taskBeans = withContext(Dispatchers.IO) {
                    DatabaseWrapper.loadAllTask()
                }
                exportData.tasks = if (taskBeans.isNotEmpty()) taskBeans else ArrayList<DailyTaskBean>()

                val json = exportData.toJson()
                Log.d(kTag, "导出配置长度=${json.length}")

                // 写入文件（getExternalFilesDir/Documents，无需存储权限）
                val dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                if (dir == null) {
                    "导出失败：外部存储不可用".show(context)
                    return@launch
                }
                val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())
                val file = File(dir, "dailytask_config_$timeStamp.json")
                runCatching { file.writeText(json) }.onFailure {
                    "导出失败：${it.message}".show(context)
                    return@launch
                }

                // 通过系统分享面板导出文件（FileProvider 授权临时读取）
                val authority = BuildConfig.APPLICATION_ID + ".fileprovider"
                val uri = FileProvider.getUriForFile(this@TaskConfigActivity, authority, file)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    startActivity(Intent.createChooser(shareIntent, "导出配置"))
                    "配置已导出为文件：${file.name}".show(context)
                } catch (e: Exception) {
                    "导出失败：${e.message}".show(context)
                }
            }

        }

        binding.importLayout.setOnClickListener {
            pickConfigLauncher.launch("application/json")
        }
    }

    private fun showWorkdaySelector() {
        val orderedDays = CustomWorkdayManager.getOrderedDays()
        val selectedDays = CustomWorkdayManager.loadWorkdays().toMutableSet()
        val labels = orderedDays.map { CustomWorkdayManager.getDayLabel(it) }.toTypedArray()
        val checkedItems = orderedDays.map { it in selectedDays }.toBooleanArray()

        MaterialAlertDialogBuilder(this)
            .setTitle("选择工作日")
            .setMultiChoiceItems(labels, checkedItems) { _, which, isChecked ->
                val day = orderedDays[which]
                if (isChecked) {
                    selectedDays.add(day)
                } else {
                    selectedDays.remove(day)
                }
            }
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ ->
                if (selectedDays.isEmpty()) {
                    "至少保留一天为工作日".show(this)
                    return@setPositiveButton
                }

                val normalized = orderedDays.filter { it in selectedDays }.toSet()
                CustomWorkdayManager.saveWorkdays(normalized)
                updateCustomWorkdaySummary(normalized)
            }
            .show()
    }

    private fun updateCustomWorkdaySummary(workdays: Set<DayOfWeek>) {
        binding.workdayValueView.text = CustomWorkdayManager.formatWorkdays(workdays)
    }

    private fun setHourByPosition(position: Int) {
        if (position == hourArray.size - 1) {
            AlertInputDialog.Builder()
                .setContext(this)
                .setTitle("设置重置时间")
                .setHintMessage("直接输入整数时间即可，如：6")
                .setNegativeButton("取消")
                .setPositiveButton("确定")
                .setOnDialogButtonClickListener(object :
                    AlertInputDialog.OnDialogButtonClickListener {
                    override fun onConfirmClick(value: String) {
                        if (value.isNumber()) {
                            updateResetHour(value.toInt())
                        } else {
                            "直接输入整数时间即可".show(context)
                        }
                    }

                    override fun onCancelClick() {}
                }).build().show()
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
        // 通知 Service 更新倒计时显示
        ForegroundRunningService.emitResetTaskTime()
        // 通知调度器：重置时间已修改，立即按新时间重算每日等待（下一分钟级生效，不重启任务）
        TaskScheduler.notifyResetTimeChanged()
        // 重新调度每日重置精确闹钟，使自定义重置点即时生效
        KeepAliveReceiver.scheduleResetAlarm(this, hour)
    }

    private fun setTimeByPosition(position: Int) {
        if (position == timeArray.size - 1) {
            AlertInputDialog.Builder()
                .setContext(this)
                .setTitle("设置超时时间")
                .setHintMessage("直接输入整数时间即可，如：60")
                .setNegativeButton("取消")
                .setPositiveButton("确定")
                .setOnDialogButtonClickListener(object :
                    AlertInputDialog.OnDialogButtonClickListener {
                    override fun onConfirmClick(value: String) {
                        if (value.isNumber()) {
                            updateTimeout(value.toInt())
                        } else {
                            "直接输入整数时间即可".show(context)
                        }
                    }

                    override fun onCancelClick() {}
                }).build().show()
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
    }

    private fun updateRandomMinuteRange(value: Int) {
        if (value < 0) {
            "随机时间范围不能小于0分钟".show(this)
            return
        }
        binding.minuteRangeView.text = "${value}分钟"
        SaveKeyValues.saveInt(Constant.TIME_RANGE_KEY, value)
    }
}
