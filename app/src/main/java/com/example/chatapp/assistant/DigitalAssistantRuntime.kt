package com.example.chatapp.assistant

import android.content.Context

object DigitalAssistantRuntime {
    @Volatile
    private var viewModel: DigitalAssistantViewModel? = null

    fun get(context: Context): DigitalAssistantViewModel {
        return viewModel ?: synchronized(this) {
            viewModel ?: DigitalAssistantViewModel(context.applicationContext).also {
                viewModel = it
            }
        }
    }
}

