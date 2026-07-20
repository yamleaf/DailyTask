package com.pengxh.daily.app.sqlite

import com.pengxh.daily.app.DailyTaskApplication
import com.pengxh.daily.app.sqlite.bean.DailyTaskBean
import com.pengxh.daily.app.sqlite.bean.NotificationBean
import java.time.LocalDate

object DatabaseWrapper {
    private val dailyTaskDao by lazy { DailyTaskApplication.get().dataBase.dailyTaskDao() }

    suspend fun loadAllTask(): MutableList<DailyTaskBean> {
        return dailyTaskDao.loadAll()
    }

    suspend fun isTaskTimeExist(time: String): Boolean {
        return dailyTaskDao.queryTaskByTime(time) > 0
    }

    suspend fun updateTask(bean: DailyTaskBean) {
        dailyTaskDao.update(bean)
    }

    suspend fun deleteTask(bean: DailyTaskBean) {
        dailyTaskDao.delete(bean)
    }

    suspend fun insert(bean: DailyTaskBean) {
        dailyTaskDao.insert(bean)
    }

    /*****************************************************************************************/
    private val noticeDao by lazy { DailyTaskApplication.get().dataBase.noticeDao() }

    suspend fun loadCurrentDayNotice(): MutableList<NotificationBean> {
        return noticeDao.loadCurrentDayNotice("${LocalDate.now()}")
    }

    /**
     * 返回 [start, endExclusive) 区间内含"考勤打卡"的通知所属日期集合，
     * 用于状态查询日历的"实际打卡"标记。
     */
    suspend fun loadPunchDatesBetween(start: LocalDate, endExclusive: LocalDate): Set<LocalDate> {
        val from = "${start} 00:00:00"
        val to = "${endExclusive} 00:00:00"
        val notices = noticeDao.loadBetween(from, to)
        return notices.filter { it.noticeMessage.contains("考勤打卡") }
            .mapNotNull { note ->
                runCatching { LocalDate.parse(note.postTime.take(10)) }.getOrNull()
            }.toSet()
    }

    suspend fun insertNotice(bean: NotificationBean) {
        noticeDao.insert(bean)
    }
}
