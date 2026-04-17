package com.example.chatapp.network

/**
 * Конфигурация выбранного AI бэкенда и модели.
 * Результат работы AiModelSelector.
 */
data class AiTarget(
    val backend: String,     // "gemini" или "vsegpt"
    val model: String,       // Идентификатор модели
    val apiUrl: String,      // Полный URL для запроса
    val isNativeGemini: Boolean, // Нужен ли нативный формат Gemini (vs OpenAI)
    val isImageGen: Boolean  // Режим генерации изображений
)

/**
 * Выбор AI-модели и бэкенда на основе текущего режима и настроек.
 *
 * Логика выбора:
 * - search/shopping → всегда VseGPT (Perplexity)
 * - create_image + gemini → Gemini image gen
 * - create_image + vsegpt → Flux
 * - vision (base64 вложения) → VseGPT Qwen-VL
 * - auto → пробуем Gemini, при ошибке fallback на VseGPT
 */
object AiModelSelector {

    /**
     * Определяет целевой бэкенд, модель и URL на основе параметров запроса.
     *
     * @param currentMode текущий режим чата (search, shopping, create_image, study, null)
     * @param aiMode настройка пользователя (auto, free, vsegpt)
     * @param hasVision true если в сообщениях есть base64-вложения (изображения)
     * @param forceVseGpt true при повторной попытке после ошибки Gemini
     * @param geminiApiKey ключ Google Gemini
     */
    fun selectTarget(
        currentMode: String?,
        aiMode: String,
        hasVision: Boolean,
        forceVseGpt: Boolean,
        geminiApiKey: String
    ): AiTarget {
        val isImageGen = currentMode == "create_image"
        val isVseGptMode = currentMode == "search" || currentMode == "shopping"

        var targetBackend = "gemini"
        var targetModel = ""

        if (isVseGptMode) {
            targetBackend = "vsegpt"
            targetModel = "perplexity/llama-3.1-sonar-small-128k-online"
        } else {
            when (aiMode) {
                "vsegpt" -> {
                    targetBackend = "vsegpt"
                    targetModel = when {
                        isImageGen -> "img-flux/flux-2-klein-4b"
                        hasVision -> "openai/gpt-5.4-nano"
                        else -> "openai/gpt-5.4-nano"
                    }
                }
                "free" -> {
                    targetBackend = "gemini"
                    targetModel = when {
                        isImageGen -> "gemini-2.5-flash-image"
                        else -> "gemini-2.5-flash"
                    }
                }
                "auto" -> {
                    if (forceVseGpt) {
                        targetBackend = "vsegpt"
                        targetModel = when {
                            isImageGen -> "img-flux/flux-2-klein-4b"
                            hasVision -> "openai/gpt-5.4-nano"
                            else -> "openai/gpt-5.4-nano"
                        }
                    } else {
                        when {
                            isImageGen -> {
                                targetBackend = "vsegpt"
                                targetModel = "img-flux/flux-2-klein-4b"
                            }
                            hasVision -> {
                                targetBackend = "vsegpt"
                                targetModel = "openai/gpt-5.4-nano"
                            }
                            else -> {
                                targetBackend = "gemini"
                                targetModel = "gemini-2.5-flash"
                            }
                        }
                    }
                }
                else -> {
                    targetBackend = "gemini"
                    targetModel = "gemini-2.5-flash"
                }
            }
        }

        val isNativeGemini = targetBackend == "gemini" && !isImageGen

        val apiUrl = when {
            isImageGen && targetBackend == "gemini" ->
                "https://generativelanguage.googleapis.com/v1beta/openai/images/generations"
            isImageGen && targetBackend == "vsegpt" ->
                "https://api.vsegpt.ru/v1/images/generations"
            targetBackend == "vsegpt" ->
                "https://api.vsegpt.ru/v1/chat/completions"
            else ->
                "https://generativelanguage.googleapis.com/v1beta/models/$targetModel:streamGenerateContent?alt=sse&key=$geminiApiKey"
        }

        return AiTarget(
            backend = targetBackend,
            model = targetModel,
            apiUrl = apiUrl,
            isNativeGemini = isNativeGemini,
            isImageGen = isImageGen
        )
    }
}
