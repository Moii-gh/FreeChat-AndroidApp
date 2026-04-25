package com.example.chatapp.data

import com.example.chatapp.network.AuthApiService
import com.example.chatapp.network.dto.ApiErrorResponse
import com.example.chatapp.network.dto.AuthResponse
import com.example.chatapp.network.dto.BillingCheckoutResponse
import com.example.chatapp.network.dto.BillingStatusResponse
import com.example.chatapp.network.dto.ChangePasswordRequest
import com.example.chatapp.network.dto.TelegramAuthBeginResponse
import com.example.chatapp.network.dto.TelegramBeginMigrationRequest
import com.example.chatapp.network.dto.TelegramCompleteLoginRequest
import com.example.chatapp.network.dto.TelegramCompleteMigrationRequest
import com.example.chatapp.network.dto.TelegramCompleteRegistrationRequest
import com.example.chatapp.network.dto.TelegramVerifyCodeRequest
import com.example.chatapp.network.dto.TelegramVerifyCodeResponse
import com.example.chatapp.network.dto.TelegramNativeLoginRequest
import com.example.chatapp.network.dto.TelegramWidgetLoginRequest
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

    suspend fun completeTelegramWidgetLogin(
        request: TelegramWidgetLoginRequest
    ): NetworkResult<AuthResponse>

    suspend fun completeTelegramNativeLogin(
        request: TelegramNativeLoginRequest
    ): NetworkResult<AuthResponse>

    suspend fun changePassword(
        token: String,
        request: ChangePasswordRequest
    ): NetworkResult<AuthResponse>

    suspend fun getProfile(
        token: String
    ): NetworkResult<AuthResponse>

    suspend fun getBillingStatus(
        token: String
    ): NetworkResult<BillingStatusResponse>

    suspend fun startBillingCheckout(
        token: String
    ): NetworkResult<BillingCheckoutResponse>

    suspend fun cancelBillingSubscription(
        token: String
    ): NetworkResult<BillingStatusResponse>
}

class AuthRepository(
    private val service: AuthApiService,
    private val localize: (String, Array<out Any>) -> String = { key, _ -> key },
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

    override suspend fun completeTelegramWidgetLogin(
        request: TelegramWidgetLoginRequest
    ): NetworkResult<AuthResponse> = handle {
        service.completeTelegramWidgetLogin(request)
    }

    override suspend fun completeTelegramNativeLogin(
        request: TelegramNativeLoginRequest
    ): NetworkResult<AuthResponse> = handle {
        service.completeTelegramNativeLogin(request)
    }

    override suspend fun changePassword(
        token: String,
        request: ChangePasswordRequest
    ): NetworkResult<AuthResponse> = handle {
        service.changePassword("Bearer $token", request)
    }

    override suspend fun getProfile(
        token: String
    ): NetworkResult<AuthResponse> = handle {
        service.getProfile("Bearer $token")
    }

    override suspend fun getBillingStatus(
        token: String
    ): NetworkResult<BillingStatusResponse> = handle {
        service.getBillingStatus("Bearer $token")
    }

    override suspend fun startBillingCheckout(
        token: String
    ): NetworkResult<BillingCheckoutResponse> = handle {
        service.startBillingCheckout("Bearer $token")
    }

    override suspend fun cancelBillingSubscription(
        token: String
    ): NetworkResult<BillingStatusResponse> = handle {
        service.cancelBillingSubscription("Bearer $token")
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
                    NetworkResult.Error(message = localize("network_empty_response", emptyArray()))
                }
            } else {
                val errorBody = response.errorBody()?.string()
                val parsed = errorBody?.let {
                    runCatching { gson.fromJson(it, ApiErrorResponse::class.java) }.getOrNull()
                }
                NetworkResult.Error(
                    message = parsed?.message ?: localize("network_http_error", arrayOf(response.code())),
                    fieldErrors = parsed?.errors.orEmpty()
                )
            }
        } catch (_: IOException) {
            NetworkResult.Error(message = localize("network_connect_error", emptyArray()))
        } catch (error: Exception) {
            NetworkResult.Error(message = error.message ?: localize("network_unknown_error", emptyArray()))
        }
    }
}
