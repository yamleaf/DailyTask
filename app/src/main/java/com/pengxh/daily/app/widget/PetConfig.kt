package com.pengxh.daily.app.widget

/**
 * 桌面宠物交互与视觉参数。
 * 单位：dp / ms / 度。通过 resources.displayMetrics.density 换算成像素。
 */
object PetConfig {

    // ========== 窗口尺寸 ==========

    /**
     * 宠物窗口尺寸（dp）。
     * 资产画布 240x280；相对原 150x175 再缩小 30% → 105x122.5，降低误触面积。
     */
    const val WINDOW_WIDTH_DP = 105f
    const val WINDOW_HEIGHT_DP = 122.5f

    /**
     * 点击热区相对窗口的内缩比例（每边）。
     * Lottie 画布四周有透明边，猫主体约居中 70%+；内缩后更接近「点到猫才响应」。
     */
    const val HIT_INSET_RATIO = 0.14f

    // ========== 拖动与贴边 ==========

    /** 松手时，距屏幕左/右边缘小于该阈值才触发贴边（dp）。不做上下贴边。 */
    const val SNAP_RELEASE_THRESHOLD_DP = 30f

    /**
     * 贴边后屏幕内窗口宽度（dp）。
     * 小于 dock 等比宽，靠墙一侧多裁掉一些身体，只多露一点头。
     */
    const val SNAP_HEAD_VISIBLE_DP = 48f

    /** blink_dock_* 画布尺寸（与自由态同高缩放时用于定宽） */
    const val DOCK_CANVAS_WIDTH = 168f
    const val DOCK_CANVAS_HEIGHT = 280f

    /**
     * 画布内容缩放（自由态与贴边共用，保证贴边猫体与自由态同大）。
     * 窗口已缩小 30%；此处再略收一点透明边，视觉更紧。
     */
    const val BLINK_SCALE_FACTOR = 0.85f

    /** 贴边位移动画时长（ms）。宽与 x 同步插值，到位后切烘焙 Lottie。 */
    const val SNAP_ANIM_DURATION_MS = 320L

    // ========== 定时器 ==========

    /** 随机动作最小/最大间隔（ms）。IDLE 自由态下循环调度。 */
    const val RANDOM_ACTION_MIN_MS = 12_000L
    const val RANDOM_ACTION_MAX_MS = 25_000L

    /**
     * 无用户操作后离场超时（ms）。
     * 需明显长于随机间隔上限，否则约 30s 就会离场贴边，自由态随机只能打出 1 次。
     */
    const val IDLE_EXIT_TIMEOUT_MS = 120_000L

    // ========== 动画资产路径 ==========

    const val ASSET_ENTER = "pet/enter.json"
    const val ASSET_BLINK = "pet/blink.json"
    /** 左贴边专用（由 blink 烘焙：已含镜像+倾角，运行时不再做 View 变换） */
    const val ASSET_BLINK_DOCK_LEFT = "pet/blink_dock_left.json"
    /** 右贴边专用（由 blink 烘焙：已含倾角） */
    const val ASSET_BLINK_DOCK_RIGHT = "pet/blink_dock_right.json"
    const val ASSET_WAVE = "pet/wave.json"
    const val ASSET_EXIT = "pet/exit.json"
    const val ASSET_SCRATCH_BUTT = "pet/scratch_butt.json"
    const val ASSET_SCRATCH_LEG = "pet/scratch_leg.json"

    // ========== 兜底屏幕尺寸 ==========

    const val FALLBACK_SCREEN_WIDTH_DP = 360
    const val FALLBACK_SCREEN_HEIGHT_DP = 800
}
