package com.pengxh.daily.app.service

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.pengxh.daily.app.extensions.openApplication
import com.pengxh.daily.app.ui.MainActivity
import com.pengxh.daily.app.utils.AppRuntimeConfig
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.FloatingWindowController
import com.pengxh.daily.app.utils.IdlePseudoMaskController
import com.pengxh.daily.app.utils.LogFileManager
import com.pengxh.daily.app.utils.MaskOverlayHelper
import com.pengxh.daily.app.utils.MessageDispatcher
import com.pengxh.daily.app.utils.MonitorEvent
import com.pengxh.daily.app.utils.StatusReporter
import com.pengxh.daily.app.utils.TaskScheduler
import com.pengxh.kt.lite.utils.SaveKeyValues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * 远程打卡执行器（与通知监听服务解耦）：
 * MQTT 下发与 QQ/微信指令共用同一套打卡编排——亮屏/摘蒙层 → 唤起目标 App →
 * 倒计时（文本命中提前结束 / 末段兜底截屏）→ 回桌面 → 结果反馈。
 * 仅依赖 Context 与协程作用域，无障碍/截屏等能力由各静态组件自行提供，
 * 因此控制端 MQTT 手动打卡不再要求被控端开启通知监听权限。
 */
object RemotePunchRunner {

    private const val kTag = "RemotePunchRunner"

    fun run(
        context: Context,
        scope: CoroutineScope,
        keyword: String = SaveKeyValues.loadString(Constant.REMOTE_COMMAND_KEY, "打卡")
    ) {
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
        val keptAwakeForPunch = IdlePseudoMaskController.keepAwakeForPunchIfNeeded(context)
        val maskWasShowing = MaskOverlayHelper.isShowing()
        if (maskWasShowing) {
            LogFileManager.writeLog("远程打卡：伪息屏蒙层显示中，临时移除以确保障碍不遮挡目标App")
            MaskOverlayHelper.hide(context, MaskOverlayHelper.HideReason.TEMP_PUNCH)
        }
        try {
            val opened = context.openApplication {
                launchIn(scope, "远程打卡倒计时") {
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
                                        || (feedbackMode == 1 && AutoProjectionAccessibilityService.canTakeScreenshot(context))
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
                                context.startActivity(Intent(Intent.ACTION_MAIN).apply {
                                    addCategory(Intent.CATEGORY_HOME)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                })
                            } catch (e: Exception) {
                                Log.w(kTag, "返回桌面失败: ${e.message}")
                            }
                            delay(300L)
                            bringMainActivityForMask(context, showMask = maskWasShowing)
                            // 真息屏打卡优化：远程指令同样在回到前台后，若「打卡前真息屏 + 伪息屏关 + 模式=息屏」，
                            // 盖不保亮黑蒙层等待系统超时灭屏（ForegroundRunningService 在 SCREEN_OFF/SCREEN_ON 时摘除）。
                            if (keptAwakeForPunch && !maskWasShowing &&
                                !AppRuntimeConfig.isForcePseudoMask() &&
                                AppRuntimeConfig.getScreenMode() == Constant.SCREEN_MODE_OFF
                            ) {
                                MaskOverlayHelper.show(context, keepAwake = false)
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
                                // 按「伪息屏开关 + 屏幕模式」三态矩阵恢复蒙层：
                                // 伪息屏开或模式=伪息屏 → 保亮伪息屏；模式=息屏 → 不保亮黑蒙层等系统超时；
                                // 模式=亮屏 → 不恢复蒙层（前台常亮阻止系统息屏）
                                val pseudoOn = AppRuntimeConfig.isForcePseudoMask()
                                val mode = AppRuntimeConfig.getScreenMode()
                                when {
                                    pseudoOn || mode == Constant.SCREEN_MODE_PSEUDO ->
                                        MaskOverlayHelper.show(context)
                                    mode == Constant.SCREEN_MODE_OFF -> {
                                        MaskOverlayHelper.show(context, keepAwake = false)
                                        LogFileManager.writeLog("远程打卡结束：伪息屏关+模式息屏，盖不保亮黑蒙层等待系统超时灭屏")
                                    }
                                    else ->
                                        LogFileManager.writeLog("远程打卡结束：伪息屏关+模式亮屏，不恢复蒙层")
                                }
                            }
                        }
                        // 无论是否恢复蒙层，只要打卡前亮过屏就释放打卡保活，让屏幕回到系统自然管理
                        if (keptAwakeForPunch) {
                            IdlePseudoMaskController.releaseKeepAwakeForPunch(context)
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
                            // 按「伪息屏开关 + 屏幕模式」三态矩阵恢复：模式=息屏盖不保亮黑蒙层等系统超时，模式=亮屏不恢复
                            val pseudoOn = AppRuntimeConfig.isForcePseudoMask()
                            val mode = AppRuntimeConfig.getScreenMode()
                            when {
                                pseudoOn || mode == Constant.SCREEN_MODE_PSEUDO ->
                                    MaskOverlayHelper.show(context)
                                mode == Constant.SCREEN_MODE_OFF -> {
                                    MaskOverlayHelper.show(context, keepAwake = false)
                                    LogFileManager.writeLog("伪息屏关+模式息屏：盖不保亮黑蒙层等待系统超时灭屏")
                                }
                                else ->
                                    LogFileManager.writeLog("伪息屏关+模式亮屏：不恢复蒙层")
                            }
                        }
                        IdlePseudoMaskController.releaseKeepAwakeForPunch(context)
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
                    if (maskWasShowing) MaskOverlayHelper.show(context)
                    IdlePseudoMaskController.releaseKeepAwakeForPunch(context)
                }
            }
        }
    }

    /** 尝试把主界面拉到前台，同步 Activity 内蒙层；即使后台启动被系统拦截，悬浮窗蒙层也已生效。 */
    private fun bringMainActivityForMask(context: Context, showMask: Boolean) {
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
                putExtra(Constant.EXTRA_MASK_COMMAND, if (showMask) 1 else 0)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(kTag, "bringMainActivityForMask failed: ${e.message}")
            LogFileManager.error("拉起主界面失败（蒙层仍可能已通过悬浮窗显示）: ${e.message}")
        }
    }

    private fun launchIn(scope: CoroutineScope, tag: String, block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
        if (!scope.isActive) {
            Log.w(kTag, "scope 已取消，无法处理指令: $tag")
            LogFileManager.error("scope 已取消，$tag 指令丢弃")
            return
        }
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                Log.e(kTag, "$tag 处理异常", e)
                LogFileManager.error("$tag 处理异常: ${e.message}")
            }
        }
    }
}
