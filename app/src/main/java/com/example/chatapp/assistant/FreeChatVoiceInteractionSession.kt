package com.example.chatapp.assistant

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.View

class FreeChatVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    private var host: AssistantSessionHost? = null
    private val viewModel = DigitalAssistantRuntime.get(context)

    override fun onCreateContentView(): View {
        return AssistantSessionHost(this, context, viewModel).also {
            host = it
        }.overlayView
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        viewModel.beginAssistSession()
        host?.overlayView?.requestFocus()
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        super.onHandleScreenshot(screenshot)
        viewModel.setAssistScreenshot(screenshot)
    }

    override fun onBackPressed() {
        host?.overlayView?.dispatchKeyEvent(
            android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK)
        ) ?: super.onBackPressed()
    }

    override fun onDestroy() {
        host?.let { DigitalAssistantRuntime.unregisterHost(it) }
        host = null
        super.onDestroy()
    }
}
