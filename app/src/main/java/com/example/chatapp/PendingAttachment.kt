package com.example.chatapp

import android.net.Uri

data class PendingAttachment(
    val uri: Uri,
    val displayName: String?,
    val mimeType: String,
    val sizeBytes: Long?,
    val sourceUri: String? = null,
    val isLocalCopy: Boolean = false
)
