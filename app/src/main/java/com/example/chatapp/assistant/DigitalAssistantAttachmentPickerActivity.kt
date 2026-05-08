package com.example.chatapp.assistant

import android.net.Uri
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.example.chatapp.camera.PremiumCameraActivity
import com.example.chatapp.ChatAttachmentHelper
import com.example.chatapp.LocaleHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class DigitalAssistantAttachmentPickerActivity : AppCompatActivity() {
    private var cameraImageUri: Uri? = null
    private var launched = false

    private val premiumCameraLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val resultUri = result.data
                ?.getStringExtra(PremiumCameraActivity.EXTRA_RESULT_URI)
                ?.let(Uri::parse)
                ?: cameraImageUri
            handlePickedUri(resultUri)
        } else {
            finishAndRestoreAssistant()
        }
    }

    private val imageMultiPickerLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        handlePickedUris(uris)
    }

    private val fileMultiPickerLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        handlePickedUris(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        overridePendingTransition(0, 0)
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            launched = savedInstanceState.getBoolean(KEY_LAUNCHED, false)
            cameraImageUri = savedInstanceState.getParcelable(KEY_CAMERA_URI)
        }
        if (!launched) {
            launched = true
            launchPicker(intent.getStringExtra(EXTRA_SOURCE).orEmpty())
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_LAUNCHED, launched)
        outState.putParcelable(KEY_CAMERA_URI, cameraImageUri)
        super.onSaveInstanceState(outState)
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    private fun launchPicker(source: String) {
        when (source) {
            SOURCE_CAMERA -> launchCamera()
            SOURCE_PHOTO -> imageMultiPickerLauncher.launch("image/*")
            SOURCE_FILES -> fileMultiPickerLauncher.launch("*/*")
            else -> {
                finishAndRestoreAssistant()
            }
        }
    }

    private fun launchCamera() {
        val file = File(cacheDir, "assistant_camera_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            file
        )
        cameraImageUri = uri
        premiumCameraLauncher.launch(PremiumCameraActivity.newIntent(this, uri, file))
    }

    private fun handlePickedUri(uri: Uri?) {
        if (uri == null) {
            finishAndRestoreAssistant()
            return
        }
        handlePickedUris(listOf(uri))
    }

    private fun handlePickedUris(uris: List<Uri>) {
        if (uris.isEmpty()) {
            finishAndRestoreAssistant()
            return
        }

        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val helper = ChatAttachmentHelper(this@DigitalAssistantAttachmentPickerActivity)
                    uris.mapIndexed { index, uri ->
                        val payload = helper.buildAttachmentPayload(uri)
                            ?: error(LocaleHelper.getString(this@DigitalAssistantAttachmentPickerActivity, "attachment_read_error"))
                        AssistantAttachment(
                            mimeType = payload.mimeType,
                            fileName = payload.fileName ?: "assistant_attachment_${System.currentTimeMillis()}_$index",
                            base64Data = payload.base64Data.orEmpty(),
                            cacheFilePath = payload.fileUri,
                            attachmentContext = payload.attachmentContext
                        )
                    }
                }
            }.onSuccess { attachments ->
                DigitalAssistantRuntime.get(this@DigitalAssistantAttachmentPickerActivity)
                    .setScreenAttachments(attachments)
            }.onFailure { error ->
                Toast.makeText(
                    this@DigitalAssistantAttachmentPickerActivity,
                    error.message ?: LocaleHelper.getString(this@DigitalAssistantAttachmentPickerActivity, "attachment_read_error"),
                    Toast.LENGTH_SHORT
                ).show()
            }
            finishAndRestoreAssistant()
        }
    }

    private fun finishAndRestoreAssistant() {
        overridePendingTransition(0, 0)
        finish()
        Handler(Looper.getMainLooper()).postDelayed(
            { restoreAssistantOverlay() },
            RESTORE_DELAY_MS
        )
    }

    private fun restoreAssistantOverlay() {
        if (DigitalAssistantRuntime.showAfterExternalPicker()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) return
        val intent = Intent(this, DigitalAssistantOverlayService::class.java).apply {
            action = DigitalAssistantOverlayService.ACTION_SHOW_OVERLAY
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    companion object {
        const val EXTRA_SOURCE = "source"
        const val SOURCE_CAMERA = "camera"
        const val SOURCE_PHOTO = "photo"
        const val SOURCE_FILES = "files"

        private const val KEY_LAUNCHED = "launched"
        private const val KEY_CAMERA_URI = "camera_uri"
        private const val RESTORE_DELAY_MS = 120L
    }
}
