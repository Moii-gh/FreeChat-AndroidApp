package com.example.chatapp.network

import com.example.chatapp.util.ApiKeyProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Сервис для взаимодействия с AI API (Gemini, VseGPT).
 * Отвечает за формирование запросов, стриминг ответов и верификацию.
 */
object AiApiService {

    /**
     * Callback для стриминга — вызывается на каждый новый чанк ответа.
     */
    interface StreamCallback {
        /** Вызывается при получении нового чанка текста */
        fun onChunk(accumulatedText: String)
        /** Вызывается при завершении стриминга */
        fun onComplete(fullText: String)
        /** Вызывается при ошибке */
        fun onError(errorMessage: String)
        /** Вызывается когда нужна повторная генерация через fallback бэкенд */
        fun onFallbackRequired()
    }

    /**
     * Формирует системный промпт на основе режима, инструкций и контекстной выжимки.
     */
    fun buildSystemPrompt(
        currentMode: String?,
        customInstructions: String,
        chatContextSummary: String
    ): String? {
        val baseSystemPrompt = when (currentMode) {
            "shopping" -> "Ты помощник по покупкам. Ищи в интернете только товары, предоставляя варианты с ценами и возможными местами приобретения."
            "study" -> "Ты учитель. Отвечай как опытный преподаватель, объясняй подробно, приводи наглядные примеры и задавай вопросы для проверки понимания."
            else -> null
        }

        val parts = mutableListOf<String>()
        if (baseSystemPrompt != null) parts.add(baseSystemPrompt)
        if (customInstructions.isNotEmpty()) {
            parts.add("Пользовательские инструкции (строго следуй им):\n$customInstructions")
        }
        if (chatContextSummary.isNotEmpty()) {
            parts.add("Краткая выжимка предыдущего разговора (важно для контекста):\n$chatContextSummary")
        }

        return if (parts.isNotEmpty()) parts.joinToString("\n\n") else null
    }

    /**
     * Формирует тело запроса в нативном формате Google Gemini API.
     */
    fun buildNativeGeminiRequestBody(
        messagesToKeep: List<JSONObject>,
        systemPrompt: String?
    ): String {
        return JSONObject().apply {
            if (systemPrompt != null) {
                put("system_instruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().apply { put("text", systemPrompt) }))
                })
            }

            val contents = JSONArray()
            messagesToKeep.forEach { msg ->
                val role = if (msg.getString("role") == "assistant") "model" else "user"
                val contentObj = JSONObject().apply {
                    put("role", role)
                    val parts = JSONArray()
                    parts.put(JSONObject().apply { put("text", msg.getString("content")) })
                    if (msg.has("base64")) {
                        val mimeType = msg.optString("mimeType", "image/jpeg")
                        parts.put(JSONObject().apply {
                            put("inline_data", JSONObject().apply {
                                put("mime_type", mimeType)
                                put("data", msg.getString("base64"))
                            })
                        })
                    }
                    put("parts", parts)
                }
                contents.put(contentObj)
            }
            put("contents", contents)
        }.toString()
    }

    /**
     * Формирует тело запроса в формате OpenAI (для VseGPT и Gemini image gen).
     */
    fun buildOpenAiRequestBody(
        target: AiTarget,
        messagesToKeep: List<JSONObject>,
        systemPrompt: String?
    ): String {
        return JSONObject().apply {
            if (target.isImageGen) {
                put("model", target.model)
                put("response_format", "b64_json")
                val lastPrompt = messagesToKeep.lastOrNull {
                    it.getString("role") == "user"
                }?.getString("content") ?: "Creative image"
                put("prompt", lastPrompt)
            } else {
                put("model", target.model)
                put("stream", true)

                val arr = JSONArray()
                if (systemPrompt != null) {
                    arr.put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                }

                messagesToKeep.forEach { msg ->
                    if (msg.has("base64")) {
                        val visionObj = JSONObject()
                        visionObj.put("role", msg.getString("role"))

                        val contentArr = JSONArray()
                        contentArr.put(JSONObject().apply {
                            put("type", "text")
                            put("text", msg.getString("content"))
                        })
                        contentArr.put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply {
                                val mimeType = msg.optString("mimeType", "image/jpeg")
                                put("url", "data:$mimeType;base64," + msg.getString("base64"))
                            })
                        })
                        visionObj.put("content", contentArr)
                        arr.put(visionObj)
                    } else {
                        arr.put(JSONObject().apply {
                            put("role", msg.getString("role"))
                            put("content", msg.getString("content"))
                        })
                    }
                }
                put("messages", arr)
            }
        }.toString()
    }

    /**
     * Выполняет SSE-стриминг запроса к AI API.
     * Передаёт чанки через callback, при ошибке может запросить fallback.
     */
    suspend fun fetchStreamingResponse(
        target: AiTarget,
        messagesToKeep: List<JSONObject>,
        currentMode: String?,
        customInstructions: String,
        chatContextSummary: String,
        aiMode: String,
        forceVseGpt: Boolean,
        callback: StreamCallback
    ) {
        withContext(Dispatchers.IO) {
            try {
                val systemPrompt = buildSystemPrompt(currentMode, customInstructions, chatContextSummary)

                val jsonInput = if (target.isNativeGemini) {
                    buildNativeGeminiRequestBody(messagesToKeep, systemPrompt)
                } else {
                    buildOpenAiRequestBody(target, messagesToKeep, systemPrompt)
                }

                val url = URL(target.apiUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json; utf-8")
                if (!target.isNativeGemini) {
                    val apiKey = if (target.backend == "vsegpt") {
                        ApiKeyProvider.vsegptApiKey
                    } else {
                        ApiKeyProvider.geminiApiKey
                    }
                    connection.setRequestProperty("Authorization", "Bearer $apiKey")
                }
                connection.doOutput = true

                OutputStreamWriter(connection.outputStream).use {
                    it.write(jsonInput)
                    it.flush()
                }

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    var finalReply = ""

                    if (target.isImageGen) {
                        val response = BufferedReader(InputStreamReader(connection.inputStream, "utf-8")).readText()
                        val b64 = JSONObject(response).getJSONArray("data").getJSONObject(0).getString("b64_json")
                        finalReply = "![image](data:image/png;base64,$b64)"
                        withContext(Dispatchers.Main) {
                            callback.onChunk(finalReply)
                        }
                    } else {
                        // Streaming Server-Sent Events
                        val reader = BufferedReader(InputStreamReader(connection.inputStream, "utf-8"))
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            if (line!!.startsWith("data:")) {
                                val data = line!!.substring(5).trim()
                                if (data == "[DONE]" || data.isEmpty()) continue

                                try {
                                    val json = JSONObject(data)
                                    val chunk = if (target.isNativeGemini) {
                                        if (json.has("candidates")) {
                                            json.getJSONArray("candidates")
                                                .getJSONObject(0)
                                                .getJSONObject("content")
                                                .getJSONArray("parts")
                                                .getJSONObject(0)
                                                .optString("text", "")
                                        } else ""
                                    } else {
                                        if (json.has("choices")) {
                                            val choices = json.getJSONArray("choices")
                                            if (choices.length() > 0) {
                                                choices.getJSONObject(0)
                                                    .getJSONObject("delta")
                                                    .optString("content", "")
                                            } else ""
                                        } else ""
                                    }

                                    if (chunk.isNotEmpty()) {
                                        finalReply += chunk
                                        withContext(Dispatchers.Main) {
                                            callback.onChunk(finalReply)
                                        }
                                    }
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                        }
                    }

                    // Проверка на ответ-заглушку Gemini (лимиты исчерпаны)
                    if (aiMode == "auto" && !forceVseGpt && target.backend == "gemini") {
                        val isLimit = verifyResponseIsLimitError(finalReply)
                        if (isLimit) {
                            withContext(Dispatchers.Main) { callback.onFallbackRequired() }
                            return@withContext
                        }
                    }

                    withContext(Dispatchers.Main) { callback.onComplete(finalReply) }
                } else {
                    handleHttpError(connection, aiMode, forceVseGpt, target, callback)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    callback.onError("Ошибка сети: ${e.message}")
                }
            }
        }
    }

    /**
     * Обработка HTTP-ошибки: если это лимит Gemini в auto-режиме → fallback,
     * иначе → показываем сообщение об ошибке.
     */
    private suspend fun handleHttpError(
        connection: HttpURLConnection,
        aiMode: String,
        forceVseGpt: Boolean,
        target: AiTarget,
        callback: StreamCallback
    ) {
        val errorBody = try {
            connection.errorStream?.bufferedReader()?.readText() ?: ""
        } catch (_: Exception) { "" }

        val isLimitHTTP = connection.responseCode == 429 ||
                errorBody.contains("quota", true) ||
                errorBody.contains("resource has been exhausted", true)

        if (aiMode == "auto" && !forceVseGpt && (isLimitHTTP || target.backend == "gemini")) {
            withContext(Dispatchers.Main) { callback.onFallbackRequired() }
            return
        }

        val errorMsg = try {
            JSONObject(errorBody).getJSONObject("error").getString("message")
        } catch (_: Exception) { "Код ${connection.responseCode}" }

        withContext(Dispatchers.Main) { callback.onError("Ошибка: $errorMsg") }
    }

    /**
     * Верифицирует, является ли ответ сообщением об исчерпании лимитов.
     * Отправляет ответ на проверку в DeepSeek через VseGPT.
     */
    suspend fun verifyResponseIsLimitError(responseText: String): Boolean {
        if (responseText.isEmpty() || responseText.length < 10) return false
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("https://api.vsegpt.ru/v1/chat/completions")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json; utf-8")
                connection.setRequestProperty("Authorization", "Bearer ${ApiKeyProvider.vsegptApiKey}")
                connection.doOutput = true

                val jsonInput = JSONObject().apply {
                    put("model", "deepseek/deepseek-v3.2-alt")
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "system")
                            put("content", "Проанализируй следующий ответ ИИ. Содержит ли он сообщение об исчерпании лимитов, квоты или ошибке сервиса (например 'I am a free model and I reached my limits', 'Resource has been exhausted', 'Quota exceeded')? Ответь строго 'YES' если это так, и 'NO' если ответ ИИ нормальный.")
                        })
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", responseText)
                        })
                    })
                    put("stream", false)
                }.toString()

                OutputStreamWriter(connection.outputStream).use {
                    it.write(jsonInput)
                    it.flush()
                }

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = BufferedReader(InputStreamReader(connection.inputStream, "utf-8")).readText()
                    val answer = JSONObject(response)
                        .getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").getString("content")
                    return@withContext answer.trim().contains("YES", ignoreCase = true)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return@withContext false
        }
    }

    /**
     * Фоновая суммаризация старых сообщений для сжатия контекста.
     * Предотвращает превышение лимита контекстного окна модели.
     */
    suspend fun summarizeMessages(messagesToSummarize: List<JSONObject>): String? {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("https://api.vsegpt.ru/v1/chat/completions")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; utf-8")
                conn.setRequestProperty("Authorization", "Bearer ${ApiKeyProvider.vsegptApiKey}")
                conn.doOutput = true

                val promptText = "Сделай краткую выжимку важных фактов из этой части переписки (сохрани ключевые детали):\n" +
                    messagesToSummarize.joinToString("\n") { it.getString("role") + ": " + it.getString("content") }

                val jsonInput = JSONObject().apply {
                    put("model", "openai/gpt-5.4-nano")
                    put("stream", false)
                    put("max_tokens", 300)
                    val messages = JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "system")
                            put("content", "Обнови информацию.")
                        })
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", promptText)
                        })
                    }
                    put("messages", messages)
                }.toString()

                OutputStreamWriter(conn.outputStream).use { it.write(jsonInput); it.flush() }
                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = BufferedReader(InputStreamReader(conn.inputStream, "utf-8")).readText()
                    JSONObject(response)
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                } else null
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }
}
