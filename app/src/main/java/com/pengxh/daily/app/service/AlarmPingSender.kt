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
import org.eclipse.paho.client.mqttv3.MqttMessage
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

    /**
     * 钩子执行线程池：钩子实现是阻塞式 QoS1 publish（waitForCompletion），若直接在闹钟
     * onReceive（主线程）调用，弱网/断网未 RST 时会阻塞主线程到连接判定超时 → ANR。
     * 单线程 + daemon 即可：一次 PING 唤醒只有一帧钩子，串行避免重入。
     */
    private val hookExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "ping-hook-executor").apply { isDaemon = true }
    }

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
                // 闹钟驱动下 Paho checkForActivity 的「该发 PINGREQ」判定会持续 false
                // （PINGRESP 未回卡住 pingOutstanding / 重连后 240s 毫秒误差），导致心跳从未发出 → broker 360s 踢线。
                // 故闹钟到点【无条件强制发 PINGREQ】：sendNoWait 直接入队，checkForActivity 仅用于更新内部计时并排下一轮。
                // token 必须非 null（传 null 触发 internalSend 内 token.getClient() NPE），用 MqttToken(clientId)。
                val commsRef = comms
                if (commsRef != null && commsRef.isConnected) {
                    commsRef.sendNoWait(MqttPingReq(), MqttToken(commsRef.getClient().clientId))
                    Log.d(TAG, "PING 强制发送 PINGREQ")
                    commsRef.checkForActivity()
                    // 蹭本次唤醒窗口补发占位牌（连接态与节流由钩子实现方判断），异常隔离。
                    // 钩子实现是阻塞式 QoS1 publish，绝不能占主线程（弱网下会阻塞到连接判定超时 → ANR），
                    // 因此 goAsync() 保活广播 + 后台单线程执行，结束后必须 finish() 归还 PendingResult。
                    // CPU/WifiLock 仍按原有 3s/5s 窗口释放：健康网络下 publish 在窗口内完成；
                    // 极端慢网下最多丢一帧 presence HB（周期性补发 + 重连后 claim 会兜底），可接受。
                    val hook = onPingHook
                    if (hook != null && hookExecutor.isShutdown) {
                        // stop() 已关闭执行器（连接销毁/重建中）：直接跳过本帧，
                        // 避免 goAsync 后 execute 必然被拒还要手工归还 PendingResult
                        Log.d(TAG, "PING 钩子跳过：执行器已关闭（连接销毁中）")
                    } else if (hook != null) {
                        val pending = goAsync()
                        // 【ANR 防护 11-22】goAsync 把广播生命周期延长到 pending.finish()：钩子线程一旦
                        // 永久阻塞（真机实证：Paho 同步 publish 等 QoS1 PUBACK 无超时，TCP 半开 /
                        // stop() 竞态 shutdownNow 无法中断已跑任务），finish() 永不执行 → 系统按广播
                        // 60s 超时判 ANR，且随 80s 心跳周期反复弹出「DT 没有响应」。
                        // 硬保险：无论钩子是否返回，广播最多延长 8s 即归还 PendingResult（finish 幂等）。
                        val forceFinish = Runnable {
                            if (runCatching { pending.finish() }.isSuccess) {
                                Log.w(TAG, "PING 钩子超时未完成（${PENDING_FINISH_GUARD_MS}ms），强制归还广播")
                            }
                        }
                        mainHandler.postDelayed(forceFinish, PENDING_FINISH_GUARD_MS)
                        // 提交单独 try/catch 与本帧外层隔离：stop() 的 shutdownNow 若抢在 execute 前执行，
                        // 会抛 RejectedExecutionException —— 落入外层 catch 会误报「强制发送 PINGREQ 异常」
                        // 并对已停止的实例重排闹钟；此处单独捕获，立即归还 PendingResult（广播不超时）。
                        try {
                            hookExecutor.execute {
                                try {
                                    runCatching { hook() }
                                } finally {
                                    mainHandler.removeCallbacks(forceFinish)
                                    runCatching { pending.finish() }
                                }
                            }
                        } catch (e: java.util.concurrent.RejectedExecutionException) {
                            Log.d(TAG, "PING 钩子未提交：执行器已被关闭（stop 竞态），跳过本帧")
                            mainHandler.removeCallbacks(forceFinish)
                            runCatching { pending.finish() }
                        }
                    }
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

    /** Paho 关闭（ClientComms.close）时调用；幂等（onDestroy 兜底也会调）。
     * 注意：本实例 stop() 后即被上层置空丢弃（MqttAgentService.rebuildMqttClient/onDestroy），
     * 不会再 start() 复用，故可在此 shutdownNow 释放钩子线程，避免级别切换时线程泄漏。 */
    override fun stop() {
        cancelAlarm()
        unregisterReceiver()
        releaseWakeLock()
        releaseWifiLock()
        // 释放钩子线程池：shutdownNow 中断正在执行的钩子（其内部 runCatching + finally finish 兜底），
        // 并拒绝后续任务。stop 后实例不再复用，安全。
        runCatching { hookExecutor.shutdownNow() }
    }

    /** Paho 每次 checkForActivity 后按剩余 keepalive 时间调用；同 PendingIntent 多次 set 自动覆盖旧闹钟 */
    override fun schedule(delayInMilliseconds: Long) {
        if (delayInMilliseconds <= 0) return
        // ALARM 加固：息屏待机时 Standby Bucket 把精确闹钟延迟约 60s，名义 240s 心跳实际≈300s，逼近 broker 360s 死线。
        // 压缩排程为 1/ALARM_PING_COMPRESS_DIVISOR（≈80s），即便被推到窗口末端仍 <360s；PINGREQ 发得比 keepAlive 更勤合法。
        // 本方法被 start() 与 checkForActivity 驱动的下一轮排程共用，入口统一压缩即覆盖全部节律。
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
        /** goAsync 广播硬超时（ms）：钩子线程异常阻塞时也必须归还 PendingResult，
         * 远低于系统前台广播 60s ANR 阈值，避免 ALARM 心跳周期反复触发「DT 没有响应」 */
        private const val PENDING_FINISH_GUARD_MS = 8_000L
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

    /**
     * 【ANR 防护 11-22】带超时的 QoS1 发布：绕开同步 MqttClient.publish() 内部
     * waitForCompletion() 无超时等 PUBACK 的问题（TCP 半开 / stop() 竞态 shutdownNow
     * 无法中断已跑任务 → 钩子线程永久阻塞 → goAsync 广播不归还 → 系统 60s 判 ANR）。
     * 直接走底层异步 aClient.publish + 显式超时；超时即放弃本帧（presence 有 30s 循环兜底）。
     * 注意：IMqttToken.waitForCompletion(long) 返回 void（Paho 1.2.5 官方签名），正常返回即代表
     * 动作已完成；超时或动作失败会抛 MqttException，统一由 catch 返回 false。官方明确「超时后
     * 动作仍可能在稍后完成」，故本方法只表示「本帧是否等到回执」，不代表消息一定没发出。
     * @return true=超时内等到完成；false=超时/失败
     */
    fun publishWithTimeout(topic: String, message: MqttMessage, timeoutMs: Long): Boolean = try {
        val token = aClient.publish(topic, message, null, null)
        token.waitForCompletion(timeoutMs)
        true
    } catch (_: Exception) {
        false
    }
}
