package com.pengxh.daily.app.utils

import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 配置导出用的可逆加密工具：把邮箱授权码以密文形式写入配置文件，导入时再解密自动填充。
 *
 * 设计说明：
 * - 密钥由内置常量经 SHA-256 派生，属于“随文件迁移的混淆加密”，目的是避免授权码以明文/脱敏形式
 *   随配置文件传播，方便跨设备迁移时自动填充，并非替代 Android Keystore 的设备级安全存储。
 * - 设备级安全存储仍由 [EmailSecureConfig]（EncryptedSharedPreferences + Keystore）负责；
 *   此处密文仅在跨设备迁移过程中短暂存在，且密钥随应用分发，安全性等同于应用自身。
 * - 输出格式：Base64(IV) + "]" + Base64(密文)，IV 每次随机生成，避免相同明文产生相同密文。
 */
object ConfigCipher {
    private const val SECRET = "DailyTask.Config.Export.SECRET.v1"
    private const val TRANSFORMATION = "AES/CBC/PKCS5Padding"
    private const val IV_SEPARATOR = "]"

    private val secretKey: SecretKeySpec by lazy {
        val digest = MessageDigest.getInstance("SHA-256").digest(SECRET.toByteArray(Charsets.UTF_8))
        SecretKeySpec(digest.copyOf(32), "AES")
    }

    /** 加密明文，返回可随文件存储的密文；空串直接返回空串。任何异常返回空串（不阻断导出）。 */
    fun encrypt(plain: String): String {
        if (plain.isBlank()) return ""
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val iv = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, IvParameterSpec(iv))
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            val dataB64 = Base64.encodeToString(encrypted, Base64.NO_WRAP)
            "$ivB64$IV_SEPARATOR$dataB64"
        }.getOrDefault("")
    }

    /** 解密密文还原明文；空串或任何异常返回空串（导入时据此跳过自动填充）。 */
    fun decrypt(payload: String): String {
        if (payload.isBlank()) return ""
        return runCatching {
            val (ivB64, dataB64) = payload.split(IV_SEPARATOR, limit = 2)
            val iv = Base64.decode(ivB64, Base64.NO_WRAP)
            val data = Base64.decode(dataB64, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        }.getOrDefault("")
    }
}
