package com.pengxh.kt.lite.base

import android.content.Context

/**
 * 前台「无操作」计时桥接器。
 *
 * lite 模块不能反向依赖 app 模块，因此所有前台页面共用的基类（KotlinBaseActivity）
 * 在生命周期 / 用户交互时回调本桥，再由 app 模块在 DailyTaskApplication.onCreate 中
 * 把具体逻辑接到 IdlePseudoMaskController。
 *
 * 这样既能覆盖全部前台页面（任务页 / 远程页 / 设置页等），又不破坏模块依赖方向。
 */
object ForegroundIdleBridge {
    var onResume: ((Context) -> Unit)? = null
    var onPause: ((Context) -> Unit)? = null
    var onUserInteraction: ((Context) -> Unit)? = null
}
