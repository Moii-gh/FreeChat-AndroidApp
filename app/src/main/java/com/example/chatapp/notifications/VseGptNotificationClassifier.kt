package com.example.chatapp.notifications

import com.example.chatapp.util.SafeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

data class SmartNotificationPayload(
    val sourcePackageName: String,
    val title: String,
    val text: String
)

enum class SmartNotificationDecision {
    SPAM,
    KEEP
}

class VseGptNotificationClassifier(
    private val apiKeyProvider: () -> String,
    private val client: OkHttpClient = defaultClient()
) {

    suspend fun classify(payload: SmartNotificationPayload): SmartNotificationDecision =
        withContext(Dispatchers.IO) {
            val apiKey = apiKeyProvider().trim()
            if (apiKey.isBlank()) {
                SafeLog.w(TAG, "Skipping smart notification classification: VseGPT API key is missing")
                return@withContext SmartNotificationDecision.KEEP
            }

            val request = Request.Builder()
                .url(CHAT_COMPLETIONS_URL)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json; charset=utf-8")
                .header("X-Title", "FreeChat Smart Notifications")
                .post(buildRequestBody(payload).toRequestBody(JSON_MEDIA_TYPE))
                .build()

            runCatching {
                client.newCall(request).execute().use { response ->
                    val responseText = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        SafeLog.w(TAG, "VseGPT classification failed: http=${response.code}")
                        return@use SmartNotificationDecision.KEEP
                    }

                    parseDecision(responseText) ?: SmartNotificationDecision.KEEP
                }
            }.getOrElse { error ->
                if (error is IOException) {
                    SafeLog.w(TAG, "VseGPT classification network error", error)
                } else {
                    SafeLog.w(TAG, "VseGPT classification failed", error)
                }
                SmartNotificationDecision.KEEP
            }
        }

    internal fun buildRequestBody(payload: SmartNotificationPayload): String {
        val userContent = buildString {
            append("Пакет приложения: ").append(payload.sourcePackageName)
            append("\nЗаголовок: ").append(payload.title.ifBlank { "-" })
            append("\nТекст: ").append(payload.text.ifBlank { "-" })
        }

        return JSONObject().apply {
            put("model", MODEL)
            put("temperature", 0)
            put("max_tokens", 4)
            put("stream", false)
            put(
                "messages",
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("role", "system")
                            put("content", SYSTEM_PROMPT)
                        }
                    )
                    put(
                        JSONObject().apply {
                            put("role", "user")
                            put("content", userContent)
                        }
                    )
                }
            )
        }.toString()
    }

    internal fun parseDecision(responseBody: String): SmartNotificationDecision? {
        val content = runCatching {
            JSONObject(responseBody)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                .orEmpty()
        }.getOrDefault("")

        val normalized = content
            .trim()
            .lineSequence()
            .firstOrNull()
            ?.trim()
            ?.uppercase(Locale.US)
            .orEmpty()

        return when {
            normalized == "SPAM" || normalized.startsWith("SPAM ") -> SmartNotificationDecision.SPAM
            normalized == "KEEP" || normalized.startsWith("KEEP ") -> SmartNotificationDecision.KEEP
            else -> null
        }
    }

    private companion object {
        private const val TAG = "SmartNotifications"
        private const val CHAT_COMPLETIONS_URL = "https://api.vsegpt.ru/v1/chat/completions"
        private const val MODEL = "deepseek/deepseek-v4-flash"
        private const val SYSTEM_PROMPT =
            "Ты ИИ-фильтр. Оцени уведомление. Если это спам, реклама или мусор — ответь 'SPAM'. Если важное, код или личное сообщение — ответь 'KEEP'. Отвечай одним словом."
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .writeTimeout(8, TimeUnit.SECONDS)
                .build()
    }
}
