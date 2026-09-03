package com.pengxh.daily.app.shizuku

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 坐标采集悬浮窗（feat_shiziku，独立文件）。
 * 双窗独立可拖动：准星窗（十字+范围圈）与控制窗（- 范围 + / 取消 确认）。
 * 范围圈直径 = 2×range(px) 真实覆盖点击的圆形区域；准星中心 = 实际确认点击坐标。
 * 两窗之外无遮罩，可正常点击底层被控端 App。
 */
object CoordinateCaptureOverlay {

    private val RANGE_LEVELS = intArrayOf(0, 5, 8, 12, 16, 20, 26, 30, 40, 50, 60, 80, 100, 120, 150, 200, 250, 300)

    private var stageView: View? = null
    private var ctrlView: View? = null
    private var windowManager: WindowManager? = null

    fun canShow(context: Context): Boolean = Settings.canDrawOverlays(context)

    private fun dp(context: Context, v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    fun startCapture(
        context: Context,
        onConfirm: (Int, Int, Int) -> Unit,
        initialX: Int = -1,
        initialY: Int = -1,
        initialRange: Int = 0,
        onCancel: () -> Unit = {},
        twoPoint: Boolean = false,
        onTwoPointConfirm: ((sx: Int, sy: Int, ex: Int, ey: Int, range: Int) -> Unit)? = null
    ) {
        if (!Settings.canDrawOverlays(context)) { onCancel(); return }
        dismiss()
        // 两阶段（滑动采集）：null=待选起点，已定=待选终点
        var startPoint: Pair<Int, Int>? = null
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val screenW = context.resources.displayMetrics.widthPixels
        val screenH = context.resources.displayMetrics.heightPixels
        val density = context.resources.displayMetrics.density
        var levelIndex = RANGE_LEVELS.indexOfFirst { it >= initialRange }
            .let { if (it < 0) RANGE_LEVELS.lastIndex else it }

        // 范围圈（直径 = 2×range px，真实覆盖随机点击范围）
        val ring = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(80, 233, 30, 30))
                setStroke(dp(context, 2), Color.argb(180, 233, 30, 30))
            }
        }
        // 十字准星（中心透明，交叉点即实际点击坐标）
        fun crossLine(horizontal: Boolean): View = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.argb(230, 233, 30, 30))
            }
            layoutParams = if (horizontal) FrameLayout.LayoutParams(dp(context, 30), dp(context, 2))
            else FrameLayout.LayoutParams(dp(context, 2), dp(context, 30))
        }
        val dot = FrameLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            addView(crossLine(true), FrameLayout.LayoutParams(dp(context, 30), dp(context, 2)).apply { gravity = Gravity.CENTER })
            addView(crossLine(false), FrameLayout.LayoutParams(dp(context, 2), dp(context, 30)).apply { gravity = Gravity.CENTER })
        }

        // 范围值显示
        val rangeInfo = TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 15f
            setTextColor(Color.WHITE)
            maxLines = 1
            setSingleLine(true)
            // 固定最小宽度：保证「- 范围 Xpx +」完整渲染，+ 号不被挤出
            minWidth = dp(context, 90)
            setPadding(dp(context, 8), dp(context, 2), dp(context, 8), dp(context, 2))
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 10).toFloat()
                setColor(Color.argb(150, 0, 0, 0))
            }
        }
        // 阶段提示（两点模式显示：第几次/起点已定请选终点）
        val stageHint = TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 12f
            setTextColor(Color.WHITE)
            maxLines = 2
            isSingleLine = false
            setPadding(dp(context, 10), dp(context, 2), dp(context, 10), dp(context, 2))
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 8).toFloat()
                setColor(Color.argb(160, 0, 0, 0))
            }
            visibility = if (twoPoint) View.VISIBLE else View.GONE
            text = if (twoPoint) "第 1 步：拖动准星定起点，点确认" else ""
        }
        fun refreshRange() {
            val range = RANGE_LEVELS[levelIndex]
            // 真实覆盖：直径 = 2×range(px)；range=0 时仅一个中心小点，便于精确定位
            val d = if (range <= 0) dp(context, 6) else (range * 2).coerceAtLeast(dp(context, 6))
            ring.layoutParams = FrameLayout.LayoutParams(d, d).apply { gravity = Gravity.CENTER }
            rangeInfo.text = "范围 ${range}px"
        }

        // 准星窗：范围圈 + 十字，均以窗口中心为圆心/交点；窗口尺寸随范围圈自适应（直径 + 边距）
        var stageW = dp(context, 120)
        var stageH = dp(context, 120)
        val initD = RANGE_LEVELS[levelIndex].let { r ->
            if (r <= 0) dp(context, 6) else (r * 2).coerceAtLeast(dp(context, 6))
        }
        stageW = (initD + dp(context, 24)).coerceAtLeast(dp(context, 120))
        stageH = stageW
        val stage = FrameLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            addView(ring, FrameLayout.LayoutParams(dp(context, 6), dp(context, 6)).apply { gravity = Gravity.CENTER })
            addView(dot, FrameLayout.LayoutParams(dp(context, 30), dp(context, 30)).apply { gravity = Gravity.CENTER })
        }

        // 胶囊按钮
        fun pill(text: String, bg: Int): TextView = TextView(context).apply {
            this.text = text
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            includeFontPadding = false
            minWidth = dp(context, 32)
            minHeight = dp(context, 28)
            setPadding(dp(context, 4), dp(context, 3), dp(context, 4), dp(context, 3))
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 10).toFloat()
                setColor(bg)
            }
        }
        val minusBtn = pill("-", Color.argb(180, 60, 60, 60))
        val plusBtn = pill("+", Color.argb(180, 60, 60, 60))
        val cancelBtn = pill("取消", Color.argb(190, 40, 40, 40))
        val okBtn = pill("确认", Color.argb(235, 233, 30, 30))

        // 控制窗
        val rangeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.TRANSPARENT)
            addView(minusBtn)
            val g1 = View(context); addView(g1, LinearLayout.LayoutParams(dp(context, 6), 1))
            addView(rangeInfo)
            val g2 = View(context); addView(g2, LinearLayout.LayoutParams(dp(context, 6), 1))
            addView(plusBtn)
        }
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.TRANSPARENT)
            addView(cancelBtn)
            val g0 = View(context); addView(g0, LinearLayout.LayoutParams(dp(context, 8), 1))
            addView(okBtn)
        }
        val ctrlGroup = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.TRANSPARENT)
            addView(stageHint, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(context, 4) })
            addView(rangeRow)
            addView(btnRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(context, 4) })
        }
        // 先填充范围文字，确保「- 范围 Xpx +」渲染完整
        refreshRange()
        // 控制窗固定尺寸：两点模式需容纳阶段提示（较长文字），统一加宽到 240dp
        val ctrlW = dp(context, if (twoPoint) 240 else 180)
        val ctrlH = dp(context, if (twoPoint) 112 else 84)

        fun overlayParams(w: Int, h: Int, title: String, allowOffscreen: Boolean): WindowManager.LayoutParams =
            WindowManager.LayoutParams(
                w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                if (allowOffscreen) {
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                } else {
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                },
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START; this.title = title }

        // 准星窗：可拖到屏幕边缘（FLAG_LAYOUT_NO_LIMITS 允许窗口出屏，准星中心可达屏边）
        var stageX = if (initialX >= 0) initialX - stageW / 2 else (screenW - stageW) / 2
        var stageY = if (initialY >= 0) initialY - stageH / 2 else (screenH - stageH) / 2
        stageX = stageX.coerceIn(-(stageW / 2), screenW - stageW / 2)
        stageY = stageY.coerceIn(-(stageH / 2), screenH - stageH / 2)
        val lpStage = overlayParams(stageW, stageH, "CoordinateCapture" + "Stage", true)
        lpStage.x = stageX; lpStage.y = stageY

        // 范围圈变化时准星窗自适应：窗口 = 直径 + 边距，保持范围圈完整可见（最大 300px 直径 600px）；
        // 同时保持准星中心（窗口中心）不动——窗口左上角需随尺寸变化反向回退，避免点 +/- 时准星漂移
        fun resizeStage() {
            val range = RANGE_LEVELS[levelIndex]
            val d = if (range <= 0) dp(context, 6) else (range * 2).coerceAtLeast(dp(context, 6))
            val need = (d + dp(context, 24)).coerceAtLeast(dp(context, 120))
            if (need != stageW || need != stageH) {
                val cx = stageX + stageW / 2
                val cy = stageY + stageH / 2
                stageW = need; stageH = need
                stageX = cx - stageW / 2
                stageY = cy - stageH / 2
                lpStage.width = need; lpStage.height = need
                lpStage.x = stageX; lpStage.y = stageY
                runCatching { wm.updateViewLayout(stage, lpStage) }
            }
        }

        // 控制窗：可独立拖动，初始右下角
        val lpCtrl = overlayParams(ctrlW, ctrlH, "CoordinateCapture" + "Ctrl", false)
        lpCtrl.x = screenW - ctrlW - dp(context, 16)
        lpCtrl.y = screenH - ctrlH - dp(context, 60)

        // 拖动控制窗（自由，重叠可拖开）
        var cex = 0; var cey = 0; var csx = 0; var csy = 0
        ctrlGroup.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    cex = event.rawX.toInt(); cey = event.rawY.toInt(); csx = lpCtrl.x; csy = lpCtrl.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lpCtrl.x = csx + (event.rawX.toInt() - cex)
                    lpCtrl.y = csy + (event.rawY.toInt() - cey)
                    runCatching { wm.updateViewLayout(ctrlGroup, lpCtrl) }
                    true
                }
                else -> true
            }
        }

        // 拖动控制窗内的按钮需可点击：按钮 click 优先于拖动
        cancelBtn.isClickable = true
        okBtn.isClickable = true
        minusBtn.isClickable = true
        plusBtn.isClickable = true

        // 拖动准星窗：自由拖动（同控制组），可贴到任意屏幕边缘；出屏后 userPoint 可拖回
        var dx = 0; var dy = 0; var sx = 0; var sy = 0
        stage.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dx = event.rawX.toInt(); dy = event.rawY.toInt(); sx = stageX; sy = stageY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    stageX = sx + (event.rawX.toInt() - dx)
                    stageY = sy + (event.rawY.toInt() - dy)
                    lpStage.x = stageX; lpStage.y = stageY
                    runCatching { wm.updateViewLayout(stage, lpStage) }
                    true
                }
                else -> true
            }
        }

        minusBtn.setOnClickListener { levelIndex = (levelIndex - 1).coerceAtLeast(0); refreshRange(); resizeStage() }
        plusBtn.setOnClickListener { levelIndex = (levelIndex + 1).coerceAtMost(RANGE_LEVELS.lastIndex); refreshRange(); resizeStage() }
        cancelBtn.setOnClickListener { dismiss(); onCancel() }
        okBtn.setOnClickListener {
            // 实际点击坐标 = 准星窗口中心（十字交点）
            val cx = stageX + stageW / 2
            val cy = stageY + stageH / 2
            val range = RANGE_LEVELS[levelIndex]
            if (twoPoint) {
                if (startPoint == null) {
                    // 第一次：记录起点，切到第二步不动窗口，等用户拖到终点
                    startPoint = cx to cy
                    stageHint.text = "第 2 步：拖动准星定终点 → 确认"
                    rangeInfo.text = "起点($cx,$cy)"
                    return@setOnClickListener
                }
                val (sx, sy) = startPoint
                dismiss()
                onTwoPointConfirm?.invoke(sx, sy, cx, cy, range)
            } else {
                dismiss()
                onConfirm(cx, cy, range)
            }
        }

        refreshRange()
        runCatching { wm.addView(stage, lpStage) }
        runCatching { wm.addView(ctrlGroup, lpCtrl) }
        stageView = stage
        ctrlView = ctrlGroup
        windowManager = wm
    }

    fun dismiss() {
        val wm = windowManager
        val s = stageView
        val c = ctrlView
        stageView = null
        ctrlView = null
        windowManager = null
        if (wm != null) {
            if (s != null) runCatching { wm.removeView(s) }
            if (c != null) runCatching { wm.removeView(c) }
        }
    }
}