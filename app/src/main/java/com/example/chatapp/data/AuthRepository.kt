package com.example.chatapp.data

import com.example.chatapp.network.AuthApiService
import com.example.chatapp.network.dto.ApiErrorResponse
import com.example.chatapp.network.dto.AuthResponse
import com.example.chatapp.network.dto.ChangePasswordRequest
import com.example.chatapp.network.dto.TelegramAuthBeginResponse
import com.example.chatapp.network.dto.TelegramBeginMigrationRequest
import com.example.chatapp.network.dto.TelegramCompleteLoginRequest
import com.example.chatapp.network.dto.TelegramCompleteMigrationRequest
import com.example.chatapp.network.dto.TelegramCompleteRegistrationRequest
import com.example.chatapp.network.dto.TelegramVerifyCodeRequest
import com.example.chatapp.network.dto.TelegramVerifyCodeResponse
import com.google.gson.Gson
import retrofit2.Response
import java.io.IOException

interface AuthRepositoryContract {
    suspend fun beginTelegramRegistration(): NetworkResult<TelegramAuthBeginResponse>
    suspend fun beginTelegramLogin(): NetworkResult<TelegramAuthBeginResponse>
    suspend fun verifyTelegramCode(
        request: TelegramVerifyCodeRequest
    ): NetworkResult<TelegramVerifyCodeResponse>

    suspend fun completeTelegramRegistration(
        request: TelegramCompleteRegistrationRequest
    ): NetworkResult<AuthResponse>

    suspend fun completeTelegramLogin(
        request: TelegramCompleteLoginRequest
    ): NetworkResult<AuthResponse>

    suspend fun beginTelegramMigration(
        request: TelegramBeginMigrationRequest
    ): NetworkResult<TelegramAuthBeginResponse>

    suspend fun completeTelegramMigration(
        request: TelegramCompleteMigrationRequest
    ): NetworkResult<AuthResponse>

    suspend fun changePassword(
        token: String,
        request: ChangePasswordRequest
    ): NetworkResult<AuthResponse>
}

class AuthRepository(
    private val service: AuthApiService,
    private val gson: Gson = Gson()
) : AuthRepositoryContract {

    override suspend fun beginTelegramRegistration(): NetworkResult<TelegramAuthBeginResponse> =
        handle { service.beginTelegramRegistration() }

    override suspend fun beginTelegramLogin(): NetworkResult<TelegramAuthBeginResponse> =
        handle { service.beginTelegramLogin() }

    override suspend fun verifyTelegramCode(
        request: TelegramVerifyCodeRequest
    ): NetworkResult<TelegramVerifyCodeResponse> = handle {
        service.verifyTelegramCode(request)
    }

    override suspend fun completeTelegramRegistration(
        request: TelegramCompleteRegistrationRequest
    ): NetworkResult<AuthResponse> = handle {
        service.completeTelegramRegistration(request)
    }

    override suspend fun completeTelegramLogin(
        request: TelegramCompleteLoginRequest
    ): NetworkResult<AuthResponse> = handle {
        service.completeTelegramLogin(request)
    }

    override suspend fun beginTelegramMigration(
        request: TelegramBeginMigrationRequest
    ): NetworkResult<TelegramAuthBeginResponse> = handle {
        service.beginTelegramMigration(request)
    }

    override suspend fun completeTelegramMigration(
        request: TelegramCompleteMigrationRequest
    ): NetworkResult<AuthResponse> = handle {
        service.completeTelegramMigration(request)
    }

    override suspend fun changePassword(
        token: String,
        request: ChangePasswordRequest
    ): NetworkResult<AuthResponse> = handle {
        service.changePassword("Bearer $token", request)
    }

    private suspend fun <T> handle(
        call: suspend () -> Response<T>
    ): NetworkResult<T> {
        return try {
            val response = call()
            if (response.isSuccessful) {
                val body = response.body()
                if (body != null) {
                    NetworkResult.Success(body)
                } else {
                    NetworkResult.Error(message = "Пустой ответ сервера")
                }
            } else {
                val errorBody = response.errorBody()?.string()
                val parsed = errorBody?.let {
                    runCatching { gson.fromJson(it, ApiErrorResponse::class.java) }.getOrNull()
                }
                NetworkResult.Error(
                    message = parsed?.message ?: "Ошибка ${response.code()}",
                    fieldErrors = parsed?.errors.orEmpty()
                )
            }
        } catch (_: IOException) {
            NetworkResult.Error(message = "Не удалось подключиться к серверу")
        } catch (error: Exception) {
            NetworkResult.Error(message = error.message ?: "Неизвестная ошибка")
        }
    }
}
