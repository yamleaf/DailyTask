package com.pengxh.daily.app.shizuku

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * 经 Shizuku 执行 shell 命令（feat_shiziku）：
 * uiautomator dump / input / screencap / content query，全部走 shizuku 进程，无需无障碍权限。
 *
 * 统一通过 [ShizukuRuntime] 获取远程进程能力（官方 shizuku 或自定义 shizuku 双通道），
 * 上层不直接依赖具体接口类，便于两种 shizuku 平滑切换。
 *
 * 可靠性约定：
 *  - [exec] 带硬超时，超时 destroy 子进程并放弃结果（不再无限阻塞占用 IO 线程）；
 *  - 动作类命令（点击/滑动/输入）一律走 [execChecked]，以 echo 标记判定「命令确实跑完」，
 *    不再用「输出非 null」判定（空 stdout 也会非 null，会误报成功）；
 *  - 系统动画由 [disableAnimations]/[restoreAnimations] 成对管理，流程结束必恢复原值。
 */
object ShizukuShell {

    private const val TAG = "ShizukuShell"

    /** 单条命令默认超时（ms）；dump 因 uiautomator 等 idle 需更长时间，单独放宽 */
    private const val EXEC_TIMEOUT_MS = 20_000L
    private const val DUMP_TIMEOUT_MS = 40_000L

    /** 成功标记：命令跑完后 echo 出来，用于区分「执行成功」与「空输出」 */
    private const val OK_MARK = "__SZ_OK__"

    /** 系统动画设置键（自动化期间临时置 0，流程结束恢复） */
    private val ANIM_KEYS = arrayOf(
        "window_animation_scale",
        "transition_animation_scale",
        "animator_duration_scale"
    )
    /** 置 0 前的原值；非空表示当前处于「已临时关闭动画」状态 */
    private val savedAnim = HashMap<String, String>()

    /** 动画开关互斥（disable/restore 成对串行，防并发交错） */
    private val animMutex = Mutex()

    /**
     * 执行单条 shell 命令，返回 stdout 文本；未授权/失败/超时返回 null。
     *
     * 阻塞读在独立线程进行，主线程轮询 + [withTimeout] 守时；超时后 destroy 子进程
     * （destroy 会关闭管道 → 读端收到 EOF → 读线程自然退出），避免协程与线程双双挂死。
     */
    suspend fun exec(cmd: String, timeoutMs: Long = EXEC_TIMEOUT_MS): String? = withContext(Dispatchers.IO) {
        val process = ShizukuRuntime.newProcess(arrayOf("sh", "-c", cmd)) ?: return@withContext null
        var out: String? = null
        val reader = Thread({
            out = ShizukuRuntime.readOutput(process)
        }, "sz-exec").apply { isDaemon = true }
        reader.start()
        try {
            withTimeout(timeoutMs) {
                while (reader.isAlive) delay(100)
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "exec timeout[${timeoutMs}ms] destroyed: ${cmd.take(60)}")
            destroy(process)
            return@withContext null
        }
        out
    }

    /** 执行并校验：命令跑完且 echo 出标记才算成功（空输出一律视为失败） */
    private suspend fun execChecked(cmd: String, timeoutMs: Long = EXEC_TIMEOUT_MS): Boolean {
        val out = exec("{ $cmd ; } >/dev/null 2>&1; echo $OK_MARK", timeoutMs)
        return out?.contains(OK_MARK) == true
    }

    /** 尽力销毁远程子进程（不同 shizuku 版本的 IRemoteProcess 均有 destroy()） */
    private fun destroy(process: Any) {
        runCatching { process.javaClass.getMethod("destroy").invoke(process) }
            .onFailure { Log.w(TAG, "destroy failed: ${it.message}") }
    }

    // ═══════ 系统动画（成对管理，避免永久改变用户设备设置）═══════

    /**
     * 临时关闭系统动画并暂存原值（重复调用只记一次原值）。
     * 动画开启时 uiautomator/input 的时序不稳定，自动化期间需置 0。
     */
    suspend fun disableAnimations() = animMutex.withLock {
        if (savedAnim.isNotEmpty()) return@withLock // 已置过，避免二次覆盖成 0
        for (k in ANIM_KEYS) {
            val v = exec("settings get global $k")?.trim()
            // 未设置/解析失败时按系统默认 1.0 恢复，避免恢复成 0 或空值
            savedAnim[k] = v?.takeIf { it.isNotBlank() && it != "null" } ?: "1.0"
        }
        exec(
            "settings put global window_animation_scale 0; " +
                "settings put global transition_animation_scale 0; " +
                "settings put global animator_duration_scale 0"
        )
        Log.d(TAG, "animations disabled, saved=$savedAnim")
    }

    /** 恢复系统动画原值（流程结束必须调用，通常在 finally 中） */
    suspend fun restoreAnimations() = animMutex.withLock {
        if (savedAnim.isEmpty()) return@withLock
        val sb = StringBuilder()
        for (k in ANIM_KEYS) {
            sb.append("settings put global $k ${savedAnim[k] ?: "1.0"}; ")
        }
        sb.append("echo done")
        exec(sb.toString())
        Log.d(TAG, "animations restored: $savedAnim")
        savedAnim.clear()
    }

    /**
     * 亮屏 + 解锁进系统（执行 Shizuku 动作前强制调用）。
     * 只覆盖两种场景：
     *  - 亮屏后直接进系统（无锁屏）：dismiss-keyguard 直接点亮进入
     *  - 亮屏 + 上滑解锁进系统（无密码 slide 锁屏）：WAKEUP → 上滑手势兜底
     * 不处理 SIM PIN / 图案 / 数字密码锁屏。
     *
     * 注意：系统动画的关闭/恢复不由这里管理，由调用方用 disableAnimations/restoreAnimations 成对处理。
     *
     * @param wakeIfNeeded 息屏时才 KEYCODE_WAKEUP 亮屏，亮屏时不重复唤醒
     * @param swipeIfNeeded 锁屏界面才上滑解锁，亮屏且已解锁时不上滑
     */
    suspend fun wakeAndUnlock(wakeIfNeeded: Boolean = true, swipeIfNeeded: Boolean = true) {
        val sb = StringBuilder()
        if (wakeIfNeeded) sb.append("input keyevent KEYCODE_WAKEUP; ")
        sb.append("wm dismiss-keyguard; ")
        if (swipeIfNeeded) sb.append("input swipe 540 1900 540 300 300; ")
        sb.append("echo done")
        exec(sb.toString())
        delay(600)
        // 第二次 dismiss 兜底（部分 ROM 首调不上屏）
        exec("wm dismiss-keyguard")
        delay(400)
    }

    /**
     * dump 当前前台窗口节点树为 XML（快路径 + timeout 硬超时 + 多次重试）。
     * - 真机上 uiautomator 等 UI idle 本身就要 8~12s，timeout 设为 15s 让其自然完成，
     *   避免 8s 过早 kill 拿到半截文件；页面 ongoing 动画时同策略下失败即返回 null。
     * - 先清旧文件再 dump，避免读残留 XML 误判；不用 --compressed（压缩模式会过滤大段文本节点，
     *   导致短信正文等长文本缺失，须完整 dump 才能采到）。
     * - uiautomator 偶发「null root node」（唤醒/转场未 idle 时），重试 3 次、间隔 800ms；
     *   再失败回退一次全量 dump。
     */
    suspend fun dumpUiXml(): String? {
        suspend fun quick(): String? {
            val xml = exec(
                "rm -f /data/local/tmp/sz_ui.xml; " +
                    "timeout 15 uiautomator dump /data/local/tmp/sz_ui.xml >/dev/null 2>&1; " +
                    "cat /data/local/tmp/sz_ui.xml",
                DUMP_TIMEOUT_MS
            )
            return xml?.takeIf { it.startsWith("<") && it.length > 64 }
        }

        val start = System.currentTimeMillis()
        repeat(3) { i ->
            quick()?.let {
                Log.d(TAG, "dump ok(attempt=${i + 1}) cost=[${System.currentTimeMillis() - start}]ms")
                return it
            }
            // 偶发 null root（唤醒/转场未 idle），等待后重试
            if (i < 2) delay(800)
        }
        // 回退全量 dump（个别机型/场景 compressed 或 timeout 不可用）
        val full = exec(
            "rm -f /data/local/tmp/sz_ui.xml; " +
                "uiautomator dump /data/local/tmp/sz_ui.xml >/dev/null 2>&1; " +
                "cat /data/local/tmp/sz_ui.xml",
            DUMP_TIMEOUT_MS
        )
        Log.d(TAG, "dump fail/full cost=[${System.currentTimeMillis() - start}]ms full=${full?.length ?: 0}")
        return full?.takeIf { it.startsWith("<") && it.length > 64 }
    }

    /**
     * 模拟人工点击：同坐标短时长 swipe（约 80ms），比 input tap 更接近真实手指，降低被检测风险。
     * 支持 range（像素）：>0 时在 (x±range, y±range) 内随机取点，避免每次点击位置完全相同、
     * 看起来更接近真人。需 Shizuku 已就绪。
     */
    suspend fun tap(x: Int, y: Int, range: Int = 0): Boolean {
        val dx = if (range > 0) (Math.random() * 2 - 1) * range else 0.0
        val dy = if (range > 0) (Math.random() * 2 - 1) * range else 0.0
        val rx = (x + dx).toInt()
        val ry = (y + dy).toInt()
        return execChecked("input swipe $rx $ry $rx $ry 80; sleep 0.1")
    }

    /**
     * 输入文本（**支持中文**）。
     * - 纯可打印 ASCII 且不含空格：走 `input text`（最快，不触碰剪贴板）；
     * - 其余（含中文 / 空格 / 特殊符号）：走「剪贴板 + KEYCODE_PASTE」。
     *
     * 原因：Android 的 `input text` 基于 KeyCharacterMap 逐个投递按键事件，**不支持非 ASCII**，
     * 中文/全角字符必然输入失败，因此非 ASCII 必须借剪贴板粘贴。
     * 粘贴后立刻清空剪贴板，避免密码/验证码残留在系统剪贴板被其他 App 读取。
     */
    suspend fun inputText(text: String): Boolean {
        if (text.isBlank()) return false
        return if (isAsciiInput(text)) {
            val escaped = escape(text)
            if (execChecked("input text '$escaped'; sleep 0.1")) true
            else inputViaClipboard(text) // ASCII 路径异常时回退粘贴
        } else {
            inputViaClipboard(text)
        }
    }

    /** `input text` 可安全投递的字符集：可打印 ASCII（0x21~0x7E），不含空格 */
    private fun isAsciiInput(text: String): Boolean = text.all { it.code in 0x21..0x7E }

    /** shell 单引号转义（支持任意 UTF-8 文本，含中文与单引号本身） */
    private fun escape(text: String): String = text.replace("'", "'\\''")

    /** 剪贴板 + 粘贴：非 ASCII / 含空格文本的唯一可行输入方式 */
    private suspend fun inputViaClipboard(text: String): Boolean {
        val escaped = escape(text)
        // Android 10+ 用 cmd clipboard；写失败（Unknown command / Error）回退 service call
        val set = exec("cmd clipboard set-text '$escaped' 2>&1; echo $OK_MARK")
        if (set == null || !set.contains(OK_MARK) ||
            set.contains("Unknown command") || set.contains("Error")
        ) {
            exec("service call clipboard 2 s16 '$escaped' >/dev/null 2>&1")
        }
        // KEYCODE_PASTE=279；粘贴前给剪贴板服务一点落盘时间
        val ok = execChecked("sleep 0.2; input keyevent 279; sleep 0.3")
        // 隐私：无论成败都清空剪贴板（密码/验证码不残留）
        exec("cmd clipboard set-text '' >/dev/null 2>&1")
        Log.d(TAG, "inputViaClipboard ok=$ok len=${text.length}")
        return ok
    }

    /**
     * 弧线滑动：起点 (x1,y1) → 终点 (x2,y2)，用二次贝塞尔插值分段 `input swipe` 逼近，
     * 中间轨迹带弧度更接近人手，**终点精确落在 (x2,y2) 不偏**。
     * @param segments 分段数（默认 6，越多越平滑）；每段固定 60ms，总时长 ~segments*60ms
     */
    suspend fun gesture(x1: Int, y1: Int, x2: Int, y2: Int, segments: Int = 6): Boolean {
        // 控制点：起点与终点连线垂直方向的随机偏移，让弧度方向每次略有不同
        val dx = x2 - x1
        val dy = y2 - y1
        val dist = Math.hypot(dx.toDouble(), dy.toDouble()).coerceAtLeast(1.0)
        // 垂直偏移幅值 = 距离的 10%~25%，方向随机（上/下）
        val amp = dist * (0.10 + Math.random() * 0.15)
        val ctrlX = (x1 + x2) / 2 + ((-dy / dist) * amp * (if (Math.random() < 0.5) -1.0 else 1.0)).toInt()
        val ctrlY = (y1 + y2) / 2 + ((dx / dist) * amp * (if (Math.random() < 0.5) -1.0 else 1.0)).toInt()
        // 首段从起点出发、末段收在终点，中间按二次贝塞尔插值
        var prevX = x1
        var prevY = y1
        val sb = StringBuilder()
        for (i in 1..segments) {
            val t = i.toDouble() / segments
            val mt = 1 - t
            val ix = (mt * mt * x1 + 2 * mt * t * ctrlX + t * t * x2).toInt()
            val iy = (mt * mt * y1 + 2 * mt * t * ctrlY + t * t * y2).toInt()
            if (i == segments) { // 末段强制终点精确
                sb.append("input swipe $prevX $prevY $x2 $y2 60; ")
            } else {
                sb.append("input swipe $prevX $prevY $ix $iy 60; ")
                prevX = ix; prevY = iy
            }
        }
        return execChecked(sb.toString())
    }

    /** 截图字节流（PNG，经 /data/local/tmp 中转，读完即删，不留垃圾文件） */
    suspend fun screenshotBytes(): ByteArray? = withContext(Dispatchers.IO) {
        val tmp = "/data/local/tmp/sz_s.png"
        val shoot = ShizukuRuntime.newProcess(arrayOf("sh", "-c", "screencap -p $tmp")) ?: return@withContext null
        runCatching { shoot.javaClass.getMethod("waitFor").invoke(shoot) }
        val cat = ShizukuRuntime.newProcess(arrayOf("sh", "-c", "cat $tmp; rm -f $tmp")) ?: return@withContext null
        ShizukuRuntime.readBytes(cat)
    }

    /** 读短信（验证码自读，Android 16 需验证 shell 仍可读） */
    suspend fun querySms(): String? = exec(
        "content query --uri content://sms/inbox --projection address,body,date --sort \"date desc\" limit 5"
    )
}
