package com.example.chatapp.network

import com.example.chatapp.BuildConfig
import com.example.chatapp.util.SafeLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import okhttp3.MultipartBody
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Прямой клиент OpenAI API.
 *
 * Для поиска используется встроенный OpenAI Web Search через Responses API.
 * Для изображений остаются инструменты вызова функций:
 *  • generate_image
 *  • edit_image
 *  • придумать название чата (generate_chat_title)
 */
object OpenAiDirectService {

    private const val TAG = "OpenAiDirect"
    private const val BASE_URL = "https://api.openai.com/v1/"
    private const val MODEL = "gpt-5.4-mini"
    private const val FILE_SEARCH_MAX_RESULTS = 20
    private const val FILE_SEARCH_VECTOR_STORE_TTL_DAYS = 1
    private const val FILE_SEARCH_POLL_INTERVAL_MS = 500L
    private const val FILE_SEARCH_MAX_POLL_ATTEMPTS = 60

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private data class FileSearchAttachment(
        val fileName: String,
        val mimeType: String,
        val base64Data: String
    )

    private data class FileSearchResources(
        val vectorStoreId: String,
        val fileIds: List<String>
    )

    private data class EditableImageReference(
        val imageUrl: String,
        val sourceRole: String,
        val fileName: String?
    )

    // ──────── Описания инструментов ────────

    private val toolsArray: JSONArray by lazy {
        JSONArray().apply {
            put(toolDefinition(
                name = "generate_image",
                description = "Генерация изображения по текстовому описанию. Используй когда пользователь просит нарисовать, создать или сгенерировать картинку.",
                parameters = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("prompt", JSONObject().apply {
                            put("type", "string")
                            put("description", "Подробное описание изображения на английском языке")
                        })
                        put("size", JSONObject().apply {
                            put("type", "string")
                            put("enum", JSONArray().put("1024x1024").put("1792x1024").put("1024x1792"))
                            put("description", "Размер изображения")
                        })
                    })
                    put("required", JSONArray().put("prompt"))
                }
            ))
            put(toolDefinition(
                name = "edit_image",
                description = "Редактирование существующего изображения по описанию. Используй когда пользователь просит изменить, отредактировать или доработать картинку.",
                parameters = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("prompt", JSONObject().apply {
                            put("type", "string")
                            put("description", "Описание желаемых изменений на английском языке")
                        })
                    })
                    put("required", JSONArray().put("prompt"))
                }
            ))
        }
    }

    private fun toolDefinition(
        name: String,
        description: String,
        parameters: JSONObject
    ): JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", parameters)
        })
    }

    // ──────── Потоковый чат ────────

    suspend fun fetchStreamingResponse(
        apiKey: String,
        messagesToKeep: List<JSONObject>,
        currentMode: String?,
        customInstructions: String,
        chatContextSummary: String,
        filesContext: String = "",
        callback: AiApiService.StreamCallback
    ) {
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) {
                withContext(Dispatchers.Main) {
                    callback.onError("OpenAI API key is not set. Go to Settings → AI Provider.")
                }
                return@withContext
            }

            try {
                val coroutineContext = currentCoroutineContext()
                val systemPrompt = buildOpenAiSystemPrompt(
                    currentMode, customInstructions, chatContextSummary, filesContext
                )
                val fileSearchAttachments = collectFileSearchAttachments(messagesToKeep)
                val useWebSearch = shouldUseOpenAiWebSearch(currentMode, messagesToKeep)

                if (shouldUseOpenAiImageEdit(currentMode, messagesToKeep)) {
                    val finalReply = executeResponsesImageEditRequest(
                        apiKey = apiKey,
                        systemPrompt = systemPrompt,
                        messagesToKeep = messagesToKeep,
                        callback = callback
                    )
                    withContext(Dispatchers.Main) {
                        callback.onComplete(finalReply)
                    }
                    return@withContext
                }

                if (fileSearchAttachments.isNotEmpty() && currentMode != "create_image") {
                    val finalReply = executeResponsesFileSearchRequest(
                        apiKey = apiKey,
                        systemPrompt = systemPrompt,
                        messagesToKeep = messagesToKeep,
                        attachments = fileSearchAttachments,
                        includeWebSearch = useWebSearch,
                        callback = callback
                    )
                    withContext(Dispatchers.Main) {
                        callback.onComplete(finalReply)
                    }
                    return@withContext
                }

                if (useWebSearch) {
                    val finalReply = executeResponsesWebSearchRequest(
                        apiKey = apiKey,
                        systemPrompt = systemPrompt,
                        messagesToKeep = messagesToKeep,
                        callback = callback
                    )
                    withContext(Dispatchers.Main) {
                        callback.onComplete(finalReply)
                    }
                    return@withContext
                }

                val messages = buildMessagesArray(systemPrompt, messagesToKeep)
                val finalReply = if (currentMode == "create_image") {
                    executeWithToolLoop(apiKey, messages, coroutineContext, callback)
                } else {
                    executeStreamingRequest(
                        apiKey = apiKey,
                        messages = messages,
                        coroutineContext = coroutineContext,
                        callback = callback,
                        includeTools = false
                    )
                }

                withContext(Dispatchers.Main) {
                    callback.onComplete(finalReply)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!currentCoroutineContext().isActive) {
                    throw CancellationException("AI response was cancelled", error)
                }
                withContext(Dispatchers.Main) {
                    callback.onError(formatOpenAiError(error.message))
                }
            }
        }
    }

    /**
     * Выполняет запрос к OpenAI, обрабатывает tool_calls в цикле,
     * и стримит финальный текстовый ответ.
     */
    private suspend fun executeWithToolLoop(
        apiKey: String,
        messages: JSONArray,
        coroutineContext: kotlin.coroutines.CoroutineContext,
        callback: AiApiService.StreamCallback
    ): String {
        // Первый запрос — НЕ стрим, чтобы получить tool_calls
        val firstResponse = executeNonStreamingRequest(apiKey, messages)
        val choice = firstResponse.optJSONArray("choices")
            ?.optJSONObject(0) ?: throw Exception("Empty response from OpenAI")
        val finishReason = choice.optString("finish_reason", "")
        val assistantMessage = choice.optJSONObject("message")
            ?: throw Exception("No message in response")

        if (finishReason == "tool_calls" || assistantMessage.has("tool_calls")) {
            val executedToolsText = java.lang.StringBuilder()
            val toolCalls = assistantMessage.optJSONArray("tool_calls") ?: JSONArray()
            if (toolCalls.length() == 0) {
                throw Exception("OpenAI requested a tool call but returned no tool calls")
            }
            var directToolReply: String? = null
            messages.put(buildAssistantToolMessage(assistantMessage, toolCalls))
            for (i in 0 until toolCalls.length()) {
                val toolCall = toolCalls.getJSONObject(i)
                val toolId = toolCall.getString("id")
                val functionObj = toolCall.getJSONObject("function")
                val functionName = functionObj.getString("name")
                
                val statusText = when (functionName) {
                    "generate_image" -> "Генерация изображения..."
                    else -> "Использование инструмента $functionName..."
                }
                withContext(Dispatchers.Main) { callback.onChunk(statusText) }

                val args = runCatching {
                    JSONObject(functionObj.getString("arguments"))
                }.getOrDefault(JSONObject())

                val toolResult = executeToolCall(apiKey, functionName, args, messages)
                if (functionName == "generate_image" || functionName == "edit_image") {
                    directToolReply = toolResult
                }
                
                // По просьбе пользователя не добавляем префикс об инструментах в финальный ответ

                messages.put(JSONObject().apply {
                    put("role", "tool")
                    put("tool_call_id", toolId)
                    put("content", toolResult)
                })
            }

            if (directToolReply != null) {
                withContext(Dispatchers.Main) {
                    callback.onChunk(directToolReply)
                }
                return directToolReply
            }

            // Повторный запрос — теперь стрим финального ответа
            return executeStreamingRequest(apiKey, messages, coroutineContext, callback, includeTools = false, prefix = executedToolsText.toString())
        }

        // Если tool_calls нет — модель ответила сразу, стримим
        val content = assistantMessage.optString("content", "")
        if (content.isNotBlank()) {
            withContext(Dispatchers.Main) {
                callback.onChunk(content)
            }
        }
        return content
    }

    private fun executeToolCall(apiKey: String, functionName: String, args: JSONObject, messages: JSONArray): String {
        SafeLog.d(TAG, "OpenAI tool call: $functionName")
        return when (functionName) {
            "generate_image" -> {
                val prompt = args.optString("prompt", "")
                if (prompt.isBlank()) return "Пустое описание"
                generateImage(apiKey, prompt)
            }
            "edit_image" -> {
                val prompt = args.optString("prompt", "")
                if (prompt.isBlank()) return "Пустое описание для редактирования"
                editImage(apiKey, prompt, messages)
            }
            else -> "Unknown tool: $functionName"
        }
    }

    private suspend fun executeResponsesWebSearchRequest(
        apiKey: String,
        systemPrompt: String,
        messagesToKeep: List<JSONObject>,
        callback: AiApiService.StreamCallback
    ): String {
        withContext(Dispatchers.Main) {
            callback.onChunk("Поиск в сети...")
        }

        val body = buildResponsesWebSearchRequestBody(systemPrompt, messagesToKeep)
        val request = Request.Builder()
            .url("${BASE_URL}responses")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        val finalReply = createOpenAiClient().newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw Exception(parseOpenAiError(response.code, responseBody))
            }

            extractResponsesFinalText(JSONObject(responseBody))
        }
        if (finalReply.isBlank()) {
            throw Exception("OpenAI Responses API returned an empty answer")
        }

        withContext(Dispatchers.Main) { callback.onChunk(finalReply) }
        return finalReply
    }

    private suspend fun executeResponsesFileSearchRequest(
        apiKey: String,
        systemPrompt: String,
        messagesToKeep: List<JSONObject>,
        attachments: List<FileSearchAttachment>,
        includeWebSearch: Boolean,
        callback: AiApiService.StreamCallback
    ): String {
        val statusText = if (attachments.size == 1) "Анализ файла..." else "Анализ файлов..."
        withContext(Dispatchers.Main) {
            callback.onChunk(statusText)
        }

        var resources: FileSearchResources? = null
        try {
            resources = prepareFileSearchResources(apiKey, attachments)
            val body = buildResponsesFileSearchRequestBody(
                systemPrompt = systemPrompt,
                messagesToKeep = messagesToKeep,
                vectorStoreId = resources.vectorStoreId,
                includeWebSearch = includeWebSearch
            )
            val request = Request.Builder()
                .url("${BASE_URL}responses")
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(body.toString().toRequestBody(jsonMediaType))
                .build()

            val finalReply = createOpenAiClient().newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw Exception(parseOpenAiError(response.code, responseBody))
                }

                extractResponsesFinalText(JSONObject(responseBody))
            }
            if (finalReply.isBlank()) {
                throw Exception("OpenAI Responses API returned an empty answer")
            }

            withContext(Dispatchers.Main) { callback.onChunk(finalReply) }
            return finalReply
        } finally {
            resources?.let { cleanupFileSearchResources(apiKey, it) }
        }
    }

    private suspend fun executeResponsesImageEditRequest(
        apiKey: String,
        systemPrompt: String,
        messagesToKeep: List<JSONObject>,
        callback: AiApiService.StreamCallback
    ): String {
        withContext(Dispatchers.Main) {
            callback.onChunk("Редактирование изображения...")
        }

        val body = buildResponsesImageEditRequestBody(systemPrompt, messagesToKeep)
        val request = Request.Builder()
            .url("${BASE_URL}responses")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        val finalReply = createOpenAiClient().newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw Exception(parseOpenAiError(response.code, responseBody))
            }

            extractResponsesImageGenerationMarkdown(
                responseJson = JSONObject(responseBody),
                altText = "Отредактированное изображение",
                successText = "Изображение успешно изменено."
            )
        }
        if (finalReply.isBlank()) {
            throw Exception("OpenAI Responses API returned no edited image")
        }

        withContext(Dispatchers.Main) { callback.onChunk(finalReply) }
        return finalReply
    }

    internal fun buildResponsesWebSearchRequestBody(
        systemPrompt: String,
        messagesToKeep: List<JSONObject>
    ): JSONObject {
        return JSONObject().apply {
            put("model", MODEL)
            put("instructions", systemPrompt)
            put("input", buildResponsesInputArray(messagesToKeep))
            put("tools", JSONArray().put(JSONObject().apply {
                put("type", "web_search")
            }))
            put("tool_choice", "required")
            put("include", JSONArray().put("web_search_call.action.sources"))
        }
    }

    internal fun buildResponsesFileSearchRequestBody(
        systemPrompt: String,
        messagesToKeep: List<JSONObject>,
        vectorStoreId: String,
        includeWebSearch: Boolean
    ): JSONObject {
        val tools = JSONArray().put(JSONObject().apply {
            put("type", "file_search")
            put("vector_store_ids", JSONArray().put(vectorStoreId))
            put("max_num_results", FILE_SEARCH_MAX_RESULTS)
        })
        val include = JSONArray().put("file_search_call.results")

        if (includeWebSearch) {
            tools.put(JSONObject().apply {
                put("type", "web_search")
            })
            include.put("web_search_call.action.sources")
        }

        return JSONObject().apply {
            put("model", MODEL)
            put(
                "instructions",
                systemPrompt + "\n\n" +
                    "Прикреплённые файлы доступны через OpenAI File Search. " +
                    "Для вопросов по файлам обязательно используй file_search и отвечай по найденным фрагментам."
            )
            put("input", buildResponsesInputArray(messagesToKeep, includeFileContext = false))
            put("tools", tools)
            put("tool_choice", "required")
            put("include", include)
        }
    }

    internal fun buildResponsesImageEditRequestBody(
        systemPrompt: String,
        messagesToKeep: List<JSONObject>
    ): JSONObject {
        val sourceImage = findLatestEditableImage(messagesToKeep)
            ?: throw IllegalArgumentException("No editable image found in chat context")
        return JSONObject().apply {
            put("model", MODEL)
            put(
                "instructions",
                systemPrompt + "\n\n" +
                    "Пользователь прикрепил изображение и просит получить изменённую версию. " +
                    "Используй прикреплённое изображение как визуальную основу, сохраняя важные детали исходника, если пользователь не попросил иначе. " +
                    "Верни именно изображение, а не только текстовое описание."
            )
            put("input", buildResponsesImageEditInputArray(messagesToKeep, sourceImage))
            put("tools", JSONArray().put(JSONObject().apply {
                put("type", "image_generation")
                put("action", "edit")
                put("quality", "auto")
                put("size", "auto")
            }))
            put("tool_choice", JSONObject().apply {
                put("type", "image_generation")
            })
        }
    }

    private fun buildResponsesImageEditInputArray(
        messagesToKeep: List<JSONObject>,
        sourceImage: EditableImageReference
    ): JSONArray {
        return JSONArray().apply {
            messagesToKeep.forEach { msg ->
                if (isToolProtocolMessage(msg)) {
                    return@forEach
                }

                val role = normalizedChatRole(msg.optString("role", "user"))
                val content = buildPlainMessageContent(msg)
                if (content.isNotBlank()) {
                    put(JSONObject().apply {
                        put("role", role)
                        put("content", content)
                    })
                }
            }

            put(JSONObject().apply {
                put("role", "user")
                put("content", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "input_text")
                        put(
                            "text",
                            buildString {
                                append("Изображение ниже является исходником для редактирования. ")
                                append("Оно взято из ")
                                append(if (sourceImage.sourceRole == "assistant") "последнего сгенерированного ассистентом изображения" else "последнего изображения пользователя")
                                if (!sourceImage.fileName.isNullOrBlank()) {
                                    append(": ").append(sourceImage.fileName)
                                }
                                append(". Выполни последнюю просьбу пользователя именно над этим изображением.")
                            }
                        )
                    })
                    put(JSONObject().apply {
                        put("type", "input_image")
                        put("image_url", sourceImage.imageUrl)
                    })
                })
            })
        }
    }

    private fun buildResponsesInputArray(
        messagesToKeep: List<JSONObject>,
        includeFileContext: Boolean = true
    ): JSONArray {
        return JSONArray().apply {
            messagesToKeep.forEach { msg ->
                if (isToolProtocolMessage(msg)) {
                    return@forEach
                }

                val role = normalizedChatRole(msg.optString("role", "user"))
                val content = buildPlainMessageContent(msg, includeFileContext)
                val mimeType = msg.optString("mimeType", "").ifBlank { "image/jpeg" }

                if (role == "user" && msg.has("base64") && mimeType.startsWith("image/", ignoreCase = true)) {
                    put(JSONObject().apply {
                        put("role", role)
                        put("content", JSONArray().apply {
                            put(JSONObject().apply {
                                put("type", "input_text")
                                put("text", content.ifBlank { "Описание прикреплённого изображения" })
                            })
                            put(JSONObject().apply {
                                put("type", "input_image")
                                put("image_url", "data:$mimeType;base64,${msg.getString("base64")}")
                            })
                        })
                    })
                } else if (content.isNotBlank()) {
                    put(JSONObject().apply {
                        put("role", role)
                        put("content", content)
                    })
                }
            }
        }
    }

    internal fun extractResponsesFinalText(responseJson: JSONObject): String {
        val citations = linkedMapOf<String, String>()
        val fileCitations = linkedSetOf<String>()
        val outputText = responseJson.optString("output_text", "").trim()
        val textBuilder = StringBuilder()

        val output = responseJson.optJSONArray("output") ?: JSONArray()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            if (item.optString("type") != "message") {
                continue
            }

            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.optJSONObject(j) ?: continue
                if (part.optString("type") == "output_text") {
                    val text = part.optString("text", "")
                    if (text.isNotBlank()) {
                        if (textBuilder.isNotEmpty()) textBuilder.append("\n\n")
                        textBuilder.append(text.trim())
                    }
                    collectResponseCitations(part, citations, fileCitations)
                }
            }
        }

        val text = outputText.ifBlank { textBuilder.toString().trim() }
        collectWebSearchSources(output, citations)
        return appendFileCitationList(appendCitationList(text, citations), fileCitations)
    }

    internal fun extractResponsesImageGenerationMarkdown(
        responseJson: JSONObject,
        altText: String,
        successText: String
    ): String {
        val output = responseJson.optJSONArray("output") ?: JSONArray()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            if (item.optString("type") != "image_generation_call") {
                continue
            }

            val b64Json = item.optString("result", "")
                .filterNot { it.isWhitespace() }
                .takeIf { it.isNotBlank() }
                ?: item.optString("b64_json", "")
                    .filterNot { it.isWhitespace() }
                    .takeIf { it.isNotBlank() }
            if (!b64Json.isNullOrBlank()) {
                return "$successText\n\n![$altText](data:image/png;base64,$b64Json)"
            }
        }

        return extractResponsesFinalText(responseJson)
    }

    private fun collectWebSearchSources(output: JSONArray, citations: MutableMap<String, String>) {
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            if (item.optString("type") != "web_search_call") {
                continue
            }

            val action = item.optJSONObject("action")
            val sources = action?.optJSONArray("sources")
                ?: item.optJSONArray("sources")
                ?: continue
            for (j in 0 until sources.length()) {
                val source = sources.optJSONObject(j) ?: continue
                val url = source.optString("url")
                    .ifBlank { source.optString("uri") }
                if (url.isBlank() || citations.containsKey(url)) {
                    continue
                }

                citations[url] = source.optString("title")
                    .ifBlank { source.optString("name") }
                    .ifBlank { url }
            }
        }
    }

    private fun collectResponseCitations(
        part: JSONObject,
        citations: MutableMap<String, String>,
        fileCitations: MutableSet<String>
    ) {
        val annotations = part.optJSONArray("annotations") ?: return
        for (i in 0 until annotations.length()) {
            val annotation = annotations.optJSONObject(i) ?: continue
            when (annotation.optString("type")) {
                "url_citation" -> {
                    val nestedCitation = annotation.optJSONObject("url_citation")
                    val url = annotation.optString("url")
                        .ifBlank { nestedCitation?.optString("url").orEmpty() }
                    if (url.isNotBlank() && !citations.containsKey(url)) {
                        citations[url] = annotation.optString("title")
                            .ifBlank { nestedCitation?.optString("title").orEmpty() }
                            .ifBlank { url }
                    }
                }
                "file_citation" -> {
                    val fileName = annotation.optString("filename")
                        .ifBlank { annotation.optString("file_id") }
                    if (fileName.isNotBlank()) {
                        fileCitations.add(fileName)
                    }
                }
            }
        }
    }

    private fun appendCitationList(text: String, citations: Map<String, String>): String {
        if (text.isBlank() || citations.isEmpty()) {
            return text
        }

        return buildString {
            append(text)
            append("\n\nИсточники:\n")
            citations.entries.forEachIndexed { index, (url, title) ->
                append(index + 1)
                append(". [")
                append(title.replace("[", "\\[").replace("]", "\\]"))
                append("](")
                append(url)
                append(")")
                if (index < citations.size - 1) append("\n")
            }
        }
    }

    private fun appendFileCitationList(text: String, fileCitations: Set<String>): String {
        if (text.isBlank() || fileCitations.isEmpty()) {
            return text
        }

        return buildString {
            append(text)
            append("\n\nФайлы:\n")
            fileCitations.forEachIndexed { index, fileName ->
                append(index + 1)
                append(". ")
                append(fileName)
                if (index < fileCitations.size - 1) append("\n")
            }
        }
    }

    internal fun shouldUseOpenAiWebSearch(
        currentMode: String?,
        messagesToKeep: List<JSONObject>
    ): Boolean {
        if (currentMode == "create_image") {
            return false
        }
        if (currentMode == "search" || currentMode == "shopping") {
            return true
        }

        val lastUserText = lastUserMessageText(messagesToKeep).lowercase()
        if (lastUserText.isBlank()) {
            return false
        }

        val webSearchMarkers = listOf(
            "найди", "поиск", "поищи", "в сети", "в интернете", "интернет",
            "новост", "актуаль", "последн", "сегодня", "сейчас", "цена",
            "search", "web", "internet", "latest", "current", "today", "news", "price"
        )
        return webSearchMarkers.any { marker -> lastUserText.contains(marker) }
    }

    internal fun shouldUseOpenAiImageEdit(
        currentMode: String?,
        messagesToKeep: List<JSONObject>
    ): Boolean {
        if (findLatestEditableImage(messagesToKeep) == null) {
            return false
        }
        val lastUserText = lastUserMessageText(messagesToKeep).lowercase()
        if (lastUserText.isBlank()) {
            return false
        }

        val imageEditMarkers = listOf(
            "измени", "изменить", "редакт", "отредакт", "доработ", "передел",
            "добавь", "добавить", "убери", "удали", "замени", "поменяй",
            "перерис", "стиль", "фон", "цвет", "улучш", "улучши",
            "фото в", "картинку в", "изображение в",
            "edit", "change", "modify", "replace", "remove", "add", "make it", "make this", "turn",
            "style", "background", "color", "enhance", "improve"
        )
        return imageEditMarkers.any { marker -> lastUserText.contains(marker) }
    }

    private fun lastUserMessageText(messages: List<JSONObject>): String {
        return messages.asReversed()
            .firstOrNull { it.optString("role") == "user" }
            ?.optString("content", "")
            .orEmpty()
    }

    private fun findLatestEditableImage(messages: List<JSONObject>): EditableImageReference? {
        for (msg in messages.asReversed()) {
            if (isToolProtocolMessage(msg)) {
                continue
            }

            val role = normalizedChatRole(msg.optString("role", "user"))
            val mimeType = msg.optString("mimeType", "").ifBlank { "image/png" }
            val fileName = msg.optString("fileName", "").takeIf { it.isNotBlank() }
            val base64Data = msg.optString("base64", "")
                .filterNot { it.isWhitespace() }
            if (base64Data.isNotBlank() && mimeType.startsWith("image/", ignoreCase = true)) {
                return EditableImageReference(
                    imageUrl = "data:$mimeType;base64,$base64Data",
                    sourceRole = role,
                    fileName = fileName
                )
            }

            val storedImageUrl = msg.optString("imageUri", "")
                .ifBlank { msg.optString("imageUrl", "") }
                .takeIf { isRemoteOrDataImageUrl(it) }
            if (storedImageUrl != null) {
                return EditableImageReference(
                    imageUrl = storedImageUrl,
                    sourceRole = role,
                    fileName = fileName
                )
            }

            val markdownImageUrl = extractMarkdownImageUrl(msg.optString("content", ""))
                ?.takeIf { isRemoteOrDataImageUrl(it) }
            if (markdownImageUrl != null) {
                return EditableImageReference(
                    imageUrl = markdownImageUrl,
                    sourceRole = role,
                    fileName = fileName
                )
            }
        }

        return null
    }

    private fun isRemoteOrDataImageUrl(value: String?): Boolean {
        if (value.isNullOrBlank()) {
            return false
        }

        return value.startsWith("data:image", ignoreCase = true) ||
            value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true)
    }

    private fun extractMarkdownImageUrl(content: String): String? {
        val imageStart = content.indexOf("![")
        if (imageStart == -1) {
            return null
        }

        val linkStart = content.indexOf("](", imageStart)
        if (linkStart == -1) {
            return null
        }

        val linkEnd = content.indexOf(')', linkStart + 2)
        if (linkEnd == -1) {
            return null
        }

        return content.substring(linkStart + 2, linkEnd).trim()
    }

    private fun collectFileSearchAttachments(messages: List<JSONObject>): List<FileSearchAttachment> {
        val seen = mutableSetOf<String>()
        val attachments = mutableListOf<FileSearchAttachment>()

        messages.forEach { msg ->
            if (isToolProtocolMessage(msg) || normalizedChatRole(msg.optString("role", "user")) != "user") {
                return@forEach
            }

            val base64Data = msg.optString("base64", "")
                .filterNot { it.isWhitespace() }
            if (base64Data.isBlank()) {
                return@forEach
            }

            val mimeType = msg.optString("mimeType", "")
                .ifBlank { "application/octet-stream" }
            if (mimeType.startsWith("image/", ignoreCase = true)) {
                return@forEach
            }

            val fileName = normalizedFileSearchFileName(
                rawFileName = msg.optString("fileName", ""),
                mimeType = mimeType,
                index = attachments.size + 1
            )
            if (!isSupportedFileSearchFile(mimeType, fileName)) {
                return@forEach
            }

            val key = "$fileName:$mimeType:${base64Data.hashCode()}"
            if (seen.add(key)) {
                attachments.add(
                    FileSearchAttachment(
                        fileName = fileName,
                        mimeType = mimeType,
                        base64Data = base64Data
                    )
                )
            }
        }

        return attachments
    }

    private suspend fun prepareFileSearchResources(
        apiKey: String,
        attachments: List<FileSearchAttachment>
    ): FileSearchResources {
        val fileIds = mutableListOf<String>()
        try {
            attachments.forEach { attachment ->
                fileIds.add(uploadFileSearchFile(apiKey, attachment))
            }
            val vectorStoreId = createFileSearchVectorStore(apiKey, fileIds)
            waitForVectorStoreReady(apiKey, vectorStoreId)
            return FileSearchResources(vectorStoreId, fileIds)
        } catch (error: Exception) {
            fileIds.forEach { fileId ->
                runCatching { deleteOpenAiFile(apiKey, fileId) }
            }
            throw error
        }
    }

    private fun uploadFileSearchFile(apiKey: String, attachment: FileSearchAttachment): String {
        val fileBytes = runCatching {
            android.util.Base64.decode(attachment.base64Data, android.util.Base64.DEFAULT)
        }.getOrElse {
            throw Exception("Не удалось подготовить файл ${attachment.fileName} для OpenAI File Search")
        }
        val mediaType = attachment.mimeType.toMediaTypeOrNull()
            ?: "application/octet-stream".toMediaType()

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("purpose", "assistants")
            .addFormDataPart(
                "file",
                attachment.fileName,
                fileBytes.toRequestBody(mediaType)
            )
            .build()

        val request = Request.Builder()
            .url("${BASE_URL}files")
            .header("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        return createOpenAiClient().newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw Exception(parseOpenAiError(response.code, responseBody))
            }

            JSONObject(responseBody).getString("id")
        }
    }

    private fun createFileSearchVectorStore(apiKey: String, fileIds: List<String>): String {
        val body = JSONObject().apply {
            put("name", "chatapp-file-search-${System.currentTimeMillis()}")
            put("file_ids", JSONArray().apply {
                fileIds.forEach { put(it) }
            })
            put("expires_after", JSONObject().apply {
                put("anchor", "last_active_at")
                put("days", FILE_SEARCH_VECTOR_STORE_TTL_DAYS)
            })
        }

        val request = Request.Builder()
            .url("${BASE_URL}vector_stores")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("OpenAI-Beta", "assistants=v2")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        return createOpenAiClient().newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw Exception(parseOpenAiError(response.code, responseBody))
            }

            JSONObject(responseBody).getString("id")
        }
    }

    private suspend fun waitForVectorStoreReady(apiKey: String, vectorStoreId: String) {
        repeat(FILE_SEARCH_MAX_POLL_ATTEMPTS) {
            val vectorStore = getVectorStore(apiKey, vectorStoreId)
            val status = vectorStore.optString("status", "")
            val fileCounts = vectorStore.optJSONObject("file_counts")
            val failedCount = fileCounts?.optInt("failed", 0) ?: 0
            val totalCount = fileCounts?.optInt("total", 0) ?: 0
            val completedCount = fileCounts?.optInt("completed", 0) ?: 0

            if (failedCount > 0) {
                throw Exception("OpenAI File Search не смог обработать один или несколько файлов")
            }
            if (status == "completed" || (totalCount > 0 && completedCount == totalCount)) {
                return
            }

            delay(FILE_SEARCH_POLL_INTERVAL_MS)
        }

        throw Exception("OpenAI File Search не успел подготовить файл. Попробуйте ещё раз.")
    }

    private fun getVectorStore(apiKey: String, vectorStoreId: String): JSONObject {
        val request = Request.Builder()
            .url("${BASE_URL}vector_stores/$vectorStoreId")
            .header("Authorization", "Bearer $apiKey")
            .header("OpenAI-Beta", "assistants=v2")
            .get()
            .build()

        return createOpenAiClient().newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw Exception(parseOpenAiError(response.code, responseBody))
            }

            JSONObject(responseBody)
        }
    }

    private fun cleanupFileSearchResources(apiKey: String, resources: FileSearchResources) {
        runCatching { deleteOpenAiVectorStore(apiKey, resources.vectorStoreId) }
        resources.fileIds.forEach { fileId ->
            runCatching { deleteOpenAiFile(apiKey, fileId) }
        }
    }

    private fun deleteOpenAiVectorStore(apiKey: String, vectorStoreId: String) {
        val request = Request.Builder()
            .url("${BASE_URL}vector_stores/$vectorStoreId")
            .header("Authorization", "Bearer $apiKey")
            .header("OpenAI-Beta", "assistants=v2")
            .delete()
            .build()

        createOpenAiClient().newCall(request).execute().close()
    }

    private fun deleteOpenAiFile(apiKey: String, fileId: String) {
        val request = Request.Builder()
            .url("${BASE_URL}files/$fileId")
            .header("Authorization", "Bearer $apiKey")
            .delete()
            .build()

        createOpenAiClient().newCall(request).execute().close()
    }

    private fun normalizedFileSearchFileName(rawFileName: String, mimeType: String, index: Int): String {
        val cleaned = rawFileName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .trim('.', ' ', '_')
        val fallback = "attachment_$index${defaultFileSearchExtension(mimeType)}"
        return cleaned.ifBlank { fallback }.take(160)
    }

    private fun defaultFileSearchExtension(mimeType: String): String {
        return when (mimeType.lowercase()) {
            "application/pdf" -> ".pdf"
            "application/msword" -> ".doc"
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> ".docx"
            "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> ".pptx"
            "application/json" -> ".json"
            "application/typescript" -> ".ts"
            "text/markdown" -> ".md"
            "text/html" -> ".html"
            "text/css" -> ".css"
            "text/javascript" -> ".js"
            "text/plain" -> ".txt"
            else -> if (mimeType.startsWith("text/", ignoreCase = true)) ".txt" else ".txt"
        }
    }

    private fun isSupportedFileSearchFile(mimeType: String, fileName: String): Boolean {
        if (mimeType.startsWith("text/", ignoreCase = true)) {
            return true
        }

        val normalizedMime = mimeType.lowercase()
        if (normalizedMime in setOf(
                "application/msword",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/json",
                "application/pdf",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "application/typescript",
                "application/x-sh"
            )
        ) {
            return true
        }

        val extension = fileName.substringAfterLast('.', "").lowercase()
        return extension in setOf(
            "c", "cpp", "cs", "css", "doc", "docx", "go", "html", "java",
            "js", "json", "md", "pdf", "php", "pptx", "py", "rb", "sh",
            "tex", "ts", "txt"
        )
    }

        private fun editImage(apiKey: String, prompt: String, messages: JSONArray): String {
        return try {
            // Ищем последнее изображение в base64
            var base64Image: String? = null
            for (i in messages.length() - 1 downTo 0) {
                val msg = messages.optJSONObject(i)
                if (msg != null && msg.optString("role") == "user") {
                    val contentArr = msg.optJSONArray("content")
                    if (contentArr != null) {
                        for (j in 0 until contentArr.length()) {
                            val part = contentArr.optJSONObject(j)
                            if (part != null && part.optString("type") == "image_url") {
                                val url = part.optJSONObject("image_url")?.optString("url")
                                if (url != null && url.startsWith("data:image")) {
                                    base64Image = url.substringAfter("base64,")
                                    break
                                }
                            }
                        }
                    }
                }
                if (base64Image != null) break
            }

            if (base64Image == null) {
                return "Ошибка: Не найдено исходное изображение для редактирования в последних сообщениях."
            }

            // Декодируем и обрезаем в квадрат 1024x1024
            val imageBytes = android.util.Base64.decode(base64Image, android.util.Base64.DEFAULT)
            var bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            
            val minSize = Math.min(bitmap.width, bitmap.height)
            val cropX = (bitmap.width - minSize) / 2
            val cropY = (bitmap.height - minSize) / 2
            val croppedBitmap = Bitmap.createBitmap(bitmap, cropX, cropY, minSize, minSize)
            val scaledBitmap = Bitmap.createScaledBitmap(croppedBitmap, 1024, 1024, true)
            
            val outputStream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            val pngBytes = outputStream.toByteArray()

            val maskBitmap = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888)
            maskBitmap.eraseColor(android.graphics.Color.TRANSPARENT)
            val maskStream = ByteArrayOutputStream()
            maskBitmap.compress(Bitmap.CompressFormat.PNG, 100, maskStream)
            val maskBytes = maskStream.toByteArray()

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", "gpt-image-1")
                .addFormDataPart("prompt", prompt)
                .addFormDataPart("n", "1")
                .addFormDataPart("size", "1024x1024")
                .addFormDataPart("image", "image.png", pngBytes.toRequestBody("image/png".toMediaType()))
                .addFormDataPart("mask", "mask.png", maskBytes.toRequestBody("image/png".toMediaType()))
                .build()

            val request = Request.Builder()
                .url("${BASE_URL}images/edits")
                .header("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()

            val response = createOpenAiClient().newCall(request).execute()
            val responseBody = response.body?.string().orEmpty()
            if (response.isSuccessful) {
                imageMarkdownFromOpenAiResponse(
                    responseBody = responseBody,
                    altText = "Отредактированное изображение",
                    successText = "Изображение успешно изменено."
                ) ?: "Ошибка: изображение не найдено в ответе OpenAI."
            } else {
                "Ошибка при редактировании изображения: $responseBody"
            }
        } catch (e: Exception) {
            SafeLog.e(TAG, "Image edit request failed", e)
            "Сетевая ошибка при редактировании изображения: ${e.message}"
        }
    }

private fun generateImage(apiKey: String, prompt: String): String {
        return try {
            val body = JSONObject().apply {
                put("model", "gpt-image-1")
                put("prompt", prompt)
                put("n", 1)
                put("size", "1024x1024")
            }
            val request = Request.Builder()
                .url("${BASE_URL}images/generations")
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(body.toString().toRequestBody(jsonMediaType))
                .build()

            val response = createOpenAiClient().newCall(request).execute()
            val responseBody = response.body?.string().orEmpty()
            if (response.isSuccessful) {
                imageMarkdownFromOpenAiResponse(
                    responseBody = responseBody,
                    altText = "Сгенерированное изображение",
                    successText = "Изображение успешно сгенерировано."
                ) ?: "Ошибка: изображение не найдено в ответе OpenAI."
            } else {
                "Ошибка при генерации изображения: $responseBody"
            }
        } catch (e: Exception) {
            "Сетевая ошибка при генерации изображения: ${e.message}"
        }
    }

    internal fun imageMarkdownFromOpenAiResponse(
        responseBody: String,
        altText: String,
        successText: String
    ): String? {
        val imageObject = JSONObject(responseBody)
            .optJSONArray("data")
            ?.optJSONObject(0)
            ?: return null

        val url = imageObject.optString("url").takeIf { it.isNotBlank() }
        if (url != null) {
            return "$successText\n\n![$altText]($url)"
        }

        val b64Json = imageObject.optString("b64_json")
            .filterNot { it.isWhitespace() }
            .takeIf { it.isNotBlank() }
            ?: return null
        val mimeType = when (imageObject.optString("output_format", "png").lowercase()) {
            "jpeg", "jpg" -> "image/jpeg"
            "webp" -> "image/webp"
            else -> "image/png"
        }

        return "$successText\n\n![$altText](data:$mimeType;base64,$b64Json)"
    }

    // ──────── Обычный запрос для первичного обнаружения инструментов ────────

    private fun executeNonStreamingRequest(apiKey: String, messages: JSONArray): JSONObject {
        val body = JSONObject().apply {
            put("model", MODEL)
            put("messages", messages)
            put("tools", toolsArray)
            put("tool_choice", "auto")
            put("stream", false)
        }

        val request = Request.Builder()
            .url("${BASE_URL}chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        val response = createOpenAiClient().newCall(request).execute()
        val responseBody = response.body?.string().orEmpty()

        if (!response.isSuccessful) {
            val errorMsg = parseOpenAiError(response.code, responseBody)
            throw Exception(errorMsg)
        }

        return JSONObject(responseBody)
    }

    // ──────── Потоковый запрос ────────

    private suspend fun executeStreamingRequest(
        apiKey: String,
        messages: JSONArray,
        coroutineContext: kotlin.coroutines.CoroutineContext,
        callback: AiApiService.StreamCallback,
        includeTools: Boolean = true,
        prefix: String = ""
    ): String {
        val body = JSONObject().apply {
            put("model", MODEL)
            put("messages", messages)
            if (includeTools) {
                put("tools", toolsArray)
                put("tool_choice", "auto")
            }
            put("stream", true)
        }

        val request = Request.Builder()
            .url("${BASE_URL}chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        val call = createOpenAiClient().newCall(request)
        val cancellationHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException) call.cancel()
        }

        try {
            call.execute().use { response ->
                val responseBody = response.body
                if (!response.isSuccessful || responseBody == null) {
                    throw Exception(parseOpenAiError(response.code, responseBody?.string().orEmpty()))
                }

                var finalReply = prefix
                if (prefix.isNotEmpty()) {
                    withContext(Dispatchers.Main) { callback.onChunk(finalReply) }
                }
                BufferedReader(InputStreamReader(responseBody.byteStream(), Charsets.UTF_8)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (!line.startsWith("data:")) continue

                        val data = line.removePrefix("data:").trim()
                        if (data.isEmpty() || data == "[DONE]") continue

                        runCatching {
                            val json = JSONObject(data)
                            val choices = json.optJSONArray("choices")
                            if (choices != null && choices.length() > 0) {
                                choices.getJSONObject(0)
                                    .optJSONObject("delta")
                                    ?.optString("content", "")
                                    .orEmpty()
                            } else ""
                        }.getOrDefault("").takeIf { it.isNotEmpty() }?.let { chunk ->
                            finalReply += chunk
                            withContext(Dispatchers.Main) {
                                callback.onChunk(finalReply)
                            }
                        }
                    }
                }
                return finalReply
            }
        } finally {
            cancellationHandle?.dispose()
        }
    }

    // ──────── Генерация названия ────────

    suspend fun generateTitle(apiKey: String, firstUserMessage: String): String? =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) return@withContext null

            runCatching {
                val messages = JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", "Ты помощник. Придумай короткое название (до 6 слов) для чата на основе первого сообщения пользователя. " +
                            "Ответь ТОЛЬКО названием, без кавычек и точки.")
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", firstUserMessage)
                    })
                }

                val body = JSONObject().apply {
                    put("model", MODEL)
                    put("messages", messages)
                    put("max_tokens", 30)
                    put("stream", false)
                }

                val request = Request.Builder()
                    .url("${BASE_URL}chat/completions")
                    .header("Authorization", "Bearer $apiKey")
                    .header("Content-Type", "application/json")
                    .post(body.toString().toRequestBody(jsonMediaType))
                    .build()

                val response = createOpenAiClient().newCall(request).execute()
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    SafeLog.w(TAG, "generateTitle failed: HTTP ${response.code}")
                    return@runCatching null
                }
                JSONObject(responseBody)
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    ?.trim()
                    ?.removeSurrounding("\"")
                    ?.removeSuffix(".")
                    ?.takeIf { it.isNotBlank() && it.length <= 60 }
            }.getOrNull()
        }

    // ──────── Суммаризация ────────

    suspend fun summarizeMessages(
        apiKey: String,
        messagesToSummarize: List<JSONObject>
    ): String? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext null

        val promptText = buildString {
            append("Сделай краткую сводку важных фактов из этой части переписки:\n")
            for (msg in messagesToSummarize) {
                append(msg.getString("role")).append(": ").append(msg.getString("content")).append("\n")
                val fileName = msg.optString("fileName", "")
                val fileContext = msg.optString("fileContext", "")
                if (fileName.isNotBlank()) append("[Файл: $fileName]\n")
                if (fileContext.isNotBlank()) append("[Выдержка из файла:\n$fileContext]\n")
            }
        }

        runCatching {
            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "Ты ассистент для суммаризации. Сделай краткую сводку ключевых фактов из диалога.")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", promptText)
                })
            }

            val body = JSONObject().apply {
                put("model", MODEL)
                put("messages", messages)
                put("max_tokens", 500)
                put("stream", false)
            }

            val request = Request.Builder()
                .url("${BASE_URL}chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(body.toString().toRequestBody(jsonMediaType))
                .build()

            val response = createOpenAiClient().newCall(request).execute()
            if (!response.isSuccessful) return@runCatching null

            JSONObject(response.body?.string().orEmpty())
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    // ──────── Вспомогательные методы ────────

    private fun buildOpenAiSystemPrompt(
        currentMode: String?,
        customInstructions: String,
        chatContextSummary: String,
        filesContext: String
    ): String {
        val parts = mutableListOf<String>()

        parts.add(
            "Ты — умный AI-ассистент. Отвечай на языке пользователя. " +
            "Для генерации и редактирования изображений используй доступные image tools, когда это уместно."
        )

        val modePrompt = when (currentMode) {
            "shopping" -> "Ты помощник по покупкам. Ищи варианты, сравнивай характеристики и отмечай реальные ограничения."
            "study" -> "Ты учебный помощник. Объясняй пошагово, проверяй понимание и помогай закрепить материал."
            "search" -> "Пользователь хочет найти информацию."
            "create_image" -> "Пользователь хочет создать изображение. Используй инструмент generate_image."
            else -> null
        }
        if (modePrompt != null) parts.add(modePrompt)

        if (customInstructions.isNotEmpty()) {
            parts.add("Инструкции пользователя:\n$customInstructions")
        }
        if (filesContext.isNotEmpty()) {
            parts.add("Краткие выдержки из ранее прикреплённых файлов:\n$filesContext")
        }
        if (chatContextSummary.isNotEmpty()) {
            parts.add("Сжатый контекст предыдущего диалога:\n$chatContextSummary")
        }

        return parts.joinToString("\n\n")
    }

    internal fun buildMessagesArray(
        systemPrompt: String,
        messagesToKeep: List<JSONObject>
    ): JSONArray {
        val messages = JSONArray()
        messages.put(JSONObject().apply {
            put("role", "system")
            put("content", systemPrompt)
        })

        messagesToKeep.forEach { msg ->
            if (isToolProtocolMessage(msg)) {
                return@forEach
            }

            val content = buildPlainMessageContent(msg)
            val mimeType = msg.optString("mimeType", "").ifBlank { "image/jpeg" }
            val role = normalizedChatRole(msg.optString("role", "user"))

            if (role == "user" && msg.has("base64") && mimeType.startsWith("image/", ignoreCase = true)) {
                messages.put(JSONObject().apply {
                    put("role", role)
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", content.ifBlank { "Описание прикреплённого изображения" })
                        })
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply {
                                put("url", "data:$mimeType;base64,${msg.getString("base64")}")
                            })
                        })
                    })
                })
            } else {
                if (content.isBlank()) {
                    return@forEach
                }
                messages.put(JSONObject().apply {
                    put("role", role)
                    put("content", content)
                })
            }
        }

        return messages
    }

    private fun buildAssistantToolMessage(assistantMessage: JSONObject, toolCalls: JSONArray): JSONObject {
        return JSONObject().apply {
            put("role", "assistant")
            if (assistantMessage.isNull("content")) {
                put("content", JSONObject.NULL)
            } else {
                put("content", assistantMessage.optString("content", ""))
            }
            put("tool_calls", JSONArray().apply {
                for (i in 0 until toolCalls.length()) {
                    val toolCall = toolCalls.optJSONObject(i) ?: continue
                    val functionObj = toolCall.optJSONObject("function") ?: JSONObject()
                    put(JSONObject().apply {
                        put("id", toolCall.optString("id"))
                        put("type", toolCall.optString("type", "function"))
                        put("function", JSONObject().apply {
                            put("name", functionObj.optString("name"))
                            put("arguments", functionObj.optString("arguments", "{}"))
                        })
                    })
                }
            })
        }
    }

    private fun isToolProtocolMessage(msg: JSONObject): Boolean {
        return msg.optString("role") == "tool" ||
            msg.has("tool_calls") ||
            msg.has("tool_call_id")
    }

    private fun normalizedChatRole(role: String): String {
        return when (role.lowercase()) {
            "assistant" -> "assistant"
            "system" -> "system"
            else -> "user"
        }
    }

    private fun buildPlainMessageContent(msg: JSONObject, includeFileContext: Boolean = true): String {
        val content = msg.optString("content", "")
            .replace(Regex("!\\[[^]]*]\\(data:image/[^)]+\\)"), "[Generated image]")
        val fileName = msg.optString("fileName", "")
        val fileContext = if (includeFileContext) {
            msg.optString("fileContext", "").ifBlank { msg.optString("fileText", "") }
        } else {
            ""
        }

        if (fileName.isBlank() && fileContext.isBlank()) {
            return content
        }

        return buildString {
            append(content)
            if (isNotEmpty()) append("\n\n")
            append("Прикреплённый файл")
            if (fileName.isNotBlank()) append(": $fileName")
            if (fileContext.isNotBlank()) append("\n\nВыдержка:\n$fileContext")
        }
    }

    private fun formatOpenAiError(message: String?): String {
        val cleanMessage = message?.takeIf { it.isNotBlank() } ?: "Unknown OpenAI error"
        return if (
            cleanMessage.startsWith("OpenAI") ||
            cleanMessage.startsWith("Invalid OpenAI") ||
            cleanMessage.startsWith("Insufficient OpenAI")
        ) {
            cleanMessage
        } else {
            "OpenAI error: $cleanMessage"
        }
    }

    private fun createOpenAiClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .addInterceptor(HttpLoggingInterceptor().apply {
                redactHeader("Authorization")
                level = if (BuildConfig.DEBUG) {
                    HttpLoggingInterceptor.Level.BASIC
                } else {
                    HttpLoggingInterceptor.Level.NONE
                }
            })
            .build()
    }

    private fun parseOpenAiError(statusCode: Int, body: String): String {
        val parsedMessage = runCatching {
            val json = JSONObject(body)
            json.optJSONObject("error")?.optString("message")
                ?: json.optString("message")
        }.getOrDefault("")

        return when {
            statusCode == 401 -> "Invalid OpenAI API key. Check your key in Settings → AI Provider."
            statusCode == 429 -> "OpenAI rate limit exceeded. Try again later."
            statusCode == 402 -> "Insufficient OpenAI credits. Check your billing at platform.openai.com."
            parsedMessage.isNotBlank() -> "OpenAI: $parsedMessage"
            else -> "OpenAI request failed: HTTP $statusCode"
        }
    }
}
