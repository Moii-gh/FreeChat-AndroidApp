package com.example.chatapp.ai

import com.example.chatapp.ChatMode
import org.json.JSONArray
import org.json.JSONObject

object AiActivityToolMapper {

    fun initialStateForRequest(
        currentMode: String?,
        messagesToKeep: List<JSONObject>
    ): AiActivityState {
        val lastUserMessage = messagesToKeep.lastOrNull { it.optString("role") == "user" }
        val hasImageInput = lastUserMessage?.let(::hasImageInput) == true
        val hasFileInput = lastUserMessage?.let(::hasFileInput) == true

        return when (normalize(currentMode)) {
            ChatMode.CREATE_IMAGE -> if (hasImageInput) {
                AiActivityState.EditingImage
            } else {
                AiActivityState.GeneratingImage
            }
            ChatMode.SEARCH, ChatMode.SHOPPING, "web_search", "search_web" -> AiActivityState.WebSearching
            "file_search", "file-search" -> AiActivityState.ReadingFiles
            "translate", "translation" -> AiActivityState.Translating
            "summary", "summarize", "summarization" -> AiActivityState.Summarizing
            else -> when {
                hasImageInput -> AiActivityState.AnalyzingImage
                hasFileInput -> AiActivityState.ReadingFiles
                else -> AiActivityState.Thinking
            }
        }
    }

    fun fromToolName(toolName: String?): AiActivityState {
        val normalized = normalize(toolName)
        return when {
            normalized.isBlank() -> AiActivityState.ProcessingRequest
            normalized in WEB_SEARCH_TOOLS || normalized.contains("web_search") || normalized.contains("browse") ->
                AiActivityState.WebSearching
            normalized in IMAGE_GENERATION_TOOLS || normalized.contains("image_generation") || normalized.contains("generate_image") ->
                AiActivityState.GeneratingImage
            normalized in IMAGE_EDIT_TOOLS || normalized.contains("image_edit") || normalized.contains("edit_image") ->
                AiActivityState.EditingImage
            normalized.contains("vision") || normalized.contains("image_analysis") || normalized.contains("analyze_image") ->
                AiActivityState.AnalyzingImage
            normalized.contains("code") || normalized.contains("python") || normalized.contains("execution") ->
                AiActivityState.ExecutingCode
            normalized.contains("file_search") || normalized.contains("search_files") || normalized.contains("document") ->
                AiActivityState.ReadingFiles
            normalized.contains("memory") ->
                AiActivityState.LookingUpMemory
            normalized.contains("translat") ->
                AiActivityState.Translating
            normalized.contains("summar") ->
                AiActivityState.Summarizing
            normalized.contains("audio") || normalized.contains("transcription") || normalized.contains("speech") ->
                AiActivityState.TranscribingAudio
            normalized.contains("reason") || normalized.contains("analysis") ->
                AiActivityState.Thinking
            else -> AiActivityState.ProcessingRequest
        }
    }

    fun fromActivityName(activityName: String?, customText: String? = null): AiActivityState {
        val normalized = normalize(activityName)
        val custom = customText?.trim()?.takeIf { it.isNotBlank() }
        return when (normalized) {
            "thinking", "reasoning", "analyzing_request" -> AiActivityState.Thinking
            "web_searching", "web_search", "searching_web" -> AiActivityState.WebSearching
            "generating_image", "image_generation" -> AiActivityState.GeneratingImage
            "editing_image", "image_edit" -> AiActivityState.EditingImage
            "analyzing_image", "vision", "image_analysis" -> AiActivityState.AnalyzingImage
            "executing_code", "code_execution" -> AiActivityState.ExecutingCode
            "reading_files", "file_search", "searching_files" -> AiActivityState.ReadingFiles
            "memory_lookup", "looking_up_memory" -> AiActivityState.LookingUpMemory
            "translation", "translating" -> AiActivityState.Translating
            "summarization", "summarizing" -> AiActivityState.Summarizing
            "audio_transcription", "transcribing_audio" -> AiActivityState.TranscribingAudio
            "multi_tool_chain", "processing", "processing_request" -> AiActivityState.ProcessingRequest
            "analyzing_search_results" -> AiActivityState.AnalyzingSearchResults
            "forming_answer", "responding" -> AiActivityState.FormingAnswer
            "custom" -> custom?.let(AiActivityState::Custom) ?: AiActivityState.ProcessingRequest
            else -> custom?.let(AiActivityState::Custom) ?: fromToolName(activityName)
        }
    }

    fun fromProviderEvent(event: JSONObject): AiActivityState? {
        event.optJSONObject("freechat_activity")?.let { activity ->
            return fromActivityName(
                activity.optString("state").ifBlank { activity.optString("type") },
                activity.optString("text").ifBlank { null }
            )
        }

        fromEventType(event.optString("type"))?.let { return it }
        fromToolContainer(event.optJSONObject("item"))?.let { return it }
        fromToolContainer(event.optJSONObject("output_item"))?.let { return it }

        val choices = event.optJSONArray("choices") ?: return null
        for (index in 0 until choices.length()) {
            val choice = choices.optJSONObject(index) ?: continue
            val delta = choice.optJSONObject("delta") ?: continue
            fromToolContainer(delta)?.let { return it }
        }
        return null
    }

    private fun fromEventType(type: String?): AiActivityState? {
        val normalized = normalize(type)
        if (normalized.isBlank()) return null
        return when {
            normalized.contains("web_search") -> AiActivityState.WebSearching
            normalized.contains("file_search") -> AiActivityState.ReadingFiles
            normalized.contains("image_generation") -> AiActivityState.GeneratingImage
            normalized.contains("image_edit") -> AiActivityState.EditingImage
            normalized.contains("code") -> AiActivityState.ExecutingCode
            normalized.contains("function_call") || normalized.contains("tool_call") -> AiActivityState.ProcessingRequest
            else -> null
        }
    }

    private fun fromToolContainer(container: JSONObject?): AiActivityState? {
        container ?: return null

        val directType = container.optString("type")
        fromEventType(directType)?.let { return it }
        if (directType.isNotBlank() && directType != "function") {
            val mapped = fromToolName(directType)
            if (mapped !is AiActivityState.ProcessingRequest) return mapped
        }

        container.optJSONObject("function")?.optString("name")
            ?.takeIf { it.isNotBlank() }
            ?.let { return fromToolName(it) }
        container.optString("name")
            .takeIf { it.isNotBlank() }
            ?.let { return fromToolName(it) }

        return fromToolCalls(container.optJSONArray("tool_calls"))
            ?: fromToolCalls(container.optJSONArray("tools"))
    }

    private fun fromToolCalls(calls: JSONArray?): AiActivityState? {
        calls ?: return null
        if (calls.length() > 1) {
            return AiActivityState.ProcessingRequest
        }
        for (index in 0 until calls.length()) {
            val call = calls.optJSONObject(index) ?: continue
            fromToolContainer(call)?.let { return it }
        }
        return null
    }

    private fun hasImageInput(message: JSONObject): Boolean {
        val mimeType = message.optString("mimeType", "image/jpeg").ifBlank { "image/jpeg" }
        return message.optString("base64").isNotBlank() && mimeType.startsWith("image/", ignoreCase = true)
    }

    private fun hasFileInput(message: JSONObject): Boolean {
        val hasBinaryFile = message.optString("base64").isNotBlank() && !hasImageInput(message)
        return hasBinaryFile ||
            message.optString("fileContext").isNotBlank() ||
            message.optString("fileName").isNotBlank()
    }

    private fun normalize(value: String?): String =
        value.orEmpty()
            .trim()
            .lowercase()
            .replace('-', '_')
            .replace('.', '_')

    private val WEB_SEARCH_TOOLS = setOf(
        "web_search",
        "web_search_preview",
        "search",
        "browser_search",
        "internet_search"
    )

    private val IMAGE_GENERATION_TOOLS = setOf(
        "image_generation",
        "generate_image",
        "images_generations"
    )

    private val IMAGE_EDIT_TOOLS = setOf(
        "image_edit",
        "edit_image",
        "images_edits"
    )
}
