package com.pengxh.daily.app.shizuku

/**
 * Shizuku 通道统一能力接口（feat_shiziku 双通道）。
 *
 * 官方单通道编译时只编译 [OfficialShizukuChannel]；启用自定义 shizuku 时（CI 传
 * -PcustomShizukuPkg + customShizukuLib，由脚本基于模板生成 [CustomShizukuChannel]）
 * 额外编译自定义实现。上层 [ShizukuRuntime] 只按通道路由，不感知两个实现的差异。
 *
 * 约定：
 *  - [activeChannel]：当前生效通道标识（"shizuku-official" / "shizuku-custom" / "不可用"）。
 *  - [attach]：向 server 注册为 attached client；自定义通道必须成功 attach 后才能
 *    requestPermission（server 的 requestPermission 强制要求 clientRecord）。
 */
interface ShizukuChannel {

    /** 通道是否在线（server 可达） */
    fun isAvailable(): Boolean

    /** 是否已授予权限 */
    fun isGranted(): Boolean

    /** 当前生效通道：shizuku-official / shizuku-custom / 不可用 */
    fun activeChannel(): String

    /** 授权来源描述（由谁授权，未授权给出兜底文案） */
    fun grantSource(): String

    /** 通道对应的 server 进程名（如 shizuku_server / helper_server） */
    fun serverProcessName(): String

    /** 向 server 注册为 attached client；成功返回 true。官方通道一般为 true（库内自动注册）。 */
    fun attach(): Boolean

    /** 发起权限请求（唤起对应 shizuku 的授权确认） */
    fun requestPermission(requestCode: Int): Boolean

    /** 创建远程 shell 进程（命令以 sh -c 方式执行）；失败/未就绪返回 null */
    fun newProcess(cmd: Array<String>): Any?
}