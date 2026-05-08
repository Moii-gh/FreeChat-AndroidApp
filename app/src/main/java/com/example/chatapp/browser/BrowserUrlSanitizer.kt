package com.example.chatapp.browser

import android.net.Uri

object BrowserUrlSanitizer {
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

        val uri = runCatching { Uri.parse(withScheme) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return null
        if (uri.host.isNullOrBlank()) return null

        return uri.buildUpon()
            .scheme(scheme)
            .build()
            .toString()
    }

    fun displayHost(url: String?): String {
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        return uri?.host?.removePrefix("www.") ?: url.orEmpty()
    }

    fun isSupported(url: String?): Boolean = normalize(url) != null
}
