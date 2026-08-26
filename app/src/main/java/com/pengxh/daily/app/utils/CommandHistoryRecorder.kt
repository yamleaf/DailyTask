package com.pengxh.daily.app.utils

import android.os.Handler
import android.os.Looper
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.pengxh.kt.lite.utils.SaveKeyValues

data class CommandEntry(
    val timestamp: Long,
    val source: String,
    val command: String,
    val result: String
)

/**
 * 指令历史记录器。
 *
 * 写盘策略：record() 只写内存缓存并标记脏位，由 10 秒防抖窗口合并落盘——
 * 窗口内任意多条指令只触发一次 SharedPreferences 写入，避免高频远程指令
 * （如控制端连续下发设置）造成频繁磁盘 I/O 徒增耗电。
 * 代价：落盘前进程被杀可能丢失最近 10 秒内的记录，对历史日志可接受。
 */
object CommandHistoryRecorder {
    private const val KEY = "command_history_v2"
    private const val MAX_ENTRIES = 50
    private const val FLUSH_DELAY_MS = 10_000L
    private val gson = Gson()

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 内存缓存：首次访问时从 SP 懒加载，此后读写均走内存 */
    private var cache: MutableList<CommandEntry>? = null

    @Volatile
    private var dirty = false

    @Synchronized
    fun record(source: String, command: String, result: String = "") {
        val list = ensureCache()
        list.add(CommandEntry(System.currentTimeMillis(), source, command, result))
        if (list.size > MAX_ENTRIES) {
            list.removeAt(0)
        }
        dirty = true
        mainHandler.removeCallbacks(flushRunnable)
        mainHandler.postDelayed(flushRunnable, FLUSH_DELAY_MS)
    }

    /** 返回缓存快照（拷贝），避免遍历时被并发 record 修改 */
    @Synchronized
    fun load(): MutableList<CommandEntry> = ensureCache().toMutableList()

    @Synchronized
    fun clear() {
        cache = mutableListOf()
        dirty = false
        mainHandler.removeCallbacks(flushRunnable)
        SaveKeyValues.saveString(KEY, "")
    }

    private fun ensureCache(): MutableList<CommandEntry> {
        if (cache == null) {
            cache = try {
                val json = SaveKeyValues.loadString(KEY, "")
                if (json.isBlank()) {
                    mutableListOf()
                } else {
                    val type = object : TypeToken<MutableList<CommandEntry>>() {}.type
                    // filterNotNull：SP 数据损坏时可能出现 null 元素，过滤避免遍历 NPE
                    gson.fromJson<MutableList<CommandEntry>>(json, type)
                        ?.filterNotNull()?.toMutableList() ?: mutableListOf()
                }
            } catch (e: Exception) {
                mutableListOf()
            }
        }
        return cache!!
    }

    private val flushRunnable = Runnable { flush() }

    @Synchronized
    private fun flush() {
        if (!dirty) return
        dirty = false
        SaveKeyValues.saveString(KEY, gson.toJson(cache))
    }
}