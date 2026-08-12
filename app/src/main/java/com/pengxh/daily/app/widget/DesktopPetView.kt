package com.pengxh.daily.app.widget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.FrameLayout
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import com.pengxh.daily.app.R

/**
 * 桌面小宠物的可视容器。
 *
 * 职责只负责 Lottie 的承载与动画切换：
 * - [playAsset]：异步加载资产 → 成功后播放；贴边用 [fillViewport] 按自由态同缩放对齐窄视口。
 * - [setViewportSize]：裁剪窗口尺寸。贴边时收成「头宽」。
 * - [resetTransform]：清除旋转/平移/镜像残留。
 *
 * 竞态保护：用 [playToken] 丢弃被顶掉的 onAnimationEnd。
 */
class DesktopPetView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val kTag = "DesktopPetView"

    val lottieView: LottieAnimationView

    /** Lottie 固有全尺寸（自由态窗口大小） */
    private val fullWidthPx: Int
    private val fullHeightPx: Int

    private var playToken = 0
    private var contentScale = 1f

    init {
        val d = resources.displayMetrics.density
        fullWidthPx = (PetConfig.WINDOW_WIDTH_DP * d).toInt()
        fullHeightPx = (PetConfig.WINDOW_HEIGHT_DP * d).toInt()
        LayoutInflater.from(context).inflate(R.layout.window_pet_view, this, true)
        lottieView = findViewById(R.id.petLottieView)
        lottieView.layoutParams = LayoutParams(fullWidthPx, fullHeightPx)
        clipChildren = true
        clipToPadding = true
    }

    /**
     * 设置可视裁剪窗口。贴边时 width≈头宽；自由态恢复 [fullWidthPx]×[fullHeightPx]。
     */
    fun setViewportSize(widthPx: Int, heightPx: Int) {
        val lp = layoutParams ?: LayoutParams(widthPx, heightPx)
        lp.width = widthPx
        lp.height = heightPx
        layoutParams = lp
        requestLayout()
    }

    /**
     * 切换动画资产。
     * @param fillViewport 贴边烘焙资源：高度与自由态一致、按 dock 画布比例定宽，并套用同一 [PetConfig.BLINK_SCALE_FACTOR]
     * @param alignToEnd 左贴边 true（靠视口右缘，裁掉靠墙的身体）；右贴边 false
     */
    fun playAsset(
        assetPath: String,
        loop: Boolean,
        fillViewport: Boolean = false,
        alignToEnd: Boolean = false,
        onEnd: (() -> Unit)? = null
    ) {
        val token = ++playToken
        // 贴边与自由态同一缩放，避免「贴边突然变大」
        contentScale = PetConfig.BLINK_SCALE_FACTOR
        lottieView.rotation = 0f
        lottieView.translationX = 0f
        if (fillViewport) {
            val dockW = (fullHeightPx * PetConfig.DOCK_CANVAS_WIDTH / PetConfig.DOCK_CANVAS_HEIGHT)
                .toInt()
                .coerceAtLeast(1)
            val lp = LayoutParams(dockW, fullHeightPx).apply {
                gravity = (if (alignToEnd) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL
            }
            lottieView.layoutParams = lp
            // 缩放绕贴边侧，避免缩完后离开屏幕边缘
            lottieView.pivotX = if (alignToEnd) dockW.toFloat() else 0f
            lottieView.pivotY = fullHeightPx / 2f
        } else {
            lottieView.layoutParams = LayoutParams(fullWidthPx, fullHeightPx)
            lottieView.pivotX = fullWidthPx / 2f
            lottieView.pivotY = fullHeightPx / 2f
        }
        lottieView.removeAllAnimatorListeners()
        lottieView.cancelAnimation()
        lottieView.progress = 0f
        lottieView.scaleX = contentScale
        lottieView.scaleY = contentScale
        LottieCompositionFactory.fromAsset(context, assetPath)
            .addListener { composition ->
                if (token != playToken || !isAttachedToWindow) return@addListener
                lottieView.setComposition(composition)
                lottieView.repeatCount = if (loop) LottieDrawable.INFINITE else 0
                lottieView.playAnimation()
                if (!loop && onEnd != null) {
                    lottieView.addAnimatorListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            if (token == playToken) {
                                onEnd()
                            }
                        }
                    })
                }
            }
            .addFailureListener { e ->
                Log.w(kTag, "Lottie load failed: $assetPath", e)
                // 失败也推进状态机，避免永久卡在 ENTERING/RANDOM/EXITING 等
                if (!loop && onEnd != null && token == playToken) {
                    onEnd()
                }
            }
    }

    fun resetTransform() {
        lottieView.rotation = 0f
        lottieView.scaleX = contentScale
        lottieView.scaleY = contentScale
        lottieView.translationX = 0f
    }

    fun stopAndReset() {
        playToken++
        lottieView.removeAllAnimatorListeners()
        lottieView.cancelAnimation()
        lottieView.progress = 0f
    }

    fun destroy() {
        playToken++
        lottieView.removeAllAnimatorListeners()
        lottieView.cancelAnimation()
    }
}
