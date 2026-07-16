package com.pengxh.daily.app.utils

import android.content.Context
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date
import java.util.Properties
import javax.activation.DataHandler
import javax.activation.FileDataSource
import javax.mail.Message
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

object EmailManager {
    private val kTag = "EmailManager"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    private data class EmailConfig(val outbox: String, val authCode: String, val inbox: String)

    private fun loadEmailConfig(onFailure: ((String) -> Unit)?): EmailConfig? {
        val obj = ConfigStore.get().load(Constant.EMAIL_CONFIG_KEY)
        if (obj.isEmpty) {
            onFailure?.invoke("邮箱未配置，无法发送邮件")
            return null
        }
        Log.d(kTag, "邮箱配置: $obj")
        return EmailConfig(
            outbox = obj.get("outbox").asString,
            authCode = obj.get("authCode").asString,
            inbox = obj.get("inbox").asString
        )
    }

    private fun createSmtpProperties(): Properties {
        val props = Properties().apply {
            put("mail.smtp.host", "smtp.qq.com")
            put("mail.smtp.port", "465")
            put("mail.smtp.auth", "true")
            put("mail.smtp.ssl.checkserveridentity", "true")
            put("mail.smtp.ssl.enable", "true")
            put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
            put("mail.smtp.socketFactory.port", "465")
        }
        return props
    }

    /**
     * 发送普通邮件
     * @param isHtml 为 true 时以 HTML 格式发送，手机端渲染更美观
     */
    fun sendEmail(
        title: String,
        content: String,
        isHtml: Boolean = false,
        onSuccess: (() -> Unit)? = null,
        onFailure: ((String) -> Unit)? = null
    ) {
        val config = loadEmailConfig(onFailure) ?: return

        val authenticator = EmailAuthenticator(config.outbox, config.authCode)
        val props = createSmtpProperties()
        val session = Session.getInstance(props, authenticator)

        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(config.outbox))
            setRecipient(Message.RecipientType.TO, InternetAddress(config.inbox))
            subject = title
            sentDate = Date()
            if (isHtml) {
                setContent(content, "text/html; charset=utf-8")
            } else {
                setText(content)
            }
        }

        sendAsync(message, onSuccess, onFailure)
    }

    /**
     * 发送带附件的邮件
     * @param isHtml 为 true 时正文以 HTML 格式发送
     */
    fun sendAttachmentEmail(
        title: String,
        content: String,
        filePath: String,
        isHtml: Boolean = false,
        onSuccess: (() -> Unit)? = null,
        onFailure: ((String) -> Unit)? = null
    ) {
        val config = loadEmailConfig(onFailure) ?: return

        val authenticator = EmailAuthenticator(config.outbox, config.authCode)
        val props = createSmtpProperties()
        val session = Session.getInstance(props, authenticator)

        // 正文部分
        val textPart = MimeBodyPart().apply {
            if (isHtml) {
                setContent(content, "text/html; charset=utf-8")
            } else {
                setText(content)
            }
        }

        // 附件部分
        val attachmentPart = MimeBodyPart().apply {
            val file = File(filePath)
            dataHandler = DataHandler(FileDataSource(file))
            fileName = file.name
        }

        // 组合 multipart
        val multipart = MimeMultipart().apply {
            addBodyPart(textPart)
            addBodyPart(attachmentPart)
        }

        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(config.outbox))
            setRecipient(Message.RecipientType.TO, InternetAddress(config.inbox))
            subject = title
            sentDate = Date()
            setContent(multipart)
        }

        sendAsync(message, onSuccess, onFailure)
    }

    /**
     * 异步发送邮件
     */
    private fun sendAsync(
        message: MimeMessage,
        onSuccess: (() -> Unit)? = null,
        onFailure: ((String) -> Unit)? = null
    ) {
        scope.launch {
            // 灭屏/Doze 下 SMTP 可能被挂起，发信期间持有 CPU WakeLock
            val wakeLock = appContext?.getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DailyTask:SendEmail")
            try {
                wakeLock?.acquire(60_000L)
                Transport.send(message)
                LogFileManager.writeLog("邮件发送成功: ${message.subject}")
                withContext(Dispatchers.Main) {
                    onSuccess?.invoke()
                }
            } catch (e: Exception) {
                val errorMessage = when {
                    e.message?.contains("535", ignoreCase = true) == true ->
                        "邮箱认证失败，请检查邮箱账号和授权码是否正确"

                    e.message?.contains("authentication failed", ignoreCase = true) == true ->
                        "邮箱认证失败，请确认使用的是授权码而非登录密码"

                    else -> "邮件发送失败: ${e.javaClass.simpleName} - ${e.message}"
                }
                Log.e(kTag, errorMessage, e)
                LogFileManager.writeLog(errorMessage)

                withContext(Dispatchers.Main) {
                    onFailure?.invoke(errorMessage)
                }
            } finally {
                if (wakeLock?.isHeld == true) {
                    wakeLock.release()
                }
            }
        }
    }

}
