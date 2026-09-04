package com.pengxh.daily.app.shizuku

import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import __CUSTOM_PKG__.server.IShizukuApplication
import __CUSTOM_PKG__.server.IShizukuService

/**
 * 自定义通道实现 —— 【模板生成文件，勿手改 / 勿提交】。
 *
 * 由生成脚本把 __CUSTOM_PKG__ 替换为 CI 传入的真实包名（示例 com.exmple.custom.shizuku）后产出
 * `app/build/generated/shizuku/.../CustomShizukuChannel.kt`，随工程编译。
 * 官方单通道时产物为桩版本（见 build.gradle / 生成脚本），本文件在启用自定义时才有完整逻辑。
 *
 * 与官方通道的同构差异点：
 *  - descriptor = __CUSTOM_PKG__.server.IShizukuService
 *  - 必须先 attachApplication 注册为 attached client，server 的 requestPermission 才放行
 *    （见根因：Service.requireClient 抛 "Not an attached client"）
 */
object CustomShizukuChannel : ShizukuChannel {

    private const val TAG = "CustomShizukuChannel"
    private val customPkg: String = "__CUSTOM_PKG__"
    private val descriptor: String = "$customPkg.server.IShizukuService"
    private val permission: String = "$customPkg.manager.permission.API_V23"

    @Volatile
    private var appContext: Context? = null

    /** 已成功 attach 的 binder（同上一个）；用于幂等 attach：同一 binder 只 attach 一次 */
    @Volatile
    private var attachedBinder: IBinder? = null

    /** 由 ShizukuRuntime.init 注入，供真实权限判断与 attach 参数使用 */
    fun init(context: Context?) {
        appContext = context?.applicationContext
    }

    /** 自定义 shizuku 是否在运行（重命名 server 已推送 binder 到 provider 槽） */
    override fun isAvailable(): Boolean =
        officialPing() && binderDescriptor() == descriptor

    /** 已真实授权：必须持有 <pkg>.manager.permission.API_V23（server 鉴权依据） */
    override fun isGranted(): Boolean {
        if (!isAvailable()) return false
        val ctx = appContext ?: return false
        return ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED
    }

    override fun activeChannel(): String = if (binderDescriptor() == descriptor) "shizuku-custom" else "不可用"

    override fun grantSource(): String = if (isGranted()) "Shizuku-Custom 授权" else "未授权"

    override fun serverProcessName(): String =
        if (isAvailable()) "${customPkg.substringAfterLast('.')}_server" else "未知"

    /** 向自定义 server 注册为 attached client（幂等：同一 binder 只 attach 一次，贴近官方 attach 语义）。
     *  失败时清空缓存，下次调用会重试。 */
    override fun attach(): Boolean {
        if (binderDescriptor() != descriptor) return false
        val binder = customBinder() ?: return false
        if (binder === attachedBinder) return true // 已 attach 过同一 binder
        val ctx = appContext ?: return false // 未完成 init 注入（异常关闭时序）时放弃，避免传空包名
        val ok = runCatching {
            val service = IShizukuService.Stub.asInterface(binder)
            val app = object : IShizukuApplication.Stub() {
                override fun bindApplication(data: Bundle?) {}
                override fun dispatchRequestPermissionResult(requestCode: Int, data: Bundle?) {}
                override fun showPermissionConfirmation(
                    requestUid: Int, requestPid: Int, requestPackageName: String?, requestCode: Int
                ) {}
            }
            val args = Bundle().apply {
                putString("shizuku:attach-package-name", ctx.packageName)
                putInt("shizuku:attach-api-version", 13)
            }
            service.attachApplication(app, args)
            true
        }.onFailure { Log.w(TAG, "自定义 Shizuku attachApplication 失败", it) }.getOrDefault(false)
        if (ok) attachedBinder = binder else attachedBinder = null
        return ok
    }

    override fun requestPermission(requestCode: Int): Boolean {
        if (binderDescriptor() != descriptor) return false
        // 必须先成为 attached client，server 的 requestPermission 才能通过 requireClient；
        // attach 失败直接返回 false，避免静默（上层据此提示用户）
        if (!attach()) return false
        return runCatching {
            val binder = customBinder() ?: return false
            IShizukuService.Stub.asInterface(binder).requestPermission(requestCode)
            true
        }.onFailure { Log.w(TAG, "自定义 Shizuku requestPermission 失败", it) }.getOrDefault(false)
    }

    override fun newProcess(cmd: Array<String>): Any? {
        if (!isGranted()) return null
        attach()
        return runCatching {
            val binder = customBinder() ?: return null
            IShizukuService.Stub.asInterface(binder).newProcess(cmd, null, null)
        }.onFailure { Log.w(TAG, "自定义 Shizuku newProcess 失败", it) }.getOrNull()
    }

    // ---------- 内部 ----------

    private fun officialPing(): Boolean = runCatching { rikka.shizuku.Shizuku.pingBinder() }.getOrDefault(false)

    private fun customBinder(): IBinder? = runCatching { rikka.shizuku.Shizuku.getBinder() }.getOrNull()

    private fun binderDescriptor(): String? =
        runCatching { customBinder()?.interfaceDescriptor }.getOrNull()
}