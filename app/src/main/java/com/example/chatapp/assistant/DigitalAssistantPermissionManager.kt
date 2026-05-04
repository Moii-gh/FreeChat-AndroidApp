package com.example.chatapp.assistant

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.example.chatapp.LocaleHelper

class DigitalAssistantPermissionManager(private val context: Context) {

    enum class Status {
        READY,
        NEEDS_PERMISSION,
        CAPTURE_INACTIVE
    }

    fun canDrawOverlays(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun isAssistantSettingsAvailable(): Boolean =
        voiceInputSettingsIntent().resolveActivity(context.packageManager) != null

    fun voiceInputSettingsIntent(): Intent =
        Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)

    fun overlaySettingsIntent(): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )

    fun isFreeChatDefaultAssistant(): Boolean {
        val expectedPackage = context.packageName
        return runCatching {
            val assistant = Settings.Secure.getString(context.contentResolver, "assistant").orEmpty()
            val voiceInteraction = Settings.Secure.getString(
                context.contentResolver,
                "voice_interaction_service"
            ).orEmpty()
            assistant.contains(expectedPackage) || voiceInteraction.contains(expectedPackage)
        }.getOrDefault(false)
    }

    fun status(settingsStore: DigitalAssistantSettingsStore): Status {
        if (!settingsStore.isEnabled || !isFreeChatDefaultAssistant()) {
            return Status.NEEDS_PERMISSION
        }
        return if (canDrawOverlays() && hasNotificationPermission()) {
            Status.CAPTURE_INACTIVE
        } else {
            Status.NEEDS_PERMISSION
        }
    }

    fun statusLabel(status: Status): String = when (status) {
        Status.READY -> LocaleHelper.getString(context, "digital_assistant_ready")
        Status.NEEDS_PERMISSION -> LocaleHelper.getString(context, "digital_assistant_permission_needed")
        Status.CAPTURE_INACTIVE -> LocaleHelper.getString(context, "digital_assistant_capture_inactive")
    }

    fun assistantComponentName(): ComponentName =
        ComponentName(context, FreeChatVoiceInteractionService::class.java)
}

