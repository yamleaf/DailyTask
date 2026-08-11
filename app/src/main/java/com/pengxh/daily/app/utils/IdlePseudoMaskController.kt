package com.pengxh.daily.app.utils

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

import com.pengxh.daily.app.extensions.acquireWakeLock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.pengxh.daily.app.DailyTaskApplication
import com.pengxh.daily.app.extensions.bringDailyTaskToFront
import com.pengxh.daily.app.service.KeepAliveReceiver
import com.pengxh.daily.app.utils.Constant
import com.pengxh.kt.lite.utils.SaveKeyValues

/**
 * 离开本软件后的「强制伪息屏」策略（需在设置中开启）：
 * 1. 立刻铺一层「透明保亮」悬浮窗，尽量阻止系统自动灭屏（触摸可穿透）
 * 2. 离开本软件超过设定秒数（默认 60s，可配置）后，升级为黑屏伪息屏蒙层
 * 3. 进入伪息屏前按「返回桌面」开关分流：开启则先退回桌面再跳回本 App，关闭则直接跳回本 App
 * 4. 若系统仍发出 SCREEN_OFF，则主动亮屏并进入黑屏蒙层
 *
 * 开关关闭时不执行上述行为。打卡等待窗口内不盖黑屏。
 * 注意：本控制器只负责「强制伪息屏」路径；远程打卡复原使用独立的 bringMainActivityForMask，互不打扰。
 * 「暂停使用」开启时所有入口一律 no-op，实现被控端彻底安静。
 */
object IdlePseudoMaskController {

    private const val DEFAULT_IDLE_TO_MASK_SEC = 60
    private const val MIN_IDLE_TO_MASK_SEC = 10
    private const val MAX_IDLE_TO_MASK_SEC = 3600
    /** 先回桌面、再拉起本 App 的间隔：等桌面切换稳定后再拉起，避免两个 Intent 抢前台导致蒙层闪退 */
    private const val HOME_TO_APP_DELAY_MS = 800L
    private const val WAKE_LOCK_MS = 5_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var appInBackground = false
    private var pendingReturnFromBackground = false
    /** 是否正处于「进入伪息屏」的过渡中（离开超时后到蒙层真正显示前），用于阻止倒计时被重复重置 */
    private var enteringMask = false
    /** 打卡期间持有的独立保亮 WakeLock：唤醒并保持屏幕亮，避免蒙层移除释放 SCREEN_DIM 后系统休眠锁屏 */
    private var punchWakeLock: PowerManager.WakeLock? = null
    /** 打卡期间伪息屏被延后的日志每轮只报一次，避免高频重复写日志 */
    private var punchIdlePostponeNotified = false
    private var keepAwakeView: View? = null

    /** 离开本软件多少秒后进入伪息屏（可配置，默认 60s，范围 10~3600s） */
    private fun idleToMaskMs(): Long {
        val sec = SaveKeyValues.loadInt(
            Constant.IDLE_PSEUDO_MASK_TIMEOUT_KEY, DEFAULT_IDLE_TO_MASK_SEC
        ).coerceIn(MIN_IDLE_TO_MASK_SEC, MAX_IDLE_TO_MASK_SEC)
        return sec * 1000L
    }

    private val upgradeToMaskRunnable: Runnable = Runnable {
        if (KeepAliveReceiver.isPaused()) return@Runnable
        if (!appInBackground) return@Runnable
        if (!AppRuntimeConfig.isForcePseudoMask()) return@Runnable
        if (TaskScheduler.isInActivePunch()) {
            // 打卡进行中：绝不盖黑屏 / 强制返回桌面。延后日志每轮只报一次，避免高频重复写。
            if (!punchIdlePostponeNotified) {
                LogFileManager.writeLog("打卡进行中，延后伪息屏")
                punchIdlePostponeNotified = true
            }
            mainHandler.postDelayed(upgradeToMaskRunnable, idleToMaskMs())
            return@Runnable
        }
        punchIdlePostponeNotified = false
        val context = DailyTaskApplication.get()
        if (MaskOverlayHelper.isShowing()) return@Runnable
        LogFileManager.action("强制伪息屏：离开本软件超时，进入伪息屏流程")
        enterPseudoMask(context)
    }

    /**
     * 当前 DailyTask 应用是否整体处于前台（由 DailyTaskApplication 的 Activity 生命周期统计得出）。
     * 区别于「MainActivity 是否在后台」：停留在设置页等其它本应用页面时，应用仍在前台，
     * 不应被判定为「离开了本软件」，否则会误触发回桌面+跳回（用户在设置页就撞到的 bug）。
     */
    private fun isDailyTaskAppForeground(): Boolean = DailyTaskApplication.isAppForeground

    /**
     * 进入伪息屏：按「返回桌面」开关分流。
     * - 开启：先退回桌面（步骤1），等桌面切换稳定后再拉起本 App 并由其显示蒙层（步骤2）。
     * - 关闭：直接拉起本 App 再显示蒙层（不经由桌面）。
     * 两个 Intent 串行化（延迟 [HOME_TO_APP_DELAY_MS]），避免抢前台导致蒙层闪退。
     * 全程不影响远程打卡：打卡复原路径使用独立的 bringMainActivityForMask，不经此方法。
     */
    private fun enterPseudoMask(context: Context) {
        if (enteringMask) return
        if (!appInBackground) {
            LogFileManager.writeLog("强制伪息屏：已进入前台，跳过蒙层")
            return
        }
        // 双保险：确认本应用整体确实已离开前台（用户在其它 App / 桌面）。
        // 仅以 MainActivity 的 onPause 判断会误把「停留在设置页」当成离开，这里用应用级前台状态兜底。
        if (isDailyTaskAppForeground()) {
            LogFileManager.writeLog("强制伪息屏：本应用仍在前台（如设置页），跳过蒙层（防误触发）")
            return
        }
        if (MaskOverlayHelper.isShowing()) return
        val backToHome = SaveKeyValues.loadBoolean(Constant.BACK_TO_HOME_KEY, Constant.BACK_TO_HOME_DEFAULT)
        enteringMask = true
        removeKeepAwake(context)
        if (backToHome) {
            // 步骤1（受「返回桌面」开关控制）：先退回桌面
            try {
                context.startActivity(
                    Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            } catch (e: Exception) {
                LogFileManager.error("强制伪息屏：返回桌面失败: ${e.message}")
            }
            LogFileManager.action("强制伪息屏：已退回桌面，稍后跳回 DailyTask 显示蒙层")
            // 串行化：等桌面切换稳定后再拉起本 App，避免两个 Intent 抢前台导致蒙层闪退
            mainHandler.postDelayed({
                if (!appInBackground || !enteringMask) return@postDelayed
                if (MaskOverlayHelper.isShowing()) return@postDelayed
                context.bringDailyTaskToFront(true)
                scheduleOverlayFallback(context)
            }, HOME_TO_APP_DELAY_MS)
        } else {
            // 步骤1 关闭：直接跳回本 App 再显示蒙层
            LogFileManager.action("强制伪息屏：直接跳回 DailyTask 显示蒙层（未开启返回桌面）")
            context.bringDailyTaskToFront(true)
            scheduleOverlayFallback(context)
        }
    }

    /**
     * MIUI DeviceGuard 等系统的后台 Activity 启动限制会拦截 bringDailyTaskToFront 拉起 MainActivity，
     * 导致蒙层无法经 Activity 显示。兜底：应用仍停留后台且蒙层未显示时，直接用悬浮窗蒙层盖黑屏，
     * 保证「强制伪息屏」在后台拉起被拦时依然生效。
     */
    private fun scheduleOverlayFallback(context: Context) {
        mainHandler.postDelayed({
            if (!appInBackground || !enteringMask) return@postDelayed
            if (MaskOverlayHelper.isShowing()) return@postDelayed
            LogFileManager.action("MainActivity 后台拉起被系统拦截，改用悬浮窗蒙层兜底")
            MaskOverlayHelper.show(context)
        }, HOME_TO_APP_DELAY_MS + 1000L)
    }

    fun onAppForegrounded(context: Context) {
        appInBackground = false
        enteringMask = false
        mainHandler.removeCallbacks(upgradeToMaskRunnable)
        removeKeepAwake(context)
    }

    fun onAppBackgrounded(context: Context) {
        // 「暂停使用」开启：不进入伪息屏流程（不保亮、不倒计时），实现彻底安静
        if (KeepAliveReceiver.isPaused()) {
            appInBackground = false
            enteringMask = false
            mainHandler.removeCallbacks(upgradeToMaskRunnable)
            return
        }
        appInBackground = true
        pendingReturnFromBackground = true
        enteringMask = false
        punchIdlePostponeNotified = false
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
        mainHandler.postDelayed(upgradeToMaskRunnable, idleToMaskMs())
        LogFileManager.writeLog(
            "强制伪息屏已开启：离开本软件，启动 ${idleToMaskMs() / 1000}s 倒计时（已开启透明保亮）"
        )
    }

    /**
     * 系统灭屏回调：仅在强制伪息屏开启时抢回亮屏并进入伪息屏。
     * App 在前台时不干预——蒙层由 Activity 生命周期管理，唤醒屏幕会造成循环。
     */
    fun onSystemScreenOff(context: Context) {
        if (KeepAliveReceiver.isPaused()) return
        if (!AppRuntimeConfig.isForcePseudoMask()) return
        if (!appInBackground) return
        if (TaskScheduler.isInActivePunch()) {
            wakeScreen(context)
            ensureKeepAwake(context)
            LogFileManager.writeLog("打卡中收到 SCREEN_OFF，已保亮但不盖黑屏")
            return
        }
        // 进入黑屏蒙层，但【不要】主动唤醒背光：
        // 蒙层已无 FLAG_KEEP_SCREEN_ON，系统会按自身超时自然熄灭背光，进入真正省电的伪息屏。
        // 若在此处 wakeScreen()，会导致「黑屏但背光常亮、滑动无反应」的假死状态（问题1）。
        removeKeepAwake(context)
        MaskOverlayHelper.show(context)
        LogFileManager.writeLog("收到 SCREEN_OFF，已进入伪息屏（不强制亮屏）")
    }

    /**
     * 打卡期间保活屏幕：
     * 1. 同步获取并持有带 ACQUIRE_CAUSES_WAKEUP 的屏幕 WakeLock，立即唤醒并保持亮屏；
     * 2. 另加 1x1dp FLAG_KEEP_SCREEN_ON 透明窗口兜底。
     *
     * 必须在【移除蒙层之前】调用：蒙层隐藏时会释放 SCREEN_DIM_WAKE_LOCK（MaskOverlayHelper.releaseKeepAwake），
     * 若先移除蒙层再保活，会形成「最后一个保亮锁已释放、新锁未建立」的空窗，系统按自身屏幕超时立即休眠锁屏
     * （真机复现：打卡瞬间 goToSleep + show keyguard + 指纹图标）。先唤醒保亮、再移除蒙层，彻底消除竞态。
     */
    fun keepAwakeForPunch(context: Context) {
        punchWakeLock = context.acquireWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
            "DailyTask:PunchKeepAwake",
            extraFlags = PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE
        )
        ensureKeepAwake(context)
        LogFileManager.writeLog("打卡期间：唤醒并保持亮屏，防止屏幕休眠")
    }

    /**
     * 打卡结束释放保活：释放打卡 WakeLock、移除透明保亮层，让黑屏蒙层恢复后屏幕可自然熄灭，回到省电伪息屏。
     */
    fun releaseKeepAwakeForPunch(context: Context) {
        punchWakeLock?.let { if (it.isHeld) it.release() }
        punchWakeLock = null
        removeKeepAwake(context)
        LogFileManager.writeLog("打卡结束：释放透明保亮")
    }

    fun onForcePseudoMaskDisabled() {
        enteringMask = false
        mainHandler.removeCallbacks(upgradeToMaskRunnable)
        mainHandler.removeCallbacks(foregroundIdleRunnable)
        runCatching { removeKeepAwake(DailyTaskApplication.get()) }
        runCatching {
            punchWakeLock?.let { if (it.isHeld) it.release() }
            punchWakeLock = null
        }
        LogFileManager.writeLog("强制伪息屏已关闭，取消后台保亮与倒计时、前台无操作计时")
    }

    fun cancel() {
        appInBackground = false
        enteringMask = false
        mainHandler.removeCallbacks(upgradeToMaskRunnable)
        runCatching { removeKeepAwake(DailyTaskApplication.get()) }
        runCatching {
            punchWakeLock?.let { if (it.isHeld) it.release() }
            punchWakeLock = null
        }
    }

    // ═══════════════════════ 前台「无操作」自动进入伪熄屏 ═══════════════════════
    // 由 lite 模块 KotlinBaseActivity 经 ForegroundIdleBridge 在 DailyTaskApplication.onCreate
    // 接线：统一驱动任务页 / 远程页 / 设置页等所有前台页面的无操作计时，
    // 复用「伪熄屏增强」延时配置。前台无操作息屏受「伪熄屏」总开关控制：
    // 开关开启时，App 在前台无操作超过延时即自动进伪熄屏（进入方式与后台路径一致）；
    // 开关关闭时，前台无操作不再自动进伪熄屏。

    private var idleMaskContext: Context? = null

    private val foregroundIdleRunnable: Runnable = Runnable {
        val context = idleMaskContext ?: return@Runnable
        if (KeepAliveReceiver.isPaused()) return@Runnable
        if (!AppRuntimeConfig.isForcePseudoMask()) return@Runnable
        if (appInBackground) return@Runnable
        if (TaskScheduler.isInActivePunch()) return@Runnable
        if (MaskOverlayHelper.isShowing()) return@Runnable
        LogFileManager.action("前台无操作超时（${idleToMaskMs() / 1000}s），进入伪熄屏")
        // 与后台「离开超时」路径一致：拉起 MainActivity 由 MaskViewController 接管，
        // 隐藏系统栏 + 屏幕亮度置 0 + activity 黑屏 + overlay，实现真正彻底黑（而非仅 overlay 一层）。
        context.bringDailyTaskToFront(true)
    }

    fun startIdleMask(context: Context) {
        if (KeepAliveReceiver.isPaused()) return
        if (!AppRuntimeConfig.isForcePseudoMask()) return
        if (MaskOverlayHelper.isShowing()) return
        idleMaskContext = context
        mainHandler.removeCallbacks(foregroundIdleRunnable)
        mainHandler.postDelayed(foregroundIdleRunnable, idleToMaskMs())
    }

    fun stopIdleMask() {
        mainHandler.removeCallbacks(foregroundIdleRunnable)
    }

    /** 用户在本 App 前台有交互：重置无操作计时 */
    fun notifyUserActivity(context: Context) {
        startIdleMask(context)
    }

    /** 主界面在 onNewIntent 时判断：刚从后台拉起则补显伪息屏蒙层 */
    fun wasAppInBackground(): Boolean = appInBackground

    /**
     * 消费「刚从后台返回」标志。onActivityStarted（onAppForegrounded）会先于
     * Activity.onNewIntent 把 appInBackground 重置为 false，导致 onNewIntent 无法据此判断，
     * 因此用本标志在 onAppBackgrounded 置位、由 onNewIntent 一次性消费。
     */
    fun consumeReturnFromBackground(): Boolean {
        val v = pendingReturnFromBackground
        pendingReturnFromBackground = false
        return v
    }

    // ═══════════════════════ 打卡返回即息屏 ═══════════════════════
    /** 黑屏蒙层被关掉后，若仍在外部且开关开启，则继续透明保亮并重新计时 */
    fun onBlackMaskHidden(context: Context) {
        enteringMask = false
        if (KeepAliveReceiver.isPaused()) return
        if (!AppRuntimeConfig.isForcePseudoMask()) return
        if (appInBackground) {
            // 后台解除：仍在外部，重新保亮并计时，超时再次进入伪息屏
            ensureKeepAwake(context)
            mainHandler.removeCallbacks(upgradeToMaskRunnable)
            mainHandler.postDelayed(upgradeToMaskRunnable, idleToMaskMs())
            LogFileManager.writeLog("伪息屏已解除但仍在外部，重新开启透明保亮与倒计时")
        } else {
            // 前台手动取消：重新启动前台无操作计时，超时后再次自动进入伪息屏（修复取消后不再自动进入）
            startIdleMask(idleMaskContext ?: context)
            LogFileManager.writeLog("伪息屏已解除（前台），重新启动无操作计时")
        }
    }

    /**
     * 前台任务切换时重置伪息屏倒计时。
     * 由无障碍服务的 TYPE_WINDOW_STATE_CHANGED 事件触发，
     * 用户在后台期间切换其他 App 时，倒计时重新开始。
     */
    fun onForegroundTaskChanged() {
        if (KeepAliveReceiver.isPaused()) return
        if (!appInBackground) return
        if (!AppRuntimeConfig.isForcePseudoMask()) return
        if (TaskScheduler.isInActivePunch()) return  // 打卡期间不重置/触发伪息屏计时（同时避免高频日志）
        if (enteringMask) return
        if (MaskOverlayHelper.isShowing()) return
        mainHandler.removeCallbacks(upgradeToMaskRunnable)
        mainHandler.postDelayed(upgradeToMaskRunnable, idleToMaskMs())
    }

    private fun ensureKeepAwake(context: Context) {
        val appCtx = context.applicationContext
        mainHandler.post {
            if (!AppRuntimeConfig.isForcePseudoMask()) return@post
            if (MaskOverlayHelper.isShowing()) return@post
            if (keepAwakeView != null) return@post
            if (!Settings.canDrawOverlays(appCtx)) {
                LogFileManager.error("透明保亮失败：无悬浮窗权限")
                return@post
            }
            val windowManager = appCtx.getSystemService(WindowManager::class.java) ?: return@post
            val view = View(appCtx).apply {
                setBackgroundColor(Color.TRANSPARENT)
            }
            // 极小 1x1dp 透明窗口保亮：FLAG_KEEP_SCREEN_ON 是窗口级属性，窗口可见即保亮，无需全屏。
            // 原全屏 MATCH_PARENT overlay 在安卓12 覆盖含手势导航区的整屏后（即使带 FLAG_NOT_TOUCHABLE）
            // 实测吞掉桌面/系统输入 → 「退到桌面即锁死」（关闭悬浮窗权限即恢复，已实锤）。
            // 改 1x1dp 后不再覆盖任何交互区域，锁死根除，保亮/不锁屏行为不变。
            val size = (1 * appCtx.resources.displayMetrics.density).toInt().coerceAtLeast(1)
            val flags = (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val params = WindowManager.LayoutParams(
                size,
                size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
            }
            try {
                windowManager.addView(view, params)
                keepAwakeView = view
                LogFileManager.writeLog("透明保亮层已开启（1x1dp）")
            } catch (e: Exception) {
                LogFileManager.error("透明保亮层失败: ${e.message}")
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
            context.acquireWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
                "DailyTask:IdlePseudoMask",
                WAKE_LOCK_MS,
                PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE
            )
        } catch (e: Exception) {
            LogFileManager.error("wakeScreen 失败: ${e.message}")
        }
    }
}
