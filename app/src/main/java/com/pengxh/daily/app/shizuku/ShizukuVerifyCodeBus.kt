package com.pengxh.daily.app.shizuku

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 验证码进程内总线（feat_shiziku）：
 * 控制端经 FIELD_VERIFY_CODE 下发验证码 → MqttAgentService 转发到此 → 执行器等待消费。
 * 单向消费：consume() 取走即清空，避免旧验证码被下次登录误用。
 */
object ShizukuVerifyCodeBus {
    private val _code = MutableStateFlow<String?>(null)
    val code: StateFlow<String?> = _code.asStateFlow()

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
    }
}
