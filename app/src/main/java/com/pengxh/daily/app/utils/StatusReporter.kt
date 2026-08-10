package com.pengxh.daily.app.utils

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.app.KeyguardManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.pengxh.daily.app.BuildConfig
import com.pengxh.daily.app.DailyTaskApplication
import com.pengxh.daily.app.extensions.formatTime
import com.pengxh.daily.app.extensions.notificationEnable
import com.pengxh.daily.app.service.ForegroundRunningService
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.kt.lite.utils.SaveKeyValues
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek

import com.pengxh.daily.app.extensions.format
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.Date
import java.util.Locale

/**
 * 统一组装状态查询 / 任务通知正文。
 */
object StatusReporter {

    private val dateTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** 线程安全地格式化当前时间（SimpleDateFormat 非线程安全，并发 format 会偶发异常/错乱时间戳） */
    private fun Date.formatDateTime(): String =
        dateTimeFormat.format(LocalDateTime.ofInstant(this.toInstant(), ZoneId.systemDefault()))

    /** 远程指令清单（邮件反馈 / 指令页共用） */
    val remoteCommands: List<Pair<String, String>> = listOf(
        "DT#执行任务" to "启动当日任务调度",
        "DT#终止任务" to "仅停止当日任务调度",
        "DT#开启循环" to "每日重置时启动任务调度",
        "DT#关闭循环" to "关闭自动启动任务调度",
        "DT#息屏" to "开启伪息屏",
        "DT#亮屏" to "退出伪息屏",
        "DT#考勤记录" to "导出当天监听的打卡通知",
        "DT#打卡" to "触发一次临时打卡",
        "DT#状态查询" to "查询程序状态信息等",
        "DT#截屏" to "截取App 画面并回传",
        "DT#开启转移" to "开启通知转移（打卡应用通知转发到目标手机）",
        "DT#关闭转移" to "关闭通知转移",
        "DT#开启远程" to "重新开启本机远程控制服务（MQTT）",
        "DT#关闭远程" to "关闭本机远程控制服务（MQTT）"
    )

    /** 状态报告共性取数（纯文本版与 HTML 版共用），避免两份近乎镜像的取数逻辑分叉（P2-4） */
    private data class StatusReportData(
        val battery: Int,
        val channelType: Int,
        val autoRecycle: Boolean,
        val resetHour: Int,
        val plans: List<TaskScheduler.TaskPlanItem>,
        val now: Long,
        val pending: List<TaskScheduler.TaskPlanItem>,
        val cal: PunchCalendarData
    )

    private suspend fun gatherStatusData(context: Context): StatusReportData {
        val batteryManager = context.getSystemService(BatteryManager::class.java)
        val battery = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val channelType = SaveKeyValues.loadInt(Constant.MSG_CHANNEL_KEY, Constant.DEFAULT_INDEX)
        val autoRecycle = SaveKeyValues.loadBoolean(Constant.TASK_AUTO_RECYCLE_KEY, true)
        val resetHour = SaveKeyValues.loadInt(Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR)
        val plans = TaskScheduler.loadTodayTaskPlans(usePersisted = true)
        val now = System.currentTimeMillis()
        val pending = plans.filter { it.actualTimeMillis > now }
        val cal = computePunchCalendar(14, 14)
        return StatusReportData(battery, channelType, autoRecycle, resetHour, plans, now, pending, cal)
    }

    suspend fun buildStatusReport(context: Context, listenerConnected: Boolean): String {
        val d = gatherStatusData(context)
        val batterySnap = readBatterySnapshot(context)
        return buildString {
            appendLine("====================")
            appendLine("  状态查询通知")
            appendLine("====================")
            appendLine()
            appendLine("【运行状态】")
            appendLine("· 任务调度：${TaskScheduler.describeRunningState()}")
            appendLine(
                "· 每日循环：${
                    if (d.autoRecycle) {
                        "开启（每天 ${"%02d".format(d.resetHour)}:00 重置时自动启动）"
                    } else {
                        "关闭（重置时不自动启动）"
                    }
                }"
            )
            appendLine(
                "· 伪息屏增强：${
                    if (AppRuntimeConfig.isForcePseudoMask()) {
                        val sec = SaveKeyValues.loadInt(Constant.IDLE_PSEUDO_MASK_TIMEOUT_KEY, 60)
                        "开启（离开本软件超过 ${sec} 秒会主动盖黑屏）"
                    } else {
                        "关闭"
                    }
                }"
            )
            appendLine("· 省电模式：${if (AppRuntimeConfig.isPowerSaveMode()) "开启" else "关闭"}")
            appendLine(
                "· 跳过节假日：${
                    if (SaveKeyValues.loadBoolean(Constant.SKIP_HOLIDAY_KEY, true)) "开启" else "关闭"
                }"
            )
            appendLine("· 距下次重置：${TaskScheduler.secondsUntilNextReset().formatTime()}")
            appendLine("· 下次打卡：${nextPunchText(d.cal, d.plans, d.now, d.autoRecycle)}")
            appendLine("· 最近打卡：${recentPunchText(d.cal)}")
            appendLine()
            appendTaskPlanSection(d.plans, d.pending.size, d.now)
            appendLine()
            appendLine("【服务状态】")
            appendLine("· 前台服务：${if (ForegroundRunningService.isRunning) "运行中" else "未运行"}")
            appendLine("· 悬浮窗权限：${if (Settings.canDrawOverlays(context)) "已获取" else "未获取"}")
            appendLine(
                "· 通知监听：${
                    when {
                        !context.notificationEnable() -> "未授权"
                        listenerConnected -> "正常"
                        else -> "已授权但断开"
                    }
                }"
            )
            val resultSource = SaveKeyValues.loadInt(Constant.RESULT_SOURCE_KEY, Constant.DEFAULT_INDEX)
            val resultSourceText = when (resultSource) {
                0 -> "通知监听"
                1 -> "截屏反馈"
                2 -> {
                    val mode = if (SaveKeyValues.loadInt(Constant.ACCESSIBILITY_FEEDBACK_MODE_KEY, 0) == 0) "截屏" else "文本"
                    "无障碍-${mode}反馈"
                }
                else -> "未配置"
            }
            appendLine("· 结果来源：$resultSourceText")
            appendLine(
                "· 消息渠道：${
                    when (d.channelType) {
                        0 -> "QQ邮箱"
                        1 -> "企业微信"
                        else -> "未配置"
                    }
                }"
            )
            appendLine()
            appendLine("【近两周打卡日历】")
            val punchedTotal = (d.cal.punched + d.cal.timeoutDates).count { !it.isAfter(d.cal.today) }
            val scheduledPast = d.cal.scheduled.count { !it.isAfter(d.cal.today) }
            val missed = (scheduledPast - punchedTotal).coerceAtLeast(0)
            appendLine("· 已打卡 $punchedTotal 天 · 计划 $scheduledPast 天 · 未计划 $missed 天")
            appendLine("· 窗口内：节假日 ${d.cal.holidays.size} 天 · 休息日 ${d.cal.restDays.size} 天 · 调休补班 ${d.cal.makeupWorkdays.size} 天")
            if (d.cal.holidays.isNotEmpty()) {
                appendLine("· 节假日跳过：${d.cal.holidays.sorted().joinToString("、") { it.toString().substring(5) }}")
            }
            if (d.cal.makeupWorkdays.isNotEmpty()) {
                appendLine("· 调休补班：${d.cal.makeupWorkdays.sorted().joinToString("、") { it.toString().substring(5) }}")
            }
            val missedDates = d.cal.scheduled.filter { it.isBefore(d.cal.today) && it !in d.cal.punched && it !in d.cal.timeoutDates }.sorted()
            if (missedDates.isNotEmpty()) {
                appendLine("· 未计划：${missedDates.joinToString("、") { it.toString().substring(5) }}")
            }
            val futurePlanned = d.cal.scheduled.filter { !it.isBefore(d.cal.today) }.sorted().take(7)
            if (futurePlanned.isNotEmpty()) {
                appendLine("· 未来计划打卡：${futurePlanned.joinToString("、") { it.toString().substring(5) }}")
            }
            appendLine("· 今日：${buildTodayTextNote(d.cal)}")
            appendLine()
            appendLine("【设备信息】")
            appendLine("· 手机状态：${deviceScreenStateText(context)}")
            appendLine("· 当前时间：${Date().formatDateTime()}")
            appendLine("· 当前电量：${if (d.battery >= 0) "${d.battery}%" else "未知"}")
            appendLine("· 充电状态：${batterySnap.chargingStatus}")
            appendLine("· 电池电流：${batterySnap.currentText}")
            appendLine("· 电池健康：${batterySnap.healthText}")
            appendLine("· 过去1小时掉电：${BatteryHistory.drainOver(context, 1)}")
            appendLine("· 过去6小时掉电：${BatteryHistory.drainOver(context, 6)}")
            appendLine("· 过去12小时掉电：${BatteryHistory.drainOver(context, 12)}")
            appendLine("· WiFi 状态：${wifiStatusText(context)}")
            appendLine("· 蓝牙状态：${bluetoothStatusText(context)}")
            appendLine("· 手机温度：${batterySnap.temperatureText}")
            appendLine("· 系统版本：${androidVersionText()}")
            appendLine("· 版本号：${BuildConfig.VERSION_NAME}")
            appendLine()
            appendRemoteCommandSection()
        }
    }

    suspend fun buildTaskExecutingContent(
        index: Int,
        total: Int,
        plannedTime: String,
        actualTime: String
    ): String {
        val plans = TaskScheduler.loadTodayTaskPlans(usePersisted = true)
        val now = System.currentTimeMillis()
        val pending = plans.filter { it.actualTimeMillis > now }
        return buildString {
            appendLine("====================")
            appendLine("  任务执行通知")
            appendLine("====================")
            appendLine()
            appendLine("【当前任务】")
            appendLine("· 序号：$index / $total")
            appendLine("· 计划时间：$plannedTime")
            appendLine("· 实际时间：$actualTime")
            appendLine("· 省电模式：${if (AppRuntimeConfig.isPowerSaveMode()) "开启" else "关闭"}")
            appendLine()
            appendTaskPlanSection(plans, pending.size, now)
        }
    }

    suspend fun buildTaskCompletedContent(): String {
        val plans = TaskScheduler.loadTodayTaskPlans(usePersisted = true)
        val resetHour =
            SaveKeyValues.loadInt(Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR)
        val autoRecycle =
            SaveKeyValues.loadBoolean(Constant.TASK_AUTO_RECYCLE_KEY, true)
        return buildString {
            appendLine("====================")
            appendLine("  任务状态通知")
            appendLine("====================")
            appendLine()
            appendLine("【结果】")
            appendLine("· 今日任务已全部执行完毕")
            appendLine(
                "· 每日循环：${
                    if (autoRecycle) {
                        "开启（等待每天 ${"%02d".format(resetHour)}:00 自动启动）"
                    } else {
                        "关闭"
                    }
                }"
            )
            appendLine("· 距下次重置：${TaskScheduler.secondsUntilNextReset().formatTime()}")
            appendLine()
            appendTaskPlanSection(plans, pendingCount = 0, nowMillis = System.currentTimeMillis())
        }
    }

    fun buildSkipContent(): String {
        return buildString {
            appendLine("====================")
            appendLine("  任务跳过通知")
            appendLine("====================")
            appendLine()
            appendLine("· 当前为节假日/休息日，任务已自动跳过")
        }
    }

    fun buildLowBatteryContent(battery: Int, threshold: Int, stage: Int): String {
        return buildString {
            appendLine("====================")
            appendLine("  低电量提醒（第${stage}档）")
            appendLine("====================")
            appendLine()
            appendLine("· 当前电量：$battery%")
            appendLine("· 低电量阈值：$threshold%")
            appendLine("· 电量已低于第 $stage 档边界（阈值 - ${(stage - 1) * 10}%），请及时充电")
            appendLine("· 各档仅提醒一次（开始充电后重新计数）")
        }
    }

    private fun StringBuilder.appendTaskPlanSection(
        plans: List<TaskScheduler.TaskPlanItem>,
        pendingCount: Int,
        nowMillis: Long
    ) {
        appendLine("【今日任务】")
        if (plans.isEmpty()) {
            appendLine("· （未配置任务时间点）")
            return
        }
        appendLine("· 共 ${plans.size} 个，待执行 $pendingCount 个")
        plans.forEach { plan ->
            appendLine(
                "  ${plan.index}) 计划 ${plan.plannedTime} → 实际 ${plan.actualTime} 【${plan.statusLabel(nowMillis)}】"
            )
        }
    }

    private fun StringBuilder.appendRemoteCommandSection() {
        appendLine("【远程指令速查】")
        appendLine("· 指令需以 DT# 开头，经 QQ/微信等发送")
        appendLine()

        // 计算两列最大显示宽度（CJK 字符计 2）
        val cmdWidth = remoteCommands.maxOf { (cmd, _) -> cmd.displayWidth() }
        val descWidth = remoteCommands.maxOf { (_, desc) -> desc.displayWidth() }

        // 表头
        appendTableRow("指令", "说明", cmdWidth, descWidth)
        appendTableSeparator(cmdWidth, descWidth)

        // 数据行
        remoteCommands.forEach { (cmd, desc) ->
            appendTableRow(cmd, desc, cmdWidth, descWidth)
        }
    }

    /** 计算字符串显示宽度：ASCII 字符计 1，其余计 2 */
    private fun String.displayWidth(): Int {
        var w = 0
        for (ch in this) {
            w += if (ch.code <= 0x7F) 1 else 2
        }
        return w
    }

    private fun StringBuilder.padByDisplayWidth(text: String, targetWidth: Int): StringBuilder {
        append(text)
        val current = text.displayWidth()
        repeat(targetWidth - current) { append(' ') }
        return this
    }

    private fun StringBuilder.appendTableRow(
        cmd: String, desc: String, cmdWidth: Int, descWidth: Int
    ) {
        append("│ ")
        padByDisplayWidth(cmd, cmdWidth)
        append(" │ ")
        padByDisplayWidth(desc, descWidth)
        appendLine(" │")
    }

    private fun StringBuilder.appendTableSeparator(cmdWidth: Int, descWidth: Int) {
        append("├─")
        repeat(cmdWidth) { append('─') }
        append("─┼─")
        repeat(descWidth) { append('─') }
        appendLine("─┤")
    }

    // ======================== HTML 邮件版本 ========================

    /** 状态 Badge */
    private fun badge(text: String, type: String): String {
        val (bg, fg) = when (type) {
            "ok" -> "#f6ffed" to "#389e0d"
            "warn" -> "#fff7e6" to "#d46b08"
            "err" -> "#fff2f0" to "#cf1322"
            "info" -> "#e6f7ff" to "#096dd9"
            "skip" -> "#f0f0f0" to "#8c8c8c"
            else -> "#f5f5f5" to "#595959"
        }
        return "<span style=\"display:inline-block;padding:2px 10px;border-radius:12px;font-size:12px;background:$bg;color:$fg;font-weight:500;\">$text</span>"
    }

    /** 值行: 标签 | 值 */
    private fun row(label: String, value: String): String {
        return "<tr><td style=\"padding:4px 0;font-size:13px;color:#888;white-space:nowrap;vertical-align:top;\">$label</td><td style=\"padding:4px 0 4px 14px;font-size:13px;color:#333;word-break:break-all;\">$value</td></tr>"
    }

    /**
     * 统一邮件 HTML 外壳（HTML 报告与轻量级通知共用，消除 htmlShell/centerShell 重复，P3-6）。
     * compact=true 用于轻量级通知（居中图标卡片）：body padding 24px 20px 且底部圆角直接合并到 body 容器；
     * compact=false 用于完整报告：body padding 20px 24px 16px，并用一条底部圆角白条收口。
     */
    private fun pageShell(title: String, body: String, compact: Boolean): String {
        val ts = Date().formatDateTime()
        val bodyStyle = if (compact) {
            "background:#fff;padding:24px 20px;border-radius:0 0 12px 12px;"
        } else {
            "background:#fff;padding:20px 24px 16px;"
        }
        return buildString {
            append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1.0,maximum-scale=1.0\"></head>")
            append("<body style=\"margin:0;padding:16px;background:#f0f2f5;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','PingFang SC','Microsoft YaHei',sans-serif;\">")
            append("<div style=\"max-width:600px;margin:0 auto;\">")
            // Header
            append("<div style=\"background:linear-gradient(135deg,#4f6ef7,#6c5ce7);padding:22px 24px;border-radius:12px 12px 0 0;\">")
            append("<table cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\"><tr>")
            append("<td style=\"color:#fff;font-size:19px;font-weight:700;\">$title</td>")
            append("</tr><tr>")
            append("<td style=\"color:rgba(255,255,255,0.65);font-size:12px;padding-top:4px;\">$ts</td>")
            append("</tr></table></div>")
            // Body
            append("<div style=\"$bodyStyle\">")
            append(body)
            // Footer
            append("<div style=\"margin-top:24px;padding-top:12px;border-top:1px solid #f0f0f0;font-size:11px;color:#bbb;text-align:center;\">DailyTask v${BuildConfig.VERSION_NAME} · 自动发送</div>")
            append("</div>")
            if (!compact) {
                // Card bottom radius
                append("<div style=\"height:8px;background:#fff;border-radius:0 0 12px 12px;margin-bottom:8px;\"></div>")
            }
            append("</div></body></html>")
        }
    }

    /** 带标题的分区卡片 */
    private fun section(title: String, vararg rows: String): String {
        return buildString {
            append("<div style=\"margin-bottom:14px;\">")
            append("<div style=\"font-size:14px;font-weight:600;color:#1a1a1a;margin-bottom:8px;padding-bottom:6px;border-bottom:2px solid #4f6ef7;\">$title</div>")
            append("<table cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\">")
            rows.forEach { append(it) }
            append("</table></div>")
        }
    }

    /** 任务计划 HTML 表格 */
    private fun taskPlanTable(plans: List<TaskScheduler.TaskPlanItem>, nowMillis: Long): String {
        if (plans.isEmpty()) {
            return """<div style="font-size:13px;color:#999;padding:4px 0;">（未配置任务时间点）</div>"""
        }
        return buildString {
            append("<table cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\" style=\"font-size:12px;border-collapse:collapse;\">")
            // 表头
            append("<tr style=\"background:#fafafa;\">")
            append("<td style=\"padding:6px 8px;color:#888;border-bottom:1px solid #e8e8e8;text-align:center;width:28px;\">#</td>")
            append("<td style=\"padding:6px 8px;color:#888;border-bottom:1px solid #e8e8e8;\">计划</td>")
            append("<td style=\"padding:6px 8px;color:#888;border-bottom:1px solid #e8e8e8;\">实际</td>")
            append("<td style=\"padding:6px 8px;color:#888;border-bottom:1px solid #e8e8e8;text-align:center;width:70px;\">状态</td>")
            append("</tr>")
            // 数据行
            plans.forEach { plan ->
                append("<tr>")
                append("<td style=\"padding:5px 8px;border-bottom:1px solid #f5f5f5;text-align:center;color:#999;\">${plan.index}</td>")
                append("<td style=\"padding:5px 8px;border-bottom:1px solid #f5f5f5;color:#333;\">${plan.plannedTime}</td>")
                append("<td style=\"padding:5px 8px;border-bottom:1px solid #f5f5f5;color:#333;\">${plan.actualTime}</td>")
                val (st, tp) = plan.statusLabel(nowMillis).let {
                    when {
                        it.contains("完成") || it.contains("执行") -> it to "ok"
                        it.contains("待执行") -> it to "info"
                        it.contains("跳过") -> it to "skip"
                        else -> it to "warn"
                    }
                }
                append("<td style=\"padding:5px 8px;border-bottom:1px solid #f5f5f5;text-align:center;\">${badge(st, tp)}</td>")
                append("</tr>")
            }
            append("</table>")
        }
    }

    /** 远程指令 HTML 表格 */
    private fun commandTable(): String {
        return buildString {
            append("<table cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\" style=\"font-size:12px;border-collapse:collapse;\">")
            remoteCommands.forEachIndexed { i, (cmd, desc) ->
                val bg = if (i % 2 == 0) "#fafafa" else "#fff"
                append("<tr style=\"background:$bg;\">")
                append("<td style=\"padding:5px 8px;color:#4f6ef7;font-family:monospace;font-weight:500;white-space:nowrap;border-bottom:1px solid #f0f0f0;\">$cmd</td>")
                append("<td style=\"padding:5px 8px;color:#555;border-bottom:1px solid #f0f0f0;\">$desc</td>")
                append("</tr>")
            }
            append("</table>")
        }
    }

    // ======================== 近两周打卡日历 ========================

    /** 日历中每一天的分类，用于精确表达「为什么这天打/不打」 */
    private enum class DayKind {
        PUNCHED,      // 已打卡（已确认）
        TIMEOUT,      // 打卡超时（未确认成功，需人工核对截图）
        MISSED,       // 未计划（已过计划工作日但无打卡记录，日历中以「未计划」展示）
        SCHEDULED,    // 计划打卡（未来工作日）
        MAKEUP,       // 调休补班（周末但需上班，会打卡）
        HOLIDAY,      // 法定节假日，自动跳过
        REST,         // 自定义休息日，自动跳过
        NO_TASK,      // 未配置任务，无打卡计划
        NOT_RUNNING   // 今天调度未启动，不会自动打卡（仅用于“今天”）
    }

    private data class PunchCalendarData(
        val windowStart: LocalDate,
        val windowEnd: LocalDate,
        val today: LocalDate,
        val scheduled: Set<LocalDate>,
        val punched: Set<LocalDate>,
        val timeoutDates: Set<LocalDate>,
        val holidays: Set<LocalDate>,
        val makeupWorkdays: Set<LocalDate>,
        val restDays: Set<LocalDate>,
        val customWorkdays: Set<DayOfWeek>,
        val hasTasks: Boolean,
        val skipHolidayEnabled: Boolean,
        val autoRecycle: Boolean
    )

    /**
     * 计算 [pastDays] 天前到 [futureDays] 天后窗口内的
     * 计划打卡日、实际打卡日，以及每一天的跳过原因（节假日 / 调休补班 / 自定义休息日）。
     */
    private suspend fun computePunchCalendar(pastDays: Int, futureDays: Int): PunchCalendarData {
        val today = LocalDate.now()
        val windowStart = today.minusDays(pastDays.toLong())
        val windowEnd = today.plusDays(futureDays.toLong())
        val allTasks = withContext(Dispatchers.IO) {
            DatabaseWrapper.loadAllTask()
        }
        val hasTasks = allTasks.isNotEmpty()
        val skipHolidayEnabled = SaveKeyValues.loadBoolean(Constant.SKIP_HOLIDAY_KEY, true)
        val autoRecycle = SaveKeyValues.loadBoolean(Constant.TASK_AUTO_RECYCLE_KEY, true)
        val customWorkdays = CustomWorkdayManager.loadWorkdays()

        val scheduled = mutableSetOf<LocalDate>()
        val holidays = mutableSetOf<LocalDate>()
        val makeupWorkdays = mutableSetOf<LocalDate>()
        val restDays = mutableSetOf<LocalDate>()
        var d = windowStart
        while (!d.isAfter(windowEnd)) {
            if (TaskScheduler.isPunchScheduled(d, allTasks)) scheduled.add(d)
            if (skipHolidayEnabled && ChinaHolidayManager.isHoliday(d)) holidays.add(d)
            if (ChinaHolidayManager.isWorkday(d)) makeupWorkdays.add(d)
            if (d.dayOfWeek !in customWorkdays) restDays.add(d)
            d = d.plusDays(1)
        }
        val result = withContext(Dispatchers.IO) {
            DatabaseWrapper.loadPunchResults(windowStart, windowEnd.plusDays(1))
        }
        val punched = result.successDates
        val timeoutDates = result.timeoutDates
        return PunchCalendarData(
            windowStart, windowEnd, today, scheduled, punched, timeoutDates,
            holidays, makeupWorkdays, restDays, customWorkdays, hasTasks, skipHolidayEnabled,
            autoRecycle
        )
    }

    /** 今天是哪一类（供 HTML / 纯文本共用） */
    private fun todayKind(cal: PunchCalendarData): DayKind {
        val today = cal.today
        val punched = today in cal.punched
        val timedOut = today in cal.timeoutDates
        val holiday = cal.skipHolidayEnabled && today in cal.holidays
        val makeup = today in cal.makeupWorkdays
        val customRest = today.dayOfWeek !in cal.customWorkdays
        val schedulerRunning = TaskScheduler.isRunning()
        return when {
            punched -> DayKind.PUNCHED
            timedOut -> DayKind.TIMEOUT
            holiday -> DayKind.HOLIDAY
            makeup -> if (schedulerRunning) DayKind.MAKEUP else DayKind.NOT_RUNNING
            customRest -> DayKind.REST
            !cal.hasTasks -> DayKind.NO_TASK
            schedulerRunning -> DayKind.SCHEDULED
            else -> DayKind.NOT_RUNNING
        }
    }

    private fun legendDot(color: String, label: String): String {
        return "<span style=\"display:inline-flex;align-items:center;gap:4px;\">" +
            "<span style=\"width:9px;height:9px;border-radius:50%;background:$color;display:inline-block;\"></span>$label</span>"
    }

    // 白色圆点 + 对应色符号（成功/超时/计划用）：白底、彩色描边、符号用对应色，色相强对比、色盲友好，
    // 圆点本身为白，仅通过描边颜色与符号颜色体现状态，与图例小色点风格区分。
    private fun dotMark(color: String, glyph: String): String =
        "<span style=\"display:inline-flex;align-items:center;justify-content:center;width:18px;height:18px;margin:0 auto;border-radius:50%;background:#fff;border:2px solid $color;color:$color;font-size:11px;font-weight:700;line-height:18px;\">$glyph</span>"

    private fun textMark(color: String, text: String): String =
        "<span style=\"color:$color;font-weight:500;\">$text</span>"

    private fun calendarCell(date: LocalDate, cal: PunchCalendarData): String {
        val inWindow = !date.isBefore(cal.windowStart) && !date.isAfter(cal.windowEnd)
        val punched = date in cal.punched
        val timedOut = date in cal.timeoutDates && !punched
        val isToday = date == cal.today
        val isPast = date.isBefore(cal.today)
        val holiday = cal.skipHolidayEnabled && date in cal.holidays
        val makeup = date in cal.makeupWorkdays
        val customRest = date.dayOfWeek !in cal.customWorkdays
        // 历史按已发生事实展示；今天看调度是否在跑；未来看每日循环是否开启（关闭则不会重启，未来不自动打卡）
        val willSchedule = if (isPast) {
            false
        } else if (isToday) {
            TaskScheduler.isRunning()
        } else {
            cal.autoRecycle
        }

        val kind = when {
            punched -> DayKind.PUNCHED
            timedOut -> DayKind.TIMEOUT
            holiday -> DayKind.HOLIDAY
            makeup -> when {
                isPast && !punched -> DayKind.MISSED
                willSchedule -> DayKind.MAKEUP
                else -> DayKind.NOT_RUNNING
            }
            customRest -> DayKind.REST
            !cal.hasTasks -> DayKind.NO_TASK
            isPast -> DayKind.MISSED
            willSchedule -> DayKind.SCHEDULED
            else -> DayKind.NOT_RUNNING
        }

        val (cellBg, dayColor, markHtml) = when (kind) {
            // 成功/超时/计划：实心圆点 + 白色图标，色相强对比、色盲友好，不依赖颜色也能一眼区分
            DayKind.PUNCHED -> Triple("#ffffff", "#22c55e", dotMark("#22c55e", "✓"))
            DayKind.TIMEOUT -> Triple("#ffffff", "#fa8c16", dotMark("#fa8c16", "☐"))
            DayKind.SCHEDULED -> Triple("#ffffff", "#4f6ef7", dotMark("#4f6ef7", "❖"))
            DayKind.MISSED -> Triple("#f5f5f5", "#9aa0a6", dotMark("#9aa0a6", "✗"))
            DayKind.NOT_RUNNING -> Triple("#fff1f0", "#ef4444", dotMark("#ef4444", "✗"))
            DayKind.MAKEUP -> Triple("#f9f0ff", "#722ed1", textMark("#722ed1", "班"))
            DayKind.HOLIDAY -> Triple("#e6f7ff", "#0ea5e9", textMark("#0ea5e9", "假"))
            DayKind.REST -> Triple("#e6f7f7", "#0e9b98", textMark("#0e9b98", "休"))
            DayKind.NO_TASK -> Triple("#fafafa", "#cfd3d6", textMark("#cfd3d6", "—"))
        }
        val border = if (isToday) "border:2px solid #4f6ef7;" else "border:1px solid #eee;"
        val opacity = if (inWindow) "1" else "0.35"
        val dayNum = date.dayOfMonth
        return "<td style=\"text-align:center;vertical-align:middle;\">" +
            "<div style=\"background:$cellBg;$border;border-radius:8px;padding:6px 0;opacity:$opacity;\">" +
            "<div style=\"font-size:13px;font-weight:${if (isToday) 700 else 500};color:$dayColor;line-height:1.1;\">$dayNum</div>" +
            "<div style=\"font-size:11px;height:18px;line-height:18px;margin-top:2px;\">$markHtml</div>" +
            "</div></td>"
    }

    private fun punchCalendarHtml(cal: PunchCalendarData): String {
        val calStart = cal.windowStart.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val calEnd = cal.windowEnd.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
        val headers = listOf("一", "二", "三", "四", "五", "六", "日")

        val sb = StringBuilder()
        sb.append("<div style=\"margin-bottom:14px;\">")
        sb.append("<div style=\"font-size:14px;font-weight:600;color:#1a1a1a;margin-bottom:8px;padding-bottom:6px;border-bottom:2px solid #4f6ef7;\">📅 打卡日历 <span style=\"font-weight:400;font-size:12px;color:#888;\">近两周（含未来两周）</span></div>")
        // 图例
        sb.append("<div style=\"font-size:11px;color:#888;margin-bottom:8px;display:flex;gap:12px;flex-wrap:wrap;\">")
        sb.append(legendDot("#22c55e", "打卡成功"))
        sb.append(legendDot("#fa8c16", "打卡超时"))
        sb.append(legendDot("#9aa0a6", "未计划(过去)"))
        sb.append(legendDot("#4f6ef7", "计划打卡"))
        sb.append(legendDot("#722ed1", "调休补班"))
        sb.append(legendDot("#0ea5e9", "节假日"))
        sb.append(legendDot("#0e9b98", "休息日"))
        sb.append(legendDot("#ef4444", "未计划(今日/未来)"))
        sb.append("</div>")
        // 周网格
        sb.append("<table cellpadding=\"0\" cellspacing=\"4\" border=\"0\" width=\"100%\" style=\"font-size:12px;border-collapse:separate;\">")
        sb.append("<tr>")
        headers.forEach { h ->
            sb.append("<td style=\"text-align:center;color:#aaa;font-size:11px;padding:2px 0;\">$h</td>")
        }
        sb.append("</tr>")
        var d = calStart
        while (!d.isAfter(calEnd)) {
            sb.append("<tr>")
            for (i in 0..6) {
                sb.append(calendarCell(d.plusDays(i.toLong()), cal))
            }
            sb.append("</tr>")
            d = d.plusDays(7)
        }
        sb.append("</table>")
        // 汇总
        val punchedCount = cal.punched.count { !it.isAfter(cal.today) }
        val timeoutCount = cal.timeoutDates.count { !it.isAfter(cal.today) && it !in cal.punched }
        val scheduledPast = cal.scheduled.count { !it.isAfter(cal.today) }
        val missed = (scheduledPast - punchedCount - timeoutCount).coerceAtLeast(0)
        val todayNotRunning = todayKind(cal) == DayKind.NOT_RUNNING
        val futureScheduled = cal.scheduled
            .filter { !it.isBefore(cal.today) }
            .let { list ->
                val withoutToday = if (todayNotRunning) (list - cal.today) else list
                // 每日循环关闭时未来不会自动重启调度，未来工作日不计入“计划”
                if (cal.autoRecycle) withoutToday else emptySet()
            }
            .size
        sb.append("<div style=\"font-size:12px;color:#555;margin-top:8px;line-height:1.7;\">")
        sb.append("已打卡 <b>${punchedCount + timeoutCount}</b> 天 · 计划 <b>$futureScheduled</b> 天 · 未计划 <b style=\"color:#999;\">$missed</b> 天<br>")
        sb.append("窗口内：节假日 <b style=\"color:#d46b08;\">${cal.holidays.size}</b> 天 · 休息日 <b>${cal.restDays.size}</b> 天 · 调休补班 <b style=\"color:#722ed1;\">${cal.makeupWorkdays.size}</b> 天")
        if (!cal.autoRecycle) {
            sb.append("<br><span style=\"color:#cf1322;\">⚠️ 每日循环已关闭，未来日期不会自动打卡（已标红「未计划」）</span>")
        }
        sb.append("</div>")
        // 今日明确说明
        sb.append(todayCalendarNote(cal))
        sb.append("</div>")
        return sb.toString()
    }

    /** 今日是否会自动打卡的明确说明，避免「停止循环没成功误打卡」类问题 */
    private fun todayCalendarNote(cal: PunchCalendarData): String {
        val kind = todayKind(cal)
        val text = when (kind) {
            DayKind.PUNCHED -> "今日打卡成功"
            DayKind.TIMEOUT -> "今日打卡超时（未确认成功，请查收截图邮件手动核对）"
            DayKind.HOLIDAY -> "今日为法定节假日，自动跳过（不会打卡）"
            DayKind.MAKEUP -> "今日为调休补班日，计划打卡"
            DayKind.REST -> "今日为休息日，自动跳过（不会打卡）"
            DayKind.NO_TASK -> "未配置任务，今日无打卡计划"
            DayKind.SCHEDULED -> "今日为工作日，计划打卡"
            DayKind.MISSED -> "今日未计划打卡"
            DayKind.NOT_RUNNING -> "今日未计划打卡（调度未启动）"
        }
        val running = TaskScheduler.isRunning()
        val warn = when {
            kind == DayKind.NOT_RUNNING -> ""
            (kind == DayKind.SCHEDULED || kind == DayKind.MAKEUP) && running ->
                "（调度运行中，将自动打卡）"
            (kind == DayKind.SCHEDULED || kind == DayKind.MAKEUP) && !running ->
                "（当前调度未运行，今日不会自动打卡）"
            else -> ""
        }
        return "<div style=\"font-size:12px;color:#333;margin-top:6px;padding:6px 10px;background:#f6f8ff;border-radius:6px;\">" +
            "📌 今日：$text$warn</div>"
    }

    /** 纯文本邮件的今日说明 */
    private fun buildTodayTextNote(cal: PunchCalendarData): String {
        val kind = todayKind(cal)
        val base = when (kind) {
            DayKind.PUNCHED -> "打卡成功"
            DayKind.TIMEOUT -> "打卡超时（未确认成功）"
            DayKind.HOLIDAY -> "法定节假日，自动跳过"
            DayKind.MAKEUP -> "调休补班日，计划打卡"
            DayKind.REST -> "休息日，自动跳过"
            DayKind.NO_TASK -> "未配置任务，无打卡计划"
            DayKind.SCHEDULED -> "工作日，计划打卡"
            DayKind.MISSED -> "未计划打卡"
            DayKind.NOT_RUNNING -> "未计划打卡（调度未启动）"
        }
        val running = TaskScheduler.isRunning()
        return when {
            kind == DayKind.NOT_RUNNING -> base
            (kind == DayKind.SCHEDULED || kind == DayKind.MAKEUP) && running ->
                "$base（调度运行中，将自动打卡）"
            (kind == DayKind.SCHEDULED || kind == DayKind.MAKEUP) && !running ->
                "$base（当前调度未运行，不会自动打卡）"
            else -> base
        }
    }

    private fun nextPunchText(
        cal: PunchCalendarData,
        plans: List<TaskScheduler.TaskPlanItem>,
        now: Long,
        autoRecycle: Boolean
    ): String {
        val running = TaskScheduler.isRunning()
        return when {
            running -> {
                val next = plans.filter { it.actualTimeMillis > now }.minByOrNull { it.actualTimeMillis }
                if (next != null) {
                    // 使用与 TaskScheduler.kt:166-168 相同的等价写法，避免在部分设备/dex 配置下
                    // LocalDate.ofInstant(Instant, ZoneId) 报 NoSuchMethodError。
                    val d = Instant.ofEpochMilli(next.actualTimeMillis).atZone(ZoneId.systemDefault()).toLocalDate()
                    val rel = when {
                        d == cal.today -> "今天"
                        d == cal.today.plusDays(1) -> "明天"
                        else -> "${"%02d".format(d.monthValue)}-${"%02d".format(d.dayOfMonth)}"
                    }
                    "$rel ${next.actualTime}"
                } else {
                    "今日无（已执行完）"
                }
            }
            // 调度未运行，但每日循环开启：下次会在每日重置后自动启动，
            // 重置点之前随机偏移尚未生成，因此只显示基准计划时间，并提示随机范围
            autoRecycle -> {
                val t = plans.firstOrNull()?.plannedTime
                if (t != null) {
                    val needRandom = SaveKeyValues.loadBoolean(Constant.RANDOM_TIME_KEY, true)
                    val range =
                        SaveKeyValues.loadInt(Constant.TIME_RANGE_KEY, Constant.DEFAULT_TIME_RANGE)
                    val suffix = if (needRandom) {
                        "（不含 $range min 随机时间）"
                    } else {
                        "（随机时间已关闭）"
                    }
                    "次日 $t$suffix"
                } else "任务未配置，无下次打卡"
            }
            // 调度未运行且每日循环关闭：完全不会自动打卡
            else -> "任务未启动，无下次打卡"
        }
    }

    private fun recentPunchText(cal: PunchCalendarData): String {
        val recent = (cal.punched + cal.timeoutDates).filter { !it.isAfter(cal.today) }.maxOrNull()
        return recent?.toString() ?: "—"
    }

    @JvmStatic
    suspend fun buildStatusReportHtml(context: Context, listenerConnected: Boolean): String {
        val d = gatherStatusData(context)
        val batterySnap = readBatterySnapshot(context)

        val body = buildString {
            // 概览卡片
            append("<div style=\"display:flex;gap:10px;margin-bottom:14px;flex-wrap:wrap;\">")

            // 任务调度
            val running = TaskScheduler.describeRunningState()
            val isRunning = running.startsWith("运行中")
            val runMain = if (isRunning) "运行中" else "未启动"
            val runSub = if (isRunning) running.substringAfter("｜", "") else "需手动或远程启动"
            val runBadgeType = if (isRunning) "ok" else "warn"
            val runBadge = badge(runMain, runBadgeType)
            append("<div style=\"flex:1;min-width:120px;background:#f9fafc;border-radius:8px;padding:10px 12px;text-align:center;\">")
            append("<div style=\"font-size:11px;color:#888;margin-bottom:6px;\">任务调度</div>")
            append(runBadge)
            if (!isRunning) {
                append("<div style=\"font-size:10px;color:#999;margin-top:4px;line-height:1.3;\">$runSub</div>")
            }
            append("</div>")

            // 电量
            val batColor = when { d.battery < 0 -> "#999"; d.battery < 30 -> "#f5222d"; d.battery < 60 -> "#fa8c16"; else -> "#52c41a" }
            append("<div style=\"flex:1;min-width:120px;background:#f9fafc;border-radius:8px;padding:10px 12px;text-align:center;\">")
            append("<div style=\"font-size:11px;color:#888;margin-bottom:4px;\">当前电量</div>")
            append("<div style=\"font-size:20px;font-weight:700;color:$batColor;\">${if (d.battery >= 0) "${d.battery}%" else "—"}</div>")
            append("</div>")

            // 消息渠道
            append("<div style=\"flex:1;min-width:120px;background:#f9fafc;border-radius:8px;padding:10px 12px;text-align:center;\">")
            append("<div style=\"font-size:11px;color:#888;margin-bottom:4px;\">消息渠道</div>")
            append("<div style=\"font-size:13px;color:#333;font-weight:500;\">${if (d.channelType == 0) "QQ邮箱" else if (d.channelType == 1) "企业微信" else "未配置"}</div>")
            append("</div>")
            append("</div>")

            // 运行状态
            val cycleText = if (d.autoRecycle) {
                badge("开启", "ok") + " <span style=\"font-size:11px;color:#888;\">每天 ${"%02d".format(d.resetHour)}:00 自动启动</span>"
            } else {
                badge("关闭", "warn") + " <span style=\"font-size:11px;color:#888;\">需手动启动</span>"
            }
            val maskText = if (AppRuntimeConfig.isForcePseudoMask()) {
                val sec = SaveKeyValues.loadInt(Constant.IDLE_PSEUDO_MASK_TIMEOUT_KEY, 60)
                "${badge("开启", "ok")} <span style=\"font-size:11px;color:#888;\">离开${sec}s自动黑屏</span>"
            } else {
                badge("关闭", "warn")
            }
            val powerSaveText = if (AppRuntimeConfig.isPowerSaveMode()) badge("开启", "ok") else badge("关闭", "info")
            val holidayText = if (SaveKeyValues.loadBoolean(Constant.SKIP_HOLIDAY_KEY, true)) badge("开启", "ok") else badge("关闭", "info")
            val transferText = if (SaveKeyValues.loadBoolean(Constant.NOTIFICATION_TRANSFER_KEY, false)) badge("开启", "ok") else badge("关闭", "warn")
            val runDetail = if (running.startsWith("运行中")) running.substringAfter("｜", "") else "需手动或远程启动，每日循环仅在每日重置时生效"

            append(section("⚙️ 运行状态",
                row("任务调度", "$runBadge <span style=\"font-size:11px;color:#888;\">${if (runDetail.isNotEmpty()) runDetail else ""}</span>"),
                row("每日循环", cycleText),
                row("距下次重置", TaskScheduler.secondsUntilNextReset().formatTime()),
                row("下次打卡", nextPunchText(d.cal, d.plans, d.now, d.autoRecycle)),
                row("最近打卡", recentPunchText(d.cal)),
                row("省电模式", powerSaveText),
                row("跳过节假日", holidayText),
                row("伪息屏增强", maskText),
                row("通知转移", transferText)
            ))

            // 今日任务
            val taskHeader = buildString {
                append("📋 今日任务")
                if (d.plans.isNotEmpty()) {
                    append(" <span style=\"font-weight:400;font-size:12px;color:#888;\">共 ${d.plans.size} 个 · 待执行 ${d.pending.size} 个</span>")
                }
            }
            append("<div style=\"margin-bottom:14px;\">")
            append("<div style=\"font-size:14px;font-weight:600;color:#1a1a1a;margin-bottom:8px;padding-bottom:6px;border-bottom:2px solid #4f6ef7;\">$taskHeader</div>")
            append(taskPlanTable(d.plans, d.now))
            append("</div>")

            // 服务状态
            val overlay = if (Settings.canDrawOverlays(context)) badge("已获取", "ok") else badge("未获取", "warn")
            val notify = when {
                !context.notificationEnable() -> badge("未授权", "err")
                listenerConnected -> badge("正常", "ok")
                else -> badge("已授权但断开", "warn")
            }
            val screen = when (SaveKeyValues.loadInt(Constant.RESULT_SOURCE_KEY, Constant.DEFAULT_INDEX)) {
                1 -> badge("截屏反馈", "ok")
                2 -> {
                    val mode = if (SaveKeyValues.loadInt(Constant.ACCESSIBILITY_FEEDBACK_MODE_KEY, 0) == 0) "截屏" else "文本"
                    badge("无障碍-${mode}反馈", "ok")
                }
                else -> badge("断开", "warn")
            }

            append(section("🔌 服务状态",
                row("前台服务", if (ForegroundRunningService.isRunning) badge("运行中", "ok") else badge("未运行", "err")),
                row("悬浮窗权限", overlay),
                row("通知监听", notify),
                row("结果来源", screen)
            ))

            // 设备信息
            append(section("📱 设备信息",
                row("手机状态", deviceScreenStateText(context)),
                row("当前时间", Date().formatDateTime()),
                row("充电状态", batterySnap.chargingStatus),
                row("电池电流", batterySnap.currentText),
                row("电池健康", batterySnap.healthText),
                row("过去1小时掉电", BatteryHistory.drainOver(context, 1)),
                row("过去6小时掉电", BatteryHistory.drainOver(context, 6)),
                row("过去12小时掉电", BatteryHistory.drainOver(context, 12)),
                row("WiFi 状态", wifiStatusText(context)),
                row("蓝牙状态", bluetoothStatusText(context)),
                row("手机温度", batterySnap.temperatureText),
                row("系统版本", androidVersionText()),
                row("版本号", BuildConfig.VERSION_NAME)
            ))

            // 打卡日历
            append(punchCalendarHtml(d.cal))

            // 远程指令
            append(section("💬 远程指令",
                row("触发方式", "微信/QQ 发送 <code style=\"background:#f0f5ff;padding:1px 5px;border-radius:3px;font-size:12px;\">DT#指令</code>")
            ))
            append("<div style=\"margin-bottom:4px;\">")
            append(commandTable())
            append("</div>")
        }

        return pageShell("📊 状态查询通知", body, compact = false)
    }

    @JvmStatic
    suspend fun buildTaskExecutingContentHtml(
        index: Int, total: Int, plannedTime: String, actualTime: String
    ): String {
        val plans = TaskScheduler.loadTodayTaskPlans(usePersisted = true)
        val now = System.currentTimeMillis()
        val pending = plans.filter { it.actualTimeMillis > now }

        val body = buildString {
            // 进度概览
            append("<div style=\"display:flex;gap:10px;margin-bottom:14px;flex-wrap:wrap;\">")
            append("<div style=\"flex:1;min-width:100px;background:#f9fafc;border-radius:8px;padding:10px 12px;text-align:center;\">")
            append("<div style=\"font-size:11px;color:#888;margin-bottom:2px;\">当前进度</div>")
            append("<div style=\"font-size:22px;font-weight:700;color:#4f6ef7;\">$index<span style=\"font-size:14px;color:#999;\">/$total</span></div>")
            append("</div>")
            append("<div style=\"flex:1;min-width:100px;background:#f9fafc;border-radius:8px;padding:10px 12px;text-align:center;\">")
            append("<div style=\"font-size:11px;color:#888;margin-bottom:2px;\">计划时间</div>")
            append("<div style=\"font-size:15px;color:#333;font-weight:500;\">$plannedTime</div>")
            append("</div>")
            append("<div style=\"flex:1;min-width:100px;background:#f9fafc;border-radius:8px;padding:10px 12px;text-align:center;\">")
            append("<div style=\"font-size:11px;color:#888;margin-bottom:2px;\">实际时间</div>")
            append("<div style=\"font-size:15px;color:#6c5ce7;font-weight:500;\">$actualTime</div>")
            append("</div>")
            append("</div>")

            // 进度条
            val pct = if (total > 0) (index * 100 / total) else 0
            append("<div style=\"margin-bottom:14px;\">")
            append("<div style=\"background:#f0f0f0;border-radius:4px;height:6px;overflow:hidden;\">")
            append("<div style=\"background:linear-gradient(90deg,#4f6ef7,#6c5ce7);height:6px;width:${pct}%;border-radius:4px;\"></div>")
            append("</div>")
            append("<div style=\"font-size:11px;color:#888;text-align:right;margin-top:3px;\">${pct}% 已完成</div>")
            append("</div>")

            val taskHeader = buildString {
                append("📋 全部任务")
                if (plans.isNotEmpty()) {
                    append(" <span style=\"font-weight:400;font-size:12px;color:#888;\">共 ${plans.size} 个 · 待执行 ${pending.size} 个</span>")
                }
            }
            append("<div style=\"margin-bottom:14px;\">")
            append("<div style=\"font-size:14px;font-weight:600;color:#1a1a1a;margin-bottom:8px;padding-bottom:6px;border-bottom:2px solid #4f6ef7;\">$taskHeader</div>")
            append(taskPlanTable(plans, now))
            append("</div>")

            // 省电模式
            val ps = if (AppRuntimeConfig.isPowerSaveMode()) badge("开启", "ok") else badge("关闭", "info")
            append(section("⚙️ 运行模式",
                row("省电模式", ps)
            ))
        }

        return pageShell("▶️ 任务执行通知", body, compact = false)
    }

    @JvmStatic
    suspend fun buildTaskCompletedContentHtml(): String {
        val plans = TaskScheduler.loadTodayTaskPlans(usePersisted = true)
        val resetHour = SaveKeyValues.loadInt(Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR)
        val autoRecycle = SaveKeyValues.loadBoolean(Constant.TASK_AUTO_RECYCLE_KEY, true)

        val cycleText = if (autoRecycle) {
            "开启 <span style=\"color:#888;font-size:11px;\">每天 ${"%02d".format(resetHour)}:00 自动启动</span>"
        } else {
            badge("关闭", "warn")
        }

        val body = buildString {
            // 完成提示
            append("<div style=\"text-align:center;padding:16px 0 10px;\">")
            append("<div style=\"font-size:40px;margin-bottom:8px;\">✅</div>")
            append("<div style=\"font-size:16px;font-weight:600;color:#52c41a;margin-bottom:4px;\">本轮任务已全部执行完毕</div>")
            append("<div style=\"font-size:12px;color:#888;\">距下次重置 ${TaskScheduler.secondsUntilNextReset().formatTime()}</div>")
            append("</div>")

            append(section("⚙️ 运行状态",
                row("每日循环", cycleText)
            ))

            val taskHeader = "📋 已完成任务 <span style=\"font-weight:400;font-size:12px;color:#888;\">共 ${plans.size} 个</span>"
            append("<div style=\"margin-bottom:14px;\">")
            append("<div style=\"font-size:14px;font-weight:600;color:#1a1a1a;margin-bottom:8px;padding-bottom:6px;border-bottom:2px solid #4f6ef7;\">$taskHeader</div>")
            append(taskPlanTable(plans, System.currentTimeMillis()))
            append("</div>")
        }

        return pageShell("✅ 任务状态通知", body, compact = false)
    }

    @JvmStatic
    fun buildSkipContentHtml(): String {
        val body = buildString {
            append("<div style=\"text-align:center;padding:16px 0 10px;\">")
            append("<div style=\"font-size:40px;margin-bottom:8px;\">🏖️</div>")
            append("<div style=\"font-size:16px;font-weight:600;color:#fa8c16;margin-bottom:4px;\">节假日/休息日</div>")
            append("<div style=\"font-size:13px;color:#888;\">任务已自动跳过</div>")
            append("</div>")
        }
        return pageShell("⏭️ 任务跳过通知", body, compact = false)
    }

    @JvmStatic
    fun buildLowBatteryContentHtml(battery: Int, threshold: Int, stage: Int): String {
        val batColor = if (battery < 15) "#f5222d" else "#fa8c16"
        val pct = battery.coerceIn(0, 100)
        val bound = threshold - (stage - 1) * 10

        val body = buildString {
            append("<div style=\"text-align:center;padding:16px 0 10px;\">")
            append("<div style=\"font-size:40px;margin-bottom:8px;\">🔋</div>")
            append("<div style=\"font-size:28px;font-weight:700;color:$batColor;\">$battery%</div>")
            append("<div style=\"font-size:13px;color:#888;margin-top:2px;\">低电量提醒 · 第 ${stage} 档（低于 $bound%）</div>")
            append("</div>")

            // 电量条
            append("<div style=\"margin-bottom:10px;\">")
            append("<div style=\"background:#f0f0f0;border-radius:6px;height:10px;overflow:hidden;\">")
            append("<div style=\"background:$batColor;height:10px;width:${pct}%;border-radius:6px;\"></div>")
            append("</div>")
            append("</div>")

            append("<div style=\"font-size:13px;color:#555;line-height:1.6;\">")
            append("<p style=\"margin:0 0 6px;\">📌 请及时连接充电器，避免设备关机导致任务中断。</p>")
            append("<p style=\"margin:0;color:#888;font-size:11px;\">低电量阈值 $threshold%：分 3 档各提醒一次（开始充电后重新计数）。</p>")
            append("</div>")
        }
        return pageShell("🔋 低电量提醒（第${stage}档）", body, compact = false)
    }

    /** 开始充电、低电量告警取消（纯文本，备用） */
    @JvmStatic
    fun buildChargingResumedContent(battery: Int): String {
        return buildString {
            appendLine("====================")
            appendLine("  电量开始充电")
            appendLine("====================")
            appendLine()
            appendLine("· 当前电量：$battery%")
            appendLine("· 设备已接入电源，低电量告警已取消")
            appendLine("· 下次放电将重新按档计数")
        }
    }

    /** 开始充电、低电量告警取消（HTML，走反馈渠道通知） */
    @JvmStatic
    fun buildChargingResumedContentHtml(battery: Int): String {
        val body = buildString {
            append("<div style=\"text-align:center;padding:16px 0 10px;\">")
            append("<div style=\"font-size:40px;margin-bottom:8px;\">⚡</div>")
            append("<div style=\"font-size:28px;font-weight:700;color:#52c41a;\">$battery%</div>")
            append("<div style=\"font-size:13px;color:#888;margin-top:2px;\">设备已接入电源</div>")
            append("</div>")
            append("<div style=\"font-size:13px;color:#555;line-height:1.6;\">")
            append("<p style=\"margin:0 0 6px;\">⚡ 设备已开始充电，低电量告警已取消。</p>")
            append("<p style=\"margin:0;color:#888;font-size:11px;\">下次放电将重新按档计数。</p>")
            append("</div>")
        }
        return pageShell("⚡ 电量开始充电", body, compact = false)
    }

    /** 电量已充满（纯文本，备用） */
    @JvmStatic
    fun buildBatteryFullContent(battery: Int): String {
        return buildString {
            appendLine("====================")
            appendLine("  电量已充满")
            appendLine("====================")
            appendLine()
            appendLine("· 当前电量：$battery%")
            appendLine("· 设备已充满，可拔除电源")
            appendLine("· 充满后每轮充电周期上报一次，不重复")
        }
    }

    /** 电量已充满（HTML，走反馈渠道通知） */
    @JvmStatic
    fun buildBatteryFullContentHtml(battery: Int): String {
        val body = buildString {
            append("<div style=\"text-align:center;padding:16px 0 10px;\">")
            append("<div style=\"font-size:40px;margin-bottom:8px;\">🔋</div>")
            append("<div style=\"font-size:28px;font-weight:700;color:#16a34a;\">100%</div>")
            append("<div style=\"font-size:13px;color:#888;margin-top:2px;\">电量已充满</div>")
            append("</div>")
            append("<div style=\"font-size:13px;color:#555;line-height:1.6;\">")
            append("<p style=\"margin:0 0 6px;\">🔋 设备电量已充满（$battery%），可拔除电源。</p>")
            append("<p style=\"margin:0;color:#888;font-size:11px;\">充满后每轮充电周期上报一次，不重复提醒。</p>")
            append("</div>")
        }
        return pageShell("🔋 电量已充满", body, compact = false)
    }

    /** 电量智能预警（HTML，走反馈渠道通知） */
    @JvmStatic
    fun buildBatterySmartAlertContentHtml(battery: Int, predictedTime: String, warningMinute: Int, threshold: Int, pred: BatteryPredictor.Prediction): String {
        val shutdownTimeMs = System.currentTimeMillis() + (battery / pred.ratePerHour * 3600 * 1000).toLong()
        val shutdownTimeText = java.util.Date(shutdownTimeMs).format("HH:mm")
        val warningTimeText = BatteryPredictor.formatWarningMinute(warningMinute)
        val rateText = String.format("%.1f", pred.ratePerHour)
        val body = buildString {
            // ── 标题区：图标 + 大标题 ──
            append("<div style=\"text-align:center;padding:20px 0 16px;\">")
            append("<div style=\"font-size:44px;margin-bottom:10px;\">⚠️</div>")
            append("<div style=\"font-size:22px;font-weight:700;color:#b45309;\">电量耗尽预警</div>")
            append("</div>")

            // ── 三栏核心数据卡 ──
            append("<div style=\"display:flex;gap:0;margin:0 0 18px;border-radius:12px;overflow:hidden;border:1px solid #e8e8e8;\">")
            // 当前电量
            append("<div style=\"flex:1;text-align:center;padding:14px 4px;background:#fffbf0;border-right:1px solid #e8e8e8;\">")
            append("<div style=\"font-size:26px;font-weight:700;color:#b45309;line-height:1.2;\">$battery<span style=\"font-size:14px\">%</span></div>")
            append("<div style=\"font-size:11px;color:#999;margin-top:4px;\">当前电量</div>")
            append("</div>")
            // 预计耗尽
            append("<div style=\"flex:1;text-align:center;padding:14px 4px;background:#fff5f5;border-right:1px solid #e8e8e8;\">")
            append("<div style=\"font-size:20px;font-weight:700;color:#dc2626;line-height:1.2;\">$shutdownTimeText</div>")
            append("<div style=\"font-size:11px;color:#999;margin-top:4px;\">预计耗尽</div>")
            append("</div>")
            // 建议充电
            append("<div style=\"flex:1;text-align:center;padding:14px 4px;background:#f0fdf4;\">")
            append("<div style=\"font-size:20px;font-weight:700;color:#16a34a;line-height:1.2;\">$warningTimeText</div>")
            append("<div style=\"font-size:11px;color:#999;margin-top:4px;\">建议充电</div>")
            append("</div>")
            append("</div>") // end 三栏

            // ── 消耗速度（辅助信息，弱化）──
            append("<div style=\"text-align:center;font-size:12px;color:#aaa;margin-bottom:16px;\">⚡ ${rateText}%/h 消耗速度</div>")

            // ── 原因说明（精简为一段）──
            append("<div style=\"background:#fafafa;border-radius:10px;padding:14px 16px;margin-bottom:18px;\">")
            append("<div style=\"font-size:13px;color:#444;line-height:1.7;\">")
            append("预测电量将在 <b>$predictedTime</b> 降至 <b>$threshold%</b>，时间落在夜间（<b>${warningTimeText}</b> 之后），可能在你睡眠期间自动关机。</div>")
            append("</div>")

            // ── 行动召唤条（高亮）──
            append("<div style=\"background:linear-gradient(135deg,#fef3c7,#fde68a);border-radius:10px;padding:14px 20px;text-align:center;border-left:4px solid #f59e0b;\">")
            append("<div style=\"font-size:15px;font-weight:600;color:#92400e;\">🔌 请在 <b style=\"color:#b45309;font-size:17px;\">${warningTimeText}</b> 前为设备充电</div>")
            append("</div>")
        }
        return pageShell("⚠️ 电量耗尽预警", body, compact = false)
    }

    // ======================== 轻量级通知 HTML ========================

    /**
     * 轻量级通知的居中图标 + 标题 + 副标题卡片头（P3-6，消除 6+ 个 builder 的重复）。
     * tip 为空时不渲染副标题行，保持与原单 div 结构一致。
     */
    private fun centerHeader(icon: String, color: String, label: String, tip: String? = null): String {
        return buildString {
            append("<div style=\"text-align:center;padding:12px 0;\">")
            append("<div style=\"font-size:40px;margin-bottom:8px;\">$icon</div>")
            append("<div style=\"font-size:16px;font-weight:600;color:$color;margin-bottom:4px;\">$label</div>")
            if (!tip.isNullOrBlank()) {
                append("<div style=\"font-size:13px;color:#888;\">$tip</div>")
            }
            append("</div>")
        }
    }

    @JvmStatic
    fun buildClockInTextResultHtml(
        snippet: String,
        keyword: String,
        appName: String,
        extra: String? = null,
        clockInTime: Long? = null
    ): String {
        val clockInText = if (clockInTime != null && clockInTime > 0L) {
            java.util.Date(clockInTime).format("HH:mm")
        } else {
            null
        }
        val body = buildString {
            append(centerHeader("✅", "#52c41a", "打卡成功", "无障碍文本识别"))
            append("<table cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\" style=\"margin-bottom:8px;\">")
            append("<tr><td style=\"font-size:12px;color:#888;padding:3px 0;\">应用来源</td><td style=\"font-size:12px;color:#333;text-align:right;\">$appName</td></tr>")
            if (clockInText != null) {
                append("<tr><td style=\"font-size:12px;color:#888;padding:3px 0;\">打卡时间</td><td style=\"font-size:12px;color:#333;text-align:right;\">$clockInText</td></tr>")
            }
            append("<tr><td style=\"font-size:12px;color:#888;padding:3px 0;\">匹配关键词</td><td style=\"font-size:12px;color:#333;text-align:right;\">$keyword</td></tr>")
            append("<tr><td style=\"font-size:12px;color:#888;padding:3px 0;vertical-align:top;\">识别摘要</td><td style=\"font-size:12px;color:#333;text-align:right;word-break:break-all;\">${snippet.ifBlank { "—" }}</td></tr>")
            if (!extra.isNullOrBlank()) {
                append("<tr><td style=\"font-size:12px;color:#888;padding:3px 0;\">备注</td><td style=\"font-size:12px;color:#cf1322;text-align:right;\">$extra</td></tr>")
            }
            append("</table>")
        }
        return pageShell("✅ 打卡结果通知", body, compact = true)
    }

    @JvmStatic
    fun buildClockInResultHtml(title: String, notice: String): String {
        val battery = batteryCap()
        val body = centerHeader("✅", "#52c41a", "打卡成功") +
                "<table cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\" style=\"margin-bottom:8px;\">" +
                "<tr><td style=\"font-size:12px;color:#888;padding:3px 0;\">通知标题</td><td style=\"font-size:12px;color:#333;text-align:right;\">${title.ifBlank { "—" }}</td></tr>" +
                "<tr><td style=\"font-size:12px;color:#888;padding:3px 0;\">通知内容</td><td style=\"font-size:12px;color:#333;text-align:right;\">${notice}</td></tr>" +
                "<tr><td style=\"font-size:12px;color:#888;padding:3px 0;\">设备电量</td><td style=\"font-size:12px;color:#333;text-align:right;\">$battery</td></tr>" +
                "</table>"
        return pageShell("✅ 打卡结果通知", body, compact = true)
    }

    @JvmStatic
    fun buildNotificationTransferHtml(
        appName: String,
        title: String,
        content: String,
        time: String
    ): String {
        val body = centerHeader("📨", "#1677ff", "通知转移", "${appName} 的通知已转发到本机") +
                "<table cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\" style=\"margin-bottom:8px;\">" +
                "<tr><td style=\"font-size:12px;color:#888;padding:3px 0;\">来源应用</td><td style=\"font-size:12px;color:#333;text-align:right;\">$appName</td></tr>" +
                "<tr><td style=\"font-size:12px;color:#888;padding:3px 0;\">通知标题</td><td style=\"font-size:12px;color:#333;text-align:right;word-break:break-all;\">${title.ifBlank { "—" }}</td></tr>" +
                "<tr><td style=\"font-size:12px;color:#888;padding:3px 0;vertical-align:top;\">通知内容</td><td style=\"font-size:12px;color:#333;text-align:right;word-break:break-all;\">${content.ifBlank { "—" }}</td></tr>" +
                "<tr><td style=\"font-size:12px;color:#888;padding:3px 0;\">接收时间</td><td style=\"font-size:12px;color:#333;text-align:right;\">$time</td></tr>" +
                "</table>"
        return pageShell("📨 通知转移 · $appName", body, compact = true)
    }

    @JvmStatic
    fun buildStopTaskHtml(): String {
        val battery = batteryCap()
        val body = centerHeader("⏹️", "#fa8c16", "任务调度已停止", "请及时在下次任务前重新启动") +
                "<table cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\" style=\"margin-top:8px;\">" +
                "<tr><td style=\"font-size:12px;color:#888;padding:3px 0;\">设备电量</td><td style=\"font-size:12px;color:#333;text-align:right;\">$battery</td></tr>" +
                "</table>"
        return pageShell("⏹️ 任务停止通知", body, compact = true)
    }

    @JvmStatic
    fun buildCycleStatusHtml(enabled: Boolean): String {
        val icon = if (enabled) "🔄" else "⏸️"
        val color = if (enabled) "#52c41a" else "#fa8c16"
        val label = if (enabled) "每日循环已开启" else "每日循环已关闭"
        val tip = if (enabled) "每天重置时将自动启动调度" else "需手动或远程启动调度"
        val battery = batteryCap()
        val body = centerHeader(icon, color, label, tip) +
                "<table cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\" style=\"margin-top:8px;\">" +
                "<tr><td style=\"font-size:12px;color:#888;padding:3px 0;\">当前状态</td><td style=\"font-size:12px;color:#333;text-align:right;\">${if (enabled) "自动运行" else "手动控制"}</td></tr>" +
                "<tr><td style=\"font-size:12px;color:#888;padding:3px 0;\">设备电量</td><td style=\"font-size:12px;color:#333;text-align:right;\">$battery</td></tr>" +
                "</table>"
        return pageShell("${if (enabled) "🔄" else "⏸️"} 循环状态通知", body, compact = true)
    }

    /**
     * 通知转移开关状态回执（远程指令 DT#开启转移 / DT#关闭转移 触发）。
     * warning 不为空时提示配置未补全，但仍会保存开关态。
     */
    @JvmStatic
    fun buildTransferStatusHtml(enabled: Boolean, warning: String?): String {
        val icon = if (enabled) "📨" else "🔕"
        val color = if (enabled) "#1677ff" else "#fa8c16"
        val label = if (enabled) "通知转移已开启" else "通知转移已关闭"
        val tip = if (enabled) "目标打卡应用通知将经现有渠道转发到目标手机" else "不再转发打卡应用通知"
        val battery = batteryCap()
        val body = buildString {
            append(centerHeader(icon, color, label, tip))
            append("<table cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\" style=\"margin-top:8px;\">")
            append("<tr><td style=\"font-size:12px;color:#888;padding:3px 0;\">当前状态</td><td style=\"font-size:12px;color:#333;text-align:right;\">${if (enabled) "转发中" else "已停用"}</td></tr>")
            append("<tr><td style=\"font-size:12px;color:#888;padding:3px 0;\">设备电量</td><td style=\"font-size:12px;color:#333;text-align:right;\">$battery</td></tr>")
            append("</table>")
            if (!warning.isNullOrBlank()) {
                append("<div style=\"margin-top:10px;padding:8px 10px;background:#fff7e6;border:1px solid #ffd591;border-radius:6px;font-size:12px;color:#ad6800;line-height:1.4;\">⚠️ $warning</div>")
            }
        }
        return pageShell("${if (enabled) "📨" else "🔕"} 通知转移状态通知", body, compact = true)
    }

    @JvmStatic
    fun buildAttendanceRecordHtml(records: String): String {
        val lines = records.trim().lines().filter { it.isNotBlank() }
        val tableBody: String
        if (lines.isEmpty()) {
            tableBody = "<div style=\"font-size:13px;color:#999;text-align:center;padding:16px 0;\">暂无考勤记录</div>"
        } else {
            tableBody = buildString {
                append("<table cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\" style=\"font-size:12px;border-collapse:collapse;\">")
                append("<tr style=\"background:#fafafa;\">")
                append("<td style=\"padding:6px 8px;color:#888;border-bottom:1px solid #e8e8e8;text-align:center;width:28px;\">#</td>")
                append("<td style=\"padding:6px 8px;color:#888;border-bottom:1px solid #e8e8e8;\">打卡记录</td>")
                append("</tr>")
                lines.forEachIndexed { i, line ->
                    val bg = if (i % 2 == 0) "#fff" else "#fafafa"
                    // 去掉 "【第X次】"，因为在表格里已有序号列
                    val display = line.replace(Regex("【第\\d+次】"), "")
                    append("<tr style=\"background:$bg;\">")
                    append("<td style=\"padding:5px 8px;border-bottom:1px solid #f0f0f0;text-align:center;color:#999;\">${i + 1}</td>")
                    append("<td style=\"padding:5px 8px;border-bottom:1px solid #f0f0f0;color:#333;font-size:12px;\">$display</td>")
                    append("</tr>")
                }
                append("</table>")
            }
        }
        val body = "<div style=\"font-size:14px;font-weight:600;color:#1a1a1a;margin-bottom:8px;padding-bottom:6px;border-bottom:2px solid #4f6ef7;\">" +
                "📋 今日打卡记录 <span style=\"font-weight:400;font-size:12px;color:#888;\">共 ${lines.size} 条</span>" +
                "</div>" + tableBody
        return pageShell("📋 考勤记录", body, compact = true)
    }

    @JvmStatic
    fun buildScreenshotResultHtml(success: Boolean, detail: String): String {
        val (icon, color, label) = if (success) {
            Triple("📸", "#52c41a", "截图成功")
        } else {
            Triple("❌", "#cf1322", "截图失败")
        }
        val body = centerHeader(icon, color, label, detail)
        return pageShell("📸 截屏通知", body, compact = true)
    }

    @JvmStatic
    fun buildTimeoutAlertHtml(alertTitle: String, detail: String): String {
        val body = centerHeader("⚠️", "#cf1322", alertTitle, detail)
        return pageShell("⚠️ 超时提醒", body, compact = true)
    }

    @JvmStatic
    fun buildMemoryAlertHtml(): String {
        val battery = batteryCap()
        val body = centerHeader("💾", "#cf1322", "内存使用已超过 90%", "请关注设备运行情况，必要时重启释放内存") +
                "<table cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\" style=\"margin-top:8px;\">" +
                "<tr><td style=\"font-size:12px;color:#888;padding:3px 0;\">设备电量</td><td style=\"font-size:12px;color:#333;text-align:right;\">$battery</td></tr>" +
                "</table>"
        return pageShell("💾 内存预警", body, compact = true)
    }

    // ======================== 设备状态辅助 ========================

    /** 一次性读取电池状态快照，避免重复注册粘性广播（P2-5） */
    private data class BatterySnapshot(
        val chargingStatus: String,
        val currentText: String,
        val healthText: String,
        val temperatureText: String
    )

    private fun readBatterySnapshot(context: Context): BatterySnapshot {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val mgr = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            val health = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1
            val microAmp = mgr?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: Int.MIN_VALUE
            val chargingStatus = when (status) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "充电中"
                BatteryManager.BATTERY_STATUS_FULL -> "已充满"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "未充电（放电中）"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "未充电"
                else -> "未知"
            }
            val temperatureText = if (temp > 0) String.format(Locale.CHINA, "%.1f℃", temp / 10.0) else "未知"
            val healthText = when (health) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "良好"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "过热"
                BatteryManager.BATTERY_HEALTH_DEAD -> "已损坏"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "过压"
                BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "故障"
                BatteryManager.BATTERY_HEALTH_COLD -> "过冷"
                else -> "未知"
            }
            val currentText = if (microAmp == Int.MIN_VALUE) "未知" else {
                val milliAmp = Math.abs(microAmp) / 1000.0
                // CURRENT_NOW 符号各厂商约定相反，充放电方向以系统 EXTRA_STATUS 为准
                if (status == BatteryManager.BATTERY_STATUS_CHARGING) {
                    "充电 +${"%.0f".format(milliAmp)} mA"
                } else {
                    "放电 -${"%.0f".format(milliAmp)} mA"
                }
            }
            BatterySnapshot(chargingStatus, currentText, healthText, temperatureText)
        } catch (_: Exception) {
            BatterySnapshot("未知", "未知", "未知", "未知")
        }
    }

    /**
     * 手机屏幕状态：亮屏 / 伪息屏 / 锁屏。
     * 优先级：伪息屏（黑屏蒙层悬浮窗在显示）> 锁屏（灭屏或锁屏界面）> 亮屏。
     */
    private fun deviceScreenStateText(context: Context): String {
        return try {
            when {
                MaskOverlayHelper.isShowing() -> "伪息屏"
                else -> {
                    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
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

    /** WiFi 连接状态 */
    private fun wifiStatusText(context: Context): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val net = cm?.activeNetwork
            val caps = if (net != null) cm.getNetworkCapabilities(net) else null
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) "已连接" else "未连接"
        } catch (_: Exception) {
            "未知"
        }
    }

    /** 蓝牙开关状态（不支持时返回“不支持”） */
    @Suppress("DEPRECATION")
    private fun bluetoothStatusText(context: Context): String {
        return try {
            val adapter = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(BluetoothManager::class.java)?.adapter
            } else {
                BluetoothAdapter.getDefaultAdapter()
            }
            if (adapter == null) "不支持" else if (adapter.isEnabled) "已开启" else "未开启"
        } catch (_: Exception) {
            "未知"
        }
    }

    /** 安卓系统版本，如 Android 14 (API 34) */
    private fun androidVersionText(): String {
        return "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    }

    /** 快捷获取电量字符串 */
    private fun batteryCap(): String {
        return try {
            val ctx = DailyTaskApplication.get()
            val mgr = ctx.getSystemService(android.content.Context.BATTERY_SERVICE) as? android.os.BatteryManager
            val cap = mgr?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (cap >= 0) "${cap}%" else "—"
        } catch (_: Exception) {
            "—"
        }
    }

    @JvmStatic
    fun buildRemotePunchHtml(timeoutSeconds: Int): String {
        val timeoutMin = timeoutSeconds / 60
        val timeoutText = if (timeoutMin >= 1) "${timeoutMin} 分钟" else "${timeoutSeconds} 秒"
        val body = buildString {
            append("<div style=\"text-align:center;padding:16px 0 10px;\">")
            append("<div style=\"font-size:40px;margin-bottom:8px;\">👆</div>")
            append("<div style=\"font-size:16px;font-weight:600;color:#4f6ef7;margin-bottom:4px;\">远程打卡指令已执行</div>")
            append("<div style=\"font-size:13px;color:#888;line-height:1.7;\">")
            append("正在打开目标应用，请在 <b style=\"color:#333;\">$timeoutText</b> 内完成打卡操作")
            append("</div>")
            append("</div>")
        }
        return pageShell("👆 远程打卡通知", body, compact = false)
    }

    /** 测试邮件（HTML 版，供「邮箱测试」按钮发送，确保渲染美观） */
    @JvmStatic
    fun buildTestEmailHtml(): String {
        val body = "<div style=\"text-align:center;padding:16px 0 10px;\">" +
                "<div style=\"font-size:40px;margin-bottom:8px;\">📧</div>" +
                "<div style=\"font-size:16px;font-weight:600;color:#4f6ef7;margin-bottom:4px;\">邮件通道测试</div>" +
                "<div style=\"font-size:13px;color:#888;line-height:1.7;\">这是一封测试邮件，不必关注。<br>若你能看到此卡片样式，说明 HTML 邮件发送正常。</div>" +
                "</div>"
        return pageShell("📧 邮箱测试", body, compact = false)
    }
}
