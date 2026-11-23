package com.pengxh.daily.app.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pengxh.daily.app.utils.ChinaHolidayManager
import com.pengxh.daily.app.utils.LogFileManager
import com.pengxh.daily.app.utils.LogLevel
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * 节假日数据周期刷新 Worker（feasibility probe，方向 B 试点）。
 *
 * 目的：把「节假日 CDN 数据」这个非紧急周期任务，从常驻前台服务的节奏中剥离，
 * 交给 WorkManager 让系统在维护窗口内批量调度，减少 CPU 唤醒、降低掉电率。
 *
 * 设计要点（遵循「不破坏既有功能」原则）：
 * 1. 独立 package 隔离，不修改 ChinaHolidayManager 的任何现有逻辑；
 * 2. 仅是「新增一条周期后台刷新路径」，与 SettingsFragment 手动刷新、
 *    RuntimeStateApplier 指令刷新两路并存，互不干扰；
 * 3. ChinaHolidayManager.updateChinaHolidayData 内部有 isSyncing CAS 去重 + 缓存覆盖判断
 *    （缓存覆盖足够就不联网），因此周期触发天然幂等，重复调度不会重复联网；
 * 4. Worker 是 CoroutineWorker，doWork 挂起等待同步完成，WorkManager 保住 wakelock 直至结束；
 * 5. 周期刷新只更新本地数据，不 pushCalendar（无控制端在等待，无需回推）。
 *
 * 调度入口见 [HolidaySyncScheduler.schedule]，在 DailyTaskApplication.onCreate 冷启动时注册。
 */
class HolidaySyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            // 已在同步中（如用户正在设置页手动刷新）则直接跳过，避免重复触发 + 挂起等待无回调。
            if (ChinaHolidayManager.isSyncing()) {
                LogFileManager.writeLog("HolidaySyncWorker：已在同步中，跳过本次周期刷新")
                return Result.success()
            }
            // 挂起等待同步完成（updateChinaHolidayData 内部走缓存/网络，完成或失败都会回调 onComplete）。
            // 加 30s 超时兜底：极端情况下 onComplete 未按时回调也不至于让 worker 卡死，
            // 超时仅记录日志、下次周期再试（数据同步本身由 ChinaHolidayManager 的独立 scope 继续完成）。
            val completed = kotlinx.coroutines.withTimeoutOrNull(30_000L) {
                kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
                    ChinaHolidayManager.updateChinaHolidayData {
                        if (cont.isActive) cont.resume(Unit) { _, _, _ -> }
                    }
                }
            }
            LogFileManager.action(
                if (completed != null) "HolidaySyncWorker：节假日周期刷新完成"
                else "HolidaySyncWorker：同步超时（30s），下次周期再试"
            )
            Result.success()
        } catch (e: CancellationException) {
            // 协程取消（WorkManager 停止本次 worker / 进程被回收 / 应用被 force-stop）：
            // 必须原样抛出交给框架处理，绝不能当业务失败吞掉——
            // 否则会误记一条 [E] "Job was cancelled" 污染诊断日志，还会把 work 标成 success 掩盖真实状态。
            LogFileManager.writeLog(LogLevel.I, "HolidaySyncWorker 被取消（系统停止/进程回收），由 WorkManager 重新排期")
            throw e
        } catch (e: Exception) {
            LogFileManager.error("HolidaySyncWorker 执行异常：${e.message}")
            // 网络类失败交下次周期；不 retry 以免紧循环反复唤醒耗电
            Result.success()
        }
    }
}

/**
 * 节假日周期刷新的调度器（独立入口，供 MainActivity 冷启动时调用一次）。
 */
object HolidaySyncScheduler {

    private const val UNIQUE_NAME = "holiday_periodic_sync"

    /**
     * 注册 24h 周期刷新（幂等：同名 periodic work 用 KEEP，重复调用不会重复注册）。
     *
     * 约束：
     * - 有网络连接（Connected，含 WiFi 与蜂窝）时才执行；
     * - 电量不低（BatteryNotLow）时不执行，避免低电量下白折腾网络。
     */
    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<HolidaySyncWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
