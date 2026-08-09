package com.pengxh.daily.app.utils

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import com.pengxh.kt.lite.utils.SaveKeyValues

/**
 * 手势控制器
 *
 * 职责：
 * 1. 管理滑动手势检测
 * 2. 根据手势操作控制蒙层显示/隐藏
 * 3. 提供手势开关配置
 *
 * 手势规则：
 * - 双指下滑：进入伪息屏
 * - 单指 / 双指上滑：解除伪息屏
 *
 * @param context 上下文
 * @param maskViewController 蒙层视图控制器
 */
class GestureController(
    private val context: Context, private val maskViewController: MaskViewController
) {

    private val minFlingDistance = 1000f

    /** 当前手势序列中同时落下的最大手指数（ACTION_DOWN 时重置，用于双指判定） */
    private var gesturePointerCount = 0

    private val gestureDetector: GestureDetector

    init {
        gestureDetector = GestureDetector(context, GestureListener())
    }

    /**
     * 处理触摸事件
     *
     * 始终把完整事件序列交给手势识别器（否则其无法感知 ACTION_DOWN，
     * onFling 的 e1 为 null）。
     * - 下滑进入伪息屏：仅在本次手势同时落下的手指数 ≥ 2 时触发（双指下滑）。
     * - 上滑解除伪息屏：单指 / 双指均可触发。
     * 其余单指滑动交给 App 自身滚动/点击逻辑处理。
     *
     * @param event 触摸事件
     */
    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> gesturePointerCount = 1
            MotionEvent.ACTION_POINTER_DOWN -> gesturePointerCount = maxOf(gesturePointerCount, event.pointerCount)
            else -> gesturePointerCount = maxOf(gesturePointerCount, event.pointerCount)
        }
        return gestureDetector.onTouchEvent(event)
    }

    /**
     * 手势监听器
     */
    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onFling(
            e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float
        ): Boolean {
            val isGestureEnabled =
                SaveKeyValues.loadBoolean(Constant.GESTURE_DETECTOR_KEY, true)
            // 如果手势未启用，则不处理
            if (!isGestureEnabled) {
                return false
            }

            val deltaY = calculateDeltaY(e1, e2)

            // 上滑解除伪息屏：单指 / 双指均可
            if (isSwipeUp(deltaY, e1, e2)) {
                handleHideMask()
                return true
            }

            // 下滑进入伪息屏：仅双指触发
            if (gesturePointerCount >= 2 && isSwipeDown(deltaY, e1, e2)) {
                handleShowMask()
                return true
            }

            return super.onFling(e1, e2, velocityX, velocityY)
        }
    }

    /**
     * 计算垂直滑动距离
     */
    private fun calculateDeltaY(e1: MotionEvent?, e2: MotionEvent): Float {
        return kotlin.math.abs(e2.y - (e1?.y ?: e2.y))
    }

    /**
     * 判断是否为向下滑动手势（进入伪息屏，仅双指）
     */
    private fun isSwipeDown(deltaY: Float, e1: MotionEvent?, e2: MotionEvent): Boolean {
        return deltaY > minFlingDistance
                && (e2.y - (e1?.y ?: e2.y)) > 0
                && !maskViewController.isMaskVisible()
    }

    /**
     * 判断是否为向上滑动手势（解除伪息屏，单指 / 双指均可）
     */
    private fun isSwipeUp(deltaY: Float, e1: MotionEvent?, e2: MotionEvent): Boolean {
        return deltaY > minFlingDistance
                && (e2.y - (e1?.y ?: e2.y)) < 0
                && maskViewController.isMaskVisible()
    }

    /**
     * 处理显示蒙层
     */
    private fun handleShowMask() {
        if (!maskViewController.isMaskVisible()) {
            maskViewController.showMaskView()
        }
    }

    /**
     * 处理隐藏蒙层
     */
    private fun handleHideMask() {
        if (maskViewController.isMaskVisible()) {
            maskViewController.hideMaskView()
        }
    }
}