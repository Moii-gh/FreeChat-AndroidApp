package com.example.chatapp.ui.auth.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.chatapp.ui.auth.components.AuthTestTags
import com.example.chatapp.ui.auth.components.BirthDateWheelPicker
import com.example.chatapp.ui.auth.components.PrimaryActionButton
import com.example.chatapp.ui.auth.theme.AppBlack
import com.example.chatapp.ui.auth.theme.AppStroke
import com.example.chatapp.ui.auth.theme.AppSurface
import com.example.chatapp.ui.auth.theme.AppSurfaceElevated
import com.example.chatapp.ui.auth.theme.AppTextPrimary
import com.example.chatapp.viewmodel.AuthUiState

@Composable
fun BirthDatePickerScreen(
    state: AuthUiState,
    onBack: () -> Unit,
    onDaySelected: (Int) -> Unit,
    onMonthSelected: (Int) -> Unit,
    onYearSelected: (Int) -> Unit,
    onConfirm: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(AppBlack.copy(alpha = 0.95f), AppSurface)
                )
            )
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 20.dp, top = 26.dp)
                .size(34.dp),
            shape = CircleShape,
            color = Color.White.copy(alpha = 0.08f),
            border = BorderStroke(1.dp, AppStroke)
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Назад",
                    tint = AppTextPrimary
                )
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
            color = AppSurfaceElevated.copy(alpha = 0.98f),
            shape = RoundedCornerShape(34.dp),
            border = BorderStroke(1.dp, AppStroke)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 22.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .height(4.dp)
                        .fillMaxWidth(0.16f)
                        .background(
                            color = Color.White.copy(alpha = 0.32f),
                            shape = RoundedCornerShape(999.dp)
                        )
                )
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = "Выбери свой день рождения",
                    style = MaterialTheme.typography.titleLarge,
                    color = AppTextPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag(AuthTestTags.BIRTH_DATE_PICKER)
                )
                Spacer(modifier = Modifier.height(22.dp))
                BirthDateWheelPicker(
                    selectedDay = state.birthDateDraft.day,
                    selectedMonth = state.birthDateDraft.month,
                    selectedYear = state.birthDateDraft.year,
                    onDaySelected = onDaySelected,
                    onMonthSelected = onMonthSelected,
                    onYearSelected = onYearSelected
                )
                Spacer(modifier = Modifier.height(22.dp))
                PrimaryActionButton(
                    text = "Подтвердить",
                    enabled = true,
                    loading = false,
                    onClick = onConfirm
                )
            }
        }
    }
}
