package com.example.chatapp.network

import com.example.chatapp.BuildConfig
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

object AiApiService {

    interface StreamCallback {
        fun onChunk(accumulatedText: String)
        fun onComplete(fullText: String)
        fun onError(errorMessage: String)
    }

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

    fun buildRequestBody(
        target: AiTarget,
        messagesToKeep: List<JSONObject>,
        systemPrompt: String?
    ): String {
        return JSONObject().apply {
            if (target.isImageGeneration) {
                put("model", target.model)
                put("response_format", "b64_json")
                val lastPrompt = messagesToKeep.lastOrNull {
                    it.getString("role") == "user"
                }?.getString("content") ?: "Creative image"
                put("prompt", lastPrompt)
            } else {
                put("model", target.model)
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
        val fileName = msg.optString("fileName", "")
        val mimeType = msg.optString("mimeType", "")
        val fileText = msg.optString("fileText", "")

        if (fileName.isBlank() && mimeType.isBlank() && fileText.isBlank()) {
            return content
        }

        return buildString {
            append(content)
            if (isNotBlank()) append("\n\n")
            append("Attached file")
            if (fileName.isNotBlank()) append(": ").append(fileName)
            if (mimeType.isNotBlank()) append("\nMIME type: ").append(mimeType)
            if (fileText.isNotBlank()) {
                append("\n\nFile content:\n")
                append(fileText)
            } else if (msg.has("base64") && !isImageMimeType(mimeType)) {
                append("\n\nThe file is attached as inline binary data.")
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
        target: AiTarget,
        messagesToKeep: List<JSONObject>,
        currentMode: String?,
        customInstructions: String,
        chatContextSummary: String,
        callback: StreamCallback
    ) {
        withContext(Dispatchers.IO) {
            if (!isTargetConfigured(target)) {
                withContext(Dispatchers.Main) {
                    callback.onError("AI сервис не настроен")
                }
                return@withContext
            }

            try {
                val systemPrompt = buildSystemPrompt(currentMode, customInstructions, chatContextSummary)
                val jsonInput = buildRequestBody(target, messagesToKeep, systemPrompt)

                val connection = (URL(target.apiUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json; utf-8")
                    setRequestProperty("Authorization", "Bearer ${ApiKeyProvider.aiApiKey}")
                    doOutput = true
                }

                OutputStreamWriter(connection.outputStream).use {
                    it.write(jsonInput)
                    it.flush()
                }

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    var finalReply = ""

                    if (target.isImageGeneration) {
                        val response = BufferedReader(
                            InputStreamReader(connection.inputStream, "utf-8")
                        ).readText()
                        val b64 = JSONObject(response)
                            .getJSONArray("data")
                            .getJSONObject(0)
                            .getString("b64_json")
                        finalReply = "![image](data:image/png;base64,$b64)"
                        withContext(Dispatchers.Main) {
                            callback.onChunk(finalReply)
                        }
                    } else {
                        val reader = BufferedReader(InputStreamReader(connection.inputStream, "utf-8"))
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            if (line!!.startsWith("data:")) {
                                val data = line!!.substring(5).trim()
                                if (data == "[DONE]" || data.isEmpty()) continue

                                try {
                                    val json = JSONObject(data)
                                    val chunk = if (json.has("choices")) {
                                        val choices = json.getJSONArray("choices")
                                        if (choices.length() > 0) {
                                            choices.getJSONObject(0)
                                                .getJSONObject("delta")
                                                .optString("content", "")
                                        } else {
                                            ""
                                        }
                                    } else {
                                        ""
                                    }

                                    if (chunk.isNotEmpty()) {
                                        finalReply += chunk
                                        withContext(Dispatchers.Main) {
                                            callback.onChunk(finalReply)
                                        }
                                    }
                                } catch (_: Exception) {
                                }
                            }
                        }
                    }

                    withContext(Dispatchers.Main) {
                        callback.onComplete(finalReply)
                    }
                } else {
                    val errorBody = try {
                        connection.errorStream?.bufferedReader()?.readText() ?: ""
                    } catch (_: Exception) {
                        ""
                    }

                    val errorMessage = try {
                        JSONObject(errorBody).getJSONObject("error").getString("message")
                    } catch (_: Exception) {
                        "Код ${connection.responseCode}"
                    }

                    withContext(Dispatchers.Main) {
                        callback.onError("Ошибка: $errorMessage")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    callback.onError("Ошибка сети: ${e.message}")
                }
            }
        }
    }

    suspend fun summarizeMessages(messagesToSummarize: List<JSONObject>): String? {
        if (BuildConfig.AI_CHAT_URL.isBlank() ||
            BuildConfig.AI_SUMMARY_MODEL.isBlank() ||
            ApiKeyProvider.aiApiKey.isBlank()
        ) {
            return null
        }

        return withContext(Dispatchers.IO) {
            try {
                val connection = (URL(BuildConfig.AI_CHAT_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json; utf-8")
                    setRequestProperty("Authorization", "Bearer ${ApiKeyProvider.aiApiKey}")
                    doOutput = true
                }

                val promptText =
                    "Сделай краткую выжимку важных фактов из этой части переписки (сохрани ключевые детали):\n" +
                        messagesToSummarize.joinToString("\n") {
                            it.getString("role") + ": " + it.getString("content")
                        }

                val jsonInput = JSONObject().apply {
                    put("model", BuildConfig.AI_SUMMARY_MODEL)
                    put("stream", false)
                    put("max_tokens", 300)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "system")
                            put("content", "Обнови информацию.")
                        })
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", promptText)
                        })
                    })
                }.toString()

                OutputStreamWriter(connection.outputStream).use {
                    it.write(jsonInput)
                    it.flush()
                }

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = BufferedReader(
                        InputStreamReader(connection.inputStream, "utf-8")
                    ).readText()
                    JSONObject(response)
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun isTargetConfigured(target: AiTarget): Boolean {
        return target.apiUrl.isNotBlank() &&
            target.model.isNotBlank() &&
            ApiKeyProvider.aiApiKey.isNotBlank()
    }
}
