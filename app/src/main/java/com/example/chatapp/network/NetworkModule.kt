package com.example.chatapp.network

import android.util.Log
import com.example.chatapp.BuildConfig
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.net.URI
import java.util.concurrent.TimeUnit

object NetworkModule {
    private const val TAG = "NetworkModule"

    fun normalizedBaseUrl(baseUrl: String): String {
        val normalized = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val scheme = runCatching { URI(normalized).scheme?.lowercase() }.getOrNull()
            ?: throw IllegalStateException("APP_API_BASE_URL must be an absolute URL")

        if (!BuildConfig.ALLOW_HTTP_BASE_URL && scheme != "https") {
            throw IllegalStateException("Release builds require HTTPS APP_API_BASE_URL")
        }

        return normalized
    }

    fun createAuthApiService(baseUrl: String): AuthApiService {
        return Retrofit.Builder()
            .baseUrl(normalizedBaseUrl(baseUrl))
            .client(baseClientBuilder(timeoutSeconds = 20).build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AuthApiService::class.java)
    }

    fun createSyncApiService(baseUrl: String, token: String): SyncApiService {
        return Retrofit.Builder()
            .baseUrl(normalizedBaseUrl(baseUrl))
            .client(
                baseClientBuilder(timeoutSeconds = 30)
                    .addInterceptor(authorizationInterceptor(token))
                    .build()
            )
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SyncApiService::class.java)
    }

    fun createAiLimitsApiService(baseUrl: String, token: String): AiLimitsApiService {
        return Retrofit.Builder()
            .baseUrl(normalizedBaseUrl(baseUrl))
            .client(
                baseClientBuilder(timeoutSeconds = 20)
                    .addInterceptor(authorizationInterceptor(token))
                    .build()
            )
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AiLimitsApiService::class.java)
    }

    fun createChatShareApiService(baseUrl: String, token: String? = null): ChatShareApiService {
        val clientBuilder = baseClientBuilder(timeoutSeconds = 30)
        if (!token.isNullOrBlank()) {
            clientBuilder.addInterceptor(authorizationInterceptor(token))
        }

        return Retrofit.Builder()
            .baseUrl(normalizedBaseUrl(baseUrl))
            .client(clientBuilder.build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ChatShareApiService::class.java)
    }

    fun createAiHttpClient(token: String): OkHttpClient {
        return baseClientBuilder(timeoutSeconds = 120)
            .addInterceptor(authorizationInterceptor(token))
            .build()
    }

    private fun baseClientBuilder(timeoutSeconds: Long): OkHttpClient.Builder {
        return OkHttpClient.Builder()
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .addInterceptor(sanitizedLoggingInterceptor())
    }

    private fun authorizationInterceptor(token: String): Interceptor = Interceptor { chain ->
        val request = chain.request().newBuilder()
            .addHeader("Authorization", "Bearer $token")
            .build()
        chain.proceed(request)
    }

    private fun sanitizedLoggingInterceptor(): HttpLoggingInterceptor {
        val logger = HttpLoggingInterceptor.Logger { message ->
            Log.d(TAG, redact(message))
        }

        return HttpLoggingInterceptor(logger).apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
    }

    private fun redact(message: String): String {
        return if (message.startsWith("Authorization:", ignoreCase = true)) {
            "Authorization: ██"
        } else {
            message
        }
    }
}
