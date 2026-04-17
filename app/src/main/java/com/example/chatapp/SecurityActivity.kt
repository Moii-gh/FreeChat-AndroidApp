package com.example.chatapp

import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
        findViewById<TextView>(R.id.tvPasswordLabel)?.text = LocaleHelper.getString(this, "lable_your_password")
        findViewById<TextView>(R.id.btnChangePassword)?.text = LocaleHelper.getString(this, "button_change_password")
        findViewById<TextView>(R.id.btnSavePassword)?.text = LocaleHelper.getString(this, "button_save")
    }

    private fun renderPasswordField() {
        val signedIn = sessionStore.isSignedIn()
        val maskedValue = if (signedIn) "********" else "Войдите снова"
        val visibleValue = if (signedIn) "Пароль хранится безопасно" else "Войдите снова"

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
            Toast.makeText(this, "Сессия не найдена. Войдите снова", Toast.LENGTH_SHORT).show()
            return
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        val currentPasswordField = createPasswordInput("Текущий пароль")
        val newPasswordField = createPasswordInput("Новый пароль")
        val confirmPasswordField = createPasswordInput("Повторите новый пароль")

        container.addView(currentPasswordField)
        container.addView(newPasswordField)
        container.addView(confirmPasswordField)

        val dialog = AlertDialog.Builder(this)
            .setTitle(LocaleHelper.getString(this, "dialog_change_password"))
            .setView(container)
            .setNegativeButton(LocaleHelper.getString(this, "button_cancel"), null)
            .setPositiveButton(LocaleHelper.getString(this, "button_save"), null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val currentPassword = currentPasswordField.text.toString().trim()
                val newPassword = newPasswordField.text.toString().trim()
                val confirmPassword = confirmPasswordField.text.toString().trim()

                when {
                    currentPassword.isEmpty() -> toast("Введите текущий пароль")
                    newPassword.length < 6 -> toast("Новый пароль должен содержать минимум 6 символов")
                    newPassword != confirmPassword -> toast("Новый пароль и подтверждение не совпадают")
                    else -> {
                        changePassword(token, currentPassword, newPassword, dialog)
                    }
                }
            }
        }

        dialog.show()
    }

    private fun changePassword(
        token: String,
        currentPassword: String,
        newPassword: String,
        dialog: AlertDialog
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

    private fun createPasswordInput(hint: String): EditText {
        return EditText(this).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#8E8E93"))
            setPadding(0, 24, 0, 24)
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
