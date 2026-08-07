package com.pengxh.daily.app.ui

import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.TransitionDrawable
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textview.MaterialTextView
import com.google.gson.Gson
import com.pengxh.daily.app.BuildConfig
import com.pengxh.daily.app.R
import com.pengxh.daily.app.databinding.FragmentRemoteControlBinding
import com.pengxh.daily.app.extensions.notificationEnable
import com.pengxh.daily.app.service.CaptureImageService
import com.pengxh.daily.app.service.MqttAgentService
import com.pengxh.daily.app.service.NotificationMonitorService
import com.pengxh.daily.app.utils.ConfigImportSignal
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.DailyTask
import com.pengxh.daily.app.utils.MqttSecureConfig
import com.pengxh.daily.app.utils.ProjectionSession
import com.pengxh.daily.app.utils.ServerlessApiSecureConfig
import com.pengxh.daily.app.utils.WatermarkDrawable
import com.pengxh.kt.lite.base.KotlinBaseFragment
import com.pengxh.kt.lite.extensions.convertColor
import com.pengxh.kt.lite.extensions.show
import com.pengxh.kt.lite.utils.SaveKeyValues
import com.yample.mqttprotocol.BindingPayload
import com.yample.mqttprotocol.MqttQuota
import com.yample.mqttprotocol.dialog.UnifiedDialogKit
import com.yample.mqttprotocol.Protocol
import com.yample.mqttprotocol.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.WriterException

/**
 * 远程控制 Tab：MQTT 连接状态 / 配置编辑 / 绑定二维码 / 在线客户端 / 连接额度。
 */
class RemoteControlFragment : KotlinBaseFragment<FragmentRemoteControlBinding>() {

    private val kTag = "RemoteControlFragment"
    private val ctx by lazy { requireContext() }
    private val theme by lazy { ctx.theme }
    private val gson = Gson()

    /** 最近一次连接成功的时间戳（用于「最后心跳」展示） */
    private var lastConnectedMs = 0L
    /** 最近一次到 broker 的 RTT（ms），由 MqttAgentService.measureRtt 测量 */
    private var lastRttMs = -1L
    /** Hero 电源按钮当前背景资源（用于绿↔红交叉淡入，避免重复设置） */
    private var lastHeroPowerRes = 0

    /** 断线重连倒计时刷新（Hero 区 retryHint 每秒刷新） */
    private val retryRunnable = object : Runnable {
        override fun run() {
            if (!isHidden) updateHeroUI()
        }
    }

    override fun initViewBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentRemoteControlBinding =
        FragmentRemoteControlBinding.inflate(inflater, container, false)

    override fun setupTopBarLayout() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { view, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.setPadding(0, statusBarHeight, 0, 0)
            insets
        }
        binding.toolbar.setNavigationOnClickListener {
            (activity as? MainActivity)?.switchToTaskTab()
        }
    }

    override fun initOnCreate(savedInstanceState: Bundle?) {
        ensureDeviceIdentity()
        reloadSettingsUI()

        binding.publicMqttRow.setOnClickListener { showPublicMqttDialog() }
        binding.guideRow.setOnClickListener { showConfigGuide() }
        binding.brokerRow.setOnClickListener { editTextRow("MQTT 服务器", Constant.MQTT_BROKER_KEY, binding.brokerValue, false) }
        binding.userRow.setOnClickListener { editTextRow("被控端用户名", Constant.MQTT_USER_KEY, binding.userValue, false) }
        binding.passRow.setOnClickListener { editTextRow("被控端密码", Constant.MQTT_PASS_KEY, binding.passValue, true) }
        binding.deviceIdRow.setOnClickListener { editDeviceId() }
        binding.ctlRow.setOnClickListener { showCtlEditDialog() }
        binding.apiUrlRow.setOnClickListener { editTextRow("API 地址", Constant.MQTT_SERVERLESS_API_URL_KEY, binding.apiUrlValue, false) }
        binding.apiAppIdRow.setOnClickListener { editTextRow("AppID", Constant.MQTT_SERVERLESS_API_APP_ID_KEY, binding.apiAppIdValue, false) }
        binding.apiAppSecretRow.setOnClickListener { editTextRow("AppSecret", Constant.MQTT_SERVERLESS_API_APP_SECRET_KEY, binding.apiAppSecretValue, true) }
        binding.apiTestRow.setOnClickListener { testApiConnection() }
        binding.apiClientsRow.setOnClickListener { showApiClients() }
        binding.qrRow.setOnClickListener { generateAndShowQR() }
        binding.unbindRow.setOnClickListener { forceUnbind() }
        binding.btnGoQr.setOnClickListener { generateAndShowQR() }
        binding.btnRetryNow.setOnClickListener { MqttAgentService.reconnectNow() }
        binding.heroPowerBtn.setOnClickListener {
            onRemoteServiceChanged(!SaveKeyValues.loadBoolean(Constant.MQTT_ENABLED_KEY, false))
        }

        // 额度折叠初始状态
        val collapsed = SaveKeyValues.loadBoolean("collapse_quota", true)
        applyCollapse(binding.bodyQuota, binding.ivChevronQuota, !collapsed)
        binding.btnToggleQuota.setOnClickListener {
            toggleSection(binding.bodyQuota, binding.ivChevronQuota, "collapse_quota")
        }

        registerListeners()
        startMqttService()
    }

    override fun observeRequestState() {
    }

    override fun initEvent() {
    }

    override fun onResume() {
        super.onResume()
        if (!isHidden) refreshUi()
    }

    override fun onPause() {
        super.onPause()
        binding.root.removeCallbacks(retryRunnable)
        if (MqttAgentService.isRunning()) {
            MqttAgentService.stateListener = null
            MqttAgentService.bindingStateListener = null
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && isAdded) refreshUi()
    }

    override fun onDestroyView() {
        binding.root.removeCallbacks(retryRunnable)
        super.onDestroyView()
    }

    // ═══════════════════════ 连接与状态 ═══════════════════════

    private fun registerListeners() {
        MqttAgentService.stateListener = { connected ->
            activity?.runOnUiThread {
                if (connected) lastConnectedMs = System.currentTimeMillis()
                updateStatusUI(connected)
                updateHeroUI()
            }
        }
        MqttAgentService.bindingStateListener = { bound ->
            activity?.runOnUiThread {
                updateBindingUI(bound)
            }
        }
        MqttAgentService.notifyState()
    }

    private fun refreshUi() {
        startMqttService()
        registerListeners()
        updateStatusUI(MqttAgentService.isConnected())
        updateBindingUI(MqttAgentService.isBound())
        updateRescanBanner()
        updateHeroUI()
        updateQuotaUI()
        MqttAgentService.measureRtt { rtt ->
            lastRttMs = rtt
            activity?.runOnUiThread { updateQuotaUI() }
        }
        if (isAdded && MqttAgentService.isConnected() && !MqttAgentService.isBound()) {
            binding.root.postDelayed({
                if (MqttAgentService.isConnected() && !MqttAgentService.isBound()) {
                    "已连接但未绑定：请点「生成绑定二维码」，再用控制端 App 扫码完成配对".show(ctx)
                }
            }, 800)
        }
    }

    /** 刷新 MQTT 配置显示（Broker/账号/设备ID/ctl/API） */
    private fun reloadSettingsUI() {
        binding.brokerValue.text = SaveKeyValues.loadString(Constant.MQTT_BROKER_KEY, "").ifBlank { "未设置" }
        binding.userValue.text = SaveKeyValues.loadString(Constant.MQTT_USER_KEY, "").ifBlank { "未设置" }
        binding.passValue.text = if (MqttSecureConfig.loadPass().isNotBlank()) "已设置" else "未设置"
        binding.deviceIdValue.text = SaveKeyValues.loadString(Constant.DEVICE_ID_KEY, "").ifBlank { "未生成" }
        binding.ctlValue.text = SaveKeyValues.loadString(Constant.MQTT_CTL_USER_KEY, "").ifBlank { "未生成" }
        binding.apiUrlValue.text = SaveKeyValues.loadString(Constant.MQTT_SERVERLESS_API_URL_KEY, "").ifBlank { "未设置" }
        binding.apiAppIdValue.text = SaveKeyValues.loadString(Constant.MQTT_SERVERLESS_API_APP_ID_KEY, "").ifBlank { "未设置" }
        binding.apiAppSecretValue.text = if (ServerlessApiSecureConfig.loadSecret().isNotBlank()) "已设置" else "未设置"
        updateStatusUI(MqttAgentService.isConnected())
        updateBindingUI(MqttAgentService.isBound())
        updateHeroUI()
    }

    private fun updateStatusUI(connected: Boolean) {
        binding.heroConnStatus.text = if (connected) "已连接" else "未连接"
        binding.heroConnStatus.setBackgroundResource(
            if (connected) R.drawable.bg_status_pill_online else R.drawable.bg_status_pill_offline
        )
        binding.heroHeartbeat.text = if (connected) formatHeartbeat() else ""
    }

    private fun updateBindingUI(bound: Boolean) {
        val connected = MqttAgentService.isConnected()
        val reason = MqttAgentService.lastUnbindReason
        val text = when {
            bound -> "已绑定"
            reason == "force" || reason == "remote" -> "已解绑"
            connected -> "待扫码"
            else -> "未绑定"
        }
        binding.heroBindStatus.text = text
        binding.heroBindStatus.setBackgroundResource(
            if (bound) R.drawable.bg_status_pill_online else R.drawable.bg_status_pill_offline
        )
        updateRescanBanner()
    }

    private fun updateHeroUI() {
        val enabled = SaveKeyValues.loadBoolean(Constant.MQTT_ENABLED_KEY, false)
        val connected = MqttAgentService.isConnected()
        binding.root.removeCallbacks(retryRunnable)
        val retrying = enabled && !connected
        val nextReconnectIn = ((MqttAgentService.nextReconnectAtMs - System.currentTimeMillis()) / 1000).coerceAtLeast(0)

        if (enabled) {
            if (!connected) {
                binding.heroIcon.setImageResource(R.drawable.daily_ic_cancel_circle)
                binding.heroSubtitle.text = if (nextReconnectIn <= 0) "连接中…" else "连接中 · ${nextReconnectIn}s"
                applyHeroPowerBg(R.drawable.bg_hero_power_on)
                binding.heroDesc.text = "正在尝试建立连接，请稍候…"
            } else {
                binding.heroIcon.setImageResource(R.drawable.daily_ic_check_circle)
                binding.heroSubtitle.text = "正在运行"
                applyHeroPowerBg(R.drawable.bg_hero_power_on)
                binding.heroDesc.text = "远程控制服务运行中，控制端可随时查看与操作本机。"
            }
        } else {
            binding.heroIcon.setImageResource(R.drawable.daily_ic_cancel_circle)
            binding.heroSubtitle.text = "已关闭"
            applyHeroPowerBg(R.drawable.bg_hero_power_off)
            binding.heroDesc.text = "开启后启动远控服务功能，控制端可远程查看与操作本机。关闭则完全停止本机远控相关服务。"
        }
        binding.heroVersion.text = BuildConfig.VERSION_NAME

        if (retrying) {
            binding.retryHint.text = if (nextReconnectIn <= 0) "正在尝试重连…" else "断线，约 $nextReconnectIn 秒后自动重连"
            binding.root.postDelayed(retryRunnable, 1000)
        }
        binding.retryRow.visibility = if (retrying) View.VISIBLE else View.GONE
        applyRemoteDisabled(enabled)
    }

    private fun applyRemoteDisabled(enabled: Boolean) {
        val configAlpha = if (enabled) 0.45f else 1f
        val actionAlpha = 1f
        // MQTT 配置类行：服务开启时禁用
        listOf(
            binding.brokerRow, binding.userRow, binding.passRow,
            binding.apiUrlRow, binding.apiAppIdRow, binding.apiAppSecretRow,
            binding.deviceIdRow, binding.ctlRow
        ).forEach {
            it.alpha = configAlpha
            it.isClickable = !enabled
            it.isFocusable = !enabled
        }
        // 操作类行：始终可用
        listOf(
            binding.guideRow, binding.qrRow, binding.unbindRow,
            binding.apiTestRow
        ).forEach {
            it.alpha = actionAlpha
            it.isClickable = true
            it.isFocusable = true
        }
    }

    private fun applyHeroPowerBg(res: Int) {
        if (res == lastHeroPowerRes) return
        val old = lastHeroPowerRes
        lastHeroPowerRes = res
        if (old != 0) {
            val from = ContextCompat.getDrawable(ctx, old) ?: return
            val to = ContextCompat.getDrawable(ctx, res) ?: return
            TransitionDrawable(arrayOf(from, to)).apply {
                isCrossFadeEnabled = true
                binding.heroPowerBtn.background = this
                startTransition(220)
            }
        } else {
            binding.heroPowerBtn.setBackgroundResource(res)
        }
    }

    private fun formatHeartbeat(): String {
        if (lastConnectedMs <= 0) return "—"
        val seconds = (System.currentTimeMillis() - lastConnectedMs) / 1000
        return when {
            seconds >= 3600 -> "${seconds / 3600}小时前"
            seconds >= 60 -> "${seconds / 60}分钟前"
            else -> "${seconds}秒前"
        }
    }

    private fun computeConnQuality(): String {
        if (!MqttAgentService.isConnected()) return "未连接"
        if (lastRttMs < 0) {
            if (lastConnectedMs > 0) {
                val seconds = (System.currentTimeMillis() - lastConnectedMs) / 1000
                return when {
                    seconds < 30 -> "优"
                    seconds < 120 -> "良"
                    seconds < 300 -> "一般"
                    else -> "弱"
                }
            }
            return "已连接"
        }
        return when {
            lastRttMs < 300 -> "优 · ${lastRttMs}ms"
            lastRttMs < 800 -> "良 · ${lastRttMs}ms"
            lastRttMs < 2000 -> "一般 · ${lastRttMs}ms"
            else -> "弱 · ${lastRttMs}ms"
        }
    }

    private fun resolveRunningModeText(): String = when (SaveKeyValues.loadInt(Constant.RESULT_SOURCE_KEY, -1)) {
        0 -> "通知监听"
        1 -> "截屏反馈"
        2 -> {
            val feedback = if (SaveKeyValues.loadInt(Constant.ACCESSIBILITY_FEEDBACK_MODE_KEY, 0) != 0) "文本" else "截屏"
            "无障碍-${feedback}反馈"
        }

        else -> "未配置"
    }

    // ═══════════════════════ MQTT 配置编辑 ═══════════════════════

    private fun ensureDeviceIdentity() {
        if (SaveKeyValues.loadString(Constant.DEVICE_ID_KEY, "").isBlank()) {
            SaveKeyValues.saveString(Constant.DEVICE_ID_KEY, UUID.randomUUID().toString().take(8))
        }
        val deviceId = SaveKeyValues.loadString(Constant.DEVICE_ID_KEY, "")
        if (SaveKeyValues.loadString(Constant.MQTT_CTL_USER_KEY, "").isBlank()) {
            SaveKeyValues.saveString(Constant.MQTT_CTL_USER_KEY, "ctl-$deviceId")
        }
        if (SaveKeyValues.loadString(Constant.MQTT_CTL_PASS_KEY, "").isBlank()) {
            val bytes = ByteArray(24)
            SecureRandom().nextBytes(bytes)
            SaveKeyValues.saveString(Constant.MQTT_CTL_PASS_KEY, bytes.joinToString("") { "%02x".format(it) })
        }
    }

    private fun editTextRow(title: String, key: String, target: TextView, isSecret: Boolean) {
        val current = SaveKeyValues.loadString(key, "")
        val hint = when {
            current.isNotBlank() -> if (isSecret) "当前：已设置" else "当前：$current"
            else -> "请输入$title"
        }
        val editText = EditText(ctx).apply { this.hint = hint }
        UnifiedDialogKit.showForm(
            ctx, editText,
            title = title,
            positiveText = "保存",
            negativeText = "取消",
            onConfirm = {
                val value = editText.text.toString().trim()
                if (value.isEmpty()) {
                    "输入错误，请检查！".show(ctx)
                    false
                } else {
                    SaveKeyValues.saveString(key, value)
                    target.text = if (isSecret) "已设置" else value
                    ConfigImportSignal.notifyRemoteChanged(ctx)
                    "已保存".show(ctx)
                    true
                }
            }
        )
    }

    private fun editDeviceId() {
        val current = SaveKeyValues.loadString(Constant.DEVICE_ID_KEY, "")
        val hint = if (current.isNotBlank()) "当前：$current" else "请输入设备ID（建议与 EMQX 中规划的一致，如 k20pro）"
        val editText = EditText(ctx).apply { this.hint = hint }
        UnifiedDialogKit.showForm(
            ctx, editText,
            title = "设备ID",
            positiveText = "保存",
            negativeText = "取消",
            onConfirm = {
                val deviceId = editText.text.toString().trim()
                if (deviceId.isEmpty()) {
                    "输入错误，请检查！".show(ctx)
                    false
                } else {
                    SaveKeyValues.saveString(Constant.DEVICE_ID_KEY, deviceId)
                    // 主题前缀与控制端账户同步
                    if (SaveKeyValues.loadString(Constant.MQTT_CTL_USER_KEY, "").startsWith("ctl-")) {
                        SaveKeyValues.saveString(Constant.MQTT_CTL_USER_KEY, "ctl-$deviceId")
                    }
                    binding.deviceIdValue.text = deviceId
                    binding.ctlValue.text = SaveKeyValues.loadString(Constant.MQTT_CTL_USER_KEY, "")
                    ConfigImportSignal.notifyRemoteChanged(ctx)
                    "已保存设备ID（主题前缀与控制端账户已同步；已绑定的控制端需重新扫码）".show(ctx)
                    true
                }
            }
        )
    }

    private fun showCtlEditDialog() {
        val ctlUser = SaveKeyValues.loadString(Constant.MQTT_CTL_USER_KEY, "")
        val ctlPass = SaveKeyValues.loadString(Constant.MQTT_CTL_PASS_KEY, "")
        if (ctlUser.isBlank()) {
            "凭证未生成".show(ctx)
            return
        }
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }
        val etUser = EditText(ctx).apply {
            setText(ctlUser)
            hint = "控制端用户名（默认 ctl-{设备ID}）"
        }
        val etPass = EditText(ctx).apply {
            setText(ctlPass)
            hint = "控制端密码（默认随机生成）"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        container.addView(etUser)
        container.addView(TextView(ctx).apply { setPadding(0, 12, 0, 0) }) // spacing
        container.addView(etPass)
        // 复制凭证按钮：文字按钮样式（避免默认实心主色底+主色文字导致文字不可见）
        val btnCopy = com.google.android.material.button.MaterialButton(ctx, null, android.R.attr.borderlessButtonStyle).apply {
            text = "复制凭证"
            setTextColor(resources.getColor(R.color.md_primary, theme))
            setOnClickListener {
                val user = etUser.text.toString().trim()
                val pass = etPass.text.toString().trim()
                if (user.isBlank() || pass.isBlank()) {
                    "请先填写凭证再复制".show(ctx)
                } else {
                    copyToClipboard("控制端用户名：$user\n控制端密码：$pass", "ctl_credential")
                    "已复制控制端凭证，可粘贴到 EMQX".show(ctx)
                }
            }
        }
        container.addView(btnCopy)
        container.addView(TextView(ctx).apply { setPadding(0, 8, 0, 0) })
        UnifiedDialogKit.showForm(
            ctx = ctx,
            contentView = container,
            title = "控制端凭证 (ctl)",
            message = "控制端通过此账户连接 MQTT，仅拥有本设备的受限权限。\n" +
                "默认由 App 自动生成（ctl-{设备ID} + 随机密码）；你也可以改为自定义账户，" +
                "但需与 EMQX 中建立的账户名/密码保持一致（含 ACL）。",
            positiveText = "保存",
            negativeText = "取消",
            onConfirm = { dlg ->
                val user = etUser.text.toString().trim()
                val pass = etPass.text.toString().trim()
                if (user.isBlank() || pass.isBlank()) {
                    "用户名与密码均不能为空".show(ctx)
                    false
                } else {
                    SaveKeyValues.saveString(Constant.MQTT_CTL_USER_KEY, user)
                    SaveKeyValues.saveString(Constant.MQTT_CTL_PASS_KEY, pass)
                    binding.ctlValue.text = user
                    "已保存控制端凭证".show(ctx)
                    true
                }
            }
        )
    }

    private fun isMqttConfigValid(): Boolean =
        SaveKeyValues.loadString(Constant.MQTT_BROKER_KEY, "").isNotBlank() &&
            SaveKeyValues.loadString(Constant.MQTT_USER_KEY, "").isNotBlank() &&
            MqttSecureConfig.loadPass().isNotBlank()

    private fun normalizeBrokerHost(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return trimmed
        val schemeIdx = trimmed.indexOf("://")
        val scheme = if (schemeIdx > 0) trimmed.substring(0, schemeIdx + 3) else ""
        val host = if (schemeIdx > 0) trimmed.substring(schemeIdx + 3) else trimmed
        if (host.isBlank()) return trimmed
        // 已带端口或 IPv6 字面量则原样返回
        if (host.startsWith("[") || host.contains(":")) return trimmed
        val isSecure = scheme.startsWith("ssl") || scheme.startsWith("wss") || scheme.startsWith("mqtts")
        val defaultPort = if (scheme.isNotEmpty() && !isSecure) 1883 else 8883
        return if (scheme.isEmpty() && !isSecure) "ssl://$host:$defaultPort" else "$scheme$host:$defaultPort"
    }

    // ═══════════════════════ MQTT 服务 ═══════════════════════

    private fun startMqttService() {
        if (SaveKeyValues.loadBoolean(Constant.MQTT_ENABLED_KEY, false) &&
            isMqttConfigValid() && !MqttAgentService.isRunning()
        ) {
            ctx.startForegroundService(Intent(ctx, MqttAgentService::class.java))
        }
    }

    private fun restartMqttService() {
        ctx.stopService(Intent(ctx, MqttAgentService::class.java))
        if (SaveKeyValues.loadBoolean(Constant.MQTT_ENABLED_KEY, false) && isMqttConfigValid()) {
            ctx.startForegroundService(Intent(ctx, MqttAgentService::class.java))
        }
    }

    private fun onRemoteServiceChanged(enabled: Boolean) {
        SaveKeyValues.saveBoolean(Constant.MQTT_ENABLED_KEY, enabled)
        if (!enabled) {
            ctx.stopService(Intent(ctx, MqttAgentService::class.java))
            updateStatusUI(false)
            updateHeroUI()
        } else {
            if (isMqttConfigValid()) {
                ctx.startForegroundService(Intent(ctx, MqttAgentService::class.java))
                updateHeroUI()
            } else {
                SaveKeyValues.saveBoolean(Constant.MQTT_ENABLED_KEY, false)
                updateHeroUI()
                "请先完成 MQTT 服务器 / 被控端用户名 / 被控端密码 配置".show(ctx)
            }
        }
    }

    private fun copyToClipboard(text: String, label: String = "pairing_payload") {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun openUrl(url: String) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        } catch (e: Exception) {
            url.show(ctx)
        }
    }

    // ═══════════════════════ 绑定二维码 ═══════════════════════

    @Throws(WriterException::class)
    private fun encodeQR(content: String, size: Int): Bitmap {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val matrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                pixels[y * w + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }

    private fun generateAndShowQR() {
        val broker = SaveKeyValues.loadString(Constant.MQTT_BROKER_KEY, "")
        if (broker.isBlank()) {
            "请先设置 MQTT 服务器（可点「MQTT 配置引导」查看获取方式）".show(ctx)
            return
        }
        if (SaveKeyValues.loadString(Constant.MQTT_USER_KEY, "").isBlank() || MqttSecureConfig.loadPass().isBlank()) {
            "请先填写被控端用户名与密码".show(ctx)
            return
        }
        if (MqttAgentService.isBound()) {
            UnifiedDialogKit.showWarning(
                ctx,
                "设备已绑定",
                "当前设备已与控制端配对。如需重新绑定，请先「强制解绑」再生成二维码。",
                confirmText = "强制解绑",
                cancelText = "我知道了",
                onConfirm = { forceUnbind() }
            )
            return
        }
        // 生成单次 / 60s 配对令牌（进二维码，不出长期密钥）
        val tokenBytes = ByteArray(16)
        SecureRandom().nextBytes(tokenBytes)
        val pairingToken = tokenBytes.joinToString("") { "%02x".format(it) }
        SaveKeyValues.saveString(Constant.MQTT_PAIRING_TOKEN_KEY, pairingToken)
        SaveKeyValues.saveLong(
            Constant.MQTT_PAIRING_EXPIRY_KEY,
            System.currentTimeMillis() + Protocol.PAIRING_TTL_MS
        )

        val payload = BindingPayload(
            broker = broker,
            deviceId = SaveKeyValues.loadString(Constant.DEVICE_ID_KEY, ""),
            ctlUser = SaveKeyValues.loadString(Constant.MQTT_CTL_USER_KEY, ""),
            ctlPass = SaveKeyValues.loadString(Constant.MQTT_CTL_PASS_KEY, ""),
            pairingToken = pairingToken
        )
        val payloadJson = gson.toJson(payload)
        try {
            val bitmap = encodeQR(payloadJson, 512)
            showQRDialog(bitmap, payloadJson)
            // 重新生成二维码即视为已处理设备ID变更，清除常驻提示
            SaveKeyValues.saveBoolean("pending_rescan", false)
            updateRescanBanner()
        } catch (e: WriterException) {
            e.printStackTrace()
            "二维码生成失败".show(ctx)
        }
    }

    /** 二维码弹窗：含扫码码 + 控制端 CTL 凭证（需在 EMQX 建同名受限账户） */
    private fun showQRDialog(bitmap: Bitmap, payloadJson: String) {
        val ctlUser = SaveKeyValues.loadString(Constant.MQTT_CTL_USER_KEY, "")
        val ctlPass = SaveKeyValues.loadString(Constant.MQTT_CTL_PASS_KEY, "")
        val scroll = ScrollView(ctx).apply { setPadding(40, 24, 40, 24) }
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        val iv = ImageView(ctx).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
        }
        container.addView(iv)

        val note = TextView(ctx).apply {
            text = "用控制端 App 扫描二维码完成绑定，令牌 2 分钟有效。\n\n控制端凭证（EMQX 中建同名账户）："
            setTextColor(ContextCompat.getColor(ctx, R.color.md_onSurfaceVariant))
            textSize = 13f
            setPadding(0, 14, 0, 0)
        }
        container.addView(note)

        val userLine = TextView(ctx).apply {
            text = "用户名：$ctlUser"
            setTextColor(ContextCompat.getColor(ctx, R.color.md_onSurfaceVariant))
            textSize = 13f
        }
        container.addView(userLine)

        // 密码显示/隐藏切换
        val passLine = TextView(ctx).apply {
            text = "密码：••••••••"
            setTextColor(ContextCompat.getColor(ctx, R.color.md_onSurfaceVariant))
            textSize = 13f
        }
        val toggle = TextView(ctx).apply {
            text = "显示"
            setTextColor(ContextCompat.getColor(ctx, R.color.md_primary))
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setPadding(20, 0, 0, 0)
            setOnClickListener {
                val showing = passLine.text.toString().startsWith("密码：$ctlPass")
                passLine.text = if (showing) "密码：••••••••" else "密码：$ctlPass"
                text = if (showing) "显示" else "隐藏"
            }
        }
        val passRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 0)
            addView(passLine)
            addView(toggle)
        }
        container.addView(passRow)
        scroll.addView(container)

        MaterialAlertDialogBuilder(ctx)
            .setTitle("扫描绑定设备")
            .setView(scroll)
            .setNeutralButton("复制配对信息") { _, _ ->
                copyToClipboard(payloadJson)
                "已复制配对信息到剪贴板".show(ctx)
            }
            .setPositiveButton("关闭", null)
            .show()
    }

    /** 强制解绑：确认后清绑定态，保留 MQTT 配置 */
    private fun forceUnbind() {
        UnifiedDialogKit.showWarning(
            ctx,
            "强制解绑",
            "确定强制解绑？解绑后，已绑定的控制端将无法再查询或控制本设备。\n\n" +
                "注意：本操作只解除绑定关系，不会清除本机的 MQTT 配置（服务器 / 账号 / 设备ID / 控制端凭证），" +
                "下次直接生成二维码即可重新绑定，无需重新填写。",
            confirmText = "强制解绑"
        ) {
            MqttAgentService.unbind() // 仅清绑定态，保留 MQTT 配置
            "已强制解绑（MQTT 配置已保留）".show(ctx)
        }
    }

    // ═══════════════════════ 额度统计 ═══════════════════════

    private fun addQuotaRow(label: String, value: String) {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, resources.getDimensionPixelSize(R.dimen.dp_4), 0, resources.getDimensionPixelSize(R.dimen.dp_4))
        }
        row.addView(TextView(ctx).apply {
            text = label
            setTextColor(resources.getColor(R.color.text_default_color, theme))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(ctx).apply {
            text = value
            setTextColor(resources.getColor(R.color.text_hint_color, theme))
            textSize = 14f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        })
        binding.quotaDetailsLayout.addView(row)
    }

    private fun addQuotaSubHeader(title: String) {
        binding.quotaDetailsLayout.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                resources.getDimensionPixelSize(R.dimen.dividerLine)
            ).apply { topMargin = resources.getDimensionPixelSize(R.dimen.dp_8) }
            setBackgroundColor(resources.getColor(R.color.outline_variant, theme))
        })
        binding.quotaDetailsLayout.addView(TextView(ctx).apply {
            text = title
            setTextColor(resources.getColor(R.color.text_default_color, theme))
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = resources.getDimensionPixelSize(R.dimen.dp_8) }
        })
    }

    private fun updateQuotaUI() {
        val stats = MqttQuota.get(ctx)
        binding.quotaValue.text = "已用 ${stats.total}"
        binding.quotaProgress.visibility = View.GONE
        binding.quotaDetailsLayout.removeAllViews()

        val deviceId = SaveKeyValues.loadString(Constant.DEVICE_ID_KEY, "")
        val broker = SaveKeyValues.loadString(Constant.MQTT_BROKER_KEY, "").ifBlank { "未设置" }
        val lastConn = if (lastConnectedMs > 0) {
            SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(lastConnectedMs))
        } else {
            "—"
        }
        val clientId = if (deviceId.isNotBlank()) "dev-$deviceId" else "—"
        listOf(
            "连接质量" to computeConnQuality(),
            "Broker" to broker,
            "设备ID" to deviceId.ifBlank { "—" },
            "客户端ID" to clientId,
            "配对状态" to if (MqttAgentService.isBound()) "已配对" else "未配对",
            "最近连接" to lastConn
        ).forEach { (k, v) -> addQuotaRow(k, v) }

        addQuotaSubHeader("消息统计")
        listOf(
            "已累计连接" to MqttQuota.formatDuration(stats.totalConnectedMs),
            "本次连接" to MqttQuota.formatDuration(stats.sessionConnectedMs),
            "已发送消息" to "${stats.sent} 条",
            "已接收消息" to "${stats.received} 条",
            "消息总计" to "${stats.total} 条"
        ).forEach { (k, v) -> addQuotaRow(k, v) }
    }

    // ═══════════════════════ 折叠 ═══════════════════════

    private fun applyCollapse(content: View, chevron: View, expanded: Boolean) {
        content.visibility = if (expanded) View.VISIBLE else View.GONE
        chevron.rotation = if (expanded) 0f else 180f
    }

    private fun toggleSection(content: View, chevron: View, key: String) {
        val expanded = content.visibility != View.VISIBLE
        applyCollapse(content, chevron, expanded)
        SaveKeyValues.saveBoolean(key, !expanded)
    }

    // ═══════════════════════ Serverless API ═══════════════════════

    private fun loadApiConfig(): Pair<String, String>? {
        val baseUrl = SaveKeyValues.loadString(Constant.MQTT_SERVERLESS_API_URL_KEY, "").trim()
        val appId = SaveKeyValues.loadString(Constant.MQTT_SERVERLESS_API_APP_ID_KEY, "").trim()
        val appSecret = ServerlessApiSecureConfig.loadSecret().trim()
        if (baseUrl.isBlank() || appId.isBlank() || appSecret.isBlank()) {
            "请先填写 API 地址、AppID 和 AppSecret".show(ctx)
            return null
        }
        return baseUrl.removeSuffix("/") to Credentials.basic(appId, appSecret)
    }

    private fun testApiConnection() {
        val baseUrl = SaveKeyValues.loadString(Constant.MQTT_SERVERLESS_API_URL_KEY, "").trim()
        val appId = SaveKeyValues.loadString(Constant.MQTT_SERVERLESS_API_APP_ID_KEY, "").trim()
        val appSecret = ServerlessApiSecureConfig.loadSecret().trim()
        if (baseUrl.isBlank() || appId.isBlank() || appSecret.isBlank()) {
            "请先填写 API 地址、AppID 和 AppSecret".show(ctx)
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val client = OkHttpClient()
                val request = Request.Builder()
                    .url("${baseUrl.removeSuffix("/")}/clients")
                    .header("Authorization", Credentials.basic(appId, appSecret))
                    .header("Accept", "application/json")
                    .get()
                    .build()
                client.newCall(request).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (resp.code in 200..299) {
                        val count = JSONObject(body).optJSONArray("data")?.length() ?: 0
                        withContext(Dispatchers.Main) {
                            "连接成功，当前在线客户端 $count 个".show(ctx)
                        }
                    } else {
                        val msg = JSONObject(body).optString("message", "")
                        withContext(Dispatchers.Main) {
                            "API 请求失败：HTTP ${resp.code} $msg".show(ctx)
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    "API 请求异常：${e.message}".show(ctx)
                }
            }
        }
    }

    private fun fetchApiClients(
        baseUrl: String,
        auth: String,
        dialog: AlertDialog,
        onResult: (String, List<String>) -> Unit
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder()
                    .url("$baseUrl/clients?limit=100")
                    .header("Authorization", auth)
                    .header("Accept", "application/json")
                    .get()
                    .build()
                client.newCall(request).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (resp.code in 200..299) {
                        val array = JSONObject(body).optJSONArray("data")
                        val list = mutableListOf<String>()
                        if (array != null) {
                            for (i in 0 until array.length()) {
                                val obj = array.optJSONObject(i)
                                val clientId = obj?.optString("clientid") ?: ""
                                val ip = obj?.optString("ip_address") ?: ""
                                if (clientId.isNotBlank()) list.add("$clientId|$ip")
                            }
                        }
                        val title = "在线客户端（${list.size}）"
                        withContext(Dispatchers.Main) {
                            onResult(title, list)
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            onResult("API 请求失败：HTTP ${resp.code}", emptyList())
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult("API 请求异常：${e.message}", emptyList())
                }
            }
        }
    }

    private fun kickApiClient(baseUrl: String, clientId: String, auth: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder()
                    .url("$baseUrl/clients/${android.net.Uri.encode(clientId)}")
                    .header("Authorization", auth)
                    .header("Accept", "application/json")
                    .delete()
                    .build()
                client.newCall(request).execute().use { resp ->
                    val msg = if (resp.code in 200..299) "已下线客户端 $clientId" else "下线失败：HTTP ${resp.code}"
                    withContext(Dispatchers.Main) { msg.show(ctx) }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { "下线异常：${e.message}".show(ctx) }
            }
        }
    }

    private fun showApiClients() {
        val config = loadApiConfig() ?: return
        val (baseUrl, auth) = config
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle("在线客户端")
            .setMessage("正在查询…")
            .setPositiveButton("刷新", null)
            .setNegativeButton("关闭", null)
            .create()
        dialog.show()
        fetchApiClients(baseUrl, auth, dialog) { title, clients ->
            renderApiClients(dialog, title, clients, baseUrl, auth)
        }
    }

    private fun renderApiClients(
        dialog: AlertDialog,
        title: String,
        clients: List<String>,
        baseUrl: String,
        auth: String
    ) {
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 12, 24, 12)
        }
        container.addView(TextView(ctx).apply {
            text = title
            setTextColor(resources.getColor(R.color.text_hint_color, theme))
            textSize = 14f
            setPadding(0, 0, 0, 8)
        })
        if (clients.isEmpty()) {
            container.addView(TextView(ctx).apply {
                text = if (title.contains("无在线客户端")) "当前没有在线客户端" else "无客户端信息"
                setTextColor(resources.getColor(R.color.text_hint_color, theme))
                textSize = 13f
                setPadding(0, 8, 0, 0)
            })
        } else {
            clients.forEach { entry ->
                val parts = entry.split("|")
                val clientId = parts.getOrElse(0) { entry }
                val ip = parts.getOrElse(1) { "" }
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 6, 0, 6)
                }
                row.addView(TextView(ctx).apply {
                    text = clientId
                    setTextColor(resources.getColor(R.color.text_default_color, theme))
                    textSize = 13f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.MIDDLE
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                if (ip.isNotBlank()) {
                    row.addView(TextView(ctx).apply {
                        text = ip
                        setTextColor(resources.getColor(R.color.text_hint_color, theme))
                        textSize = 12f
                    })
                }
                row.addView(TextView(ctx).apply {
                    text = "下线"
                    setTextColor(R.color.red.convertColor(ctx))
                    textSize = 13f
                    setTypeface(null, Typeface.BOLD)
                    setPadding(16, 0, 0, 0)
                    setOnClickListener { kickApiClient(baseUrl, clientId, auth) }
                })
                container.addView(row)
            }
        }
        dialog.setView(container)
        dialog.setTitle("在线客户端")
    }

    // ═══════════════════════ 公共 MQTT / 引导 ═══════════════════════

    /** 应用临时公共 MQTT 配置：填 broker + 随机账号密码，重启服务并标记使用来源 */
    private fun applyPublicMqttConfig() {
        val deviceId = SaveKeyValues.loadString(Constant.DEVICE_ID_KEY, "").ifBlank {
            UUID.randomUUID().toString().take(8).also { SaveKeyValues.saveString(Constant.DEVICE_ID_KEY, it) }
        }
        // 公共 broker 匿名开放，账号按设备ID派生，保证每台被控端 DEV/CTL 账户与主题均唯一、互不冲突
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        val randomUser = "dev-$deviceId"
        SaveKeyValues.saveString(Constant.MQTT_BROKER_KEY, "broker.emqx.io:1883")
        SaveKeyValues.saveString(Constant.MQTT_USER_KEY, randomUser)
        MqttSecureConfig.savePass(bytes.joinToString("") { "%02x".format(it) })
        SaveKeyValues.saveBoolean(Constant.MQTT_USE_PUBLIC_KEY, true)
        if (SaveKeyValues.loadString(Constant.DEVICE_ID_KEY, "").isBlank()) {
            SaveKeyValues.saveString(Constant.DEVICE_ID_KEY, deviceId)
        }
        reloadSettingsUI()
        restartMqttService()
        "已配置临时公共 MQTT，正在连接…（请及时生成二维码完成绑定）".show(ctx)
    }

    /** 临时公共 MQTT（测试用）弹窗：信息卡 + 警告 + 建议（可点跳转配置引导） */
    private fun showPublicMqttDialog() {
        val dip = { px: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, px.toFloat(), resources.displayMetrics).toInt() }
        val cInk = ContextCompat.getColor(ctx, R.color.md_onSurface)       // #1f2329
        val cSub = ContextCompat.getColor(ctx, R.color.md_onSurfaceVariant) // #5b6470
        val cSurface = ContextCompat.getColor(ctx, R.color.md_surface)      // #FBF8FF
        val cOutline = ContextCompat.getColor(ctx, R.color.md_outlineVariant)
        val cPrimary = ContextCompat.getColor(ctx, R.color.md_primary)       // #5B5BD6
        val cWarnBg = Color.parseColor("#FFF8E6")
        val cWarnBorder = Color.parseColor("#FFE082")
        val cWarnText = Color.parseColor("#B07D00")
        val cSuccessBg = Color.parseColor("#F0FDF4")
        val cSuccessBorder = Color.parseColor("#BBF7D0")
        val cSuccessText = Color.parseColor("#166534")

        fun cardBg(color: Int, strokeColor: Int = color) = android.graphics.drawable.GradientDrawable().apply {
            setColor(color); cornerRadius = dip(14).toFloat(); setStroke(dip(1), strokeColor)
        }

        val container = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        // ── 信息区：服务器 + 账号合并为紧凑两行 ──
        val infoCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dip(16), dip(12), dip(16), dip(12))
            background = cardBg(cSurface, cOutline)
        }
        fun infoLine(label: String, value: String): TextView = TextView(ctx).apply {
            text = android.text.SpannableStringBuilder().append(android.text.SpannableString(label)).apply {
                setSpan(android.text.style.ForegroundColorSpan(cSub), 0, label.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(android.text.style.RelativeSizeSpan(0.88f), 0, label.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }.append("  ").append(android.text.SpannableString(value)).apply {
                setSpan(android.text.style.StyleSpan(Typeface.BOLD), length - value.length, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(android.text.style.ForegroundColorSpan(cInk), length - value.length, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            textSize = 13.5f; setPadding(0, dip(3), 0, dip(3))
        }
        infoCard.addView(infoLine("服务器", "broker.emqx.io:1883"))
        infoCard.addView(infoLine("被控端账号", "随机生成（公共 broker 匿名开放）"))
        container.addView(infoCard)

        // ── 警告：压缩为两行 ──
        val gap = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dip(10)) }
        container.addView(gap)
        val warnCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg(cWarnBg, cWarnBorder); setPadding(dip(14), dip(10), dip(14), dip(10))
        }
        warnCard.addView(TextView(ctx).apply {
            text = "⚠ 仅适合临时测试 · 消息不保证持久 · 命名空间全局共享"
            setTextColor(cWarnText); textSize = 12.5f
        })
        container.addView(warnCard)

        // ── 建议：一行搞定 ──
        container.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dip(8)) })
        val adviceRow = TextView(ctx).apply {
            text = "💡 长期使用建议自建 EMQX  →  配置引导"
            setTextColor(cSuccessText); textSize = 13f; setTypeface(null, Typeface.BOLD)
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            setPadding(dip(4), dip(2), 0, 0)
            setOnClickListener { showConfigGuide() }
        }
        container.addView(adviceRow)

        UnifiedDialogKit.showForm(
            ctx,
            ScrollView(ctx).apply {
                addView(container)
                setPadding(dip(20), dip(8), dip(20), dip(8))
            },
            title = "临时公共 MQTT（测试用）",
            positiveText = "一键配置",
            negativeText = "取消",
            onConfirm = {
                applyPublicMqttConfig()
                true
            }
        )
    }

    /** MQTT 配置引导：原生卡片式弹窗（替代 HTML+WebView，色彩鲜明、秒开无延迟） */
    private fun showConfigGuide(onOk: (() -> Unit)? = null) {
        val dip = { px: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, px.toFloat(), resources.displayMetrics).toInt() }
        val cInk = ContextCompat.getColor(ctx, R.color.md_onSurface)
        val cSub = ContextCompat.getColor(ctx, R.color.md_onSurfaceVariant)
        val cPrimary = ContextCompat.getColor(ctx, R.color.md_primary)       // #5B5BD6
        val cSurface = ContextCompat.getColor(ctx, R.color.md_surface)
        val cOutline = ContextCompat.getColor(ctx, R.color.md_outlineVariant)
        val cCodeBg = Color.parseColor("#EEF1F4")
        val cCodeInk = Color.parseColor("#C0264A")
        val cConceptBg = Color.parseColor("#F8F6FF")
        val cConceptBorder = Color.parseColor("#DDD6FE")
        val cTipBg = Color.parseColor("#EFF6FF")
        val cTipBorder = Color.parseColor("#BFDBFE")
        val cTipAccent = Color.parseColor("#3D5AFE")

        fun card(color: Int, stroke: Int = cOutline, r: Int = 14): android.graphics.drawable.GradientDrawable =
            android.graphics.drawable.GradientDrawable().apply {
                setColor(color); cornerRadius = dip(r).toFloat(); setStroke(dip(1), stroke)
            }
        fun codeLabel(content: String): TextView = TextView(ctx).apply {
            text = content; textSize = 11.5f; setTypeface(Typeface.MONOSPACE)
            setTextColor(cCodeInk); background = GradientDrawable().apply {
                setColor(cCodeBg); cornerRadius = dip(5).toFloat(); setStroke(dip(1), Color.parseColor("#DDE3E8"))
            }
            setPadding(dip(5), dip(1), dip(5), dip(1))
        }
        fun tagLabel(content: String, bgColor: Int = Color.parseColor("#EDE9FE"), textColor: Int = cPrimary): TextView =
            TextView(ctx).apply {
                text = content; textSize = 11.5f; setTypeface(null, Typeface.BOLD)
                setTextColor(textColor); background = GradientDrawable().apply {
                    setColor(bgColor); cornerRadius = dip(5).toFloat()
                }
                setPadding(dip(7), dip(1), dip(7), dip(1))
            }
        fun bullet(content: String): TextView = TextView(ctx).apply {
            text = "• $content"; textSize = 13f; setTextColor(cInk)
            setPadding(dip(4), dip(2), 0, dip(2))
        }
        fun stepBadge(content: String): TextView = TextView(ctx).apply {
            text = content; textSize = 13f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE); background = GradientDrawable().apply {
                setColor(cPrimary); cornerRadius = 999f
            }
            setPadding(dip(11), dip(2), dip(11), dip(2))
        }

        val container = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        // ── 核心概念 ──
        val conceptCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = card(cConceptBg, cConceptBorder); setPadding(dip(14), dip(12), dip(14), dip(12))
        }
        val conceptTitle = TextView(ctx).apply {
            text = android.text.SpannableStringBuilder("【核心概念】").apply {
                setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(android.text.style.ForegroundColorSpan(cPrimary), 0, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            textSize = 13.5f
        }
        val conceptBody = TextView(ctx).apply {
            text = "本 App 用「设备ID」标识设备，所有 MQTT 主题都在 dt/设备ID/ 下。\n例：设备ID为 d7da9d15 → 主题前缀为 dt/d7da9d15/"
            textSize = 13f; setTextColor(cSub); setLineSpacing(dip(3).toFloat(), 1f)
            setPadding(0, dip(6), 0, 0)
        }
        conceptCard.addView(conceptTitle); conceptCard.addView(conceptBody)
        container.addView(conceptCard)

        // ── 步骤① 注册 EMQX ──
        container.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dip(10)) })
        val step1 = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; background = card(cSurface); setPadding(dip(16), dip(12), dip(16), dip(12))
        }
        step1.addView(stepBadge("① 注册 EMQX Cloud（电脑浏览器）"))
        listOf(
            "打开 emqx.com → 手机号注册登录",
            "「免费试用」→「云端」→ 新建 Serverless 部署（区域选最近的，消费限额设 0）",
            "等 1~2 分钟变绿「运行中」，记下连接地址与端口 8883(TLS)"
        ).forEach { step1.addView(bullet(it)) }
        container.addView(step1)

        // ── 步骤② 建两个账户 ──
        container.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dip(10)) })
        val step2 = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; background = card(cSurface); setPadding(dip(16), dip(12), dip(16), dip(12))
        }
        step2.addView(stepBadge("② 在 EMQX 建两个账户"))
        step2.addView(TextView(ctx).apply {
            text = "左侧「访问控制」→「客户端认证」→ 新建"; textSize = 12f; setTextColor(cSub); setPadding(dip(4), dip(2), 0, dip(2))
        })
        // 内联 code 样式辅助
        fun codeStyle(content: String): CharSequence = android.text.SpannableString(content).apply {
            setSpan(android.text.style.BackgroundColorSpan(cCodeBg), 0, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(android.text.style.ForegroundColorSpan(cCodeInk), 0, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(android.text.style.StyleSpan(Typeface.MONOSPACE.style), 0, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val devRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dip(4), dip(4), 0, 0); gravity = android.view.Gravity.TOP }
        devRow.addView(tagLabel("被控端 DEV"))
        val devText = TextView(ctx).apply {
            text = android.text.SpannableStringBuilder()
                .append("用户名 = ")
                .append(codeStyle("dev-{设备ID}"))
                .append("，例如 ")
                .append(codeStyle("dev-d7da9d15"))
                .append("，密码自设")
            textSize = 13f; setTextColor(cInk); setLineSpacing(dip(2).toFloat(), 1f); setPadding(dip(8), 0, 0, 0)
        }
        devRow.addView(devText)
        step2.addView(devRow)
        val ctlRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dip(4), dip(4), 0, 0); gravity = android.view.Gravity.TOP }
        ctlRow.addView(tagLabel("控制端 CTL"))
        val ctlText = TextView(ctx).apply {
            text = android.text.SpannableStringBuilder()
                .append("用户名 = ")
                .append(codeStyle("ctl-{设备ID}"))
                .append("，例如 ")
                .append(codeStyle("ctl-d7da9d15"))
                .append("，密码自设")
            textSize = 13f; setTextColor(cInk); setLineSpacing(dip(2).toFloat(), 1f); setPadding(dip(8), 0, 0, 0)
        }
        ctlRow.addView(ctlText)
        step2.addView(ctlRow)
        container.addView(step2)

        // ── 步骤③ ACL 权限表 ──
        container.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dip(10)) })
        val step3 = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; background = card(cSurface); setPadding(dip(16), dip(12), dip(16), dip(12))
        }
        step3.addView(stepBadge("③ 配置 ACL 权限"))
        step3.addView(TextView(ctx).apply {
            text = "左侧「访问控制」→「客户端授权」→ 用户名，分别给两个账户各加一条相同规则："
            textSize = 12f; setTextColor(cSub); setPadding(dip(4), dip(2), 0, dip(2))
        })
        val aclHeader = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply { setColor(Color.parseColor("#EEF2F7")); cornerRadius = dip(8).toFloat() }
            setPadding(dip(10), dip(6), dip(10), dip(6))
        }
        listOf("主题" to 2.5f, "动作" to 1f, "是否允许" to 1.2f).forEach { (title, w) ->
            val tv = TextView(ctx).apply {
                text = title; textSize = 11.5f; setTypeface(null, Typeface.BOLD); setTextColor(cSub)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w)
            }
            aclHeader.addView(tv)
        }
        step3.addView(aclHeader)
        step3.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dip(4)) })
        // ── DEV 规则行 ──
        step3.addView(TextView(ctx).apply {
            text = "例：用户名 dev-d7da9d15 → 添加 →"; textSize = 11.5f; setTextColor(cSub); setPadding(dip(4), dip(2), 0, dip(2))
        })
        val aclRow1 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply { setColor(Color.parseColor("#F1F5F9")); cornerRadius = dip(8).toFloat() }
            setPadding(dip(10), dip(6), dip(10), dip(6)); gravity = android.view.Gravity.CENTER_VERTICAL
        }
        listOf(
            codeLabel("dt/d7da9d15/#") to 2.5f,
            codeLabel("发布&订阅") to 1f,
            (TextView(ctx).apply { text = " 允许"; textSize = 12.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#15803D")) }) to 1.2f
        ).forEach { (v, w) ->
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w)
            v.layoutParams = lp
            aclRow1.addView(v)
        }
        step3.addView(aclRow1)
        // ── CTL 规则行 ──
        step3.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dip(6)) })
        step3.addView(TextView(ctx).apply {
            text = "例：用户名 ctl-d7da9d15 → 添加 →"; textSize = 11.5f; setTextColor(cSub); setPadding(dip(4), dip(2), 0, dip(2))
        })
        val aclRow2 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply { setColor(Color.parseColor("#F1F5F9")); cornerRadius = dip(8).toFloat() }
            setPadding(dip(10), dip(6), dip(10), dip(6)); gravity = android.view.Gravity.CENTER_VERTICAL
        }
        listOf(
            codeLabel("dt/d7da9d15/#") to 2.5f,
            codeLabel("发布&订阅") to 1f,
            (TextView(ctx).apply { text = " 允许"; textSize = 12.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#15803D")) }) to 1.2f
        ).forEach { (v, w) ->
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w)
            v.layoutParams = lp
            aclRow2.addView(v)
        }
        step3.addView(aclRow2)
        // ── 白名单兜底建议 ──
        step3.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dip(8)) })
        val tipBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = card(Color.parseColor("#FFF7ED"), Color.parseColor("#FED7AA"))
            setPadding(dip(10), dip(8), dip(10), dip(8))
        }
        tipBox.addView(TextView(ctx).apply {
            text = "💡 建议：增加白名单限制，确保只有上述新建账户允许访问 EMQX"
            textSize = 12f; setTextColor(Color.parseColor("#B45309")); setTypeface(null, Typeface.BOLD)
        })
        tipBox.addView(TextView(ctx).apply {
            text = "访问控制 → 客户端授权 → 全部用户，添加："
            textSize = 11.5f; setTextColor(cSub); setPadding(0, dip(4), 0, dip(2))
        })
        val denyRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply { setColor(Color.parseColor("#FEF2F2")); cornerRadius = dip(6).toFloat() }
            setPadding(dip(8), dip(4), dip(8), dip(4)); gravity = android.view.Gravity.CENTER_VERTICAL
        }
        listOf(
            codeLabel("#") to 1f,
            codeLabel("发布&订阅") to 1f,
            (TextView(ctx).apply { text = " 不允许"; textSize = 12.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#B91C1C")) }) to 1f
        ).forEach { (v, w) ->
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w)
            v.layoutParams = lp
            denyRow.addView(v)
        }
        tipBox.addView(denyRow)
        tipBox.addView(TextView(ctx).apply {
            text = "注：用户名留空即可（兜底拒绝所有未授权账户）"
            textSize = 11f; setTextColor(cSub); setPadding(0, dip(4), 0, 0)
        })
        step3.addView(tipBox)
        container.addView(step3)

        // ── 步骤④ 回 App 填写 ──
        container.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dip(10)) })
        val step4 = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; background = card(cSurface); setPadding(dip(16), dip(12), dip(16), dip(12))
        }
        step4.addView(stepBadge("④ 回 App 填写并绑定"))
        listOf(
            "MQTT 服务器：填地址:8883（如 xxxx.emqxsl.com:8883）",
            "被控端账号/密码：填第②步的 DEV 账户",
            "控制端凭证(ctl)：改为第②步的 CTL 账户（需与 EMQX 一致）",
            "点「生成绑定二维码」→ 控制端 App 扫码 → 完成配对"
        ).forEach { step4.addView(bullet(it)) }
        container.addView(step4)

        // ── 提示 ──
        container.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dip(10)) })
        val tipCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = card(cTipBg, cTipBorder); setPadding(dip(14), dip(12), dip(14), dip(12))
        }
        tipCard.addView(TextView(ctx).apply {
            text = android.text.SpannableStringBuilder("【提示】增加被控端").apply {
                setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(android.text.style.ForegroundColorSpan(cTipAccent), 0, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            textSize = 13f
        })
        tipCard.addView(TextView(ctx).apply {
            text = "换一个不重复的设备ID，在 EMQX 中重复 ②③④ 即可，无需新建部署。"
            textSize = 13f; setTextColor(cSub); setLineSpacing(dip(3).toFloat(), 1f); setPadding(0, dip(6), 0, 0)
        })
        container.addView(tipCard)

        val dialogRoot = ScrollView(ctx).apply {
                addView(container)
                setPadding(dip(20), dip(8), dip(20), dip(8))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (resources.displayMetrics.heightPixels * 0.7).toInt()
                )
            }
            UnifiedDialogKit.showForm(
                ctx,
                dialogRoot,
                title = "MQTT 配置引导",
                positiveText = "关闭",
                negativeText = "跳转 EMQX",
                onCancel = {
                    openUrl("https://www.emqx.com/zh/free-trial")
                    onOk?.invoke()
                    true
                },
                onConfirm = {
                    onOk?.invoke()
                    true
                }
            )
        }

    /** 获取控制端下载地址弹窗 */
    private fun showControllerDownload() {
        val url = BuildConfig.CTRL_DOWNLOAD_URL.trim()
        if (url.isEmpty()) {
            UnifiedDialogKit.showInfo(
                ctx,
                "获取控制端 DailyController",
                "当前未配置控制端下载地址。\n\n控制端安装包由分发方通过构建参数注入。如需安装控制端，请向提供者获取安装方式，或等待后续版本开放下载入口。"
            )
        } else {
            val summary = StringBuilder()
            val container = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(40, 16, 40, 8)
            }
            val item = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 12, 24, 12)
                isClickable = true
                setOnClickListener { openUrl(url) }
            }
            item.addView(TextView(ctx).apply {
                text = "控制端下载"
                textSize = 17f
                setTypeface(null, Typeface.BOLD)
                setTextColor(resources.getColor(R.color.text_default_color, theme))
            })
            item.addView(TextView(ctx).apply {
                text = "点击打开下载页，或复制地址"
                textSize = 13f
                setTextColor(resources.getColor(R.color.text_hint_color, theme))
                setPadding(0, 4, 0, 0)
            })
            item.addView(TextView(ctx).apply {
                text = url
                textSize = 13f
                setTextColor(resources.getColor(R.color.md_primary, theme))
                setPadding(0, 4, 0, 0)
            })
            container.addView(item)
            summary.append("控制端下载：$url\n")
            UnifiedDialogKit.showForm(
                ctx,
                container,
                title = "获取控制端 DailyController",
                positiveText = "复制全部",
                negativeText = "关闭",
                onConfirm = {
                    copyToClipboard(summary.toString().trimEnd(), "控制端下载地址")
                    "已复制全部下载地址到剪贴板".show(ctx)
                    true
                }
            )
        }
    }

    /** 二维码生成后更新重扫提示横幅 */
    private fun updateRescanBanner() {
        val connected = MqttAgentService.isConnected()
        val bound = MqttAgentService.isBound()
        val pending = SaveKeyValues.loadBoolean("pending_rescan", false)
        val (show, text) = when {
            pending -> true to "设备ID已变更，请重新生成二维码绑定"
            !connected || bound -> false to ""
            else -> true to "未绑定：点「生成绑定二维码」，用控制端扫码完成配对"
        }
        binding.rescanText.text = text
        binding.rescanBanner.visibility = if (show) View.VISIBLE else View.GONE
    }
}
