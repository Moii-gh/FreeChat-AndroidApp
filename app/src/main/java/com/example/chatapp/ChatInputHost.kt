package com.example.chatapp

import android.net.Uri

interface ChatInputHost {
    fun setInputContext(
        title: String,
        iconRes: Int,
        hint: String,
        iconTint: String = "#34C759",
        mode: String? = null
    )

    fun showFilePreview(fileUri: Uri)
}
