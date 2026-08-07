package com.pengxh.daily.app.service

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.gson.GsonBuilder
import com.pengxh.daily.app.R
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.ui.MainActivity
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.MqttSecureConfig
import com.pengxh.daily.app.utils.ConfigImportSignal
import com.yample.mqttprotocol.MqttQuota
import com.pengxh.daily.app.utils.RemoteSnapshot
import com.pengxh.daily.app.utils.RuntimeStateApplier
import com.pengxh.daily.app.service.NotificationMonitorService
import com.pengxh.daily.app.utils.TaskScheduler
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.yample.mqttprotocol.BrokerUtils
import com.yample.mqttprotocol.Hkdf
import com.yample.mqttprotocol.MqttPacket
import com.yample.mqttprotocol.MqttSigner
import com.yample.mqttprotocol.PacketValue
import com.yample.mqttprotocol.PacketValueAdapter
import com.yample.mqttprotocol.Protocol
import com.pengxh.kt.lite.utils.SaveKeyValues
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.ArrayDeque
import java.util.UUID

class MqttAgentService : Service() {

    companion object {
        private const val TAG = "MqttAgentService"
        private var instance: MqttAgentService? = null

        /** 增量推送：状态变化时通知控制端刷新（打卡完成 / 任务启停）。publishPush 已做未配对/未连接守卫。 */
        fun pushTaskIncrement() {
            instance?.publishPush(setOf("tasks", "calendar", "statuses"))
        }

        /** 测量到 broker 的 RTT（ms），以 QoS1 PUBACK 到达时刻计时；未连接时回调 -1 */
        fun measureRtt(callback: (Long) -> Unit) {
            instance?.doMeasureRtt(callback) ?: callback(-1)
        }

        fun isRunning(): Boolean = instance != null
        fun isConnected(): Boolean = instance?._connected ?: false
        fun isBound(): Boolean = SaveKeyValues.loadBoolean(Constant.IS_BOUND_KEY, false)

        /**
         * 连接状态变化回调。MainActivity 在 onResume 注册、onPause 注销，
         * 回调在 MQTT 后台线程触发，调用方需自行切主线程。
         */
        var stateListener: ((connected: Boolean) -> Unit)? = null

        /** 绑定状态变化回调（配对成功 / 解绑） */
        var bindingStateListener: ((bound: Boolean) -> Unit)? = null

        /** 解绑原因，供远程控制页（RemoteControlFragment）区分 UI 文案：
         * ""=从未绑定 / "remote"=被控制端移除 / "force"=本机强制解绑
         */
        var lastUnbindReason: String = ""

        /** 由 RuntimeStateApplier 调用，回执远程指令执行结果 */
        fun publishAck(rid: String, result: String) {
            instance?.doPublishAck(rid, result)
        }

        /**
         * 低电量分段告警 / 开始充电通知 → 经 MQTT 推送给控制端。
         * 守卫在 publishAlertInternal 内（未连接 / 未配对不推送）。
         */
        fun publishAlert(json: String) {
            instance?.publishAlertInternal(json)
        }

        /** 被控端主动强制解绑：仅清绑定态，保留 MQTT 配置 */
        fun unbind() {
            instance?.doUnbind(notifyController = true)
        }

        /** 由 Activity 在注册监听后调用，把当前连接/绑定状态推给 UI，避免错过实时回调 */
        fun notifyState() {
            instance?.pushCurrentState()
        }

        /** B2：下次复活倒计时目标时刻（ms），供 UI 显示「Xs 后重试」 */
        @Volatile var nextReconnectAtMs: Long = 0L

        /** B2：立即重连 —— 取消复活闹钟并调用实例 reconnect() */
        fun reconnectNow() {
            nextReconnectAtMs = 0L
            instance?.let { svc ->
                svc.cancelResurrect()
                // 关键修复：reconnect() 内的 mqttClient.connect() 是 Paho 阻塞式网络调用，
                // 若在 UI 线程执行（按钮点击 / 闹钟 BroadcastReceiver 均为主线程）会卡死主线程 → ANR。
                // 派发到服务的 IO 协程作用域执行，彻底离开主线程。
                svc.scope.launch { svc.reconnect() }
            }
        }
    }

    @Volatile private var _connected = false
    @Volatile private var _bound = SaveKeyValues.loadBoolean(Constant.IS_BOUND_KEY, false)
    private var mqttClient: MqttClient? = null
    private var connectOptions: MqttConnectOptions? = null
    private var deviceId: String = ""
    private val channelId = "MqttAgentService"
    /** B1：保存前台通知 Builder，便于连接/绑定态变化时就地更新文案 */
    private var notificationBuilder: NotificationCompat.Builder? = null

    /** 后台协程作用域：快照构建 / 任务落库等耗时操作在此执行，避免阻塞 MQTT 回调线程 */
    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    /** A4：防重放定长环形队列（最多 200 条）+ 校验 ts 时钟偏斜 ±120s，避免长运行内存无界增长 */
    private val recentRids = ArrayDeque<String>()

    /** A2：复活闹钟退避计数。0 表示刚连上/无失败；随连续失败递增，连接成功后重置为 0 */
    private var resurrectAttempt = 0

    /** 被控端本地数据变更广播接收器：收到后增量推送受影响区块给控制端（不再由控制端轮询全量） */
    private val remoteChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // A3：本地数据变更后失效快照缓存，确保后续控制端查询拿到最新数据
            RemoteSnapshot.invalidateCache()
            // 设置/任务等变更（来自远程指令或本地操作）：推送受影响区块（不含较重的 calendar/history）
            publishPush(setOf("tasks", "settings", "statuses", "runtime"))
        }
    }

    /** 带 PacketValue 适配器的 Gson：解决 sealed 类反序列化失败 */
    private val gson = GsonBuilder()
        .registerTypeAdapter(PacketValue::class.java, PacketValueAdapter)
        .create()

    override fun onCreate() {
        super.onCreate()
        instance = this
        // A1：开关守卫 —— 关闭 MQTT 开关时直接不启动连接，保证「关闭零耗电」。
        // 否则进程被系统回收后重启，onCreate 会重新 initMqtt 绕过开关继续连接，承诺破防。
        if (!SaveKeyValues.loadBoolean(Constant.MQTT_ENABLED_KEY, false)) {
            Log.d(TAG, "MQTT 开关关闭，服务不启动（关闭零耗电）")
            try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) { }
            stopSelf()
            return
        }
        // 注册本地数据变更广播：设置/任务经 RuntimeStateApplier / applyTaskChange 改动后会发此广播，
        // 由此统一触发增量推送，避免控制端轮询全量快照。
        try {
            ContextCompat.registerReceiver(
                this, remoteChangedReceiver,
                IntentFilter(ConfigImportSignal.ACTION_REMOTE_CONFIG_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (_: Exception) {
            registerReceiver(remoteChangedReceiver, IntentFilter(ConfigImportSignal.ACTION_REMOTE_CONFIG_CHANGED))
        }
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
        // B1：点击通知直达远程控制页，并设为常驻（setOngoing）避免被划掉
        val contentIntent = PendingIntent.getActivity(
            this, 1002, Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_REMOTE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setContentTitle("远程控制已关闭")
            .setContentText("点击进入远程控制页")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(contentIntent)
            .setOngoing(true)
        startForeground(1001, notificationBuilder!!.build())
        updateNotification()
    }

    /** B1：按连接/绑定态刷新前台通知文案（已连接·已绑定 / 已连接·待配对 / 连接中 / 已关闭） */
    private fun updateNotification() {
        val nb = notificationBuilder ?: return
        val enabled = SaveKeyValues.loadBoolean(Constant.MQTT_ENABLED_KEY, false)
        val broker = SaveKeyValues.loadString(Constant.MQTT_BROKER_KEY, "")
        val (title, text) = when {
            !enabled -> "远程控制已关闭" to "点击进入远程控制页重新开启"
            _connected && _bound -> "远程控制已连接" to "已绑定 · $broker"
            _connected -> "已连接，待配对" to "等待控制端扫码 · $broker"
            else -> "远程控制连接中" to "正在连接 $broker"
        }
        nb.setContentTitle(title).setContentText(text)
        try {
            getSystemService(NotificationManager::class.java).notify(1001, nb.build())
        } catch (_: Exception) {
        }
    }

    private fun initMqtt() {
        val broker = SaveKeyValues.loadString(Constant.MQTT_BROKER_KEY, "")
        if (broker.isBlank()) {
            // 未配置 Broker：服务常驻但不连接，待用户填写后由其重启本服务
            return
        }

        deviceId = SaveKeyValues.loadString(Constant.DEVICE_ID_KEY, "default")

        mqttClient = MqttClient(BrokerUtils.normalizeBroker(broker), "dev-$deviceId", MemoryPersistence())
        connectOptions = MqttConnectOptions().apply {
            isCleanSession = false
            userName = SaveKeyValues.loadString(Constant.MQTT_USER_KEY, "")
            password = MqttSecureConfig.loadPass().toCharArray()
            connectionTimeout = 10
            // 自动重连：网络切换（WiFi↔移动）或瞬时掉线后由 Paho 自带退避重连，无需我们持有 WakeLock
            isAutomaticReconnect = true
            // 心跳间隔 4 分钟：在不持有 WakeLock 的前提下维持长连接，显著减少心跳包以省电
            keepAliveInterval = 240
            // LWT：断线后由 Broker 代为发布 retained offline，控制端据此感知掉线
            setWill(topicStatus(), "offline".toByteArray(), 1, true)
        }

        // 关键：回调必须在 connect() 之前注册，否则连接成功后到达的消息/断线事件可能丢失或不被分发。
        // 用 MqttCallbackExtended：connectComplete 在【首次连接】与【Paho 自动重连】时都会触发。
        // 把“订阅命令主题 + 发布 online + 纠正连接态”集中到这里，可保证 broker 重启/掉线恢复后
        // 仍能收到指令（否则自动重连不会重新订阅，导致收不到 cmd、不回 ack、查询超时），
        // 同时纠正“已连但 UI 显示未连接”的问题（自动重连不会回调 onConnected）。
        mqttClient?.setCallback(object : MqttCallbackExtended {
            override fun connectionLost(cause: Throwable?) {
                onDisconnected()
                // Paho 自动重连在息屏/Doze 下可能失效，额外安排复活闹钟兜底（带退避）
                scheduleResurrectWithBackoff()
            }

            override fun messageArrived(topic: String?, message: MqttMessage?) {
                message?.payload?.let {
                    MqttQuota.add(this@MqttAgentService, 0, 1)
                    handleIncoming(String(it))
                }
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {}

            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                Log.d(TAG, "MQTT ${if (reconnect) "自动重连" else "首次连接"}成功，重新订阅命令主题并发布 online")
                onConnected()
                // 以下操作失败不应把「已连接」状态重置为 false，否则 TCP 连接仍在但 UI 永久显示未连接
                try {
                    // 被控端只接收 cmd / pair，resp 是自己发布给控制端的，不需要订阅
                    val okCmd = subscribeWithDiag(topicCmd())
                    val okPair = subscribeWithDiag(topicPair())
                    if (!okCmd || !okPair) {
                        "MQTT 订阅被 broker 拒绝：请检查 EMQX 中 DEV 账户（${SaveKeyValues.loadString(Constant.MQTT_USER_KEY, "")}）的 ACL 是否允许 ${Protocol.TOPIC_PREFIX}/$deviceId/#".showToast()
                    }
                    publishStatus("online")
                } catch (e: Exception) {
                    e.printStackTrace()
                    "MQTT 订阅/状态上报失败：${e.message}".showToast()
                }
            }
        })

        try {
            mqttClient?.connect(connectOptions)
            onConnected()
            // 首次连接也同步订阅一次（connectComplete 在自动重连时同样会订阅，二者幂等）：
            // 保证即便个别 Paho 版本首连不回调 connectComplete，命令主题也不会遗漏。
            try {
                val okCmd = subscribeWithDiag(topicCmd())
                val okPair = subscribeWithDiag(topicPair())
                if (!okCmd || !okPair) {
                    "MQTT 订阅被 broker 拒绝：请检查 EMQX 中 DEV 账户（${SaveKeyValues.loadString(Constant.MQTT_USER_KEY, "")}）的 ACL 是否允许 ${Protocol.TOPIC_PREFIX}/$deviceId/#".showToast()
                }
                publishStatus("online")
            } catch (e: Exception) {
                e.printStackTrace()
                "MQTT 订阅/状态上报失败：${e.message}".showToast()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            onDisconnected()
            "MQTT 连接失败：${e.message}".showToast()
            // 连接失败也安排复活闹钟（如网络未就绪时），带退避避免失败态持续耗电
            scheduleResurrectWithBackoff()
        }
    }

    private fun handleIncoming(json: String) {
        try {
            val packet = gson.fromJson(json, MqttPacket::class.java) ?: return
            when (packet.c) {
                Protocol.CMD_UPDATE -> handleUpdate(packet)
                Protocol.CMD_QUERY -> handleQuery(packet)
                Protocol.CMD_TASK -> handleTask(packet)
                Protocol.CMD_ACTION -> handleAction(packet)
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
        val session = MqttSecureConfig.loadSession()
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

    /** 查询快照：校验会话 → 构建设备/运行/设置/任务 JSON → 经 resp 主题回执 */
    private fun handleQuery(packet: MqttPacket) {
        Log.d(TAG, "收到快照查询 rid=${packet.rid} ts=${packet.ts}")
        val session = MqttSecureConfig.loadSession()
        if (session.isBlank()) {
            doPublishAck(packet.rid, "UNBOUND")
            return
        }
        if (!verifyWithSession(packet, session)) {
            Log.w(TAG, "快照查询签名校验失败 rid=${packet.rid}")
            doPublishAck(packet.rid, "SIGN_FAIL")
            return
        }
        if (!acceptRid(packet.rid, packet.ts)) {
            Log.w(TAG, "快照查询 rid 重复或时间戳过期 rid=${packet.rid}")
            doPublishAck(packet.rid, "DUP_OR_STALE")
            return
        }
        scope.launch {
            try {
                val json = RemoteSnapshot.buildJson(this@MqttAgentService)
                val ok = publishResp(packet.rid, "snapshot", json)
                if (!ok) {
                    Log.w(TAG, "快照 resp 发布失败（连接断开），回执 SNAPSHOT_FAIL rid=${packet.rid}")
                    doPublishAck(packet.rid, "SNAPSHOT_FAIL")
                } else {
                    Log.d(TAG, "快照已返回 rid=${packet.rid} size=${json.length}")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Log.e(TAG, "快照构建/发送异常 rid=${packet.rid}", e)
                doPublishAck(packet.rid, "SNAPSHOT_FAIL")
            }
        }
    }

    /**
     * 远程任务增删改：action ∈ {add,update,delete}，time 为 HH:mm:ss。
     * 落库后若调度正在运行则重启调度以立即生效。
     */
    private fun handleTask(packet: MqttPacket) {
        val session = MqttSecureConfig.loadSession()
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
        val payload = (packet.v as? PacketValue.StringValue)?.s ?: ""
        scope.launch {
            val result = runCatching {
                val json = com.google.gson.JsonParser.parseString(payload).asJsonObject
                val action = json.get("action")?.asString ?: ""
                val time = json.get("time")?.asString ?: ""
                val oldTime = json.get("oldTime")?.asString
                val name = json.get("name")?.asString
                applyTaskChange(action, time, oldTime, name)
            }.fold(
                onSuccess = { it },
                onFailure = { "TASK_FAIL:${it.message}" }
            )
            doPublishAck(packet.rid, result)
        }
    }

    /**
     * 一次性动作命令：f ∈ {start,stop,punch,attendance,screenshot}。
     * start/stop 直接调用 TaskScheduler（不经 MainActivity，无人在场也可靠）；
     * punch/attendance/screenshot 复用 NotificationMonitorService 内既有流程（结果经消息渠道回传）。
     */
    private fun handleAction(packet: MqttPacket) {
        val session = MqttSecureConfig.loadSession()
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
        val action = packet.f
        scope.launch {
            // 动作分支（如 TaskScheduler.stopTask 在无可停任务时）可能抛异常；必须用 runCatching 包裹，
            // 否则协程崩溃会跳过 doPublishAck，导致控制端收不到回执、长期超时。任何异常都回 ACK_ACTION_FAIL。
            val result = runCatching {
                when (action) {
                    Protocol.ACTION_START -> {
                        TaskScheduler.startTask()
                        "SUCCESS"
                    }
                    Protocol.ACTION_STOP -> {
                        TaskScheduler.stopTask()
                        "SUCCESS"
                    }
                    Protocol.ACTION_PUNCH -> {
                        val nms = NotificationMonitorService.instance
                        if (nms != null) {
                            nms.performRemotePunch()
                            "SUCCESS"
                        } else "SERVICE_UNAVAILABLE"
                    }
                    Protocol.ACTION_ATTENDANCE -> {
                        val nms = NotificationMonitorService.instance
                        if (nms != null) {
                            nms.performAttendanceExport()
                            "SUCCESS"
                        } else "SERVICE_UNAVAILABLE"
                    }
                    Protocol.ACTION_SCREENSHOT -> {
                        val nms = NotificationMonitorService.instance
                        if (nms != null) {
                            nms.performScreenshot()
                            "SUCCESS"
                        } else "SERVICE_UNAVAILABLE"
                    }
                    else -> "UNKNOWN_ACTION"
                }
            }.fold(
                onSuccess = { it },
                onFailure = { "ACTION_FAIL:${it.message}" }
            )
            doPublishAck(packet.rid, result)
            // 数据变更增量推送：让控制端本地缓存即时更新（不再依赖轮询）
            if (result == "SUCCESS") {
                // 动作改变了运行/任务/日历/历史等状态 → 失效全量快照缓存。
                // 若不失效，控制端收到 ACK 后触发的 forceRefreshSnapshot（走 buildJson、命中 30s TTL 缓存）
                // 会返回动作执行前的旧快照，把刚由增量推送更新的正确状态覆盖回旧值 → 卡片蓝变灰。
                // 只有 buildDelta（增量推送）实时无缓存；buildJson（全量查询）复用缓存，必须先失效。
                RemoteSnapshot.invalidateCache()
                when (action) {
                    Protocol.ACTION_PUNCH -> {
                        publishPush(setOf("statuses", "runtime"))
                        // 打卡结果经消息渠道异步回传，延迟再推一次日历/历史以反映完成态
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            publishPush(setOf("calendar", "history", "statuses", "runtime"))
                        }, 5000L)
                    }
                    Protocol.ACTION_ATTENDANCE -> {
                        publishPush(setOf("statuses", "runtime"))
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            publishPush(setOf("history", "calendar", "statuses"))
                        }, 5000L)
                    }
                    Protocol.ACTION_START, Protocol.ACTION_STOP ->
                        publishPush(setOf("statuses", "runtime", "tasks"))
                    Protocol.ACTION_SCREENSHOT -> { /* 截图不影响快照区块 */ }
                }
            }
        }
    }

    private suspend fun applyTaskChange(action: String, time: String, oldTime: String?, name: String?): String {
        if (!TASK_TIME_PATTERN.matches(time)) return "TASK_FAIL:时间格式应为 HH:mm:ss"
        val result = when (action) {
            "add" -> {
                if (DatabaseWrapper.isTaskTimeExist(time)) return "TASK_FAIL:该时间点已存在"
                DatabaseWrapper.insert(com.pengxh.daily.app.sqlite.bean.DailyTaskBean().apply {
                    this.time = time
                    this.name = name ?: ""
                })
                rescheduleIfRunning()
                "TASK_OK"
            }
            "update" -> {
                val key = oldTime?.takeIf { it.isNotBlank() } ?: time
                val list = DatabaseWrapper.loadAllTask()
                val target = list.firstOrNull { it.time == key } ?: return "TASK_FAIL:未找到原任务"
                if (oldTime != null && oldTime != time && DatabaseWrapper.isTaskTimeExist(time)) {
                    return "TASK_FAIL:新时间点已存在"
                }
                target.time = time
                if (name != null) target.name = name
                DatabaseWrapper.updateTask(target)
                rescheduleIfRunning()
                "TASK_OK"
            }
            "delete" -> {
                val key = oldTime?.takeIf { it.isNotBlank() } ?: time
                val list = DatabaseWrapper.loadAllTask()
                val target = list.firstOrNull { it.time == key } ?: return "TASK_FAIL:未找到该任务"
                DatabaseWrapper.deleteTask(target)
                rescheduleIfRunning()
                "TASK_OK"
            }
            else -> "TASK_FAIL:未知操作"
        }
        // 任务增删改成功：置位刷新标志并广播，让前台被控端（主界面任务列表）即时刷新
        if (result == "TASK_OK") {
            ConfigImportSignal.notifyRemoteChanged(this@MqttAgentService)
        }
        return result
    }

    /** 调度运行中时，停掉再重启以让新任务时间点立即生效 */
    private fun rescheduleIfRunning() {
        if (TaskScheduler.isRunning()) {
            TaskScheduler.stopTask()
            TaskScheduler.startTask()
        }
    }

    /** 把快照 JSON 经 resp 主题回给控制端（带会话签名）；返回 true 表示成功发布 */
    private fun publishResp(rid: String, field: String, json: String): Boolean {
        val client = mqttClient ?: return false
        if (!client.isConnected) return false
        val ts = System.currentTimeMillis()
        val session = MqttSecureConfig.loadSession()
        val sign = if (session.isNotBlank())
            MqttSigner.sign(session, deviceId, ts, rid, field, "s", json, Protocol.CMD_RESP)
        else ""
        val packet = MqttPacket(
            c = Protocol.CMD_RESP,
            f = field,
            v = PacketValue.StringValue(json),
            rid = rid,
            ts = ts,
            sign = sign
        )
        return try {
            client.publish(
                topicResp(),
                MqttMessage(gson.toJson(packet).toByteArray()).apply { qos = 1 }
            )
            MqttQuota.add(this, 1, 0)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /** 配对：校验 pairingToken（TTL 窗口内可重复）→ 派生 sessionSecret → 标记绑定 → 回 pair/accept */
    private fun handlePair(packet: MqttPacket) {
        val active = readActivePairingToken()
        if (active == null) {
            // 被控端尚未生成配对码，或配对码已过期：提示用户去生成，并回执让控制端重试
            doPublishAck(packet.rid, "NO_PAIRING")
            "配对失败：本机尚未生成配对码，请先在「远程控制」页点「生成绑定二维码」，再用控制端扫描".showToast()
            return
        }
        val received = (packet.v as? PacketValue.StringValue)?.s ?: ""
        Log.d(TAG, "收到配对请求 rid=${packet.rid} 携带令牌长度=${received.length} 本机令牌长度=${active.first.length}")
        if (received != active.first) {
            Log.w(TAG, "配对令牌不匹配 rid=${packet.rid}")
            doPublishAck(packet.rid, "TOKEN_MISMATCH")
            "配对失败：控制端携带的配对码不一致或已过期，请在「远程控制」页重新生成二维码再扫码".showToast()
            return
        }
        // 注意：这里【不消费】令牌。令牌在 PAIRING_TTL_MS 窗口内保持有效，
        // 允许控制端在窗口内重复扫码重试（解决“首次因重连丢包导致配对失败后，重扫同一二维码仍失败”）；
        // 令牌仅随 TTL 过期或强制解绑而清除。
        Log.d(TAG, "配对令牌匹配 rid=${packet.rid}，开始派生会话密钥")

        val session = Hkdf.deriveHex(active.first, deviceId, Protocol.PAIRING_INFO, Protocol.SESSION_KEY_LEN)
        MqttSecureConfig.saveSession(session)
        SaveKeyValues.saveBoolean(Constant.IS_BOUND_KEY, true)
        _bound = true
        lastUnbindReason = "" // 重新配对成功，清除此前的解绑原因

        publishPairAccept()
        // 配对成功后立即发布 online retained status，覆盖此前解绑时发布的 force_unbound/unbound，
        // 否则控制端重连订阅 status 时仍会收到旧的 retained 解绑消息，误触发解绑流程。
        scope.launch { publishStatus("online") }
        bindingStateListener?.invoke(true)
        updateNotification()
        "已与控制端完成配对".showToast()
    }

    /** 控制端主动解绑 / 被控端强制解绑：仅清绑定态，保留 MQTT 配置 */
    private fun doUnbind(notifyController: Boolean) {
        val wasBound = SaveKeyValues.loadBoolean(Constant.IS_BOUND_KEY, false)
        SaveKeyValues.saveBoolean(Constant.IS_BOUND_KEY, false)
        _bound = false
        MqttSecureConfig.saveSession("")
        SaveKeyValues.saveString(Constant.MQTT_PAIRING_TOKEN_KEY, "")
        SaveKeyValues.saveLong(Constant.MQTT_PAIRING_EXPIRY_KEY, 0L)
        recentRids.clear()
        // 关键：publishStatus 是 blocking 的 qos1 发布，绝不能在主线程执行
        // （被控端“强制解绑”按钮在主线程调用本方法，若在主线程 publish 会 ANR/闪退）。
        // 统一丢到 IO 协程域执行，无论本方法从主线程还是 MQTT 回调线程进入都安全。
        // 记录解绑原因，供远程控制页（RemoteControlFragment）区分“从未绑定 / 被控制端移除 / 本机强制解绑”文案。
        // notifyController=true 表示本机强制解绑（需告知控制端），false 表示控制端主动解绑。
        lastUnbindReason = if (notifyController) "force" else "remote"
        if (wasBound || notifyController) {
            // retained：控制端订阅 status 主题可见，重新连接也能拿到最新状态
            Log.d("MqttAgentService", "doUnbind notifyController=$notifyController reason=${lastUnbindReason} -> 即将发布 retained status=${if (notifyController) "force_unbound" else "unbound"}")
            scope.launch { publishStatus(if (notifyController) "force_unbound" else "unbound") }
        }
        bindingStateListener?.invoke(false)
        updateNotification()
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
        recentRids.addLast(rid)
        // A4：定长环形（最多 200 条），超限移除最旧，避免长运行内存渐增
        while (recentRids.size > 200) recentRids.removeFirst()
        return true
    }

    private fun doPublishAck(rid: String, result: String) {
        val client = mqttClient ?: return
        if (!client.isConnected) return
        val ts = System.currentTimeMillis()
        val session = MqttSecureConfig.loadSession()
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
        MqttQuota.add(this, 1, 0)
    }

    /**
     * 增量推送：仅把 sections 指定的区块构建成 delta JSON 经 dt/{id}/push 发布给控制端。
     * 控制端按 key 覆盖合并到本地缓存，从而避免每次传输完整快照（省流量、降频）。
     * 仅在已配对（有会话密钥）且已连接时推送。
     */
    private fun publishPush(sections: Set<String>) {
        val client = mqttClient ?: return
        if (!client.isConnected) return
        val session = MqttSecureConfig.loadSession()
        if (session.isBlank()) return // 未配对不推送
        if (sections.isEmpty()) return
        scope.launch {
            try {
                val json = RemoteSnapshot.buildDelta(this@MqttAgentService, sections)
                if (json == "{}") return@launch
                val ts = System.currentTimeMillis()
                val rid = UUID.randomUUID().toString()
                val sign = MqttSigner.sign(session, deviceId, ts, rid, "delta", "s", json, Protocol.CMD_PUSH)
                val packet = MqttPacket(
                    c = Protocol.CMD_PUSH,
                    f = "delta",
                    v = PacketValue.StringValue(json),
                    rid = rid,
                    ts = ts,
                    sign = sign
                )
                client.publish(
                    topicPush(),
                    MqttMessage(gson.toJson(packet).toByteArray()).apply { qos = 1 }
                )
                MqttQuota.add(this@MqttAgentService, 1, 0)
                Log.d(TAG, "增量推送 delta -> ${topicPush()} sections=$sections size=${json.length}")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 测量到 broker 的往返时延：在 IO 协程中同步 publish 一条 QoS1 的 ping，
     * 以 PUBACK 返回（publish 阻塞返回）时刻计时，近似 RTT。
     * 注：本工程编译期可见的 MqttClient.publish 仅含同步重载（byte[]/MqttMessage），
     * 异步 IMqttActionListener 重载不可见，故用 scope.launch(Dispatchers.IO) 包裹同步调用，
     * 既拿到 PUBACK 时刻、又不阻塞主线程。
     */
    private fun doMeasureRtt(callback: (Long) -> Unit) {
        val client = mqttClient ?: return callback(-1)
        if (!client.isConnected) return callback(-1)
        val topic = "${Protocol.TOPIC_PREFIX}/$deviceId/ping"
        scope.launch {
            try {
                val sent = System.currentTimeMillis()
                client.publish(topic, MqttMessage("{}".toByteArray()).apply { qos = 1 })
                callback(System.currentTimeMillis() - sent)
            } catch (e: Exception) {
                callback(-1)
            }
        }
    }

    private fun publishStatus(state: String) {
        mqttClient?.publish(
            topicStatus(),
            MqttMessage(state.toByteArray()).apply { qos = 1; isRetained = true }
        )
        MqttQuota.add(this, 1, 0)
    }

    private fun publishPairAccept() {
        val client = mqttClient ?: return
        if (!client.isConnected) {
            Log.w(TAG, "publishPairAccept 跳过：未连接")
            return
        }
        Log.d(TAG, "回执配对确认 PA -> ${Protocol.TOPIC_PREFIX}/$deviceId/pair/accept")
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
        MqttQuota.add(this, 1, 0)
    }

    /** 把当前连接/绑定状态推送给已注册的监听者（Activity 可见时调用，避免错过实时回调） */
    private fun pushCurrentState() {
        stateListener?.invoke(_connected)
        bindingStateListener?.invoke(_bound)
    }

    private fun onConnected() {
        _connected = true
        MqttQuota.onConnect(this)
        stateListener?.invoke(true)
        // A2：连接成功，重置复活退避计数并取消待发的复活闹钟（避免退避期间无谓唤醒）
        resurrectAttempt = 0
        cancelResurrect()
        updateNotification()
        // Activity 注册监听可能略晚于连接成功，延迟再推一次，避免 UI 永久错过
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            pushCurrentState()
        }, 1500)
    }

    private fun onDisconnected() {
        _connected = false
        MqttQuota.onDisconnect(this)
        stateListener?.invoke(false)
        updateNotification()
    }

    private fun reconnect() {
        try {
            if (mqttClient?.isConnected == true) return
            mqttClient?.connect(connectOptions)
            onConnected()
            try {
                val okCmd = subscribeWithDiag(topicCmd())
                val okPair = subscribeWithDiag(topicPair())
                if (!okCmd || !okPair) {
                    "MQTT 订阅被 broker 拒绝：请检查 EMQX 中 DEV 账户（${SaveKeyValues.loadString(Constant.MQTT_USER_KEY, "")}）的 ACL 是否允许 ${Protocol.TOPIC_PREFIX}/$deviceId/#".showToast()
                }
                publishStatus("online")
            } catch (e: Exception) {
                e.printStackTrace()
                "MQTT 重连后订阅失败：${e.message}".showToast()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            onDisconnected()
            scheduleResurrectWithBackoff()
        }
    }

    // ===== 主题构建（最终模型 dt/{id}/...）=====
    private fun topicCmd() = "${Protocol.TOPIC_PREFIX}/$deviceId/cmd"
    private fun topicStatus() = "${Protocol.TOPIC_PREFIX}/$deviceId/status"
    private fun topicAck() = "${Protocol.TOPIC_PREFIX}/$deviceId/ack"
    private fun topicResp() = "${Protocol.TOPIC_PREFIX}/$deviceId/resp"
    private fun topicPair() = "${Protocol.TOPIC_PREFIX}/$deviceId/pair"
    private fun topicPairAccept() = "${Protocol.TOPIC_PREFIX}/$deviceId/pair/accept"
    private fun topicPush() = "${Protocol.TOPIC_PREFIX}/$deviceId/push"

    /**
     * 一次性事件告警推送（dt/{id}/alert）：低电量分段告警、开始充电通知等。
     * 与 publishPush 不同，这是「事件」而非「状态」，不进快照缓存，仅推一次。
     * 仅在已配对（有会话密钥）且已连接时推送。
     */
    private fun publishAlertInternal(json: String) {
        val client = mqttClient ?: return
        if (!client.isConnected) return
        val session = MqttSecureConfig.loadSession()
        if (session.isBlank()) return // 未配对不推送
        scope.launch {
            try {
                val ts = System.currentTimeMillis()
                val rid = UUID.randomUUID().toString()
                val sign = MqttSigner.sign(session, deviceId, ts, rid, "alert", "s", json, Protocol.CMD_ALERT)
                val packet = MqttPacket(
                    c = Protocol.CMD_ALERT,
                    f = "alert",
                    v = PacketValue.StringValue(json),
                    rid = rid,
                    ts = ts,
                    sign = sign
                )
                client.publish(
                    topicAlert(),
                    MqttMessage(gson.toJson(packet).toByteArray()).apply { qos = 1 }
                )
                MqttQuota.add(this@MqttAgentService, 1, 0)
                Log.d(TAG, "告警推送 alert -> ${topicAlert()} $json")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun topicAlert() = "${Protocol.TOPIC_PREFIX}/$deviceId/alert"

    /**
     * 带诊断的订阅：返回订阅是否真正成功（broker 授予的 QoS != 128）。
     * Paho 在 broker 拒绝订阅（如 EMQX ACL 未授权）时不会抛异常，
     * 而是把 grantedQos 设为 [128]，因此必须用 subscribeWithResponse 解析 SUBACK。
     */
    private fun subscribeWithDiag(topic: String): Boolean {
        val client = mqttClient ?: return false
        return try {
            // 同步订阅并返回令牌：broker 拒绝订阅（如 EMQX ACL 未授权）时 SUBACK 授予 QoS=128(0x80)，
            // 但不会抛异常，必须从 grantedQos 读取判断。注意用 subscribeWithResponse（同步），
            // 不能用 subscribeWithResult（那是 MqttAndroidClient 的方法，同步 MqttClient 没有）。
            val token = client.subscribeWithResponse(topic, 1)
            val granted = token?.grantedQos?.firstOrNull() ?: 1
            if (granted == 128) {
                Log.w(TAG, "订阅被 broker 拒绝(ACL): $topic 账户=${SaveKeyValues.loadString(Constant.MQTT_USER_KEY, "")}")
                false
            } else {
                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Log.w(TAG, "订阅异常: $topic ${e.message}")
            false
        }
    }

    /** A2：带指数退避的复活调度。前 3 次用 30s 快速重试（应对短暂失联），
     * 之后按 2^n×60s 退避（120s→240s→…），上限 15min；连接成功后由 onConnected 重置计数。
     * 注意：resurrectAttempt 先参与计算再自增，得到序列 30/30/30/120/240/… */
    private fun scheduleResurrectWithBackoff() {
        if (!SaveKeyValues.loadBoolean(Constant.MQTT_ENABLED_KEY, false)) return
        val delay = if (resurrectAttempt < 3) 30_000L
        else minOf((1L shl (resurrectAttempt - 2)) * 60_000L, 15 * 60_000L)
        resurrectAttempt++
        Log.d(TAG, "安排复活闹钟(退避) attempt=$resurrectAttempt delay=${delay}ms")
        nextReconnectAtMs = System.currentTimeMillis() + delay
        scheduleResurrect(delay)
    }

    /** 复活闹钟：进程/服务被杀或长时间断线后兜底重启服务 */
    private fun scheduleResurrect(delayMs: Long) {
        if (!SaveKeyValues.loadBoolean(Constant.MQTT_ENABLED_KEY, false)) return
        nextReconnectAtMs = System.currentTimeMillis() + delayMs
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(this, MqttAgentService::class.java)
        // Android 8+ 后台启动前台服务必须用 getForegroundService，否则 startService 会抛 IllegalStateException
        val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                this, 2001, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getService(
                this, 2001, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val triggerAt = SystemClock.elapsedRealtime() + delayMs
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            } else {
                alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun cancelResurrect() {
        nextReconnectAtMs = 0L
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(this, MqttAgentService::class.java)
        val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                this, 2001, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getService(
                this, 2001, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        alarmManager.cancel(pi)
    }

    /** 任务时间点格式 HH:mm:ss（与控制端约定一致） */
    private val TASK_TIME_PATTERN = Regex("""^([01]\d|2[0-3]):[0-5]\d:[0-5]\d$""")



    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A1：开关守卫 —— 关闭时返回 START_NOT_STICKY，不让系统因内存回收自动拉起并绕过开关重连；
        // 仅开关开启时返回 START_STICKY（被杀后由系统重新拉起并继续连接）。
        if (!SaveKeyValues.loadBoolean(Constant.MQTT_ENABLED_KEY, false)) {
            try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) { }
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // 用户划掉任务卡片后，若远程开关仍开启，则安排复活闹钟兜底
        if (SaveKeyValues.loadBoolean(Constant.MQTT_ENABLED_KEY, false)) {
            scheduleResurrect(delayMs = 5_000L)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(remoteChangedReceiver) } catch (_: Exception) { }
        instance = null
        stateListener = null
        bindingStateListener = null
        scope.cancel()
        MqttQuota.onDisconnect(this)
        // 优雅停止：主动发布 retained offline（区别于 broker 的 Last-Will 异常掉线），
        // 让控制端在服务被主动关闭时也能即时感知离线（需求：掉线增强）。
        try { if (mqttClient?.isConnected == true) publishStatus("offline") } catch (_: Exception) { }
        try {
            mqttClient?.disconnect()
            mqttClient?.close()
        } catch (_: Exception) {
        }
        // 如果开关仍开启（非正常手动关闭），安排复活闹钟兜底重连；
        // 正常关闭开关会先把 MQTT_ENABLED_KEY 设为 false，因此不会复活。
        if (SaveKeyValues.loadBoolean(Constant.MQTT_ENABLED_KEY, false)) {
            // 服务被销毁但开关仍开（非正常手动关闭），安排复活闹钟（带退避）
            scheduleResurrectWithBackoff()
        } else {
            cancelResurrect()
            // 撤销前台通知，确保关闭开关后通知栏无残留
            stopForeground(true)
        }
    }

    private var lastToastKey: String? = null
    private var lastToastAt = 0L
    private val TOAST_DEDUP_MS = 30_000L

    private fun String.showToast() {
        val msg = this
        val now = System.currentTimeMillis()
        if (msg == lastToastKey && now - lastToastAt < TOAST_DEDUP_MS) return
        lastToastKey = msg
        lastToastAt = now
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(this@MqttAgentService, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
