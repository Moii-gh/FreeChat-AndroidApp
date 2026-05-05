package com.example.chatapp.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Анимированные три точки (typing indicator).
 * Каждая точка плавно пульсирует с задержкой по фазе, создавая эффект «волны».
 */
class TypingDotsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val DOT_COUNT = 3
        private const val DOT_RADIUS_DP = 3.5f
        private const val DOT_SPACING_DP = 10f
        private const val ANIM_DURATION_MS = 1200L
        private const val MIN_ALPHA = 0.25f
        private const val MAX_ALPHA = 1.0f
        private const val MIN_SCALE = 0.7f
        private const val MAX_SCALE = 1.0f
    }

    private val density = resources.displayMetrics.density
    private val dotRadius = DOT_RADIUS_DP * density
    private val dotSpacing = DOT_SPACING_DP * density

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    /** Фаза каждой точки: 0..1 */
    private val phases = FloatArray(DOT_COUNT)

    private var animator: ValueAnimator? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAnimation()
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        super.onDetachedFromWindow()
    }

    fun startAnimation() {
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ANIM_DURATION_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val globalPhase = anim.animatedValue as Float
                for (i in 0 until DOT_COUNT) {
                    // Сдвигаем фазу для каждой точки на 0.2 (т.е. ~240мс)
                    val shifted = (globalPhase + i * 0.2f) % 1f
                    phases[i] = shifted
                }
                invalidate()
            }
            start()
        }
    }

    fun stopAnimation() {
        animator?.cancel()
        animator = null
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val totalWidth = (dotRadius * 2 * DOT_COUNT + dotSpacing * (DOT_COUNT - 1)).toInt()
        val totalHeight = (dotRadius * 2 * (1f / MIN_SCALE)).toInt() // Запас под масштаб
        setMeasuredDimension(
            resolveSize(totalWidth + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(totalHeight + paddingTop + paddingBottom, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val totalDotsWidth = dotRadius * 2 * DOT_COUNT + dotSpacing * (DOT_COUNT - 1)
        val startX = (width - totalDotsWidth) / 2f + dotRadius
        val centerY = height / 2f

        for (i in 0 until DOT_COUNT) {
            val phase = phases[i]
            // Синусоидальная пульсация для плавности
            val wave = (Math.sin(phase * 2.0 * Math.PI).toFloat() + 1f) / 2f // 0..1

            val alpha = MIN_ALPHA + (MAX_ALPHA - MIN_ALPHA) * wave
            val scale = MIN_SCALE + (MAX_SCALE - MIN_SCALE) * wave

            dotPaint.alpha = (alpha * 255).toInt()
            val cx = startX + i * (dotRadius * 2 + dotSpacing)
            canvas.drawCircle(cx, centerY, dotRadius * scale, dotPaint)
        }
    }
}
