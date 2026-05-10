package com.example.chatapp.network.dto

import androidx.annotation.Keep

@Keep
data class AiLimitsResponse(
    val dailyLimit: Int,
    val usedToday: Int,
    val baseRemaining: Int,
    val bonusRequests: Int,
    val totalRemaining: Int,
    val resetAt: String?
)

@Keep
data class AiTrendingResponse(
    val queries: List<String> = emptyList()
)

@Keep
data class AiModelsResponse(
    val defaultProvider: String = "openai",
    val models: List<AiModelDescriptorResponse> = emptyList()
)

@Keep
data class AiModelDescriptorResponse(
    val provider: String,
    val modelKey: String,
    val displayName: String,
    val isDefault: Boolean = false
)
