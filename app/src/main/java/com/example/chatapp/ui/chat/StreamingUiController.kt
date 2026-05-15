package com.example.chatapp.ui.chat

import android.os.Handler
import android.os.Looper
import com.example.chatapp.ui.AssistantMessageWrapper

internal class StreamingUiController(
    private val isCurrentAssistantMessage: (AssistantMessageWrapper) -> Boolean,
    private val onContentApplied: () -> Unit,
    private val onCancelAutoScroll: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var pendingRequestId = 0L
    private var pendingWrapper: AssistantMessageWrapper? = null
    var pendingText: String? = null
        private set
    private var lastRenderedText: String? = null
    private var flushRunnable: Runnable? = null

    var activeGenerationId = 0L
        private set

    fun nextGenerationId(): Long {
        activeGenerationId += 1
        cancelPendingWork(cancelScroll = true)
        lastRenderedText = null
        return activeGenerationId
    }

    fun finishGeneration(requestId: Long) {
        if (requestId == activeGenerationId) {
            activeGenerationId += 1
        }
        cancelScheduledFlush()
        clearPendingText()
        lastRenderedText = null
    }

    fun invalidate(cancelScroll: Boolean = true) {
        activeGenerationId += 1
        cancelPendingWork(cancelScroll = cancelScroll)
        lastRenderedText = null
    }

    fun isActive(requestId: Long, wrapper: AssistantMessageWrapper): Boolean =
        requestId == activeGenerationId && isCurrentAssistantMessage(wrapper)

    fun enqueue(requestId: Long, wrapper: AssistantMessageWrapper, text: String) {
        if (!isActive(requestId, wrapper)) return
        if (text == pendingText || text == lastRenderedText) return

        pendingRequestId = requestId
        pendingWrapper = wrapper
        pendingText = text

        if (flushRunnable != null) return
        val runnable = Runnable {
            flushRunnable = null
            val request = pendingRequestId
            val targetWrapper = pendingWrapper ?: return@Runnable
            val targetText = pendingText ?: return@Runnable
            apply(request, targetWrapper, targetText, isFinal = false)
        }
        flushRunnable = runnable
        handler.postDelayed(runnable, flushDelayFor(text))
    }

    fun flush(requestId: Long, wrapper: AssistantMessageWrapper, text: String, isFinal: Boolean) {
        cancelScheduledFlush()
        pendingRequestId = requestId
        pendingWrapper = wrapper
        pendingText = text
        apply(requestId, wrapper, text, isFinal)
    }

    fun cancelScheduledFlush() {
        flushRunnable?.let { handler.removeCallbacks(it) }
        flushRunnable = null
    }

    private fun apply(
        requestId: Long,
        wrapper: AssistantMessageWrapper,
        text: String,
        isFinal: Boolean
    ) {
        if (!isActive(requestId, wrapper)) return
        if (!isFinal && text == lastRenderedText) return

        clearPendingText()
        lastRenderedText = text

        wrapper.updateContent(text, animate = false, isFinal = isFinal)
        onContentApplied()
    }

    private fun cancelPendingWork(cancelScroll: Boolean) {
        cancelScheduledFlush()
        clearPendingText()
        if (cancelScroll) {
            onCancelAutoScroll()
        }
    }

    private fun clearPendingText() {
        pendingRequestId = 0L
        pendingWrapper = null
        pendingText = null
    }

    private companion object {
        const val FLUSH_INTERVAL_MS = 50L
        const val LARGE_FLUSH_INTERVAL_MS = 120L
        const val HUGE_RESPONSE_CHARS = 40_000

        fun flushDelayFor(text: String): Long =
            if (text.length >= HUGE_RESPONSE_CHARS) LARGE_FLUSH_INTERVAL_MS else FLUSH_INTERVAL_MS
    }
}
