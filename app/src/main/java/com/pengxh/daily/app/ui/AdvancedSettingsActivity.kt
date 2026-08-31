package com.pengxh.daily.app.ui

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.pengxh.daily.app.R
import com.pengxh.daily.app.databinding.ActivityAdvancedSettingsBinding
import com.pengxh.daily.app.shizuku.ShizukuConfigStore
import com.pengxh.daily.app.shizuku.ShizukuLoginMethod
import com.pengxh.daily.app.shizuku.ShizukuManager
import com.pengxh.daily.app.shizuku.ShizukuStep
import rikka.shizuku.Shizuku

/**
 * 高级设置（feat_shiziku）：Shizuku 服务卡片 + 登录选项 + 身份验证配置。
 * 独立 Activity，设置页仅一个入口跳转；Shizuku 全部逻辑收口在此，不触碰现有功能。
 *
 * 安全约定：密码仅在「密码缓存」对话框（设置场景）可显隐，其他任何界面不展示明文。
 */
class AdvancedSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdvancedSettingsBinding

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, _ ->
        refreshShizukuState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdvancedSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Shizuku.addRequestPermissionResultListener(permissionListener)

        binding.btnShizukuAuth.setOnClickListener { ShizukuManager.requestPermission(this) }
        binding.swAdvancedFeature.setOnCheckedChangeListener { _, checked ->
            ShizukuConfigStore.setEnabled(checked)
            refreshShizukuState()
        }
        binding.layoutLoginMethod.setOnClickListener { if (editable()) showMethodDialog() }
        binding.layoutPassword.setOnClickListener { if (editable()) showPasswordDialog() }
        binding.layoutVerifyWait.setOnClickListener { if (editable()) showWaitDialog(isAuth = false) }
        binding.layoutLoginSteps.setOnClickListener {
            if (editable()) showStepEditor("登录", ShizukuConfigStore.loginSteps()) {
                ShizukuConfigStore.setLoginSteps(it); refreshConfig()
            }
        }
        binding.layoutAuthWait.setOnClickListener { if (editable()) showWaitDialog(isAuth = true) }
        binding.layoutAuthSteps.setOnClickListener {
            if (editable()) showStepEditor("身份验证", ShizukuConfigStore.authSteps()) {
                ShizukuConfigStore.setAuthSteps(it); refreshConfig()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshShizukuState()
    }

    override fun onDestroy() {
        runCatching { Shizuku.removeRequestPermissionResultListener(permissionListener) }
        super.onDestroy()
    }

    private fun editable(): Boolean = ShizukuConfigStore.isEnabled()

    /** 刷新 Shizuku 服务卡片（运行时状态）+ 配置区可编辑性 */
    private fun refreshShizukuState() {
        val available = ShizukuManager.isAvailable()
        val granted = ShizukuManager.isGranted()
        binding.txtShizukuStatus.text = if (available) "可用" else "不可用"
        binding.txtShizukuStatus.setTextColor(statusColor(available))
        binding.txtShizukuAuth.text = if (granted) "已授权" else "未授权"
        binding.txtShizukuAuth.setTextColor(statusColor(granted))
        binding.btnShizukuAuth.visibility = if (available && !granted) View.VISIBLE else View.GONE
        // 高级功能：仅 Shizuku 可用且已授权时可切换
        binding.swAdvancedFeature.isEnabled = available && granted
        binding.swAdvancedFeature.isChecked = ShizukuConfigStore.isEnabled()

        val on = ShizukuConfigStore.isEnabled()
        binding.cardLogin.alpha = if (on) 1f else 0.45f
        binding.cardAuth.alpha = if (on) 1f else 0.45f
        refreshConfig()
    }

    private fun statusColor(ok: Boolean): Int = if (ok) {
        ContextCompat.getColor(this, R.color.md_success)
    } else {
        ContextCompat.getColor(this, R.color.md_warning)
    }

    private fun refreshConfig() {
        val method = ShizukuConfigStore.loginMethod()
        binding.txtLoginMethod.text = if (method == ShizukuLoginMethod.PASSWORD) "密码登录" else "验证码登录"
        val showPwd = method == ShizukuLoginMethod.PASSWORD
        binding.layoutPassword.visibility = if (showPwd) View.VISIBLE else View.GONE
        binding.dividerPassword.visibility = if (showPwd) View.VISIBLE else View.GONE
        binding.layoutVerifyWait.visibility = if (showPwd) View.GONE else View.VISIBLE
        binding.dividerVerifyWait.visibility = if (showPwd) View.GONE else View.VISIBLE
        binding.txtPasswordStatus.text = if (ShizukuConfigStore.hasPassword()) "已设置" else "未设置"
        binding.txtVerifyWait.text = "${ShizukuConfigStore.verifyWaitSeconds()} 秒"
        binding.txtAuthWait.text = "${ShizukuConfigStore.authWaitSeconds()} 秒"
        binding.txtLoginSteps.text = stepsLabel(ShizukuConfigStore.loginSteps())
        binding.txtAuthSteps.text = stepsLabel(ShizukuConfigStore.authSteps())
    }

    private fun stepsLabel(steps: List<ShizukuStep>): String =
        if (steps.isEmpty()) "未配置" else steps.joinToString(" → ") { it.buttonText }

    // ═══════ 对话框 ═══════

    private fun showMethodDialog() {
        val methods = arrayOf("密码登录", "验证码登录")
        val current = if (ShizukuConfigStore.loginMethod() == ShizukuLoginMethod.PASSWORD) 0 else 1
        AlertDialog.Builder(this)
            .setTitle("登录方式")
            .setSingleChoiceItems(methods, current) { d, which ->
                ShizukuConfigStore.setLoginMethod(
                    if (which == 0) ShizukuLoginMethod.PASSWORD else ShizukuLoginMethod.VERIFY_CODE
                )
                refreshConfig()
                d.dismiss()
            }
            .show()
    }

    /** 密码设置：仅此处可显隐；确认后密文经 SecurePrefs（Keystore）保存 */
    private fun showPasswordDialog() {
        val input = EditText(this).apply {
            hint = "请输入登录密码"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(ShizukuConfigStore.password())
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val toggle = CheckBox(this).apply {
            text = "显示密码"
            setOnCheckedChangeListener { _, checked ->
                input.inputType = if (checked) {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                } else {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                input.setSelection(input.text.length)
            }
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
            addView(toggle)
        }
        AlertDialog.Builder(this)
            .setTitle("密码缓存")
            .setMessage("密码仅存于本机加密存储，仅在设置界面可查看")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                ShizukuConfigStore.setPassword(input.text.toString())
                refreshConfig()
            }
            .setNegativeButton("清除") { _, _ ->
                ShizukuConfigStore.setPassword("")
                refreshConfig()
            }
            .setNeutralButton("取消", null)
            .show()
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
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("验证码等待时间")
            .setMessage("等待控制端下发验证码，超时则退出本次操作")
            .setView(box)
            .setPositiveButton("确定") { _, _ ->
                val sec = input.text.toString().toIntOrNull() ?: current
                if (isAuth) ShizukuConfigStore.setAuthWaitSeconds(sec) else ShizukuConfigStore.setVerifyWaitSeconds(sec)
                refreshConfig()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 步骤编辑器：每步一行「序号 + 按钮文字」，支持增删；按钮文字用于 uiautomator 识别 */
    private fun showStepEditor(title: String, current: List<ShizukuStep>, onSave: (List<ShizukuStep>) -> Unit) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val editors = mutableListOf<EditText>()

        fun addRow(step: ShizukuStep) {
            val idx = editors.size + 1
            val edit = EditText(this@AdvancedSettingsActivity).apply {
                hint = "第 $idx 步按钮文字"
                setText(step.buttonText)
                setPadding(dp(12), dp(6), dp(12), dp(6))
            }
            val remove = TextView(this@AdvancedSettingsActivity).apply {
                text = "✕"
                textSize = 16f
                setTextColor(ContextCompat.getColor(this@AdvancedSettingsActivity, R.color.md_error))
                gravity = Gravity.CENTER
                setPadding(dp(8), 0, dp(8), 0)
                setOnClickListener {
                    val pos = editors.indexOf(edit)
                    if (pos >= 0) {
                        editors.removeAt(pos)
                        container.removeView(edit.parent as? View ?: return@setOnClickListener)
                        refreshRowNumbers(container, editors)
                    }
                }
            }
            val row = LinearLayout(this@AdvancedSettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(edit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(remove)
            }
            editors.add(edit)
            container.addView(row)
        }

        current.forEach { addRow(it) }

        val addBtn = TextView(this).apply {
            text = "＋ 添加步骤"
            setTextColor(ContextCompat.getColor(this@AdvancedSettingsActivity, R.color.md_primary))
            setPadding(0, dp(10), 0, dp(10))
            setOnClickListener { addRow(ShizukuStep("")) }
        }
        container.addView(addBtn)

        AlertDialog.Builder(this)
            .setTitle("$title 步骤")
            .setMessage("按从上到下依次点击；每步填按钮上显示的文字")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val steps = editors.mapNotNull { it.text.toString().trim().takeIf(String::isNotBlank) }
                    .map { ShizukuStep(it) }
                onSave(steps)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun refreshRowNumbers(container: LinearLayout, editors: List<EditText>) {
        editors.forEachIndexed { i, e ->
            e.hint = "第 ${i + 1} 步按钮文字"
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
