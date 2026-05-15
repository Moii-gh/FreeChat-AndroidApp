package com.example.chatapp.ui

import android.graphics.Color
import android.text.Selection
import android.text.Spannable
import android.text.Spanned
import android.text.method.ArrowKeyMovementMethod
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.TextView
import java.util.WeakHashMap
import kotlin.math.abs

internal object SelectableTextSupport {
    private val selectionHighlightColor = Color.parseColor("#668E8E93")
    private val configuredTextViews = WeakHashMap<TextView, SelectionConfig>()

    interface LongClickableSpan {
        fun onLongClick(widget: View): Boolean
    }

    private data class SelectionConfig(
        val linkColor: Int?,
        val openLinksOnTap: Boolean
    )

    fun configure(
        textView: TextView,
        linkColor: Int? = null,
        openLinksOnTap: Boolean = false
    ) {
        val config = SelectionConfig(linkColor, openLinksOnTap)
        configuredTextViews[textView] = config
        textView.setTextIsSelectable(true)
        applyConfiguration(textView, config)
    }

    fun clearAllSelections() {
        configuredTextViews.keys.toList().forEach { textView ->
            val config = configuredTextViews[textView] ?: return@forEach
            (textView.text as? Spannable)?.let(Selection::removeSelection)
            textView.cancelLongPress()
            textView.isPressed = false
            textView.clearFocus()

            if (textView.isTextSelectable) {
                textView.setTextIsSelectable(false)
                textView.setTextIsSelectable(true)
                applyConfiguration(textView, config)
            }
        }
    }

    private fun applyConfiguration(textView: TextView, config: SelectionConfig) {
        textView.highlightColor = selectionHighlightColor
        textView.linksClickable = config.openLinksOnTap
        config.linkColor?.let(textView::setLinkTextColor)
        if (textView.movementMethod is LinkMovementMethod) {
            textView.movementMethod = ArrowKeyMovementMethod.getInstance()
        }
        textView.isClickable = true
        textView.isLongClickable = true
        textView.setOnTouchListener(SelectableTextTouchListener(config.openLinksOnTap))
    }

    private class SelectableTextTouchListener(
        private val openLinksOnTap: Boolean
    ) : View.OnTouchListener {
        private var downSpan: ClickableSpan? = null
        private var downX = 0f
        private var downY = 0f
        private var downTime = 0L
        private var downSpanUrl: String? = null
        private var consumedLongClick = false
        private var parentLockedForSelection = false
        private var parentLockedForLink = false
        private var parentLockRunnable: Runnable? = null
        private var linkLongPressRunnable: Runnable? = null

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
                    downSpanUrl = (downSpan as? URLSpan)?.url
                    consumedLongClick = false
                    parentLockedForSelection = false
                    if (downSpan != null) {
                        parentLockedForLink = true
                        textView.parent?.requestDisallowInterceptTouchEvent(true)
                        highlightSpan(textView, downSpan)
                        linkLongPressRunnable = Runnable {
                            val span = downSpan
                            if (span is LongClickableSpan) {
                                consumedLongClick = span.onLongClick(textView)
                                clearPressedSpan(textView)
                            }
                        }.also {
                            textView.postDelayed(it, ViewConfiguration.getLongPressTimeout().toLong())
                        }
                        return true
                    } else {
                        parentLockRunnable = Runnable {
                            parentLockedForSelection = true
                            textView.parent?.requestDisallowInterceptTouchEvent(true)
                        }.also {
                            textView.postDelayed(it, ViewConfiguration.getLongPressTimeout().toLong())
                        }
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val moved = movedPastSlop(event, touchSlop)
                    if (downSpan != null) {
                        if (moved) {
                            clearLinkTouch(textView)
                        }
                        return true
                    }
                    if (!parentLockedForSelection && moved) {
                        clearParentLock(textView)
                        clearLinkLongPress(textView)
                        clearPressedSpan(textView)
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
                    clearLinkLongPress(textView)
                    clearPressedSpan(textView)
                    clearLinkParentLock(textView)
                    downSpan = null
                    downSpanUrl = null

                    if (!consumedLongClick && pressedSpan != null && shortTap && !moved &&
                        (releasedSpan == null || isSameLink(pressedSpan, releasedSpan))
                    ) {
                        pressedSpan.onClick(textView)
                        return true
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    clearParentLock(textView)
                    clearLinkLongPress(textView)
                    clearPressedSpan(textView)
                    clearLinkParentLock(textView)
                    downSpan = null
                    downSpanUrl = null
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

        private fun clearLinkLongPress(textView: TextView) {
            linkLongPressRunnable?.let(textView::removeCallbacks)
            linkLongPressRunnable = null
        }

        private fun clearLinkParentLock(textView: TextView) {
            if (parentLockedForLink) {
                textView.parent?.requestDisallowInterceptTouchEvent(false)
            }
            parentLockedForLink = false
        }

        private fun clearLinkTouch(textView: TextView) {
            clearLinkLongPress(textView)
            clearPressedSpan(textView)
            clearLinkParentLock(textView)
            downSpan = null
            downSpanUrl = null
        }

        private fun isSameLink(pressedSpan: ClickableSpan, releasedSpan: ClickableSpan): Boolean {
            if (pressedSpan === releasedSpan) return true
            val pressedUrl = downSpanUrl ?: (pressedSpan as? URLSpan)?.url
            val releasedUrl = (releasedSpan as? URLSpan)?.url
            return pressedUrl != null && pressedUrl == releasedUrl
        }

        private fun highlightSpan(textView: TextView, span: ClickableSpan?) {
            val text = textView.text as? Spannable ?: return
            if (span == null) return
            val start = text.getSpanStart(span)
            val end = text.getSpanEnd(span)
            if (start >= 0 && end > start) {
                Selection.setSelection(text, start, end)
            }
        }

        private fun clearPressedSpan(textView: TextView) {
            (textView.text as? Spannable)?.let(Selection::removeSelection)
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
            val queryStart = offset.coerceIn(0, text.length)
            val queryEnd = (queryStart + 1).coerceAtMost(text.length)
            val direct = text.getSpans(queryStart, queryEnd, ClickableSpan::class.java)
                .firstOrNull { span ->
                    val start = text.getSpanStart(span)
                    val end = text.getSpanEnd(span)
                    queryStart in start until end
                }
            if (direct != null || queryStart == 0) return direct

            val previous = queryStart - 1
            return text.getSpans(previous, queryStart, ClickableSpan::class.java)
                .firstOrNull { span ->
                    val start = text.getSpanStart(span)
                    val end = text.getSpanEnd(span)
                    previous in start until end
                }
        }
    }
}
