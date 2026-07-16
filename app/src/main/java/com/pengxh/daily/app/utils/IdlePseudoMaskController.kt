package com.pengxh.daily.app.utils

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.pengxh.daily.app.DailyTaskApplication

/**
 * 离开本软件后的「强制伪息屏」策略（需在设置中开启）：
 * 1. 立刻铺一层「透明保亮」悬浮窗，尽量阻止系统自动灭屏（触摸可穿透）
 * 2. 无回到本软件超过 [IDLE_TO_MASK_MS] 后，升级为黑屏伪息屏蒙层
 * 3. 若系统仍发出 SCREEN_OFF，则主动亮屏并进入黑屏蒙层
 *
 * 开关关闭时不执行上述行为。打卡等待窗口内不盖黑屏。
 */
object IdlePseudoMaskController {

    private const val IDLE_TO_MASK_MS = 60_000L
    private const val WAKE_LOCK_MS = 5_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var appInBackground = false
    private var keepAwakeView: View? = null

    private val upgradeToMaskRunnable: Runnable = Runnable {
        if (!appInBackground) return@Runnable
        if (!AppRuntimeConfig.isForcePseudoMask()) return@Runnable
        if (TaskScheduler.isInActivePunch()) {
            LogFileManager.writeLog("打卡进行中，延后伪息屏")
            mainHandler.postDelayed(upgradeToMaskRunnable, IDLE_TO_MASK_MS)
            return@Runnable
        }
        val context = DailyTaskApplication.get()
        if (MaskOverlayHelper.isShowing()) return@Runnable
        LogFileManager.writeLog("强制伪息屏：离开本软件超时，升级为伪息屏蒙层")
        removeKeepAwake(context)
        MaskOverlayHelper.show(context)
    }

    fun onAppForegrounded(context: Context) {
        appInBackground = false
        mainHandler.removeCallbacks(upgradeToMaskRunnable)
        removeKeepAwake(context)
    }

    fun onAppBackgrounded(context: Context) {
        appInBackground = true
        mainHandler.removeCallbacks(upgradeToMaskRunnable)
        if (!AppRuntimeConfig.isForcePseudoMask()) {
            LogFileManager.writeLog("已离开本软件（强制伪息屏未开启，跳过保亮/倒计时）")
            return
        }
        // 立刻保亮，避免系统在 15s~30s 先灭屏
        ensureKeepAwake(context)
        if (MaskOverlayHelper.isShowing()) {
            return
        }
        mainHandler.postDelayed(upgradeToMaskRunnable, IDLE_TO_MASK_MS)
        LogFileManager.writeLog(
            "强制伪息屏已开启：离开本软件，启动 ${IDLE_TO_MASK_MS / 1000}s 倒计时（已开启透明保亮）"
        )
    }

    /**
     * 系统灭屏回调：仅在强制伪息屏开启时抢回亮屏并进入伪息屏。
     * App 在前台时不干预——蒙层由 Activity 生命周期管理，唤醒屏幕会造成循环。
     */
    fun onSystemScreenOff(context: Context) {
        if (!AppRuntimeConfig.isForcePseudoMask()) return
        if (!appInBackground) return
        if (TaskScheduler.isInActivePunch()) {
            wakeScreen(context)
            ensureKeepAwake(context)
            LogFileManager.writeLog("打卡中收到 SCREEN_OFF，已保亮但不盖黑屏")
            return
        }
        wakeScreen(context)
        removeKeepAwake(context)
        MaskOverlayHelper.show(context)
        LogFileManager.writeLog("收到 SCREEN_OFF，已亮屏并进入伪息屏")
    }

    fun onForcePseudoMaskDisabled() {
        mainHandler.removeCallbacks(upgradeToMaskRunnable)
        runCatching { removeKeepAwake(DailyTaskApplication.get()) }
        LogFileManager.writeLog("强制伪息屏已关闭，取消后台保亮与倒计时")
    }

    fun cancel() {
        appInBackground = false
        mainHandler.removeCallbacks(upgradeToMaskRunnable)
        runCatching { removeKeepAwake(DailyTaskApplication.get()) }
    }

    /** 黑屏蒙层被关掉后，若仍在外部且开关开启，则继续透明保亮并重新计时 */
    fun onBlackMaskHidden(context: Context) {
        if (!appInBackground) return
        if (!AppRuntimeConfig.isForcePseudoMask()) return
        ensureKeepAwake(context)
        mainHandler.removeCallbacks(upgradeToMaskRunnable)
        mainHandler.postDelayed(upgradeToMaskRunnable, IDLE_TO_MASK_MS)
        LogFileManager.writeLog("伪息屏已解除但仍在外部，重新开启透明保亮与倒计时")
    }

    /**
     * 前台任务切换时重置伪息屏倒计时。
     * 由无障碍服务的 TYPE_WINDOW_STATE_CHANGED 事件触发，
     * 用户在后台期间切换其他 App 时，倒计时重新开始。
     */
    fun onForegroundTaskChanged() {
        if (!appInBackground) return
        if (!AppRuntimeConfig.isForcePseudoMask()) return
        if (MaskOverlayHelper.isShowing()) return
        mainHandler.removeCallbacks(upgradeToMaskRunnable)
        mainHandler.postDelayed(upgradeToMaskRunnable, IDLE_TO_MASK_MS)
        LogFileManager.writeLog("前台任务切换，伪息屏倒计时已重置（${IDLE_TO_MASK_MS / 1000}s）")
    }

    private fun ensureKeepAwake(context: Context) {
        val appCtx = context.applicationContext
        mainHandler.post {
            if (!AppRuntimeConfig.isForcePseudoMask()) return@post
            if (MaskOverlayHelper.isShowing()) return@post
            if (keepAwakeView != null) return@post
            if (!Settings.canDrawOverlays(appCtx)) {
                LogFileManager.writeLog("透明保亮失败：无悬浮窗权限")
                return@post
            }
            val windowManager = appCtx.getSystemService(WindowManager::class.java) ?: return@post
            val view = View(appCtx).apply {
                setBackgroundColor(Color.TRANSPARENT)
            }
            val flags = (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                width = WindowManager.LayoutParams.MATCH_PARENT
                height = WindowManager.LayoutParams.MATCH_PARENT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            try {
                windowManager.addView(view, params)
                keepAwakeView = view
                LogFileManager.writeLog("透明保亮层已开启")
            } catch (e: Exception) {
                LogFileManager.writeLog("透明保亮层失败: ${e.message}")
            }
        }
    }

    private fun removeKeepAwake(context: Context) {
        val appCtx = context.applicationContext
        mainHandler.post {
            val view = keepAwakeView ?: return@post
            runCatching {
                appCtx.getSystemService(WindowManager::class.java)?.removeView(view)
            }
            keepAwakeView = null
        }
    }

    private fun wakeScreen(context: Context) {
        try {
            val powerManager = context.getSystemService(PowerManager::class.java) ?: return
            @Suppress("DEPRECATION")
            val wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                "DailyTask:IdlePseudoMask"
            )
            wakeLock.acquire(WAKE_LOCK_MS)
        } catch (e: Exception) {
            LogFileManager.writeLog("wakeScreen 失败: ${e.message}")
        }
    }
}
