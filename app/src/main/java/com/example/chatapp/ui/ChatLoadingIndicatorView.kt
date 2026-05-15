package com.example.chatapp.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

class ChatLoadingIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val indicatorSize = (16f * density).toInt()
    private val strokeWidth = 1.5f * density
    private val arcBounds = RectF()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3A3A3C")
        style = Paint.Style.STROKE
        strokeWidth = this@ChatLoadingIndicatorView.strokeWidth
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = this@ChatLoadingIndicatorView.strokeWidth
        strokeCap = Paint.Cap.ROUND
    }

    private var rotationDegrees = 0f
    private var animator: ValueAnimator? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(indicatorSize + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(indicatorSize + paddingTop + paddingBottom, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = (minOf(width - paddingLeft - paddingRight, height - paddingTop - paddingBottom) / 2f) -
            strokeWidth
        val centerX = width / 2f
        val centerY = height / 2f
        canvas.drawCircle(centerX, centerY, radius, fillPaint)

        arcBounds.set(
            centerX - radius,
            centerY - radius,
            centerX + radius,
            centerY + radius
        )
        canvas.drawOval(arcBounds, ringPaint)
        canvas.drawArc(arcBounds, rotationDegrees, 95f, false, arcPaint)
    }

    private fun start() {
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 900L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { valueAnimator ->
                rotationDegrees = valueAnimator.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stop() {
        animator?.cancel()
        animator = null
    }
}
