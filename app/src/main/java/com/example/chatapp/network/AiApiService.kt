package com.example.chatapp.network

import com.example.chatapp.BuildConfig
import com.example.chatapp.ChatMode
import com.example.chatapp.ai.AiActivityState
import com.example.chatapp.ai.AiActivityToolMapper
import com.example.chatapp.util.SafeLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader

object AiApiService {

    interface StreamCallback {
        suspend fun onActivity(activityState: AiActivityState) {}
        suspend fun onStreamStarted() {}
        suspend fun onChunk(accumulatedText: String)
        suspend fun onComplete(fullText: String)
        suspend fun onError(errorMessage: String)
    }

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private const val MAX_AI_REQUEST_ATTEMPTS = 2
    private const val RETRY_DELAY_MS = 700L
    private const val LINK_FORMAT_INSTRUCTION =
        "When you include links in the answer, format them as Markdown [meaningful link text](URL) " +
            "or HTML <a href=\"URL\">meaningful link text</a>. Do not leave raw plain-text URLs. " +
            "The link text must describe the target, not repeat the URL."

    fun buildSystemPrompt(
        currentMode: String?,
        customInstructions: String,
        chatContextSummary: String,
        filesContext: String = "",
        adultMode: Boolean = false
    ): String? {
        val baseSystemPrompt = when (currentMode) {
            ChatMode.SEARCH -> "Пользователь включил поиск в сети. Используй online search-возможности выбранной модели, отвечай по актуальной информации и добавляй раздел \"Источники\" со ссылками."
            ChatMode.SHOPPING -> "Ты помощник по покупкам. Используй online search-возможности выбранной модели, ищи актуальные варианты, сравнивай характеристики, отмечай реальные ограничения и добавляй раздел \"Источники\" со ссылками."
            ChatMode.STUDY -> "Ты учебный помощник. Объясняй пошагово, проверяй понимание и помогай закрепить материал."
            else -> null
        }

        val parts = mutableListOf<String>()
        if (baseSystemPrompt != null) parts.add(baseSystemPrompt)
        parts.add(LINK_FORMAT_INSTRUCTION)
        if (adultMode) {
            parts.add(
                "18+ style mode is enabled. Reply in a direct adult conversational tone. " +
                    "Use strong language and profanity naturally when it fits the user's tone, " +
                    "but keep the answer useful and do not target protected groups or encourage harm."
            )
        }
        if (customInstructions.isNotEmpty()) {
            parts.add("Инструкции пользователя:\n$customInstructions")
        }
        if (filesContext.isNotEmpty()) {
            parts.add("Краткие выдержки из ранее прикреплённых файлов:\n$filesContext")
        }
        if (chatContextSummary.isNotEmpty()) {
            parts.add("Сжатый контекст предыдущего диалога:\n$chatContextSummary")
        }

        return if (parts.isNotEmpty()) parts.joinToString("\n\n") else null
    }

    fun buildRequestBody(
        isImageGeneration: Boolean,
        messagesToKeep: List<JSONObject>,
        systemPrompt: String?
    ): String {
        return JSONObject().apply {
            if (isImageGeneration) {
                put("response_format", "b64_json")
                put("n", 1)
                put("size", "1024x1024")
                val lastUserMessage = messagesToKeep.lastOrNull {
                    it.optString("role") == "user"
                }
                val lastPrompt = lastUserMessage?.optString("content") ?: "Creative image"
                put("prompt", lastPrompt)
                val imageReferences = buildImageEditReferences(lastUserMessage)
                if (imageReferences.length() > 0) {
                    put("images", imageReferences)
                }
            } else {
                put("stream", true)

                val messages = JSONArray()
                val fileSearchFiles = JSONArray()
                if (systemPrompt != null) {
                    messages.put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                }

                messagesToKeep.forEach { msg ->
                    val messageText = buildMessageText(msg)
                    val mimeType = normalizedMimeType(msg)
                    if (msg.has("base64") && isImageMimeType(mimeType)) {
                        messages.put(
                            JSONObject().apply {
                                put("role", msg.optString("role", "user"))
                                put("content", JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("type", "text")
                                        put("text", messageText)
                                    })
                                    put(JSONObject().apply {
                                        put("type", "image_url")
                                        put("image_url", JSONObject().apply {
                                            put("url", "data:$mimeType;base64," + msg.getString("base64"))
                                        })
                                    })
                                })
                            }
                        )
                    } else {
                        buildFileSearchAttachment(msg, mimeType)?.let { fileSearchFiles.put(it) }
                        messages.put(JSONObject().apply {
                            put("role", msg.optString("role", "user"))
                            put("content", messageText)
                        })
                    }
                }

                put("messages", messages)
                if (fileSearchFiles.length() > 0) {
                    put("fileSearchFiles", fileSearchFiles)
                }
            }
        }.toString()
    }

    fun buildChatPayload(
        provider: AiProvider,
        modelKey: String,
        currentMode: String?,
        adultMode: Boolean,
        requestBody: String
    ): String = JSONObject().apply {
        put("provider", provider.code)
        put("modelKey", modelKey)
        put("currentMode", currentMode)
        put("adultMode", adultMode)
        put("request", JSONObject(requestBody))
    }.toString()

    fun parseImageResponseBody(bodyText: String): String? = runCatching {
        val imageData = JSONObject(bodyText)
            .getJSONArray("data")
            .getJSONObject(0)
        val b64 = imageData.optString("b64_json")
        val imageUrl = imageData.optString("url")
        when {
            b64.isNotBlank() -> "![image](data:image/png;base64,$b64)"
            imageUrl.isNotBlank() -> "![image]($imageUrl)"
            else -> null
        }
    }.getOrNull()

    private fun extractStreamContent(json: JSONObject): String {
        val choices = json.optJSONArray("choices")
        if (choices != null && choices.length() > 0) {
            return choices.optJSONObject(0)
                ?.optJSONObject("delta")
                ?.optString("content", "")
                .orEmpty()
        }

        val eventType = json.optString("type")
        if (eventType == "response.output_text.delta") {
            return json.optString("delta", "")
        }

        if (eventType == "content_block_delta") {
            return json.optJSONObject("delta")
                ?.optString("text", "")
                .orEmpty()
        }

        return ""
    }

    private fun buildMessageText(msg: JSONObject): String {
        val content = msg.optString("content", "")
            .replace(
                Regex("!\\[[^]]*]\\((?:data:image/|content://|file://|https?://)[^)]+\\)"),
                "[Generated image]"
            )
        val fileName = msg.optString("fileName", "")
        val mimeType = msg.optString("mimeType", "")
        val fileContext = msg.optString("fileContext").ifBlank { msg.optString("fileText") }

        if (fileName.isBlank() && mimeType.isBlank() && fileContext.isBlank()) {
            return content
        }

        return buildString {
            append(content)
            if (isNotBlank()) append("\n\n")
            append("Прикреплённый файл")
            if (fileName.isNotBlank()) append(": ").append(fileName)
            if (mimeType.isNotBlank()) append("\nMIME: ").append(mimeType)
            if (fileContext.isNotBlank()) {
                append("\n\nКраткая выдержка:\n")
                append(fileContext)
            } else if (msg.has("base64") && !isImageMimeType(mimeType)) {
                append("\n\n(Бинарный файл без текстовой выдержки)")
            }
        }
    }

    private fun normalizedMimeType(msg: JSONObject): String {
        val rawMimeType = msg.optString("mimeType", "image/jpeg")
        return rawMimeType.ifBlank { "image/jpeg" }
    }

    private fun isImageMimeType(mimeType: String): Boolean =
        mimeType.startsWith("image/", ignoreCase = true)

    private fun buildImageEditReferences(message: JSONObject?): JSONArray {
        val result = JSONArray()
        if (message == null) return result

        val mimeType = normalizedMimeType(message)
        val base64 = message.optString("base64").takeIf { it.isNotBlank() }
        if (base64 != null && isImageMimeType(mimeType)) {
            result.put(JSONObject().apply {
                put("image_url", "data:$mimeType;base64,$base64")
            })
        }

        return result
    }

    private fun buildFileSearchAttachment(message: JSONObject, mimeType: String): JSONObject? {
        if (isImageMimeType(mimeType)) return null
        val base64 = message.optString("base64").takeIf { it.isNotBlank() } ?: return null

        return JSONObject().apply {
            put("base64", base64)
            put("mimeType", mimeType)
            message.optString("fileName").takeIf { it.isNotBlank() }?.let {
                put("fileName", it)
            }
        }
    }

    suspend fun fetchStreamingResponse(
        authToken: String,
        provider: AiProvider,
        modelKey: String,
        messagesToKeep: List<JSONObject>,
        currentMode: String?,
        customInstructions: String,
        chatContextSummary: String,
        filesContext: String = "",
        adultMode: Boolean = false,
        callback: StreamCallback
    ) {
        withContext(Dispatchers.IO) {
            if (authToken.isBlank()) {
                withContext(Dispatchers.Main) {
                    callback.onError("Session expired. Sign in again.")
                }
                return@withContext
            }

            try {
                val coroutineContext = currentCoroutineContext()
                val isImageGeneration = currentMode == ChatMode.CREATE_IMAGE
                if (ChatMode.usesFreshWebContext(currentMode)) {
                    withContext(Dispatchers.Main) {
                        callback.onActivity(AiActivityState.WebSearching)
                    }
                }
                val systemPrompt = buildSystemPrompt(
                    currentMode,
                    customInstructions,
                    chatContextSummary,
                    filesContext,
                    adultMode
                )
                val jsonInput = buildRequestBody(isImageGeneration, messagesToKeep, systemPrompt)
                val payload = buildChatPayload(
                    provider = provider,
                    modelKey = modelKey,
                    currentMode = currentMode,
                    adultMode = adultMode,
                    requestBody = jsonInput
                )

                var attempt = 0
                while (true) {
                    attempt++
                    val request = buildJsonRequest(path = "ai/chat", payload = payload)
                    val call = NetworkModule.createAiHttpClient(authToken).newCall(request)
                    val cancellationHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
                        if (cause is CancellationException) {
                            call.cancel()
                        }
                    }

                    try {
                        val response = call.execute()
                        var retryAfterDelay = false
                        try {
                            val responseBody = response.body
                            if (!response.isSuccessful || responseBody == null) {
                                val errorBody = responseBody?.string().orEmpty()
                                if (attempt < MAX_AI_REQUEST_ATTEMPTS && isRetryableHttpStatus(response.code)) {
                                    retryAfterDelay = true
                                } else {
                                    withContext(Dispatchers.Main) {
                                        callback.onError(parseErrorMessage(response.code, errorBody))
                                    }
                                    return@withContext
                                }
                            } else {
                                var finalReply = ""
                                val isJsonResponse = response.header("Content-Type")
                                    .orEmpty()
                                    .contains("application/json", ignoreCase = true)

                                if (isImageGeneration || isJsonResponse) {
                                    val bodyText = responseBody.string()
                                    val imageReply = parseImageResponseBody(bodyText)
                                    finalReply = imageReply
                                        ?: if (isImageGeneration) error("Image response is empty") else bodyText
                                    withContext(Dispatchers.Main) {
                                        callback.onStreamStarted()
                                        if (imageReply == null) {
                                            callback.onChunk(finalReply)
                                        }
                                    }
                                } else {
                                    var streamStarted = false
                                    BufferedReader(InputStreamReader(responseBody.byteStream(), Charsets.UTF_8)).use { reader ->
                                        var eventName: String? = null
                                        val dataLines = mutableListOf<String>()

                                        suspend fun dispatchEvent() {
                                            if (dataLines.isEmpty()) return
                                            val data = dataLines.joinToString("\n").trim()
                                            dataLines.clear()
                                            val currentEventName = eventName
                                            eventName = null

                                            if (data.isEmpty() || data == "[DONE]") {
                                                return
                                            }

                                            val json = runCatching { JSONObject(data) }.getOrNull() ?: return
                                            val activityState = if (currentEventName == "ai_activity") {
                                                AiActivityToolMapper.fromActivityName(
                                                    json.optString("state").ifBlank { json.optString("type") },
                                                    json.optString("text").ifBlank { null }
                                                )
                                            } else {
                                                AiActivityToolMapper.fromProviderEvent(json)
                                            }
                                            if (activityState != null) {
                                                withContext(Dispatchers.Main) {
                                                    callback.onActivity(activityState)
                                                }
                                            }

                                            extractStreamContent(json).takeIf { it.isNotEmpty() }?.let { chunk ->
                                                finalReply += chunk
                                                withContext(Dispatchers.Main) {
                                                    if (!streamStarted) {
                                                        streamStarted = true
                                                        callback.onStreamStarted()
                                                    }
                                                    callback.onChunk(finalReply)
                                                }
                                            }
                                        }

                                        while (true) {
                                            val line = reader.readLine() ?: break
                                            when {
                                                line.isEmpty() -> dispatchEvent()
                                                line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
                                                line.startsWith("data:") -> dataLines.add(line.removePrefix("data:").trim())
                                            }
                                        }
                                        dispatchEvent()
                                    }
                                }

                                withContext(Dispatchers.Main) {
                                    callback.onComplete(finalReply)
                                }
                            }
                        } finally {
                            response.close()
                        }
                        if (retryAfterDelay) {
                            delay(RETRY_DELAY_MS * attempt)
                            continue
                        }
                        break
                    } catch (error: IOException) {
                        if (attempt < MAX_AI_REQUEST_ATTEMPTS && currentCoroutineContext().isActive) {
                            delay(RETRY_DELAY_MS * attempt)
                            continue
                        }
                        throw error
                    } finally {
                        cancellationHandle?.dispose()
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!currentCoroutineContext().isActive) {
                    throw CancellationException("AI response was cancelled", error)
                }
                withContext(Dispatchers.Main) {
                    callback.onError("Network error: ${error.message}")
                }
            }
        }
    }

    suspend fun generateTitle(
        authToken: String,
        provider: AiProvider,
        modelKey: String,
        firstUserMessage: String,
        firstAssistantMessage: String? = null
    ): String? {
        SafeLog.d(
            "AiApiService",
            "Requesting chat title provider=${provider.code} modelKey=$modelKey hasAssistantMessage=${!firstAssistantMessage.isNullOrBlank()}"
        )
        return executeContentRequest(
            authToken = authToken,
            path = "ai/title",
            payload = buildTitlePayload(
                provider = provider,
                modelKey = modelKey,
                firstUserMessage = firstUserMessage,
                firstAssistantMessage = firstAssistantMessage
            )
        )?.let(::sanitizeTitle)
    }

    fun buildTitlePayload(
        provider: AiProvider,
        modelKey: String,
        firstUserMessage: String,
        firstAssistantMessage: String? = null
    ): String = JSONObject().apply {
        put("provider", provider.code)
        put("modelKey", modelKey)
        put("firstUserMessage", firstUserMessage)
        if (!firstAssistantMessage.isNullOrBlank()) {
            put("firstAssistantMessage", firstAssistantMessage)
        }
    }.toString()

    private fun sanitizeTitle(rawTitle: String): String? =
        rawTitle
            .trim()
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            ?.replace(Regex("^(title|название)\\s*[:：-]\\s*", RegexOption.IGNORE_CASE), "")
            ?.trim('"', '\'', '«', '»', '“', '”', '„')
            ?.replace(Regex("\\s+"), " ")
            ?.removeSuffix(".")
            ?.trim()
            ?.takeIf { it.isNotBlank() && it.length <= 80 }

    suspend fun summarizeMessages(
        authToken: String,
        provider: AiProvider,
        modelKey: String,
        messagesToSummarize: List<JSONObject>
    ): String? {
        if (authToken.isBlank()) {
            return null
        }

        val promptText = buildString {
            append("Сделай краткую сводку важных фактов из этой части переписки:\n")
            for (msg in messagesToSummarize) {
                append(msg.getString("role")).append(": ").append(msg.getString("content")).append("\n")
                val fileName = msg.optString("fileName", "")
                val fileContext = msg.optString("fileContext", "")
                if (fileName.isNotBlank()) {
                    append("[Файл: ").append(fileName).append("]\n")
                }
                if (fileContext.isNotBlank()) {
                    append("[Выдержка из файла:\n").append(fileContext).append("]\n")
                }
            }
        }

        return executeContentRequest(
            authToken = authToken,
            path = "ai/summary",
            payload = JSONObject().apply {
                put("provider", provider.code)
                put("modelKey", modelKey)
                put("promptText", promptText)
            }.toString()
        )
    }

    private suspend fun executeContentRequest(
        authToken: String,
        path: String,
        payload: String
    ): String? = withContext(Dispatchers.IO) {
        runCatching {
            val request = buildJsonRequest(path, payload)
            NetworkModule.createAiHttpClient(authToken).newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    SafeLog.w("AiApiService", "Content request failed path=$path http=${response.code}")
                    return@use null
                }

                JSONObject(body).optString("content").takeIf { it.isNotBlank() }
            }
        }.onFailure { error ->
            SafeLog.w("AiApiService", "Content request failed path=$path", error)
        }.getOrNull()
    }

    private fun buildJsonRequest(path: String, payload: String): Request {
        val url = "${NetworkModule.normalizedBaseUrl(BuildConfig.APP_API_BASE_URL)}${path.removePrefix("/")}"
        return Request.Builder()
            .url(url)
            .header("Content-Type", "application/json; charset=utf-8")
            .post(payload.toRequestBody(jsonMediaType))
            .build()
    }

    private fun parseErrorMessage(statusCode: Int, body: String): String {
        val parsedMessage = runCatching {
            val json = JSONObject(body)
            when {
                json.has("error") -> json.getJSONObject("error").optString("message")
                json.has("message") -> json.optString("message")
                else -> ""
            }
        }.getOrDefault("")

        return when {
            parsedMessage.isNotBlank() -> parsedMessage
            else -> "Request failed: HTTP $statusCode"
        }
    }

    private fun isRetryableHttpStatus(statusCode: Int): Boolean =
        statusCode == 408 || statusCode == 429 || statusCode in 500..599
}
