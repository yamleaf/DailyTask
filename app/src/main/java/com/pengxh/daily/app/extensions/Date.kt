package com.pengxh.daily.app.extensions

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 线程安全的 Date 格式化扩展。
 *
 * SimpleDateFormat 本身非线程安全，若被多线程共享实例并发 format 会偶发错乱/异常。
 * 这里按「线程 × pattern」缓存独立实例：每个线程持有自己的 SimpleDateFormat，
 * 既避免每次 new 的开销，也彻底消除「共享实例」在未来引发的并发隐患。
 * 默认 Locale.CHINA，与工程原有用法行为完全一致。
 */
private val threadLocalFormatters = ThreadLocal.withInitial { HashMap<String, SimpleDateFormat>() }

fun Date.format(pattern: String, locale: Locale = Locale.CHINA): String {
    val cache = threadLocalFormatters.get()
        ?: HashMap<String, SimpleDateFormat>().also { threadLocalFormatters.set(it) }
    val formatter = cache.getOrPut(pattern) { SimpleDateFormat(pattern, locale) }
    return formatter.format(this)
}
