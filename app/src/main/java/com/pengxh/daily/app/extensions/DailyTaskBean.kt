package com.pengxh.daily.app.extensions

import com.github.gzuliyujiang.wheelpicker.entity.TimeEntity
import com.pengxh.daily.app.sqlite.bean.DailyTaskBean
import com.pengxh.daily.app.utils.Constant
import com.pengxh.kt.lite.extensions.appendZero
import com.pengxh.kt.lite.utils.SaveKeyValues
import java.time.LocalDate
import java.util.Calendar
import java.util.Date
import java.util.Random

private const val MAX_SECONDS_OF_DAY = 86399 // 一天最大秒数（23:59:59）

fun DailyTaskBean.convertToTimeEntity(): TimeEntity {
    // 直接拆 HH:mm[:ss]，避免 SimpleDateFormat 对个别格式解析失败后回落到「当前时间」
    val parts = time.trim().split(":")
    val hour = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23)
    val minute = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59)
    if (hour == null || minute == null) {
        return TimeEntity.target(Date())
    }
    val second = parts.getOrNull(2)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
    return runCatching {
        TimeEntity.target(hour, minute, second)
    }.getOrElse {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, second)
            set(Calendar.MILLISECOND, 0)
        }
        TimeEntity.target(cal.time)
    }
}

fun DailyTaskBean.resolveExecutionTime(): String {
    val totalSeconds = resolveExecutionSeconds()
    val hour = totalSeconds / 3600
    val minute = (totalSeconds % 3600) / 60
    val second = totalSeconds % 60
    return "${hour.appendZero()}:${minute.appendZero()}:${second.appendZero()}"
}

private fun DailyTaskBean.resolveExecutionSeconds(): Int {
    val needRandom = SaveKeyValues.loadBoolean(Constant.RANDOM_TIME_KEY, true)

    // time 为可空 DB 列且格式不保证（导入/历史数据可能是 "HH:mm" 或脏值），
    // 健壮解析避免 NPE / IndexOutOfBounds / NumberFormatException 打断调度协程
    val parts = time?.trim()?.split(":").orEmpty()
    val hour = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 0
    val minute = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
    val second = parts.getOrNull(2)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
    var totalSeconds = hour * 3600 + minute * 60 + second

    // 随机时间
    if (needRandom) {
        val minuteRange =
            SaveKeyValues.loadInt(Constant.TIME_RANGE_KEY, Constant.DEFAULT_TIME_RANGE)

        // 生成随机种子, 保证每天的随机时间是一致的
        val key = "${LocalDate.now()}|$id|$time|$minuteRange|${Constant.getInstallId()}"
        val seed = key.hashCode().toLong()
        val random = Random(seed)

        val seedMinute = if (minuteRange > 0) random.nextInt(minuteRange) else 0
        val seedSeconds = random.nextInt(60)
        totalSeconds += seedMinute * 60 + seedSeconds

        // 确保不超过当天23:59:59
        totalSeconds = minOf(totalSeconds, MAX_SECONDS_OF_DAY) // 第一次边界检查
    }

    return totalSeconds.coerceIn(0, MAX_SECONDS_OF_DAY) // 第二次边界检查
}
