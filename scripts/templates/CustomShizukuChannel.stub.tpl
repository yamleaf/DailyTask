package com.pengxh.daily.app.shizuku

import android.content.Context

/**
 * 自定义通道实现 —— 【模板生成文件 · 官方单通道桩版本，勿提交】。
 *
 * 未启用自定义 shizuku（CUSTOM_SHIZUKU_PKG 为空）时由生成脚本产出本桩版本：
 * 类存在、全部能力 no-op，使上层 [ShizukuRuntime] 恒可稳定路由而不感知「官方/自定义」差异。
 */
object CustomShizukuChannel : ShizukuChannel {

    fun init(context: Context?) {}

    override fun isAvailable(): Boolean = false

    override fun isGranted(): Boolean = false

    override fun activeChannel(): String = "不可用"

    override fun grantSource(): String = "未授权"

    override fun serverProcessName(): String = "未知"

    override fun attach(): Boolean = false

    override fun requestPermission(requestCode: Int): Boolean = false

    override fun newProcess(cmd: Array<String>): Any? = null
}