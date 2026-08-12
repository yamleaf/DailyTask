package com.pengxh.daily.app.widget

/**
 * 桌面宠物状态机。
 *
 * 与动画资产的映射关系：
 * - [ENTERING] ：进场动画 cat in（从右向左横向进入屏幕，动画结束时停在屏幕中间），播放一次。
 * - [IDLE]     ：待机 blink 眨眼循环。自由态与贴边态共用。
 * - [RANDOM]   ：随机动作（挠屁股 / 挠腿 二选一，播放一次），播完回 blink。
 * - [CLICKED]  ：点击反馈挥手（播放一次），播完回 blink。
 * - [EXITING]  ：离场动画 cat out（向左走出屏幕，播放一次）。
 * - [COUNTDOWN]：打卡倒计时悬浮窗（不是 Lottie；联动停/启动画与计时器）。
 * - [DIMMED]   ：伪息屏蒙层遮挡。暂停随机/离场计时，禁止拖动；与 COUNTDOWN 可并存（正交 flag）。
 *
 * 贴边不是独立状态：由控制器 `dockSide` + 烘焙 blink_dock_* 资源表现。
 */
enum class PetState {

    ENTERING,

    IDLE,

    RANDOM,

    CLICKED,

    EXITING,

    COUNTDOWN,

    DIMMED;

    /** 播放一次即结束的「一次性动画」状态 */
    val isOneShot: Boolean
        get() = this == ENTERING || this == RANDOM || this == CLICKED || this == EXITING
}
