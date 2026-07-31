package com.pengxh.daily.app.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.gson.Gson
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.WriterException
import com.pengxh.daily.app.databinding.ActivityRemoteControlBinding
import com.pengxh.daily.app.service.MqttAgentService
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.protocol.BindingPayload
import com.pengxh.kt.lite.base.KotlinBaseActivity
import com.pengxh.kt.lite.extensions.show
import com.pengxh.kt.lite.utils.SaveKeyValues
import com.pengxh.kt.lite.widget.dialog.AlertInputDialog
import java.security.SecureRandom
import java.util.UUID

class RemoteControlActivity : KotlinBaseActivity<ActivityRemoteControlBinding>() {

    private val gson = Gson()

    override fun initViewBinding(): ActivityRemoteControlBinding =
        ActivityRemoteControlBinding.inflate(layoutInflater)

    override fun setupTopBarLayout() {}

    override fun initOnCreate(savedInstanceState: Bundle?) {
        // 1) 设备身份 / 控制端凭证缺失则生成（仅一次，持久化，解绑不清）
        ensureDeviceIdentity()

        // 2) 回显配置
        reloadSettingsUI()

        // 3) 选项行交互（任务配置同款范式）
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

        binding.qrRow.setOnClickListener { generateAndShowQR() }
        binding.unbindRow.setOnClickListener { forceUnbind() }

        // 4) 连接 / 绑定状态回调 + 启动 MQTT 代理服务
        registerListeners()
        startMqttService()
    }

    override fun onResume() {
        super.onResume()
        registerListeners()
        updateStatusUI(MqttAgentService.isConnected())
        updateBindingUI(MqttAgentService.isBound())
    }

    override fun onPause() {
        super.onPause()
        if (MqttAgentService.isRunning()) {
            MqttAgentService.stateListener = null
            MqttAgentService.bindingStateListener = null
        }
    }

    private fun registerListeners() {
        MqttAgentService.stateListener = { connected ->
            runOnUiThread { updateStatusUI(connected) }
        }
        MqttAgentService.bindingStateListener = { bound ->
            runOnUiThread { updateBindingUI(bound) }
        }
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
            if (SaveKeyValues.loadString(Constant.MQTT_PASS_KEY, "").isBlank()) "未设置" else "已设置"
        binding.deviceIdValue.text =
            SaveKeyValues.loadString(Constant.DEVICE_ID_KEY, "").ifBlank { "未生成" }
        val ctlUser = SaveKeyValues.loadString(Constant.MQTT_CTL_USER_KEY, "")
        binding.ctlValue.text = if (ctlUser.isBlank()) "未生成" else ctlUser
        updateStatusUI(MqttAgentService.isConnected())
        updateBindingUI(MqttAgentService.isBound())
    }

    private fun updateStatusUI(connected: Boolean) {
        binding.statusValue.text = if (connected) "已连接" else "未连接"
    }

    private fun updateBindingUI(bound: Boolean) {
        binding.bindingValue.text = if (bound) "已绑定" else "未绑定"
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
                    SaveKeyValues.saveString(key, value)
                    valueView.text = if (isPassword) "已设置" else value.ifBlank { "未设置" }
                    "已保存".show(this@RemoteControlActivity)
                    if (key == Constant.MQTT_BROKER_KEY) restartMqttService()
                }

                override fun onCancelClick() {}
            })
            .build().show()
    }

    private fun startMqttService() {
        if (SaveKeyValues.loadString(Constant.MQTT_BROKER_KEY, "").isNotBlank() &&
            !MqttAgentService.isRunning()
        ) {
            startForegroundService(Intent(this, MqttAgentService::class.java))
        }
    }

    private fun restartMqttService() {
        stopService(Intent(this, MqttAgentService::class.java))
        startForegroundService(Intent(this, MqttAgentService::class.java))
    }

    private fun generateAndShowQR() {
        val broker = SaveKeyValues.loadString(Constant.MQTT_BROKER_KEY, "")
        if (broker.isBlank()) {
            "请先设置 MQTT 服务器（可点「MQTT 配置引导」查看获取方式）".show(this)
            return
        }
        if (SaveKeyValues.loadString(Constant.MQTT_USER_KEY, "").isBlank() ||
            SaveKeyValues.loadString(Constant.MQTT_PASS_KEY, "").isBlank()
        ) {
            "请先填写被控端用户名与密码".show(this)
            return
        }
        // 生成单次 / 60s 配对令牌（进二维码，不出长期密钥）
        val tokenBytes = ByteArray(16)
        SecureRandom().nextBytes(tokenBytes)
        val pairingToken = tokenBytes.joinToString("") { "%02x".format(it) }
        SaveKeyValues.saveString(Constant.MQTT_PAIRING_TOKEN_KEY, pairingToken)
        SaveKeyValues.saveLong(
            Constant.MQTT_PAIRING_EXPIRY_KEY,
            System.currentTimeMillis() + 60_000L
        )

        val payload = BindingPayload(
            broker = broker,
            deviceId = SaveKeyValues.loadString(Constant.DEVICE_ID_KEY, ""),
            ctlUser = SaveKeyValues.loadString(Constant.MQTT_CTL_USER_KEY, ""),
            ctlPass = SaveKeyValues.loadString(Constant.MQTT_CTL_PASS_KEY, ""),
            pairingToken = pairingToken
        )
        try {
            val bitmap = encodeQR(gson.toJson(payload), 512)
            showQRDialog(bitmap)
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
    private fun showQRDialog(bitmap: Bitmap) {
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
            text = "用控制端 App 扫描上方二维码完成绑定。配对令牌 60 秒内有效，请尽快扫码。" +
                "\n\n控制端 CTL 凭证（请在 EMQX 中建同名账户并配置受限 ACL）：" +
                "\n用户名：$ctlUser" +
                "\n密码：$ctlPass"
            setTextColor(Color.DKGRAY)
            textSize = 13f
            setPadding(0, 16, 0, 0)
        }
        container.addView(iv)
        container.addView(note)
        scroll.addView(container)
        AlertDialog.Builder(this)
            .setTitle("扫描绑定设备")
            .setView(scroll)
            .setPositiveButton("关闭", null)
            .show()
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

    /** MQTT 配置引导：用 WebView 渲染与 mqtt_guide_preview.html 同款的 HTML（卡片/代码块/表格/步骤胶囊） */
    private fun showConfigGuide(onOk: (() -> Unit)? = null) {
        val webView = WebView(this).apply {
            settings.javaScriptEnabled = false
            settings.defaultTextEncodingName = "utf-8"
            loadDataWithBaseURL(null, buildGuideHtml(), "text/html; charset=utf-8", "utf-8", null)
        }
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

    /** 生成引导 HTML（示例统一用 d7da9d15；与预览文件格式一致） */
    private fun buildGuideHtml(): String {
        return """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
            <meta charset="UTF-8"/>
            <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
            <style>
            :root{
              --bg:#f4f6f8; --card:#ffffff; --ink:#1f2329; --sub:#5b6470;
              --brand:#00a88a; --brand2:#3d5afe; --warn:#e8830c;
              --code-bg:#eef1f4; --code-ink:#c0264a; --line:#e4e8ec;
            }
            *{box-sizing:border-box;}
            a{color:var(--brand2);text-decoration:underline;}
            body{margin:0;background:var(--bg);color:var(--ink);
              font-family:-apple-system,"PingFang SC","Microsoft YaHei","Segoe UI",Roboto,Helvetica,Arial,sans-serif;
              line-height:1.7;padding:16px 10px 32px;}
            .wrap{max-width:720px;margin:0 auto;}
            .card{background:var(--card);border:1px solid var(--line);border-radius:16px;
              padding:16px 16px 18px;margin-bottom:14px;box-shadow:0 2px 10px rgba(0,0,0,0.04);}
            h1{font-size:19px;margin:0 0 4px;}
            .subtitle{color:var(--sub);font-size:13px;margin:0 0 12px;}
            .concept{background:#fff7ed;border:1px solid #fed7aa;border-left:4px solid var(--warn);
              border-radius:12px;padding:12px 14px;margin-bottom:4px;}
            .concept b{color:#b45309;}
            .step-title{display:inline-block;background:linear-gradient(135deg,var(--brand),var(--brand2));
              color:#fff;font-weight:700;font-size:14px;padding:3px 12px;border-radius:999px;margin:14px 0 8px;}
            ul{margin:6px 0 6px;padding-left:20px;}
            li{margin:4px 0;}
            code{background:var(--code-bg);color:var(--code-ink);
              font-family:"SFMono-Regular",Consolas,"Liberation Mono",Menlo,monospace;
              font-size:13px;padding:1px 6px;border-radius:6px;border:1px solid #dde3e8;white-space:nowrap;}
            .table-scroll{overflow-x:auto;-webkit-overflow-scrolling:touch;}
            table.acl{width:100%;border-collapse:collapse;margin:8px 0;font-size:12px;}
            table.acl th,table.acl td{border:1px solid var(--line);padding:7px 9px;text-align:left;}
            table.acl th{background:#f1f5f9;}
            .allow{color:#15803d;font-weight:700;}
            .deny{color:#b91c1c;font-weight:700;}
            .tag{display:inline-block;font-size:12px;padding:1px 8px;border-radius:6px;
              background:#ecfdf5;color:#047857;margin-right:6px;}
            .note{color:var(--sub);font-size:13px;}
            .tip{background:#eff6ff;border:1px solid #bfdbfe;border-left:4px solid var(--brand2);
              border-radius:12px;padding:12px 14px;}
            </style>
            </head>
            <body>
            <div class="wrap">

              <div class="card">
                <div class="concept">
                  <b>【核心概念 · 先看这一行】</b><br/>
                  本 App 用「设备ID」标识一台设备，所有 MQTT 主题都在 <code>dt/设备ID/</code> 之下。例如设备ID 为 <code>d7da9d15</code> 时，主题为 <code>dt/d7da9d15/</code>；你也可以自定义设备ID，例如 <code>88888888</code>，对应主题 <code>dt/88888888/</code>。<br/>
                  「设备ID」允许自定义；注意：EMQX 里的账户名与主题前缀都要用这个设备ID，一旦变更就需同步修改，否则连不上。
                </div>
              </div>

              <div class="card">
                <span class="step-title">① 注册 EMQX Cloud（用电脑浏览器）</span>
                <ul>
                  <li>打开 <a href="https://www.emqx.com/">emqx.com</a> → 手机号注册并登录</li>
                  <li>点「免费试用」→「云端」→ 新建 Serverless 部署（区域选离自己最近的，如 杭州；消费限额 0）</li>
                  <li>等 1~2 分钟变绿「运行中」，记下「连接地址」与「端口 8883(TLS)」</li>
                </ul>
              </div>

              <div class="card">
                <span class="step-title">② 在 EMQX 建两个账户</span>
                <p class="note">（左侧「访问控制」→「客户端认证」→ 新建）</p>
                <ul>
                  <li><span class="tag">被控端 DEV</span> 用户名（例：d7da9d15）和密码自设（任意）</li>
                  <li><span class="tag">控制端 CTL</span> 用户名和密码可使用 App 默认生成的 <code>ctl-d7da9d15</code>，也可自定义（例：ctl-k20pro）</li>
                </ul>
              </div>

              <div class="card">
                <span class="step-title">③ 在 EMQX 配权限（ACL）</span>
                <p class="note">（左侧「访问控制」→「客户端授权」→ 用户名 选项卡）</p>
                <p>两个账户的用户名就是第②步定的：被控端 = <code>d7da9d15</code>，控制端 = <code>ctl-d7da9d15</code>；两者<b>只是用户名不同</b>，ACL 规则完全一样，各加 <b>1 条</b>即可：</p>
                <div class="table-scroll">
                  <table class="acl">
                    <thead><tr><th>账户（用户名）</th><th>主题</th><th>操作</th><th>权限</th></tr></thead>
                    <tbody>
                      <tr><td><code>d7da9d15</code></td><td><code>dt/d7da9d15/#</code></td><td>发布&amp;订阅</td><td class="allow">允许</td></tr>
                      <tr><td><code>ctl-d7da9d15</code></td><td><code>dt/d7da9d15/#</code></td><td>发布&amp;订阅</td><td class="allow">允许</td></tr>
                    </tbody>
                  </table>
                </div>
                <p class="note">主题按设备ID，不按账户名。例：设备ID=d7da9d15 时，被控端账户 d7da9d15 与控制端账户 ctl-d7da9d15 都允许 <code>dt/d7da9d15/#</code> 的发布&amp;订阅。</p>
                <p><b>白名单兜底（用户名留空＝所有用户）</b></p>
                <div class="table-scroll">
                  <table class="acl">
                    <thead><tr><th>账户（用户名）</th><th>主题</th><th>操作</th><th>权限</th></tr></thead>
                    <tbody>
                      <tr><td>（留空）</td><td><code>#</code></td><td>发布&amp;订阅</td><td class="deny">拒绝</td></tr>
                    </tbody>
                  </table>
                </div>
                <p class="note">这条「所有用户 / # / 拒绝」开启白名单：此后只有上面明确允许的规则才放行，其余一律拒绝。</p>
              </div>

              <div class="card">
                <span class="step-title">④ 回 App 填写并绑定</span>
                <ul>
                  <li>MQTT 服务器：填 EMQX 地址:8883（如 xxxx.emqxsl.com:8883）</li>
                  <li>被控端用户名 / 密码：填第②步的 DEV 账户（即 d7da9d15 及其密码）</li>
                  <li>控制端凭证(ctl)：点开，改成第②步的 CTL 账户（<code>ctl-d7da9d15</code>），或直接用默认生成值（只要 EMQX 里一致）</li>
                  <li>点「生成绑定二维码」→ 用控制端 App 扫码 → 完成配对（会话密钥握手时派生，不进二维码）</li>
                </ul>
              </div>

              <div class="card">
                <div class="tip">
                  <b>【提示】增加一台被控端设备</b><br/>
                  可以直接使用 App 里的「设备ID」或自定义设备ID为 99999999（不要跟之前的重复），<br/>
                  再为这个新的 99999999 及其 <code>ctl-99999999</code> 在 EMQX 重复 ② ③ ④ 即可，无需新建部署 EMQX 服务器。
                </div>
              </div>

            </div>
            </body>
            </html>
        """.trimIndent()
    }

    override fun observeRequestState() {}
    override fun initEvent() {}
}
