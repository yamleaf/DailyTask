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
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.pengxh.daily.app.R
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.ui.MainActivity
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.MqttSecureConfig
import com.pengxh.daily.app.utils.ConfigImportSignal
import com.pengxh.daily.app.utils.LogFileManager
import com.yample.mqttprotocol.MqttQuota
import com.pengxh.daily.app.utils.RemoteSnapshot
import com.pengxh.daily.app.utils.RuntimeStateApplier
import com.pengxh.daily.app.service.NotificationMonitorService
import com.pengxh.daily.app.utils.TaskScheduler
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.yample.mqttprotocol.BrokerUtils
import com.yample.mqttprotocol.Hkdf
import com.yample.mqttprotocol.MqttPacket
import com.yample.mqttprotocol.MqttSigner
import com.yample.mqttprotocol.PacketValue
import com.yample.mqttprotocol.PacketValueAdapter
import com.yample.mqttprotocol.PresenceArbitration
import com.yample.mqttprotocol.PresenceGuard
import com.yample.mqttprotocol.Protocol
import com.yample.mqttprotocol.SecretBox
import com.pengxh.kt.lite.utils.SaveKeyValues
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MqttAgentService : Service() {

    companion object {
        private const val TAG = "MqttAgentService"
        /** @Volatile：onCreate/onDestroy 在主线程写、MQTT 回调线程读，跨线程可见性必须显式保证，
         * 否则 P3 的 instance === this 校验可能读到旧值而失效 */
        @Volatile
        private var instance: MqttAgentService? = null

        /** 增量推送：状态变化时通知控制端刷新（打卡完成 / 任务启停）。publishPush 已做未配对/未连接守卫。 */
        fun pushTaskIncrement() {
            instance?.publishPush(setOf("tasks", "calendar", "statuses"))
        }

        /** 测量到 broker 的 RTT（ms），以 QoS1 PUBACK 到达时刻计时；未连接时回调 -1 */
        fun measureRtt(callback: (Long) -> Unit) {
            instance?.doMeasureRtt(callback) ?: callback(-1)
        }

        /** MQTT 连接测试结果 */
        data class MqttTestResult(
            val ok: Boolean,
            val connectMs: Long,
            val rtts: List<Long>,
            val error: String? = null
        ) {
            val avgRtt: Long get() = if (rtts.isEmpty()) -1L else rtts.average().toLong()
            val minRtt: Long get() = if (rtts.isEmpty()) -1L else rtts.minOrNull()!!
            val maxRtt: Long get() = if (rtts.isEmpty()) -1L else rtts.maxOrNull()!!
        }

        /**
         * 独立 MQTT 连接测试：用一次性客户端连接当前配置的 broker，
         * 测连接握手耗时 + 订阅/发布往返 RTT（3 轮），验证可用性与连接质量。
         * 用独立 clientId + 独立测试主题，不影响正在运行的长连接。后台线程执行。
         */
        suspend fun testConnection(): MqttTestResult = withContext(Dispatchers.IO) {
            val broker = SaveKeyValues.loadString(Constant.MQTT_BROKER_KEY, "").trim()
            val user = SaveKeyValues.loadString(Constant.MQTT_USER_KEY, "").trim()
            val pass = MqttSecureConfig.loadPass()
            if (broker.isBlank() || user.isBlank() || pass.isBlank()) {
                return@withContext MqttTestResult(false, -1, emptyList(), "请先填写 MQTT 服务器 / 用户名 / 密码")
            }
            val deviceId = SaveKeyValues.loadString(Constant.DEVICE_ID_KEY, "default")
            val testClientId = "dev-test-${System.currentTimeMillis()}"
            val testTopic = "${Protocol.TOPIC_PREFIX}/$deviceId/test/$testClientId"
            val client = MqttClient(BrokerUtils.normalizeBroker(broker), testClientId, MemoryPersistence())
            try {
                val opts = MqttConnectOptions().apply {
                    isCleanSession = true
                    userName = user
                    password = pass.toCharArray()
                    connectionTimeout = 8
                    keepAliveInterval = 30
                    isAutomaticReconnect = false
                }
                val t0 = System.currentTimeMillis()
                client.connect(opts)
                val connectMs = System.currentTimeMillis() - t0
                if (!client.isConnected) {
                    return@withContext MqttTestResult(false, connectMs, emptyList(), "连接未建立")
                }
                // 校验订阅是否被 ACL 拒绝（SUBACK 0x80），复现「订阅被拒」问题的直接证据
                try {
                    val tok = client.subscribeWithResponse(testTopic, 1)
                    if (tok?.grantedQos?.firstOrNull() == 128) {
                        return@withContext MqttTestResult(
                            false, connectMs, emptyList(),
                            "订阅被 broker 拒绝（SUBACK 0x80）——请检查 EMQX 中该账户 ACL 是否允许 ${Protocol.TOPIC_PREFIX}/$deviceId/# 的 subscribe"
                        )
                    }
                } catch (e: Exception) {
                    return@withContext MqttTestResult(false, connectMs, emptyList(), "订阅异常：${e.message}")
                }
                val rtts = mutableListOf<Long>()
                val sentRef = AtomicLong(0)
                var pending = CountDownLatch(1)
                client.setCallback(object : MqttCallback {
                    override fun connectionLost(cause: Throwable?) {}
                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        val sent = sentRef.get()
                        if (sent > 0) {
                            rtts.add(System.currentTimeMillis() - sent)
                            sentRef.set(0)
                            pending.countDown()
                        }
                    }
                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })
                repeat(3) { round ->
                    pending = CountDownLatch(1)
                    sentRef.set(System.currentTimeMillis())
                    try {
                        client.publish(testTopic, MqttMessage("ping-$round".toByteArray()).apply { qos = 1 })
                    } catch (e: Exception) {
                        sentRef.set(0)
                        rtts.add(-1)
                    }
                    if (!pending.await(5, TimeUnit.SECONDS)) {
                        sentRef.set(0)
                        rtts.add(-1)
                    }
                }
                val good = rtts.filter { it >= 0 }
                if (good.isEmpty()) {
                    return@withContext MqttTestResult(false, connectMs, emptyList(), "连接可用但发布/回环不通（RTT 无响应）")
                }
                MqttTestResult(true, connectMs, good)
            } catch (e: Exception) {
                MqttTestResult(false, -1, emptyList(), "连接异常：${e.message}")
            } finally {
                try { client.disconnect(2_000) } catch (_: Exception) { }
                try { client.close() } catch (_: Exception) { }
            }
        }

        fun isRunning(): Boolean = instance != null
        /** 连接态须同时满足「开关开启」，否则关闭开关到 onDestroy 执行之间存在窗口，
         * 期间 instance 未置空、_connected 仍为 true，UI 会短暂残留「已连接」。 */
        fun isConnected(): Boolean =
            instance?._connected == true && SaveKeyValues.loadBoolean(Constant.MQTT_ENABLED_KEY, false)
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

        /** 配置变更等场景推送指定快照区块，让控制端本地缓存即时更新（闭环：ACK → 增量推送 → 控制端刷新） */
        fun pushSections(sections: Set<String>) {
            instance?.publishPush(sections)
        }

        /** 节假日数据更新等完成后，推送给控制端刷新 calendar 区块 */
        fun pushCalendar() {
            instance?.publishPush(setOf("calendar"))
        }

        /**
         * 告警推送给控制端，并写入本地环形缓冲供 AQ 回放（连接与否不影响留存）。
         * 缓冲 aid 与发布 rid 同源：实时送达与事后回放按同一 rid 幂等去重。
         */
        fun publishAlert(json: String) {
            val aid = recordAlertToBuffer(json)
            instance?.publishAlertInternal(json, ridOverride = aid)
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

        /** 当前实例实际使用的 clientId（dev-{id}-{随机后缀}），供 UI 展示、便于在 broker 客户端列表核对 */
        @Volatile var currentClientId: String? = null

        /** B2：立即重连 —— 取消复活闹钟并触发进程内重连（带去重） */
        fun reconnectNow() {
            nextReconnectAtMs = 0L
            instance?.let { svc ->
                svc.cancelResurrect()
                // 关键：reconnect() 内的 Paho 调用会在 IO 协程上执行，不能放主线程（按钮/闹钟均为主线程）→ ANR。
                // 由 launchReconnectOnce 去重并派发到 IO 协程。
                svc.launchReconnectOnce()
            }
        }

        /** 进程在跑但 MQTT 断开时，由救援/复活闹钟等后台路径触发进程内重连（幂等、去重）。 */
        fun triggerReconnectIfNeeded() {
            instance?.launchReconnectOnce()
        }

        /**
         * 取消 MQTT 复活/救援闹钟（服务实例可能已销毁，仍按相同 PendingIntent 取消）。
         * 「暂停使用」路径必须调用，避免残留闹钟在暂停期间再次拉起服务。
         */
        fun cancelResurrectAlarms(context: Context) {
            nextReconnectAtMs = 0L
            instance?.cancelResurrect()
            KeepAliveReceiver.cancelRescueAlarm(context.applicationContext)
            // 兼容旧版：取消曾指向本服务的 getForegroundService PendingIntent
            val appCtx = context.applicationContext
            val alarmManager = appCtx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(appCtx, MqttAgentService::class.java)
            val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(
                    appCtx, 2001, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                PendingIntent.getService(
                    appCtx, 2001, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
            alarmManager.cancel(pi)
        }

        // ═══════ 设备ID占用仲裁：冲突标志与告警（详见 PresenceGuard） ═══════

        /** 是否处于「设备ID冲突已停服」状态：所有自动拉起路径据此拦截，仅手动开启时清除 */
        fun isIdConflictBlocked(): Boolean =
            SaveKeyValues.loadBoolean(Constant.MQTT_ID_CONFLICT_KEY, false)

        /** 返回上次冲突的外来 HB 时间戳（毫秒），若无则返回 0L */
        fun foreignTs(): Long =
            SaveKeyValues.loadLong(Constant.MQTT_ID_CONFLICT_FOREIGN_TS_KEY, 0L)

        /** 按持久化的原因码生成用户文案；原因码缺失/非法时退化为通用文案 */
        fun conflictReasonText(): String {
            val deviceId = SaveKeyValues.loadString(Constant.DEVICE_ID_KEY, "")
            val reason = PresenceGuard.ConflictReason.fromName(
                SaveKeyValues.loadString(Constant.MQTT_ID_CONFLICT_REASON_KEY, "")
            )
            val foreignTs = SaveKeyValues.loadLong(Constant.MQTT_ID_CONFLICT_FOREIGN_TS_KEY, 0L)
            return reason?.userMessage(deviceId, foreignTs)
                ?: "设备ID $deviceId 存在同账号占用冲突，远程服务已暂停。请修改设备ID后重新开启。"
        }

        /** 异常接入黄条横幅实时回调（监听方自行切主线程） */
        @Volatile var idAlertListener: (() -> Unit)? = null

        /** 设备ID冲突停服实时回调：UI 收到后立即刷新 hero 状态与冲突横幅 */
        @Volatile var conflictListener: (() -> Unit)? = null

        /** 清除冲突标志：仅限用户手动动作（开启开关/修改设备ID）调用，自动拉起路径一律不清 */
        fun clearIdConflictFlag(context: Context) {
            if (!isIdConflictBlocked()) return
            SaveKeyValues.saveBoolean(Constant.MQTT_ID_CONFLICT_KEY, false)
            LogFileManager.action("设备ID冲突标志已手动清除")
        }

        // ═══════ 近期告警环形缓冲（供 AQ 回放） ═══════

        private const val ALERT_BUFFER_MAX = 20
        /** 回放保留窗口：只回放 48h 内的告警，更旧的视为过期 */
        private const val ALERT_REPLAY_MAX_AGE_MS = 48L * 3600_000L

        /**
         * 告警写入本地缓冲 [{aid, ts, body}]，最新在前，超限裁剪最旧。
         * 返回本次 aid，由调用方作为报文 rid 发布——实时/回放共用同一去重键。
         */
        private fun recordAlertToBuffer(json: String): String {
            val aid = UUID.randomUUID().toString()
            try {
                val body = JsonParser.parseString(json).asJsonObject ?: return aid
                val entry = JsonObject().apply {
                    addProperty("aid", aid)
                    addProperty("ts", System.currentTimeMillis())
                    add("body", body)
                }
                // 最新在前：头插后拼接旧内容（JsonArray 无位置插入 API）
                val out = com.google.gson.JsonArray()
                out.add(entry)
                loadAlertBuffer().forEach { out.add(it) }
                if (out.size() > ALERT_BUFFER_MAX) {
                    val fixed = com.google.gson.JsonArray()
                    for (i in 0 until ALERT_BUFFER_MAX) fixed.add(out.get(i))
                    SaveKeyValues.saveString(Constant.ALERT_RECENT_BUFFER_KEY, fixed.toString())
                } else {
                    SaveKeyValues.saveString(Constant.ALERT_RECENT_BUFFER_KEY, out.toString())
                }
            } catch (_: Exception) {
                // 缓冲失败不影响告警主链路
            }
            return aid
        }

        private fun loadAlertBuffer(): com.google.gson.JsonArray = try {
            val raw = SaveKeyValues.loadString(Constant.ALERT_RECENT_BUFFER_KEY, "")
            if (raw.isBlank()) com.google.gson.JsonArray()
            else JsonParser.parseString(raw).asJsonArray
        } catch (_: Exception) {
            com.google.gson.JsonArray()
        }

        /** 构建 AQ 回放载荷：过滤超过保留窗口的旧告警 */
        private fun buildAlertReplayPayload(): String {
            val cutoff = System.currentTimeMillis() - ALERT_REPLAY_MAX_AGE_MS
            val out = com.google.gson.JsonArray()
            for (e in loadAlertBuffer()) {
                val obj = e.asJsonObject ?: continue
                val ts = obj.get("ts")?.asLong ?: 0L
                if (ts in 1..cutoff) continue
                val item = JsonObject()
                item.addProperty("aid", obj.get("aid")?.asString ?: "")
                item.addProperty("occurredAt", ts)
                item.add("alert", obj.get("body") ?: JsonObject())
                out.add(item)
            }
            return out.toString()
        }
    }

    @Volatile private var _connected = false
    @Volatile private var _bound = SaveKeyValues.loadBoolean(Constant.IS_BOUND_KEY, false)
    private var mqttClient: MqttClient? = null
    /** 闹钟驱动的心跳发送器（仅 ALARM 级别使用）。Paho close() 会调其 stop()，onDestroy 显式 stop() 幂等兜底 */
    private var alarmPingSender: AlarmPingSender? = null
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

    /** startForeground 因 FGS 配额失败时置位，避免 onDestroy 再排短间隔复活连撞限额 */
    @Volatile
    private var quitDueToFgsQuota = false

    /** 网络恢复时触发重连（息屏/Doze 下比干等退避更省空窗） */
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** 息屏保活 WiFi 锁：阻止息屏后 WiFi suspend 挂起导致 MQTT 心跳失联（详见 acquireWifiLock） */
    private var wifiLock: WifiManager.WifiLock? = null

    /** 命令处理 PARTIAL_WAKE_LOCK：收到控制端指令时持 30s 短时锁（超时自动释放），
     * 确保异步处理（快照构建/设置下发等）完成前 CPU 不睡回 */
    private var commandWakeLock: PowerManager.WakeLock? = null

    // ===== CPU 保活级别（兜底）：息屏持 PARTIAL_WAKE_LOCK，CPU 不深睡 → Timer 心跳线程始终被调度 =====
    /** 息屏保活 PARTIAL_WAKE_LOCK：SCREEN_OFF 持有、SCREEN_ON 释放（仅 CPU 级别使用） */
    private var screenWakeLock: PowerManager.WakeLock? = null

    private var screenReceiverRegistered = false

    /** 屏幕广播：SCREEN_OFF → 获取 WakeLock 保 CPU 调度；SCREEN_ON → 释放省电 */
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> acquireScreenWakeLock()
                Intent.ACTION_SCREEN_ON -> releaseScreenWakeLock()
            }
        }
    }

    /** 保活级别升级时正在重建连接（防 connectionLost 与 rebuild 循环触发） */
    @Volatile
    private var isRebuilding = false

    @Volatile
    private var lastNetworkReconnectAtMs = 0L

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

    // ═══════ 设备ID占用仲裁（presence，详见 PresenceGuard） ═══════
    /** 本机会话标识：持久化（MQTT_PRESENCE_SID_KEY），跨进程死亡认领自己的旧占位牌 */
    private var presenceSid: String = ""
    /** 已通过仲裁上岗：此后重连只做 RECLAIM 检查，不再完整仲裁 */
    @Volatile private var arbitrationPassed = false
    /** 仲裁进行中守卫：connectComplete 与初始连接分支可能双触发，只允许一次进入 */
    private val arbitrationGate = java.util.concurrent.atomic.AtomicBoolean(false)
    /** 决策窗口状态机（非空=仲裁中，presence 消息喂给它） */
    @Volatile private var arbiter: PresenceArbitration? = null
    private var lastHbPublishedAtMs = 0L
    /** 占位牌心跳协程：仅进程醒着时顺手补发（不排闹钟不持锁，Doze 冻结无害） */
    private var hbLoopJob: kotlinx.coroutines.Job? = null
    /** 对同一挑战者回 CLM 的节流 */
    private var lastClaimReplySid = ""
    private var lastClaimReplyAtMs = 0L
    /** 黄条提醒 + id_conflict 上报的去重集合（同一挑战者只提示/上报一次） */
    private val challengedSidsReported = mutableSetOf<String>()
    /** 上岗后监听期截止时刻（TAKEOVER_WATCH_MS 内被抗议则让位） */
    @Volatile private var takeoverWatchUntilMs = 0L
    /** 最近收到的外来占位牌（sid to ts）：RECLAIM 检查用；仅保留最新一条即可 */
    @Volatile private var lastForeignHb: Pair<String, Long>? = null
    /** RECLAIM 确认探测进行中：期间收到外来 CLAIM 即坐实接管 */
    @Volatile private var reclaimProbeActive = false
    @Volatile private var reclaimProbeSeenClaim = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        // A1：开关守卫 —— 关闭 MQTT 开关时直接不启动连接，保证「关闭零耗电」。
        // 否则进程被系统回收后重启，onCreate 会重新 initMqtt 绕过开关继续连接，承诺破防。
        if (!SaveKeyValues.loadBoolean(Constant.MQTT_ENABLED_KEY, false)
            || KeepAliveReceiver.isPaused()
        ) {
            Log.d(TAG, "MQTT 开关关闭或暂停使用中，服务不启动")
            try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) { }
            stopSelf()
            return
        }
        // 设备ID冲突守卫：冲突停服后任何自动拉起都不得重连，仅用户手动开启时由 clearIdConflictFlag 解除
        if (isIdConflictBlocked()) {
            Log.w(TAG, "设备ID冲突停服中，拒绝自动拉起（需用户手动开启）")
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
        // C1：前台服务启动防护。Android 15+ 对后台启动前台服务有严格配额（"Time limit already exhausted"），
        // startForeground 可能抛 ForegroundServiceStartNotAllowedException；若任其扩散会以
        // "Unable to create service" 崩溃整个进程，连带同进程的 NotificationMonitorService（远程指令链路）
        // 一起死掉。因此 startForeground 失败时优雅退出本服务（stopSelf），绝不崩进程；
        // MQTT 由 KeepAliveReceiver / 复活闹钟 / 前台打开 App 在配额恢复后重新拉起。
        if (!startForegroundNotification()) {
            LogFileManager.error("MQTT 服务前台启动被系统拒绝（后台 FGS 配额限制），已降级退出，等待配额恢复后重试")
            quitDueToFgsQuota = true
            KeepAliveReceiver.scheduleFgsQuotaBackoff(this)
            return
        }
        registerNetworkCallback()
        // 息屏保活 WiFi 锁改「PING 窗口持有」（方案 A，省电优先，详见 acquireWifiLock 注释）：
        // - ALARM 模式（默认）：由 AlarmPingSender 在每次发 PINGREQ 的窗口内短时持有 WifiLock，
        //   息屏间隙 WiFi 可 suspend 挂起，比全程常驻明显省电；连接靠 80s 内活动保活不断连。
        // - CPU 模式（兜底）：由 acquireScreenWakeLock 在息屏时持续持有 WifiLock（与 PARTIAL_WAKE_LOCK 绑定），
        //   保证极不可靠设备连接稳定（行为同旧版常驻）。
        // 故 onCreate 不再常驻获取，改由各模式按需持有。
        // 息屏保活：auto 模式每次启动重置为 ALARM（起步即闹钟心跳）；指定模式保持固定级别。不做降级
        MqttKeepAliveStrategy.onServiceStart()
        // Paho connect 为阻塞调用（失败时最长等待 connectionTimeout=10s），必须在后台线程执行，
        // 否则在主线程执行会导致服务启动超时 + 界面 ANR
        Thread { initMqtt() }.start()
    }

    /** 拉起前台通知。返回 false 表示 startForeground 被系统拒绝（后台 FGS 配额耗尽），
     * 调用方应优雅退出服务，防止进程被 "Unable to create service" 整体拖崩。 */
    private fun startForegroundNotification(): Boolean {
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
        return try {
            // Android 15+ 对 dataSync/unknown 类 FGS 有 6h/24h 时间配额：常驻服务跑满即被系统停止，
            // 且重启 startForeground 全部被拒（08-18 K20 Pro 夜间离线根因：type unknown/dataSync 配额耗尽）。
            // 显式传 specialUse（manifest 已声明 + FOREGROUND_SERVICE_SPECIAL_USE 权限 + property）不受时限。
            startForeground(1001, notificationBuilder!!.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            updateNotification()
            true
        } catch (e: Exception) {
            // Android 15+ 后台启动前台服务的配额已耗尽（ForegroundServiceStartNotAllowedException
            // "Time limit already exhausted for foreground service type xxx"）。这里绝不能让异常
            // 逃逸出 onCreate，否则整个进程（含通知监听）一起崩溃、远程指令彻底失联。
            LogFileManager.error("startForeground 被系统拒绝：${e.message}")
            Log.e(TAG, "startForeground 失败（后台 FGS 配额限制）", e)
            stopSelf()
            false
        }
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

        // clientId 每次服务实例唯一（后缀 UUID 前 8 位）：避免 broker 上残留上一次运行的同 clientId
        // 僵尸会话，导致「会话接管/互踢」——表现为 connect 成功约 1s 后被 broker 断开、无限重连循环
        // （与真机日志中 connectComplete 后紧接 connectionLost + 32110 的现象吻合）。
        // 主题按 deviceId 路由、控制端靠 status retained 发现设备，clientId 唯一不影响配对/寻址，
        // 可参考控制端 OfflineMonitorService 用独立 clientId 避免互踢的既有做法。
        val clientId = "dev-$deviceId-${UUID.randomUUID().toString().take(8)}"
        currentClientId = clientId
        // 自适应保活：按当前级别创建 client（pingSender 在构造时注入，级别切换需重建连接）
        when (MqttKeepAliveStrategy.current()) {
            MqttKeepAliveStrategy.Level.CPU -> {
                // CPU 兜底：TimerPingSender + 息屏持 PARTIAL_WAKE_LOCK（CPU 不深睡 → 心跳线程始终被调度）
                mqttClient = MqttClient(BrokerUtils.normalizeBroker(broker), clientId, MemoryPersistence())
                registerScreenStateReceiver()
                Log.d(TAG, "保活级别 CPU：TimerPingSender + 息屏持锁")
            }
            MqttKeepAliveStrategy.Level.ALARM -> {
                // 闹钟保活：AlarmPingSender（系统精确闹钟）驱动心跳
                alarmPingSender = AlarmPingSender(this)
                // 占位牌保鲜：蹭 PING 闹钟的唤醒窗口补发 presence HB（钩子内部判连接态+节流），
                // 不新增闹钟/WakeLock；异常在 AlarmPingSender 内隔离，绝不影响 PING 链路
                alarmPingSender?.onPingHook = { refreshPresenceHbIfDue() }
                mqttClient = MqttClientWithAlarmPingSender(
                    BrokerUtils.normalizeBroker(broker), clientId, MemoryPersistence(), alarmPingSender!!
                )
                Log.d(TAG, "保活级别 ALARM：闹钟驱动心跳")
            }
        }
        connectOptions = MqttConnectOptions().apply {
            // 使用全新会话：Doze 挂网产生半死僵尸会话后，重连若沿用旧会话（cleanSession=false）
            // 会触发 EMQX 会话接管/合并，导致重订阅被 broker 以 SUBACK 0x80(ACL) 拒绝。
            // 改新会话后每次重连都重新订阅（connectComplete 已幂等订阅），不再依赖 broker 侧会话恢复。
            // 注意：被控端 publish 的告警/状态是「发布」，投递由订阅方会话保证（控制端 ctl-mon 持久会话），
            // 不受本端会话策略影响；仅离线期间 broker 排队给本端的 cmd/pair 指令不再补发（有 rid 去重+超时兜底）。
            isCleanSession = true
            userName = SaveKeyValues.loadString(Constant.MQTT_USER_KEY, "")
            password = MqttSecureConfig.loadPass().toCharArray()
            connectionTimeout = 10
            // 自动重连：网络切换（WiFi↔移动）或瞬时掉线后由 Paho 自带退避重连，无需我们持有 WakeLock
            isAutomaticReconnect = true
            // 心跳间隔 4 分钟（keepAlive=240s）：ALARM 级别由闹钟调度、CPU 级别持锁保证 Timer 线程运行。
            // 间隔长可减少心跳包与唤醒次数以省电。
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
                // P3：服务已销毁（onDestroy 已将 instance 置空）后到达的迟到断线回调，
                // 不再更新状态/排闹钟/触发重连，避免残留通知与无效复活闹钟
                if (instance !== this@MqttAgentService) return
                Log.w(TAG, "MQTT 连接丢失：${cause?.message}", cause)
                // 掉线瞬间网络状态诊断（降级 Log.d，logcat 保留；区分网络断 vs 保活失效）
                Log.d(TAG, "连接丢失诊断 网络=${networkStateDesc()} cause=${cause?.message}")
                onDisconnected()
                // 自适应保活升级：30min 窗口内掉线 ≥2 次 → 升一级（ALARM→CPU）。
                // 升级后重建连接（pingSender 在 client 构造时注入，级别切换必须重建）。
                // isRebuilding 守卫防止 rebuild 过程中 disconnect 触发本回调造成循环。
                if (!isRebuilding && MqttKeepAliveStrategy.recordDisconnect() != null) {
                    rebuildMqttClient()
                }
                // 进程存活时的瞬时掉线，统一交给 Paho isAutomaticReconnect 自行退避重连：
                // connectComplete(reconnect=true) 会幂等重订阅并发布 online，进程内无需再做任何事。
                // 此前在此处排复活闹钟 + 3s 后手动 reconnect()，与 Paho 自动重连构成双驱动，
                // 互相抢同一 clientId 连接槽位 → broker 会话接管互踢 + 32110「已在进行连接」，
                // 且每次掉线都堆一个 30s 闹钟（真机 90s 内堆了 105 个），持续给循环喂料——
                // 正是反复上线下线重连的放大器。进程/服务被系统杀死的情况由 onDestroy 单独排复活闹钟兜底。
            }

            override fun messageArrived(topic: String?, message: MqttMessage?) {
                message?.payload?.let {
                    MqttQuota.add(this@MqttAgentService, 0, 1)
                    // 持 30s 超时短时锁，覆盖 handleIncoming 内异步处理（快照/任务落库等）。
                    // 不在此处提前 release：同步返回时异步尚未完成；靠超时自动释放省电。
                    acquireCommandWakeLock()
                    val json = String(it)
                    // presence 主题走占用仲裁协议（轻量 JSON），先于 handleIncoming 分流
                    if (topic == topicPresence()) {
                        handlePresenceMessage(json)
                        return
                    }
                    handleIncoming(json)
                }
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {}

            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                // P3：重连成功回调与 onDestroy 存在竞争窗口 —— Paho 连接线程独立于 scope，onDestroy
                // 无法取消它；服务已销毁（instance 置空）后到达的迟到成功回调若继续 onConnected()，
                // updateNotification 会用残留的 notificationBuilder 重新弹一条普通通知。这里直接拦截。
                if (instance !== this@MqttAgentService) return
                Log.d(TAG, "MQTT ${if (reconnect) "自动重连" else "首次连接"}成功")
                onConnected()
                // 统一上线入口（仲裁/RECLAIM），双触发由 enterServiceAsync 门禁去重
                enterServiceAsync(reconnect)
            }
        })

        try {
            mqttClient?.connect(connectOptions)
            onConnected()
            // 首次连接兜底：个别 Paho 版本首连不回调 connectComplete，命令主题不能遗漏
            enterServiceAsync(reconnect = false)
        } catch (e: Exception) {
            Log.e(TAG, "caught exception", e)
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
                Protocol.CMD_SYNC -> handleSync(packet)
                Protocol.CMD_PAIR -> handlePair(packet)
                Protocol.CMD_UNBOUND -> handleUnbound(packet)
                Protocol.CMD_ALERT_QUERY -> handleAlertQuery(packet)
                else -> { /* 其它命令暂忽略 */ }
            }
        } catch (e: Exception) {
            Log.e(TAG, "caught exception", e)
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

    /** AQ 告警回放查询：校验后把本地告警缓冲经 resp(field=alerts) 密封回放；为空也回空数组 */
    private fun handleAlertQuery(packet: MqttPacket) {
        val session = MqttSecureConfig.loadSession()
        if (session.isBlank()) {
            doPublishAck(packet.rid, "UNBOUND")
            return
        }
        if (!verifyWithSession(packet, session)) {
            Log.w(TAG, "告警回放查询签名校验失败 rid=${packet.rid}")
            doPublishAck(packet.rid, "SIGN_FAIL")
            return
        }
        if (!acceptRid(packet.rid, packet.ts)) {
            doPublishAck(packet.rid, "DUP_OR_STALE")
            return
        }
        scope.launch {
            try {
                val payload = buildAlertReplayPayload()
                val ok = publishResp(packet.rid, "alerts", payload)
                if (!ok) {
                    doPublishAck(packet.rid, "ALERTS_FAIL")
                } else {
                    Log.d(TAG, "告警回放已返回 rid=${packet.rid} size=${payload.length}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "告警回放构建/发送异常 rid=${packet.rid}", e)
                doPublishAck(packet.rid, "ALERTS_FAIL")
            }
        }
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
                Log.e(TAG, "caught exception", e)
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
                        // 重启后未点开被控端时：确保 FGS 作用域 + 悬浮窗就绪，再启动调度
                        if (!ForegroundRunningService.isRunning) {
                            try {
                                startForegroundService(
                                    Intent(this@MqttAgentService, ForegroundRunningService::class.java)
                                )
                            } catch (e: Exception) {
                                Log.e(TAG, "ACTION_START 拉起 FGS 失败", e)
                            }
                            kotlinx.coroutines.delay(1200)
                        }
                        KeepAliveReceiver.ensureFloatingWindow(this@MqttAgentService)
                        if (!FloatingWindowService.isRunning) {
                            kotlinx.coroutines.delay(500)
                        }
                        TaskScheduler.startTask()
                        "SUCCESS"
                    }
                    Protocol.ACTION_STOP -> {
                        TaskScheduler.stopTask()
                        "SUCCESS"
                    }
                    Protocol.ACTION_PUNCH -> {
                        // 打卡编排已解耦至 RemotePunchRunner：不要求通知监听服务运行，
                        // 结果确认仍由无障碍文本/截屏兜底链路完成
                        RemotePunchRunner.run(this@MqttAgentService, scope)
                        "SUCCESS"
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

    /** 把快照 JSON 经 resp 主题回给控制端（SecretBox 密封 + 会话签名）；返回 true 表示成功发布 */
    private fun publishResp(rid: String, field: String, json: String): Boolean {
        val client = mqttClient ?: return false
        if (!client.isConnected) return false
        val ts = System.currentTimeMillis()
        val session = MqttSecureConfig.loadSession()
        val wire = if (session.isNotBlank()) SecretBox.seal(session, json) else json
        val sign = if (session.isNotBlank())
            MqttSigner.sign(session, deviceId, ts, rid, field, "s", wire, Protocol.CMD_RESP)
        else ""
        val packet = MqttPacket(
            c = Protocol.CMD_RESP,
            f = field,
            v = PacketValue.StringValue(wire),
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
            Log.e(TAG, "caught exception", e)
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
        // 令牌在 PAIRING_TTL_MS 内可重复用于重试（防 PA 丢包）；PA 本身用令牌签名，防伪造 accept。
        Log.d(TAG, "配对令牌匹配 rid=${packet.rid}，开始派生会话密钥")
        val pairingToken = active.first
        val session = Hkdf.deriveHex(pairingToken, deviceId, Protocol.PAIRING_INFO, Protocol.SESSION_KEY_LEN)
        MqttSecureConfig.saveSession(session)
        SaveKeyValues.saveBoolean(Constant.IS_BOUND_KEY, true)
        _bound = true
        lastUnbindReason = "" // 重新配对成功，清除此前的解绑原因

        publishPairAccept(pairRid = packet.rid, pairingToken = pairingToken)
        // 配对成功后立即发布 online retained status，覆盖此前解绑时发布的 force_unbound/unbound，
        // 否则控制端重连订阅 status 时仍会收到旧的 retained 解绑消息，误触发解绑流程。
        scope.launch { publishStatus("online") }
        bindingStateListener?.invoke(true)
        updateNotification()
        "已与控制端完成配对".showToast()
    }

    /** 已配对时要求 UB 会话签名，防止公共 Broker 上仅凭 deviceId 伪造解绑 */
    private fun handleUnbound(packet: MqttPacket) {
        val session = MqttSecureConfig.loadSession()
        if (session.isNotBlank()) {
            if (!verifyWithSession(packet, session)) {
                Log.w(TAG, "忽略未验签/验签失败的解绑命令 rid=${packet.rid}")
                doPublishAck(packet.rid, "SIGN_FAIL")
                return
            }
            if (!acceptRid(packet.rid, packet.ts)) {
                Log.w(TAG, "忽略重放解绑命令 rid=${packet.rid}")
                return
            }
        }
        doUnbind(notifyController = false)
    }

    /** 已配对时 SYNC 须带会话签名；未配对允许无签名探活 */
    private fun handleSync(packet: MqttPacket) {
        val session = MqttSecureConfig.loadSession()
        if (session.isNotBlank()) {
            if (!verifyWithSession(packet, session)) {
                doPublishAck(packet.rid, "SIGN_FAIL")
                return
            }
            if (!acceptRid(packet.rid, packet.ts)) return
        }
        doPublishAck(packet.rid, "ONLINE")
    }

    /** 控制端主动解绑 / 被控端强制解绑：仅清绑定态，保留 MQTT 配置 */
    private fun doUnbind(notifyController: Boolean) {
        val wasBound = SaveKeyValues.loadBoolean(Constant.IS_BOUND_KEY, false)
        // 先用会话签了解绑状态信封，再清密钥（否则公共 Broker 上 plain unbound 可被伪造）
        val sessionForSign = MqttSecureConfig.loadSession()
        val statusText = if (notifyController) "force_unbound" else "unbound"
        SaveKeyValues.saveBoolean(Constant.IS_BOUND_KEY, false)
        _bound = false
        MqttSecureConfig.saveSession("")
        SaveKeyValues.saveString(Constant.MQTT_PAIRING_TOKEN_KEY, "")
        SaveKeyValues.saveLong(Constant.MQTT_PAIRING_EXPIRY_KEY, 0L)
        recentRids.clear()
        // 警告：publishStatus 是 blocking 的 qos1 发布，绝不能在主线程执行
        // （被控端“强制解绑”按钮在主线程调用本方法，若在主线程 publish 会 ANR/闪退）。
        // 统一丢到 IO 协程域执行，无论本方法从主线程还是 MQTT 回调线程进入都安全。
        // 记录解绑原因，供远程控制页（RemoteControlFragment）区分“从未绑定 / 被控制端移除 / 本机强制解绑”文案。
        // notifyController=true 表示本机强制解绑（需告知控制端），false 表示控制端主动解绑。
        lastUnbindReason = if (notifyController) "force" else "remote"
        if (wasBound || notifyController) {
            Log.d("MqttAgentService", "doUnbind notifyController=$notifyController reason=${lastUnbindReason} -> 即将发布 retained status=$statusText")
            scope.launch { publishStatus(statusText, sessionForSign) }
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
                val wire = SecretBox.seal(session, json)
                val ts = System.currentTimeMillis()
                val rid = UUID.randomUUID().toString()
                val sign = MqttSigner.sign(session, deviceId, ts, rid, "delta", "s", wire, Protocol.CMD_PUSH)
                val packet = MqttPacket(
                    c = Protocol.CMD_PUSH,
                    f = "delta",
                    v = PacketValue.StringValue(wire),
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
                Log.e(TAG, "caught exception", e)
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

    /**
     * 发布 status。
     * - online/offline：明文 retained（兼容 LWT）
     * - unbound/force_unbound：有会话时发签名 JSON 信封 retained（防公共 Broker 伪造 plain）；
     *   无会话时仍发明文（未配对场景）
     */
    private fun publishStatus(state: String, sessionForSign: String = "") {
        val client = mqttClient ?: return
        val isUnbind = state == "unbound" || state == "force_unbound"
        if (isUnbind && sessionForSign.isNotBlank()) {
            val ts = System.currentTimeMillis()
            val rid = UUID.randomUUID().toString()
            val sign = MqttSigner.sign(
                sessionForSign, deviceId, ts, rid, "", "s", state, Protocol.CMD_STATUS
            )
            val packet = MqttPacket(
                c = Protocol.CMD_STATUS,
                f = "",
                v = PacketValue.StringValue(state),
                rid = rid,
                ts = ts,
                sign = sign
            )
            client.publish(
                topicStatus(),
                MqttMessage(gson.toJson(packet).toByteArray()).apply { qos = 1; isRetained = true }
            )
        } else {
            client.publish(
                topicStatus(),
                MqttMessage(state.toByteArray()).apply { qos = 1; isRetained = true }
            )
        }
        MqttQuota.add(this, 1, 0)
    }

    /** PA 用配对令牌签名，并回填 pair 请求的 rid，供控制端验签防伪造 accept */
    private fun publishPairAccept(pairRid: String, pairingToken: String) {
        val client = mqttClient ?: return
        if (!client.isConnected) {
            Log.w(TAG, "publishPairAccept 跳过：未连接")
            return
        }
        Log.d(TAG, "回执配对确认 PA -> ${Protocol.TOPIC_PREFIX}/$deviceId/pair/accept")
        val ts = System.currentTimeMillis()
        val rid = pairRid.ifBlank { UUID.randomUUID().toString() }
        val sign = MqttSigner.sign(
            pairingToken, deviceId, ts, rid, "", "s", "OK", Protocol.CMD_PAIR_ACCEPT
        )
        val packet = MqttPacket(
            c = Protocol.CMD_PAIR_ACCEPT,
            f = "",
            v = PacketValue.StringValue("OK"),
            rid = rid,
            ts = ts,
            sign = sign
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

    /** 诊断（08-18）：采样当前网络可用性描述（activeNetwork + VALIDATED/INTERNET），掉线时落盘定位用 */
    private fun networkStateDesc(): String = runCatching {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = cm?.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val internet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        "active=${network != null} validated=$validated internet=$internet"
    }.getOrDefault("采样异常")

    /** 进程内重连任务（带去重）：多个触发源（3s 延迟 / 网络恢复 / 救援闹钟 / 手动按钮）并发时只保留一个在跑 */
    @Volatile
    private var reconnectJob: kotlinx.coroutines.Job? = null

    /** P2：保护 reconnectJob「检查+赋值」的锁。触发源分布在多线程（系统网络线程 / IO 协程 / 主线程），
     * 不加锁并发通过检查会创建两个重连 job → 两次无效 reconnect() */
    private val reconnectLock = Any()

    /** 统一入口：已连接或已有重连在跑则跳过，否则在 IO 协程上执行一次重连 */
    private fun launchReconnectOnce() {
        if (_connected) return
        // P2：开关守卫 —— 关闭 MQTT 开关到 onDestroy 执行之间的短窗口内，心跳/闹钟可能触发此处；
        // 不拦截会发起一次真实连接尝试，短暂违背「关闭零耗电」。broker 未配置（mqttClient == null）
        // 时每次触发都是空转，一并挡掉。
        if (!SaveKeyValues.loadBoolean(Constant.MQTT_ENABLED_KEY, false)) return
        if (mqttClient == null) return
        // P2：检查+赋值必须原子（synchronized 内只做快速检查与 launch，不会持有锁等待协程执行）
        synchronized(reconnectLock) {
            if (reconnectJob?.isActive == true) return
            reconnectJob = scope.launch {
                try {
                    reconnect()
                } catch (e: Exception) {
                    Log.e(TAG, "重连协程异常", e)
                } finally {
                    // P1：重连兜底 —— Paho reconnect() 非阻塞、同步路径不抛异常，失败由 Paho 内部
                    // 循环续排；但若 Paho 循环停摆（注释里担心的「饿死」镜像场景），下次兜底要等
                    // 15min 心跳。这里在 job 结束后仍未连接则补排 Android 复活闹钟（指数退避，最快 30s，
                    // 进程被杀也能拉起）。若重连实际已成功（connectComplete 稍后回调），
                    // onConnected 会 cancelResurrect 取消误排；isConnected 判断也能挡掉已连上的情况。
                    if (!_connected && mqttClient?.isConnected != true) {
                        scheduleResurrectWithBackoff()
                    }
                }
            }
        }
    }

    /**
     * 重连：必须用 Paho 的 reconnect() 而不是 connect()！
     *
     * MqttAsyncClient.reconnect() 内部是 stopReconnectCycle() + attemptReconnect()：
     *  - 先停掉 Paho 自己的自动重连定时器，避免与我们手动触发的一次性连接抢同一个连接槽位；
     *  - 失败时 MqttReconnectActionListener.onFailure 会重新排程自动重连循环（退避）。
     * 若改用 connect()，在 Paho 两次重试的间隙被我们抢走连接槽位后：
     *  - Paho 的 ReconnectTask 到时发现 isConnecting → 抛 32110，循环不再续排；
     *  - 我们这次 connect() 失败（ConnectBG 超时）时 userCallback 为空，onFailure 也不会续排循环，
     *    于是 Paho 自动重连循环被永久饿死——只能靠手动按钮救活。这正是 2e138c2 引入的回归。
     *
     * 该调用为非阻塞（立即返回），连接成功后由 connectComplete(reconnect=true) 回调统一订阅/上线/置位，
     * 失败则由 Paho 循环继续退避重试，这里不需要（也不应该）立刻置位 _connected。
     */
    private fun reconnect() {
        try {
            if (mqttClient?.isConnected == true) return
            mqttClient?.reconnect()
        } catch (e: Exception) {
            // 32110「已在进行连接」= Paho 自动重连正在连接中，本调用撞车属正常竞争，
            // Paho 自己的循环会完成连接。这里只记日志，绝不置位断开/排复活闹钟——
            // 否则会与并发成功的连接互相覆盖状态、并叠加闹钟（曾经的震荡放大器）。
            // 其它异常同样交由 Paho 自动重连循环（onFailure 会续排退避）兜底。
            Log.w(TAG, "重连撞上正在进行的连接（Paho 自动重连会完成），忽略：${e.message}")
        }
    }

    /**
     * 保活级别升级后重建 MQTT 连接。
     *
     * pingSender（Timer / Alarm）在 MqttClient 构造时注入，无法动态更换，因此级别切换必须
     * 关闭旧 client 并按新级别重新创建。流程：关闭旧连接（disconnect 会停掉 Paho 自动重连循环，
     * 避免与新连接抢同一 clientId 槽位）→ 释放闹钟/屏幕锁 → 调 initMqtt 按当前级别重建并连接。
     * 在 IO 协程执行（connect 阻塞最长 10s）；isRebuilding 防重入与防 connectionLost 循环触发。
     */
    private fun rebuildMqttClient() {
        if (isRebuilding) return
        if (mqttClient == null) return
        isRebuilding = true
        scope.launch {
            try {
                LogFileManager.action(
                    "保活级别重建（${MqttKeepAliveStrategy.current().name}），重建 MQTT 连接"
                )
                // 关闭旧连接：disconnect 会停掉 Paho isAutomaticReconnect 循环；close 释放内部资源
                runCatching { mqttClient?.disconnect() }
                runCatching { mqttClient?.close() }
                mqttClient = null
                alarmPingSender?.stop()
                alarmPingSender = null
                // 注销 CPU 级别屏幕广播并释放息屏锁（升级/降级后按新级别重新决定是否持锁；
                // 若从 CPU 降级，receiver 不注销会残留并在息屏时误持锁）
                unregisterScreenStateReceiver()
                // 按新级别重新初始化并连接（initMqtt 内部读取 MqttKeepAliveStrategy.current()）
                initMqtt()
            } catch (e: Exception) {
                Log.e(TAG, "重建 MQTT 失败", e)
                onDisconnected()
                scheduleResurrectWithBackoff()
            } finally {
                isRebuilding = false
            }
        }
    }

    // ═══════ 设备ID占用仲裁（presence 协议，纯逻辑见 PresenceGuard） ═══════

    /** RECLAIM 检查：重连后等待 broker 补发 retained 占位牌的时长 */
    private val RECLAIM_RETAINED_WAIT_MS = 1_500L

    private fun topicPresence() = PresenceGuard.presenceTopic(Protocol.TOPIC_PREFIX, deviceId)

    /** 本机会话标识：持久化，跨进程死亡认领自己的旧占位牌 */
    private fun loadOrCreateSid(): String {
        var sid = SaveKeyValues.loadString(Constant.MQTT_PRESENCE_SID_KEY, "")
        if (sid.isBlank()) {
            sid = UUID.randomUUID().toString()
            SaveKeyValues.saveString(Constant.MQTT_PRESENCE_SID_KEY, sid)
        }
        return sid
    }

    /** presence 发布：轻量 JSON，QoS1，retained 可选；失败仅记日志（失败开放原则） */
    private fun publishPresenceMsg(json: String, retained: Boolean) {
        val client = mqttClient ?: return
        try {
            client.publish(
                topicPresence(),
                MqttMessage(json.toByteArray()).apply { qos = 1; isRetained = retained }
            )
            MqttQuota.add(this, 1, 0)
        } catch (e: Exception) {
            Log.w(TAG, "presence 发布失败: ${e.message}")
        }
    }

    /**
     * 连接建立后的统一入口：未上岗先跑占用仲裁，已上岗做 RECLAIM 检查后恢复。
     * 双触发由 [arbitrationGate] 去重；任何异常一律放行上岗（失败开放，不阻断既有连接能力）。
     */
    private fun enterServiceAsync(reconnect: Boolean) {
        if (instance !== this@MqttAgentService) return // 服务已销毁的迟到回调，直接拦截
        if (arbitrationPassed) {
            scope.launch {
                try {
                    if (checkReclaimAfterReconnect()) return@launch // 判定被接管：内部已完成停服处置
                    serveOnline()
                } catch (e: Exception) {
                    Log.e(TAG, "RECLAIM 检查异常（失败开放恢复）", e)
                    serveOnline()
                }
            }
            return
        }
        if (!arbitrationGate.compareAndSet(false, true)) {
            Log.d(TAG, "占用仲裁进行中，忽略本次重复触发")
            return
        }
        scope.launch {
            try {
                presenceSid = loadOrCreateSid()
                runArbitration()
            } catch (e: Exception) {
                Log.e(TAG, "占用仲裁异常（失败开放放行）", e)
                arbiter = null
                arbitrationPassed = true
                takeoverWatchUntilMs = System.currentTimeMillis() + PresenceGuard.TAKEOVER_WATCH_MS
                serveOnline()
            } finally {
                arbitrationGate.set(false)
            }
        }
    }

    /** 完整上线仲裁（仅未上岗时执行一次）：先挂状态机再订阅，抖动错峰后发 PRB，窗口结束裁决 */
    private suspend fun runArbitration() {
        val collector = PresenceArbitration(presenceSid)
        arbiter = collector
        val subOk = runCatching { subscribeWithDiag(topicPresence()) }.getOrDefault(false)
        if (!subOk) {
            arbiter = null
            LogFileManager.action("presence 订阅失败/被拒，跳过占用仲裁直接上岗（失败开放）")
            arbitrationPassed = true
            takeoverWatchUntilMs = System.currentTimeMillis() + PresenceGuard.TAKEOVER_WATCH_MS
            serveOnline()
            return
        }
        delay(400L + (0L..PresenceGuard.PROBE_JITTER_MS).random())
        publishPresenceMsg(PresenceGuard.encodeProbe(presenceSid, System.currentTimeMillis()), retained = false)
        delay(PresenceGuard.DECISION_WINDOW_MS)
        arbiter = null
        when (val verdict = collector.evaluate()) {
            is PresenceArbitration.Outcome.Win -> {
                LogFileManager.action("设备ID占用仲裁通过，本机上岗 sid=${presenceSid.take(8)}")
                arbitrationPassed = true
                takeoverWatchUntilMs = System.currentTimeMillis() + PresenceGuard.TAKEOVER_WATCH_MS
                serveOnline()
            }
            is PresenceArbitration.Outcome.Occupied -> {
                LogFileManager.action("设备ID占用冲突 reason=${verdict.reason} 对方sid=${verdict.foreignSid.take(8)}")
                handlePresenceConflict(verdict.reason, verdict.foreignSid, verdict.foreignTs)
            }
        }
    }

    /**
     * 重连后的 RECLAIM 检查：无外来新鲜占位牌/声明 → 直接恢复；有 → 发确认探测再裁决，
     * 窗口内收到 CLAIM 才判定被接管（防深睡在位者来不及应答被误判）。
     * @return true 表示判定被接管并已停服处置
     */
    private suspend fun checkReclaimAfterReconnect(): Boolean {
        lastForeignHb = null
        reclaimProbeSeenClaim = false
        reclaimProbeActive = true
        try {
            val ok = runCatching { subscribeWithDiag(topicPresence()) }.getOrDefault(false)
            if (!ok) return false // 无法观测 presence → 失败开放恢复服务
            delay(RECLAIM_RETAINED_WAIT_MS)
            val freshForeignHbSid = lastForeignHb
                ?.takeIf { (fsid, ts) ->
                    fsid != presenceSid && PresenceGuard.isHeartbeatFresh(ts, System.currentTimeMillis())
                }
                ?.first
            if (freshForeignHbSid == null && !reclaimProbeSeenClaim) return false
            LogFileManager.action("RECLAIM：发现疑似接管（HB=${freshForeignHbSid?.take(8)}），发确认探测")
            publishPresenceMsg(PresenceGuard.encodeProbe(presenceSid, System.currentTimeMillis()), retained = false)
            delay(PresenceGuard.DECISION_WINDOW_MS)
            if (!reclaimProbeSeenClaim) {
                LogFileManager.action("RECLAIM 确认探测无应答，按残留占位牌处理，恢复服务")
                return false
            }
            handlePresenceConflict(PresenceGuard.ConflictReason.RECLAIM, freshForeignHbSid, lastForeignHb?.second ?: 0L)
            return true
        } finally {
            reclaimProbeActive = false
        }
    }

    /** 上岗/恢复：订阅 cmd/pair + 发布 online + 启动占位牌心跳（幂等可重入） */
    private fun serveOnline() {
        try {
            // 被控端只接收 cmd / pair，resp 是自己发布给控制端的，不需要订阅
            val okCmd = subscribeWithDiag(topicCmd())
            val okPair = subscribeWithDiag(topicPair())
            if (!okCmd || !okPair) {
                "MQTT 订阅被 broker 拒绝：请检查 EMQX 中 DEV 账户（${SaveKeyValues.loadString(Constant.MQTT_USER_KEY, "")}）的 ACL 是否允许 ${Protocol.TOPIC_PREFIX}/$deviceId/#".showToast()
            }
            if (_bound) publishStatus("online")
        } catch (e: Exception) {
            Log.e(TAG, "caught exception", e)
            "MQTT 订阅/状态上报失败：${e.message}".showToast()
        }
        // 立即发布一条占位牌并启动进程内节流补发循环；息屏保鲜由 AlarmPingSender 钩子负责
        publishPresenceHb(force = true)
        startHeartbeatLoop()
    }

    /**
     * presence 消息分流（messageArrived 按主题进入，轻量 JSON 非 MqttPacket）。
     * 身份判定顺序：仲裁中喂状态机 / RECLAIM 确认期记声明 / 在位者应答+提醒 / 监听期内让位。
     * 核心规则：在位者绝不因仲裁停服，只有「挑战者身份」才走冲突停服。
     */
    private fun handlePresenceMessage(json: String) {
        if (instance !== this@MqttAgentService) return
        val msg = PresenceGuard.decode(json) ?: return
        if (msg.sid == presenceSid) return // 本机自发自收的回声
        // 1) 首次上线仲裁中：全部喂给决策状态机
        arbiter?.let { arb ->
            arb.onForeignMessage(msg)
            return
        }
        // 2) RECLAIM 确认探测期：坐实接管的唯一证据是对方实时 CLAIM
        if (reclaimProbeActive && msg.type == Protocol.CMD_CLAIM) {
            reclaimProbeSeenClaim = true
            return
        }
        when (msg.type) {
            Protocol.CMD_PROBE -> if (arbitrationPassed) {
                // 在位者应答敲门：回 CLM（按挑战者节流）+ 黄条提醒（同一挑战者去重）
                replyClaimToChallenger(msg.sid)
                noteForeignChallenge(msg.sid)
            }
            Protocol.CMD_CLAIM -> if (arbitrationPassed) {
                if (System.currentTimeMillis() < takeoverWatchUntilMs) {
                    // 上岗监听期内听到原占用者实时声明（如深睡刚醒、TCP 其实没死）→ 本机是事实上的挑战者，让位
                    LogFileManager.action("监听期内收到原占用者声明，本机让位 sid=${msg.sid.take(8)}")
                    handlePresenceConflict(PresenceGuard.ConflictReason.CONTESTED, msg.sid)
                } else {
                    // 监听期外的合法回归者已被 RECLAIM 门禁拦下，此处理论不可达；在位者绝不停服
                    Log.w(TAG, "收到外来 CLAIM（非监听期），忽略 sid=${msg.sid.take(8)}")
                }
            }
            Protocol.CMD_HEARTBEAT -> {
                // 仅记录最新外来占位牌供 RECLAIM 检查用；在位期间收到不做任何动作
                lastForeignHb = msg.sid to msg.ts
            }
        }
    }

    /** 回 CLM 给敲门者（按挑战者 sid 节流，避免重复探测刷流量） */
    private fun replyClaimToChallenger(challengerSid: String) {
        val now = System.currentTimeMillis()
        if (challengerSid == lastClaimReplySid &&
            now - lastClaimReplyAtMs < PresenceGuard.CLAIM_REPLY_THROTTLE_MS
        ) return
        lastClaimReplySid = challengerSid
        lastClaimReplyAtMs = now
        publishPresenceMsg(PresenceGuard.encodeClaim(presenceSid, now), retained = false)
    }

    /**
     * 异常接入提醒 + 上报控制端（同一挑战者只处理一次）：
     * 本机不停服，仅黄条横幅提醒；id_conflict 由持会话密钥的在位者代报给控制端。
     */
    private fun noteForeignChallenge(challengerSid: String) {
        synchronized(challengedSidsReported) {
            if (!challengedSidsReported.add(challengerSid)) return
        }
        LogFileManager.action("检测到同设备ID设备尝试上线（sid=${challengerSid.take(8)}），已拒绝其接入")
        val payload = org.json.JSONObject().apply {
            put("ts", System.currentTimeMillis())
            put("challenger", challengerSid.take(8))
        }.toString()
        SaveKeyValues.saveString(Constant.REMOTE_ID_ALERT_BANNER_KEY, payload)
        runCatching { idAlertListener?.invoke() }
        reportIdConflictToController(challengerSid)
    }

    /** 经 alert 通道向控制端代报异常接入事件；走静态 publishAlert 入口以同时写入本地缓冲 */
    private fun reportIdConflictToController(challengerSid: String) {
        val json = org.json.JSONObject().apply {
            put("type", Protocol.ALERT_TYPE_ID_CONFLICT)
            put("challenger", challengerSid.take(8))
            put("ts", System.currentTimeMillis())
        }.toString()
        MqttAgentService.publishAlert(json)
    }

    /** 占位牌发布：retained QoS1；非 force 时按 HB_MIN_INTERVAL_MS 节流（蹭既有唤醒窗口省电） */
    private fun publishPresenceHb(force: Boolean) {
        if (mqttClient?.isConnected != true) return
        val now = System.currentTimeMillis()
        if (!force && now - lastHbPublishedAtMs < PresenceGuard.HB_MIN_INTERVAL_MS) return
        lastHbPublishedAtMs = now
        publishPresenceMsg(PresenceGuard.encodeHeartbeat(presenceSid, now), retained = true)
    }

    /** 心跳保鲜检查：距上次发布超过 HB_MIN_INTERVAL_MS 才真正补发（供循环与唤醒钩子调用） */
    private fun refreshPresenceHbIfDue() {
        if (!arbitrationPassed) return
        if (System.currentTimeMillis() - lastHbPublishedAtMs < PresenceGuard.HB_MIN_INTERVAL_MS) return
        publishPresenceHb(force = true)
    }

    /** 进程活跃期补发占位牌的轻量循环（不排闹钟不持锁，Doze 冻结无害） */
    private fun startHeartbeatLoop() {
        if (hbLoopJob?.isActive == true) return
        hbLoopJob = scope.launch {
            while (true) {
                delay(30_000L)
                runCatching { refreshPresenceHbIfDue() }
            }
        }
    }

    private fun stopHeartbeatLoop() {
        hbLoopJob?.cancel()
        hbLoopJob = null
    }

    /** 优雅停止时清掉 retained 占位牌，避免挑战者在过期窗口内误判冲突 */
    private fun clearPresenceRetainedPayload() {
        val client = mqttClient ?: return
        if (!client.isConnected) return
        try {
            client.publish(
                topicPresence(),
                MqttMessage(ByteArray(0)).apply { qos = 1; isRetained = true }
            )
            MqttQuota.add(this, 1, 0)
        } catch (_: Exception) {
        }
    }

    /**
     * 冲突处置（仅挑战者身份走到这里）：持久化标志+原因码 → 静默断开停服 → 取消自动拉起闹钟。
     * 不发 offline：本机从未上岗或已让位，不应污染共享 status retained。
     */
    private fun handlePresenceConflict(reason: PresenceGuard.ConflictReason, foreignSid: String?, foreignTs: Long = 0L) {
        if (instance !== this@MqttAgentService) return
        SaveKeyValues.saveBoolean(Constant.MQTT_ID_CONFLICT_KEY, true)
        SaveKeyValues.saveString(Constant.MQTT_ID_CONFLICT_REASON_KEY, reason.name)
        SaveKeyValues.saveLong(Constant.MQTT_ID_CONFLICT_FOREIGN_TS_KEY, foreignTs)
        // 冲突停服时通知 UI 立即刷新（否则用户点"开启"后会一直显示"连接中…"直到切 Tab）
        runCatching { conflictListener?.invoke() }
        // 同步把远程总开关置为关，让 UI 开关与实际状态一致（一次点击即可重新开启并重新仲裁）
        SaveKeyValues.saveBoolean(Constant.MQTT_ENABLED_KEY, false)
        LogFileManager.error("设备ID冲突停服 reason=$reason foreignSid=${foreignSid?.take(8)}")
        stopHeartbeatLoop()
        Thread {
            try { mqttClient?.disconnectForcibly(2_000) } catch (_: Exception) { }
            try { mqttClient?.close() } catch (_: Exception) { }
        }.start()
        // 状态收口：冲突提示由远程页置顶红色横幅常驻展示
        _connected = false
        stateListener?.invoke(false)
        updateNotification()
        cancelResurrectAlarms(this)
        KeepAliveReceiver.cancelRescueAlarm(this)
        stopSelf()
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
    private fun publishAlertInternal(json: String, ridOverride: String? = null) {
        val client = mqttClient ?: return
        if (!client.isConnected) return
        val session = MqttSecureConfig.loadSession()
        if (session.isBlank()) return // 未配对不推送
        scope.launch {
            try {
                val wire = SecretBox.seal(session, json)
                val ts = System.currentTimeMillis()
                // rid 与缓冲 aid 同源（publishAlert 传入）：实时/回放共用去重键；直连调用无 override 时自生成
                val rid = ridOverride?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
                val sign = MqttSigner.sign(session, deviceId, ts, rid, "alert", "s", wire, Protocol.CMD_ALERT)
                val packet = MqttPacket(
                    c = Protocol.CMD_ALERT,
                    f = "alert",
                    v = PacketValue.StringValue(wire),
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
                Log.e(TAG, "caught exception", e)
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
            Log.e(TAG, "caught exception", e)
            Log.w(TAG, "订阅异常: $topic ${e.message}")
            false
        }
    }

    /** A2：带指数退避的复活调度。前 3 次用 30s 快速重试（应对短暂失联），
     * 之后按 2^n×60s 退避（120s→240s→…），上限 15min；连接成功后由 onConnected 重置计数。
     * 注意：resurrectAttempt 先参与计算再自增，得到序列 30/30/30/120/240/… */
    private fun scheduleResurrectWithBackoff() {
        if (!SaveKeyValues.loadBoolean(Constant.MQTT_ENABLED_KEY, false)) return
        // 「后台自启」总开关：关闭时不安排复活闹钟，进程被杀后不再尝试自启
        if (!KeepAliveReceiver.isKeepAliveEnabled()) return
        // 设备ID冲突停服中：不再排复活闹钟（用户手动开启时清除标志后由 UI 拉起）
        if (isIdConflictBlocked()) return
        val delay = if (resurrectAttempt < 3) 30_000L
        else minOf((1L shl (resurrectAttempt - 2)) * 60_000L, 15 * 60_000L)
        resurrectAttempt++
        Log.d(TAG, "安排复活闹钟(退避) attempt=$resurrectAttempt delay=${delay}ms")
        nextReconnectAtMs = System.currentTimeMillis() + delay
        scheduleResurrect(delay)
    }

    /** 复活闹钟：进程/服务被杀或长时间断线后兜底 —— 统一走 KeepAliveReceiver，避免直接狂拉 FGS */
    private fun scheduleResurrect(delayMs: Long) {
        if (!SaveKeyValues.loadBoolean(Constant.MQTT_ENABLED_KEY, false)) return
        if (!KeepAliveReceiver.isKeepAliveEnabled()) return
        // 冲突停服中：复活链条安静（同上，避免撞车循环）
        if (isIdConflictBlocked()) return
        nextReconnectAtMs = System.currentTimeMillis() + delayMs
        KeepAliveReceiver.scheduleRescue(this, delayMs, "mqtt_resurrect")
    }

    private fun cancelResurrect() {
        nextReconnectAtMs = 0L
        KeepAliveReceiver.cancelRescueAlarm(this)
    }

    /**
     * 息屏保活：获取 WifiLock(WIFI_MODE_FULL)。
     *
     * 真机定位结论：息屏后系统会把 WiFi 驱动置入 suspend 挂起（wpa_supplicant SETSUSPENDMODE 0），
     * 期间 TCP 心跳/数据完全无法收发 → Paho 心跳 32s 无 PINGRESP 判死（connectionLost「等待来自服务器的
     * 响应时超时 32000」）→ broker 发 LWT offline → 控制端显示离线、远控失败。
     * 注意：app 在 Doze 白名单也无效——WiFi suspend 是独立于 Doze 的省电机制，白名单不豁免。
     * 唯一有效手段是持有锁阻止挂起；选 WifiLock 而非 PARTIAL_WAKE_LOCK：只保 WiFi 常驻（阻止 suspend）、不强制射频全速、CPU 仍可睡眠，
     * 更省电，契合「被控端常驻」场景。
     *
     * 方案 A（省电优化，当前版本）：WifiLock 不再全程常驻，改为按需持有——
     * - ALARM 模式（默认，绝大多数设备）：不在此处获取；改由 AlarmPingSender 在每次发 PINGREQ 的窗口内
     *   短时持有（acquire(timeout)），息屏间隙 WiFi 可 suspend 挂起，比常驻明显省电；
     *   连接靠 80s 内必有 PINGREQ 活动保活，NAT/会话不断连。
     * - CPU 模式（兜底，仅掉线频繁设备）：由 acquireScreenWakeLock 在息屏时持续获取、releaseScreenWakeLock
     *   在亮屏时释放（与 PARTIAL_WAKE_LOCK 同生命周期），保证极不可靠设备连接稳定（行为同旧版常驻）。
     *
     * WAKE_LOCK 权限 manifest 已声明；「关闭零耗电」由开关守卫（onCreate/onStartCommand 早退）维持。
     */
    @Suppress("DEPRECATION") // WIFI_MODE_FULL 在 API 30 弃用；FULL 已足够阻止 WiFi suspend，无需 HIGH_PERF 强制全速（WifiLock 整体弃用前无等价替代）
    private fun acquireWifiLock() {
        runCatching {
            val wm = getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            if (wifiLock == null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL, "daily_task_mqtt_wifilock").apply {
                    setReferenceCounted(false)
                }
            }
            if (wifiLock?.isHeld != true) wifiLock?.acquire()
            Log.d(TAG, "已获取 WifiLock（息屏保活）")
        }.onFailure { e ->
            // 个别 ROM 对 WifiLock 有限制，拿不到不崩溃，仅告警（掉线由 Paho 自动重连 + 保活闹钟兜底）
            Log.w(TAG, "获取 WifiLock 失败（息屏后可能掉线，由重连机制兜底）: ${e.message}")
        }
    }

    private fun releaseWifiLock() {
        runCatching {
            if (wifiLock?.isHeld == true) wifiLock?.release()
            wifiLock = null
            Log.d(TAG, "已释放 WifiLock")
        }.onFailure { e ->
            Log.w(TAG, "释放 WifiLock 异常: ${e.message}")
        }
    }

    /**
     * 命令处理持锁：收到控制端指令时 acquire 30s 超时短时锁，覆盖 handleIncoming 及其异步处理。
     * 不在 messageArrived 同步返回时提前 release（异步尚未完成）；超时自动释放防泄漏。
     * 幂等：已持有则不再重复 acquire（非引用计数锁）。
     */
    private fun acquireCommandWakeLock() {
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            if (commandWakeLock == null) {
                commandWakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "daily_task_mqtt_command"
                ).apply { setReferenceCounted(false) }
            }
            if (commandWakeLock?.isHeld != true) commandWakeLock?.acquire(30_000L)
        }.onFailure { e ->
            Log.w(TAG, "获取命令处理 WakeLock 失败: ${e.message}")
        }
    }

    /** 释放命令处理锁。幂等；onDestroy 等路径可显式调用 */
    private fun releaseCommandWakeLock() {
        runCatching {
            if (commandWakeLock?.isHeld == true) commandWakeLock?.release()
        }.onFailure { _ -> }
    }

    // ===== CPU 保活级别（兜底）：息屏持 PARTIAL_WAKE_LOCK，保 CPU 调度（Timer 心跳线程持续运行）=====

    /**
     * 注册屏幕广播并做初始对齐。仅 CPU 级别启用：
     * - SCREEN_OFF：持有 PARTIAL_WAKE_LOCK，CPU 不深睡 → Paho TimerPingSender 线程始终被调度，
     *   心跳必然可靠（对 WifiLock/闹钟都无法保活的极端深睡设备兜底）
     * - SCREEN_ON：释放，亮屏时 CPU 本就活跃，不白耗电
     */
    private fun registerScreenStateReceiver() {
        if (screenReceiverRegistered) return
        try {
            ContextCompat.registerReceiver(
                this, screenStateReceiver, IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            screenReceiverRegistered = true
        } catch (_: Exception) {
            try {
                registerReceiver(screenStateReceiver, IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                })
                screenReceiverRegistered = true
            } catch (_: Exception) {
                Log.w(TAG, "注册屏幕广播失败（CPU 保活降级：无法按屏幕状态持锁）")
            }
        }
        // 初始对齐：服务启动时若已处于息屏（如保活闹钟在后台拉起），立即持有，避免空窗掉线
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm?.isInteractive == false) acquireScreenWakeLock()
    }

    /** 注销屏幕广播并释放锁（onDestroy / 级别重建时调用）。幂等 */
    private fun unregisterScreenStateReceiver() {
        if (screenReceiverRegistered) {
            screenReceiverRegistered = false
            try { unregisterReceiver(screenStateReceiver) } catch (_: Exception) { }
        }
        releaseScreenWakeLock()
    }

    /**
     * 息屏保活（CPU 模式）：持有 PARTIAL_WAKE_LOCK 保 CPU 调度，并同步持续持有 WifiLock 阻止 WiFi suspend。
     * WifiLock 在 CPU 兜底模式保持息屏常驻（与 screenWakeLock 同生命周期），保证极不可靠设备连接稳定。
     * ALARM 模式不调用本方法，改由 AlarmPingSender 在 PING 窗口短时持有 WifiLock（方案 A）。幂等。
     */
    private fun acquireScreenWakeLock() {
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            if (screenWakeLock == null) {
                screenWakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "daily_task_mqtt_screen_wakelock"
                ).apply { setReferenceCounted(false) }
            }
            if (screenWakeLock?.isHeld != true) screenWakeLock?.acquire()
            Log.d(TAG, "已获取 PARTIAL_WAKE_LOCK（CPU 保活）")
            // CPU 兜底模式：息屏常驻 WifiLock（与 screenWakeLock 同生命周期），阻止 WiFi suspend 断心跳
            acquireWifiLock()
        }.onFailure { e ->
            Log.w(TAG, "获取 PARTIAL_WAKE_LOCK 失败（心跳可能停发，由重连机制兜底）: ${e.message}")
        }
    }

    /** 释放 PARTIAL_WAKE_LOCK 与常驻 WifiLock（亮屏省电）。幂等 */
    private fun releaseScreenWakeLock() {
        runCatching {
            if (screenWakeLock?.isHeld == true) screenWakeLock?.release()
            Log.d(TAG, "已释放 PARTIAL_WAKE_LOCK")
            // 同步释放 CPU 模式常驻 WifiLock（ALARM 模式的 PING 窗口锁由 AlarmPingSender 自行管理）
            releaseWifiLock()
        }.onFailure { e ->
            Log.w(TAG, "释放 PARTIAL_WAKE_LOCK 异常: ${e.message}")
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // 已连接则无需打扰；短时间去抖，避免 WiFi↔蜂窝抖动连着重连费电
                if (_connected) return
                val now = System.currentTimeMillis()
                if (now - lastNetworkReconnectAtMs < 8_000L) return
                lastNetworkReconnectAtMs = now
                if (!SaveKeyValues.loadBoolean(Constant.MQTT_ENABLED_KEY, false)) return
                if (KeepAliveReceiver.isPaused()) return
                LogFileManager.writeLog("网络恢复：尝试 MQTT 重连")
                launchReconnectOnce()
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            networkCallback = cb
        } catch (e: Exception) {
            Log.e(TAG, "注册网络回调失败", e)
        }
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        networkCallback = null
        try {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb)
        } catch (_: Exception) {
        }
    }

    /** 任务时间点格式 HH:mm:ss（与控制端约定一致） */
    private val TASK_TIME_PATTERN = Regex("""^([01]\d|2[0-3]):[0-5]\d:[0-5]\d$""")



    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A1：开关守卫 —— 关闭时返回 START_NOT_STICKY，不让系统因内存回收自动拉起并绕过开关重连；
        // 仅开关开启时返回 START_STICKY（被杀后由系统重新拉起并继续连接）。
        if (!SaveKeyValues.loadBoolean(Constant.MQTT_ENABLED_KEY, false)
            || !KeepAliveReceiver.isKeepAliveEnabled()
            || isIdConflictBlocked()
        ) {
            try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) { }
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // 用户划掉任务卡片后，若远程开关仍开启，则安排复活闹钟兜底
        // 「后台自启」总开关关闭时不安排复活，直到用户手动重新启动
        if (SaveKeyValues.loadBoolean(Constant.MQTT_ENABLED_KEY, false)
            && KeepAliveReceiver.isKeepAliveEnabled()
        ) {
            scheduleResurrect(delayMs = 5_000L)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterNetworkCallback()
        releaseWifiLock()
        // 显式停掉心跳闹钟并注销接收器（Paho close 也会调 stop，此处幂等兜底；
        // 置空引用防服务重建时旧实例残留）
        alarmPingSender?.stop()
        alarmPingSender = null
        // CPU 保活级别：注销屏幕广播并释放息屏锁
        unregisterScreenStateReceiver()
        releaseCommandWakeLock()
        try { unregisterReceiver(remoteChangedReceiver) } catch (_: Exception) { }
        _connected = false
        instance = null
        stateListener = null
        bindingStateListener = null
        // 停掉占位牌心跳协程（scope.cancel 亦会取消，此处显式兜底）
        stopHeartbeatLoop()
        scope.cancel()
        MqttQuota.onDisconnect(this)
        // 优雅停止：先清 retained 占位牌再发 offline（冲突停服路径两者都不发——
        // 本机从未上岗或已让位，不应污染共享 retained）。
        // publishStatus/disconnect 是 Paho 同步调用，必须移出主线程避免卡 UI。
        val conflictBlocked = isIdConflictBlocked()
        val wasServing = arbitrationPassed && !conflictBlocked
        Thread {
            try { if (wasServing) clearPresenceRetainedPayload() } catch (_: Exception) { }
            try { if (!conflictBlocked && mqttClient?.isConnected == true) publishStatus("offline") } catch (_: Exception) { }
            try {
                mqttClient?.disconnect()
                mqttClient?.close()
            } catch (_: Exception) { }
        }.start()
        // 如果开关仍开启且未暂停使用，安排复活闹钟兜底重连；
        // 「暂停使用」或正常关 MQTT 开关时一律取消复活闹钟，避免安静期被再次拉起。
        // FGS 配额失败路径已单独排了 10 分钟救援，此处不再短退避连撞。
        if (quitDueToFgsQuota) {
            // keep scheduled FgsQuotaBackoff
        } else if (SaveKeyValues.loadBoolean(Constant.MQTT_ENABLED_KEY, false)
            && KeepAliveReceiver.isKeepAliveEnabled()
        ) {
            scheduleResurrectWithBackoff()
        } else {
            cancelResurrect()
            stopForeground(STOP_FOREGROUND_REMOVE)
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
