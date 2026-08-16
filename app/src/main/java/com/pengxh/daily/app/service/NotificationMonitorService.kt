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
import com.pengxh.daily.app.utils.AppRuntimeConfig
import com.pengxh.daily.app.utils.ConfigStore
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.EmailSecureConfig
import com.pengxh.daily.app.utils.FloatingWindowController
import com.pengxh.daily.app.utils.IdlePseudoMaskController
import com.pengxh.daily.app.utils.LogFileManager
import com.pengxh.daily.app.utils.MaskOverlayHelper
import com.pengxh.daily.app.utils.MessageDispatcher
import com.pengxh.daily.app.utils.MonitorEvent
import com.pengxh.daily.app.utils.MqttSecureConfig
import com.pengxh.daily.app.utils.ProjectionSession
import com.pengxh.daily.app.DailyTaskApplication
import com.pengxh.daily.app.service.MqttAgentService
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
import kotlinx.coroutines.withTimeout

/**
 * @description: 状态栏监听服务
 * @author: Pengxh
 * @email: 290677893@qq.com
 * @date: 2019/12/25 23:17
 */
class NotificationMonitorService : NotificationListenerService() {
    companion object {
        /** MQTT 桥接用：MqttAgentService 通过此引用直接触发打卡/考勤/截屏动作 */
        var instance: NotificationMonitorService? = null

        private val _events = MutableSharedFlow<MonitorEvent>(extraBufferCapacity = 2)
        val events = _events.asSharedFlow()

        private val writeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        private var lastSuccessWriteMs = 0L

        /** 遥控截屏会话结束时是否需恢复伪息屏 / 打卡保活 */
        @Volatile
        var pendingScreenshotMaskRestore = false

        @Volatile
        var pendingScreenshotKeepAwakeRelease = false

        /**
         * 发送事件
         */
        fun emitMonitorEvent(event: MonitorEvent) {
            _events.tryEmit(event)
            if (event is MonitorEvent.ClockInSuccess) {
                // 开机自动调度时 MainActivity 可能未启动，必须在此通知调度器，否则只会走超时分支
                TaskScheduler.notifyClockIn()
                val now = System.currentTimeMillis()
                if (now - lastSuccessWriteMs > 1500L) {
                    lastSuccessWriteMs = now
                    writeScope.launch {
                        try {
                            val ts = now.timestampToCompleteDate()
                            DatabaseWrapper.insertNotice(NotificationBean().apply {
                                packageName = Constant.getTargetApp()
                                noticeTitle = "考勤打卡"
                                noticeMessage = "考勤打卡成功 · $ts"
                                postTime = ts
                            })
                        } catch (e: Exception) {
                            Log.e("MonitorService", "Insert punch success notice failed", e)
                        }
                    }
                }
            }
        }

        private val _listenerState = MutableSharedFlow<Boolean>(replay = 1, extraBufferCapacity = 1)
        val listenerState = _listenerState.asSharedFlow()

        /**
         * 发送监听状态
         */
        fun emitListenerState(connected: Boolean) {
            _listenerState.tryEmit(connected)
        }

        /** 同步读取最近一次监听连接状态（供 RemoteSnapshot 快照使用） */
        fun isListenerConnected(): Boolean = _listenerState.replayCache.firstOrNull() == true
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
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onListenerConnected() {
        listenerConnected = true
        emitListenerState(true)
        LogFileManager.action("通知监听服务已连接")
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
        if (pkg in auxiliaryApp) {
            LogFileManager.action(
                "通知回调: pkg=$pkg, title=$title, notice空=${notice.isNullOrBlank()}, 含DT#=${notice?.contains(Constant.COMMAND_PREFIX) == true}"
            )
        }
        // 「暂停使用」开启：进程可能因系统绑定通知监听而存活，但不得执行任何远程指令 /
        // 打卡结果邮件 / 通知转移。仅保留目标通知入库。
        if (KeepAliveReceiver.isPaused()) {
            if (!notice.isNullOrBlank()) {
                saveTargetNotice(pkg, Constant.getTargetApp(), title, notice)
            }
            return
        }

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

        // 通知转移：开启后将目标打卡 App 的通知原文经邮件转发到目标手机
        forwardNotificationIfEnabled(pkg, targetApp, title, notice)

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
            // 用类型化读取，避免 API 33+ 下无类型版本返回非 Bundle[] 导致
            // getMessagesFromBundleArray 抛 ClassCastException 把整个回调打崩
            try {
                val messages: Array<Bundle>? =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        extras.getParcelableArray(Notification.EXTRA_MESSAGES, Bundle::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        extras.getParcelableArray(Notification.EXTRA_MESSAGES)
                            ?.mapNotNull { it as? Bundle }
                            ?.toTypedArray()
                    }
                messages?.let {
                    Notification.MessagingStyle.Message.getMessagesFromBundleArray(it)
                        .forEach { msg -> add(msg.text) }
                }
            } catch (e: Exception) {
                Log.w(kTag, "解析 MessagingStyle 消息失败: ${e.message}")
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
     * 通知转移：开启后，将目标打卡 App 的通知原文经用户已配置的现有消息渠道
     * （企业微信 / 邮箱，与打卡结果等通知共用同一渠道）转发到目标手机。
     * 范围限制为当前目标打卡 App（仅飞书/企微/钉钉/M3/自定义打卡应用），
     * 不去转发辅助聊天 App 或本应用自身。
     * 去重由 MessageDispatcher 的 90s 同标题同正文窗口保证，避免同一条通知重复发送刷屏。
     */
    private fun forwardNotificationIfEnabled(
        pkg: String,
        targetApp: String,
        title: String,
        notice: String
    ) {
        if (!SaveKeyValues.loadBoolean(Constant.NOTIFICATION_TRANSFER_KEY, false)) return
        if (pkg != targetApp) return
        if (notice.isBlank()) return

        val appName = Constant.getAppName(pkg)
        val time = System.currentTimeMillis().timestampToCompleteDate()
        val html = StatusReporter.buildNotificationTransferHtml(appName, title, notice, time)
        // 复用用户已配置的消息渠道（企业微信/邮箱），不强制特定通道，与打卡结果通知保持一致
        MessageDispatcher.sendMessage(
            "通知转移 · $appName",
            html,
            appendMeta = false
        )
    }

    /**
     * 校验通知转移配置是否齐全。开启时若渠道/授权码缺失，返回告警文案（仍会保存开关态，待用户补全配置）。
     * 复用与设置页通知转移开关一致的校验逻辑，远程开启时通过消息回执提示而非 Toast。
     */
    private fun validateTransferConfig(enabled: Boolean): String? {
        if (!enabled) return null
        val channel = SaveKeyValues.loadInt(Constant.MSG_CHANNEL_KEY, Constant.DEFAULT_INDEX)
        return when (channel) {
            0 -> { // 邮箱
                val obj = ConfigStore.get().load(Constant.EMAIL_CONFIG_KEY)
                val inbox =
                    if (!obj.isEmpty && obj.has("inbox")) obj.get("inbox").asString else ""
                if (inbox.isBlank() || EmailSecureConfig.loadAuthCode().isBlank()) {
                    "邮箱或授权码未配置，开启后无法转发，请先在设置补全"
                } else null
            }

            1 -> { // 企业微信
                val wxKey = SaveKeyValues.loadString(Constant.WX_WEB_HOOK_KEY, "")
                if (wxKey.isBlank()) "企业微信 Webhook 未配置，开启后无法转发，请先在设置补全" else null
            }

            else -> "消息渠道未配置，开启后无法转发，请先配置渠道"
        }
    }

    /**
     * 处理远程指令
     */
    private fun handleRemoteCommand(pkg: String, notice: String) {
        if (pkg !in auxiliaryApp) return
        // 「暂停使用」开启时忽略一切远程指令（执行任务 / 开启远程 / 打卡等），
        // 保证暂停期间被控端彻底安静，不拉起任何服务、不执行任何动作。
        if (KeepAliveReceiver.isPaused()) {
            LogFileManager.writeLog("暂停使用中，忽略远程指令")
            return
        }

        // 灭屏后正文可能带前缀（昵称/摘要），取最后一次 DT# 作为当前指令
        val commandIndex = notice.lastIndexOf(Constant.COMMAND_PREFIX)
        if (commandIndex < 0) return
        val command = notice.substring(commandIndex)

        Log.d(kTag, "收到远程指令: pkg=$pkg, cmd=$command, scopeActive=${serviceScope.isActive}")

        when {
            command.contains("执行任务") -> {
                LogFileManager.action("收到执行任务指令")
                emitMonitorEvent(MonitorEvent.StartTaskCommand)
            }

            command.contains("终止任务") -> {
                LogFileManager.writeLog("收到终止任务指令")
                emitMonitorEvent(MonitorEvent.StopTaskCommand)
            }

            command.contains("开启循环") -> {
                LogFileManager.writeLog("收到开启循环指令")
                SaveKeyValues.saveBoolean(Constant.TASK_AUTO_RECYCLE_KEY, true)
                KeepAliveReceiver.scheduleResetAlarm(this)
                MessageDispatcher.sendMessage(
                    "循环任务状态通知", StatusReporter.buildCycleStatusHtml(true),
                    force = true, appendMeta = false
                )
            }

            command.contains("关闭循环") -> {
                LogFileManager.writeLog("收到关闭循环指令")
                SaveKeyValues.saveBoolean(Constant.TASK_AUTO_RECYCLE_KEY, false)
                KeepAliveReceiver.cancelResetAlarm(this)
                MessageDispatcher.sendMessage(
                    "循环任务状态通知", StatusReporter.buildCycleStatusHtml(false),
                    force = true, appendMeta = false
                )
            }

            command.contains("息屏") -> {
                LogFileManager.writeLog("收到息屏指令")
                bringMainActivityForMask(showMask = true)
                Handler(Looper.getMainLooper()).postDelayed({
                    // 伪息屏关 + 模式=息屏：盖不保亮黑蒙层，系统超时自然灭屏锁屏（不恢复保亮伪息屏）
                    if (AppRuntimeConfig.isForcePseudoMask() ||
                        AppRuntimeConfig.getScreenMode() != Constant.SCREEN_MODE_OFF
                    ) {
                        MaskOverlayHelper.show(this@NotificationMonitorService)
                    } else {
                        MaskOverlayHelper.show(this@NotificationMonitorService, keepAwake = false)
                        LogFileManager.writeLog("息屏指令：伪息屏关+模式息屏，盖不保亮黑蒙层等待系统超时灭屏")
                    }
                }, 400L)
                emitMonitorEvent(MonitorEvent.ShowMaskCommand)
            }

            command.contains("亮屏") -> {
                LogFileManager.writeLog("收到亮屏指令")
                // 由 bringMainActivityForMask(false) 拉起并卸 Activity 蒙层；此处仅 SYNC 卸 overlay，避免抢前台竞态
                MaskOverlayHelper.hide(
                    this@NotificationMonitorService,
                    MaskOverlayHelper.HideReason.SYNC
                )
                bringMainActivityForMask(showMask = false)
                emitMonitorEvent(MonitorEvent.HideMaskCommand)
            }

            command.contains("考勤记录") -> {
                performAttendanceExport()
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
                                LogFileManager.error("状态查询邮件发送失败: $err")
                            }
                        )
                    } catch (e: Exception) {
                        Log.e(kTag, "状态查询处理失败", e)
                        LogFileManager.error("状态查询处理失败: ${e.message}")
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
                performScreenshot()
            }

            command.contains("开启转移") -> {
                LogFileManager.writeLog("收到开启通知转移指令")
                val warning = validateTransferConfig(true)
                SaveKeyValues.saveBoolean(Constant.NOTIFICATION_TRANSFER_KEY, true)
                MessageDispatcher.sendMessage(
                    "通知转移状态通知",
                    StatusReporter.buildTransferStatusHtml(true, warning),
                    force = true, appendMeta = false
                )
            }

            command.contains("关闭转移") -> {
                LogFileManager.writeLog("收到关闭通知转移指令")
                SaveKeyValues.saveBoolean(Constant.NOTIFICATION_TRANSFER_KEY, false)
                MessageDispatcher.sendMessage(
                    "通知转移状态通知",
                    StatusReporter.buildTransferStatusHtml(false, null),
                    force = true, appendMeta = false
                )
            }

            command.contains("开启远程") -> {
                LogFileManager.action("收到开启远程指令")
                SaveKeyValues.saveBoolean(Constant.MQTT_ENABLED_KEY, true)
                val intent = Intent(this, MqttAgentService::class.java)
                val valid = SaveKeyValues.loadString(Constant.MQTT_BROKER_KEY, "").isNotBlank()
                        && SaveKeyValues.loadString(Constant.MQTT_USER_KEY, "").isNotBlank()
                        && MqttSecureConfig.loadPass().isNotBlank()
                if (valid) {
                    try {
                        startForegroundService(intent)
                    } catch (e: Exception) {
                        // Android 15+ 后台启动前台服务受限时，调用点也会抛异常；
                        // 绝不能让它从通知监听回调里逃逸，否则整个进程（含远程指令链路）崩溃。
                        LogFileManager.error("开启远程指令拉起 MQTT 服务失败：${e.message}")
                    }
                    MessageDispatcher.sendMessage(
                        "远程服务状态通知",
                        "已开启本机远程控制服务（MQTT），控制端可重新连接。",
                        force = true, appendMeta = false
                    )
                } else {
                    "MQTT 未配置完整，无法开启远程".show(this)
                }
            }

            command.contains("关闭远程") -> {
                LogFileManager.action("收到关闭远程指令")
                SaveKeyValues.saveBoolean(Constant.MQTT_ENABLED_KEY, false)
                stopService(Intent(this, MqttAgentService::class.java))
                MessageDispatcher.sendMessage(
                    "远程服务状态通知",
                    "已关闭本机远程控制服务（MQTT）。",
                    force = true, appendMeta = false
                )
            }

            else -> {
                // 自定义打卡指令，用户可配置关键词（如 "打卡"），同样需要 DT# 前缀
                val key = SaveKeyValues.loadString(Constant.REMOTE_COMMAND_KEY, "打卡")
                if (command.contains(key)) {
                    performRemotePunch(key)
                }
            }
        }
    }

    /**
     * 触发一次远程打卡（对应 DT#打卡 / 控制端动作 punch）。
     * 复用通知监听服务内的完整打卡流程：唤起目标 App → 倒计时 → 兜底截屏 → 返回主页
     * → 经消息渠道回传结果。供 DT# 指令与 MQTT 动作命令共用。
     */
    fun performRemotePunch(keyword: String = SaveKeyValues.loadString(Constant.REMOTE_COMMAND_KEY, "打卡")) {
        // 「暂停使用」开启时不执行远程打卡
        if (KeepAliveReceiver.isPaused()) {
            LogFileManager.writeLog("暂停使用中，忽略远程打卡指令")
            return
        }
        LogFileManager.action("收到远程打卡指令（关键词=$keyword）")
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
        // 打卡前准备：屏幕当前息屏时先亮屏再打卡，保证打卡界面真实可见、可被无障碍正常操作；
        // 伪息屏蒙层显示时同样先保亮、再移除蒙层（避免蒙层释放 SCREEN_DIM 瞬间被系统休眠锁屏）。
        val keptAwakeForPunch = IdlePseudoMaskController.keepAwakeForPunchIfNeeded(this)
        val maskWasShowing = MaskOverlayHelper.isShowing()
        if (maskWasShowing) {
            LogFileManager.writeLog("远程打卡：伪息屏蒙层显示中，临时移除以确保障碍不遮挡目标App")
            MaskOverlayHelper.hide(
                this@NotificationMonitorService,
                MaskOverlayHelper.HideReason.TEMP_PUNCH
            )
        }
        try {
            val opened = openApplication {
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
                    // 文本检测命中标记（用于结果兜底判断）
                    var detectedSuccess = false
                    // 监听无障碍成功事件：命中后打标，避免重复发通知
                    val detectionJob = launch {
                        NotificationMonitorService.events.collect { event ->
                            if (event is MonitorEvent.ClockInSuccess) detectedSuccess = true
                        }
                    }
                    // 无障碍文本反馈模式：开启文本检测
                    if (resultSource == 2 && feedbackMode == 1) {
                        AutoProjectionAccessibilityService.setTextDetectionEnabled(true)
                    } else if (resultSource == 2) {
                        LogFileManager.action("远程打卡：无障碍截屏反馈（不启用文本识别）")
                    }
                    val target = SystemClock.elapsedRealtime() + timeout * 1000L
                    var hasCaptured = false
                    var captureDeferred: CompletableDeferred<String?>? = null
                    try {
                        while (isActive) {
                            if (detectedSuccess) break
                            val remaining = target - SystemClock.elapsedRealtime()
                            if (remaining <= 0) break
                            val tick = (remaining / 1000).toInt()
                            FloatingWindowController.updateTime(tick)

                            // 最后 5 秒兜底截屏（只触发一次）；文本反馈同样保留末段截屏
                            if (tick <= 5 && !hasCaptured && !detectedSuccess) {
                                if (resultSource == 1) {
                                    // 截屏模式：MediaProjection
                                    hasCaptured = true
                                    // 等悬浮窗把卡片压到低透明后再截，避免同帧仍拍到不透明遮挡
                                    delay(FloatingWindowController.SCREENSHOT_FADE_YIELD_MS)
                                    captureDeferred = CaptureImageService.requestCaptureScreen()
                                } else if (resultSource == 2 && (
                                    feedbackMode == 0
                                        || (feedbackMode == 1 && AutoProjectionAccessibilityService.canTakeScreenshot(this@NotificationMonitorService))
                                    )
                                ) {
                                    // 无障碍模式兜底截屏：
                                    // · 截屏反馈(feedbackMode=0) 直接 AccessibilityService.takeScreenshot
                                    // · 文本反馈(feedbackMode=1) 有截屏能力(Android14+)时同样兜底截屏；
                                    //   无截屏能力(版本过低)则不预截屏，交由后续 tryFallbackScreenshot 失败 → 文字提示
                                    hasCaptured = true
                                    delay(FloatingWindowController.SCREENSHOT_FADE_YIELD_MS)
                                    val a11yDeferred = AutoProjectionAccessibilityService.requestScreenshot()
                                    captureDeferred = a11yDeferred
                                        ?: CompletableDeferred<String?>().apply { complete("") }
                                }
                            }

                            delay(minOf(1000L, remaining).coerceAtLeast(1))
                        }
                        FloatingWindowController.stopFloatSession()
                        // 倒计时结束 / 文本命中提前结束：关闭文本检测；未成功时再取截图
                        AutoProjectionAccessibilityService.setTextDetectionEnabled(false)
                        detectionJob.cancel()

                        var imagePath = ""
                        if (!detectedSuccess) {
                            LogFileManager.writeLog("远程打卡倒计时结束，目标 App 仍在台，准备截图")
                            val deferred = captureDeferred
                            if (hasCaptured && deferred != null) {
                                imagePath = runCatching {
                                    withTimeout(5000) { deferred.await() ?: "" }
                                }.getOrNull() ?: ""
                            }
                            if (imagePath.isEmpty()) {
                                imagePath = runCatching {
                                    withTimeout(5000) { TaskScheduler.tryFallbackScreenshot() }
                                }.getOrNull() ?: ""
                            }
                        } else {
                            // 文本已命中：取消未完成的末段预截图等待，避免多余落盘/日志
                            captureDeferred?.cancel()
                        }

                        // 截屏临时隐藏结束后先恢复悬浮窗（贴边宠物），再回桌面/本 App，
                        // 否则安卓 15+ 缺少可见悬浮窗豁免，可能停在目标 App。
                        FloatingWindowController.restoreAfterScreenshot()

                        // 现在返回主页 / 本 App
                        LogFileManager.writeLog("远程打卡结束，返回主页")
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
                            // 真息屏打卡优化：远程指令同样在回到前台后，若「打卡前真息屏 + 伪息屏关 + 模式=息屏」，
                            // 盖不保亮黑蒙层等待系统超时灭屏（ForegroundRunningService 在 SCREEN_OFF/SCREEN_ON 时摘除）。
                            if (keptAwakeForPunch && !maskWasShowing &&
                                !AppRuntimeConfig.isForcePseudoMask() &&
                                AppRuntimeConfig.getScreenMode() == Constant.SCREEN_MODE_OFF
                            ) {
                                MaskOverlayHelper.show(this@NotificationMonitorService, keepAwake = false)
                                LogFileManager.writeLog("远程打卡结束：真息屏场景，盖不保亮黑蒙层等待系统超时")
                            }
                        }

                        // 统一发送远程打卡结果：无论何种模式都必须有反馈，避免“什么都没有”。
                        if (detectedSuccess) {
                            // 文本检测命中成功：无障碍服务已直接发过“打卡结果通知”，不重复
                            LogFileManager.action("远程打卡结果：文本识别已成功")
                        } else {
                            if (imagePath.isNotEmpty()) {
                                MessageDispatcher.sendAttachmentMessage(
                                    "远程打卡结果",
                                    StatusReporter.buildTimeoutAlertHtml(
                                        "远程打卡结果",
                                        "远程打卡已执行，截图见附件，请手动确认是否成功"
                                    ),
                                    imagePath,
                                    force = true
                                )
                                LogFileManager.writeLog("远程打卡结果：已发兜底截图 $imagePath")
                            } else {
                                MessageDispatcher.sendMessage(
                                    "远程打卡结果",
                                    StatusReporter.buildTimeoutAlertHtml(
                                        "远程打卡结果",
                                        "远程打卡已执行，但当前无可用的截屏权限（无障碍/截屏服务均未启用），请手动登录检查是否成功"
                                    ),
                                    force = true,
                                    appendMeta = false
                                )
                                LogFileManager.error("远程打卡结果：无可用截屏权限，已发文字提醒")
                            }
                        }
                    } finally {
                        // 异常路径兜底：确保悬浮窗一定收起
                        FloatingWindowController.stopFloatSession()
                        if (maskWasShowing) {
                            LogFileManager.writeLog("远程打卡结束，恢复伪息屏蒙层")
                            withContext(Dispatchers.Main) {
                                // 伪息屏关闭时不恢复保亮蒙层（打卡前蒙层可能是真息屏不保亮黑蒙层残留，
                                // 恢复保亮会导致屏幕微亮常驻不锁屏）；改为按模式盖不保亮蒙层等系统超时
                                if (AppRuntimeConfig.isForcePseudoMask()) {
                                    MaskOverlayHelper.show(this@NotificationMonitorService)
                                } else if (AppRuntimeConfig.getScreenMode() == Constant.SCREEN_MODE_OFF) {
                                    MaskOverlayHelper.show(this@NotificationMonitorService, keepAwake = false)
                                    LogFileManager.writeLog("远程打卡结束：伪息屏关+模式息屏，盖不保亮黑蒙层等待系统超时灭屏")
                                } else {
                                    LogFileManager.writeLog("远程打卡结束：伪息屏关闭，不恢复蒙层")
                                }
                            }
                        }
                        // 无论是否恢复蒙层，只要打卡前亮过屏就释放打卡保活，让屏幕回到系统自然管理
                        if (keptAwakeForPunch) {
                            IdlePseudoMaskController.releaseKeepAwakeForPunch(this@NotificationMonitorService)
                        }
                    }
                }
            }
            if (!opened) {
                LogFileManager.error("远程打卡：目标 App 未能启动，恢复蒙层/保活")
                MessageDispatcher.sendMessage(
                    "远程打卡通知",
                    StatusReporter.buildTimeoutAlertHtml("远程打卡失败", "目标应用未能启动，请检查是否已安装及后台弹出权限"),
                    force = true,
                    appendMeta = false
                )
                if (maskWasShowing || keptAwakeForPunch) {
                    Handler(Looper.getMainLooper()).post {
                        if (maskWasShowing) {
                            // 伪息屏关时不恢复保亮蒙层（防屏幕微亮常驻不锁屏）；模式=息屏则盖不保亮黑蒙层等系统超时
                            if (AppRuntimeConfig.isForcePseudoMask()) {
                                MaskOverlayHelper.show(this@NotificationMonitorService)
                            } else if (AppRuntimeConfig.getScreenMode() == Constant.SCREEN_MODE_OFF) {
                                MaskOverlayHelper.show(this@NotificationMonitorService, keepAwake = false)
                                LogFileManager.writeLog("伪息屏关+模式息屏：盖不保亮黑蒙层等待系统超时灭屏")
                            }
                        }
                        IdlePseudoMaskController.releaseKeepAwakeForPunch(this@NotificationMonitorService)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(kTag, "远程打卡启动失败", e)
            LogFileManager.error("远程打卡启动失败: ${e.message}")
            MessageDispatcher.sendMessage(
                "远程打卡通知",
                StatusReporter.buildTimeoutAlertHtml("远程打卡失败", e.message ?: "未知错误"),
                force = true,
                appendMeta = false
            )
            // 异常时也要恢复蒙层 / 释放打卡保活
            if (maskWasShowing || keptAwakeForPunch) {
                Handler(Looper.getMainLooper()).post {
                    if (maskWasShowing) MaskOverlayHelper.show(this@NotificationMonitorService)
                    IdlePseudoMaskController.releaseKeepAwakeForPunch(this@NotificationMonitorService)
                }
            }
        }
    }

    /** 导出当天考勤记录（对应 DT#考勤记录 / 控制端动作 attendance），经消息渠道回传 */
    fun performAttendanceExport() {
        LogFileManager.action("收到考勤记录指令")
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

    /** 截取目标应用画面（对应 DT#截屏 / 控制端动作 screenshot），经消息渠道回传 */
    fun performScreenshot() {
        if (KeepAliveReceiver.isPaused()) {
            LogFileManager.writeLog("暂停使用中，忽略截屏指令")
            return
        }
        LogFileManager.action("收到截屏指令")
        val resultSource = SaveKeyValues.loadInt(
            Constant.RESULT_SOURCE_KEY, Constant.DEFAULT_INDEX
        )
        val mediaProjectionReady = ProjectionSession.isStateActive()
        val a11yScreenshotReady = AutoProjectionAccessibilityService.canTakeScreenshot(this)
        // 截屏命令：优先 MediaProjection；无障碍模式即使文本反馈，只要有无障碍截屏能力也兜底截屏
        val canProceed = when (resultSource) {
            0 -> false                                            // 通知监听模式不支持截屏
            1 -> mediaProjectionReady || a11yScreenshotReady        // 截屏服务模式可走无障碍兜底
            2 -> a11yScreenshotReady                                // 无障碍模式：有截屏能力即兜底
            else -> false
        }
        if (!canProceed) {
            val failMsg = when (resultSource) {
                0 -> "当前为通知监听模式，不支持截屏"
                2 -> "无障碍截屏不可用（需 Android 14+ 且已开启无障碍服务），将以文本反馈为主"
                else -> "截屏服务未开启且无障碍截屏不可用，请检查设置"
            }
            MessageDispatcher.sendMessage(
                "截屏状态通知",
                StatusReporter.buildScreenshotResultHtml(false, failMsg),
                appendMeta = false
            )
            return
        }
        val keptAwakeForShot = IdlePseudoMaskController.keepAwakeForPunchIfNeeded(this)
        val maskWasShowing = MaskOverlayHelper.isShowing()
        if (maskWasShowing) {
            MaskOverlayHelper.hide(this, MaskOverlayHelper.HideReason.TEMP_PUNCH)
        }
        // 供 MainActivity 截屏会话 finally 恢复
        pendingScreenshotMaskRestore = maskWasShowing
        pendingScreenshotKeepAwakeRelease = keptAwakeForShot
        val opened = openApplication { emitMonitorEvent(MonitorEvent.AppOpenedForScreenshot) }
        if (!opened) {
            pendingScreenshotMaskRestore = false
            pendingScreenshotKeepAwakeRelease = false
            if (maskWasShowing) MaskOverlayHelper.show(this)
            if (keptAwakeForShot) IdlePseudoMaskController.releaseKeepAwakeForPunch(this)
            MessageDispatcher.sendMessage(
                "截屏状态通知",
                StatusReporter.buildScreenshotResultHtml(false, "目标应用未能启动"),
                appendMeta = false
            )
        }
    }

    /**
     * 安全启动协程：scope 已取消时直接打日志告警，避免静默丢弃指令。
     * block 以 CoroutineScope 为接收者，内部可直接使用 isActive / delay 等。
     */
    private fun launchOrWarn(tag: String, block: suspend CoroutineScope.() -> Unit) {
        if (!serviceScope.isActive) {
            Log.w(kTag, "serviceScope 已取消，无法处理指令: $tag")
            LogFileManager.error("serviceScope 已取消，$tag 指令丢弃")
            return
        }
        serviceScope.launch {
            try {
                block()
            } catch (e: Exception) {
                Log.e(kTag, "$tag 处理异常", e)
                LogFileManager.error("$tag 处理异常: ${e.message}")
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
            LogFileManager.error("拉起主界面失败（蒙层仍可能已通过悬浮窗显示）: ${e.message}")
        }
    }

    /**
     * 当有通知移除时会回调
     */
    override fun onNotificationRemoved(sbn: StatusBarNotification) {}

    override fun onListenerDisconnected() {
        listenerConnected = false
        emitListenerState(false)
        LogFileManager.action("通知监听服务已断开，尝试重新绑定")
        // 主动请求系统重新绑定监听服务
        requestRebind(ComponentName(this, NotificationMonitorService::class.java))
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
        serviceScope.cancel()
    }
}