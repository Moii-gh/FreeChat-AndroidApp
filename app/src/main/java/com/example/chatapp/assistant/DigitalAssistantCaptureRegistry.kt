package com.example.chatapp.assistant

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

object DigitalAssistantCaptureRegistry {
    private var callback: ((Result<AssistantAttachment>) -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun request(context: Context, onResult: (Result<AssistantAttachment>) -> Unit) {
        callback = onResult
        val intent = Intent(context, DigitalAssistantCapturePermissionActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun dispatch(result: Result<AssistantAttachment>) {
        val pending = callback
        callback = null
        if (pending != null) {
            mainHandler.post { pending.invoke(result) }
        }
    }
}
