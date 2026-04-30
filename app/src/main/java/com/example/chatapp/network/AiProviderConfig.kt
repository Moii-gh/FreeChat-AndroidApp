package com.example.chatapp.network

import android.content.Context
import com.example.chatapp.data.AccountScopedSettings

/**
 * Поддерживаемые AI-провайдеры.
 *
 * VSEGPT  — все запросы уходят на наш бэкенд (текущее поведение).
 * OPENAI  — напрямую к OpenAI API с function-calling (tools).
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
        AiProvider.fromCode(accountSettings.getString("ai_provider"))

    fun setProvider(provider: AiProvider) {
        accountSettings.saveString("ai_provider", provider.code)
    }

    fun getOpenAiApiKey(): String =
        accountSettings.getString("openai_api_key")

    fun setOpenAiApiKey(key: String) {
        accountSettings.saveString("openai_api_key", key.trim())
    }

    fun isAdultModeEnabled(): Boolean =
        accountSettings.getBoolean("adult_mode_enabled")

    fun setAdultModeEnabled(enabled: Boolean) {
        accountSettings.saveBoolean("adult_mode_enabled", enabled)
        if (enabled) {
            setProvider(AiProvider.VSEGPT)
        }
    }
}
