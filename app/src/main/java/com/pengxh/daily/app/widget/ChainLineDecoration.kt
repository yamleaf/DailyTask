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
import kotlin.math.abs
import kotlin.math.sin

/**
 * 任务列表的链式时间轴：节点 + 节点间连线 + 流动脉冲光点。
 *
 * 连线状态化：相邻两节点都已过点（时间已走过的轨迹）时线段用琥珀色，其余用描边灰。
 * 状态由 Adapter 在 bind 时写入 itemView 的 keyed tag（[TAG_PASSED] / [TAG_RUNNING]）。
 *
 * 卡片化适配（2026-11-22，对齐 DC 控制端）：
 * 任务行已改成「一任务一卡片」，原先布局里左侧 24dp 链轨列 + 14dp 节点 View 已移除。
 * 现在：
 * - 轨道 x = 卡片左内侧 12dp（卡片内容从 16dp 起，不压文字），本类自己画节点圆点；
 * - 节点 y = 每张卡片的垂直中心（不再是旧版的固定顶部偏移）；
 * - 灰线仍画在子 View 下层 → 只在卡片间 12dp 间隙可见；
 * - 节点 / 光点 / 彗尾画在子 View 上层（onDrawOver）→ 卡片是不透明白底，
 *   画在下层会被完全遮住、只在间隙闪一下，凑不成「流水」。
 *
 * 省电：每帧只是重绘，不起线程/不轮询。息屏或伪息屏时不跑动画；
 * 切走 Tab 由 [pause] 暂停、[resume] 恢复；视图销毁由 [release] 取消动画并断开 Host。
 * */
class ChainLineDecoration(context: Context) : RecyclerView.ItemDecoration() {

    private val appContext = context.applicationContext

    private val density = context.resources.displayMetrics.density

    /** 缓存 PowerManager，避免每帧 getSystemService 开销导致掉帧 */
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private val pulseColor = Color.parseColor("#40C4FF")

    /** 轨道中心 x（相对 item 左边缘）：落在卡片左内侧 padding 区 */
    private val trackCenterX = 12f * density

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

    /** 节点圆点（静态）：颜色按状态每帧设置 */
    private val nodeDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.md_outlineVariant)
    }

    /** 执行中节点的扩散光圈（雷达波纹）：描边圆，随相位外扩渐隐 */
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        color = pulseColor
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

    /** 节点被光点经过时的柔光 */
    private val nodeHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = pulseColor
    }

    private var host: RecyclerView? = null

    /** 流动进度 0→1：0 = 第一个节点，1 = 最后一个节点，循环 */
    private var flowProgress = 0f

    /** 当前光点 y 坐标，供 onDrawOver 判断哪些节点被「经过」 */
    private var pulseY = Float.MIN_VALUE

    /** 当前是否处于「可见且应动画」状态；切走 Tab 置 false，阻止 onDraw 重新拉起动画 */
    private var active = true

    /** 是否已释放；视图销毁后为 true，此后任何绘制都直接返回 */
    private var released = false

    private val warningColor = ContextCompat.getColor(context, R.color.md_warning)
    private val primaryColor = ContextCompat.getColor(context, R.color.md_primary)
    private val idleNodeColor = ContextCompat.getColor(context, R.color.md_outlineVariant)

    private val flowAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 3600
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { anim ->
            // 息屏 / 伪息屏时自停省电；亮屏后由 onDraw 重新拉起
            if (!isScreenActuallyOn()) {
                anim.pause()
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

    /** Tab 切走时暂停循环动画，避免不可见仍每帧 invalidate */
    fun pause() {
        active = false
        if (flowAnimator.isStarted && !flowAnimator.isPaused) flowAnimator.pause()
    }

    /** Tab 切回时恢复循环动画 */
    fun resume() {
        if (released) return
        active = true
        if (flowAnimator.isStarted && flowAnimator.isPaused) flowAnimator.resume()
    }

    /** 视图销毁时调用：停掉无限动画并断开对 RecyclerView 的引用 */
    fun release() {
        released = true
        active = false
        if (flowAnimator.isStarted) flowAnimator.cancel()
        flowAnimator.removeAllUpdateListeners()
        host = null
    }

    override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        if (released) return
        if (host !== parent) host = parent

        // 亮屏时确保流动动画运行（息屏时不重拉，由 updateListener 自行暂停）
        if (active && isScreenActuallyOn() && flowAnimator.isPaused) {
            flowAnimator.resume()
        }
        if (active && !flowAnimator.isStarted) {
            flowAnimator.start()
        }

        val count = parent.childCount
        if (count < 2) return

        // 只画连线：画在子 View 下层，卡片不透明 → 只在卡片间隙可见（像卡片之间的连接线）
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

            val x = child.left + trackCenterX
            canvas.drawLine(x, nodeCenterY(child), x, nodeCenterY(next), paint)
        }
    }

    private fun nodeCenterY(child: android.view.View): Float = child.top + child.height / 2f

    /**
     * 节点与脉冲都画在子 View 之上（onDrawOver）。
     *
     * 顺序：静态节点圆点 → 执行中波纹 → 流动光点与彗尾 → 节点被经过时的柔光。
     * */
    override fun onDrawOver(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        if (released || parent.childCount == 0) return

        drawNodes(canvas, parent)

        // 息屏 / 伪息屏：不再画流动脉冲与呼吸，省电
        if (!isScreenActuallyOn()) return

        drawFlowPulse(canvas, parent)
        drawNodeGlow(canvas, parent)
    }

    /** 静态节点圆点 + 执行中节点的呼吸与雷达波纹 */
    private fun drawNodes(canvas: Canvas, parent: RecyclerView) {
        val x = parent.getChildAt(0).left + trackCenterX
        // 呼吸 / 波纹相位：复用流动动画的进度（3600ms 内走 2 个周期 → 1800ms 一轮），
        // 不新增第二个动画，生命周期与流动脉冲完全一致
        val rippleT = (flowProgress * 2f) % 1f
        val breathScale = if (isScreenActuallyOn()) 1f + 0.3f * sin(rippleT * 2f * Math.PI).toFloat() else 1f

        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val nodeY = nodeCenterY(child)
            val passed = child.getTag(TAG_PASSED) as? Boolean ?: false
            val running = child.getTag(TAG_RUNNING) as? Boolean ?: false

            val color = when {
                running -> primaryColor
                passed -> warningColor
                else -> idleNodeColor
            }
            nodeDotPaint.color = color
            nodeDotPaint.alpha = if (passed || running) 0xFF else 0x99

            val baseRadius = 3f * density
            if (running) {
                // 雷达波纹：从 1 倍外扩到 2.6 倍并渐隐，与旧版 item 内 ripple View 一致
                val progress = 1f + 1.6f * rippleT
                ripplePaint.color = color
                ripplePaint.alpha = ((1f - (progress - 1f) / 1.6f) * 0.45f * 255).toInt().coerceIn(0, 255)
                canvas.drawCircle(x, nodeY, baseRadius * progress, ripplePaint)
                canvas.drawCircle(x, nodeY, baseRadius * breathScale, nodeDotPaint)
            } else {
                canvas.drawCircle(x, nodeY, baseRadius, nodeDotPaint)
            }
        }
    }

    /** 流动光点 + 渐隐彗尾：沿节点连线从首个节点依次流向下一个 */
    private fun drawFlowPulse(canvas: Canvas, parent: RecyclerView) {
        val count = parent.childCount
        // 先复位：本次算不出光点时，节点柔光不应沿用上一帧的旧位置
        pulseY = Float.MIN_VALUE
        if (count < 2) return

        val segments = ArrayList<FloatArray>(count - 1)
        var totalLen = 0f
        for (i in 0 until count - 1) {
            val child = parent.getChildAt(i)
            val next = parent.getChildAt(i + 1)
            val pos = parent.getChildAdapterPosition(child)
            val nextPos = parent.getChildAdapterPosition(next)
            if (pos == RecyclerView.NO_POSITION || nextPos == RecyclerView.NO_POSITION) continue
            if (nextPos != pos + 1) continue

            val startY = nodeCenterY(child)
            val endY = nodeCenterY(next)
            if (endY <= startY) continue
            segments.add(floatArrayOf(startY, endY))
            totalLen += endY - startY
        }
        if (segments.isEmpty() || totalLen <= 0f) return

        val target = flowProgress * totalLen
        var acc = 0f
        pulseY = segments[0][0]
        for (seg in segments) {
            val segLen = seg[1] - seg[0]
            if (target <= acc + segLen) {
                val frac = if (segLen > 0f) (target - acc) / segLen else 0f
                pulseY = seg[0] + frac * segLen
                break
            }
            acc += segLen
        }

        val x = parent.getChildAt(0).left + trackCenterX

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
    }

    /** 节点被光点「经过」时泛青光：小尺寸柔光，不压卡片内容 */
    private fun drawNodeGlow(canvas: Canvas, parent: RecyclerView) {
        if (pulseY == Float.MIN_VALUE) return

        val x = parent.getChildAt(0).left + trackCenterX
        val glowRange = 32f * density
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val nodeY = nodeCenterY(child)
            val dist = abs(nodeY - pulseY)
            if (dist >= glowRange) continue

            val intensity = 1f - dist / glowRange
            nodeHaloPaint.alpha = (intensity * 0x18).toInt().coerceIn(0, 255)
            canvas.drawCircle(x, nodeY, 9f * density, nodeHaloPaint)
            nodeHaloPaint.alpha = (intensity * 0x30).toInt().coerceIn(0, 255)
            canvas.drawCircle(x, nodeY, 6f * density, nodeHaloPaint)
        }
    }

    companion object {
        /**
         * itemView keyed tag 的 key：存「是否已过点」布尔，供连线状态化用。
         *
         * 刻意用字面量而非 R.id.xxx：const val 引用 R.id 字段时，R8 资源收缩会重写
         * R 类的 id 访问器，导致 release 包运行时报 `NoSuchMethodError: getTAG_PASSED()`
         * （debug 不启用 R8 故不复现）。tag key 只需唯一 int，无需是资源 id。
         * 0x4D414441 落在 AAPT 资源 id（0x7f..）之外，不会与真实资源冲突。
         */
        const val TAG_PASSED = 0x4D414441

        /** itemView keyed tag：存「是否执行中」布尔，供节点着色与呼吸/波纹用。同样用字面量避开 R8 */
        const val TAG_RUNNING = 0x4D414442
    }
}
