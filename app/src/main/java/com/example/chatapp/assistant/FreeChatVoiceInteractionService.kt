package com.example.chatapp.assistant

import android.service.voice.VoiceInteractionService

class FreeChatVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        setDisabledShowContext(0)
    }
}
