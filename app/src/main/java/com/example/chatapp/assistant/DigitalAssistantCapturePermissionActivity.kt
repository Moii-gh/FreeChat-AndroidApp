package com.example.chatapp.assistant

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.example.chatapp.LocaleHelper

class DigitalAssistantCapturePermissionActivity : ComponentActivity() {
    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val captureData = result.data
        if (result.resultCode == RESULT_OK && captureData != null) {
            DigitalAssistantScreenCaptureService.start(
                context = this,
                resultCode = result.resultCode,
                data = captureData
            )
        } else {
            DigitalAssistantCaptureRegistry.dispatch(
                Result.failure(IllegalStateException(LocaleHelper.getString(this, "digital_assistant_capture_failed")))
            )
        }
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        captureLauncher.launch(projectionManager.createScreenCaptureIntent())
    }
}
