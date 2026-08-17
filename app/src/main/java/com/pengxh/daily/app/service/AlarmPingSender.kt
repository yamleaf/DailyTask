package com.pengxh.daily.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttClientPersistence
import org.eclipse.paho.client.mqttv3.MqttPingSender
import org.eclipse.paho.client.mqttv3.internal.ClientComms

/**
 * MQTT 保活自适应第二级（ALARM）：以系统精确闹钟替代 Paho TimerPingSender 的 java.util.Timer
 * 线程调度心跳。由 MqttKeepAliveStrategy 在 TIMER 级别频繁掉线时启用。
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

    @Volatile
    private var receiverRegistered = false

    private val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /** 动态注册接收 PING 闹钟广播；闹钟到点由系统唤醒 CPU 后在此触发心跳 */
    private val pingReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action != ACTION_PING) return
            Log.d(TAG, "PING 闹钟到点：唤醒 CPU 发心跳")
            acquireWakeLock()
            try {
                // Paho 内部判定：距上次出站活动超过 keepalive 则发 PINGREQ，并再次 schedule 下一轮
                comms?.checkForActivity()
            } catch (e: Exception) {
                Log.w(TAG, "checkForActivity 异常（Paho 重连机制兜底）: ${e.message}")
            }
        }
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
    }

    /** Paho 每次 checkForActivity 后按剩余 keepalive 时间调用；同 PendingIntent 多次 set 自动覆盖旧闹钟 */
    override fun schedule(delayInMilliseconds: Long) {
        if (delayInMilliseconds <= 0) return
        val triggerAt = SystemClock.elapsedRealtime() + delayInMilliseconds
        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pingPendingIntent()
            )
            Log.d(TAG, "已排 PING 闹钟 ${delayInMilliseconds}ms")
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

    /** 5s 超时自动释放：够 PINGREQ 发出 + PINGRESP 返回 + 下一轮 schedule，防处理中 CPU 睡回。
     * 注意保持短窗口——30s/240s 会让 CPU 活跃率高达 12.5%（实测 1.5%/h 耗电），5s 仅 ~2%。 */
    private fun acquireWakeLock() {
        runCatching {
            if (wakeLock == null) {
                val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "daily_task_mqtt_ping")
                    .apply { setReferenceCounted(false) }
            }
            if (wakeLock?.isHeld != true) wakeLock?.acquire(5_000L)
        }.onFailure { e ->
            Log.w(TAG, "获取 PING WakeLock 失败: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }.onFailure { _ -> }
    }

    companion object {
        private const val TAG = "AlarmPingSender"
        private const val ACTION_PING = "com.pengxh.daily.action.MQTT_PING"
        /** 与 KeepAliveReceiver 的 1003-1008 分离，避免 PendingIntent 互撞 */
        private const val PING_REQUEST_CODE = 1010
        /** start() 时 comms 异常为 null 的兜底：keepAlive=240s（与 connectOptions 配置一致） */
        private const val DEFAULT_KEEPALIVE_MS = 240_000L
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
