package com.example.chatapp

import android.net.Uri
import java.net.URI

object ChatShareDeepLink {
    private val tokenPattern = Regex("^[A-Za-z0-9_-]{32,128}$")

    fun extractToken(uri: Uri?): String? {
        if (uri == null) return null

        return extractToken(
            scheme = uri.scheme,
            host = uri.host,
            pathSegments = uri.pathSegments
        )
    }

    fun extractToken(rawUri: String?): String? {
        val uri = rawUri
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { URI(it) }.getOrNull() }
            ?: return null
        return extractToken(
            scheme = uri.scheme,
            host = uri.host,
            pathSegments = uri.path.orEmpty().split('/').filter { it.isNotBlank() }
        )
    }

    private fun extractToken(
        scheme: String?,
        host: String?,
        pathSegments: List<String>
    ): String? {
        val normalizedScheme = scheme?.lowercase().orEmpty()
        val normalizedHost = host?.lowercase().orEmpty()
        if (normalizedScheme == "freechat" && normalizedHost == "share" && pathSegments.size == 1) {
            return pathSegments.firstOrNull()?.takeIf(::isValidToken)
        }

        if (normalizedScheme == "http" || normalizedScheme == "https") {
            val expectedPrefix = configuredPublicSharePathPrefix(normalizedScheme, normalizedHost)
                ?: return null
            val expectedSharePath = expectedPrefix + "share"
            if (
                pathSegments.size == expectedSharePath.size + 1 &&
                pathSegments.take(expectedSharePath.size) == expectedSharePath
            ) {
                return pathSegments.getOrNull(expectedSharePath.size)?.takeIf(::isValidToken)
            }
        }

        return null
    }

    private fun isValidToken(token: String): Boolean = tokenPattern.matches(token)

    private fun configuredPublicSharePathPrefix(scheme: String, host: String): List<String>? {
        val configured = runCatching { URI(BuildConfig.CHAT_SHARE_PUBLIC_BASE_URL) }.getOrNull()
            ?: return null
        val configuredHost = configured.host?.lowercase().orEmpty()
        if (configuredHost.isBlank() || host != configuredHost) {
            return null
        }

        val configuredScheme = configured.scheme?.lowercase().orEmpty()
        if (configuredScheme.isNotBlank() && scheme != configuredScheme) {
            return null
        }

        return configured.path.orEmpty().split('/').filter { it.isNotBlank() }
    }
}
