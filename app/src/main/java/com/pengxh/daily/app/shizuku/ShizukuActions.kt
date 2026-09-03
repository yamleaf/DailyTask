package com.pengxh.daily.app.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.app.KeyguardManager
import android.os.PowerManager
import android.util.Log
import com.pengxh.daily.app.service.MqttAgentService
import com.pengxh.daily.app.ui.MainActivity
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.MessageDispatcher
import com.yample.mqttprotocol.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Shizuku 手动登录 / 身份验证执行器（feat_shiziku，独立文件）。
 *
 * 流程（两种模式一致）：
 *   1. 前置校验（Shizuku 就绪 + 配置完整）→ 不满足则 alert 反馈并跳过
 *   2. 打开目标打卡 App
 *   3. 按配置步骤逐一执行（**纯坐标点选**：点击/滑动/密码/验证码回填都按采集的坐标执行，
 *      不做 dump 文字查找定位）
 *   4. 验证码模式：页面出现验证码输入框 → alert 请求控制端 → 等待配置超时 → 回填
 *   5. 完成/失败/超时 → 退回主界面 + alert 反馈结果
 *
 * 并发安全：登录/验证/模拟打卡共用 [actionMutex]，同一时刻仅一个动作在跑；
 * 系统动画经 ShizukuShell.disableAnimations/restoreAnimations 成对管理，finally 必恢复。
 * 所有异常均 runCatching 兜底，绝不影响打卡主流程。
 */
object ShizukuActions {

    private const val TAG = "ShizukuActions"

    private const val MODE_LOGIN = "login"
    private const val MODE_VERIFY = "verify"
    private const val MODE_PUNCH = "punch"

    /**
     * 动作互斥锁：登录 / 身份验证 / 模拟打卡共用同一套 shell 点击通道，
     * 并发执行会互相干扰（A 的点击落到 B 的页面上），故同一时刻只允许一个动作在跑。
     * 占用时直接反馈「跳过」而不是排队等待——排队会让控制端在数十秒内收不到任何反馈。
     */
    private val actionMutex = Mutex()

    /** 控制端下发「手动登录」后调用 */
    fun runManualLogin(context: Context, scope: CoroutineScope) {
        scope.launch { execute(context, MODE_LOGIN) }
    }

    /** 控制端下发「身份验证」后调用 */
    fun runIdentityVerify(context: Context, scope: CoroutineScope) {
        scope.launch { execute(context, MODE_VERIFY) }
    }

    /** 控制端下发「模拟打卡」后调用 */
    fun runSimulatePunch(context: Context, scope: CoroutineScope) {
        scope.launch { execute(context, MODE_PUNCH) }
    }

    private suspend fun execute(context: Context, mode: String) {
        val resultType = when (mode) {
            MODE_LOGIN -> Protocol.ALERT_TYPE_LOGIN_RESULT
            MODE_VERIFY -> Protocol.ALERT_TYPE_VERIFY_RESULT
            else -> Protocol.ALERT_TYPE_SIMULATE_PUNCH_RESULT
        }
        val tag = when (mode) {
            MODE_LOGIN -> "登录"
            MODE_VERIFY -> "验证"
            else -> "模拟打卡"
        }
        Log.d(TAG, "Shizuku $tag 动作开始 mode=$mode")

        if (!actionMutex.tryLock()) {
            feedback(resultType, "跳过：已有 Shizuku 操作正在执行，请稍后再试")
            return
        }
        try {
            runFlow(context, mode, resultType, tag)
        } finally {
            // 无论成败/异常都恢复系统动画并释放锁：绝不能把用户设备的动画永久置 0
            runCatching { ShizukuShell.restoreAnimations() }
            actionMutex.unlock()
        }
    }

    private suspend fun runFlow(context: Context, mode: String, resultType: String, tag: String) {
        // 1. 前置校验：Shizuku 就绪 + 高级功能开启 + 对应配置完整
        if (!ShizukuManager.isAvailable() || !ShizukuManager.isGranted()) {
            feedback(resultType, "跳过：Shizuku 未就绪，请先在被控端高级设置中授权")
            return
        }
        if (!ShizukuConfigStore.isEnabled()) {
            feedback(resultType, "跳过：Shizuku 高级功能未开启")
            return
        }
        val steps = when (mode) {
            MODE_LOGIN -> ShizukuConfigStore.loginSteps()
            MODE_VERIFY -> ShizukuConfigStore.authSteps()
            else -> ShizukuConfigStore.punchSteps()
        }
        if (steps.isEmpty()) {
            feedback(resultType, "跳过：${tag}步骤未配置")
            return
        }
        // 「结果判定」会 return 结束整个流程，放在中间会静默吞掉后续步骤——直接拒绝并给出位置提示
        val resultCheckIdx = steps.indexOfFirst { it.type == ShizukuStepType.RESULT_CHECK }
        if (resultCheckIdx >= 0 && resultCheckIdx != steps.lastIndex) {
            feedback(
                resultType,
                "跳过：结果判定必须是最后一步（当前在第 ${resultCheckIdx + 1}/${steps.size} 步）"
            )
            return
        }
        Log.d(TAG, "Shizuku 步骤: $steps")

        // 1.4 临时关闭系统动画（转场动画会打乱 uiautomator/input 时序）；
        //     恢复动作在 execute() 的 finally 中，异常/超时路径同样保证执行
        ShizukuShell.disableAnimations()

        // 1.5 亮屏 + 退出锁屏（息屏时点击无效）；息屏才亮屏、锁屏界面才上滑，避免亮屏未锁时多余手势
        val km = context.getSystemService(KeyguardManager::class.java)
        val pm = context.getSystemService(PowerManager::class.java)
        val screenOff = pm?.isInteractive == false
        val locked = km?.isKeyguardLocked == true
        ShizukuShell.wakeAndUnlock(wakeIfNeeded = screenOff, swipeIfNeeded = locked)

        // 2. 打开目标打卡 App
        if (!launchTargetApp(context)) {
            feedback(resultType, "$tag·失败：无法启动打卡应用")
            return
        }
        // 略等 App 拉起（首步真正的等待在第 1 步执行前，默认 5s）
        delay(500)

        // 3. 执行步骤（全坐标点选，无需 dump 判定页面）
        val waitSeconds = when (mode) {
            MODE_LOGIN -> ShizukuConfigStore.verifyWaitSeconds()
            MODE_VERIFY -> ShizukuConfigStore.authWaitSeconds()
            else -> ShizukuConfigStore.verifyWaitSeconds()
        }
        val result = executeSteps(context, steps, tag, waitSeconds)
        backToMain(context)
        feedback(resultType, result)
    }

    /** 按步骤类型执行（全坐标点选，无 dump 回退）；返回结果描述（xx·成功 / xx·失败 / xx·超时）。
     *  步骤须带坐标（hasCoord），未配置坐标即失败——采集坐标用高级设置「从当前屏幕采集坐标」。
     *  多格验证码：配置 N 个「验证码输入」步骤对应 N 个格子，先取整串验证码，再逐格填单个数字；
     *  单框验证码：配置 1 个「验证码输入」步骤，整串填入。
     */
    private suspend fun executeSteps(context: Context, steps: List<ShizukuStep>, tag: String, waitSeconds: Int): String {
        val codeInputs = steps.filter { it.type == ShizukuStepType.CODE_INPUT }
        val multiBox = codeInputs.size > 1
        var code: String? = null
        var codeFilled = 0

        for ((index, step) in steps.withIndex()) {
            if (step.type == ShizukuStepType.SWIPE) {
                if (!step.hasCoord || !step.hasEndpoint) return "$tag·失败：第 ${index + 1} 步滑动未配置起点/终点"
            } else if (step.type != ShizukuStepType.CODE_CAPTURE && step.type != ShizukuStepType.RESULT_CHECK && !step.hasCoord) {
                return "$tag·失败：第 ${index + 1} 步未配置坐标"
            }
            // 到自己的步骤，先实现基础延迟（秒）：
            //  - 有延迟范围配置（min~max）：在范围内按 0.1s 步进随机，如 0-5→0~5s、3-5→3~5s、1-1→固定 1s
            //  - 未配置：默认第 1 步 5s、其余 3s；多格验证码第 2+ 格默认 1s
            val defaultDelay = if (step.type == ShizukuStepType.CODE_INPUT && codeFilled > 0) {
                1
            } else if (index == 0) {
                5
            } else {
                3
            }
            val base: Double = if (step.hasDelay) {
                val lo = step.delayMin.coerceAtLeast(0)
                val hi = step.delayMax.coerceAtLeast(lo)
                if (hi <= lo) {
                    lo.toDouble()
                } else {
                    // 在 lo~hi 之间按 0.1s 步进随机取一个值（如 3-5 → 3.0~5.0 步进 0.1）
                    val steps = (hi - lo) * 10
                    (lo + (Math.random() * (steps + 1)).toInt() / 10.0)
                }
            } else {
                defaultDelay.toDouble()
            }
            // 基础停顿：每次操作前追加 0.5~2s 随机停留，模拟人阅读/找按钮的自然节奏
            val pause = 500 + (Math.random() * 1500).toInt()
            val waitMs = (base * 1000).toInt() + pause
            delay(waitMs.toLong())
            Log.d(TAG, "step[${index + 1}] wait=${waitMs}ms(type=${step.type}, coords=(${step.x},${step.y}), range=${step.range})")
            when (step.type) {
                ShizukuStepType.CLICK -> {
                    if (!ShizukuShell.tap(step.x, step.y, step.range)) return "$tag·失败：第 ${index + 1} 步点击失败"
                }

                ShizukuStepType.SWIPE -> {
                    if (!ShizukuShell.gesture(step.x, step.y, step.x2, step.y2)) return "$tag·失败：第 ${index + 1} 步滑动失败"
                }

                ShizukuStepType.PWD_INPUT -> {
                    val pwd = ShizukuConfigStore.password()
                    if (pwd.isBlank()) return "$tag·失败：密码未配置"
                    if (!ShizukuShell.tap(step.x, step.y, step.range)) return "$tag·失败：第 ${index + 1} 步点击失败"
                    delay(300)
                    if (!ShizukuShell.inputText(pwd)) return "$tag·失败：密码输入失败"
                }

                ShizukuStepType.CODE_INPUT -> {
                    if (code == null) {
                        code = waitForVerifyCode(waitSeconds) ?: return "$tag·超时：等待验证码超时"
                    }
                    val full = code
                    // 多格逐位 / 单框整串
                    val text = if (multiBox) {
                        if (codeFilled >= full.length) {
                            return "$tag·失败：验证码长度不足（需 ${codeInputs.size} 位）"
                        }
                        full[codeFilled].toString()
                    } else {
                        full
                    }
                    if (!ShizukuShell.tap(step.x, step.y, step.range)) return "$tag·失败：第 ${index + 1} 步点击失败"
                    delay(300)
                    if (!ShizukuShell.inputText(text)) return "$tag·失败：验证码回填失败"
                    codeFilled++
                }

                ShizukuStepType.CODE_CAPTURE -> {
                    if (!captureSmsAndWaitSent(step, waitSeconds)) return "$tag·超时：等待短信发送确认超时"
                }

                ShizukuStepType.RESULT_CHECK -> {
                    val confirm = screenshotAndWaitConfirm(context, waitSeconds) ?: return "$tag·超时：等待结果确认超时"
                    return if (confirm == "success") "$tag·成功" else "$tag·失败：用户确认登录未成功"
                }
            }
        }
        return "$tag·成功"
    }

    /**
     * 钉钉验证码登录（CODE_CAPTURE）：解析页面提取「短信发送内容 + 收件人」上报控制端（ALERT_TYPE_SMS_CAPTURE），
     * 等待控制端确认「短信已发送」（FIELD_SMS_SENT），超时返回 false。
     * 采集规则：
     *  - uiautomator 常把整段短信聚合成一个节点（行间为 &#10; 或 \n），先把文本拆成行再逐行精配，
     *    避免整段文本被当作内容误上报。
     *  - 收件人：kw1 非空时按关键字定位「含关键字且含号码」的行再提取；空则全文号码正则 1\d{10,}
     *    （覆盖 11 位手机号与钉钉 1069076... 等长号码，不截断）。
     *  - 内容：kw2 非空时取含 kw2 的行（关键字原样匹配，空格参与，如「发送 」）；空则内置规则，
     *    优先钉钉「发送 … 复制」正文行，再回退「验证码/发送至/发送到/短信」关键字。
     *  - 关键字保留原样（含首尾空格）：onConfirm 不再 trim，方便「到 」/「发送 」这类精确匹配。
     */
    private suspend fun captureSmsAndWaitSent(step: ShizukuStep, waitSeconds: Int): Boolean {
        ShizukuVerifyCodeBus.reset()
        val xml = ShizukuShell.dumpUiXml() ?: return false
        val nodes = UiNodeParser.parse(xml)
        // 拆成文本行：兼容聚合节点（行间 &#10; / \n）与独立节点，去空白后逐行精配
        val lines = nodes.mapNotNull { it.text }
            .flatMap { it.replace("&#10;", "\n").split('\n', '\r') }
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val phoneRegex = Regex("""1\d{10,}""")
        val recipient = if (step.kw1.isNotBlank()) {
            lines.firstOrNull { it.contains(step.kw1) && phoneRegex.containsMatchIn(it) }
                ?.let { phoneRegex.find(it)?.value }
                ?: lines.firstOrNull { phoneRegex.containsMatchIn(it) }?.let { phoneRegex.find(it)?.value }
        } else {
            lines.firstOrNull { phoneRegex.containsMatchIn(it) }?.let { phoneRegex.find(it)?.value }
        }
        // 短信正文清洗：去「发送 」前缀（钉钉）与「复制」尾标签；其余行原样保留
        fun contentFrom(line: String): String = line.trim()
            .replace(Regex("""^发送\s+"""), "")
            .replace(Regex("""\s*(复制|Copy|点此复制|拷贝)\s*$"""), "")
            .trim()
        val content = if (step.kw2.isNotBlank()) {
            lines.firstOrNull { it.contains(step.kw2) }?.let { contentFrom(it) }
                ?.takeIf { it.isNotBlank() } ?: step.kw2
        } else {
            // 内置优先级：钉钉「发送 … 复制」→ 含「发送 」（发送+空格）→「验证码」（排除按钮）→ 发送至/发送到 → 短信（排除状态横幅）
            lines.firstOrNull { it.contains("发送 ") }?.let { contentFrom(it) }
                ?.takeIf { it.isNotBlank() }
                ?: lines.firstOrNull { it.contains("验证码") && !it.contains("发送") }?.let { contentFrom(it) }
                ?: lines.firstOrNull { it.contains("发送至") || it.contains("发送到") }?.let { contentFrom(it) }
                ?: lines.firstOrNull { it.contains("短信") && !it.contains("互发") && !it.contains("彩信") }
                    ?.let { contentFrom(it) }
                ?: "请将登录验证码短信发送至收件人"
        }
        val json = org.json.JSONObject().apply {
            put("content", content)
            put("recipient", recipient ?: "待定")
        }
        feedback(Protocol.ALERT_TYPE_SMS_CAPTURE, json.toString())
        val deadline = System.currentTimeMillis() + waitSeconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            if (ShizukuVerifyCodeBus.consumeSmsSent()) return true
            delay(500)
        }
        return false
    }

    /**
     * 结果判定（RESULT_CHECK，作为最后一个步骤）：截图回传人工确认。
     * 1) Shizuku 截图（screencap）保存到本地文件；
     * 2) 经消息渠道（邮箱附件/企微图片）回传用户——**不走 MQTT，避免大包浪费额度**；
     * 3) alert 请求控制端人工确认（ALERT_TYPE_RESULT_SCREENSHOT，仅文本）；
     * 4) 用户看截图后点「成功/失败」→ FIELD_RESULT_CONFIRM 回传 → 判定成败；
     * 超时返回 null。
     */
    private suspend fun screenshotAndWaitConfirm(context: Context, waitSeconds: Int): String? {
        ShizukuVerifyCodeBus.reset()
        val bytes = ShizukuShell.screenshotBytes()
        if (bytes != null && bytes.isNotEmpty()) {
            val file = runCatching {
                val dir = context.getExternalFilesDir(null) ?: context.cacheDir
                val f = File(dir, "sz_result_${System.currentTimeMillis()}.png")
                FileOutputStream(f).use { it.write(bytes) }
                f
            }.getOrNull()
            if (file != null) {
                MessageDispatcher.sendAttachmentMessage(
                    "Shizuku登录结果确认",
                    "请查看截图，确认登录/验证是否成功（附件为被控端当前画面）",
                    file.absolutePath,
                    force = true
                )
                pruneResultShots(file)
            }
        }
        feedback(
            Protocol.ALERT_TYPE_RESULT_SCREENSHOT,
            if (bytes != null) "结果判定：截图已经邮箱/企微回传，请在控制端确认成功/失败"
            else "结果判定：截图失败，请在控制端确认成功/失败"
        )
        val deadline = System.currentTimeMillis() + waitSeconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            ShizukuVerifyCodeBus.consumeResultConfirm()?.let { return it }
            delay(500)
        }
        return null
    }

    /**
     * 只保留最近 3 张结果截图，删除更旧的（避免长期运行堆积占用存储）。
     * 注意：仅删除「非本次」的旧文件——本次截图可能正被异步邮件发送读取，不能删。
     */
    private fun pruneResultShots(keep: File) {
        runCatching {
            val dir = keep.parentFile ?: return
            dir.listFiles { f -> f.name.startsWith("sz_result_") && f.name.endsWith(".png") }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(3)
                ?.forEach { runCatching { it.delete() } }
        }
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
