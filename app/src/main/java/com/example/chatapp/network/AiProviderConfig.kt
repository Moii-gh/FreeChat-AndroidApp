package com.example.chatapp.network

import android.content.Context
import com.example.chatapp.data.AccountScopedSettings

enum class AiProvider(val code: String, val displayLabel: String) {
    OPENAI("openai", "OpenAI API"),
    VSEGPT("vsegpt", "VseGPT");

    companion object {
        fun fromCode(code: String?): AiProvider =
            entries.firstOrNull { it.code == code } ?: OPENAI
    }
}

data class AiModelOption(
    val provider: AiProvider,
    val modelKey: String,
    val displayName: String,
    val isDefault: Boolean = false,
    val capabilities: Set<String> = emptySet()
)

object AiCapabilities {
    const val TEXT = "text"
    const val VISION = "vision"
    const val WEB_SEARCH = "webSearch"
    const val FILE_SEARCH = "fileSearch"
    const val IMAGE_GENERATION = "imageGeneration"
    const val IMAGE_EDIT = "imageEdit"
}

object AiModelCatalog {
    const val OPENAI_DEFAULT_MODEL_KEY = "gpt54"
    const val VSEGPT_DEFAULT_MODEL_KEY = "gemini3"

    val fallbackModels: List<AiModelOption> = listOf(
        AiModelOption(
            provider = AiProvider.OPENAI,
            modelKey = OPENAI_DEFAULT_MODEL_KEY,
            displayName = "GPT-5.4",
            isDefault = true,
            capabilities = setOf(
                AiCapabilities.TEXT,
                AiCapabilities.VISION,
                AiCapabilities.WEB_SEARCH,
                AiCapabilities.FILE_SEARCH,
                AiCapabilities.IMAGE_GENERATION,
                AiCapabilities.IMAGE_EDIT
            )
        ),
        AiModelOption(
            provider = AiProvider.VSEGPT,
            modelKey = "gpt55",
            displayName = "GPT-5.5",
            capabilities = setOf(
                AiCapabilities.TEXT,
                AiCapabilities.WEB_SEARCH,
                AiCapabilities.IMAGE_GENERATION
            )
        ),
        AiModelOption(
            provider = AiProvider.VSEGPT,
            modelKey = VSEGPT_DEFAULT_MODEL_KEY,
            displayName = "Gemini-3",
            isDefault = true,
            capabilities = setOf(
                AiCapabilities.TEXT,
                AiCapabilities.VISION,
                AiCapabilities.WEB_SEARCH,
                AiCapabilities.IMAGE_GENERATION
            )
        ),
        AiModelOption(
            provider = AiProvider.VSEGPT,
            modelKey = "deepseek",
            displayName = "DeepSeek",
            capabilities = setOf(
                AiCapabilities.TEXT,
                AiCapabilities.WEB_SEARCH,
                AiCapabilities.IMAGE_GENERATION
            )
        )
    )

    fun modelsFor(provider: AiProvider): List<AiModelOption> =
        fallbackModels.filter { it.provider == provider }

    fun defaultModelKey(provider: AiProvider): String =
        when (provider) {
            AiProvider.OPENAI -> OPENAI_DEFAULT_MODEL_KEY
            AiProvider.VSEGPT -> VSEGPT_DEFAULT_MODEL_KEY
        }

    fun find(provider: AiProvider, modelKey: String?): AiModelOption {
        val models = modelsFor(provider)
        return models.firstOrNull { it.modelKey == modelKey }
            ?: models.firstOrNull { it.isDefault }
            ?: models.first()
    }

    fun supports(provider: AiProvider, modelKey: String?, capability: String): Boolean =
        find(provider, modelKey).capabilities.contains(capability)
}

class AiProviderSettings(private val accountSettings: AccountScopedSettings) {

    constructor(context: Context) : this(AccountScopedSettings(context))

    init {
        accountSettings.removeString("openai" + "_api_key")
    }

    fun getProvider(): AiProvider =
        AiProvider.fromCode(accountSettings.getString(KEY_AI_PROVIDER))

    fun setProvider(provider: AiProvider) {
        val previousProvider = getProvider()
        accountSettings.saveString(KEY_AI_PROVIDER, provider.code)
        if (previousProvider != provider) {
            setModelKey(AiModelCatalog.defaultModelKey(provider))
        }
    }

    fun getModelKey(): String {
        val provider = getProvider()
        return AiModelCatalog.find(provider, accountSettings.getString(KEY_AI_MODEL_KEY)).modelKey
    }

    fun setModelKey(modelKey: String) {
        val provider = getProvider()
        val safeModelKey = AiModelCatalog.find(provider, modelKey).modelKey
        accountSettings.saveString(KEY_AI_MODEL_KEY, safeModelKey)
    }

    fun getSelectedModel(): AiModelOption {
        val provider = getProvider()
        return AiModelCatalog.find(provider, getModelKey())
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
        const val KEY_AI_MODEL_KEY = "ai_model_key"
        const val KEY_ADULT_MODE_ENABLED = "adult_mode_enabled"
    }
}
