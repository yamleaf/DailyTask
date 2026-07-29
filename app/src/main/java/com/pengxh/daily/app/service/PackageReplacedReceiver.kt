package com.pengxh.daily.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pengxh.daily.app.utils.Constant
import com.pengxh.kt.lite.utils.SaveKeyValues

/**
 * 覆盖安装（MY_PACKAGE_REPLACED）接收器。
 * 系统更新应用后会解除通知监听绑定，且不会自动重启应用进程，导致远程指令接收失效
 * （表现为：更新后远程指令静默失败，直到重启手机才恢复）。
 * 这里在更新完成后主动拉起前台服务（使进程存活），系统随后自动重绑 NotificationListenerService；
 * 同时重建保活心跳闹钟与每日重置闹钟（覆盖安装会清除旧版设置的闹钟）。
 */
class PackageReplacedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        if (SaveKeyValues.loadBoolean(Constant.BACKGROUND_KEEP_ALIVE_KEY, true)) {
            try {
                context.startForegroundService(
                    Intent(context, ForegroundRunningService::class.java)
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 重建保活心跳闹钟与每日重置闹钟（更新后旧闹钟已被系统清除，需幂等重建）
        KeepAliveReceiver.schedule(context)
        KeepAliveReceiver.scheduleResetAlarm(context)
    }
}
