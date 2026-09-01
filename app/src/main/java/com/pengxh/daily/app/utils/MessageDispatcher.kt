package com.pengxh.daily.app.utils

import android.content.Context
import android.os.BatteryManager
import android.util.Log
import com.pengxh.daily.app.BuildConfig
import com.pengxh.daily.app.extensions.getResponseHeader
import com.pengxh.daily.app.retrofit.RetrofitServiceManager
import com.pengxh.kt.lite.utils.SaveKeyValues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * 统一消息分发器
 * 封装邮件和企业微信两种渠道的分流逻辑，全局复用同一个协程作用域。
 *
 * 必须在 [com.pengxh.daily.app.DailyTaskApplication.onCreate] 中初始化。
 */
object MessageDispatcher {

    private val kTag = "MessageDispatcher"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 相同 title+正文 在窗口内不重复发送 */
    private const val DEDUP_WINDOW_MS = 90_000L
    private val recentSendAt = ConcurrentHashMap<String, Long>()

    private lateinit var batteryManager: BatteryManager

    fun initialize(context: Context) {
        batteryManager = context.getSystemService(BatteryManager::class.java)
    }

    /**
     * 发送文本消息，根据用户配置自动选择邮件或企业微信渠道
     *
     * @param channelOverride 渠道覆盖：null=走用户配置，0=强制邮箱，1=强制企微
     * @param force 为 true 时跳过去重（如用户主动状态查询）
     * @param appendMeta 为 false 时不再追加执行模式/日期/电量等（正文已自带时用）
     */
    fun sendMessage(
        title: String,
        content: String,
        channelOverride: Int? = null,
        force: Boolean = false,
        appendMeta: Boolean = true,
        onSuccess: (() -> Unit)? = null,
        onFailure: ((String) -> Unit)? = null
    ) {
        if (SaveKeyValues.loadBoolean(Constant.FEEDBACK_NOTIFY_DISABLED_KEY, false)) {
            Log.d(kTag, "反馈通知已关闭，跳过消息发送: $title")
            return
        }

        if (!force && shouldSkipDuplicate(title, content)) {
            Log.d(kTag, "跳过短时间内重复消息: $title")
            return
        }

        val channelType = channelOverride
            ?: SaveKeyValues.loadInt(Constant.MSG_CHANNEL_KEY, Constant.DEFAULT_INDEX)
        val messageTitle = SaveKeyValues.loadString(Constant.MESSAGE_TITLE_KEY, "打卡结果通知")

        val fullContent = if (appendMeta) {
            val battery = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            buildString {
                appendLine("====================")
                appendLine("  ${title.ifBlank { messageTitle }}")
                appendLine("====================")
                appendLine()
                appendLine(content.ifBlank { "未监听到打卡成功的通知，请手动登录检查" })
                appendLine()
                appendLine("· 当前日期：${LocalDate.now()}")
                appendLine("· 当前电量：${if (battery >= 0) "$battery%" else "未知"}")
                append("· 版本号：${BuildConfig.VERSION_NAME}")
            }
        } else {
            content.ifBlank { "未监听到打卡成功的通知，请手动登录检查" }
        }

        when (channelType) {
            0 -> {
                val html = toEmailHtml(title.ifBlank { messageTitle }, fullContent)
                EmailManager.sendEmail(
                    title.ifBlank { messageTitle },
                    html,
                    isHtml = true,
                    onSuccess = onSuccess,
                    onFailure = onFailure
                )
            }

            1 -> {
                sendViaWechat(fullContent, onSuccess, onFailure)
            }

            else -> {
                Log.w(kTag, "消息渠道不支持: $channelType")
                onFailure?.invoke("消息渠道未配置")
            }
        }
    }

    /**
     * 发送带附件的消息（邮件附件 / 企业微信图片）
     */
    fun sendAttachmentMessage(
        title: String,
        content: String,
        filePath: String,
        channelOverride: Int? = null,
        force: Boolean = false,
        onSuccess: (() -> Unit)? = null,
        onFailure: ((String) -> Unit)? = null
    ) {
        if (SaveKeyValues.loadBoolean(Constant.FEEDBACK_NOTIFY_DISABLED_KEY, false)) {
            Log.d(kTag, "反馈通知已关闭，跳过附件消息发送: $title")
            return
        }

        if (!force && shouldSkipDuplicate(title, content)) {
            Log.d(kTag, "跳过短时间内重复附件消息: $title")
            return
        }

        val channelType = channelOverride
            ?: SaveKeyValues.loadInt(Constant.MSG_CHANNEL_KEY, Constant.DEFAULT_INDEX)
        val messageTitle = SaveKeyValues.loadString(Constant.MESSAGE_TITLE_KEY, "打卡结果通知")

        when (channelType) {
            0 -> {
                val isHtml = content.trimStart().startsWith("<!DOCTYPE html>", ignoreCase = true)
                val fullContent = if (isHtml) {
                    // HTML 正文直接使用，不套 plain-text 壳
                    content
                } else {
                    val battery =
                        batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    buildString {
                        appendLine("====================")
                        appendLine("  ${title.ifBlank { messageTitle }}")
                        appendLine("====================")
                        appendLine()
                        appendLine(content)
                        appendLine()
                        appendLine("· 当前日期：${LocalDate.now()}")
                        appendLine("· 当前电量：${if (battery >= 0) "$battery%" else "未知"}")
                        append("· 版本号：${BuildConfig.VERSION_NAME}")
                    }
                }
                EmailManager.sendAttachmentEmail(
                    title.ifBlank { messageTitle },
                    toEmailHtml(title.ifBlank { messageTitle }, fullContent),
                    filePath,
                    isHtml = true,
                    onSuccess = onSuccess,
                    onFailure = onFailure
                )
            }

            1 -> sendImageViaWechat(filePath, onSuccess, onFailure)

            else -> {
                Log.w(kTag, "消息渠道不支持: $channelType")
                onFailure?.invoke("消息渠道未配置")
            }
        }
    }

    /**
     * 统一邮件 HTML 化：已是完整 HTML 文档（<!DOCTYPE html>/<html 开头）原样直传；
     * 纯文本则转义后包一层简洁 HTML 外壳，保证所有邮件均以 HTML 渲染（企微渠道不受影响）。
     */
    private fun toEmailHtml(title: String, content: String): String {
        val trimmed = content.trimStart()
        if (trimmed.startsWith("<!DOCTYPE html>", ignoreCase = true) || trimmed.startsWith("<html", ignoreCase = true)) {
            return content
        }
        val body = buildString {
            append("<div style=\"max-width:600px;margin:0 auto;background:#fff;border-radius:12px;overflow:hidden;\">")
            append("<div style=\"background:linear-gradient(135deg,#4f6ef7,#6c5ce7);padding:14px 20px;color:#fff;font-size:15px;font-weight:700;\">")
            append(escapeHtml(title.ifBlank { "通知" }))
            append("</div>")
            append("<div style=\"padding:16px 20px;font-size:13px;color:#333;line-height:1.7;white-space:pre-wrap;word-break:break-word;\">")
            append(escapeHtml(content))
            append("</div></div>")
        }
        return "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"></head>" +
            "<body style=\"margin:0;padding:16px;background:#f0f2f5;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','PingFang SC','Microsoft YaHei',sans-serif;\">$body</body></html>"
    }

    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    private fun shouldSkipDuplicate(title: String, content: String): Boolean {
        val key = "${title.trim()}\n${content.trim()}"
        val now = System.currentTimeMillis()
        cleanupExpired(now)
        val last = recentSendAt[key]
        if (last != null && now - last < DEDUP_WINDOW_MS) {
            return true
        }
        recentSendAt[key] = now
        return false
    }

    private fun cleanupExpired(now: Long) {
        if (recentSendAt.size < 32) return
        val iterator = recentSendAt.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value >= DEDUP_WINDOW_MS) {
                iterator.remove()
            }
        }
    }

    private fun sendViaWechat(
        fullContent: String,
        onSuccess: (() -> Unit)?,
        onFailure: ((String) -> Unit)?
    ) {
        scope.launch {
            try {
                val response = RetrofitServiceManager.sendMessage(fullContent)
                handleWechatResponse(response, onSuccess, onFailure)
            } catch (e: Exception) {
                Log.e(kTag, "消息发送异常", e)
                if (onSuccess != null || onFailure != null) {
                    withContext(Dispatchers.Main) {
                        onFailure?.invoke(e.message ?: "未知错误")
                    }
                }
            }
        }
    }

    private fun sendImageViaWechat(
        imagePath: String,
        onSuccess: (() -> Unit)?,
        onFailure: ((String) -> Unit)?
    ) {
        scope.launch {
            try {
                val response = RetrofitServiceManager.sendImageMessage(imagePath)
                handleWechatResponse(response, onSuccess, onFailure)
            } catch (e: Exception) {
                Log.e(kTag, "消息发送异常", e)
                if (onSuccess != null || onFailure != null) {
                    withContext(Dispatchers.Main) {
                        onFailure?.invoke(e.message ?: "未知错误")
                    }
                }
            }
        }
    }

    private suspend fun handleWechatResponse(
        response: String,
        onSuccess: (() -> Unit)?,
        onFailure: ((String) -> Unit)?
    ) {
        if (onSuccess == null && onFailure == null) return
        val header = response.getResponseHeader()
        withContext(Dispatchers.Main) {
            if (header.first == 0) {
                onSuccess?.invoke()
            } else {
                onFailure?.invoke(header.second)
            }
        }
    }
}
