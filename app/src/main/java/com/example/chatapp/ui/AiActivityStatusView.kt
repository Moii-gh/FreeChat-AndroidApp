package com.example.chatapp.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Shader
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.chatapp.R
import com.example.chatapp.ai.AiActivityState
import com.example.chatapp.util.dpToPx

class AiActivityStatusView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val iconView = ImageView(context).apply {
        layoutParams = LayoutParams(18.dpToPx(), 18.dpToPx()).apply {
            marginEnd = 8.dpToPx()
        }
        setColorFilter(ACTIVITY_TINT)
    }

    private val textView = TextView(context).apply {
        setTextColor(ACTIVITY_TINT)
        textSize = 15f
        includeFontPadding = false
        maxWidth = (resources.displayMetrics.widthPixels - 96.dpToPx()).coerceAtLeast(120.dpToPx())
        setLineSpacing(0f, 1.15f)
    }

    private val dotsView = TypingDotsView(context).apply {
        alpha = 0.72f
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = 8.dpToPx()
        }
    }

    private var currentText: String? = null
    private var shimmerAnimator: ValueAnimator? = null
    private var iconAnimator: ValueAnimator? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 8.dpToPx(), 0, 8.dpToPx())
        setTag(R.id.ai_activity_view, true)
        addView(iconView)
        addView(textView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(dotsView)
        alpha = 0f
        translationY = 4.dpToPx().toFloat()
    }

    fun bind(state: AiActivityState) {
        val text = AiActivityStatusPresenter.text(context, state)
        val iconRes = AiActivityStatusPresenter.iconRes(state)
        iconView.setImageResource(iconRes)
        iconView.setColorFilter(ACTIVITY_TINT)
        contentDescription = text

        if (currentText != text) {
            currentText = text
            textView.text = text
            textView.translationY = 3.dpToPx().toFloat()
            textView.alpha = 0.74f
            textView.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(160L)
                .setInterpolator(DecelerateInterpolator())
                .start()
            startTextShimmer()
        }

        if (visibility != View.VISIBLE) {
            visibility = View.VISIBLE
        }
        animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(180L)
            .setInterpolator(DecelerateInterpolator())
            .start()
        startIconPulse()
        dotsView.startAnimation()
    }

    fun stopAnimations() {
        shimmerAnimator?.cancel()
        shimmerAnimator = null
        iconAnimator?.cancel()
        iconAnimator = null
        textView.paint.shader = null
        textView.invalidate()
        dotsView.stopAnimation()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        currentText?.let { startTextShimmer() }
        startIconPulse()
    }

    override fun onDetachedFromWindow() {
        stopAnimations()
        super.onDetachedFromWindow()
    }

    private fun startIconPulse() {
        if (iconAnimator?.isRunning == true) return
        iconAnimator = ValueAnimator.ofFloat(0.52f, 1f).apply {
            duration = 1100L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                iconView.alpha = animator.animatedValue as Float
            }
            start()
        }
    }

    private fun startTextShimmer() {
        shimmerAnimator?.cancel()
        val text = textView.text?.toString().orEmpty()
        if (text.isBlank()) return

        textView.post {
            val width = textView.paint.measureText(text)
            if (width <= 0f) return@post

            val shader = LinearGradient(
                0f,
                0f,
                width / 2f,
                0f,
                intArrayOf(ACTIVITY_TINT, Color.WHITE, ACTIVITY_TINT),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.MIRROR
            )
            val matrix = Matrix()
            textView.paint.shader = shader
            shimmerAnimator = ValueAnimator.ofFloat(0f, width).apply {
                duration = 1400L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { animator ->
                    matrix.setTranslate(animator.animatedValue as Float, 0f)
                    shader.setLocalMatrix(matrix)
                    textView.invalidate()
                }
                start()
            }
        }
    }

    private companion object {
        val ACTIVITY_TINT: Int = Color.parseColor("#B3B3B3")
    }
}
