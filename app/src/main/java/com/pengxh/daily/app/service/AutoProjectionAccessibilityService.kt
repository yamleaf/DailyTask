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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
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
                // 记录“开始监听”的时刻：极速打卡会在打开 App 后短时间内自动完成，
                // 因此只有时间接近此刻的打卡成功记录才算本次结果（见 onAccessibilityEvent）。
                armTimeMillis = System.currentTimeMillis()
                sawPunchButton = false
                lastTextScanMillis = 0L
                Log.d(TAG, "文本检测状态: active=$enabled, armTime=$armTimeMillis")
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
    private val dateTimeFormat by lazy { SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA) }

    @Volatile
    private var activeWakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var textDetectionActive = false

    @Volatile
    private var textDetected = false

    /** 开始监听打卡结果的时刻（setTextDetectionEnabled(true) 时记录）。
     *  极速打卡在打开 App 后短时间内自动完成，故只有时间接近此刻的成功记录才算本次结果。 */
    @Volatile
    private var armTimeMillis = 0L

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
        LogFileManager.writeLog("截图前状态: isScreenOn=$isScreenOn, isKeyguardLocked=$isKeyguardLocked, maskShowing=$maskShowing")

        // 屏幕关闭时先 WakeLock 强制点亮，否则 takeScreenshot 会截到息屏/AOD 黑屏
        if (!isScreenOn && powerManager != null) {
            try {
                @Suppress("DEPRECATION")
                val wakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                            PowerManager.ACQUIRE_CAUSES_WAKEUP or
                            PowerManager.ON_AFTER_RELEASE,
                    "DailyTask:ScreenshotWakeLock"
                )
                wakeLock.acquire(10_000L)
                activeWakeLock = wakeLock
                Log.d(TAG, "屏幕关闭，已请求 WakeLock 点亮")
                LogFileManager.writeLog("屏幕关闭，已请求 WakeLock 点亮")
            } catch (e: Exception) {
                Log.e(TAG, "WakeLock 点亮屏幕失败: ${e.message}", e)
                LogFileManager.writeLog("WakeLock 点亮屏幕失败: ${e.message}")
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

                        // 保存全屏截图（不再裁剪中间区域）
                        val imagePath = "${createImageFileDir()}/${dateTimeFormat.format(Date())}.png"
                        softwareBitmap.saveImage(imagePath)
                        softwareBitmap.recycle()

                        LogFileManager.writeLog("无障碍截屏成功: $imagePath")
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

    // ============================================================
    // 文本读取：监听无障碍事件，自动判断打卡结果
    // ============================================================

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 前台任务切换时重置伪息屏倒计时（不依赖文本检测开关）
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            if (pkg != null && pkg != packageName && pkg != lastForegroundPackage) {
                lastForegroundPackage = pkg
                IdlePseudoMaskController.onForegroundTaskChanged()
            }
        }

        if (!textDetectionActive || textDetected) return
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            && event?.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }

        val root = rootInActiveWindow ?: return
        try {
            val packageName = root.packageName?.toString() ?: return
            val targetApp = Constant.getTargetApp()
            if (packageName != targetApp) {
                // 非目标应用，忽略
                return
            }

            // 文本识别节流：无障碍事件（尤其 TYPE_WINDOW_CONTENT_CHANGED）在飞书中极密集，
            // 原逻辑每秒触发数十次重复扫描。用户要求每 3 秒识别一次即可，故在此限流。
            val now = System.currentTimeMillis()
            if (now - lastTextScanMillis < TEXT_SCAN_INTERVAL_MS) {
                return
            }
            lastTextScanMillis = now

            val text = collectNodeText(root).replace("\n", " ").replace("\\s+".toRegex(), " ").trim()
            if (text.isBlank()) return

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

            // 可靠性核心：只接受“时间接近本次监听起点、且属于今天”的成功记录。
            // 历史记录（如昨天的 18:11 下班极速打卡成功）要么时间对不上“现在”，
            // 要么被飞书归入“昨天”分组，都会被直接排除，不再依赖脆弱的固定延迟基线。
            val (candidateMillis, groupIsToday) = parseSuccessTimeAndGroup(text, keywordPos)
            val accepted = if (candidateMillis != null) {
                val fresh = candidateMillis in (armTimeMillis - 3 * 60_000)..(armTimeMillis + 5 * 60_000)
                // groupIsToday == true 或无法确定分组（无日期分组标记）时，结合新鲜度判定
                fresh && groupIsToday != false
            } else {
                // 无时间戳的成功词（如“已打卡”按钮状态，或企业微信“自动打卡·正常”）：
                // 必须本次会话确实见过打卡按钮，证明是本次打卡动作导致的状态变化，
                // 避免把今天早已打卡的状态误报为本次成功
                (matchedKeyword == "已打卡" || isWeWorkAuto) && sawPunchButton
            }

            if (accepted) {
                textDetected = true
                LogFileManager.writeLog("无障碍检测到打卡成功（keyword=$matchedKeyword，candidateMillis=$candidateMillis，groupIsToday=$groupIsToday）")
                val snippet = extractSnippet(text, matchedKeyword)
                handleTextDetected(snippet, matchedKeyword, packageName)
            } else {
                LogFileManager.writeLog("无障碍忽略成功词（历史/非本次）：keyword=$matchedKeyword，candidateMillis=$candidateMillis，groupIsToday=$groupIsToday")
            }
        } finally {
            root.recycle()
        }
    }

    /**
     * 递归收集 AccessibilityNodeInfo 中的文本内容
     */
    private fun collectNodeText(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        val sb = StringBuilder()
        if (!node.text.isNullOrEmpty()) {
            sb.append(node.text).append(" ")
        }
        if (!node.contentDescription.isNullOrEmpty()) {
            sb.append(node.contentDescription).append(" ")
        }
        for (i in 0 until node.childCount) {
            sb.append(collectNodeText(node.getChild(i)))
        }
        return sb.toString()
    }

    /**
     * 检测到打卡成功文本后的处理：
     * - 文本反馈：直接发送文本内容
     * - 截屏反馈：再截一张图并发送
     * 最后通知 TaskScheduler 取消超时等待。
     */
    private fun handleTextDetected(snippet: String, keyword: String, packageName: String) {
        val resultSource = SaveKeyValues.loadInt(Constant.RESULT_SOURCE_KEY, Constant.DEFAULT_INDEX)
        if (resultSource != 2) return

        val feedbackMode = SaveKeyValues.loadInt(Constant.ACCESSIBILITY_FEEDBACK_MODE_KEY, 0)
        val messageTitle = SaveKeyValues.loadString(Constant.MESSAGE_TITLE_KEY, "打卡结果通知")
        val appName = Constant.getAppName(packageName)

        if (feedbackMode == 1) {
            // 文本反馈：直接发文本
            MessageDispatcher.sendMessage(
                messageTitle,
                StatusReporter.buildClockInTextResultHtml(snippet, keyword, appName),
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
                        StatusReporter.buildClockInTextResultHtml(snippet, keyword, appName),
                        imagePath
                    )
                } else {
                    MessageDispatcher.sendMessage(
                        messageTitle,
                        StatusReporter.buildClockInTextResultHtml(snippet, keyword, appName, "截屏失败，仅有文本结果"),
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
        val timeMatch = timePattern.find(window)
        val candidateMillis = if (timeMatch != null) {
            val (h, m) = timeMatch.destructured
            try {
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

    override fun onInterrupt() {
        Log.w(TAG, "无障碍服务被中断")
    }
}
