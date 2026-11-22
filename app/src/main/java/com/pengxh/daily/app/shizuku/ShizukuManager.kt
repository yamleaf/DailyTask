package com.pengxh.daily.app.shizuku

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.pengxh.daily.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Shizuku 运行时状态与授权管理（feat_shiziku，独立包）。
 * 不触碰现有无障碍链路；未授权时相关能力一律置灰降级。
 */
object ShizukuManager {
    const val REQUEST_PERMISSION_CODE = 0x5A1E

    private const val TAG = "ShizukuManager"

    /**
     * 远程激活标记：DT 发广播时带上后，Shizuku fork 会走「无人值守」启动分支，
     * 跳过锁屏等待并立即尝试重新打开无线调试 / mDNS 发现。字面值须与 Shizuku fork
     * 的 WirelessBootStartWorker.KEY_REMOTE_ACTIVATION 保持一致。
     */
    private const val EXTRA_REMOTE_ACTIVATION = "remote_activation"

    private const val DEFAULT_REMOTE_START_TIMEOUT_MS = 30_000L

    /** 串行化远程激活，避免 DC 连续下发时多条广播/多个 WorkManager 任务互相打断 */
    private val activationMutex = Mutex()

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

    /** 远程激活的最终回执码；冒号前给 DC 做分支，冒号后给失败原因/成功来源 */
    enum class RemoteStartAck(val wire: String) {
        ALREADY_AVAILABLE("START_OK:ALREADY"),
        STARTED("START_OK:BINDER"),
        NO_MANAGER("START_FAIL:NO_MANAGER"),
        BROADCAST_FAILED("START_FAIL:BROADCAST"),
        TIMEOUT("START_FAIL:TIMEOUT")
    }

    /**
     * 远程激活 Shizuku：向已安装的 Shizuku manager 发显式启动广播。
     *
     * 这是「只投递一次」的底层函数；无人值守路径请用 [requestRemoteStartAndWait]。
     *
     * @param remoteActivation true 时附加快照标记，Shizuku fork 会跳过锁屏等待并立即尝试无线调试发现；
     *                         false 保持旧行为，兼容普通手动/历史调用。
     * @return true 表示至少向一个已安装的 manager 成功投递了广播（不代表 server 必然启动成功）。
     */
    private fun sendStartBroadcastOnce(context: Context, remoteActivation: Boolean): Boolean {
        var sent = false
        for (pkg in candidatePackages()) {
            val installed = runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
            if (!installed) {
                Log.d(TAG, "requestRemoteStart 跳过未安装的 $pkg")
                continue
            }
            val ok = runCatching {
                val intent = Intent("$pkg.START").setPackage(pkg)
                    .putExtra(EXTRA_REMOTE_ACTIVATION, remoteActivation)
                    // 被控端重启/进程被停掉后仍能唤醒 receiver；MIUI 侧仍依赖 10008 自启动权限
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
                context.sendBroadcast(intent)
                true
            }.getOrDefault(false)
            if (ok) {
                sent = true
                Log.i(TAG, "已向 $pkg 发送启动广播 $pkg.START（remote=$remoteActivation）")
            }
        }
        if (!sent) {
            Log.w(TAG, "requestRemoteStart 未找到已安装的 manager，候选包: ${candidatePackages()}")
        }
        return sent
    }

    /** 兼容旧调用：只投递广播，不等待服务启动 */
    fun requestRemoteStart(context: Context): Boolean = sendStartBroadcastOnce(context, false)

    /**
     * 无人值守远程激活：发广播后等待 Shizuku binder 真正出现再返回 ACK。
     *
     * DC 下发 FIELD_SHIZUKU_START 后调用；成功条件从「广播发出」升级为「binder 可用」，
     * 避免 DT 回 START_SENT、DC 显示成功但实际服务没起的误报。
     *
     * 串行化 + withTimeout 保证：
     *  - 连续下发不会并发启动多个 WorkManager 任务；
     *  - 不阻塞 MQTT 回调线程（调用方在 service scope 内 launch）；
     *  - 超时后仍回明确失败，由 DC 提示检查自启动/无线调试。
     */
    suspend fun requestRemoteStartAndWait(
        context: Context,
        timeoutMs: Long = DEFAULT_REMOTE_START_TIMEOUT_MS
    ): RemoteStartAck = withContext(Dispatchers.IO) {
        activationMutex.withLock {
            if (isAvailable()) {
                Log.i(TAG, "远程激活跳过：Shizuku binder 已可用")
                return@withLock RemoteStartAck.ALREADY_AVAILABLE
            }

            val sent = sendStartBroadcastOnce(context.applicationContext, remoteActivation = true)
            if (!sent) {
                return@withLock RemoteStartAck.NO_MANAGER
            }

            val started = withTimeoutOrNull(timeoutMs) {
                while (!isAvailable()) {
                    delay(300L)
                }
                true
            } == true

            if (started) {
                Log.i(TAG, "远程激活成功：已观测到 Shizuku binder")
                RemoteStartAck.STARTED
            } else {
                Log.w(TAG, "远程激活超时：$timeoutMs ms 内未观测到 Shizuku binder")
                RemoteStartAck.TIMEOUT
            }
        }
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
