package com.example.chatapp.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.chatapp.LanguageManager
import com.example.chatapp.R

class FreeChatAttachmentWidgetConfigActivity : AppCompatActivity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var selectedBackgroundImageUri: String? = null
    private lateinit var transparencySeekBar: SeekBar
    private lateinit var transparencyValue: TextView
    private lateinit var previewPanel: View
    private lateinit var previewBackgroundImage: ImageView
    private lateinit var previewBackgroundScrim: View
    private lateinit var removeBackgroundButton: View

    private val pickBackgroundImage = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@registerForActivityResult
        persistReadPermission(uri)
        selectedBackgroundImageUri = uri.toString()
        applyBackgroundPreview()
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguageManager.applyLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)

        appWidgetId = savedInstanceState?.getInt(
            KEY_APP_WIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )
            ?: intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID
            )
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID || appWidgetId <= 0) {
            finish()
            return
        }

        setContentView(R.layout.activity_attachment_widget_config)
        window.statusBarColor = Color.TRANSPARENT

        bindViews()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(KEY_APP_WIDGET_ID, appWidgetId)
        super.onSaveInstanceState(outState)
    }

    private fun bindViews() {
        transparencySeekBar = findViewById(R.id.widgetTransparencySeekBar)
        transparencyValue = findViewById(R.id.tvWidgetTransparencyValue)
        previewPanel = findViewById(R.id.widgetConfigPreviewPanel)
        previewBackgroundImage = findViewById(R.id.widgetConfigPreviewBackgroundImage)
        previewBackgroundScrim = findViewById(R.id.widgetConfigPreviewBackgroundScrim)
        removeBackgroundButton = findViewById(R.id.btnWidgetRemoveBackground)

        transparencySeekBar.max = FreeChatAttachmentWidgetStateStore.MAX_TRANSPARENCY_PERCENT -
            FreeChatAttachmentWidgetStateStore.MIN_TRANSPARENCY_PERCENT

        val state = FreeChatAttachmentWidgetStateStore.load(this, appWidgetId)
        val transparency = state.transparencyPercent
        selectedBackgroundImageUri = state.backgroundImageUri
        transparencySeekBar.progress = transparency -
            FreeChatAttachmentWidgetStateStore.MIN_TRANSPARENCY_PERCENT
        applyPreview(transparency)
        applyBackgroundPreview()

        transparencySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                applyPreview(currentTransparency())
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnSave).setOnClickListener { saveAndFinish() }
        findViewById<View>(R.id.btnWidgetChooseBackground).setOnClickListener {
            pickBackgroundImage.launch(arrayOf("image/*"))
        }
        removeBackgroundButton.setOnClickListener {
            selectedBackgroundImageUri = null
            applyBackgroundPreview()
        }
    }

    private fun currentTransparency(): Int {
        return transparencySeekBar.progress +
            FreeChatAttachmentWidgetStateStore.MIN_TRANSPARENCY_PERCENT
    }

    private fun applyPreview(transparencyPercent: Int) {
        transparencyValue.text = getString(R.string.widget_config_transparency_value, transparencyPercent)
        previewPanel.alpha = FreeChatAttachmentWidgetStateStore.alphaForTransparency(transparencyPercent)
    }

    private fun applyBackgroundPreview() {
        val uri = selectedBackgroundImageUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
        if (uri == null) {
            previewBackgroundImage.setImageDrawable(null)
            previewBackgroundImage.visibility = View.GONE
            previewBackgroundScrim.visibility = View.GONE
            removeBackgroundButton.visibility = View.GONE
            return
        }

        previewBackgroundImage.setImageURI(uri)
        previewBackgroundImage.visibility = View.VISIBLE
        previewBackgroundScrim.visibility = View.VISIBLE
        removeBackgroundButton.visibility = View.VISIBLE
    }

    private fun persistReadPermission(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }.onFailure {
            Toast.makeText(
                this,
                R.string.widget_config_background_permission_error,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun saveAndFinish() {
        FreeChatAttachmentWidgetStateStore.saveTransparency(
            context = this,
            appWidgetId = appWidgetId,
            transparencyPercent = currentTransparency()
        )
        val backgroundImageUri = selectedBackgroundImageUri
        if (backgroundImageUri == null) {
            FreeChatAttachmentWidgetStateStore.clearBackgroundImageUri(this, appWidgetId)
        } else {
            FreeChatAttachmentWidgetStateStore.saveBackgroundImageUri(
                context = this,
                appWidgetId = appWidgetId,
                backgroundImageUri = backgroundImageUri
            )
        }

        val appWidgetManager = AppWidgetManager.getInstance(this)
        val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
        appWidgetManager.updateAppWidget(
            appWidgetId,
            FreeChatAttachmentWidgetProvider.buildRemoteViews(this, appWidgetId, options)
        )

        setResult(
            RESULT_OK,
            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        )
        finish()
    }

    private companion object {
        const val KEY_APP_WIDGET_ID = "app_widget_id"
    }
}
