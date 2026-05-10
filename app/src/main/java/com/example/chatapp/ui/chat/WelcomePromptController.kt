package com.example.chatapp.ui.chat

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import com.example.chatapp.LocaleHelper

internal class WelcomePromptController(
    private val context: Context,
    private val titleView: TextView
) {
    private val handler = Handler(Looper.getMainLooper())
    private var prompts: List<String> = emptyList()
    private var promptIndex = 0
    private var charIndex = 0
    private var isRunning = false
    private var runId = 0
    private var typingTask: Runnable? = null
    private var rotateTask: Runnable? = null

    fun refreshPrompts() {
        prompts = LocaleHelper.getStringList(context, "welcome_prompt")
            .ifEmpty { listOf(LocaleHelper.getString(context, "welcome_question")) }
        if (promptIndex >= prompts.size) {
            promptIndex = 0
        }
    }

    fun start(resetIndex: Boolean) {
        refreshPrompts()
        if (prompts.isEmpty()) return

        if (resetIndex) {
            promptIndex = 0
            restartAnimation()
            return
        }

        if (!isRunning) {
            restartAnimation()
        }
    }

    fun stop() {
        isRunning = false
        runId += 1
        clearScheduledWork()
        titleView.animate().cancel()
    }

    private fun restartAnimation() {
        isRunning = true
        runId += 1
        clearScheduledWork()
        titleView.animate().cancel()
        titleView.alpha = 1f
        titleView.translationY = 0f
        startTyping(runId)
    }

    private fun startTyping(token: Int) {
        val prompt = prompts.getOrNull(promptIndex).orEmpty()
        charIndex = 0
        titleView.text = ""

        fun scheduleNext() {
            typingTask = Runnable {
                if (!isRunning || token != runId) return@Runnable
                if (charIndex < prompt.length) {
                    charIndex += 1
                    titleView.text = prompt.take(charIndex)
                    scheduleNext()
                } else {
                    scheduleRotation(token)
                }
            }
            handler.postDelayed(typingTask ?: return, TYPE_STEP_MS)
        }

        scheduleNext()
    }

    private fun scheduleRotation(token: Int) {
        rotateTask = Runnable {
            if (!isRunning || token != runId || prompts.isEmpty()) return@Runnable
            promptIndex = (promptIndex + 1) % prompts.size
            titleView.animate()
                .alpha(0f)
                .translationY(-FADE_OFFSET_PX)
                .setDuration(FADE_MS)
                .withEndAction {
                    if (!isRunning || token != runId) return@withEndAction
                    titleView.translationY = FADE_OFFSET_PX
                    titleView.alpha = 0f
                    titleView.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(FADE_MS)
                        .start()
                    startTyping(token)
                }
                .start()
        }
        handler.postDelayed(rotateTask ?: return, ROTATION_MS)
    }

    private fun clearScheduledWork() {
        typingTask?.let(handler::removeCallbacks)
        rotateTask?.let(handler::removeCallbacks)
        typingTask = null
        rotateTask = null
    }

    private companion object {
        const val TYPE_STEP_MS = 24L
        const val ROTATION_MS = 4_000L
        const val FADE_MS = 180L
        const val FADE_OFFSET_PX = 10f
    }
}
