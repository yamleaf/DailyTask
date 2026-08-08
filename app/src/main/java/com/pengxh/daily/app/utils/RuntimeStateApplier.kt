package com.pengxh.daily.app.utils

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.pengxh.daily.app.DailyTaskApplication
import com.pengxh.daily.app.service.MqttAgentService
import com.yample.mqttprotocol.MqttPacket
import com.yample.mqttprotocol.PacketValue
import com.yample.mqttprotocol.Protocol
import com.yample.mqttprotocol.SecretBox
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
                Protocol.FIELD_MSG_CHANNEL -> {
                    val v = (packet.v as? PacketValue.IntValue)?.i ?: 0
                    SaveKeyValues.saveInt(Constant.MSG_CHANNEL_KEY, v.coerceIn(0, 1))
                    true
                }
                Protocol.FIELD_MESSAGE_TITLE -> {
                    val v = (packet.v as? PacketValue.StringValue)?.s ?: return@launch
                    SaveKeyValues.saveString(Constant.MESSAGE_TITLE_KEY, v)
                    ConfigImportSignal.notifyRemoteChanged(DailyTaskApplication.get())
                    true
                }
                Protocol.FIELD_REMOTE_ENABLED -> {
                    val v = (packet.v as? PacketValue.BooleanValue)?.b ?: return@launch
                    SaveKeyValues.saveBoolean(Constant.MQTT_ENABLED_KEY, v)
                    // 开关变更后参照被控端主开关逻辑：开则校验账户并拉起 MQTT 前台服务，关则停止服务
                    val app = DailyTaskApplication.get()
                    if (v) {
                        if (isMqttConfigValid(app)) app.startForegroundService(Intent(app, MqttAgentService::class.java))
                    } else {
                        // 延后停服：先让本条指令的 Ack 发出去，避免控制端一直等回执
                        Handler(Looper.getMainLooper()).postDelayed({
                            app.stopService(Intent(app, MqttAgentService::class.java))
                        }, 1500L)
                    }
                    ConfigImportSignal.notifyRemoteChanged(app)
                    true
                }
                Protocol.FIELD_MSG_CONFIG -> {
                    val json = (packet.v as? PacketValue.StringValue)?.s ?: return@launch
                    applyMessageConfig(json)
                    true
                }
                Protocol.FIELD_LOW_BATTERY_THRESHOLD -> {
                    val v = (packet.v as? PacketValue.IntValue)?.i ?: return@launch
                    // 阈值范围 10~80%，变更后清零三段提醒标记，避免旧阈值下的已提醒状态污染新阈值
                    SaveKeyValues.saveInt(Constant.LOW_BATTERY_THRESHOLD_KEY, v.coerceIn(10, 80))
                    SaveKeyValues.saveBoolean(Constant.LOW_BATTERY_STAGE1_KEY, false)
                    SaveKeyValues.saveBoolean(Constant.LOW_BATTERY_STAGE2_KEY, false)
                    SaveKeyValues.saveBoolean(Constant.LOW_BATTERY_STAGE3_KEY, false)
                    ConfigImportSignal.notifyRemoteChanged(DailyTaskApplication.get())
                    true
                }
                Protocol.FIELD_BATTERY_SMART_ALERT -> {
                    val v = (packet.v as? PacketValue.BooleanValue)?.b ?: return@launch
                    SaveKeyValues.saveBoolean(Constant.BATTERY_SMART_ALERT_ENABLED_KEY, v)
                    ConfigImportSignal.notifyRemoteChanged(DailyTaskApplication.get())
                    true
                }
                Protocol.FIELD_BATTERY_WARNING_HOUR -> {
                    val v = (packet.v as? PacketValue.IntValue)?.i ?: return@launch
                    SaveKeyValues.saveInt(Constant.BATTERY_WARNING_HOUR_KEY, v.coerceIn(0, 23))
                    ConfigImportSignal.notifyRemoteChanged(DailyTaskApplication.get())
                    true
                }
                Protocol.FIELD_BATTERY_ALERT_STAGES -> {
                    val v = (packet.v as? PacketValue.IntValue)?.i ?: return@launch
                    SaveKeyValues.saveInt(Constant.BATTERY_ALERT_MAX_STAGES_KEY, v.coerceIn(0, 3))
                    ConfigImportSignal.notifyRemoteChanged(DailyTaskApplication.get())
                    true
                }
                Protocol.FIELD_BATTERY_ALERT_RANGE_START -> {
                    val v = (packet.v as? PacketValue.IntValue)?.i ?: return@launch
                    SaveKeyValues.saveInt(Constant.BATTERY_ALERT_DETECTION_START_KEY, v.coerceIn(0, 23))
                    ConfigImportSignal.notifyRemoteChanged(DailyTaskApplication.get())
                    true
                }
                Protocol.FIELD_BATTERY_ALERT_RANGE_DURATION -> {
                    val v = (packet.v as? PacketValue.IntValue)?.i ?: return@launch
                    SaveKeyValues.saveInt(Constant.BATTERY_ALERT_DETECTION_DURATION_KEY, v.coerceIn(1, 24))
                    ConfigImportSignal.notifyRemoteChanged(DailyTaskApplication.get())
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

    /** MQTT 账户是否完整（broker + 被控端用户名 + 被控端密码 均非空） */
    private fun isMqttConfigValid(ctx: android.content.Context): Boolean =
        SaveKeyValues.loadString(Constant.MQTT_BROKER_KEY, "").isNotBlank() &&
            SaveKeyValues.loadString(Constant.MQTT_USER_KEY, "").isNotBlank() &&
            MqttSecureConfig.loadPass().isNotBlank()

    /**
     * 消息渠道批量配置（控制端下发）：JSON {wxKey, emailOutbox, emailInbox, emailAuth, messageTitle}。
     * 敏感字段（企业微信 Key / 邮箱授权码）只在此处由控制端录入后下发落地，快照中始终以掩码呈现。
     */
    private fun applyMessageConfig(payload: String) {
        try {
            // 需求 8：控制端用会话密钥做了 AES-GCM 信封加密，这里先解封（旧版明文报文原样兼容）
            val session = SaveKeyValues.loadString(Constant.MQTT_SESSION_SECRET_KEY, "")
            val json = SecretBox.open(session, payload)
            val obj = JsonParser.parseString(json).asJsonObject
            val app = DailyTaskApplication.get()
            if (obj.has("wxKey")) {
                val k = obj.get("wxKey").asString
                if (k.isNotBlank()) SaveKeyValues.saveString(Constant.WX_WEB_HOOK_KEY, k)
            }
            if (obj.has("emailOutbox") || obj.has("emailInbox")) {
                // 合并写入：只覆盖控制端本次下发的字段，保留本机既有的其他键值
                val cache: JsonObject = ConfigStore.get().load(Constant.EMAIL_CONFIG_KEY)
                if (obj.has("emailOutbox")) {
                    val raw = obj.get("emailOutbox").asString.trim()
                    if (raw.isNotBlank()) {
                        // 与本机「消息渠道」页保持一致：纯 QQ 号自动补全 @qq.com
                        val outbox = if (raw.contains("@")) raw else "$raw@qq.com"
                        cache.addProperty("outbox", outbox)
                    }
                }
                if (obj.has("emailInbox")) {
                    val inbox = obj.get("emailInbox").asString.trim()
                    if (inbox.isNotBlank()) cache.addProperty("inbox", inbox)
                }
                ConfigStore.get().save(Constant.EMAIL_CONFIG_KEY, cache)
            }
            if (obj.has("emailAuth")) {
                val a = obj.get("emailAuth").asString
                if (a.isNotBlank()) EmailSecureConfig.saveAuthCode(a)
            }
            if (obj.has("messageTitle")) {
                val t = obj.get("messageTitle").asString
                if (t.isNotBlank()) SaveKeyValues.saveString(Constant.MESSAGE_TITLE_KEY, t)
            }
            ConfigImportSignal.notifyRemoteChanged(app)
        } catch (_: Exception) {
        }
    }
}
