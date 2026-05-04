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
import com.example.chatapp.network.dto.TelegramCompleteRegistrationRequest
import com.example.chatapp.network.dto.TelegramNativeLoginRequest
import com.example.chatapp.network.dto.TelegramVerifyCodeRequest
import com.example.chatapp.network.dto.TelegramWidgetLoginRequest
import com.example.chatapp.network.dto.VkNativeLoginRequest
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

enum class TelegramFlowMode {
    REGISTER,
    WIDGET
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
    val telegramCode: String = "",
    val telegramChallengeId: String? = null,
    val telegramBotUrl: String? = null,
    val telegramFlowMode: TelegramFlowMode = TelegramFlowMode.REGISTER,
    val isPasswordVisible: Boolean = false,
    val isLoading: Boolean = false,
    val isOpeningTelegram: Boolean = false,
    val isVerifyingTelegramCode: Boolean = false,
    val isTelegramCodeVerified: Boolean = false,
    val isVkLoginInProgress: Boolean = false,
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

    val isTelegramCodeValid: Boolean
        get() = telegramCode.length == 6

    val canContinueFromAboutYou: Boolean
        get() = isFullNameValid && isBirthDateValid

    val canVerifyTelegramCode: Boolean
        get() = !telegramChallengeId.isNullOrBlank() && isTelegramCodeValid && !isVerifyingTelegramCode

    val canSubmitPasswordStep: Boolean
        get() = when (telegramFlowMode) {
            TelegramFlowMode.REGISTER -> {
                isTelegramCodeVerified && isFullNameValid && isBirthDateValid && isPasswordValid
            }
            TelegramFlowMode.WIDGET -> false
        }
}

sealed interface AuthEvent {
    data class ShowMessage(val message: String) : AuthEvent
    data class Navigate(val route: String) : AuthEvent
    data class OpenTelegram(val url: String) : AuthEvent
    data class OpenTelegramNativeLogin(
        val clientId: String,
        val redirectUri: String,
        val scopes: List<String>
    ) : AuthEvent
    data class OpenVkLogin(
        val scopes: List<String>
    ) : AuthEvent
}

class AuthViewModel(
    private val repository: AuthRepositoryContract,
    private val accountSessionStore: AccountSessionStore,
    private val localize: (String) -> String = { it }
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

    fun beginVkLogin(
        isConfigured: Boolean,
        scopes: List<String>
    ) {
        if (!isConfigured) {
            setError(localize("auth_error_vk_login_not_configured"))
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    isVkLoginInProgress = true,
                    errorMessage = null,
                    infoMessage = localize("auth_info_opening_vk")
                )
            }
            _events.emit(AuthEvent.OpenVkLogin(scopes))
        }
    }

    fun beginTelegramWidgetLogin(
        clientId: String,
        redirectUri: String,
        scopes: List<String>
    ) {
        if (clientId.isBlank() || redirectUri.isBlank()) {
            setError(localize("auth_error_telegram_login_not_configured"))
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    telegramFlowMode = TelegramFlowMode.WIDGET,
                    isLoading = true,
                    errorMessage = null,
                    infoMessage = localize("auth_info_opening_telegram")
                )
            }
            _events.emit(
                AuthEvent.OpenTelegramNativeLogin(
                    clientId = clientId,
                    redirectUri = redirectUri,
                    scopes = scopes
                )
            )
        }
    }

    fun openTelegramBot() {
        val botUrl = _uiState.value.telegramBotUrl
        if (botUrl.isNullOrBlank()) {
            setError(localize("auth_error_bot_link_not_ready"))
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
            challengeId.isNullOrBlank() -> setError(localize("auth_error_restart_login"))
            !state.isTelegramCodeValid -> setError(localize("auth_error_code_required"))
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

                                TelegramFlowMode.WIDGET -> Unit
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
            TelegramFlowMode.WIDGET -> Unit
        }
    }

    fun completeTelegramWidgetLogin(request: TelegramWidgetLoginRequest) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    telegramFlowMode = TelegramFlowMode.WIDGET,
                    isLoading = true,
                    errorMessage = null,
                    infoMessage = localize("auth_info_verifying_telegram_data")
                )
            }

            when (val result = repository.completeTelegramWidgetLogin(request)) {
                is NetworkResult.Success -> handleAuthenticatedSuccess(result.data)
                is NetworkResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = result.message,
                            infoMessage = null
                        )
                    }
                }
            }
        }
    }

    fun completeTelegramNativeLogin(idToken: String) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    telegramFlowMode = TelegramFlowMode.WIDGET,
                    isLoading = true,
                    errorMessage = null,
                    infoMessage = localize("auth_info_verifying_telegram_token")
                )
            }

            when (
                val result = repository.completeTelegramNativeLogin(
                    TelegramNativeLoginRequest(idToken = idToken)
                )
            ) {
                is NetworkResult.Success -> handleAuthenticatedSuccess(result.data)
                is NetworkResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = result.message,
                            infoMessage = null
                        )
                    }
                }
            }
        }
    }

    fun completeVkNativeLogin(request: VkNativeLoginRequest) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    isVkLoginInProgress = true,
                    errorMessage = null,
                    infoMessage = localize("auth_info_verifying_vk_data")
                )
            }

            when (val result = repository.completeVkNativeLogin(request)) {
                is NetworkResult.Success -> handleAuthenticatedSuccess(result.data)
                is NetworkResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isVkLoginInProgress = false,
                            errorMessage = result.message,
                            infoMessage = null
                        )
                    }
                }
            }
        }
    }

    fun onTelegramWidgetError(message: String) {
        _uiState.update {
            it.copy(
                isLoading = false,
                errorMessage = message,
                infoMessage = null
            )
        }
    }

    fun onTelegramWidgetCanceled() {
        _uiState.update {
            it.copy(
                isLoading = false,
                errorMessage = null,
                infoMessage = localize("auth_info_login_canceled")
            )
        }
    }

    fun onVkLoginError(message: String) {
        _uiState.update {
            it.copy(
                isLoading = false,
                isVkLoginInProgress = false,
                errorMessage = message,
                infoMessage = null
            )
        }
    }

    fun onVkLoginCanceled() {
        _uiState.update {
            it.copy(
                isLoading = false,
                isVkLoginInProgress = false,
                errorMessage = null,
                infoMessage = localize("auth_info_vk_login_canceled")
            )
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
                    isVkLoginInProgress = false,
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
            challengeId.isNullOrBlank() -> setError(localize("auth_error_restart_registration"))
            !state.isTelegramCodeVerified -> setError(localize("auth_error_verify_code_first"))
            !state.isFullNameValid -> setError(localize("auth_error_full_name_required"))
            state.birthDate == null -> setError(localize("auth_error_birth_date_required"))
            !state.isPasswordValid -> setError(localize("auth_error_password_too_short"))
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
                        is NetworkResult.Success -> handleAuthenticatedSuccess(
                            response = result.data
                        )
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

    private suspend fun handleAuthenticatedSuccess(
        response: AuthResponse
    ) {
        persistAuthenticatedPayload(response)
        _uiState.update {
            it.copy(
                isLoading = false,
                isVkLoginInProgress = false,
                authToken = response.token,
                authenticatedUser = response.user,
                infoMessage = response.message,
                errorMessage = null
            )
        }
        _events.emit(AuthEvent.Navigate(AuthRoutes.SIGNED_IN))
        _events.emit(AuthEvent.ShowMessage(response.message))
    }

    private fun persistAuthenticatedPayload(
        response: AuthResponse
    ) {
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
        private val accountSessionStore: AccountSessionStore,
        private val localize: (String) -> String = { it }
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return AuthViewModel(repository, accountSessionStore, localize) as T
        }
    }
}
