package com.example.chatapp.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.chatapp.LanguageManager
import com.example.chatapp.R

class FreeChatAttachmentWidgetConfigActivity : AppCompatActivity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private lateinit var transparencySeekBar: SeekBar
    private lateinit var transparencyValue: TextView
    private lateinit var previewPanel: View

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

        transparencySeekBar.max = FreeChatAttachmentWidgetStateStore.MAX_TRANSPARENCY_PERCENT -
            FreeChatAttachmentWidgetStateStore.MIN_TRANSPARENCY_PERCENT

        val transparency = FreeChatAttachmentWidgetStateStore
            .load(this, appWidgetId)
            .transparencyPercent
        transparencySeekBar.progress = transparency -
            FreeChatAttachmentWidgetStateStore.MIN_TRANSPARENCY_PERCENT
        applyPreview(transparency)

        transparencySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                applyPreview(currentTransparency())
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnSave).setOnClickListener { saveAndFinish() }
    }

    private fun currentTransparency(): Int {
        return transparencySeekBar.progress +
            FreeChatAttachmentWidgetStateStore.MIN_TRANSPARENCY_PERCENT
    }

    private fun applyPreview(transparencyPercent: Int) {
        transparencyValue.text = getString(R.string.widget_config_transparency_value, transparencyPercent)
        previewPanel.alpha = FreeChatAttachmentWidgetStateStore.alphaForTransparency(transparencyPercent)
    }

    private fun saveAndFinish() {
        FreeChatAttachmentWidgetStateStore.saveTransparency(
            context = this,
            appWidgetId = appWidgetId,
            transparencyPercent = currentTransparency()
        )

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
