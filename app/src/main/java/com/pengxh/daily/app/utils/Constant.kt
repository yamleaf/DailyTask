package com.pengxh.daily.app.utils

import com.pengxh.kt.lite.utils.SaveKeyValues
import java.util.UUID

/**
 * @author: Pengxh
 * @email: 290677893@qq.com
 * @date: 2019/12/29 12:42
 */
object Constant {
    // SharedPreferences 键
    const val RESET_TIME_KEY = "RESET_TIME_KEY" // 任务重置时间点(Int)
    const val SCHEDULED_EXEC_TIME_KEY = "SCHEDULED_EXEC_TIME_KEY"
    const val STAY_OVERTIME_KEY = "STAY_OVERTIME_KEY" // 打卡停留在目标APP的时间(Int)
    const val TIME_RANGE_KEY = "TIME_RANGE_KEY" // 随机时间范围[0,range](Int)
    const val MSG_CHANNEL_KEY = "MSG_CHANNEL_KEY" // 消息渠道：0-邮件，1-企业微信(Int)
    const val TARGET_APP_KEY = "TARGET_APP_KEY" // 目标应用(Int, 0-3 内置; 100 表示自定义)
    /** 自定义目标应用包名白名单（逗号分隔存储） */
    const val CUSTOM_TARGET_APPS_KEY = "CUSTOM_TARGET_APPS_KEY"
    /** 当前选中的自定义目标包名（TARGET_APP_KEY == CUSTOM_TARGET_INDEX 时生效） */
    const val CUSTOM_TARGET_SELECTED_KEY = "CUSTOM_TARGET_SELECTED_KEY"
    /** 选中自定义目标应用的索引哨兵 */
    const val CUSTOM_TARGET_INDEX = 100

    const val REMOTE_COMMAND_KEY = "REMOTE_COMMAND_KEY" // 打卡远程消息指令(String)
    const val MESSAGE_TITLE_KEY = "MESSAGE_TITLE_KEY" // 打卡消息标题(String)
    const val WX_WEB_HOOK_KEY = "WX_WEB_HOOK_KEY" // 企业微信消息Key(String)
    /** 关闭反馈通知：开启后不再发送邮件/企业微信消息(Boolean, 默认 false) */
    const val FEEDBACK_NOTIFY_DISABLED_KEY = "FEEDBACK_NOTIFY_DISABLED_KEY" // Boolean
    const val CUSTOM_WORKDAYS_KEY = "CUSTOM_WORKDAYS_KEY" // 自定义工作日(String)

    const val GESTURE_DETECTOR_KEY = "GESTURE_DETECTOR_KEY" // 检测手势(Boolean)
    const val BACK_TO_HOME_KEY = "BACK_TO_HOME_KEY" // 返回桌面(Boolean)
    const val TASK_AUTO_RECYCLE_KEY = "TASK_AUTO_RECYCLE_KEY" // 任务每日自动循环(Boolean)
    const val RANDOM_TIME_KEY = "RANDOM_TIME_KEY" // 随机时间(Boolean)
    const val SKIP_HOLIDAY_KEY = "SKIP_HOLIDAY_KEY" // 跳过节假日(Boolean)
    const val NOTIFICATION_TRANSFER_KEY = "NOTIFICATION_TRANSFER_KEY" // 通知转移：将目标打卡应用通知经现有消息渠道(企业微信/邮箱)转发到目标手机(Boolean)
    const val POWER_SAVE_MODE_KEY = "POWER_SAVE_MODE_KEY" // 省电模式(Boolean)
    /** 强制伪息屏：离开 App 超过设定秒数主动盖黑屏蒙层 */
    const val FORCE_PSEUDO_MASK_KEY = "FORCE_PSEUDO_MASK_KEY" // Boolean
    /** 强制伪息屏：离开 App 多少秒后进入伪息屏（Int，秒，默认 60，范围 10~3600） */
    const val IDLE_PSEUDO_MASK_TIMEOUT_KEY = "IDLE_PSEUDO_MASK_TIMEOUT_KEY" // Int
    /** 伪息屏：关闭时钟显示（Boolean，默认 false=显示时钟）。开启后伪息屏只显示黑屏，更省电 */
    const val PSEUDO_MASK_NO_CLOCK_KEY = "PSEUDO_MASK_NO_CLOCK_KEY" // Boolean

    /** 后台保活：开机自启 + 进程被杀后由精确闹钟兜底重启前台服务(Boolean) */
    const val BACKGROUND_KEEP_ALIVE_KEY = "BACKGROUND_KEEP_ALIVE_KEY"

    // ===== 远程控制 MQTT 配置（被控端持久化；解绑时【不】清除，下次绑定无需重输）=====
    const val MQTT_BROKER_KEY = "MQTT_BROKER_KEY"       // EMQX broker 地址(含端口)，如 xxx.emqx.com:8883
    const val MQTT_USER_KEY = "MQTT_USER_KEY"           // 被控端 DEV 账户（设备自用，【不】进二维码）
    const val MQTT_PASS_KEY = "MQTT_PASS_KEY"           // 被控端 DEV 账户密码
    const val DEVICE_ID_KEY = "DEVICE_ID_KEY"           // 8 位设备 ID
    /** 控制端 CTL 账户用户名：默认由 App 生成（ctl-{deviceId}），可在「控制端凭证(ctl)」改为自定义；进绑定二维码；需在 EMQX 建同名受限账户 */
    const val MQTT_CTL_USER_KEY = "MQTT_CTL_USER_KEY"
    /** 控制端 CTL 账户密码：默认由 App 随机生成，可在「控制端凭证(ctl)」改为自定义；进绑定二维码；需在 EMQX 建同名受限账户 */
    const val MQTT_CTL_PASS_KEY = "MQTT_CTL_PASS_KEY"
    /** 远程控制服务总开关：默认关闭；开启且账号配置有效才连接 MQTT，关闭则完全停止服务（断开连接、撤销前台通知、零耗电） */
    const val MQTT_ENABLED_KEY = "MQTT_ENABLED_KEY"

    // ===== EMQX Serverless API 配置（被控端专用；用于 HTTP 方式管理/测试部署）=====
    const val MQTT_SERVERLESS_API_URL_KEY = "MQTT_SERVERLESS_API_URL_KEY" // 如 https://xxx.emqxsl.com/api/v5
    const val MQTT_SERVERLESS_API_APP_ID_KEY = "MQTT_SERVERLESS_API_APP_ID_KEY"
    const val MQTT_SERVERLESS_API_APP_SECRET_KEY = "MQTT_SERVERLESS_API_APP_SECRET_KEY"

    // ===== 临时公共 MQTT（EMQX 官方免费公共 broker，仅临时测试用）=====
    const val PUBLIC_MQTT_BROKER = "broker.emqx.io:1883"
    const val MQTT_USE_PUBLIC_KEY = "MQTT_USE_PUBLIC_KEY" // Boolean：当前是否使用临时公共 MQTT 配置

    // ===== 绑定态（解绑时清除；不含上面的 MQTT 配置）=====
    const val IS_BOUND_KEY = "IS_BOUND_KEY"                       // 是否已与控制端完成配对
    const val MQTT_SESSION_SECRET_KEY = "MQTT_SESSION_SECRET_KEY" // 配对后 HKDF 派生的会话密钥，运行时报文验签
    const val MQTT_PAIRING_TOKEN_KEY = "MQTT_PAIRING_TOKEN_KEY"   // 配对令牌（单次 / 60s），进二维码
    const val MQTT_PAIRING_EXPIRY_KEY = "MQTT_PAIRING_EXPIRY_KEY" // 配对令牌过期时间戳(ms)


    // Intent extra：远程息屏/亮屏（1=息屏，0=亮屏）
    const val EXTRA_MASK_COMMAND = "EXTRA_MASK_COMMAND"

    // 不导出的sp缓存
    const val LAST_RESET_DATE_KEY = "LAST_RESET_DATE_KEY"
    const val RESULT_SOURCE_KEY = "RESULT_SOURCE_KEY"
    /** 无障碍反馈模式：0=截屏反馈, 1=文本反馈 */
    const val ACCESSIBILITY_FEEDBACK_MODE_KEY = "ACCESSIBILITY_FEEDBACK_MODE_KEY"
    /** 电量低于 30% 是否已提醒过（回升到 30% 以上后清零） */
    const val LOW_BATTERY_NOTIFIED_KEY = "LOW_BATTERY_NOTIFIED_KEY"
    const val INSTALL_ID_KEY = "INSTALL_ID_KEY"

    // ConfigStore 键
    const val EMAIL_CONFIG_KEY = "emailConfig" // 邮箱配置

    // 目标应用
    const val DING_DING = "com.alibaba.android.rimet" // 钉钉
    const val WEWORK = "com.tencent.wework" // 企业微信
    const val FEI_SHU = "com.ss.android.lark" // 飞书
    const val MOBILE_M3 = "com.seeyon.cmp" // 移动办公M3

    // 消息指令
    const val COMMAND_PREFIX = "DT#" // 远程指令前缀，避免日常对话误触发
    const val WECHAT = "com.tencent.mm" // 微信
    const val QQ = "com.tencent.mobileqq" // QQ
    const val TIM = "com.tencent.tim" // TIM
    const val ZFB = "com.eg.android.AlipayGphone" // 支付宝

    // webhook
    const val WX_WEB_HOOK_URL = "https://qyapi.weixin.qq.com"

    // 其他默认值
    const val DEFAULT_INDEX = -1
    const val DEFAULT_RESET_HOUR = 0
    const val DEFAULT_TIME_RANGE = 5
    const val DEFAULT_OVER_TIME = 30
    const val CAPTURE_IMAGE_SERVICE_NOTIFICATION_ID = 1001
    const val FOREGROUND_RUNNING_SERVICE_NOTIFICATION_ID = 1002

    // 目标APP
    fun getTargetApp(): String {
        val index = SaveKeyValues.loadInt(TARGET_APP_KEY, 0)
        return if (index == CUSTOM_TARGET_INDEX) {
            val pkg = SaveKeyValues.loadString(CUSTOM_TARGET_SELECTED_KEY, "")
            if (pkg.isNotBlank()) pkg else DING_DING
        } else {
            when (index) {
                0 -> DING_DING
                1 -> WEWORK
                2 -> FEI_SHU
                3 -> MOBILE_M3
                else -> DING_DING
            }
        }
    }

    /** 内置目标应用：包名 + 显示名（固定顺序） */
    fun getBuiltInTargets(): List<Pair<String, String>> =
        listOf(
            DING_DING to "钉钉",
            WEWORK to "企业微信",
            FEI_SHU to "飞书",
            MOBILE_M3 to "移动办公M3"
        )

    /** 自定义目标应用包名列表（用户设置的白名单，运行时可变） */
    fun getCustomTargetApps(): List<String> {
        val raw = SaveKeyValues.loadString(CUSTOM_TARGET_APPS_KEY, "")
        if (raw.isBlank()) return emptyList()
        return raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
    }

    /** 目标应用选择列表（显示名）：内置在前，末尾追加“自定义应用”入口 */
    fun getTargetAppLabels(): List<String> =
        getBuiltInTargets().map { it.second } + listOf("自定义应用")

    /** 根据选择位置返回目标包名；<内置数量 为内置，==内置数量 时返回当前选中的自定义应用包名 */
    fun getTargetAppPackageByPosition(position: Int): String? {
        val builtin = getBuiltInTargets()
        return if (position in builtin.indices) {
            builtin[position].first
        } else if (position == builtin.size) {
            val pkg = SaveKeyValues.loadString(CUSTOM_TARGET_SELECTED_KEY, "")
            pkg.ifBlank { null }
        } else {
            null
        }
    }

    /** 当前选中目标在选择列表中的位置（用于 BottomSheet 回显 / 图标定位）。
     *  内置返回 0~3；选中自定义应用时返回“自定义应用”入口位置（= 内置数量）。 */
    fun getTargetAppPosition(): Int {
        val index = SaveKeyValues.loadInt(TARGET_APP_KEY, 0)
        if (index == CUSTOM_TARGET_INDEX) {
            return getBuiltInTargets().size
        }
        return index.coerceIn(0, 3)
    }

    fun getAppName(packageName: String): String {
        return when (packageName) {
            DING_DING -> "钉钉"
            WEWORK -> "企业微信"
            FEI_SHU -> "飞书"
            MOBILE_M3 -> "移动办公M3"
            else -> packageName
        }
    }

    private var cachedInstallId: String? = null
    fun getInstallId(): String {
        cachedInstallId?.let { return it }
        var id = SaveKeyValues.loadString(INSTALL_ID_KEY, "")
        if (id.isBlank()) {
            id = UUID.randomUUID().toString()
            SaveKeyValues.saveString(INSTALL_ID_KEY, id)
        }
        cachedInstallId = id
        return id
    }
}
