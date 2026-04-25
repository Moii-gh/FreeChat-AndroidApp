package com.example.chatapp.ui.auth.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.example.chatapp.LocaleHelper
import com.example.chatapp.R
import com.example.chatapp.ui.auth.theme.AppBlack
import com.example.chatapp.ui.auth.theme.AppButtonDisabled
import com.example.chatapp.ui.auth.theme.AppStroke
import com.example.chatapp.ui.auth.theme.AppStrokeStrong
import com.example.chatapp.ui.auth.theme.AppSurface
import com.example.chatapp.ui.auth.theme.AppSurfaceElevated
import com.example.chatapp.ui.auth.theme.AppTextMuted
import com.example.chatapp.ui.auth.theme.AppTextPrimary
import com.example.chatapp.ui.auth.theme.AppTextSecondary

private val AuthPillShape = RoundedCornerShape(30.dp)
private val AuthCardShape = RoundedCornerShape(28.dp)
private val AuthBackShape = RoundedCornerShape(18.dp)

@Composable
fun AuthScreenLayout(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    footer: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(AppBlack, AppBlack, AppSurface)
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .imePadding()
                .padding(horizontal = 22.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                if (onBack != null) {
                    Surface(
                        modifier = Modifier
                            .offset(x = (-8).dp, y = (-6).dp)
                            .size(34.dp)
                            .align(Alignment.TopStart),
                        shape = AuthBackShape,
                        color = Color.White.copy(alpha = 0.07f),
                        border = BorderStroke(1.dp, AppStroke)
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = LocaleHelper.getString(androidx.compose.ui.platform.LocalContext.current, "content_desc_back"),
                                tint = AppTextPrimary
                            )
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.logo),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(82.dp)
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = AppTextPrimary,
                    textAlign = TextAlign.Center
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = 320.dp)
                    )
                }
                Spacer(modifier = Modifier.height(26.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 336.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    content()
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                content = footer
            )
        }
    }
}

@Composable
fun StatusMessageCard(
    errorMessage: String?,
    infoMessage: String?
) {
    val message = errorMessage ?: infoMessage ?: return
    val background = if (errorMessage != null) {
        Color(0x26FF6B6B)
    } else {
        Color.White.copy(alpha = 0.08f)
    }
    val border = if (errorMessage != null) {
        Color(0x40FF6B6B)
    } else {
        AppStrokeStrong
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AuthCardShape,
        color = background,
        border = BorderStroke(1.dp, border)
    ) {
        Text(
            text = message,
            color = AppTextPrimary,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
        )
    }
}

@Composable
fun AuthTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trailingIcon: @Composable (() -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    isPasswordField: Boolean = false,
    isPasswordVisible: Boolean = false,
    readOnly: Boolean = false,
    testTag: String? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        enabled = enabled,
        readOnly = readOnly,
        singleLine = true,
        shape = AuthPillShape,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = AppTextPrimary),
        placeholder = {
            Text(
                text = placeholder,
                color = AppTextMuted,
                style = MaterialTheme.typography.bodyLarge
            )
        },
        trailingIcon = trailingIcon,
        keyboardOptions = keyboardOptions,
        visualTransformation = if (isPasswordField && !isPasswordVisible) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = AppStrokeStrong,
            unfocusedBorderColor = AppStroke,
            disabledBorderColor = AppStroke,
            focusedTextColor = AppTextPrimary,
            unfocusedTextColor = AppTextPrimary,
            cursorColor = AppTextPrimary,
            focusedPlaceholderColor = AppTextMuted,
            unfocusedPlaceholderColor = AppTextMuted,
            focusedContainerColor = AppSurfaceElevated,
            unfocusedContainerColor = AppSurfaceElevated,
            disabledContainerColor = AppSurfaceElevated
        )
    )
}

@Composable
fun ReadonlyField(
    value: String,
    placeholder: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        shape = AuthPillShape,
        color = AppSurfaceElevated,
        border = BorderStroke(1.dp, AppStroke)
    ) {
        Text(
            text = if (value.isBlank()) placeholder else value,
            color = if (value.isBlank()) AppTextMuted else AppTextPrimary,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 15.dp)
        )
    }
}

@Composable
fun PrimaryActionButton(
    text: String,
    enabled: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        shape = AuthPillShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = AppTextPrimary,
            contentColor = AppBlack,
            disabledContainerColor = AppButtonDisabled,
            disabledContentColor = AppTextSecondary
        ),
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = AppBlack
            )
        } else {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
            )
        }
    }
}

@Composable
fun TelegramLoginButton(
    text: String,
    enabled: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        shape = AuthPillShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = AppTextPrimary,
            contentColor = AppBlack,
            disabledContainerColor = AppButtonDisabled,
            disabledContentColor = AppTextSecondary
        ),
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = AppBlack
            )
        } else {
            Icon(
                painter = painterResource(id = R.drawable.ic_telegram_outline),
                contentDescription = null,
                tint = AppBlack,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
            )
        }
    }
}

@Composable
fun SecondaryOutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: @Composable (() -> Unit)? = null,
    testTag: String? = null
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = AuthPillShape,
        border = BorderStroke(1.dp, AppStrokeStrong),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = AppBlack,
            contentColor = AppTextPrimary,
            disabledContentColor = AppTextSecondary
        ),
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
    ) {
        if (leadingIcon != null) {
            leadingIcon()
        }
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SubtleTextButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    testTag: String? = null
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (enabled) AppTextSecondary else AppTextMuted,
        textDecoration = TextDecoration.Underline,
        modifier = Modifier
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 4.dp)
    )
}

@Composable
fun OrDivider() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = AppStroke
        )
        Text(
            text = LocaleHelper.getString(androidx.compose.ui.platform.LocalContext.current, "auth_or"),
            style = MaterialTheme.typography.bodyMedium,
            color = AppTextMuted
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = AppStroke
        )
    }
}

@Composable
fun FooterLinks(
    onTermsClick: () -> Unit,
    onPolicyClick: () -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = LocaleHelper.getString(androidx.compose.ui.platform.LocalContext.current, "auth_terms"),
            style = MaterialTheme.typography.bodyMedium,
            color = AppTextSecondary,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.clickable(onClick = onTermsClick)
        )
        Text(
            text = LocaleHelper.getString(androidx.compose.ui.platform.LocalContext.current, "auth_privacy_policy"),
            style = MaterialTheme.typography.bodyMedium,
            color = AppTextSecondary,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.clickable(onClick = onPolicyClick)
        )
    }
}

@Composable
fun AgreementHint() {
    Text(
        text = LocaleHelper.getString(androidx.compose.ui.platform.LocalContext.current, "auth_agreement_hint"),
        style = MaterialTheme.typography.bodyMedium,
        color = AppTextSecondary,
        textAlign = TextAlign.Center
    )
}
