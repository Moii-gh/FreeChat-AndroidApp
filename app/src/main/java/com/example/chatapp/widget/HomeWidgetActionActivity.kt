package com.example.chatapp.widget

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.example.chatapp.FreeChatActivity
import com.example.chatapp.LocaleHelper
import com.example.chatapp.camera.PremiumCameraActivity
import java.io.File
import java.util.Locale

class HomeWidgetActionActivity : AppCompatActivity() {
    private var launched = false
    private var cameraImageUri: Uri? = null

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val resultUri = result.data
                ?.getStringExtra(PremiumCameraActivity.EXTRA_RESULT_URI)
                ?.let(Uri::parse)
                ?: cameraImageUri
            openChat(attachmentUri = resultUri, focusInput = true)
        } else {
            finish()
        }
    }

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            openChat(attachmentUri = uri, focusInput = true)
        } else {
            finish()
        }
    }

    private val documentLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            openChat(attachmentUri = uri, focusInput = true)
        } else {
            finish()
        }
    }

    private val speechLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spokenText = if (result.resultCode == Activity.RESULT_OK) {
            result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                .orEmpty()
        } else {
            ""
        }
        openChat(prefill = spokenText, focusInput = true)
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
            when (intent.getStringExtra(EXTRA_ACTION)) {
                ACTION_MESSAGE -> openChat(focusInput = true)
                ACTION_CAMERA -> launchCamera()
                ACTION_GALLERY -> galleryLauncher.launch("image/*")
                ACTION_DOCUMENT -> documentLauncher.launch("*/*")
                ACTION_MIC -> launchSpeechRecognizer()
                else -> finish()
            }
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

    private fun launchCamera() {
        val file = File(cacheDir, "home_widget_camera_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            file
        )
        cameraImageUri = uri
        cameraLauncher.launch(PremiumCameraActivity.newIntent(this, uri, file))
    }

    private fun launchSpeechRecognizer() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }
        if (intent.resolveActivity(packageManager) == null) {
            Toast.makeText(
                this,
                LocaleHelper.getString(this, "speech_error_generic"),
                Toast.LENGTH_SHORT
            ).show()
            openChat(focusInput = true)
            return
        }
        speechLauncher.launch(intent)
    }

    private fun openChat(
        attachmentUri: Uri? = null,
        prefill: String = "",
        focusInput: Boolean = false
    ) {
        attachmentUri?.let {
            grantUriPermission(packageName, it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent(this, FreeChatActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (focusInput) putExtra(FreeChatActivity.EXTRA_FOCUS_INPUT, true)
            if (prefill.isNotBlank()) putExtra(FreeChatActivity.EXTRA_PREFILL_INPUT, prefill)
            attachmentUri?.let {
                putExtra(FreeChatActivity.EXTRA_WIDGET_ATTACHMENT_URI, it.toString())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        })
        finish()
    }

    companion object {
        const val EXTRA_ACTION = "com.example.chatapp.widget.EXTRA_ACTION"
        const val ACTION_MESSAGE = "message"
        const val ACTION_CAMERA = "camera"
        const val ACTION_GALLERY = "gallery"
        const val ACTION_DOCUMENT = "document"
        const val ACTION_MIC = "mic"

        private const val KEY_LAUNCHED = "launched"
        private const val KEY_CAMERA_URI = "camera_uri"
    }
}
