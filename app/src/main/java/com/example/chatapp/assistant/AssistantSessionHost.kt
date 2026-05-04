package com.example.chatapp.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.View
import android.widget.Toast
import com.example.chatapp.FreeChatActivity

class AssistantSessionHost(
    private val session: VoiceInteractionSession,
    override val hostContext: Context,
    private val viewModel: DigitalAssistantViewModel
) : DigitalAssistantHost {
    val overlayView: DigitalAssistantOverlayView =
        DigitalAssistantOverlayView(hostContext, viewModel, this)

    init {
        DigitalAssistantRuntime.registerHost(this)
    }

    override fun requestScreenCapture(onResult: (Result<AssistantAttachment>) -> Unit) {
        viewModel.requestAssistScreenshotAttachment(onResult)
    }

    override fun openFreeChat(handoffToken: String) {
        val intent = Intent(hostContext, FreeChatActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(DigitalAssistantHandoffStore.EXTRA_HANDOFF_ID, handoffToken)
            putExtra(FreeChatActivity.EXTRA_SKIP_BIOMETRIC_ONCE, true)
        }
        hostContext.startActivity(intent)
        closeAssistant(force = false)
    }

    override fun hideForExternalPicker() {
        overlayView.visibility = View.GONE
        session.hide()
    }

    override fun showAfterExternalPicker() {
        overlayView.visibility = View.VISIBLE
        session.show(Bundle.EMPTY, 0)
        overlayView.requestFocus()
    }

    override fun closeAssistant(force: Boolean) {
        if (force) {
            viewModel.cancelAndReset()
        } else {
            viewModel.resetIdleOnly()
        }
        DigitalAssistantRuntime.unregisterHost(this)
        session.finish()
    }

    override fun showMessage(message: String) {
        Toast.makeText(hostContext, message, Toast.LENGTH_SHORT).show()
    }
}
