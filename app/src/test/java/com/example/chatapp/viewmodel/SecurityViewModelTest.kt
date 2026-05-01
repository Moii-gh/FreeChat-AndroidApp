package com.example.chatapp.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityViewModelTest {

    @Test
    fun `registration password is shown only from the account store`() {
        val viewModel = SecurityViewModel(FakeSecuritySettingsStore(savedRegistrationPassword = "abc123"))

        val initialState = viewModel.uiState.value
        assertTrue(initialState.hasRegistrationPassword)
        assertEquals("abc123", initialState.registrationPasswordPreview)
        assertFalse(initialState.isPasswordVisible)

        viewModel.togglePasswordVisibility()

        assertTrue(viewModel.uiState.value.isPasswordVisible)
    }

    @Test
    fun `missing registration password is handled without local replacement`() {
        val viewModel = SecurityViewModel(FakeSecuritySettingsStore(savedRegistrationPassword = ""))

        val state = viewModel.uiState.value
        assertFalse(state.hasRegistrationPassword)
        assertEquals("", state.registrationPasswordPreview)

        viewModel.togglePasswordVisibility()
        viewModel.refreshPassword()

        assertFalse(viewModel.uiState.value.isPasswordVisible)
    }

    @Test
    fun `all faq items are collapsed initially and toggle independently`() {
        val viewModel = SecurityViewModel(FakeSecuritySettingsStore())

        assertTrue(viewModel.uiState.value.expandedFaqItems.isEmpty())

        viewModel.toggleFaq(SecurityFaqItem.DATA_PROTECTION)
        assertTrue(SecurityFaqItem.DATA_PROTECTION in viewModel.uiState.value.expandedFaqItems)

        viewModel.toggleFaq(SecurityFaqItem.DATA_PROTECTION)
        assertFalse(SecurityFaqItem.DATA_PROTECTION in viewModel.uiState.value.expandedFaqItems)
    }

    @Test
    fun `biometric availability does not erase persisted enabled state`() {
        val store = FakeSecuritySettingsStore(savedBiometricEnabled = true)
        val viewModel = SecurityViewModel(store)

        viewModel.setBiometricAvailability(SecurityBiometricAvailability.NO_HARDWARE)
        assertTrue(viewModel.uiState.value.isBiometricEnabled)
        assertTrue(store.savedBiometricEnabled)

        viewModel.setBiometricAvailability(SecurityBiometricAvailability.AVAILABLE)
        viewModel.onBiometricClicked()
        assertFalse(viewModel.uiState.value.isBiometricEnabled)
        assertFalse(store.savedBiometricEnabled)
    }

    @Test
    fun `biometric enable is saved after successful prompt`() {
        val store = FakeSecuritySettingsStore(savedBiometricEnabled = false)
        val viewModel = SecurityViewModel(store)

        viewModel.setBiometricAvailability(SecurityBiometricAvailability.AVAILABLE)
        viewModel.onBiometricAuthenticationSucceeded()

        assertTrue(viewModel.uiState.value.isBiometricEnabled)
        assertTrue(store.savedBiometricEnabled)
    }
}

private class FakeSecuritySettingsStore(
    var savedRegistrationPassword: String = "",
    var savedBiometricEnabled: Boolean = false
) : SecuritySettingsStore {
    override fun getRegistrationPassword(): String = savedRegistrationPassword

    override fun isBiometricEnabled(): Boolean = savedBiometricEnabled

    override fun setBiometricEnabled(enabled: Boolean) {
        savedBiometricEnabled = enabled
    }
}
