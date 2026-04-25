package com.example.chatapp.ui.auth.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.chatapp.LocaleHelper
import com.example.chatapp.ui.auth.components.AuthScreenLayout
import com.example.chatapp.ui.auth.components.AuthTestTags
import com.example.chatapp.ui.auth.components.StatusMessageCard
import com.example.chatapp.ui.auth.components.TelegramLoginButton
import com.example.chatapp.viewmodel.AuthUiState
import com.example.chatapp.viewmodel.TelegramFlowMode

@Composable
fun AuthWelcomeScreen(
    state: AuthUiState,
    onContinueWithTelegram: () -> Unit
) {
    val context = LocalContext.current
    AuthScreenLayout(
        title = LocaleHelper.getString(context, "auth_welcome_title"),
        subtitle = LocaleHelper.getString(context, "auth_welcome_subtitle")
    ) {
        StatusMessageCard(
            errorMessage = state.errorMessage,
            infoMessage = state.infoMessage
        )
        if (state.errorMessage != null || state.infoMessage != null) {
            Spacer(modifier = Modifier.height(16.dp))
        }
        TelegramLoginButton(
            text = LocaleHelper.getString(context, "auth_continue_with_telegram"),
            enabled = !state.isLoading,
            loading = state.isLoading && state.telegramFlowMode == TelegramFlowMode.WIDGET,
            onClick = onContinueWithTelegram,
            testTag = AuthTestTags.TELEGRAM_CONTINUE_BUTTON
        )
    }
}
