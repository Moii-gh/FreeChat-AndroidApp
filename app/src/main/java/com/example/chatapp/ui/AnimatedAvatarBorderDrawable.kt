package com.example.chatapp.ui

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
import android.view.animation.AccelerateDecelerateInterpolator
import kotlin.math.min
import kotlin.math.sin

class AnimatedAvatarBorderDrawable(
    private val density: Float
) : Drawable(), Animatable {

    private val rect = RectF()
    private val gradientMatrix = Matrix()
    private val baseStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
        color = Color.argb(92, 150, 150, 152)
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(7.5f)
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(2.3f)
    }

    private var borderGradient: SweepGradient? = null
    private var animator: ValueAnimator? = null
    private var progress = 0f

    override fun draw(canvas: Canvas) {
        if (bounds.width() <= 0 || bounds.height() <= 0) return

        val inset = dp(5f)
        rect.set(
            bounds.left + inset,
            bounds.top + inset,
            bounds.right - inset,
            bounds.bottom - inset
        )

        val radius = min(rect.width(), rect.height()) / 2f
        canvas.drawOval(rect, baseStrokePaint)

        val gradient = borderGradient ?: return
        gradientMatrix.setRotate(360f * progress - 40f, rect.centerX(), rect.centerY())
        gradient.setLocalMatrix(gradientMatrix)

        val pulse = ((sin((progress * Math.PI * 2.0) - Math.PI / 2.0) + 1.0) / 2.0).toFloat()
        val calmPulse = 0.58f + 0.42f * pulse

        glowPaint.shader = gradient
        glowPaint.alpha = (52 * calmPulse).toInt()
        canvas.drawOval(rect, glowPaint)

        highlightPaint.shader = gradient
        highlightPaint.alpha = (210 * calmPulse).toInt()
        canvas.drawOval(rect, highlightPaint)

        highlightPaint.shader = null
        highlightPaint.color = Color.WHITE
        highlightPaint.alpha = (16 * calmPulse).toInt()
        canvas.drawCircle(rect.centerX(), rect.centerY(), radius, highlightPaint)
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        if (bounds.width() <= 0 || bounds.height() <= 0) return

        borderGradient = SweepGradient(
            bounds.exactCenterX(),
            bounds.exactCenterY(),
            intArrayOf(
                Color.TRANSPARENT,
                Color.argb(72, 130, 130, 132),
                Color.argb(172, 198, 198, 200),
                Color.argb(236, 248, 248, 249),
                Color.argb(150, 210, 210, 212),
                Color.argb(58, 126, 126, 128),
                Color.TRANSPARENT,
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.18f, 0.31f, 0.4f, 0.52f, 0.66f, 0.82f, 1f)
        )
    }

    override fun start() {
        if (isRunning) return

        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 18000L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidateSelf()
            }
            start()
        }
    }

    override fun stop() {
        animator?.cancel()
        animator = null
    }

    override fun isRunning(): Boolean = animator?.isRunning == true

    override fun setAlpha(alpha: Int) {
        baseStrokePaint.alpha = alpha
        glowPaint.alpha = alpha
        highlightPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        baseStrokePaint.colorFilter = colorFilter
        glowPaint.colorFilter = colorFilter
        highlightPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private fun dp(value: Float): Float = value * density
}
