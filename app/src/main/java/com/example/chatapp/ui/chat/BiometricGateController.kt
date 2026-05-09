package com.example.chatapp.ui.chat

import android.content.Intent
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.example.chatapp.LocaleHelper
import com.example.chatapp.MainActivity
import com.example.chatapp.data.SharedPrefsAccountSessionStore
import com.example.chatapp.viewmodel.AccountSecuritySettingsStore

internal class BiometricGateController(
    private val activity: FragmentActivity,
    private val rootView: View,
    private val onUnlocked: () -> Unit,
    private val onMessage: (String) -> Unit
) {
    private val securitySettingsStore = AccountSecuritySettingsStore(activity.applicationContext)
    private var gateDialog: AlertDialog? = null
    private var isActive = false

    fun shouldGate(skipOnce: Boolean): Boolean =
        securitySettingsStore.isBiometricEnabled() && !skipOnce

    fun prepareGate() {
        isActive = true
        rootView.alpha = 0f
    }

    fun start() {
        if (!isActive || activity.isFinishing || activity.isDestroyed) return
        when (biometricAvailability()) {
            BiometricManager.BIOMETRIC_SUCCESS -> showUnlockPrompt()
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                showGateDialog("security_biometric_not_enrolled")
            }
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> {
                showGateDialog("security_biometric_no_hardware")
            }
            else -> {
                showGateDialog("security_biometric_unavailable")
            }
        }
    }

    fun dismiss() {
        gateDialog?.dismiss()
        gateDialog = null
        isActive = false
    }

    private fun showUnlockPrompt() {
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(LocaleHelper.getString(activity, "security_biometric_unlock_title"))
            .setSubtitle(LocaleHelper.getString(activity, "security_biometric_unlock_subtitle"))
            .setNegativeButtonText(LocaleHelper.getString(activity, "button_cancel"))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .build()

        BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    unlock()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    if (!isActive) return
                    val messageKey = when (errorCode) {
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_CANCELED -> "security_biometric_required_message"
                        else -> "security_biometric_auth_failed"
                    }
                    showGateDialog(messageKey)
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    onMessage(LocaleHelper.getString(activity, "security_biometric_auth_failed"))
                }
            }
        ).authenticate(promptInfo)
    }

    private fun unlock() {
        isActive = false
        gateDialog?.dismiss()
        gateDialog = null
        onUnlocked()
        rootView.animate()
            .alpha(1f)
            .setDuration(UNLOCK_FADE_MS)
            .start()
    }

    private fun showGateDialog(messageKey: String) {
        if (!isActive || activity.isFinishing || activity.isDestroyed) return
        gateDialog?.dismiss()
        gateDialog = AlertDialog.Builder(activity)
            .setTitle(LocaleHelper.getString(activity, "security_biometric_required_title"))
            .setMessage(LocaleHelper.getString(activity, messageKey))
            .setPositiveButton(LocaleHelper.getString(activity, "security_biometric_retry")) { _, _ ->
                start()
            }
            .setNegativeButton(LocaleHelper.getString(activity, "security_biometric_use_login")) { _, _ ->
                SharedPrefsAccountSessionStore(activity.applicationContext).clearSession()
                activity.startActivity(
                    Intent(activity, MainActivity::class.java).apply {
                        putExtra(MainActivity.EXTRA_SKIP_BIOMETRIC_ONCE_AFTER_LOGIN, true)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                )
                activity.finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun biometricAvailability(): Int =
        BiometricManager.from(activity)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)

    private companion object {
        // Fade запускается только после успешной системной аутентификации, чтобы не показать чат до unlock.
        const val UNLOCK_FADE_MS = 180L
    }
}
