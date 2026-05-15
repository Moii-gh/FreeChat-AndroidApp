package com.example.chatapp.ui.chat

import android.animation.ObjectAnimator
import android.content.Context
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextUtils
import android.util.Property
import android.util.TypedValue
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.random.Random

internal data class TypingAnimationConfig(
    val minTypingDelayMillis: Long = 34L,
    val maxTypingDelayMillis: Long = 88L,
    val minDeleteDelayMillis: Long = 18L,
    val maxDeleteDelayMillis: Long = 42L,
    val beforeTypingDelayMillis: Long = 180L,
    val completedHoldMillis: Long = 1_650L,
    val betweenPhrasesDelayMillis: Long = 260L,
    val cursorFadeDurationMillis: Long = 540L,
    val firstCharacterFadeMillis: Long = 150L
)

internal class TypingAnimationEngine(
    context: Context,
    private val container: FrameLayout,
    private val textView: TypingTitleTextView,
    val config: TypingAnimationConfig = TypingAnimationConfig()
) {
    private val random = Random(SystemClock.elapsedRealtime())
    private val stableMinHeight = max(container.minimumHeight, container.layoutParams.height.takeIf { it > 0 } ?: 0)
    private val cursorInterpolator = PathInterpolator(0.25f, 0f, 0.15f, 1f)
    private val firstCharacterInterpolator = PathInterpolator(0.16f, 1f, 0.3f, 1f)
    private val verticalPaddingPx = 4f.dpToPx(context).toInt()
    private var cursorAnimator: ObjectAnimator? = null

    var visibleText: String = ""
        private set

    init {
        container.clipChildren = false
        container.clipToPadding = false
        textView.isCursorEnabled = false
        textView.cursorAlphaFraction = 1f
    }

    fun prepareForPhrases(phrases: List<String>) {
        if (phrases.isEmpty()) return
        if (container.width > 0) {
            updateStableHeight(phrases)
        } else {
            container.post {
                if (container.width > 0) {
                    updateStableHeight(phrases)
                }
            }
        }
    }

    fun setVisibleText(text: String) {
        renderText(text, animateFirstCharacter = false)
    }

    fun startCursor() {
        textView.isCursorEnabled = true
        if (cursorAnimator?.isStarted == true) return

        cursorAnimator = ObjectAnimator.ofFloat(
            textView,
            CursorAlphaProperty,
            1f,
            0.18f
        ).apply {
            duration = config.cursorFadeDurationMillis
            interpolator = cursorInterpolator
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
    }

    fun stopCursor() {
        cursorAnimator?.cancel()
        cursorAnimator = null
        textView.cursorAlphaFraction = 1f
        textView.isCursorEnabled = false
    }

    suspend fun playPhrase(phrase: String) {
        if (!phrase.startsWith(visibleText)) {
            deleteVisibleText()
            delay(config.betweenPhrasesDelayMillis)
        }

        typeTo(phrase)
        delay(config.completedHoldMillis)
        deleteVisibleText()
        delay(config.betweenPhrasesDelayMillis)
    }

    private suspend fun typeTo(target: String) {
        val totalCodePoints = target.codePointCountSafe()
        var visibleCodePoints = visibleText.codePointCountSafe()
            .coerceAtMost(totalCodePoints)

        if (visibleCodePoints == 0 && totalCodePoints > 0) {
            delay(config.beforeTypingDelayMillis)
        }

        while (visibleCodePoints < totalCodePoints) {
            visibleCodePoints += 1
            val nextText = target.prefixByCodePoints(visibleCodePoints)
            renderText(nextText, animateFirstCharacter = visibleCodePoints == 1)

            val typedCodePoint = target.codePointAtIndex(visibleCodePoints - 1)
            val previousCodePoint = if (visibleCodePoints > 1) {
                target.codePointAtIndex(visibleCodePoints - 2)
            } else {
                null
            }
            delay(naturalTypingDelay(typedCodePoint, previousCodePoint))
        }
    }

    private suspend fun deleteVisibleText() {
        var codePoints = visibleText.codePointCountSafe()
        while (codePoints > 0) {
            val removedCodePoint = visibleText.codePointAtIndex(codePoints - 1)
            codePoints -= 1
            renderText(visibleText.prefixByCodePoints(codePoints), animateFirstCharacter = false)
            delay(naturalDeleteDelay(removedCodePoint))
        }
    }

    private fun renderText(text: String, animateFirstCharacter: Boolean) {
        if (visibleText == text && textView.text?.toString() == text) return

        visibleText = text
        if (textView.text?.toString() != text) {
            textView.text = text
        }
        container.contentDescription = text

        if (animateFirstCharacter && text.isNotEmpty()) {
            textView.animate().cancel()
            textView.alpha = 0.72f
            textView.animate()
                .alpha(1f)
                .setDuration(config.firstCharacterFadeMillis)
                .setInterpolator(firstCharacterInterpolator)
                .start()
        } else if (textView.alpha != 1f) {
            textView.animate().cancel()
            textView.alpha = 1f
        }
    }

    private fun updateStableHeight(phrases: List<String>) {
        val availableWidth = container.width - container.paddingLeft - container.paddingRight
        if (availableWidth <= 0) return

        val measuredTextHeight = phrases.maxOf { phrase ->
            textView.measureTextHeight(phrase, availableWidth)
        }
        val targetHeight = max(stableMinHeight, measuredTextHeight + verticalPaddingPx * 2)
        val params = container.layoutParams
        if (params.height != targetHeight) {
            params.height = targetHeight
            container.layoutParams = params
        }

        val childParams = textView.layoutParams
        if (childParams.height != ViewGroup.LayoutParams.MATCH_PARENT) {
            childParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            textView.layoutParams = childParams
        }
    }

    private fun naturalTypingDelay(currentCodePoint: Int, previousCodePoint: Int?): Long {
        var delayMillis = random.nextLong(config.minTypingDelayMillis, config.maxTypingDelayMillis + 1)
        val current = currentCodePoint.toReadableChar()
        val previous = previousCodePoint?.toReadableChar()

        if (current == ' ') {
            delayMillis += random.nextLong(18L, 62L)
        }
        if (current in PUNCTUATION) {
            delayMillis += random.nextLong(80L, 210L)
        }
        if (previous == ' ' && random.nextFloat() < WORD_START_PAUSE_CHANCE) {
            delayMillis += random.nextLong(45L, 135L)
        }
        if (random.nextFloat() < MICRO_PAUSE_CHANCE) {
            delayMillis += random.nextLong(70L, 190L)
        }

        return delayMillis
    }

    private fun naturalDeleteDelay(removedCodePoint: Int): Long {
        var delayMillis = random.nextLong(config.minDeleteDelayMillis, config.maxDeleteDelayMillis + 1)
        if (removedCodePoint.toReadableChar() == ' ') {
            delayMillis += random.nextLong(12L, 38L)
        }
        return delayMillis
    }

    private fun TypingTitleTextView.measureTextHeight(text: String, availableWidth: Int): Int {
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

    private fun String.codePointCountSafe(): Int =
        codePointCount(0, length)

    private fun String.prefixByCodePoints(codePointCount: Int): String {
        if (codePointCount <= 0) return ""
        val end = offsetByCodePoints(0, codePointCount.coerceAtMost(codePointCountSafe()))
        return substring(0, end)
    }

    private fun String.codePointAtIndex(codePointIndex: Int): Int {
        val charIndex = offsetByCodePoints(0, codePointIndex)
        return codePointAt(charIndex)
    }

    private fun Int.toReadableChar(): Char? =
        takeIf { Character.charCount(it) == 1 }?.toChar()

    private fun Float.dpToPx(context: Context): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            this,
            context.resources.displayMetrics
        )

    private companion object {
        const val MICRO_PAUSE_CHANCE = 0.08f
        const val WORD_START_PAUSE_CHANCE = 0.16f
        val PUNCTUATION = setOf('.', ',', '?', '!', ':', ';', '-')

        val CursorAlphaProperty = object : Property<TypingTitleTextView, Float>(
            Float::class.java,
            "cursorAlphaFraction"
        ) {
            override fun get(view: TypingTitleTextView): Float = view.cursorAlphaFraction

            override fun set(view: TypingTitleTextView, value: Float) {
                view.cursorAlphaFraction = value
            }
        }
    }
}
