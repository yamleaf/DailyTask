package com.pengxh.daily.app.service

import android.util.Log

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.content.ComponentName
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.service.notification.NotificationListenerService
import com.pengxh.daily.app.utils.BatteryHistory
import androidx.core.app.NotificationCompat
import com.pengxh.daily.app.R
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.IdlePseudoMaskController
import com.pengxh.daily.app.utils.LogFileManager
import com.pengxh.daily.app.utils.LogLevel
import com.pengxh.daily.app.utils.MessageDispatcher
import com.pengxh.daily.app.utils.StatusReporter
import com.pengxh.daily.app.utils.TaskScheduler
import com.pengxh.kt.lite.utils.SaveKeyValues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.Calendar

import com.pengxh.daily.app.extensions.format
import java.util.Date
import java.util.Locale

/**
 * APP 前台服务，降低 APP 被系统杀死的可能性。
 * 同时托管 TaskScheduler 的协程作用域。
 */
class ForegroundRunningService : Service() {

    companion object {
        /** 服务进程是否存活（保活闹钟据此判断是否需重启） */
        @Volatile
        var isRunning = false

        private val _notificationText = MutableSharedFlow<String>(extraBufferCapacity = 1)
        val notificationText = _notificationText.asSharedFlow()

        /**
         * 更新通知文字
         * */
        fun emitNotificationText(text: String) {
            _notificationText.tryEmit(text)
        }

        private val _resetTickTime = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 1)
        val resetTickTime = _resetTickTime.asSharedFlow()

        /**
         * 更新任务重置时间倒计时文字
         * */
        fun emitResetTickTime(text: String) {
            _resetTickTime.tryEmit(text)
        }

        private val _resetTaskTime = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val resetTaskTime = _resetTaskTime.asSharedFlow()

        /**
         * 更新任务重置时间点
         * */
        fun emitResetTaskTime() {
            _resetTaskTime.tryEmit(Unit)
        }
    }

    private val batteryManager by lazy { getSystemService(BatteryManager::class.java) }
    /** 上一次检测时的充电状态，用于仅在「刚插入充电」的瞬间触发一次「开始充电」通知 */
    private var wasCharging = false
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }
    private lateinit var notificationBuilder: NotificationCompat.Builder

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        // 注入协程作用域给 TaskScheduler
        TaskScheduler.attach(serviceScope)

        // 防御性重绑通知监听：覆盖安装 / 进程被杀后由保活拉起时，部分 ROM 不会主动重绑
        // NotificationListenerService，导致远程指令接收失效。进程已存活时主动请求一次重绑
        // （监听已连接则系统忽略，无副作用）。
        try {
            NotificationListenerService.requestRebind(
                ComponentName(this, NotificationMonitorService::class.java)
            )
        } catch (e: Exception) {
            Log.e(javaClass.simpleName, "ForegroundRunningService 操作异常", e)
        }

        val name = "${resources.getString(R.string.app_name)}前台服务"
        val channel = NotificationChannel(
            "foreground_running_service_channel", name, NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Channel for Foreground Running Service"
        }
        notificationManager.createNotificationChannel(channel)
        notificationBuilder =
            NotificationCompat.Builder(this, "foreground_running_service_channel").apply {
                setSmallIcon(R.mipmap.ic_launcher)
                setContentText("为保证程序正常运行，请勿移除此通知")
                setPriority(NotificationCompat.PRIORITY_LOW) // 设置通知优先级
                setOngoing(true)
                setOnlyAlertOnce(true)
                setSilent(true)
                setCategory(NotificationCompat.CATEGORY_SERVICE)
                setShowWhen(true)
                setSound(null) // 禁用声音
                setVibrate(null) // 禁用振动
            }
        val notification = notificationBuilder.build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                Constant.FOREGROUND_RUNNING_SERVICE_NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(Constant.FOREGROUND_RUNNING_SERVICE_NOTIFICATION_ID, notification)
        }

        serviceScope.launch {
            notificationText.collect { text ->
                val notification = notificationBuilder.apply {
                    setContentText(text)
                }.build()
                notificationManager.notify(
                    Constant.FOREGROUND_RUNNING_SERVICE_NOTIFICATION_ID, notification
                )
            }
        }

        serviceScope.launch {
            resetTaskTime.collect {
                updateResetTimeView()
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK) // 每分钟广播
            addAction(Intent.ACTION_BATTERY_CHANGED) // 电池状态改变广播
            addAction(Intent.ACTION_SCREEN_OFF) // 系统灭屏时尽量转入伪息屏
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(systemBroadcastReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(systemBroadcastReceiver, filter)
        }

        // 立即更新一次倒计时显示
        updateResetTimeView()

        // 初始化充电状态基线，避免服务启动瞬间误触发「开始充电」通知
        val initStatus = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
        wasCharging = initStatus == BatteryManager.BATTERY_STATUS_CHARGING
                || initStatus == BatteryManager.BATTERY_STATUS_FULL
        checkLowBattery()
        // 立即记录一笔电池采样（之后由每分钟 TIME_TICK 续记）
        BatteryHistory.recordSample(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        checkLowBattery()
        KeepAliveReceiver.schedule(this)
        // 无论是否收到重置广播，每次服务启动都确保每日重置闹钟已调度（幂等）
        KeepAliveReceiver.scheduleResetAlarm(this)
        // 重启 / 复活后恢复远程控制 MQTT 代理：开机自启与保活心跳都经本服务进入，
        // 在此统一拉起 MQTT，否则手机重启后 MqttAgentService 永不启动、控制端命令下发失效
        KeepAliveReceiver.startMqttAgentIfEnabled(this)
        // 由每日重置闹钟触发：到点后启动任务调度
        if (intent?.action == KeepAliveReceiver.ACTION_RESET_TASK) {
            if (SaveKeyValues.loadBoolean(Constant.TASK_AUTO_RECYCLE_KEY, true)
                && !TaskScheduler.isRunning()
            ) {
                TaskScheduler.startTask()
            }
            // 重置点顺手清理临时诊断文件（诊断报告 txt + 截屏兜底 png），防止长期累积占用存储
            cleanupTempDiagnosticFiles()
        }
        return START_STICKY
    }

    private val systemBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.action?.let {
                when (it) {
                    Intent.ACTION_TIME_TICK -> {
                        updateResetTimeView()
                        checkAndTriggerReset()
                        BatteryHistory.recordSample(this@ForegroundRunningService)
                    }

                    Intent.ACTION_BATTERY_CHANGED -> checkLowBattery()

                    Intent.ACTION_SCREEN_OFF -> {
                        IdlePseudoMaskController.onSystemScreenOff(this@ForegroundRunningService)
                    }
                }
            }
        }
    }

    private fun updateResetTimeView() {
        val resetHour = SaveKeyValues.loadInt(Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR)
        val seconds = resetTaskSeconds(resetHour)

        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val time = String.format(Locale.getDefault(), "%02d小时%02d分钟", hours, minutes)
        emitResetTickTime("${time}后刷新每日任务")
    }

    private fun resetTaskSeconds(hour: Int): Int {
        val calendar = Calendar.getInstance()
        val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
        val currentMinute = calendar.get(Calendar.MINUTE)
        val currentSecond = calendar.get(Calendar.SECOND)

        val todayTargetMillis = calendar.clone() as Calendar
        todayTargetMillis.set(Calendar.HOUR_OF_DAY, hour)
        todayTargetMillis.set(Calendar.MINUTE, 0)
        todayTargetMillis.set(Calendar.SECOND, 0)
        todayTargetMillis.set(Calendar.MILLISECOND, 0)

        // 根据当前时间决定计算哪一天的计划时间
        val targetMillis = if (currentHour < hour) {
            // 今天还没到计划时间
            todayTargetMillis.timeInMillis
        } else if (currentHour == hour && currentMinute == 0 && currentSecond == 0) {
            // 刚好是整点，计算明天的
            todayTargetMillis.add(Calendar.DATE, 1)
            todayTargetMillis.timeInMillis
        } else {
            // 今天已经过了计划时间，计算明天的
            todayTargetMillis.add(Calendar.DATE, 1)
            todayTargetMillis.timeInMillis
        }

        val delta = (targetMillis - System.currentTimeMillis()) / 1000
        return delta.toInt()
    }

    /**
     * 低电量告警（分段 3 次 + 充电取消）：
     * - 阈值 T（默认 30%，范围 10~80%，可在控制端镜像设置）由 [Constant.LOW_BATTERY_THRESHOLD_KEY] 读取。
     * - 三档边界 T / T-10 / T-20，每档在电量首次跌破时各告警一次（直到充电或阈值变更清零）。
     *   例 T=30：29%→第1档、19%→第2档、9%→第3档。
     * - 手机「开始充电」的瞬间（充电状态由 false→true）：清零全部三段标记，下次放电重新计数；
     *   仅当开始充电时电量仍低于阈值（即确实处于低电量告警状态）且「本轮低电量周期尚未报过」才发送一次
     *   「开始充电，低电量告警取消」通知；充电抖动或第一段/第二段/第三段均不会重复上报。
     *   只有当电量再次跌破阈值（重新进入低电量状态）时，才重置并允许下一次充电再报一次。
     * - 告警同时走反馈渠道（邮件/企业微信）与控制端 MQTT 推送（控制端 App 打开且远程控制开启时可见）。
     */
    private fun checkLowBattery() {
        val battery = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val threshold = SaveKeyValues.loadInt(
            Constant.LOW_BATTERY_THRESHOLD_KEY, Constant.DEFAULT_LOW_BATTERY_THRESHOLD
        ).coerceIn(10, 80)
        // 充电态：BATTERY_STATUS_CHARGING(插着且未充满) 或 BATTERY_STATUS_FULL(插着已充满)，均视为接入电源
        val status = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL

        // 1) 刚插入充电：清零三段标记（重新计数）；
        //    仅当「充电前电量仍低于阈值」且「本轮低电量周期尚未报过取消通知」才发一次；
        //    避免第一段/第二段/第三段各报一次、或充电抖动（断充瞬间再插电）导致的重复上报。
        if (charging && !wasCharging) {
            resetLowBatteryStages()
            val chargeNotified = SaveKeyValues.loadBoolean(Constant.LOW_BATTERY_CHARGE_NOTIFIED_KEY, false)
            if (battery < threshold) {
                if (!chargeNotified) {
                    LogFileManager.writeLog("设备开始充电：当前 ${battery}% < 阈值 ${threshold}%，发送低电量告警取消通知")
                    MessageDispatcher.sendMessage(
                        "电量开始充电",
                        StatusReporter.buildChargingResumedContentHtml(battery),
                        force = true,
                        appendMeta = false
                    )
                    MqttAgentService.publishAlert(
                        buildAlertJson("charging_resumed", battery, threshold, null)
                    )
                    SaveKeyValues.saveBoolean(Constant.LOW_BATTERY_CHARGE_NOTIFIED_KEY, true)
                } else {
                    LogFileManager.writeLog("设备开始充电：当前 ${battery}% < 阈值 ${threshold}%，但本轮已报过取消通知，跳过重复上报")
                }
            } else {
                LogFileManager.writeLog("设备开始充电：当前 ${battery}% 未低于阈值 ${threshold}%，不发送低电量告警取消通知")
            }
        }
        wasCharging = charging

        // 1.5) 电量已充满通知：充电态下电量到达 100%（status=FULL 或 capacity=100）时上报一次。
        // 与「开始充电」取消通知同规则防重复：本轮充电周期已报过则跳过，直到充电态结束
        // （拔出电源/电量回落，charging=false）才复位，允许下次充满再报一次。
        if (charging) {
            val isFull = status == BatteryManager.BATTERY_STATUS_FULL || battery >= 100
            if (isFull) {
                val fullNotified = SaveKeyValues.loadBoolean(Constant.BATTERY_FULL_NOTIFIED_KEY, false)
                if (!fullNotified) {
                    SaveKeyValues.saveBoolean(Constant.BATTERY_FULL_NOTIFIED_KEY, true)
                    LogFileManager.writeLog("电量已充满：当前 ${battery}%，发送充满通知")
                    MessageDispatcher.sendMessage(
                        "电量已充满",
                        StatusReporter.buildBatteryFullContentHtml(battery),
                        force = true,
                        appendMeta = false
                    )
                    MqttAgentService.publishAlert(
                        buildAlertJson("battery_full", battery, threshold, null)
                    )
                } else {
                    LogFileManager.writeLog("电量已充满：当前 ${battery}%，本轮已上报过，跳过重复上报")
                }
            }
        } else {
            // 未在充电：复位「已充满」标记，下次充满周期可再次上报
            SaveKeyValues.saveBoolean(Constant.BATTERY_FULL_NOTIFIED_KEY, false)
        }

        // 充电中不处理低电量分段（避免插着电反复误报）
        if (charging) return

        // 2) 三段式低电量告警
        val stageBounds = listOf(
            Triple(Constant.LOW_BATTERY_STAGE1_KEY, threshold, 1),
            Triple(Constant.LOW_BATTERY_STAGE2_KEY, threshold - 10, 2),
            Triple(Constant.LOW_BATTERY_STAGE3_KEY, threshold - 20, 3)
        )
        for ((key, bound, stage) in stageBounds) {
            if (battery < bound && !SaveKeyValues.loadBoolean(key, false)) {
                SaveKeyValues.saveBoolean(key, true)
                // 跌破任一档边界即说明当前电量低于阈值（重新进入低电量状态），
                // 允许下次充电时再报一次取消通知（清零「已报」标记）
                SaveKeyValues.saveBoolean(Constant.LOW_BATTERY_CHARGE_NOTIFIED_KEY, false)
                LogFileManager.writeLog("低电量提醒（第${stage}档，阈值<${bound}%）：当前 ${battery}%，发送通知")
                MessageDispatcher.sendMessage(
                    "低电量提醒（第${stage}档）",
                    StatusReporter.buildLowBatteryContentHtml(battery, threshold, stage),
                    force = true,
                    appendMeta = false
                )
                MqttAgentService.publishAlert(
                    buildAlertJson("low_battery", battery, threshold, stage)
                )
            }
        }
    }

    /** 清零三段低电量告警标记 */
    private fun resetLowBatteryStages() {
        SaveKeyValues.saveBoolean(Constant.LOW_BATTERY_STAGE1_KEY, false)
        SaveKeyValues.saveBoolean(Constant.LOW_BATTERY_STAGE2_KEY, false)
        SaveKeyValues.saveBoolean(Constant.LOW_BATTERY_STAGE3_KEY, false)
    }

    /** 构造低电量告警的 MQTT 推送 JSON（type: low_battery | charging_resumed | battery_full） */
    private fun buildAlertJson(type: String, battery: Int, threshold: Int, stage: Int?): String {
        return org.json.JSONObject().apply {
            put("type", type)
            put("battery", battery)
            put("threshold", threshold)
            if (stage != null) put("stage", stage)
        }.toString()
    }

    /**
     * 每分钟检查是否需要触发任务重置
     * 作为协程 delay 的兜底，防止长时间运行后协程异常退出导致任务不重置
     */
    private fun checkAndTriggerReset() {
        val resetHour = SaveKeyValues.loadInt(Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR)
        val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

        // 只在 resetHour ~ resetHour+1 这个范围触发检查
        if (currentHour !in resetHour..(resetHour + 1)) {
            return
        }

        val today = Date().format("yyyy-MM-dd")
        val lastResetDate = SaveKeyValues.loadString(Constant.LAST_RESET_DATE_KEY, "")

        // 今天已重置，跳过
        if (lastResetDate == today) {
            return
        }

        // 标记今天已重置，防止重复触发
        SaveKeyValues.saveString(Constant.LAST_RESET_DATE_KEY, today)

        // 任务重置
        if (SaveKeyValues.loadBoolean(Constant.TASK_AUTO_RECYCLE_KEY, true)) {
            TaskScheduler.startTask()
        }
    }

    /**
     * 重置点自动清理临时生成的诊断文件，避免长期累积占用存储：
     *  - Documents 目录下的 diagnostic_*.txt（一键诊断导出报告）
     *  - Pictures 目录下的 *.png（无障碍识别失败时的截屏兜底图）
     * 二者均为一次性临时文件，可安全删除；删除失败仅记录日志，不影响重置主流程。
     * 在 IO 协程中执行，避免阻塞重置广播处理。
     */
    private fun cleanupTempDiagnosticFiles() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val docsDir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                docsDir?.listFiles { file ->
                    file.name.startsWith("diagnostic_") && file.name.endsWith(".txt")
                }?.forEach { it.delete() }

                val picsDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                picsDir?.listFiles { file -> file.name.endsWith(".png") }
                    ?.forEach { it.delete() }

                LogFileManager.writeLog("重置点已自动清理临时诊断文件（diagnostic_*.txt / *.png）")
            } catch (e: Exception) {
                LogFileManager.writeLog(LogLevel.W, "重置点清理临时诊断文件失败：${e.message}")
            }
        }
    }

    override fun onDestroy() {
        isRunning = false
        // 注意：此处不再调用 KeepAliveReceiver.cancel(this)。
        // 关闭保活时已由 SettingsActivity 显式取消闹钟；若此处取消，会在服务被系统杀死时
        // 误删“下一次复活闹钟”，导致进程被杀后无法被 RESURRECT 心跳拉起。
        super.onDestroy()
        try {
            unregisterReceiver(systemBroadcastReceiver)
        } catch (e: Exception) {
            Log.e(javaClass.simpleName, "ForegroundRunningService 操作异常", e)
        }

        // 还原通知文本
        val notification = notificationBuilder.apply {
            setContentText("为保证程序正常运行，请勿移除此通知")
        }.build()
        notificationManager.notify(
            Constant.FOREGROUND_RUNNING_SERVICE_NOTIFICATION_ID, notification
        )

        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
