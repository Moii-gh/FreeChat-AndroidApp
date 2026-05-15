package com.example.chatapp

import android.content.Context

object AccountExitLimitMessages {
    fun title(context: Context): String =
        LocaleHelper.getString(context, "logout_limit_title")

    fun body(context: Context, remainingHours: Long): String =
        if (remainingHours < 1L) {
            LocaleHelper.getString(context, "logout_limit_message_less_than_hour")
        } else {
            LocaleHelper.formatString(context, "logout_limit_message_hours", remainingHours)
        }

    fun fullMessage(context: Context, remainingHours: Long): String =
        "${title(context)}. ${body(context, remainingHours)}"
}

