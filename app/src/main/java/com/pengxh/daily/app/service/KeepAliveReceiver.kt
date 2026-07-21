package com.pengxh.daily.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.pengxh.daily.app.utils.Constant
import com.pengxh.kt.lite.utils.SaveKeyValues
import java.util.Calendar

/**
 * 后台保活接收器：
 * - 监听系统开机广播（BOOT_COMPLETED），在用户开启「开机自启/后台保活」时拉起前台服务；
 * - 监听精确闹钟广播（ACTION_RESURRECT），进程被系统杀死后兜底重启前台服务；
 * - 监听每日重置闹钟（ACTION_RESET_TASK），在自定义重置点准时启动任务调度（覆盖 ACTION_TIME_TICK
 *   在 Doze/息屏下被延迟或丢弃导致到点未启动的问题）。
 *
 * 注：Android 12+ 对后台启动前台服务有约束，这里用 try/catch 兜底，
 * 若被系统拒绝则本次不启动（不会崩溃），等待下次闹钟或用户打开 App。
 */
class KeepAliveReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_RESURRECT = "com.pengxh.daily.action.RESURRECT"
        const val ACTION_RESET_TASK = "com.pengxh.daily.action.RESET_TASK"
        private const val ALARM_REQUEST_CODE = 1003
        private const val RESET_ALARM_REQUEST_CODE = 1004
        private const val INTERVAL_MS = 15 * 60 * 1000L

        private fun resurrectIntent(context: Context): Intent =
            Intent(context, KeepAliveReceiver::class.java).apply { action = ACTION_RESURRECT }

        private fun resetIntent(context: Context): Intent =
            Intent(context, KeepAliveReceiver::class.java).apply { action = ACTION_RESET_TASK }

        private fun pendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                ALARM_REQUEST_CODE,
                resurrectIntent(context),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        private fun resetPendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                RESET_ALARM_REQUEST_CODE,
                resetIntent(context),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        /** 调度一次精确闹钟（15 分钟后触发复活广播）；受后台保活开关控制 */
        fun schedule(context: Context) {
            if (!SaveKeyValues.loadBoolean(Constant.BACKGROUND_KEEP_ALIVE_KEY, true)) return
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            val pi = pendingIntent(context)
            val triggerAt = System.currentTimeMillis() + INTERVAL_MS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                // 无精确闹钟权限时退化为「允许空闲时」触发，仍能兜底
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        }

        /** 取消保活闹钟 */
        fun cancel(context: Context) {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            alarmManager.cancel(pendingIntent(context))
        }

        /**
         * 调度每日重置闹钟：精确到自定义重置点整点（或下一个重置点）。
         * 当「每日循环」关闭时，取消已设置的闹钟。
         */
        fun scheduleResetAlarm(context: Context, hour: Int = -1) {
            val autoRecycle = SaveKeyValues.loadBoolean(Constant.TASK_AUTO_RECYCLE_KEY, true)
            if (!autoRecycle) {
                cancelResetAlarm(context)
                return
            }
            val resetHour = if (hour in 0..23) hour else SaveKeyValues.loadInt(
                Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR
            )
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            val pi = resetPendingIntent(context)

            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, resetHour)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            // 若今天的重置点已过，则排到明天同一时间
            if (target.timeInMillis <= System.currentTimeMillis()) {
                target.add(Calendar.DATE, 1)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, target.timeInMillis, pi)
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, target.timeInMillis, pi)
            }
        }

        /** 取消每日重置闹钟 */
        fun cancelResetAlarm(context: Context) {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            alarmManager.cancel(resetPendingIntent(context))
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                if (SaveKeyValues.loadBoolean(Constant.BACKGROUND_KEEP_ALIVE_KEY, true)) {
                    tryStartForegroundService(context)
                }
                // 无论保活是否开启，只要每日循环开启就调度重置闹钟（开机后生效）
                scheduleResetAlarm(context)
            }
            ACTION_RESURRECT -> {
                if (!SaveKeyValues.loadBoolean(Constant.BACKGROUND_KEEP_ALIVE_KEY, true)) return
                if (ForegroundRunningService.isRunning) return
                tryStartForegroundService(context)
                // 复活后同时确保每日重置闹钟存在
                scheduleResetAlarm(context)
            }
            ACTION_RESET_TASK -> {
                if (!SaveKeyValues.loadBoolean(Constant.TASK_AUTO_RECYCLE_KEY, true)) return
                // 拉起/触发前台服务，由服务内部启动任务调度；比直接 startTask 更稳妥，
                // 因为服务可能尚未启动，需要它先初始化协程作用域。
                val serviceIntent = Intent(context, ForegroundRunningService::class.java).apply {
                    action = ACTION_RESET_TASK
                }
                try {
                    context.startForegroundService(serviceIntent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                // 立即安排明天的重置闹钟，避免今天任务结束后循环不再设置
                scheduleResetAlarm(context)
            }
        }
    }

    private fun tryStartForegroundService(context: Context) {
        try {
            context.startForegroundService(Intent(context, ForegroundRunningService::class.java))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
