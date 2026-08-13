package com.pengxh.daily.app.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.Gravity
import androidx.appcompat.widget.AppCompatTextView

/**
 * 空闲悬浮状态球：七彩圆 + 短文案。
 * 透明度约 30%（填充不透明度约 70%）；描边更实，保证 8dp 贴边色条可辨。
 */
class IdleStatusBallView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
    }
    private var sweepDegrees = 0f
    private var fillShader: SweepGradient? = null
    private var ringShader: SweepGradient? = null
    private var lastSize = 0

    init {
        gravity = Gravity.CENTER
        // 深色字 + 浅色描边式阴影，在七彩底上保持对比度
        setTextColor(Color.argb(245, 20, 24, 36))
        textSize = 8.5f
        includeFontPadding = false
        maxLines = 1
        paint.isFakeBoldText = true
        setShadowLayer(3.2f, 0f, 0.5f, Color.argb(220, 255, 255, 255))
        setBackgroundColor(Color.TRANSPARENT)
        alpha = 1f
        setPadding(0, 0, 0, 0)
    }

    fun setSweepDegrees(degrees: Float) {
        sweepDegrees = degrees % 360f
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && w != lastSize) {
            lastSize = w
            rebuildShaders(w / 2f, h / 2f)
        }
    }

    private fun rebuildShaders(cx: Float, cy: Float) {
        // 填充 ≈70% 不透明 → 透明度约 30%
        fillShader = SweepGradient(
            cx, cy,
            intArrayOf(
                Color.argb(178, 255, 80, 80),
                Color.argb(178, 255, 170, 50),
                Color.argb(178, 80, 210, 110),
                Color.argb(178, 60, 170, 255),
                Color.argb(178, 160, 100, 255),
                Color.argb(178, 255, 80, 190),
                Color.argb(178, 255, 80, 80)
            ),
            null
        )
        // 描边接近不透明，贴边色条主要靠它
        ringShader = SweepGradient(
            cx, cy,
            intArrayOf(
                Color.argb(255, 255, 120, 120),
                Color.argb(255, 255, 200, 90),
                Color.argb(255, 110, 230, 150),
                Color.argb(255, 100, 200, 255),
                Color.argb(255, 190, 140, 255),
                Color.argb(255, 255, 120, 210),
                Color.argb(255, 255, 120, 120)
            ),
            null
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) {
            super.onDraw(canvas)
            return
        }
        val cx = w / 2f
        val cy = h / 2f
        if (fillShader == null) rebuildShaders(cx, cy)
        val radius = minOf(cx, cy) - 1f
        canvas.save()
        canvas.rotate(sweepDegrees, cx, cy)
        fillPaint.shader = fillShader
        canvas.drawCircle(cx, cy, radius, fillPaint)
        ringPaint.shader = ringShader
        canvas.drawCircle(cx, cy, (radius - ringPaint.strokeWidth * 0.35f).coerceAtLeast(1f), ringPaint)
        canvas.restore()
        fillPaint.shader = null
        ringPaint.shader = null
        super.onDraw(canvas)
    }
}
