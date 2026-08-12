package com.pengxh.daily.app.ui

import android.os.Bundle
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.gson.JsonObject
import com.pengxh.daily.app.databinding.ActivityMessageChannelBinding
import com.pengxh.daily.app.utils.ConfigImportSignal
import com.pengxh.daily.app.utils.ConfigStore
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.EmailSecureConfig
import com.pengxh.daily.app.utils.MessageDispatcher
import com.pengxh.daily.app.utils.StatusReporter
import com.pengxh.kt.lite.base.KotlinBaseActivity
import com.pengxh.kt.lite.extensions.isEmail
import com.pengxh.kt.lite.extensions.show
import com.pengxh.kt.lite.utils.LoadingDialog
import com.pengxh.kt.lite.utils.SaveKeyValues
import com.yample.mqttprotocol.dialog.UnifiedDialogKit

class MessageChannelActivity : KotlinBaseActivity<ActivityMessageChannelBinding>() {

    private val context = this

    override fun initViewBinding(): ActivityMessageChannelBinding {
        return ActivityMessageChannelBinding.inflate(layoutInflater)
    }

    override fun setupTopBarLayout() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { view, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.setPadding(0, statusBarHeight, 0, 0)
            insets
        }
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    override fun initOnCreate(savedInstanceState: Bundle?) {
        val title = SaveKeyValues.loadString(Constant.MESSAGE_TITLE_KEY, "打卡结果通知")
        binding.messageTitleView.setText(title)

        val key = SaveKeyValues.loadString(Constant.WX_WEB_HOOK_KEY, "")
        if (!key.isBlank()) {
            binding.wxKeyView.setText(key)
        }

        val obj = ConfigStore.get().load(Constant.EMAIL_CONFIG_KEY)
        if (!obj.isEmpty) {
            val outbox = if (obj.has("outbox")) obj.get("outbox").asString else ""
            val inbox = if (obj.has("inbox")) obj.get("inbox").asString else ""
            val authCode = EmailSecureConfig.loadAuthCode()
            binding.emailSendAddressView.setText(if (outbox.contains("@qq.com")) outbox.dropLast(7) else outbox)
            binding.emailSendCodeView.setText(authCode)
            binding.emailInboxView.setText(inbox)
        }

        binding.feedbackNotifySwitch.isChecked =
            SaveKeyValues.loadBoolean(Constant.FEEDBACK_NOTIFY_DISABLED_KEY, false)
    }

    override fun observeRequestState() {

    }

    override fun initEvent() {
        binding.sendWxButton.setOnClickListener {
            if (SaveKeyValues.loadBoolean(Constant.FEEDBACK_NOTIFY_DISABLED_KEY, false)) {
                "已开启静默通知，无法发送测试消息".show(this)
                return@setOnClickListener
            }
            val key = binding.wxKeyView.text.toString()
            if (key.isBlank()) {
                "企业微信消息 Webhook key 为空".show(this)
                return@setOnClickListener
            }

            SaveKeyValues.saveString(
                Constant.WX_WEB_HOOK_KEY, binding.wxKeyView.text.toString()
            )
            ConfigImportSignal.notifyRemoteChanged(context)

            UnifiedDialogKit.showConfirm(
                this,
                "测试消息",
                "企业微信配置完成，可以发送企业微信消息。\n\n是否继续？",
                confirmText = "继续",
                cancelText = "取消",
                cancelable = false,
                onConfirm = { sendTestMessage() }
            )
        }

        binding.sendEmailButton.setOnClickListener {
            if (SaveKeyValues.loadBoolean(Constant.FEEDBACK_NOTIFY_DISABLED_KEY, false)) {
                "已开启静默通知，无法发送测试邮件".show(context)
                return@setOnClickListener
            }
            val address = binding.emailSendAddressView.text.toString()
            if (address.isBlank()) {
                binding.emailSendAddressView.shakeIfEmpty()
                "发件箱地址为空".show(context)
                return@setOnClickListener
            }
            val outbox = if (address.contains("@qq.com")) {
                address
            } else {
                "${address}@qq.com"
            }
            if (!outbox.isEmail()) {
                "发件箱格式错误，请检查".show(context)
                return@setOnClickListener
            }

            val authCode = binding.emailSendCodeView.text.toString()
            if (authCode.isBlank()) {
                binding.emailSendCodeView.shakeIfEmpty()
                "发件箱授权码为空".show(context)
                return@setOnClickListener
            }

            val inbox = binding.emailInboxView.text.toString()
            if (inbox.isBlank()) {
                binding.emailInboxView.shakeIfEmpty()
                "收件箱地址为空".show(context)
                return@setOnClickListener
            }
            if (!inbox.isEmail()) {
                "发件箱格式错误，请检查".show(context)
                return@setOnClickListener
            }

            val cacheObj = JsonObject().apply {
                addProperty("outbox", outbox)
                addProperty("inbox", binding.emailInboxView.text.toString())
            }
            ConfigStore.get().save(Constant.EMAIL_CONFIG_KEY, cacheObj)
            EmailSecureConfig.saveAuthCode(binding.emailSendCodeView.text.toString())
            ConfigImportSignal.notifyRemoteChanged(context)

            sendTestEmail()
        }

        binding.feedbackNotifySwitch.setOnCheckedChangeListener { _, isChecked ->
            SaveKeyValues.saveBoolean(Constant.FEEDBACK_NOTIFY_DISABLED_KEY, isChecked)
            if (isChecked) {
                "已开启静默通知，将不再发送邮件/企业微信消息".show(this)
            }
            ConfigImportSignal.notifyRemoteChanged(context)
        }
    }

    private fun sendTestMessage() {
        val message = buildString {
            appendLine("你好！")
            append("这是来自 DailyTask 的测试消息 🎉")
        }
        LoadingDialog.show(this, "消息发送中，请稍后...")
        MessageDispatcher.sendMessage(
            "测试消息", message,
            channelOverride = 1,
            onSuccess = {
                if (isFinishing || isDestroyed) return@sendMessage
                LoadingDialog.dismiss()

                SaveKeyValues.saveString(
                    Constant.MESSAGE_TITLE_KEY, binding.messageTitleView.text.toString().trim()
                )

                SaveKeyValues.saveInt(Constant.MSG_CHANNEL_KEY, 1)
                ConfigImportSignal.notifyRemoteChanged(context)
            },
            onFailure = {
                if (isFinishing || isDestroyed) return@sendMessage
                LoadingDialog.dismiss()
                it.show(this)
            })
    }

    private fun sendTestEmail() {
        UnifiedDialogKit.showConfirm(
            this,
            "测试邮件",
            "QQ邮箱配置完成，可以发送QQ邮件。\n\n是否继续？",
            confirmText = "继续",
            cancelText = "取消",
            cancelable = false,
            onConfirm = {
                LoadingDialog.show(context, "邮件发送中，请稍后....")
                MessageDispatcher.sendMessage(
                    "邮箱测试", StatusReporter.buildTestEmailHtml(),
                    channelOverride = 0,
                    appendMeta = false,
                    onSuccess = {
                        LoadingDialog.dismiss()
                        "发送成功，请注意查收".show(context)

                        SaveKeyValues.saveString(
                            Constant.MESSAGE_TITLE_KEY,
                            binding.messageTitleView.text.toString().trim()
                        )

                        SaveKeyValues.saveInt(Constant.MSG_CHANNEL_KEY, 0)
                        ConfigImportSignal.notifyRemoteChanged(context)
                    },
                    onFailure = {
                        LoadingDialog.dismiss()
                        "发送失败：${it}".show(context)
                    })
            }
        )
    }
}
