package com.example.chatapp.network

import com.example.chatapp.BuildConfig
import com.example.chatapp.util.ApiKeyProvider
import java.util.Locale

data class AiTarget(
    val route: String,
    val model: String,
    val apiUrl: String,
    val usesNativeProtocol: Boolean,
    val isImageGeneration: Boolean
)

object AiModelSelector {

    fun selectTarget(
        currentMode: String?,
        aiMode: String,
        hasVision: Boolean,
        forceFallbackRoute: Boolean
    ): AiTarget {
        val isImageGeneration = currentMode == "create_image"
        val requiresSecondaryRoute = currentMode == "search" || currentMode == "shopping"

        var selectedRoute = "primary"
        var selectedModel = ""

        if (requiresSecondaryRoute) {
            selectedRoute = "secondary"
            selectedModel = BuildConfig.SECONDARY_AI_SEARCH_MODEL
        } else {
            when (aiMode) {
                "plus" -> {
                    selectedRoute = "secondary"
                    selectedModel = when {
                        isImageGeneration -> BuildConfig.SECONDARY_AI_IMAGE_MODEL
                        hasVision -> BuildConfig.SECONDARY_AI_VISION_MODEL
                        else -> BuildConfig.SECONDARY_AI_TEXT_MODEL
                    }
                }

                "free" -> {
                    selectedRoute = "primary"
                    selectedModel = if (isImageGeneration) {
                        BuildConfig.PRIMARY_AI_IMAGE_MODEL
                    } else {
                        BuildConfig.PRIMARY_AI_TEXT_MODEL
                    }
                }

                "auto" -> {
                    if (forceFallbackRoute) {
                        selectedRoute = "secondary"
                        selectedModel = when {
                            isImageGeneration -> BuildConfig.SECONDARY_AI_IMAGE_MODEL
                            hasVision -> BuildConfig.SECONDARY_AI_VISION_MODEL
                            else -> BuildConfig.SECONDARY_AI_TEXT_MODEL
                        }
                    } else {
                        when {
                            isImageGeneration -> {
                                selectedRoute = "secondary"
                                selectedModel = BuildConfig.SECONDARY_AI_IMAGE_MODEL
                            }

                            hasVision -> {
                                selectedRoute = "secondary"
                                selectedModel = BuildConfig.SECONDARY_AI_VISION_MODEL
                            }

                            else -> {
                                selectedRoute = "primary"
                                selectedModel = BuildConfig.PRIMARY_AI_TEXT_MODEL
                            }
                        }
                    }
                }

                else -> {
                    selectedRoute = "primary"
                    selectedModel = BuildConfig.PRIMARY_AI_TEXT_MODEL
                }
            }
        }

        val usesNativeProtocol = selectedRoute == "primary" && !isImageGeneration
        val apiUrl = when {
            isImageGeneration && selectedRoute == "primary" -> BuildConfig.PRIMARY_AI_IMAGE_URL
            isImageGeneration && selectedRoute == "secondary" -> BuildConfig.SECONDARY_AI_IMAGE_URL
            selectedRoute == "secondary" -> BuildConfig.SECONDARY_AI_CHAT_URL
            else -> buildPrimaryStreamUrl(selectedModel)
        }

        return AiTarget(
            route = selectedRoute,
            model = selectedModel,
            apiUrl = apiUrl,
            usesNativeProtocol = usesNativeProtocol,
            isImageGeneration = isImageGeneration
        )
    }

    private fun buildPrimaryStreamUrl(model: String): String {
        val template = BuildConfig.PRIMARY_AI_STREAM_URL_TEMPLATE
        if (template.isBlank()) {
            return ""
        }

        return runCatching {
            String.format(
                Locale.US,
                template,
                model,
                ApiKeyProvider.primaryAiApiKey
            )
        }.getOrElse { template }
    }
}
