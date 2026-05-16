package com.example.chatapp.util

import android.util.Log
import com.example.chatapp.BuildConfig
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

data class SafeLogEntry(
    val timestampMillis: Long,
    val level: String,
    val tag: String,
    val message: String
)

object SafeLog {
    private const val MAX_ENTRIES = 200

    private val bearerRegex = Regex("Bearer\\s+[A-Za-z0-9._~+/=-]+", RegexOption.IGNORE_CASE)
    private val apiKeyRegex = Regex("sk-[A-Za-z0-9_-]{12,}")
    private val sensitiveFieldRegex = Regex(
        "(password|token|api[_-]?key|client[_-]?secret)(\\s*[=:]\\s*)([^\\s,&}]+)",
        RegexOption.IGNORE_CASE
    )
    private val dataImageRegex = Regex("data:image/[^;]+;base64,[A-Za-z0-9+/=\\r\\n]+")
    private val logEntries = ArrayDeque<SafeLogEntry>()
    private val lock = Any()

    fun d(tag: String, message: String) {
        if (!BuildConfig.DEBUG) return
        val safeMessage = record("DEBUG", tag, message)
        Log.d(tag, safeMessage)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (!BuildConfig.DEBUG) return
        val safeMessage = record("WARN", tag, message, throwable)
        if (throwable == null) {
            Log.w(tag, safeMessage)
        } else {
            Log.w(tag, safeMessage, throwable)
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (!BuildConfig.DEBUG) return
        val safeMessage = record("ERROR", tag, message, throwable)
        if (throwable == null) {
            Log.e(tag, safeMessage)
        } else {
            Log.e(tag, safeMessage, throwable)
        }
    }

    fun recentEntries(): List<SafeLogEntry> = synchronized(lock) {
        logEntries.toList()
    }

    fun clearEntries() {
        synchronized(lock) {
            logEntries.clear()
        }
    }

    fun formatEntries(entries: List<SafeLogEntry>): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return entries.joinToString(separator = "\n") { entry ->
            val timestamp = formatter.format(Date(entry.timestampMillis))
            "$timestamp ${entry.level}/${entry.tag}: ${entry.message}"
        }
    }

    private fun record(
        level: String,
        tag: String,
        message: String,
        throwable: Throwable? = null
    ): String {
        val safeMessage = buildSafeMessage(message, throwable)
        synchronized(lock) {
            while (logEntries.size >= MAX_ENTRIES) {
                logEntries.removeFirst()
            }
            logEntries.addLast(
                SafeLogEntry(
                    timestampMillis = System.currentTimeMillis(),
                    level = level,
                    tag = tag,
                    message = safeMessage
                )
            )
        }
        return safeMessage
    }

    private fun buildSafeMessage(message: String, throwable: Throwable?): String {
        val redactedMessage = redact(message)
        if (throwable == null) return redactedMessage

        val throwableMessage = throwable.message
            ?.takeIf { it.isNotBlank() }
            ?.let(::redact)
            ?.let { ": $it" }
            .orEmpty()
        return "$redactedMessage (${throwable.javaClass.simpleName}$throwableMessage)"
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
