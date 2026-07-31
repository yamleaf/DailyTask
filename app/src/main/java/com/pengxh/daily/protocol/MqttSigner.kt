package com.pengxh.daily.protocol

import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object MqttSigner {
    private const val ALGORITHM = "HmacSHA256"

    fun sign(secretKey: String, deviceId: String, ts: Long, rid: String, f: String, t: String, v: String, c: String): String {
        val data = deviceId + ts + rid + f + t + v + c
        val sha256_HMAC = Mac.getInstance(ALGORITHM)
        val secret_key = SecretKeySpec(secretKey.toByteArray(), ALGORITHM)
        sha256_HMAC.init(secret_key)
        return sha256_HMAC.doFinal(data.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
