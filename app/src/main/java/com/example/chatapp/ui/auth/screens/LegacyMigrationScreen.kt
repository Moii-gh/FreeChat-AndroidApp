package com.example.chatapp.ui.auth.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.chatapp.ui.auth.components.AuthScreenLayout
import com.example.chatapp.ui.auth.components.AuthTestTags
import com.example.chatapp.ui.auth.components.AuthTextField
import com.example.chatapp.ui.auth.components.PrimaryActionButton
import com.example.chatapp.ui.auth.components.StatusMessageCard
import com.example.chatapp.ui.auth.theme.AppTextMuted
import com.example.chatapp.viewmodel.AuthUiState

@Composable
fun LegacyMigrationScreen(
    state: AuthUiState,
    onEmailChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onTogglePasswordVisibility: () -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit
) {
    AuthScreenLayout(
        title = "Старый аккаунт",
        subtitle = "Введите email и пароль старого аккаунта, затем подтвердите Telegram, чтобы перенести вход.",
        onBack = onBack
    ) {
        StatusMessageCard(
            errorMessage = state.errorMessage,
            infoMessage = state.infoMessage
        )
        if (state.errorMessage != null || state.infoMessage != null) {
            Spacer(modifier = Modifier.height(16.dp))
        }
        AuthTextField(
            value = state.legacyEmail,
            onValueChange = onEmailChanged,
            placeholder = "Электронная почта",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            testTag = AuthTestTags.LEGACY_EMAIL_FIELD
        )
        Spacer(modifier = Modifier.height(12.dp))
        AuthTextField(
            value = state.legacyPassword,
            onValueChange = onPasswordChanged,
            placeholder = "Пароль",
            isPasswordField = true,
            isPasswordVisible = state.isPasswordVisible,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = onTogglePasswordVisibility) {
                    Icon(
                        imageVector = if (state.isPasswordVisible) {
                            Icons.Outlined.VisibilityOff
                        } else {
                            Icons.Outlined.Visibility
                        },
                        contentDescription = null,
                        tint = AppTextMuted
                    )
                }
            },
            testTag = AuthTestTags.LEGACY_PASSWORD_FIELD
        )
        Spacer(modifier = Modifier.height(20.dp))
        PrimaryActionButton(
            text = "Продолжить",
            enabled = state.canSubmitMigration,
            loading = state.isLoading,
            onClick = onContinue,
            testTag = AuthTestTags.CONTINUE_BUTTON
        )
    }
}
