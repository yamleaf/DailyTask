package com.pengxh.daily.app.utils

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

import com.pengxh.daily.app.extensions.LegacyWakeLockFlags
import com.pengxh.daily.app.extensions.acquireWakeLock
import com.pengxh.kt.lite.utils.SaveKeyValues
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextClock
import androidx.core.content.res.ResourcesCompat
import com.pengxh.daily.app.R
import com.pengxh.daily.app.extensions.bringDailyTaskToFront

/**
 * 伪息屏蒙层（系统悬浮窗）。
 * 可在 NotificationListenerService 等后台组件中直接显示，不依赖 MainActivity 是否在前台。
 *
 * 与 [MaskViewController] 成对：进入时通常两层都盖上；用户解锁时同步卸掉 Activity 内蒙层。
 */
object MaskOverlayHelper {

    /**
     * 隐藏蒙层的意图，决定副作用（拉前台 / 同步 Activity / 通知 idle / 显示浮窗）。
     */
    enum class HideReason {
        /** 用户上滑 / 音量键解锁：同步 Activity 蒙层；仅 overlay 时拉起控制界面 */
        USER_UNLOCK,

        /** Activity / 远程指令已处理 UI：只卸 overlay，不 bringFront */
        SYNC,

        /**
         * 打卡前临时卸蒙层：释放 SCREEN_DIM（调用方已 keepAwakeForPunch）、显示浮窗倒计时；
         * 不拉前台、不同步 Activity、不 onBlackMaskHidden（结束时由调用方按需 show 恢复）。
         */
        TEMP_PUNCH,
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var rootView: View? = null
    private var touchDownY = 0f
    private var dismissLatch = false

    /** 截图前临时隐藏标记：只有为 true 时 restoreAfterScreenshot 才会重新显示 */
    @Volatile
    private var hiddenForScreenshot = false

    /**
     * 由 MainActivity 注册：若 Activity 内蒙层仍在，则同步隐藏并返回 true。
     */
    @Volatile
    var activityMaskHider: (() -> Boolean)? = null

    fun isShowing(): Boolean = rootView != null

    /** 蒙层显示期间持有的「微亮不锁屏」WakeLock（SCREEN_DIM），比全亮省电 */
    @Volatile
    private var keepAwakeWakeLock: PowerManager.WakeLock? = null

    private fun acquireKeepAwake(context: Context) {
        if (keepAwakeWakeLock?.isHeld == true) return
        keepAwakeWakeLock = context.acquireWakeLock(
            LegacyWakeLockFlags.SCREEN_DIM,
            "DailyTask:MaskPseudo",
            extraFlags = LegacyWakeLockFlags.CAUSES_WAKEUP
        )
        if (keepAwakeWakeLock != null) {
            LogFileManager.writeLog("伪息屏：已持有 SCREEN_DIM_WAKE_LOCK（微亮不锁屏）")
        }
    }

    private fun releaseKeepAwake(context: Context) {
        keepAwakeWakeLock?.let { if (it.isHeld) it.release() }
        keepAwakeWakeLock = null
        LogFileManager.writeLog("伪息屏：释放 SCREEN_DIM_WAKE_LOCK")
    }

    /**
     * @param keepAwake 是否持有 SCREEN_DIM_WAKE_LOCK 保亮。
     *  - true：伪息屏语义（屏幕微亮常驻，CPU 不睡，等待系统超时熄灭背光）；
     *  - false：纯黑蒙层语义（真息屏打卡优化用）。不持任何保亮锁，屏幕照常走系统超时自然灭屏。
     */
    fun show(context: Context, keepAwake: Boolean = true) {
        val appCtx = context.applicationContext
        mainHandler.post {
            if (rootView != null) return@post
            if (!Settings.canDrawOverlays(appCtx)) {
                LogFileManager.error("伪息屏失败：无悬浮窗权限")
                return@post
            }
            val windowManager = appCtx.getSystemService(WindowManager::class.java) ?: return@post
            dismissLatch = false

            val frame = FrameLayout(appCtx).apply {
                setBackgroundColor(Color.BLACK)
                isClickable = true
                isFocusable = true
                isFocusableInTouchMode = true
                setOnTouchEventForDismiss(this)
                setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN &&
                        keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                    ) {
                        hide(appCtx, HideReason.USER_UNLOCK)
                        true
                    } else false
                }
            }
            val clock = TextClock(appCtx).apply {
                format24Hour = "HH:mm"
                setTextColor(0xB3FFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 56f)
                runCatching {
                    typeface = ResourcesCompat.getFont(appCtx, R.font.ds_digital)
                }
            }
            if (!SaveKeyValues.loadBoolean(Constant.PSEUDO_MASK_NO_CLOCK_KEY, false)) {
                frame.addView(
                    clock,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER
                    )
                )
            } else {
                LogFileManager.writeLog("伪息屏：已隐藏时钟（仅黑屏）")
            }

            val flags = (WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.OPAQUE
            ).apply {
                gravity = Gravity.CENTER
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }

            try {
                windowManager.addView(frame, params)
                rootView = frame
                if (keepAwake) {
                    acquireKeepAwake(appCtx)
                }
                FloatingWindowController.hide()
                if (keepAwake) {
                    LogFileManager.action("伪息屏蒙层已显示（微亮不锁屏）")
                } else {
                    LogFileManager.action("纯黑蒙层已显示（不保亮，等待系统超时灭屏）")
                }
            } catch (e: Exception) {
                LogFileManager.error("伪息屏蒙层显示失败: ${e.message}")
            }
        }
    }

    /** 用户解锁默认入口（上滑 / 兼容旧调用） */
    fun hide(context: Context) {
        hide(context, HideReason.USER_UNLOCK)
    }

    fun hide(context: Context, reason: HideReason) {
        val appCtx = context.applicationContext
        val run = Runnable { hideInternal(appCtx, reason) }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            run.run()
        } else {
            mainHandler.post(run)
        }
    }

    private fun hideInternal(appCtx: Context, reason: HideReason) {
        val hadOverlay = rootView != null
        rootView?.let { view ->
            runCatching {
                appCtx.getSystemService(WindowManager::class.java)?.removeView(view)
            }
            rootView = null
            // 打卡路径调用方已持有 SCREEN_BRIGHT；用户/同步路径释放 SCREEN_DIM
            releaseKeepAwake(appCtx)
        }
        dismissLatch = false

        when (reason) {
            HideReason.USER_UNLOCK -> {
                val activityHidMask = activityMaskHider?.invoke() == true
                if (hadOverlay && !activityHidMask) {
                    appCtx.bringDailyTaskToFront(showMask = false)
                }
                FloatingWindowController.show()
                if (hadOverlay) {
                    LogFileManager.writeLog("伪息屏蒙层已隐藏（用户解锁）")
                    IdlePseudoMaskController.onBlackMaskHidden(appCtx)
                }
            }

            HideReason.SYNC -> {
                FloatingWindowController.show()
                if (hadOverlay) {
                    LogFileManager.writeLog("伪息屏蒙层已隐藏（同步）")
                    IdlePseudoMaskController.onBlackMaskHidden(appCtx)
                }
            }

            HideReason.TEMP_PUNCH -> {
                // 允许打卡倒计时浮窗；不拉前台、不扰动 Activity 蒙层 / idle 计时
                FloatingWindowController.show()
                if (hadOverlay) {
                    LogFileManager.writeLog("伪息屏蒙层已临时移除（打卡）")
                }
            }
        }
    }

    /**
     * 截图前临时移除蒙层（不触发 FloatingWindowController.show / onBlackMaskHidden 等副作用），
     * 避免 takeScreenshot 截到黑屏蒙层而非应用界面。
     */
    fun hideForScreenshot(context: Context) {
        val appCtx = context.applicationContext
        mainHandler.post {
            if (rootView == null) {
                hiddenForScreenshot = false
                return@post
            }
            hiddenForScreenshot = true
            val view = rootView ?: return@post
            runCatching {
                appCtx.getSystemService(WindowManager::class.java)?.removeView(view)
            }
            rootView = null
            LogFileManager.writeLog("截图前临时移除伪息屏蒙层")
        }
    }

    /**
     * 截图后恢复蒙层（仅当 hideForScreenshot 之前确实移除了蒙层时才恢复）。
     */
    fun restoreAfterScreenshot(context: Context) {
        val appCtx = context.applicationContext
        mainHandler.post {
            if (!hiddenForScreenshot) return@post
            hiddenForScreenshot = false
            show(appCtx)
            LogFileManager.writeLog("截图后恢复伪息屏蒙层")
        }
    }

    /**
     * 统一封装「截图/操作前临时移除蒙层 → 执行 block → 操作后恢复蒙层」。
     */
    suspend fun <T> withMaskSuspended(context: Context, block: suspend () -> T): T {
        val maskShowing = isShowing()
        if (maskShowing) hideForScreenshot(context)
        return try {
            block()
        } finally {
            if (maskShowing) restoreAfterScreenshot(context)
        }
    }

    private fun setOnTouchEventForDismiss(view: View) {
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownY = event.rawY
                    dismissLatch = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val deltaY = event.rawY - touchDownY
                    val isSwipe = kotlin.math.abs(deltaY) > 200f
                    if (isSwipe && !dismissLatch) {
                        dismissLatch = true
                        hide(view.context, HideReason.USER_UNLOCK)
                    }
                    isSwipe
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val deltaY = event.rawY - touchDownY
                    val isSwipe = kotlin.math.abs(deltaY) > 200f
                    if (isSwipe && !dismissLatch) {
                        dismissLatch = true
                        hide(view.context, HideReason.USER_UNLOCK)
                    }
                    isSwipe
                }

                else -> false
            }
        }
    }
}
