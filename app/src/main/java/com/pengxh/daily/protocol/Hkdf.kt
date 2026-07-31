package com.pengxh.daily.protocol

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HKDF-SHA256（RFC 5869）精简实现，仅用于配对握手派生会话密钥。
 * 两端（被控端 / 控制端）必须传入相同的 ikm / salt / info，才能得到一致的会话密钥。
 */
object Hkdf {
    private const val ALG = "HmacSHA256"

    /** 派生指定长度字节；salt 为空时按 RFC 用全 0 字节 */
    fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmac(if (salt.isEmpty()) ByteArray(32) { 0 } else salt, ikm)
        val okm = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            t = hmac(prk, t + info + byteArrayOf(counter.toByte()))
            val n = minOf(t.size, length - offset)
            System.arraycopy(t, 0, okm, offset, n)
            offset += n
            counter++
        }
        return okm
    }

    /** 以字符串入参派生并输出十六进制（两端统一用字符串字节） */
    fun deriveHex(ikm: String, salt: String, info: String, length: Int): String =
        derive(ikm.toByteArray(), salt.toByteArray(), info.toByteArray(), length)
            .joinToString("") { "%02x".format(it) }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(ALG)
        mac.init(SecretKeySpec(key, ALG))
        return mac.doFinal(data)
    }
}
