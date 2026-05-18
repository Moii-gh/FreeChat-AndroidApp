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
import android.view.animation.LinearInterpolator

class BrowserSplashGradientDrawable : Drawable(), Animatable {

    private val rect = RectF()
    private val shimmerMatrix = Matrix()
    private val shimmerInterpolator = AccelerateDecelerateInterpolator()
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val verticalLightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val shimmerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var baseGradient: LinearGradient? = null
    private var verticalGradient: LinearGradient? = null
    private var shimmerGradient: LinearGradient? = null
    private var animator: ValueAnimator? = null
    private var progress = 0f

    override fun draw(canvas: Canvas) {
        if (bounds.width() <= 0 || bounds.height() <= 0) return

        rect.set(bounds)

        baseGradient?.let { gradient ->
            basePaint.shader = gradient
            canvas.drawRect(rect, basePaint)
        }

        verticalGradient?.let { gradient ->
            verticalLightPaint.shader = gradient
            canvas.drawRect(rect, verticalLightPaint)
        }

        if (progress <= SHIMMER_ACTIVE_FRACTION) {
            shimmerGradient?.let { gradient ->
                val activeProgress = shimmerInterpolator.getInterpolation(
                    (progress / SHIMMER_ACTIVE_FRACTION).coerceIn(0f, 1f)
                )
                val travelDistance = bounds.width() * 2.45f
                val offset = -bounds.width() * 0.9f + travelDistance * activeProgress
                shimmerMatrix.setTranslate(offset, 0f)
                gradient.setLocalMatrix(shimmerMatrix)
                shimmerPaint.shader = gradient
                canvas.drawRect(rect, shimmerPaint)
            }
        }
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        if (bounds.width() <= 0 || bounds.height() <= 0) return

        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()
        baseGradient = LinearGradient(
            0f,
            0f,
            0f,
            height,
            intArrayOf(
                Color.BLACK,
                Color.parseColor("#050506"),
                Color.BLACK
            ),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )
        verticalGradient = LinearGradient(
            0f,
            0f,
            0f,
            height,
            intArrayOf(
                Color.argb(30, 92, 92, 94),
                Color.argb(6, 44, 44, 46),
                Color.argb(42, 8, 8, 10)
            ),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )
        shimmerGradient = LinearGradient(
            -width,
            0f,
            width,
            height,
            intArrayOf(
                Color.argb(0, 255, 255, 255),
                Color.argb(26, 108, 108, 112),
                Color.argb(86, 245, 245, 247),
                Color.argb(34, 132, 132, 136),
                Color.argb(0, 255, 255, 255)
            ),
            floatArrayOf(0f, 0.28f, 0.5f, 0.72f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun start() {
        if (isRunning) return

        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SHIMMER_INTERVAL_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
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
        progress = 0f
    }

    override fun isRunning(): Boolean = animator?.isRunning == true

    override fun setAlpha(alpha: Int) {
        basePaint.alpha = alpha
        verticalLightPaint.alpha = alpha
        shimmerPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        basePaint.colorFilter = colorFilter
        verticalLightPaint.colorFilter = colorFilter
        shimmerPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        const val SHIMMER_INTERVAL_MS = 5_000L
        const val SHIMMER_ACTIVE_FRACTION = 0.58f
    }
}
