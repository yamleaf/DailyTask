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
import com.pengxh.daily.app.shizuku.ShizukuLoginMethod
import com.pengxh.daily.app.shizuku.ShizukuManager
import com.pengxh.daily.app.shizuku.ShizukuShell
import com.pengxh.daily.app.shizuku.ShizukuStep
import com.pengxh.daily.app.shizuku.ShizukuStepType
import com.pengxh.daily.app.service.NotificationMonitorService
import com.pengxh.daily.app.utils.Constant
import com.yample.mqttprotocol.dialog.UnifiedDialogKit
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdvancedSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        UiInsets.applyStatusBarPadding(this, binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        Shizuku.addRequestPermissionResultListener(permissionListener)

        binding.btnShizukuAuth.setOnClickListener { ShizukuManager.requestPermission(this) }
        binding.layoutQuickGrant.setOnClickListener { quickGrant() }
        binding.layoutLoginMethod.setOnClickListener { if (editable()) showMethodDialog() }
        binding.layoutPassword.setOnClickListener { if (editable()) showPasswordDialog() }
        binding.layoutPwdSteps.setOnClickListener {
            if (editable()) showStepEditor("密码登录", ShizukuConfigStore.pwdLoginSteps()) {
                ShizukuConfigStore.setPwdLoginSteps(it); refreshConfig()
            }
        }
        binding.layoutVerifySteps.setOnClickListener {
            if (editable()) showStepEditor("验证码登录", ShizukuConfigStore.verifyLoginSteps()) {
                ShizukuConfigStore.setVerifyLoginSteps(it); refreshConfig()
            }
        }
        binding.layoutVerifyWait.setOnClickListener { if (editable()) showWaitDialog(isAuth = false) }
        binding.layoutAuthWait.setOnClickListener { if (editable()) showWaitDialog(isAuth = true) }
        binding.layoutAuthSteps.setOnClickListener {
            if (editable()) showStepEditor("身份验证", ShizukuConfigStore.authSteps()) {
                ShizukuConfigStore.setAuthSteps(it); refreshConfig()
            }
        }
        binding.layoutPunchSteps.setOnClickListener {
            if (editable()) showStepEditor("模拟打卡", ShizukuConfigStore.punchSteps()) {
                ShizukuConfigStore.setPunchSteps(it); refreshConfig()
            }
        }
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
        runCatching { Shizuku.removeRequestPermissionResultListener(permissionListener) }
        super.onDestroy()
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
        binding.cardLogin.alpha = if (on) 1f else 0.45f
        binding.cardAuth.alpha = if (on) 1f else 0.45f
        binding.cardPunch.alpha = if (on) 1f else 0.45f
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

        // 自启动（仅 MIUI/HyperOS 有公开 appops，可尝试自动授权；其余原生视为无需）
        val autostart = isAutostartGranted()
        rows += when (autostart) {
            true -> GrantRow("自启动", "已授权")
            false -> {
                runCatching { ShizukuShell.exec("appops set $pkg AUTO_START allow; echo done") }
                GrantRow("自启动", if (isAutostartGranted() == true) "已通过 adb 授权" else "授权失败，需手动")
            }
            null -> GrantRow("自启动", "原生无需")
        }

        // 通知监听
        if (!ctx.notificationEnable()) {
            runCatching {
                ShizukuShell.exec(
                    "settings put secure enabled_notification_listeners " +
                        "$pkg/com.pengxh.daily.app.service.NotificationMonitorService; echo done"
                )
            }
            delay(400)
            // adb 只写入 secure 设置（已授权）；运行中进程不会自动重绑服务（未连接），
            // 主动 requestRebind 让系统立即绑定 NotificationListenerService
            runCatching {
                NotificationListenerService.requestRebind(
                    ComponentName(ctx, NotificationMonitorService::class.java)
                )
            }
            delay(300)
            rows += GrantRow(
                "通知监听",
                if (ctx.notificationEnable()) "已通过 adb 授权" else "授权失败，需手动"
            )
        } else {
            rows += GrantRow("通知监听", "已授权")
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

    /** 登录配置与身份验证配置可同时编辑（密码登录 / 验证码登录不互斥；步骤分别维护） */
    private fun refreshConfig() {
        val method = ShizukuConfigStore.loginMethod()
        binding.txtLoginMethod.text = if (method == ShizukuLoginMethod.PASSWORD) "密码登录" else "验证码登录"
        binding.txtPasswordStatus.text = if (ShizukuConfigStore.hasPassword()) "已设置" else "未设置"
        binding.txtPwdSteps.text = stepsLabel(ShizukuConfigStore.pwdLoginSteps())
        binding.txtVerifySteps.text = stepsLabel(ShizukuConfigStore.verifyLoginSteps())
        binding.txtVerifyWait.text = "${ShizukuConfigStore.verifyWaitSeconds()} 秒"
        binding.txtAuthWait.text = "${ShizukuConfigStore.authWaitSeconds()} 秒"
        binding.txtAuthSteps.text = stepsLabel(ShizukuConfigStore.authSteps())
        binding.txtPunchSteps.text = stepsLabel(ShizukuConfigStore.punchSteps())
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

    private fun showMethodDialog() {
        val items = listOf("密码登录", "验证码登录")
        val current = if (ShizukuConfigStore.loginMethod() == ShizukuLoginMethod.PASSWORD) 0 else 1
        UnifiedDialogKit.showSingleChoice(this, "登录方式", items, current) { which ->
            ShizukuConfigStore.setLoginMethod(
                if (which == 0) ShizukuLoginMethod.PASSWORD else ShizukuLoginMethod.VERIFY_CODE
            )
            refreshConfig()
        }
    }

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

    private fun showWaitDialog(isAuth: Boolean) {
        val current = if (isAuth) ShizukuConfigStore.authWaitSeconds() else ShizukuConfigStore.verifyWaitSeconds()
        val input = EditText(this).apply {
            hint = "10~600 秒"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(current.toString())
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
            title = "验证码超时",
            message = "等待控制端下发验证码，超时则退出本次操作",
            positiveText = "确定",
            negativeText = "取消",
            onConfirm = {
                val sec = input.text.toString().toIntOrNull() ?: current
                if (isAuth) ShizukuConfigStore.setAuthWaitSeconds(sec) else ShizukuConfigStore.setVerifyWaitSeconds(sec)
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

// 步骤编辑器：全屏（近屏宽）内嵌操作——每步卡片 1-2 行，添加步骤按钮就地插入，不弹窗
    private fun showStepEditor(title: String, current: List<ShizukuStep>, onSave: (List<ShizukuStep>) -> Unit) {
        val typeLabels = arrayOf("点击坐标", "手势滑动", "密码输入", "验证码输入", "验证信息采集", "结果判定")

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(8), dp(4), 0)
        }
        val rows = mutableListOf<StepRow>()

        // 「添加步骤」按钮先声明并加入容器末尾，新步骤都插在它前面（监听在 addRow 定义后绑定）
        val addBtn = TextView(this).apply {
            text = "＋ 添加步骤"
            setTextColor(ContextCompat.getColor(this@AdvancedSettingsActivity, R.color.md_primary))
            setPadding(0, dp(8), 0, dp(4))
        }
        container.addView(addBtn)

        fun addRow(step: ShizukuStep) {
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
            // 随机点击范围（编辑器不显示，仅在点选悬浮窗内可视化调节；此处保存该行范围）
            row = StepRow(
                typeTv, step.type, remarkEdit,
                xEdit, yEdit, x2Edit, y2Edit, collect, collect2,
                kw1Edit, kw2Edit,
                if (step.delayMin > 0) step.delayMin else 0,
                if (step.delayMax > 0) step.delayMax else 0,
                if (step.range > 0) step.range else 0
            )
            // 本步执行延迟（秒）：默认第 1 步 5s、其余 3s；第 2 个及以后的验证码输入格默认 1s；0=用默认
            val prevCodeInputs = rows.count { it.type == ShizukuStepType.CODE_INPUT }
            val defaultDelay = when {
                rows.isEmpty() -> 5
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
                rows.removeAll { it.typeTv === typeTv }
            }
            // 插入到「添加步骤」按钮之前
            if (container.indexOfChild(addBtn) < 0) container.addView(addBtn)
            container.addView(card, container.indexOfChild(addBtn))
            rows.add(row)
        }

        // addRow 定义完毕后再绑定「添加步骤」监听
        addBtn.setOnClickListener { addRow(ShizukuStep()) }

        // 空步骤（未配置）时默认展示内置步骤，便于首次直接微调
        val effective = if (current.isEmpty()) {
            when {
                title == "登录" || title == "密码登录" || title == "验证码登录" -> ShizukuConfigStore.loginSteps()
                title == "模拟打卡" -> ShizukuConfigStore.punchSteps()
                else -> ShizukuConfigStore.authSteps()
            }
        } else current
        effective.forEach { addRow(it) }
        if (current.isEmpty() && effective.isEmpty()) addRow(ShizukuStep())

        // 步骤多时内容超出屏幕：包进 ScrollView 并限制高度（约屏高 75%）
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.75).toInt()
            )
            addView(container)
        }

        UnifiedDialogKit.showForm(
            ctx = this,
            contentView = scroll,
            title = "$title 步骤",
            message = "点击/密码/验证码输入类需点选坐标；滑动类需点选起点与终点；验证信息采集可设收件人/内容关键字（空=内置规则）；验证码多格请逐格添加卡片。",
            positiveText = "保存",
            negativeText = "取消",
            fullWidth = true,
            onConfirm = {
                val steps = rows.mapNotNull { row ->
                    val x = row.xEdit.text.toString().toIntOrNull() ?: -1
                    val y = row.yEdit.text.toString().toIntOrNull() ?: -1
                    val x2 = row.x2Edit.text.toString().toIntOrNull() ?: -1
                    val y2 = row.y2Edit.text.toString().toIntOrNull() ?: -1
                    val remark = row.remarkEdit.text.toString().trim()
                    // 关键字保留原样（含首尾空格）：便于「到 」「发送 」这类带空格精确匹配，避免误识别
                    val kw1 = row.kw1Edit.text.toString()
                    val kw2 = row.kw2Edit.text.toString()
                    val needCoord = row.type == ShizukuStepType.CLICK ||
                        row.type == ShizukuStepType.SWIPE ||
                        row.type == ShizukuStepType.PWD_INPUT ||
                        row.type == ShizukuStepType.CODE_INPUT
                    if (needCoord && (x < 0 || y < 0)) {
                        return@mapNotNull null
                    }
                    if (row.type == ShizukuStepType.SWIPE && (x2 < 0 || y2 < 0)) {
                        return@mapNotNull null
                    }
                    ShizukuStep(row.type, remark, x, y, x2, y2, row.range, row.delayMin, row.delayMax, kw1, kw2)
                }
                if (steps.isEmpty()) {
                    UnifiedDialogKit.showWarning(this, "未保存", "请至少为一步配置有效内容（点击/输入类需点选坐标）")
                    return@showForm false
                }
                onSave(steps)
                true
            }
        )
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
        var range: Int
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