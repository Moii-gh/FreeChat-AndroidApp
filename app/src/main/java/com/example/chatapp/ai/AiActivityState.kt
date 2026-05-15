package com.example.chatapp.ai

sealed class AiActivityState {
    data object Thinking : AiActivityState()
    data object WebSearching : AiActivityState()
    data object GeneratingImage : AiActivityState()
    data object EditingImage : AiActivityState()
    data object AnalyzingImage : AiActivityState()
    data object ExecutingCode : AiActivityState()
    data object ReadingFiles : AiActivityState()
    data object LookingUpMemory : AiActivityState()
    data object Translating : AiActivityState()
    data object Summarizing : AiActivityState()
    data object TranscribingAudio : AiActivityState()
    data object ProcessingRequest : AiActivityState()
    data object AnalyzingSearchResults : AiActivityState()
    data object FormingAnswer : AiActivityState()
    data class Custom(val text: String) : AiActivityState()
}

data class AiActivitySnapshot(
    val generationId: Long,
    val state: AiActivityState,
    val queuedTransitions: Int = 0,
    val activeTools: Int = 0,
    val sequence: Long = 0L
)
