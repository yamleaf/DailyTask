package com.pengxh.daily.app.utils

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 轻量电池历史采样。
 *
 * 采样完全挂在 ForegroundRunningService 已有的每分钟 ACTION_TIME_TICK 回调上，
 * 不新增任何闹钟 / 唤醒 / 广播接收器，因此对本 App 而言零额外功耗。
 * 仅用于计算“过去 N 小时真实掉电百分比”。
 *
 * 数据存于应用私有目录 battery_samples.csv（ts,level,charging 每行一条），
 * 仅保留最近 13 小时（覆盖 12h 窗口），文件极小。
 */
object BatteryHistory {

    private const val FILE_NAME = "battery_samples.csv"
    private const val RETENTION_HOURS = 13L     // 保留窗口，需 >= 最大查询窗口(12h)
    private const val SAMPLE_INTERVAL_MIN = 5L  // 每 5 分钟记一笔，控制 I/O 频率

    private fun file(ctx: Context) = File(ctx.filesDir, FILE_NAME)

    /** 在前台服务每分钟心跳中调用；内部已做节流与裁剪，失败不影响主流程 */
    fun recordSample(ctx: Context) {
        try {
            val now = System.currentTimeMillis()
            val f = file(ctx)
            if (f.exists()) {
                val lastTs = f.readLines().lastOrNull()
                    ?.substringBefore(',')?.toLongOrNull()
                if (lastTs != null && now - lastTs < TimeUnit.MINUTES.toMillis(SAMPLE_INTERVAL_MIN)) {
                    return
                }
            }
            val level = currentLevel(ctx)
            val charging = isCharging(ctx)
            f.appendText("$now,$level,$charging\n")
            prune(f, now)
        } catch (_: Exception) {
            // 采样失败不应影响主流程
        }
    }

    /**
     * 过去 windowHours 小时内的真实掉电百分比。
     *
     * 关键：若窗口内发生过充电，则充电前的采样不可靠（电量百分比基准在充电前后不一致），
     * 因此只统计“最后一次充电之后”的连续放电段，并标注“充电后 …（约 N 分钟）”。
     * 当前仍在充电 → “充电中”；数据不足以构成放电段 → “数据不足”。
     */
    fun drainOver(ctx: Context, windowHours: Int): String {
        return try {
            // 查询时顺手补一笔采样：状态查询本就要读电池广播，多写一行 csv 几乎零成本，
            // 即使前台服务未常驻，也能借此积累历史样本（内部 5 分钟节流，不重复写）。
            recordSample(ctx)
            val f = file(ctx)
            if (!f.exists()) return "数据不足"
            val now = System.currentTimeMillis()
            val windowMs = TimeUnit.HOURS.toMillis(windowHours.toLong())
            val inWindow = f.readLines()
                .mapNotNull { parse(it) }
                .filter { now - it.ts <= windowMs }
            if (inWindow.size < 2) return "数据不足"

            // 当前最新样本仍在充电，直接给出充电中
            if (inWindow.last().charging) return "充电中"

            // 找到窗口内最后一次充电的样本，其之前的采样均不可靠，予以排除
            val lastChargeIdx = inWindow.indexOfLast { it.charging }
            val reliable = if (lastChargeIdx >= 0) {
                inWindow.subList(lastChargeIdx + 1, inWindow.size)
            } else {
                inWindow
            }
            if (reliable.size < 2) return "数据不足"

            val earliest = reliable.first()
            val latest = reliable.last()
            val delta = earliest.level - latest.level
            if (delta <= 0) return "数据不足"   // 无可测量的掉电

            if (lastChargeIdx >= 0) {
                "充电后掉电 ${delta}%（约 ${fmtSpan(latest.ts - earliest.ts)}）"
            } else {
                "${delta}%"
            }
        } catch (_: Exception) {
            "数据不足"
        }
    }

    private fun fmtSpan(ms: Long): String {
        val min = ms / 60000
        return if (min >= 60) "%.1f 小时".format(min / 60.0) else "${min} 分钟"
    }

    /**
     * 供远程快照暴露的电池采样序列（B5：控制端电池曲线复用）。
     * 返回最近 windowHours 小时内的 {ts, level} 序列，降采样到 maxPoints 个点以便控制端绘制。
     * 失败/无数据返回空列表（控制端据此显示“数据不足”占位，不影响主流程）。
     */
    fun recentSeries(ctx: Context, windowHours: Int = 12, maxPoints: Int = 24): List<Pair<Long, Int>> {
        return try {
            val f = file(ctx)
            if (!f.exists()) return emptyList()
            val now = System.currentTimeMillis()
            val windowMs = TimeUnit.HOURS.toMillis(windowHours.toLong())
            val pts = f.readLines()
                .mapNotNull { parse(it) }
                .filter { now - it.ts <= windowMs }
                .map { it.ts to it.level }
            if (pts.size <= maxPoints) return pts
            // 均匀降采样：保留首点 + 每隔 step 取一点 + 末点
            val step = (pts.size / maxPoints).coerceAtLeast(1)
            val sampled = pts.filterIndexed { i, _ -> i % step == 0 }.toMutableList()
            if (sampled.last().first != pts.last().first) sampled.add(pts.last())
            sampled
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun currentLevel(ctx: Context): Int {
        val mgr = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        return mgr?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    }

    private fun isCharging(ctx: Context): Boolean {
        val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return when (intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1) {
            BatteryManager.BATTERY_STATUS_CHARGING,
            BatteryManager.BATTERY_STATUS_FULL -> true
            else -> false
        }
    }

    private fun parse(line: String): Sample? {
        val parts = line.split(',')
        if (parts.size < 3) return null
        val ts = parts[0].toLongOrNull() ?: return null
        val level = parts[1].toIntOrNull() ?: return null
        val charging = parts[2].toBooleanStrictOrNull() ?: return null
        return Sample(ts, level, charging)
    }

    private fun prune(f: File, now: Long) {
        try {
            val cutoff = now - TimeUnit.HOURS.toMillis(RETENTION_HOURS)
            val kept = f.readLines()
                .mapNotNull { parse(it) }
                .filter { it.ts >= cutoff }
                .joinToString("\n") { "${it.ts},${it.level},${it.charging}" }
            f.writeText(if (kept.isEmpty()) "" else "$kept\n")
        } catch (_: Exception) {
            // 裁剪失败忽略
        }
    }

    private data class Sample(val ts: Long, val level: Int, val charging: Boolean)
}
