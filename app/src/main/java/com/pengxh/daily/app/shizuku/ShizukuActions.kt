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
import com.pengxh.daily.app.utils.LogFileManager
import com.pengxh.daily.app.utils.MessageDispatcher
import com.yample.mqttprotocol.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
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
    private const val MODE_VERIFY_LOGIN = "verify_login"
    private const val MODE_CUSTOM_1 = "custom_1"

    /**
     * 动作互斥锁：登录 / 身份验证 / 模拟打卡共用同一套 shell 点击通道，
     * 并发执行会互相干扰（A 的点击落到 B 的页面上），故同一时刻只允许一个动作在跑。
     * 占用时直接反馈「跳过」而不是排队等待——排队会让控制端在数十秒内收不到任何反馈。
     */
    private val actionMutex = Mutex()

    // ═══════ 执行状态跟踪（供 DT 本地「高级设置」页展示 / 手动终止）═══════
    /** 当前正在执行的操作协程 Job；null=空闲。终止即 cancel，步骤/等待循环在 delay 处抛 CancellationException 中断 */
    @Volatile
    private var runningJob: Job? = null

    /** 当前操作展示名（密码登录/身份验证/模拟打卡…），仅 runningJob 非空时有效 */
    @Volatile
    private var runningTag: String = ""

    /** 当前执行到的步骤（1 起）；0=尚未进入步骤循环 */
    @Volatile
    private var currentStep: Int = 0

    /** 当前操作总步骤数 */
    @Volatile
    private var totalSteps: Int = 0

    /** 是否有 Shizuku 操作正在执行 */
    val isRunning: Boolean get() = runningJob?.isActive == true

    /**
     * 执行进度快照（供 UI 轮询）：空闲返回 null；执行中返回 (操作名, "第x/y步")。
     */
    fun progressSnapshot(): Pair<String, String>? {
        val job = runningJob
        return if (job?.isActive == true) {
            runningTag to if (totalSteps > 0) "第$currentStep/$totalSteps 步" else "启动中"
        } else null
    }

    /**
     * 手动终止当前执行：cancel 协程 Job，步骤循环 / 验证码等待 / 短信采集等待 / 结果确认等待
     * 均在 delay 挂起点中断并抛 CancellationException → execute() 的 finally 恢复动画 + 释放互斥锁。
     * 空闲/无执行时无操作（幂等）。
     */
    fun stopCurrent() {
        val job = runningJob
        if (job?.isActive == true) {
            Log.d(TAG, "手动终止当前 Shizuku 操作: $runningTag")
            job.cancel()
        }
    }

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

    /** 控制端下发「验证码登录」后调用（独立按钮，走验证码登录步骤） */
    fun runVerifyLogin(context: Context, scope: CoroutineScope) {
        scope.launch { execute(context, MODE_VERIFY_LOGIN) }
    }

    /** 控制端下发「自定义操作1」后调用 */
    fun runCustomAction1(context: Context, scope: CoroutineScope) {
        scope.launch { execute(context, MODE_CUSTOM_1) }
    }

    private suspend fun execute(context: Context, mode: String) {
        val resultType = when (mode) {
            MODE_LOGIN, MODE_VERIFY_LOGIN -> Protocol.ALERT_TYPE_LOGIN_RESULT
            MODE_VERIFY -> Protocol.ALERT_TYPE_VERIFY_RESULT
            MODE_CUSTOM_1 -> Protocol.ALERT_TYPE_CUSTOM_RESULT
            else -> Protocol.ALERT_TYPE_SIMULATE_PUNCH_RESULT
        }
        val tag = when (mode) {
            MODE_LOGIN -> "密码登录"
            MODE_VERIFY_LOGIN -> "验证码登录"
            MODE_VERIFY -> "身份验证"
            MODE_CUSTOM_1 -> ShizukuConfigStore.opName1()
            else -> "模拟打卡"
        }
        Log.d(TAG, "Shizuku $tag 动作开始 mode=$mode")

        if (!actionMutex.tryLock()) {
            feedback(resultType, "跳过：已有 Shizuku 操作正在执行，请稍后再试")
            return
        }
        // 登记当前执行状态（供「高级设置」页展示进度 + 手动终止）
        runningJob = coroutineContext[Job]
        runningTag = tag
        currentStep = 0
        totalSteps = 0
        try {
            runFlow(context, mode, resultType, tag)
        } catch (e: CancellationException) {
            // 手动终止（stopCurrent）：步骤/等待循环在 delay 处中断并抛 CancellationException。
            // 仅在 runningJob 仍指向本协程（即确实是被 stopCurrent 终止）时反馈「已终止」；
            // 宿主 scope 整体取消（如 MqttAgentService 销毁）不打扰反馈，随后统一按取消语义传播。
            if (runningJob === coroutineContext[Job]) {
                Log.d(TAG, "Shizuku $tag 动作被手动终止")
                feedback(resultType, "$tag·已手动终止")
            }
            throw e
        } finally {
            // 无论成败/异常/手动终止都恢复系统动画并释放锁：绝不能把用户设备的动画永久置 0
            runCatching { ShizukuShell.restoreAnimations() }
            actionMutex.unlock()
            // 清空执行状态（手动终止经 CancellationException 走 finally 同样清理）
            runningJob = null
            runningTag = ""
            currentStep = 0
            totalSteps = 0
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
            MODE_LOGIN -> ShizukuConfigStore.pwdLoginSteps()
            MODE_VERIFY_LOGIN -> ShizukuConfigStore.verifyLoginSteps()
            MODE_VERIFY -> ShizukuConfigStore.authSteps()
            MODE_PUNCH -> ShizukuConfigStore.punchSteps()
            MODE_CUSTOM_1 -> ShizukuConfigStore.custom1Steps()
            else -> ShizukuConfigStore.punchSteps()
        }
        if (steps.isEmpty()) {
            feedback(resultType, "跳过：${tag}步骤未配置")
            return
        }
        // 供进度展示：步骤总数
        totalSteps = steps.size
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
            MODE_VERIFY_LOGIN -> ShizukuConfigStore.verifyWaitSeconds()
            MODE_VERIFY -> ShizukuConfigStore.authWaitSeconds()
            MODE_CUSTOM_1 -> ShizukuConfigStore.verifyWaitSeconds()
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
            // 供进度展示：当前执行到的步骤号（1 起）
            currentStep = index + 1
            Log.d(TAG, "step[${index + 1}] wait=${waitMs}ms(type=${step.type}, coords=(${step.x},${step.y}), range=${step.range})")
            when (step.type) {
                ShizukuStepType.CLICK -> {
                    if (!ShizukuShell.tap(step.x, step.y, step.range)) return "$tag·失败：第 ${index + 1} 步点击失败"
                }

                ShizukuStepType.SWIPE -> {
                    if (!ShizukuShell.gesture(step.x, step.y, step.x2, step.y2)) return "$tag·失败：第 ${index + 1} 步滑动失败"
                }

                ShizukuStepType.PWD_INPUT -> {
                    // 每步独立密码优先，未配置回退全局密码
                    val pwd = ShizukuConfigStore.stepPassword(step.uid)
                        .ifBlank { ShizukuConfigStore.password() }
                    if (pwd.isBlank()) return "$tag·失败：密码未配置"
                    if (!ShizukuShell.tap(step.x, step.y, step.range)) return "$tag·失败：第 ${index + 1} 步点击失败"
                    delay(300)
                    if (!ShizukuShell.inputText(pwd)) return "$tag·失败：密码输入失败"
                }

                ShizukuStepType.CODE_INPUT -> {
                    if (code == null) {
                        // 每步独立超时优先，0 回退该操作全局超时
                        val wait = if (step.codeWait > 0) step.codeWait else waitSeconds
                        code = waitForVerifyCode(wait) ?: return "$tag·超时：等待验证码超时"
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
                    val wait = if (step.codeWait > 0) step.codeWait else waitSeconds
                    if (!captureSmsAndWaitSent(step, wait)) return "$tag·超时：等待短信发送确认超时"
                }

                ShizukuStepType.RESULT_CHECK -> {
                    // 方案 B：截图回传后立即收尾（不等待控制端人工确认——成败由用户查看
                    // 邮件/企微截图自行判定），避免流程悬挂「执行中」，操作完即回空闲
                    return if (captureAndSendResultShot(context)) {
                        "$tag·完成（结果截图已回传邮箱/企微，请查看确认登录状态）"
                    } else {
                        "$tag·失败：结果截图获取失败，请人工检查登录状态"
                    }
                }
            }
        }
        return "$tag·成功"
    }

    /**
     * 钉钉验证码登录（CODE_CAPTURE）：解析页面提取「短信发送内容 + 收件人」上报控制端（ALERT_TYPE_SMS_CAPTURE），
     * 等待「短信已发送且生效」后返回 true，超时返回 false。
     *
     * 采集锚点三层递进，全部锚定「短信内容本身」，不猜 UI 布局：
     *  1) resource-id 精确锚定（当前钉钉页面结构）：
     *     正文节点 rid 含 sms_up_tv_sms_token → text 即短信正文（钉钉登录#IWYYA）；
     *     收件人节点 rid 含 sms_up_tv_sms_target_mobile → text 即收件人（10690760295102）。
     *  2) 短信模板关键字兜底（rid 失效/版本变化时，靠短信模板内容定位——模板由钉钉服务端
     *     下发，接入号与正文前缀极稳定，UI 怎么改布局都不影响）：
     *     - kw1 默认「1069076」（钉钉上行接入号前缀）→ 定位含该号的节点取收件人号码；
     *     - kw2 默认「钉钉登录」（短信正文前缀）→ 定位含该文本的节点取短信正文。
     *  3) 步骤配置可显式填 kw1/kw2 覆盖默认（接入号或短信模板变更时人工指定新关键字）。
     *  兜底仍全失时收件人走全文号码正则 1\d{10,}；正文标记「待人工填写」。
     *
     * 「已生效」判定（短信由控制端发出，钉钉服务器验证后本机自动登录跳转）：
     *  - 进入采集时动态记录顶层 Activity（发送短信验证页）；验证页离开 = 短信生效、登录跳转，
     *    等待期间周期自检，命中即自动继续（控制端忘点「已完成」也能走通）；
     *  - 收到控制端「已完成」（FIELD_SMS_SENT）时先校验验证页是否已跳转：已跳转/无法判定即接受；
     *    未跳转（短信疑似未生效/误点）则宽限等待跳转，仍未跳转忽略该确认继续等待；
     *  - exec/解析顶层失败（null）时自检与校验自动降级，仅靠手动确认 + 超时（兼容旧行为）。
     */
    private suspend fun captureSmsAndWaitSent(step: ShizukuStep, waitSeconds: Int): Boolean {
        ShizukuVerifyCodeBus.reset()
        val xml = ShizukuShell.dumpUiXml() ?: return false
        val nodes = UiNodeParser.parse(xml)

        // ① rid 精确锚定（仅用于当前钉钉页面结构已知的节点）
        fun ridText(idContains: String): String? = nodes.firstOrNull {
            it.resourceId?.contains(idContains) == true
        }?.text?.trim()?.takeIf { it.isNotBlank() }
        val ridContent = ridText("sms_up_tv_sms_token")
        val ridRecipient = ridText("sms_up_tv_sms_target_mobile")

        // ② 短信模板关键字兜底：kw 未配置时用钉钉模板默认值（接入号前缀 / 正文前缀）。
        //    kw 定位取「text 含关键字」的节点——独立节点布局下正文/号码就是节点文本本身；
        //    若取到的是聚合文本（发送…复制 同行），做轻量清洗还原正文。
        val kwRecipient = step.kw1.ifBlank { DEFAULT_SMS_RECIPIENT_KW }
        val kwContent = step.kw2.ifBlank { DEFAULT_SMS_CONTENT_KW }
        val phoneRegex = Regex("""1\d{10,}""")
        val phoneOf = { t: String? -> t?.let { phoneRegex.find(it)?.value } }
        val recipient = ridRecipient
            ?: nodes.firstOrNull { it.text?.contains(kwRecipient) == true }?.text?.let { phoneOf(it) }
            ?: nodes.firstOrNull { phoneRegex.containsMatchIn(it.text.orEmpty()) }?.text?.let { phoneOf(it) }

        // 正文轻量清洗：仅剥离钉钉 UI 的「发送 」前缀与「复制」尾标签，不动正文本身
        fun contentFrom(line: String): String = line.trim()
            .replace(Regex("""^发送\s+"""), "")
            .replace(Regex("""\s*(复制|Copy|点此复制|拷贝)\s*$"""), "")
            .trim()
        val content = ridContent
            ?: nodes.firstOrNull { it.text?.contains(kwContent) == true }?.text
                ?.let { contentFrom(it) }?.takeIf { it.isNotBlank() }
            ?: "请将登录验证码短信发送至收件人"

        val json = org.json.JSONObject().apply {
            put("content", content)
            put("recipient", recipient ?: "待定")
        }
        feedback(Protocol.ALERT_TYPE_SMS_CAPTURE, json.toString())

        // —— 短信生效自动信号（防控制端忘点「已完成」卡死 + 防误点提前执行）——
        // 短信由控制端发出，钉钉服务器收到验证后被控端钉钉自动登录跳转 → 顶层 Activity
        // 离开「发送短信验证页」即登录成功。进入采集时动态记录当前顶层（不硬编码页面类名），
        // 等待期间周期自检 + 收到「已完成」时也先校验是否真的跳转。exec/解析失败（null）时
        // 相应判定自动降级：自检跳过、手动确认直接接受（人工信号优先），不阻塞原流程。
        val baselineTop = topResumedComponent()
        if (baselineTop != null) {
            LogFileManager.writeLog("短信采集：记录验证页顶层 $baselineTop，登录跳转将自动继续")
        } else {
            LogFileManager.writeLog("短信采集：无法读取顶层 Activity，将依赖控制端「已完成」确认")
        }
        var lastTopProbe = 0L
        val deadline = System.currentTimeMillis() + waitSeconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            // 控制端「已完成」确认：先校验验证页是否已跳转，防止短信未生效时误点导致后续步骤提前执行
            if (ShizukuVerifyCodeBus.consumeSmsSent()) {
                val still = if (baselineTop != null) topStill(baselineTop) else null
                if (still != true) {
                    // 已跳转 / 无法判定（exec 失败）→ 接受确认
                    if (baselineTop != null && still == false) {
                        LogFileManager.writeLog("短信采集：收到「已完成」且验证页已跳转 → 继续")
                    }
                    return true
                }
                // 验证页仍在：短信可能未送达/服务器未处理，短暂宽限再验，期间周期自检
                LogFileManager.writeLog("短信采集：收到「已完成」但验证页未跳转（短信疑似未生效/误点），宽限等待跳转")
                val graceEnd = System.currentTimeMillis() + SMS_GRACE_MS
                // baselineTop 此处为 String?（编译器不跨变量推断 still==true ⇒ 非空），while 条件判空触发 smart cast
                while (baselineTop != null && System.currentTimeMillis() < graceEnd) {
                    if (topStill(baselineTop) == false) return true
                    delay(500)
                }
                LogFileManager.writeLog("短信采集：宽限后验证页仍未跳转，忽略该确认，继续等待")
            }
            // 周期自检：验证页离开 → 短信已生效、登录跳转 → 自动继续
            val now = System.currentTimeMillis()
            if (baselineTop != null && now - lastTopProbe >= TOP_PROBE_INTERVAL_MS) {
                lastTopProbe = now
                if (topStill(baselineTop) == false) {
                    LogFileManager.writeLog("短信采集：检测到验证页已离开（短信生效、登录跳转）→ 自动继续")
                    return true
                }
            }
            delay(500)
        }
        return false
    }

    /**
     * 当前顶层 resumed Activity 的 component（如 com.alibaba.android.rimet/.xxx.SendMsmVerifyV2Activity）。
     * 经 `dumpsys activity activities` 解析；exec 失败或格式不识别返回 null（调用方按无法判定降级）。
     */
    private suspend fun topResumedComponent(): String? {
        val out = ShizukuShell.exec(
            "dumpsys activity activities 2>/dev/null | grep -m1 -E 'topResumedActivity|mResumedActivity'",
            timeoutMs = TOP_DUMP_TIMEOUT_MS
        ) ?: return null
        return Regex(
            """(?:topResumedActivity|mResumedActivity)[=:]\s*ActivityRecord\{[^}]*? u0\s+(\S+)"""
        ).find(out)?.groupValues?.get(1)
    }

    /**
     * 当前顶层是否仍是 baseline（尚未离开验证页）。
     * @return true=仍在；false=已切换；null=exec/解析失败无法判定（调用方按场景降级）
     */
    private suspend fun topStill(baseline: String): Boolean? {
        val cur = topResumedComponent() ?: return null
        return cur == baseline
    }

    /** 钉钉短信采集默认关键字（kw 未配置时兜底；短信模板由钉钉服务端下发，接入号/正文前缀稳定） */
    private const val DEFAULT_SMS_RECIPIENT_KW = "1069076"
    private const val DEFAULT_SMS_CONTENT_KW = "钉钉登录"

    /** 短信生效自检：顶层 Activity 轮询间隔（ms）与单次 dumpsys 超时 */
    private const val TOP_PROBE_INTERVAL_MS = 2000L
    private const val TOP_DUMP_TIMEOUT_MS = 3000L

    /** 收到「已完成」但验证页未跳转时的宽限等待（ms）：容忍短信送达/服务器处理延迟 */
    private const val SMS_GRACE_MS = 5000L

    /**
     * 结果判定（RESULT_CHECK，作为最后一个步骤）：截图回传后立即收尾，不再等待控制端人工确认。
     * 1) Shizuku 截图（screencap）保存到本地文件；
     * 2) 经消息渠道（邮箱附件/企微图片）回传用户——**不走 MQTT，避免大包浪费额度**；
     * 3) alert 通知控制端结果已回传（ALERT_TYPE_RESULT_SCREENSHOT，仅文本）；
     * 4) 成败由用户查看邮件/企微截图自行判定，DT 不再 while 等待 FIELD_RESULT_CONFIRM——
     *    避免截图已回传、无人点确认时流程悬挂在「执行中」状态。
     * @return 截图是否成功回传（true=已回传可人工判定；false=截图失败，流程按失败收尾）
     */
    private suspend fun captureAndSendResultShot(context: Context): Boolean {
        ShizukuVerifyCodeBus.reset()
        val bytes = ShizukuShell.screenshotBytes()
        var sent = false
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
                sent = true
            }
        }
        feedback(
            Protocol.ALERT_TYPE_RESULT_SCREENSHOT,
            if (sent) "结果判定：截图已经邮箱/企微回传，请查看附件确认登录是否成功"
            else "结果判定：截图失败，请人工检查登录状态"
        )
        return sent
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
