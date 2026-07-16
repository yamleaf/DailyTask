package com.pengxh.daily.app.utils

/**
 * 通知监听服务 → UI 事件
 * 通过 NotificationMonitorService.events: SharedFlow 传递
 */
sealed class MonitorEvent {
    /**
     * 打卡成功通知
     * @param text 可选：从界面或通知中读取到的打卡成功文本（完整文本，已弃用展示）
     * @param keyword 可选：匹配到的成功关键词
     * @param appName 可选：识别到的应用名称
     * @param snippet 可选：关键词附近的摘要文本
     * */
    data class ClockInSuccess(
        val text: String? = null,
        val keyword: String? = null,
        val appName: String? = null,
        val snippet: String? = null
    ) : MonitorEvent()

    /**
     * 远程"执行任务"指令
     * */
    data object StartTaskCommand : MonitorEvent()

    /**
     * 远程"终止任务"指令
     * */
    data object StopTaskCommand : MonitorEvent()

    /**
     * 远程"息屏"指令
     * */
    data object ShowMaskCommand : MonitorEvent()

    /**
     * 远程"亮屏"指令
     * */
    data object HideMaskCommand : MonitorEvent()

    /**
     * 远程"截屏"指令
     * */
    data object AppOpenedForScreenshot : MonitorEvent()
}
