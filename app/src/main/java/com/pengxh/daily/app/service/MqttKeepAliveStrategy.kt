package com.pengxh.daily.app.service

import android.util.Log
import com.pengxh.daily.app.utils.AppRuntimeConfig
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.LogFileManager
import com.pengxh.kt.lite.utils.SaveKeyValues
import java.util.ArrayDeque

/**
 * MQTT 息屏保活策略（省电优先）。
 *
 * 支持两种工作模式（设置页「息屏保活」选项，仅屏幕模式=息屏时显示）：
 * - AUTO（默认）：每次 MQTT 服务启动/开启时重置为 TIMER（最省电，等价 a9b1e1f 行为），
 *   运行期间按掉线频率升级 TIMER→ALARM→CPU（30min 窗口内掉线 ≥2 次升一级），**不降级**——
 *   升级状态保持到下次启动重置，避免夜间反复升降级抖动。
 * - 指定模式（TIMER / ALARM / CPU）：固定使用该级别，**不升级、不降级**。
 *
 * 说明：TIMER=Paho 默认 TimerPingSender（最省电，K20 Pro 实测）；ALARM=AlarmPingSender 系统精确闹钟
 * 驱动心跳（TIMER 频繁掉线时升级）；CPU=息屏持 PARTIAL_WAKE_LOCK + TimerPingSender（兜底，CPU 不深睡
 * → Timer 线程始终被调度 → 心跳必然可靠）。
 */
object MqttKeepAliveStrategy {

    enum class Level { TIMER, ALARM, CPU }

    /** 息屏保活模式，与 Constant.KEEPALIVE_MODE_* 一一对应 */
    enum class Mode(val code: Int) {
        AUTO(Constant.KEEPALIVE_MODE_AUTO),
        TIMER(Constant.KEEPALIVE_MODE_TIMER),
        ALARM(Constant.KEEPALIVE_MODE_ALARM),
        CPU(Constant.KEEPALIVE_MODE_CPU);

        companion object {
            fun from(code: Int): Mode = entries.firstOrNull { it.code == code } ?: AUTO
        }
    }

    private const val TAG = "MqttKeepAliveStrategy"
    /** 掉线统计窗口：30 分钟 */
    private const val WINDOW_MS = 30 * 60 * 1000L
    /** 窗口内掉线次数达到该值即升级（仅 auto 模式） */
    private const val UPGRADE_THRESHOLD = 2

    /** auto 模式下当前升级到的级别；指定模式忽略。启动时由 [onServiceStart] 重置为 TIMER */
    @Volatile
    private var level: Level = Level.TIMER

    /** 掉线时间窗口（进程内计数，仅 auto 模式使用） */
    private val disconnectTimes = ArrayDeque<Long>()
    private val lock = Any()

    /** 当前设置的保活模式（auto / 指定） */
    fun mode(): Mode = Mode.from(AppRuntimeConfig.getKeepAliveMode())

    /**
     * 当前生效的保活级别：
     * - auto：返回升级状态（启动已重置 TIMER）；
     * - 指定模式：返回固定级别。
     */
    fun current(): Level = when (mode()) {
        Mode.AUTO -> level
        Mode.TIMER -> Level.TIMER
        Mode.ALARM -> Level.ALARM
        Mode.CPU -> Level.CPU
    }

    /** 是否已到最高级 CPU（用于 UI/日志提示"已到兜底级别"） */
    fun isMaxLevel(): Boolean = current() == Level.CPU

    /**
     * MQTT 服务启动 / 开启时调用：
     * - auto 模式：重置为 TIMER（每次启动从最省电开始，清残留升级状态与掉线窗口）；
     * - 指定模式：保持固定级别，无操作。
     */
    fun onServiceStart() {
        if (mode() != Mode.AUTO) return
        synchronized(lock) {
            disconnectTimes.clear()
            if (level != Level.TIMER || SaveKeyValues.loadInt(Constant.MQTT_KEEPALIVE_LEVEL_KEY, 0) != 0) {
                level = Level.TIMER
                SaveKeyValues.saveInt(Constant.MQTT_KEEPALIVE_LEVEL_KEY, 0)
                recordChanged("启动重置 TIMER")
                LogFileManager.action("MQTT 保活级别重置为 TIMER（auto 模式）")
                Log.w(TAG, "MQTT 保活级别重置为 TIMER（auto 模式）")
            }
        }
    }

    /**
     * 记录一次掉线。
     * - 所有模式统一统计掉线次数与最近掉线时间（供控制端快照展示）；
     * - auto 模式额外：窗口内掉线 ≥2 次 → 升一级并持久化，返回新级别（调用方据此重建连接）；否则返回 null。
     * - 指定模式：不升级，返回 null（固定级别不因掉线变化）。
     */
    fun recordDisconnect(): Level? {
        // 掉线计数与时间戳（不分模式，指定模式也统计；跨天自动重置）
        recordDisconnectCount()
        if (mode() != Mode.AUTO) return null
        synchronized(lock) {
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

    /** 当日掉线计数 + 最近掉线时间戳（所有模式统一，跨天自动重置） */
    private fun recordDisconnectCount() {
        val today = java.time.LocalDate.now().toString()
        val storedDate = SaveKeyValues.loadString(Constant.MQTT_DISCONNECT_DATE_KEY, "")
        val count = if (storedDate == today) {
            SaveKeyValues.loadInt(Constant.MQTT_DISCONNECT_COUNT_KEY, 0)
        } else 0
        SaveKeyValues.saveString(Constant.MQTT_DISCONNECT_DATE_KEY, today)
        SaveKeyValues.saveInt(Constant.MQTT_DISCONNECT_COUNT_KEY, count + 1)
        SaveKeyValues.saveLong(Constant.MQTT_KEEPALIVE_LAST_DISC_KEY, System.currentTimeMillis())
    }

    /** 当日掉线次数（供控制端快照展示；跨天自动归零） */
    fun disconnectCount(): Int {
        val today = java.time.LocalDate.now().toString()
        return if (SaveKeyValues.loadString(Constant.MQTT_DISCONNECT_DATE_KEY, "") == today) {
            SaveKeyValues.loadInt(Constant.MQTT_DISCONNECT_COUNT_KEY, 0)
        } else 0
    }

    /** 最近一次掉线时间戳（ms，0 表示从未掉线） */
    fun lastDisconnectAt(): Long =
        SaveKeyValues.loadLong(Constant.MQTT_KEEPALIVE_LAST_DISC_KEY, 0L)

    private fun save(next: Level) {
        level = next
        SaveKeyValues.saveInt(Constant.MQTT_KEEPALIVE_LEVEL_KEY, next.ordinal)
        recordChanged("→ ${next.name}")
        LogFileManager.action("MQTT 保活级别切换为 ${next.name}")
        Log.w(TAG, "MQTT 保活级别 → ${next.name}")
    }

    private fun recordChanged(desc: String) {
        SaveKeyValues.saveLong(Constant.MQTT_KEEPALIVE_CHANGED_KEY, System.currentTimeMillis())
        SaveKeyValues.saveString(Constant.MQTT_KEEPALIVE_CHANGED_DESC_KEY, desc)
    }

    /**
     * 保活级别最近一次切换时间戳（ms），供控制端快照展示。
     * 指定模式（固定级别）下无"切换"概念，返回 0 → 控制端显示 "—"。
     */
    fun lastChangedAt(): Long {
        if (mode() != Mode.AUTO) return 0L
        return SaveKeyValues.loadLong(Constant.MQTT_KEEPALIVE_CHANGED_KEY, 0L)
    }

    /**
     * 保活级别最近一次切换描述（如 "→ ALARM"），供控制端快照展示。
     * 指定模式下返回固定模式说明（如 "固定 ALARM"），控制端据此显示固定模式而非切换方向。
     */
    fun lastChangedDesc(): String {
        if (mode() != Mode.AUTO) return "固定 ${current().name}"
        return SaveKeyValues.loadString(Constant.MQTT_KEEPALIVE_CHANGED_DESC_KEY, "")
    }
}
