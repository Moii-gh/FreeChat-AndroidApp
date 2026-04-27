package com.example.chatapp

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.example.chatapp.data.AccountSessionStore
import com.example.chatapp.data.AuthRepositoryContract
import com.example.chatapp.data.NetworkResult
import com.example.chatapp.network.dto.ApiUser
import com.example.chatapp.network.dto.AuthResponse
import com.example.chatapp.network.dto.ChangePasswordRequest
import com.example.chatapp.network.dto.TelegramAuthBeginResponse
import com.example.chatapp.network.dto.TelegramBeginMigrationRequest
import com.example.chatapp.network.dto.TelegramCompleteLoginRequest
import com.example.chatapp.network.dto.TelegramCompleteMigrationRequest
import com.example.chatapp.network.dto.TelegramCompleteRegistrationRequest
import com.example.chatapp.network.dto.TelegramNativeLoginRequest
import com.example.chatapp.network.dto.TelegramVerifyCodeRequest
import com.example.chatapp.network.dto.TelegramVerifyCodeResponse
import com.example.chatapp.network.dto.TelegramWidgetLoginRequest
import com.example.chatapp.ui.auth.components.AuthTestTags
import com.example.chatapp.ui.auth.screens.AboutYouScreen
import com.example.chatapp.ui.auth.screens.BirthDatePickerScreen
import com.example.chatapp.ui.auth.screens.TelegramCodeScreen
import com.example.chatapp.ui.auth.theme.ChatAppTheme
import com.example.chatapp.viewmodel.AuthUiState
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class AuthFlowComposeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun requireEmulatorComposeHost() {
        assumeTrue("Compose UI tests run on emulator hosts", isRunningOnEmulator())
    }

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

        composeRule.waitForIdle()
        waitForTag(AuthTestTags.CONTINUE_BUTTON)
        waitForTag(AuthTestTags.OPEN_TELEGRAM_BUTTON)
        composeRule.onNodeWithTag(AuthTestTags.CONTINUE_BUTTON).assertIsNotEnabled()
        composeRule.onNodeWithTag(AuthTestTags.OPEN_TELEGRAM_BUTTON).assertIsDisplayed()
    }

    @Test
    fun clickingBirthDateFieldOpensPickerScreen() {
        composeRule.setContent {
            ChatAppTheme {
                var showPicker by remember { mutableStateOf(false) }
                if (showPicker) {
                    BirthDatePickerScreen(
                        state = AuthUiState(),
                        onBack = { showPicker = false },
                        onDaySelected = {},
                        onMonthSelected = {},
                        onYearSelected = {},
                        onConfirm = { showPicker = false }
                    )
                } else {
                    AboutYouScreen(
                        state = AuthUiState(),
                        onFullNameChanged = {},
                        onBirthDateClick = { showPicker = true },
                        onContinue = {},
                        onBack = {}
                    )
                }
            }
        }

        waitForTag(AuthTestTags.DATE_FIELD)
        composeRule.onNodeWithTag(AuthTestTags.DATE_FIELD).performClick()
        waitForTag(AuthTestTags.BIRTH_DATE_PICKER)
        composeRule.onNodeWithTag(AuthTestTags.BIRTH_DATE_PICKER).assertIsDisplayed()
    }

    private fun waitForTag(tag: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
    }

    private fun isRunningOnEmulator(): Boolean {
        val fingerprint = Build.FINGERPRINT.lowercase()
        val hardware = Build.HARDWARE.lowercase()
        val product = Build.PRODUCT.lowercase()
        return fingerprint.contains("generic") ||
            fingerprint.contains("emulator") ||
            hardware.contains("goldfish") ||
            hardware.contains("ranchu") ||
            product.contains("sdk") ||
            product.contains("emulator")
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

    override suspend fun completeTelegramWidgetLogin(
        request: TelegramWidgetLoginRequest
    ): NetworkResult<AuthResponse> {
        return NetworkResult.Success(
            AuthResponse(
                message = "Вход через Telegram выполнен",
                token = "jwt",
                user = ApiUser(
                    id = "user-widget",
                    email = null,
                    fullName = request.firstName,
                    birthDate = null,
                    isVerified = true,
                    telegramId = request.id,
                    telegramUsername = request.username,
                    authProvider = "telegram"
                )
            )
        )
    }

    override suspend fun completeTelegramNativeLogin(
        request: TelegramNativeLoginRequest
    ): NetworkResult<AuthResponse> {
        return NetworkResult.Success(
            AuthResponse(
                message = "Вход через Telegram выполнен",
                token = "jwt",
                user = ApiUser(
                    id = "user-native",
                    email = null,
                    fullName = "Ada",
                    birthDate = null,
                    isVerified = true,
                    telegramId = "424242",
                    telegramUsername = "ada",
                    authProvider = "telegram"
                )
            )
        )
    }

    override suspend fun changePassword(
        token: String,
        request: ChangePasswordRequest
    ): NetworkResult<AuthResponse> {
        return NetworkResult.Success(AuthResponse(message = "ok"))
    }

    override suspend fun getProfile(token: String): NetworkResult<AuthResponse> {
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

    override fun getDailyRequestLimit(): Int? = null

    override fun getRemainingDailyRequests(): Int? = null

    override fun getDailyQuotaResetsAt(): String? = null

    override fun saveRemainingDailyRequests(value: Int?) = Unit

    override fun consumeDailyRequest() = Unit

    override fun addDailyRequests(amount: Int) = Unit
}
