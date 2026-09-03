package com.pengxh.daily.app.shizuku

import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.content.ContextCompat
import com.pengxh.daily.app.BuildConfig
import rikka.shizuku.Shizuku

/**
 * Shizuku 执行能力抽象（feat_shiziku，双通道）：
 *  - 官方通道：rikka.shizuku 官方库（descriptor=moe.shizuku.server.IShizukuService）
 *  - 自定义通道：CI 传 -PcustomShizukuPkg 编译的配套 shizuku（descriptor=<自定义>.server.IShizukuService）
 *    —— 由「自定义 shizuku（我们编译）」向 DT 的 ShizukuProvider 推送自定义 descriptor 的 binder，
 *       DT 用官方 Shizuku.getBinder() 拿到它后，经反射按自定义包名 asInterface 调用，规避接口类冲突。
 *
 * 未启用自定义（BuildConfig.CUSTOM_SHIZUKU_PKG=''）时整段反射代码短路为桩，不触发任何类加载，
 * 仅官方通道；启用后官方优先、自定义兜底，运行时按实际安装的 shizuku 版本工作。
 */
object ShizukuRuntime {

    private const val TAG = "ShizukuRuntime"
    private val customPkg: String = BuildConfig.CUSTOM_SHIZUKU_PKG
    private var appContext: Context? = null

    /** 由 Application 在启动时注入，供自定义通道做真实权限判断（checkSelfPermission） */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Shizuku 服务是否在线（官方优先，自定义兜底） */
    fun isAvailable(): Boolean = officialAvailable() || customAvailable()

    /** 是否已授予权限（任一通道授权即视为可用） */
    fun isGranted(): Boolean = officialGranted() || customGranted()

    /**
     * 当前生效通道：shizuku-official / shizuku-custom，未就绪返回 "不可用"。
     * 依据 provider 槽里 binder 的 interfaceDescriptor 判断（官方=customPkg 为空或 moe.shizuku，
     * 自定义=本包推的 <customPkg>.server.IShizukuService）。
     */
    fun activeChannel(): String {
        val binder = runCatching { Shizuku.getBinder() }.getOrNull() ?: return "不可用"
        val desc = runCatching { binder.interfaceDescriptor }.getOrNull()
        return when (desc) {
            "$customPkg.server.IShizukuService" -> "shizuku-custom"
            "moe.shizuku.server.IShizukuService" -> "shizuku-official"
            else -> "不可用"
        }
    }

    /** 授权来源描述：由谁授权（配合通道展示，未授权/不可用给出兜底文案） */
    fun grantSource(): String = when {
        !isGranted() -> "未授权"
        activeChannel() == "shizuku-custom" -> "自定义 shizuku(root 放行)"
        activeChannel() == "shizuku-official" -> "Shizuku 授权"
        else -> "已授权"
    }

    /**
     * Shizuku 服务进程名（即"shizuku 的服务名"）：
     * 自定义通道 = <包名末段>_server（如 helper_server），官方通道 = shizuku_server。
     */
    fun serverProcessName(): String = when (activeChannel()) {
        "shizuku-custom" -> "${customPkg.substringAfterLast('.')}_server"
        "shizuku-official" -> "shizuku_server"
        else -> "未知"
    }

    /** 创建远程 shell 进程：官方优先，失败回退自定义；未就绪返回 null */
    fun newProcess(cmd: Array<String>): Any? =
        officialNewProcess(cmd) ?: customNewProcess(cmd)

    /**
     * 发起权限请求（按当前生效通道路由）：
     *  - 自定义通道：反射调用自定义 Stub 的 requestPermission（descriptor 一致，server 端才能通过
     *    enforceInterface 校验；若走官方 Shizuku.requestPermission 会因 descriptor 不匹配被 server
     *    抛 SecurityException 而无响应）
     *  - 官方通道：官方 rikka.shizuku 库
     */
    fun requestPermission(requestCode: Int): Boolean = when (activeChannel()) {
        "shizuku-custom" -> customRequestPermission(requestCode)
        "shizuku-official" -> runCatching {
            val binder = Shizuku.getBinder() ?: return false
            moe.shizuku.server.IShizukuService.Stub.asInterface(binder).requestPermission(requestCode)
            true
        }.onFailure { Log.w(TAG, "官方 Shizuku requestPermission 失败: ${it.message}") }.getOrDefault(false)
        else -> false
    }

    /** 读取远程进程 stdout 全部文本（官方/自定义 IRemoteProcess 均可用 getInputStream） */
    fun readOutput(process: Any): String? = runCatching {
        val pfd = process.javaClass.getMethod("getInputStream").invoke(process) as ParcelFileDescriptor
        ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrNull()

    /** 读取远程进程 stdout 全部字节（截图用） */
    fun readBytes(process: Any): ByteArray? = runCatching {
        val pfd = process.javaClass.getMethod("getInputStream").invoke(process) as ParcelFileDescriptor
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }.getOrNull()

    // ---------- 官方通道 ----------

    private fun officialAvailable(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    private fun officialGranted(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun officialNewProcess(cmd: Array<String>): Any? {
        if (!officialGranted()) return null
        val binder = Shizuku.getBinder() ?: return null
        return runCatching {
            moe.shizuku.server.IShizukuService.Stub.asInterface(binder).newProcess(cmd, null, null)
        }.getOrNull()
    }

    // ---------- 自定义通道（桩短路：customPkg 为空则全部 false/null） ----------

    private fun customEnabled(): Boolean = customPkg.isNotBlank()

    private fun customAvailable(): Boolean {
        if (!customEnabled()) return false
        return officialAvailable() && customBinder() != null
    }

    /**
     * 自定义通道是否已真实授权：仅收到 binder 不算，还必须拿到配套 shizuku 的
     * `<customPkg>.manager.permission.API_V23` 运行时权限（server 端 newProcess 的
     * checkCallingPermission 强制要求，见 ShizukuService.checkCallerPermission）。
     * 未授权时界面「申请授权」按钮应出现、相关卡片置灰，授权后方可执行。
     */
    private fun customGranted(): Boolean {
        if (!customEnabled()) return false
        if (!officialAvailable()) return false
        if (customBinder() == null) return false
        val ctx = appContext ?: return false
        return ContextCompat.checkSelfPermission(ctx, "$customPkg.manager.permission.API_V23") ==
            PackageManager.PERMISSION_GRANTED
    }

    /** 自定义 shizuku 推送到 DT 的 binder：复用官方 Shizuku.getBinder()（同一 provider 槽） */
    private fun customBinder(): IBinder? {
        if (!customEnabled()) return null
        return runCatching { Shizuku.getBinder() }.getOrNull()
    }

    private fun customNewProcess(cmd: Array<String>): Any? {
        if (!customGranted()) return null
        return runCatching {
            val binder = customBinder() ?: return null
            val stub = Class.forName("$customPkg.server.IShizukuService\$Stub")
            val service = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            service.javaClass.getMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
            ).invoke(service, cmd, null, null)
        }.onFailure { Log.w(TAG, "自定义 Shizuku 反射调用失败: ${it.message}") }.getOrNull()
    }

    /** 自定义通道发权限请求：反射自定义 Stub 的 requestPermission（descriptor 一致才能通过 server 校验） */
    private fun customRequestPermission(requestCode: Int): Boolean = runCatching {
        val binder = customBinder() ?: return false
        val stub = Class.forName("$customPkg.server.IShizukuService\$Stub")
        val service = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        service.javaClass.getMethod("requestPermission", Integer.TYPE).invoke(service, requestCode)
        true
    }.onFailure { Log.w(TAG, "自定义 Shizuku requestPermission 反射失败: ${it.message}") }.getOrDefault(false)
}