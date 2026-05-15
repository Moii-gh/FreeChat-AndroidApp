package com.example.chatapp.ui.chat

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.text.Layout
import android.text.StaticLayout
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt

internal data class WelcomePromptTiming(
    val visibleDurationMillis: Long = 3_800L,
    val transitionDurationMillis: Long = 620L,
    val verticalTravelDp: Float = 10f
)

internal class SmoothTextRotator(
    context: Context,
    private val container: FrameLayout,
    primaryView: TextView,
    secondaryView: TextView,
    private val timing: WelcomePromptTiming = WelcomePromptTiming()
) {
    private val textViews = arrayOf(primaryView, secondaryView)
    private val travelPx = timing.verticalTravelDp.dpToPx(context)
    private val stableMinHeight = max(container.minimumHeight, container.layoutParams.height.takeIf { it > 0 } ?: 0)
    private val transitionInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)

    private var currentView: TextView = primaryView
    private var nextView: TextView = secondaryView
    private var animator: ValueAnimator? = null

    var currentText: String = ""
        private set

    init {
        container.clipChildren = false
        container.clipToPadding = false
        textViews.forEach { view ->
            view.alpha = 0f
            view.translationY = 0f
            view.visibility = View.VISIBLE
            view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        currentView.alpha = 1f
    }

    fun prepareForTexts(texts: List<String>) {
        if (texts.isEmpty()) return
        if (container.width > 0) {
            updateStableHeight(texts)
        } else {
            container.post {
                if (container.width > 0) {
                    updateStableHeight(texts)
                }
            }
        }
    }

    fun showImmediately(text: String) {
        animator?.cancel()
        animator = null
        currentText = text
        setTextIfChanged(currentView, text)
        container.contentDescription = text

        currentView.alpha = 1f
        currentView.translationY = 0f
        nextView.alpha = 0f
        nextView.translationY = 0f
        clearHardwareLayers()
    }

    suspend fun transitionTo(text: String): Boolean {
        if (text == currentText) return true

        return suspendCancellableCoroutine { continuation ->
            animator?.cancel()

            val outgoing = currentView
            val incoming = nextView
            setTextIfChanged(incoming, text)
            incoming.alpha = 0f
            incoming.translationY = travelPx
            outgoing.alpha = 1f
            outgoing.translationY = 0f
            enableHardwareLayers(outgoing, incoming)

            var cancelled = false
            val transition = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = timing.transitionDurationMillis
                interpolator = transitionInterpolator
                addUpdateListener { animator ->
                    val progress = animator.animatedValue as Float
                    outgoing.alpha = 1f - progress
                    outgoing.translationY = -travelPx * progress
                    incoming.alpha = progress
                    incoming.translationY = travelPx * (1f - progress)
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationCancel(animation: Animator) {
                        cancelled = true
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        if (animator === animation) {
                            animator = null
                        }

                        if (cancelled) {
                            settleOnCurrent(outgoing, incoming)
                            if (continuation.isActive) {
                                continuation.resume(false)
                            }
                            return
                        }

                        finishTransition(outgoing, incoming, text)
                        if (continuation.isActive) {
                            continuation.resume(true)
                        }
                    }
                })
            }

            animator = transition
            continuation.invokeOnCancellation {
                if (animator === transition) {
                    transition.cancel()
                }
            }
            transition.start()
        }
    }

    fun cancelTransition() {
        animator?.cancel()
        animator = null
        settleOnCurrent(currentView, nextView)
    }

    private fun updateStableHeight(texts: List<String>) {
        val availableWidth = container.width - container.paddingLeft - container.paddingRight
        if (availableWidth <= 0) return

        val measuredTextHeight = texts.maxOf { text -> currentView.measureTextHeight(text, availableWidth) }
        val targetHeight = max(stableMinHeight, measuredTextHeight + (travelPx * 2f).roundToInt())
        val params = container.layoutParams
        if (params.height != targetHeight) {
            params.height = targetHeight
            container.layoutParams = params
            textViews.forEach { view ->
                val childParams = view.layoutParams
                if (childParams.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                    childParams.height = ViewGroup.LayoutParams.MATCH_PARENT
                    view.layoutParams = childParams
                }
            }
        }
    }

    private fun finishTransition(outgoing: TextView, incoming: TextView, text: String) {
        currentText = text
        container.contentDescription = text

        incoming.alpha = 1f
        incoming.translationY = 0f
        outgoing.alpha = 0f
        outgoing.translationY = 0f

        currentView = incoming
        nextView = outgoing
        clearHardwareLayers()
    }

    private fun settleOnCurrent(outgoing: TextView, incoming: TextView) {
        outgoing.alpha = 1f
        outgoing.translationY = 0f
        incoming.alpha = 0f
        incoming.translationY = 0f
        clearHardwareLayers()
    }

    private fun enableHardwareLayers(outgoing: TextView, incoming: TextView) {
        outgoing.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        incoming.setLayerType(View.LAYER_TYPE_HARDWARE, null)
    }

    private fun clearHardwareLayers() {
        textViews.forEach { view ->
            view.setLayerType(View.LAYER_TYPE_NONE, null)
        }
    }

    private fun setTextIfChanged(view: TextView, text: String) {
        if (view.text?.toString() != text) {
            view.text = text
        }
    }

    private fun TextView.measureTextHeight(text: String, availableWidth: Int): Int {
        val maxLinesForLayout = maxLines.takeIf { it > 0 } ?: Int.MAX_VALUE
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, availableWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setEllipsizedWidth(availableWidth)
            .setIncludePad(includeFontPadding)
            .setLineSpacing(lineSpacingExtra, lineSpacingMultiplier)
            .setMaxLines(maxLinesForLayout)
            .build()
        return layout.height + compoundPaddingTop + compoundPaddingBottom
    }

    private fun Float.dpToPx(context: Context): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            this,
            context.resources.displayMetrics
        )
}
