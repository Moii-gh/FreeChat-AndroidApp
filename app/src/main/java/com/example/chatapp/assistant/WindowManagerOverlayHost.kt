package com.example.chatapp.assistant

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import com.example.chatapp.FreeChatActivity
import com.example.chatapp.LocaleHelper

class WindowManagerOverlayHost(
    override val hostContext: Context,
    private val viewModel: DigitalAssistantViewModel,
    private val onDetached: () -> Unit
) : DigitalAssistantHost {
    private val windowManager = hostContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: DigitalAssistantOverlayView? = null

    init {
        DigitalAssistantRuntime.registerHost(this)
    }

    fun attach() {
        if (overlayView != null) return
        val view = DigitalAssistantOverlayView(hostContext, viewModel, this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        overlayView = view
        windowManager.addView(view, params)
    }

    fun detach() {
        overlayView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        overlayView = null
    }

    override fun requestScreenCapture(onResult: (Result<AssistantAttachment>) -> Unit) {
        onResult(
            Result.failure(
                IllegalStateException(
                    LocaleHelper.getString(hostContext, "digital_assistant_assist_screenshot_unavailable")
                )
            )
        )
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
        detach()
    }

    override fun showAfterExternalPicker() {
        attach()
    }

    override fun closeAssistant(force: Boolean) {
        if (force) {
            viewModel.cancelAndReset()
        } else {
            viewModel.resetIdleOnly()
        }
        detach()
        DigitalAssistantRuntime.unregisterHost(this)
        onDetached()
    }

    override fun showMessage(message: String) {
        Toast.makeText(hostContext, message, Toast.LENGTH_SHORT).show()
    }
}
