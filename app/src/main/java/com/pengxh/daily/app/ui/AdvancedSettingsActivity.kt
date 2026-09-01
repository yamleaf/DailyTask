package com.pengxh.daily.app.ui

import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.pengxh.daily.app.R
import com.pengxh.daily.app.UiInsets
import com.pengxh.daily.app.databinding.ActivityAdvancedSettingsBinding
import com.pengxh.daily.app.shizuku.CoordinateCaptureOverlay
import com.pengxh.daily.app.shizuku.ShizukuConfigStore
import com.pengxh.daily.app.shizuku.ShizukuLoginMethod
import com.pengxh.daily.app.shizuku.ShizukuManager
import com.pengxh.daily.app.shizuku.ShizukuStep
import com.pengxh.daily.app.shizuku.ShizukuStepType
import com.pengxh.daily.app.utils.Constant
import com.yample.mqttprotocol.dialog.UnifiedDialogKit
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
 *  - 密码登录 / 验证码登录步骤分开配置；未配置自动使用内置默认（按目标 App）。
 */
class AdvancedSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdvancedSettingsBinding

    // 坐标采集（可拖动十字光标 + 范围可视化 + 自动回跳回填）挂起状态
    private var pendingCaptureCallback: ((Int, Int, Int) -> Unit)? = null
    private var pendingCaptureCoord: Triple<Int, Int, Int>? = null

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
    }

    override fun onDestroy() {
        CoordinateCaptureOverlay.dismiss()
        runCatching { Shizuku.removeRequestPermissionResultListener(permissionListener) }
        super.onDestroy()
    }

    private fun editable(): Boolean = ShizukuManager.isGranted()

    /** 刷新 Shizuku 服务胶囊（运行时状态）+ 配置区可编辑性 */
    private fun refreshShizukuState() {
        val available = ShizukuManager.isAvailable()
        val granted = ShizukuManager.isGranted()
        binding.txtShizukuStatus.text = "服务：${if (available) "可用" else "不可用"}"
        binding.txtShizukuStatus.setTextColor(statusColor(available))
        binding.txtShizukuAuth.text = "授权：${if (granted) "已授权" else "未授权"}"
        binding.txtShizukuAuth.setTextColor(statusColor(granted))
        binding.btnShizukuAuth.visibility = if (available && !granted) View.VISIBLE else View.GONE
        // 高级功能：Shizuku 已授权即生效，配置区始终随授权状态可编辑
        val on = ShizukuManager.isGranted()
        binding.cardLogin.alpha = if (on) 1f else 0.45f
        binding.cardAuth.alpha = if (on) 1f else 0.45f
        binding.cardPunch.alpha = if (on) 1f else 0.45f
        refreshConfig()
    }

    private fun statusColor(ok: Boolean): Int = if (ok) {
        ContextCompat.getColor(this, R.color.md_success)
    } else {
        ContextCompat.getColor(this, R.color.md_warning)
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
    private fun showDelaySlider(current: Int, onResult: (Int) -> Unit) {
        val valueTv = TextView(this).apply {
            text = "${current} 秒"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@AdvancedSettingsActivity, R.color.md_primary))
        }
        val seek = SeekBar(this).apply {
            max = 29
            progress = (current - 1).coerceIn(0, 29)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    valueTv.text = "${p + 1} 秒"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(8), dp(4), 0)
            addView(valueTv)
            addView(seek, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) })
        }
        UnifiedDialogKit.showForm(
            ctx = this,
            contentView = box,
            title = "本步执行延迟",
            message = "到本步后先等待该秒数再执行（1~30 秒）",
            positiveText = "确定",
            negativeText = "取消",
            onConfirm = {
                onResult(seek.progress + 1)
                true
            }
        )
    }

/** 步骤编辑器：每步一张卡片，卡片头为「类型标签」+ 删除，卡片内为「坐标 + 点选」。
     *  新步骤插入在「添加步骤」按钮之前；执行器全坐标点选。
     */
    private fun showStepEditor(title: String, current: List<ShizukuStep>, onSave: (List<ShizukuStep>) -> Unit) {
        val typeLabels = arrayOf("点击坐标", "密码输入", "验证码输入", "验证信息采集", "结果判定")

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
                    // 坐标区仅点击/密码/验证码输入需要；关键字区仅验证信息采集需要；结果判定两者皆无
                    val needCoord = row.type == ShizukuStepType.CLICK ||
                        row.type == ShizukuStepType.PWD_INPUT ||
                        row.type == ShizukuStepType.CODE_INPUT
                    coordArea.visibility = if (needCoord) View.VISIBLE else View.GONE
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
                typeTv, step.type, remarkEdit, xEdit, yEdit, kw1Edit, kw2Edit,
                if (step.delay > 0) step.delay else 0,
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
                text = "延迟 ${if (row.delay > 0) row.delay else defaultDelay}s"
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@AdvancedSettingsActivity, R.color.md_primary))
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(6), dp(12), dp(6))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(ContextCompat.getColor(this@AdvancedSettingsActivity, R.color.md_primaryContainer))
                }
            }
            delayTv.setOnClickListener { showDelaySlider(if (row.delay > 0) row.delay else defaultDelay) { row.delay = it; delayTv.text = "延迟 ${row.delay}s" } }
            // 点选按钮（文字胶囊）：打开打卡App → 十字光标点选 + 范围可视化 → 回填 X/Y/范围（已有坐标默认定位）
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
            // 关键字区（验证信息采集）：收件人关键字 + 内容关键字，空=内置规则
            keywordArea = LinearLayout(this@AdvancedSettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(kw1Edit)
                addView(kw2Edit)
            }
            // 卡片体：坐标区 | 关键字区 + 延迟（按类型切换可见性）
            val body = LinearLayout(this@AdvancedSettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(coordArea, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(keywordArea, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(delayTv, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = dp(4) })
            }
            // 按当前类型初始化区域可见性
            val needCoord = row.type == ShizukuStepType.CLICK ||
                row.type == ShizukuStepType.PWD_INPUT ||
                row.type == ShizukuStepType.CODE_INPUT
            coordArea.visibility = if (needCoord) View.VISIBLE else View.GONE
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

        UnifiedDialogKit.showForm(
            ctx = this,
            contentView = container,
            title = "$title 步骤",
            message = "点击/密码/验证码输入类需点选坐标；验证信息采集可设收件人/内容关键字（空=内置规则）；验证码多格请逐格添加卡片。",
            positiveText = "保存",
            negativeText = "取消",
            onConfirm = {
                val steps = rows.mapNotNull { row ->
                    val x = row.xEdit.text.toString().toIntOrNull() ?: -1
                    val y = row.yEdit.text.toString().toIntOrNull() ?: -1
                    val remark = row.remarkEdit.text.toString().trim()
                    // 关键字保留原样（含首尾空格）：便于「到 」「发送 」这类带空格精确匹配，避免误识别
                    val kw1 = row.kw1Edit.text.toString()
                    val kw2 = row.kw2Edit.text.toString()
                    val needCoord = row.type == ShizukuStepType.CLICK ||
                        row.type == ShizukuStepType.PWD_INPUT ||
                        row.type == ShizukuStepType.CODE_INPUT
                    if (needCoord && (x < 0 || y < 0)) {
                        return@mapNotNull null
                    }
                    ShizukuStep(row.type, remark, x, y, row.range, row.delay, kw1, kw2)
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
        val kw1Edit: EditText,
        val kw2Edit: EditText,
        var delay: Int,
        var range: Int
    )

    /** 步骤类型 → 下拉索引 */
    private fun typeIndex(type: ShizukuStepType): Int = when (type) {
        ShizukuStepType.CLICK -> 0
        ShizukuStepType.PWD_INPUT -> 1
        ShizukuStepType.CODE_INPUT -> 2
        ShizukuStepType.CODE_CAPTURE -> 3
        ShizukuStepType.RESULT_CHECK -> 4
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

    private fun refreshRowNumbers(editors: List<EditText>) {
        editors.forEachIndexed { i, e ->
            e.hint = "第 ${i + 1} 步按钮文字"
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}