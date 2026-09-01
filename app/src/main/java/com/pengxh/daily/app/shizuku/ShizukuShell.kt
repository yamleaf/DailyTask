package com.pengxh.daily.app.shizuku

import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku

/**
 * 经 Shizuku 执行 shell 命令（feat_shiziku）：
 * uiautomator dump / input / screencap / content query，全部走 shizuku 进程，无需无障碍权限。
 *
 * 全部使用 Shizuku 官方公开 API（无反射、无私有成员依赖，公版兼容）：
 *   Shizuku.getBinder() → IShizukuService.Stub.asInterface() → newProcess() → IRemoteProcess
 *   （IRemoteProcess 为 aidl 公开接口，getInputStream 返回 ParcelFileDescriptor）
 */
object ShizukuShell {

    private const val TAG = "ShizukuShell"

    /** 获取远程进程：经公开 API 启动 shell 进程；未授权/binder 缺失返回 null */
    private fun newProcess(cmd: Array<String>): IRemoteProcess? {
        if (!ShizukuManager.isGranted()) return null
        val binder = Shizuku.getBinder() ?: return null
        return runCatching {
            IShizukuService.Stub.asInterface(binder).newProcess(cmd, null, null)
        }.getOrNull()
    }

    /** 读取远程进程 stdout 全部文本 */
    private fun readOutput(process: IRemoteProcess): String? = runCatching {
        val pfd: ParcelFileDescriptor = process.getInputStream()
        ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrNull()

    /** 读取远程进程 stdout 全部字节（截图用） */
    private fun readBytes(process: IRemoteProcess): ByteArray? = runCatching {
        val pfd: ParcelFileDescriptor = process.getInputStream()
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }.getOrNull()

    /** 执行单条 shell 命令，返回 stdout 文本；未授权/失败返回 null */
    suspend fun exec(cmd: String): String? = withContext(Dispatchers.IO) {
        val p = newProcess(arrayOf("sh", "-c", cmd)) ?: return@withContext null
        readOutput(p)
    }

    /**
     * 亮屏 + 解锁进系统（执行 Shizuku 动作前强制调用）。
     * 只覆盖两种场景：
     *  - 亮屏后直接进系统（无锁屏）：dismiss-keyguard 直接点亮进入
     *  - 亮屏 + 上滑解锁进系统（无密码 slide 锁屏）：WAKEUP → 上滑手势兜底
     * 不处理 SIM PIN / 图案 / 数字密码锁屏。
     *
     * @param wakeIfNeeded 息屏时才 KEYCODE_WAKEUP 亮屏，亮屏时不重复唤醒
     * @param swipeIfNeeded 锁屏界面才上滑解锁，亮屏且已解锁时不上滑
     */
    suspend fun wakeAndUnlock(wakeIfNeeded: Boolean = true, swipeIfNeeded: Boolean = true) {
        // 单条 shell 合并：关系统动画 →（息屏时）亮屏 → dismiss-keyguard →（锁屏时）上滑兜底
        val sb = StringBuilder()
        sb.append("settings put global window_animation_scale 0; ")
        sb.append("settings put global transition_animation_scale 0; ")
        sb.append("settings put global animator_duration_scale 0; ")
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
            val xml = exec("rm -f /data/local/tmp/sz_ui.xml; " +
                "timeout 15 uiautomator dump /data/local/tmp/sz_ui.xml >/dev/null 2>&1; " +
                "cat /data/local/tmp/sz_ui.xml")
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
        val full = exec("rm -f /data/local/tmp/sz_ui.xml; " +
            "uiautomator dump /data/local/tmp/sz_ui.xml >/dev/null 2>&1; " +
            "cat /data/local/tmp/sz_ui.xml")
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
        return exec("input swipe $rx $ry $rx $ry 80") != null
    }

    /** 输入文本：单引号包裹 + 转义，兼容中文与特殊字符 */
    suspend fun inputText(text: String): Boolean {
        if (text.isBlank()) return false
        val escaped = text.replace("'", "'\\''")
        return exec("input text '$escaped'") != null
    }

    /** 截图字节流（PNG，经 /data/local/tmp 中转） */
    suspend fun screenshotBytes(): ByteArray? = withContext(Dispatchers.IO) {
        val shoot = newProcess(arrayOf("sh", "-c", "screencap -p /data/local/tmp/sz_s.png")) ?: return@withContext null
        runCatching { shoot.waitFor() }
        val cat = newProcess(arrayOf("sh", "-c", "cat /data/local/tmp/sz_s.png")) ?: return@withContext null
        readBytes(cat)
    }

    /** 读短信（验证码自读，Android 16 需验证 shell 仍可读） */
    suspend fun querySms(): String? = exec(
        "content query --uri content://sms/inbox --projection address,body,date --sort \"date desc\" limit 5"
    )
}
