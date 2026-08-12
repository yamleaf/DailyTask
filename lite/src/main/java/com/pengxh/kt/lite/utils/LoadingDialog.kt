@file:Suppress("DEPRECATION")

package com.pengxh.kt.lite.utils

import android.app.Activity
import android.app.ProgressDialog
import android.util.Log

/**
 * 全局加载框。底层仍用 ProgressDialog（API 已废弃），换 Material 实现会改视觉，
 * 在不影响功能的前提下仅抑制 deprecation 告警。
 */
object LoadingDialog {
    private const val kTag = "LoadingDialog"

    private var loadingDialog: ProgressDialog? = null

    /**
     * 显示加载对话框
     * @param activity 上下文Activity
     * @param message 提示消息
     */
    fun show(activity: Activity, message: String) {
        dismiss() // 先关闭已有的dialog
        if (!activity.isDestroyed && !activity.isFinishing) {
            try {
                loadingDialog = ProgressDialog(activity).apply {
                    setMessage(message)
                    setCancelable(false)
                    setCanceledOnTouchOutside(false)
                    show()
                }
            } catch (e: Exception) {
                Log.e(kTag, "show loading dialog failed", e)
            }
        }
    }

    /**
     * 显示带标题的加载对话框
     * @param activity 上下文Activity
     * @param title 标题
     * @param message 提示消息
     */
    fun show(activity: Activity, title: String, message: String) {
        dismiss() // 先关闭已有的dialog
        if (!activity.isDestroyed && !activity.isFinishing) {
            try {
                loadingDialog = ProgressDialog(activity).apply {
                    setTitle(title)
                    setMessage(message)
                    setCancelable(false)
                    setCanceledOnTouchOutside(false)
                    show()
                }
            } catch (e: Exception) {
                Log.e(kTag, "show loading dialog failed", e)
            }
        }
    }

    /**
     * 关闭加载对话框
     */
    fun dismiss() {
        loadingDialog?.takeIf { it.isShowing }?.dismiss()
        loadingDialog = null
    }
}