package com.example.chatapp

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.example.chatapp.data.AccountSessionStore
import com.example.chatapp.data.AuthRepositoryContract
import com.example.chatapp.data.NetworkResult
import com.example.chatapp.navigation.AuthNavGraph
import com.example.chatapp.network.dto.ApiUser
import com.example.chatapp.network.dto.AuthResponse
import com.example.chatapp.network.dto.ChangePasswordRequest
import com.example.chatapp.network.dto.TelegramAuthBeginResponse
import com.example.chatapp.network.dto.TelegramBeginMigrationRequest
import com.example.chatapp.network.dto.TelegramCompleteLoginRequest
import com.example.chatapp.network.dto.TelegramCompleteMigrationRequest
import com.example.chatapp.network.dto.TelegramCompleteRegistrationRequest
import com.example.chatapp.network.dto.TelegramVerifyCodeRequest
import com.example.chatapp.network.dto.TelegramVerifyCodeResponse
import com.example.chatapp.ui.auth.components.AuthTestTags
import com.example.chatapp.ui.auth.screens.LegacyMigrationScreen
import com.example.chatapp.ui.auth.screens.TelegramCodeScreen
import com.example.chatapp.ui.auth.theme.ChatAppTheme
import com.example.chatapp.viewmodel.AuthUiState
import com.example.chatapp.viewmodel.AuthViewModel
import org.junit.Rule
import org.junit.Test

class AuthFlowComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun telegramCodeContinueButtonIsDisabledWithoutCode() {
        composeRule.setContent {
            ChatAppTheme {
                TelegramCodeScreen(
                    state = AuthUiState(
                        telegramChallengeId = "challenge-1",
                        telegramBotUrl = "tg://resolve?domain=sample_app_bot"
                    ),
                    onCodeChanged = {},
                    onOpenTelegram = {},
                    onContinue = {},
                    onBack = {}
                )
            }
        }

        composeRule.onNodeWithTag(AuthTestTags.CONTINUE_BUTTON).assertIsNotEnabled()
        composeRule.onNodeWithTag(AuthTestTags.OPEN_TELEGRAM_BUTTON).assertIsDisplayed()
    }

    @Test
    fun legacyMigrationButtonIsDisabledForInvalidCredentials() {
        composeRule.setContent {
            ChatAppTheme {
                LegacyMigrationScreen(
                    state = AuthUiState(),
                    onEmailChanged = {},
                    onPasswordChanged = {},
                    onTogglePasswordVisibility = {},
                    onContinue = {},
                    onBack = {}
                )
            }
        }

        composeRule.onNodeWithTag(AuthTestTags.CONTINUE_BUTTON).assertIsNotEnabled()
    }

    @Test
    fun clickingBirthDateFieldOpensPickerScreen() {
        val viewModel = AuthViewModel(FakeComposeAuthRepository(), FakeComposeAccountSessionStore())

        composeRule.setContent {
            ChatAppTheme {
                AuthNavGraph(viewModel = viewModel)
            }
        }

        composeRule.runOnIdle {
            viewModel.beginTelegramRegistration()
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag(AuthTestTags.TELEGRAM_CODE_FIELD).performTextInput("123456")
        composeRule.onNodeWithTag(AuthTestTags.CONTINUE_BUTTON).performClick()
        composeRule.onNodeWithTag(AuthTestTags.DATE_FIELD).performClick()
        composeRule.onNodeWithTag(AuthTestTags.BIRTH_DATE_PICKER).assertIsDisplayed()
    }
}

private class FakeComposeAuthRepository : AuthRepositoryContract {
    override suspend fun beginTelegramRegistration(): NetworkResult<TelegramAuthBeginResponse> {
        return NetworkResult.Success(
            TelegramAuthBeginResponse(
                message = "Откройте Telegram и отправьте команду /start",
                challengeId = "challenge-1",
                botUrl = "invalid://telegram",
                expiresAt = "2026-04-16T12:00:00Z"
            )
        )
    }

    override suspend fun beginTelegramLogin(): NetworkResult<TelegramAuthBeginResponse> {
        return NetworkResult.Success(
            TelegramAuthBeginResponse(
                message = "Откройте Telegram и отправьте команду /start",
                challengeId = "challenge-login",
                botUrl = "invalid://telegram",
                expiresAt = "2026-04-16T12:00:00Z"
            )
        )
    }

    override suspend fun verifyTelegramCode(
        request: TelegramVerifyCodeRequest
    ): NetworkResult<TelegramVerifyCodeResponse> {
        return NetworkResult.Success(
            TelegramVerifyCodeResponse(
                message = "Код подтверждён",
                verified = true,
                purpose = "register"
            )
        )
    }

    override suspend fun completeTelegramRegistration(
        request: TelegramCompleteRegistrationRequest
    ): NetworkResult<AuthResponse> {
        return NetworkResult.Success(
            AuthResponse(
                message = "Аккаунт создан",
                token = "jwt",
                user = ApiUser(
                    id = "user-1",
                    email = null,
                    fullName = request.fullName,
                    birthDate = request.birthDate,
                    isVerified = true,
                    telegramUsername = "ada",
                    authProvider = "telegram"
                )
            )
        )
    }

    override suspend fun completeTelegramLogin(
        request: TelegramCompleteLoginRequest
    ): NetworkResult<AuthResponse> {
        return NetworkResult.Success(
            AuthResponse(
                message = "Вход выполнен",
                token = "jwt",
                user = ApiUser(
                    id = "user-1",
                    email = null,
                    fullName = "Ada",
                    birthDate = "1995-12-09",
                    isVerified = true
                )
            )
        )
    }

    override suspend fun beginTelegramMigration(
        request: TelegramBeginMigrationRequest
    ): NetworkResult<TelegramAuthBeginResponse> {
        return NetworkResult.Success(
            TelegramAuthBeginResponse(
                message = "Откройте Telegram и отправьте команду /start",
                challengeId = "challenge-migrate",
                botUrl = "invalid://telegram",
                expiresAt = "2026-04-16T12:00:00Z"
            )
        )
    }

    override suspend fun completeTelegramMigration(
        request: TelegramCompleteMigrationRequest
    ): NetworkResult<AuthResponse> {
        return NetworkResult.Success(AuthResponse(message = "Аккаунт переведён", token = "jwt"))
    }

    override suspend fun changePassword(
        token: String,
        request: ChangePasswordRequest
    ): NetworkResult<AuthResponse> {
        return NetworkResult.Success(AuthResponse(message = "ok"))
    }
}

private class FakeComposeAccountSessionStore : AccountSessionStore {
    override fun saveAuthenticatedUser(user: ApiUser?, token: String?) = Unit

    override fun isSignedIn(): Boolean = false

    override fun clearSession() = Unit

    override fun getAuthToken(): String? = null

    override fun getCurrentUserId(): String? = null

    override fun getCurrentUserEmail(): String? = null

    override fun getCurrentUserName(): String? = null
}
