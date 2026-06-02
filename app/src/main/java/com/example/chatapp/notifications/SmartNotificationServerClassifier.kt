package com.example.chatapp.notifications

import com.example.chatapp.BuildConfig
import com.example.chatapp.network.NetworkModule
import com.example.chatapp.util.SafeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

data class SmartNotificationPayload(
    val sourcePackageName: String,
    val title: String,
    val text: String
)

enum class SmartNotificationDecision {
    SPAM,
    KEEP
}

class SmartNotificationServerClassifier(
    private val authTokenProvider: () -> String?,
    private val clientProvider: (String) -> OkHttpClient = NetworkModule::createSmartNotificationsHttpClient,
    private val apiBaseUrl: String = BuildConfig.APP_API_BASE_URL
) {

    @Volatile
    private var cachedAuthToken: String = ""

    @Volatile
    private var cachedClient: OkHttpClient? = null

    suspend fun classify(payload: SmartNotificationPayload): SmartNotificationDecision =
        withContext(Dispatchers.IO) {
            val authToken = authTokenProvider()?.trim().orEmpty()
            if (authToken.isBlank()) {
                SafeLog.w(TAG, "Skipping smart notification classification: user token is missing")
                return@withContext SmartNotificationDecision.KEEP
            }

            val endpointUrl = runCatching {
                NetworkModule.normalizedBaseUrl(apiBaseUrl) + ENDPOINT_PATH
            }.getOrElse { error ->
                SafeLog.w(TAG, "Smart notification endpoint is not configured", error)
                return@withContext SmartNotificationDecision.KEEP
            }

            val request = Request.Builder()
                .url(endpointUrl)
                .header("Content-Type", "application/json; charset=utf-8")
                .post(buildRequestBody(payload).toRequestBody(JSON_MEDIA_TYPE))
                .build()

            runCatching {
                clientFor(authToken).newCall(request).execute().use { response ->
                    val responseText = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        SafeLog.w(TAG, "Smart notification classification failed: http=${response.code}")
                        return@use SmartNotificationDecision.KEEP
                    }

                    parseDecision(responseText) ?: SmartNotificationDecision.KEEP
                }
            }.getOrElse { error ->
                if (error is IOException) {
                    SafeLog.w(TAG, "Smart notification classification network error", error)
                } else {
                    SafeLog.w(TAG, "Smart notification classification failed", error)
                }
                SmartNotificationDecision.KEEP
            }
        }

    internal fun buildRequestBody(payload: SmartNotificationPayload): String =
        JSONObject().apply {
            put("packageName", payload.sourcePackageName)
            put("title", payload.title)
            put("text", payload.text)
        }.toString()

    internal fun parseDecision(responseBody: String): SmartNotificationDecision? {
        val normalized = runCatching {
            JSONObject(responseBody)
                .optString("decision")
                .trim()
                .lineSequence()
                .firstOrNull()
                ?.trim()
                ?.uppercase(Locale.US)
                .orEmpty()
        }.getOrDefault("")

        return when {
            normalized == "SPAM" || normalized.startsWith("SPAM ") -> SmartNotificationDecision.SPAM
            normalized == "KEEP" || normalized.startsWith("KEEP ") -> SmartNotificationDecision.KEEP
            else -> null
        }
    }

    private fun clientFor(authToken: String): OkHttpClient {
        cachedClient?.takeIf { cachedAuthToken == authToken }?.let { return it }

        return synchronized(this) {
            cachedClient?.takeIf { cachedAuthToken == authToken } ?: clientProvider(authToken).also {
                cachedAuthToken = authToken
                cachedClient = it
            }
        }
    }

    private companion object {
        private const val TAG = "SmartNotifications"
        private const val ENDPOINT_PATH = "ai/notification-filter"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
