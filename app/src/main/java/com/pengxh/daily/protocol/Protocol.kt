package com.pengxh.daily.protocol

import androidx.annotation.Keep

@Keep
object Protocol {
    const val CMD_SYNC = "S"
    const val CMD_UPDATE = "U"
    const val CMD_ACK = "A"
    const val CMD_NOTIFY = "N"
    const val CMD_PAIR = "P"          // 控制端发起配对（携带 pairingToken）
    const val CMD_PAIR_ACCEPT = "PA"  // 被控端配对成功回执
    const val CMD_UNBOUND = "UB"      // 解除绑定（任一侧发起）

    const val FIELD_POWER_SAVE = "ps"
    const val FIELD_FORCE_PSEUDO_MASK = "pm"
    const val FIELD_PSEUDO_MASK_TIMEOUT = "tm"

    /** 最终落地主题前缀（替代早期 daily/task/），主题形如 dt/{id}/cmd、dt/{id}/status … */
    const val TOPIC_PREFIX = "dt"

    /** 配对握手上下文信息（HKDF info），两端必须一致 */
    const val PAIRING_INFO = "daily-pairing-v1"
    /** 派生会话密钥长度（字节） */
    const val SESSION_KEY_LEN = 32
    /** 配对令牌有效期（ms） */
    const val PAIRING_TTL_MS = 60_000L
}
