package com.example.chatapp.network

import com.example.chatapp.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

object AiApiService {

    interface StreamCallback {
        fun onChunk(accumulatedText: String)
        fun onComplete(fullText: String)
        fun onError(errorMessage: String)
    }

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun buildSystemPrompt(
        currentMode: String?,
        customInstructions: String,
        chatContextSummary: String,
        filesContext: String = ""
    ): String? {
        val baseSystemPrompt = when (currentMode) {
            "search" -> "Пользователь включил поиск в сети. Используй online search-возможности выбранной модели, отвечай по актуальной информации и добавляй раздел \"Источники\" со ссылками."
            "shopping" -> "Ты помощник по покупкам. Используй online search-возможности выбранной модели, ищи актуальные варианты, сравнивай характеристики, отмечай реальные ограничения и добавляй раздел \"Источники\" со ссылками."
            "study" -> "Ты учебный помощник. Объясняй пошагово, проверяй понимание и помогай закрепить материал."
            else -> null
        }

        val parts = mutableListOf<String>()
        if (baseSystemPrompt != null) parts.add(baseSystemPrompt)
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
                val lastPrompt = messagesToKeep.lastOrNull {
                    it.getString("role") == "user"
                }?.getString("content") ?: "Creative image"
                put("prompt", lastPrompt)
            } else {
                put("stream", true)

                val messages = JSONArray()
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
                        messages.put(JSONObject().apply {
                            put("role", msg.optString("role", "user"))
                            put("content", messageText)
                        })
                    }
                }

                put("messages", messages)
            }
        }.toString()
    }

    private fun buildMessageText(msg: JSONObject): String {
        val content = msg.optString("content", "")
            .replace(Regex("!\\[[^]]*]\\(data:image/[^)]+\\)"), "[Generated image]")
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

    suspend fun fetchStreamingResponse(
        authToken: String,
        messagesToKeep: List<JSONObject>,
        currentMode: String?,
        customInstructions: String,
        chatContextSummary: String,
        filesContext: String = "",
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
                val isImageGeneration = currentMode == "create_image"
                if (currentMode == "search" || currentMode == "shopping") {
                    withContext(Dispatchers.Main) {
                        callback.onChunk("Поиск в сети...")
                    }
                }
                val systemPrompt = buildSystemPrompt(
                    currentMode,
                    customInstructions,
                    chatContextSummary,
                    filesContext
                )
                val jsonInput = buildRequestBody(isImageGeneration, messagesToKeep, systemPrompt)
                val payload = JSONObject().apply {
                    put("currentMode", currentMode)
                    put("request", JSONObject(jsonInput))
                }.toString()

                val request = buildJsonRequest(path = "ai/chat", payload = payload)
                val call = NetworkModule.createAiHttpClient(authToken).newCall(request)
                val cancellationHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
                    if (cause is CancellationException) {
                        call.cancel()
                    }
                }

                try {
                    call.execute().use { response ->
                        val responseBody = response.body
                        if (!response.isSuccessful || responseBody == null) {
                            withContext(Dispatchers.Main) {
                                callback.onError(parseErrorMessage(response.code, responseBody?.string().orEmpty()))
                            }
                            return@use
                        }

                        var finalReply = ""

                        if (isImageGeneration) {
                            val imageData = JSONObject(responseBody.string())
                                .getJSONArray("data")
                                .getJSONObject(0)
                            val b64 = imageData.optString("b64_json")
                            val imageUrl = imageData.optString("url")
                            finalReply = when {
                                b64.isNotBlank() -> "![image](data:image/png;base64,$b64)"
                                imageUrl.isNotBlank() -> "![image]($imageUrl)"
                                else -> error("Image response is empty")
                            }
                            withContext(Dispatchers.Main) {
                                callback.onChunk(finalReply)
                            }
                        } else {
                            BufferedReader(InputStreamReader(responseBody.byteStream(), Charsets.UTF_8)).use { reader ->
                                while (true) {
                                    val line = reader.readLine() ?: break
                                    if (!line.startsWith("data:")) {
                                        continue
                                    }

                                    val data = line.removePrefix("data:").trim()
                                    if (data.isEmpty() || data == "[DONE]") {
                                        continue
                                    }

                                    runCatching {
                                        val json = JSONObject(data)
                                        val choices = json.optJSONArray("choices")
                                        if (choices != null && choices.length() > 0) {
                                            choices.getJSONObject(0)
                                                .optJSONObject("delta")
                                                ?.optString("content", "")
                                                .orEmpty()
                                        } else {
                                            ""
                                        }
                                    }.getOrDefault("").takeIf { it.isNotEmpty() }?.let { chunk ->
                                        finalReply += chunk
                                        withContext(Dispatchers.Main) {
                                            callback.onChunk(finalReply)
                                        }
                                    }
                                }
                            }
                        }

                        withContext(Dispatchers.Main) {
                            callback.onComplete(finalReply)
                        }
                    }
                } finally {
                    cancellationHandle?.dispose()
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
        firstUserMessage: String
    ): String? {
        return executeContentRequest(
            authToken = authToken,
            path = "ai/title",
            payload = JSONObject().apply {
                put("firstUserMessage", firstUserMessage)
            }.toString()
        )?.trim()
            ?.removeSurrounding("\"")
            ?.removeSuffix(".")
            ?.takeIf { it.isNotBlank() && it.length <= 60 }
    }

    suspend fun summarizeMessages(
        authToken: String,
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
                    return@use null
                }

                JSONObject(body).optString("content").takeIf { it.isNotBlank() }
            }
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
}
