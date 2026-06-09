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
import com.example.chatapp.R
import com.example.chatapp.data.SharedPrefsAccountSessionStore
import com.example.chatapp.viewmodel.AccountSecuritySettingsStore

internal class BiometricGateController(
    private val activity: FragmentActivity,
    private val rootView: View,
    private val onUnlocked: () -> Unit,
    private val onMessage: (String) -> Unit
) {
    private val securitySettingsStore = AccountSecuritySettingsStore(activity.applicationContext)
    private var gateDialog: android.app.Dialog? = null
    private var isActive = false

    fun shouldGate(skipOnce: Boolean): Boolean =
        (securitySettingsStore.isBiometricEnabled() || securitySettingsStore.getLocalPassword().isNotEmpty()) && !skipOnce

    fun prepareGate() {
        isActive = true
        rootView.alpha = 0f
    }

    fun start() {
        if (!isActive || activity.isFinishing || activity.isDestroyed) return
        val biometricEnabled = securitySettingsStore.isBiometricEnabled()
        val biometricAvailable = biometricAvailability() == BiometricManager.BIOMETRIC_SUCCESS

        if (biometricEnabled && biometricAvailable) {
            showUnlockPrompt()
        } else {
            val hasPass = securitySettingsStore.getLocalPassword().isNotEmpty()
            if (hasPass) {
                showPasscodeEntryDialog()
            } else if (biometricEnabled) {
                val messageKey = when (biometricAvailability()) {
                    BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "security_biometric_not_enrolled"
                    BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "security_biometric_no_hardware"
                    else -> "security_biometric_unavailable"
                }
                showGateDialog(messageKey)
            } else {
                unlock()
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
                    val hasPass = securitySettingsStore.getLocalPassword().isNotEmpty()
                    if (hasPass) {
                        showPasscodeEntryDialog()
                    } else {
                        val messageKey = when (errorCode) {
                            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                            BiometricPrompt.ERROR_USER_CANCELED,
                            BiometricPrompt.ERROR_CANCELED -> "security_biometric_required_message"
                            else -> "security_biometric_auth_failed"
                        }
                        showGateDialog(messageKey)
                    }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    onMessage(LocaleHelper.getString(activity, "security_biometric_auth_failed"))
                }
            }
        ).authenticate(promptInfo)
    }

    private fun showPasscodeEntryDialog() {
        if (!isActive || activity.isFinishing || activity.isDestroyed) return
        gateDialog?.dismiss()

        val dialog = android.app.Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_local_password_entry, null)
        val card = dialogView.findViewById<android.view.View>(R.id.entryDialogCard)
        val title = dialogView.findViewById<android.widget.TextView>(R.id.tvEntryTitle)
        val errorMsg = dialogView.findViewById<android.widget.TextView>(R.id.tvEntryError)
        val etPassword = dialogView.findViewById<android.widget.EditText>(R.id.etEntryPassword)
        val btnToggle = dialogView.findViewById<android.widget.ImageButton>(R.id.btnToggleEntryPassword)
        val btnFingerprint = dialogView.findViewById<android.widget.ImageButton>(R.id.btnEntryFingerprint)
        val btnUnlock = dialogView.findViewById<android.widget.TextView>(R.id.btnEntryUnlock)

        title.text = LocaleHelper.getString(activity, "security_biometric_required_title")
        btnUnlock.text = LocaleHelper.getString(activity, "security_password_unlock")
        etPassword.hint = LocaleHelper.getString(activity, "security_password_enter_hint")

        val isBioAvailable = BiometricManager.from(activity).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
        if (isBioAvailable) {
            btnFingerprint.visibility = android.view.View.VISIBLE
        } else {
            btnFingerprint.visibility = android.view.View.GONE
        }

        card.alpha = 0f
        card.scaleX = 0.9f
        card.scaleY = 0.9f
        card.translationY = 18f * activity.resources.displayMetrics.density

        var isPasswordVisible = false
        btnToggle.setOnClickListener {
            isPasswordVisible = !isPasswordVisible
            if (isPasswordVisible) {
                etPassword.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                btnToggle.setImageResource(R.drawable.ic_security_eye)
            } else {
                etPassword.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                btnToggle.setImageResource(R.drawable.ic_security_eye_off)
            }
            etPassword.setSelection(etPassword.text.length)
        }

        btnFingerprint.setOnClickListener {
            dialog.dismiss()
            gateDialog = null
            showUnlockPrompt()
        }

        val performUnlock = {
            val entered = etPassword.text.toString()
            if (entered == securitySettingsStore.getLocalPassword()) {
                dialog.dismiss()
                gateDialog = null
                unlock()
            } else {
                errorMsg.text = LocaleHelper.getString(activity, "security_password_error_wrong")
                errorMsg.visibility = android.view.View.VISIBLE
            }
        }

        btnUnlock.setOnClickListener { performUnlock() }
        etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                performUnlock()
                true
            } else {
                false
            }
        }

        dialog.setContentView(dialogView)
        dialog.setCancelable(false)
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            setDimAmount(0.58f)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setGravity(android.view.Gravity.CENTER)
            setWindowAnimations(0)
            @Suppress("DEPRECATION")
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            attributes = attributes.apply {
                width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            }
        }
        dialog.setOnShowListener {
            dialog.window?.setLayout(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            card.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(260L)
                .setInterpolator(android.view.animation.PathInterpolator(0.16f, 1f, 0.3f, 1f))
                .start()
        }

        gateDialog = dialog
        dialog.show()
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

        val dialog = android.app.Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_biometric_gate, null)
        val card = dialogView.findViewById<android.view.View>(R.id.gateDialogCard)
        val title = dialogView.findViewById<android.widget.TextView>(R.id.tvGateTitle)
        val message = dialogView.findViewById<android.widget.TextView>(R.id.tvGateMessage)
        val btnLogout = dialogView.findViewById<android.widget.TextView>(R.id.btnGateLogout)
        val btnRetry = dialogView.findViewById<android.widget.TextView>(R.id.btnGateRetry)

        title.text = LocaleHelper.getString(activity, "security_biometric_required_title")
        message.text = LocaleHelper.getString(activity, messageKey)
        btnLogout.text = LocaleHelper.getString(activity, "security_biometric_use_login")
        btnRetry.text = LocaleHelper.getString(activity, "security_biometric_retry")

        card.alpha = 0f
        card.scaleX = 0.9f
        card.scaleY = 0.9f
        card.translationY = 18f * activity.resources.displayMetrics.density

        btnLogout.setOnClickListener {
            dialog.dismiss()
            gateDialog = null
            SharedPrefsAccountSessionStore(activity.applicationContext).clearSession()
            activity.startActivity(
                Intent(activity, MainActivity::class.java).apply {
                    putExtra(MainActivity.EXTRA_SKIP_BIOMETRIC_ONCE_AFTER_LOGIN, true)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
            )
            activity.finish()
        }

        btnRetry.setOnClickListener {
            dialog.dismiss()
            gateDialog = null
            start()
        }

        dialog.setContentView(dialogView)
        dialog.setCancelable(false)
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            setDimAmount(0.58f)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setGravity(android.view.Gravity.CENTER)
            setWindowAnimations(0)
            attributes = attributes.apply {
                width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            }
        }
        dialog.setOnShowListener {
            dialog.window?.setLayout(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            card.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(260L)
                .setInterpolator(android.view.animation.PathInterpolator(0.16f, 1f, 0.3f, 1f))
                .start()
        }

        gateDialog = dialog
        dialog.show()
    }

    private fun biometricAvailability(): Int =
        BiometricManager.from(activity)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)

    private companion object {
        const val UNLOCK_FADE_MS = 180L
    }
}
