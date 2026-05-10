package com.example.chatapp.network

import com.example.chatapp.network.dto.AiLimitsResponse
import com.example.chatapp.network.dto.AiModelsResponse
import com.example.chatapp.network.dto.AiTrendingResponse
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface AiLimitsApiService {
    @GET("ai/limits")
    suspend fun getLimits(): Response<AiLimitsResponse>

    @GET("ai/trending")
    suspend fun getTrendingQueries(
        @Query("locale") locale: String = "ru"
    ): Response<AiTrendingResponse>

    @GET("ai/models")
    suspend fun getModels(): Response<AiModelsResponse>

    @POST("ai/reward-ad")
    suspend fun rewardAd(): Response<AiLimitsResponse>
}
