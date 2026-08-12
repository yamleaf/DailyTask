package com.pengxh.daily.app.widget

import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

/**
 * 桌面宠物交互控制器（重构版）。
 *
 * 状态机：[PetState]（ENTERING / IDLE / RANDOM / CLICKED / EXITING / COUNTDOWN / DIMMED）。
 * 贴边不是独立状态——由 [DockSide] 记录方向；自由态播 blink，贴边态播烘焙好的
 * blink_dock_left / blink_dock_right（运行时不再旋转/镜像，避免回弹）。
 *
 * 核心规则（与需求一一对应）：
 * 1. 进场：启动时窗口置于屏幕中央，播放 enter.json（右→左进入），播完切 blink 循环。
 * 2. 拖动：单指自由拖动，期间保持 blink 循环；松手时仅判断距**左右**边缘是否 < 阈值，
 *    是则平滑吸附贴边，否则停留在松手位置（垂直方向永不吸附）。
 * 3. 贴边视觉：滑到边缘并收窄视口后，切换对应贴边 Lottie，只露头部。
 * 4. 交互：仅点到猫主体热区才拖动/单击挥手；自由态每 12~25s 随机挠屁股/挠腿，播完回 blink；
 *    连续 120s 无用户操作播放 exit.json 向左出屏，播完强制贴左边并重置计时。
 * 5. 打卡：COUNTDOWN 时停止动画与定时器；结束恢复待机动画 + 重启计时器。
 * 6. 伪息屏：DIMMED 时强制贴最近边、暂停随机/离场计时、屏蔽拖动；解除后恢复并保持贴边。
 *
 * 坐标模型：窗口左上角相对屏幕左上角的绝对像素，gravity 恒为 START|TOP。
 * 窗口尺寸固定 [PetConfig.WINDOW_WIDTH_DP] x [PetConfig.WINDOW_HEIGHT_DP]。
 */
class DesktopPetController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val petView: DesktopPetView,
    /** 窗口根视图：WindowManager.addView 上屏的那个视图（如 window_floating 的 root） */
    private val windowView: android.view.View,
    private val params: WindowManager.LayoutParams
) {

    /** 贴边方向（非独立状态，是 IDLE 的视觉属性） */
    enum class DockSide { NONE, LEFT, RIGHT }

    private val kTag = "DesktopPetController"
    private val density: Float get() = context.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val mainHandler = Handler(Looper.getMainLooper())
    private val random = Random(System.currentTimeMillis())

    // ========== 状态 ==========

    private var currentState = PetState.ENTERING
    private var dockSide = DockSide.NONE
    /** Pseudo-mask occlusion (orthogonal to currentState; can coexist with COUNTDOWN) */
    private var dimmed = false
    /** Punch countdown session (orthogonal to currentState; can coexist with dimmed) */
    private var countdownActive = false
    private var destroyed = false

    // ========== 窗口位置（绝对像素） ==========

    private var windowX = 0
    private var windowY = 0
    private var windowWidthPx = 0
    private var windowHeightPx = 0
    /** 当前悬浮窗视口（贴边时收成头宽，自由态=全尺寸） */
    private var viewportWidthPx = 0
    private var viewportHeightPx = 0

    /** 贴边位移动画；再次贴边 / 拖起时取消，避免两段动画叠在一起 */
    private var dockAnimator: ValueAnimator? = null

    // ========== 拖动 ==========

    private var dragging = false
    private var totalMove = 0f
    private var initialWindowX = 0
    private var initialWindowY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    // ========== 定时器 ==========

    private var randomActionRunnable: Runnable? = null
    private var idleExitRunnable: Runnable? = null

    /** 离场动画结束后强制贴左边（由 EXITING 流程设置，IDLE 恢复时消费） */
    private var snapLeftAfterExit = false

    /** 随机动作播放期间离场计时到期 → 动作播完后再离场 */
    private var pendingExitAfterRandom = false

    // ========== 手势 ==========

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            // 点击特效：仅自由态（未贴边）播放挥手；贴边期间保持 blink，不响应点击
            if (currentState == PetState.IDLE && !dimmed && dockSide == DockSide.NONE) {
                playWave()
            }
            return true
        }
    })

    init {
        // 初始化窗口尺寸与位置：屏幕中央，进场动画
        val (sw, sh) = screenSize()
        windowWidthPx = (PetConfig.WINDOW_WIDTH_DP * density).toInt()
        windowHeightPx = (PetConfig.WINDOW_HEIGHT_DP * density).toInt()
        viewportWidthPx = windowWidthPx
        viewportHeightPx = windowHeightPx
        // 自由态视口 = WINDOW_*_DP；贴边时收成 SNAP_HEAD_VISIBLE_DP 宽
        petView.setViewportSize(viewportWidthPx, viewportHeightPx)
        windowX = (sw - windowWidthPx) / 2
        windowY = ((sh - windowHeightPx) / 2).coerceIn(safeTop(), sh - windowHeightPx - safeBottom())
        applyParamsPosition()
        attachTouchListener()
        // 进场：右→左进入屏幕
        petView.stopAndReset()
        petView.playAsset(PetConfig.ASSET_ENTER, loop = false) { onEnterFinished() }
    }

    // ========== 对外控制（由 FloatingWindowService 联动调用） ==========

    /** Punch session start/end (orthogonal to mask dimmed) */
    fun onCountdownChanged(active: Boolean) {
        if (active == countdownActive) return
        countdownActive = active
        if (active) {
            enterCountdown()
        } else {
            reconcileAfterOverlayFlags()
        }
    }

    /** Mask occlusion change (driven by float visibility, not config switch) */
    fun onDimmedChanged(dimming: Boolean) {
        if (dimming == dimmed) return
        dimmed = dimming
        if (dimming) {
            enterDimmed()
        } else {
            reconcileAfterOverlayFlags()
        }
    }

    private fun clearPendingExitAfterRandom() {
        pendingExitAfterRandom = false
    }

    // ========== 状态切换 ==========

    /**
     * 进入待机：自由态播 blink，贴边态播对应 blink_dock_*；
     * 自由态下启动随机/离场计时器；贴边态由 [applyDock] 取消随机动作。
     */
    private fun enterIdle(resetExitTimer: Boolean) {
        if (destroyed || countdownActive) return
        if (dimmed) {
            currentState = PetState.DIMMED
            return
        }
        currentState = PetState.IDLE
        // 非贴边时恢复全尺寸（打卡结束 / 随机动作回来等）；贴边由 applyDock 再收窄
        if (dockSide == DockSide.NONE && !snapLeftAfterExit) {
            setFreeViewport()
        }
        if (snapLeftAfterExit) {
            snapLeftAfterExit = false
            applyDock(DockSide.LEFT, animate = true)
        } else {
            playIdleAsset()
        }
        scheduleRandomAction()
        if (resetExitTimer) {
            scheduleIdleExit()
        }
    }

    /** 按当前贴边方向播放待机资源（自由 blink / 左右贴边烘焙 JSON） */
    private fun playIdleAsset() {
        petView.stopAndReset()
        when (dockSide) {
            DockSide.LEFT -> petView.playAsset(
                PetConfig.ASSET_BLINK_DOCK_LEFT,
                loop = true,
                fillViewport = true,
                alignToEnd = true
            )
            DockSide.RIGHT -> petView.playAsset(
                PetConfig.ASSET_BLINK_DOCK_RIGHT,
                loop = true,
                fillViewport = true,
                alignToEnd = false
            )
            DockSide.NONE -> petView.playAsset(PetConfig.ASSET_BLINK, loop = true)
        }
    }

    /** 进场动画结束 → 待机 */
    private fun onEnterFinished() {
        enterIdle(resetExitTimer = true)
    }

    /** 点击：挥手（一次性），播完回 IDLE 并重置离场计时（点击=用户操作） */
    private fun playWave() {
        clearPendingExitAfterRandom()
        currentState = PetState.CLICKED
        petView.stopAndReset()
        petView.playAsset(PetConfig.ASSET_WAVE, loop = false) {
            enterIdle(resetExitTimer = true)
        }
    }

    // ========== 随机动作 ==========

    private fun scheduleRandomAction() {
        cancelRandomAction()
        // 贴边 / 非 IDLE 不调度；回到自由 IDLE 时由 enterIdle / 拖起贴边 再次调用
        if (dimmed || dockSide != DockSide.NONE || currentState != PetState.IDLE) return
        val delay = random.nextLong(PetConfig.RANDOM_ACTION_MIN_MS, PetConfig.RANDOM_ACTION_MAX_MS + 1)
        val task = Runnable {
            randomActionRunnable = null
            // 触发时若已离开自由 IDLE，丢弃本轮（不吞掉后续调度——由当前态的 enterIdle 负责重挂）
            if (dimmed || dockSide != DockSide.NONE || currentState != PetState.IDLE) return@Runnable
            val asset = if (random.nextBoolean()) {
                PetConfig.ASSET_SCRATCH_BUTT
            } else {
                PetConfig.ASSET_SCRATCH_LEG
            }
            currentState = PetState.RANDOM
            petView.stopAndReset()
            petView.playAsset(asset, loop = false) {
                if (pendingExitAfterRandom) {
                    pendingExitAfterRandom = false
                    startExit()
                } else {
                    // 随机非用户操作：不重置离场计时，但必须重新挂上下一轮随机
                    enterIdle(resetExitTimer = false)
                }
            }
        }
        randomActionRunnable = task
        mainHandler.postDelayed(task, delay)
        Log.d(kTag, "scheduleRandomAction in ${delay}ms")
    }

    private fun cancelRandomAction() {
        randomActionRunnable?.let { mainHandler.removeCallbacks(it) }
        randomActionRunnable = null
    }

    // ========== 离场动画（无用户操作超时） ==========

    private fun scheduleIdleExit() {
        cancelIdleExit()
        if (dimmed || dockSide != DockSide.NONE || currentState != PetState.IDLE) return
        val task = Runnable {
            idleExitRunnable = null
            if (dimmed || dockSide != DockSide.NONE) return@Runnable
            when (currentState) {
                PetState.RANDOM -> {
                    // 随机播完后再离场
                    pendingExitAfterRandom = true
                }
                PetState.IDLE -> startExit()
                else -> Unit
            }
        }
        idleExitRunnable = task
        mainHandler.postDelayed(task, PetConfig.IDLE_EXIT_TIMEOUT_MS)
    }

    private fun cancelIdleExit() {
        idleExitRunnable?.let { mainHandler.removeCallbacks(it) }
        idleExitRunnable = null
    }

    /** 离场：播放向左出屏动画，播完强制贴左边 */
    private fun startExit() {
        clearPendingExitAfterRandom()
        currentState = PetState.EXITING
        cancelRandomAction()
        cancelIdleExit()
        // 离场动画含「向左走出」；若此前贴边（窄视口贴边），先恢复全尺寸再播离场。
        dockSide = DockSide.NONE
        clearDockVisual()
        windowX = clampHorizontal(windowX)
        windowY = clampVertical(windowY)
        applyParamsPosition()
        petView.stopAndReset()
        petView.playAsset(PetConfig.ASSET_EXIT, loop = false) {
            snapLeftAfterExit = true
            enterIdle(resetExitTimer = true)
        }
    }

    // ========== COUNTDOWN / DIMMED ==========

    private fun enterCountdown() {
        cancelDockAnimator()
        clearPendingExitAfterRandom()
        cancelRandomAction()
        cancelIdleExit()
        currentState = PetState.COUNTDOWN
        petView.stopAndReset()
        // Countdown card larger than pet: WRAP_CONTENT avoids head-width clip
        params.width = WindowManager.LayoutParams.WRAP_CONTENT
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        try {
            windowManager.updateViewLayout(windowView, params)
        } catch (e: IllegalArgumentException) {
            Log.d(kTag, "updateViewLayout skipped: ${e.message}")
        }
    }

    private fun enterDimmed() {
        cancelDockAnimator()
        clearPendingExitAfterRandom()
        cancelRandomAction()
        cancelIdleExit()
        // Punch session owns window chrome; only pause timers (visibility GONE + DIMMED)
        if (countdownActive) return
        currentState = PetState.DIMMED
        if (dockSide == DockSide.NONE) {
            applyDock(nearestSide(), animate = true)
        } else {
            playIdleAsset()
        }
    }

    /** After countdown/dimmed flag changes, converge to the correct pose. */
    private fun reconcileAfterOverlayFlags() {
        if (destroyed) return
        if (countdownActive) {
            currentState = PetState.COUNTDOWN
            return
        }
        if (dimmed) {
            currentState = PetState.DIMMED
            if (dockSide == DockSide.NONE) {
                applyDock(nearestSide(), animate = true)
            } else {
                playIdleAsset()
            }
            return
        }
        // Restore pet viewport px after leaving COUNTDOWN WRAP_CONTENT
        if (dockSide != DockSide.NONE) {
            val visiblePx = (PetConfig.SNAP_HEAD_VISIBLE_DP * density).toInt()
                .coerceIn(1, windowWidthPx)
            val (sw, _) = screenSize()
            windowX = when (dockSide) {
                DockSide.LEFT -> 0
                DockSide.RIGHT -> (sw - visiblePx).coerceAtLeast(0)
                DockSide.NONE -> windowX
            }
            setDockViewport(visiblePx)
        } else {
            setFreeViewport()
        }
        applyParamsPosition()
        enterIdle(resetExitTimer = true)
    }

    private fun nearestSide(): DockSide {
        val (sw, _) = screenSize()
        val distLeft = windowX.toFloat()
        val distRight = (sw - windowX - windowWidthPx).toFloat()
        return if (distLeft <= distRight) DockSide.LEFT else DockSide.RIGHT
    }

    /**
     * 应用贴边：先全尺寸滑到边缘，再切到烘焙好的左右贴边 Lottie（无 View 旋转/镜像，避免回弹）。
     */
    private fun applyDock(side: DockSide, animate: Boolean) {
        if (side == DockSide.NONE) return
        dockSide = side
        cancelDockAnimator()
        val (sw, sh) = screenSize()
        val visiblePx = (PetConfig.SNAP_HEAD_VISIBLE_DP * density).toInt()
            .coerceIn(1, windowWidthPx)
        val targetY = windowY.coerceIn(safeTop(), sh - windowHeightPx - safeBottom())
        val edgeXDock = when (side) {
            DockSide.LEFT -> 0
            DockSide.RIGHT -> (sw - visiblePx).coerceAtLeast(0)
        }
        val dockAsset = when (side) {
            DockSide.LEFT -> PetConfig.ASSET_BLINK_DOCK_LEFT
            DockSide.RIGHT -> PetConfig.ASSET_BLINK_DOCK_RIGHT
        }

        cancelRandomAction()
        // Do not overwrite currentState; DIMMED/IDLE owned by callers + reconcile

        val alignToEnd = side == DockSide.LEFT
        if (!animate) {
            windowX = edgeXDock
            windowY = targetY
            setDockViewport(visiblePx)
            petView.resetTransform()
            petView.stopAndReset()
            petView.playAsset(
                dockAsset, loop = true, fillViewport = true, alignToEnd = alignToEnd
            )
            applyParamsPosition()
            return
        }

        val startX = windowX
        val startY = windowY
        setFreeViewport()
        petView.resetTransform()
        petView.stopAndReset()
        petView.playAsset(PetConfig.ASSET_BLINK, loop = true)
        applyParamsPosition()

        val slideMs = PetConfig.SNAP_ANIM_DURATION_MS

        val slide = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = slideMs
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { a ->
                if (destroyed || countdownActive) return@addUpdateListener
                val f = a.animatedValue as Float
                // 同步收窄：左锚定 x=插值到 0，右锚定右缘；结束时正好是头宽视口
                val w = (windowWidthPx + (visiblePx - windowWidthPx) * f).toInt().coerceAtLeast(1)
                windowX = when (side) {
                    DockSide.LEFT -> (startX + (edgeXDock - startX) * f).toInt()
                    DockSide.RIGHT -> {
                        val startRight = startX + windowWidthPx
                        val right = startRight + (sw - startRight) * f
                        (right - w).toInt()
                    }
                }
                windowY = (startY + (targetY - startY) * f).toInt()
                viewportWidthPx = w
                viewportHeightPx = windowHeightPx
                petView.setViewportSize(viewportWidthPx, viewportHeightPx)
                applyParamsPosition()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (dockAnimator === animation) dockAnimator = null
                    if (destroyed || countdownActive) return
                    windowX = edgeXDock
                    windowY = targetY
                    setDockViewport(visiblePx)
                    petView.resetTransform()
                    petView.stopAndReset()
                    petView.playAsset(
                        dockAsset, loop = true, fillViewport = true, alignToEnd = alignToEnd
                    )
                    applyParamsPosition()
                }

                override fun onAnimationCancel(animation: android.animation.Animator) {
                    if (dockAnimator === animation) dockAnimator = null
                }
            })
        }
        dockAnimator = slide
        slide.start()
    }

    private fun setDockViewport(visiblePx: Int) {
        viewportWidthPx = visiblePx
        viewportHeightPx = windowHeightPx
        petView.setViewportSize(viewportWidthPx, viewportHeightPx)
    }

    private fun setFreeViewport() {
        viewportWidthPx = windowWidthPx
        viewportHeightPx = windowHeightPx
        petView.setViewportSize(viewportWidthPx, viewportHeightPx)
    }

    private fun cancelDockAnimator() {
        dockAnimator?.cancel()
        dockAnimator = null
    }

    /** 清除贴边视觉并恢复全尺寸视口 */
    private fun clearDockVisual() {
        cancelDockAnimator()
        petView.resetTransform()
        setFreeViewport()
    }

    // ========== 拖动 ==========

    private fun attachTouchListener() {
        petView.setOnTouchListener { _, event ->
            // Only IDLE accepts drag/tap
            if (dimmed || countdownActive || currentState != PetState.IDLE) {
                return@setOnTouchListener false
            }
            // 仅猫主体热区响应；点到画布透明边则穿透到下层 App，避免误触
            if (event.actionMasked == MotionEvent.ACTION_DOWN && !isTouchOnCat(event)) {
                passThroughCurrentTouch()
                return@setOnTouchListener false
            }
            // DOWN 已在热区内：后续 MOVE/UP 持续跟手，即使滑出热区也不断触
            if (event.actionMasked != MotionEvent.ACTION_DOWN && !dragging) {
                return@setOnTouchListener false
            }
            handleTouch(event)
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    /**
     * 热区：窗口居中内缩 [PetConfig.HIT_INSET_RATIO]，贴近猫主体，忽略画布透明边。
     * 贴边窄窗时热区铺满视口（本身已是头宽）。
     */
    private fun isTouchOnCat(event: MotionEvent): Boolean {
        val w = petView.width.takeIf { it > 0 } ?: viewportWidthPx
        val h = petView.height.takeIf { it > 0 } ?: viewportHeightPx
        if (w <= 0 || h <= 0) return true
        // 贴边头宽视口：整窗都是有效点击区
        if (dockSide != DockSide.NONE || w <= (PetConfig.SNAP_HEAD_VISIBLE_DP * density * 1.2f)) {
            return true
        }
        val insetX = w * PetConfig.HIT_INSET_RATIO
        val insetY = h * PetConfig.HIT_INSET_RATIO
        val x = event.x
        val y = event.y
        return x in insetX..(w - insetX) && y in insetY..(h - insetY)
    }

    /**
     * 让当前这一笔触摸落到下层窗口：短暂加上 FLAG_NOT_TOUCHABLE 再恢复。
     */
    private fun passThroughCurrentTouch() {
        val old = params.flags
        params.flags = old or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        try {
            windowManager.updateViewLayout(windowView, params)
        } catch (_: IllegalArgumentException) {
            params.flags = old
            return
        }
        mainHandler.post {
            params.flags = old and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            try {
                windowManager.updateViewLayout(windowView, params)
            } catch (_: IllegalArgumentException) {
                Log.d(kTag, "restore touchable flags skipped")
            }
        }
    }

    private fun handleTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                totalMove = 0f
                clearPendingExitAfterRandom()
                if (dockSide != DockSide.NONE) {
                    dockSide = DockSide.NONE
                    clearDockVisual()
                    windowX = event.rawX.toInt() - windowWidthPx / 2
                    windowY = event.rawY.toInt() - windowHeightPx / 2
                    applyParamsPosition()
                    // 切回自由态 blink（贴边用的是烘焙 JSON）
                    petView.stopAndReset()
                    petView.playAsset(PetConfig.ASSET_BLINK, loop = true)
                    // 从贴边拖回自由态：重新挂上随机 / 离场计时
                    if (currentState == PetState.IDLE && !dimmed) {
                        scheduleRandomAction()
                        scheduleIdleExit()
                    }
                }
                initialWindowX = windowX
                initialWindowY = windowY
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                lastTouchX = event.rawX
                lastTouchY = event.rawY
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return
                totalMove += abs(event.rawX - lastTouchX) + abs(event.rawY - lastTouchY)
                lastTouchX = event.rawX
                lastTouchY = event.rawY
                if (totalMove < touchSlop) return
                windowX = clampHorizontal(initialWindowX + (event.rawX - initialTouchX).toInt())
                windowY = clampVertical(initialWindowY + (event.rawY - initialTouchY).toInt())
                applyParamsPosition()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return
                dragging = false
                if (totalMove >= touchSlop) {
                    snapOnRelease()
                }
            }
        }
    }

    /**
     * 松手吸附：仅判断左右边缘。
     * 距左/右边缘 < [PetConfig.SNAP_RELEASE_THRESHOLD_DP] → 平滑吸附贴边；否则保持当前位置。
     * 垂直方向永不吸附。贴边后不调度离场计时（scheduleIdleExit 在 dockSide!=NONE 时直接 return）。
     */
    private fun snapOnRelease() {
        val (sw, _) = screenSize()
        val distLeft = windowX.toFloat()
        val distRight = (sw - windowX - windowWidthPx).toFloat()
        val threshold = PetConfig.SNAP_RELEASE_THRESHOLD_DP * density

        val target = when {
            distLeft < threshold && distLeft <= distRight -> DockSide.LEFT
            distRight < threshold -> DockSide.RIGHT
            else -> DockSide.NONE
        }
        if (target == DockSide.NONE) {
            return
        }
        applyDock(target, animate = true)
    }

    // ========== 窗口定位 ==========

    private fun applyParamsPosition() {
        if (destroyed || countdownActive) return
        params.gravity = Gravity.START or Gravity.TOP
        params.x = windowX
        params.y = windowY
        // 显式宽高：贴边窄视口必须写进 LayoutParams，单靠 WRAP_CONTENT 在部分机型上不收窄
        params.width = viewportWidthPx
        params.height = viewportHeightPx
        try {
            windowManager.updateViewLayout(windowView, params)
        } catch (e: IllegalArgumentException) {
            Log.d(kTag, "updateViewLayout skipped: ${e.message}")
        }
    }

    private fun clampHorizontal(v: Int): Int {
        val (sw, _) = screenSize()
        return v.coerceIn(0, sw - windowWidthPx)
    }

    private fun clampVertical(v: Int): Int {
        val (_, sh) = screenSize()
        return v.coerceIn(safeTop(), sh - windowHeightPx - safeBottom())
    }

    // ========== 屏幕与安全区 ==========

    private fun screenSize(): Pair<Int, Int> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val b = windowManager.currentWindowMetrics.bounds
                b.width() to b.height()
            } else {
                @Suppress("DEPRECATION")
                val dm = DisplayMetrics().also { context.display.getRealMetrics(it) }
                (if (dm.widthPixels > 0) dm.widthPixels else (PetConfig.FALLBACK_SCREEN_WIDTH_DP * density).toInt()) to
                        (if (dm.heightPixels > 0) dm.heightPixels else (PetConfig.FALLBACK_SCREEN_HEIGHT_DP * density).toInt())
            }
        } catch (e: Exception) {
            (PetConfig.FALLBACK_SCREEN_WIDTH_DP * density).toInt() to
                    (PetConfig.FALLBACK_SCREEN_HEIGHT_DP * density).toInt()
        }
    }

    private fun safeTop(): Int {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val insets = windowManager.currentWindowMetrics.windowInsets
                max(
                    insets.getInsets(WindowInsets.Type.displayCutout()).top,
                    insets.getInsets(WindowInsets.Type.statusBars()).top
                )
            } else {
                (32 * density).toInt()
            }
        } catch (e: Exception) {
            (32 * density).toInt()
        }
    }

    private fun safeBottom(): Int {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val insets = windowManager.currentWindowMetrics.windowInsets
                max(
                    insets.getInsets(WindowInsets.Type.displayCutout()).bottom,
                    insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                )
            } else {
                (24 * density).toInt()
            }
        } catch (e: Exception) {
            (24 * density).toInt()
        }
    }

    // ========== 生命周期 ==========

    fun destroy() {
        destroyed = true
        cancelDockAnimator()
        clearPendingExitAfterRandom()
        mainHandler.removeCallbacksAndMessages(null)
        randomActionRunnable = null
        idleExitRunnable = null
        petView.setOnTouchListener(null)
        petView.destroy()
        dragging = false
    }
}