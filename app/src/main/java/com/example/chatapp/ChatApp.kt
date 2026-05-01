package com.example.chatapp

import android.app.Application
import android.content.Context

class ChatApp : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LanguageManager.applyLocale(base))
    }
}
