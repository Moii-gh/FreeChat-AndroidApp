package com.example.chatapp

import android.app.Application
import android.content.Context
import com.vk.id.VKID

class ChatApp : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LanguageManager.applyLocale(base))
    }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.VKID_CLIENT_ID.isNotBlank() && BuildConfig.VKID_CLIENT_SECRET.isNotBlank()) {
            VKID.init(this)
        }
    }
}
