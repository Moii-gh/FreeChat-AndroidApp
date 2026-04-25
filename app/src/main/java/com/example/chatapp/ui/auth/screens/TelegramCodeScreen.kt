package com.example.chatapp.ui.auth.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.chatapp.LocaleHelper
import com.example.chatapp.ui.auth.components.AuthScreenLayout
import com.example.chatapp.ui.auth.components.AuthTestTags
import com.example.chatapp.ui.auth.components.AuthTextField
import com.example.chatapp.ui.auth.components.PrimaryActionButton
import com.example.chatapp.ui.auth.components.SecondaryOutlineButton
import com.example.chatapp.ui.auth.components.StatusMessageCard
import com.example.chatapp.viewmodel.AuthUiState
import com.example.chatapp.viewmodel.TelegramFlowMode

@Composable
fun TelegramCodeScreen(
    state: AuthUiState,
    onCodeChanged: (String) -> Unit,
    onOpenTelegram: () -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val subtitle = when (state.telegramFlowMode) {
        TelegramFlowMode.REGISTER ->
            LocaleHelper.getString(context, "auth_telegram_code_register_subtitle")

        TelegramFlowMode.WIDGET ->
            LocaleHelper.getString(context, "auth_telegram_code_widget_subtitle")
    }

    AuthScreenLayout(
        title = LocaleHelper.getString(context, "auth_telegram_code_title"),
        subtitle = subtitle,
        onBack = onBack
    ) {
        StatusMessageCard(
            errorMessage = state.errorMessage,
            infoMessage = state.infoMessage
        )
        if (state.errorMessage != null || state.infoMessage != null) {
            Spacer(modifier = Modifier.height(16.dp))
        }
        SecondaryOutlineButton(
            text = if (state.isOpeningTelegram) LocaleHelper.getString(context, "auth_opening_telegram") else LocaleHelper.getString(context, "auth_open_telegram"),
            onClick = onOpenTelegram,
            enabled = !state.isOpeningTelegram && !state.telegramBotUrl.isNullOrBlank(),
            testTag = AuthTestTags.OPEN_TELEGRAM_BUTTON
        )
        Spacer(modifier = Modifier.height(16.dp))
        AuthTextField(
            value = state.telegramCode,
            onValueChange = onCodeChanged,
            placeholder = LocaleHelper.getString(context, "auth_bot_code_placeholder"),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            testTag = AuthTestTags.TELEGRAM_CODE_FIELD
        )
        Spacer(modifier = Modifier.height(20.dp))
        PrimaryActionButton(
            text = LocaleHelper.getString(context, "button_continue"),
            enabled = state.canVerifyTelegramCode,
            loading = state.isVerifyingTelegramCode || state.isLoading,
            onClick = onContinue,
            testTag = AuthTestTags.CONTINUE_BUTTON
        )
    }
}
