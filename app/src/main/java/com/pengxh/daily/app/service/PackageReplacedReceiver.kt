package com.pengxh.daily.app.service

import android.util.Log

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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

        // 显式恢复 NotificationMonitorService 组件为 enabled：若覆盖安装前组件因 toggle 竞态
        // 停留在 disabled（系统通知访问列表只显示 enabled 组件，会导致更新后授权列表查不到本应用），
        // 覆盖安装会保留该 disabled 状态，此处强制恢复为 enabled 再走正常绑定流程。
        try {
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, NotificationMonitorService::class.java),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (e: Exception) {
            Log.e(javaClass.simpleName, "覆盖安装后恢复通知监听组件状态失败", e)
        }

        // 后台自启常驻开启：覆盖安装后无条件拉起前台服务
        try {
            context.startForegroundService(
                Intent(context, ForegroundRunningService::class.java)
            )
        } catch (e: Exception) {
            Log.e(javaClass.simpleName, "覆盖安装后启动前台服务失败", e)
        }

        // 覆盖安装后同时拉起 MQTT 代理服务：否则开关开启且配置有效时，远程控制会在更新后
        // 静默失效（收不到指令、不回 ack）直到用户再次手动进入「远程控制」页。
        // 仅当总开关开启时启动；initMqtt 内部会对 broker 为空等无效配置安全 no-op。
        if (SaveKeyValues.loadBoolean(Constant.MQTT_ENABLED_KEY, false)) {
            try {
                context.startForegroundService(Intent(context, MqttAgentService::class.java))
            } catch (e: Exception) {
                Log.e(javaClass.simpleName, "覆盖安装后启动 MQTT 代理服务失败", e)
            }
        }

        // 重建保活心跳闹钟与每日重置闹钟（更新后旧闹钟已被系统清除，需幂等重建）
        KeepAliveReceiver.schedule(context)
        KeepAliveReceiver.scheduleResetAlarm(context)
    }
}
