package com.pengxh.daily.app.utils

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
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

    /** 预期安装版本（下载校验通过后写入）：安装完成（任意途径）后与真实安装版本比对，不一致则明确报错 */
    private const val PREF_PENDING_VERCODE = "update_pending_vercode"

    /** 主线程 Handler：后台线程解析 APK 下载地址后切回主线程发起下载 */
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 错误提示防抖：同类错误 15 秒内只弹一次，避免反复报错刷屏 */
    private const val ERROR_TOAST_MIN_INTERVAL_MS = 15_000L
    private var lastErrorToastTime = 0L

    /** 包替换广播是否已注册（幂等，防止重复注册导致安装完成重复回调） */
    private var installReceiverRegistered = false

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
        val tag: String = "",
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
        // 注册包替换广播 + 兜底清理：覆盖两种安装场景（App 内跳装 / 文件管理器手动装）的版本一致性核对
        ensureInstallMonitor(context)
        reconcilePendingVersion(context)
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
                if (!silent && showNoUpdateToast) {
                    showErrorOnce(context, "检查更新失败：无法获取版本信息，请稍后重试")
                }
                false
            }
        }
    }

    // ═══════════════════════ 拉取与解密（与 publish_gitee.py 对称）═══════════════════════

    /** 私密仓库 raw（API v5），必须带 access_token；ref 显式指定 master（仓库默认分支） */
    private fun fetchVersionFile(): String {
        val url = "https://gitee.com/api/v5/repos/$GITEE_OWNER/$GITEE_REPO/raw/$DATA_PATH?access_token=$APP_TOKEN&ref=master"
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
            return resp.body.string().orEmpty()
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
            apk = obj.get("apk")?.asString ?: "",
            md5 = obj.get("md5")?.asString,
            force = obj.get("force")?.asBoolean ?: false,
            note = obj.get("note")?.asString ?: "",
            tag = obj.get("tag")?.asString ?: "",
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
        // note 多行显示：兼容 CI 输入框直接回车（真换行）和手写字面 "\n" 两种情况，
        // 后者常见于 "复制粘贴" 或跨平台脚本生成的更新说明（如 "1、测试1\n2、测试2"）。
        val message = buildString {
            append("版本：v${BuildConfig.VERSION_NAME} --> v${info.vn}\n")
            if (info.note.isNotBlank()) {
                append("\n${info.note.replace("\\n", "\n")}")
            }
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

    /** 解析 APK 下载地址：优先旧版 apk 直链，否则从 release 附件动态获取（手动上传场景） */
    private fun resolveApkUrl(info: VersionInfo): String? {
        if (info.apk.isNotBlank()) return info.apk
        if (info.tag.isBlank()) return null
        return runCatching {
            val url = "https://gitee.com/api/v5/repos/$GITEE_OWNER/$GITEE_REPO/releases/tags/${info.tag}?access_token=$APP_TOKEN"
            val client = OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .build()
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    LogFileManager.error("检查更新：拉取 release 附件失败 HTTP ${resp.code}")
                    return@runCatching null
                }
                val arr = JsonParser.parseString(resp.body.string().orEmpty())
                    .asJsonObject.getAsJsonArray("assets")
                for (el in arr) {
                    val o = el.asJsonObject
                    val name = o.get("name")?.asString ?: ""
                    // 跳过源码归档（.zip/.tar.gz），只取 .apk
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        return@runCatching o.get("browser_download_url")?.asString
                    }
                }
                null
            }
        }.getOrNull()
    }

    /** DownloadManager 下载明文 APK（私密仓库，URL 拼 access_token）。APK 地址先经 resolveApkUrl 解析 */
    private fun startDownload(context: Context, info: VersionInfo) {
        // 下载地址需网络请求获取，放后台线程；结果回主线程发起下载与注册监听
        Thread {
            val baseUrl = resolveApkUrl(info)
            if (baseUrl.isNullOrBlank()) {
                showErrorOnce(context, "更新包尚未上传：Gitee Release『${info.tag}』无 APK 附件，请发布者上传对应版本后重试")
                LogFileManager.error("检查更新：未找到 APK 附件（release=${info.tag}），可能尚未手动上传")
                return@Thread
            }
            mainHandler.post { enqueueDownload(context, baseUrl, info) }
        }.start()
    }

    private fun enqueueDownload(context: Context, baseUrl: String, info: VersionInfo) {
        val dm = context.getSystemService(DownloadManager::class.java) ?: return
        val fileName = "dailyTask-update.apk"
        // 私密仓库附件下载需带令牌；URL 若已含 ? 则用 & 拼接
        val sep = if (baseUrl.contains("?")) "&" else "?"
        val dlUrl = baseUrl + sep + "access_token=" + APP_TOKEN
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
            showErrorOnce(context, "更新包校验失败（MD5 不匹配）：下载可能损坏，请重新下载")
            return
        }
        // 版本一致性校验（手动上传场景）：安装包 versionCode 必须与 .dat 发布版本一致。
        // 不一致说明 Gitee Release 上传了错误版本的 APK——拦截并明确报错，
        // 避免装上旧版导致「每次检查都提示更新」的反复报错循环。
        val apkVc = apkVersionCode(context, apkFile.absolutePath)
        if (apkVc != null && apkVc != info.v.toLong()) {
            LogFileManager.error("检查更新：安装包版本不一致 apk=$apkVc 发布=${info.v}（release=${info.tag}）")
            runCatching { apkFile.delete() }
            showErrorOnce(context, "更新包版本不一致（$apkVc ≠ ${info.v}）：请确认 Gitee Release『${info.tag}』上传的 APK 为对应构建产物")
            return
        }
        // 记录预期安装版本：无论自动跳装还是用户去文件管理器安装，
        // 安装完成后经包替换广播统一核对版本一致性（见 onPackageInstalled）
        SaveKeyValues.saveInt(PREF_PENDING_VERCODE, info.v)
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

    /** 注册「自身包安装/替换」广播：覆盖两种安装场景（App 内跳装 / 文件管理器手动装）的版本一致性核对 */
    private fun ensureInstallMonitor(context: Context) {
        if (installReceiverRegistered) return
        installReceiverRegistered = true
        runCatching {
            val filter = IntentFilter(Intent.ACTION_PACKAGE_REPLACED).apply { addDataScheme("package") }
            ContextCompat.registerReceiver(
                context.applicationContext,
                object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context?, intent: Intent?) {
                        val pkg = intent?.data?.schemeSpecificPart ?: return
                        if (pkg != context.packageName) return
                        onPackageInstalled(context.applicationContext)
                    }
                },
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
        }
    }

    /** 安装完成（任意途径）后核对：实际安装版本 vs 预期发布版本（PREF_PENDING_VERCODE） */
    private fun onPackageInstalled(context: Context) {
        val expected = SaveKeyValues.loadInt(PREF_PENDING_VERCODE, 0)
        if (expected <= 0) return
        val installed = installedVersionCode(context)
        SaveKeyValues.saveInt(PREF_PENDING_VERCODE, 0) // 一次性核对
        if (installed == null) return
        if (installed >= expected.toLong()) {
            // 装到了目标版本（或更高）：更新完成，清除红点
            SaveKeyValues.saveBoolean(PREF_HAS_UPDATE, false)
            LogFileManager.action("检查更新：新版本 v$installed 安装成功（预期 v$expected），清除更新红点")
        } else {
            // 版本不一致：可能装了旧包/错包（如文件管理器里选了遗留的 APK）
            showErrorOnce(context, "安装的版本（v$installed）与发布版本（v$expected）不一致：请确认安装的是 Gitee Release 对应版本的更新包")
            LogFileManager.error("检查更新：安装后版本不一致 installed=$installed expected=$expected")
        }
    }

    /** 启动补查：包替换广播丢失时（进程被杀等）兜底——已装到目标版本则清理标记与红点 */
    private fun reconcilePendingVersion(context: Context) {
        val expected = SaveKeyValues.loadInt(PREF_PENDING_VERCODE, 0)
        if (expected <= 0) return
        val installed = installedVersionCode(context) ?: return
        if (installed >= expected.toLong()) {
            SaveKeyValues.saveInt(PREF_PENDING_VERCODE, 0)
            SaveKeyValues.saveBoolean(PREF_HAS_UPDATE, false)
        }
    }

    /** 当前已安装的自身版本号（获取失败返回 null） */
    @Suppress("DEPRECATION")
    private fun installedVersionCode(context: Context): Long? {
        return runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
            else info.versionCode.toLong()
        }.getOrNull()
    }

    /** 错误提示（防抖）：15 秒内只弹一次，避免反复报错刷屏 */
    private fun showErrorOnce(context: Context, msg: String) {
        val now = System.currentTimeMillis()
        if (now - lastErrorToastTime < ERROR_TOAST_MIN_INTERVAL_MS) return
        lastErrorToastTime = now
        mainHandler.post {
            Toast.makeText(context.applicationContext, msg, Toast.LENGTH_LONG).show()
        }
    }

    /** 解析本地 APK 文件的 versionCode（下载后安装前校验用；解析失败返回 null 则不拦截） */
    @Suppress("DEPRECATION")
    private fun apkVersionCode(context: Context, apkPath: String): Long? {
        return runCatching {
            val info = context.packageManager.getPackageArchiveInfo(apkPath, 0) ?: return null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
            else info.versionCode.toLong()
        }.getOrNull()
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
