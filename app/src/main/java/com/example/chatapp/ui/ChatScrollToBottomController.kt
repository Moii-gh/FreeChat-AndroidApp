package com.example.chatapp.ui

import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ScrollView
import androidx.core.view.isGone
import androidx.core.view.isVisible
import kotlin.math.roundToInt

private const val SCROLL_THROTTLE_MS = 80L
private const val SHOW_DURATION_MS = 190L
private const val HIDE_DURATION_MS = 140L
private const val SHOW_AFTER_VIEWPORTS = 2.2f
private const val HIDE_UNDER_VIEWPORTS = 0.85f
private const val HIDDEN_SCALE = 0.84f

class ChatScrollToBottomController(
    private val scrollView: ScrollView,
    private val button: View,
    private val bottomScrollY: () -> Int,
    private val isPinnedToBottom: () -> Boolean,
    private val onScrollStateChanged: () -> Unit,
    private val onScrollToBottom: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val throttledUpdate = Runnable {
        isUpdateScheduled = false
        updateVisibility()
    }

    private var isUpdateScheduled = false
    private var isButtonVisible = false
    private var suppressUntilNearBottom = false

    fun attach() {
        button.alpha = 0f
        button.scaleX = HIDDEN_SCALE
        button.scaleY = HIDDEN_SCALE
        button.isGone = true
        button.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            suppressUntilNearBottom = true
            hide(animated = true)
            onScrollToBottom()
        }
        scrollView.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            if (scrollY < oldScrollY) {
                suppressUntilNearBottom = false
            }
            onScrollStateChanged()
            scheduleUpdate()
        }
        refresh()
    }

    fun refresh() {
        handler.removeCallbacks(throttledUpdate)
        isUpdateScheduled = false
        onScrollStateChanged()
        updateVisibility()
    }

    fun detach() {
        handler.removeCallbacks(throttledUpdate)
        isUpdateScheduled = false
        scrollView.setOnScrollChangeListener(null)
        button.animate().cancel()
        button.setOnClickListener(null)
    }

    private fun scheduleUpdate() {
        if (isUpdateScheduled) return
        isUpdateScheduled = true
        handler.postDelayed(throttledUpdate, SCROLL_THROTTLE_MS)
    }

    private fun updateVisibility() {
        val shouldShow = shouldShowButton()
        if (shouldShow == isButtonVisible) return
        if (shouldShow) {
            show()
        } else {
            hide(animated = true)
        }
    }

    private fun shouldShowButton(): Boolean {
        if (!scrollView.isShown) return false
        if (isPinnedToBottom()) return false
        val viewportHeight = scrollView.height
        if (viewportHeight <= 0) return false

        val maxScrollY = bottomScrollY()
        if (maxScrollY <= 0) return false

        val distanceFromBottom = maxScrollY - scrollView.scrollY
        val showThreshold = (viewportHeight * SHOW_AFTER_VIEWPORTS).roundToInt()
        val hideThreshold = (viewportHeight * HIDE_UNDER_VIEWPORTS).roundToInt()
        if (suppressUntilNearBottom) {
            if (distanceFromBottom <= hideThreshold) {
                suppressUntilNearBottom = false
            } else {
                return false
            }
        }

        return if (isButtonVisible) {
            distanceFromBottom > hideThreshold
        } else {
            distanceFromBottom > showThreshold
        }
    }

    private fun show() {
        isButtonVisible = true
        button.animate().cancel()
        button.isVisible = true
        button.alpha = 0f
        button.scaleX = HIDDEN_SCALE
        button.scaleY = HIDDEN_SCALE
        button.translationY = button.resources.displayMetrics.density * 8f
        button.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(SHOW_DURATION_MS)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .start()
    }

    private fun hide(animated: Boolean) {
        if (!isButtonVisible && button.isGone) return
        isButtonVisible = false
        button.animate().cancel()
        if (!animated) {
            button.isGone = true
            button.alpha = 0f
            button.scaleX = HIDDEN_SCALE
            button.scaleY = HIDDEN_SCALE
            button.translationY = 0f
            return
        }

        button.animate()
            .alpha(0f)
            .scaleX(HIDDEN_SCALE)
            .scaleY(HIDDEN_SCALE)
            .translationY(button.resources.displayMetrics.density * 6f)
            .setDuration(HIDE_DURATION_MS)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                if (!isButtonVisible) {
                    button.isGone = true
                    button.translationY = 0f
                }
            }
            .start()
    }
}
