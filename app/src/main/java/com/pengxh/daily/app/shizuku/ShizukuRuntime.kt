package com.pengxh.daily.app.shizuku

import android.content.Context
import android.os.ParcelFileDescriptor

/**
 * Shizuku 执行能力抽象（feat_shiziku，双通道路由层）。
 *
 * 差异实现收敛到两个 channel：
 *  - [OfficialShizukuChannel]：官方通道（descriptor=moe.shizuku.server.IShizukuService）
 *  - [CustomShizukuChannel]：自定义通道（由模板按 customShizukuPkg 构建期生成，零反射直调）
 *
 * 本层只按 [activeChannel] 路由，不感知两个实现差异；readOutput/readBytes 操作的是两个
 * 通道共用的 IRemoteProcess（getInputStream），与通道无关，故保留在此。
 *
 * 官方单通道编译（CUSTOM_SHIZUKU_PKG=''）时，CustomShizukuChannel 为桩（不可用），
 * 路由恒落官方通道，行为与旧版完全一致。
 */
object ShizukuRuntime {

    /** 由 Application 在启动时注入，转发给自定义通道做真实权限判断与 attach 参数 */
    fun init(context: Context) {
        CustomShizukuChannel.init(context)
    }

    /** Shizuku 服务是否在线（官方优先，自定义兜底） */
    fun isAvailable(): Boolean = OfficialShizukuChannel.isAvailable() || CustomShizukuChannel.isAvailable()

    /** 是否已授予权限（任一通道授权即视为可用） */
    fun isGranted(): Boolean = OfficialShizukuChannel.isGranted() || CustomShizukuChannel.isGranted()

    /**
     * 当前生效通道：shizuku-official / shizuku-custom，未就绪返回 "不可用"。
     * 官方优先，其次自定义（由各 channel 依据 provider 槽 binder 的 interfaceDescriptor 判定）。
     */
    fun activeChannel(): String {
        val official = OfficialShizukuChannel.activeChannel()
        if (official == "shizuku-official") return official
        return CustomShizukuChannel.activeChannel()
    }

    /** 授权来源描述（配合通道展示，未授权/不可用给出兜底文案） */
    fun grantSource(): String = when (activeChannel()) {
        "shizuku-official" -> OfficialShizukuChannel.grantSource()
        "shizuku-custom" -> CustomShizukuChannel.grantSource()
        else -> "不可用"
    }

    /** Shizuku 服务进程名（自定义=<末段>_server，官方=shizuku_server） */
    fun serverProcessName(): String = when (activeChannel()) {
        "shizuku-official" -> OfficialShizukuChannel.serverProcessName()
        "shizuku-custom" -> CustomShizukuChannel.serverProcessName()
        else -> "未知"
    }

    /** 创建远程 shell 进程：按当前通道路由；未就绪返回 null */
    fun newProcess(cmd: Array<String>): Any? = when (activeChannel()) {
        "shizuku-official" -> OfficialShizukuChannel.newProcess(cmd)
        "shizuku-custom" -> CustomShizukuChannel.newProcess(cmd)
        else -> null
    }

    /** 发起权限请求：按当前通道路由，唤起对应 shizuku 的授权确认 */
    fun requestPermission(requestCode: Int): Boolean = when (activeChannel()) {
        "shizuku-official" -> OfficialShizukuChannel.requestPermission(requestCode)
        "shizuku-custom" -> CustomShizukuChannel.requestPermission(requestCode)
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

}