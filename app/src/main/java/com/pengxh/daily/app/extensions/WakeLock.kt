package com.pengxh.daily.app.extensions

import android.content.Context
import android.os.PowerManager

/**
 * 统一获取并立即持有 WakeLock，消除四处重复的
 * `newWakeLock(level or ACQUIRE_CAUSES_WAKEUP or ON_AFTER_RELEASE, "DailyTask:Tag")` 样板。
 *
 * - timeoutMs > 0：带超时自动释放（如截图点亮、发信期间）
 * - timeoutMs == 0：无限期持有，需调用方自行 release（如蒙层微亮）
 *
 * 返回 nullable WakeLock（getSystemService 为 null 时返回 null），调用方按需判空。
 * 常量本身（SCREEN_BRIGHT_WAKE_LOCK 等）在 API 26+ 已被标记废弃但仍可用，统一在此抑制。
 */
@Suppress("DEPRECATION")
fun Context.acquireWakeLock(
    level: Int,
    tag: String,
    timeoutMs: Long = 0L,
    extraFlags: Int = 0
): PowerManager.WakeLock? {
    val pm = getSystemService(PowerManager::class.java) ?: return null
    val lock = pm.newWakeLock(level or extraFlags, tag)
    if (timeoutMs > 0L) lock.acquire(timeoutMs) else lock.acquire()
    return lock
}
