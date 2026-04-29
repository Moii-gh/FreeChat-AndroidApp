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
