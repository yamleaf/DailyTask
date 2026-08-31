package com.pengxh.daily.app.shizuku

import android.app.Activity
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * Shizuku 运行时状态与授权管理（feat_shiziku，独立包）。
 * 不触碰现有无障碍链路；未授权时相关能力一律置灰降级。
 */
object ShizukuManager {
    const val REQUEST_PERMISSION_CODE = 0x5A1E

    /** Shizuku 服务是否在线（adb 激活或 root 模式） */
    fun isAvailable(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /** 是否已授予权限 */
    fun isGranted(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** 完整状态描述：不可用 / 可用未授权 / 可用已授权 */
    fun statusText(): String = when {
        !isAvailable() -> "不可用"
        !isGranted() -> "未授权"
        else -> "已授权"
    }

    fun requestPermission(activity: Activity) {
        if (!isAvailable()) return
        if (!isGranted()) {
            runCatching { Shizuku.requestPermission(REQUEST_PERMISSION_CODE) }
        }
    }
}
