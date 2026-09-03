package com.pengxh.daily.app.shizuku

import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * 官方通道实现（始终编译）：描述符 `moe.shizuku.server.IShizukuService`，server 进程 `shizuku_server`。
 * 全部走官方 rikka.shizuku 库直调，无反射。attach 由官方库在 provider 绑定阶段自动完成，此处恒为 true。
 */
object OfficialShizukuChannel : ShizukuChannel {

    private const val TAG = "OfficialShizukuChannel"
    private const val DESCRIPTOR = "moe.shizuku.server.IShizukuService"

    override fun isAvailable(): Boolean =
        runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    override fun isGranted(): Boolean =
        runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)

    override fun activeChannel(): String = when (binderDescriptor()) {
        DESCRIPTOR -> "shizuku-official"
        else -> "不可用"
    }

    override fun grantSource(): String = if (isGranted()) "Shizuku 授权" else "未授权"

    override fun serverProcessName(): String = "shizuku_server"

    /** 官方库绑定后已 auto-attach，直接视为成功。 */
    override fun attach(): Boolean = true

    override fun requestPermission(requestCode: Int): Boolean = runCatching {
        val binder = Shizuku.getBinder() ?: return false
        moe.shizuku.server.IShizukuService.Stub.asInterface(binder).requestPermission(requestCode)
        true
    }.onFailure { Log.w(TAG, "官方 Shizuku requestPermission 失败", it) }.getOrDefault(false)

    override fun newProcess(cmd: Array<String>): Any? {
        if (!isGranted()) return null
        val binder = Shizuku.getBinder() ?: return null
        return runCatching {
            moe.shizuku.server.IShizukuService.Stub.asInterface(binder).newProcess(cmd, null, null)
        }.onFailure { Log.w(TAG, "官方 Shizuku newProcess 失败", it) }.getOrNull()
    }

    private fun binderDescriptor(): String? =
        runCatching { (Shizuku.getBinder() as IBinder).interfaceDescriptor }.getOrNull()
}