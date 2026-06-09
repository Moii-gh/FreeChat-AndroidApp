package com.example.chatapp

import android.content.Context
import android.os.Bundle
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.chatapp.databinding.ActivitySecurityBinding
import com.example.chatapp.util.setHapticClickListener
import com.example.chatapp.viewmodel.SecurityBiometricAvailability
import com.example.chatapp.viewmodel.SecurityEvent
import com.example.chatapp.viewmodel.SecurityFaqItem
import com.example.chatapp.viewmodel.SecurityUiState
import com.example.chatapp.viewmodel.SecurityViewModel
import kotlinx.coroutines.launch

class SecurityActivity : AppCompatActivity() {

    private val viewModel: SecurityViewModel by viewModels {
        SecurityViewModel.Factory(applicationContext)
    }

    private lateinit var binding: ActivitySecurityBinding
    private var lastRenderedState: SecurityUiState? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguageManager.applyLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySecurityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.statusBarColor = ContextCompat.getColor(this, R.color.security_background)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.security_background)

        bindActions()
        collectState()
        viewModel.setBiometricAvailability(resolveBiometricAvailability())
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshPassword()
        viewModel.setBiometricAvailability(resolveBiometricAvailability())
        render(viewModel.uiState.value)
    }

    private fun bindActions() {
        binding.btnBack.setHapticClickListener { finish() }
        binding.passwordCard.setHapticClickListener { showLocalPasswordSettingsDialog() }
        binding.btnShowPassword.setHapticClickListener { viewModel.togglePasswordVisibility() }
        binding.faqDataProtectionRow.setHapticClickListener {
            viewModel.toggleFaq(SecurityFaqItem.DATA_PROTECTION)
        }
        binding.faqSafePasswordRow.setHapticClickListener {
            viewModel.toggleFaq(SecurityFaqItem.SAFE_PASSWORD)
        }
        binding.faqDataStorageRow.setHapticClickListener {
            viewModel.toggleFaq(SecurityFaqItem.DATA_STORAGE)
        }
        binding.faqTelegramRow.setHapticClickListener {
            viewModel.toggleFaq(SecurityFaqItem.TELEGRAM_LOGIN)
        }
        binding.encryptionCard.setHapticClickListener { viewModel.toggleEncryptionExplanation() }
        binding.biometricCard.setHapticClickListener { viewModel.onBiometricClicked() }
    }

    private fun collectState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { render(it) }
                }
                launch {
                    viewModel.events.collect { handleEvent(it) }
                }
            }
        }
    }

    private fun render(state: SecurityUiState) {
        val shouldAnimate = lastRenderedState != null &&
            (
                lastRenderedState?.expandedFaqItems != state.expandedFaqItems ||
                    lastRenderedState?.isEncryptionExpanded != state.isEncryptionExpanded
                )
        if (shouldAnimate) {
            TransitionManager.beginDelayedTransition(
                binding.securityContent,
                AutoTransition().apply { duration = 180L }
            )
        }

        renderStaticText(state)
        renderPasswordCard(state)
        renderFaq(state)
        renderEncryption(state)
        renderBiometric(state)
        lastRenderedState = state
    }

    private fun renderStaticText(state: SecurityUiState) {
        binding.tvToolbarTitle.text = text("button_security")
        binding.btnBack.contentDescription = text("content_desc_back")
        binding.tvPasswordLabel.text = text("security_your_password_label")
        binding.tvFaqDataProtectionTitle.text = text("security_faq_title")
        binding.tvFaqSafePasswordTitle.text = text("security_faq_safe_password_title")
        binding.tvFaqDataStorageTitle.text = text("security_faq_data_storage_title")
        binding.tvFaqTelegramTitle.text = text("security_faq_telegram_title")
        binding.tvFaqDataProtectionAnswerLabel.text = text("security_faq_answer_label")
        binding.tvFaqSafePasswordAnswerLabel.text = text("security_faq_answer_label")
        binding.tvFaqDataStorageAnswerLabel.text = text("security_faq_answer_label")
        binding.tvFaqTelegramAnswerLabel.text = text("security_faq_answer_label")
        binding.tvFaqDataProtectionAnswer.text = text("security_faq_data_protection_answer")
        binding.tvFaqSafePasswordAnswer.text = text("security_faq_safe_password_answer")
        binding.tvFaqDataStorageAnswer.text = text("security_faq_data_storage_answer")
        binding.tvFaqTelegramAnswer.text = text("security_faq_telegram_answer")
        binding.tvEncryptionTitle.text = text("security_encryption_title")
        binding.tvEncryptionDescription.text = text("security_encryption_description")
        binding.tvBiometricTitle.text = text("security_biometric_title")
        binding.tvAppVersion.text = LocaleHelper.formatString(
            this,
            "app_version",
            BuildConfig.VERSION_NAME
        )
        binding.encryptionCard.contentDescription = text("security_encryption_title")
        binding.biometricCard.contentDescription = text("security_biometric_title")
        binding.btnShowPassword.contentDescription = text(
            if (state.isPasswordVisible) "button_hide_password" else "button_show_password"
        )
    }

    private fun renderPasswordCard(state: SecurityUiState) {
        binding.tvPasswordValue.text = when {
            state.hasRegistrationPassword && state.isPasswordVisible -> viewModel.getLocalPassword()
            state.hasRegistrationPassword -> text("security_password_mask")
            else -> text("security_password_not_set")
        }
        binding.btnShowPassword.isVisible = state.hasRegistrationPassword
        binding.btnShowPassword.setImageResource(
            if (state.isPasswordVisible) R.drawable.ic_security_eye else R.drawable.ic_security_eye_off
        )
    }

    private fun showLocalPasswordSettingsDialog() {
        val hasPass = viewModel.uiState.value.hasRegistrationPassword
        val dialog = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

        val dialogView = layoutInflater.inflate(R.layout.dialog_local_password_settings, null)
        val card = dialogView.findViewById<android.view.View>(R.id.dialogCard)
        val title = dialogView.findViewById<android.widget.TextView>(R.id.tvDialogTitle)
        val errorMsg = dialogView.findViewById<android.widget.TextView>(R.id.tvErrorMsg)

        val containerCurrent = dialogView.findViewById<android.view.View>(R.id.containerCurrentPassword)
        val etCurrent = dialogView.findViewById<android.widget.EditText>(R.id.etCurrentPassword)

        val containerNew = dialogView.findViewById<android.view.View>(R.id.containerNewPassword)
        val etNew = dialogView.findViewById<android.widget.EditText>(R.id.etNewPassword)

        val containerConfirm = dialogView.findViewById<android.view.View>(R.id.containerConfirmPassword)
        val etConfirm = dialogView.findViewById<android.widget.EditText>(R.id.etConfirmPassword)

        val btnCancel = dialogView.findViewById<android.widget.TextView>(R.id.btnCancel)
        val btnDisable = dialogView.findViewById<android.widget.TextView>(R.id.btnDisable)
        val btnSave = dialogView.findViewById<android.widget.TextView>(R.id.btnSave)

        btnCancel.text = text("button_cancel")

        if (hasPass) {
            title.text = text("dialog_change_password")
            containerCurrent.visibility = android.view.View.VISIBLE
            btnDisable.visibility = android.view.View.VISIBLE
            btnDisable.text = text("security_password_disable")
            btnSave.text = text("button_save")
        } else {
            title.text = text("security_password_set_title")
            containerCurrent.visibility = android.view.View.GONE
            btnDisable.visibility = android.view.View.GONE
            btnSave.text = text("button_save")
        }

        card.alpha = 0f
        card.scaleX = 0.9f
        card.scaleY = 0.9f
        card.translationY = 18f * resources.displayMetrics.density

        btnCancel.setHapticClickListener { dialog.dismiss() }

        btnDisable.setHapticClickListener {
            val enteredCurrent = etCurrent.text.toString()
            if (enteredCurrent != viewModel.getLocalPassword()) {
                errorMsg.text = text("security_password_current_error")
                errorMsg.visibility = android.view.View.VISIBLE
            } else {
                viewModel.setLocalPassword("")
                toast(text("security_password_change_success"))
                dialog.dismiss()
            }
        }

        btnSave.setHapticClickListener {
            val enteredCurrent = etCurrent.text.toString()
            val enteredNew = etNew.text.toString()
            val enteredConfirm = etConfirm.text.toString()

            if (hasPass && enteredCurrent != viewModel.getLocalPassword()) {
                errorMsg.text = text("security_password_current_error")
                errorMsg.visibility = android.view.View.VISIBLE
                return@setHapticClickListener
            }

            if (enteredNew.length < 6) {
                errorMsg.text = text("auth_error_password_too_short")
                errorMsg.visibility = android.view.View.VISIBLE
                return@setHapticClickListener
            }

            if (enteredNew != enteredConfirm) {
                errorMsg.text = text("password_error_mismatch")
                errorMsg.visibility = android.view.View.VISIBLE
                return@setHapticClickListener
            }

            viewModel.setLocalPassword(enteredNew)
            toast(text("security_password_change_success"))
            dialog.dismiss()
        }

        dialog.setContentView(dialogView)
        dialog.setCanceledOnTouchOutside(true)
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
        dialog.show()
    }

    private fun renderFaq(state: SecurityUiState) {
        renderFaqItem(
            expanded = SecurityFaqItem.DATA_PROTECTION in state.expandedFaqItems,
            actionView = binding.tvFaqDataProtectionAction,
            arrowView = binding.ivFaqDataProtectionArrow,
            answerGroup = binding.faqDataProtectionAnswerGroup
        )
        renderFaqItem(
            expanded = SecurityFaqItem.SAFE_PASSWORD in state.expandedFaqItems,
            actionView = binding.tvFaqSafePasswordAction,
            arrowView = binding.ivFaqSafePasswordArrow,
            answerGroup = binding.faqSafePasswordAnswerGroup
        )
        renderFaqItem(
            expanded = SecurityFaqItem.DATA_STORAGE in state.expandedFaqItems,
            actionView = binding.tvFaqDataStorageAction,
            arrowView = binding.ivFaqDataStorageArrow,
            answerGroup = binding.faqDataStorageAnswerGroup
        )
        renderFaqItem(
            expanded = SecurityFaqItem.TELEGRAM_LOGIN in state.expandedFaqItems,
            actionView = binding.tvFaqTelegramAction,
            arrowView = binding.ivFaqTelegramArrow,
            answerGroup = binding.faqTelegramAnswerGroup
        )
    }

    private fun renderFaqItem(
        expanded: Boolean,
        actionView: TextView,
        arrowView: ImageView,
        answerGroup: android.view.View
    ) {
        actionView.text = text(
            if (expanded) "security_faq_action_answer" else "security_faq_action_view"
        )
        arrowView.animate().rotation(if (expanded) 180f else 0f).setDuration(180L).start()
        answerGroup.isVisible = expanded
    }

    private fun renderEncryption(state: SecurityUiState) {
        binding.tvEncryptionDescription.isVisible = state.isEncryptionExpanded
    }

    private fun renderBiometric(state: SecurityUiState) {
        binding.tvBiometricStatus.text = when {
            state.isBiometricEnabled -> text("security_biometric_status_enabled")
            else -> text("security_biometric_status_absent")
        }
    }

    private fun handleEvent(event: SecurityEvent) {
        when (event) {
            is SecurityEvent.ShowMessage -> toast(text(event.key))
            SecurityEvent.RequestBiometricPrompt -> showBiometricPrompt()
        }
    }

    private fun showBiometricPrompt() {
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(text("security_biometric_prompt_title"))
            .setSubtitle(text("security_biometric_prompt_subtitle"))
            .setNegativeButtonText(text("button_cancel"))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .build()

        BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    viewModel.onBiometricAuthenticationSucceeded()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    if (
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                        errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_CANCELED
                    ) {
                        viewModel.onBiometricAuthenticationFailed()
                    }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    viewModel.onBiometricAuthenticationFailed()
                }
            }
        ).authenticate(promptInfo)
    }

    private fun resolveBiometricAvailability(): SecurityBiometricAvailability {
        return when (
            BiometricManager.from(this)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        ) {
            BiometricManager.BIOMETRIC_SUCCESS -> SecurityBiometricAvailability.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> SecurityBiometricAvailability.NO_HARDWARE
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> SecurityBiometricAvailability.HARDWARE_UNAVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> SecurityBiometricAvailability.NONE_ENROLLED
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED,
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED -> SecurityBiometricAvailability.UNSUPPORTED
            else -> SecurityBiometricAvailability.UNKNOWN
        }
    }

    private fun text(key: String): String = LocaleHelper.getString(this, key)

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
