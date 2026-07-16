package com.pengxh.daily.app.service

import android.app.Notification
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.pengxh.daily.app.extensions.openApplication
import com.pengxh.daily.app.service.AutoProjectionAccessibilityService
import com.pengxh.daily.app.service.CaptureImageService
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.sqlite.bean.NotificationBean
import com.pengxh.daily.app.ui.MainActivity
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.FloatingWindowController
import com.pengxh.daily.app.utils.LogFileManager
import com.pengxh.daily.app.utils.MaskOverlayHelper
import com.pengxh.daily.app.utils.MessageDispatcher
import com.pengxh.daily.app.utils.MonitorEvent
import com.pengxh.daily.app.utils.ProjectionSession
import com.pengxh.daily.app.utils.StatusReporter
import com.pengxh.daily.app.utils.TaskScheduler
import com.pengxh.kt.lite.extensions.show
import com.pengxh.kt.lite.extensions.timestampToCompleteDate
import com.pengxh.kt.lite.utils.SaveKeyValues
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * @description: 状态栏监听服务
 * @author: Pengxh
 * @email: 290677893@qq.com
 * @date: 2019/12/25 23:17
 */
class NotificationMonitorService : NotificationListenerService() {
    companion object {
        private val _events = MutableSharedFlow<MonitorEvent>(extraBufferCapacity = 2)
        val events = _events.asSharedFlow()

        /**
         * 发送事件
         */
        fun emitMonitorEvent(event: MonitorEvent) {
            _events.tryEmit(event)
        }

        private val _listenerState = MutableSharedFlow<Boolean>(replay = 1, extraBufferCapacity = 1)
        val listenerState = _listenerState.asSharedFlow()

        /**
         * 发送监听状态
         */
        fun emitListenerState(connected: Boolean) {
            _listenerState.tryEmit(connected)
        }
    }

    private val kTag = "MonitorService"
    // 允许指令来源：微信/QQ/TIM/支付宝 + 所有目标打卡 App（钉钉/企微/飞书/M3）
    private val auxiliaryApp = arrayOf(
        Constant.WECHAT, Constant.QQ, Constant.TIM, Constant.ZFB,
        Constant.DING_DING, Constant.WEWORK, Constant.FEI_SHU, Constant.MOBILE_M3
    )
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var listenerConnected = false

    /**
     * 有可用的并且和通知管理器连接成功时回调
     */
    override fun onListenerConnected() {
        listenerConnected = true
        emitListenerState(true)
    }

    /**
     * 当有新通知到来时会回调
     */
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val pkg = sbn.packageName
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        // 灭屏/锁屏时微信等常把正文放到 MessagingStyle，或仅保留摘要；尽量拼出完整内容
        val notice = extractNoticeContent(extras)

        if (notice.isNullOrBlank()) {
            // 聚合成空不代表没有 EXTRA_TEXT，息屏场景下 EXTRA_TEXT 可能独立存在
            val rawText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
            if (rawText.isNullOrEmpty() || !rawText.contains(Constant.COMMAND_PREFIX)) return
            // 有 DT# 指令但 extractNoticeContent 没拼出来，直接用 rawText
            handleRemoteCommand(pkg, rawText)
            return
        }
        val targetApp = Constant.getTargetApp()

        // 保存指定包名的通知，其他的一律不保存
        saveTargetNotice(pkg, targetApp, title, notice)

        // 截屏模式选中 + 钉钉手动打卡 → 通知被第 99 行拦截，仅测试场景会出现
        // 目标应用打卡通知，如果设置通知监听，那么结果来源只能选通知监听。
        if (SaveKeyValues.loadInt(Constant.RESULT_SOURCE_KEY, Constant.DEFAULT_INDEX) == 0) {
            if (pkg == targetApp && notice.contains("成功")) {
                emitMonitorEvent(MonitorEvent.ClockInSuccess())
                "即将发送通知邮件，请注意查收".show(this)
                val messageTitle =
                    SaveKeyValues.loadString(Constant.MESSAGE_TITLE_KEY, "打卡结果通知")
                // 打卡成功：用 HTML 壳包裹系统通知原文
                MessageDispatcher.sendMessage(
                    messageTitle,
                    StatusReporter.buildClockInResultHtml(title, notice),
                    appendMeta = false
                )
            }
        }

        // 其他消息指令
        handleRemoteCommand(pkg, notice)
    }

    /**
     * 聚合通知标题 / 正文 / MessagingStyle 消息，避免灭屏后 EXTRA_TEXT 为空或仅为摘要导致指令丢失。
     */
    private fun extractNoticeContent(extras: Bundle): String? {
        val parts = LinkedHashSet<String>()
        fun add(value: CharSequence?) {
            val text = value?.toString()?.trim()
            if (!text.isNullOrEmpty()) {
                parts.add(text)
            }
        }

        add(extras.getCharSequence(Notification.EXTRA_TEXT))
        add(extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { add(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            if (messages != null) {
                Notification.MessagingStyle.Message
                    .getMessagesFromBundleArray(messages)
                    .forEach { add(it.text) }
            }
        }
        add(extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT))
        add(extras.getCharSequence(Notification.EXTRA_TITLE))
        return parts.joinToString("\n").ifBlank { null }
    }

    private fun saveTargetNotice(pkg: String, targetApp: String, title: String, notice: String) {
        if (pkg != targetApp && pkg !in auxiliaryApp) return

        NotificationBean().apply {
            packageName = pkg
            noticeTitle = title
            noticeMessage = notice
            postTime = System.currentTimeMillis().timestampToCompleteDate()
        }.also {
            serviceScope.launch {
                try {
                    DatabaseWrapper.insertNotice(it)
                } catch (e: Exception) {
                    Log.e(kTag, "Insert notice failed", e)
                }
            }
        }
    }

    /**
     * 处理远程指令
     */
    private fun handleRemoteCommand(pkg: String, notice: String) {
        if (pkg !in auxiliaryApp) return

        // 灭屏后正文可能带前缀（昵称/摘要），取最后一次 DT# 作为当前指令
        val commandIndex = notice.lastIndexOf(Constant.COMMAND_PREFIX)
        if (commandIndex < 0) return
        val command = notice.substring(commandIndex)

        Log.d(kTag, "收到远程指令: pkg=$pkg, cmd=$command, scopeActive=${serviceScope.isActive}")

        when {
            command.contains("执行任务") -> {
                LogFileManager.writeLog("收到执行任务指令")
                emitMonitorEvent(MonitorEvent.StartTaskCommand)
            }

            command.contains("终止任务") -> {
                LogFileManager.writeLog("收到终止任务指令")
                emitMonitorEvent(MonitorEvent.StopTaskCommand)
            }

            command.contains("开启循环") -> {
                LogFileManager.writeLog("收到开启循环指令")
                SaveKeyValues.saveBoolean(Constant.TASK_AUTO_RECYCLE_KEY, true)
                MessageDispatcher.sendMessage(
                    "循环任务状态通知", StatusReporter.buildCycleStatusHtml(true),
                    force = true, appendMeta = false
                )
            }

            command.contains("关闭循环") -> {
                LogFileManager.writeLog("收到关闭循环指令")
                SaveKeyValues.saveBoolean(Constant.TASK_AUTO_RECYCLE_KEY, false)
                MessageDispatcher.sendMessage(
                    "循环任务状态通知", StatusReporter.buildCycleStatusHtml(false),
                    force = true, appendMeta = false
                )
            }

            command.contains("息屏") -> {
                LogFileManager.writeLog("收到息屏指令")
                bringMainActivityForMask(showMask = true)
                Handler(Looper.getMainLooper()).postDelayed({
                    MaskOverlayHelper.show(this@NotificationMonitorService)
                }, 400L)
                emitMonitorEvent(MonitorEvent.ShowMaskCommand)
            }

            command.contains("亮屏") -> {
                LogFileManager.writeLog("收到亮屏指令")
                MaskOverlayHelper.hide(this)
                bringMainActivityForMask(showMask = false)
                emitMonitorEvent(MonitorEvent.HideMaskCommand)
            }

            command.contains("考勤记录") -> {
                LogFileManager.writeLog("收到考勤记录指令")
                launchOrWarn("考勤记录") {
                    val notices = try {
                        DatabaseWrapper.loadCurrentDayNotice()
                    } catch (e: Exception) {
                        Log.e(kTag, "Load notices failed", e)
                        emptyList()
                    }

                    val record = buildString {
                        var index = 1
                        notices.filter {
                            it.noticeMessage.contains("考勤打卡")
                        }.forEach {
                            append("【第${index}次】${it.noticeMessage}，时间：${it.postTime}\r\n")
                            index++
                        }
                    }

                    val htmlContent = try {
                        StatusReporter.buildAttendanceRecordHtml(record)
                    } catch (e: Exception) {
                        Log.e(kTag, "Build attendance HTML failed, fallback to plain text", e)
                        record.ifBlank { "暂无考勤记录" }
                    }
                    MessageDispatcher.sendMessage(
                        "当天考勤记录通知",
                        htmlContent,
                        force = true,
                        appendMeta = false
                    )
                }
            }

            command.contains("状态查询") -> {
                LogFileManager.writeLog("收到状态查询指令，准备回信")
                launchOrWarn("状态查询") {
                    try {
                        val content = StatusReporter.buildStatusReportHtml(
                            this@NotificationMonitorService, listenerConnected
                        )
                        LogFileManager.writeLog("状态查询 HTML 生成完成，发送邮件")
                        MessageDispatcher.sendMessage(
                            "状态查询通知", content, force = true, appendMeta = false,
                            onFailure = { err ->
                                Log.e(kTag, "状态查询邮件发送失败: $err")
                                LogFileManager.writeLog("状态查询邮件发送失败: $err")
                            }
                        )
                    } catch (e: Exception) {
                        Log.e(kTag, "状态查询处理失败", e)
                        LogFileManager.writeLog("状态查询处理失败: ${e.message}")
                        MessageDispatcher.sendMessage(
                            "状态查询通知",
                            StatusReporter.buildTimeoutAlertHtml("状态查询失败", e.message ?: "未知错误"),
                            force = true,
                            appendMeta = false
                        )
                    }
                }
            }

            command.contains("截屏") -> {
                LogFileManager.writeLog("收到截屏指令")
                val resultSource = SaveKeyValues.loadInt(
                    Constant.RESULT_SOURCE_KEY, Constant.DEFAULT_INDEX
                )
                val feedbackMode = SaveKeyValues.loadInt(
                    Constant.ACCESSIBILITY_FEEDBACK_MODE_KEY, 0
                )
                val canScreenshot = resultSource == 1 || (resultSource == 2 && feedbackMode == 0)
                val serviceReady = if (resultSource == 1) {
                    ProjectionSession.isStateActive()
                } else {
                    AutoProjectionAccessibilityService.isEnabled(this)
                }
                if (canScreenshot && serviceReady) {
                    openApplication { emitMonitorEvent(MonitorEvent.AppOpenedForScreenshot) }
                } else {
                    MessageDispatcher.sendMessage(
                        "截屏状态通知",
                        StatusReporter.buildScreenshotResultHtml(false, "截屏服务已断开或当前为文本反馈模式"),
                        appendMeta = false
                    )
                }
            }

            else -> {
                // 自定义打卡指令，用户可配置关键词（如 "打卡"），同样需要 DT# 前缀
                val key = SaveKeyValues.loadString(Constant.REMOTE_COMMAND_KEY, "打卡")
                if (command.contains(key)) {
                    LogFileManager.writeLog("收到远程打卡指令（关键词=$key）")
                    // 遥控"打卡"：一次性，只唤起目标 App 并倒计时，不关联任务调度
                    val timeoutSeconds = SaveKeyValues.loadInt(
                        Constant.STAY_OVERTIME_KEY, Constant.DEFAULT_OVER_TIME
                    )
                    MessageDispatcher.sendMessage(
                        "远程打卡通知",
                        StatusReporter.buildRemotePunchHtml(timeoutSeconds),
                        force = true,
                        appendMeta = false
                    )
                    // 伪息屏蒙层显示时，先临时移除，让目标 App 能正常打开和打卡
                    val maskWasShowing = MaskOverlayHelper.isShowing()
                    if (maskWasShowing) {
                        LogFileManager.writeLog("远程打卡：伪息屏蒙层显示中，临时移除以确保障碍不遮挡目标App")
                        MaskOverlayHelper.hide(this@NotificationMonitorService)
                    }
                    try {
                        openApplication {
                            launchOrWarn("远程打卡倒计时") {
                                val timeout = SaveKeyValues.loadInt(
                                    Constant.STAY_OVERTIME_KEY, Constant.DEFAULT_OVER_TIME
                                )
                                val resultSource = SaveKeyValues.loadInt(
                                    Constant.RESULT_SOURCE_KEY, Constant.DEFAULT_INDEX
                                )
                                val feedbackMode = SaveKeyValues.loadInt(
                                    Constant.ACCESSIBILITY_FEEDBACK_MODE_KEY, 0
                                )
                                // 无障碍文本反馈模式：开启文本检测
                                if (resultSource == 2 && feedbackMode == 1) {
                                    AutoProjectionAccessibilityService.setTextDetectionEnabled(true)
                                }
                                val target = SystemClock.elapsedRealtime() + timeout * 1000L
                                var hasCaptured = false
                                var captureDeferred: CompletableDeferred<String?>? = null
                                try {
                                    while (isActive) {
                                        val remaining = target - SystemClock.elapsedRealtime()
                                        if (remaining <= 0) break
                                        val tick = (remaining / 1000).toInt()
                                        FloatingWindowController.updateTime(tick)

                                        // 最后 5 秒兜底截屏（只触发一次）
                                        if (tick <= 5 && !hasCaptured) {
                                            if (resultSource == 1) {
                                                // 截屏模式：MediaProjection
                                                hasCaptured = true
                                                captureDeferred = CaptureImageService.requestCaptureScreen()
                                            } else if (resultSource == 2 && feedbackMode == 0) {
                                                // 无障碍-截屏反馈模式：AccessibilityService.takeScreenshot
                                                hasCaptured = true
                                                val a11yDeferred = AutoProjectionAccessibilityService.requestScreenshot()
                                                captureDeferred = a11yDeferred
                                                    ?: CompletableDeferred<String?>().apply { complete("") }
                                            }
                                        }

                                        delay(minOf(1000L, remaining).coerceAtLeast(1))
                                    }
                                    // 倒计时结束：关闭文本检测，返回本 App，不在目标 App 傻等着
                                    AutoProjectionAccessibilityService.setTextDetectionEnabled(false)
                                    LogFileManager.writeLog("远程打卡倒计时结束，返回主页")
                                    withContext(Dispatchers.Main) {
                                        try {
                                            startActivity(Intent(Intent.ACTION_MAIN).apply {
                                                addCategory(Intent.CATEGORY_HOME)
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            })
                                        } catch (e: Exception) {
                                            Log.w(kTag, "返回桌面失败: ${e.message}")
                                        }
                                        delay(300L)
                                        bringMainActivityForMask(showMask = maskWasShowing)
                                    }

                                    // 发送远程打卡结果
                                    if (hasCaptured && captureDeferred != null) {
                                        val imagePath = captureDeferred!!.await()
                                        if (imagePath != null && imagePath.isNotEmpty()) {
                                            MessageDispatcher.sendAttachmentMessage(
                                                "远程打卡结果",
                                                StatusReporter.buildScreenshotResultHtml(true, "远程打卡截图已发送"),
                                                imagePath
                                            )
                                        } else {
                                            MessageDispatcher.sendMessage(
                                                "远程打卡结果",
                                                StatusReporter.buildScreenshotResultHtml(false, "截图失败，请手动检查"),
                                                appendMeta = false
                                            )
                                        }
                                    }
                                } finally {
                                    // 恢复伪息屏蒙层
                                    if (maskWasShowing) {
                                        LogFileManager.writeLog("远程打卡结束，恢复伪息屏蒙层")
                                        withContext(Dispatchers.Main) {
                                            MaskOverlayHelper.show(this@NotificationMonitorService)
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(kTag, "远程打卡启动失败", e)
                        LogFileManager.writeLog("远程打卡启动失败: ${e.message}")
                        MessageDispatcher.sendMessage(
                            "远程打卡通知",
                            StatusReporter.buildTimeoutAlertHtml("远程打卡失败", e.message ?: "未知错误"),
                            force = true,
                            appendMeta = false
                        )
                        // 异常时也要恢复蒙层
                        if (maskWasShowing) {
                            Handler(Looper.getMainLooper()).post {
                                MaskOverlayHelper.show(this@NotificationMonitorService)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 安全启动协程：scope 已取消时直接打日志告警，避免静默丢弃指令。
     * block 以 CoroutineScope 为接收者，内部可直接使用 isActive / delay 等。
     */
    private fun launchOrWarn(tag: String, block: suspend CoroutineScope.() -> Unit) {
        if (!serviceScope.isActive) {
            Log.w(kTag, "serviceScope 已取消，无法处理指令: $tag")
            LogFileManager.writeLog("serviceScope 已取消，$tag 指令丢弃")
            return
        }
        serviceScope.launch {
            try {
                block()
            } catch (e: Exception) {
                Log.e(kTag, "$tag 处理异常", e)
                LogFileManager.writeLog("$tag 处理异常: ${e.message}")
            }
        }
    }

    /**
     * 尝试把主界面拉到前台，同步 Activity 内蒙层；即使后台启动被系统拦截，悬浮窗蒙层也已生效。
     */
    private fun bringMainActivityForMask(showMask: Boolean) {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
                putExtra(Constant.EXTRA_MASK_COMMAND, if (showMask) 1 else 0)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(kTag, "bringMainActivityForMask failed: ${e.message}")
            LogFileManager.writeLog("拉起主界面失败（蒙层仍可能已通过悬浮窗显示）: ${e.message}")
        }
    }

    /**
     * 当有通知移除时会回调
     */
    override fun onNotificationRemoved(sbn: StatusBarNotification) {}

    override fun onListenerDisconnected() {
        listenerConnected = false
        emitListenerState(false)
        // 主动请求系统重新绑定监听服务
        requestRebind(ComponentName(this, NotificationMonitorService::class.java))
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}