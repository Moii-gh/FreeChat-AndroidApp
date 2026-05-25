package com.example.chatapp.widget

import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.chatapp.LanguageManager
import com.example.chatapp.R

class FreeChatAttachmentWidgetConfigActivity : AppCompatActivity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var selectedStyle = WidgetStyle.LiquidGlass
    private var selectedSize = "2x4"

    private lateinit var styleButtons: Map<WidgetStyle, TextView>
    private lateinit var styleSelectedBgs: Map<WidgetStyle, View>
    private lateinit var sizeButtons: Map<String, TextView>
    private lateinit var sizeSelectedBgs: Map<String, View>
    private var isFirstLoad = true
    private val activeAnimators = mutableMapOf<Any, ValueAnimator>()

    private val sizeHeights = mapOf(
        "1x1" to 60,
        "1x2" to 90,
        "1x3" to 120,
        "1x4" to 150,
        "2x1" to 60,
        "2x2" to 90,
        "2x3" to 120,
        "2x4" to 150
    )

    private val allInputs by lazy {
        listOfNotNull(
            findViewById<View>(R.id.widgetConfigPreviewInput),
            findViewById<View>(R.id.widgetConfigPreviewInput2x2),
            findViewById<View>(R.id.widgetConfigPreviewInput2x3)
        )
    }

    private val allPlaceholders by lazy {
        listOfNotNull(
            findViewById<TextView>(R.id.widgetConfigPreviewPlaceholder),
            findViewById<TextView>(R.id.widgetConfigPreviewPlaceholder2x2),
            findViewById<TextView>(R.id.widgetConfigPreviewPlaceholder2x3)
        )
    }

    private val allLogos by lazy {
        listOfNotNull(
            findViewById<ImageView>(R.id.widgetConfigPreviewLogo),
            findViewById<ImageView>(R.id.widgetConfigPreviewLogo2x2),
            findViewById<ImageView>(R.id.widgetConfigPreviewLogo2x3)
        )
    }

    private val allButtons by lazy {
        listOfNotNull(
            findViewById<ImageView>(R.id.widgetConfigPreviewCamera),
            findViewById<ImageView>(R.id.widgetConfigPreviewGallery),
            findViewById<ImageView>(R.id.widgetConfigPreviewDocument),
            findViewById<ImageView>(R.id.widgetConfigPreviewMic),
            findViewById<ImageView>(R.id.widgetConfigPreviewCamera2x2),
            findViewById<ImageView>(R.id.widgetConfigPreviewMic2x2),
            findViewById<ImageView>(R.id.widgetConfigPreviewCamera2x3),
            findViewById<ImageView>(R.id.widgetConfigPreviewGallery2x3),
            findViewById<ImageView>(R.id.widgetConfigPreviewMic2x3)
        )
    }

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
        bindSizeButtons()
        bindPreviewViews()
        bindControls()

        // Set initial preview size (default is 2x4 layout with 150dp height and MATCH_PARENT width)
        val params = previewPanel.layoutParams
        params.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
        params.height = (150 * resources.displayMetrics.density).toInt()
        previewPanel.layoutParams = params

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

        sizeButtons.forEach { (size, button) ->
            button.setOnClickListener {
                updateSizeSelection(size)
            }
        }

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnSave).setOnClickListener { saveAndFinish() }

        updateSizeButtons()
        applyPreview()
    }

    private fun bindStyleButtons() {
        styleButtons = mapOf(
            WidgetStyle.LiquidGlass to findViewById(R.id.widgetStyleLiquidGlass),
            WidgetStyle.Dark to findViewById(R.id.widgetStyleDark),
            WidgetStyle.Adaptive to findViewById(R.id.widgetStyleAdaptive)
        )
        styleSelectedBgs = mapOf(
            WidgetStyle.LiquidGlass to findViewById(R.id.widgetStyleLiquidGlassSelectedBg),
            WidgetStyle.Dark to findViewById(R.id.widgetStyleDarkSelectedBg),
            WidgetStyle.Adaptive to findViewById(R.id.widgetStyleAdaptiveSelectedBg)
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

            allInputs.forEach { input ->
                input.setBackgroundResource(R.drawable.bg_attachment_widget_active)
                input.backgroundTintList = android.content.res.ColorStateList.valueOf(colors.inputBg)
            }
            allPlaceholders.forEach { placeholder ->
                placeholder.setTextColor(colors.textColor)
            }
            allLogos.forEach { logo ->
                logo.setColorFilter(colors.iconTint)
            }
            allButtons.forEach { button ->
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
            allInputs.forEach { input ->
                input.setBackgroundResource(style.inputBackgroundResId)
                input.backgroundTintList = null
            }
            allPlaceholders.forEach { placeholder ->
                placeholder.setTextColor(style.textColor)
            }
            allLogos.forEach { logo ->
                logo.setColorFilter(style.iconTint)
            }
            allButtons.forEach { button ->
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
            val selectedBg = styleSelectedBgs[style] ?: return@forEach

            val targetAlpha = if (selected) 1.0f else 0.0f
            val targetTextColor = if (selected) 0xFF0B0E14.toInt() else 0xFFA8B1BE.toInt()

            if (isFirstLoad) {
                // Apply instantly on first load to prevent flickering
                selectedBg.alpha = targetAlpha
                button.setTextColor(targetTextColor)
            } else {
                // Smooth animated transition
                animateButtonState(button, selectedBg, targetAlpha, targetTextColor)
            }
        }
        isFirstLoad = false
    }

    private fun animateButtonState(
        button: TextView,
        selectedBg: View,
        targetAlpha: Float,
        targetTextColor: Int
    ) {
        activeAnimators[selectedBg]?.cancel()
        activeAnimators[button]?.cancel()

        // 1. Selected background alpha fade anim
        val startAlpha = selectedBg.alpha
        if (startAlpha != targetAlpha) {
            val bgAnimator = ObjectAnimator.ofFloat(
                selectedBg,
                "alpha",
                startAlpha,
                targetAlpha
            ).apply {
                duration = 240
                interpolator = DecelerateInterpolator()
            }
            activeAnimators[selectedBg] = bgAnimator
            bgAnimator.start()
        }

        // 2. Text color transition anim
        val startTextColor = button.textColors.defaultColor
        if (startTextColor != targetTextColor) {
            val colorAnimator = ValueAnimator.ofObject(
                ArgbEvaluator(),
                startTextColor,
                targetTextColor
            ).apply {
                duration = 240
                interpolator = DecelerateInterpolator()
                addUpdateListener { animator ->
                    button.setTextColor(animator.animatedValue as Int)
                }
            }
            activeAnimators[button] = colorAnimator
            colorAnimator.start()
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

    private fun bindSizeButtons() {
        sizeButtons = mapOf(
            "1x1" to findViewById(R.id.widgetSize1x1),
            "1x2" to findViewById(R.id.widgetSize1x2),
            "1x3" to findViewById(R.id.widgetSize1x3),
            "1x4" to findViewById(R.id.widgetSize1x4),
            "2x1" to findViewById(R.id.widgetSize2x1),
            "2x2" to findViewById(R.id.widgetSize2x2),
            "2x3" to findViewById(R.id.widgetSize2x3),
            "2x4" to findViewById(R.id.widgetSize2x4)
        )
        sizeSelectedBgs = mapOf(
            "1x1" to findViewById(R.id.widgetSize1x1SelectedBg),
            "1x2" to findViewById(R.id.widgetSize1x2SelectedBg),
            "1x3" to findViewById(R.id.widgetSize1x3SelectedBg),
            "1x4" to findViewById(R.id.widgetSize1x4SelectedBg),
            "2x1" to findViewById(R.id.widgetSize2x1SelectedBg),
            "2x2" to findViewById(R.id.widgetSize2x2SelectedBg),
            "2x3" to findViewById(R.id.widgetSize2x3SelectedBg),
            "2x4" to findViewById(R.id.widgetSize2x4SelectedBg)
        )
    }

    private fun updateSizeSelection(newSize: String) {
        if (selectedSize == newSize) return
        val oldSize = selectedSize
        selectedSize = newSize

        // 1. Smoothly animate layout height while maintaining full width
        val targetHeightDp = sizeHeights[newSize] ?: 150
        val targetHeightPx = (targetHeightDp * resources.displayMetrics.density).toInt()
        val currentHeightPx = previewPanel.height

        val heightAnimator = ValueAnimator.ofInt(currentHeightPx, targetHeightPx).apply {
            duration = 300
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                val params = previewPanel.layoutParams
                params.height = animator.animatedValue as Int
                previewPanel.layoutParams = params
            }
        }
        activeAnimators[previewPanel]?.cancel()
        activeAnimators[previewPanel] = heightAnimator
        heightAnimator.start()

        // 2. Smoothly cross-fade layout contents
        val oldLayout = getLayoutViewForSize(oldSize)
        val newLayout = getLayoutViewForSize(newSize)

        if (oldLayout != null && newLayout != null) {
            newLayout.visibility = View.VISIBLE
            newLayout.alpha = 0f

            val fadeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 300
                interpolator = DecelerateInterpolator()
                addUpdateListener { animator ->
                    val value = animator.animatedValue as Float
                    oldLayout.alpha = 1f - value
                    newLayout.alpha = value
                }
            }
            fadeAnimator.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    oldLayout.visibility = View.GONE
                }
            })
            fadeAnimator.start()
        }

        updateSizeButtons()
        applyPreview()
    }

    private fun getLayoutViewForSize(size: String): View? {
        return when (size) {
            "1x1" -> findViewById(R.id.layoutPreview1x1)
            "1x2" -> findViewById(R.id.layoutPreview1x2)
            "1x3" -> findViewById(R.id.layoutPreview1x3)
            "1x4" -> findViewById(R.id.layoutPreview1x4)
            "2x1" -> findViewById(R.id.layoutPreview2x1)
            "2x2" -> findViewById(R.id.layoutPreview2x2)
            "2x3" -> findViewById(R.id.layoutPreview2x3)
            "2x4" -> findViewById(R.id.layoutPreview2x4)
            else -> null
        }
    }

    private fun updateSizeButtons() {
        sizeButtons.forEach { (size, button) ->
            val selected = size == selectedSize
            val selectedBg = sizeSelectedBgs[size] ?: return@forEach

            val targetAlpha = if (selected) 1.0f else 0.0f
            val targetTextColor = if (selected) 0xFF0B0E14.toInt() else 0xFFA8B1BE.toInt()

            if (isFirstLoad) {
                selectedBg.alpha = targetAlpha
                button.setTextColor(targetTextColor)
            } else {
                animateButtonState(button, selectedBg, targetAlpha, targetTextColor)
            }
        }
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
