package com.pengxh.daily.app.utils

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.util.Base64
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.JsonParser
import com.pengxh.daily.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 检查更新（Gitee 公开镜像仓库托管，版本文件加密公开）：
 * 1. 拉取仓库 updates/v_task.dat（公开 raw，无需令牌）——内容是 Base64(XOR(json)) 密文
 * 2. XOR 解密 → Gson 解析；versionCode 对比（CI alpha 构建的时间戳版本号单调递增）
 * 3. 有新版 → 弹窗 → 下载 Release 附件（AES-256-CBC 加密的 APK 密文）
 * 4. AES 解密还原明文 APK（密钥 = SHA-256(VERSION_KEY)，IV = 16 字节 0）→ MD5 校验 → 安装
 *
 * 与 CI 脚本 scripts/publish_gitee.py 完全对称。零新依赖（复用 OkHttp/Gson/material；
 * AES 用 Android 内置 javax.crypto）。
 */
object UpdateChecker {

    // ===== Gitee 公开镜像配置（必须与 CI workflow 的 GITEE_OWNER/GITEE_REPO 一致）=====
    private const val GITEE_OWNER = "yamleaf"
    private const val GITEE_REPO = "DailyTask"
    private const val DATA_PATH = "updates/v_task.dat"

    /** XOR 密钥：必须与 CI Secret VERSION_KEY 一致；AES 的 APK 密钥由其 SHA-256 派生 */
    private val VERSION_KEY: ByteArray by lazy {
        "DailyTaskUpdateKey2026!".toByteArray(Charsets.UTF_8)
    }

    data class VersionInfo(
        val v: Int,
        val vn: String,
        val apk: String,
        val md5: String?,
        val force: Boolean,
        val note: String,
        val enc: String?, // "aes256" = APK 附件为 AES-256-CBC 密文，需解密后安装
    )

    /**
     * 检查更新。
     * @param showNoUpdateToast 无更新/失败时是否 Toast 提示（手动点击入口传 true，静默检查传 false）
     * @return true=发现新版本并已弹窗
     */
    suspend fun check(context: Context, showNoUpdateToast: Boolean = false): Boolean {
        return withContext(Dispatchers.IO) {
            runCatching {
                parse(decrypt(fetchVersionFile()))
            }.getOrNull()?.let { info ->
                if (info.v > BuildConfig.VERSION_CODE) {
                    withContext(Dispatchers.Main) { showUpdateDialog(context, info) }
                    true
                } else {
                    if (showNoUpdateToast) toast(context, "当前已是最新版本")
                    false
                }
            } ?: run {
                if (showNoUpdateToast) toast(context, "检查更新失败，请稍后重试")
                false
            }
        }
    }

    // ═══════════════════════ 拉取与解密（与 publish_gitee.py 对称）═══════════════════════

    /** 公开仓库 raw，无需令牌 */
    private fun fetchVersionFile(): String {
        val url = "https://gitee.com/$GITEE_OWNER/$GITEE_REPO/raw/master/$DATA_PATH"
        val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                LogFileManager.error("检查更新：拉取版本文件失败 HTTP ${resp.code}")
                throw IOException("HTTP ${resp.code}")
            }
            return resp.body?.string().orEmpty()
        }
    }

    /** .dat 内容 = Base64(XOR(json))，先 Base64 解码再逐字节 XOR 还原 JSON */
    private fun decrypt(datText: String): String {
        val xorBytes = Base64.decode(datText.trim(), Base64.DEFAULT)
        val key = VERSION_KEY
        val out = ByteArray(xorBytes.size)
        for (i in xorBytes.indices) {
            out[i] = (xorBytes[i].toInt() xor key[i % key.size].toInt()).toByte()
        }
        return String(out, Charsets.UTF_8)
    }

    private fun parse(json: String): VersionInfo {
        val obj = JsonParser.parseString(json).asJsonObject
        return VersionInfo(
            v = obj.get("v").asInt,
            vn = obj.get("vn").asString,
            apk = obj.get("apk").asString,
            md5 = obj.get("md5")?.asString,
            force = obj.get("force")?.asBoolean ?: false,
            note = obj.get("note")?.asString ?: "",
            enc = obj.get("enc")?.asString,
        )
    }

    /** AES-256-CBC 解密（与 CI openssl enc -aes-256-cbc 兼容；PKCS5=PKCS7） */
    private fun aesDecrypt(encBytes: ByteArray, key: ByteArray): ByteArray {
        val keyBytes = MessageDigest.getInstance("SHA-256").digest(key)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(keyBytes, "AES"),
            IvParameterSpec(ByteArray(16))
        )
        return cipher.doFinal(encBytes)
    }

    private fun md5Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5").digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }

    // ═══════════════════════ 提示 / 下载 / 解密 / 安装 ═══════════════════════

    private fun showUpdateDialog(context: Context, info: VersionInfo) {
        val activity = context as? Activity ?: return
        val message = buildString {
            append("新版本：v${info.vn}\n")
            if (info.note.isNotBlank()) append("\n${info.note}\n")
            append("\n当前版本：v${BuildConfig.VERSION_NAME}")
            if (info.force) append("\n\n⚠ 此版本为强制更新")
        }
        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle("发现新版本")
            .setMessage(message)
            .setPositiveButton("立即更新") { _, _ -> startDownload(activity, info) }
            .setCancelable(!info.force)
        if (!info.force) {
            builder.setNegativeButton("稍后") { _, _ -> }
        }
        builder.show()
        LogFileManager.action("检查更新：发现新版本 v${info.vn}（code ${info.v} > 本地 ${BuildConfig.VERSION_CODE}，force=${info.force}）")
    }

    /** DownloadManager 下载 APK 附件（aes256=密文包，否则按明文包处理） */
    private fun startDownload(context: Context, info: VersionInfo) {
        val dm = context.getSystemService(DownloadManager::class.java) ?: return
        val isEnc = info.enc == "aes256"
        val fileName = if (isEnc) "dailyTask-update.apk.enc" else "dailyTask-update.apk"
        val request = DownloadManager.Request(Uri.parse(info.apk))
            .setTitle("DailyTask 更新")
            .setDescription("v${info.vn} 下载中")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
        val downloadId = runCatching { dm.enqueue(request) }.getOrElse { e ->
            LogFileManager.error("检查更新：下载任务入队失败 ${e.message}")
            return
        }
        LogFileManager.action("检查更新：开始下载新版本 v${info.vn}（downloadId=$downloadId，enc=${info.enc}）")
        registerCompleteReceiver(context, downloadId, fileName, info)
    }

    private fun registerCompleteReceiver(
        context: Context,
        downloadId: Long,
        fileName: String,
        info: VersionInfo,
    ) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != downloadId) return
                ctx?.unregisterReceiver(this)
                val appCtx = ctx ?: context
                // 解密/校验放后台线程，避免阻塞主线程
                Thread {
                    decryptAndInstall(appCtx, downloadId, fileName, info)
                }.start()
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    /** 下载完成 → （AES 解密）→ MD5 校验 → FileProvider 安装 */
    private fun decryptAndInstall(context: Context, downloadId: Long, fileName: String, info: VersionInfo) {
        val dm = context.getSystemService(DownloadManager::class.java)
        if (queryStatus(dm, downloadId) != DownloadManager.STATUS_SUCCESSFUL) {
            LogFileManager.error("检查更新：下载未成功，跳过解密安装")
            return
        }
        val dlFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        if (!dlFile.exists()) {
            LogFileManager.error("检查更新：下载文件不存在 ${dlFile.absolutePath}")
            return
        }
        val isEnc = info.enc == "aes256"
        val apkFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "dailyTask-update.apk")
        runCatching {
            val bytes = if (isEnc) aesDecrypt(dlFile.readBytes(), VERSION_KEY) else dlFile.readBytes()
            info.md5?.let { expect ->
                val actual = md5Hex(bytes)
                if (!actual.equals(expect, ignoreCase = true)) {
                    throw IOException("MD5 校验失败 expected=$expect actual=$actual")
                }
            }
            apkFile.writeBytes(bytes)
        }.onFailure { e ->
            LogFileManager.error("检查更新：解密/校验失败 ${e.message}")
            runCatching { dlFile.delete() }
            runCatching { apkFile.delete() }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(context.applicationContext, "更新包校验失败，请重试", Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (isEnc) runCatching { dlFile.delete() }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            context.startActivity(intent)
            LogFileManager.action("检查更新：已跳转系统安装页（v${info.vn}）")
        }.onFailure { e ->
            LogFileManager.error("检查更新：跳转安装页失败 ${e.message}")
        }
    }

    private fun queryStatus(dm: DownloadManager?, downloadId: Long): Int {
        if (dm == null) return DownloadManager.STATUS_FAILED
        val cursor = dm.query(DownloadManager.Query().setFilterById(downloadId))
        return runCatching {
            if (cursor.moveToFirst()) {
                cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            } else DownloadManager.STATUS_FAILED
        }.getOrElse { DownloadManager.STATUS_FAILED }.also { cursor.close() }
    }

    private suspend fun toast(context: Context, msg: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context.applicationContext, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
