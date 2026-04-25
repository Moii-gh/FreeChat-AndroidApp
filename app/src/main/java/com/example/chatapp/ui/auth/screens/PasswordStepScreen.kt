package com.example.chatapp.ui.auth.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.chatapp.LocaleHelper
import com.example.chatapp.ui.auth.components.AuthScreenLayout
import com.example.chatapp.ui.auth.components.AuthTestTags
import com.example.chatapp.ui.auth.components.AuthTextField
import com.example.chatapp.ui.auth.components.PrimaryActionButton
import com.example.chatapp.ui.auth.components.StatusMessageCard
import com.example.chatapp.ui.auth.theme.AppStroke
import com.example.chatapp.ui.auth.theme.AppSurfaceElevated
import com.example.chatapp.ui.auth.theme.AppTextMuted
import com.example.chatapp.ui.auth.theme.AppTextPrimary
import com.example.chatapp.viewmodel.AuthUiState

@Composable
fun PasswordStepScreen(
    state: AuthUiState,
    onPasswordChanged: (String) -> Unit,
    onTogglePasswordVisibility: () -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val title = LocaleHelper.getString(context, "auth_password_title")
    val subtitle = LocaleHelper.getString(context, "auth_password_subtitle")

    AuthScreenLayout(
        title = title,
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
        TelegramConfirmedCard()
        Spacer(modifier = Modifier.height(12.dp))
        AuthTextField(
            value = state.password,
            onValueChange = onPasswordChanged,
            placeholder = LocaleHelper.getString(context, "auth_password_placeholder"),
            isPasswordField = true,
            isPasswordVisible = state.isPasswordVisible,
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
            testTag = AuthTestTags.PASSWORD_FIELD
        )
        Spacer(modifier = Modifier.height(20.dp))
        PrimaryActionButton(
            text = LocaleHelper.getString(context, "auth_create_account"),
            enabled = state.canSubmitPasswordStep,
            loading = state.isLoading,
            onClick = onContinue,
            testTag = AuthTestTags.CONTINUE_BUTTON
        )
    }
}

@Composable
private fun TelegramConfirmedCard() {
    val context = LocalContext.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = AppSurfaceElevated,
        border = BorderStroke(1.dp, AppStroke)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)
        ) {
            Text(
                text = LocaleHelper.getString(context, "auth_telegram_confirmed_title"),
                style = MaterialTheme.typography.bodyLarge,
                color = AppTextPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = LocaleHelper.getString(context, "auth_telegram_confirmed_desc"),
                style = MaterialTheme.typography.bodyMedium,
                color = AppTextMuted
            )
        }
    }
}
