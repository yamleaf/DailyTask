package com.pengxh.daily.app.shizuku

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.SecurePrefs
import com.pengxh.kt.lite.utils.SaveKeyValues

/** 登录方式：密码登录 / 验证码登录（当前生效方式；两种方式配置可同时维护） */
enum class ShizukuLoginMethod { PASSWORD, VERIFY_CODE }

/**
 * 步骤类型：
 *  - CLICK：直接点击指定坐标（**纯坐标，无文字查找回退**）
 *  - SWIPE：弧线滑动（起点 x/y → 终点 x2/y2）；轨迹用贝塞尔分段插值接近真实手滑，终点精确不偏离
 *  - PWD_INPUT：向指定坐标输入框回填密码（须先点选聚焦坐标；中文经剪贴板粘贴输入）
 *  - CODE_INPUT：向指定坐标输入框回填短信验证码（同样支持中文/符号，走剪贴板粘贴）
 *  - CODE_CAPTURE：钉钉等需「通知用户发短信」的采集步骤：
 *       优先识别短信内容+收件人上报控制端 → 用户发送后确认 FIELD_SMS_SENT；
 *       识别失败回退截图（screenshot）经消息渠道发送给用户，由用户自行发送短信。
 *  - RESULT_CHECK：结果判定（截图回传人工确认，**只能作为最后一个步骤**）：
 *       截图经消息渠道（邮箱/企微附件）回传用户 → alert 请求控制端人工确认；
 *       用户看截图后点「成功/失败」→ FIELD_RESULT_CONFIRM 回传 → 判定登录/验证成败。
 */
enum class ShizukuStepType { CLICK, SWIPE, PWD_INPUT, CODE_INPUT, CODE_CAPTURE, RESULT_CHECK }

/**
 * 登录/验证操作的一步（feat_shiziku）。
 * **纯坐标执行**：所有点击/输入类步骤都必须有坐标（执行器对无坐标步骤直接判失败）；
 * [buttonText] 仅为「步骤备注/标签」，用于在设置界面标识这一步是干什么的（如「点登录」「填密码」），
 * **不参与任何查找定位**——识别按钮统一靠采集的坐标，逐机不同，需在高级设置里「从当前屏幕采集坐标」。
 * SWIPE 类型：x/y 为起点，x2/y2 为终点（均须 >=0）。
 */
data class ShizukuStep(
    val type: ShizukuStepType = ShizukuStepType.CLICK,
    /** 步骤备注/标签（仅展示用，不参与点击定位；JSON 字段 t 保持兼容） */
    val buttonText: String = "",
    val x: Int = -1,
    val y: Int = -1,
    val x2: Int = -1,
    val y2: Int = -1,
    val range: Int = 0,
    /** 本步执行前等待秒数范围（min~max，0.1s 步进随机）：0-5 表示 0~5s 内随机，1-1 固定 1s；
     *  均 0=未配置用默认（第一步 5s，其余 3s；多格验证码第 2+ 格 1s） */
    val delayMin: Int = 0,
    val delayMax: Int = 0,
    /** CODE_CAPTURE 专用：收件人关键字（空=内置手机号正则）；用于定位含收件人手机号的文本行 */
    val kw1: String = "",
    /** CODE_CAPTURE 专用：内容关键字（空=内置「验证码/短信」）；用于定位短信正文文本行 */
    val kw2: String = ""
) {
    val hasCoord: Boolean get() = x >= 0 && y >= 0
    /** SWIPE 需终点坐标；其余类型只看起点 */
    val hasEndpoint: Boolean get() = x2 >= 0 && y2 >= 0
    val hasRange: Boolean get() = range > 0
    /** 是否配置了有效延迟范围（min>0 或 max>0） */
    val hasDelay: Boolean get() = delayMin > 0 || delayMax > 0

    fun toJson(): JsonObject = JsonObject().apply {
        if (type != ShizukuStepType.CLICK) addProperty("ty", type.name)
        addProperty("t", buttonText)
        if (hasCoord) {
            addProperty("x", x)
            addProperty("y", y)
        }
        if (hasEndpoint) {
            addProperty("x2", x2)
            addProperty("y2", y2)
        }
        if (hasRange) addProperty("r", range)
        if (hasDelay) {
            addProperty("d1", delayMin)
            addProperty("d2", delayMax)
        }
        if (kw1.isNotBlank()) addProperty("k1", kw1)
        if (kw2.isNotBlank()) addProperty("k2", kw2)
    }

    companion object {
        /** 兼容旧数据：无 ty 一律视为 CLICK（旧数据仅有 t/x/y）；旧 d 字段（固定秒）迁移为 d1=d2=d */
        fun fromJson(o: JsonObject): ShizukuStep = ShizukuStep(
            type = runCatching { ShizukuStepType.valueOf(o.get("ty")?.asString ?: "CLICK") }
                .getOrDefault(ShizukuStepType.CLICK),
            buttonText = o.get("t")?.asString ?: "",
            x = o.get("x")?.asInt ?: -1,
            y = o.get("y")?.asInt ?: -1,
            x2 = o.get("x2")?.asInt ?: -1,
            y2 = o.get("y2")?.asInt ?: -1,
            range = o.get("r")?.asInt ?: 0,
            delayMin = o.get("d1")?.asInt ?: (o.get("d")?.asInt ?: 0),
            delayMax = o.get("d2")?.asInt ?: (o.get("d")?.asInt ?: 0),
            kw1 = o.get("k1")?.asString ?: "",
            kw2 = o.get("k2")?.asString ?: ""
        )
    }
}

/**
 * Shizuku 高级功能配置存储（feat_shiziku，独立新键，与现有配置零交集）。
 *
 * - 非敏感配置走 SaveKeyValues；密码走 SecurePrefs（Keystore AES256-GCM 加密），
 *   只在被控端高级设置界面可显隐，**永不进快照 / 协议 / 日志**。
 * - 控制端镜像下发的配置（FIELD_SHIZUKU_CONFIG）只接受非密码字段。
 * - 登录 / 身份验证 / 模拟打卡步骤均无内置默认：全坐标采集，坐标逐机不同，
 *   未配置时执行器直接反馈「步骤未配置」，需用户在高级设置自行采集坐标。
 */
object ShizukuConfigStore {
    private const val KEY_METHOD = "shizuku_login_method"
    private const val KEY_PASSWORD = "shizuku_login_password"
    private const val KEY_PWD_STEPS = "shizuku_pwd_login_steps"
    private const val KEY_VERIFY_STEPS = "shizuku_verify_login_steps"
    private const val KEY_VERIFY_WAIT = "shizuku_verify_wait_sec"
    private const val KEY_AUTH_STEPS = "shizuku_auth_steps"
    private const val KEY_AUTH_WAIT = "shizuku_auth_wait_sec"
    private const val KEY_PUNCH_STEPS = "shizuku_punch_steps"

    // ---- 高级功能：Shizuku 已授权即默认开启，无独立开关 ----
    fun isEnabled(): Boolean = ShizukuManager.isGranted()

    // ---- 登录方式（当前生效方式，控制端可切换；两种配置可同时维护）----
    fun loginMethod(): ShizukuLoginMethod =
        if (SaveKeyValues.loadString(KEY_METHOD, "") == ShizukuLoginMethod.VERIFY_CODE.name) {
            ShizukuLoginMethod.VERIFY_CODE
        } else {
            ShizukuLoginMethod.PASSWORD
        }
    fun setLoginMethod(m: ShizukuLoginMethod) = SaveKeyValues.saveString(KEY_METHOD, m.name)

    // ---- 密码（仅 SecurePrefs 加密存储，仅被控端本地可读）----
    fun password(): String = SecurePrefs.getString(KEY_PASSWORD)
    fun setPassword(p: String) = SecurePrefs.putString(KEY_PASSWORD, p)
    fun hasPassword(): Boolean = password().isNotBlank()

    // ---- 步骤序列化 ----
    private fun encodeSteps(steps: List<ShizukuStep>): String =
        JsonArray().apply { steps.forEach { add(it.toJson()) } }.toString()

    /** 步骤展示串（备注优先；无备注回退类型占位），供控制端镜像只读展示；空列表返回 "" */
    private fun stepsLabel(steps: List<ShizukuStep>): String {
        if (steps.isEmpty()) return ""
        return steps.mapIndexed { i, s ->
            val label = s.buttonText.ifBlank {
                when (s.type) {
                    ShizukuStepType.CLICK -> "坐"
                    ShizukuStepType.SWIPE -> "滑"
                    ShizukuStepType.PWD_INPUT -> "密"
                    ShizukuStepType.CODE_INPUT -> "码"
                    ShizukuStepType.CODE_CAPTURE -> "采"
                    ShizukuStepType.RESULT_CHECK -> "结"
                }
            }
            "$label${i + 1}"
        }.joinToString("->")
    }

    private fun decodeSteps(raw: String): List<ShizukuStep> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            JsonParser.parseString(raw).asJsonArray
                .mapNotNull { runCatching { it.asJsonObject }.getOrNull() }
                .map { ShizukuStep.fromJson(it) }
                .filter {
                    it.hasCoord || it.hasEndpoint || it.buttonText.isNotBlank() || it.kw1.isNotBlank() || it.kw2.isNotBlank() ||
                        it.type == ShizukuStepType.CODE_CAPTURE || it.type == ShizukuStepType.RESULT_CHECK
                }
        }.getOrDefault(emptyList())
    }

    // ---- 密码登录步骤 / 验证码登录步骤（分开维护；坐标逐机采集，无内置默认）----
    fun pwdLoginSteps(): List<ShizukuStep> =
        decodeSteps(SaveKeyValues.loadString(KEY_PWD_STEPS, ""))
    fun setPwdLoginSteps(steps: List<ShizukuStep>) = SaveKeyValues.saveString(KEY_PWD_STEPS, encodeSteps(steps))

    fun verifyLoginSteps(): List<ShizukuStep> =
        decodeSteps(SaveKeyValues.loadString(KEY_VERIFY_STEPS, ""))
    fun setVerifyLoginSteps(steps: List<ShizukuStep>) = SaveKeyValues.saveString(KEY_VERIFY_STEPS, encodeSteps(steps))

    /** 当前登录方式对应的生效步骤，执行器统一走这里 */
    fun loginSteps(): List<ShizukuStep> =
        if (loginMethod() == ShizukuLoginMethod.PASSWORD) pwdLoginSteps() else verifyLoginSteps()

    /** 身份验证步骤（坐标逐机采集，无内置默认） */
    fun authSteps(): List<ShizukuStep> =
        decodeSteps(SaveKeyValues.loadString(KEY_AUTH_STEPS, ""))
    fun setAuthSteps(steps: List<ShizukuStep>) = SaveKeyValues.saveString(KEY_AUTH_STEPS, encodeSteps(steps))

    /** 模拟打卡步骤（坐标逐机采集，无内置默认；控制端「模拟打卡」动作走这里） */
    fun punchSteps(): List<ShizukuStep> =
        decodeSteps(SaveKeyValues.loadString(KEY_PUNCH_STEPS, ""))
    fun setPunchSteps(steps: List<ShizukuStep>) = SaveKeyValues.saveString(KEY_PUNCH_STEPS, encodeSteps(steps))

    // ---- 验证码等待超时（秒，10~600）----
    fun verifyWaitSeconds(): Int = SaveKeyValues.loadInt(KEY_VERIFY_WAIT, 120)
    fun setVerifyWaitSeconds(v: Int) = SaveKeyValues.saveInt(KEY_VERIFY_WAIT, v.coerceIn(10, 600))
    fun authWaitSeconds(): Int = SaveKeyValues.loadInt(KEY_AUTH_WAIT, 120)
    fun setAuthWaitSeconds(v: Int) = SaveKeyValues.saveInt(KEY_AUTH_WAIT, v.coerceIn(10, 600))

    /** 快照上报摘要（**不含密码明文**）：供控制端镜像展示 */
    fun summaryJson(): JsonObject = JsonObject().apply {
        addProperty("enabled", isEnabled())
        addProperty("method", loginMethod().name)
        addProperty("pwdSteps", pwdLoginSteps().size)
        addProperty("verifySteps", verifyLoginSteps().size)
        addProperty("authStepsCount", authSteps().size)
        addProperty("punchSteps", punchSteps().size)
        addProperty("pwdStepsLabel", stepsLabel(pwdLoginSteps()))
        addProperty("verifyStepsLabel", stepsLabel(verifyLoginSteps()))
        addProperty("authStepsLabel", stepsLabel(authSteps()))
        addProperty("punchStepsLabel", stepsLabel(punchSteps()))
        addProperty("hasPassword", hasPassword())
        addProperty("verifyWait", verifyWaitSeconds())
        addProperty("authWait", authWaitSeconds())
    }

    /** 控制端镜像下发应用（FIELD_SHIZUKU_CONFIG）：仅接受非密码字段；步骤由被控端本地维护 */
    fun applyRemote(json: String) {
        if (json.isBlank()) return
        runCatching {
            val o = JsonParser.parseString(json).asJsonObject
            // enabled 字段为只读镜像（授权即生效），不再接受下发
            if (o.has("method")) {
                val m = runCatching { ShizukuLoginMethod.valueOf(o.get("method").asString) }
                    .getOrDefault(ShizukuLoginMethod.PASSWORD)
                setLoginMethod(m)
            }
            if (o.has("verifyWait")) setVerifyWaitSeconds(o.get("verifyWait").asInt)
            if (o.has("authWait")) setAuthWaitSeconds(o.get("authWait").asInt)
            // 注意：password 字段绝不接收——密码仅被控端本地设置，公共 Broker 不明文传输；
            // loginSteps/authSteps 亦由被控端本地维护（控制端只读展示，不下发）
        }
    }
}