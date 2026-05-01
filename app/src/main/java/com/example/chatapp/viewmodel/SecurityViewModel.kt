package com.example.chatapp.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.chatapp.data.AccountScopedSettings
import com.example.chatapp.data.SharedPrefsAccountSessionStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private const val KEY_BIOMETRIC_ENABLED = "security_biometric_enabled"

enum class SecurityFaqItem {
    DATA_PROTECTION,
    SAFE_PASSWORD,
    DATA_STORAGE,
    TELEGRAM_LOGIN
}

enum class SecurityBiometricAvailability {
    UNKNOWN,
    AVAILABLE,
    NO_HARDWARE,
    HARDWARE_UNAVAILABLE,
    NONE_ENROLLED,
    UNSUPPORTED
}

data class SecurityUiState(
    val hasRegistrationPassword: Boolean = false,
    val registrationPasswordPreview: String = "",
    val isPasswordVisible: Boolean = false,
    val expandedFaqItems: Set<SecurityFaqItem> = emptySet(),
    val isEncryptionExpanded: Boolean = false,
    val isBiometricEnabled: Boolean = false,
    val biometricAvailability: SecurityBiometricAvailability = SecurityBiometricAvailability.UNKNOWN
)

sealed interface SecurityEvent {
    data class ShowMessage(val key: String) : SecurityEvent
    data object RequestBiometricPrompt : SecurityEvent
}

interface SecuritySettingsStore {
    fun getRegistrationPassword(): String
    fun isBiometricEnabled(): Boolean
    fun setBiometricEnabled(enabled: Boolean)
}

class AccountSecuritySettingsStore(
    private val accountSettings: AccountScopedSettings,
    private val sessionStore: SharedPrefsAccountSessionStore
) : SecuritySettingsStore {
    constructor(context: Context) : this(
        AccountScopedSettings(context.applicationContext),
        SharedPrefsAccountSessionStore(context.applicationContext)
    )

    override fun getRegistrationPassword(): String = sessionStore.getCurrentUserPassword().orEmpty()

    override fun isBiometricEnabled(): Boolean {
        return accountSettings.getBoolean(KEY_BIOMETRIC_ENABLED, defaultValue = false)
    }

    override fun setBiometricEnabled(enabled: Boolean) {
        accountSettings.saveBoolean(KEY_BIOMETRIC_ENABLED, enabled)
    }
}

class SecurityViewModel(
    private val settingsStore: SecuritySettingsStore
) : ViewModel() {

    private var registrationPassword: String = settingsStore.getRegistrationPassword()

    private val _uiState = MutableStateFlow(
        SecurityUiState(
            hasRegistrationPassword = registrationPassword.isNotBlank(),
            registrationPasswordPreview = registrationPassword,
            isBiometricEnabled = settingsStore.isBiometricEnabled()
        )
    )
    val uiState: StateFlow<SecurityUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<SecurityEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<SecurityEvent> = _events.asSharedFlow()

    fun togglePasswordVisibility() {
        _uiState.update { it.copy(isPasswordVisible = !it.isPasswordVisible) }
    }

    fun refreshPassword() {
        registrationPassword = settingsStore.getRegistrationPassword()
        _uiState.update {
            it.copy(
                hasRegistrationPassword = registrationPassword.isNotBlank(),
                registrationPasswordPreview = registrationPassword,
                isPasswordVisible = if (registrationPassword.isBlank()) false else it.isPasswordVisible
            )
        }
    }

    fun toggleFaq(item: SecurityFaqItem) {
        _uiState.update { state ->
            val nextExpanded = state.expandedFaqItems.toMutableSet()
            if (!nextExpanded.add(item)) {
                nextExpanded.remove(item)
            }
            state.copy(expandedFaqItems = nextExpanded)
        }
    }

    fun toggleEncryptionExplanation() {
        _uiState.update { it.copy(isEncryptionExpanded = !it.isEncryptionExpanded) }
    }

    fun setBiometricAvailability(availability: SecurityBiometricAvailability) {
        _uiState.update {
            it.copy(
                biometricAvailability = availability,
                isBiometricEnabled = settingsStore.isBiometricEnabled()
            )
        }
    }

    fun onBiometricClicked() {
        val state = _uiState.value
        if (state.isBiometricEnabled) {
            settingsStore.setBiometricEnabled(false)
            _uiState.update { it.copy(isBiometricEnabled = false) }
            _events.tryEmit(SecurityEvent.ShowMessage("security_biometric_disabled"))
            return
        }

        when (state.biometricAvailability) {
            SecurityBiometricAvailability.AVAILABLE -> {
                _events.tryEmit(SecurityEvent.RequestBiometricPrompt)
            }
            SecurityBiometricAvailability.NONE_ENROLLED -> {
                _events.tryEmit(SecurityEvent.ShowMessage("security_biometric_not_enrolled"))
            }
            SecurityBiometricAvailability.NO_HARDWARE -> {
                _events.tryEmit(SecurityEvent.ShowMessage("security_biometric_no_hardware"))
            }
            SecurityBiometricAvailability.HARDWARE_UNAVAILABLE,
            SecurityBiometricAvailability.UNSUPPORTED,
            SecurityBiometricAvailability.UNKNOWN -> {
                _events.tryEmit(SecurityEvent.ShowMessage("security_biometric_unavailable"))
            }
        }
    }

    fun onBiometricAuthenticationSucceeded() {
        settingsStore.setBiometricEnabled(true)
        _uiState.update { it.copy(isBiometricEnabled = true) }
        _events.tryEmit(SecurityEvent.ShowMessage("security_biometric_enabled"))
    }

    fun onBiometricAuthenticationFailed() {
        _events.tryEmit(SecurityEvent.ShowMessage("security_biometric_auth_failed"))
    }

    class Factory(
        private val context: Context
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(SecurityViewModel::class.java)) {
                return SecurityViewModel(AccountSecuritySettingsStore(context.applicationContext)) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
