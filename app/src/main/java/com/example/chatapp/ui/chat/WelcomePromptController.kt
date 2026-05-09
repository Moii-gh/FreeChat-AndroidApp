package com.example.chatapp.ui.chat

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.TextView
import com.example.chatapp.LocaleHelper

internal class WelcomePromptController(
    private val context: Context,
    private val titleView: TextView
) {
    private val handler = Handler(Looper.getMainLooper())
    private var animator: AnimatorSet? = null
    private var prompts: List<String> = emptyList()
    private var promptIndex = 0
    private var promptText = ""
    private var visibleChars = 0
    private var cursorVisible = true
    private var isRunning = false

    private val rotationRunnable = object : Runnable {
        override fun run() {
            if (!isRunning || prompts.isEmpty()) return
            showNextPrompt()
            handler.postDelayed(this, ROTATION_MS)
        }
    }

    private val typingRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            if (visibleChars < promptText.length) {
                visibleChars += 1
                renderPrompt()
                handler.postDelayed(this, TYPE_STEP_MS)
            } else {
                renderPrompt(showCursor = false)
            }
        }
    }

    private val cursorRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            cursorVisible = !cursorVisible
            if (visibleChars < promptText.length) {
                renderPrompt()
            }
            handler.postDelayed(this, CURSOR_BLINK_MS)
        }
    }

    fun refreshPrompts() {
        val localizedPrompts = LocaleHelper.getStringList(context, "welcome_prompt")
            .ifEmpty { listOf(LocaleHelper.getString(context, "welcome_question")) }
        prompts = localizedPrompts
        if (promptIndex >= prompts.size) {
            promptIndex = 0
        }
    }

    fun start(resetIndex: Boolean) {
        refreshPrompts()
        if (prompts.isEmpty()) return

        if (resetIndex) {
            promptIndex = 0
        }

        isRunning = true
        handler.removeCallbacks(rotationRunnable)
        handler.removeCallbacks(typingRunnable)
        handler.removeCallbacks(cursorRunnable)
        animator?.cancel()

        titleView.alpha = 1f
        titleView.translationY = 0f
        typePrompt(prompts[promptIndex])
        handler.postDelayed(rotationRunnable, ROTATION_MS)
        handler.postDelayed(cursorRunnable, CURSOR_BLINK_MS)
    }

    fun stop() {
        isRunning = false
        animator?.cancel()
        animator = null
        handler.removeCallbacks(rotationRunnable)
        handler.removeCallbacks(typingRunnable)
        handler.removeCallbacks(cursorRunnable)
    }

    private fun showNextPrompt() {
        if (prompts.isEmpty()) return
        promptIndex = (promptIndex + 1) % prompts.size
        val nextPrompt = prompts[promptIndex]

        animator?.cancel()
        handler.removeCallbacks(typingRunnable)

        val fadeOut = ObjectAnimator.ofFloat(titleView, View.ALPHA, 1f, 0f)
        val slideOut = ObjectAnimator.ofFloat(titleView, View.TRANSLATION_Y, 0f, -8.dpToPx())
        val fadeIn = ObjectAnimator.ofFloat(titleView, View.ALPHA, 0f, 1f)
        val slideIn = ObjectAnimator.ofFloat(titleView, View.TRANSLATION_Y, 10.dpToPx(), 0f)

        animator = AnimatorSet().apply {
            playTogether(fadeOut, slideOut)
            duration = ANIMATION_MS
            interpolator = AccelerateDecelerateInterpolator()
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!isRunning) return
                    typePrompt(nextPrompt)
                    AnimatorSet().apply {
                        playTogether(fadeIn, slideIn)
                        duration = ANIMATION_MS
                        interpolator = AccelerateDecelerateInterpolator()
                        start()
                    }
                }
            })
            start()
        }
    }

    private fun typePrompt(prompt: String) {
        promptText = prompt
        visibleChars = 0
        cursorVisible = true
        renderPrompt()
        handler.postDelayed(typingRunnable, TYPE_STEP_MS)
    }

    private fun renderPrompt(showCursor: Boolean = true) {
        val visibleText = promptText.take(visibleChars)
        val cursor = if (showCursor && cursorVisible) CURSOR else ""
        titleView.text = visibleText + cursor
    }

    private fun Int.dpToPx(): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            toFloat(),
            context.resources.displayMetrics
        )

    private companion object {
        const val ROTATION_MS = 5_000L
        const val ANIMATION_MS = 260L
        const val TYPE_STEP_MS = 26L
        const val CURSOR_BLINK_MS = 460L
        const val CURSOR = "|"
    }
}
