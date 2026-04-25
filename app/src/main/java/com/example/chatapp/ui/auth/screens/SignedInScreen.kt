package com.example.chatapp.ui.auth.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.chatapp.LocaleHelper
import com.example.chatapp.ui.auth.components.AuthScreenLayout
import com.example.chatapp.ui.auth.components.AuthTestTags
import com.example.chatapp.ui.auth.theme.AppStroke
import com.example.chatapp.ui.auth.theme.AppSuccess
import com.example.chatapp.ui.auth.theme.AppSurfaceElevated
import com.example.chatapp.ui.auth.theme.AppTextPrimary
import com.example.chatapp.ui.auth.theme.AppTextSecondary

@Composable
fun SignedInScreen(
    email: String
) {
    val context = LocalContext.current
    AuthScreenLayout(
        title = LocaleHelper.getString(context, "auth_signed_in_title"),
        subtitle = LocaleHelper.getString(context, "auth_signed_in_subtitle")
    ) {
        Surface(
            modifier = Modifier.testTag(AuthTestTags.SIGNED_IN_SCREEN),
            shape = RoundedCornerShape(20.dp),
            color = AppSurfaceElevated,
            border = BorderStroke(1.dp, AppStroke)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = AppSuccess,
                    modifier = Modifier.size(42.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = email,
                    style = MaterialTheme.typography.titleLarge,
                    color = AppTextPrimary,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = LocaleHelper.getString(context, "auth_signed_in_desc"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTextSecondary,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
