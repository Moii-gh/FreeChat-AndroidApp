package com.example.chatapp.util

import com.example.chatapp.BuildConfig

/**
 * Централизованный доступ к API-ключам.
 * Ключи читаются из BuildConfig (определяются в build.gradle.kts),
 * что предотвращает их жёсткое кодирование в исходных файлах.
 *
 * Примечание: для полной безопасности ключи должны проксироваться через
 * собственный backend-сервер, но BuildConfig значительно лучше хардкода.
 */
object ApiKeyProvider {

    /** Ключ VseGPT — используется для поиска (Perplexity) и fallback моделей */
    val vsegptApiKey: String
        get() = BuildConfig.VSEGPT_API_KEY

    /** Ключ Google Gemini — основная бесплатная модель */
    val geminiApiKey: String
        get() = BuildConfig.GEMINI_API_KEY
}
