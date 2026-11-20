package com.pengxh.daily.app.utils

import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.kt.lite.utils.SaveKeyValues
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 打卡日历：按日推导"这一天打/不打，以及为什么"的状态。
 *
 * 从 [StatusReporter] 抽离出的纯计算逻辑，供 HTML 报告与原生日历视图共用，
 * 避免日状态判定散落在报告生成器里。
 */
object PunchCalendar {

    /** 日历中每一天的分类，精确表达「为什么这天打/不打」 */
    enum class DayKind {
        PUNCHED,      // 已打卡（已确认）
        TIMEOUT,      // 打卡超时（未确认成功，需人工核对截图）
        MISSED,       // 未计划（已过计划工作日但无打卡记录）
        SCHEDULED,    // 计划打卡（未来工作日）
        MAKEUP,       // 调休补班（周末但需上班，会打卡）
        HOLIDAY,      // 法定节假日，自动跳过
        REST,         // 自定义休息日，自动跳过
        NO_TASK,      // 未配置任务，无打卡计划
        NOT_RUNNING   // 今天调度未启动，不会自动打卡（仅用于"今天"）
    }

    data class PunchCalendarData(
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
    suspend fun compute(pastDays: Int, futureDays: Int): PunchCalendarData {
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

    /**
     * 推导某一天的打卡状态。
     *
     * 历史按已发生事实展示；今天看调度是否在跑；未来看每日循环是否开启
     * （关闭则不会重启，未来不自动打卡）。
     */
    fun dayKindOf(date: LocalDate, cal: PunchCalendarData): DayKind {
        val punched = date in cal.punched
        val timedOut = date in cal.timeoutDates && !punched
        val isToday = date == cal.today
        val isPast = date.isBefore(cal.today)
        val holiday = cal.skipHolidayEnabled && date in cal.holidays
        val makeup = date in cal.makeupWorkdays
        val customRest = date.dayOfWeek !in cal.customWorkdays
        val willSchedule = when {
            isPast -> false
            isToday -> TaskScheduler.isRunning()
            else -> cal.autoRecycle
        }

        return when {
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
    }
}
