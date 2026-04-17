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
        fun onFallbackRequired()
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

    fun buildNativeRequestBody(
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
                contents.put(
                    JSONObject().apply {
                        put("role", role)
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", msg.getString("content")) })
                            if (msg.has("base64")) {
                                val mimeType = msg.optString("mimeType", "image/jpeg")
                                put(JSONObject().apply {
                                    put("inline_data", JSONObject().apply {
                                        put("mime_type", mimeType)
                                        put("data", msg.getString("base64"))
                                    })
                                })
                            }
                        })
                    }
                )
            }
            put("contents", contents)
        }.toString()
    }

    fun buildCompatibleRequestBody(
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
                    if (msg.has("base64")) {
                        messages.put(
                            JSONObject().apply {
                                put("role", msg.getString("role"))
                                put("content", JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("type", "text")
                                        put("text", msg.getString("content"))
                                    })
                                    put(JSONObject().apply {
                                        put("type", "image_url")
                                        put("image_url", JSONObject().apply {
                                            val mimeType = msg.optString("mimeType", "image/jpeg")
                                            put("url", "data:$mimeType;base64," + msg.getString("base64"))
                                        })
                                    })
                                })
                            }
                        )
                    } else {
                        messages.put(JSONObject().apply {
                            put("role", msg.getString("role"))
                            put("content", msg.getString("content"))
                        })
                    }
                }

                put("messages", messages)
            }
        }.toString()
    }

    suspend fun fetchStreamingResponse(
        target: AiTarget,
        messagesToKeep: List<JSONObject>,
        currentMode: String?,
        customInstructions: String,
        chatContextSummary: String,
        aiMode: String,
        forceFallbackRoute: Boolean,
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
                val jsonInput = if (target.usesNativeProtocol) {
                    buildNativeRequestBody(messagesToKeep, systemPrompt)
                } else {
                    buildCompatibleRequestBody(target, messagesToKeep, systemPrompt)
                }

                val connection = (URL(target.apiUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json; utf-8")
                    if (!target.usesNativeProtocol) {
                        setRequestProperty("Authorization", "Bearer ${resolvedApiKey(target.route)}")
                    }
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
                                    val chunk = if (target.usesNativeProtocol) {
                                        if (json.has("candidates")) {
                                            json.getJSONArray("candidates")
                                                .getJSONObject(0)
                                                .getJSONObject("content")
                                                .getJSONArray("parts")
                                                .getJSONObject(0)
                                                .optString("text", "")
                                        } else {
                                            ""
                                        }
                                    } else {
                                        if (json.has("choices")) {
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

                    if (aiMode == "auto" && !forceFallbackRoute && target.route == "primary") {
                        val isLimit = verifyResponseIsLimitError(finalReply)
                        if (isLimit) {
                            withContext(Dispatchers.Main) {
                                callback.onFallbackRequired()
                            }
                            return@withContext
                        }
                    }

                    withContext(Dispatchers.Main) {
                        callback.onComplete(finalReply)
                    }
                } else {
                    handleHttpError(connection, aiMode, forceFallbackRoute, target, callback)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    callback.onError("Ошибка сети: ${e.message}")
                }
            }
        }
    }

    private suspend fun handleHttpError(
        connection: HttpURLConnection,
        aiMode: String,
        forceFallbackRoute: Boolean,
        target: AiTarget,
        callback: StreamCallback
    ) {
        val errorBody = try {
            connection.errorStream?.bufferedReader()?.readText() ?: ""
        } catch (_: Exception) {
            ""
        }

        val isLimitHttp = connection.responseCode == 429 ||
            errorBody.contains("quota", true) ||
            errorBody.contains("resource has been exhausted", true)

        if (aiMode == "auto" && !forceFallbackRoute && (isLimitHttp || target.route == "primary")) {
            withContext(Dispatchers.Main) {
                callback.onFallbackRequired()
            }
            return
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

    suspend fun verifyResponseIsLimitError(responseText: String): Boolean {
        if (responseText.isEmpty() || responseText.length < 10) return false
        if (BuildConfig.SECONDARY_AI_CHAT_URL.isBlank() ||
            BuildConfig.SECONDARY_AI_AUDIT_MODEL.isBlank() ||
            ApiKeyProvider.secondaryAiApiKey.isBlank()
        ) {
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                val connection = (URL(BuildConfig.SECONDARY_AI_CHAT_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json; utf-8")
                    setRequestProperty("Authorization", "Bearer ${ApiKeyProvider.secondaryAiApiKey}")
                    doOutput = true
                }

                val jsonInput = JSONObject().apply {
                    put("model", BuildConfig.SECONDARY_AI_AUDIT_MODEL)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "system")
                            put(
                                "content",
                                "Проанализируй следующий ответ ИИ. Содержит ли он сообщение об исчерпании лимитов, квоты или ошибке сервиса? Ответь строго YES или NO."
                            )
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
                    val response = BufferedReader(
                        InputStreamReader(connection.inputStream, "utf-8")
                    ).readText()
                    val answer = JSONObject(response)
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")

                    return@withContext answer.trim().contains("YES", ignoreCase = true)
                }
            } catch (_: Exception) {
            }

            false
        }
    }

    suspend fun summarizeMessages(messagesToSummarize: List<JSONObject>): String? {
        if (BuildConfig.SECONDARY_AI_CHAT_URL.isBlank() ||
            BuildConfig.SECONDARY_AI_SUMMARY_MODEL.isBlank() ||
            ApiKeyProvider.secondaryAiApiKey.isBlank()
        ) {
            return null
        }

        return withContext(Dispatchers.IO) {
            try {
                val connection = (URL(BuildConfig.SECONDARY_AI_CHAT_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json; utf-8")
                    setRequestProperty("Authorization", "Bearer ${ApiKeyProvider.secondaryAiApiKey}")
                    doOutput = true
                }

                val promptText =
                    "Сделай краткую выжимку важных фактов из этой части переписки (сохрани ключевые детали):\n" +
                        messagesToSummarize.joinToString("\n") {
                            it.getString("role") + ": " + it.getString("content")
                        }

                val jsonInput = JSONObject().apply {
                    put("model", BuildConfig.SECONDARY_AI_SUMMARY_MODEL)
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
        if (target.apiUrl.isBlank() || target.model.isBlank()) {
            return false
        }

        return target.usesNativeProtocol || resolvedApiKey(target.route).isNotBlank()
    }

    private fun resolvedApiKey(route: String): String =
        if (route == "secondary") {
            ApiKeyProvider.secondaryAiApiKey
        } else {
            ApiKeyProvider.primaryAiApiKey
        }
}
