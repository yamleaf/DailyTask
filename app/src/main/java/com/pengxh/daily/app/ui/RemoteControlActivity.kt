package com.pengxh.daily.app.ui

import android.content.ClipboardManager
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.WriterException
import com.pengxh.daily.app.R
import com.pengxh.daily.app.databinding.ActivityRemoteControlBinding
import com.pengxh.daily.app.service.MqttAgentService
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.MqttSecureConfig
import com.pengxh.daily.app.utils.ServerlessApiSecureConfig
import com.yample.mqttprotocol.MqttQuota
import com.yample.mqttprotocol.BindingPayload
import com.yample.mqttprotocol.Protocol
import com.pengxh.kt.lite.base.KotlinBaseActivity
import com.pengxh.kt.lite.extensions.show
import com.pengxh.kt.lite.utils.SaveKeyValues
import com.pengxh.kt.lite.widget.dialog.AlertInputDialog
import java.security.SecureRandom
import java.util.UUID
import com.pengxh.daily.app.BuildConfig

class RemoteControlActivity : KotlinBaseActivity<ActivityRemoteControlBinding>() {

    private val gson = Gson()

    /** 最近一次连接成功的时间戳（用于 Hero 卡「最后心跳」展示） */
    private var lastConnectedMs = 0L

    /** 最近一次到 broker 的 RTT（ms），由 MqttAgentService.measureRtt 测量，用于连接质量展示 */
    private var lastRttMs = -1L

    override fun initViewBinding(): ActivityRemoteControlBinding =
        ActivityRemoteControlBinding.inflate(layoutInflater)

    override fun setupTopBarLayout() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { view, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.setPadding(0, statusBarHeight, 0, 0)
            insets
        }
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    override fun initOnCreate(savedInstanceState: Bundle?) {
        // 1) 设备身份 / 控制端凭证缺失则生成（仅一次，持久化，解绑不清）
        ensureDeviceIdentity()

        // 2) 回显配置
        reloadSettingsUI()

        // 3) 选项行交互（任务配置同款范式）
        binding.publicMqttRow.setOnClickListener { showPublicMqttDialog() }
        binding.guideRow.setOnClickListener { showConfigGuide() }
        binding.brokerRow.setOnClickListener {
            editTextRow("MQTT 服务器", Constant.MQTT_BROKER_KEY, binding.brokerValue)
        }
        binding.userRow.setOnClickListener {
            editTextRow("被控端用户名", Constant.MQTT_USER_KEY, binding.userValue)
        }
        binding.passRow.setOnClickListener {
            editTextRow("被控端密码", Constant.MQTT_PASS_KEY, binding.passValue, isPassword = true)
        }
        binding.deviceIdRow.setOnClickListener { editDeviceId() }
        binding.ctlRow.setOnClickListener { showCtlEditDialog() }

        binding.apiUrlRow.setOnClickListener {
            editTextRow("API 地址", Constant.MQTT_SERVERLESS_API_URL_KEY, binding.apiUrlValue)
        }
        binding.apiAppIdRow.setOnClickListener {
            editTextRow("AppID", Constant.MQTT_SERVERLESS_API_APP_ID_KEY, binding.apiAppIdValue)
        }
        binding.apiAppSecretRow.setOnClickListener {
            editTextRow("AppSecret", Constant.MQTT_SERVERLESS_API_APP_SECRET_KEY, binding.apiAppSecretValue, isPassword = true)
        }
        binding.apiTestRow.setOnClickListener { testApiConnection() }
        binding.apiClientsRow.setOnClickListener { showApiClients() }

        binding.qrRow.setOnClickListener { generateAndShowQR() }
        binding.unbindRow.setOnClickListener { forceUnbind() }
        // 关于：控制端下载地址（默认 GitHub Releases 发布页，可由 CI 环境变量 CTRL_DOWNLOAD_URL 覆盖；未配置时显示友好提示）
        binding.downloadRow.setOnClickListener { showControllerDownload() }
        // 预热配置引导 WebView，使首次打开几乎无感
        prewarmGuideWebView()
        binding.btnGoQr.setOnClickListener { generateAndShowQR() }
        binding.btnRetryNow.setOnClickListener { MqttAgentService.reconnectNow() }
        binding.mqttSwitch.setOnCheckedChangeListener { _, isChecked -> onMqttSwitchChanged(isChecked) }

        // C1：MQTT 额度默认折叠
        val quotaCollapsed = SaveKeyValues.loadBoolean("collapse_quota", true)
        applyCollapse(binding.bodyQuota, binding.ivChevronQuota, !quotaCollapsed)
        binding.btnToggleQuota.setOnClickListener { toggleSection(binding.bodyQuota, binding.ivChevronQuota, "collapse_quota") }

        // 4) 连接 / 绑定状态回调 + 启动 MQTT 代理服务
        registerListeners()
        startMqttService()
    }

    override fun onResume() {
        super.onResume()
        // 服务可能被系统回收，重新进入页面时兜底拉起（只要开关和配置都有效）
        startMqttService()
        registerListeners()
        binding.mqttSwitch.isChecked = SaveKeyValues.loadBoolean(Constant.MQTT_ENABLED_KEY, false)
        updateStatusUI(MqttAgentService.isConnected())
        updateBindingUI(MqttAgentService.isBound())
        updateRescanBanner()
        updateHeroUI()
        updateQuotaUI()
        // 页面可见时主动测一次 RTT，使「连接质量」展示与控制端一致的「优 · 100ms」口径
        MqttAgentService.measureRtt { rtt ->
            lastRttMs = rtt
            runOnUiThread { updateQuotaUI() }
        }
        // 已连上但还没绑定：给出下一步指引，避免用户茫然停在「未绑定」
        if (MqttAgentService.isConnected() && !MqttAgentService.isBound()) {
            binding.root.postDelayed({
                if (MqttAgentService.isConnected() && !MqttAgentService.isBound()) {
                    "已连接但未绑定：请点「生成绑定二维码」，再用控制端 App 扫码完成配对".show(this)
                }
            }, 800)
        }
    }

    override fun onPause() {
        super.onPause()
        binding.root.removeCallbacks(retryRunnable)
        if (MqttAgentService.isRunning()) {
            MqttAgentService.stateListener = null
            MqttAgentService.bindingStateListener = null
        }
    }

    private fun registerListeners() {
        MqttAgentService.stateListener = { connected ->
            runOnUiThread {
                if (connected) lastConnectedMs = System.currentTimeMillis()
                updateStatusUI(connected)
                updateHeroUI()
            }
        }
        MqttAgentService.bindingStateListener = { bound ->
            runOnUiThread { updateBindingUI(bound) }
        }
        // 立即把服务当前的连接/绑定状态推给 UI，避免 Activity 错过实时回调（#2 连接态、#3 绑定态刷新）
        MqttAgentService.notifyState()
    }

    /** 生成设备ID（8位）与控制端 CTL 凭证（固定，持久化；解绑不清） */
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
            SaveKeyValues.saveString(
                Constant.MQTT_CTL_PASS_KEY,
                bytes.joinToString("") { "%02x".format(it) }
            )
        }
    }

    private fun reloadSettingsUI() {
        binding.brokerValue.text =
            SaveKeyValues.loadString(Constant.MQTT_BROKER_KEY, "").ifBlank { "未设置" }
        binding.userValue.text =
            SaveKeyValues.loadString(Constant.MQTT_USER_KEY, "").ifBlank { "未设置" }
        binding.passValue.text =
            if (MqttSecureConfig.loadPass().isBlank()) "未设置" else "已设置"
        binding.deviceIdValue.text =
            SaveKeyValues.loadString(Constant.DEVICE_ID_KEY, "").ifBlank { "未生成" }
        val ctlUser = SaveKeyValues.loadString(Constant.MQTT_CTL_USER_KEY, "")
        binding.ctlValue.text = if (ctlUser.isBlank()) "未生成" else ctlUser
        binding.apiUrlValue.text =
            SaveKeyValues.loadString(Constant.MQTT_SERVERLESS_API_URL_KEY, "").ifBlank { "未设置" }
        binding.apiAppIdValue.text =
            SaveKeyValues.loadString(Constant.MQTT_SERVERLESS_API_APP_ID_KEY, "").ifBlank { "未设置" }
        binding.apiAppSecretValue.text =
            if (ServerlessApiSecureConfig.loadSecret().isBlank()) "未设置" else "已设置"
        binding.mqttSwitch.isChecked = SaveKeyValues.loadBoolean(Constant.MQTT_ENABLED_KEY, false)
        updateStatusUI(MqttAgentService.isConnected())
        updateBindingUI(MqttAgentService.isBound())
        updateHeroUI()
    }

    /**
     * 需求 4：原「MQTT 状态」更名为「连接信息」，内容与控制端「连接信息」卡片保持一致：
     * 上半部分为连接标识（Broker / 设备ID / 客户端ID / 配对状态 / 最近连接），下半部分为消息统计。
     */
    private fun updateQuotaUI() {
        val stats = MqttQuota.get(this)
        binding.quotaValue.text = "已用 ${stats.total}"
        binding.quotaProgress.visibility = View.GONE

        binding.quotaDetailsLayout.removeAllViews()

        val deviceId = SaveKeyValues.loadString(Constant.DEVICE_ID_KEY, "")
        val broker = SaveKeyValues.loadString(Constant.MQTT_BROKER_KEY, "").ifBlank { "未设置" }
        val lastConn = if (lastConnectedMs > 0) {
            java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.CHINA)
                .format(java.util.Date(lastConnectedMs))
        } else "—"

        listOf(
            "连接质量" to computeConnQuality(),
            "Broker" to broker,
            "设备ID" to deviceId.ifBlank { "—" },
            "客户端ID" to if (deviceId.isBlank()) "—" else "dev-$deviceId",
            "配对状态" to if (MqttAgentService.isBound()) "已配对" else "未配对",
            "最近连接" to lastConn
        ).forEach { (label, value) -> addQuotaRow(label, value) }

        addQuotaSubHeader("消息统计")

        listOf(
            "已累计连接" to MqttQuota.formatDuration(stats.totalConnectedMs),
            "本次连接" to MqttQuota.formatDuration(stats.sessionConnectedMs),
            "已发送消息" to "${stats.sent} 条",
            "已接收消息" to "${stats.received} 条",
            "消息总计" to "${stats.total} 条"
        ).forEach { (label, value) -> addQuotaRow(label, value) }
    }

    /**
     * 连接质量：由连接状态 + 最近心跳新鲜度推导的定性评估（不做端到端 RTT 探测，避免额外网络开销）。
     * 断线=未连接；在线且心跳很新=优；随心跳变旧逐级降为 良/一般/弱。
     */
    private fun computeConnQuality(): String {
        if (!MqttAgentService.isConnected()) return "未连接"
        // 优先用实测 RTT（与控制端「优 · 100ms」口径一致）；无实测值时回退到心跳新鲜度
        if (lastRttMs >= 0) {
            return when {
                lastRttMs < 300 -> "优 · ${lastRttMs}ms"
                lastRttMs < 800 -> "良 · ${lastRttMs}ms"
                lastRttMs < 2000 -> "一般 · ${lastRttMs}ms"
                else -> "弱 · ${lastRttMs}ms"
            }
        }
        if (lastConnectedMs <= 0L) return "已连接"
        val sec = (System.currentTimeMillis() - lastConnectedMs) / 1000
        return when {
            sec < 30 -> "优"
            sec < 120 -> "良"
            sec < 300 -> "一般"
            else -> "弱"
        }
    }

    private fun addQuotaRow(label: String, value: String) {
        val pad = resources.getDimensionPixelSize(R.dimen.dp_4)
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(0, pad, 0, pad)
        }
        val labelTv = TextView(this).apply {
            text = label
            setTextColor(resources.getColor(R.color.text_default_color, theme))
            textSize = 13f
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        val valueTv = TextView(this).apply {
            text = value
            setTextColor(resources.getColor(R.color.text_hint_color, theme))
            textSize = 13f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        row.addView(labelTv)
        row.addView(valueTv)
        binding.quotaDetailsLayout.addView(row)
    }

    private fun addQuotaSubHeader(title: String) {
        val divider = View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                resources.getDimensionPixelSize(R.dimen.dividerLine)
            ).apply { topMargin = resources.getDimensionPixelSize(R.dimen.dp_8) }
            setBackgroundColor(resources.getColor(R.color.outline_variant, theme))
        }
        val header = TextView(this).apply {
            text = title
            setTextColor(resources.getColor(R.color.text_default_color, theme))
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = resources.getDimensionPixelSize(R.dimen.dp_8) }
        }
        binding.quotaDetailsLayout.addView(divider)
        binding.quotaDetailsLayout.addView(header)
    }

    private fun updateStatusUI(connected: Boolean) {
        binding.statusValue.text = if (connected) "已连接" else "未连接"
        val colorRes = if (connected) R.color.ios_green else R.color.red
        val dotRes = if (connected) R.drawable.bg_status_dot_online else R.drawable.bg_status_dot_offline
        binding.statusValue.setTextColor(resources.getColor(colorRes, theme))
        binding.statusValue.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, dotRes, 0)
        binding.statusValue.compoundDrawablePadding = resources.getDimensionPixelSize(R.dimen.dp_8)
        // 连接状态变化后，绑定提示也需联动刷新（如：已连接却仍未绑定时给出下一步指引）
        updateBindingUI(MqttAgentService.isBound())
    }

    /** 状态 Hero 卡（C4）：在线/离线点 + 文案 + 最后心跳 + 关闭零耗电说明 */
    private fun updateHeroUI() {
        val enabled = SaveKeyValues.loadBoolean(Constant.MQTT_ENABLED_KEY, false)
        val connected = MqttAgentService.isConnected()
        // B2：每秒刷新倒计时（断线时）
        binding.root.removeCallbacks(retryRunnable)
        val showRetry = enabled && !connected
        val remainSec = ((MqttAgentService.nextReconnectAtMs - System.currentTimeMillis()) / 1000).toInt().coerceAtLeast(0)
        val (dotRes, statusText, beatText) = when {
            !enabled -> {
                Triple(R.drawable.bg_dot_offline, "远程控制已关闭", "—")
            }
            connected -> {
                Triple(R.drawable.bg_dot_online, "远程控制已连接", formatHeartbeat())
            }
            else -> {
                val txt = if (remainSec > 0) "连接中 · ${remainSec}s 后重试" else "连接中…"
                Triple(R.drawable.bg_dot_offline, txt, "—")
            }
        }
        if (showRetry) {
            binding.retryHint.text = if (remainSec > 0) "断线，约 ${remainSec} 秒后自动重连" else "正在尝试重连…"
            binding.root.postDelayed(retryRunnable, 1000)
        }
        binding.retryRow.visibility = if (showRetry) View.VISIBLE else View.GONE
        binding.heroDot.setBackgroundResource(dotRes)
        binding.heroStatus.text = statusText
        binding.heroHeartbeat.text = beatText
        applyRemoteDisabled(enabled)
    }

    /** B2：断线倒计时刷新 Runnable（每秒重算 Hero 文案） */
    private val retryRunnable = Runnable { updateHeroUI() }

    /** 最后心跳：相对当前时间的可读文本 */
    private fun formatHeartbeat(): String {
        if (lastConnectedMs <= 0L) return "—"
        val sec = (System.currentTimeMillis() - lastConnectedMs) / 1000
        return when {
            sec < 60 -> "${sec}秒前"
            sec < 3600 -> "${sec / 60}分钟前"
            else -> "${sec / 3600}小时前"
        }
    }

    /** 主开关关闭时，把依赖 MQTT 的配置/身份/绑定行整体调暗（保留可点击，方便先配置再开启） */
    private fun applyRemoteDisabled(enabled: Boolean) {
        val alpha = if (enabled) 1f else 0.45f
        listOf(
            binding.guideRow, binding.brokerRow, binding.userRow, binding.passRow,
            binding.apiUrlRow, binding.apiAppIdRow, binding.apiAppSecretRow, binding.apiTestRow,
            binding.deviceIdRow, binding.ctlRow, binding.qrRow, binding.unbindRow
        ).forEach { it.alpha = alpha }
    }

    /** C1：折叠卡辅助 */
    private fun applyCollapse(body: View, chevron: View, expanded: Boolean) {
        body.visibility = if (expanded) View.VISIBLE else View.GONE
        chevron.rotation = if (expanded) 0f else 90f
    }

    private fun toggleSection(body: View, chevron: View, key: String) {
        val expanded = body.visibility != View.VISIBLE
        applyCollapse(body, chevron, expanded)
        SaveKeyValues.saveBoolean(key, !expanded)
    }

    private fun updateBindingUI(bound: Boolean) {
        val connected = MqttAgentService.isConnected()
        val reason = MqttAgentService.lastUnbindReason
        binding.bindingValue.text = when {
            bound -> "已绑定（可远程控制）"
            reason == "force" -> "已解绑（本机已强制解绑）"
            reason == "remote" -> "已解绑（控制端已移除本设备）"
            connected -> "未绑定（请生成二维码→控制端扫码）"
            else -> "未绑定"
        }
        val colorRes = if (bound) R.color.ios_green else R.color.red
        val dotRes = if (bound) R.drawable.bg_status_dot_online else R.drawable.bg_status_dot_offline
        binding.bindingValue.setTextColor(resources.getColor(colorRes, theme))
        binding.bindingValue.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, dotRes, 0)
        binding.bindingValue.compoundDrawablePadding = resources.getDimensionPixelSize(R.dimen.dp_8)
        // C2：绑定态变化后联动刷新「需重新扫码」提示条
        updateRescanBanner()
    }

    /** C2：未绑定 / 设备ID变更时，页顶常驻提示条引导重新扫码（替代一次性 Toast） */
    private fun updateRescanBanner() {
        val connected = MqttAgentService.isConnected()
        val bound = MqttAgentService.isBound()
        val pendingRescan = SaveKeyValues.loadBoolean("pending_rescan", false)
        val (show, text) = when {
            pendingRescan -> true to "设备ID已变更，请重新生成二维码绑定"
            connected && !bound -> true to "未绑定：点「生成绑定二维码」，用控制端扫码完成配对"
            else -> false to ""
        }
        binding.rescanText.text = text
        binding.rescanBanner.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun editTextRow(title: String, key: String, valueView: TextView, isPassword: Boolean = false) {
        val current = SaveKeyValues.loadString(key, "")
        AlertInputDialog.Builder()
            .setContext(this)
            .setTitle(title)
            .setHintMessage(
                if (current.isBlank()) "请输入$title"
                else "当前：${if (isPassword) "已设置" else current}"
            )
            .setPositiveButton("保存")
            .setNegativeButton("取消")
            .setOnDialogButtonClickListener(object : AlertInputDialog.OnDialogButtonClickListener {
                override fun onConfirmClick(value: String) {
                    val saved = if (key == Constant.MQTT_BROKER_KEY) normalizeBrokerHost(value) else value
                    when (key) {
                        Constant.MQTT_PASS_KEY -> MqttSecureConfig.savePass(saved) // 加密存储并清除明文残留
                        Constant.MQTT_SERVERLESS_API_APP_SECRET_KEY -> ServerlessApiSecureConfig.saveSecret(saved)
                        else -> SaveKeyValues.saveString(key, saved)
                    }
                    valueView.text = if (isPassword) "已设置" else saved.ifBlank { "未设置" }
                    "已保存".show(this@RemoteControlActivity)
                    if (key == Constant.MQTT_BROKER_KEY) restartMqttService()
                }

                override fun onCancelClick() {}
            })
            .build().show()
    }

    /** 测试 EMQX Serverless API 连接：调用 GET /clients 验证 AppID/AppSecret 与 API 地址 */
    /** 规范化 MQTT 服务器地址：若只填了主机名未带端口，自动补全 :1883（仅当无 scheme 且无冒号时） */
    /**
     * 需求 6：Broker 地址只填域名/IP 时自动补默认端口。
     * - `broker.emqx.io`            → `broker.emqx.io:1883`
     * - `tcp://broker.emqx.io`      → `tcp://broker.emqx.io:1883`
     * - `ssl://broker.emqx.io`      → `ssl://broker.emqx.io:8883`（TLS 默认端口）
     * - 已带端口 / IPv6 字面量（含 `[]`）保持原样
     */
    private fun normalizeBrokerHost(raw: String): String {
        var v = raw.trim().trimEnd('/')
        if (v.isEmpty()) return v
        val schemeIdx = v.indexOf("://")
        val scheme = if (schemeIdx > 0) v.substring(0, schemeIdx + 3) else ""
        val hostPart = if (schemeIdx > 0) v.substring(schemeIdx + 3) else v
        if (hostPart.isBlank()) return v
        // IPv6 字面量必须写成 [::1]:1883，这里不做猜测
        if (hostPart.startsWith("[")) return v
        if (hostPart.contains(":")) return v
        val defaultPort = if (scheme.startsWith("ssl") || scheme.startsWith("wss") || scheme.startsWith("mqtts")) 8883 else 1883
        v = "$scheme$hostPart:$defaultPort"
        return v
    }

    private fun testApiConnection() {
        val baseUrl = SaveKeyValues.loadString(Constant.MQTT_SERVERLESS_API_URL_KEY, "").trim()
        val appId = SaveKeyValues.loadString(Constant.MQTT_SERVERLESS_API_APP_ID_KEY, "").trim()
        val appSecret = ServerlessApiSecureConfig.loadSecret().trim()

        if (baseUrl.isBlank() || appId.isBlank() || appSecret.isBlank()) {
            "请先填写 API 地址、AppID 和 AppSecret".show(this)
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val client = OkHttpClient()
                val normalized = baseUrl.removeSuffix("/")
                val request = Request.Builder()
                    .url("$normalized/clients")
                    .header("Authorization", Credentials.basic(appId, appSecret))
                    .header("Accept", "application/json")
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    val body = response.body.string()
                    when (response.code) {
                        200 -> {
                            val count = try {
                                JSONObject(body).optJSONArray("data")?.length() ?: -1
                            } catch (_: Exception) {
                                -1
                            }
                            "API 连接成功（在线客户端 $count 个）"
                        }
                        401 -> "API 鉴权失败：请检查 AppID / AppSecret 是否正确"
                        else -> "API 请求失败：HTTP ${response.code} ${response.message}"
                    }
                }
            }.getOrElse { "API 连接异常：${it.message}" }

            withContext(Dispatchers.Main) { result.show(this@RemoteControlActivity) }
        }
    }

    /** 读取 Serverless API 配置；缺失则提示并返回 null */
    private fun loadApiConfig(): Pair<String, String>? {
        val baseUrl = SaveKeyValues.loadString(Constant.MQTT_SERVERLESS_API_URL_KEY, "").trim()
        val appId = SaveKeyValues.loadString(Constant.MQTT_SERVERLESS_API_APP_ID_KEY, "").trim()
        val appSecret = ServerlessApiSecureConfig.loadSecret().trim()
        if (baseUrl.isBlank() || appId.isBlank() || appSecret.isBlank()) {
            "请先填写 API 地址、AppID 和 AppSecret".show(this)
            return null
        }
        return baseUrl.removeSuffix("/") to Credentials.basic(appId, appSecret)
    }

    /** 控制端下载地址：由构建注入的单一地址（默认 GitHub Releases 发布页）；未配置时显示友好提示 */
    private fun showControllerDownload() {
        val downloadUrl = BuildConfig.CTRL_DOWNLOAD_URL.trim()
        if (downloadUrl.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("获取控制端 DailyController")
                .setMessage("当前未配置控制端下载地址。\n\n控制端安装包由分发方通过构建参数注入。如需安装控制端，请向提供者获取安装方式，或等待后续版本开放下载入口。")
                .setPositiveButton("知道了", null)
                .show()
            return
        }
        val items = listOf(
            Triple("控制端下载", downloadUrl, "点击打开下载页，或复制地址")
        )
        val sb = StringBuilder()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 16, 40, 8)
        }
        val outValue = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        items.forEach { (label, url, desc) ->
            sb.append("$label：$url\n")
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 12, 24, 12)
                setBackgroundResource(outValue.resourceId)
                setOnClickListener { openUrl(url) }
            }
            row.addView(TextView(this).apply {
                text = label
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(resources.getColor(R.color.text_default_color, theme))
            })
            row.addView(TextView(this).apply {
                text = desc
                textSize = 12f
                setTextColor(resources.getColor(R.color.text_hint_color, theme))
                setPadding(0, 4, 0, 0)
            })
            row.addView(TextView(this).apply {
                text = url
                textSize = 12f
                setTextColor(resources.getColor(R.color.md_primary, theme))
                setPadding(0, 4, 0, 0)
            })
            container.addView(row)
        }
        AlertDialog.Builder(this)
            .setTitle("获取控制端 DailyController")
            .setView(container)
            .setPositiveButton("复制全部") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("控制端下载地址", sb.toString().trimEnd()))
                "已复制全部下载地址到剪贴板".show(this)
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            url.show(this)
        }
    }

    /** 在线客户端管理：调用 GET /clients 列出当前在线客户端，可强制下线（DELETE /clients/{clientid}） */
    private fun showApiClients() {
        val config = loadApiConfig() ?: return
        val (normalized, auth) = config
        val dialog = AlertDialog.Builder(this)
            .setTitle("在线客户端")
            .setMessage("正在查询…")
            .setPositiveButton("刷新", null)
            .setNegativeButton("关闭", null)
            .create()
        dialog.show()

        fun render(status: String, clients: List<Pair<String, String>>) {
            val container = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(24, 12, 24, 12)
            }
            container.addView(TextView(this).apply {
                text = status
                setTextColor(resources.getColor(R.color.text_hint_color, theme))
                textSize = 13f
                setPadding(0, 0, 0, 8)
            })
            if (clients.isEmpty()) {
                container.addView(TextView(this).apply {
                    text = if (status.contains("无在线客户端")) "当前没有在线客户端" else "无客户端信息"
                    setTextColor(resources.getColor(R.color.text_hint_color, theme))
                    textSize = 14f
                    setPadding(0, 8, 0, 0)
                })
            }
            clients.forEach { (clientId, username) ->
                val row = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(0, 6, 0, 6)
                }
                val info = TextView(this).apply {
                    text = buildString {
                        append(clientId)
                        if (username.isNotBlank()) append("\n$username")
                    }
                    setTextColor(resources.getColor(R.color.text_default_color, theme))
                    textSize = 13f
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val kick = com.google.android.material.button.MaterialButton(this).apply {
                    text = "下线"
                    isAllCaps = false
                    insetTop = 0
                    insetBottom = 0
                    textSize = 12f
                    setOnClickListener { kickApiClient(normalized, auth, clientId) }
                }
                row.addView(info)
                row.addView(kick)
                container.addView(row)
            }
            dialog.setView(container)
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                dialog.setMessage("正在查询…")
                fetchApiClients(normalized, auth, dialog)
            }
        }

        fetchApiClients(normalized, auth, dialog, ::render)
    }

    private fun fetchApiClients(
        normalized: String,
        auth: String,
        dialog: AlertDialog,
        onResult: (String, List<Pair<String, String>>) -> Unit = { _, _ -> }
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder()
                    .url("$normalized/clients?limit=100")
                    .header("Authorization", auth)
                    .header("Accept", "application/json")
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    val body = response.body.string()
                    when (response.code) {
                        200 -> {
                            val arr = try {
                                JSONObject(body).optJSONArray("data")
                            } catch (_: Exception) {
                                null
                            }
                            val list = mutableListOf<Pair<String, String>>()
                            if (arr != null) {
                                for (i in 0 until arr.length()) {
                                    val item = arr.optJSONObject(i) ?: continue
                                    val cid = item.optString("clientid", "")
                                    if (cid.isNotBlank()) list.add(cid to item.optString("username", ""))
                                }
                            }
                            if (list.isEmpty()) "无在线客户端" to list else "共 ${list.size} 个在线客户端" to list
                        }
                        401 -> "API 鉴权失败：请检查 AppID / AppSecret 是否正确" to emptyList<Pair<String, String>>()
                        else -> "API 请求失败：HTTP ${response.code}" to emptyList<Pair<String, String>>()
                    }
                }
            }.getOrElse { "API 连接异常：${it.message}" to emptyList<Pair<String, String>>() }

            withContext(Dispatchers.Main) {
                onResult(result.first, result.second)
            }
        }
    }

    /** 强制下线指定客户端：DELETE /clients/{clientid} */
    private fun kickApiClient(normalized: String, auth: String, clientId: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder()
                    .url("$normalized/clients/${Uri.encode(clientId)}")
                    .header("Authorization", auth)
                    .header("Accept", "application/json")
                    .delete()
                    .build()
                client.newCall(request).execute().use { response ->
                    when (response.code) {
                        200, 204 -> "已下线：$clientId"
                        401 -> "API 鉴权失败：请检查 AppID / AppSecret 是否正确"
                        else -> "下线失败：HTTP ${response.code}"
                    }
                }
            }.getOrElse { "下线异常：${it.message}" }

            withContext(Dispatchers.Main) { result.show(this@RemoteControlActivity) }
        }
    }

    /** MQTT 账户是否完整（broker + 被控端用户名 + 被控端密码 均非空） */
    private fun isMqttConfigValid(): Boolean =
        SaveKeyValues.loadString(Constant.MQTT_BROKER_KEY, "").isNotBlank() &&
            SaveKeyValues.loadString(Constant.MQTT_USER_KEY, "").isNotBlank() &&
            MqttSecureConfig.loadPass().isNotBlank()

    /** 总开关切换：开启则校验账户并连接，关闭则完全停止服务（断开连接、撤销前台通知） */
    private fun onMqttSwitchChanged(isChecked: Boolean) {
        SaveKeyValues.saveBoolean(Constant.MQTT_ENABLED_KEY, isChecked)
        if (isChecked) {
            if (!isMqttConfigValid()) {
                // 账户未配置完整：回退开关并提示
                binding.mqttSwitch.isChecked = false
                SaveKeyValues.saveBoolean(Constant.MQTT_ENABLED_KEY, false)
                updateHeroUI()
                "请先完成 MQTT 服务器 / 被控端用户名 / 被控端密码 配置".show(this)
                return
            }
            startForegroundService(Intent(this, MqttAgentService::class.java))
            updateHeroUI()
        } else {
            stopService(Intent(this, MqttAgentService::class.java))
            updateStatusUI(false)
            updateHeroUI()
        }
    }

    /** 仅当总开关开启且账户有效时，才拉起 MQTT 前台服务 */
    private fun startMqttService() {
        if (SaveKeyValues.loadBoolean(Constant.MQTT_ENABLED_KEY, false) &&
            isMqttConfigValid() &&
            !MqttAgentService.isRunning()
        ) {
            startForegroundService(Intent(this, MqttAgentService::class.java))
        }
    }

    /** 重启服务：先停，再按开关 + 配置决定要不要重新拉起 */
    private fun restartMqttService() {
        stopService(Intent(this, MqttAgentService::class.java))
        if (SaveKeyValues.loadBoolean(Constant.MQTT_ENABLED_KEY, false) && isMqttConfigValid()) {
            startForegroundService(Intent(this, MqttAgentService::class.java))
        }
    }

    private fun generateAndShowQR() {
        val broker = SaveKeyValues.loadString(Constant.MQTT_BROKER_KEY, "")
        if (broker.isBlank()) {
            "请先设置 MQTT 服务器（可点「MQTT 配置引导」查看获取方式）".show(this)
            return
        }
        if (SaveKeyValues.loadString(Constant.MQTT_USER_KEY, "").isBlank() ||
            MqttSecureConfig.loadPass().isBlank()
        ) {
            "请先填写被控端用户名与密码".show(this)
            return
        }
        // 13：已绑定设备不再生成二维码，避免重复配对
        if (MqttAgentService.isBound()) {
            AlertDialog.Builder(this)
                .setTitle("设备已绑定")
                .setMessage("当前设备已与控制端配对。如需重新绑定，请先「强制解绑」再生成二维码。")
                .setPositiveButton("我知道了", null)
                .setNeutralButton("强制解绑") { _, _ -> forceUnbind() }
                .show()
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
            // 剪贴板配对：生成二维码的同时把同一份配对信息写入剪贴板，
            // 控制端在模拟器等无法扫码的场景可直接「从剪贴板导入」，跳过相机。
            copyToClipboard(payloadJson)
            "配对信息已复制到剪贴板，控制端也可「从剪贴板导入」".show(this)
        } catch (e: WriterException) {
            e.printStackTrace()
            "二维码生成失败".show(this)
        }
    }

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

    /** 二维码弹窗：含扫码码 + 控制端 CTL 凭证（需在 EMQX 建同名受限账户） */
    private fun showQRDialog(bitmap: Bitmap, payloadJson: String) {
        val ctlUser = SaveKeyValues.loadString(Constant.MQTT_CTL_USER_KEY, "")
        val ctlPass = SaveKeyValues.loadString(Constant.MQTT_CTL_PASS_KEY, "")
        val scroll = ScrollView(this).apply { setPadding(40, 24, 40, 24) }
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        val iv = ImageView(this).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
        }
        val note = TextView(this).apply {
            text = "用控制端 App 扫描上方二维码完成绑定。配对令牌 2 分钟内有效，请尽快扫码（有效期内可重复扫码重试）。" +
                "\n\n控制端 CTL 凭证（请在 EMQX 中建同名账户并配置受限 ACL）："
            setTextColor(Color.DKGRAY)
            textSize = 13f
            setPadding(0, 16, 0, 0)
        }
        // D4：CTL 密码默认掩码，防肩窥；提供「显示/隐藏」开关
        val userLine = TextView(this).apply {
            text = "用户名：$ctlUser"
            setTextColor(Color.DKGRAY)
            textSize = 13f
        }
        val passLine = TextView(this).apply {
            text = "密码：••••••••"
            setTextColor(Color.DKGRAY)
            textSize = 13f
        }
        // D4：CTL 密码默认掩码，防肩窥；提供「显示/隐藏」文字按钮（链接风格）
        var masked = true
        val toggle = TextView(this).apply {
            text = "显示"
            setTextColor(ContextCompat.getColor(this@RemoteControlActivity, R.color.theme_color))
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setPadding(20, 0, 0, 0)
            setOnClickListener {
                masked = !masked
                passLine.text = if (masked) "密码：••••••••" else "密码：$ctlPass"
                text = if (masked) "显示" else "隐藏"
            }
        }
        val passRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 0)
            addView(passLine)
            addView(toggle)
        }
        container.addView(iv)
        container.addView(note)
        container.addView(userLine)
        container.addView(passRow)
        scroll.addView(container)
        AlertDialog.Builder(this)
            .setTitle("扫描绑定设备")
            .setView(scroll)
            .setNeutralButton("复制配对信息") { _, _ ->
                copyToClipboard(payloadJson)
                "已复制配对信息到剪贴板".show(this)
            }
            .setPositiveButton("关闭", null)
            .show()
    }

    /** 把文本写入系统剪贴板（剪贴板配对用） */
    private fun copyToClipboard(text: String, label: String = "pairing_payload") {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
    }

    /** 控制端 CTL 凭证：默认由 App 生成（ctl-{设备ID} + 随机密码），此处可改为自定义账户 */
    private fun showCtlEditDialog() {
        val ctlUser = SaveKeyValues.loadString(Constant.MQTT_CTL_USER_KEY, "")
        val ctlPass = SaveKeyValues.loadString(Constant.MQTT_CTL_PASS_KEY, "")
        if (ctlUser.isBlank()) {
            "凭证未生成".show(this)
            return
        }
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        val etUser = android.widget.EditText(this).apply {
            setText(ctlUser)
            hint = "控制端用户名（默认 ctl-{设备ID}）"
        }
        val etPass = android.widget.EditText(this).apply {
            setText(ctlPass)
            hint = "控制端密码（默认随机生成）"
        }
        layout.addView(etUser)
        layout.addView(etPass)
        AlertDialog.Builder(this)
            .setTitle("控制端凭证 (ctl)")
            .setMessage(
                "控制端通过此账户连接 MQTT，仅拥有本设备的受限权限。\n" +
                    "默认由 App 自动生成（ctl-{设备ID} + 随机密码）；你也可以改为自定义账户，" +
                    "但需与 EMQX 中建立的账户名/密码保持一致（含 ACL）。"
            )
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                val u = etUser.text.toString().trim()
                val p = etPass.text.toString().trim()
                if (u.isBlank() || p.isBlank()) {
                    "用户名与密码均不能为空".show(this@RemoteControlActivity)
                    return@setPositiveButton
                }
                SaveKeyValues.saveString(Constant.MQTT_CTL_USER_KEY, u)
                SaveKeyValues.saveString(Constant.MQTT_CTL_PASS_KEY, p)
                binding.ctlValue.text = u
                "已保存控制端凭证".show(this@RemoteControlActivity)
            }
            .setNeutralButton("复制凭证") { _, _ ->
                val u = etUser.text.toString().trim()
                val p = etPass.text.toString().trim()
                if (u.isBlank() || p.isBlank()) {
                    "请先填写凭证再复制".show(this@RemoteControlActivity)
                    return@setNeutralButton
                }
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(
                    android.content.ClipData.newPlainText(
                        "ctl_credential",
                        "控制端用户名：$u\n控制端密码：$p"
                    )
                )
                "已复制控制端凭证，可粘贴到 EMQX".show(this@RemoteControlActivity)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 设备ID：可编辑，默认自动生成；EMQX 的 {username} 与主题前缀 dt/{设备ID}/ 均以此为基准 */
    private fun editDeviceId() {
        val oldId = SaveKeyValues.loadString(Constant.DEVICE_ID_KEY, "")
        AlertInputDialog.Builder()
            .setContext(this)
            .setTitle("设备ID")
            .setHintMessage(
                if (oldId.isBlank()) "请输入设备ID（建议与 EMQX 中规划的一致，如 k20pro）"
                else "当前：$oldId"
            )
            .setPositiveButton("保存")
            .setNegativeButton("取消")
            .setOnDialogButtonClickListener(object : AlertInputDialog.OnDialogButtonClickListener {
                override fun onConfirmClick(value: String) {
                    val newId = value.trim()
                    if (newId.isBlank()) {
                        "设备ID不能为空".show(this@RemoteControlActivity)
                        return
                    }
                    if (newId != oldId) {
                        SaveKeyValues.saveString(Constant.DEVICE_ID_KEY, newId)
                        // 若 CTL 用户名仍是旧设备ID派生的默认名，同步更新以保持 ctl-{设备ID} 一致
                        val oldCtl = SaveKeyValues.loadString(Constant.MQTT_CTL_USER_KEY, "")
                        if (oldId.isNotBlank() && oldCtl == "ctl-$oldId") {
                            SaveKeyValues.saveString(Constant.MQTT_CTL_USER_KEY, "ctl-$newId")
                        }
                        binding.deviceIdValue.text = newId
                        binding.ctlValue.text = SaveKeyValues.loadString(Constant.MQTT_CTL_USER_KEY, "")
                        // 设备ID变更会使旧主题/绑定失效：重置绑定态并重启 MQTT
                        if (MqttAgentService.isBound()) {
                            MqttAgentService.unbind()
                            updateBindingUI(false)
                        }
                        // C2：标记设备ID变更，页顶常驻提示重新扫码
                        SaveKeyValues.saveBoolean("pending_rescan", true)
                        updateRescanBanner()
                        restartMqttService()
                        "已保存设备ID（主题前缀与控制端账户已同步；已绑定的控制端需重新扫码）".show(this@RemoteControlActivity)
                    }
                }

                override fun onCancelClick() {}
            })
            .build().show()
    }

    private fun forceUnbind() {
        AlertDialog.Builder(this)
            .setTitle("强制解绑")
            .setMessage(
                "确定强制解绑？解绑后，已绑定的控制端将无法再查询或控制本设备。\n\n" +
                    "注意：本操作只解除绑定关系，不会清除本机的 MQTT 配置" +
                    "（服务器 / 账号 / 设备ID / 控制端凭证），下次直接生成二维码即可重新绑定，无需重新填写。"
            )
            .setPositiveButton("强制解绑") { _, _ ->
                MqttAgentService.unbind() // 仅清绑定态，保留 MQTT 配置
                reloadSettingsUI()
                "已强制解绑（MQTT 配置已保留）".show(this)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 临时公共 MQTT 一键配置：自动填入 EMQX 官方免费公共 broker（broker.emqx.io:1883），
     * 随机生成被控端账号，并说明其有效期与安全局限，供无自有 EMQX 时临时联调使用。
     */
    private fun showPublicMqttDialog() {
        val ctx = this
        val dip = { px: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, px.toFloat(), resources.displayMetrics).toInt() }
        // 现代化卡片式布局
        val scroll = ScrollView(ctx).apply { setPadding(dip(20), dip(4), dip(20), dip(4)) }
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        // 顶部说明
        val header = TextView(ctx).apply {
            text = "将自动配置官方免费公共 broker"
            setTextColor(Color.parseColor("#1f2329"))
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dip(12))
        }
        // 信息卡片
        fun infoCard(label: String, value: String): LinearLayout {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dip(16), dip(10), dip(16), dip(10))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#f4f6f8"))
                    cornerRadius = dip(12).toFloat()
                }
            }
            val lbl = TextView(ctx).apply {
                text = label
                setTextColor(Color.parseColor("#5b6470"))
                textSize = 12f
            }
            val valTv = TextView(ctx).apply {
                text = value
                setTextColor(Color.parseColor("#1f2329"))
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setPadding(0, dip(4), 0, 0)
            }
            row.addView(lbl); row.addView(valTv)
            return row
        }
        container.addView(header)
        container.addView(infoCard("服务器", "broker.emqx.io:1883"))
        val gap1 = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dip(8)) }
        container.addView(gap1)
        container.addView(infoCard("被控端账号", "随机生成（公共 broker 匿名开放，任意账号均被接受）"))

        // 警告区域
        val gap2 = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dip(12)) }
        container.addView(gap2)
        val warnCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#fff7ed"))
                cornerRadius = dip(12).toFloat()
                setStroke(dip(1), Color.parseColor("#fed7aa"))
            }
            setPadding(dip(14), dip(12), dip(14), dip(12))
        }
        val warnTitle = TextView(ctx).apply {
            text = "⚠ 有效期与局限"
            setTextColor(Color.parseColor("#b45309"))
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
        }
        val warnBody = TextView(ctx).apply {
            text = "• 公共 broker 面向全球开发者开放，仅适合临时测试\n• 消息/会话可能随时被清理，不保证持久保留\n• 账号、主题、设备ID均为全局共享命名空间\n• 本配置只写一次账号，随时可改回自有 EMQX"
            setTextColor(Color.parseColor("#5b6470"))
            textSize = 12.5f
            setLineSpacing(dip(4).toFloat(), 1f)
            setPadding(0, dip(8), 0, 0)
        }
        warnCard.addView(warnTitle); warnCard.addView(warnBody)
        container.addView(warnCard)

        // 长期使用建议：单独成卡，醒目提示自建 EMQX（点击直接跳转配置引导）
        val gap3 = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dip(10)) }
        container.addView(gap3)
        val adviceCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#ecfdf5"))
                cornerRadius = dip(12).toFloat()
                setStroke(dip(1), Color.parseColor("#a7f3d0"))
            }
            setPadding(dip(14), dip(12), dip(14), dip(12))
        }
        val adviceTitle = TextView(ctx).apply {
            text = "💡 长期使用建议"
            setTextColor(Color.parseColor("#047857"))
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
        }
        val adviceBody = TextView(ctx).apply {
            text = "公共 broker 仅适合临时联调。长期使用建议自建 EMQX 服务器：稳定、私密、可控，且不受公共 broker 清理策略影响。"
            setTextColor(Color.parseColor("#065f46"))
            textSize = 12.5f
            setLineSpacing(dip(4).toFloat(), 1f)
            setPadding(0, dip(6), 0, dip(8))
        }
        val adviceBtn = TextView(ctx).apply {
            text = "查看 MQTT 配置引导 →"
            setTextColor(Color.parseColor("#047857"))
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            gravity = android.view.Gravity.END
        }
        adviceBtn.setOnClickListener { showConfigGuide() }
        adviceCard.addView(adviceTitle); adviceCard.addView(adviceBody); adviceCard.addView(adviceBtn)
        container.addView(adviceCard)

        scroll.addView(container)
        AlertDialog.Builder(ctx)
            .setTitle("临时公共 MQTT（测试用）")
            .setView(scroll)
            .setNegativeButton("取消", null)
            // 需求 7：长期使用建议自建，给一个直达「MQTT 配置引导」的入口
            .setNeutralButton("配置引导") { _, _ -> showConfigGuide() }
            .setPositiveButton("一键配置") { _, _ -> applyPublicMqttConfig() }
            .show()
    }

    /** 应用临时公共 MQTT 配置：填 broker + 随机账号密码，重启服务并标记使用来源 */
    private fun applyPublicMqttConfig() {
        val deviceId = SaveKeyValues.loadString(Constant.DEVICE_ID_KEY, "").ifBlank {
            UUID.randomUUID().toString().take(8).also { SaveKeyValues.saveString(Constant.DEVICE_ID_KEY, it) }
        }
        // 公共 broker 匿名开放，账号按设备ID派生，保证每台被控端 DEV/CTL 账户与主题均唯一、互不冲突
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        val randomUser = "dev-$deviceId"
        SaveKeyValues.saveString(Constant.MQTT_BROKER_KEY, Constant.PUBLIC_MQTT_BROKER)
        SaveKeyValues.saveString(Constant.MQTT_USER_KEY, randomUser)
        MqttSecureConfig.savePass(bytes.joinToString("") { "%02x".format(it) })
        SaveKeyValues.saveBoolean(Constant.MQTT_USE_PUBLIC_KEY, true)
        // 设备ID若已默认生成则沿用；仅当用户从未生成过才顺手创建（避免改绑）
        if (SaveKeyValues.loadString(Constant.DEVICE_ID_KEY, "").isBlank()) {
            SaveKeyValues.saveString(Constant.DEVICE_ID_KEY, deviceId)
        }
        reloadSettingsUI()
        restartMqttService()
        "已配置临时公共 MQTT，正在连接…（请及时生成二维码完成绑定）".show(this)
    }

    /** MQTT 配置引导：复用单个 WebView 实例（避免每次打开都重新初始化 Chromium，解决「打开太卡」） */
    private var guideWebView: WebView? = null
    private var guideLoaded = false

    /** 懒创建并复用 WebView；首次从 assets 加载一次 HTML，之后复用已渲染内容，秒开 */
    private fun ensureGuideWebView(): WebView {
        if (guideWebView == null) {
            guideWebView = WebView(this).apply {
                settings.javaScriptEnabled = false
                settings.defaultTextEncodingName = "utf-8"
                settings.domStorageEnabled = true
                settings.loadsImagesAutomatically = false
                settings.blockNetworkImage = true
                settings.cacheMode = android.webkit.WebSettings.LOAD_CACHE_ELSE_NETWORK
            }
        }
        val wv = guideWebView!!
        if (!guideLoaded) {
            // 从 assets 文件加载（比 loadData 解析内联字符串更快，且可走磁盘缓存）
            wv.loadUrl("file:///android_asset/mqtt_guide.html")
            guideLoaded = true
        }
        (wv.parent as? android.view.ViewGroup)?.removeView(wv)
        return wv
    }

    /** 进入页面后空闲预热 WebView，使首次打开引导几乎无感 */
    private fun prewarmGuideWebView() {
        if (guideWebView == null) binding.root.postDelayed({ ensureGuideWebView() }, 1500)
    }

    /** MQTT 配置引导：用 WebView 渲染与 mqtt_guide_preview.html 同款的 HTML（卡片/代码块/表格/步骤胶囊） */
    private fun showConfigGuide(onOk: (() -> Unit)? = null) {
        val webView = ensureGuideWebView()
        val container = FrameLayout(this).apply {
            setPadding(8, 4, 8, 4)
            addView(
                webView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("MQTT 配置引导")
            .setView(container)
            .setPositiveButton("我知道了") { _, _ -> onOk?.invoke() }
            .setNegativeButton("关闭", null)
            .show()
        dialog.window?.apply {
            setLayout(
                (resources.displayMetrics.widthPixels * 0.96).toInt(),
                (resources.displayMetrics.heightPixels * 0.94).toInt()
            )
        }
    }

    override fun observeRequestState() {}
    override fun initEvent() {}

    override fun onDestroy() {
        super.onDestroy()
        guideWebView?.apply {
            (parent as? android.view.ViewGroup)?.removeView(this)
            destroy()
        }
        guideWebView = null
    }
}
