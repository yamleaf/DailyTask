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
    private val dateTimeFormat by lazy { SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA) }

    @Volatile
    private var activeWakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var textDetectionActive = false

    @Volatile
    private var textDetected = false

    /** 记录上次前台包名，用于检测前台任务切换 */
    @Volatile
    private var lastForegroundPackage: String? = null

    /** 打卡成功关键词（包含任意一个即判定成功） */
    private val successKeywords = listOf(
        "打卡成功",
        "已打卡",
        "上班打卡",
        "下班打卡",
        "外出打卡",
        "打卡完成",
        "考勤成功"
    )

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

            val text = collectNodeText(root).replace("\n", " ").replace("\\s+".toRegex(), " ").trim()
            if (text.isBlank()) return

            LogFileManager.writeLog("无障碍读取文本：package=$packageName, text=${text.take(120)}")

            val matchedKeyword = successKeywords.find { text.contains(it) }
            if (matchedKeyword != null) {
                textDetected = true
                LogFileManager.writeLog("无障碍检测到打卡成功：keyword=$matchedKeyword")
                val snippet = extractSnippet(text, matchedKeyword)
                handleTextDetected(snippet, matchedKeyword, packageName)
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

    override fun onInterrupt() {
        Log.w(TAG, "无障碍服务被中断")
    }
}
