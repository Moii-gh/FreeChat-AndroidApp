package com.example.chatapp.widget

import android.content.Context
import android.content.res.Configuration
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
            WidgetStyle.DarkMatte -> RemoteStyle(
                panelBackgroundResId = mattePanelBackground(state.cornerRadiusDp),
                inputBackgroundResId = R.drawable.bg_attachment_widget_matte_input,
                activeInputBackgroundResId = R.drawable.bg_attachment_widget_matte_active,
                buttonBackgroundResId = R.drawable.bg_attachment_widget_matte_button,
                textColor = 0xFFE9E7EF.toInt(),
                iconTint = 0xFFFFFFFF.toInt()
            )
            WidgetStyle.Solid -> RemoteStyle(
                panelBackgroundResId = solidPanelBackground(state.cornerRadiusDp),
                inputBackgroundResId = R.drawable.bg_attachment_widget_solid_input,
                activeInputBackgroundResId = R.drawable.bg_attachment_widget_solid_active,
                buttonBackgroundResId = R.drawable.bg_attachment_widget_solid_button,
                textColor = 0xFF1B1D24.toInt(),
                iconTint = 0xFF171A22.toInt()
            )
            WidgetStyle.AdaptiveSystem -> error("Adaptive style must be resolved before use")
        }
    }

    fun effectiveStyle(
        context: Context,
        state: FreeChatAttachmentWidgetStateStore.State
    ): WidgetStyle {
        if (state.widgetStyle != WidgetStyle.AdaptiveSystem) return state.widgetStyle
        return if (context.isNightMode()) WidgetStyle.LiquidGlass else WidgetStyle.Solid
    }

    private fun liquidPanelBackground(cornerRadiusDp: Int): Int {
        return when (cornerRadiusDp) {
            in 0..25 -> R.drawable.bg_attachment_widget_liquid_panel_soft
            in 26..38 -> R.drawable.bg_attachment_widget_liquid_panel_medium
            else -> R.drawable.bg_attachment_widget_liquid_panel_round
        }
    }

    private fun mattePanelBackground(cornerRadiusDp: Int): Int {
        return when (cornerRadiusDp) {
            in 0..25 -> R.drawable.bg_attachment_widget_matte_panel_soft
            in 26..38 -> R.drawable.bg_attachment_widget_matte_panel_medium
            else -> R.drawable.bg_attachment_widget_matte_panel_round
        }
    }

    private fun solidPanelBackground(cornerRadiusDp: Int): Int {
        return when (cornerRadiusDp) {
            in 0..25 -> R.drawable.bg_attachment_widget_solid_panel_soft
            in 26..38 -> R.drawable.bg_attachment_widget_solid_panel_medium
            else -> R.drawable.bg_attachment_widget_solid_panel_round
        }
    }

    private fun Context.isNightMode(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }
}
