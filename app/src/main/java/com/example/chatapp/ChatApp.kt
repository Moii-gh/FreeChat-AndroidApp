package com.example.chatapp

import android.app.Application
import android.content.Context
import com.example.chatapp.assistant.DigitalAssistantHandoffStore
import com.example.chatapp.ui.LaunchLogoAnimator
import com.example.chatapp.util.SafeLog
import com.vk.id.VKID

class ChatApp : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LanguageManager.applyLocale(base))
    }

    override fun onCreate() {
        super.onCreate()
        ChatResponseNotifications.ensureChannel(this)
        ChatGenerationManager.recoverInterruptedGenerations(this)
        LaunchLogoAnimator.registerLifecycleCallbacks(this)
        DigitalAssistantHandoffStore.cleanupOldFiles(this)
        if (BuildConfig.VKID_NATIVE_LOGIN_ENABLED) {
            runCatching {
                VKID.init(this)
            }.onFailure { error ->
                SafeLog.w("ChatApp", "VK ID initialization failed", error)
            }
        }
    }
}
