package com.pengxh.daily.app.utils

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.pengxh.daily.app.BuildConfig
import com.pengxh.daily.app.DailyTaskApplication
import com.pengxh.daily.app.extensions.notificationEnable
import com.pengxh.daily.app.service.AutoProjectionAccessibilityService
import com.pengxh.daily.app.service.ForegroundRunningService
import com.pengxh.daily.app.service.MqttKeepAliveStrategy
import com.pengxh.daily.app.service.NotificationMonitorService
import com.pengxh.daily.app.utils.ProjectionSession
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.sqlite.bean.DailyTaskBean
import com.pengxh.daily.app.utils.ConfigStore
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.EmailSecureConfig
import com.pengxh.kt.lite.utils.SaveKeyValues
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * 远程控制快照：把被控端「设备信息 / 运行概览 / 打卡日历 / 可改设置 / 系统权限状态 / 任务时间点 / 历史记录」
 * 汇总为一份 JSON，由 MqttAgentService 经 dt/{id}/resp 返回给控制端渲染。
 *
 * 设计要点：
 * - 全程纯 JSON（StringValue 包裹），避免引入新的可序列化模型，R8 下零风险。
 * - 可写设置（writable=true）控制端会发 U 指令修改；系统权限类（writable=false）仅展示状态，
 *   因为无障碍/截屏是系统授权，MQTT 只能“请求”，不能程序化开关。
 */
object RemoteSnapshot {

    private val dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val timeDtf = DateTimeFormatter.ofPattern("HH:mm:ss")

    /** A3：全量快照内存缓存（TTL 30s），避免控制端 15s 轮询/重连引导时反复全量查 DB（15天日历+任务+历史） */
    @Volatile private var cacheJson: String? = null
    @Volatile private var cacheTs: Long = 0L
    private const val CACHE_TTL_MS = 30_000L

    /** 全部区块（用于全量快照） */
    val ALL_SECTIONS: Set<String> = setOf("device", "runtime", "calendar", "settings", "statuses", "tasks", "history")

    /** A3：本地数据变更后失效缓存，下次构建用最新数据（设置/任务经 RuntimeStateApplier / applyTaskChange 改动后调用） */
    fun invalidateCache() {
        cacheJson = null
    }

    /** 全量快照：带 TTL 缓存构建所有区块（锁内只做非挂起读写，避免 suspend 落在临界区） */
    suspend fun buildJson(context: Context): String {
        val now = System.currentTimeMillis()
        var hit: String? = null
        synchronized(this) {
            if (cacheJson != null && now - cacheTs < CACHE_TTL_MS) hit = cacheJson
        }
        val cached = hit
        if (cached != null) return cached
        val json = buildDelta(context, ALL_SECTIONS)
        synchronized(this) {
            cacheJson = json
            cacheTs = System.currentTimeMillis()
        }
        return json
    }

    /**
     * 增量快照：仅构建 sections 指定的区块，其余区块不出现在 JSON 中。
     * 控制端收到后按 key 覆盖合并，从而「只把变化的部分」推送，避免每次传输完整快照。
     */
    suspend fun buildDelta(context: Context, sections: Set<String>): String = withContext(Dispatchers.IO) {
        val root = JsonObject()
        if ("device" in sections) root.add("device", buildDevice(context))
        if ("runtime" in sections) root.add("runtime", buildRuntime(context))
        if ("calendar" in sections) root.add("calendar", buildCalendar())
        if ("settings" in sections) root.add("settings", buildSettings())
        if ("statuses" in sections) root.add("statuses", buildStatuses(context))
        if ("tasks" in sections) root.add("tasks", buildTasks())
        if ("history" in sections) root.add("history", buildHistory())
        root.toString()
    }

    // ===================== 设备信息 =====================
    private fun buildDevice(context: Context): JsonObject {
        val o = JsonObject()
        o.addProperty("protoVer", com.yample.mqttprotocol.Protocol.PROTO_VER)
        o.addProperty("deviceId", SaveKeyValues.loadString(Constant.DEVICE_ID_KEY, "default"))
        o.addProperty("model", Build.MODEL)
        o.addProperty("brand", Build.BRAND)
        o.addProperty("manufacturer", Build.MANUFACTURER)
        o.addProperty("androidVersion", Build.VERSION.RELEASE)
        o.addProperty("sdk", Build.VERSION.SDK_INT)
        o.addProperty("appVersion", BuildConfig.VERSION_NAME)
        val dm = context.resources.displayMetrics
        o.addProperty("screen", "${dm.widthPixels}x${dm.heightPixels}")
        o.addProperty("installId", Constant.getInstallId())
        return o
    }

    // ===================== 运行概览 =====================
    private suspend fun buildRuntime(context: Context): JsonObject {
        val o = JsonObject()
        val bm = readBattery(context)
        o.addProperty("battery", bm.capacity)
        o.addProperty("charging", bm.charging)
        o.addProperty("temperature", bm.temperature)
        o.addProperty("foregroundRunning", ForegroundRunningService.isRunning)
        o.addProperty("schedulerRunning", TaskScheduler.isRunning())
        o.addProperty("schedulerDesc", TaskScheduler.describeRunningState())
        // 息屏保活三级自适应：当前级别 + 最近切换时间 + 切换方向（控制端在设备页息屏模式下展示）
        o.addProperty("keepaliveLevel", keepaliveLevelText())
        val changedAt = MqttKeepAliveStrategy.lastChangedAt()
        o.addProperty(
            "keepaliveLevelChangedAt",
            if (changedAt > 0L) dtf.format(java.time.Instant.ofEpochMilli(changedAt).atZone(ZoneId.systemDefault()).toLocalDateTime())
            else "—"
        )
        o.addProperty("keepaliveLevelChangedDesc", MqttKeepAliveStrategy.lastChangedDesc())
        // 掉线统计（所有保活模式统一计数，跨天重置）：当日掉线次数 + 最近掉线时间
        o.addProperty("mqttDisconnectCount", MqttKeepAliveStrategy.disconnectCount())
        val lastDiscAt = MqttKeepAliveStrategy.lastDisconnectAt()
        o.addProperty(
            "mqttLastDisconnectAt",
            if (lastDiscAt > 0L) dtf.format(java.time.Instant.ofEpochMilli(lastDiscAt).atZone(ZoneId.systemDefault()).toLocalDateTime())
            else "—"
        )
        o.addProperty("powerSaveMode", AppRuntimeConfig.isPowerSaveMode())
        o.addProperty("forcePseudoMask", AppRuntimeConfig.isForcePseudoMask())
        o.addProperty("wifi", wifiStatus(context))
        o.addProperty("bluetooth", bluetoothStatus(context))
        o.addProperty("screenState", screenStateText(context))
        o.addProperty("currentTime", dtf.format(LocalDateTime.now()))
        o.addProperty("nextReset", nextResetDesc())
        o.addProperty("nextResetSeconds", TaskScheduler.secondsUntilNextReset())
        o.addProperty("serviceRunningMinutes", serviceRunningMinutes())
        o.addProperty("appRunningMinutes", appRunningMinutes())
        // B5：电池曲线序列（读本地 BatteryHistory CSV），供控制端绘制 sparkline
        try {
            val series = BatteryHistory.recentSeries(context, 12, 24)
            if (series.isNotEmpty()) {
                val arr = JsonArray()
                series.forEach { (ts, level, charging) ->
                    val p = JsonObject()
                    p.addProperty("ts", ts)
                    p.addProperty("level", level)
                    p.addProperty("charging", charging)
                    arr.add(p)
                }
                o.add("batterySeries", arr)
            }
        } catch (_: Exception) { }
        // B5：电量智能预测（复用被控端 BatteryPredictor，控制端直接展示避免算法不一致导致偏差）
        // 仍高于阈值 → 预测降至阈值；已低于阈值 → 改预测耗尽（0%），避免「0.0 小时后降至阈值」
        try {
            val threshold = SaveKeyValues.loadInt(
                Constant.LOW_BATTERY_THRESHOLD_KEY, Constant.DEFAULT_LOW_BATTERY_THRESHOLD
            ).coerceIn(10, 80)
            val level = bm.capacity
            val predToThreshold = BatteryPredictor.predict(context, threshold)
            when {
                predToThreshold != null && predToThreshold.isCharging -> {
                    o.addProperty("batteryPredictHas", true)
                    o.addProperty("batteryPredictCharging", true)
                    o.addProperty("batteryPredictExhaust", false)
                    o.addProperty("batteryPredictThreshold", threshold)
                }
                predToThreshold != null && !predToThreshold.isCharging && predToThreshold.targetTimeMs > 0 -> {
                    o.addProperty("batteryPredictHas", true)
                    o.addProperty("batteryPredictCharging", false)
                    o.addProperty("batteryPredictExhaust", false)
                    o.addProperty("batteryPredictRate", String.format("%.1f", predToThreshold.ratePerHour))
                    o.addProperty("batteryPredictTime", BatteryPredictor.formatTime(predToThreshold.targetTimeMs))
                    o.addProperty("batteryPredictHours",
                        String.format("%.1f", predToThreshold.minutesToTarget / 60.0))
                    o.addProperty("batteryPredictThreshold", threshold)
                }
                level in 1..threshold -> {
                    val predExhaust = BatteryPredictor.predict(context, 0)
                    if (predExhaust != null && !predExhaust.isCharging && predExhaust.ratePerHour > 0f) {
                        o.addProperty("batteryPredictHas", true)
                        o.addProperty("batteryPredictCharging", false)
                        o.addProperty("batteryPredictExhaust", true)
                        o.addProperty("batteryPredictRate", String.format("%.1f", predExhaust.ratePerHour))
                        o.addProperty("batteryPredictTime", BatteryPredictor.formatTime(predExhaust.targetTimeMs))
                        o.addProperty("batteryPredictHours",
                            String.format("%.1f", predExhaust.minutesToTarget / 60.0))
                        o.addProperty("batteryPredictThreshold", threshold)
                    }
                }
            }
        } catch (_: Exception) { }
        try {
            val plans = TaskScheduler.loadTodayTaskPlans(usePersisted = true)
            val now = System.currentTimeMillis()
            val next = plans.filter { it.actualTimeMillis > now }
                .minByOrNull { it.actualTimeMillis }
            o.addProperty("nextPunch", next?.actualTime ?: (if (TaskScheduler.isRunning()) "今日已执行完" else "无"))
            val cal = buildCalendar()
            o.addProperty("recentPunch", cal["recentPunch"].asString)
        } catch (_: Exception) {
            o.addProperty("nextPunch", "未知")
            o.addProperty("recentPunch", "—")
        }
        return o
    }

    private fun nextResetDesc(): String {
        val hour = SaveKeyValues.loadInt(Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR)
        return String.format("%02d:00", hour)
    }

    /** 保活级别可读文本：ALARM 闹钟心跳 / CPU 息屏持锁兜底 */
    private fun keepaliveLevelText(): String = when (MqttKeepAliveStrategy.current()) {
        MqttKeepAliveStrategy.Level.ALARM -> "ALARM 闹钟"
        MqttKeepAliveStrategy.Level.CPU -> "CPU 保活"
    }

    /** 前台保活服务已运行分钟数（服务本次启动起计时，重启后重新计时） */
    private fun serviceRunningMinutes(): Long {
        val start = ForegroundRunningService.serviceStartAtMs
        return if (start > 0L) TimeUnit.MILLISECONDS.toMinutes(System.currentTimeMillis() - start) else -1L
    }

    /** App 进程已运行分钟数（进程本次启动起计时，被杀重启后重新计时） */
    private fun appRunningMinutes(): Long {
        val start = DailyTaskApplication.processStartAtMs
        return if (start > 0L) TimeUnit.MILLISECONDS.toMinutes(System.currentTimeMillis() - start) else -1L
    }

    // ===================== 打卡日历汇总 =====================
    private suspend fun buildCalendar(): JsonObject {
        val o = JsonObject()
        val daysArr = JsonArray()
        return try {
            val today = LocalDate.now()
            val start = today.minusDays(14)
            val windowEnd = today.plusDays(14) // 与状态查询邮件一致：近两周（含未来两周）
            val allTasks = DatabaseWrapper.loadAllTask()
            val result = DatabaseWrapper.loadPunchResults(start, windowEnd.plusDays(1))
            val successSet = result.successDates.filter { !it.isAfter(today) }.toSet()
            val timeoutSet = result.timeoutDates.filter { !it.isAfter(today) }.toSet()
            val scheduledSet = mutableSetOf<LocalDate>()
            var d = start
            while (!d.isAfter(today)) {
                if (TaskScheduler.isPunchScheduled(d, allTasks)) scheduledSet.add(d)
                d = d.plusDays(1)
            }
            val punched = successSet.size
            val scheduled = scheduledSet.size
            val missed = (scheduled - successSet.size).coerceAtLeast(0)
            // 「最近打卡」按日维度：成功 OR 超时都算打过卡；与 StatusReporter.recentPunchText 的成功∪超时口径对齐，
            // 否则今天只有超时的情况下成功集合为空，会误回 "—"（见 feedback 2026-08-18 总览页 vs 日历页不一致）
            val punchedSet = successSet + timeoutSet
            o.addProperty("punched", punched)
            o.addProperty("scheduled", scheduled)
            o.addProperty("missed", missed)
            o.addProperty("recentPunch", recentPunchText(today, punchedSet))
            o.addProperty("today", todayStatus(today, allTasks, successSet, timeoutSet))

            // 逐日状态（近两周 + 未来两周，共 29 天，与状态查询邮件窗口一致；
            // 未来工作日标 scheduled、未来休息日标 rest，控制端据此渲染满周日历）
            var day = start
            while (!day.isAfter(windowEnd)) {
                val status = when {
                    day in successSet -> "success"
                    day in timeoutSet -> "timeout"
                    day == today && day in scheduledSet -> "pending"
                    day in scheduledSet && day.isBefore(today) -> "missed"
                    TaskScheduler.isPunchScheduled(day, allTasks) -> "scheduled"
                    allTasks.isEmpty() -> "none"
                    else -> "rest"
                }
                val dayObj = JsonObject()
                dayObj.addProperty("date", day.toString())
                dayObj.addProperty("weekday", day.dayOfWeek.value) // 1=周一
                dayObj.addProperty("status", status)
                dayObj.addProperty("label", day.dayOfMonth.toString())
                daysArr.add(dayObj)
                day = day.plusDays(1)
            }
            o.add("days", daysArr)
            o
        } catch (e: Exception) {
            o.addProperty("punched", 0)
            o.addProperty("scheduled", 0)
            o.addProperty("missed", 0)
            o.addProperty("recentPunch", "—")
            o.addProperty("today", "—")
            o.add("days", daysArr)
            o
        }
    }

    private fun todayStatus(
        today: LocalDate,
        allTasks: List<DailyTaskBean>,
        punchedSet: Set<LocalDate>,
        timeoutSet: Set<LocalDate>
    ): String {
        return when {
            today in punchedSet -> "今日打卡成功"
            today in timeoutSet -> "今日打卡超时"
            TaskScheduler.isPunchScheduled(today, allTasks) && TaskScheduler.isRunning() -> "计划打卡（调度运行中）"
            TaskScheduler.isPunchScheduled(today, allTasks) -> "计划打卡（调度未运行）"
            allTasks.isEmpty() -> "未配置任务"
            else -> "今日休息（跳过）"
        }
    }

    /**
     * 最近一次打卡（成功或超时）：若发生在今天，拼接通知里的完整时间（含时分秒）；
     * 否则只显示日期（历史打卡记录按日展示）。
     * [punchedSet] = 成功集 ∪ 超时集，确保今天只有超时时也能查到具体时间，与日历页 history 口径一致。
     */
    private suspend fun recentPunchText(today: LocalDate, punchedSet: Set<LocalDate>): String {
        val latest = punchedSet.maxOrNull() ?: return "—"
        // 非今天：按日记录即可（仅显示日期，无时间）
        if (latest != today) return latest.toString()
        // 今天：显示具体时间点（成功或超时均可，loadLatestPunchTime 已识别两种类型）
        return try {
            DatabaseWrapper.loadLatestPunchTime(today) ?: latest.toString()
        } catch (_: Exception) {
            latest.toString()
        }
    }

    // ===================== 可写设置 =====================
    private fun buildSettings(): JsonArray {
        val arr = JsonArray()
        fun add(key: String, label: String, type: String, value: Any, min: Int? = null, max: Int? = null, step: Int? = null, options: JsonArray? = null, writable: Boolean = true) {
            val o = JsonObject()
            o.addProperty("key", key)
            o.addProperty("label", label)
            o.addProperty("type", type)
            when (value) {
                is Boolean -> o.addProperty("value", value)
                is Int -> o.addProperty("value", value)
                else -> o.addProperty("value", value.toString())
            }
            o.addProperty("writable", writable)
            if (min != null) o.addProperty("min", min)
            if (max != null) o.addProperty("max", max)
            if (step != null) o.addProperty("step", step)
            if (options != null) o.add("options", options)
            arr.add(o)
        }
        add("ps", "节能模式", "bool", AppRuntimeConfig.isPowerSaveMode())
        add("pm", "伪熄屏", "bool", AppRuntimeConfig.isForcePseudoMask())
        // 屏幕模式：0 伪息屏 / 1 息屏 / 2 常亮。仅伪息屏关闭时可改（writable 随 forcePseudoMask 联动）
        val smOptions = JsonArray().apply {
            add(Constant.SCREEN_MODE_PSEUDO); add(Constant.SCREEN_MODE_OFF); add(Constant.SCREEN_MODE_KEEP_ON)
        }
        add("sm", "屏幕模式", "int", AppRuntimeConfig.getScreenMode().coerceIn(
            Constant.SCREEN_MODE_PSEUDO, Constant.SCREEN_MODE_KEEP_ON),
            min = Constant.SCREEN_MODE_PSEUDO, max = Constant.SCREEN_MODE_KEEP_ON, step = 1, options = smOptions,
            writable = !AppRuntimeConfig.isForcePseudoMask())
        add("nc", "隐藏息屏时钟", "bool", SaveKeyValues.loadBoolean(Constant.PSEUDO_MASK_NO_CLOCK_KEY, false))
        add("nt", "通知转发", "bool", SaveKeyValues.loadBoolean(Constant.NOTIFICATION_TRANSFER_KEY, false))
        add("fd", "静默通知", "bool", SaveKeyValues.loadBoolean(Constant.FEEDBACK_NOTIFY_DISABLED_KEY, false))
        add("sh", "假日跳过", "bool", SaveKeyValues.loadBoolean(Constant.SKIP_HOLIDAY_KEY, true))
        // 自定义工作日：控制端多选星期下发（cw），快照回传序列化串（如 "1,2,3,4,5"）
        add("cw", "自定义工作日", "string", CustomWorkdayManager.serializeWorkdays(CustomWorkdayManager.loadWorkdays()))
        // 更新节假日：触发型字段，快照仅回传当前是否同步中
        add("uh", "更新节假日", "bool", ChinaHolidayManager.isSyncing())
        add("ar", "任务每日自动循环", "bool", SaveKeyValues.loadBoolean(Constant.TASK_AUTO_RECYCLE_KEY, true))
        add("rt", "随机时间", "bool", SaveKeyValues.loadBoolean(Constant.RANDOM_TIME_KEY, true))
        add("ga", "手势识别", "bool", SaveKeyValues.loadBoolean(Constant.GESTURE_DETECTOR_KEY, true))
        add("bh", "返回桌面", "bool", SaveKeyValues.loadBoolean(Constant.BACK_TO_HOME_KEY, Constant.BACK_TO_HOME_DEFAULT))
        add("bo", "开机自动调度", "bool", SaveKeyValues.loadBoolean(Constant.BOOT_AUTO_SCHEDULE_KEY, false))
        add("dp", "桌面宠物", "bool", AppRuntimeConfig.isDesktopPetEnabled())
        add("lg", "运行日志", "bool", SaveKeyValues.loadBoolean(Constant.LOG_ENABLED_KEY, true))
        // 离散档位与控制端伪息屏延迟滑块共用，确保两端可选延迟值完全一致
        // （非线性：时间越大档位间隔越大，缩短滑块行程），与被控端 showPseudoMaskDelayDialog 同源
        val tmOptions = intArrayOf(
            10, 15, 20, 25, 30, 35, 40, 45, 50,
            60, 70, 80, 90, 100, 120, 150, 180,
            240, 300, 420, 600, 900, 1200, 1800, 2700, 3600
        )
        val tmOptionsJson = JsonArray().apply { tmOptions.forEach { add(it) } }
        add("tm", "伪熄屏延迟（秒）", "int", SaveKeyValues.loadInt(Constant.IDLE_PSEUDO_MASK_TIMEOUT_KEY, 60).coerceIn(10, 3600),
            min = 10, max = 3600, step = 10, options = tmOptionsJson)
        add("rh", "每日重置", "int", SaveKeyValues.loadInt(Constant.RESET_TIME_KEY, 0).coerceIn(0, 23),
            min = 0, max = 23, step = 1)
        add("tr", "随机范围", "int", SaveKeyValues.loadInt(Constant.TIME_RANGE_KEY, 5).coerceIn(0, 60),
            min = 0, max = 60, step = 1)
        add("ot", "超时时间", "int", SaveKeyValues.loadInt(Constant.STAY_OVERTIME_KEY, 30).coerceIn(0, 120),
            min = 0, max = 120, step = 1)
        add("lb", "低电量阈值（%）", "int", SaveKeyValues.loadInt(Constant.LOW_BATTERY_THRESHOLD_KEY, Constant.DEFAULT_LOW_BATTERY_THRESHOLD).coerceIn(10, 80),
            min = 10, max = 80, step = 1)
        add("ba", "电量智能提醒", "bool", SaveKeyValues.loadBoolean(Constant.BATTERY_SMART_ALERT_ENABLED_KEY, false))
        // type=time：控制端用时间选择器；value 仍为当日分钟数 0~1439
        add("bw", "智能提醒时间", "time", SaveKeyValues.loadInt(Constant.BATTERY_WARNING_HOUR_KEY, 20 * 60).coerceIn(0, 1439),
            min = 0, max = 1439, step = 1)
        add("bs", "低电量提醒次数", "int", SaveKeyValues.loadInt(Constant.BATTERY_ALERT_MAX_STAGES_KEY, 3).coerceIn(0, 3),
            min = 0, max = 3, step = 1)
        add("br", "检测开始时间", "int", SaveKeyValues.loadInt(Constant.BATTERY_ALERT_DETECTION_START_KEY, 20).coerceIn(0, 23),
            min = 0, max = 23, step = 1)
        add("bd", "检测时长（小时）", "int", SaveKeyValues.loadInt(Constant.BATTERY_ALERT_DETECTION_DURATION_KEY, Constant.DEFAULT_BATTERY_ALERT_DURATION).coerceIn(1, 24),
            min = 1, max = 24, step = 1)
        // 消息渠道 + 远程控制开关（镜像到控制端，允许控制端查看与修改）
        add("re", "远程控制", "bool", SaveKeyValues.loadBoolean(Constant.MQTT_ENABLED_KEY, false))
        add("mc", "消息渠道", "int", SaveKeyValues.loadInt(Constant.MSG_CHANNEL_KEY, 0).coerceIn(0, 1))
        add("mt", "消息标题", "string", SaveKeyValues.loadString(Constant.MESSAGE_TITLE_KEY, "打卡结果通知"))
        val emailObj = ConfigStore.get().load(Constant.EMAIL_CONFIG_KEY)
        add("em", "发件箱", "string", if (emailObj.has("outbox")) emailObj.get("outbox").asString else "")
        add("ei", "收件箱", "string", if (emailObj.has("inbox")) emailObj.get("inbox").asString else "")
        // 企业微信 Key / 邮箱授权码属敏感字段：快照只回传「是否已设置」掩码，
        // 明文永不出网，仅在控制端「配置消息渠道」时由用户重新录入并下发
        add("wk", "企业微信Key", "string",
            if (SaveKeyValues.loadString(Constant.WX_WEB_HOOK_KEY, "").isBlank()) "" else "•".repeat(8))
        add("ea", "邮箱授权码", "string",
            if (EmailSecureConfig.loadAuthCode().isBlank()) "" else "•".repeat(8))
        // Shizuku 高级功能（feat_shiziku）：只读镜像展示；配置修改走专用 FIELD_SHIZUKU_CONFIG 通道（不含密码明文）
        val sz = com.pengxh.daily.app.shizuku.ShizukuConfigStore.summaryJson()
        add("sz_enabled", "Shizuku 高级功能", "bool", sz.get("enabled").asBoolean, writable = false)
        add("sz_method", "登录方式", "string",
            if (sz.get("method").asString == "VERIFY_CODE") "验证码登录" else "密码登录", writable = false)
        add("sz_steps", "登录步骤", "string", "${sz.get("stepsCount").asInt} 步", writable = false)
        add("sz_auth", "身份验证步骤", "string", "${sz.get("authStepsCount").asInt} 步", writable = false)
        return arr
    }

    // ===================== 只读系统权限状态 =====================
    private fun buildStatuses(context: Context): JsonArray {
        val arr = JsonArray()
        fun add(key: String, label: String, value: String) {
            val o = JsonObject()
            o.addProperty("key", key)
            o.addProperty("label", label)
            o.addProperty("value", value)
            arr.add(o)
        }
        add("overlay", "悬浮窗权限", if (Settings.canDrawOverlays(context)) "已获取" else "未获取")
        add(
            "notify", "通知监听",
            if (!context.notificationEnable()) "未授权"
            else if (NotificationMonitorService.isListenerConnected()) "正常"
            else "已授权但断开"
        )
        add("capture", "截图权限", if (ProjectionSession.isStateActive()) "已开启" else "未开启")
        add(
            "accessibility", "无障碍权限",
            if (AutoProjectionAccessibilityService.isEnabled(context)) "已开启" else "未开启"
        )
        val src = SaveKeyValues.loadInt(Constant.RESULT_SOURCE_KEY, Constant.DEFAULT_INDEX)
        add("resultSource", "结果来源", if (src == 1) "截图" else if (src == 2) "无障碍" else "通知")
        return arr
    }

    // ===================== 任务时间点 =====================
    private suspend fun buildTasks(): JsonArray {
        val arr = JsonArray()
        try {
            val plans = TaskScheduler.loadTodayTaskPlans(usePersisted = true)
            val notices = DatabaseWrapper.loadCurrentDayNotice()
            val now = System.currentTimeMillis()
            plans.forEach { plan ->
                val o = JsonObject()
                o.addProperty("id", plan.task.id)
                o.addProperty("time", plan.task.time)
                o.addProperty("name", plan.task.name ?: "")
                o.addProperty("actualTime", plan.actualTime)
                o.addProperty("status", taskStatus(plan, now, notices))
                o.addProperty("statusLabel", taskStatusLabel(plan, now, notices))
                arr.add(o)
            }
        } catch (_: Exception) {
        }
        return arr
    }

    private fun taskStatus(plan: TaskScheduler.TaskPlanItem, now: Long, notices: List<com.pengxh.daily.app.sqlite.bean.NotificationBean>): String {
        if (TaskScheduler.isTodaySkipped()) return "skip"
        return when {
            hasNotice(plan.actualTime, notices, "考勤打卡成功") -> "success"
            hasNotice(plan.actualTime, notices, "考勤打卡超时") -> "timeout"
            plan.actualTimeMillis > now -> "pending"
            else -> "expired"
        }
    }

    private fun taskStatusLabel(plan: TaskScheduler.TaskPlanItem, now: Long, notices: List<com.pengxh.daily.app.sqlite.bean.NotificationBean>): String {
        if (TaskScheduler.isTodaySkipped()) return "已跳过"
        return when {
            hasNotice(plan.actualTime, notices, "考勤打卡成功") -> "已打卡"
            hasNotice(plan.actualTime, notices, "考勤打卡超时") -> "超时未确认"
            plan.actualTimeMillis > now -> "待执行"
            else -> "已过期"
        }
    }

    private fun hasNotice(actualTime: String, notices: List<com.pengxh.daily.app.sqlite.bean.NotificationBean>, keyword: String): Boolean {
        // actualTime 形如 "09:05:23"，通知 postTime 形如 "2026-08-01 09:05:45"
        return notices.any { notice ->
            notice.noticeMessage.contains(keyword) && notice.postTime.contains(" $actualTime")
        }
    }

    // ===================== 历史记录 =====================
    private suspend fun buildHistory(): JsonArray {
        val arr = JsonArray()
        try {
            val start = LocalDate.now().minusDays(14)
            val result = DatabaseWrapper.loadPunchResults(start, LocalDate.now().plusDays(1))
            val notices = DatabaseWrapper.loadCurrentDayNotice()
            // 今日通知明细已覆盖的日期：跳过日期聚合项，避免「今日打卡」重复出现两条
            val todayDetailed = notices.filter { it.noticeMessage.contains("考勤打卡") }
                .map { it.postTime.take(10) }.toSet()
            val all = mutableListOf<Pair<String, String>>()
            result.successDates.filter { it.toString() !in todayDetailed }
                .forEach { all.add(it.toString() to "成功") }
            result.timeoutDates.filter { it.toString() !in todayDetailed }
                .forEach { all.add(it.toString() to "超时") }
            // 补充今日通知详情（含时间，保留同一天多次打卡）
            notices.filter { it.noticeMessage.contains("考勤打卡") }
                .forEach { notice ->
                    val date = notice.postTime.take(10)
                    val time = notice.postTime.drop(11).take(8)
                    val resultText = when {
                        notice.noticeMessage.contains("成功") -> "成功"
                        notice.noticeMessage.contains("超时") -> "超时"
                        else -> "记录"
                    }
                    all.add("$date $time" to resultText)
                }
            all.sortedByDescending { it.first }
                .take(10)
                .forEach { (time, resultText) ->
                    val o = JsonObject()
                    o.addProperty("time", time)
                    o.addProperty("result", resultText)
                    arr.add(o)
                }
        } catch (_: Exception) {
        }
        return arr
    }

    // ===================== 设备状态辅助 =====================
    private data class BatteryInfo(val capacity: Int, val charging: String, val temperature: String)

    private fun readBattery(context: Context): BatteryInfo {
        return try {
            val mgr = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val cap = mgr?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            val charging = when (status) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "充电中"
                BatteryManager.BATTERY_STATUS_FULL -> "已充满"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "放电中"
                else -> "未充电"
            }
            val temperature = if (temp > 0) String.format(java.util.Locale.CHINA, "%.1f℃", temp / 10.0) else "未知"
            BatteryInfo(cap, charging, temperature)
        } catch (_: Exception) {
            BatteryInfo(-1, "未知", "未知")
        }
    }

    private fun wifiStatus(context: Context): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            if (caps != null && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) "已连接" else "未连接"
        } catch (_: Exception) {
            "未知"
        }
    }

    @Suppress("DEPRECATION")
    private fun bluetoothStatus(context: Context): String {
        return try {
            val adapter = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
            } else {
                android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            }
            if (adapter == null) "不支持" else if (adapter.isEnabled) "已开启" else "未开启"
        } catch (_: Exception) {
            "未知"
        }
    }

    /** 手机屏幕状态：伪息屏 > 锁屏 > 亮屏，供控制端设备页展示 */
    private fun screenStateText(context: Context): String {
        return try {
            when {
                MaskOverlayHelper.isShowing() -> "伪息屏"
                else -> {
                    val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                    val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
                    when {
                        pm?.isInteractive == false -> "锁屏（灭屏）"
                        km?.isKeyguardLocked == true -> "锁屏"
                        else -> "亮屏"
                    }
                }
            }
        } catch (_: Exception) {
            "未知"
        }
    }
}
