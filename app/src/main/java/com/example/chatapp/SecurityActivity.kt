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
            state.hasRegistrationPassword && state.isPasswordVisible -> state.registrationPasswordPreview
            state.hasRegistrationPassword -> text("security_password_mask")
            else -> text("security_password_unavailable")
        }
        binding.btnShowPassword.setImageResource(
            if (state.isPasswordVisible) R.drawable.ic_security_eye else R.drawable.ic_security_eye_off
        )
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
