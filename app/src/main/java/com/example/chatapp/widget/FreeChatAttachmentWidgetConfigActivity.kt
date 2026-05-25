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
    private var selectedStyle = WidgetStyle.LiquidGlass
    private var noiseTexture = FreeChatAttachmentWidgetStateStore.DEFAULT_NOISE_TEXTURE
    private var dynamicReflections = FreeChatAttachmentWidgetStateStore.DEFAULT_DYNAMIC_REFLECTIONS
    private var matchWallpaperColors = FreeChatAttachmentWidgetStateStore.DEFAULT_MATCH_WALLPAPER_COLORS

    private lateinit var styleButtons: Map<WidgetStyle, TextView>
    private lateinit var blurSeekBar: SeekBar
    private lateinit var blurValue: TextView
    private lateinit var transparencySeekBar: SeekBar
    private lateinit var transparencyValue: TextView
    private lateinit var borderGlowSeekBar: SeekBar
    private lateinit var borderGlowValue: TextView
    private lateinit var cornerRadiusSeekBar: SeekBar
    private lateinit var cornerRadiusValue: TextView
    private lateinit var glassDepthSeekBar: SeekBar
    private lateinit var glassDepthValue: TextView
    private lateinit var noiseToggle: TextView
    private lateinit var dynamicReflectionsToggle: TextView
    private lateinit var matchWallpaperToggle: TextView
    private lateinit var previewPanel: View
    private lateinit var previewBackgroundImage: ImageView
    private lateinit var previewBackgroundScrim: View
    private lateinit var previewInput: View
    private lateinit var previewLogo: ImageView
    private lateinit var previewPlaceholder: TextView
    private lateinit var previewButtons: List<ImageView>
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
        bindStyleButtons()
        bindPreviewViews()
        bindControls()

        val state = FreeChatAttachmentWidgetStateStore.load(this, appWidgetId)
        selectedStyle = state.widgetStyle
        selectedBackgroundImageUri = state.backgroundImageUri
        noiseTexture = state.noiseTexture
        dynamicReflections = state.dynamicReflections
        matchWallpaperColors = state.matchWallpaperColors

        configureSeekBar(
            seekBar = blurSeekBar,
            valueView = blurValue,
            min = FreeChatAttachmentWidgetStateStore.MIN_BLUR_INTENSITY,
            max = FreeChatAttachmentWidgetStateStore.MAX_BLUR_INTENSITY,
            initialValue = state.blurIntensity,
            valueFormatter = ::percentText
        )
        configureSeekBar(
            seekBar = transparencySeekBar,
            valueView = transparencyValue,
            min = FreeChatAttachmentWidgetStateStore.MIN_TRANSPARENCY_PERCENT,
            max = FreeChatAttachmentWidgetStateStore.MAX_TRANSPARENCY_PERCENT,
            initialValue = state.transparencyPercent,
            valueFormatter = ::percentText
        )
        configureSeekBar(
            seekBar = borderGlowSeekBar,
            valueView = borderGlowValue,
            min = FreeChatAttachmentWidgetStateStore.MIN_BORDER_GLOW,
            max = FreeChatAttachmentWidgetStateStore.MAX_BORDER_GLOW,
            initialValue = state.borderGlow,
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
        configureSeekBar(
            seekBar = glassDepthSeekBar,
            valueView = glassDepthValue,
            min = FreeChatAttachmentWidgetStateStore.MIN_GLASS_DEPTH,
            max = FreeChatAttachmentWidgetStateStore.MAX_GLASS_DEPTH,
            initialValue = state.glassDepth,
            valueFormatter = ::percentText
        )

        styleButtons.forEach { (style, button) ->
            button.setOnClickListener {
                selectedStyle = style
                applyPreview()
            }
        }
        noiseToggle.setOnClickListener {
            noiseTexture = !noiseTexture
            applyPreview()
        }
        dynamicReflectionsToggle.setOnClickListener {
            dynamicReflections = !dynamicReflections
            applyPreview()
        }
        matchWallpaperToggle.setOnClickListener {
            matchWallpaperColors = !matchWallpaperColors
            applyPreview()
        }

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnSave).setOnClickListener { saveAndFinish() }
        findViewById<View>(R.id.btnWidgetChooseBackground).setOnClickListener {
            pickBackgroundImage.launch(arrayOf("image/*"))
        }
        removeBackgroundButton.setOnClickListener {
            selectedBackgroundImageUri = null
            applyBackgroundPreview()
        }

        applyBackgroundPreview()
    }

    private fun bindStyleButtons() {
        styleButtons = mapOf(
            WidgetStyle.LiquidGlass to findViewById(R.id.widgetStyleLiquidGlass),
            WidgetStyle.DarkMatte to findViewById(R.id.widgetStyleDarkMatte),
            WidgetStyle.Solid to findViewById(R.id.widgetStyleSolid),
            WidgetStyle.AdaptiveSystem to findViewById(R.id.widgetStyleAdaptiveSystem)
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
        removeBackgroundButton = findViewById(R.id.btnWidgetRemoveBackground)
    }

    private fun bindControls() {
        blurSeekBar = findViewById(R.id.widgetBlurSeekBar)
        blurValue = findViewById(R.id.tvWidgetBlurValue)
        transparencySeekBar = findViewById(R.id.widgetTransparencySeekBar)
        transparencyValue = findViewById(R.id.tvWidgetTransparencyValue)
        borderGlowSeekBar = findViewById(R.id.widgetBorderGlowSeekBar)
        borderGlowValue = findViewById(R.id.tvWidgetBorderGlowValue)
        cornerRadiusSeekBar = findViewById(R.id.widgetCornerRadiusSeekBar)
        cornerRadiusValue = findViewById(R.id.tvWidgetCornerRadiusValue)
        glassDepthSeekBar = findViewById(R.id.widgetGlassDepthSeekBar)
        glassDepthValue = findViewById(R.id.tvWidgetGlassDepthValue)
        noiseToggle = findViewById(R.id.toggleWidgetNoiseTexture)
        dynamicReflectionsToggle = findViewById(R.id.toggleWidgetDynamicReflections)
        matchWallpaperToggle = findViewById(R.id.toggleWidgetMatchWallpaper)
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

    private fun applyBackgroundPreview() {
        removeBackgroundButton.visibility = if (selectedBackgroundImageUri == null) {
            View.GONE
        } else {
            View.VISIBLE
        }
        applyPreview()
    }

    private fun applyPreview() {
        val state = currentState()
        val style = WidgetStyleResources.remoteStyle(this, state)
        previewPanel.setBackgroundResource(style.panelBackgroundResId)
        previewPanel.alpha = FreeChatAttachmentWidgetStateStore.alphaForTransparency(
            state.transparencyPercent
        )
        previewInput.setBackgroundResource(style.inputBackgroundResId)
        previewPlaceholder.setTextColor(style.textColor)
        previewLogo.setColorFilter(style.iconTint)
        previewButtons.forEach { button ->
            button.setBackgroundResource(style.buttonBackgroundResId)
            button.setColorFilter(style.iconTint)
        }
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
        updateStyleButtons()
        updateToggles()
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

    private fun updateToggles() {
        updateToggle(
            view = noiseToggle,
            labelResId = R.string.widget_config_noise_texture,
            enabled = noiseTexture
        )
        updateToggle(
            view = dynamicReflectionsToggle,
            labelResId = R.string.widget_config_dynamic_reflections,
            enabled = dynamicReflections
        )
        updateToggle(
            view = matchWallpaperToggle,
            labelResId = R.string.widget_config_match_wallpaper,
            enabled = matchWallpaperColors
        )
    }

    private fun updateToggle(view: TextView, labelResId: Int, enabled: Boolean) {
        view.setBackgroundResource(
            if (enabled) {
                R.drawable.bg_widget_config_toggle_on
            } else {
                R.drawable.bg_widget_config_toggle_off
            }
        )
        val state = getString(
            if (enabled) R.string.widget_config_toggle_on else R.string.widget_config_toggle_off
        )
        view.text = "${getString(labelResId)}: $state"
        view.setTextColor(if (enabled) Color.WHITE else 0xFFA8B1BE.toInt())
    }

    private fun currentState(): FreeChatAttachmentWidgetStateStore.State {
        return FreeChatAttachmentWidgetStateStore.load(this, appWidgetId).copy(
            widgetStyle = selectedStyle,
            blurIntensity = currentBlurIntensity(),
            transparencyPercent = currentTransparency(),
            borderGlow = currentBorderGlow(),
            noiseTexture = noiseTexture,
            cornerRadiusDp = currentCornerRadius(),
            dynamicReflections = dynamicReflections,
            matchWallpaperColors = matchWallpaperColors,
            glassDepth = currentGlassDepth(),
            backgroundImageUri = selectedBackgroundImageUri
        )
    }

    private fun currentBlurIntensity(): Int {
        return blurSeekBar.currentValue(FreeChatAttachmentWidgetStateStore.MIN_BLUR_INTENSITY)
    }

    private fun currentTransparency(): Int {
        return transparencySeekBar.currentValue(
            FreeChatAttachmentWidgetStateStore.MIN_TRANSPARENCY_PERCENT
        )
    }

    private fun currentBorderGlow(): Int {
        return borderGlowSeekBar.currentValue(FreeChatAttachmentWidgetStateStore.MIN_BORDER_GLOW)
    }

    private fun currentCornerRadius(): Int {
        return cornerRadiusSeekBar.currentValue(
            FreeChatAttachmentWidgetStateStore.MIN_CORNER_RADIUS_DP
        )
    }

    private fun currentGlassDepth(): Int {
        return glassDepthSeekBar.currentValue(FreeChatAttachmentWidgetStateStore.MIN_GLASS_DEPTH)
    }

    private fun percentText(value: Int): String {
        return getString(R.string.widget_config_percent_value, value)
    }

    private fun dpText(value: Int): String {
        return getString(R.string.widget_config_dp_value, value)
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
        FreeChatAttachmentWidgetStateStore.saveAppearance(
            context = this,
            appWidgetId = appWidgetId,
            widgetStyle = selectedStyle,
            blurIntensity = currentBlurIntensity(),
            transparencyPercent = currentTransparency(),
            borderGlow = currentBorderGlow(),
            noiseTexture = noiseTexture,
            cornerRadiusDp = currentCornerRadius(),
            dynamicReflections = dynamicReflections,
            matchWallpaperColors = matchWallpaperColors,
            glassDepth = currentGlassDepth()
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
