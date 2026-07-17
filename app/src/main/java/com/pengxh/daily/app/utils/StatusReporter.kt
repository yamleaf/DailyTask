package com.pengxh.daily.app.utils

import android.content.Context
import android.os.BatteryManager
import android.provider.Settings
import com.pengxh.daily.app.BuildConfig
import com.pengxh.daily.app.DailyTaskApplication
import com.pengxh.daily.app.extensions.formatTime
import com.pengxh.daily.app.extensions.notificationEnable
import com.pengxh.kt.lite.utils.SaveKeyValues
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 统一组装状态查询 / 任务通知正文。
 */
object StatusReporter {

    private val dateTimeFormat =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

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
        "DT#截屏" to "截取App 画面并回传"
    )

    suspend fun buildStatusReport(context: Context, listenerConnected: Boolean): String {
        val batteryManager = context.getSystemService(BatteryManager::class.java)
        val battery = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val channelType =
            SaveKeyValues.loadInt(Constant.MSG_CHANNEL_KEY, Constant.DEFAULT_INDEX)
        val autoRecycle =
            SaveKeyValues.loadBoolean(Constant.TASK_AUTO_RECYCLE_KEY, true)
        val resetHour =
            SaveKeyValues.loadInt(Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR)
        val plans = TaskScheduler.loadTodayTaskPlans()
        val now = System.currentTimeMillis()
        val pending = plans.filter { it.actualTimeMillis > now }

        return buildString {
            appendLine("====================")
            appendLine("  状态查询通知")
            appendLine("====================")
            appendLine()
            appendLine("【运行状态】")
            appendLine("· 任务调度：${TaskScheduler.describeRunningState()}")
            appendLine(
                "· 每日循环：${
                    if (autoRecycle) {
                        "开启（每天 ${"%02d".format(resetHour)}:00 重置时自动启动）"
                    } else {
                        "关闭（重置时不自动启动）"
                    }
                }"
            )
            appendLine("· 待机方式：伪息屏常亮（禁止系统自动灭屏）")
            appendLine(
                "· 强制伪息屏：${
                    if (AppRuntimeConfig.isForcePseudoMask()) {
                        "开启（离开本软件超过 60 秒会主动盖黑屏）"
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
            appendLine()
            appendTaskPlanSection(plans, pending.size, now)
            appendLine()
            appendLine("【服务状态】")
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
                    when (channelType) {
                        0 -> "QQ邮箱"
                        1 -> "企业微信"
                        else -> "未配置"
                    }
                }"
            )
            appendLine()
            appendLine("【设备信息】")
            appendLine("· 当前时间：${dateTimeFormat.format(Date())}")
            appendLine("· 当前电量：${if (battery >= 0) "$battery%" else "未知"}")
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
        val plans = TaskScheduler.loadTodayTaskPlans()
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
        val plans = TaskScheduler.loadTodayTaskPlans()
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
            appendLine("· 待机方式：伪息屏常亮")
        }
    }

    fun buildLowBatteryContent(battery: Int): String {
        return buildString {
            appendLine("====================")
            appendLine("  低电量提醒")
            appendLine("====================")
            appendLine()
            appendLine("· 当前电量：$battery%")
            appendLine("· 电量已低于 30%，请及时充电")
            appendLine("· 本次欠压区间内仅提醒一次（充电回升到 30% 以上后重新计数）")
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
            else -> "#f5f5f5" to "#595959"
        }
        return "<span style=\"display:inline-block;padding:2px 10px;border-radius:12px;font-size:12px;background:$bg;color:$fg;font-weight:500;\">$text</span>"
    }

    /** 值行: 标签 | 值 */
    private fun row(label: String, value: String): String {
        return "<tr><td style=\"padding:4px 0;font-size:13px;color:#888;white-space:nowrap;vertical-align:top;\">$label</td><td style=\"padding:4px 0 4px 14px;font-size:13px;color:#333;word-break:break-all;\">$value</td></tr>"
    }

    private fun htmlShell(title: String, body: String): String {
        val ts = dateTimeFormat.format(Date())
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
            append("<div style=\"background:#fff;padding:20px 24px 16px;\">")
            append(body)
            // Footer
            append("<div style=\"margin-top:24px;padding-top:12px;border-top:1px solid #f0f0f0;font-size:11px;color:#bbb;text-align:center;\">DailyTask v${BuildConfig.VERSION_NAME} · 自动发送</div>")
            append("</div>")
            // Card bottom radius
            append("<div style=\"height:8px;background:#fff;border-radius:0 0 12px 12px;margin-bottom:8px;\"></div>")
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

    @JvmStatic
    suspend fun buildStatusReportHtml(context: Context, listenerConnected: Boolean): String {
        val batteryManager = context.getSystemService(BatteryManager::class.java)
        val battery = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val channelType = SaveKeyValues.loadInt(Constant.MSG_CHANNEL_KEY, Constant.DEFAULT_INDEX)
        val autoRecycle = SaveKeyValues.loadBoolean(Constant.TASK_AUTO_RECYCLE_KEY, true)
        val resetHour = SaveKeyValues.loadInt(Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR)
        val plans = TaskScheduler.loadTodayTaskPlans()
        val now = System.currentTimeMillis()
        val pending = plans.filter { it.actualTimeMillis > now }

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
            val batColor = when { battery < 0 -> "#999"; battery < 30 -> "#f5222d"; battery < 60 -> "#fa8c16"; else -> "#52c41a" }
            append("<div style=\"flex:1;min-width:120px;background:#f9fafc;border-radius:8px;padding:10px 12px;text-align:center;\">")
            append("<div style=\"font-size:11px;color:#888;margin-bottom:4px;\">当前电量</div>")
            append("<div style=\"font-size:20px;font-weight:700;color:$batColor;\">${if (battery >= 0) "$battery%" else "—"}</div>")
            append("</div>")

            // 消息渠道
            append("<div style=\"flex:1;min-width:120px;background:#f9fafc;border-radius:8px;padding:10px 12px;text-align:center;\">")
            append("<div style=\"font-size:11px;color:#888;margin-bottom:4px;\">消息渠道</div>")
            append("<div style=\"font-size:13px;color:#333;font-weight:500;\">${if (channelType == 0) "QQ邮箱" else if (channelType == 1) "企业微信" else "未配置"}</div>")
            append("</div>")
            append("</div>")

            // 运行状态
            val cycleText = if (autoRecycle) {
                badge("开启", "ok") + " <span style=\"font-size:11px;color:#888;\">每天 ${"%02d".format(resetHour)}:00 自动启动</span>"
            } else {
                badge("关闭", "warn") + " <span style=\"font-size:11px;color:#888;\">需手动启动</span>"
            }
            val maskText = if (AppRuntimeConfig.isForcePseudoMask()) {
                "${badge("开启", "ok")} <span style=\"font-size:11px;color:#888;\">离开60s自动黑屏</span>"
            } else {
                badge("关闭", "warn")
            }
            val powerSaveText = if (AppRuntimeConfig.isPowerSaveMode()) badge("开启", "ok") else badge("关闭", "info")
            val holidayText = if (SaveKeyValues.loadBoolean(Constant.SKIP_HOLIDAY_KEY, true)) badge("开启", "ok") else badge("关闭", "info")
            val runDetail = if (running.startsWith("运行中")) running.substringAfter("｜", "") else "需手动或远程启动，每日循环仅在每日重置时生效"

            append(section("⚙️ 运行状态",
                row("任务调度", "$runBadge <span style=\"font-size:11px;color:#888;\">${if (runDetail.isNotEmpty()) runDetail else ""}</span>"),
                row("每日循环", cycleText),
                row("距下次重置", TaskScheduler.secondsUntilNextReset().formatTime()),
                row("省电模式", powerSaveText),
                row("跳过节假日", holidayText),
                row("强制伪息屏", maskText)
            ))

            // 今日任务
            val taskHeader = buildString {
                append("📋 今日任务")
                if (plans.isNotEmpty()) {
                    append(" <span style=\"font-weight:400;font-size:12px;color:#888;\">共 ${plans.size} 个 · 待执行 ${pending.size} 个</span>")
                }
            }
            append("<div style=\"margin-bottom:14px;\">")
            append("<div style=\"font-size:14px;font-weight:600;color:#1a1a1a;margin-bottom:8px;padding-bottom:6px;border-bottom:2px solid #4f6ef7;\">$taskHeader</div>")
            append(taskPlanTable(plans, now))
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
                row("悬浮窗权限", overlay),
                row("通知监听", notify),
                row("结果来源", screen)
            ))

            // 远程指令
            append(section("💬 远程指令",
                row("触发方式", "微信/QQ 发送 <code style=\"background:#f0f5ff;padding:1px 5px;border-radius:3px;font-size:12px;\">DT#指令</code>")
            ))
            append("<div style=\"margin-bottom:4px;\">")
            append(commandTable())
            append("</div>")
        }

        return htmlShell("📊 状态查询通知", body)
    }

    @JvmStatic
    suspend fun buildTaskExecutingContentHtml(
        index: Int, total: Int, plannedTime: String, actualTime: String
    ): String {
        val plans = TaskScheduler.loadTodayTaskPlans()
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
                row("省电模式", ps),
                row("待机方式", "伪息屏常亮")
            ))
        }

        return htmlShell("▶️ 任务执行通知", body)
    }

    @JvmStatic
    suspend fun buildTaskCompletedContentHtml(): String {
        val plans = TaskScheduler.loadTodayTaskPlans()
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

        return htmlShell("✅ 任务状态通知", body)
    }

    @JvmStatic
    fun buildSkipContentHtml(): String {
        val body = buildString {
            append("<div style=\"text-align:center;padding:16px 0 10px;\">")
            append("<div style=\"font-size:40px;margin-bottom:8px;\">🏖️</div>")
            append("<div style=\"font-size:16px;font-weight:600;color:#fa8c16;margin-bottom:4px;\">节假日/休息日</div>")
            append("<div style=\"font-size:13px;color:#888;\">任务已自动跳过，待机方式：伪息屏常亮</div>")
            append("</div>")
        }
        return htmlShell("⏭️ 任务跳过通知", body)
    }

    @JvmStatic
    fun buildLowBatteryContentHtml(battery: Int): String {
        val batColor = if (battery < 15) "#f5222d" else "#fa8c16"
        val pct = battery.coerceIn(0, 100)

        val body = buildString {
            append("<div style=\"text-align:center;padding:16px 0 10px;\">")
            append("<div style=\"font-size:40px;margin-bottom:8px;\">🔋</div>")
            append("<div style=\"font-size:28px;font-weight:700;color:$batColor;\">$battery%</div>")
            append("<div style=\"font-size:13px;color:#888;margin-top:2px;\">电量低于 30%</div>")
            append("</div>")

            // 电量条
            append("<div style=\"margin-bottom:10px;\">")
            append("<div style=\"background:#f0f0f0;border-radius:6px;height:10px;overflow:hidden;\">")
            append("<div style=\"background:$batColor;height:10px;width:${pct}%;border-radius:6px;\"></div>")
            append("</div>")
            append("</div>")

            append("<div style=\"font-size:13px;color:#555;line-height:1.6;\">")
            append("<p style=\"margin:0 0 6px;\">📌 请及时连接充电器，避免设备关机导致任务中断。</p>")
            append("<p style=\"margin:0;color:#888;font-size:11px;\">本次欠压区间仅提醒一次（充电回升到 30% 以上后重新计数）。</p>")
            append("</div>")
        }
        return htmlShell("🔋 低电量提醒", body)
    }

    // ======================== 轻量级通知 HTML ========================

    /** 居中大图标 + 标题的轻量 shell */
    private fun centerShell(title: String, body: String): String {
        val ts = dateTimeFormat.format(Date())
        return "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1.0,maximum-scale=1.0\"></head>" +
                "<body style=\"margin:0;padding:16px;background:#f0f2f5;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','PingFang SC','Microsoft YaHei',sans-serif;\">" +
                "<div style=\"max-width:600px;margin:0 auto;\">" +
                "<div style=\"background:linear-gradient(135deg,#4f6ef7,#6c5ce7);padding:22px 24px;border-radius:12px 12px 0 0;\">" +
                "<table cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\"><tr>" +
                "<td style=\"color:#fff;font-size:19px;font-weight:700;\">$title</td>" +
                "</tr><tr>" +
                "<td style=\"color:rgba(255,255,255,0.65);font-size:12px;padding-top:4px;\">$ts</td>" +
                "</tr></table></div>" +
                "<div style=\"background:#fff;padding:24px 20px;border-radius:0 0 12px 12px;\">" +
                body +
                "<div style=\"margin-top:24px;padding-top:12px;border-top:1px solid #f0f0f0;font-size:11px;color:#bbb;text-align:center;\">DailyTask v${BuildConfig.VERSION_NAME} · 自动发送</div>" +
                "</div></div></body></html>"
    }

    @JvmStatic
    fun buildClockInTextResultHtml(
        snippet: String,
        keyword: String,
        appName: String,
        extra: String? = null
    ): String {
        val body = buildString {
            append("<div style=\"text-align:center;padding:12px 0;\">")
            append("<div style=\"font-size:40px;margin-bottom:8px;\">✅</div>")
            append("<div style=\"font-size:16px;font-weight:600;color:#52c41a;margin-bottom:4px;\">打卡成功</div>")
            append("<div style=\"font-size:13px;color:#888;\">无障碍文本识别</div>")
            append("</div>")
            append("<table cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\" style=\"margin-bottom:8px;\">")
            append("<tr><td style=\"font-size:12px;color:#888;padding:3px 0;\">应用来源</td><td style=\"font-size:12px;color:#333;text-align:right;\">$appName</td></tr>")
            append("<tr><td style=\"font-size:12px;color:#888;padding:3px 0;\">匹配关键词</td><td style=\"font-size:12px;color:#333;text-align:right;\">$keyword</td></tr>")
            append("<tr><td style=\"font-size:12px;color:#888;padding:3px 0;vertical-align:top;\">识别摘要</td><td style=\"font-size:12px;color:#333;text-align:right;word-break:break-all;\">${snippet.ifBlank { "—" }}</td></tr>")
            if (!extra.isNullOrBlank()) {
                append("<tr><td style=\"font-size:12px;color:#888;padding:3px 0;\">备注</td><td style=\"font-size:12px;color:#cf1322;text-align:right;\">$extra</td></tr>")
            }
            append("</table>")
        }
        return centerShell("✅ 打卡结果通知", body)
    }

    @JvmStatic
    fun buildClockInResultHtml(title: String, notice: String): String {
        val battery = batteryCap()
        val body = "<div style=\"text-align:center;padding:12px 0;\">" +
                "<div style=\"font-size:40px;margin-bottom:8px;\">✅</div>" +
                "<div style=\"font-size:16px;font-weight:600;color:#52c41a;margin-bottom:4px;\">打卡成功</div>" +
                "</div>" +
                "<table cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\" style=\"margin-bottom:8px;\">" +
                "<tr><td style=\"font-size:12px;color:#888;padding:3px 0;\">通知标题</td><td style=\"font-size:12px;color:#333;text-align:right;\">${title.ifBlank { "—" }}</td></tr>" +
                "<tr><td style=\"font-size:12px;color:#888;padding:3px 0;\">通知内容</td><td style=\"font-size:12px;color:#333;text-align:right;\">${notice}</td></tr>" +
                "<tr><td style=\"font-size:12px;color:#888;padding:3px 0;\">设备电量</td><td style=\"font-size:12px;color:#333;text-align:right;\">$battery</td></tr>" +
                "</table>"
        return centerShell("✅ 打卡结果通知", body)
    }

    @JvmStatic
    fun buildStopTaskHtml(): String {
        val battery = batteryCap()
        val body = "<div style=\"text-align:center;padding:12px 0;\">" +
                "<div style=\"font-size:40px;margin-bottom:8px;\">⏹️</div>" +
                "<div style=\"font-size:16px;font-weight:600;color:#fa8c16;margin-bottom:4px;\">任务调度已停止</div>" +
                "<div style=\"font-size:13px;color:#888;\">请及时在下次任务前重新启动</div>" +
                "</div>" +
                "<table cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\" style=\"margin-top:8px;\">" +
                "<tr><td style=\"font-size:12px;color:#888;padding:3px 0;\">设备电量</td><td style=\"font-size:12px;color:#333;text-align:right;\">$battery</td></tr>" +
                "</table>"
        return centerShell("⏹️ 任务停止通知", body)
    }

    @JvmStatic
    fun buildCycleStatusHtml(enabled: Boolean): String {
        val (icon, color, label, tip) = if (enabled) {
            arrayOf("🔄", "#52c41a", "每日循环已开启", "每天重置时将自动启动调度")
        } else {
            arrayOf("⏸️", "#fa8c16", "每日循环已关闭", "需手动或远程启动调度")
        }
        val battery = batteryCap()
        val body = "<div style=\"text-align:center;padding:12px 0;\">" +
                "<div style=\"font-size:40px;margin-bottom:8px;\">$icon</div>" +
                "<div style=\"font-size:16px;font-weight:600;color:$color;margin-bottom:4px;\">$label</div>" +
                "<div style=\"font-size:13px;color:#888;\">$tip</div>" +
                "</div>" +
                "<table cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\" style=\"margin-top:8px;\">" +
                "<tr><td style=\"font-size:12px;color:#888;padding:3px 0;\">当前状态</td><td style=\"font-size:12px;color:#333;text-align:right;\">${if (enabled) "自动运行" else "手动控制"}</td></tr>" +
                "<tr><td style=\"font-size:12px;color:#888;padding:3px 0;\">设备电量</td><td style=\"font-size:12px;color:#333;text-align:right;\">$battery</td></tr>" +
                "</table>"
        return centerShell("${if (enabled) "🔄" else "⏸️"} 循环状态通知", body)
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
        return centerShell("📋 考勤记录", body)
    }

    @JvmStatic
    fun buildScreenshotResultHtml(success: Boolean, detail: String): String {
        val (icon, color, label) = if (success) {
            arrayOf("📸", "#52c41a", "截图成功")
        } else {
            arrayOf("❌", "#cf1322", "截图失败")
        }
        val body = "<div style=\"text-align:center;padding:12px 0;\">" +
                "<div style=\"font-size:40px;margin-bottom:8px;\">$icon</div>" +
                "<div style=\"font-size:16px;font-weight:600;color:$color;margin-bottom:4px;\">$label</div>" +
                "<div style=\"font-size:13px;color:#888;\">$detail</div>" +
                "</div>"
        return centerShell("📸 截屏通知", body)
    }

    @JvmStatic
    fun buildTimeoutAlertHtml(alertTitle: String, detail: String): String {
        val body = "<div style=\"text-align:center;padding:12px 0;\">" +
                "<div style=\"font-size:40px;margin-bottom:8px;\">⚠️</div>" +
                "<div style=\"font-size:16px;font-weight:600;color:#cf1322;margin-bottom:4px;\">$alertTitle</div>" +
                "<div style=\"font-size:13px;color:#888;\">$detail</div>" +
                "</div>"
        return centerShell("⚠️ 超时提醒", body)
    }

    @JvmStatic
    fun buildMemoryAlertHtml(): String {
        val battery = batteryCap()
        val body = "<div style=\"text-align:center;padding:12px 0;\">" +
                "<div style=\"font-size:40px;margin-bottom:8px;\">💾</div>" +
                "<div style=\"font-size:16px;font-weight:600;color:#cf1322;margin-bottom:4px;\">内存使用已超过 90%</div>" +
                "<div style=\"font-size:13px;color:#888;\">请关注设备运行情况，必要时重启释放内存</div>" +
                "</div>" +
                "<table cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\" style=\"margin-top:8px;\">" +
                "<tr><td style=\"font-size:12px;color:#888;padding:3px 0;\">设备电量</td><td style=\"font-size:12px;color:#333;text-align:right;\">$battery</td></tr>" +
                "</table>"
        return centerShell("💾 内存预警", body)
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
        return htmlShell("👆 远程打卡通知", body)
    }
}
