package com.example.chatapp

import android.net.Uri

object ChatShareDeepLink {
    fun extractToken(uri: Uri?): String? {
        if (uri == null) return null

        val scheme = uri.scheme?.lowercase().orEmpty()
        if (scheme == "freechat" && uri.host == "share") {
            return uri.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() }
        }

        if ((scheme == "http" || scheme == "https") && uri.pathSegments.firstOrNull() == "share") {
            return uri.pathSegments.getOrNull(1)?.takeIf { it.isNotBlank() }
        }

        return null
    }
}
