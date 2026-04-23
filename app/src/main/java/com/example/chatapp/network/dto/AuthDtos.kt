package com.example.chatapp.network.dto

import com.google.gson.annotations.SerializedName

data class RegisterRequest(
    val email: String,
    val password: String,
    val fullName: String,
    val birthDate: String
)

data class LoginRequest(
    val email: String,
    val password: String
)

data class CheckEmailRequest(
    val email: String
)

data class VerifyEmailRequest(
    val email: String,
    val code: String
)

data class ResendCodeRequest(
    val email: String
)

data class ChangePasswordRequest(
    val currentPassword: String,
    val newPassword: String
)

data class TelegramAuthBeginResponse(
    val message: String,
    val challengeId: String,
    val botUrl: String,
    val expiresAt: String
)

data class TelegramVerifyCodeRequest(
    val challengeId: String,
    val code: String
)

data class TelegramVerifyCodeResponse(
    val message: String,
    val verified: Boolean,
    val purpose: String
)

data class TelegramCompleteRegistrationRequest(
    val challengeId: String,
    val fullName: String,
    val birthDate: String,
    val password: String
)

data class TelegramCompleteLoginRequest(
    val challengeId: String,
    val password: String
)

data class TelegramBeginMigrationRequest(
    val email: String,
    val password: String
)

data class TelegramCompleteMigrationRequest(
    val challengeId: String
)

data class TelegramWidgetLoginRequest(
    val id: String,
    @SerializedName("first_name")
    val firstName: String,
    @SerializedName("last_name")
    val lastName: String? = null,
    val username: String? = null,
    @SerializedName("photo_url")
    val photoUrl: String? = null,
    @SerializedName("auth_date")
    val authDate: Long,
    val hash: String
)

data class TelegramNativeLoginRequest(
    val idToken: String
)

data class ApiUser(
    val id: String,
    val email: String?,
    val fullName: String,
    val birthDate: String?,
    val isVerified: Boolean,
    val telegramId: String? = null,
    val telegramUsername: String? = null,
    val telegramFirstName: String? = null,
    val telegramLastName: String? = null,
    val telegramPhotoUrl: String? = null,
    val authProvider: String? = null,
    val planCode: String? = null,
    val subscriptionStatus: String? = null,
    val planExpiresAt: String? = null,
    val isPro: Boolean = false
)

data class AuthResponse(
    val message: String,
    val token: String? = null,
    val user: ApiUser? = null
)

data class BillingStatusResponse(
    val planCode: String,
    val subscriptionStatus: String,
    val planExpiresAt: String? = null,
    val currentPeriodEnd: String? = null,
    val cancelAtPeriodEnd: Boolean = false,
    val hasPaymentMethod: Boolean = false,
    val priceRub: Int = 100,
    val isPro: Boolean = false
)

data class BillingCheckoutResponse(
    val paymentId: String,
    val confirmationUrl: String?,
    val status: String
)

data class CheckEmailResponse(
    val message: String,
    val exists: Boolean,
    val isVerified: Boolean,
    val user: ApiUser? = null
)

data class ApiErrorResponse(
    val message: String,
    val errors: Map<String, String>? = null
)
