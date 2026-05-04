package com.example.chatapp.util

import android.util.Log
import com.example.chatapp.BuildConfig

object SafeLog {
    private val bearerRegex = Regex("Bearer\\s+[A-Za-z0-9._~+/=-]+", RegexOption.IGNORE_CASE)
    private val apiKeyRegex = Regex("sk-[A-Za-z0-9_-]{12,}")
    private val sensitiveFieldRegex = Regex(
        "(password|token|api[_-]?key|client[_-]?secret)(\\s*[=:]\\s*)([^\\s,&}]+)",
        RegexOption.IGNORE_CASE
    )
    private val dataImageRegex = Regex("data:image/[^;]+;base64,[A-Za-z0-9+/=\\r\\n]+")

    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(tag, redact(message))
        }
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (!BuildConfig.DEBUG) return
        if (throwable == null) {
            Log.w(tag, redact(message))
        } else {
            Log.w(tag, redact(message), throwable)
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (!BuildConfig.DEBUG) return
        if (throwable == null) {
            Log.e(tag, redact(message))
        } else {
            Log.e(tag, redact(message), throwable)
        }
    }

    private fun redact(message: String): String {
        return message
            .replace(bearerRegex, "Bearer [redacted]")
            .replace(apiKeyRegex, "sk-[redacted]")
            .replace(sensitiveFieldRegex) { match ->
                "${match.groupValues[1]}${match.groupValues[2]}[redacted]"
            }
            .replace(dataImageRegex, "data:image/[redacted]")
    }
}
