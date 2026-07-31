package com.pengxh.daily.app.utils

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.pengxh.daily.app.service.MqttAgentService
import com.pengxh.daily.protocol.MqttPacket
import com.pengxh.daily.protocol.PacketValue
import com.pengxh.daily.protocol.Protocol
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
                "pm" -> {
                    val v = (packet.v as? PacketValue.BooleanValue)?.b ?: return@launch
                    withContext(Dispatchers.Main) {
                        AppRuntimeConfig.setForcePseudoMask(v)
                    }
                    true
                }
                "tm" -> {
                    val v = (packet.v as? PacketValue.IntValue)?.i ?: 60
                    SaveKeyValues.saveInt(Constant.IDLE_PSEUDO_MASK_TIMEOUT_KEY, v.coerceIn(10, 3600))
                    withContext(Dispatchers.Main) {
                        ConfigImportSignal.pendingSettingsRefresh = true
                    }
                    true
                }
                "ax", "cx" -> false // 权限类开关，拒绝执行
                else -> false
            }

            // 2. 回执处理 (异步回传 Ack)
            val ackMsg = if (success) "SUCCESS" else "NEED_MANUAL"
            MqttAgentService.publishAck(packet.rid, ackMsg)
            
            Log.d(TAG, "Apply success: ${packet.f}, Ack sent: $ackMsg")
        }
    }
}
