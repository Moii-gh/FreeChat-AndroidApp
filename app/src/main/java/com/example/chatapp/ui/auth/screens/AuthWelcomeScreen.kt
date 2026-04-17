package com.example.chatapp.ui.auth.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.chatapp.ui.auth.components.AuthScreenLayout
import com.example.chatapp.ui.auth.components.AuthTestTags
import com.example.chatapp.ui.auth.components.OrDivider
import com.example.chatapp.ui.auth.components.PrimaryActionButton
import com.example.chatapp.ui.auth.components.SecondaryOutlineButton
import com.example.chatapp.ui.auth.components.StatusMessageCard
import com.example.chatapp.ui.auth.components.SubtleTextButton
import com.example.chatapp.viewmodel.AuthUiState

@Composable
fun AuthWelcomeScreen(
    state: AuthUiState,
    onContinueWithTelegram: () -> Unit,
    onLoginClick: () -> Unit,
    onLegacyAccountClick: () -> Unit
) {
    AuthScreenLayout(
        title = "Войти или зарегистрироваться",
        subtitle = "Подтвердите Telegram, чтобы создать новый аккаунт или безопасно войти в FreeChat."
    ) {
        StatusMessageCard(
            errorMessage = state.errorMessage,
            infoMessage = state.infoMessage
        )
        if (state.errorMessage != null || state.infoMessage != null) {
            Spacer(modifier = Modifier.height(16.dp))
        }
        PrimaryActionButton(
            text = "Продолжить через Telegram",
            enabled = !state.isLoading,
            loading = state.isLoading && state.telegramFlowMode == com.example.chatapp.viewmodel.TelegramFlowMode.REGISTER,
            onClick = onContinueWithTelegram,
            testTag = AuthTestTags.TELEGRAM_CONTINUE_BUTTON
        )
        Spacer(modifier = Modifier.height(24.dp))
        OrDivider()
        Spacer(modifier = Modifier.height(24.dp))
        SecondaryOutlineButton(
            text = "Войти",
            onClick = onLoginClick,
            enabled = !state.isLoading,
            testTag = AuthTestTags.LOGIN_BUTTON
        )
        Spacer(modifier = Modifier.height(18.dp))
        SubtleTextButton(
            text = "У меня старый аккаунт",
            onClick = onLegacyAccountClick,
            enabled = !state.isLoading,
            testTag = AuthTestTags.LEGACY_ACCOUNT_BUTTON
        )
    }
}
