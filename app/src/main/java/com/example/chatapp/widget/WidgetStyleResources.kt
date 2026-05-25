package com.example.chatapp.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import com.example.chatapp.R

internal object WidgetStyleResources {
    data class RemoteStyle(
        val panelBackgroundResId: Int,
        val inputBackgroundResId: Int,
        val activeInputBackgroundResId: Int,
        val buttonBackgroundResId: Int,
        val textColor: Int,
        val iconTint: Int
    )

    data class AdaptiveColors(
        val panelBg: Int,
        val buttonBg: Int,
        val inputBg: Int,
        val textColor: Int,
        val iconTint: Int
    )

    fun remoteStyle(context: Context, state: FreeChatAttachmentWidgetStateStore.State): RemoteStyle {
        val style = effectiveStyle(context, state)
        return when (style) {
            WidgetStyle.LiquidGlass -> RemoteStyle(
                panelBackgroundResId = liquidPanelBackground(state.cornerRadiusDp),
                inputBackgroundResId = R.drawable.bg_attachment_widget_liquid_input,
                activeInputBackgroundResId = R.drawable.bg_attachment_widget_liquid_active,
                buttonBackgroundResId = R.drawable.bg_attachment_widget_liquid_button,
                textColor = 0xFFF8FBFF.toInt(),
                iconTint = 0xFFFFFFFF.toInt()
            )
            WidgetStyle.Dark -> RemoteStyle(
                panelBackgroundResId = R.drawable.bg_attachment_widget_panel,
                inputBackgroundResId = R.drawable.bg_attachment_widget_input,
                activeInputBackgroundResId = R.drawable.bg_attachment_widget_active,
                buttonBackgroundResId = R.drawable.bg_attachment_widget_button,
                textColor = 0xFFD0CED6.toInt(),
                iconTint = 0xFFFFFFFF.toInt()
            )
            WidgetStyle.Adaptive -> RemoteStyle(
                panelBackgroundResId = android.R.color.transparent,
                inputBackgroundResId = R.drawable.bg_attachment_widget_input,
                activeInputBackgroundResId = R.drawable.bg_attachment_widget_active,
                buttonBackgroundResId = R.drawable.bg_attachment_widget_button,
                textColor = 0xFFFFFFFF.toInt(),
                iconTint = 0xFFFFFFFF.toInt()
            )
        }
    }

    fun effectiveStyle(
        context: Context,
        state: FreeChatAttachmentWidgetStateStore.State
    ): WidgetStyle {
        return state.widgetStyle
    }

    fun getSystemAccentColor(context: Context): Int? {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val resId = context.resources.getIdentifier("system_accent1_500", "color", "android")
            if (resId != 0) {
                return context.getColor(resId)
            }
        }
        val typedValue = android.util.TypedValue()
        if (context.theme.resolveAttribute(android.R.attr.colorAccent, typedValue, true)) {
            return typedValue.data
        }
        return null
    }

    fun resolveAdaptiveColors(accentColor: Int?, isDark: Boolean): AdaptiveColors {
        // Fallback to slate blue-grey: a neutral modern hue
        val baseColor = accentColor ?: 0xFF5C7896.toInt()

        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(baseColor, hsl)
        val h = hsl[0]
        val s = hsl[1]

        // 1. Panel Background: Muted accent color with soft transparency
        val panelSat = s.coerceAtMost(0.22f)
        val panelLight = if (isDark) 0.12f else 0.93f
        val panelColor = ColorUtils.HSLToColor(floatArrayOf(h, panelSat, panelLight))
        val panelAlpha = if (isDark) 0xEE else 0xF0
        val finalPanelBg = (panelColor and 0x00FFFFFF) or (panelAlpha shl 24)

        // 2. Buttons: Contrasting accent-derived tone
        val buttonSat = s.coerceAtMost(0.38f)
        val buttonBg = if (isDark) {
            // Dark mode: buttons are lighter to stand out on dark panel background
            ColorUtils.HSLToColor(floatArrayOf(h, buttonSat, 0.24f))
        } else {
            // Light mode: buttons are darker to stand out on light panel background
            ColorUtils.HSLToColor(floatArrayOf(h, buttonSat, 0.80f))
        }

        // 3. Input Field Background: Premium inset style
        val inputBg = if (isDark) {
            ColorUtils.HSLToColor(floatArrayOf(h, panelSat, 0.08f))
        } else {
            ColorUtils.HSLToColor(floatArrayOf(h, panelSat, 0.97f))
        }

        // 4. Text and Icons: Perfect readability
        val textColor = if (isDark) 0xFFE2E8F0.toInt() else 0xFF1E293B.toInt()
        val iconTint = if (isDark) {
            0xFFFFFFFF.toInt()
        } else {
            ColorUtils.HSLToColor(floatArrayOf(h, buttonSat.coerceAtLeast(0.20f), 0.16f))
        }

        return AdaptiveColors(
            panelBg = finalPanelBg,
            buttonBg = buttonBg,
            inputBg = inputBg,
            textColor = textColor,
            iconTint = iconTint
        )
    }

    fun liquidPanelBackground(cornerRadiusDp: Int): Int {
        return when (cornerRadiusDp) {
            in 0..25 -> R.drawable.bg_attachment_widget_liquid_panel_soft
            in 26..38 -> R.drawable.bg_attachment_widget_liquid_panel_medium
            else -> R.drawable.bg_attachment_widget_liquid_panel_round
        }
    }

    fun Context.isNightMode(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }
}

