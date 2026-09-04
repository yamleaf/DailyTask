package com.pengxh.daily.app.ui

import android.content.ComponentName
import android.content.Intent
import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.pengxh.daily.app.R
import com.pengxh.daily.app.UiInsets
import com.pengxh.daily.app.databinding.ActivityAdvancedSettingsBinding
import com.pengxh.daily.app.extensions.isAutostartGranted
import com.pengxh.daily.app.extensions.notificationEnable
import com.pengxh.daily.app.shizuku.CoordinateCaptureOverlay
import com.pengxh.daily.app.shizuku.ShizukuConfigStore
import androidx.lifecycle.lifecycleScope
import com.pengxh.daily.app.shizuku.ShizukuManager
import com.pengxh.daily.app.shizuku.ShizukuShell
import com.pengxh.daily.app.shizuku.ShizukuStep
import com.pengxh.daily.app.shizuku.ShizukuStepType
import com.pengxh.daily.app.service.NotificationMonitorService
import com.pengxh.daily.app.utils.Constant
import com.yample.mqttprotocol.dialog.UnifiedDialogKit
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * 高级设置（feat_shiziku）：服务权限状态 + 登录/身份验证配置。
 * 独立 Activity，设置页仅一个入口跳转；Shizuku 全部逻辑收口在此，不触碰现有功能。
 *
 * 风格约定（与 app 整体一致）：
 *  - 标题栏 MaterialToolbar（渐变头）+ SectionCard 分组卡片；
 *  - 弹窗统一走 UnifiedDialogKit，不手拼原生 AlertDialog。
 *
 * 安全约定：
 *  - 密码仅在「密码缓存」对话框输入时录入；保存后不回显明文，再次进入只能重新填写覆盖。
 *  - 密码登录 / 验证码登录步骤分开配置；步骤一律**按采集的坐标执行**（逐机不同，无内置默认），
 *    未配置时执行器直接反馈「步骤未配置」，需在高级设置里「从当前屏幕采集坐标」。
 */
class AdvancedSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdvancedSettingsBinding

    // 坐标采集（可拖动十字光标 + 范围可视化 + 自动回跳回填）挂起状态
    private var pendingCaptureCallback: ((Int, Int, Int) -> Unit)? = null
    private var pendingCaptureCoord: Triple<Int, Int, Int>? = null

    // 滑动采集（两点模式）挂起状态：起点(sx,sy) + 终点(ex,ey)
    private var pendingSwipeCallback: ((Int, Int, Int, Int) -> Unit)? = null
    private var pendingSwipeCoord: Pair<Pair<Int, Int>, Pair<Int, Int>>? = null

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, _ ->
        refreshShizukuState()
    }

    // 自动检测：Shizuku 服务上线/下线时刷新界面（无需用户手动点击刷新）
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread { refreshShizukuState() }
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        runOnUiThread { refreshShizukuState() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdvancedSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        UiInsets.applyStatusBarPadding(this, binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)

        binding.btnShizukuAuth.setOnClickListener { ShizukuManager.requestPermission(this) }
        binding.btnRefreshShizukuState.setOnClickListener { refreshShizukuState() }
        binding.layoutQuickGrant.setOnClickListener { quickGrant() }
        binding.layoutOp1Name.setOnClickListener { if (editable()) showOpNameDialog() }
        OpCard.entries.forEach { card ->
            cardAddButton(card).setOnClickListener { if (editable()) addStep(card) }
        }
        populateAllStepCards()
    }

    override fun onResume() {
        super.onResume()
        refreshShizukuState()
        // 坐标采集确认后回跳：把中心坐标 + 范围回填到刚才打开的步骤编辑器
        val coord = pendingCaptureCoord
        val cb = pendingCaptureCallback
        if (coord != null && cb != null) {
            pendingCaptureCoord = null
            pendingCaptureCallback = null
            cb(coord.first, coord.second, coord.third)
        }
        // 滑动采集确认后回跳：起点 + 终点一起回填
        val swipe = pendingSwipeCoord
        val scb = pendingSwipeCallback
        if (swipe != null && scb != null) {
            pendingSwipeCoord = null
            pendingSwipeCallback = null
            val (s, e) = swipe
            scb(s.first, s.second, e.first, e.second)
        }
    }

    override fun onDestroy() {
        CoordinateCaptureOverlay.dismiss()
        runCatching {
            Shizuku.removeRequestPermissionResultListener(permissionListener)
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
        }
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        saveAllSteps()
    }

    private fun editable(): Boolean = ShizukuManager.isGranted()

    /** 刷新 Shizuku 服务明细（行式）+ 配置区可编辑性 */
    private fun refreshShizukuState() {
        val available = ShizukuManager.isAvailable()
        val granted = ShizukuManager.isGranted()
        binding.txtShizukuChannel.text = ShizukuManager.channelLabel()
        binding.txtShizukuChannel.setTextColor(statusColor(available))
        binding.txtShizukuAuthSource.text = ShizukuManager.grantSource()
        binding.txtShizukuAuthSource.setTextColor(statusColor(granted))
        binding.btnShizukuAuth.visibility = if (available && !granted) View.VISIBLE else View.GONE
        // 高级功能：Shizuku 已授权即生效，配置区始终随授权状态可编辑
        val on = ShizukuManager.isGranted()
        OpCard.entries.forEach { card -> cardRoot(card).alpha = if (on) 1f else 0.45f }
        refreshConfig()
        refreshEnv()
    }

    /** 环境明细（Shizuku 服务 / 开发者选项 / 无线调试 / ADB 状态），经 shizuku getprop 异步刷新 */
    private fun refreshEnv() {
        lifecycleScope.launch {
            val env = ShizukuManager.environment(this@AdvancedSettingsActivity)
            binding.txtShizukuServer.text = env.shizukuServer
            binding.txtDevOpt.text = env.devOpt
            binding.txtWirelessAdb.text = env.wirelessAdb
            binding.txtAdbStatus.text = env.adbUsb
        }
    }

    private fun statusColor(ok: Boolean): Int = if (ok) {
        ContextCompat.getColor(this, R.color.md_success)
    } else {
        ContextCompat.getColor(this, R.color.md_warning)
    }

    /** 一键授权：检测悬浮窗/通知/自启动/电池白名单/通知监听，未授权项尝试用 Shizuku(adb) 授权 */
    private fun quickGrant() {
        val ctx = this
        if (!ShizukuManager.isGranted()) {
            UnifiedDialogKit.showConfirm(
                ctx,
                "Shizuku 未就绪",
                "一键授权需要通过 Shizuku 执行 adb 授权，请先完成 Shizuku 授权。",
                confirmText = "去授权",
                cancelText = "取消",
                onConfirm = { ShizukuManager.requestPermission(ctx) }
            )
            return
        }
        UnifiedDialogKit.showConfirm(
            ctx,
            "一键授权",
            "将检测以下权限，未授权项尝试通过 Shizuku(adb) 自动授权：\n\n悬浮窗 / 通知 / 自启动 / 电池白名单 / 通知监听",
            confirmText = "开始",
            cancelText = "取消",
            cancelable = false,
            onConfirm = {
                binding.txtQuickGrantResult.text = "授权中…"
                lifecycleScope.launch(Dispatchers.IO) {
                    val rows = grantViaAdb(ctx)
                    withContext(Dispatchers.Main) {
                        binding.txtQuickGrantResult.text =
                            if (rows.all { it.status.startsWith("已") }) "已全部授权" else "部分未授权"
                        showGrantResult(rows)
                    }
                }
                true
            }
        )
    }

    private data class GrantRow(val name: String, val status: String)

    private suspend fun grantViaAdb(ctx: Context): List<GrantRow> {
        val pkg = ctx.packageName
        val rows = mutableListOf<GrantRow>()

        // 悬浮窗
        if (!Settings.canDrawOverlays(ctx)) {
            runCatching {
                ShizukuShell.exec("cmd appops set $pkg SYSTEM_ALERT_WINDOW allow 2>/dev/null; appops set $pkg SYSTEM_ALERT_WINDOW allow 2>/dev/null; echo done")
            }
            delay(400)
            rows += GrantRow(
                "悬浮窗",
                if (Settings.canDrawOverlays(ctx)) "已通过 adb 授权" else "授权失败，需手动"
            )
        } else {
            rows += GrantRow("悬浮窗", "已授权")
        }

        // 通知（Android 13+ 运行时权限）
        val notifGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!notifGranted) {
            runCatching {
                ShizukuShell.exec("pm grant $pkg android.permission.POST_NOTIFICATIONS; echo done")
            }
            delay(300)
            rows += GrantRow(
                "通知",
                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
                ) "已通过 adb 授权" else "授权失败，需手动"
            )
        } else {
            rows += GrantRow("通知", "已授权")
        }

        // 电池白名单
        val pm = ctx.getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(pkg)) {
            runCatching {
                ShizukuShell.exec("dumpsys deviceidle whitelist +$pkg; echo done")
            }
            delay(400)
            rows += GrantRow(
                "电池白名单",
                if (pm.isIgnoringBatteryOptimizations(pkg)) "已通过 adb 授权" else "授权失败，需手动"
            )
        } else {
            rows += GrantRow("电池白名单", "已授权")
        }

        // 自启动：仅 MIUI/HyperOS 等有公开自启动开关（appops 10008）的 ROM 才显示并尝试授权；
        // 原生/无开关 ROM 隐藏该项，避免「原生无需」干扰全部已授权的判定（不再出现部分授权误报）。
        val autostart = isAutostartGranted()
        if (autostart != null) {
            rows += when (autostart) {
                true -> GrantRow("自启动", "已授权")
                false -> {
                    runCatching { ShizukuShell.exec("appops set $pkg AUTO_START allow; echo done") }
                    GrantRow("自启动", if (isAutostartGranted() == true) "已通过 adb 授权" else "授权失败，需手动")
                }
            }
        }

        // 通知监听：尝试用系统正式命令 allow_listener 授权（更新 v2 权威字段 + 触发绑定，持久且连接）。
        // Android 12+ 仅 settings put 会「已授权未连接」且杀进程/重启后丢失，回退不可靠故不再回退；
        // allow_listener 在 root/Custom shizuku 下可用、官方 shell 模式静默失败——失败则整项从一键授权移除。
        val listenerComponent = "$pkg/com.pengxh.daily.app.service.NotificationMonitorService"
        if (ctx.notificationEnable()) {
            rows += GrantRow("通知监听", "已授权")
        } else {
            runCatching {
                ShizukuShell.exec("cmd notification allow_listener $listenerComponent; echo done")
            }
            delay(400)
            if (ctx.notificationEnable()) {
                rows += GrantRow("通知监听", "已通过 adb 授权")
            }
        }

        return rows
    }

    private fun showGrantResult(rows: List<GrantRow>) {
        val lines = rows.joinToString("\n") { "• ${it.name}：${it.status}" }
        val allOk = rows.all { it.status.startsWith("已") }
        if (allOk) {
            UnifiedDialogKit.showSuccess(this, "一键授权完成", lines, cancelText = null)
        } else {
            UnifiedDialogKit.showConfirm(
                this,
                "一键授权结果",
                lines,
                confirmText = "知道了",
                cancelText = null
            )
        }
    }

    private fun refreshConfig() {
        binding.txtOp1Name.text = ShizukuConfigStore.opName1()
    }

    private fun stepsLabel(steps: List<ShizukuStep>): String {
        if (steps.isEmpty()) return "未配置"
        val typeTag = mapOf(
            ShizukuStepType.CLICK to "点",
            ShizukuStepType.PWD_INPUT to "密",
            ShizukuStepType.CODE_INPUT to "码",
            ShizukuStepType.CODE_CAPTURE to "采",
            ShizukuStepType.RESULT_CHECK to "判"
        )
        return steps.joinToString(" → ") {
            val t = typeTag[it.type] ?: "点"
            val coord = if (it.hasCoord) "(${it.x},${it.y})" else it.buttonText
            "[$t]$coord"
        }
    }

    // ═══════ 对话框（统一 UnifiedDialogKit 风格）═══════

    /**
     * 密码设置：密文经 SecurePrefs（Keystore AES256-GCM）保存。
     * 保存后不回显明文——再次打开输入框为空，只能重新填写覆盖（或清除）。
     */
    private fun showPasswordDialog() {
        val has = ShizukuConfigStore.hasPassword()
        val input = EditText(this).apply {
            hint = if (has) "已设置，重新输入将覆盖" else "请输入登录密码"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val tip = TextView(this).apply {
            text = "密码仅存于本机加密存储；保存后不再显示，仅可重新填写覆盖。"
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@AdvancedSettingsActivity, R.color.md_onSurfaceVariant))
            setPadding(0, 0, 0, dp(6))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(8), dp(4), 0)
            addView(input)
            addView(tip)
        }
        UnifiedDialogKit.showForm(
            ctx = this,
            contentView = box,
            title = "密码缓存",
            positiveText = "保存",
            negativeText = "取消",
            onConfirm = {
                // 留空视为清除（用户主动置空）
                ShizukuConfigStore.setPassword(input.text.toString())
                refreshConfig()
                true
            }
        )
    }

    private fun showOpNameDialog() {
        val current = ShizukuConfigStore.opName1()
        val input = EditText(this).apply {
            hint = "请输入操作名称"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(current)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(8), dp(4), 0)
            addView(input)
        }
        UnifiedDialogKit.showForm(
            ctx = this,
            contentView = box,
            title = "操作名称（空格可回复默认）",
            message = "修改后，控制端总览页与本页将同步显示新名称",
            positiveText = "确定",
            negativeText = "取消",
            onConfirm = {
                val name = input.text.toString().trim()
                ShizukuConfigStore.setOpName1(name)
                refreshConfig()
                true
            }
        )
    }
    /** 本步延迟滑块选择（1~30s）：胶囊「延迟 s」点击弹出，拖动滑块即时预览 */
    /** 延迟范围设置：min~max 秒（0.1s 步进随机；相同=固定），如 0-5/1-1/3-5 */
    private fun showDelayRangeDialog(currentMin: Int, currentMax: Int, onResult: (Int, Int) -> Unit) {
        val minEt = EditText(this).apply {
            hint = "最小秒（0）"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(if (currentMin > 0) currentMin.toString() else "")
            textSize = 16f
            gravity = Gravity.CENTER
        }
        val maxEt = EditText(this).apply {
            hint = "最大秒（5）"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(if (currentMax > 0) currentMax.toString() else "")
            textSize = 16f
            gravity = Gravity.CENTER
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            addView(minEt, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@AdvancedSettingsActivity).apply {
                text = "~"
                textSize = 16f
                setTextColor(ContextCompat.getColor(this@AdvancedSettingsActivity, R.color.md_onSurfaceVariant))
                setPadding(dp(12), 0, dp(12), 0)
            })
            addView(maxEt, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(8), dp(4), 0)
            addView(TextView(this@AdvancedSettingsActivity).apply {
                text = "本步执行前等待秒数范围（0.1s 步进随机）：\n· 0-5 → 0~5 秒内随机\n· 1-1 → 固定 1 秒\n· 3-5 → 3~5 秒内随机\n留空 = 默认（第 1 步 5s、其余 3s）"
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@AdvancedSettingsActivity, R.color.md_onSurfaceVariant))
                setLineSpacing(dp(3).toFloat(), 1f)
            })
            addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) })
        }
        UnifiedDialogKit.showForm(
            ctx = this,
            contentView = box,
            title = "本步执行延迟",
            positiveText = "确定",
            negativeText = "取消",
            onConfirm = {
                val min = minEt.text.toString().trim().toIntOrNull() ?: 0
                val max = maxEt.text.toString().trim().toIntOrNull()?.coerceAtLeast(min) ?: min
                onResult(min, max)
                true
            }
        )
    }

    // ===================== 内联步骤编辑（6 个操作卡片，步骤展开在卡片中）=====================

    private enum class OpCard(val title: String) {
        PWD_LOGIN("密码登录"), VERIFY_LOGIN("验证码登录"), AUTH("身份验证"),
        PUNCH("模拟打卡"), CUSTOM_1("操作1")
    }

    private val cardStepRows = mutableMapOf<OpCard, MutableList<StepRow>>()

    private fun cardRoot(card: OpCard) = when (card) {
        OpCard.PWD_LOGIN -> binding.cardPwdLogin
        OpCard.VERIFY_LOGIN -> binding.cardVerifyLogin
        OpCard.AUTH -> binding.cardAuth
        OpCard.PUNCH -> binding.cardPunch
        OpCard.CUSTOM_1 -> binding.cardCustom1
    }

    private fun cardContainer(card: OpCard): LinearLayout = when (card) {
        OpCard.PWD_LOGIN -> binding.containerPwdLoginSteps
        OpCard.VERIFY_LOGIN -> binding.containerVerifyLoginSteps
        OpCard.AUTH -> binding.containerAuthSteps
        OpCard.PUNCH -> binding.containerPunchSteps
        OpCard.CUSTOM_1 -> binding.containerCustom1Steps
    }

    private fun cardAddButton(card: OpCard): TextView = when (card) {
        OpCard.PWD_LOGIN -> binding.btnAddPwdLoginStep
        OpCard.VERIFY_LOGIN -> binding.btnAddVerifyLoginStep
        OpCard.AUTH -> binding.btnAddAuthStep
        OpCard.PUNCH -> binding.btnAddPunchStep
        OpCard.CUSTOM_1 -> binding.btnAddCustom1Step
    }

    private fun loadSteps(card: OpCard): List<ShizukuStep> = when (card) {
        OpCard.PWD_LOGIN -> ShizukuConfigStore.pwdLoginSteps()
        OpCard.VERIFY_LOGIN -> ShizukuConfigStore.verifyLoginSteps()
        OpCard.AUTH -> ShizukuConfigStore.authSteps()
        OpCard.PUNCH -> ShizukuConfigStore.punchSteps()
        OpCard.CUSTOM_1 -> ShizukuConfigStore.custom1Steps()
    }

    private fun saveSteps(card: OpCard, steps: List<ShizukuStep>) = when (card) {
        OpCard.PWD_LOGIN -> ShizukuConfigStore.setPwdLoginSteps(steps)
        OpCard.VERIFY_LOGIN -> ShizukuConfigStore.setVerifyLoginSteps(steps)
        OpCard.AUTH -> ShizukuConfigStore.setAuthSteps(steps)
        OpCard.PUNCH -> ShizukuConfigStore.setPunchSteps(steps)
        OpCard.CUSTOM_1 -> ShizukuConfigStore.setCustom1Steps(steps)
    }

    private fun populateAllStepCards() {
        OpCard.entries.forEach { card ->
            val container = cardContainer(card)
            container.removeAllViews()
            val rows = mutableListOf<StepRow>()
            // 先注册列表再逐个构建：buildStepRow 计算后续步骤默认延迟时能读到已添加的步骤
            cardStepRows[card] = rows
            loadSteps(card).forEach { step -> rows.add(buildStepRow(card, step)) }
            refreshStepCapsules(card)
        }
    }

    private fun addStep(card: OpCard) {
        val row = buildStepRow(card, ShizukuStep())
        cardStepRows.getOrPut(card) { mutableListOf() }.add(row)
        refreshStepCapsules(card)
        saveCardSteps(card)
    }

    private fun collectSteps(card: OpCard): List<ShizukuStep> {
        val rows = cardStepRows[card] ?: return emptyList()
        return rows.mapNotNull { row ->
            val x = row.xEdit.text.toString().trim().toIntOrNull() ?: -1
            val y = row.yEdit.text.toString().trim().toIntOrNull() ?: -1
            val x2 = row.x2Edit.text.toString().trim().toIntOrNull() ?: -1
            val y2 = row.y2Edit.text.toString().trim().toIntOrNull() ?: -1
            val remark = row.remarkEdit.text.toString().trim()
            val kw1 = row.kw1Edit.text.toString()
            val kw2 = row.kw2Edit.text.toString()
            val needCoord = row.type == ShizukuStepType.CLICK ||
                row.type == ShizukuStepType.SWIPE ||
                row.type == ShizukuStepType.PWD_INPUT ||
                row.type == ShizukuStepType.CODE_INPUT
            if (needCoord && (x < 0 || y < 0)) return@mapNotNull null
            if (row.type == ShizukuStepType.SWIPE && (x2 < 0 || y2 < 0)) return@mapNotNull null
            ShizukuStep(row.type, remark, x, y, x2, y2, row.range, row.delayMin, row.delayMax, kw1, kw2, row.uid, row.codeWait)
        }
    }

    private fun saveCardSteps(card: OpCard) {
        saveSteps(card, collectSteps(card))
    }

    private fun saveAllSteps() {
        OpCard.entries.forEach { saveCardSteps(it) }
    }

    /** 按步骤类型刷新每步胶囊显隐与文案（密码：仅密码输入；超时：验证码输入/采集，连续验证码输入只有首个显示） */
    private fun refreshStepCapsules(card: OpCard) {
        val rows = cardStepRows[card] ?: return
        rows.forEachIndexed { i, r ->
            val isCodeInput = r.type == ShizukuStepType.CODE_INPUT
            val isCodeType = isCodeInput || r.type == ShizukuStepType.CODE_CAPTURE
            val prevCodeInput = i > 0 && rows[i - 1].type == ShizukuStepType.CODE_INPUT
            r.pwdCapsule.visibility = if (r.type == ShizukuStepType.PWD_INPUT) View.VISIBLE else View.GONE
            r.pwdCapsule.text = if (ShizukuConfigStore.hasStepPassword(r.uid)) "密码·已设" else "密码·未设"
            // 连续多个验证码输入（多格验证码）只有第一个显示超时配置胶囊
            r.waitCapsule.visibility =
                if (isCodeType && !(isCodeInput && prevCodeInput)) View.VISIBLE else View.GONE
            r.waitCapsule.text = if (r.codeWait > 0) "超时 ${r.codeWait}s" else "超时·默认"
        }
    }

    /** 本步独立密码设置（SecurePrefs 按 uid 加密存储；留空=清除并回退全局密码） */
    private fun showStepPasswordDialog(uid: String, onChanged: () -> Unit) {
        val has = ShizukuConfigStore.hasStepPassword(uid)
        val input = EditText(this).apply {
            hint = if (has) "已设置，重新输入将覆盖" else "请输入本步密码"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val tip = TextView(this).apply {
            text = "本步密码仅存于本机加密存储；留空保存 = 清除（执行时回退全局密码缓存）。"
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@AdvancedSettingsActivity, R.color.md_onSurfaceVariant))
            setPadding(0, 0, 0, dp(6))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(8), dp(4), 0)
            addView(input)
            addView(tip)
        }
        UnifiedDialogKit.showForm(
            ctx = this,
            contentView = box,
            title = "本步密码",
            positiveText = "保存",
            negativeText = "取消",
            onConfirm = {
                ShizukuConfigStore.setStepPassword(uid, input.text.toString())
                onChanged()
                true
            }
        )
    }

    /** 本步独立验证码超时（秒）；留空=0=用该操作全局超时 */
    private fun showStepTimeoutDialog(current: Int, onResult: (Int) -> Unit) {
        val input = EditText(this).apply {
            hint = "10~600，留空用默认"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(if (current > 0) current.toString() else "")
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val tip = TextView(this).apply {
            text = "本步等待验证码 / 短信发送确认的超时秒数；留空 = 用该操作的全局超时。"
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@AdvancedSettingsActivity, R.color.md_onSurfaceVariant))
            setPadding(0, 0, 0, dp(6))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(8), dp(4), 0)
            addView(input)
            addView(tip)
        }
        UnifiedDialogKit.showForm(
            ctx = this,
            contentView = box,
            title = "本步验证码超时",
            positiveText = "确定",
            negativeText = "取消",
            onConfirm = {
                val sec = input.text.toString().trim().toIntOrNull() ?: 0
                onResult(sec.coerceIn(0, 600))
                true
            }
        )
    }

    private fun buildStepRow(op: OpCard, step: ShizukuStep): StepRow {
        val typeLabels = arrayOf("点击坐标", "手势滑动", "密码输入", "验证码输入", "验证信息采集", "结果判定")
        val container = cardContainer(op)
        lateinit var row: StepRow
        lateinit var coordArea: LinearLayout
        lateinit var endpointArea: LinearLayout
        lateinit var keywordArea: LinearLayout
        // 卡片头类型：胶囊样式（文字 + ▾ 下拉符），点击弹单选，与「点选/延迟」胶囊风格统一
        val typeTv = TextView(this@AdvancedSettingsActivity).apply {
            text = "${typeLabels[typeIndex(step.type)]} ▾"
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@AdvancedSettingsActivity, R.color.md_secondary))
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(ContextCompat.getColor(this@AdvancedSettingsActivity, R.color.md_secondaryContainer))
            }
        }
        typeTv.setOnClickListener {
            UnifiedDialogKit.showSingleChoice(
                this@AdvancedSettingsActivity,
                "步骤类型",
                typeLabels.toList(),
                typeIndex(row.type)
            ) { which ->
                row.type = ShizukuStepType.entries[which]
                typeTv.text = "${typeLabels[which]} ▾"
                val needCoord = row.type == ShizukuStepType.CLICK ||
                    row.type == ShizukuStepType.SWIPE ||
                    row.type == ShizukuStepType.PWD_INPUT ||
                    row.type == ShizukuStepType.CODE_INPUT
                coordArea.visibility = if (needCoord) View.VISIBLE else View.GONE
                endpointArea.visibility = if (row.type == ShizukuStepType.SWIPE) View.VISIBLE else View.GONE
                keywordArea.visibility = if (row.type == ShizukuStepType.CODE_CAPTURE) View.VISIBLE else View.GONE
                refreshStepCapsules(op)
                saveCardSteps(op)
            }
        }
        // 备注（小字，默认为空时显示占位「备注」）
        val remarkEdit = EditText(this@AdvancedSettingsActivity).apply {
            hint = "备注"
            setText(step.buttonText)
            textSize = 11f
            background = null
            maxLines = 1
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val xEdit = EditText(this@AdvancedSettingsActivity).apply {
            hint = "X"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(if (step.x >= 0) step.x.toString() else "")
            setPadding(dp(6), dp(4), dp(6), dp(4))
            minWidth = dp(40)
            textSize = 12f
        }
        val yEdit = EditText(this@AdvancedSettingsActivity).apply {
            hint = "Y"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(if (step.y >= 0) step.y.toString() else "")
            setPadding(dp(6), dp(4), dp(6), dp(4))
            minWidth = dp(40)
            textSize = 12f
        }
        // 滑动终点（SWIPE 专用）：X2/Y2 + 终点点选
        val x2Edit = EditText(this@AdvancedSettingsActivity).apply {
            hint = "X2"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(if (step.x2 >= 0) step.x2.toString() else "")
            setPadding(dp(6), dp(4), dp(6), dp(4))
            minWidth = dp(40)
            textSize = 12f
        }
        val y2Edit = EditText(this@AdvancedSettingsActivity).apply {
            hint = "Y2"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(if (step.y2 >= 0) step.y2.toString() else "")
            setPadding(dp(6), dp(4), dp(6), dp(4))
            minWidth = dp(40)
            textSize = 12f
        }
        // 终点点选按钮：打开打卡App → 十字光标点选目标点 → 回填 X2/Y2
        val collect2 = TextView(this@AdvancedSettingsActivity).apply {
            text = "终点"
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@AdvancedSettingsActivity, R.color.md_primary))
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(ContextCompat.getColor(this@AdvancedSettingsActivity, R.color.md_primaryContainer))
            }
            setOnClickListener {
                val curX = x2Edit.text.toString().toIntOrNull() ?: -1
                val curY = y2Edit.text.toString().toIntOrNull() ?: -1
                startCoordinateCapture(
                    initialX = if (curX >= 0) curX else -1,
                    initialY = if (curY >= 0) curY else -1,
                    initialRange = 0
                ) { x, y, _ ->
                    x2Edit.setText(x.toString())
                    y2Edit.setText(y.toString())
                    saveCardSteps(op)
                }
            }
        }
        // 起点「点选」按钮：打开打卡App → 十字光标点选 → 回填 X/Y/范围
        val collect = TextView(this@AdvancedSettingsActivity).apply {
            text = "点选"
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@AdvancedSettingsActivity, R.color.md_primary))
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(ContextCompat.getColor(this@AdvancedSettingsActivity, R.color.md_primaryContainer))
            }
            setOnClickListener {
                val curX = xEdit.text.toString().toIntOrNull() ?: -1
                val curY = yEdit.text.toString().toIntOrNull() ?: -1
                if (row.type == ShizukuStepType.SWIPE) {
                    // 滑动采集模式：起始十字 → 确认 → 自动切终点十字 → 再确认，一次性回填起终点
                    startSwipeCapture(
                        initialSx = if (curX >= 0) curX else -1,
                        initialSy = if (curY >= 0) curY else -1
                    ) { sx, sy, ex, ey ->
                        xEdit.setText(sx.toString())
                        yEdit.setText(sy.toString())
                        x2Edit.setText(ex.toString())
                        y2Edit.setText(ey.toString())
                        saveCardSteps(op)
                    }
                } else {
                    startCoordinateCapture(
                        initialX = if (curX >= 0) curX else -1,
                        initialY = if (curY >= 0) curY else -1,
                        initialRange = row.range
                    ) { x, y, r ->
                        xEdit.setText(x.toString())
                        yEdit.setText(y.toString())
                        row.range = r
                        saveCardSteps(op)
                    }
                }
            }
        }
        // CODE_CAPTURE 专用关键字（空=使用内置匹配规则）
        val kw1Edit = EditText(this@AdvancedSettingsActivity).apply {
            hint = "收件人关键字"
            setText(step.kw1)
            textSize = 12f
            maxLines = 1
            background = null
            setPadding(dp(6), dp(4), dp(6), dp(4))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val kw2Edit = EditText(this@AdvancedSettingsActivity).apply {
            hint = "内容关键字"
            setText(step.kw2)
            textSize = 12f
            maxLines = 1
            background = null
            setPadding(dp(6), dp(4), dp(6), dp(4))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        // 每步唯一标识：老数据无 uid 时按需生成（关联 SecurePrefs 中该步独立密码）
        val uid = if (step.uid.isBlank()) UUID.randomUUID().toString() else step.uid
        // 密码胶囊（点击设本步密码）——视图先建，点击回调引用 row（lateinit，随后即赋值）
        val pwdCapsule = TextView(this@AdvancedSettingsActivity).apply {
            text = "密码"
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@AdvancedSettingsActivity, R.color.md_primary))
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(ContextCompat.getColor(this@AdvancedSettingsActivity, R.color.md_primaryContainer))
            }
        }
        pwdCapsule.setOnClickListener {
            if (editable()) showStepPasswordDialog(row.uid) {
                pwdCapsule.text = if (ShizukuConfigStore.hasStepPassword(row.uid)) "密码·已设" else "密码·未设"
                saveCardSteps(op)
            }
        }
        // 超时胶囊（点击设本步验证码超时）
        val waitCapsule = TextView(this@AdvancedSettingsActivity).apply {
            text = "超时"
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@AdvancedSettingsActivity, R.color.md_primary))
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(ContextCompat.getColor(this@AdvancedSettingsActivity, R.color.md_primaryContainer))
            }
        }
        waitCapsule.setOnClickListener {
            if (editable()) showStepTimeoutDialog(row.codeWait) { sec ->
                row.codeWait = sec
                saveCardSteps(op)
            }
        }
        // 随机点击范围（编辑器不显示，仅在点选悬浮窗内可视化调节；此处保存该行范围）
        row = StepRow(
            typeTv, step.type, remarkEdit,
            xEdit, yEdit, x2Edit, y2Edit, collect, collect2,
            kw1Edit, kw2Edit,
            if (step.delayMin > 0) step.delayMin else 0,
            if (step.delayMax > 0) step.delayMax else 0,
            if (step.range > 0) step.range else 0,
            uid,
            if (step.codeWait > 0) step.codeWait else 0,
            pwdCapsule,
            waitCapsule
        )
        pwdCapsule.text = if (ShizukuConfigStore.hasStepPassword(row.uid)) "密码·已设" else "密码·未设"
        waitCapsule.text = if (row.codeWait > 0) "超时 ${row.codeWait}s" else "超时·默认"
        // 内联文本（备注/坐标/关键字）失焦即保存：改动不依赖离开页面
        val focusSaver = View.OnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveCardSteps(op)
        }
        remarkEdit.onFocusChangeListener = focusSaver
        xEdit.onFocusChangeListener = focusSaver
        yEdit.onFocusChangeListener = focusSaver
        x2Edit.onFocusChangeListener = focusSaver
        y2Edit.onFocusChangeListener = focusSaver
        kw1Edit.onFocusChangeListener = focusSaver
        kw2Edit.onFocusChangeListener = focusSaver
        // 本步执行延迟（秒）：默认第 1 步 5s、其余 3s；第 2 个及以后的验证码输入格默认 1s；0=用默认
        val prevCodeInputs = cardStepRows[op]?.count { it.type == ShizukuStepType.CODE_INPUT } ?: 0
        val defaultDelay = when {
            cardStepRows[op].isNullOrEmpty() -> 5
            row.type == ShizukuStepType.CODE_INPUT && prevCodeInputs > 0 -> 1
            else -> 3
        }
        val delayTv = TextView(this@AdvancedSettingsActivity).apply {
            text = if (row.delayMax > 0) "延迟 ${row.delayMin}-${row.delayMax}s" else "延迟 0-${defaultDelay}s"
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@AdvancedSettingsActivity, R.color.md_primary))
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(ContextCompat.getColor(this@AdvancedSettingsActivity, R.color.md_primaryContainer))
            }
        }
        delayTv.setOnClickListener {
            showDelayRangeDialog(
                if (row.delayMax > 0) row.delayMin else 0,
                if (row.delayMax > 0) row.delayMax else defaultDelay
            ) { min, max ->
                row.delayMin = min
                row.delayMax = max
                delayTv.text = if (max > 0) "延迟 ${min}-${max}s" else "延迟 0-${defaultDelay}s"
                saveCardSteps(op)
            }
        }
        val remove = TextView(this@AdvancedSettingsActivity).apply {
            text = "✕"
            textSize = 16f
            setTextColor(ContextCompat.getColor(this@AdvancedSettingsActivity, R.color.md_error))
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), 0)
        }

        // 卡片头：类型（箭头紧贴）+ 备注小字 + 删除
        val header = LinearLayout(this@AdvancedSettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(typeTv)
            addView(remarkEdit)
            addView(remove)
        }
        // 坐标区（点击/密码/验证码输入）：坐标 + X + Y + 点选
        coordArea = LinearLayout(this@AdvancedSettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val label = TextView(this@AdvancedSettingsActivity).apply {
                text = "坐标"
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@AdvancedSettingsActivity, R.color.md_onSurfaceVariant))
            }
            addView(label)
            addView(xEdit)
            addView(yEdit)
            addView(collect, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(4) })
        }
        // 终点区（SWIPE 专用）：终点坐标 + 终点点选
        endpointArea = LinearLayout(this@AdvancedSettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val label = TextView(this@AdvancedSettingsActivity).apply {
                text = "终点"
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@AdvancedSettingsActivity, R.color.md_onSurfaceVariant))
            }
            addView(label)
            addView(x2Edit)
            addView(y2Edit)
            addView(collect2, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(4) })
        }
        // 关键字区（验证信息采集）：收件人关键字 + 内容关键字，空=内置规则
        keywordArea = LinearLayout(this@AdvancedSettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(kw1Edit)
            addView(kw2Edit)
        }
        // 卡片体：第一行起点/坐标区 + 关键字区 + 延迟（紧凑）；终点区（SWIPE）独立第二行
        val body = LinearLayout(this@AdvancedSettingsActivity).apply {
            orientation = LinearLayout.VERTICAL
            val row1 = LinearLayout(this@AdvancedSettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(coordArea, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ))
                addView(keywordArea, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(delayTv, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = dp(4) })
                addView(pwdCapsule, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = dp(4) })
                addView(waitCapsule, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = dp(4) })
            }
            addView(row1)
            addView(endpointArea, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) })
        }
        // 按当前类型初始化区域可见性
        val needCoord = row.type == ShizukuStepType.CLICK ||
            row.type == ShizukuStepType.SWIPE ||
            row.type == ShizukuStepType.PWD_INPUT ||
            row.type == ShizukuStepType.CODE_INPUT
        coordArea.visibility = if (needCoord) View.VISIBLE else View.GONE
        endpointArea.visibility = if (row.type == ShizukuStepType.SWIPE) View.VISIBLE else View.GONE
        keywordArea.visibility = if (row.type == ShizukuStepType.CODE_CAPTURE) View.VISIBLE else View.GONE
        // 整张卡片
        val card = LinearLayout(this@AdvancedSettingsActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(ContextCompat.getColor(this@AdvancedSettingsActivity, R.color.md_surface))
                setStroke(dp(1), ContextCompat.getColor(this@AdvancedSettingsActivity, R.color.md_outlineVariant))
            }
            addView(header)
            addView(body, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) })
        }
        remove.setOnClickListener {
            container.removeView(card)
            cardStepRows[op]?.removeAll { it.typeTv === typeTv }
            // 清理该步遗留的独立密码
            ShizukuConfigStore.setStepPassword(uid, "")
            refreshStepCapsules(op)
            saveCardSteps(op)
        }
        container.addView(card)
        return row
    }

    /** 步骤编辑器单行持有 */
    private data class StepRow(
        val typeTv: TextView,
        var type: ShizukuStepType,
        val remarkEdit: EditText,
        val xEdit: EditText,
        val yEdit: EditText,
        val x2Edit: EditText,
        val y2Edit: EditText,
        val collect: TextView,
        val collect2: TextView,
        val kw1Edit: EditText,
        val kw2Edit: EditText,
        var delayMin: Int,
        var delayMax: Int,
        var range: Int,
        var uid: String,
        var codeWait: Int,
        val pwdCapsule: TextView,
        val waitCapsule: TextView
    )

    /** 步骤类型 → 下拉索引 */
    private fun typeIndex(type: ShizukuStepType): Int = when (type) {
        ShizukuStepType.CLICK -> 0
        ShizukuStepType.SWIPE -> 1
        ShizukuStepType.PWD_INPUT -> 2
        ShizukuStepType.CODE_INPUT -> 3
        ShizukuStepType.CODE_CAPTURE -> 4
        ShizukuStepType.RESULT_CHECK -> 5
    }

    /**
     * 坐标采集（用户指定交互，简化版）：
     *  1) 打开目标打卡 App 到前台；
     *  2) 屏幕显示「十字光标」定位 + 范围圈可视化；若已有坐标，光标默认定位到该处；
     *  3) 用户拖动寻址、用 [-]/[+] 调整范围（实时可见覆盖按钮大小），点「确认」；
     *  4) 记录中心坐标 + 范围 → 自动回跳本页 → 回填到步骤编辑器。
     */
    private fun startCoordinateCapture(
        initialX: Int = -1,
        initialY: Int = -1,
        initialRange: Int = 0,
        onResult: (Int, Int, Int) -> Unit
    ) {
        pendingCaptureCallback = onResult
        // 打开目标打卡 App
        val pkg = Constant.getTargetApp()
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            setPackage(pkg)
        }
        val info = packageManager.queryIntentActivities(intent, 0).firstOrNull()
        if (info != null) {
            intent.component = ComponentName(info.activityInfo.packageName, info.activityInfo.name)
            runCatching { startActivity(intent) }
        }
        // 十字光标 + 范围圈：确认后记录坐标+范围并自动回跳本页（onResume 回填）
        CoordinateCaptureOverlay.startCapture(
            context = this,
            onConfirm = { x, y, range ->
                pendingCaptureCoord = Triple(x, y, range)
                runCatching {
                    startActivity(Intent(this@AdvancedSettingsActivity, AdvancedSettingsActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    })
                }
            },
            initialX = initialX,
            initialY = initialY,
            initialRange = initialRange,
            onCancel = {
                pendingCaptureCallback = null
            }
        )
    }

    /** 滑动采集（两点模式）：开起始十字 → 确认记起点 → 自动切终点十字 → 再确认 → 回跳回填起点+终点 */
    private fun startSwipeCapture(
        initialSx: Int = -1,
        initialSy: Int = -1,
        onResult: (sx: Int, sy: Int, ex: Int, ey: Int) -> Unit
    ) {
        pendingSwipeCallback = onResult
        // 打开目标打卡 App
        val pkg = Constant.getTargetApp()
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            setPackage(pkg)
        }
        val info = packageManager.queryIntentActivities(intent, 0).firstOrNull()
        if (info != null) {
            intent.component = ComponentName(info.activityInfo.packageName, info.activityInfo.name)
            runCatching { startActivity(intent) }
        }
        // 两点模式：第一次确认记起点、再拖到终点确认 → 自动回跳回填
        CoordinateCaptureOverlay.startCapture(
            context = this,
            onConfirm = { _: Int, _: Int, _: Int -> },
            twoPoint = true,
            onTwoPointConfirm = { sx, sy, ex, ey, _ ->
                pendingSwipeCoord = (sx to sy) to (ex to ey)
                runCatching {
                    startActivity(Intent(this@AdvancedSettingsActivity, AdvancedSettingsActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    })
                }
            },
            initialX = if (initialSx >= 0) initialSx else -1,
            initialY = if (initialSy >= 0) initialSy else -1,
            onCancel = {
                pendingSwipeCallback = null
            }
        )
    }

    private fun refreshRowNumbers(editors: List<EditText>) {
        editors.forEachIndexed { i, e ->
            e.hint = "第 ${i + 1} 步备注（仅标签，不参与定位）"
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}