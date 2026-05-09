package com.example.chatapp.ui

import android.graphics.Color
import android.text.Spanned
import android.text.style.ClickableSpan
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.TextView
import kotlin.math.abs

internal object SelectableTextSupport {
    private val selectionHighlightColor = Color.parseColor("#668E8E93")

    fun configure(
        textView: TextView,
        linkColor: Int? = null,
        openLinksOnTap: Boolean = false
    ) {
        textView.setTextIsSelectable(true)
        textView.highlightColor = selectionHighlightColor
        textView.linksClickable = openLinksOnTap
        linkColor?.let(textView::setLinkTextColor)
        textView.setOnTouchListener(SelectableTextTouchListener(openLinksOnTap))
    }

    private class SelectableTextTouchListener(
        private val openLinksOnTap: Boolean
    ) : View.OnTouchListener {
        private var downSpan: ClickableSpan? = null
        private var downX = 0f
        private var downY = 0f
        private var downTime = 0L
        private var parentLockedForSelection = false
        private var parentLockRunnable: Runnable? = null

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val textView = view as? TextView ?: return false
            val config = ViewConfiguration.get(textView.context)
            val touchSlop = config.scaledTouchSlop

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    downTime = event.eventTime
                    downSpan = if (openLinksOnTap) findClickableSpan(textView, event) else null
                    parentLockedForSelection = false
                    parentLockRunnable = Runnable {
                        parentLockedForSelection = true
                        textView.parent?.requestDisallowInterceptTouchEvent(true)
                    }.also {
                        textView.postDelayed(it, ViewConfiguration.getLongPressTimeout().toLong())
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val moved = movedPastSlop(event, touchSlop)
                    if (!parentLockedForSelection && moved) {
                        clearParentLock(textView)
                    } else if (parentLockedForSelection) {
                        textView.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val pressedSpan = downSpan
                    val releasedSpan = if (openLinksOnTap) findClickableSpan(textView, event) else null
                    val shortTap = event.eventTime - downTime < ViewConfiguration.getLongPressTimeout()
                    val moved = movedPastSlop(event, touchSlop)
                    clearParentLock(textView)
                    downSpan = null

                    if (pressedSpan != null && pressedSpan === releasedSpan && shortTap && !moved) {
                        pressedSpan.onClick(textView)
                        return true
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    clearParentLock(textView)
                    downSpan = null
                }
            }

            return false
        }

        private fun movedPastSlop(event: MotionEvent, touchSlop: Int): Boolean =
            abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop

        private fun clearParentLock(textView: TextView) {
            parentLockRunnable?.let(textView::removeCallbacks)
            parentLockRunnable = null
            if (parentLockedForSelection) {
                textView.parent?.requestDisallowInterceptTouchEvent(false)
            }
            parentLockedForSelection = false
        }

        private fun findClickableSpan(textView: TextView, event: MotionEvent): ClickableSpan? {
            val text = textView.text as? Spanned ?: return null
            val layout = textView.layout ?: return null
            val x = event.x.toInt() - textView.totalPaddingLeft + textView.scrollX
            val y = event.y.toInt() - textView.totalPaddingTop + textView.scrollY
            if (x < 0 || y < 0 || y > layout.height) return null

            val line = layout.getLineForVertical(y)
            if (x < layout.getLineLeft(line) || x > layout.getLineRight(line)) return null

            val offset = layout.getOffsetForHorizontal(line, x.toFloat())
            return text.getSpans(offset, offset, ClickableSpan::class.java)
                .firstOrNull { span ->
                    val start = text.getSpanStart(span)
                    val end = text.getSpanEnd(span)
                    offset in start until end
                }
        }
    }
}
