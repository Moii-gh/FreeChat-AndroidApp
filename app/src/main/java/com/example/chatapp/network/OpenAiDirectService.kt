package com.example.chatapp.network

import android.util.Log
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
 * Прямой клиент OpenAI API с поддержкой function-calling (tools).
 *
 * Модель сама решает, когда нужно:
 *  • искать в интернете   (web_search)
 *  • генерировать картинку (generate_image)
 *  • редактировать картинку (edit_image)
 *  • придумать название чата (generate_chat_title)
 *
 * Все tools возвращают результат «фиктивно» — модель получает
 * текстовый placeholder и формулирует ответ сама.
 */
object OpenAiDirectService {

    private const val TAG = "OpenAiDirect"
    private const val BASE_URL = "https://api.openai.com/v1/"
    private const val MODEL = "gpt-5.4-mini"

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // ──────── Tool definitions ────────

    private val toolsArray: JSONArray by lazy {
        JSONArray().apply {
            put(toolDefinition(
                name = "web_search",
                description = "Поиск информации в интернете. Используй когда пользователь просит найти актуальную информацию, новости, факты.",
                parameters = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("query", JSONObject().apply {
                            put("type", "string")
                            put("description", "Поисковый запрос")
                        })
                    })
                    put("required", JSONArray().put("query"))
                }
            ))
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

    // ──────── Streaming chat ────────

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

                val messages = buildMessagesArray(systemPrompt, messagesToKeep)

                // First request — may trigger tool calls
                var finalReply = executeWithToolLoop(apiKey, messages, coroutineContext, callback)

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
                    callback.onError("OpenAI error: ${error.message}")
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
            // Обрабатываем tool calls
            messages.put(assistantMessage) // добавляем assistant message с tool_calls

            val executedToolsText = java.lang.StringBuilder()
            val toolCalls = assistantMessage.optJSONArray("tool_calls") ?: JSONArray()
            for (i in 0 until toolCalls.length()) {
                val toolCall = toolCalls.getJSONObject(i)
                val toolId = toolCall.getString("id")
                val functionObj = toolCall.getJSONObject("function")
                val functionName = functionObj.getString("name")
                
                val statusText = when (functionName) {
                    "web_search" -> "Поиск в сети..."
                    "generate_image" -> "Генерация изображения..."
                    else -> "Использование инструмента $functionName..."
                }
                withContext(Dispatchers.Main) { callback.onChunk(statusText) }

                val args = runCatching {
                    JSONObject(functionObj.getString("arguments"))
                }.getOrDefault(JSONObject())

                val toolResult = executeToolCall(apiKey, functionName, args, messages)
                
                val toolNameReadable = when (functionName) {
                    "web_search" -> "Поиск в сети"
                    "generate_image" -> "Генерация изображения"
                    else -> functionName
                }
                
                // По просьбе пользователя не добавляем префикс об инструментах в финальный ответ

                messages.put(JSONObject().apply {
                    put("role", "tool")
                    put("tool_call_id", toolId)
                    put("content", toolResult)
                })
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
        Log.d(TAG, "Tool call: $functionName, args: $args")
        return when (functionName) {
            "web_search" -> {
                val query = args.optString("query", "")
                if (query.isBlank()) return "Пустой запрос"
                searchInInternet(query)
            }
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

    private fun searchInInternet(query: String): String {
        return try {
            val formBody = okhttp3.FormBody.Builder()
                .add("q", query)
                .build()

            val request = Request.Builder()
                .url("https://html.duckduckgo.com/html/")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .post(formBody)
                .build()

            val response = createOpenAiClient().newCall(request).execute()
            val html = response.body?.string() ?: return "Нет ответа от поисковика."

            val regex = "<a class=\"result__snippet[^>]*>(.*?)</a>".toRegex(RegexOption.IGNORE_CASE)
            val matches = regex.findAll(html)
            
            val snippets = mutableListOf<String>()
            for (match in matches.take(5)) {
                val snippet = match.groups[1]?.value?.replace(Regex("<[^>]+>"), "")?.replace(Regex("&\\w+;"), " ")?.trim()
                if (!snippet.isNullOrBlank()) {
                    snippets.add("- $snippet")
                }
            }
            
            if (snippets.isEmpty()) {
                "Нет результатов поиска для: $query"
            } else {
                "Результаты поиска:\n" + snippets.joinToString("\n")
            }
        } catch (e: Exception) {
            "Ошибка при поиске: ${e.message}"
        }
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
                val url = JSONObject(responseBody).optJSONArray("data")?.optJSONObject(0)?.optString("url")
                if (url != null) {
                    "Изображение успешно изменено. Выведи пользователю следующий Markdown: ![Отредактированное изображение]($url)"
                } else {
                    "Ошибка: URL изображения не найден в ответе."
                }
            } else {
                "Ошибка при редактировании изображения: $responseBody"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Image Edit Error", e)
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
                val url = JSONObject(responseBody).optJSONArray("data")?.optJSONObject(0)?.optString("url")
                if (url != null) {
                    "Изображение успешно сгенерировано. Выведи пользователю следующий Markdown: ![Сгенерированное изображение]($url)"
                } else {
                    "Ошибка: URL изображения не найден в ответе."
                }
            } else {
                "Ошибка при генерации изображения: $responseBody"
            }
        } catch (e: Exception) {
            "Сетевая ошибка при генерации изображения: ${e.message}"
        }
    }

    // ──────── Non-streaming request (for initial tool detection) ────────

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

    // ──────── Streaming request ────────

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
                    val errorMsg = parseOpenAiError(response.code, responseBody?.string().orEmpty())
                    withContext(Dispatchers.Main) {
                        callback.onError(errorMsg)
                    }
                    return ""
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

    // ──────── Title generation ────────

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
                    Log.e(TAG, "generateTitle error: ${response.code} $responseBody")
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

    // ──────── Summarization ────────

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

    // ──────── Helpers ────────

    private fun buildOpenAiSystemPrompt(
        currentMode: String?,
        customInstructions: String,
        chatContextSummary: String,
        filesContext: String
    ): String {
        val parts = mutableListOf<String>()

        parts.add(
            "Ты — умный AI-ассистент. Отвечай на языке пользователя. " +
            "У тебя есть доступ к инструментам (tools): поиск в интернете, генерация и редактирование изображений. " +
            "Используй их когда это уместно — сам определяй, когда нужен тот или иной инструмент."
        )

        val modePrompt = when (currentMode) {
            "shopping" -> "Ты помощник по покупкам. Ищи варианты, сравнивай характеристики и отмечай реальные ограничения."
            "study" -> "Ты учебный помощник. Объясняй пошагово, проверяй понимание и помогай закрепить материал."
            "search" -> "Пользователь хочет найти информацию. Используй инструмент web_search для поиска."
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

    private fun buildMessagesArray(
        systemPrompt: String,
        messagesToKeep: List<JSONObject>
    ): JSONArray {
        val messages = JSONArray()
        messages.put(JSONObject().apply {
            put("role", "system")
            put("content", systemPrompt)
        })

        messagesToKeep.forEach { msg ->
            val content = msg.optString("content", "")
            val mimeType = msg.optString("mimeType", "").ifBlank { "image/jpeg" }
            val role = msg.optString("role", "user")

            if (msg.has("base64") && mimeType.startsWith("image/", ignoreCase = true)) {
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
                val fullContent = buildString {
                    append(content)
                    val fileName = msg.optString("fileName", "")
                    val fileContext = msg.optString("fileContext", "").ifBlank { msg.optString("fileText", "") }
                    if (fileName.isNotBlank() || fileContext.isNotBlank()) {
                        if (isNotEmpty()) append("\n\n")
                        append("Прикреплённый файл")
                        if (fileName.isNotBlank()) append(": $fileName")
                        if (fileContext.isNotBlank()) append("\n\nВыдержка:\n$fileContext")
                    }
                }
                messages.put(JSONObject().apply {
                    put("role", role)
                    put("content", fullContent)
                })
            }
        }

        return messages
    }

    private fun createOpenAiClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
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
