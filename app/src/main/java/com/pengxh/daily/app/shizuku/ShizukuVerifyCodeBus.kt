package com.pengxh.daily.app.shizuku

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 验证码进程内总线（feat_shiziku）：
 * 控制端经 FIELD_VERIFY_CODE 下发验证码 → MqttAgentService 转发到此 → 执行器等待消费。
 * 单向消费：consume() 取走即清空，避免旧验证码被下次登录误用。
 *
 * 另承载「短信已发送」信号（FIELD_SMS_SENT，钉钉验证码登录）：
 * 被控端采集短信内容+收件人上报后，控制端确认已发送 → dispatchSmsSent() → 执行器继续。
 *
 * 还承载「结果判定人工确认」（FIELD_RESULT_CONFIRM，结果判定步骤）：
 * 被控端截图回传后，控制端看截图点「成功/失败」→ dispatchResultConfirm("success"/"fail") → 执行器判定成败。
 */
object ShizukuVerifyCodeBus {
    private val _code = MutableStateFlow<String?>(null)
    val code: StateFlow<String?> = _code.asStateFlow()

    private val _smsSent = MutableStateFlow(false)
    val smsSent: StateFlow<Boolean> = _smsSent.asStateFlow()

    private val _resultConfirm = MutableStateFlow<String?>(null)
    val resultConfirm: StateFlow<String?> = _resultConfirm.asStateFlow()

    fun dispatch(code: String) {
        _code.value = code
    }

    fun consume(): String? {
        val c = _code.value
        if (c != null) _code.value = null
        return c
    }

    fun reset() {
        _code.value = null
        _smsSent.value = false
        _resultConfirm.value = null
    }

    /** 控制端确认「短信已发送」 */
    fun dispatchSmsSent() {
        _smsSent.value = true
    }

    /** 消费「短信已发送」信号；未收到返回 false（消费即清空） */
    fun consumeSmsSent(): Boolean {
        val v = _smsSent.value
        if (v) _smsSent.value = false
        return v
    }

    /** 控制端人工确认结果（"success"/"fail"） */
    fun dispatchResultConfirm(v: String) {
        _resultConfirm.value = v
    }

    /** 消费人工确认结果；未收到返回 null（消费即清空） */
    fun consumeResultConfirm(): String? {
        val v = _resultConfirm.value
        if (v != null) _resultConfirm.value = null
        return v
    }
}