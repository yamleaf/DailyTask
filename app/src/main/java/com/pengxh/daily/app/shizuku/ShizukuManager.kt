package com.pengxh.daily.app.shizuku

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.pengxh.daily.app.BuildConfig

/**
 * Shizuku 运行时状态与授权管理（feat_shiziku，独立包）。
 * 不触碰现有无障碍链路；未授权时相关能力一律置灰降级。
 */
object ShizukuManager {
    const val REQUEST_PERMISSION_CODE = 0x5A1E

    private const val TAG = "ShizukuManager"

    /**
     * 官方 Shizuku manager 的**安装包名（applicationId）**：moe.shizuku.privileged.api。
     * 注意不是源码 namespace / 库包名 moe.shizuku.manager——那里只是代码目录，设备上装的 app 叫前者。
     * 写成 namespace 会导致 getPackageInfo 永远查不到，远程激活直接回 START_FAIL。
     */
    const val OFFICIAL_MANAGER_PKG = "moe.shizuku.privileged.api"

    /**
     * 候选 Shizuku manager 包名列表：双通道编译下用户可能只装其中一个。
     * 自定义（若启用）优先 + 官方兜底；远程激活时对已安装者逐个发广播（谁装了谁收到，未装者无害落空）。
     */
    private fun candidatePackages(): List<String> = buildList {
        if (BuildConfig.CUSTOM_SHIZUKU_PKG.isNotBlank()) add(BuildConfig.CUSTOM_SHIZUKU_PKG)
        add(OFFICIAL_MANAGER_PKG)
    }.distinct()

    /** Shizuku 服务是否在线（官方优先，自定义兜底） */
    fun isAvailable(): Boolean = ShizukuRuntime.isAvailable()

    /** 是否已授予权限（任一通道可用即视为已授权） */
    fun isGranted(): Boolean = ShizukuRuntime.isGranted()

    /** 当前生效通道：shizuku-official / shizuku-custom / 不可用 */
    fun channelLabel(): String = ShizukuRuntime.activeChannel()

    /** 授权来源：由谁授权 */
    fun grantSource(): String = ShizukuRuntime.grantSource()

    /**
     * 远程激活 Shizuku：向前台 Shizuku manager 发显式启动广播，让它在无用户手动点击的情况下
     * 自行拉起 shizuku_server（无线调试 / root 通道，取决于其最后一次启动方式）。
     *
     * 由控制端下发 FIELD_SHIZUKU_START 触发；用于被控端重启后 Shizuku 服务掉线、控制端远程补救的场景。
     * 广播 action 采用 ${package}.START（Shizuku fork 的 ManualStartReceiver 声明并 exported=true），
     * 显式 setPackage 保证只投递给目标 manager，不被其他应用截胡。
     *
     * 双通道：同时尝试自定义 + 官方两个包名（用户可能只装其中一个），谁装了谁收到。
     *
     * @return true 表示至少向一个已安装的 manager 成功投递了广播（不代表 server 必然启动成功，
     *         无线调试配对缺失/未解锁时可能失败）；false 表示两个候选 manager 都未安装。
     */
    fun requestRemoteStart(context: Context): Boolean {
        var sent = false
        for (pkg in candidatePackages()) {
            val installed = runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
            if (!installed) {
                Log.d(TAG, "requestRemoteStart 跳过未安装的 $pkg")
                continue
            }
            val ok = runCatching {
                val intent = Intent("$pkg.START").setPackage(pkg)
                context.sendBroadcast(intent)
                true
            }.getOrDefault(false)
            if (ok) {
                sent = true
                Log.i(TAG, "已向 $pkg 发送启动广播 $pkg.START")
            }
        }
        if (!sent) {
            Log.w(TAG, "requestRemoteStart 未找到已安装的 manager，候选包: ${candidatePackages()}")
        }
        return sent
    }

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
