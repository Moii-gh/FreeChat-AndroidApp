package com.example.chatapp.ui.chat

import android.content.Context
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.TextView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.example.chatapp.LocaleHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class WelcomePromptController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    titleContainer: FrameLayout,
    primaryTitleView: TextView,
    secondaryTitleView: TextView,
    private val timing: WelcomePromptTiming = WelcomePromptTiming()
) : DefaultLifecycleObserver {
    private val rotator = SmoothTextRotator(
        context = context,
        container = titleContainer,
        primaryView = primaryTitleView,
        secondaryView = secondaryTitleView,
        timing = timing
    )

    private var cycleJob: Job? = null
    private var prompts: List<String> = emptyList()
    private var promptIndex = 0
    private var startRequested = false
    private var restoredIndexPending = false

    init {
        lifecycleOwner.lifecycle.addObserver(this)
    }

    fun restoreState(savedInstanceState: Bundle?) {
        if (savedInstanceState == null || !savedInstanceState.containsKey(KEY_PROMPT_INDEX)) return
        promptIndex = savedInstanceState.getInt(KEY_PROMPT_INDEX, 0).coerceAtLeast(0)
        restoredIndexPending = true
    }

    fun saveState(outState: Bundle) {
        outState.putInt(KEY_PROMPT_INDEX, promptIndex)
    }

    fun refreshPrompts() {
        val changed = loadPrompts()
        if (prompts.isEmpty()) return

        rotator.prepareForTexts(prompts)
        promptIndex = promptIndex.coerceIn(0, prompts.lastIndex)
        if (startRequested && changed) {
            rotator.showImmediately(prompts[promptIndex])
        }
    }

    fun start(resetIndex: Boolean) {
        val promptsChanged = loadPrompts()
        if (prompts.isEmpty()) return

        if (resetIndex && !restoredIndexPending) {
            promptIndex = 0
        }
        restoredIndexPending = false
        promptIndex = promptIndex.coerceIn(0, prompts.lastIndex)

        rotator.prepareForTexts(prompts)

        val wasAlreadyRequested = startRequested
        startRequested = true
        val currentPrompt = prompts[promptIndex]
        if (!wasAlreadyRequested || promptsChanged || rotator.currentText != currentPrompt) {
            rotator.showImmediately(currentPrompt)
        }

        startLoopIfReady()
    }

    fun stop() {
        startRequested = false
        pauseLoop()
    }

    override fun onStart(owner: LifecycleOwner) {
        startLoopIfReady()
    }

    override fun onStop(owner: LifecycleOwner) {
        pauseLoop()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        stop()
        lifecycleOwner.lifecycle.removeObserver(this)
    }

    private fun loadPrompts(): Boolean {
        val nextPrompts = LocaleHelper.getStringList(context, "welcome_prompt")
            .ifEmpty { listOf(LocaleHelper.getString(context, "welcome_question")) }
            .filter { it.isNotBlank() }

        if (nextPrompts == prompts) return false

        prompts = nextPrompts
        if (prompts.isEmpty()) {
            promptIndex = 0
        } else if (promptIndex >= prompts.size) {
            promptIndex = 0
        }
        return true
    }

    private fun startLoopIfReady() {
        if (!startRequested) return
        if (cycleJob?.isActive == true) return
        if (prompts.size < MIN_PROMPTS_TO_ROTATE) return
        if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return

        cycleJob = lifecycleOwner.lifecycleScope.launch {
            while (isActive && startRequested) {
                delay(timing.visibleDurationMillis)
                if (!isActive || !startRequested || prompts.size < MIN_PROMPTS_TO_ROTATE) {
                    continue
                }

                val nextIndex = (promptIndex + 1) % prompts.size
                val completed = rotator.transitionTo(prompts[nextIndex])
                if (completed) {
                    promptIndex = nextIndex
                }
            }
        }
    }

    private fun pauseLoop() {
        cycleJob?.cancel()
        cycleJob = null
        rotator.cancelTransition()
    }

    private companion object {
        const val KEY_PROMPT_INDEX = "welcome_prompt_index"
        const val MIN_PROMPTS_TO_ROTATE = 2
    }
}
