package com.example.chatapp.network

import com.example.chatapp.network.dto.SyncPayload
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface SyncApiService {
    @POST("sync")
    suspend fun syncData(@Body request: SyncPayload): Response<SyncPayload>
}
