package com.example.chatapp

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.chatapp.data.AuthRepository
import com.example.chatapp.data.NetworkResult
import com.example.chatapp.data.SharedPrefsAccountSessionStore
import com.example.chatapp.network.NetworkModule
import com.example.chatapp.network.dto.ChangePasswordRequest
import kotlinx.coroutines.launch

class SecurityActivity : AppCompatActivity() {

    private lateinit var etPassword: EditText
    private lateinit var btnShowPassword: TextView
    private lateinit var sessionStore: SharedPrefsAccountSessionStore
    private lateinit var authRepository: AuthRepository
    private var isMasked = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_security)

        window.statusBarColor = Color.TRANSPARENT

        sessionStore = SharedPrefsAccountSessionStore(this)
        authRepository = AuthRepository(
            service = NetworkModule.createAuthApiService(BuildConfig.APP_API_BASE_URL)
        )

        etPassword = findViewById(R.id.etPassword)
        btnShowPassword = findViewById(R.id.btnShowPassword)

        renderPasswordField()
        bindTranslations()

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        btnShowPassword.setOnClickListener {
            isMasked = !isMasked
            renderPasswordField()
        }

        findViewById<View>(R.id.btnChangePassword).setOnClickListener {
            showChangePasswordDialog()
        }

        findViewById<View>(R.id.btnSavePassword).setOnClickListener {
            showChangePasswordDialog()
        }
    }

    private fun bindTranslations() {
        findViewById<TextView>(R.id.tvToolbarTitle)?.text = LocaleHelper.getString(this, "button_security")
        findViewById<TextView>(R.id.tvPasswordLabel)?.text = LocaleHelper.getString(this, "label_your_password")
        findViewById<TextView>(R.id.btnChangePassword)?.text = LocaleHelper.getString(this, "button_change_password")
        findViewById<TextView>(R.id.btnSavePassword)?.text = LocaleHelper.getString(this, "button_save")
    }

    private fun renderPasswordField() {
        val signedIn = sessionStore.isSignedIn()
        val maskedValue = if (signedIn) "********" else LocaleHelper.getString(this, "password_sign_in_again")
        val visibleValue = if (signedIn) {
            LocaleHelper.getString(this, "password_securely_stored")
        } else {
            LocaleHelper.getString(this, "password_sign_in_again")
        }

        etPassword.setText(if (isMasked) maskedValue else visibleValue)
        etPassword.inputType = if (isMasked) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        etPassword.setSelection(etPassword.text.length)
        etPassword.isEnabled = false

        btnShowPassword.text = if (isMasked) {
            LocaleHelper.getString(this, "button_show_password")
        } else {
            LocaleHelper.getString(this, "button_hide_password")
        }
    }

    private fun showChangePasswordDialog() {
        val token = sessionStore.getAuthToken()
        if (token.isNullOrBlank()) {
            Toast.makeText(this, LocaleHelper.getString(this, "session_missing_sign_in"), Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_change_password, null)
        val currentPasswordField = dialogView.findViewById<EditText>(R.id.etCurrentPassword)
        val newPasswordField = dialogView.findViewById<EditText>(R.id.etNewPassword)
        val confirmPasswordField = dialogView.findViewById<EditText>(R.id.etConfirmPassword)
        val cancelButton = dialogView.findViewById<TextView>(R.id.btnCancelChangePassword)
        val saveButton = dialogView.findViewById<TextView>(R.id.btnSaveChangePassword)
        val closeButton = dialogView.findViewById<View>(R.id.btnCloseChangePassword)

        currentPasswordField.hint = "Текущий пароль"
        newPasswordField.hint = "Новый пароль"
        confirmPasswordField.hint = "Повторите пароль"
        cancelButton.text = LocaleHelper.getString(this, "button_cancel")
        saveButton.text = LocaleHelper.getString(this, "button_save")
        closeButton.contentDescription = LocaleHelper.getString(this, "button_cancel")

        listOf(currentPasswordField, newPasswordField, confirmPasswordField).forEach { field ->
            field.onFocusChangeListener = View.OnFocusChangeListener { view, hasFocus ->
                val scale = if (hasFocus) 1.015f else 1f
                view.animate()
                    .scaleX(scale)
                    .scaleY(scale)
                    .setDuration(160L)
                    .start()
            }
        }

        val dialog = Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(dialogView)
            setCanceledOnTouchOutside(true)
        }

        closeButton.setOnClickListener {
            dialog.dismiss()
        }
        cancelButton.setOnClickListener {
            dialog.dismiss()
        }
        saveButton.setOnClickListener {
            val currentPassword = currentPasswordField.text.toString().trim()
            val newPassword = newPasswordField.text.toString().trim()
            val confirmPassword = confirmPasswordField.text.toString().trim()

            when {
                currentPassword.isEmpty() -> toast(LocaleHelper.getString(this, "password_error_current_required"))
                newPassword.length < 6 -> toast(LocaleHelper.getString(this, "password_error_new_too_short"))
                newPassword != confirmPassword -> toast(LocaleHelper.getString(this, "password_error_mismatch"))
                else -> {
                    changePassword(token, currentPassword, newPassword, dialog)
                }
            }
        }

        dialog.show()
        dialog.window?.let { window ->
            val horizontalMargin = dp(48)
            val maxWidth = dp(734)
            val targetWidth = (resources.displayMetrics.widthPixels - horizontalMargin).coerceAtMost(maxWidth)

            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.setDimAmount(0.58f)
            window.setGravity(Gravity.CENTER)
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.setLayout(targetWidth, WindowManager.LayoutParams.WRAP_CONTENT)
        }

        dialogView.alpha = 0f
        dialogView.scaleX = 0.96f
        dialogView.scaleY = 0.96f
        dialogView.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(220L)
            .start()
    }

    private fun changePassword(
        token: String,
        currentPassword: String,
        newPassword: String,
        dialog: Dialog
    ) {
        lifecycleScope.launch {
            when (
                val result = authRepository.changePassword(
                    token = token,
                    request = ChangePasswordRequest(
                        currentPassword = currentPassword,
                        newPassword = newPassword
                    )
                )
            ) {
                is NetworkResult.Success -> {
                    toast(result.data.message)
                    dialog.dismiss()
                    isMasked = true
                    renderPasswordField()
                }

                is NetworkResult.Error -> {
                    toast(result.message)
                }
            }
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
