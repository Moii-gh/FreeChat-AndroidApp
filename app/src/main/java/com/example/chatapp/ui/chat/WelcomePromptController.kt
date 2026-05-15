package com.example.chatapp.ui.chat

import android.content.Context
import android.os.Bundle
import android.widget.FrameLayout
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.example.chatapp.LocaleHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class WelcomePromptController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    titleContainer: FrameLayout,
    titleView: TypingTitleTextView,
    private val config: TypingAnimationConfig = TypingAnimationConfig()
) : DefaultLifecycleObserver {
    private val typingEngine = TypingAnimationEngine(
        context = context,
        container = titleContainer,
        textView = titleView,
        config = config
    )

    private var cycleJob: Job? = null
    private var prompts: List<String> = emptyList()
    private var promptIndex = 0
    private var startRequested = false
    private var restoredIndexPending = false
    private var restoredVisibleText: String? = null

    init {
        lifecycleOwner.lifecycle.addObserver(this)
    }

    fun restoreState(savedInstanceState: Bundle?) {
        if (savedInstanceState == null || !savedInstanceState.containsKey(KEY_PROMPT_INDEX)) return
        promptIndex = savedInstanceState.getInt(KEY_PROMPT_INDEX, 0).coerceAtLeast(0)
        restoredVisibleText = savedInstanceState.getString(KEY_VISIBLE_TEXT).orEmpty()
        restoredIndexPending = true
    }

    fun saveState(outState: Bundle) {
        outState.putInt(KEY_PROMPT_INDEX, promptIndex)
        outState.putString(KEY_VISIBLE_TEXT, typingEngine.visibleText)
    }

    fun refreshPrompts() {
        val changed = loadPrompts()
        if (prompts.isEmpty()) return

        promptIndex = promptIndex.coerceIn(0, prompts.lastIndex)
        typingEngine.prepareForPhrases(prompts)
        if (changed && startRequested) {
            pauseLoop()
            typingEngine.setVisibleText("")
            startLoopIfReady()
        }
    }

    fun start(resetIndex: Boolean) {
        val promptsChanged = loadPrompts()
        if (prompts.isEmpty()) return

        if (resetIndex && !restoredIndexPending) {
            promptIndex = 0
            restoredVisibleText = null
            typingEngine.setVisibleText("")
        }

        promptIndex = promptIndex.coerceIn(0, prompts.lastIndex)
        typingEngine.prepareForPhrases(prompts)

        val wasAlreadyRequested = startRequested
        startRequested = true

        restoredVisibleText?.let { visibleText ->
            typingEngine.setVisibleText(visibleText)
            restoredVisibleText = null
        }
        restoredIndexPending = false

        if (promptsChanged && wasAlreadyRequested) {
            pauseLoop()
            typingEngine.setVisibleText("")
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
        if (prompts.isEmpty()) return
        if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return

        typingEngine.startCursor()
        cycleJob = lifecycleOwner.lifecycleScope.launch {
            while (isActive && startRequested && prompts.isNotEmpty()) {
                val safeIndex = promptIndex.coerceIn(0, prompts.lastIndex)
                promptIndex = safeIndex
                typingEngine.playPhrase(prompts[safeIndex])
                promptIndex = (safeIndex + 1) % prompts.size
            }
        }
    }

    private fun pauseLoop() {
        cycleJob?.cancel()
        cycleJob = null
        typingEngine.stopCursor()
    }

    private companion object {
        const val KEY_PROMPT_INDEX = "welcome_prompt_index"
        const val KEY_VISIBLE_TEXT = "welcome_prompt_visible_text"
    }
}
