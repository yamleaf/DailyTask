package com.pengxh.daily.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.pengxh.daily.app.utils.LogFileManager
import com.pengxh.daily.app.utils.LogLevel
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttClientPersistence
import org.eclipse.paho.client.mqttv3.MqttPingSender
import org.eclipse.paho.client.mqttv3.MqttToken
import org.eclipse.paho.client.mqttv3.internal.ClientComms
import org.eclipse.paho.client.mqttv3.internal.wire.MqttPingReq

/**
 * MQTT 保活自适应第二级（ALARM）：以系统精确闹钟替代 Paho TimerPingSender 的 java.util.Timer
 * 线程调度心跳。由 MqttKeepAliveStrategy 在 ALARM 级别驱动。
 *
 * 背景：息屏且未持 CPU 锁时设备可进入深睡（s2idle），Paho 的 TimerPingSender 线程不被调度，
 * PINGREQ 停发 → broker 在 keepalive×1.5（本项目 240s×1.5=360s）后踢线失联。系统精确闹钟
 * （setExactAndAllowWhileIdle）在深睡时仍可唤醒 CPU 投递广播，从而替代线程调度驱动心跳。
 *
 * 原理：MqttPingSender 由 Paho 驱动 —— 连接成功 ClientState.connected() 调 start()；每次
 * checkForActivity() 内部按 keepalive 剩余时间调 schedule(delay)；关闭 ClientComms.close() 调 stop()。
 * 本实现把「到时执行」从 Timer 线程改为系统精确闹钟（App 已在电池优化白名单内不延迟；
 * 精确闹钟权限缺失时降级 set）：
 *   1. start() 排第一个闹钟（delay = keepAlive ms，与 TimerPingSender.start() 行为一致）；
 *   2. 闹钟到点 → 系统唤醒 CPU 投递广播 → acquire 5s 超时 PARTIAL_WAKE_LOCK（够 PINGREQ 发出 +
 *      PINGRESP 返回 + 下一轮 schedule，防处理中 CPU 睡回）→ comms.checkForActivity()；
 *   3. Paho 内部判定该发则发 PINGREQ，并再次 schedule 下一轮 → 5s 后锁自动释放，CPU 回到深睡。
 * 结果：CPU 活跃占比 ~2%（5s/240s），远低于息屏全程持锁。
 *
 * 进程被杀：动态注册的 receiver 随进程消失，PING 闹钟广播被丢一次不阻塞业务 —— 连接本身已断，
 * 由 KeepAliveReceiver（静态注册）的复活/心跳闹钟拉起服务重建连接后，Paho 重新 start()/schedule()。
 */
class AlarmPingSender(context: Context) : MqttPingSender {

    private val appContext = context.applicationContext
    private var comms: ClientComms? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * 占位牌心跳钩子（MqttAgentService 注入）：蹭 PING 闹钟唤醒窗口补发 presence HB，
     * 不新增闹钟/WakeLock；连接态与节流由实现方判断，异常隔离不影响 PING 链路。
     */
    @Volatile
    var onPingHook: (() -> Unit)? = null

    /** WifiLock(WIFI_MODE_FULL)：方案 A —— 仅 PING 窗口持有，息屏间隙 WiFi 可 suspend 省电 */
    private var wifiLock: WifiManager.WifiLock? = null
    /** 主线程 Handler：WifiLock 无 acquire(timeout) 重载，用 postDelayed 模拟超时释放 */
    private val mainHandler = Handler(Looper.getMainLooper())
    /** PING 窗口超时释放 WifiLock 的回调（stop() 与超时均安全调用，幂等） */
    private val wifiLockReleaseRunnable = Runnable { releaseWifiLockNow() }

    @Volatile
    private var receiverRegistered = false

    /** PING 诊断（08-18）：上次闹钟唤醒的 elapsedRealtime，用于计算实际唤醒间隔（判断闹钟是否被 Doze 节流） */
    @Volatile
    private var lastPingWakeElapsedMs = 0L

    private val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /** 动态注册接收 PING 闹钟广播；闹钟到点由系统唤醒 CPU 后在此触发心跳 */
    private val pingReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action != ACTION_PING) return
            Log.d(TAG, "PING 闹钟到点：唤醒 CPU 发心跳")
            acquireWakeLock()
            // 方案 A：PING 窗口获取 WifiLock，阻止本窗口内 WiFi suspend（息屏间隙仍允许 suspend 省电）
            acquireWifiLock()
            // PING 诊断（降级 Log.d，logcat 保留；诊断结论已定，不再落盘刷日志）：
            // 采样唤醒间隔 + 网络状态，用于核对闹钟节奏与网络可用性
            val wakeAtElapsed = SystemClock.elapsedRealtime()
            val sinceLastWake = if (lastPingWakeElapsedMs > 0L) wakeAtElapsed - lastPingWakeElapsedMs else -1L
            lastPingWakeElapsedMs = wakeAtElapsed
            val (netOk, netDesc) = sampleNetwork()
            Log.d(TAG, "PING 唤醒：距上次=${if (sinceLastWake < 0) "首次" else "${sinceLastWake / 1000}s"} 网络=$netDesc")
            if (!netOk) {
                // 唤醒瞬间网络未验证可用：后台轮询记录「网络恢复耗时」（Log.d，不刷盘）
                observeNetworkRecovery(wakeAtElapsed)
            }
            try {
                // 问题2修复（08-18 实测实证）：Paho checkForActivity 的「该发 PINGREQ」判定在闹钟驱动下
                // 持续 false（① PINGRESP 一次没回导致 pingOutstanding 卡住，之后永远不发新 ping；
                // ② 重连后 lastOutbound=CONNECT 时刻，240s 边界毫秒误差判定未到点），心跳从未实际发出
                // → broker 360s 踢线（实测 3 次唤醒 0 次 PINGREQ）。
                // 改为闹钟到点【无条件强制发 PINGREQ】：sendNoWait 直接入队（已连接时由 CommsSender 写出，
                // 不经过"该发判定"）；随后 checkForActivity 仅用于让 Paho 更新内部计时并排下一轮闹钟。
                // 注意：token 必须非 null——实测传 null 触发 Paho internalSend 内部 token.getClient() NPE
                // （反编译确认：internalSend 会自动把 comms 的 client 设进 token，传 MqttToken(clientId) 即可）。
                val commsRef = comms
                if (commsRef != null && commsRef.isConnected) {
                    commsRef.sendNoWait(MqttPingReq(), MqttToken(commsRef.getClient().clientId))
                    Log.d(TAG, "PING 强制发送 PINGREQ")
                    commsRef.checkForActivity()
                    // 蹭本次唤醒窗口补发占位牌（连接态与节流由钩子实现方判断），异常隔离
                    runCatching { onPingHook?.invoke() }
                } else {
                    // 未连接（掉线重连中）：Paho 重连机制兜底，本帧跳过
                    Log.d(TAG, "PING 未连接，跳过强制发送")
                }
            } catch (e: Exception) {
                Log.w(TAG, "强制发送 PINGREQ 异常（Paho 重连机制兜底）: ${e.message}")
                LogFileManager.writeLog(LogLevel.W, "PING诊断 强制发送 PINGREQ 异常：${e.message}")
                // 兜底重排下一轮闹钟，防止闹钟链断裂（异常时 checkForActivity 未执行，无下一轮排期）
                runCatching { schedule(comms?.getKeepAlive()?.takeIf { it > 0 } ?: DEFAULT_KEEPALIVE_MS) }
            }
        }
    }

    /**
     * 采样当前网络状态，返回 (网络是否可用, 描述串)。
     * 可用 = 存在 activeNetwork 且 NET_CAPABILITY_VALIDATED（系统已验证可上网）；描述串含 WiFi ssid/rssi/速率。
     */
    private fun sampleNetwork(): Pair<Boolean, String> = runCatching {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = cm?.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val internet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val wifiInfo = getWifiInfo()
        val desc = buildString {
            append("active=${network != null} validated=$validated internet=$internet")
            if (wifiInfo != null) append(" $wifiInfo")
        }
        (network != null && validated) to desc
    }.getOrDefault(false to "采样异常")

    @Suppress("DEPRECATION") // WifiManager.getConnectionInfo 在 API 31 起 discouraged，诊断期仍可用
    private fun getWifiInfo(): String? = runCatching {
        val wm = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        val info = wm.connectionInfo ?: return null
        val ssid = info.ssid?.takeIf { it.isNotBlank() && it != "<unknown ssid>" } ?: return null
        "ssid=$ssid rssi=${info.rssi} link=${info.linkSpeed}Mbps"
    }.getOrNull()

    /** 唤醒时网络未验证可用 → 后台线程轮询记录「网络恢复耗时」（最多 3s），仅诊断，不阻塞主线程 */
    private fun observeNetworkRecovery(wakeAtElapsed: Long) {
        Thread {
            var probeCount = 0
            var recoveredMs = -1L
            while (probeCount++ < 6 && recoveredMs < 0) {
                Thread.sleep(500)
                val (ok, _) = sampleNetwork()
                if (ok) recoveredMs = SystemClock.elapsedRealtime() - wakeAtElapsed
            }
            if (recoveredMs >= 0) {
                Log.d(TAG, "PING 网络恢复：唤醒后 ${recoveredMs}ms 可用")
            } else {
                Log.d(TAG, "PING 网络 3s 内未恢复")
            }
        }.apply { name = "PING-net-probe"; isDaemon = true }.start()
    }

    override fun init(comms: ClientComms) {
        this.comms = comms
    }

    /** Paho 连接成功（ClientState.connected）时调用。与 TimerPingSender.start() 一致：立即排第一个心跳 */
    override fun start() {
        registerReceiver()
        val keepAliveMs = comms?.getKeepAlive()?.takeIf { it > 0 } ?: DEFAULT_KEEPALIVE_MS
        schedule(keepAliveMs)
    }

    /** Paho 关闭（ClientComms.close）时调用；幂等（onDestroy 兜底也会调） */
    override fun stop() {
        cancelAlarm()
        unregisterReceiver()
        releaseWakeLock()
        releaseWifiLock()
    }

    /** Paho 每次 checkForActivity 后按剩余 keepalive 时间调用；同 PendingIntent 多次 set 自动覆盖旧闹钟 */
    override fun schedule(delayInMilliseconds: Long) {
        if (delayInMilliseconds <= 0) return
        // ALARM 加固（08-18）：息屏待机时 App Standby Bucket 把精确闹钟延迟约 60s，
        // 名义 240s 心跳实际≈300s，逼近 broker 360s 踢线死线（裕度仅 60s）。
        // 把排程间隔压缩为 1/ALARM_PING_COMPRESS_DIVISOR（≈80s），即便被 Standby 推到窗口末端
        // (+248s) 有效间隔仍 <360s，稳；PINGREQ 发得比 keepAlive(240s) 更勤完全合法，
        // broker 只要求 ≤keepAlive 内有活动，发更勤只会更确信连接存活。
        // 注：本方法被 start() 与 Paho checkForActivity() 驱动的下一轮排程共用，入口统一压缩即覆盖全部节律。
        val actualDelay = delayInMilliseconds / ALARM_PING_COMPRESS_DIVISOR
        val triggerAt = SystemClock.elapsedRealtime() + actualDelay
        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pingPendingIntent()
            )
            Log.d(TAG, "已排 PING 闹钟 ${actualDelay}ms（原始 ${delayInMilliseconds}ms /${ALARM_PING_COMPRESS_DIVISOR}）")
        } catch (e: Exception) {
            // 精确闹钟权限缺失等异常：降级非精确（Doze 下可能延迟，由 Paho 自动重连兜底）
            Log.w(TAG, "精确闹钟失败，降级 set（Doze 下可能延迟，由 Paho 重连兜底）: ${e.message}")
            try {
                alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pingPendingIntent())
            } catch (_: Exception) {
            }
        }
    }

    private fun pingPendingIntent(): PendingIntent =
        PendingIntent.getBroadcast(
            appContext,
            PING_REQUEST_CODE,
            Intent(ACTION_PING).setPackage(appContext.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun cancelAlarm() {
        runCatching { alarmManager.cancel(pingPendingIntent()) }
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        try {
            // targetSdk 34+ 对自定义 action 的动态 receiver 必须显式指定导出标志；
            // 本广播仅由本 app 的 PendingIntent 触发，用 NOT_EXPORTED 即可
            ContextCompat.registerReceiver(
                appContext, pingReceiver, IntentFilter(ACTION_PING),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        } catch (_: Exception) {
        }
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) return
        receiverRegistered = false
        try {
            appContext.unregisterReceiver(pingReceiver)
        } catch (_: Exception) {
        }
    }

    /**
     * 3s 超时自动释放：够 PINGREQ 发出 + PINGRESP 返回 + 下一轮 schedule，防处理中 CPU 睡回。
     * 窗口已从 5s 再缩到 3s（08-18 优化：心跳 PINGRESP 通常在几百 ms 内返回，3s 足够；
     * CPU 活跃 3s/80s≈3.75%（ALARM 心跳压缩为 80s 后），闹钟频率提升占比略升但仍极低。
     * 历史：30s/240s 会让 CPU 活跃率高达 12.5%（实测 1.5%/h 耗电），5s 仅 ~2%。 */
    private fun acquireWakeLock() {
        runCatching {
            if (wakeLock == null) {
                val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "daily_task_mqtt_ping")
                    .apply { setReferenceCounted(false) }
            }
            if (wakeLock?.isHeld != true) wakeLock?.acquire(3_000L)
        }.onFailure { e ->
            Log.w(TAG, "获取 PING WakeLock 失败: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }.onFailure { _ -> }
    }

    /**
     * 方案 A（省电）：WifiLock 仅 PING 窗口持有。
     * 闹钟唤醒 CPU 后、发 PINGREQ 前获取 WifiLock(WIFI_MODE_FULL) 阻止 WiFi suspend，
     * 超时自动释放（WIFI_LOCK_TIMEOUT_MS 足够 PINGREQ 发出 + PINGRESP 返回 + 下一轮 schedule），
     * 之后息屏间隙 WiFi 可 suspend 挂起 → 比全程常驻明显省电。
     * 连接靠 80s 内必有 PINGREQ 活动保活，NAT/会话不断连；获取失败不崩溃（由 Paho 重连 + 保活闹钟兜底）。
     */
    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        runCatching {
            val wm = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            if (wifiLock == null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL, "daily_task_mqtt_wifilock").apply {
                    setReferenceCounted(false)
                }
            }
            if (wifiLock?.isHeld != true) wifiLock?.acquire()
            Log.d(TAG, "PING 窗口已获取 WifiLock")
            // WifiLock 无 acquire(timeout) 重载（仅 WakeLock 有），用主线程 Handler 模拟超时释放：
            // 5s 足够 PINGREQ 发出 + PINGRESP 返回 + 下一轮 schedule，之后息屏间隙 WiFi 可 suspend 省电。
            mainHandler.removeCallbacks(wifiLockReleaseRunnable)
            mainHandler.postDelayed(wifiLockReleaseRunnable, WIFI_LOCK_TIMEOUT_MS)
        }.onFailure { e ->
            Log.w(TAG, "PING 窗口获取 WifiLock 失败（由重连机制兜底）: ${e.message}")
        }
    }

    /** 取消待释放回调并立即释放（stop() 与超时回调均可安全调用，幂等） */
    private fun releaseWifiLock() {
        mainHandler.removeCallbacks(wifiLockReleaseRunnable)
        releaseWifiLockNow()
    }

    private fun releaseWifiLockNow() {
        runCatching {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        }.onFailure { _ -> }
    }

    companion object {
        private const val TAG = "AlarmPingSender"
        private const val ACTION_PING = "com.pengxh.daily.action.MQTT_PING"
        /** 与 KeepAliveReceiver 的 1003-1008 分离，避免 PendingIntent 互撞 */
        private const val PING_REQUEST_CODE = 1010
        /** start() 时 comms 异常为 null 的兜底：keepAlive=240s（与 connectOptions 配置一致） */
        private const val DEFAULT_KEEPALIVE_MS = 240_000L
        /** ALARM 心跳间隔压缩系数（08-18）：keepAlive 240s → 实际排程 80s，对抗 App Standby 对精确闹钟的 ~60s 延迟 */
        private const val ALARM_PING_COMPRESS_DIVISOR = 3
        /** 方案 A：PING 窗口 WifiLock 超时（ms）。够 PINGREQ 发出 + PINGRESP 返回 + 下一轮 schedule；
         * 超时自释放后息屏间隙 WiFi 可 suspend 省电；占 80s 周期极小比例 */
        private const val WIFI_LOCK_TIMEOUT_MS = 5_000L
    }
}

/**
 * MqttClient 子类：把内部默认的 TimerPingSender 替换为自定义 MqttPingSender。
 *
 * MqttClient 未提供注入 pingSender 的构造器，但 aClient（protected MqttAsyncClient）可由子类替换；
 * 所有公开方法（connect/publish/subscribe/reconnect/disconnect/close/isConnected）仍委托给新
 * aClient，语义与原来完全一致（同步 publish/connect、非阻塞 reconnect 等）。父类构造器创建的
 * 默认 MqttAsyncClient 从未 connect（仅构造了内部对象），替换后无引用即被回收，无资源泄漏。
 */
class MqttClientWithAlarmPingSender(
    serverURI: String,
    clientId: String,
    persistence: MqttClientPersistence,
    pingSender: MqttPingSender
) : MqttClient(serverURI, clientId, persistence) {

    init {
        // 替换父类构造器创建的默认 aClient（TimerPingSender）为带 AlarmPingSender 的异步客户端
        aClient = MqttAsyncClient(serverURI, clientId, persistence, pingSender)
    }
}
