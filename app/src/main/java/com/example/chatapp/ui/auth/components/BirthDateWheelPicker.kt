package com.example.chatapp.ui.auth.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.chatapp.ui.auth.theme.AppStroke
import com.example.chatapp.ui.auth.theme.AppSurfaceElevated
import com.example.chatapp.ui.auth.theme.AppTextMuted
import com.example.chatapp.ui.auth.theme.AppTextPrimary
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import java.time.LocalDate

@Composable
fun BirthDateWheelPicker(
    selectedDay: Int,
    selectedMonth: Int,
    selectedYear: Int,
    onDaySelected: (Int) -> Unit,
    onMonthSelected: (Int) -> Unit,
    onYearSelected: (Int) -> Unit
) {
    val years = (MIN_BIRTH_YEAR..LocalDate.now().year).toList()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
    ) {
        PickerColumn(
            values = (1..31).toList(),
            selectedValue = selectedDay,
            onValueSelected = onDaySelected,
            valueFormatter = { "%02d".format(it) },
            modifier = Modifier.width(78.dp)
        )
        PickerColumn(
            values = (1..12).toList(),
            selectedValue = selectedMonth,
            onValueSelected = onMonthSelected,
            valueFormatter = { "%02d".format(it) },
            modifier = Modifier.width(78.dp)
        )
        PickerColumn(
            values = years,
            selectedValue = selectedYear,
            onValueSelected = onYearSelected,
            valueFormatter = { it.toString() },
            modifier = Modifier.width(94.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun <T> PickerColumn(
    values: List<T>,
    selectedValue: T,
    onValueSelected: (T) -> Unit,
    valueFormatter: (T) -> String,
    modifier: Modifier = Modifier
) {
    val initialIndex = values.indexOf(selectedValue).coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val rowHeight = 44.dp
    val density = LocalDensity.current
    val rowHeightPx = with(density) { rowHeight.roundToPx() }

    LaunchedEffect(selectedValue, values) {
        val index = values.indexOf(selectedValue).coerceAtLeast(0)
        if (!listState.isScrollInProgress && listState.firstVisibleItemIndex != index) {
            listState.scrollToItem(index)
        }
    }

    LaunchedEffect(listState, values) {
        snapshotFlow { listState.isScrollInProgress }
            .filter { !it }
            .map {
                val target = listState.firstVisibleItemIndex +
                    if (listState.firstVisibleItemScrollOffset > rowHeightPx / 2) 1 else 0
                target.coerceIn(0, values.lastIndex)
            }
            .distinctUntilChanged()
            .collect { index ->
                listState.animateScrollToItem(index)
                onValueSelected(values[index])
            }
    }

    Box(
        modifier = modifier.height(204.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp),
            shape = RoundedCornerShape(20.dp),
            color = AppSurfaceElevated.copy(alpha = 0.92f),
            border = BorderStroke(1.dp, AppStroke)
        ) {}

        LazyColumn(
            state = listState,
            flingBehavior = rememberSnapFlingBehavior(lazyListState = listState),
            modifier = Modifier.fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(vertical = 80.dp)
        ) {
            items(values.size) { index ->
                val item = values[index]
                val isSelected = item == selectedValue
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(rowHeight),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = valueFormatter(item),
                        color = if (isSelected) AppTextPrimary else AppTextMuted,
                        textAlign = TextAlign.Center,
                        style = if (isSelected) {
                            MaterialTheme.typography.bodyLarge
                        } else {
                            MaterialTheme.typography.bodyMedium
                        }
                    )
                }
            }
        }
    }
}

private const val MIN_BIRTH_YEAR = 1950
