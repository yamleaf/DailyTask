package com.pengxh.daily.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import com.pengxh.daily.app.BuildConfig
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.IdlePseudoMaskController
import com.pengxh.daily.app.utils.LogFileManager
import com.pengxh.daily.app.utils.MaskOverlayHelper
import com.pengxh.daily.app.utils.MessageDispatcher
import com.pengxh.daily.app.utils.MonitorEvent
import com.pengxh.daily.app.utils.StatusReporter
import com.pengxh.kt.lite.extensions.createImageFileDir
import com.pengxh.kt.lite.extensions.saveImage
import com.pengxh.kt.lite.utils.SaveKeyValues
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Date

import com.pengxh.daily.app.extensions.acquireWakeLock
import com.pengxh.daily.app.extensions.format
import java.util.concurrent.Executors

/**
 * 无障碍服务（方案1：AccessibilityService.takeScreenshot + 文本读取）
 *
 * 完全绕过 MediaProjection：
 * - 通过 AccessibilityService.takeScreenshot() API 截屏
 * - 通过 rootInActiveWindow 读取目标应用界面文本，自动判断打卡是否成功
 * - 无授权弹窗、无屏幕共享通知、不与远控软件冲突
 * - 需要用户在系统设置中开启无障碍服务
 */
class AutoProjectionAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ScreenshotA11y"
        /** 文本识别节流间隔：无障碍事件极密集，限制每 3 秒最多扫描一次 */
        private const val TEXT_SCAN_INTERVAL_MS = 3000L

        /** 打卡 App 进入前台后、开始判定前的等待时长：飞书极速打卡自触发 + 成功消息渲染到
         *  列表需要时间。等待期间只扫描不判定，避免列表尚未刷新时扫到历史消息抢答。 */
        private const val PUNCH_FOREGROUND_WAIT_MS = 5_000L

        /** 打卡成功消息时间戳与「当前扫描时刻」的最大允许偏差：极速打卡即时产生，其时间戳
         *  必落在当前时刻附近；历史消息相差几小时，必然超出。±2 分钟已覆盖「分钟级时间戳
         *  向下取整 + 渲染/扫描延迟」的极端叠加，同时远小于真实历史的间隔。 */
        private const val PUNCH_TIME_WINDOW_MS = 120_000L

        @Volatile
        private var instance: AutoProjectionAccessibilityService? = null

        /**
         * 检查无障碍服务是否已启用
         */
        fun isEnabled(context: Context): Boolean {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            val enabledServices =
                am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
            val target =
                ComponentName(context.packageName, AutoProjectionAccessibilityService::class.java.name)
            return enabledServices.any {
                ComponentName(it.resolveInfo.serviceInfo.packageName, it.resolveInfo.serviceInfo.name) == target
            }
        }

        /**
         * 是否具备无障碍截屏能力：服务已启用，且系统版本满足 AccessibilityService.takeScreenshot 要求（Android 14+）。
         * 低于 Android 14 时该 API 不可用，即使服务已启用也无法截屏，故此处一并判断。
         */
        fun canTakeScreenshot(context: Context): Boolean {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                    && isEnabled(context)
        }

        /**
         * 请求截屏
         * @return CompletableDeferred<String?> 截图文件路径；null 表示服务未连接，"" 表示截屏失败
         */
        fun requestScreenshot(): CompletableDeferred<String?>? {
            val service = instance ?: return null
            val deferred = CompletableDeferred<String?>()
            service.doScreenshot(deferred)
            return deferred
        }

        /**
         * 设置文本检测开关（仅在无障碍结果模式下生效）
         * 建议在打卡任务窗口期开启，任务结束后关闭，避免误触发。
         */
        fun setTextDetectionEnabled(enabled: Boolean) {
            instance?.apply {
            textDetectionActive = enabled
            textDetected = false
            // 目标App进入前台时刻：首次扫描到目标App时记录（见 scanCurrentWindow），
            // 此后 PUNCH_FOREGROUND_WAIT_MS 内不判定，等飞书极速打卡自触发 + 成功消息渲染完成。
            foregroundEnterMillis = 0L
            sawPunchButton = false
            lastTextScanMillis = 0L
            if (enabled) startActiveScan() else stopActiveScan()
            Log.d(TAG, "文本检测状态: active=$enabled")
            LogFileManager.writeLog("无障碍文本检测: active=$enabled")
            }
        }

        /**
         * 当前是否处于文本检测窗口期
         */
        fun isTextDetectionActive(): Boolean = instance?.textDetectionActive == true
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var activeWakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var textDetectionActive = false

    @Volatile
    private var textDetected = false

    /** 目标打卡App进入前台的时刻：首次扫描到目标App（或捕获其窗口切换事件）时记录。
     *  此后 PUNCH_FOREGROUND_WAIT_MS 内不判定打卡结果，等飞书极速打卡自触发 + 成功消息渲染完成，
     *  避免在列表尚未刷新时把历史打卡消息误判为本次成功。 */
    @Volatile
    private var foregroundEnterMillis = 0L

    /** 本次监听会话中是否曾在界面上看到“上班打卡/下班打卡/外出打卡”按钮
     *  （用于“已打卡”状态变化的辅助判定，证明本次确实有打卡动作发生）。 */
    @Volatile
    private var sawPunchButton = false

    /** 记录上次前台包名，用于检测前台任务切换 */
    @Volatile
    private var lastForegroundPackage: String? = null

    /** 上次文本扫描时间戳（节流用），避免无障碍事件高频触发反复扫描同一界面 */
    @Volatile
    private var lastTextScanMillis = 0L

    /** 主动轮询扫描协程：setTextDetectionEnabled(true) 期间每 TEXT_SCAN_INTERVAL_MS 扫描一次，
     *  保证屏幕关闭/界面静止（无障碍事件稀少）时仍能按频率识别打卡结果。 */
    @Volatile
    private var scanJob: Job? = null

    /** 打卡成功关键词（仅真正的成功提示，不含“上班打卡/下班打卡”等按钮文字） */
    private val successKeywords = listOf(
        "打卡成功",
        "已打卡",
        "打卡完成",
        "考勤成功"
    )

    /** 打卡按钮文字（本次会话见到过即说明确有打卡动作发生） */
    private val punchButtonMarkers = listOf("上班打卡", "下班打卡", "外出打卡")

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "无障碍服务已连接")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        textDetectionActive = false
        textDetected = false
        // 收尾：取消轮询协程、关闭后台线程池，避免服务断开后协程/线程泄漏
        stopActiveScan()
        serviceScope.coroutineContext[Job]?.cancel()
        executor.shutdown()
        // 兜底：若截图过程中服务断开导致蒙层停留隐藏态，强制恢复伪息屏
        MaskOverlayHelper.restoreAfterScreenshot(this)
        Log.d(TAG, "无障碍服务已断开")
        return super.onUnbind(intent)
    }

    /**
     * 执行截屏：先尝试点亮屏幕，再调用 takeScreenshot() → 回调中转换 Bitmap → 保存文件
     */
    private fun doScreenshot(deferred: CompletableDeferred<String?>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Log.w(TAG, "takeScreenshot(displayId) 需要 Android 14+，当前 API=${Build.VERSION.SDK_INT}")
            deferred.complete(null)
            return
        }

        Log.d(TAG, "================== 开始无障碍截屏 ==================")

        // 记录屏幕状态：熄屏或锁屏时 takeScreenshot 只能截到黑屏/AOD/锁屏界面
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val isScreenOn = powerManager?.isInteractive == true
        val isKeyguardLocked = keyguardManager?.isKeyguardLocked == true
        val maskShowing = MaskOverlayHelper.isShowing()
        Log.d(TAG, "截图前状态: isScreenOn=$isScreenOn, isKeyguardLocked=$isKeyguardLocked, maskShowing=$maskShowing")

        // 屏幕关闭时先 WakeLock 强制点亮，否则 takeScreenshot 会截到息屏/AOD 黑屏
        if (!isScreenOn && powerManager != null) {
            try {
                val wakeLock = acquireWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
                    "DailyTask:ScreenshotWakeLock",
                    10_000L,
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE
                )
                activeWakeLock = wakeLock
            Log.d(TAG, "屏幕关闭，已请求 WakeLock 点亮")
            } catch (e: Exception) {
                Log.e(TAG, "WakeLock 点亮屏幕失败: ${e.message}", e)
                LogFileManager.error("WakeLock 点亮屏幕失败: ${e.message}")
            }
        }

        // 伪息屏蒙层在显示时，临时移除，否则 takeScreenshot 截到的是黑屏蒙层而非应用界面
        if (maskShowing) {
            MaskOverlayHelper.hideForScreenshot(this)
        }

        // 等待屏幕唤醒 + 蒙层移除 + 应用重绘，再执行 takeScreenshot
        val wakeDelayMs = when {
            !isScreenOn -> 800L   // 屏幕唤醒 + 蒙层移除 + 重绘
            maskShowing -> 400L   // 蒙层移除 + 重绘
            else -> 200L          // 已亮屏无蒙层，稍等即可
        }
        mainHandler.postDelayed({
            takeScreenshot(0, executor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    var hardwareBuffer: android.hardware.HardwareBuffer? = null
                    try {
                        hardwareBuffer = result.hardwareBuffer
                        val colorSpace = result.colorSpace

                        // HardwareBuffer → Bitmap
                        val wrappedBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                        if (wrappedBitmap == null) {
                            Log.e(TAG, "wrapHardwareBuffer 返回 null")
                            deferred.complete(null)
                            return
                        }

                        // 复制为软件 Bitmap 以便保存为 PNG
                        val softwareBitmap = wrappedBitmap.copy(Bitmap.Config.ARGB_8888, false)
                        wrappedBitmap.recycle()

                        if (softwareBitmap == null) {
                            Log.e(TAG, "复制软件位图失败")
                            deferred.complete(null)
                            return
                        }

                        val imagePath = "${createImageFileDir()}/${Date().format("yyyyMMdd_HHmmss")}.png"
                        // 保存全屏截图（不再裁剪中间区域）
                        softwareBitmap.saveImage(imagePath)
                        softwareBitmap.recycle()

                        LogFileManager.action("无障碍截屏成功: $imagePath")
                        Log.d(TAG, "截屏成功: $imagePath")
                        deferred.complete(imagePath)
                    } catch (e: Exception) {
                        Log.e(TAG, "截屏处理失败: ${e.message}", e)
                        deferred.complete(null)
                    } finally {
                        hardwareBuffer?.close()
                        if (maskShowing) {
                            MaskOverlayHelper.restoreAfterScreenshot(this@AutoProjectionAccessibilityService)
                        }
                        releaseScreenshotWakeLock()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    Log.e(TAG, "takeScreenshot 失败, errorCode=$errorCode")
                    deferred.complete(null)
                    if (maskShowing) {
                        MaskOverlayHelper.restoreAfterScreenshot(this@AutoProjectionAccessibilityService)
                    }
                    releaseScreenshotWakeLock()
                }
            })
        }, wakeDelayMs)
    }

    private fun releaseScreenshotWakeLock() {
        val wakeLock = activeWakeLock ?: return
        activeWakeLock = null
        try {
            if (wakeLock.isHeld) {
                wakeLock.release()
                Log.d(TAG, "截图 WakeLock 已释放")
            }
        } catch (e: Exception) {
            Log.w(TAG, "释放截图 WakeLock 失败: ${e.message}")
        }
    }

    // 文本读取：监听无障碍事件，自动判断打卡结果

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 前台任务切换时重置伪息屏倒计时（不依赖文本检测开关）
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            if (pkg != null && pkg != packageName) {
                // 目标打卡App进入前台：重置5秒等待计时起点（飞书极速打卡自触发+消息渲染需要时间）
                if (textDetectionActive && pkg == Constant.getTargetApp() && pkg != lastForegroundPackage) {
                    foregroundEnterMillis = System.currentTimeMillis()
                    LogFileManager.writeLog("无障碍目标App进入前台，开始5秒等待：pkg=$pkg")
                }
                if (pkg != lastForegroundPackage) {
                    lastForegroundPackage = pkg
                    IdlePseudoMaskController.onForegroundTaskChanged()
                }
            }
        }

        // 仅对窗口/内容变化做即时扫描；频率由 scanCurrentWindow 内部节流控制，
        // 与主动轮询协程共享 lastTextScanMillis，保证每 TEXT_SCAN_INTERVAL_MS 最多一次。
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            && event?.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }

        if (textDetectionActive && !textDetected) {
            scanCurrentWindow()
        }
    }

    /**
     * 扫描当前前台窗口文本并尝试识别打卡结果。
     * 由 onAccessibilityEvent（事件即时触发）与主动轮询协程（屏幕静止兜底）共同调用，
     * 内部用 [lastTextScanMillis] 节流，保证每 [TEXT_SCAN_INTERVAL_MS] 最多扫描一次。
     */
    private fun scanCurrentWindow() {
        if (!textDetectionActive || textDetected) return
        val root = rootInActiveWindow ?: return
        try {
            val packageName = root.packageName?.toString() ?: return
            val targetApp = Constant.getTargetApp()
            if (packageName != targetApp) {
                // 非目标应用，忽略
                return
            }

            // 文本识别节流：与主动轮询共享同一时间戳，保证每 3 秒最多扫描一次，
            // 既避免飞书高频事件重复扫描，也保证屏幕静止时主动轮询按频率兜底。
            val now = System.currentTimeMillis()
            if (now - lastTextScanMillis < TEXT_SCAN_INTERVAL_MS) {
                return
            }
            lastTextScanMillis = now

            // 定向查找成功关键词/打卡按钮，避免对飞书等复杂页面做全树文本拼接
            val directedText = collectDirectedText(root)
                .replace("\n", " ").replace("\\s+".toRegex(), " ").trim()
            if (directedText.isBlank()) {
                // 定向查找未命中任何关键词（含打卡按钮），跳过整树遍历
                return
            }
            val text = directedText

            // 目标App进入前台等待：首次观察到目标App时记录起点，PUNCH_FOREGROUND_WAIT_MS 内只扫描不判定，
            // 避免飞书极速打卡尚未自触发、成功消息尚未渲染时扫到历史消息抢答。
            if (foregroundEnterMillis == 0L) {
                foregroundEnterMillis = now
                LogFileManager.writeLog("无障碍首次观察到目标App，开始${PUNCH_FOREGROUND_WAIT_MS}ms等待")
            }
            if (now - foregroundEnterMillis < PUNCH_FOREGROUND_WAIT_MS) {
                return
            }

            LogFileManager.writeLog("无障碍读取文本：package=$packageName, text=${text.take(120)}")

            // 记录本次会话是否见到打卡按钮（用于“已打卡”状态变化判定）
            if (punchButtonMarkers.any { text.contains(it) }) {
                sawPunchButton = true
            }

            // 企业微信自动打卡消息：如“17:40 下班自动打卡·正常”。
            // 必须同时含“自动打卡”和“正常”，避免把“自动打卡·异常”误判为成功。
            val isWeWorkAuto = text.contains("自动打卡") && text.contains("正常")

            // 解析本次命中的成功关键词及位置：优先企业微信组合，其次通用成功关键词
            val (matchedKeyword, keywordPos) = if (isWeWorkAuto) {
                "自动打卡" to text.indexOf("自动打卡")
            } else {
                val keywordIndex = successKeywords.indexOfFirst { text.contains(it) }
                if (keywordIndex < 0) return
                successKeywords[keywordIndex] to text.indexOf(successKeywords[keywordIndex])
            }

            // 时间戳优先取自命中节点的「兄弟节点」（飞书把 08:38 与「上班极速打卡成功」
            // 放在同一条消息容器内平级），退而求其次解析关键词内嵌时间（企业微信）。
            val (textMillis, groupIsToday) = parseSuccessTimeAndGroup(text, keywordPos)
            val siblingMillis = findSiblingTimestamp(root, matchedKeyword)
            val candidateMillis = siblingMillis ?: textMillis

            // 判定：打卡时间落在当前时间 ±PUNCH_TIME_WINDOW_MS 内即视为本次打卡成功。
            // 极速打卡即时产生，其时间戳必接近“现在”；历史消息相差几小时，必然落在窗口外。
            // groupIsToday!=false 为二次保险（显式“昨天/前天”分组直接排除）。
            val accepted = if (candidateMillis != null) {
                val diff = kotlin.math.abs(now - candidateMillis)
                diff <= PUNCH_TIME_WINDOW_MS && (groupIsToday != false)
            } else {
                // 无时间戳的成功词：仅“已打卡”状态或企业微信“自动打卡·正常”，
                // 且本次会话确实见过打卡按钮（证明是本次打卡动作导致的状态变化）。
                (matchedKeyword == "已打卡" || isWeWorkAuto) && sawPunchButton
            }

            if (accepted) {
                textDetected = true
                val diffMs = candidateMillis?.let { kotlin.math.abs(now - it) } ?: -1L
                LogFileManager.action("无障碍检测到打卡成功（keyword=$matchedKeyword，candidateMillis=${candidateMillis ?: now}，diff=${diffMs}ms，groupIsToday=$groupIsToday）")
                val snippet = extractSnippet(text, matchedKeyword)
                handleTextDetected(snippet, matchedKeyword, packageName, candidateMillis)
            } else {
                // 命中成功词但被拒（历史消息/时间不在 ±窗口内）：正常路径，仅记一行便于核对。
                LogFileManager.writeLog("无障碍忽略成功词（历史/非本次）：keyword=$matchedKeyword，candidateMillis=$candidateMillis，diff=${candidateMillis?.let { kotlin.math.abs(now - it) } ?: -1}ms，groupIsToday=$groupIsToday")
            }
        } finally {
            root.recycle()
        }
    }

    /**
     * 主动轮询扫描：检测开关开启期间每 [TEXT_SCAN_INTERVAL_MS] 扫描一次当前窗口。
     * 兜底屏幕关闭/界面静止导致无障碍事件稀少、被动节流几乎不扫描的问题，
     * 保证“每 3 秒识别一次”既能限频（不冗余）又能兜底（事件少时也扫）。
     */
    private fun startActiveScan() {
        stopActiveScan()
        scanJob = serviceScope.launch {
            while (isActive && textDetectionActive && !textDetected) {
                scanCurrentWindow()
                delay(TEXT_SCAN_INTERVAL_MS)
            }
        }
    }

    private fun stopActiveScan() {
        scanJob?.cancel()
        scanJob = null
    }

    /**
     * 定向文本收集：仅对成功关键词/打卡按钮标记做 findAccessibilityNodeInfosByText 查找，
     * 并收集命中节点及其祖先文本作为上下文（供时间/“今天|昨天”分组解析）。
     * 避免对复杂页面做全树递归拼接，显著降低开销（P2-7）。
     */
    private fun collectDirectedText(root: AccessibilityNodeInfo): String {
        val keywords = successKeywords + punchButtonMarkers + listOf("自动打卡")
        val sb = StringBuilder()
        for (kw in keywords) {
            val nodes = runCatching { root.findAccessibilityNodeInfosByText(kw) }.getOrNull() ?: continue
            for (n in nodes) {
                if (BuildConfig.DEBUG) {
                    logNodeTree("定向扫描命中[$kw]", n)
                }
                n.text?.let { sb.append(it).append(" ") }
                n.contentDescription?.let { sb.append(it).append(" ") }
                // 向上收集祖先文本，保留时间/分组上下文
                var p = n.parent
                repeat(3) {
                    p?.text?.let { sb.append(it).append(" ") }
                    p?.contentDescription?.let { sb.append(it).append(" ") }
                    p = p?.parent
                }
            }
        }
        return sb.toString()
    }

    /**
     * DEBUG only：将节点及其祖先信息写入 app_run 日志，便于根据其它 App 的
     * 无障碍节点结构适配打卡时间/成功文本的提取逻辑。Release 构建不执行任何写入。
     */
    private fun logNodeTree(tag: String, node: AccessibilityNodeInfo) {
        if (!BuildConfig.DEBUG) return
        val sb = StringBuilder()
        sb.append("[$tag]")
        var depth = 0
        var current: AccessibilityNodeInfo? = node
        while (current != null && depth < 4) {
            val indent = "  ".repeat(depth)
            val bounds = android.graphics.Rect().apply { current.getBoundsInScreen(this) }
            sb.append("\n${indent}depth=$depth class=${current.className}")
            sb.append(" text='${current.text?.toString()?.replace("'", "\\'") ?: ""}'")
            sb.append(" desc='${current.contentDescription?.toString()?.replace("'", "\\'") ?: ""}'")
            sb.append(" id=${current.viewIdResourceName ?: "null"}")
            sb.append(" bounds=${bounds}")
            sb.append(" clickable=${current.isClickable} focusable=${current.isFocusable}")
            current = runCatching { current.parent }.getOrNull()
            depth++
        }
        val line = sb.toString()
        Log.d(TAG, line)
    }

    /**
     * 检测到打卡成功文本后的处理：
     * - 文本反馈：直接发送文本内容
     * - 截屏反馈：再截一张图并发送
     * 最后通知 TaskScheduler 取消超时等待。
     */
    private fun handleTextDetected(snippet: String, keyword: String, packageName: String, clockInTime: Long? = null) {
        val resultSource = SaveKeyValues.loadInt(Constant.RESULT_SOURCE_KEY, Constant.DEFAULT_INDEX)
        if (resultSource != 2) return

        val feedbackMode = SaveKeyValues.loadInt(Constant.ACCESSIBILITY_FEEDBACK_MODE_KEY, 0)
        val messageTitle = SaveKeyValues.loadString(Constant.MESSAGE_TITLE_KEY, "打卡结果通知")
        val appName = Constant.getAppName(packageName)

        if (feedbackMode == 1) {
            // 文本反馈：直接发文本
            MessageDispatcher.sendMessage(
                messageTitle,
                StatusReporter.buildClockInTextResultHtml(snippet, keyword, appName, clockInTime = clockInTime),
                appendMeta = false
            )
            NotificationMonitorService.emitMonitorEvent(
                MonitorEvent.ClockInSuccess(
                    keyword = keyword,
                    appName = appName,
                    snippet = snippet
                )
            )
        } else {
            // 截屏反馈：先截屏再发邮件
            serviceScope.launch {
                val deferred = CompletableDeferred<String?>()
                doScreenshot(deferred)
                val imagePath = try { deferred.await() } catch (_: Exception) { null }
                if (!imagePath.isNullOrEmpty()) {
                    MessageDispatcher.sendAttachmentMessage(
                        messageTitle,
                        StatusReporter.buildClockInTextResultHtml(snippet, keyword, appName, clockInTime = clockInTime),
                        imagePath
                    )
                } else {
                    MessageDispatcher.sendMessage(
                        messageTitle,
                        StatusReporter.buildClockInTextResultHtml(snippet, keyword, appName, "截屏失败，仅有文本结果", clockInTime),
                        appendMeta = false
                    )
                }
                NotificationMonitorService.emitMonitorEvent(
                    MonitorEvent.ClockInSuccess(
                        keyword = keyword,
                        appName = appName,
                        snippet = snippet
                    )
                )
            }
        }
    }

    /**
     * 提取关键词附近的简短上下文，避免邮件中展示整屏无关文本
     */
    private fun extractSnippet(text: String, keyword: String, radius: Int = 35): String {
        val index = text.indexOf(keyword)
        if (index == -1) return text.take(radius * 2).trim()
        val start = (index - radius).coerceAtLeast(0)
        val end = (index + keyword.length + radius).coerceAtMost(text.length)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < text.length) "…" else ""
        return "$prefix${text.substring(start, end).trim()}$suffix"
    }

    /**
     * 成功记录附近的时间戳正则（HH:mm），如“18:11 下班极速打卡成功”
     */
    private val timePattern = Regex("(\\d{1,2}):(\\d{2})")

    /**
     * 飞书消息列表的日期分组标记：今天 / 昨天 / 前天 / M月D日
     */
    private val dateGroupPattern = Regex("(今天|昨天|前天)|(\\d{1,2})月(\\d{1,2})[日号]?")

    /**
     * 解析成功关键词附近的时间戳与所属日期分组。
     *
     * @return Pair(候选时间戳毫秒(今天基准), 是否属于“今天”分组)
     *         groupIsToday: true=今天 / false=昨天·前天·过去日期 / null=无日期分组标记
     *
     * 关键：历史记录要么时间远离“现在”（被新鲜度判定拒绝），
     * 要么被飞书显式归入“昨天/过去日期”分组（被 groupIsToday=false 拒绝），
     * 由此从根上区分“本次成功”与“历史成功”，无需依赖页面加载时机。
     */
    private fun parseSuccessTimeAndGroup(text: String, keywordPos: Int): Pair<Long?, Boolean?> {
        // 1) 时间：取关键词前后 40 字符内最近的一个 HH:mm
        val start = (keywordPos - 40).coerceAtLeast(0)
        val end = (keywordPos + 40).coerceAtMost(text.length)
        val window = text.substring(start, end)
        val keywordOffsetInWindow = keywordPos - start

        // 窗口内可能有多个时间（状态栏、其他会话、同一条消息等），
        // 取与关键词位置最近的一个作为该消息的时间，避免误把远处的时间当成本次打卡时间。
        val timeMatches = timePattern.findAll(window).toList()
        val candidateMillis = if (timeMatches.isNotEmpty()) {
            try {
                val match = timeMatches.minByOrNull {
                    Math.abs((it.range.first + it.range.last) / 2 - keywordOffsetInWindow)
                } ?: timeMatches.first()
                val (h, m) = match.destructured
                val cal = Calendar.getInstance()
                cal.set(Calendar.HOUR_OF_DAY, h.toInt())
                cal.set(Calendar.MINUTE, m.toInt())
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                cal.timeInMillis
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }

        // 2) 日期分组：取关键词之前最近的日期分组标记
        val before = text.substring(0, keywordPos)
        val groupMatch = dateGroupPattern.findAll(before).lastOrNull()
        val groupIsToday = when {
            groupMatch == null -> null
            groupMatch.groupValues[1].isNotEmpty() -> when (groupMatch.groupValues[1]) {
                "今天" -> true
                else -> false // 昨天 / 前天
            }
            groupMatch.groupValues[2].isNotEmpty() -> {
                // 显式 M月D日：与今天比较
                val gm = groupMatch.groupValues[2].toInt()
                val gd = groupMatch.groupValues[3].toInt()
                val now = Calendar.getInstance()
                val isToday = now.get(Calendar.MONTH) + 1 == gm && now.get(Calendar.DAY_OF_MONTH) == gd
                // 命中今天返回 true；不匹配今天的“M月D日”可能是推荐消息、历史卡片等无关内容的日期
                // （如“2025年11月13日”），不能据此否定本次打卡——历史上因此误判 groupIsToday=false 导致漏检。
                // 故不匹配今天时返回 null，交由“新鲜度”判定（本次监听窗口附近的时间戳即视为本次打卡）。
                if (isToday) true else null
            }
            else -> null
        }
        return candidateMillis to groupIsToday
    }

    /**
     * 从命中成功词的节点出发，仅扫描其“消息条目容器”（直接父节点的子节点），
     * 找出与命中节点平级的时间戳 TextView（如飞书“08:38”与“上班极速打卡成功”是兄弟节点，
     * dump 调试已确认该布局）。绝不扫描整列表，保持定向查找的性能优势。
     * 返回：命中消息的时间戳毫秒（今天基准），找不到返回 null。
     */
    private fun findSiblingTimestamp(root: AccessibilityNodeInfo, keyword: String): Long? {
        val nodes = runCatching { root.findAccessibilityNodeInfosByText(keyword) }.getOrNull() ?: return null
        for (n in nodes) {
            scanSiblingTime(n)?.let { return it }
        }
        return null
    }

    /**
     * 扫描节点直接父节点的所有子节点，找到与命中节点平级、文本匹配 HH:mm 的时间戳。
     * 仅一层（父节点的子节点），范围限定在单条消息容器内，性能可控、不波及会话列表。
     */
    private fun scanSiblingTime(node: AccessibilityNodeInfo): Long? {
        val parent = node.parent ?: return null
        for (i in 0 until parent.childCount) {
            val child = runCatching { parent.getChild(i) }.getOrNull() ?: continue
            if (child == node) continue
            val t = child.text?.toString() ?: continue
            val match = timePattern.find(t) ?: continue
            if (BuildConfig.DEBUG) {
                logNodeTree("兄弟节点时间戳[$t]", child)
            }
            val (h, m) = match.destructured
            runCatching {
                Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, h.toInt())
                    set(Calendar.MINUTE, m.toInt())
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
            }.getOrNull()?.let { return it }
        }
        return null
    }

    override fun onInterrupt() {
        Log.w(TAG, "无障碍服务被中断")
    }
}
