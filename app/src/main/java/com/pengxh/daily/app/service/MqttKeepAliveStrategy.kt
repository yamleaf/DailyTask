package com.pengxh.daily.app.service

import android.util.Log
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.LogFileManager
import com.pengxh.kt.lite.utils.SaveKeyValues
import java.util.ArrayDeque

/**
 * MQTT 保活自适应策略（三级，省电优先）：
 * - TIMER：Paho 默认 TimerPingSender + WifiLock（最省电，a9b1e1f 版本实测两天不掉线、0.5%/h）
 * - ALARM：AlarmPingSender 系统精确闹钟驱动心跳 + WifiLock（TIMER 频繁掉线时升级）
 * - CPU：息屏持 PARTIAL_WAKE_LOCK + TimerPingSender + WifiLock（闹钟保活也掉线时兜底，
 *   CPU 不深睡 → Timer 线程始终被调度 → 心跳必然可靠）
 *
 * 升级：30 分钟窗口内掉线 ≥2 次 → 升一级；级别持久化，服务重启后保持。
 * 降级：距最后掉线 >5h 且级别 > TIMER → 降一级（慢速，防频繁升降级抖动）。两条路径：
 *  - 服务启动时（checkDowngrade）：防止上次网络波动后重启仍卡高耗电级别；
 *  - 运行中由 KeepAliveReceiver 心跳闹钟（15min 周期）驱动（maybeDowngradeKeepAlive）：
 *    覆盖「夜间待机不重启、启动降级不触发」的场景——夜间某时段升级后稳定 5h 即自动回退省电。
 *
 * 注：升级/降级切换需重建 MQTT 连接（pingSender 在 client 构造时注入），由 MqttAgentService
 * 在 connectionLost / 心跳闹钟 / 启动路径调用本策略并触发重建。
 */
object MqttKeepAliveStrategy {

    enum class Level { TIMER, ALARM, CPU }

    private const val TAG = "MqttKeepAliveStrategy"
    /** 掉线统计窗口：30 分钟 */
    private const val WINDOW_MS = 30 * 60 * 1000L
    /** 窗口内掉线次数达到该值即升级 */
    private const val UPGRADE_THRESHOLD = 2
    /** 升级后稳定该时长（距最后掉线）才降级尝试。5h：慢速降级，防频繁升降级抖动；
     * 夜间待机（8h+）期间仍至少有一次机会回退省电级别 */
    private const val DOWNGRADE_AFTER_MS = 5L * 3600 * 1000L

    @Volatile
    private var level: Level = load()

    /** 掉线时间窗口（进程内计数） */
    private val disconnectTimes = ArrayDeque<Long>()
    private val lock = Any()

    fun current(): Level = level

    /** 最高级 CPU 时是否仍掉线（用于 UI/日志提示"已到兜底级别"） */
    fun isMaxLevel(): Boolean = level == Level.CPU

    private fun load(): Level = when (SaveKeyValues.loadInt(Constant.MQTT_KEEPALIVE_LEVEL_KEY, 0)) {
        1 -> Level.ALARM
        2 -> Level.CPU
        else -> Level.TIMER
    }

    /** 最近一次级别切换时间戳（ms）；0 表示从未切换（或旧版本首次升级，尚未记录） */
    fun lastChangedAt(): Long =
        SaveKeyValues.loadLong(Constant.MQTT_KEEPALIVE_CHANGED_KEY, 0L)

    /** 最近一次级别切换描述（如 "TIMER→ALARM"）；从未切换时返回 "—" */
    fun lastChangedDesc(): String =
        SaveKeyValues.loadString(Constant.MQTT_KEEPALIVE_CHANGED_DESC_KEY, "").ifBlank { "—" }

    private fun save(next: Level) {
        val prev = level
        level = next
        SaveKeyValues.saveInt(Constant.MQTT_KEEPALIVE_LEVEL_KEY, next.ordinal)
        SaveKeyValues.saveLong(Constant.MQTT_KEEPALIVE_CHANGED_KEY, System.currentTimeMillis())
        SaveKeyValues.saveString(Constant.MQTT_KEEPALIVE_CHANGED_DESC_KEY, "${prev.name}→${next.name}")
        LogFileManager.action("MQTT 保活级别切换为 ${next.name}")
        Log.w(TAG, "MQTT 保活级别 → ${next.name}")
    }

    /**
     * 记录一次掉线。若窗口内掉线频率达到升级阈值且未到最高级，持久化升级后的级别并返回
     * （调用方据此重建连接）；否则返回 null。
     * 每次掉线都会刷新「最后掉线时间戳」，供降级判断（启动 / 运行中心跳闹钟）使用。
     */
    fun recordDisconnect(): Level? {
        synchronized(lock) {
            SaveKeyValues.saveLong(Constant.MQTT_KEEPALIVE_LAST_DISC_KEY, System.currentTimeMillis())
            if (level == Level.CPU) return null // 已兜底，不再升级
            val now = System.currentTimeMillis()
            disconnectTimes.addLast(now)
            while (disconnectTimes.isNotEmpty() && now - disconnectTimes.first() > WINDOW_MS) {
                disconnectTimes.removeFirst()
            }
            if (disconnectTimes.size < UPGRADE_THRESHOLD) return null
            val next = if (level == Level.TIMER) Level.ALARM else Level.CPU
            disconnectTimes.clear()
            save(next)
            return next
        }
    }

    /**
     * 降级检查（统一入口）：距最后掉线 >5h（或从无掉线记录）且级别 > TIMER → 降一级。
     * - 服务启动时调用：降级结果在下一次 initMqtt 建连时生效，无需重建；
     * - 运行中由 KeepAliveReceiver 心跳闹钟（15min）驱动：返回非 null 时调用方需重建连接。
     */
    fun checkDowngrade(): Level? {
        if (level == Level.TIMER) return null
        val lastDisc = SaveKeyValues.loadLong(Constant.MQTT_KEEPALIVE_LAST_DISC_KEY, 0L)
        if (lastDisc > 0L && System.currentTimeMillis() - lastDisc < DOWNGRADE_AFTER_MS) return null
        val prev = level
        val next = if (level == Level.CPU) Level.ALARM else Level.TIMER
        save(next)
        LogFileManager.action(
            "保活级别降级：${prev.name} → ${next.name}" +
                    "（${if (lastDisc == 0L) "无掉线记录" else "已稳定 >5h"}）"
        )
        return next
    }

    /** 重置进程内掉线计数（连接成功后调用，避免跨稳定期累计误判） */
    fun resetWindow() {
        synchronized(lock) { disconnectTimes.clear() }
    }
}
