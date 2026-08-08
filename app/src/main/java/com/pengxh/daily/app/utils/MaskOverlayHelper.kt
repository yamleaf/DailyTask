package com.pengxh.daily.app.utils

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

import com.pengxh.daily.app.extensions.acquireWakeLock
import com.pengxh.daily.app.utils.Constant
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

/**
 * 伪息屏蒙层（系统悬浮窗）。
 * 可在 NotificationListenerService 等后台组件中直接显示，不依赖 MainActivity 是否在前台。
 */
object MaskOverlayHelper {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var rootView: View? = null
    private var touchDownY = 0f

    /** 截图前临时隐藏标记：只有为 true 时 restoreAfterScreenshot 才会重新显示 */
    @Volatile
    private var hiddenForScreenshot = false

    fun isShowing(): Boolean = rootView != null

    /** 蒙层显示期间持有的「微亮不锁屏」WakeLock（SCREEN_DIM），比全亮省电 */
    @Volatile
    private var keepAwakeWakeLock: PowerManager.WakeLock? = null

    private fun acquireKeepAwake(context: Context) {
        if (keepAwakeWakeLock?.isHeld == true) return
        keepAwakeWakeLock = context.acquireWakeLock(
            PowerManager.SCREEN_DIM_WAKE_LOCK,
            "DailyTask:MaskPseudo",
            extraFlags = PowerManager.ACQUIRE_CAUSES_WAKEUP
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

    fun show(context: Context) {
        val appCtx = context.applicationContext
        mainHandler.post {
            if (rootView != null) return@post
            if (!Settings.canDrawOverlays(appCtx)) {
                LogFileManager.writeLog("伪息屏失败：无悬浮窗权限")
                return@post
            }
            val windowManager = appCtx.getSystemService(WindowManager::class.java) ?: return@post

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
                        hide(appCtx)
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
            // 伪息屏隐藏时钟：开启后只显示黑屏，不添加时钟视图（省电）
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

            // 注：蒙层本身不加 FLAG_KEEP_SCREEN_ON（避免全亮整夜耗电）。
            // 但为满足「保持解锁（微亮）」需求，蒙层显示期间额外持有 SCREEN_DIM_WAKE_LOCK：
            // 屏幕维持低亮度、不触发系统锁屏，既比全亮省电，又保证打卡/无障碍截图不被锁屏打断。
            // 该 WakeLock 仅在蒙层被完整 hide() 时释放（见 releaseKeepAwake）；
            // 截图前临时移除蒙层时仍保持持有，避免截图中途锁屏导致截图失败。
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
                acquireKeepAwake(appCtx)
                FloatingWindowController.hide()
                LogFileManager.writeLog("伪息屏蒙层已显示（微亮不锁屏）")
            } catch (e: Exception) {
                LogFileManager.writeLog("伪息屏蒙层显示失败: ${e.message}")
            }
        }
    }

    fun hide(context: Context) {
        val appCtx = context.applicationContext
        mainHandler.post {
            val view = rootView ?: return@post
            runCatching {
                appCtx.getSystemService(WindowManager::class.java)?.removeView(view)
            }
            rootView = null
            releaseKeepAwake(appCtx)
            FloatingWindowController.show()
            LogFileManager.writeLog("伪息屏蒙层已隐藏")
            IdlePseudoMaskController.onBlackMaskHidden(appCtx)
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
     * 统一封装「截图/操作前临时移除蒙层 → 执行 block → 操作后恢复蒙层」，
     * 收敛各处散落的 hideForScreenshot/restoreAfterScreenshot 调用（P1-3）。
     * 仅当蒙层确实在显示时才移除并恢复，无副作用。
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
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    // 在移动过程中检测，达到阈值立即关闭，避免系统手势导航提前发送 CANCEL
                    val deltaY = event.rawY - touchDownY
                    if (kotlin.math.abs(deltaY) > 200f) {
                        hide(view.context)
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val deltaY = event.rawY - touchDownY
                    if (kotlin.math.abs(deltaY) > 200f) {
                        hide(view.context)
                    }
                    true
                }

                else -> true
            }
        }
    }
}
