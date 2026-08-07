package com.pengxh.daily.app.utils

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pengxh.daily.app.R

/**
 * 统一对话框工具：卡片式图标 + 标题 + 内容 + 底部按钮组（均分）。
 * 样式统一走 ThemeOverlay.DailyTask.UnifiedDialog。
 */
object UnifiedDialogKit {

    enum class IconType { SUCCESS, WARNING, PERMISSION, INFO }

    private class IconSpec(val drawable: Int, val containerTint: Int, val iconTint: Int)

    private fun specFor(type: IconType): IconSpec = when (type) {
        IconType.SUCCESS -> IconSpec(R.drawable.ic_dialog_check, R.color.md_tertiaryContainer, R.color.md_tertiary)
        IconType.WARNING -> IconSpec(R.drawable.ic_dialog_warning, R.color.md_errorContainer, R.color.md_error)
        IconType.PERMISSION -> IconSpec(R.drawable.ic_dialog_permission, R.color.md_primaryContainer, R.color.md_primary)
        IconType.INFO -> IconSpec(R.drawable.ic_dialog_info, R.color.md_primaryContainer, R.color.md_primary)
    }

    fun builder(ctx: Context): MaterialAlertDialogBuilder =
        MaterialAlertDialogBuilder(ctx, R.style.ThemeOverlay_DailyTask_UnifiedDialog)

    private fun buildContent(ctx: Context, type: IconType, title: String, message: String): View {
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_unified_content, null)
        val spec = specFor(type)
        view.findViewById<android.widget.ImageView>(R.id.ivDialogIcon).apply {
            backgroundTintList = ContextCompat.getColorStateList(ctx, spec.containerTint)
            setImageResource(spec.drawable)
            imageTintList = ContextCompat.getColorStateList(ctx, spec.iconTint)
        }
        view.findViewById<TextView>(R.id.tvDialogTitle).text = title
        view.findViewById<TextView>(R.id.tvDialogMessage).text = message
        return view
    }

    private fun configureButtons(
        dialog: AlertDialog, ctx: Context, view: View,
        positiveText: String, negativeText: String?, isDestructive: Boolean = false,
        onPositive: (() -> Unit)? = null, onNegative: (() -> Unit)? = null
    ) {
        val btnBar = view.findViewById<LinearLayout>(R.id.btnBar)
        val btnPositive = view.findViewById<Button>(R.id.btnPositive)
        val btnNegative = view.findViewById<Button>(R.id.btnNegative)
        btnPositive.text = positiveText
        btnPositive.visibility = View.VISIBLE
        btnPositive.setOnClickListener {
            onPositive?.invoke()
            dialog.dismiss()
        }
        if (isDestructive) {
            btnPositive.backgroundTintList = ContextCompat.getColorStateList(ctx, R.color.md_error)
            btnPositive.setTextColor(ContextCompat.getColor(ctx, R.color.md_onError))
        }
        if (negativeText == null) {
            (btnPositive.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                lp.width = LinearLayout.LayoutParams.WRAP_CONTENT
                lp.weight = 0f
                lp.marginStart = 0
            }
            btnBar.gravity = android.view.Gravity.CENTER
        } else {
            btnNegative.text = negativeText
            btnNegative.visibility = View.VISIBLE
            btnNegative.setOnClickListener {
                onNegative?.invoke()
                dialog.dismiss()
            }
            btnBar.gravity = android.view.Gravity.CENTER
        }
    }

    private fun createDialog(
        ctx: Context, type: IconType, title: String, message: String,
        positiveText: String, negativeText: String?, isDestructive: Boolean, cancelable: Boolean,
        onPositive: (() -> Unit)?, onNegative: (() -> Unit)?
    ): AlertDialog {
        val view = buildContent(ctx, type, title, message)
        val dialog = MaterialAlertDialogBuilder(ctx, R.style.ThemeOverlay_DailyTask_UnifiedDialog)
            .setView(view)
            .create()
        dialog.setCancelable(cancelable)
        configureButtons(dialog, ctx, view, positiveText, negativeText, isDestructive, onPositive, onNegative)
        dialog.show()
        return dialog
    }

    fun showInfo(ctx: Context, title: String, message: String, buttonText: String = "知道了", cancelable: Boolean = true): AlertDialog =
        createDialog(ctx, IconType.INFO, title, message, buttonText, null, false, cancelable, null, null)

    fun showSuccess(
        ctx: Context, title: String, message: String,
        confirmText: String = ctx.getString(android.R.string.ok),
        cancelText: String? = ctx.getString(android.R.string.cancel),
        cancelable: Boolean = true,
        onConfirm: (() -> Unit)? = null,
        onCancel: (() -> Unit)? = null
    ): AlertDialog =
        createDialog(ctx, IconType.SUCCESS, title, message, confirmText, cancelText, false, cancelable, onConfirm, onCancel)

    fun showWarning(
        ctx: Context, title: String, message: String,
        confirmText: String = "删除", cancelable: Boolean = true,
        onConfirm: (() -> Unit)? = null, onCancel: (() -> Unit)? = null
    ): AlertDialog =
        createDialog(ctx, IconType.WARNING, title, message, confirmText, ctx.getString(android.R.string.cancel), true, cancelable, onConfirm, onCancel)

    fun showPermission(
        ctx: Context, title: String, message: String,
        grantText: String = "允许", denyText: String = "拒绝", cancelable: Boolean = true,
        onGrant: (() -> Unit)? = null
    ): AlertDialog =
        createDialog(ctx, IconType.PERMISSION, title, message, grantText, denyText, false, cancelable, onGrant, null)

    /**
     * 表单类对话框：标题/消息 + 自定义内容视图（contentHost）+ 底部按钮组。
     * onShow: (dialog, positiveBtn, negativeBtn) -> Unit 可自定义按钮行为；
     * onPositive/onNegative 返回 false 时对话框不关闭。
     */
    fun showForm(
        ctx: Context, contentView: View,
        title: String?, message: String?,
        positiveText: String, negativeText: String?,
        cancelable: Boolean = true,
        onShow: ((AlertDialog, Button, Button) -> Unit)? = null,
        onPositive: ((AlertDialog) -> Boolean)? = null,
        onNegative: ((AlertDialog) -> Boolean)? = null
    ): AlertDialog {
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_unified_form, null)
        val tvTitle = view.findViewById<TextView>(R.id.tvDialogTitle)
        val titleGap = view.findViewById<View>(R.id.titleGap)
        if (!title.isNullOrBlank()) {
            tvTitle.text = title
            tvTitle.visibility = View.VISIBLE
            titleGap.visibility = View.VISIBLE
        }
        val tvMessage = view.findViewById<TextView>(R.id.tvDialogMessage)
        val messageGap = view.findViewById<View>(R.id.messageGap)
        if (!message.isNullOrBlank()) {
            tvMessage.text = message
            tvMessage.visibility = View.VISIBLE
            messageGap.visibility = View.VISIBLE
        }
        view.findViewById<FrameLayout>(R.id.contentHost).addView(
            contentView,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        val dialog = MaterialAlertDialogBuilder(ctx, R.style.ThemeOverlay_DailyTask_UnifiedDialog)
            .setView(view)
            .create()
        val btnBar = view.findViewById<LinearLayout>(R.id.btnBar)
        val btnPositive = view.findViewById<Button>(R.id.btnPositive)
        val btnNegative = view.findViewById<Button>(R.id.btnNegative)
        btnPositive.text = positiveText
        btnPositive.visibility = View.VISIBLE
        btnPositive.setOnClickListener {
            if (onPositive?.invoke(dialog) != false) dialog.dismiss()
        }
        if (negativeText == null) {
            (btnPositive.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                lp.width = LinearLayout.LayoutParams.WRAP_CONTENT
                lp.weight = 0f
            }
            btnBar.gravity = android.view.Gravity.CENTER
        } else {
            btnNegative.text = negativeText
            btnNegative.visibility = View.VISIBLE
            btnNegative.setOnClickListener {
                if (onNegative?.invoke(dialog) != false) dialog.dismiss()
            }
            btnBar.gravity = android.view.Gravity.CENTER
        }
        dialog.setCancelable(cancelable)
        dialog.setOnShowListener {
            onShow?.invoke(dialog, btnPositive, btnNegative)
        }
        dialog.show()
        return dialog
    }
}
