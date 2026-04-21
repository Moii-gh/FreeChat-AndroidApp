package com.example.chatapp.util

import com.example.chatapp.BuildConfig

/**
 * Централизованный доступ к конфигурации внешних AI-сервисов.
 * Значения читаются из BuildConfig и не хардкодятся в исходниках.
 */
object ApiKeyProvider {

    val aiApiKey: String
        get() = BuildConfig.AI_API_KEY
}
