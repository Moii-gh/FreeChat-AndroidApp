package com.example.chatapp.ui.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import androidx.appcompat.widget.AppCompatTextView
import kotlin.math.max

internal class TypingTitleTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cursorRect = RectF()
    private val cursorWidthPx = 2.5f.dpToPx()
    private val cursorRadiusPx = 1.25f.dpToPx()
    private val cursorGapPx = 3f.dpToPx()

    var cursorAlphaFraction: Float = 1f
        set(value) {
            val constrained = value.coerceIn(0f, 1f)
            if (field == constrained) return
            field = constrained
            invalidate()
        }

    var isCursorEnabled: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isCursorEnabled || cursorAlphaFraction <= 0f) return

        cursorPaint.color = currentTextColor
        cursorPaint.alpha = (255 * cursorAlphaFraction).toInt().coerceIn(0, 255)

        val textLayout = layout
        val textValue = text?.toString().orEmpty()
        val availableHeight = height - compoundPaddingTop - compoundPaddingBottom

        val cursorHeight = (paint.fontMetrics.descent - paint.fontMetrics.ascent) * 0.88f
        val cursorCenterY: Float
        val cursorX: Float

        if (textLayout == null || textValue.isEmpty()) {
            cursorX = width / 2f
            cursorCenterY = compoundPaddingTop + availableHeight / 2f
        } else {
            val endOffset = textValue.length
            val line = textLayout.getLineForOffset(endOffset)
            val layoutTop = compoundPaddingTop + verticalLayoutOffset(textLayout.height, availableHeight)
            val baseline = layoutTop + textLayout.getLineBaseline(line)
            cursorCenterY = baseline + (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f

            val rawX = compoundPaddingLeft + textLayout.getPrimaryHorizontal(endOffset) + cursorGapPx
            cursorX = rawX.coerceIn(
                compoundPaddingLeft.toFloat(),
                (width - compoundPaddingRight - cursorWidthPx).toFloat()
            )
        }

        val top = cursorCenterY - cursorHeight / 2f
        cursorRect.set(cursorX, top, cursorX + cursorWidthPx, top + cursorHeight)
        canvas.drawRoundRect(cursorRect, cursorRadiusPx, cursorRadiusPx, cursorPaint)
    }

    private fun verticalLayoutOffset(layoutHeight: Int, availableHeight: Int): Float {
        val extra = max(0, availableHeight - layoutHeight)
        return when (gravity and Gravity.VERTICAL_GRAVITY_MASK) {
            Gravity.CENTER_VERTICAL -> extra / 2f
            Gravity.BOTTOM -> extra.toFloat()
            else -> 0f
        }
    }

    private fun Float.dpToPx(): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            this,
            resources.displayMetrics
        )
}
