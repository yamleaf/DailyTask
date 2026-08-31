package com.pengxh.daily.app.shizuku

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.pengxh.daily.app.utils.SecurePrefs
import com.pengxh.kt.lite.utils.SaveKeyValues

/** 登录方式：密码登录 / 验证码登录（二选一） */
enum class ShizukuLoginMethod { PASSWORD, VERIFY_CODE }

/** 登录/验证操作的一步：按钮文字，用于 uiautomator dump 后识别查找按钮所在 */
data class ShizukuStep(val buttonText: String) {
    fun toJson(): JsonObject = JsonObject().apply { addProperty("t", buttonText) }
    companion object {
        fun fromJson(o: JsonObject): ShizukuStep = ShizukuStep(o.get("t")?.asString ?: "")
    }
}

/**
 * Shizuku 高级功能配置存储（feat_shiziku，独立新键，与现有配置零交集）。
 *
 * - 非敏感配置走 SaveKeyValues；密码走 SecurePrefs（Keystore AES256-GCM 加密），
 *   只在被控端高级设置界面可显隐，**永不进快照 / 协议 / 日志**。
 * - 控制端镜像下发的配置（FIELD_SHIZUKU_CONFIG）只接受非密码字段。
 */
object ShizukuConfigStore {
    private const val KEY_ENABLED = "shizuku_feature_enabled"
    private const val KEY_METHOD = "shizuku_login_method"
    private const val KEY_PASSWORD = "shizuku_login_password"
    private const val KEY_LOGIN_STEPS = "shizuku_login_steps"
    private const val KEY_VERIFY_WAIT = "shizuku_verify_wait_sec"
    private const val KEY_AUTH_STEPS = "shizuku_auth_steps"
    private const val KEY_AUTH_WAIT = "shizuku_auth_wait_sec"

    // ---- 高级功能总开关（仅 Shizuku 可用 + 已授权时可开启）----
    fun isEnabled(): Boolean = SaveKeyValues.loadBoolean(KEY_ENABLED, false)
    fun setEnabled(v: Boolean) = SaveKeyValues.saveBoolean(KEY_ENABLED, v)

    // ---- 登录方式（二选一）----
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

    private fun decodeSteps(raw: String): List<ShizukuStep> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            JsonParser.parseString(raw).asJsonArray
                .mapNotNull { runCatching { it.asJsonObject }.getOrNull() }
                .map { ShizukuStep.fromJson(it) }
                .filter { it.buttonText.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    fun loginSteps(): List<ShizukuStep> = decodeSteps(SaveKeyValues.loadString(KEY_LOGIN_STEPS, ""))
    fun setLoginSteps(steps: List<ShizukuStep>) = SaveKeyValues.saveString(KEY_LOGIN_STEPS, encodeSteps(steps))
    fun authSteps(): List<ShizukuStep> = decodeSteps(SaveKeyValues.loadString(KEY_AUTH_STEPS, ""))
    fun setAuthSteps(steps: List<ShizukuStep>) = SaveKeyValues.saveString(KEY_AUTH_STEPS, encodeSteps(steps))

    // ---- 验证码等待超时（秒，10~600）----
    fun verifyWaitSeconds(): Int = SaveKeyValues.loadInt(KEY_VERIFY_WAIT, 60)
    fun setVerifyWaitSeconds(v: Int) = SaveKeyValues.saveInt(KEY_VERIFY_WAIT, v.coerceIn(10, 600))
    fun authWaitSeconds(): Int = SaveKeyValues.loadInt(KEY_AUTH_WAIT, 60)
    fun setAuthWaitSeconds(v: Int) = SaveKeyValues.saveInt(KEY_AUTH_WAIT, v.coerceIn(10, 600))

    /** 配置是否完整可用：已启用 + 有步骤 + (密码登录需已设密码 / 验证码登录需超时>0) */
    fun isConfigured(): Boolean {
        if (!isEnabled()) return false
        if (loginSteps().isEmpty()) return false
        return when (loginMethod()) {
            ShizukuLoginMethod.PASSWORD -> hasPassword()
            ShizukuLoginMethod.VERIFY_CODE -> verifyWaitSeconds() > 0
        }
    }

    /** 快照上报摘要（**不含密码明文**）：供控制端镜像展示 */
    fun summaryJson(): JsonObject = JsonObject().apply {
        addProperty("enabled", isEnabled())
        addProperty("method", loginMethod().name)
        addProperty("stepsCount", loginSteps().size)
        addProperty("hasPassword", hasPassword())
        addProperty("verifyWait", verifyWaitSeconds())
        addProperty("authStepsCount", authSteps().size)
        addProperty("authWait", authWaitSeconds())
    }

    /** 控制端镜像下发应用（FIELD_SHIZUKU_CONFIG）：仅接受非密码字段 */
    fun applyRemote(json: String) {
        if (json.isBlank()) return
        runCatching {
            val o = JsonParser.parseString(json).asJsonObject
            if (o.has("enabled")) setEnabled(o.get("enabled").asBoolean)
            if (o.has("method")) {
                val m = runCatching { ShizukuLoginMethod.valueOf(o.get("method").asString) }
                    .getOrDefault(ShizukuLoginMethod.PASSWORD)
                setLoginMethod(m)
            }
            if (o.has("loginSteps") && o.get("loginSteps").isJsonArray) {
                setLoginSteps(decodeSteps(o.getAsJsonArray("loginSteps").toString()))
            }
            if (o.has("verifyWait")) setVerifyWaitSeconds(o.get("verifyWait").asInt)
            if (o.has("authSteps") && o.get("authSteps").isJsonArray) {
                setAuthSteps(decodeSteps(o.getAsJsonArray("authSteps").toString()))
            }
            if (o.has("authWait")) setAuthWaitSeconds(o.get("authWait").asInt)
            // 注意：password 字段绝不接收——密码仅被控端本地设置，公共 Broker 不明文传输
        }
    }
}
