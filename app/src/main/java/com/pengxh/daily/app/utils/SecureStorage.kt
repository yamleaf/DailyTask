package com.pengxh.daily.app.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.pengxh.daily.app.DailyTaskApplication
import com.pengxh.kt.lite.utils.SaveKeyValues
import com.google.gson.JsonObject

/**
 * 敏感信息加密存储：基于 Android Keystore 的 EncryptedSharedPreferences。
 *
 * - 密钥由 Android Keystore（AES256_GCM）托管，明文不会落到磁盘。
 * - Keystore 不可用时（极少数设备/模拟器）自动降级到普通 private SharedPreferences，
 *   保证功能不中断（仅该极端场景安全性下降）。
 */
object SecurePrefs {
    private const val FILE_NAME = "daily_secure_prefs"
    private const val FALLBACK_FILE_NAME = "daily_secure_prefs_fallback"

    @Volatile
    private var prefs: SharedPreferences? = null

    private fun delegate(): SharedPreferences {
        prefs?.let { return it }
        synchronized(this) {
            prefs?.let { return it }
            val context: Context = DailyTaskApplication.get()
            val created = try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context,
                    FILE_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                context.getSharedPreferences(FALLBACK_FILE_NAME, Context.MODE_PRIVATE)
            }
            prefs = created
            return created
        }
    }

    fun getString(key: String, default: String = ""): String {
        return runCatching { delegate().getString(key, default) ?: default }
            .getOrDefault(default)
    }

    fun putString(key: String, value: String) {
        runCatching { delegate().edit().putString(key, value).apply() }
    }

    fun remove(key: String) {
        runCatching { delegate().edit().remove(key).apply() }
    }
}

/**
 * 邮箱授权码加密存储与迁移：
 * - 授权码只存于 Keystore 加密的 EncryptedSharedPreferences，不再写入明文配置文件。
 * - 首次读取时若加密存储为空，则从旧的明文 ConfigStore 迁移，并清除明文残留，避免泄露。
 * - 导出/展示场景使用 [maskAuthCode] 脱敏。
 */
object EmailSecureConfig {
    private const val AUTH_CODE_KEY = "email_auth_code"

    /** 读取授权码：优先加密存储；首次从无加密配置迁移 */
    fun loadAuthCode(): String {
        val secured = SecurePrefs.getString(AUTH_CODE_KEY)
        if (secured.isNotBlank()) return secured

        // 迁移旧明文配置
        val obj = ConfigStore.get().load(Constant.EMAIL_CONFIG_KEY)
        val legacy = if (!obj.isEmpty && obj.has("authCode")) obj.get("authCode").asString else ""
        if (legacy.isNotBlank()) {
            SecurePrefs.putString(AUTH_CODE_KEY, legacy)
            // 从明文配置中移除授权码，避免残留泄露
            if (obj.has("authCode")) {
                obj.remove("authCode")
                ConfigStore.get().save(Constant.EMAIL_CONFIG_KEY, obj)
            }
        }
        return legacy
    }

    fun saveAuthCode(code: String) {
        SecurePrefs.putString(AUTH_CODE_KEY, code)
    }

    fun clearAuthCode() {
        SecurePrefs.remove(AUTH_CODE_KEY)
    }

    /** 导出/展示用脱敏：仅保留首尾各 1 位，其余打码 */
    fun maskAuthCode(code: String): String {
        if (code.length <= 2) return "******"
        return "${code.first()}****${code.last()}"
    }

    /** 是否为脱敏掩码（导入时跳过，避免覆盖真实授权码） */
    fun isMasked(code: String): Boolean = code.contains('*')
}

/**
 * MQTT 敏感信息加密存储与迁移（A5）：
 * - DEV 账户密码、配对派生的会话密钥 sessionSecret 仅存于 Keystore 加密的 EncryptedSharedPreferences。
 * - 读取时优先加密存储；首次从无加密 SaveKeyValues 迁移，并清除明文残留，避免泄露。
 * - 配置导入（写明文 SaveKeyValues）也能被回读迁移兜底，不会因迁移而丢失。
 */
object MqttSecureConfig {
    private const val PASS_KEY = "mqtt_dev_pass"
    private const val SESSION_KEY = "mqtt_session_secret"

    fun loadPass(): String {
        val secured = SecurePrefs.getString(PASS_KEY)
        if (secured.isNotBlank()) return secured
        val legacy = SaveKeyValues.loadString(Constant.MQTT_PASS_KEY, "")
        if (legacy.isNotBlank()) {
            SecurePrefs.putString(PASS_KEY, legacy)
            SaveKeyValues.removeKey(Constant.MQTT_PASS_KEY) // 清除明文残留
        }
        return legacy
    }

    fun savePass(pass: String) {
        SecurePrefs.putString(PASS_KEY, pass)
        SaveKeyValues.removeKey(Constant.MQTT_PASS_KEY)
    }

    fun loadSession(): String {
        val secured = SecurePrefs.getString(SESSION_KEY)
        if (secured.isNotBlank()) return secured
        val legacy = SaveKeyValues.loadString(Constant.MQTT_SESSION_SECRET_KEY, "")
        if (legacy.isNotBlank()) {
            SecurePrefs.putString(SESSION_KEY, legacy)
            SaveKeyValues.removeKey(Constant.MQTT_SESSION_SECRET_KEY)
        }
        return legacy
    }

    fun saveSession(session: String) {
        SecurePrefs.putString(SESSION_KEY, session)
        SaveKeyValues.removeKey(Constant.MQTT_SESSION_SECRET_KEY) // 无论清空与否都移除明文残留
    }
}

/**
 * Serverless API AppSecret 加密存储与迁移（需求⑤）：
 * - AppSecret 仅存于 Keystore 加密的 EncryptedSharedPreferences，与 MQTT 密码加密保持一致。
 * - 读取时优先加密存储；首次从无加密 SaveKeyValues 迁移，并清除明文残留，避免泄露。
 */
object ServerlessApiSecureConfig {
    private const val SECRET_KEY = "serverless_api_app_secret"

    fun loadSecret(): String {
        val secured = SecurePrefs.getString(SECRET_KEY)
        if (secured.isNotBlank()) return secured
        val legacy = SaveKeyValues.loadString(Constant.MQTT_SERVERLESS_API_APP_SECRET_KEY, "")
        if (legacy.isNotBlank()) {
            SecurePrefs.putString(SECRET_KEY, legacy)
            SaveKeyValues.removeKey(Constant.MQTT_SERVERLESS_API_APP_SECRET_KEY)
        }
        return legacy
    }

    fun saveSecret(secret: String) {
        SecurePrefs.putString(SECRET_KEY, secret)
        SaveKeyValues.removeKey(Constant.MQTT_SERVERLESS_API_APP_SECRET_KEY)
    }
}
