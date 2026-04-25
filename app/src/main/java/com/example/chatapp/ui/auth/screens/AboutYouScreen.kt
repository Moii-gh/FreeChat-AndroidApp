package com.example.chatapp.ui.auth.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.chatapp.LocaleHelper
import com.example.chatapp.ui.auth.components.AgreementHint
import com.example.chatapp.ui.auth.components.AuthScreenLayout
import com.example.chatapp.ui.auth.components.AuthTestTags
import com.example.chatapp.ui.auth.components.AuthTextField
import com.example.chatapp.ui.auth.components.PrimaryActionButton
import com.example.chatapp.ui.auth.components.ReadonlyField
import com.example.chatapp.ui.auth.components.StatusMessageCard
import com.example.chatapp.viewmodel.AuthUiState
import java.time.format.DateTimeFormatter

private val BirthDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

@Composable
fun AboutYouScreen(
    state: AuthUiState,
    onFullNameChanged: (String) -> Unit,
    onBirthDateClick: () -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    AuthScreenLayout(
        title = LocaleHelper.getString(context, "auth_about_title"),
        subtitle = LocaleHelper.getString(context, "auth_about_subtitle"),
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
            value = state.fullName,
            onValueChange = onFullNameChanged,
            placeholder = LocaleHelper.getString(context, "auth_full_name_placeholder"),
            testTag = AuthTestTags.FULL_NAME_FIELD
        )
        Spacer(modifier = Modifier.height(12.dp))
        ReadonlyField(
            value = state.birthDate?.format(BirthDateFormatter).orEmpty(),
            placeholder = LocaleHelper.getString(context, "auth_birth_date_placeholder"),
            onClick = onBirthDateClick,
            testTag = AuthTestTags.DATE_FIELD
        )
        Spacer(modifier = Modifier.height(16.dp))
        AgreementHint()
        Spacer(modifier = Modifier.height(24.dp))
        PrimaryActionButton(
            text = LocaleHelper.getString(context, "button_continue"),
            enabled = state.canContinueFromAboutYou,
            loading = false,
            onClick = onContinue,
            testTag = AuthTestTags.CONTINUE_BUTTON
        )
    }
}
