package com.example.chatapp.network

import com.example.chatapp.network.dto.AiLimitsResponse
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.POST

interface AiLimitsApiService {
    @GET("ai/limits")
    suspend fun getLimits(): Response<AiLimitsResponse>

    @POST("ai/reward-ad")
    suspend fun rewardAd(): Response<AiLimitsResponse>
}
