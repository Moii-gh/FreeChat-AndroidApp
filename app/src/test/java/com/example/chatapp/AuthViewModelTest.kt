package com.example.chatapp

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
import com.example.chatapp.viewmodel.AuthViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

    private val dispatcher: TestDispatcher = StandardTestDispatcher()
    private lateinit var repository: FakeAuthRepository
    private lateinit var accountStore: FakeAccountSessionStore
    private lateinit var viewModel: AuthViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = FakeAuthRepository()
        accountStore = FakeAccountSessionStore()
        viewModel = AuthViewModel(repository, accountStore)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `telegram code accepts only first six digits`() {
        viewModel.onTelegramCodeChanged("12a34567")

        assertEquals("123456", viewModel.uiState.value.telegramCode)
        assertTrue(viewModel.uiState.value.isTelegramCodeValid)
    }

    @Test
    fun `password minimum length is enforced`() {
        viewModel.onPasswordChanged("12345")
        assertFalse(viewModel.uiState.value.isPasswordValid)

        viewModel.onPasswordChanged("123456")
        assertTrue(viewModel.uiState.value.isPasswordValid)
    }

    @Test
    fun `full name and birth date are required for about you step`() {
        viewModel.onFullNameChanged("   ")
        assertFalse(viewModel.uiState.value.canContinueFromAboutYou)

        viewModel.onFullNameChanged("Ada Lovelace")
        viewModel.updateBirthDateDraft(day = 9, month = 12, year = 1995)
        viewModel.confirmBirthDateSelection()

        assertTrue(viewModel.uiState.value.canContinueFromAboutYou)
    }

    @Test
    fun `confirming birth date moves draft into selected date`() {
        viewModel.updateBirthDateDraft(day = 29, month = 2, year = 2024)
        viewModel.confirmBirthDateSelection()

        assertEquals(LocalDate.of(2024, 2, 29), viewModel.uiState.value.birthDate)
    }

    @Test
    fun `telegram registration flow authenticates user after code and password`() = runTest(dispatcher) {
        viewModel.beginTelegramRegistration()
        runCurrent()
        assertTrue(viewModel.uiState.value.isLoading)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals("challenge-register", viewModel.uiState.value.telegramChallengeId)
        assertEquals("Open Telegram and send /start", viewModel.uiState.value.infoMessage)

        viewModel.onTelegramCodeChanged("123456")
        viewModel.verifyTelegramCode()
        runCurrent()
        assertTrue(viewModel.uiState.value.isVerifyingTelegramCode)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isTelegramCodeVerified)

        viewModel.onFullNameChanged("Ada Lovelace")
        viewModel.updateBirthDateDraft(day = 9, month = 12, year = 1995)
        viewModel.confirmBirthDateSelection()
        viewModel.onPasswordChanged("123456")

        viewModel.submitPasswordStep()
        runCurrent()
        assertTrue(viewModel.uiState.value.isLoading)
        advanceUntilIdle()

        assertEquals("jwt-token", viewModel.uiState.value.authToken)
        assertEquals("Ada Lovelace", accountStore.lastUser?.fullName)
        assertEquals("123456", accountStore.savedPassword)
        assertNotNull(repository.lastCompleteRegistrationRequest)
    }

    @Test
    fun `telegram widget login authenticates without code or password`() = runTest(dispatcher) {
        val request = TelegramWidgetLoginRequest(
            id = "424242",
            firstName = "Ada",
            lastName = "Lovelace",
            username = "ada",
            photoUrl = "https://t.me/i/userpic/320/ada.jpg",
            authDate = 1776686400,
            hash = "a".repeat(64)
        )

        viewModel.completeTelegramWidgetLogin(request)
        runCurrent()
        assertTrue(viewModel.uiState.value.isLoading)
        advanceUntilIdle()

        assertEquals("jwt-token", viewModel.uiState.value.authToken)
        assertEquals("Ada Lovelace", accountStore.lastUser?.fullName)
        assertEquals("424242", accountStore.lastUser?.telegramId)
        assertNotNull(repository.lastWidgetLoginRequest)
    }

    @Test
    fun `telegram native login authenticates with id token`() = runTest(dispatcher) {
        viewModel.completeTelegramNativeLogin("telegram-id-token")
        runCurrent()
        assertTrue(viewModel.uiState.value.isLoading)
        advanceUntilIdle()

        assertEquals("jwt-token", viewModel.uiState.value.authToken)
        assertEquals("424242", accountStore.lastUser?.telegramId)
        assertNotNull(repository.lastNativeLoginRequest)
    }
}

private class FakeAuthRepository : AuthRepositoryContract {
    var beginRegistrationResult: NetworkResult<TelegramAuthBeginResponse> = NetworkResult.Success(
        TelegramAuthBeginResponse(
            message = "Open Telegram and send /start",
            challengeId = "challenge-register",
            botUrl = "https://t.me/sample_app_bot?start=register",
            expiresAt = "2026-04-16T12:00:00Z"
        )
    )
    var beginLoginResult: NetworkResult<TelegramAuthBeginResponse> = NetworkResult.Success(
        TelegramAuthBeginResponse(
            message = "Open Telegram and send /start",
            challengeId = "challenge-login",
            botUrl = "https://t.me/sample_app_bot?start=login",
            expiresAt = "2026-04-16T12:00:00Z"
        )
    )
    var beginMigrationResult: NetworkResult<TelegramAuthBeginResponse> = NetworkResult.Success(
        TelegramAuthBeginResponse(
            message = "Open Telegram and send /start",
            challengeId = "challenge-migrate",
            botUrl = "https://t.me/sample_app_bot?start=migrate",
            expiresAt = "2026-04-16T12:00:00Z"
        )
    )
    var verifyCodeResult: NetworkResult<TelegramVerifyCodeResponse> = NetworkResult.Success(
        TelegramVerifyCodeResponse(
            message = "Code verified",
            verified = true,
            purpose = "register"
        )
    )
    var lastCompleteRegistrationRequest: TelegramCompleteRegistrationRequest? = null
    var lastCompleteMigrationRequest: TelegramCompleteMigrationRequest? = null
    var lastWidgetLoginRequest: TelegramWidgetLoginRequest? = null
    var lastNativeLoginRequest: TelegramNativeLoginRequest? = null

    override suspend fun beginTelegramRegistration(): NetworkResult<TelegramAuthBeginResponse> {
        delay(25)
        return beginRegistrationResult
    }

    override suspend fun beginTelegramLogin(): NetworkResult<TelegramAuthBeginResponse> {
        delay(25)
        return beginLoginResult
    }

    override suspend fun verifyTelegramCode(
        request: TelegramVerifyCodeRequest
    ): NetworkResult<TelegramVerifyCodeResponse> {
        delay(25)
        return verifyCodeResult
    }

    override suspend fun completeTelegramRegistration(
        request: TelegramCompleteRegistrationRequest
    ): NetworkResult<AuthResponse> {
        lastCompleteRegistrationRequest = request
        delay(25)
        return NetworkResult.Success(
            AuthResponse(
                message = "Account created",
                token = "jwt-token",
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
        delay(25)
        return NetworkResult.Success(
            AuthResponse(
                message = "Logged in",
                token = "jwt-token",
                user = ApiUser(
                    id = "user-1",
                    email = null,
                    fullName = "Ada Lovelace",
                    birthDate = "1995-12-09",
                    isVerified = true,
                    telegramUsername = "ada",
                    authProvider = "telegram"
                )
            )
        )
    }

    override suspend fun beginTelegramMigration(
        request: TelegramBeginMigrationRequest
    ): NetworkResult<TelegramAuthBeginResponse> {
        delay(25)
        return beginMigrationResult
    }

    override suspend fun completeTelegramMigration(
        request: TelegramCompleteMigrationRequest
    ): NetworkResult<AuthResponse> {
        lastCompleteMigrationRequest = request
        delay(25)
        return NetworkResult.Success(
            AuthResponse(
                message = "Account migrated",
                token = "jwt-token",
                user = ApiUser(
                    id = "user-legacy",
                    email = "legacy@example.com",
                    fullName = "Legacy User",
                    birthDate = "1990-01-01",
                    isVerified = true,
                    telegramUsername = "legacy_user",
                    authProvider = "telegram"
                )
            )
        )
    }

    override suspend fun completeTelegramWidgetLogin(
        request: TelegramWidgetLoginRequest
    ): NetworkResult<AuthResponse> {
        lastWidgetLoginRequest = request
        delay(25)
        return NetworkResult.Success(
            AuthResponse(
                message = "Telegram widget login complete",
                token = "jwt-token",
                user = ApiUser(
                    id = "user-widget",
                    email = null,
                    fullName = "${request.firstName} ${request.lastName}".trim(),
                    birthDate = null,
                    isVerified = true,
                    telegramId = request.id,
                    telegramUsername = request.username,
                    telegramFirstName = request.firstName,
                    telegramLastName = request.lastName,
                    telegramPhotoUrl = request.photoUrl,
                    authProvider = "telegram"
                )
            )
        )
    }

    override suspend fun completeTelegramNativeLogin(
        request: TelegramNativeLoginRequest
    ): NetworkResult<AuthResponse> {
        lastNativeLoginRequest = request
        delay(25)
        return NetworkResult.Success(
            AuthResponse(
                message = "Telegram native login complete",
                token = "jwt-token",
                user = ApiUser(
                    id = "user-native",
                    email = null,
                    fullName = "Ada Lovelace",
                    birthDate = null,
                    isVerified = true,
                    telegramId = "424242",
                    telegramUsername = "ada",
                    telegramFirstName = "Ada Lovelace",
                    telegramPhotoUrl = "https://cdn.telegram.test/ada.jpg",
                    authProvider = "telegram"
                )
            )
        )
    }

    override suspend fun changePassword(
        token: String,
        request: ChangePasswordRequest
    ): NetworkResult<AuthResponse> {
        delay(25)
        return NetworkResult.Success(AuthResponse(message = "Password updated"))
    }

    override suspend fun getProfile(token: String): NetworkResult<AuthResponse> {
        delay(25)
        return NetworkResult.Success(
            AuthResponse(
                message = "Profile loaded",
                user = ApiUser(
                    id = "user-1",
                    email = "ada@example.com",
                    fullName = "Ada Lovelace",
                    birthDate = "1995-12-09",
                    isVerified = true
                )
            )
        )
    }


}

private class FakeAccountSessionStore : AccountSessionStore {
    var lastUser: ApiUser? = null
    var lastToken: String? = null
    var savedPassword: String? = null

    override fun saveAuthenticatedUser(user: ApiUser?, token: String?) {
        lastUser = user
        lastToken = token
    }

    override fun isSignedIn(): Boolean = !lastToken.isNullOrBlank()

    override fun clearSession() = Unit

    override fun getAuthToken(): String? = lastToken

    override fun getCurrentUserId(): String? = lastUser?.id

    override fun getCurrentUserEmail(): String? = lastUser?.email

    override fun getCurrentUserName(): String? = lastUser?.fullName

    override fun getCurrentUserPassword(): String? = savedPassword

    override fun saveRegistrationPassword(password: String) {
        savedPassword = password
    }

    override fun getDailyRequestLimit(): Int? = null

    override fun getBaseRemainingDailyRequests(): Int? = null

    override fun getBonusRequests(): Int = 0

    override fun getRemainingDailyRequests(): Int? = null

    override fun getDailyQuotaResetsAt(): String? = null

    override fun saveDailyQuota(
        dailyLimit: Int?,
        baseRemaining: Int?,
        bonusRequests: Int?,
        resetAt: String?
    ) = Unit

    override fun consumeDailyRequest() = Unit

    override fun addDailyRequests(amount: Int) = Unit
}
