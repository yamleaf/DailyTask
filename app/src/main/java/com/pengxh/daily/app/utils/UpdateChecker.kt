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
import com.google.gson.JsonParser
import com.pengxh.daily.app.BuildConfig
import com.pengxh.kt.lite.utils.SaveKeyValues
import com.yample.mqttprotocol.dialog.UnifiedDialogKit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 检查更新（Gitee 私密仓库托管，版本文件加密 + 明文 APK 直装）：
 * 1. 拉取私密仓库 updates/v_task.dat（API v5 raw + access_token）——内容是 Base64(XOR(json)) 密文
 * 2. XOR 解密 → Gson 解析；versionCode 对比（CI alpha 构建的时间戳版本号单调递增）
 * 3. 有新版 → 弹窗 → 下载 Release 附件（明文 APK，URL 拼 access_token）
 * 4. MD5 校验（可选）→ FileProvider 直接安装（不加密、无需解密）
 *
 * 私密仓库 + 内置只读令牌：仓库文件与附件仅持有令牌者可访问；APK 为明文可直接安装。
 * 与 CI 脚本 scripts/publish_gitee.py 对称（.dat 仍 XOR 加密，密钥=VERSION_KEY）。
 */
object UpdateChecker {

    // ===== Gitee 私密仓库配置（必须与 CI workflow 的 GITEE_OWNER/GITEE_REPO 一致）=====
    private const val GITEE_OWNER = "yamleaf"
    private const val GITEE_REPO = "DailyTaskUpdate"
    private const val DATA_PATH = "updates/v_task.dat"

    /** Gitee 只读令牌（projects 权限）：私密仓库 raw/附件均需带此令牌访问。
     *  不存明文：按奇偶位拆成两段打乱存储，运行时交错还原（防 APK 反编译直接读取） */
    private val APP_TOKEN: String by lazy {
        val even = "d8956eae9971fd1b" // token 偶数位（索引 0,2,4...）
        val odd = "ac4fb3c1a84ad46e"  // token 奇数位（索引 1,3,5...）
        buildString {
            for (i in even.indices) {
                append(even[i])
                append(odd[i])
            }
        }
    }

    /** 静默检查发现新版本时写入；设置页据此显示「检查更新」红点（安装新版本后下次检查自动清除） */
    private const val PREF_HAS_UPDATE = "update_has_new_version"

    /** XOR 密钥：必须与 CI Secret VERSION_KEY 一致（仅用于 .dat 版本文件加密，APK 不加密） */
    private val VERSION_KEY: ByteArray by lazy {
        "DailyTaskUpdateKey2026!".toByteArray(Charsets.UTF_8)
    }

    /** 设置页「检查更新」红点状态（由每日静默检查/手动检查更新） */
    fun hasPendingUpdate(): Boolean = SaveKeyValues.loadBoolean(PREF_HAS_UPDATE, false)

    data class VersionInfo(
        val v: Int,
        val vn: String,
        val apk: String,
        val md5: String?,
        val force: Boolean,
        val note: String,
    )

    /**
     * 检查更新。
     * @param showNoUpdateToast 无更新/失败时是否 Toast 提示（手动点击入口传 true，静默检查传 false）
     * @param silent true=纯静默：只更新红点状态，不弹窗、不 Toast（每日重置点检查用）
     * @return true=发现新版本（silent 模式下不弹窗，仅设置页红点）
     */
    suspend fun check(
        context: Context,
        showNoUpdateToast: Boolean = false,
        silent: Boolean = false,
    ): Boolean {
        return withContext(Dispatchers.IO) {
            runCatching {
                parse(decrypt(fetchVersionFile()))
            }.getOrNull()?.let { info ->
                val hasUpdate = info.v > BuildConfig.VERSION_CODE
                // 成功拉到版本文件才更新红点状态（失败不误清）
                SaveKeyValues.saveBoolean(PREF_HAS_UPDATE, hasUpdate)
                if (hasUpdate) {
                    if (!silent) {
                        withContext(Dispatchers.Main) { showUpdateDialog(context, info) }
                    }
                    true
                } else {
                    if (!silent && showNoUpdateToast) toast(context, "当前已是最新版本")
                    false
                }
            } ?: run {
                // 拉取/解析失败：保持原红点状态，不误清
                if (!silent && showNoUpdateToast) toast(context, "检查更新失败，请稍后重试")
                false
            }
        }
    }

    // ═══════════════════════ 拉取与解密（与 publish_gitee.py 对称）═══════════════════════

    /** 私密仓库 raw（API v5），必须带 access_token */
    private fun fetchVersionFile(): String {
        val url = "https://gitee.com/api/v5/repos/$GITEE_OWNER/$GITEE_REPO/raw/$DATA_PATH?access_token=$APP_TOKEN"
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
        )
    }

    private fun md5Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5").digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }

    // ═══════════════════════ 提示 / 下载 / 解密 / 安装 ═══════════════════════

    private fun showUpdateDialog(context: Context, info: VersionInfo) {
        val activity = context as? Activity ?: return
        // 版本行：当前版本 --> 新版本（箭头连接，紧凑直观）；
        // note 支持多行显示：JSON 序列化的 \n 经 Gson 解析后还原为真换行，TextView 默认渲染
        val message = buildString {
            append("版本：v${BuildConfig.VERSION_NAME} --> v${info.vn}\n")
            if (info.note.isNotBlank()) append("\n${info.note}")
            if (info.force) append("\n\n⚠ 此版本为强制更新")
        }
        // 双端统一弹窗：双按钮等宽均分（立即更新/稍后）；force 时单按钮居中不可取消
        UnifiedDialogKit.showConfirm(
            activity,
            title = "发现新版本",
            message = message,
            confirmText = "立即更新",
            cancelText = if (info.force) null else "稍后",
            cancelable = !info.force,
            icon = UnifiedDialogKit.IconType.INFO,
            onConfirm = { startDownload(activity, info) },
        )
        LogFileManager.action("检查更新：发现新版本 v${info.vn}（code ${info.v} > 本地 ${BuildConfig.VERSION_CODE}，force=${info.force}）")
    }

    /** DownloadManager 下载明文 APK 附件（私密仓库，URL 拼 access_token） */
    private fun startDownload(context: Context, info: VersionInfo) {
        val dm = context.getSystemService(DownloadManager::class.java) ?: return
        val fileName = "dailyTask-update.apk"
        // 私密仓库附件下载需带令牌；URL 若已含 ? 则用 & 拼接
        val sep = if (info.apk.contains("?")) "&" else "?"
        val dlUrl = info.apk + sep + "access_token=" + APP_TOKEN
        val request = DownloadManager.Request(Uri.parse(dlUrl))
            .setTitle("DailyTask 更新")
            .setDescription("v${info.vn} 下载中")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
        val downloadId = runCatching { dm.enqueue(request) }.getOrElse { e ->
            LogFileManager.error("检查更新：下载任务入队失败 ${e.message}")
            return
        }
        LogFileManager.action("检查更新：开始下载新版本 v${info.vn}（downloadId=$downloadId）")
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

    /** 下载完成 → MD5 校验（可选）→ FileProvider 直接安装（APK 明文，无需解密） */
    private fun decryptAndInstall(context: Context, downloadId: Long, fileName: String, info: VersionInfo) {
        val dm = context.getSystemService(DownloadManager::class.java)
        if (queryStatus(dm, downloadId) != DownloadManager.STATUS_SUCCESSFUL) {
            LogFileManager.error("检查更新：下载未成功，跳过安装")
            return
        }
        val apkFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        if (!apkFile.exists()) {
            LogFileManager.error("检查更新：下载文件不存在 ${apkFile.absolutePath}")
            return
        }
        // 可选 MD5 校验（.dat 提供时校验，防下载损坏/替换）
        runCatching {
            info.md5?.let { expect ->
                val actual = md5Hex(apkFile.readBytes())
                if (!actual.equals(expect, ignoreCase = true)) {
                    throw IOException("MD5 校验失败 expected=$expect actual=$actual")
                }
            }
        }.onFailure { e ->
            LogFileManager.error("检查更新：校验失败 ${e.message}")
            runCatching { apkFile.delete() }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(context.applicationContext, "更新包校验失败，请重试", Toast.LENGTH_SHORT).show()
            }
            return
        }
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
