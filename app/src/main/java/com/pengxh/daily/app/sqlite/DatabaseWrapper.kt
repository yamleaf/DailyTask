package com.pengxh.daily.app.sqlite

import com.pengxh.daily.app.DailyTaskApplication
import com.pengxh.daily.app.sqlite.bean.DailyTaskBean
import com.pengxh.daily.app.sqlite.bean.NotificationBean
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object DatabaseWrapper {
    private val dailyTaskDao by lazy { DailyTaskApplication.get().dataBase.dailyTaskDao() }

    /**
     * 底层 Dao 为 Java 阻塞式 API（非 Room suspend），必须在 IO 线程执行。
     * 否则从 Main（FGS serviceScope / lifecycleScope）调用会抛
     * “Cannot access database on the main thread”，开机自动调度会被误判为任务列表为空。
     */
    suspend fun loadAllTask(): MutableList<DailyTaskBean> = withContext(Dispatchers.IO) {
        dailyTaskDao.loadAll()
    }

    suspend fun isTaskTimeExist(time: String): Boolean = withContext(Dispatchers.IO) {
        dailyTaskDao.queryTaskByTime(time) > 0
    }

    suspend fun updateTask(bean: DailyTaskBean) = withContext(Dispatchers.IO) {
        dailyTaskDao.update(bean)
    }

    suspend fun deleteTask(bean: DailyTaskBean) = withContext(Dispatchers.IO) {
        dailyTaskDao.delete(bean)
    }

    suspend fun insert(bean: DailyTaskBean) = withContext(Dispatchers.IO) {
        dailyTaskDao.insert(bean)
    }

    /*****************************************************************************************/
    private val noticeDao by lazy { DailyTaskApplication.get().dataBase.noticeDao() }

    suspend fun loadCurrentDayNotice(): MutableList<NotificationBean> = withContext(Dispatchers.IO) {
        noticeDao.loadCurrentDayNotice("${LocalDate.now()}")
    }

    /**
     * [start, endExclusive) 区间内打卡结果所属日期集合，用于状态查询日历：
     * - successDates：含"考勤打卡成功"的通知（已确认打卡）
     * - timeoutDates：含"考勤打卡超时"的通知（超时未确认，需人工核对截图）
     * 两者均含"考勤打卡"子串，故远程"考勤记录"指令（过滤 contains("考勤打卡")）仍可查到。
     */
    data class PunchDateResult(
        val successDates: Set<LocalDate>,
        val timeoutDates: Set<LocalDate>
    )

    suspend fun loadPunchResults(start: LocalDate, endExclusive: LocalDate): PunchDateResult {
        val from = "${start} 00:00:00"
        val to = "${endExclusive} 00:00:00"
        val notices = withContext(Dispatchers.IO) { noticeDao.loadBetween(from, to) }
        // noticeMessage/postTime 为可空列（历史数据可能为 NULL），全部空安全访问
        val successDates = notices.filter { it.noticeMessage?.contains("考勤打卡成功") == true }
            .mapNotNull { note -> runCatching { LocalDate.parse(note.postTime?.take(10) ?: return@mapNotNull null) }.getOrNull() }
            .toSet()
        val timeoutDates = notices.filter { it.noticeMessage?.contains("考勤打卡超时") == true }
            .mapNotNull { note -> runCatching { LocalDate.parse(note.postTime?.take(10) ?: return@mapNotNull null) }.getOrNull() }
            .toSet()
        return PunchDateResult(successDates, timeoutDates)
    }

    /**
     * 查询指定日期最近一次「考勤打卡成功/超时」通知的完整时间（"yyyy-MM-dd HH:mm:ss"），无则返回 null。
     * 用于快照「最近打卡」展示具体时间点（成功 OR 超时均纳入；超时场景下仍能展示具体时间点，否则会回退到仅日期）。
     * 历史日期也按精确到时分秒展示，不再仅显示日期（与日历页 history 列表口径一致）。
     */
    suspend fun loadLatestPunchTime(day: LocalDate): String? = withContext(Dispatchers.IO) {
        val notices = noticeDao.loadBetween("${day} 00:00:00", "${day.plusDays(1)} 00:00:00")
        notices.asSequence()
            .filter { it.noticeMessage?.contains("考勤打卡成功") == true || it.noticeMessage?.contains("考勤打卡超时") == true }
            .mapNotNull { it.postTime?.takeIf { p -> p.startsWith("$day ") } }
            .maxOrNull()
    }

    suspend fun loadNoticeRange(start: LocalDate, endExclusive: LocalDate): MutableList<NotificationBean> = withContext(Dispatchers.IO) {
        noticeDao.loadBetween("${start} 00:00:00", "${endExclusive} 00:00:00")
    }

    /**
     * 写入一条通知记录。
     * 注意：底层 noticeDao.insert 是非 suspend 的阻塞式 @Insert，必须在 IO 线程执行，
     * 否则当调用方位于主线程（如 TaskScheduler.scope=Dispatchers.Main、
     * MainActivity.lifecycleScope=Dispatchers.Main）时，Room 会因默认禁止主线程访问数据库
     * 抛出 IllegalStateException，被上层 catch 静默吞掉，导致"考勤打卡成功/超时"记录丢失，
     * 状态查询日历无法正确标记当天打卡状态（曾导致超时后日历仍显示"计划打卡"）。
     * 此处统一切到 Dispatchers.IO，确保任何调用方线程下都能安全落库。
     */
    suspend fun insertNotice(bean: NotificationBean) {
        withContext(Dispatchers.IO) {
            noticeDao.insert(bean)
        }
    }
}
