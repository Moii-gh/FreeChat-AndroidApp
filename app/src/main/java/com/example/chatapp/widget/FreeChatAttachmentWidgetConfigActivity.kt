package com.example.chatapp.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.chatapp.LanguageManager
import com.example.chatapp.R

class FreeChatAttachmentWidgetConfigActivity : AppCompatActivity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var selectedStyle = WidgetStyle.LiquidGlass

    private lateinit var styleButtons: Map<WidgetStyle, TextView>
    private lateinit var transparencySeekBar: SeekBar
    private lateinit var transparencyValue: TextView
    private lateinit var cornerRadiusSeekBar: SeekBar
    private lateinit var cornerRadiusValue: TextView
    private lateinit var previewPanel: View
    private lateinit var previewBackgroundImage: ImageView
    private lateinit var previewBackgroundScrim: View
    private lateinit var previewInput: View
    private lateinit var previewLogo: ImageView
    private lateinit var previewPlaceholder: TextView
    private lateinit var previewButtons: List<ImageView>

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
        bindStyleButtons()
        bindPreviewViews()
        bindControls()

        val state = FreeChatAttachmentWidgetStateStore.load(this, appWidgetId)
        selectedStyle = state.widgetStyle

        configureSeekBar(
            seekBar = transparencySeekBar,
            valueView = transparencyValue,
            min = FreeChatAttachmentWidgetStateStore.MIN_TRANSPARENCY_PERCENT,
            max = FreeChatAttachmentWidgetStateStore.MAX_TRANSPARENCY_PERCENT,
            initialValue = state.transparencyPercent,
            valueFormatter = ::percentText
        )
        configureSeekBar(
            seekBar = cornerRadiusSeekBar,
            valueView = cornerRadiusValue,
            min = FreeChatAttachmentWidgetStateStore.MIN_CORNER_RADIUS_DP,
            max = FreeChatAttachmentWidgetStateStore.MAX_CORNER_RADIUS_DP,
            initialValue = state.cornerRadiusDp,
            valueFormatter = ::dpText
        )

        styleButtons.forEach { (style, button) ->
            button.setOnClickListener {
                selectedStyle = style
                applyPreview()
            }
        }

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnSave).setOnClickListener { saveAndFinish() }

        applyPreview()
    }

    private fun bindStyleButtons() {
        styleButtons = mapOf(
            WidgetStyle.LiquidGlass to findViewById(R.id.widgetStyleLiquidGlass),
            WidgetStyle.Dark to findViewById(R.id.widgetStyleDark),
            WidgetStyle.Adaptive to findViewById(R.id.widgetStyleAdaptive)
        )
    }

    private fun bindPreviewViews() {
        previewPanel = findViewById(R.id.widgetConfigPreviewPanel)
        previewBackgroundImage = findViewById(R.id.widgetConfigPreviewBackgroundImage)
        previewBackgroundScrim = findViewById(R.id.widgetConfigPreviewBackgroundScrim)
        previewInput = findViewById(R.id.widgetConfigPreviewInput)
        previewLogo = findViewById(R.id.widgetConfigPreviewLogo)
        previewPlaceholder = findViewById(R.id.widgetConfigPreviewPlaceholder)
        previewButtons = listOf(
            findViewById(R.id.widgetConfigPreviewCamera),
            findViewById(R.id.widgetConfigPreviewGallery),
            findViewById(R.id.widgetConfigPreviewDocument),
            findViewById(R.id.widgetConfigPreviewMic)
        )
    }

    private fun bindControls() {
        transparencySeekBar = findViewById(R.id.widgetTransparencySeekBar)
        transparencyValue = findViewById(R.id.tvWidgetTransparencyValue)
        cornerRadiusSeekBar = findViewById(R.id.widgetCornerRadiusSeekBar)
        cornerRadiusValue = findViewById(R.id.tvWidgetCornerRadiusValue)
    }

    private fun configureSeekBar(
        seekBar: SeekBar,
        valueView: TextView,
        min: Int,
        max: Int,
        initialValue: Int,
        valueFormatter: (Int) -> String
    ) {
        seekBar.max = max - min
        seekBar.progress = initialValue.coerceIn(min, max) - min
        valueView.text = valueFormatter(seekBar.currentValue(min))
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                valueView.text = valueFormatter(seekBar.currentValue(min))
                applyPreview()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
    }

    private fun applyPreview() {
        val state = currentState()
        val effectiveStyle = WidgetStyleResources.effectiveStyle(this, state)

        if (effectiveStyle == WidgetStyle.Adaptive) {
            val isDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
            val accentColor = WidgetStyleResources.getSystemAccentColor(this)
            val colors = WidgetStyleResources.resolveAdaptiveColors(accentColor, isDark)

            val panelBackground = WidgetStyleResources.liquidPanelBackground(state.cornerRadiusDp)
            previewPanel.setBackgroundResource(panelBackground)
            previewPanel.backgroundTintList = android.content.res.ColorStateList.valueOf(colors.panelBg)
            previewPanel.alpha = FreeChatAttachmentWidgetStateStore.alphaForTransparency(
                state.transparencyPercent
            )

            previewInput.setBackgroundResource(R.drawable.bg_attachment_widget_active)
            previewInput.backgroundTintList = android.content.res.ColorStateList.valueOf(colors.inputBg)
            previewPlaceholder.setTextColor(colors.textColor)
            previewLogo.setColorFilter(colors.iconTint)

            previewButtons.forEach { button ->
                button.setBackgroundResource(R.drawable.bg_attachment_widget_button)
                button.backgroundTintList = android.content.res.ColorStateList.valueOf(colors.buttonBg)
                button.setColorFilter(colors.iconTint)
            }
        } else {
            val style = WidgetStyleResources.remoteStyle(this, state)
            previewPanel.setBackgroundResource(style.panelBackgroundResId)
            previewPanel.backgroundTintList = null
            previewPanel.alpha = FreeChatAttachmentWidgetStateStore.alphaForTransparency(
                state.transparencyPercent
            )
            previewInput.setBackgroundResource(style.inputBackgroundResId)
            previewInput.backgroundTintList = null
            previewPlaceholder.setTextColor(style.textColor)
            previewLogo.setColorFilter(style.iconTint)
            previewButtons.forEach { button ->
                button.setBackgroundResource(style.buttonBackgroundResId)
                button.backgroundTintList = null
                button.setColorFilter(style.iconTint)
            }
        }

        if (effectiveStyle == WidgetStyle.Dark || effectiveStyle == WidgetStyle.LiquidGlass || effectiveStyle == WidgetStyle.Adaptive) {
            previewBackgroundImage.visibility = View.GONE
            previewBackgroundScrim.visibility = View.GONE
        } else {
            previewBackgroundImage.setImageBitmap(
                WidgetGlassSurfaceRenderer.render(
                    context = this,
                    state = state,
                    size = WidgetSize(
                        minWidth = PREVIEW_WIDTH_DP,
                        minHeight = PREVIEW_HEIGHT_DP,
                        maxWidth = PREVIEW_WIDTH_DP,
                        maxHeight = PREVIEW_HEIGHT_DP
                    ),
                    layoutName = PREVIEW_LAYOUT_NAME
                )
            )
            previewBackgroundImage.visibility = View.VISIBLE
            previewBackgroundScrim.visibility = View.GONE
        }
        updateStyleButtons()
    }

    private fun updateStyleButtons() {
        styleButtons.forEach { (style, button) ->
            val selected = style == selectedStyle
            button.setBackgroundResource(
                if (selected) {
                    R.drawable.bg_widget_config_option_selected
                } else {
                    R.drawable.bg_widget_config_option
                }
            )
            button.setTextColor(if (selected) Color.WHITE else 0xFFA8B1BE.toInt())
        }
    }

    private fun currentState(): FreeChatAttachmentWidgetStateStore.State {
        return FreeChatAttachmentWidgetStateStore.load(this, appWidgetId).copy(
            widgetStyle = selectedStyle,
            transparencyPercent = currentTransparency(),
            cornerRadiusDp = currentCornerRadius()
        )
    }

    private fun currentTransparency(): Int {
        return transparencySeekBar.currentValue(
            FreeChatAttachmentWidgetStateStore.MIN_TRANSPARENCY_PERCENT
        )
    }

    private fun currentCornerRadius(): Int {
        return cornerRadiusSeekBar.currentValue(
            FreeChatAttachmentWidgetStateStore.MIN_CORNER_RADIUS_DP
        )
    }

    private fun percentText(value: Int): String {
        return getString(R.string.widget_config_percent_value, value)
    }

    private fun dpText(value: Int): String {
        return getString(R.string.widget_config_dp_value, value)
    }

    private fun saveAndFinish() {
        FreeChatAttachmentWidgetStateStore.saveAppearance(
            context = this,
            appWidgetId = appWidgetId,
            widgetStyle = selectedStyle,
            transparencyPercent = currentTransparency(),
            cornerRadiusDp = currentCornerRadius()
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

    private fun SeekBar.currentValue(min: Int): Int {
        return progress + min
    }

    private companion object {
        const val KEY_APP_WIDGET_ID = "app_widget_id"
        const val PREVIEW_WIDTH_DP = 320
        const val PREVIEW_HEIGHT_DP = 154
        const val PREVIEW_LAYOUT_NAME = "Preview"
    }
}
