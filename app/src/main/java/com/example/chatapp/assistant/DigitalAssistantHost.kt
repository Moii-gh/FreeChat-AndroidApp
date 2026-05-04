package com.example.chatapp.assistant

import android.content.Context

interface DigitalAssistantHost {
    val hostContext: Context
    fun requestScreenCapture(onResult: (Result<AssistantAttachment>) -> Unit)
    fun openFreeChat(handoffToken: String)
    fun hideForExternalPicker()
    fun showAfterExternalPicker()
    fun closeAssistant(force: Boolean = false)
    fun showMessage(message: String)
}
