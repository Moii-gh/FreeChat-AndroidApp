package com.example.chatapp.ui

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.view.animation.AccelerateDecelerateInterpolator

class AnimatedProfileCardDrawable(
    private val density: Float
) : Drawable(), Animatable {

    private val rect = RectF()
    private val shimmerMatrix = Matrix()
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#1C1C1E")
    }
    private val shimmerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val verticalLightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.argb(74, 196, 196, 198)
    }

    private var shimmerGradient: LinearGradient? = null
    private var verticalGradient: LinearGradient? = null
    private var animator: ValueAnimator? = null
    private var progress = 0f

    override fun draw(canvas: Canvas) {
        if (bounds.width() <= 0 || bounds.height() <= 0) return

        rect.set(bounds)
        val radius = dp(20f)

        canvas.drawRoundRect(rect, radius, radius, basePaint)

        verticalGradient?.let { gradient ->
            verticalLightPaint.shader = gradient
            canvas.drawRoundRect(rect, radius, radius, verticalLightPaint)
        }

        shimmerGradient?.let { gradient ->
            val travelDistance = bounds.width() * 2.1f
            val offset = -bounds.width() * 0.55f + travelDistance * progress
            shimmerMatrix.setTranslate(offset, 0f)
            gradient.setLocalMatrix(shimmerMatrix)
            shimmerPaint.shader = gradient
            canvas.drawRoundRect(rect, radius, radius, shimmerPaint)
        }

        val strokeInset = strokePaint.strokeWidth / 2f
        rect.inset(strokeInset, strokeInset)
        canvas.drawRoundRect(rect, radius - strokeInset, radius - strokeInset, strokePaint)
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        if (bounds.width() <= 0 || bounds.height() <= 0) return

        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()
        shimmerGradient = LinearGradient(
            -width,
            0f,
            width,
            height,
            intArrayOf(
                Color.argb(0, 28, 28, 30),
                Color.argb(32, 70, 70, 72),
                Color.argb(70, 174, 174, 176),
                Color.argb(34, 86, 86, 88),
                Color.argb(0, 28, 28, 30)
            ),
            floatArrayOf(0f, 0.28f, 0.5f, 0.72f, 1f),
            Shader.TileMode.CLAMP
        )
        verticalGradient = LinearGradient(
            0f,
            0f,
            0f,
            height,
            intArrayOf(
                Color.argb(34, 92, 92, 94),
                Color.argb(10, 44, 44, 46),
                Color.argb(42, 14, 14, 16)
            ),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP
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
        basePaint.alpha = alpha
        shimmerPaint.alpha = alpha
        verticalLightPaint.alpha = alpha
        strokePaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        basePaint.colorFilter = colorFilter
        shimmerPaint.colorFilter = colorFilter
        verticalLightPaint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private fun dp(value: Float): Float = value * density
}
