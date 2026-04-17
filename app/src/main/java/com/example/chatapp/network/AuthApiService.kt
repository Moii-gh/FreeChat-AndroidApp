package com.example.chatapp.network

import com.example.chatapp.network.dto.AuthResponse
import com.example.chatapp.network.dto.ChangePasswordRequest
import com.example.chatapp.network.dto.TelegramAuthBeginResponse
import com.example.chatapp.network.dto.TelegramBeginMigrationRequest
import com.example.chatapp.network.dto.TelegramCompleteLoginRequest
import com.example.chatapp.network.dto.TelegramCompleteMigrationRequest
import com.example.chatapp.network.dto.TelegramCompleteRegistrationRequest
import com.example.chatapp.network.dto.TelegramVerifyCodeRequest
import com.example.chatapp.network.dto.TelegramVerifyCodeResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface AuthApiService {
    @POST("telegram-auth/begin-registration")
    suspend fun beginTelegramRegistration(): Response<TelegramAuthBeginResponse>

    @POST("telegram-auth/begin-login")
    suspend fun beginTelegramLogin(): Response<TelegramAuthBeginResponse>

    @POST("telegram-auth/verify-code")
    suspend fun verifyTelegramCode(
        @Body request: TelegramVerifyCodeRequest
    ): Response<TelegramVerifyCodeResponse>

    @POST("telegram-auth/complete-registration")
    suspend fun completeTelegramRegistration(
        @Body request: TelegramCompleteRegistrationRequest
    ): Response<AuthResponse>

    @POST("telegram-auth/complete-login")
    suspend fun completeTelegramLogin(
        @Body request: TelegramCompleteLoginRequest
    ): Response<AuthResponse>

    @POST("telegram-auth/begin-migration")
    suspend fun beginTelegramMigration(
        @Body request: TelegramBeginMigrationRequest
    ): Response<TelegramAuthBeginResponse>

    @POST("telegram-auth/complete-migration")
    suspend fun completeTelegramMigration(
        @Body request: TelegramCompleteMigrationRequest
    ): Response<AuthResponse>

    @POST("change-password")
    suspend fun changePassword(
        @Header("Authorization") authorization: String,
        @Body request: ChangePasswordRequest
    ): Response<AuthResponse>
}
