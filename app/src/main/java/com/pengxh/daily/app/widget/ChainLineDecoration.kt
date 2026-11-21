package com.pengxh.daily.app.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.PowerManager
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import com.pengxh.daily.app.R
import com.pengxh.daily.app.utils.MaskOverlayHelper

/**
 * 链式任务列表的连线：从上一个节点的中心，一直画到下一个节点的中心。
 *
 * 借鉴 master 分支的 ChainLineDecoration，轨道线颜色改用主题描边色，
 * 与 alpha 现有的 md 色系保持一致。
 *
 * 连线状态化：相邻两节点都已过点（时间已走过的轨迹）时，线段用琥珀色；
 * 其余用描边灰。状态由 Adapter 在 bind 时写入 itemView 的 keyed tag（[TAG_PASSED]）。
 *
 * 流动脉冲：当存在 2 个及以上节点时，一个亮色光点沿连线从第一个节点依次流向下一个，
 * 循环往复，形成「能量沿时间轴流动」的视觉。动画在首次 onDraw 时惰性启动。
 * */
class ChainLineDecoration(context: Context) : RecyclerView.ItemDecoration() {

    private val appContext = context.applicationContext

    private val density = context.resources.displayMetrics.density

    /** 缓存 PowerManager，避免每帧 getSystemService 开销导致掉帧 */
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private val pulseColor = Color.parseColor("#40C4FF")

    /**
     * 轨道列中心 x 偏移（相对 item 左边缘）
     * */
    private val trackOffset = context.resources.getDimension(R.dimen.chain_track_width) / 2

    /**
     * 节点中心 y 偏移（相对 item 顶部）
     * */
    private val dotCenter = context.resources.getDimension(R.dimen.chain_dot_center)

    private val normalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 1 * density
        color = ContextCompat.getColor(context, R.color.md_outlineVariant)
    }

    private val passedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 1 * density
        color = ContextCompat.getColor(context, R.color.md_warning)
    }

    // 流动脉冲：多层光晕（模拟发光）+ 白色高光核心 + 渐隐彗尾
    private val glowOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ColorUtils.setAlphaComponent(pulseColor, 0x18)
    }

    private val glowMidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ColorUtils.setAlphaComponent(pulseColor, 0x38)
    }

    private val glowInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ColorUtils.setAlphaComponent(pulseColor, 0x66)
    }

    private val flowCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = pulseColor
    }

    /** 节点点亮联动：光点靠近节点时，在节点位置画一圈增亮光晕（alpha 由距离动态控制） */
    private val nodeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = pulseColor
    }

    private var host: RecyclerView? = null

    /** 流动进度 0→1：0 = 第一个节点，1 = 最后一个节点，循环 */
    private var flowProgress = 0f

    private val flowAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1800
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { anim ->
            // 息屏 / 伪息屏时自停省电；亮屏后由 onDraw 重新拉起
            if (!isScreenActuallyOn()) {
                anim.cancel()
                return@addUpdateListener
            }
            flowProgress = anim.animatedValue as Float
            // 用 postInvalidateOnAnimation（仅重绘）而非 invalidateItemDecorations（内部 requestLayout），
            // 避免每帧重新布局导致掉帧
            host?.postInvalidateOnAnimation()
        }
    }

    /** 真正亮屏可见：物理屏幕交互中 且 无伪息屏黑蒙层覆盖 */
    private fun isScreenActuallyOn(): Boolean {
        val interactive = powerManager?.isInteractive ?: true
        return interactive && !MaskOverlayHelper.isShowing()
    }

    override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        if (host !== parent) host = parent

        // 亮屏时确保流动动画运行（息屏/伪息屏时动画已在 updateListener 里自停，此处不重拉）
        val screenOn = isScreenActuallyOn()
        if (screenOn && !flowAnimator.isStarted) {
            flowAnimator.start()
        }

        val count = parent.childCount
        if (count < 2) return

        for (i in 0 until count - 1) {
            val child = parent.getChildAt(i)
            val next = parent.getChildAt(i + 1)
            val pos = parent.getChildAdapterPosition(child)
            val nextPos = parent.getChildAdapterPosition(next)
            if (pos == RecyclerView.NO_POSITION || nextPos == RecyclerView.NO_POSITION) continue
            if (nextPos != pos + 1) continue // 相邻两屏内 item 不连续时跳过

            val childPassed = child.getTag(TAG_PASSED) as? Boolean ?: false
            val nextPassed = next.getTag(TAG_PASSED) as? Boolean ?: false
            val paint = if (childPassed && nextPassed) passedPaint else normalPaint

            val x = child.left + trackOffset
            canvas.drawLine(x, child.top + dotCenter, x, next.top + dotCenter, paint)
        }

        if (screenOn) {
            drawFlowPulse(canvas, parent)
        }
    }

    /**
     * 沿连线绘制流动光点：先收集可见区内的相邻线段，按总长度把 [flowProgress] 映射到
     * 具体 y 位置，画「光晕 + 高光核心」两层，形成能量流动效果。
     * */
    private fun drawFlowPulse(canvas: Canvas, parent: RecyclerView) {
        val count = parent.childCount
        if (count < 2) return

        // 收集可见线段（相邻连续 item 的节点连线）：(startY, endY)
        val segments = ArrayList<FloatArray>(count - 1)
        var totalLen = 0f
        for (i in 0 until count - 1) {
            val child = parent.getChildAt(i)
            val next = parent.getChildAt(i + 1)
            val pos = parent.getChildAdapterPosition(child)
            val nextPos = parent.getChildAdapterPosition(next)
            if (pos == RecyclerView.NO_POSITION || nextPos == RecyclerView.NO_POSITION) continue
            if (nextPos != pos + 1) continue

            val startY = child.top + dotCenter
            val endY = next.top + dotCenter
            if (endY <= startY) continue
            segments.add(floatArrayOf(startY, endY))
            totalLen += endY - startY
        }
        if (segments.isEmpty() || totalLen <= 0f) return

        val target = flowProgress * totalLen
        var acc = 0f
        var pulseY = segments[0][0]
        for (seg in segments) {
            val segLen = seg[1] - seg[0]
            if (target <= acc + segLen) {
                val frac = if (segLen > 0f) (target - acc) / segLen else 0f
                pulseY = seg[0] + frac * segLen
                break
            }
            acc += segLen
        }

        val x = parent.getChildAt(0).left + trackOffset

        // 渐隐彗尾：光点运动反方向（上方）画一串透明度/半径递减的圆点，形成「流水」拖尾
        val trailSteps = 14
        val trailLen = 44f * density
        val firstY = segments[0][0]
        for (s in 1..trailSteps) {
            val frac = s.toFloat() / trailSteps
            val back = trailLen * frac
            val y = pulseY - back
            if (y < firstY - 4f * density) break
            val alpha = (1f - frac).let { it * it * 0.5f }
            trailPaint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
            val r = 1.3f * density * (1f - frac * 0.4f)
            canvas.drawCircle(x, y, r, trailPaint)
        }
        // 光点：多层光晕（缩小，避免盖住连线）+ 白色高光核心
        canvas.drawCircle(x, pulseY, 4.5f * density, glowOuterPaint)
        canvas.drawCircle(x, pulseY, 3f * density, glowMidPaint)
        canvas.drawCircle(x, pulseY, 2f * density, glowInnerPaint)
        canvas.drawCircle(x, pulseY, 1.3f * density, flowCorePaint)

        // 节点点亮联动：光点经过节点时，节点短暂增亮（光晕随距离衰减）
        val nodeGlowRange = 26f * density
        for (i in 0 until count) {
            val child = parent.getChildAt(i)
            val nodeY = child.top + dotCenter
            val dist = kotlin.math.abs(nodeY - pulseY)
            if (dist < nodeGlowRange) {
                val intensity = 1f - dist / nodeGlowRange
                val alpha = (intensity * intensity * 0x66).toInt()
                nodeGlowPaint.alpha = alpha.coerceIn(0, 255)
                val r = 2.2f * density * (1f + 0.6f * intensity)
                canvas.drawCircle(x, nodeY, r, nodeGlowPaint)
            }
        }
    }

    companion object {
        /** itemView keyed tag 的 key：存「是否已过点」布尔，供连线状态化用 */
        const val TAG_PASSED = R.id.taskCardView
    }
}
