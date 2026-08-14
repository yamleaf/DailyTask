package com.pengxh.daily.app.utils

import android.content.Context
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 悬浮窗控制器
 */
object FloatingWindowController {

    /** 预截图前等待末段渐隐动画推进的时间（与悬浮窗 ~920ms 步进动画对齐，取前半段） */
    const val SCREENSHOT_FADE_YIELD_MS = 380L

    private val _timeTick = MutableSharedFlow<Int>(extraBufferCapacity = 2)
    val timeTick = _timeTick.asSharedFlow()

    private val _overtime = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val overtime = _overtime.asSharedFlow()

    private val _visibility = MutableSharedFlow<Boolean>(replay = 1, extraBufferCapacity = 1)
    val visibility = _visibility.asSharedFlow()

    /** 系统屏幕开关状态（伪息屏待机/灭屏时用于暂停悬浮球动画播放） */
    private val _screenOn = MutableStateFlow(true)
    val screenOn = _screenOn.asStateFlow()

    // 目标 App 操作会话：被控端主动跳到目标 App 期间显示悬浮窗（打卡/遥控截屏等），与蒙层遮挡状态（visibility）解耦
    private val _floatSessionActive = MutableSharedFlow<Boolean>(replay = 1, extraBufferCapacity = 1)
    val floatSessionActive = _floatSessionActive.asSharedFlow()

    /** 截屏期间临时隐藏整窗（倒计时/贴边宠物），避免 TYPE_APPLICATION_OVERLAY 被截进图 */
    private val _hiddenForScreenshot = MutableSharedFlow<Boolean>(replay = 1, extraBufferCapacity = 1)
    val hiddenForScreenshot = _hiddenForScreenshot.asSharedFlow()

    /** 同步可读：是否处于打开目标 App 的倒计时会话（供伪息屏门禁等同步查询） */
    @Volatile
    var isSessionActive: Boolean = false
        private set

    @Volatile
    private var screenshotHideLatch = false

    fun updateTime(tick: Int) {
        _timeTick.tryEmit(tick)
    }

    fun startFloatSession() {
        isSessionActive = true
        _floatSessionActive.tryEmit(true)
    }

    fun stopFloatSession() {
        isSessionActive = false
        _floatSessionActive.tryEmit(false)
    }

    fun setOvertime(seconds: Int) {
        _overtime.tryEmit(seconds)
    }

    fun show() {
        _visibility.tryEmit(true)
    }

    fun hide() {
        _visibility.tryEmit(false)
    }

    /** 系统屏幕亮/灭：亮屏恢复动画，灭屏暂停动画播放 */
    fun setScreenOn(on: Boolean) {
        _screenOn.value = on
    }

    /**
     * 用 PowerManager 对齐真实亮灭屏，避免服务在已灭屏时拉起仍默认 screenOn=true、动画空转。
     */
    fun syncScreenOnFromSystem(context: Context) {
        val interactive = context.applicationContext
            .getSystemService(PowerManager::class.java)
            ?.isInteractive != false
        _screenOn.value = interactive
    }

    /** 截图前临时隐藏悬浮窗（含贴边宠物）；与蒙层 hideForScreenshot 对称 */
    fun hideForScreenshot() {
        screenshotHideLatch = true
        _hiddenForScreenshot.tryEmit(true)
    }

    /** 截图结束后恢复；仅当本次确实因截屏藏过才恢复 */
    fun restoreAfterScreenshot() {
        if (!screenshotHideLatch) return
        screenshotHideLatch = false
        _hiddenForScreenshot.tryEmit(false)
    }
}
