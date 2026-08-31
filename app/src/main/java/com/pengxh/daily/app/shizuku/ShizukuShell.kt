package com.pengxh.daily.app.shizuku

import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * 经 Shizuku 执行 shell 命令（feat_shiziku）：
 * uiautomator dump / input / screencap / content query，全部走 shizuku 进程，无需无障碍权限。
 */
object ShizukuShell {

    /** 执行单条 shell 命令，返回 stdout 文本；未授权/失败返回 null */
    suspend fun exec(cmd: String): String? = withContext(Dispatchers.IO) {
        if (!ShizukuManager.isGranted()) return@withContext null
        runCatching {
            val pfd: ParcelFileDescriptor? = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
                ?: return@runCatching null
            val text = ParcelFileDescriptor.AutoCloseInputStream(pfd.fileDescriptor)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
            runCatching { pfd.close() }
            text
        }.getOrNull()
    }

    /**
     * dump 当前前台窗口节点树为 XML。
     * 注意：shell UID 写不了 App 私有目录 → 先写 /data/local/tmp 再 cat 读回字节流。
     */
    suspend fun dumpUiXml(): String? {
        exec("uiautomator dump /data/local/tmp/sz_ui.xml") ?: return null
        return exec("cat /data/local/tmp/sz_ui.xml")
    }

    suspend fun tap(x: Int, y: Int): Boolean = exec("input tap $x $y") != null

    /** 输入文本：单引号包裹 + 转义，兼容中文与特殊字符 */
    suspend fun inputText(text: String): Boolean {
        if (text.isBlank()) return false
        val escaped = text.replace("'", "'\\''")
        return exec("input text '$escaped'") != null
    }

    /** 截图字节流（PNG，经 /data/local/tmp 中转） */
    suspend fun screenshotBytes(): ByteArray? = withContext(Dispatchers.IO) {
        if (!ShizukuManager.isGranted()) return@withContext null
        runCatching {
            Shizuku.newProcess(arrayOf("sh", "-c", "screencap -p /data/local/tmp/sz_s.png"), null, null)
                ?: return@runCatching null
            val pfd = Shizuku.newProcess(arrayOf("sh", "-c", "cat /data/local/tmp/sz_s.png"), null, null)
                ?: return@runCatching null
            val bytes = ParcelFileDescriptor.AutoCloseInputStream(pfd.fileDescriptor).use { it.readBytes() }
            runCatching { pfd.close() }
            bytes
        }.getOrNull()
    }

    /** 读短信（验证码自读，Android 16 需验证 shell 仍可读） */
    suspend fun querySms(): String? = exec(
        "content query --uri content://sms/inbox --projection address,body,date --sort \"date desc\" limit 5"
    )
}
