package com.example.chatapp.util

import com.example.chatapp.BuildConfig

/**
 * Централизованный доступ к конфигурации внешних AI-сервисов.
 * Значения читаются из BuildConfig и не хардкодятся в исходниках.
 */
object ApiKeyProvider {

    val primaryAiApiKey: String
        get() = BuildConfig.PRIMARY_AI_API_KEY

    val secondaryAiApiKey: String
        get() = BuildConfig.SECONDARY_AI_API_KEY
}
