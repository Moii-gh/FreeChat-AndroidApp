package com.example.chatapp.ui.markdown

import java.net.URI

object SafeUrlSanitizer {
    private val trailingPunctuation = Regex("[\\s)>}\\],.!?;:'\"]+$")

    fun normalize(rawUrl: String?): String? {
        val trimmed = rawUrl
            ?.trim()
            ?.replace(trailingPunctuation, "")
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val withScheme = when {
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("www.", ignoreCase = true) -> "https://$trimmed"
            else -> return null
        }

        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "https" && scheme != "http") return null
        if (uri.host.isNullOrBlank()) return null

        return runCatching {
            URI(
                scheme,
                uri.userInfo,
                uri.host,
                uri.port,
                uri.rawPath,
                uri.rawQuery,
                uri.rawFragment
            ).toString()
        }.getOrNull()
    }

    fun isSafe(rawUrl: String?): Boolean = normalize(rawUrl) != null
}
