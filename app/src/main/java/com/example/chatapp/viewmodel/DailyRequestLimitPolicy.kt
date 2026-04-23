package com.example.chatapp.viewmodel

internal const val DAILY_REQUEST_LIMIT = 20

internal fun nextDayRequestsLeft(
    currentLeft: Int,
    dailyRequestLimit: Int = DAILY_REQUEST_LIMIT
): Int = maxOf(currentLeft, dailyRequestLimit)
