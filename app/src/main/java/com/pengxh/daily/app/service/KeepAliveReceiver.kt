package com.pengxh.daily.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.pengxh.daily.app.utils.Constant
import com.pengxh.kt.lite.utils.SaveKeyValues

/**
 * 后台保活接收器：
 * - 监听系统开机广播（BOOT_COMPLETED），在用户开启「开机自启/后台保活」时拉起前台服务；
 * - 监听精确闹钟广播（ACTION_RESURRECT），进程被系统杀死后兜底重启前台服务。
 *
 * 注：Android 12+ 对后台启动前台服务有约束，这里用 try/catch 兜底，
 * 若被系统拒绝则本次不启动（不会崩溃），等待下次闹钟或用户打开 App。
 */
class KeepAliveReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_RESURRECT = "com.pengxh.daily.action.RESURRECT"
        private const val ALARM_REQUEST_CODE = 1003
        private const val INTERVAL_MS = 15 * 60 * 1000L

        private fun resurrectIntent(context: Context): Intent =
            Intent(context, KeepAliveReceiver::class.java).apply { action = ACTION_RESURRECT }

        private fun pendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                ALARM_REQUEST_CODE,
                resurrectIntent(context),
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
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != ACTION_RESURRECT) return
        if (!SaveKeyValues.loadBoolean(Constant.BACKGROUND_KEEP_ALIVE_KEY, true)) return
        if (ForegroundRunningService.isRunning) return
        try {
            context.startForegroundService(Intent(context, ForegroundRunningService::class.java))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
