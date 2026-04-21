package com.example.chatapp.network

import com.example.chatapp.BuildConfig

data class AiTarget(
    val model: String,
    val apiUrl: String,
    val isImageGeneration: Boolean
)

object AiModelSelector {

    fun selectTarget(
        currentMode: String?,
        hasVision: Boolean,
        hasFileAttachment: Boolean = false
    ): AiTarget {
        val isImageGeneration = currentMode == "create_image"
        val requiresSearch = currentMode == "search" || currentMode == "shopping"
        val hasAttachment = hasVision || hasFileAttachment

        val selectedModel = when {
            requiresSearch -> {
                if (hasVision && BuildConfig.AI_VISION_MODEL.isNotBlank()) {
                    BuildConfig.AI_VISION_MODEL
                } else {
                    BuildConfig.AI_SEARCH_MODEL
                }
            }
            isImageGeneration -> BuildConfig.AI_IMAGE_MODEL
            hasAttachment && BuildConfig.AI_VISION_MODEL.isNotBlank() -> BuildConfig.AI_VISION_MODEL
            else -> BuildConfig.AI_TEXT_MODEL
        }

        val apiUrl = when {
            isImageGeneration -> BuildConfig.AI_IMAGE_URL
            else -> BuildConfig.AI_CHAT_URL
        }

        return AiTarget(
            model = selectedModel,
            apiUrl = apiUrl,
            isImageGeneration = isImageGeneration
        )
    }
}
