package com.pengxh.daily.protocol

import androidx.annotation.Keep

/**
 * 绑定二维码载荷：被控端生成，控制端扫码后据以连接并触发配对握手。
 *
 * 最终模型（终版收敛）：
 *  - 二维码只携带「控制端 CTL 凭证 + 配对令牌」，【不】携带被控端 DEV 凭证，也【不】携带会话密钥。
 *  - 会话密钥在配对握手阶段由两端各自 HKDF(pairingToken || deviceId) 独立派生，
 *    避免二维码泄露长期密钥；pairingToken 单次 / 60s，截屏风险窗口极小。
 */
@Keep
data class BindingPayload(
    val broker: String,
    val deviceId: String,
    val ctlUser: String,
    val ctlPass: String,
    val pairingToken: String
)
