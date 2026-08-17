package com.pengxh.daily.app.utils

import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.pengxh.daily.app.DailyTaskApplication
import com.pengxh.daily.app.extensions.formatTime
import com.pengxh.daily.app.extensions.openApplication
import com.pengxh.daily.app.extensions.bringDailyTaskToFront
import com.pengxh.daily.app.extensions.resolveExecutionTime
import com.pengxh.daily.app.service.AutoProjectionAccessibilityService
import com.pengxh.daily.app.service.CaptureImageService
import com.pengxh.daily.app.service.FloatingWindowService
import com.pengxh.daily.app.service.ForegroundRunningService
import com.pengxh.daily.app.service.KeepAliveReceiver
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.sqlite.bean.DailyTaskBean
import com.pengxh.daily.app.sqlite.bean.NotificationBean
import com.pengxh.daily.app.utils.StatusReporter
import com.pengxh.kt.lite.extensions.timestampToCompleteDate
import com.pengxh.kt.lite.utils.SaveKeyValues
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar

/**
 * 任务调度器
 */
object TaskScheduler {
    /** 进程被杀后闹钟略晚于计划点时，仍允许执行的宽限（毫秒） */
    private const val PUNCH_LATE_GRACE_MS = 120_000L

    /**
     * 调度器是否在运行中
     * */
    private val _isRunning = MutableStateFlow(false)
    val isRunning = _isRunning.asStateFlow()

    /** 重置等待中断信号：修改重置时间时唤醒 waitUntilNextReset 重新计算目标 */
    @Volatile
    private var pendingResetSignal: CompletableDeferred<Unit>? = null

    /** PUNCH_DUE 精确闹钟到点信号：进程冻结时协程 delay 不可靠，闹钟广播唤醒阶段1 立即进入打卡 */
    @Volatile
    private var pendingPunchDueSignal: CompletableDeferred<Unit>? = null

    /** 重置点到达信号（RESET_TASK 闹钟 / TIME_TICK 兜底）：唤醒 waitUntilNextReset 立即返回重排。
     * 修复根因：调度协程在「等待重置期」时 isRunning() 仍为 true，旧逻辑所有兜底（RESET_TASK /
     * TIME_TICK / PUNCH_DUE）因 isRunning() 判断直接 return，重置永远无法推进——08-15/16 任务漏执行即此 */
    @Volatile
    private var pendingResetReachedSignal: CompletableDeferred<Unit>? = null

    /**
     * UI 文本事件（tipsView / adapter 高亮），不参与按钮逻辑
     * */
    private val _tipsEvent = MutableSharedFlow<TipsEvent>(extraBufferCapacity = 1)
    val tipsEvent = _tipsEvent.asSharedFlow()

    /**
     * 超时后回到主页信号（TaskScheduler → MainActivity）
     * */
    private val _returnToApp = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    val returnToApp = _returnToApp.asSharedFlow()

    private var scope: CoroutineScope? = null
    private var job: Job? = null

    /**
     * 打卡信号：外部 notifyClockIn() 触发，解除 select{} 阻塞
     * */
    private var clockInDeferred: CompletableDeferred<Unit>? = null

    /** 供状态查询使用的当前进度文案 */
    @Volatile
    private var runningDetail: String = "空闲"

    /**
     * 由 ForegroundRunningService 调用，注入协程作用域
     */
    fun attach(serviceScope: CoroutineScope) {
        scope?.cancel()
        scope = serviceScope
    }

    fun isRunning(): Boolean {
        return _isRunning.value
    }

    /** 是否处于打开目标 App / 等待打卡成功的窗口期（此期间不宜盖黑屏） */
    fun isInActivePunch(): Boolean {
        // 定时任务「等待打卡」或任意 float 会话（远程打卡/截屏）均视为活跃操作窗
        if (FloatingWindowController.isSessionActive) return true
        if (!_isRunning.value) return false
        return runningDetail.contains("等待打卡")
    }

    fun describeRunningState(): String {
        return if (_isRunning.value) {
            "运行中｜$runningDetail"
        } else {
            // 每日循环≠当前在跑：循环只负责重置点自动 startTask
            "调度未启动（需手动或远程启动，每日循环任务重置后开始生效）"
        }
    }

    /**
     * 启动每日任务调度
     * 时序：防重复 → 检查协程作用域 → 判断周末/节假日 → 构建排程 → 启动核心循环
     */
    fun startTask() {
        val currentScope = scope
        if (currentScope == null) {
            LogFileManager.error("TaskScheduler scope 未初始化")
            return
        }

        // 原子抢占运行权：startTask 可能被多个线程并发调用（FGS 启动/心跳复活/MQTT 命令/远程指令/用户点击），
        // 必须用 compareAndSet 一次性读出并置位，否则 check-then-act 竞态会让两条调度循环同时跑同一轮打卡、
        // 重复发邮件（曾实测 08:37:58 两条执行并行，成功邮件与超时邮件各发一封）。
        if (!_isRunning.compareAndSet(false, true)) {
            LogFileManager.writeLog("任务已在执行中，忽略重复启动")
            return
        }

        // 远程/开机启动时确保悬浮窗在跑，否则打卡倒计时不可见且安卓 15+ 后台跳转易失败
        KeepAliveReceiver.ensureFloatingWindow(DailyTaskApplication.get())

        SaveKeyValues.saveBoolean(Constant.SCHEDULER_WANTED_KEY, true)
        runningDetail = "初始化排程"
        // 运行态变化会影响快照的 schedulerRunning：失效全量快照缓存，
        // 否则控制端在 30s TTL 内查询会拿到「调度未运行」的旧缓存，把正确状态覆盖回去。
        RemoteSnapshot.invalidateCache()

        val tempJob = currentScope.launch {
            while (isActive) {
                // 始终基于"今天"排程；跨重置点的任务由 loadTodayTaskPlans 偏移 +1 天，
                // 实际执行日的休息/节假日由 executeSchedule 内部逐任务再判（shouldSkipDay）。
                // 因此即使"今天"是休息日（如周日），下一工作日（如周一）的任务仍会被正确排入并执行，
                // 不能在此处对"周期起始日"整轮跳过，否则跨天任务永远不会排程（曾导致周一 8:30 不执行）。
                val scheduleDate = resolveScheduleDate()
                val schedule = buildSchedule(scheduleDate)
                if (schedule.isEmpty()) {
                    LogFileManager.writeLog("任务列表为空，进入等待期")
                } else {
                    LogFileManager.action("开始执行本轮任务（${scheduleDate}），共 ${schedule.size} 个")
                    executeSchedule(schedule)
                }

                // 本轮结束，睡到下一个重置点
                if (isActive) waitUntilNextReset()
            }
        }
        tempJob.invokeOnCompletion {
            _isRunning.value = false
            runningDetail = "空闲"
            RemoteSnapshot.invalidateCache()
        }
        job = tempJob
    }

    /**
     * 链式任务主循环
     * for 循环保证顺序执行，每个任务经历三个阶段：
     *   阶段1 - delay(到任务时间) + 通知栏秒级倒计时
     *   阶段2 - openApplication() + select{超时|打卡} 竞态等待
     *   阶段3 - 推进到下一个任务（或全部完成 emit Completed）
     */
    private suspend fun CoroutineScope.executeSchedule(schedule: List<ScheduledTask>) {
        var executedCount = 0
        var skippedCount = 0

        persistScheduledTimes(schedule)

        for (task in schedule) {
            val now = System.currentTimeMillis()

            // 逐任务执行日节假日判定：任务实际执行日若为休息日/节假日则跳过。
            // 周期入口的 shouldSkipDay 只判「周期起始日」，而前重置点任务经 +1 天偏移后
            // 实际执行日可能落在次日，必须用真实执行日再判一次，避免节假日当天仍被打卡。
            val execDate = Instant.ofEpochMilli(task.actualTimeMillis)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
            if (shouldSkipDay(execDate)) {
                skippedCount++
                continue
            }

            // 任务时间已过：超过宽限则跳过；宽限内仍执行（进程被杀后闹钟略晚拉起的场景）
            if (task.actualTimeMillis <= now) {
                val lateBy = now - task.actualTimeMillis
                if (lateBy > PUNCH_LATE_GRACE_MS) {
                    skippedCount++
                    LogFileManager.writeLog(
                        "第 ${task.displayIndex} 个任务已过期（计划=${task.plannedTime}，" +
                                "实际=${task.actualTime}），跳过"
                    )
                    continue
                }
                LogFileManager.writeLog(
                    "第 ${task.displayIndex} 个任务略过期 ${lateBy}ms，宽限内仍执行"
                )
            }

            // ====== 阶段 1：倒计时等待 ======
            val delayMs = (task.actualTimeMillis - now).coerceAtLeast(0L)
            runningDetail =
                "等待第 ${task.displayIndex}/${schedule.size} 个任务 ${task.actualTime}"
            _tipsEvent.emit(
                TipsEvent.Executing(
                    task.displayIndex,
                    schedule.size,
                    task.actualTime,
                    task.plannedTime
                )
            )

            LogFileManager.writeLog(
                "调度第 ${task.displayIndex} 个任务，" +
                        "计划时间=${task.plannedTime}，" +
                        "实际时间=${task.actualTime}，" +
                        "延迟=${delayMs / 1000}s"
            )

            // 先挂 due 信号再排闹钟，避免到点极近时闹钟先到、信号未挂上导致 notifyPunchDue 空操作
            val dueSignal = if (delayMs > 0L) {
                CompletableDeferred<Unit>().also { pendingPunchDueSignal = it }
            } else null

            // 精确闹钟兜底：进程被杀后仍能到点唤醒；进程存活时与协程 delay 并行，到点闹钟多为空操作
            KeepAliveReceiver.scheduleNextPunchAlarms(
                DailyTaskApplication.get(),
                task.actualTimeMillis
            )

            if (dueSignal != null) {
                // 到点等待：协程 delay 为主，PUNCH_DUE 精确闹钟为兜底。
                // 进程冻结时协程 delay 不可靠，闹钟（系统级）到点广播会触发 notifyPunchDue()，
                // select 立即走信号分支进入打卡，避免"闹钟已触发但调度仍在倒计时傻等"（08-15/16 漏卡根因）。
                val countdownJob = launch {
                    updateCountdownWithNotification(delayMs) { remaining ->
                        val seconds = (remaining / 1000).toInt()
                        ForegroundRunningService.emitNotificationText("${seconds.formatTime()}后执行第${task.displayIndex}个任务")
                    }
                }
                try {
                    select<Unit> {
                        countdownJob.onJoin { Unit }   // 自然到点
                        dueSignal.onAwait { Unit }     // PUNCH_DUE 闹钟兜底唤醒
                    }
                } finally {
                    countdownJob.cancel()
                    pendingPunchDueSignal = null
                    // 闹钟路径持的调度推进锁：select 已返回说明 Main 协程已跑起来，可释放省电
                    KeepAliveReceiver.releaseSchedulerAdvanceWakeLock()
                }
            }
            // 统一宽限校验（防"错误时间补执行"）：无论阶段1 因协程 delay 自然恢复还是 PUNCH_DUE 闹钟
            // 信号唤醒，唤醒时刻若已超过任务时刻 + 宽限（如被冻结两天后恢复、闹钟延迟投递），
            // 本场直接跳过，绝不在错误时间补打旧任务（08-15/16 压 2 天场景的最终兜底）。
            val wokeAt = System.currentTimeMillis()
            if (wokeAt - task.actualTimeMillis > PUNCH_LATE_GRACE_MS) {
                skippedCount++
                LogFileManager.writeLog(
                    "第 ${task.displayIndex} 个任务唤醒时已超过宽限（计划=${task.plannedTime}，" +
                            "实际=${task.actualTime}，晚到=${(wokeAt - task.actualTimeMillis) / 1000}s），跳过"
                )
                // 取消本场闹钟，避免残留广播再次触发
                KeepAliveReceiver.cancelPunchAlarms(DailyTaskApplication.get())
                continue
            }
            // 进入打卡阶段前取消本场闹钟，避免与执行中的流程叠触发
            KeepAliveReceiver.cancelPunchAlarms(DailyTaskApplication.get())

            // ====== 阶段 2：打开目标 App，等待打卡或超时 ======
            val timeoutSeconds = SaveKeyValues.loadInt(
                Constant.STAY_OVERTIME_KEY, Constant.DEFAULT_OVER_TIME
            )

            runningDetail =
                "执行第 ${task.displayIndex}/${schedule.size} 个任务（等待打卡）"

            // 打卡准备：屏幕当前息屏时先亮屏再打卡，保证打卡界面真实可见、可被无障碍正常操作；
            // 伪息屏蒙层显示时同样先保亮、再移除蒙层（避免蒙层释放 SCREEN_DIM 瞬间被系统休眠锁屏）。
            val keptAwakeForPunch = IdlePseudoMaskController.keepAwakeForPunchIfNeeded(DailyTaskApplication.get())
            val maskWasShowing = MaskOverlayHelper.isShowing()
            if (maskWasShowing) {
                LogFileManager.writeLog("定时任务：伪息屏蒙层显示中，临时移除")
                // 注意顺序：必须先 keepAwakeForPunch 建立 SCREEN_BRIGHT 保亮、再 hide 蒙层——
                // 蒙层 hide 会释放 SCREEN_DIM_WAKE_LOCK，若先 hide 后保活，释放瞬间系统可能按屏幕超时休眠锁屏
                // （真机复现：打卡瞬间 goToSleep + keyguard + 指纹图标，打卡界面被锁屏挡住空跑）。
                withContext(Dispatchers.Main) {
                    MaskOverlayHelper.hide(
                        DailyTaskApplication.get(),
                        MaskOverlayHelper.HideReason.TEMP_PUNCH
                    )
                }
            }

            KeepAliveReceiver.ensureFloatingWindow(DailyTaskApplication.get())
            if (!FloatingWindowService.isRunning) {
                delay(500)
            }
            DailyTaskApplication.get().openApplication()

            // 开启无障碍文本检测（仅「文本反馈」模式；「截屏反馈」不遍历无障碍节点树）
            val resultSource = SaveKeyValues.loadInt(
                Constant.RESULT_SOURCE_KEY, Constant.DEFAULT_INDEX
            )
            val feedbackMode = SaveKeyValues.loadInt(
                Constant.ACCESSIBILITY_FEEDBACK_MODE_KEY, 0
            )
            if (resultSource == 2 && feedbackMode == 1) {
                AutoProjectionAccessibilityService.setTextDetectionEnabled(true)
            }

            // Kotlin语法糖——竞态保护：select 只取先完成的分支，另一个自动取消
            var hasCaptured = false
            var captureDeferred: CompletableDeferred<String?>? = null
            val timeoutJob = launch {
                try {
                    updateCountdownWithNotification(timeoutSeconds * 1000L) { remaining ->
                        val tick = (remaining / 1000).toInt()
                        FloatingWindowController.updateTime(tick)

                        // 最后 5 秒兜底截屏（只触发一次）
                        if (tick <= 5 && !hasCaptured) {
                            if (resultSource == 1) {
                                // 截屏模式：MediaProjection
                                hasCaptured = true
                                delay(FloatingWindowController.SCREENSHOT_FADE_YIELD_MS)
                                captureDeferred = CaptureImageService.requestCaptureScreen()
                            } else if (resultSource == 2) {
                                // 无障碍模式（文本/截屏反馈）：AccessibilityService.takeScreenshot 兜底截屏。
                                // 文本反馈模式下识别不到成功结果时，也截一张发过去，作为“看不到文本”时的兜底。
                                hasCaptured = true
                                delay(FloatingWindowController.SCREENSHOT_FADE_YIELD_MS)
                                val a11yDeferred = AutoProjectionAccessibilityService.requestScreenshot()
                                captureDeferred = a11yDeferred
                                    ?: CompletableDeferred<String?>().apply { complete("") }
                            }
                        }
                    }
                } finally {
                    // 倒计时无论正常结束、打卡成功取消、还是异常，统一收起悬浮窗
                    FloatingWindowController.stopFloatSession()
                }
            }

            val clockInSuccess = select {
                // 分支 A：超时
                timeoutJob.onJoin { false }

                // 分支 B：打卡成功
                CompletableDeferred<Unit>().also { clockInDeferred = it }.onAwait { true }
            }

            timeoutJob.cancel()
            clockInDeferred = null
            // 倒计时结束，隐藏悬浮窗
            FloatingWindowController.updateTime(0)
            // 关闭无障碍文本检测
            AutoProjectionAccessibilityService.setTextDetectionEnabled(false)

            // 超时路径——先完成兜底截屏（仍停在目标 App），恢复悬浮窗后再回桌面/本 App。
            // 截屏期间 hideForScreenshot 会 GONE 整窗；若先跳转再等截屏，宠物迟迟不出现，
            // 安卓 15+ 缺少「可见悬浮窗」豁免时 bringDailyTaskToFront 会被拦，表现为停在目标 App。
            if (!clockInSuccess) {
                if (runningDetail.contains("等待打卡")) {
                    runningDetail = runningDetail.replace("等待打卡", "打卡超时")
                }

                // 兜底截图：按「实际权限」优先级选择截屏方式（与 resultSource 配置无关）：
                // 1) 已有预截图（打卡过程中的 MediaProjection 实截）→ 直接使用
                // 2) 系统无障碍权限已开启（且 Android 14+）→ AccessibilityService.takeScreenshot
                // 3) 截屏服务权限已授权（MediaProjection 处于 ACTIVE）→ CaptureImageService 兜底
                // 4) 都没有可用权限 → 截屏失败，降级为文字提醒
                var imagePath = ""
                if (hasCaptured) {
                    imagePath = captureDeferred?.await() ?: ""
                }
                if (imagePath.isEmpty()) {
                    imagePath = runCatching { tryFallbackScreenshot() }.getOrNull() ?: ""
                }
                // 截屏结束：先恢复悬浮窗并回跳，再发超时邮件（邮件可能较慢，勿挡回被控端）
                // 不可只依赖 _returnToApp：开机自动调度时 MainActivity 可能从未启动，无人收集 SharedFlow。
                FloatingWindowController.restoreAfterScreenshot()
                returnAfterPunch(
                    restoreMask = maskWasShowing,
                    realScreenOff = keptAwakeForPunch && !maskWasShowing
                )

                if (imagePath.isNotEmpty()) {
                    // force=true：超时兜底通知是关键告警，跳过去重，保证一定送达（防「什么都没收到」）
                    MessageDispatcher.sendAttachmentMessage(
                        "任务执行结果通知",
                        StatusReporter.buildTimeoutAlertHtml("任务执行结果", "截图见附件，请手动检查是否打卡成功"),
                        imagePath,
                        force = true
                    )
                    LogFileManager.writeLog("发送打卡超时截屏: $imagePath")
                } else {
                    val failTip = StatusReporter.buildTimeoutAlertHtml(
                        "任务执行结果",
                        "超时未检测到打卡成功，且当前无可用的截屏权限（无障碍/截屏服务均未启用），请手动登录检查"
                    )
                    // force=true：关键告警跳过去重，保证一定送达
                    // appendMeta=false：failTip 为 HTML，避免被纯文本壳包裹导致邮箱显示原始 HTML 源码
                    MessageDispatcher.sendMessage("任务执行结果通知", failTip, force = true, appendMeta = false)
                    LogFileManager.error("任务执行结果：无可截屏权限")
                }
                // 记录打卡超时到通知表：供状态查询日历标记"超时未确认" + "考勤记录"远程指令使用。
                // 与成功路径（MainActivity.onClockInSuccess 写"考勤打卡成功"）对称，message 含"考勤打卡超时"子串。
                val nowTs = System.currentTimeMillis().timestampToCompleteDate()
                scope?.launch {
                    try {
                        DatabaseWrapper.insertNotice(NotificationBean().apply {
                            packageName = Constant.getTargetApp()
                            noticeTitle = "考勤打卡"
                            noticeMessage = "考勤打卡超时 · $nowTs"
                            postTime = nowTs
                        })
                    } catch (e: Exception) {
                        // 写入失败不影响超时兜底邮件发送，但需记录以便排查（曾因主线程写库被 Room 静默拒绝而丢失超时记录）
                        LogFileManager.error("写入打卡超时记录失败: ${e.message}")
                    }
                }
            } else {
                // 成功路径：丢弃末段预截图，立刻恢复悬浮窗并回被控端
                captureDeferred?.cancel()
                FloatingWindowController.restoreAfterScreenshot()
                returnAfterPunch(
                    restoreMask = maskWasShowing,
                    realScreenOff = keptAwakeForPunch && !maskWasShowing
                )
            }

            // 无论是否恢复蒙层，只要打卡前亮过屏就释放打卡保活，让屏幕回到系统自然管理
            if (keptAwakeForPunch) {
                IdlePseudoMaskController.releaseKeepAwakeForPunch(DailyTaskApplication.get())
            }

            // ====== 阶段 3：回到主界面，处理结果 ======
            executedCount++
        }

        // ====== 全部完成 ======
        val message = when {
            executedCount + skippedCount == 0 -> "无任务可供执行"
            executedCount == 0 -> "本轮所有任务均已过期，跳过（$skippedCount 个），无需执行"
            skippedCount > 0 -> "本轮任务已全部执行完毕（执行 $executedCount 个，跳过 $skippedCount 个）"
            else -> "本轮任务已全部执行完毕"
        }
        runningDetail = message
        LogFileManager.writeLog(message)
        ForegroundRunningService.emitNotificationText(message)
        KeepAliveReceiver.cancelPunchAlarms(DailyTaskApplication.get())
        // 本轮全部被跳过（如整日休息/节假日）→ 维持"今日休息"UI 高亮
        if (executedCount == 0 && skippedCount > 0) {
            _tipsEvent.emit(TipsEvent.Skip)
        }
    }

    /**
     * 等待到下一个每日重置时间（整点）。功耗最低 + 运行时可响应重置时间修改。
     *
     * 重置时间只能设为整点（分钟恒为 0），因此可一次性精确挂起到下一个整点重置点，
     * 无需每分钟轮询（轮询会白白唤醒 CPU，反而更耗电）。稳态下用单次 withTimeout
     * 挂起，零额外 CPU 唤醒；当 [notifyResetTimeChanged] 被调用（用户在设置页修改
     * 重置小时）时，通过 CompletableDeferred 信号立即唤醒并重新计算目标，下一轮
     * 等待即时生效，不中断正在执行的打卡任务。
     */
    private suspend fun waitUntilNextReset() {
        // 一次性提示：本轮任务已完成，进入等待期
        LogFileManager.writeLog("进入每日重置等待期")
        runningDetail = "本轮已完成，等待下次重置"
        _tipsEvent.emit(TipsEvent.Completed)
        ForegroundRunningService.emitNotificationText("本轮任务已执行完毕，等待下次重置")

        while (true) {
            val resetHour = SaveKeyValues.loadInt(
                Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR
            )
            val waitSeconds = calculateSecondsUntilReset(resetHour)

            // 功耗最低：单次精确挂起到下一个整点重置点，无 CPU 唤醒；
            // 重置时间被修改时由信号立即唤醒重算（不改正在执行的任务）。
            // 等待结束三途径：自然到点（withTimeout 超时）/ 重置时间修改（signal）/ 重置到点闹钟兜底（reachedSignal）
            val signal = CompletableDeferred<Unit>()
            pendingResetSignal = signal
            val reachedSignal = CompletableDeferred<Unit>()
            pendingResetReachedSignal = reachedSignal
            try {
                val reached = withTimeout(waitSeconds * 1000L) {
                    select<Boolean> {
                        signal.onAwait { false }        // 重置时间被修改 → 重新计算
                        reachedSignal.onAwait { true }  // RESET_TASK/TIME_TICK 兜底 → 立即重排
                    }
                }
                if (reached) {
                    LogFileManager.action("到达重置时间点（闹钟兜底），重排下一轮任务")
                    return
                }
            } catch (_: TimeoutCancellationException) {
                // 自然到达重置点，交给外层循环重排下一轮任务
                LogFileManager.action("到达重置时间点，重排下一轮任务")
                // 注：已移除每日自动版本检查——更新检测仅在用户点击「设置→关于→检查更新」时触发
                return
            } finally {
                pendingResetSignal = null
                pendingResetReachedSignal = null
                KeepAliveReceiver.releaseSchedulerAdvanceWakeLock()
            }
            // 被信号唤醒：重置时间已修改，重新计算等待
            LogFileManager.writeLog("重置时间被修改，重新计算每日重置等待")
        }
    }

    /**
     * 重置时间被修改时调用，立即唤醒 [waitUntilNextReset] 重新计算等待目标。
     * 不中断正在执行的打卡任务，仅影响"等待下次重置"阶段。
     */
    fun notifyResetTimeChanged() {
        pendingResetSignal?.complete(Unit)
    }

    /**
     * PUNCH_DUE 精确闹钟到点：调度处于阶段1 倒计时等待（可能协程被冻结）时，
     * 唤醒 select 立即进入打卡。调度未运行或不在阶段1 时无操作（幂等）并释放推进锁。
     * 由 KeepAliveReceiver.ACTION_PUNCH_DUE 调用。
     */
    fun notifyPunchDue() {
        val sig = pendingPunchDueSignal
        if (sig == null) {
            KeepAliveReceiver.releaseSchedulerAdvanceWakeLock()
            return
        }
        sig.complete(Unit)
    }

    /**
     * 重置点已到（RESET_TASK 闹钟 / TIME_TICK 兜底触发）：唤醒 [waitUntilNextReset] 立即返回，
     * 交给外层循环重排下一轮任务。调度未在等待期时无操作（幂等）并释放推进锁。
     * 修复旧漏洞：调度协程在等待期 isRunning()=true，旧兜底逻辑直接 return 导致重置永远无法推进。
     */
    fun notifyResetTimeReached() {
        val sig = pendingResetReachedSignal
        if (sig == null) {
            KeepAliveReceiver.releaseSchedulerAdvanceWakeLock()
            return
        }
        sig.complete(Unit)
    }

    /**
     * 超时兜底截屏：按「实际权限」优先级选择截屏方式，与 resultSource 配置无关。
     * 优先级 1：系统无障碍权限已开启且 Android 14+ → AccessibilityService.takeScreenshot
     * 优先级 2：截屏服务权限已授权（MediaProjection 处于 ACTIVE）→ CaptureImageService 兜底
     * 都没有可用权限 → 返回空串（截屏失败）
     */
    suspend fun tryFallbackScreenshot(): String {
        val ctx = DailyTaskApplication.get()
        // 优先级1：无障碍截屏（无需截屏服务授权，只要系统无障碍已开启）
        if (AutoProjectionAccessibilityService.isEnabled(ctx)
            && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        ) {
            LogFileManager.writeLog("超时兜底：检测到无障碍权限，走 AccessibilityService.takeScreenshot")
            val deferred = AutoProjectionAccessibilityService.requestScreenshot()
            if (deferred != null) {
                return runCatching { withTimeout(8000) { deferred.await() } }.getOrNull() ?: ""
            }
        }
        // 优先级2：截屏服务（MediaProjection）兜底
        if (ProjectionSession.isStateActive()) {
            LogFileManager.writeLog("超时兜底：无无障碍权限，走截屏服务（MediaProjection）兜底")
            val deferred = CaptureImageService.requestCaptureScreen()
            return runCatching { withTimeout(8000) { deferred.await() } }.getOrNull() ?: ""
        }
        // 都没有可用权限 → 截屏失败
        LogFileManager.error("超时兜底：无障碍与截屏服务均不可用，截屏失败")
        return ""
    }

    /**
     * 打卡结束后回被控端。必须在调度器内执行：开机自动调度时 MainActivity 可能未创建，
     * 仅 emit SharedFlow 会丢信号，真机（尤其 Android 15/16 BAL）会停在目标 App。
     */
    private suspend fun returnAfterPunch(restoreMask: Boolean, realScreenOff: Boolean = false) {
        val app = DailyTaskApplication.get()
        KeepAliveReceiver.ensureFloatingWindow(app)
        // 贴边悬浮窗需可见，才有安卓 15+「可见 overlay」后台启动 Activity 豁免
        FloatingWindowController.show()
        delay(400)

        withContext(Dispatchers.Main) {
            val home = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (restoreMask) {
                val pseudoOn = AppRuntimeConfig.isForcePseudoMask()
                val screenMode = AppRuntimeConfig.getScreenMode()
                if (pseudoOn || screenMode == Constant.SCREEN_MODE_PSEUDO) {
                    // 伪息屏开，或伪息屏关但屏幕模式=伪息屏：恢复保亮伪息屏（微亮常驻）
                    // 先盖 overlay 保证 SCREEN_DIM 锁不间断，再拉起 MainActivity 接管
                    LogFileManager.action("定时任务结束，恢复伪息屏蒙层")
                    MaskOverlayHelper.show(app)
                    app.startActivity(home)
                    Handler(Looper.getMainLooper()).postDelayed({
                        app.bringDailyTaskToFront(true)
                    }, 800L)
                    LogFileManager.action("打卡结束：恢复伪息屏并拉起被控端")
                } else if (screenMode == Constant.SCREEN_MODE_OFF) {
                    // 伪息屏关 + 屏幕模式=息屏：盖不保亮黑蒙层，等待系统超时自然灭屏锁屏
                    // （SCREEN_OFF 时由 ForegroundRunningService 摘除蒙层）
                    app.startActivity(home)
                    Handler(Looper.getMainLooper()).postDelayed({
                        app.bringDailyTaskToFront(false)
                    }, 800L)
                    MaskOverlayHelper.show(app, keepAwake = false)
                    LogFileManager.action("打卡结束：伪息屏关+模式息屏，盖不保亮黑蒙层等待系统超时灭屏")
                } else {
                    // 伪息屏关 + 屏幕模式=亮屏（常亮）：不恢复蒙层，拉起被控端前台常亮阻止系统息屏
                    app.startActivity(home)
                    Handler(Looper.getMainLooper()).postDelayed({
                        app.bringDailyTaskToFront(false)
                    }, 800L)
                    LogFileManager.action("打卡结束：伪息屏关+模式亮屏，不恢复蒙层（前台常亮阻止系统息屏）")
                }
            } else if (AppRuntimeConfig.isForcePseudoMask()) {
                // 伪息屏开启但打卡前未盖蒙层：回桌面，由后台伪息屏倒计时接管
                app.startActivity(home)
                LogFileManager.action("打卡结束：伪息屏开启，回桌面由后台蒙层接管")
            } else if (SaveKeyValues.loadBoolean(Constant.BACK_TO_HOME_KEY, Constant.BACK_TO_HOME_DEFAULT)) {
                app.startActivity(home)
                Handler(Looper.getMainLooper()).postDelayed({
                    app.bringDailyTaskToFront(false)
                }, 800L)
                LogFileManager.action("打卡结束：回桌面并拉起被控端")
                // 真息屏打卡优化：伪息屏关 + 屏幕模式=息屏 + 打卡前真息屏 →
                // 回到前台后立刻盖「不保亮黑蒙层」，等待系统超时自然灭屏（终态与打卡前一致）。
                // 本分支内伪息屏必为关闭（isForcePseudoMask 分支已先行），故仅需校验模式。
                if (realScreenOff &&
                    AppRuntimeConfig.getScreenMode() == Constant.SCREEN_MODE_OFF
                ) {
                    MaskOverlayHelper.show(app, keepAwake = false)
                    LogFileManager.action("打卡结束：真息屏场景，盖不保亮黑蒙层等待系统超时")
                }
            } else {
                // 与 backToMainActivity(isPunchReturn=true) 一致：关闭「返回桌面」时不拉起
                LogFileManager.action("打卡结束：未开启返回桌面，保持当前界面")
            }
        }
        // MainActivity 若存活：仅切任务 Tab（导航已由上方完成）
        _returnToApp.tryEmit(Unit)
    }

    /**
     * 打卡成功通知
     * 调用链：NotificationMonitorService.emitMonitorEvent(ClockInSuccess)
     *       （MainActivity 收集后也会再调一次，complete 幂等）
     * 效果：完成 clockInDeferred，select{} 走分支 B，推进到下一个任务
     */
    fun notifyClockIn() {
        // 成功瞬间结束「等待打卡」窗口，避免伪息屏门禁 / 其它逻辑仍把本段当作活跃打卡
        if (runningDetail.contains("等待打卡")) {
            runningDetail = runningDetail.replace("等待打卡", "打卡完成")
        }
        clockInDeferred?.complete(Unit)
    }

    fun stopTask() {
        if (!_isRunning.value) {
            LogFileManager.writeLog("任务未运行，无需停止")
            return
        }

        LogFileManager.action("停止执行每日任务")
        SaveKeyValues.saveBoolean(Constant.SCHEDULER_WANTED_KEY, false)
        KeepAliveReceiver.cancelPunchAlarms(DailyTaskApplication.get())
        job?.cancel()
        job = null
        pendingPunchDueSignal = null
        pendingResetReachedSignal = null
        _isRunning.value = false
        runningDetail = "空闲"
        RemoteSnapshot.invalidateCache()
        ForegroundRunningService.emitNotificationText("为保证程序正常运行，请勿移除此通知")
    }

    /**
     * 因外部错误请求停止（目标 App 未安装、启动失败等）
     * 由 Context.openApplication() 在无法打开目标 App 时调用
     *
     * 与 stopTask() 的区别：
     *   stopTask()     — 用户主动点击"停止"，发消息通知
     *   requestStopDueToError() — 系统错误停止，不发消息通知，只重置调度器
     */
    fun requestStopDueToError(reason: String) {
        LogFileManager.error("因错误请求停止：$reason")
        SaveKeyValues.saveBoolean(Constant.SCHEDULER_WANTED_KEY, false)
        KeepAliveReceiver.cancelPunchAlarms(DailyTaskApplication.get())
        job?.cancel()
        job = null
        pendingPunchDueSignal = null
        pendingResetReachedSignal = null
        _isRunning.value = false
        runningDetail = "空闲"
        RemoteSnapshot.invalidateCache()
    }

    /**
     * 自校准倒计时 tick，支持 UI 回调。
     * 使用 elapsedRealtime 确保休眠唤醒后剩余时间准确。
     * 剩余时间较长时降低刷新频率，以降低通知栏与 CPU 唤醒开销。
     */
    private suspend fun CoroutineScope.updateCountdownWithNotification(
        totalMs: Long, onTick: suspend (remainingMs: Long) -> Unit
    ) {
        val target = SystemClock.elapsedRealtime() + totalMs
        while (isActive) {
            val remaining = target - SystemClock.elapsedRealtime()
            if (remaining <= 0) break
            onTick(remaining)
            val step = minOf(resolveCountdownStepMs(remaining), remaining).coerceAtLeast(1)
            delay(step)
        }
    }

    /**
     * 倒计时刷新步长：越接近目标越频繁；省电模式下更稀疏。
     */
    private fun resolveCountdownStepMs(remainingMs: Long): Long {
        val powerSave = AppRuntimeConfig.isPowerSaveMode()
        return when {
            remainingMs > 3_600_000L -> if (powerSave) 900_000L else 300_000L // >1h
            remainingMs > 300_000L -> if (powerSave) 120_000L else 60_000L   // >5min
            remainingMs > 60_000L -> if (powerSave) 30_000L else 15_000L     // >1min
            else -> 1_000L
        }
    }

    /** 节假日/休息日跳过日志去重：相同文案连续只打一次，避免多任务循环刷屏 */
    @Volatile
    private var lastSkipDayLogMsg: String? = null

    private fun logSkipDayOnce(msg: String) {
        if (msg == lastSkipDayLogMsg) return
        lastSkipDayLogMsg = msg
        LogFileManager.writeLog(msg)
    }

    private fun shouldSkipDay(date: LocalDate): Boolean {
        val skipEnabled = SaveKeyValues.loadBoolean(Constant.SKIP_HOLIDAY_KEY, true)
        if (!skipEnabled) return false

        val customWorkdays = CustomWorkdayManager.loadWorkdays()

        // 法定节假日
        if (ChinaHolidayManager.isHoliday(date)) {
            logSkipDayOnce("$date 为法定节假日，跳过任务")
            return true
        }

        // 调休补班日（例外：周末但要上班）
        if (ChinaHolidayManager.isWorkday(date)) {
            logSkipDayOnce("$date 为调休补班日，正常执行任务")
            return false
        }

        // 不在自定义工作日内，即为休息日
        if (date.dayOfWeek !in customWorkdays) {
            logSkipDayOnce("$date 不在自定义工作日内，跳过任务")
            return true
        }

        return false
    }

    /**
     * 解析当前应调度哪一天的任务：始终返回「今天」。
     *
     * 周期归属不再通过"返回今天/昨天"硬切，而是统一交给 [loadTodayTaskPlans] 按
     * "当前是否已过今日重置点 + 任务时刻是否早于重置点" 决定任务落在「本轮周期(今天)」
     * 还是「下一轮周期(明天 +1 天)」。例如重置点 13:00、任务 09:00：
     *   - 当前已过 13:00 → 09:00 属于下一轮周期 → 排到明天 09:00 执行
     *   - 当前未过 13:00 → 09:00 属于当前周期，已过去则跳过，否则今天执行
     */
    private fun resolveScheduleDate(): LocalDate {
        return LocalDate.now()
    }

    /**
     * 从数据库加载所有任务，计算出当日实际执行时间，按时间排序
     * */
    private suspend fun buildSchedule(date: LocalDate): List<ScheduledTask> {
        return loadTodayTaskPlans(date).map { plan ->
            ScheduledTask(
                task = plan.task,
                displayIndex = plan.index,
                plannedTime = plan.plannedTime,
                actualTime = plan.actualTime,
                actualTimeMillis = plan.actualTimeMillis
            )
        }
    }

    /**
     * 供状态查询 / 任务通知使用的当日任务快照。
     *
     * 跨天归属：重置时间只能设为整点。若当前已过「今日」重置点，则任务时刻早于重置点的
     * 任务属于「下一轮调度周期」，实际执行时间戳偏移 +1 天（排到明天同一时刻）；
     * 任务时刻晚于/等于重置点的仍属本轮周期，今天执行。未过重置点时全部按今天处理
     * （早于此刻的已过期跳过，晚于此刻的今天执行）。
     */
    suspend fun loadTodayTaskPlans(
        date: LocalDate = LocalDate.now(),
        usePersisted: Boolean = false
    ): List<TaskPlanItem> {
        val allTasks = withContext(Dispatchers.IO) {
            DatabaseWrapper.loadAllTask()
        }
        if (allTasks.isEmpty()) return emptyList()

        val resetHour = SaveKeyValues.loadInt(
            Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR
        )
        val nowMillis = System.currentTimeMillis()

        // 当前是否已过「今日」重置点（重置点恒为整点:00）
        val resetPointToday = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, resetHour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val isPastReset = nowMillis >= resetPointToday

        val baseMillis = date
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        val oneDayMillis = 24L * 3_600_000L

        val scheduledMap = if (usePersisted) loadScheduledTimes() else emptyMap()
        return allTasks.map { task ->
            val persisted = scheduledMap[task.id]
            val (actualTime, actualMillis) = if (persisted != null && isSameDay(persisted, date)) {
                val cal = Calendar.getInstance().apply { timeInMillis = persisted }
                val t = String.format(
                    "%02d:%02d:%02d",
                    cal.get(Calendar.HOUR_OF_DAY),
                    cal.get(Calendar.MINUTE),
                    cal.get(Calendar.SECOND)
                )
                t to persisted
            } else {
                // 兜底：未持久化或归属日不符（如刚过重置点、新轮次尚未排程），按原逻辑重算
                val at = task.resolveExecutionTime()
                val parts = at.split(":").map { it.toInt() }
                var millis = baseMillis +
                        parts[0] * 3_600_000L +
                        parts[1] * 60_000L +
                        parts[2] * 1_000L
                // 关键修正：过重置点后，任务时刻 < 重置点 → 属于下一轮周期 → 偏移 +1 天
                if (isPastReset && parts[0] < resetHour) {
                    millis += oneDayMillis
                }
                at to millis
            }
            Triple(task, actualTime, actualMillis)
        }.sortedBy { it.third }
            .mapIndexed { index, (task, actualTime, actualMillis) ->
                TaskPlanItem(
                    task = task,
                    index = index + 1,
                    plannedTime = task.time,
                    actualTime = actualTime,
                    actualTimeMillis = actualMillis
                )
            }
    }

    private fun persistScheduledTimes(schedule: List<ScheduledTask>) {
        val raw = schedule.joinToString(",") { "${it.task.id}:${it.actualTimeMillis}" }
        SaveKeyValues.saveString(Constant.SCHEDULED_EXEC_TIME_KEY, raw)
    }

    private fun loadScheduledTimes(): Map<Int, Long> {
        val raw = SaveKeyValues.loadString(Constant.SCHEDULED_EXEC_TIME_KEY)
        if (raw.isEmpty()) return emptyMap()
        val map = mutableMapOf<Int, Long>()
        raw.split(",").forEach { pair ->
            val idx = pair.indexOf(':')
            if (idx > 0) {
                runCatching {
                    val id = pair.substring(0, idx).toInt()
                    val millis = pair.substring(idx + 1).toLong()
                    map[id] = millis
                }
            }
        }
        return map
    }

    private fun isSameDay(millis: Long, date: LocalDate): Boolean {
        val d = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
        return d == date
    }

    /**
     * 供状态查询日历使用：判断指定日期是否安排了打卡任务。
     * 已配置任务且当日不被跳过（非节假日/自定义休息日）时返回 true。
     */
    suspend fun isPunchScheduled(date: LocalDate): Boolean {
        val allTasks = withContext(Dispatchers.IO) {
            DatabaseWrapper.loadAllTask()
        }
        return isPunchScheduled(date, allTasks)
    }

    /** 复用已加载的任务列表，避免日历逐日重复查库 */
    fun isPunchScheduled(date: LocalDate, allTasks: List<DailyTaskBean>): Boolean {
        if (allTasks.isEmpty()) return false
        return !shouldSkipDay(date)
    }

    /**
     * 今天是否为「跳过日」：已开启「跳过节假日」且今天为法定节假日 / 调休补班外的休息日 / 自定义休息日。
     * 供状态药丸、状态查询邮件等统一判断，避免节假日/休息日仍显示「待执行」。
     */
    fun isTodaySkipped(): Boolean = shouldSkipDay(LocalDate.now())

    fun secondsUntilNextReset(): Int {
        val resetHour = SaveKeyValues.loadInt(
            Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR
        )
        return calculateSecondsUntilReset(resetHour)
    }

    /**
     * 计算距离下一次重置还有多少秒
     */
    private fun calculateSecondsUntilReset(resetHour: Int): Int {
        val now = Calendar.getInstance()
        val target = now.clone() as Calendar
        target.set(Calendar.HOUR_OF_DAY, resetHour)
        target.set(Calendar.MINUTE, 0)
        target.set(Calendar.SECOND, 0)
        target.set(Calendar.MILLISECOND, 0)

        if (now.timeInMillis >= target.timeInMillis) {
            target.add(Calendar.DATE, 1)
        }

        val diffMillis = target.timeInMillis - now.timeInMillis
        // ceil 到秒并兜底至少 1 秒：边界处 (target-now) 不足 1s 时整数除法会截断为 0，
        // 导致 withTimeout(0) 立即返回 → 外层重排 → 又立即返回，形成 21:59:59 刷屏死循环。
        return ((diffMillis + 999) / 1000).toInt().coerceAtLeast(1)
    }

    data class TaskPlanItem(
        val task: DailyTaskBean,
        val index: Int,
        val plannedTime: String,
        val actualTime: String,
        val actualTimeMillis: Long
    ) {
        fun statusLabel(nowMillis: Long = System.currentTimeMillis()): String {
            // 跳过日（节假日/休息日且开启跳过）下，今日任务统一标记为已跳过
            if (TaskScheduler.isTodaySkipped()) return "已跳过"
            return if (actualTimeMillis > nowMillis) "待执行" else "已过点"
        }
    }

    private data class ScheduledTask(
        val task: DailyTaskBean,
        val displayIndex: Int,
        val plannedTime: String,
        val actualTime: String,
        val actualTimeMillis: Long
    )
}
