package com.example.chatapp.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.chatapp.data.AccountSessionStore
import com.example.chatapp.data.AuthRepositoryContract
import com.example.chatapp.data.NetworkResult
import com.example.chatapp.navigation.AuthRoutes
import com.example.chatapp.network.dto.ApiUser
import com.example.chatapp.network.dto.AuthResponse
import com.example.chatapp.network.dto.TelegramAuthBeginResponse
import com.example.chatapp.network.dto.TelegramBeginMigrationRequest
import com.example.chatapp.network.dto.TelegramCompleteLoginRequest
import com.example.chatapp.network.dto.TelegramCompleteMigrationRequest
import com.example.chatapp.network.dto.TelegramCompleteRegistrationRequest
import com.example.chatapp.network.dto.TelegramVerifyCodeRequest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

private val EMAIL_REGEX = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")

enum class TelegramFlowMode {
    REGISTER,
    LOGIN,
    MIGRATE
}

data class BirthDateDraft(
    val day: Int,
    val month: Int,
    val year: Int
) {
    companion object {
        fun default(): BirthDateDraft {
            val defaultDate = LocalDate.now().minusYears(18)
            return BirthDateDraft(
                day = defaultDate.dayOfMonth,
                month = defaultDate.monthValue,
                year = defaultDate.year
            )
        }
    }
}

data class AuthUiState(
    val fullName: String = "",
    val birthDate: LocalDate? = null,
    val birthDateDraft: BirthDateDraft = BirthDateDraft.default(),
    val password: String = "",
    val legacyEmail: String = "",
    val legacyPassword: String = "",
    val telegramCode: String = "",
    val telegramChallengeId: String? = null,
    val telegramBotUrl: String? = null,
    val telegramFlowMode: TelegramFlowMode = TelegramFlowMode.REGISTER,
    val isPasswordVisible: Boolean = false,
    val isLoading: Boolean = false,
    val isOpeningTelegram: Boolean = false,
    val isVerifyingTelegramCode: Boolean = false,
    val isTelegramCodeVerified: Boolean = false,
    val errorMessage: String? = null,
    val infoMessage: String? = null,
    val authToken: String? = null,
    val authenticatedUser: ApiUser? = null
) {
    val isFullNameValid: Boolean
        get() = fullName.trim().isNotEmpty()

    val isBirthDateValid: Boolean
        get() = birthDate != null

    val isPasswordValid: Boolean
        get() = password.length >= 6

    val isLegacyEmailValid: Boolean
        get() = EMAIL_REGEX.matches(legacyEmail.trim())

    val isLegacyPasswordValid: Boolean
        get() = legacyPassword.length >= 6

    val isTelegramCodeValid: Boolean
        get() = telegramCode.length == 6

    val canContinueFromAboutYou: Boolean
        get() = isFullNameValid && isBirthDateValid

    val canVerifyTelegramCode: Boolean
        get() = !telegramChallengeId.isNullOrBlank() && isTelegramCodeValid && !isVerifyingTelegramCode

    val canSubmitMigration: Boolean
        get() = isLegacyEmailValid && isLegacyPasswordValid && !isLoading

    val canSubmitPasswordStep: Boolean
        get() = when (telegramFlowMode) {
            TelegramFlowMode.REGISTER -> {
                isTelegramCodeVerified && isFullNameValid && isBirthDateValid && isPasswordValid
            }

            TelegramFlowMode.LOGIN -> isTelegramCodeVerified && isPasswordValid
            TelegramFlowMode.MIGRATE -> false
        }
}

sealed interface AuthEvent {
    data class ShowMessage(val message: String) : AuthEvent
    data class Navigate(val route: String) : AuthEvent
    data class OpenTelegram(val url: String) : AuthEvent
}

class AuthViewModel(
    private val repository: AuthRepositoryContract,
    private val accountSessionStore: AccountSessionStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<AuthEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<AuthEvent> = _events.asSharedFlow()

    fun onFullNameChanged(value: String) {
        _uiState.update {
            it.copy(
                fullName = value,
                errorMessage = null
            )
        }
    }

    fun onPasswordChanged(value: String) {
        _uiState.update {
            it.copy(
                password = value,
                errorMessage = null
            )
        }
    }

    fun onLegacyEmailChanged(value: String) {
        _uiState.update {
            it.copy(
                legacyEmail = value.trim(),
                errorMessage = null
            )
        }
    }

    fun onLegacyPasswordChanged(value: String) {
        _uiState.update {
            it.copy(
                legacyPassword = value,
                errorMessage = null
            )
        }
    }

    fun onTelegramCodeChanged(value: String) {
        _uiState.update {
            it.copy(
                telegramCode = value.filter(Char::isDigit).take(6),
                errorMessage = null
            )
        }
    }

    fun togglePasswordVisibility() {
        _uiState.update { it.copy(isPasswordVisible = !it.isPasswordVisible) }
    }

    fun prepareBirthDateDraft() {
        _uiState.update { current ->
            current.copy(
                birthDateDraft = current.birthDate?.let { date ->
                    BirthDateDraft(
                        day = date.dayOfMonth,
                        month = date.monthValue,
                        year = date.year
                    )
                } ?: current.birthDateDraft
            )
        }
    }

    fun updateBirthDateDraft(day: Int? = null, month: Int? = null, year: Int? = null) {
        _uiState.update { current ->
            val nextYear = year ?: current.birthDateDraft.year
            val nextMonth = month ?: current.birthDateDraft.month
            val maxDay = YearMonth.of(nextYear, nextMonth).lengthOfMonth()
            val requestedDay = day ?: current.birthDateDraft.day
            current.copy(
                birthDateDraft = BirthDateDraft(
                    day = requestedDay.coerceAtMost(maxDay),
                    month = nextMonth,
                    year = nextYear
                )
            )
        }
    }

    fun confirmBirthDateSelection() {
        val draft = _uiState.value.birthDateDraft
        val date = LocalDate.of(draft.year, draft.month, draft.day)
        _uiState.update {
            it.copy(
                birthDate = date,
                errorMessage = null
            )
        }
    }

    fun beginTelegramRegistration() {
        launchTelegramFlow(TelegramFlowMode.REGISTER) {
            repository.beginTelegramRegistration()
        }
    }

    fun beginTelegramLogin() {
        launchTelegramFlow(TelegramFlowMode.LOGIN) {
            repository.beginTelegramLogin()
        }
    }

    fun beginTelegramMigration() {
        val state = _uiState.value
        when {
            !state.isLegacyEmailValid -> setError("Введите корректный email")
            !state.isLegacyPasswordValid -> setError("Введите пароль не короче 6 символов")
            else -> launchTelegramFlow(TelegramFlowMode.MIGRATE) {
                repository.beginTelegramMigration(
                    TelegramBeginMigrationRequest(
                        email = state.legacyEmail.trim(),
                        password = state.legacyPassword
                    )
                )
            }
        }
    }

    fun openTelegramBot() {
        val botUrl = _uiState.value.telegramBotUrl
        if (botUrl.isNullOrBlank()) {
            setError("Ссылка на Telegram-бота ещё не готова")
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isOpeningTelegram = true) }
            _events.emit(AuthEvent.OpenTelegram(botUrl))
            _uiState.update { it.copy(isOpeningTelegram = false) }
        }
    }

    fun verifyTelegramCode() {
        val state = _uiState.value
        val challengeId = state.telegramChallengeId

        when {
            challengeId.isNullOrBlank() -> setError("Начните вход заново")
            !state.isTelegramCodeValid -> setError("Введите 6-значный код")
            else -> {
                viewModelScope.launch {
                    _uiState.update {
                        it.copy(
                            isVerifyingTelegramCode = true,
                            errorMessage = null,
                            infoMessage = null
                        )
                    }

                    when (
                        val result = repository.verifyTelegramCode(
                            TelegramVerifyCodeRequest(
                                challengeId = challengeId,
                                code = state.telegramCode
                            )
                        )
                    ) {
                        is NetworkResult.Success -> {
                            _uiState.update {
                                it.copy(
                                    isVerifyingTelegramCode = false,
                                    isTelegramCodeVerified = result.data.verified,
                                    infoMessage = result.data.message,
                                    errorMessage = null
                                )
                            }
                            _events.emit(AuthEvent.ShowMessage(result.data.message))

                            when (state.telegramFlowMode) {
                                TelegramFlowMode.REGISTER -> {
                                    _events.emit(AuthEvent.Navigate(AuthRoutes.ABOUT_YOU))
                                }

                                TelegramFlowMode.LOGIN -> {
                                    _events.emit(AuthEvent.Navigate(AuthRoutes.PASSWORD_STEP))
                                }

                                TelegramFlowMode.MIGRATE -> {
                                    completeVerifiedMigration(challengeId)
                                }
                            }
                        }

                        is NetworkResult.Error -> {
                            _uiState.update {
                                it.copy(
                                    isVerifyingTelegramCode = false,
                                    errorMessage = result.fieldErrors["code"] ?: result.message
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    fun submitPasswordStep() {
        when (_uiState.value.telegramFlowMode) {
            TelegramFlowMode.REGISTER -> completeTelegramRegistration()
            TelegramFlowMode.LOGIN -> completeTelegramLogin()
            TelegramFlowMode.MIGRATE -> Unit
        }
    }

    fun showInfoMessage(message: String) {
        _uiState.update {
            it.copy(
                infoMessage = message,
                errorMessage = null
            )
        }
        _events.tryEmit(AuthEvent.ShowMessage(message))
    }

    fun clearTransientMessage() {
        _uiState.update {
            it.copy(
                errorMessage = null,
                infoMessage = null
            )
        }
    }

    private fun launchTelegramFlow(
        mode: TelegramFlowMode,
        request: suspend () -> NetworkResult<TelegramAuthBeginResponse>
    ) {
        viewModelScope.launch {
            _uiState.update { current ->
                current.copy(
                    telegramFlowMode = mode,
                    telegramChallengeId = null,
                    telegramBotUrl = null,
                    telegramCode = "",
                    password = "",
                    isTelegramCodeVerified = false,
                    isLoading = true,
                    isOpeningTelegram = false,
                    isVerifyingTelegramCode = false,
                    errorMessage = null,
                    infoMessage = null,
                    fullName = if (mode == TelegramFlowMode.REGISTER) "" else current.fullName,
                    birthDate = if (mode == TelegramFlowMode.REGISTER) null else current.birthDate,
                    birthDateDraft = if (mode == TelegramFlowMode.REGISTER) {
                        BirthDateDraft.default()
                    } else {
                        current.birthDateDraft
                    }
                )
            }

            when (val result = request()) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            telegramChallengeId = result.data.challengeId,
                            telegramBotUrl = result.data.botUrl,
                            infoMessage = result.data.message,
                            errorMessage = null
                        )
                    }
                    _events.emit(AuthEvent.Navigate(AuthRoutes.TELEGRAM_CODE))
                    _events.emit(AuthEvent.ShowMessage(result.data.message))
                    openTelegramBot()
                }

                is NetworkResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = result.message
                        )
                    }
                }
            }
        }
    }

    private fun completeTelegramRegistration() {
        val state = _uiState.value
        val challengeId = state.telegramChallengeId

        when {
            challengeId.isNullOrBlank() -> setError("Начните регистрацию заново")
            !state.isTelegramCodeVerified -> setError("Сначала подтвердите код из Telegram")
            !state.isFullNameValid -> setError("Укажите полное имя")
            state.birthDate == null -> setError("Выберите дату рождения")
            !state.isPasswordValid -> setError("Пароль должен содержать минимум 6 символов")
            else -> {
                viewModelScope.launch {
                    _uiState.update {
                        it.copy(
                            isLoading = true,
                            errorMessage = null,
                            infoMessage = null
                        )
                    }

                    when (
                        val result = repository.completeTelegramRegistration(
                            TelegramCompleteRegistrationRequest(
                                challengeId = challengeId,
                                fullName = state.fullName.trim(),
                                birthDate = state.birthDate.toString(),
                                password = state.password
                            )
                        )
                    ) {
                        is NetworkResult.Success -> handleAuthenticatedSuccess(result.data)
                        is NetworkResult.Error -> {
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    errorMessage = result.fieldErrors["password"]
                                        ?: result.fieldErrors["fullName"]
                                        ?: result.message
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun completeTelegramLogin() {
        val state = _uiState.value
        val challengeId = state.telegramChallengeId

        when {
            challengeId.isNullOrBlank() -> setError("Начните вход заново")
            !state.isTelegramCodeVerified -> setError("Сначала подтвердите код из Telegram")
            !state.isPasswordValid -> setError("Введите пароль не короче 6 символов")
            else -> {
                viewModelScope.launch {
                    _uiState.update {
                        it.copy(
                            isLoading = true,
                            errorMessage = null,
                            infoMessage = null
                        )
                    }

                    when (
                        val result = repository.completeTelegramLogin(
                            TelegramCompleteLoginRequest(
                                challengeId = challengeId,
                                password = state.password
                            )
                        )
                    ) {
                        is NetworkResult.Success -> handleAuthenticatedSuccess(result.data)
                        is NetworkResult.Error -> {
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    errorMessage = result.fieldErrors["password"] ?: result.message
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun completeVerifiedMigration(challengeId: String) {
        _uiState.update {
            it.copy(
                isLoading = true,
                errorMessage = null,
                infoMessage = null
            )
        }

        when (
            val result = repository.completeTelegramMigration(
                TelegramCompleteMigrationRequest(challengeId = challengeId)
            )
        ) {
            is NetworkResult.Success -> handleAuthenticatedSuccess(result.data)
            is NetworkResult.Error -> {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = result.message
                    )
                }
            }
        }
    }

    private suspend fun handleAuthenticatedSuccess(response: AuthResponse) {
        persistAuthenticatedPayload(response)
        _uiState.update {
            it.copy(
                isLoading = false,
                authToken = response.token,
                authenticatedUser = response.user,
                infoMessage = response.message,
                errorMessage = null
            )
        }
        _events.emit(AuthEvent.Navigate(AuthRoutes.SIGNED_IN))
        _events.emit(AuthEvent.ShowMessage(response.message))
    }

    private fun persistAuthenticatedPayload(response: AuthResponse) {
        accountSessionStore.saveAuthenticatedUser(response.user, response.token)
    }

    private fun setError(message: String) {
        _uiState.update {
            it.copy(
                errorMessage = message,
                infoMessage = null
            )
        }
        _events.tryEmit(AuthEvent.ShowMessage(message))
    }

    class Factory(
        private val repository: AuthRepositoryContract,
        private val accountSessionStore: AccountSessionStore
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return AuthViewModel(repository, accountSessionStore) as T
        }
    }
}
