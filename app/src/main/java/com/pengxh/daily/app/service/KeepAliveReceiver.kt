package com.pengxh.daily.app.service

import com.pengxh.daily.app.R
import android.util.Log

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.app.NotificationChannel
import android.app.NotificationManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
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
        const val ACTION_BATTERY_ALERT = "com.pengxh.daily.action.BATTERY_ALERT"
        private const val ALARM_REQUEST_CODE = 1003
        private const val RESET_ALARM_REQUEST_CODE = 1004
        private const val BATTERY_ALARM_REQUEST_CODE = 1005
        private const val INTERVAL_MS = 15 * 60 * 1000L

        private fun resurrectIntent(context: Context): Intent =
            Intent(context, KeepAliveReceiver::class.java).apply { action = ACTION_RESURRECT }

        private fun resetIntent(context: Context): Intent =
            Intent(context, KeepAliveReceiver::class.java).apply { action = ACTION_RESET_TASK }

        private fun batteryAlertIntent(context: Context): Intent =
            Intent(context, KeepAliveReceiver::class.java).apply { action = ACTION_BATTERY_ALERT }

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

        private fun batteryAlertPendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                BATTERY_ALARM_REQUEST_CODE,
                batteryAlertIntent(context),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        /**
         * 跨版本精确闹钟调度：
         * - Android 13+（API 33 起，含 14/15/16/17）：USE_EXACT_ALARM 不可被用户/系统撤销、无需弹窗授权，直接精确触发；
         * - Android 12/12L（API 31-32）：SCHEDULE_EXACT_ALARM 可能被撤销，有权限精确、无权限降级非精确并触发一次性引导；
         * - Android 8..11（API 26-30）：精确闹钟无需权限，直接精确触发。
         * 覆盖安卓 8 至 17 全区间。
         */
        private fun setExactAlarm(context: Context, triggerAt: Long, pi: PendingIntent) {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                    // 13+：USE_EXACT_ALARM 不可撤销，直接精确；try 兜底防止意外异常导致崩溃
                    try {
                        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                    } catch (e: SecurityException) {
                        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                    }
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                    // 12/12L：SCHEDULE_EXACT_ALARM 可被撤销
                    if (alarmManager.canScheduleExactAlarms()) {
                        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                    } else {
                        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                        notifyExactAlarmMissing(context)
                    }
                }
                else -> {
                    // 8..11：无需权限，直接精确
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                }
            }
        }

        /** 调度一次精确闹钟（15 分钟后触发复活广播）；后台自启功能常驻开启，不受开关控制 */
        fun schedule(context: Context) {
            val pi = pendingIntent(context)
            val triggerAt = System.currentTimeMillis() + INTERVAL_MS
            setExactAlarm(context, triggerAt, pi)
        }

        /** 取消保活闹钟 */
        fun cancel(context: Context) {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            alarmManager.cancel(pendingIntent(context))
        }

        /**
         * 远程控制恢复：远程开关开启时拉起 MQTT 代理前台服务。
         *
         * 关键缺口修复：手机重启后仅拉起 ForegroundRunningService 是不够的——远程指令、控制端命令
         * 下发全部依赖 MqttAgentService，而它只在 UI 开关 / 短信指令 / 覆盖安装 / 远程改配置时才被启动，
         * 其复活闹钟也无法跨重启存活。因此开机自启 / 保活心跳路径必须显式拉起它，否则重启后
         * 控制端永远联系不上被控端。
         *
         * 开关关闭时无需启动（MqttAgentService 的 onCreate / onStartCommand 自带开关守卫，会自行停止并撤通知）。
         * 用 isRunning() 防重复拉起（同一进程内 instance 判空）。
         */
        fun startMqttAgentIfEnabled(context: Context) {
            if (!SaveKeyValues.loadBoolean(Constant.MQTT_ENABLED_KEY, false)) return
            if (MqttAgentService.isRunning()) return
            try {
                context.startForegroundService(Intent(context, MqttAgentService::class.java))
            } catch (e: Exception) {
                Log.e(javaClass.simpleName, "KeepAliveReceiver 启动 MQTT 代理服务失败", e)
            }
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
            setExactAlarm(context, target.timeInMillis, pi)
        }

        /** 取消每日重置闹钟 */
        fun cancelResetAlarm(context: Context) {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            alarmManager.cancel(resetPendingIntent(context))
        }

        /**
         * 调度电量智能预警闹钟：精确到配置的预警时间（含分钟），触发 ACTION_BATTERY_ALERT。
         * 背景：ACTION_TIME_TICK 在 Doze/息屏下会被延迟或丢弃，导致到点不触发预警检测。
         * 用精确闹钟(RTC_WAKEUP)在预警时刻唤醒并执行 checkBatterySmartAlert，
         * 确保即使设备休眠也能在预警时间点准点检测并发邮件。
         * 智能预警开关关闭时取消闹钟。
         */
        fun scheduleBatteryAlert(context: Context) {
            if (!SaveKeyValues.loadBoolean(Constant.BATTERY_SMART_ALERT_ENABLED_KEY, false)) {
                cancelBatteryAlert(context)
                return
            }
            val warningMinute = SaveKeyValues.loadInt(
                Constant.BATTERY_WARNING_HOUR_KEY, 20 * 60
            ).coerceIn(0, 1439)
            val pi = batteryAlertPendingIntent(context)
            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, warningMinute / 60)
                set(Calendar.MINUTE, warningMinute % 60)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            // 若今天的预警时间已过，排到明天同一时间
            if (target.timeInMillis <= System.currentTimeMillis()) {
                target.add(Calendar.DATE, 1)
            }
            setExactAlarm(context, target.timeInMillis, pi)
        }

        /** 取消电量智能预警闹钟 */
        fun cancelBatteryAlert(context: Context) {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            alarmManager.cancel(batteryAlertPendingIntent(context))
        }

        private const val ALARM_GUIDE_NOTIFICATION_ID = 9001
        private const val ALARM_GUIDE_PREF = "alarm_guide"
        private const val ALARM_GUIDE_LAST_TS = "last_ts"
        private const val ALARM_GUIDE_INTERVAL_MS = 24L * 3600 * 1000L // 同一提示 24h 内只发一次

        /**
         * 精确闹钟权限缺失引导：从后台广播无法直接弹 Activity（受后台启动限制），
         * 改为发高优先级通知，点击跳转系统「精确闹钟」设置页授予权限。
         * 仅在 Android 12+（canScheduleExactAlarms 语义生效）触发；加 24h 去重避免刷屏。
         */
        private fun notifyExactAlarmMissing(context: Context) {
            // 仅在 Android 12/12L（API 31-32）触发：13+ 走 USE_EXACT_ALARM 不可撤销、8-11 无需权限
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            val prefs = context.getSharedPreferences(ALARM_GUIDE_PREF, Context.MODE_PRIVATE)
            val last = prefs.getLong(ALARM_GUIDE_LAST_TS, 0L)
            if (System.currentTimeMillis() - last < ALARM_GUIDE_INTERVAL_MS) return
            prefs.edit().putLong(ALARM_GUIDE_LAST_TS, System.currentTimeMillis()).apply()

            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            val channelId = "daily_task_alarm_guide"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(
                    channelId,
                    "打卡精度提醒",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "引导授予精确闹钟权限" }
                nm.createNotificationChannel(ch)
            }
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
            val pi = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val builder = NotificationCompat.Builder(context, channelId)
                .setContentTitle("打卡准时性可能受影响")
                .setContentText("“精确闹钟”权限已关闭，定时打卡可能延迟。点击前往设置开启。")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setAutoCancel(true)
                .setContentIntent(pi)
            nm.notify(ALARM_GUIDE_NOTIFICATION_ID, builder.build())
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                tryStartForegroundService(context)
                // 开机自启：恢复远程控制 MQTT 代理（远程开关开启时），否则重启后控制端命令下发失效
                startMqttAgentIfEnabled(context)
                // 无论保活是否开启，只要每日循环开启就调度重置闹钟（开机后生效）
                scheduleResetAlarm(context)
            }
            ACTION_RESURRECT -> {
                // 关键修复：无论本次是否拉起服务，都先续约下一次心跳闹钟。
                // 原实现在服务存活时直接 return、拉起后也只续重置闹钟，导致 15 分钟心跳只触发一次，
                // 进程被杀后无复活闹钟，只能苦等每日重置点。现改为每次心跳都自续约，链条永不中断。
                schedule(context)
                // 心跳复活：无论保活前台服务是否存活，都确保 MQTT 代理在运行，
                // 覆盖「进程被杀后 ForegroundRunningService 仍存活但 MQTT 已死」的情况
                startMqttAgentIfEnabled(context)
                if (ForegroundRunningService.isRunning) return
                tryStartForegroundService(context)
                // 复活后同时确保每日重置闹钟存在
                scheduleResetAlarm(context)
            }
            ACTION_RESET_TASK -> {
                if (!SaveKeyValues.loadBoolean(Constant.TASK_AUTO_RECYCLE_KEY, true)) return
                val serviceIntent = Intent(context, ForegroundRunningService::class.java).apply {
                    action = ACTION_RESET_TASK
                }
                try {
                    context.startForegroundService(serviceIntent)
                } catch (e: Exception) {
                    Log.e(javaClass.simpleName, "KeepAliveReceiver 操作异常", e)
                }
                startMqttAgentIfEnabled(context)
                scheduleResetAlarm(context)
            }
            ACTION_BATTERY_ALERT -> {
                val serviceIntent = Intent(context, ForegroundRunningService::class.java).apply {
                    action = ACTION_BATTERY_ALERT
                }
                try {
                    context.startForegroundService(serviceIntent)
                } catch (e: Exception) {
                    Log.e(javaClass.simpleName, "KeepAliveReceiver 启动前台服务失败", e)
                }
                // 排明天的预警闹钟
                scheduleBatteryAlert(context)
            }
        }
    }

    private fun tryStartForegroundService(context: Context) {
        try {
            context.startForegroundService(Intent(context, ForegroundRunningService::class.java))
        } catch (e: Exception) {
            Log.e(javaClass.simpleName, "KeepAliveReceiver 操作异常", e)
        }
    }
}
