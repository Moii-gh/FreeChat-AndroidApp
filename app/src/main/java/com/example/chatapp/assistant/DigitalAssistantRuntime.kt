package com.example.chatapp.assistant

import android.content.Context
import java.lang.ref.WeakReference

object DigitalAssistantRuntime {
    @Volatile
    private var viewModel: DigitalAssistantViewModel? = null
    @Volatile
    private var activeHost: WeakReference<DigitalAssistantHost>? = null

    fun get(context: Context): DigitalAssistantViewModel {
        return viewModel ?: synchronized(this) {
            viewModel ?: DigitalAssistantViewModel(context.applicationContext).also {
                viewModel = it
            }
        }
    }

    fun registerHost(host: DigitalAssistantHost) {
        activeHost = WeakReference(host)
    }

    fun unregisterHost(host: DigitalAssistantHost) {
        if (activeHost?.get() === host) {
            activeHost = null
        }
    }

    fun showAfterExternalPicker(): Boolean {
        val host = activeHost?.get() ?: return false
        host.showAfterExternalPicker()
        return true
    }
}
