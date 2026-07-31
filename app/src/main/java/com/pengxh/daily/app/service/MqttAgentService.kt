package com.pengxh.daily.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.gson.GsonBuilder
import com.pengxh.daily.app.R
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.RuntimeStateApplier
import com.pengxh.daily.protocol.Hkdf
import com.pengxh.daily.protocol.MqttPacket
import com.pengxh.daily.protocol.MqttSigner
import com.pengxh.daily.protocol.PacketValue
import com.pengxh.daily.protocol.PacketValueAdapter
import com.pengxh.daily.protocol.Protocol
import com.pengxh.kt.lite.utils.SaveKeyValues
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.LinkedHashSet

class MqttAgentService : Service() {

    companion object {
        private var instance: MqttAgentService? = null

        fun isRunning(): Boolean = instance != null
        fun isConnected(): Boolean = instance?._connected ?: false
        fun isBound(): Boolean = SaveKeyValues.loadBoolean(Constant.IS_BOUND_KEY, false)

        /**
         * 连接状态变化回调。RemoteControlActivity 在 onResume 注册、onPause 注销，
         * 回调在 MQTT 后台线程触发，调用方需自行切主线程。
         */
        var stateListener: ((connected: Boolean) -> Unit)? = null

        /** 绑定状态变化回调（配对成功 / 解绑） */
        var bindingStateListener: ((bound: Boolean) -> Unit)? = null

        /** 由 RuntimeStateApplier 调用，回执远程指令执行结果 */
        fun publishAck(rid: String, result: String) {
            instance?.doPublishAck(rid, result)
        }

        /** 被控端主动强制解绑：仅清绑定态，保留 MQTT 配置 */
        fun unbind() {
            instance?.doUnbind(notifyController = true)
        }
    }

    private var _connected = false
    private var mqttClient: MqttClient? = null
    private var connectOptions: MqttConnectOptions? = null
    private var deviceId: String = ""
    private val channelId = "MqttAgentService"

    /** 防重放：缓存近期 rid（5 分钟窗口）+ 校验 ts 时钟偏斜 ±120s */
    private val recentRids = LinkedHashSet<String>()

    /** 带 PacketValue 适配器的 Gson：解决 sealed 类反序列化失败 */
    private val gson = GsonBuilder()
        .registerTypeAdapter(PacketValue::class.java, PacketValueAdapter)
        .create()

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForegroundNotification()
        // Paho connect 为阻塞调用（失败时最长等待 connectionTimeout=10s），必须在后台线程执行，
        // 否则在主线程执行会导致服务启动超时 + 界面 ANR
        Thread { initMqtt() }.start()
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Remote Control", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("远程控制服务运行中")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
        startForeground(1001, notification)
    }

    private fun initMqtt() {
        val broker = SaveKeyValues.loadString(Constant.MQTT_BROKER_KEY, "")
        if (broker.isBlank()) {
            // 未配置 Broker：服务常驻但不连接，待用户填写后由其重启本服务
            return
        }

        deviceId = SaveKeyValues.loadString(Constant.DEVICE_ID_KEY, "default")

        mqttClient = MqttClient(normalizeBroker(broker), "dev-$deviceId", MemoryPersistence())
        connectOptions = MqttConnectOptions().apply {
            isCleanSession = false
            userName = SaveKeyValues.loadString(Constant.MQTT_USER_KEY, "")
            password = SaveKeyValues.loadString(Constant.MQTT_PASS_KEY, "").toCharArray()
            connectionTimeout = 10
            keepAliveInterval = 60
            // LWT：断线后由 Broker 代为发布 retained offline，控制端据此感知掉线
            setWill(topicStatus(), "offline".toByteArray(), 1, true)
        }

        try {
            mqttClient?.connect(connectOptions)
            onConnected()
            mqttClient?.subscribe(topicCmd(), 1)
            mqttClient?.subscribe(topicPair(), 1)
            publishStatus("online")
            mqttClient?.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    onDisconnected()
                    reconnect()
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    message?.payload?.let { handleIncoming(String(it)) }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })
        } catch (e: Exception) {
            e.printStackTrace()
            onDisconnected()
        }
    }

    private fun handleIncoming(json: String) {
        try {
            val packet = gson.fromJson(json, MqttPacket::class.java) ?: return
            when (packet.c) {
                Protocol.CMD_UPDATE -> handleUpdate(packet)
                Protocol.CMD_SYNC -> doPublishAck(packet.rid, "ONLINE")
                Protocol.CMD_PAIR -> handlePair(packet)
                Protocol.CMD_UNBOUND -> doUnbind(notifyController = false)
                else -> { /* 其它命令暂忽略 */ }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun handleUpdate(packet: MqttPacket) {
        val session = SaveKeyValues.loadString(Constant.MQTT_SESSION_SECRET_KEY, "")
        if (session.isBlank()) {
            doPublishAck(packet.rid, "UNBOUND")
            return
        }
        if (!verifyWithSession(packet, session)) {
            doPublishAck(packet.rid, "SIGN_FAIL")
            return
        }
        if (!acceptRid(packet.rid, packet.ts)) {
            doPublishAck(packet.rid, "DUP_OR_STALE")
            return
        }
        RuntimeStateApplier.apply(packet)
    }

    /** 配对：校验 pairingToken（单次 / 60s）→ 派生 sessionSecret → 标记绑定 → 回 pair/accept */
    private fun handlePair(packet: MqttPacket) {
        val active = readActivePairingToken() ?: run {
            doPublishAck(packet.rid, "NO_PAIRING")
            return
        }
        val received = (packet.v as? PacketValue.StringValue)?.s ?: ""
        if (received != active.first) {
            doPublishAck(packet.rid, "TOKEN_MISMATCH")
            return
        }
        // 消费令牌（单次）
        SaveKeyValues.saveString(Constant.MQTT_PAIRING_TOKEN_KEY, "")
        SaveKeyValues.saveLong(Constant.MQTT_PAIRING_EXPIRY_KEY, 0L)

        val session = Hkdf.deriveHex(active.first, deviceId, Protocol.PAIRING_INFO, Protocol.SESSION_KEY_LEN)
        SaveKeyValues.saveString(Constant.MQTT_SESSION_SECRET_KEY, session)
        SaveKeyValues.saveBoolean(Constant.IS_BOUND_KEY, true)

        publishPairAccept()
        bindingStateListener?.invoke(true)
        "已与控制端完成配对".showToast()
    }

    /** 控制端主动解绑 / 被控端强制解绑：仅清绑定态，保留 MQTT 配置 */
    private fun doUnbind(notifyController: Boolean) {
        val wasBound = SaveKeyValues.loadBoolean(Constant.IS_BOUND_KEY, false)
        SaveKeyValues.saveBoolean(Constant.IS_BOUND_KEY, false)
        SaveKeyValues.saveString(Constant.MQTT_SESSION_SECRET_KEY, "")
        SaveKeyValues.saveString(Constant.MQTT_PAIRING_TOKEN_KEY, "")
        SaveKeyValues.saveLong(Constant.MQTT_PAIRING_EXPIRY_KEY, 0L)
        recentRids.clear()
        if (wasBound) {
            publishStatus("unbound") // retained，控制端订阅 status 可见
        }
        bindingStateListener?.invoke(false)
    }

    /** 按控制端约定重建签名串并比对：data = deviceId+ts+rid+field+type+vStr+cmd */
    private fun verifyWithSession(packet: MqttPacket, session: String): Boolean {
        val (type, vStr) = packetValueParts(packet.v)
        val expected = MqttSigner.sign(
            session, deviceId, packet.ts, packet.rid, packet.f, type, vStr, packet.c
        )
        return expected == packet.sign
    }

    private fun packetValueParts(v: PacketValue?): Pair<String, String> = when (v) {
        is PacketValue.BooleanValue -> "b" to v.b.toString()
        is PacketValue.IntValue -> "i" to v.i.toString()
        is PacketValue.StringValue -> "s" to v.s
        null -> "" to ""
    }

    /** 读取处于有效期内的配对令牌；过期/不存在返回 null */
    private fun readActivePairingToken(): Pair<String, Long>? {
        val token = SaveKeyValues.loadString(Constant.MQTT_PAIRING_TOKEN_KEY, "")
        val expiry = SaveKeyValues.loadLong(Constant.MQTT_PAIRING_EXPIRY_KEY, 0L)
        if (token.isBlank()) return null
        if (System.currentTimeMillis() > expiry) {
            SaveKeyValues.saveString(Constant.MQTT_PAIRING_TOKEN_KEY, "")
            SaveKeyValues.saveLong(Constant.MQTT_PAIRING_EXPIRY_KEY, 0L)
            return null
        }
        return token to expiry
    }

    /** rid 去重 + ts 时钟偏斜窗口校验；返回 true 表示接受 */
    private fun acceptRid(rid: String, ts: Long): Boolean {
        val now = System.currentTimeMillis()
        if (kotlin.math.abs(now - ts) > 120_000L) return false // ±120s 偏斜
        if (recentRids.contains(rid)) return false
        recentRids.add(rid)
        // 简单修剪：超过 500 条或最旧超过 5 分钟则整体清空（LinkedHashSet 保序）
        if (recentRids.size > 500) recentRids.clear()
        return true
    }

    private fun doPublishAck(rid: String, result: String) {
        val client = mqttClient ?: return
        if (!client.isConnected) return
        val ts = System.currentTimeMillis()
        val session = SaveKeyValues.loadString(Constant.MQTT_SESSION_SECRET_KEY, "")
        val sign = if (session.isNotBlank())
            MqttSigner.sign(session, deviceId, ts, rid, "", "", result, Protocol.CMD_ACK)
        else ""
        val packet = MqttPacket(
            c = Protocol.CMD_ACK,
            f = "",
            v = PacketValue.StringValue(result),
            rid = rid,
            ts = ts,
            sign = sign
        )
        client.publish(
            topicAck(),
            MqttMessage(gson.toJson(packet).toByteArray()).apply { qos = 1 }
        )
    }

    private fun publishStatus(state: String) {
        mqttClient?.publish(
            topicStatus(),
            MqttMessage(state.toByteArray()).apply { qos = 1; isRetained = true }
        )
    }

    private fun publishPairAccept() {
        val client = mqttClient ?: return
        if (!client.isConnected) return
        val packet = MqttPacket(
            c = Protocol.CMD_PAIR_ACCEPT,
            f = "",
            v = PacketValue.StringValue("OK"),
            rid = "",
            ts = System.currentTimeMillis(),
            sign = ""
        )
        client.publish(
            topicPairAccept(),
            MqttMessage(gson.toJson(packet).toByteArray()).apply { qos = 1 }
        )
    }

    private fun onConnected() {
        _connected = true
        stateListener?.invoke(true)
    }

    private fun onDisconnected() {
        _connected = false
        stateListener?.invoke(false)
    }

    private fun reconnect() {
        try {
            if (mqttClient?.isConnected == true) return
            mqttClient?.connect(connectOptions)
            onConnected()
            mqttClient?.subscribe(topicCmd(), 1)
            mqttClient?.subscribe(topicPair(), 1)
        } catch (e: Exception) {
            e.printStackTrace()
            onDisconnected()
        }
    }

    // ===== 主题构建（最终模型 dt/{id}/...）=====
    private fun topicCmd() = "${Protocol.TOPIC_PREFIX}/$deviceId/cmd"
    private fun topicStatus() = "${Protocol.TOPIC_PREFIX}/$deviceId/status"
    private fun topicAck() = "${Protocol.TOPIC_PREFIX}/$deviceId/ack"
    private fun topicPair() = "${Protocol.TOPIC_PREFIX}/$deviceId/pair"
    private fun topicPairAccept() = "${Protocol.TOPIC_PREFIX}/$deviceId/pair/accept"

    /**
     * 归一化 Broker 地址：用户常直接粘贴 EMQX 的连接地址（如 xxxx.emqxsl.com:8883）
     * 而 Paho 要求带协议前缀。缺前缀时按端口判断：8883/8884/8886 走 ssl://，其余走 tcp://。
     */
    private fun normalizeBroker(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("ssl://") || trimmed.startsWith("tcp://") || trimmed.startsWith("ws://") || trimmed.startsWith("wss://")) {
            return trimmed
        }
        val port = trimmed.substringAfterLast(':', trimmed).toIntOrNull()
        val scheme = if (port != null && (port == 8883 || port == 8884 || port == 8886)) "ssl://" else "tcp://"
        return "$scheme$trimmed"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        stateListener = null
        bindingStateListener = null
        try {
            mqttClient?.disconnect()
            mqttClient?.close()
        } catch (_: Exception) {
        }
    }

    // 轻量 toast 辅助（MQTT 回调线程无 Looper，须切主线程）
    private fun String.showToast() {
        val msg = this
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(this@MqttAgentService, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
