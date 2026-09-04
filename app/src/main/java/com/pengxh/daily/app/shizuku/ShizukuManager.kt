package com.pengxh.daily.app.shizuku

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.widget.Toast

/**
 * Shizuku 运行时状态与授权管理（feat_shiziku，独立包）。
 * 不触碰现有无障碍链路；未授权时相关能力一律置灰降级。
 */
object ShizukuManager {
    const val REQUEST_PERMISSION_CODE = 0x5A1E

    /** Shizuku 服务是否在线（官方优先，自定义兜底） */
    fun isAvailable(): Boolean = ShizukuRuntime.isAvailable()

    /** 是否已授予权限（任一通道可用即视为已授权） */
    fun isGranted(): Boolean = ShizukuRuntime.isGranted()

    /** 当前生效通道：shizuku-official / shizuku-custom / 不可用 */
    fun channelLabel(): String = ShizukuRuntime.activeChannel()

    /** 授权来源：由谁授权 */
    fun grantSource(): String = ShizukuRuntime.grantSource()

    /**
     * 完整状态描述：不可用 / 可用未授权 / 可用已授权
     */
    fun statusText(): String = when {
        !isAvailable() -> "不可用"
        !isGranted() -> "未授权"
        else -> "已授权"
    }

    /**
     * 环境状态明细（简单实现）：开发者选项 / 无线调试读 Settings.Global；
     * Shizuku 服务进程名由通道推导。所有系统设置读取均容错（不可解析/缺失归为默认）。
     */
    suspend fun environment(context: Context): ShizukuEnv {
        val cr = context.contentResolver
        // getInt 对非整数存储值会抛 NumberFormatException，统一用 getString + 字符串比较，杜绝崩溃
        val devOn = runCatching {
            Settings.Global.getString(cr, "development_settings_enabled") == "1"
        }.getOrDefault(false)
        val wifi = runCatching { Settings.Global.getString(cr, "adb_wifi_enabled") }.getOrNull()
        val usb = runCatching { Settings.Global.getString(cr, "adb_enabled") }.getOrNull()
        return ShizukuEnv(
            shizukuServer = ShizukuRuntime.serverProcessName(),
            devOpt = if (devOn) "已开启" else "已关闭",
            wirelessAdb = when (wifi) { "1" -> "已开启"; "0" -> "已关闭"; else -> "N/A" },
            adbUsb = when (usb) { "1" -> "已开启"; "0" -> "已关闭"; else -> "N/A" }
        )
    }

    fun requestPermission(activity: Activity) {
        if (!isAvailable()) {
            Toast.makeText(activity, "Shizuku 服务不可用，请先启动 Shizuku", Toast.LENGTH_SHORT).show()
            return
        }
        if (!isGranted()) {
            // 按当前生效通道路由：自定义通道反射调自定义 Stub.requestPermission（descriptor 匹配才能
            // 通过 server 校验），官方通道走 rikka.shizuku 官方库，均由对应 shizuku 唤起授权确认。
            val ok = runCatching { ShizukuRuntime.requestPermission(REQUEST_PERMISSION_CODE) }.getOrDefault(false)
            if (!ok) {
                Toast.makeText(activity, "授权请求发起失败，请重启 Shizuku 服务后重试", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

/** Shizuku 环境明细（服务权限状态卡行式展示） */
data class ShizukuEnv(
    val shizukuServer: String, // Shizuku 服务进程名（helper_server / shizuku_server / 未知）
    val devOpt: String,        // 已开启 / 已关闭
    val wirelessAdb: String,   // 已开启 / 已关闭 / N/A
    val adbUsb: String         // 已开启 / 已关闭 / N/A（USB 调试总开关 adb_enabled）
)
