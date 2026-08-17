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
import com.pengxh.daily.app.utils.AppRuntimeConfig
import com.pengxh.daily.app.utils.BatteryHistory
import com.pengxh.daily.app.utils.BatteryPredictor
import androidx.core.app.NotificationCompat
import com.pengxh.daily.app.R
import com.pengxh.daily.app.utils.ConfigImportSignal
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.FloatingWindowController
import com.pengxh.daily.app.utils.IdlePseudoMaskController
import com.pengxh.daily.app.utils.LogFileManager
import com.pengxh.daily.app.utils.LogLevel
import com.pengxh.daily.app.utils.MaskOverlayHelper
import com.pengxh.daily.app.utils.MessageDispatcher
import com.pengxh.daily.app.utils.StatusReporter
import com.pengxh.daily.app.utils.TaskScheduler
import com.pengxh.kt.lite.utils.SaveKeyValues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

        /** 前台保活服务本次启动的墙钟时间戳（ms）。onCreate 写入，服务重启后重新计时。
         * RemoteSnapshot.serviceRunningMinutes 据此计算「服务运行时长」，供控制端展示。 */
        @Volatile
        var serviceStartAtMs = 0L

        /** 本进程内是否已尝试过开机自动调度（避免每次 onStartCommand 重复拉起） */
        @Volatile
        private var bootAutoScheduleTried = false

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
        // 「暂停使用」开启时：不在 onCreate 发布前台通知，直接自停，避免通知栏「消失又出现」闪烁。
        if (KeepAliveReceiver.isPaused()) {
            stopSelf()
            return
        }
        isRunning = true
        serviceStartAtMs = System.currentTimeMillis()
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
        // C1：前台服务启动防护。Android 15+ 后台启动前台服务受严格配额限制，
        // startForeground 可能抛 ForegroundServiceStartNotAllowedException；任其逃逸会以
        // "Unable to create service" 崩溃整个进程，连带同进程的通知监听（远程指令）一起死掉。
        // 失败时优雅 stopSelf，由 KeepAliveReceiver 心跳/前台打开 App 在配额恢复后重新拉起。
        val foregroundOk = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    Constant.FOREGROUND_RUNNING_SERVICE_NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(Constant.FOREGROUND_RUNNING_SERVICE_NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            LogFileManager.error("ForegroundRunningService startForeground 被系统拒绝：${e.message}")
            Log.e(javaClass.simpleName, "startForeground 失败（后台 FGS 配额限制）", e)
            isRunning = false
            // 配额耗尽：拉长救援间隔，避免短时间连撞 Android 15 FGS 限额
            KeepAliveReceiver.scheduleFgsQuotaBackoff(this)
            stopSelf()
            false
        }
        if (!foregroundOk) return

        // 对齐当前亮灭屏，避免已灭屏拉起时 screenOn 仍为默认 true
        FloatingWindowController.syncScreenOnFromSystem(this)

        // 进程复活后：按调度意图自动续跑（与开机自动调度独立）
        KeepAliveReceiver.tryResumeSchedulerIfWanted(this)

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
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(ConfigImportSignal.ACTION_REMOTE_CONFIG_CHANGED)
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
        // 「暂停使用」开启时自停：防御任意路径（广播/回调）在暂停期间拉起本服务。
        // 前置判断，避免 onCreate 已产生的副作用（TaskScheduler 作用域、通知重绑等）继续运行。
        if (KeepAliveReceiver.isPaused()) {
            stopSelf()
            return START_NOT_STICKY
        }
        checkLowBattery()
        KeepAliveReceiver.schedule(this)
        KeepAliveReceiver.scheduleResetAlarm(this)
        KeepAliveReceiver.scheduleBatteryAlert(this)
        KeepAliveReceiver.startMqttAgentIfEnabled(this)
        if (intent?.action == KeepAliveReceiver.ACTION_RESET_TASK) {
            if (SaveKeyValues.loadBoolean(Constant.TASK_AUTO_RECYCLE_KEY, true)) {
                if (TaskScheduler.isRunning()) {
                    // 调度在跑（可能处于「等待重置期」被冻结，isRunning()=true 但实际空转）→
                    // 发信号让 waitUntilNextReset 立即返回重排；旧逻辑直接 return 导致重置永远无法推进
                    TaskScheduler.notifyResetTimeReached()
                } else {
                    TaskScheduler.startTask()
                    // 无等待期信号可释放，主动卸掉 RESET 闹钟持的推进锁
                    KeepAliveReceiver.releaseSchedulerAdvanceWakeLock()
                }
            } else {
                KeepAliveReceiver.releaseSchedulerAdvanceWakeLock()
            }
            cleanupTempDiagnosticFiles()
        }
        // 开机路径必须拉悬浮窗；自动调度在 maybeTryBootAutoSchedule 中统一处理
        if (intent?.action == KeepAliveReceiver.ACTION_BOOT_SETUP) {
            KeepAliveReceiver.ensureFloatingWindow(this)
        }
        maybeTryBootAutoSchedule(intent?.action ?: "onStart")
        if (intent?.action == KeepAliveReceiver.ACTION_BATTERY_ALERT) {
            checkBatterySmartAlert()
        }
        // 「后台自启」总开关：关闭时返回 START_NOT_STICKY，进程被杀后系统不会自动重建本服务
        return if (KeepAliveReceiver.isKeepAliveEnabled()) START_STICKY else START_NOT_STICKY
    }

    private val systemBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.action?.let {
                when (it) {
                    Intent.ACTION_TIME_TICK -> {
                        updateResetTimeView()
                        checkAndTriggerReset()
                        BatteryHistory.recordSample(this@ForegroundRunningService)
                        checkBatterySmartAlert()
                    }

                    Intent.ACTION_BATTERY_CHANGED -> checkLowBattery()

                    Intent.ACTION_SCREEN_OFF -> {
                        IdlePseudoMaskController.onSystemScreenOff(this@ForegroundRunningService)
                        // 真息屏打卡优化：伪息屏关 + 屏幕模式=息屏时，摘掉打卡返回时盖的不保亮黑蒙层。
                        // 守卫同时保证不会误摘：伪息屏开→伪息屏蒙层（onSystemScreenOff 已处理）、
                        // 模式0→前台无操作蒙层（shouldForegroundIdleMaskWhenPseudoOff）。
                        // 仅在蒙层确实存在时摘除并记日志——SCREEN_OFF 在任务等待期/普通灭屏也会触发，
                        // 无条件记录会让日志失去「蒙层是否真被摘除」的可信度（排查黑屏残留的关键判据）。
                        if (!AppRuntimeConfig.isForcePseudoMask() &&
                            AppRuntimeConfig.getScreenMode() == Constant.SCREEN_MODE_OFF &&
                            MaskOverlayHelper.isShowing()
                        ) {
                            MaskOverlayHelper.hide(
                                this@ForegroundRunningService,
                                MaskOverlayHelper.HideReason.SYNC
                            )
                            LogFileManager.writeLog("系统灭屏：摘除真息屏打卡的不保亮黑蒙层")
                        }
                        FloatingWindowController.setScreenOn(false)
                    }

                    Intent.ACTION_SCREEN_ON -> {
                        // 兜底：若厂商漏发 SCREEN_OFF（蒙层未摘），亮屏时再摘一次，避免下次亮屏仍黑屏
                        if (!AppRuntimeConfig.isForcePseudoMask() &&
                            AppRuntimeConfig.getScreenMode() == Constant.SCREEN_MODE_OFF
                        ) {
                            MaskOverlayHelper.hide(
                                this@ForegroundRunningService,
                                MaskOverlayHelper.HideReason.SYNC
                            )
                        }
                        FloatingWindowController.setScreenOn(true)
                    }

                    ConfigImportSignal.ACTION_REMOTE_CONFIG_CHANGED -> {
                        KeepAliveReceiver.scheduleBatteryAlert(this@ForegroundRunningService)
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
                    LogFileManager.action("设备开始充电：当前 ${battery}% < 阈值 ${threshold}%，发送低电量告警取消通知")
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
                    LogFileManager.action("电量已充满：当前 ${battery}%，发送充满通知")
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

        // 2) 三段式低电量告警（受告警次数设置控制，各段按比例划分，最低不低于 1%）
        val maxStages = SaveKeyValues.loadInt(Constant.BATTERY_ALERT_MAX_STAGES_KEY, 3).coerceIn(0, 3)
        if (maxStages == 0) return  // 0=关闭低电量告警
        // 各段边界：阈值 / 阈值*2/3 / 阈值*1/3（比例划分，始终有效，最低 1%）
        val stageAll = listOf(
            Triple(Constant.LOW_BATTERY_STAGE1_KEY, threshold, 1),
            Triple(Constant.LOW_BATTERY_STAGE2_KEY, (threshold * 2 / 3).coerceAtLeast(1), 2),
            Triple(Constant.LOW_BATTERY_STAGE3_KEY, (threshold * 1 / 3).coerceAtLeast(1), 3)
        )
        val stageBounds = stageAll.take(maxStages)
        for ((key, bound, stage) in stageBounds) {
            if (battery < bound && !SaveKeyValues.loadBoolean(key, false)) {
                SaveKeyValues.saveBoolean(key, true)
                // 跌破任一档边界即说明当前电量低于阈值（重新进入低电量状态），
                // 允许下次充电时再报一次取消通知（清零「已报」标记）
                SaveKeyValues.saveBoolean(Constant.LOW_BATTERY_CHARGE_NOTIFIED_KEY, false)
                LogFileManager.action("低电量提醒（第${stage}档，阈值<${bound}%）：当前 ${battery}%，发送通知")
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

    /**
     * 电量智能预警检查（仅在预警时间点 ±5 分钟内运行，避免提前发送）。
     *
     * 根据 BatteryPredictor 预测电量降至低电量阈值的时间，若落在检测区间内，
     * 则在预警时间前发送预警，防止用户睡眠期间低电量关机。
     * 仅在预警时间点 ±5 分钟窗口内发送，确保只在预警时间点推送邮件。
     *
     * 日志：无论最终是否触发，均在到点时落一条关键动作日志（成功或原因），
     * 便于排查“到点无邮件”类问题（Release 下普通日志不落文件，故用 action）。
     */
    private fun checkBatterySmartAlert() {
        try {
            if (!SaveKeyValues.loadBoolean(Constant.BATTERY_SMART_ALERT_ENABLED_KEY, false)) return

            // 仅在预警时间点 ±5 分钟窗口内检查
            val warningMinute = SaveKeyValues.loadInt(
                Constant.BATTERY_WARNING_HOUR_KEY,
                20 * 60
            ).coerceIn(0, 1439)
            val cal = Calendar.getInstance()
            val now = cal.timeInMillis
            cal.set(Calendar.HOUR_OF_DAY, warningMinute / 60)
            cal.set(Calendar.MINUTE, warningMinute % 60)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val warningTimeMs = cal.timeInMillis
            val windowMs = 5 * 60 * 1000L // ±5 分钟
            if (now !in (warningTimeMs - windowMs)..(warningTimeMs + windowMs)) return

            // 每日流程标记：用本地日历日，避免 UTC 日界（CST 08:00）导致重复发送
            val dayKey = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(now))
            val sentKey = "battery_alert_sent_$dayKey"
            val flowKey = "battery_alert_flow_$dayKey"    // 窗口命中（流程启动）标记
            val resultKey = "battery_alert_logged_$dayKey" // 结果（触发/未触发/已发过）标记

            // 预警时间变更检测：本地/远程修改预警时间后，重置当日流程/结果标记，
            // 保证改动后到点必然重新检测并落日志（否则当日标记已置位会吞掉改动后的检测，
            // 造成「改时间后到点无日志」的假象）。不重置 sentKey，避免已发送时重复发邮件。
            val warningMinuteKey = "battery_alert_warning_minute_$dayKey"
            val lastMinute = SaveKeyValues.loadInt(warningMinuteKey, -1)
            if (lastMinute != warningMinute && SaveKeyValues.containsKey(warningMinuteKey)) {
                SaveKeyValues.removeKey(flowKey)
                SaveKeyValues.removeKey(resultKey)
                LogFileManager.action(
                    "电量智能预警：预警时间变更（${BatteryPredictor.formatWarningMinute(lastMinute)} → " +
                        "${BatteryPredictor.formatWarningMinute(warningMinute)}），重置当日检测标记"
                )
            }
            SaveKeyValues.saveInt(warningMinuteKey, warningMinute)

            if (SaveKeyValues.loadBoolean(sentKey, false)) {
                // 今日已成功发送过：跳过检测，但仍留一条记录，避免“到点无日志”盲区
                if (!SaveKeyValues.loadBoolean(resultKey, false)) {
                    LogFileManager.action("电量智能预警：今日已发送过，跳过检测")
                    SaveKeyValues.saveBoolean(resultKey, true)
                }
                return
            }

            // 窗口命中即记录“流程已启动”，证明检测代码在到点时确实执行过；
            // 若文件日志里连这一行都没有，说明 checkBatterySmartAlert 在 16:30 根本没被调用
            // （多半是 ForegroundRunningService 已被系统回收 / 精确闹钟未触发 / 开关被关）。
            if (!SaveKeyValues.loadBoolean(flowKey, false)) {
                LogFileManager.action("电量智能预警检测窗口命中（预警时间 ${BatteryPredictor.formatWarningMinute(warningMinute)}），开始检测")
                SaveKeyValues.saveBoolean(flowKey, true)
            }

            val result = BatteryPredictor.checkAlert(this)
            if (!result.shouldAlert) {
                // 到点未触发：记录原因（每天一条），便于排查“到点无邮件”
                if (!SaveKeyValues.loadBoolean(resultKey, false)) {
                    LogFileManager.action("电量智能预警未触发：${result.reason}")
                    SaveKeyValues.saveBoolean(resultKey, true)
                }
                return
            }

            val pred = result.prediction ?: return
            val battery = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val targetTimeText = BatteryPredictor.formatTime(pred.targetTimeMs)

            LogFileManager.action("电量智能预警已触发：预计 ${targetTimeText} 降至 ${result.threshold}%（当前 ${battery}%），在 ${BatteryPredictor.formatWarningMinute(result.warningMinute)} 前提醒")
            MessageDispatcher.sendMessage(
                "电量智能预警",
                StatusReporter.buildBatterySmartAlertContentHtml(
                    battery,
                    targetTimeText,
                    result.warningMinute,
                    result.threshold,
                    pred,
                    result.detectStartMinute,
                    result.detectEndMinute
                ),
                force = true,
                appendMeta = false
            )
            MqttAgentService.publishAlert(
                org.json.JSONObject().apply {
                    put("type", "battery_smart_alert")
                    put("battery", battery)
                    put("predictedTime", targetTimeText)
                    put("warningMinute", result.warningMinute)
                }.toString()
            )
            BatteryPredictor.markAlertSent(this)
            SaveKeyValues.saveBoolean(resultKey, true)
        } catch (e: Exception) {
            LogFileManager.error("电量智能预警检查失败：${e.message}")
            Log.e(javaClass.simpleName, "电量智能预警检查失败", e)
        }
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
     * 开机自动调度：本进程只尝试一次。
     * 开关开启 + 任务非空 + 未在跑 → startTask。
     * 不仅依赖 BOOT_SETUP（粘性重启可能丢 action），进程内首次 onStartCommand 也会兜底。
     */
    private fun maybeTryBootAutoSchedule(reason: String) {
        val enabled = SaveKeyValues.loadBoolean(Constant.BOOT_AUTO_SCHEDULE_KEY, false)
        Log.i(javaClass.simpleName, "maybeTryBootAutoSchedule reason=$reason enabled=$enabled tried=$bootAutoScheduleTried running=${TaskScheduler.isRunning()}")
        if (!enabled) return
        if (bootAutoScheduleTried) return
        if (KeepAliveReceiver.isPaused()) return
        if (TaskScheduler.isRunning()) {
            bootAutoScheduleTried = true
            return
        }
        bootAutoScheduleTried = true
        KeepAliveReceiver.ensureFloatingWindow(this)
        serviceScope.launch {
            val tasks = runCatching {
                withContext(Dispatchers.IO) {
                    com.pengxh.daily.app.sqlite.DatabaseWrapper.loadAllTask()
                }
            }.getOrElse { e ->
                // 读库失败不永久占坑，允许后续 BOOT_SETUP / 打开 App 再试
                bootAutoScheduleTried = false
                LogFileManager.error("开机自动调度读任务失败: ${e.message}")
                emptyList()
            }
            if (tasks.isEmpty()) {
                // 真正空列表才占坑；若是读失败上面已复位
                LogFileManager.action("开机自动调度已开启，但任务列表为空，跳过（$reason）")
                return@launch
            }
            delay(800)
            if (!TaskScheduler.isRunning() && !KeepAliveReceiver.isPaused()) {
                LogFileManager.action("开机自动调度：任务 ${tasks.size} 个，启动调度（$reason）")
                TaskScheduler.startTask()
                if (!TaskScheduler.isRunning()) {
                    bootAutoScheduleTried = false
                    LogFileManager.error("开机自动调度 startTask 后仍未运行（scope 可能未就绪）")
                }
            }
        }
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

        // 任务重置：调度未跑 → 补启；调度在跑（等待重置期被冻结）→ 发信号立即重排。
        // 旧逻辑在 isRunning()=true 时直接跳过，配合协程 delay 被冻结，重置永远无法推进（08-15/16 漏执行）
        if (SaveKeyValues.loadBoolean(Constant.TASK_AUTO_RECYCLE_KEY, true)) {
            if (TaskScheduler.isRunning()) {
                TaskScheduler.notifyResetTimeReached()
            } else {
                TaskScheduler.startTask()
            }
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
                LogFileManager.error("重置点清理临时诊断文件失败：${e.message}")
            }
        }
    }

    override fun onDestroy() {
        isRunning = false
        bootAutoScheduleTried = false
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
