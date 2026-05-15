package com.example.chatapp.ai

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AiActivityStateManager(
    private val scope: CoroutineScope,
    private val minTransitionIntervalMs: Long = DEFAULT_MIN_TRANSITION_INTERVAL_MS,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val _state = MutableStateFlow<AiActivitySnapshot?>(null)
    val state: StateFlow<AiActivitySnapshot?> = _state.asStateFlow()

    private val transitionQueue = ArrayDeque<AiActivityState>()
    private val activeTools = linkedMapOf<String, AiActivityState>()
    private var activeGenerationId = 0L
    private var sequence = 0L
    private var lastAppliedAt = 0L
    private var queueJob: Job? = null

    fun begin(generationId: Long, initialState: AiActivityState = AiActivityState.Thinking) {
        activeGenerationId = generationId
        activeTools.clear()
        transitionQueue.clear()
        queueJob?.cancel()
        queueJob = null
        applyState(initialState, generationId, force = true)
    }

    fun update(generationId: Long, state: AiActivityState) {
        if (!isActive(generationId)) return
        enqueueOrApply(state, generationId)
    }

    fun updateFromTool(generationId: Long, toolName: String?) {
        update(generationId, AiActivityToolMapper.fromToolName(toolName))
    }

    fun startTool(generationId: Long, toolCallId: String?, toolName: String?) {
        if (!isActive(generationId)) return
        val key = toolCallId?.takeIf { it.isNotBlank() } ?: toolName?.takeIf { it.isNotBlank() } ?: "tool"
        activeTools[key] = AiActivityToolMapper.fromToolName(toolName)
        enqueueOrApply(activeToolState(), generationId)
    }

    fun finishTool(generationId: Long, toolCallId: String?) {
        if (!isActive(generationId)) return
        if (!toolCallId.isNullOrBlank()) {
            activeTools.remove(toolCallId)
        } else if (activeTools.isNotEmpty()) {
            activeTools.remove(activeTools.keys.last())
        }
        enqueueOrApply(
            if (activeTools.isEmpty()) AiActivityState.AnalyzingSearchResults else activeToolState(),
            generationId
        )
    }

    fun markStreamStarted(generationId: Long) {
        if (!isActive(generationId)) return
        transitionQueue.clear()
        queueJob?.cancel()
        queueJob = null
        _state.value = null
    }

    fun complete(generationId: Long) {
        if (!isActive(generationId)) return
        clear()
    }

    fun fail(generationId: Long) {
        if (!isActive(generationId)) return
        clear()
    }

    fun cancelActive() {
        clear()
    }

    private fun enqueueOrApply(state: AiActivityState, generationId: Long) {
        if (!isActive(generationId)) return
        if (_state.value?.state == state && transitionQueue.isEmpty()) return

        val now = clock()
        val elapsed = now - lastAppliedAt
        if (elapsed >= minTransitionIntervalMs || _state.value == null) {
            applyState(state, generationId)
            return
        }

        if (transitionQueue.lastOrNull() != state) {
            transitionQueue.addLast(state)
        }
        while (transitionQueue.size > MAX_QUEUED_TRANSITIONS) {
            transitionQueue.removeFirst()
        }
        rescheduleQueue(generationId, minTransitionIntervalMs - elapsed)
    }

    private fun rescheduleQueue(generationId: Long, delayMs: Long) {
        if (queueJob?.isActive == true) return
        queueJob = scope.launch {
            delay(delayMs.coerceAtLeast(0L))
            while (isActive(generationId) && transitionQueue.isNotEmpty()) {
                val next = transitionQueue.removeFirst()
                applyState(next, generationId)
                if (transitionQueue.isNotEmpty()) {
                    delay(minTransitionIntervalMs)
                }
            }
            queueJob = null
        }
    }

    private fun applyState(
        state: AiActivityState,
        generationId: Long,
        force: Boolean = false
    ) {
        if (!force && !isActive(generationId)) return
        lastAppliedAt = clock()
        sequence += 1
        _state.value = AiActivitySnapshot(
            generationId = generationId,
            state = state,
            queuedTransitions = transitionQueue.size,
            activeTools = activeTools.size,
            sequence = sequence
        )
    }

    private fun activeToolState(): AiActivityState =
        if (activeTools.size > 1) {
            AiActivityState.ProcessingRequest
        } else {
            activeTools.values.lastOrNull() ?: AiActivityState.ProcessingRequest
        }

    private fun isActive(generationId: Long): Boolean =
        activeGenerationId == generationId && generationId != 0L

    private fun clear() {
        activeGenerationId = 0L
        activeTools.clear()
        transitionQueue.clear()
        queueJob?.cancel()
        queueJob = null
        _state.value = null
    }

    private companion object {
        const val DEFAULT_MIN_TRANSITION_INTERVAL_MS = 180L
        const val MAX_QUEUED_TRANSITIONS = 4
    }
}
