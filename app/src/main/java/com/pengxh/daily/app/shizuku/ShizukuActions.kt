package com.pengxh.daily.app.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.pengxh.daily.app.service.MqttAgentService
import com.pengxh.daily.app.ui.MainActivity
import com.pengxh.daily.app.utils.Constant
import com.yample.mqttprotocol.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Shizuku 手动登录 / 身份验证执行器（feat_shiziku，独立文件）。
 *
 * 流程（两种模式一致）：
 *   1. 前置校验（Shizuku 就绪 + 配置完整）→ 不满足则 alert 反馈并跳过
 *   2. 打开目标打卡 App
 *   3. dump 判定页面类型：不是目标页 → 退回 DT 主界面 + alert「跳过」
 *   4. 按配置步骤逐一点击（每步 dump → 按按钮文字定位 → input tap）
 *   5. 验证码模式：页面出现验证码输入框 → alert 请求控制端 → 等待配置超时 → 回填
 *   6. 完成/失败/超时 → 退回主界面 + alert 反馈结果
 *
 * 所有异常均 runCatching 兜底，绝不影响打卡主流程。
 */
object ShizukuActions {

    private const val MODE_LOGIN = "login"
    private const val MODE_VERIFY = "verify"
    private const val PAGE_LOAD_DELAY_MS = 1200L
    private const val STEP_DELAY_MS = 600L

    /** 控制端下发「手动登录」后调用 */
    fun runManualLogin(context: Context, scope: CoroutineScope) {
        scope.launch { execute(context, MODE_LOGIN) }
    }

    /** 控制端下发「身份验证」后调用 */
    fun runIdentityVerify(context: Context, scope: CoroutineScope) {
        scope.launch { execute(context, MODE_VERIFY) }
    }

    private suspend fun execute(context: Context, mode: String) {
        val isLogin = mode == MODE_LOGIN
        val resultType = if (isLogin) Protocol.ALERT_TYPE_LOGIN_RESULT else Protocol.ALERT_TYPE_VERIFY_RESULT
        val tag = if (isLogin) "登录" else "验证"

        // 1. 前置校验：Shizuku 就绪 + 高级功能开启 + 对应配置完整
        if (!ShizukuManager.isAvailable() || !ShizukuManager.isGranted()) {
            feedback(resultType, "跳过：Shizuku 未就绪，请先在被控端高级设置中授权")
            return
        }
        if (!ShizukuConfigStore.isEnabled()) {
            feedback(resultType, "跳过：Shizuku 高级功能未开启")
            return
        }
        val steps = if (isLogin) ShizukuConfigStore.loginSteps() else ShizukuConfigStore.authSteps()
        if (steps.isEmpty()) {
            feedback(resultType, "跳过：${tag}步骤未配置")
            return
        }

        // 2. 打开目标打卡 App
        if (!launchTargetApp(context)) {
            feedback(resultType, "$tag·失败：无法启动打卡应用")
            return
        }
        delay(PAGE_LOAD_DELAY_MS)

        // 3. dump + 页面类型判定
        val xml = ShizukuShell.dumpUiXml()
        if (xml.isNullOrBlank()) {
            backToMain(context)
            feedback(resultType, "$tag·失败：界面信息获取失败（可能为安全窗口）")
            return
        }
        val nodes = UiNodeParser.parse(xml)
        val pageTexts = steps.map { it.buttonText }
        if (!UiNodeParser.hasAnyButtonText(nodes, pageTexts)) {
            backToMain(context)
            feedback(resultType, "跳过：当前非${tag}界面")
            return
        }

        // 4. 执行步骤
        val result = executeSteps(context, steps, isLogin)
        backToMain(context)
        feedback(resultType, result)
    }

    /** 按步骤点击；返回结果描述（xx·成功 / xx·失败：原因 / xx·超时） */
    private suspend fun executeSteps(context: Context, steps: List<ShizukuStep>, isLogin: Boolean): String {
        val tag = if (isLogin) "登录" else "验证"
        val waitSeconds = if (isLogin) ShizukuConfigStore.verifyWaitSeconds() else ShizukuConfigStore.authWaitSeconds()

        // 验证码模式：开始前若页面有验证码/账号输入框（非密码框）→ 请求验证码回填
        if (!isLogin || ShizukuConfigStore.loginMethod() == ShizukuLoginMethod.VERIFY_CODE) {
            val xml0 = ShizukuShell.dumpUiXml()
            val nodes0 = UiNodeParser.parse(xml0)
            val codeBox = UiNodeParser.findEditText(nodes0, passwordOnly = false)
            val pwdBox = UiNodeParser.findEditText(nodes0, passwordOnly = true)
            val needCode = codeBox != null && (pwdBox == null || codeBox != pwdBox)
            if (needCode) {
                val code = waitForVerifyCode(waitSeconds) ?: return "$tag·超时：等待验证码超时"
                if (!fillEditText(codeBox, code)) return "$tag·失败：验证码回填失败"
                delay(STEP_DELAY_MS)
            }
        }

        // 密码模式：先填密码框
        if (isLogin && ShizukuConfigStore.loginMethod() == ShizukuLoginMethod.PASSWORD) {
            val xmlP = ShizukuShell.dumpUiXml()
            val pwdBox = UiNodeParser.findEditText(UiNodeParser.parse(xmlP), passwordOnly = true)
            if (pwdBox != null) {
                val password = ShizukuConfigStore.password()
                if (password.isBlank()) return "$tag·失败：密码未配置"
                if (!fillEditText(pwdBox, password)) return "$tag·失败：密码输入失败"
                delay(STEP_DELAY_MS)
            }
        }

        // 逐步骤点击
        for ((index, step) in steps.withIndex()) {
            val xml = ShizukuShell.dumpUiXml() ?: return "$tag·失败：第 ${index + 1} 步界面获取失败"
            val box = UiNodeParser.findButtonByText(UiNodeParser.parse(xml), step.buttonText)
                ?: return "$tag·失败：第 ${index + 1} 步未找到按钮「${step.buttonText}」"
            val center = UiNodeParser.center(box) ?: return "$tag·失败：第 ${index + 1} 步按钮坐标异常"
            if (!ShizukuShell.tap(center.first, center.second)) {
                return "$tag·失败：第 ${index + 1} 步点击执行失败"
            }
            delay(STEP_DELAY_MS)

            // 步骤执行中若出现验证码输入框（动态弹窗），同样请求验证码回填
            if (ShizukuConfigStore.loginMethod() == ShizukuLoginMethod.VERIFY_CODE) {
                val after = UiNodeParser.parse(ShizukuShell.dumpUiXml())
                val codeBox = UiNodeParser.findEditText(after, passwordOnly = false)
                val pwdBox = UiNodeParser.findEditText(after, passwordOnly = true)
                if (codeBox != null && (pwdBox == null || codeBox != pwdBox) && ShizukuVerifyCodeBus.consume() == null) {
                    val code = waitForVerifyCode(waitSeconds) ?: return "$tag·超时：等待验证码超时"
                    if (!fillEditText(codeBox, code)) return "$tag·失败：验证码回填失败"
                    delay(STEP_DELAY_MS)
                }
            }
        }
        return "$tag·成功"
    }

    /** 请求控制端下发验证码并等待，超时返回 null（等待期间持续消费总线） */
    private suspend fun waitForVerifyCode(waitSeconds: Int): String? {
        ShizukuVerifyCodeBus.reset()
        feedback(Protocol.ALERT_TYPE_VERIFY_CODE_REQUEST, "被控端需要短信验证码，请在控制端输入后下发")
        val deadline = System.currentTimeMillis() + waitSeconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            ShizukuVerifyCodeBus.consume()?.let { return it }
            delay(500)
        }
        return null
    }

    private suspend fun fillEditText(box: android.graphics.Rect, text: String): Boolean {
        val c = UiNodeParser.center(box) ?: return false
        ShizukuShell.tap(c.first, c.second)
        delay(300)
        return ShizukuShell.inputText(text)
    }

    /** 打开目标打卡应用（独立实现，不复用打卡链路的 openApplication，避免引入悬浮窗/蒙层副作用） */
    private suspend fun launchTargetApp(context: Context): Boolean = withContext(Dispatchers.Main) {
        runCatching {
            val pkg = Constant.getTargetApp()
            val intent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                setPackage(pkg)
            }
            val info = context.packageManager.queryIntentActivities(intent, 0).firstOrNull()
                ?: return@runCatching false
            intent.component = ComponentName(info.activityInfo.packageName, info.activityInfo.name)
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    /** 退回 DT 主界面（singleTask，NEW_TASK|SINGLE_TOP|REORDER_TO_FRONT） */
    private suspend fun backToMain(context: Context) {
        delay(400)
        runCatching {
            context.startActivity(Intent(context, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
            })
        }
    }

    /** 经 alert 通道反馈（控制端弹窗 + 入告警历史） */
    private fun feedback(type: String, msg: String) {
        runCatching {
            MqttAgentService.publishAlert(JSONObject().apply {
                put("type", type)
                put("msg", msg)
                put("ts", System.currentTimeMillis())
            }.toString())
        }
    }
}
