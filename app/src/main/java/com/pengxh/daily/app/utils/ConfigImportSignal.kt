package com.pengxh.daily.app.utils

import android.content.Context
import android.content.Intent

/**
 * 配置变更信号：控制端远程推送配置后，通知宿主与各 Fragment 即时刷新。
 */
object ConfigImportSignal {

    const val ACTION_REMOTE_CONFIG_CHANGED = "com.pengxh.daily.action.REMOTE_CONFIG_CHANGED"

    /** 宿主 MainActivity 需要刷新任务列表 */
    @Volatile
    var pendingMainActivityRefresh: Boolean = false

    /** 设置页需要刷新 UI */
    @Volatile
    var pendingSettingsRefresh: Boolean = false

    /** 通知所有页面：远程配置已变更 */
    fun notifyRemoteChanged(context: Context) {
        pendingMainActivityRefresh = true
        pendingSettingsRefresh = true
        // 必须显式指定本包名：各页接收器以 RECEIVER_NOT_EXPORTED 注册，
        // Android 13+ 上隐式广播（不带包名）不会送达非导出接收器，导致页面不刷新
        context.sendBroadcast(Intent(ACTION_REMOTE_CONFIG_CHANGED).setPackage(context.packageName))
    }
}
