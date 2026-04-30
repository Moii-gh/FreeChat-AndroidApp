package com.example.chatapp.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import kotlin.math.min

class FreeChatAttentionDrawable(
    private val density: Float
) : Drawable(), Animatable {

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = dp(2f)
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = dp(7f)
    }
    private val rect = RectF()
    private val gradientMatrix = Matrix()
    private val defaultGradientColors = intArrayOf(
        Color.TRANSPARENT,
        Color.TRANSPARENT,
        Color.parseColor("#8B5CFF"),
        Color.parseColor("#FF5FCB"),
        Color.parseColor("#D46CFF"),
        Color.TRANSPARENT,
        Color.TRANSPARENT
    )
    private val lowQuotaGradientColors = intArrayOf(
        Color.TRANSPARENT,
        Color.TRANSPARENT,
        Color.parseColor("#FF3B30"),
        Color.parseColor("#FF4FA3"),
        Color.parseColor("#A855F7"),
        Color.TRANSPARENT,
        Color.TRANSPARENT
    )
    private val gradientStops = floatArrayOf(0f, 0.56f, 0.68f, 0.78f, 0.87f, 0.95f, 1f)
    private var borderGradient: SweepGradient? = null
    private var animator: ValueAnimator? = null
    private var hostView: View? = null
    private var progress = 0f

    var useLowQuotaPalette: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            updateGradient(bounds)
            invalidateSelf()
        }

    var isInteracting: Boolean = false
        set(value) {
            field = value
            if (value) cancelAttention()
        }

    fun play(host: View) {
        if (isInteracting || isRunning || !host.isShown || host.width == 0 || host.height == 0) return

        hostView = host
        setBounds(0, 0, host.width, host.height)
        host.overlay.add(this)
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1600L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidateSelf()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    clearFromHost()
                }

                override fun onAnimationCancel(animation: Animator) {
                    clearFromHost()
                }
            })
            start()
        }
    }

    fun cancelAttention() {
        animator?.cancel()
        animator = null
        clearFromHost()
    }

    override fun draw(canvas: Canvas) {
        if (progress <= 0f || bounds.width() == 0 || bounds.height() == 0) return

        val pulseProgress = (progress / 0.24f).coerceIn(0f, 1f)
        val sweepProgress = ((progress - 0.12f) / 0.88f).coerceIn(0f, 1f)
        val fadeOut = if (progress > 0.78f) 1f - ((progress - 0.78f) / 0.22f) else 1f
        val alpha = (min(1f, pulseProgress * 1.12f) * fadeOut).coerceIn(0f, 1f)

        val inset = dp(4.5f) - dp(1.5f) * pulseProgress
        rect.set(
            bounds.left + inset,
            bounds.top + inset,
            bounds.right - inset,
            bounds.bottom - inset
        )
        val radius = rect.height() / 2f
        val cx = rect.centerX()
        val cy = rect.centerY()
        val gradient = borderGradient ?: return
        gradientMatrix.setRotate(360f * sweepProgress - 90f, cx, cy)
        gradient.setLocalMatrix(gradientMatrix)

        glowPaint.shader = gradient
        glowPaint.alpha = (82 * alpha).toInt()
        canvas.drawRoundRect(rect, radius, radius, glowPaint)

        borderPaint.shader = gradient
        borderPaint.alpha = (235 * alpha).toInt()
        canvas.drawRoundRect(rect, radius, radius, borderPaint)

        borderPaint.shader = null
        borderPaint.color = Color.WHITE
        borderPaint.alpha = (38 * alpha * (1f - sweepProgress)).toInt()
        canvas.drawRoundRect(rect, radius, radius, borderPaint)
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        updateGradient(bounds)
    }

    private fun updateGradient(bounds: Rect) {
        if (bounds.width() > 0 && bounds.height() > 0) {
            borderGradient = SweepGradient(
                bounds.exactCenterX(),
                bounds.exactCenterY(),
                if (useLowQuotaPalette) lowQuotaGradientColors else defaultGradientColors,
                gradientStops
            )
        }
    }

    override fun start() {
        hostView?.let(::play)
    }

    override fun stop() {
        cancelAttention()
    }

    override fun isRunning(): Boolean = animator?.isRunning == true

    override fun setAlpha(alpha: Int) {
        borderPaint.alpha = alpha
        glowPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        borderPaint.colorFilter = colorFilter
        glowPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private fun clearFromHost() {
        progress = 0f
        hostView?.overlay?.remove(this)
        invalidateSelf()
    }

    private fun dp(value: Float): Float = value * density
}
