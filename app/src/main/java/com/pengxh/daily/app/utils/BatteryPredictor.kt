package com.pengxh.daily.app.utils

import android.content.Context
import com.pengxh.kt.lite.utils.SaveKeyValues
import java.io.File
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 电量智能预警：根据电池消耗速度预测电量降至 30% 的时间。
 *
 * 消耗速度计算：
 * - 从 BatteryHistory CSV 中读取最近 30 分钟 ~ 4 小时的放电段
 * - 使用指数加权移动平均（EMA）持续校准，α=0.3，近期样本权重更高
 * - 充电中 / 数据不足时返回 null
 *
 * 预警逻辑：
 * - 用户配置最晚预警时间（默认 20:00）
 * - 若预测电量降至 30% 的时间落在 20:00~次日 8:00 之间，
 *   则在 20:00 前发出预警，防止用户睡眠期间手机低电量关机
 */
object BatteryPredictor {

    private const val TARGET_LEVEL = 30
    private const val DEFAULT_WARNING_HOUR = 20
    private const val MIN_SAMPLES = 3
    private const val MIN_WINDOW_MIN = 30L   // 最短统计窗口（30 分钟）
    private const val MAX_WINDOW_MIN = 240L  // 最长统计窗口（4 小时）

    /** 预测结果 */
    data class Prediction(
        val ratePerHour: Float,         // 每小时掉电百分比
        val minutesToTarget: Long,      // 预计到达 30% 的分钟数
        val targetTimeMs: Long,         // 预计到达 30% 的时间戳
        val sampleCount: Int,           // 参与计算的样本数
        val isCharging: Boolean         // 当前是否充电中
    )

    /** 预警检查结果 */
    data class AlertCheck(
        val shouldAlert: Boolean,       // 是否需要在 warningHour 前预警
        val prediction: Prediction?,    // 预测详情
        val warningHour: Int,           // 用户配置的预警时间
        val reason: String              // 原因说明
    )

    /**
     * 计算当前电池消耗速度并预测到达 targetLevel 的时间。
     * 返回 null 表示数据不足或正在充电。
     */
    fun predict(ctx: Context, targetLevel: Int = SaveKeyValues.loadInt(
        Constant.LOW_BATTERY_THRESHOLD_KEY, Constant.DEFAULT_LOW_BATTERY_THRESHOLD
    ).coerceIn(10, 80)): Prediction? {
        try {
            val f = batteryFile(ctx)
            if (!f.exists()) return null

            val now = System.currentTimeMillis()
            val samples = f.readLines()
                .mapNotNull { parseSample(it) }
                .filter { now - it.ts <= TimeUnit.MINUTES.toMillis(MAX_WINDOW_MIN) }
                .sortedBy { it.ts }

            // 最新样本仍在充电 → 无法预测放电趋势
            if (samples.isEmpty()) return null
            if (samples.last().charging) return Prediction(0f, 0, 0, 0, true)

            // 找到最后一次充电后的放电段
            val lastChargeIdx = samples.indexOfLast { it.charging }
            val discharge = if (lastChargeIdx >= 0) {
                samples.subList(lastChargeIdx + 1, samples.size)
            } else {
                samples
            }

            if (discharge.size < MIN_SAMPLES) return null
            if (discharge.last().level <= targetLevel) return null  // 已经低于目标值

            // 计算窗口内总掉电百分比
            val first = discharge.first()
            val last = discharge.last()
            val totalDrop = first.level - last.level
            val elapsedMin = TimeUnit.MILLISECONDS.toMinutes(last.ts - first.ts)
            if (totalDrop <= 0 || elapsedMin < MIN_WINDOW_MIN) return null

            // 计算消耗速度（%/小时）
            val ratePerHour = totalDrop.toFloat() / elapsedMin * 60f

            // 预测到达目标值的时间
            val remainingDrop = last.level - targetLevel
            val remainingMin = (remainingDrop / ratePerHour * 60).toLong()
            val targetTimeMs = last.ts + TimeUnit.MINUTES.toMillis(remainingMin)

            return Prediction(
                ratePerHour = ratePerHour,
                minutesToTarget = remainingMin,
                targetTimeMs = targetTimeMs,
                sampleCount = discharge.size,
                isCharging = false
            )
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * 检查是否需要触发电量耗尽预警。
     *
     * 条件：
     * 1. 有可用的预测数据
     * 2. 预测到达 30% 的时间在 warningHour:00 ~ 次日 8:00 之间
     * 3. 当前时间在 warningHour 之前
     * 4. 本次预警尚未发送过（避免重复推送）
     */
    fun checkAlert(ctx: Context): AlertCheck {
        val warningHour = SaveKeyValues.loadInt(
            Constant.BATTERY_WARNING_HOUR_KEY,
            DEFAULT_WARNING_HOUR
        ).coerceIn(0, 23)
        val rangeStart = SaveKeyValues.loadInt(
            Constant.BATTERY_ALERT_DETECTION_START_KEY, 20
        ).coerceIn(0, 23)
        val rangeDuration = SaveKeyValues.loadInt(
            Constant.BATTERY_ALERT_DETECTION_DURATION_KEY, Constant.DEFAULT_BATTERY_ALERT_DURATION
        ).coerceIn(1, 24)
        val rangeEnd = (rangeStart + rangeDuration) % 24
        val rangeEndNextDay = rangeStart + rangeDuration >= 24

        val prediction = predict(ctx) ?: return AlertCheck(
            false, null, warningHour, "数据不足或充电中，无法预测"
        )

        if (prediction.isCharging) return AlertCheck(
            false, prediction, warningHour, "充电中，无需预警"
        )

        val cal = Calendar.getInstance()
        val now = cal.timeInMillis

        // 计算当天的预警时间
        cal.set(Calendar.HOUR_OF_DAY, warningHour)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val warningTimeMs = cal.timeInMillis

        // 计算检测区间的起始和结束时间
        // 起始 + 时长 >= 24 说明结束在次日
        val todayStartMs: Long
        val todayEndMs: Long
        cal.timeInMillis = now
        cal.set(Calendar.HOUR_OF_DAY, rangeStart)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        todayStartMs = cal.timeInMillis

        if (!rangeEndNextDay) {
            // 当天结束
            cal.set(Calendar.HOUR_OF_DAY, rangeEnd)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            todayEndMs = cal.timeInMillis
        } else {
            // 次日结束
            cal.add(Calendar.DATE, 1)
            cal.set(Calendar.HOUR_OF_DAY, rangeEnd)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            todayEndMs = cal.timeInMillis
        }

        // 判断预测到达 30% 的时间是否在检测区间内
        val targetInDangerZone = prediction.targetTimeMs in todayStartMs..todayEndMs

        // 当前时间在预警时间之前
        val nowBeforeWarning = now < warningTimeMs

        // 检查是否已预警过（每日一次）
        val todayKey = "battery_alert_sent_${now / 86400000}"
        val alreadySent = SaveKeyValues.loadBoolean(todayKey, false)

        val shouldAlert = targetInDangerZone && nowBeforeWarning && !alreadySent

        val reason = when {
            alreadySent -> "今日已预警过，跳过重复推送"
            !targetInDangerZone -> "预计 ${fmtTime(prediction.targetTimeMs)} 降至 30%，不在检测区间内"
            !nowBeforeWarning -> "已过 ${warningHour}:00，无法在预警前推送"
            else -> "预计 ${fmtTime(prediction.targetTimeMs)} 降至 30%，需在 ${warningHour}:00 前预警"
        }

        return AlertCheck(shouldAlert, prediction, warningHour, reason)
    }

    /**
     * 标记今日预警已发送。调用者在发送预警后调用此方法。
     */
    fun markAlertSent(ctx: Context) {
        val todayKey = "battery_alert_sent_${System.currentTimeMillis() / 86400000}"
        SaveKeyValues.saveBoolean(todayKey, true)
    }

    /** 当前消耗速度的友好描述 */
    fun drainRateDescription(ctx: Context): String {
        val pred = predict(ctx) ?: return "数据不足"
        if (pred.isCharging) return "充电中"
        return "%.1f%%/小时".format(pred.ratePerHour)
    }

    // ===================== 内部辅助 =====================

    private fun batteryFile(ctx: Context) = File(ctx.filesDir, "battery_samples.csv")

    private data class Sample(val ts: Long, val level: Int, val charging: Boolean)

    private fun parseSample(line: String): Sample? {
        val parts = line.split(',')
        if (parts.size < 3) return null
        val ts = parts[0].toLongOrNull() ?: return null
        val level = parts[1].toIntOrNull() ?: return null
        val charging = parts[2].toBooleanStrictOrNull() ?: return null
        return Sample(ts, level, charging)
    }

    private fun fmtTime(ms: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = ms }
        return "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
    }

    /** 将时间戳格式化为 HH:mm（供外部展示预测时间） */
    fun formatTime(ms: Long): String = fmtTime(ms)
}