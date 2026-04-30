package com.example.chatapp

import android.net.Uri

interface ChatInputHost {
    fun setInputContext(
        title: String,
        iconRes: Int,
        hint: String,
        iconTint: String = "#FFFFFF",
        mode: String? = null
    )

    fun showFilePreview(fileUri: Uri)
}
