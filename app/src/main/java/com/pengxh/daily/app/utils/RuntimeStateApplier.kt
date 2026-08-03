package com.pengxh.daily.app.utils

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.pengxh.daily.app.DailyTaskApplication
import com.pengxh.daily.app.service.ForegroundRunningService
import com.pengxh.daily.app.service.KeepAliveReceiver
import com.pengxh.daily.app.service.MqttAgentService
import com.yample.mqttprotocol.MqttPacket
import com.yample.mqttprotocol.PacketValue
import com.yample.mqttprotocol.Protocol
import com.pengxh.kt.lite.utils.SaveKeyValues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object RuntimeStateApplier {
    private const val TAG = "RuntimeStateApplier"

    fun apply(packet: MqttPacket) {
        CoroutineScope(Dispatchers.IO).launch {
            // 1. 字段映射与落地
            val success = when (packet.f) {
                Protocol.FIELD_POWER_SAVE -> {
                    val v = (packet.v as? PacketValue.BooleanValue)?.b ?: return@launch
                    withContext(Dispatchers.Main) {
                        AppRuntimeConfig.setPowerSaveMode(v)
                    }
                    true
                }
                Protocol.FIELD_FORCE_PSEUDO_MASK -> {
                    val v = (packet.v as? PacketValue.BooleanValue)?.b ?: return@launch
                    withContext(Dispatchers.Main) {
                        AppRuntimeConfig.setForcePseudoMask(v)
                    }
                    true
                }
                Protocol.FIELD_PSEUDO_MASK_TIMEOUT -> {
                    val v = (packet.v as? PacketValue.IntValue)?.i ?: 60
                    SaveKeyValues.saveInt(Constant.IDLE_PSEUDO_MASK_TIMEOUT_KEY, v.coerceIn(10, 3600))
                    true
                }
                Protocol.FIELD_PSEUDO_MASK_NO_CLOCK -> {
                    val v = (packet.v as? PacketValue.BooleanValue)?.b ?: return@launch
                    SaveKeyValues.saveBoolean(Constant.PSEUDO_MASK_NO_CLOCK_KEY, v)
                    true
                }
                Protocol.FIELD_NOTIFICATION_TRANSFER -> {
                    val v = (packet.v as? PacketValue.BooleanValue)?.b ?: return@launch
                    SaveKeyValues.saveBoolean(Constant.NOTIFICATION_TRANSFER_KEY, v)
                    true
                }
                Protocol.FIELD_FEEDBACK_DISABLED -> {
                    val v = (packet.v as? PacketValue.BooleanValue)?.b ?: return@launch
                    SaveKeyValues.saveBoolean(Constant.FEEDBACK_NOTIFY_DISABLED_KEY, v)
                    true
                }
                Protocol.FIELD_SKIP_HOLIDAY -> {
                    val v = (packet.v as? PacketValue.BooleanValue)?.b ?: return@launch
                    SaveKeyValues.saveBoolean(Constant.SKIP_HOLIDAY_KEY, v)
                    true
                }
                Protocol.FIELD_TASK_AUTO_RECYCLE -> {
                    val v = (packet.v as? PacketValue.BooleanValue)?.b ?: return@launch
                    SaveKeyValues.saveBoolean(Constant.TASK_AUTO_RECYCLE_KEY, v)
                    true
                }
                Protocol.FIELD_RANDOM_TIME -> {
                    val v = (packet.v as? PacketValue.BooleanValue)?.b ?: return@launch
                    SaveKeyValues.saveBoolean(Constant.RANDOM_TIME_KEY, v)
                    true
                }
                Protocol.FIELD_GESTURE_DETECT -> {
                    val v = (packet.v as? PacketValue.BooleanValue)?.b ?: return@launch
                    SaveKeyValues.saveBoolean(Constant.GESTURE_DETECTOR_KEY, v)
                    true
                }
                Protocol.FIELD_BACK_TO_HOME -> {
                    val v = (packet.v as? PacketValue.BooleanValue)?.b ?: return@launch
                    SaveKeyValues.saveBoolean(Constant.BACK_TO_HOME_KEY, v)
                    true
                }
                Protocol.FIELD_KEEP_ALIVE -> {
                    val v = (packet.v as? PacketValue.BooleanValue)?.b ?: return@launch
                    applyKeepAlive(v)
                    true
                }
                Protocol.FIELD_RESET_HOUR -> {
                    val v = (packet.v as? PacketValue.IntValue)?.i ?: 0
                    SaveKeyValues.saveInt(Constant.RESET_TIME_KEY, v.coerceIn(0, 23))
                    true
                }
                Protocol.FIELD_TIME_RANGE -> {
                    val v = (packet.v as? PacketValue.IntValue)?.i ?: 5
                    SaveKeyValues.saveInt(Constant.TIME_RANGE_KEY, v.coerceIn(0, 60))
                    true
                }
                Protocol.FIELD_STAY_OVERTIME -> {
                    val v = (packet.v as? PacketValue.IntValue)?.i ?: 30
                    SaveKeyValues.saveInt(Constant.STAY_OVERTIME_KEY, v.coerceIn(0, 120))
                    true
                }
                "ax", "cx" -> false // 权限类开关，拒绝执行
                else -> false
            }

            // 2. 回执处理 (异步回传 Ack)
            val ackMsg = if (success) "SUCCESS" else "NEED_MANUAL"
            // 远程设置变更成功：置位刷新标志并发送应用内广播，让前台被控端界面即时刷新（无需二次进入）
            if (success) {
                ConfigImportSignal.notifyRemoteChanged(DailyTaskApplication.get())
            }
            MqttAgentService.publishAck(packet.rid, ackMsg)
            
            Log.d(TAG, "Apply success: ${packet.f}, Ack sent: $ackMsg")
        }
    }

    /** 后台保活：开启 = 拉起前台服务 + 注册保活闹钟；关闭 = 取消闹钟（前台服务仍驻留，不杀进程） */
    private fun applyKeepAlive(on: Boolean) {
        SaveKeyValues.saveBoolean(Constant.BACKGROUND_KEEP_ALIVE_KEY, on)
        val app = DailyTaskApplication.get()
        if (on) {
            app.startForegroundService(Intent(app, ForegroundRunningService::class.java))
            KeepAliveReceiver.schedule(app)
        } else {
            KeepAliveReceiver.cancel(app)
        }
    }
}
