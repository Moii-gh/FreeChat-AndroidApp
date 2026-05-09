package com.example.chatapp.network

import android.content.Context
import com.example.chatapp.data.AccountScopedSettings

/**
 * Поддерживаемые AI-провайдеры.
 *
 * VSEGPT  — все запросы уходят на наш бэкенд (текущее поведение).
 * OPENAI  — напрямую к OpenAI API с вызовом инструментов.
 */
enum class AiProvider(val code: String, val displayLabel: String) {
    VSEGPT("vsegpt", "VseGPT (сервер)"),
    OPENAI("openai", "OpenAI API");

    companion object {
        fun fromCode(code: String?): AiProvider =
            entries.firstOrNull { it.code == code } ?: VSEGPT
    }
}

/**
 * Чтение / запись настроек провайдера.
 * Хранит провайдер и ключ API в AccountScopedSettings.
 */
class AiProviderSettings(private val accountSettings: AccountScopedSettings) {

    constructor(context: Context) : this(AccountScopedSettings(context))

    fun getProvider(): AiProvider =
        AiProvider.fromCode(accountSettings.getString(KEY_AI_PROVIDER))

    fun setProvider(provider: AiProvider) {
        accountSettings.saveString(KEY_AI_PROVIDER, provider.code)
    }

    fun getOpenAiApiKey(): String =
        accountSettings.getString(KEY_OPENAI_API_KEY)

    fun setOpenAiApiKey(key: String) {
        accountSettings.saveString(KEY_OPENAI_API_KEY, key.trim())
    }

    fun isAdultModeEnabled(): Boolean =
        accountSettings.getBoolean(KEY_ADULT_MODE_ENABLED)

    fun setAdultModeEnabled(enabled: Boolean) {
        accountSettings.saveBoolean(KEY_ADULT_MODE_ENABLED, enabled)
        if (enabled) {
            setProvider(AiProvider.VSEGPT)
        }
    }

    private companion object {
        const val KEY_AI_PROVIDER = "ai_provider"
        const val KEY_OPENAI_API_KEY = "openai_api_key"
        const val KEY_ADULT_MODE_ENABLED = "adult_mode_enabled"
    }
}
