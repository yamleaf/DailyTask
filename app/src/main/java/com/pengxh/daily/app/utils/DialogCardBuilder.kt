package com.pengxh.daily.app.utils

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.pengxh.daily.app.R
import com.yample.mqttprotocol.dialog.UnifiedDialogKit

object DialogCardBuilder {

    enum class NoticeKind { WARN, DANGER }

    /** 标题上方警示图例：参考 UnifiedDialogKit 文字卡片的 tonal 圆形图标 */
    data class IconSpec(
        val drawable: Int,
        val containerTint: Int,
        val iconTint: Int
    )

    data class CardSpec(
        val paragraphs: List<String> = emptyList(),
        val notice: Pair<String, NoticeKind>? = null,
        val suggest: Pair<String, (() -> Unit)?>? = null,
        val icon: IconSpec? = null
    )

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    /** 段落文本块：多段合并为一段连续文字，\n 分割，无背景无卡片，紧凑 */
    private fun paragraphBlock(ctx: Context, texts: List<String>): View {
        val ink = ContextCompat.getColor(ctx, R.color.md_onSurface)
        val sb = StringBuilder()
        for ((i, t) in texts.withIndex()) {
            if (i > 0) sb.append("\n\n")
            sb.append(t)
        }
        return TextView(ctx).apply {
            text = sb.toString()
            textSize = 14f
            setTextColor(ink)
            setLineSpacing(dp(ctx, 2).toFloat(), 1.0f)
        }
    }

    /** 警告/危险卡片：左侧装饰条 + 文字，紧凑 */
    private fun noticeCard(ctx: Context, text: String, kind: NoticeKind): View {
        val (bg, accent, fg) = when (kind) {
            NoticeKind.WARN -> Triple(
                ContextCompat.getColor(ctx, R.color.md_warningContainer),
                ContextCompat.getColor(ctx, R.color.md_warning),
                ContextCompat.getColor(ctx, R.color.md_onWarningContainer)
            )
            NoticeKind.DANGER -> Triple(
                ContextCompat.getColor(ctx, R.color.md_errorContainer),
                ContextCompat.getColor(ctx, R.color.md_error),
                ContextCompat.getColor(ctx, R.color.md_onErrorContainer)
            )
        }
        // 左装饰条：MATCH_PARENT 高度，撑满卡片整高
        val accentBorder = View(ctx).apply {
            setBackgroundColor(accent)
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 4), ViewGroup.LayoutParams.MATCH_PARENT)
        }
        // 文字：靠左显示
        val tv = TextView(ctx).apply {
            this.text = text
            textSize = 13f
            setTextColor(fg)
            setLineSpacing(dp(ctx, 2).toFloat(), 1.0f)
            setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        // 外层卡片：统一圆角 + 背景，左装饰条 + 文字
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(bg)
                cornerRadius = dp(ctx, 10).toFloat()
            }
            addView(accentBorder)
            addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    /** 绿色建议行（可点击跳转） */
    private fun suggestRow(ctx: Context, text: String, onClick: (() -> Unit)?): View {
        val success = ContextCompat.getColor(ctx, R.color.md_success)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            if (onClick != null) {
                val out = android.util.TypedValue()
                ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
                foreground = if (out.resourceId != 0) {
                    ContextCompat.getDrawable(ctx, out.resourceId)
                } else null
                setOnClickListener { onClick() }
            }
        }
        val icon = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_dialog_check)
            imageTintList = ContextCompat.getColorStateList(ctx, R.color.md_tertiary)
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 16), dp(ctx, 16)).apply {
                marginEnd = dp(ctx, 4)
            }
        }
        row.addView(icon)
        val tv = TextView(ctx).apply {
            this.text = text
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(success)
            if (onClick != null) {
                paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            }
        }
        row.addView(tv)
        return row
    }

    /** 按 notice 类型生成默认警示图例（未显式指定时兜底） */
    private fun defaultIconFor(kind: NoticeKind): IconSpec = when (kind) {
        NoticeKind.WARN -> IconSpec(
            R.drawable.ic_dialog_warning,
            R.color.md_warningContainer,
            R.color.md_warning
        )
        NoticeKind.DANGER -> IconSpec(
            R.drawable.ic_dialog_warning,
            R.color.md_errorContainer,
            R.color.md_error
        )
    }

    private fun build(ctx: Context, spec: CardSpec, title: String?): View {
        val container = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val gap = { -> View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 6))
        }}

        val effectiveIcon = spec.icon ?: spec.notice?.let { defaultIconFor(it.second) }
        effectiveIcon?.let {
            val iconContainer = FrameLayout(ctx).apply {
                val size = dp(ctx, 56)
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(ContextCompat.getColor(ctx, it.containerTint))
                }
                addView(ImageView(ctx).apply {
                    setImageResource(it.drawable)
                    imageTintList = ContextCompat.getColorStateList(ctx, it.iconTint)
                    setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 16))
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                })
            }
            container.addView(iconContainer)

            if (!title.isNullOrBlank()) {
                val titleGap = View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 14))
                }
                container.addView(titleGap)
                container.addView(TextView(ctx).apply {
                    text = title
                    textSize = 20f
                    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                    gravity = Gravity.CENTER
                    setTextColor(ContextCompat.getColor(ctx, R.color.md_onSurface))
                })
            }
        }

        if (spec.paragraphs.isNotEmpty()) {
            if (effectiveIcon != null) container.addView(gap())
            container.addView(paragraphBlock(ctx, spec.paragraphs))
        }
        spec.notice?.let {
            if (spec.paragraphs.isNotEmpty()) container.addView(gap())
            container.addView(noticeCard(ctx, it.first, it.second))
        }
        spec.suggest?.let { (t, click) ->
            if (spec.paragraphs.isNotEmpty() || spec.notice != null) container.addView(gap())
            container.addView(suggestRow(ctx, t, click))
        }
        return ScrollView(ctx).apply {
            addView(container)
            setPadding(dp(ctx, 12), dp(ctx, 6), dp(ctx, 12), dp(ctx, 6))
        }
    }

    fun show(
        ctx: Context,
        title: String,
        spec: CardSpec,
        positiveText: String,
        negativeText: String? = "取消",
        cancelable: Boolean = true,
        danger: Boolean = false,
        onConfirm: (() -> Unit)? = null,
        onCancel: (() -> Unit)? = null
    ) {
        val effectiveIcon = spec.icon != null || spec.notice != null
        UnifiedDialogKit.showForm(
            ctx,
            build(ctx, spec, title),
            title = title.takeIf { !effectiveIcon },
            positiveText = positiveText,
            negativeText = negativeText,
            cancelable = cancelable,
            onShow = { _, btnPos, _ ->
                if (danger) {
                    btnPos.backgroundTintList = ContextCompat.getColorStateList(ctx, R.color.md_error)
                    btnPos.setTextColor(ContextCompat.getColor(ctx, R.color.md_onError))
                }
            },
            onCancel = { onCancel?.invoke(); true },
            onConfirm = { onConfirm?.invoke(); true }
        )
    }
}