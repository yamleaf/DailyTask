package com.pengxh.daily.app

import android.app.Activity
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Android 13 模拟器未强制 edge-to-edge，decorFitsSystemWindows 默认 true 会把内容排在状态栏下方，
 * 导致透明状态栏露出窗口底色、渐变顶栏无法延伸到状态栏（与 DC 的 UiInsets 同款策略）。
 * 给顶栏 Toolbar 加 statusBars top padding，让渐变延伸到状态栏且标题不与系统时间重叠。
 */
object UiInsets {
    fun applyStatusBarPadding(activity: Activity, appBar: View) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        ViewCompat.setOnApplyWindowInsetsListener(appBar) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.updatePadding(top = top)
            insets
        }
    }
}
