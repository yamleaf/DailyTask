package com.pengxh.daily.app.service

import com.pengxh.daily.app.R
import android.util.Log

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.app.NotificationChannel
import android.app.NotificationManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.FloatingWindowController
import com.pengxh.daily.app.utils.IdlePseudoMaskController
import com.pengxh.daily.app.utils.LogFileManager
import com.pengxh.daily.app.utils.MaskOverlayHelper
import com.pengxh.daily.app.utils.TaskScheduler
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
        /** 打卡前预热：只拉服务/悬浮窗/MQTT，不亮屏（省电） */
        const val ACTION_PUNCH_PREWARM = "com.pengxh.daily.action.PUNCH_PREWARM"
        /** 打卡到点：确保服务存活；若调度意图仍在但未运行则续跑 */
        const val ACTION_PUNCH_DUE = "com.pengxh.daily.action.PUNCH_DUE"
        private const val ALARM_REQUEST_CODE = 1003
        private const val RESET_ALARM_REQUEST_CODE = 1004
        private const val BATTERY_ALARM_REQUEST_CODE = 1005
        private const val PUNCH_PREWARM_REQUEST_CODE = 1006
        private const val PUNCH_DUE_REQUEST_CODE = 1007
        /** MQTT/FGS 配额失败等紧急救援，与 15 分钟心跳 PendingIntent 分离 */
        private const val RESCUE_ALARM_REQUEST_CODE = 1008
        private const val INTERVAL_MS = 15 * 60 * 1000L
        /** 到点前预热提前量：兼顾 MQTT 重连与安卓 15+ 服务拉起，又不过早耗电 */
        private const val PUNCH_PREWARM_BEFORE_MS = 90_000L
        private const val ENSURE_SERVICES_DEBOUNCE_MS = 8_000L
        /** FGS 配额耗尽后救援间隔：避免 30s 连撞 Android 15 后台 FGS 限额 */
        private const val FGS_QUOTA_BACKOFF_MS = 10 * 60 * 1000L

        @Volatile
        private var lastEnsureServicesAtMs = 0L

        private fun resurrectIntent(context: Context): Intent =
            Intent(context, KeepAliveReceiver::class.java).apply { action = ACTION_RESURRECT }

        private fun resetIntent(context: Context): Intent =
            Intent(context, KeepAliveReceiver::class.java).apply { action = ACTION_RESET_TASK }

        private fun batteryAlertIntent(context: Context): Intent =
            Intent(context, KeepAliveReceiver::class.java).apply { action = ACTION_BATTERY_ALERT }

        private fun punchPrewarmIntent(context: Context): Intent =
            Intent(context, KeepAliveReceiver::class.java).apply { action = ACTION_PUNCH_PREWARM }

        private fun punchDueIntent(context: Context): Intent =
            Intent(context, KeepAliveReceiver::class.java).apply { action = ACTION_PUNCH_DUE }

        private fun pendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                ALARM_REQUEST_CODE,
                resurrectIntent(context),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        private fun rescuePendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                RESCUE_ALARM_REQUEST_CODE,
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

        private fun punchPrewarmPendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                PUNCH_PREWARM_REQUEST_CODE,
                punchPrewarmIntent(context),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        private fun punchDuePendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                PUNCH_DUE_REQUEST_CODE,
                punchDueIntent(context),
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

        /**
         * 后台自启（保活复活）总开关：默认开启。
         * 关闭后，进程被杀不再尝试拉起任何服务（心跳闹钟 / 开机自启 / 任务重置 / MQTT 复活等），
         * 直到用户手动打开 App 或重新打开开关才恢复。
         */
        fun isKeepAliveEnabled(): Boolean =
            SaveKeyValues.loadBoolean(Constant.KEEP_ALIVE_ENABLED_KEY, true)

        /**
         * 「暂停使用」是否生效：暂停（后台自启关闭）时，被控端被杀后不再自启、心跳闹钟不调度，
         * 并应在切换瞬间主动停止所有服务实现彻底安静。
         */
        fun isPaused(): Boolean = !isKeepAliveEnabled()

        /**
         * 暂停所有服务：前台保活、MQTT 代理、悬浮窗、截屏服务全部停止；
         * 停止进行中的打卡任务；退出伪息屏；取消心跳 / 每日重置 / 电量预警 / MQTT 复活闹钟。
         * 通知监听与无障碍由系统托管，无法 stopService，但其业务入口均已 isPaused() 守卫。
         * 由设置页「暂停使用」开关触发。
         */
        fun pauseAllServices(context: Context) {
            val appCtx = context.applicationContext
            LogFileManager.action("暂停使用已开启：停止所有服务与闹钟")
            // 先停任务，避免前台服务销毁时打卡协程半截残留
            runCatching { TaskScheduler.stopTask() }
            runCatching { FloatingWindowController.stopFloatSession() }
            runCatching {
                appCtx.stopService(Intent(appCtx, ForegroundRunningService::class.java))
            }
            runCatching {
                appCtx.stopService(Intent(appCtx, MqttAgentService::class.java))
            }
            runCatching {
                appCtx.stopService(Intent(appCtx, FloatingWindowService::class.java))
            }
            runCatching {
                appCtx.stopService(Intent(appCtx, CaptureImageService::class.java))
            }
            runCatching { IdlePseudoMaskController.cancel() }
            // SYNC：卸蒙层但不拉前台，避免暂停瞬间抢回控制界面
            runCatching {
                MaskOverlayHelper.hide(appCtx, MaskOverlayHelper.HideReason.SYNC)
            }
            cancel(appCtx)
            cancelResetAlarm(appCtx)
            cancelBatteryAlert(appCtx)
            cancelPunchAlarms(appCtx)
            cancelRescueAlarm(appCtx)
            // 即使 MQTT 服务已销毁，也取消可能残留的复活 PendingIntent
            runCatching { MqttAgentService.cancelResurrectAlarms(appCtx) }
        }

        /**
         * 恢复所有服务：拉起前台保活（内部重建心跳 / 每日重置 / 电量预警并按开关拉 MQTT）、
         * 显式确保 MQTT 与闹钟已调度，并恢复悬浮窗。由「暂停使用」开关关闭时触发。
         */
        fun resumeAllServices(context: Context) {
            val appCtx = context.applicationContext
            LogFileManager.action("暂停使用已关闭：恢复所有服务")
            runCatching {
                appCtx.startForegroundService(
                    Intent(appCtx, ForegroundRunningService::class.java)
                )
            }
            // 与 FGS.onStartCommand 互补：FGS 启动失败时仍尽量恢复远程通道与闹钟
            startMqttAgentIfEnabled(appCtx)
            schedule(appCtx)
            scheduleResetAlarm(appCtx)
            scheduleBatteryAlert(appCtx)
            runCatching {
                if (Settings.canDrawOverlays(appCtx)) {
                    appCtx.startService(Intent(appCtx, FloatingWindowService::class.java))
                }
            }
            // 暂停时 stopTask 会停掉当日调度；恢复后按「每日循环」补启（等 FGS attach scope）
            if (SaveKeyValues.loadBoolean(Constant.TASK_AUTO_RECYCLE_KEY, true)) {
                Handler(Looper.getMainLooper()).postDelayed({
                    if (!isPaused() && !TaskScheduler.isRunning()) {
                        TaskScheduler.startTask()
                    }
                }, 1500L)
            }
        }

        /**
         * 调度一次精确闹钟（15 分钟后触发复活广播）；受「后台自启」总开关控制。
         * 开关关闭时不调度（并取消已排的闹钟），确保进程被杀后不会由心跳闹钟拉起。
         */
        fun schedule(context: Context) {
            if (!isKeepAliveEnabled()) {
                cancel(context)
                return
            }
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
         * 确保悬浮窗服务在跑（倒计时卡片 / 空闲小球）。
         * 安卓 15+ 后台拉起目标 App 依赖「可见悬浮窗」豁免；开机/远程启动调度时若未点开被控端，
         * 必须在此显式拉起，否则会出现无倒计时窗、无法跳转打卡 App。
         */
        fun ensureFloatingWindow(context: Context) {
            if (isPaused()) return
            val appCtx = context.applicationContext
            if (!Settings.canDrawOverlays(appCtx)) {
                Log.w(javaClass.simpleName, "无悬浮窗权限，跳过拉起 FloatingWindowService")
                return
            }
            if (FloatingWindowService.isRunning) return
            try {
                appCtx.startService(Intent(appCtx, FloatingWindowService::class.java))
            } catch (e: Exception) {
                Log.e(javaClass.simpleName, "拉起 FloatingWindowService 失败", e)
            }
        }

        /**
         * 合并拉起 FGS + MQTT + 悬浮窗；短时间去抖，降低 Android 15+ 后台 FGS 配额连撞。
         */
        fun ensureServicesAlive(context: Context, reason: String) {
            if (isPaused()) return
            val now = System.currentTimeMillis()
            if (now - lastEnsureServicesAtMs < ENSURE_SERVICES_DEBOUNCE_MS) {
                Log.d(javaClass.simpleName, "ensureServicesAlive 去抖跳过 reason=$reason")
                return
            }
            lastEnsureServicesAtMs = now
            LogFileManager.writeLog("保活拉起服务 reason=$reason")
            startMqttAgentIfEnabled(context)
            // 服务在跑但 MQTT 断开时，startMqttAgentIfEnabled 是空操作（isRunning() 直接 return），
            // 复活/预热/到点闹钟因此一直无法把断开的连接拉起来。这里补一个进程内重连（幂等、去重）。
            MqttAgentService.triggerReconnectIfNeeded()
            ensureFloatingWindow(context)
            if (!ForegroundRunningService.isRunning) {
                tryStartForegroundServiceStatic(context)
            }
        }

        /** 调度意图仍在但 TaskScheduler 未跑时补启（复活 / 到点闹钟） */
        fun tryResumeSchedulerIfWanted(context: Context, allowRetryStartFgs: Boolean = true) {
            if (isPaused()) return
            if (!SaveKeyValues.loadBoolean(Constant.SCHEDULER_WANTED_KEY, false)) return
            if (TaskScheduler.isRunning()) return
            if (!ForegroundRunningService.isRunning) {
                tryStartForegroundServiceStatic(context)
                // 仅再试一次：FGS 成功 onCreate 内还会再调；失败则等心跳/救援闹钟，避免死循环
                if (allowRetryStartFgs) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        tryResumeSchedulerIfWanted(context.applicationContext, allowRetryStartFgs = false)
                    }, 1200L)
                }
                return
            }
            Handler(Looper.getMainLooper()).post {
                if (isPaused() || TaskScheduler.isRunning()) return@post
                if (!SaveKeyValues.loadBoolean(Constant.SCHEDULER_WANTED_KEY, false)) return@post
                LogFileManager.action("保活续调度：自动 startTask")
                TaskScheduler.startTask()
            }
        }

        /**
         * 为下一场打卡排精确闹钟：到点执行 + 提前预热。
         * 协程 delay 仍保留（进程存活时省一次冷启动）；闹钟负责进程被杀后的兜底。
         */
        fun scheduleNextPunchAlarms(context: Context, punchAtMs: Long) {
            val appCtx = context.applicationContext
            cancelPunchAlarms(appCtx)
            if (!isKeepAliveEnabled() || isPaused()) return
            val now = System.currentTimeMillis()
            if (punchAtMs <= now) return
            setExactAlarm(appCtx, punchAtMs, punchDuePendingIntent(appCtx))
            val prewarmAt = punchAtMs - PUNCH_PREWARM_BEFORE_MS
            if (prewarmAt > now + 5_000L) {
                setExactAlarm(appCtx, prewarmAt, punchPrewarmPendingIntent(appCtx))
            }
            LogFileManager.writeLog(
                "已排打卡闹钟 due=${punchAtMs} prewarm=${if (prewarmAt > now + 5_000L) prewarmAt else 0}"
            )
        }

        fun cancelPunchAlarms(context: Context) {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            alarmManager.cancel(punchPrewarmPendingIntent(context))
            alarmManager.cancel(punchDuePendingIntent(context))
        }

        /**
         * MQTT 断线 / FGS 配额失败的救援闹钟（与 15 分钟心跳分离，避免互相覆盖）。
         * 目标统一走 ACTION_RESURRECT → ensureServicesAlive，减少直接 startForegroundService 连撞。
         */
        fun scheduleRescue(context: Context, delayMs: Long, reason: String) {
            if (!isKeepAliveEnabled() || isPaused()) return
            val triggerAt = System.currentTimeMillis() + delayMs.coerceAtLeast(5_000L)
            setExactAlarm(context.applicationContext, triggerAt, rescuePendingIntent(context))
            LogFileManager.writeLog("安排保活救援 delay=${delayMs}ms reason=$reason")
        }

        fun scheduleFgsQuotaBackoff(context: Context) {
            scheduleRescue(context, FGS_QUOTA_BACKOFF_MS, "fgs_quota")
        }

        fun cancelRescueAlarm(context: Context) {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            alarmManager.cancel(rescuePendingIntent(context))
        }

        private fun tryStartForegroundServiceStatic(context: Context) {
            try {
                context.startForegroundService(Intent(context, ForegroundRunningService::class.java))
            } catch (e: Exception) {
                Log.e(javaClass.simpleName, "KeepAliveReceiver 操作异常", e)
            }
        }

        /** 开机后由 FGS 处理：拉悬浮窗 + 可选自动调度 */
        const val ACTION_BOOT_SETUP = "com.pengxh.daily.action.BOOT_SETUP"

        /**
         * 调度每日重置闹钟：精确到自定义重置点整点（或下一个重置点）。
         * 当「每日循环」关闭时，取消已设置的闹钟。
         */
        fun scheduleResetAlarm(context: Context, hour: Int = -1) {
            if (!isKeepAliveEnabled()) {
                cancelResetAlarm(context)
                return
            }
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
            if (!isKeepAliveEnabled()) {
                cancelBatteryAlert(context)
                return
            }
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
        // 「后台自启」总开关：关闭后，进程被杀不再尝试拉起任何服务。
        // 开机广播 / 心跳复活 / 任务重置 / 电量预警闹钟全部跳过，直到用户手动打开 App 或重新打开开关。
        // 手动打开 App 由 MainActivity 直接启动 ForegroundRunningService，不受此开关限制。
        if (!isKeepAliveEnabled()) {
            Log.d(javaClass.simpleName, "后台自启已关闭，跳过拉起服务（action=${intent.action}）")
            return
        }
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                // 带 BOOT_SETUP：FGS 内补拉悬浮窗，并按开关尝试开机自动调度
                try {
                    context.startForegroundService(
                        Intent(context, ForegroundRunningService::class.java).apply {
                            action = ACTION_BOOT_SETUP
                        }
                    )
                } catch (e: Exception) {
                    Log.e(javaClass.simpleName, "KeepAliveReceiver 操作异常", e)
                    tryStartForegroundService(context)
                }
                // 开机自启：恢复远程控制 MQTT 代理（远程开关开启时），否则重启后控制端命令下发失效
                startMqttAgentIfEnabled(context)
                // 无论保活是否开启，只要每日循环开启就调度重置闹钟（开机后生效）
                scheduleResetAlarm(context)
                scheduleBatteryAlert(context)
                schedule(context)
            }
            ACTION_RESURRECT -> {
                // 无论本次是否拉起服务，都先续约下一次心跳闹钟，链条不中断。
                schedule(context)
                scheduleResetAlarm(context)
                ensureServicesAlive(context, "resurrect")
                tryResumeSchedulerIfWanted(context)
            }
            ACTION_PUNCH_PREWARM -> {
                // 到点前只预热服务，不亮屏，降低自然息屏下的耗电
                ensureServicesAlive(context, "punch_prewarm")
            }
            ACTION_PUNCH_DUE -> {
                ensureServicesAlive(context, "punch_due")
                tryResumeSchedulerIfWanted(context)
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
