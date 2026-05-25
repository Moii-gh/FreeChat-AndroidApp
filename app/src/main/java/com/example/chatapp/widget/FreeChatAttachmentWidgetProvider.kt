package com.example.chatapp.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.example.chatapp.LocaleHelper
import com.example.chatapp.R

class FreeChatAttachmentWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { appWidgetId ->
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_LOCALE_CHANGED -> updateAll(context)
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        updateAll(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        FreeChatAttachmentWidgetStateStore.delete(context, appWidgetIds)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        FreeChatAttachmentWidgetStateStore.clear(context)
    }

    override fun onRestored(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) {
        super.onRestored(context, oldWidgetIds, newWidgetIds)
        FreeChatAttachmentWidgetStateStore.restoreIds(context, oldWidgetIds, newWidgetIds)
        val appWidgetManager = AppWidgetManager.getInstance(context)
        newWidgetIds.forEach { appWidgetId ->
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        appWidgetManager.updateAppWidget(
            appWidgetId,
            buildRemoteViews(context, appWidgetId, newOptions)
        )
    }

    private fun updateWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
        appWidgetManager.updateAppWidget(
            appWidgetId,
            buildRemoteViews(context, appWidgetId, options)
        )
    }

    companion object {
        private const val DEFAULT_MIN_WIDTH_DP = 300
        private const val DEFAULT_MIN_HEIGHT_DP = 130
        private const val ONE_ROW_MAX_HEIGHT_DP = 116

        fun buildRemoteViews(
            context: Context,
            appWidgetId: Int,
            options: Bundle? = null
        ): RemoteViews {
            val state = FreeChatAttachmentWidgetStateStore.load(context, appWidgetId)
            val size = FreeChatAttachmentWidgetStateStore.sizeFrom(options, state)
            val layout = chooseLayout(size)
            val inputHint = LocaleHelper.getString(context, "main_panel_input")
            FreeChatAttachmentWidgetStateStore.saveRenderedState(
                context = context,
                appWidgetId = appWidgetId,
                size = size.withDefaults(),
                layoutName = layout.name,
                displayText = inputHint
            )
            return RemoteViews(context.packageName, layout.layoutResId).apply {
                applyAppearanceSurface(context, state, size.withDefaults(), layout.name)
                applyControlStyle(context, state, layout)
                setFloat(
                    R.id.widgetPanel,
                    "setAlpha",
                    FreeChatAttachmentWidgetStateStore.alphaForTransparency(
                        state.transparencyPercent
                    )
                )
                if (layout.hasInput) {
                    setContentDescription(R.id.widgetInput, inputHint)
                }
                if (layout.hasInputText) {
                    setTextViewText(R.id.widgetPlaceholder, inputHint)
                }
                setActionIfPresent(
                    context,
                    appWidgetId,
                    layout.hasInput,
                    R.id.widgetInput,
                    HomeWidgetActionActivity.ACTION_MESSAGE
                )
                setActionIfPresent(
                    context,
                    appWidgetId,
                    layout.hasCamera,
                    R.id.widgetCamera,
                    HomeWidgetActionActivity.ACTION_CAMERA
                )
                setActionIfPresent(
                    context,
                    appWidgetId,
                    layout.hasGallery,
                    R.id.widgetGallery,
                    HomeWidgetActionActivity.ACTION_GALLERY
                )
                setActionIfPresent(
                    context,
                    appWidgetId,
                    layout.hasDocument,
                    R.id.widgetDocument,
                    HomeWidgetActionActivity.ACTION_DOCUMENT
                )
                setActionIfPresent(
                    context,
                    appWidgetId,
                    layout.hasMic,
                    R.id.widgetMic,
                    HomeWidgetActionActivity.ACTION_MIC
                )
            }
        }

        private fun RemoteViews.applyAppearanceSurface(
            context: Context,
            state: FreeChatAttachmentWidgetStateStore.State,
            size: WidgetSize,
            layoutName: String
        ) {
            val effectiveStyle = WidgetStyleResources.effectiveStyle(context, state)
            if (effectiveStyle == WidgetStyle.Dark || effectiveStyle == WidgetStyle.LiquidGlass || effectiveStyle == WidgetStyle.Adaptive) {
                setViewVisibility(R.id.widgetBackgroundImage, View.GONE)
                setViewVisibility(R.id.widgetBackgroundScrim, View.GONE)
                return
            }
            val surface = runCatching {
                WidgetGlassSurfaceRenderer.render(context, state, size, layoutName)
            }.getOrNull()
            if (surface == null) {
                setViewVisibility(R.id.widgetBackgroundImage, View.GONE)
                setViewVisibility(R.id.widgetBackgroundScrim, View.GONE)
                return
            }
            setImageViewBitmap(R.id.widgetBackgroundImage, surface)
            setViewVisibility(R.id.widgetBackgroundImage, View.VISIBLE)
            setViewVisibility(R.id.widgetBackgroundScrim, View.GONE)
        }

        private fun RemoteViews.applyControlStyle(
            context: Context,
            state: FreeChatAttachmentWidgetStateStore.State,
            layout: WidgetLayout
        ) {
            val effectiveStyle = WidgetStyleResources.effectiveStyle(context, state)
            if (effectiveStyle == WidgetStyle.Dark) return

            if (effectiveStyle == WidgetStyle.Adaptive) {
                val isDark = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                        android.content.res.Configuration.UI_MODE_NIGHT_YES
                val accentColor = WidgetStyleResources.getSystemAccentColor(context)
                val colors = WidgetStyleResources.resolveAdaptiveColors(accentColor, isDark)

                // Dynamic panel background set using standard shape drawable to avoid cropping issues
                val panelBackground = WidgetStyleResources.liquidPanelBackground(state.cornerRadiusDp)
                setInt(R.id.widgetPanel, "setBackgroundResource", panelBackground)
                setColorStateList(
                    R.id.widgetPanel,
                    "setBackgroundTintList",
                    android.content.res.ColorStateList.valueOf(colors.panelBg)
                )

                if (layout.hasInput) {
                    val inputBackground = if (layout.name.startsWith("Tall")) {
                        R.drawable.bg_attachment_widget_input
                    } else {
                        R.drawable.bg_attachment_widget_active
                    }
                    setInt(R.id.widgetInput, "setBackgroundResource", inputBackground)
                    setColorStateList(
                        R.id.widgetInput,
                        "setBackgroundTintList",
                        android.content.res.ColorStateList.valueOf(colors.inputBg)
                    )
                    // Tint the logo for perfect readability
                    setInt(R.id.widgetLogo, "setColorFilter", colors.iconTint)
                }

                if (layout.hasInputText) {
                    setTextColor(R.id.widgetPlaceholder, colors.textColor)
                }

                val isOneRow = !layout.name.startsWith("Tall")
                setAdaptiveButtonStyleIfPresent(layout.hasCamera, R.id.widgetCamera, colors.buttonBg, colors.iconTint, isOneRow)
                setAdaptiveButtonStyleIfPresent(layout.hasGallery, R.id.widgetGallery, colors.buttonBg, colors.iconTint, isOneRow)
                setAdaptiveButtonStyleIfPresent(layout.hasDocument, R.id.widgetDocument, colors.buttonBg, colors.iconTint, isOneRow)
                setAdaptiveButtonStyleIfPresent(layout.hasMic, R.id.widgetMic, colors.buttonBg, colors.iconTint, isOneRow)
            } else {
                val style = WidgetStyleResources.remoteStyle(context, state)
                setInt(R.id.widgetPanel, "setBackgroundResource", style.panelBackgroundResId)
                if (layout.hasInput) {
                    val inputBackground = if (layout.name.startsWith("Tall")) {
                        style.inputBackgroundResId
                    } else {
                        style.activeInputBackgroundResId
                    }
                    setInt(R.id.widgetInput, "setBackgroundResource", inputBackground)
                }
                if (layout.hasInputText) {
                    setTextColor(R.id.widgetPlaceholder, style.textColor)
                }
                val isOneRow = !layout.name.startsWith("Tall")
                val buttonBg = if (isOneRow) {
                    R.drawable.bg_attachment_widget_liquid_button_one_row
                } else {
                    style.buttonBackgroundResId
                }
                setButtonStyleIfPresent(layout.hasCamera, R.id.widgetCamera, buttonBg, style.iconTint)
                setButtonStyleIfPresent(layout.hasGallery, R.id.widgetGallery, buttonBg, style.iconTint)
                setButtonStyleIfPresent(layout.hasDocument, R.id.widgetDocument, buttonBg, style.iconTint)
                setButtonStyleIfPresent(layout.hasMic, R.id.widgetMic, buttonBg, style.iconTint)
            }
        }

        private fun RemoteViews.setButtonStyleIfPresent(
            isPresent: Boolean,
            viewId: Int,
            backgroundResId: Int,
            iconTint: Int
        ) {
            if (!isPresent) return
            setInt(viewId, "setBackgroundResource", backgroundResId)
            setInt(viewId, "setColorFilter", iconTint)
        }

        private fun RemoteViews.setAdaptiveButtonStyleIfPresent(
            isPresent: Boolean,
            viewId: Int,
            buttonBgColor: Int,
            iconTint: Int,
            isOneRow: Boolean
        ) {
            if (!isPresent) return
            val bgResId = if (isOneRow) {
                R.drawable.bg_attachment_widget_button_one_row
            } else {
                R.drawable.bg_attachment_widget_button
            }
            setInt(viewId, "setBackgroundResource", bgResId)
            setColorStateList(
                viewId,
                "setBackgroundTintList",
                android.content.res.ColorStateList.valueOf(buttonBgColor)
            )
            setInt(viewId, "setColorFilter", iconTint)
        }

        fun updateAll(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, FreeChatAttachmentWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
                .takeIf { it.isNotEmpty() }
                ?: FreeChatAttachmentWidgetStateStore.knownWidgetIds(context)
            appWidgetIds.forEach { appWidgetId ->
                val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
                appWidgetManager.updateAppWidget(
                    appWidgetId,
                    buildRemoteViews(context, appWidgetId, options)
                )
            }
        }

        private fun chooseLayout(size: WidgetSize): WidgetLayout {
            val minWidth = size.minWidth.takeIf { it > 0 } ?: DEFAULT_MIN_WIDTH_DP
            val minHeight = size.minHeight.takeIf { it > 0 } ?: DEFAULT_MIN_HEIGHT_DP

            return if (minHeight < ONE_ROW_MAX_HEIGHT_DP) {
                chooseOneRowLayout(minWidth, minHeight)
            } else {
                chooseTallLayout(minWidth, minHeight)
            }
        }

        private fun chooseTallLayout(minWidth: Int, minHeight: Int): WidgetLayout {
            return when {
                minWidth <= 128 || minHeight <= 76 -> WidgetLayout.TallTiny
                minWidth < 170 && minHeight < 120 -> WidgetLayout.TallSmall
                minWidth < 190 -> WidgetLayout.TallNarrow
                minWidth < 300 || minHeight < 128 -> WidgetLayout.TallCompact
                else -> WidgetLayout.TallLarge
            }
        }

        private fun chooseOneRowLayout(minWidth: Int, minHeight: Int): WidgetLayout {
            return when {
                minWidth <= 128 || minHeight < 64 -> WidgetLayout.Tiny
                minWidth < 200 -> WidgetLayout.Small
                minWidth < 300 -> WidgetLayout.Narrow
                minWidth < 380 -> WidgetLayout.Compact
                else -> WidgetLayout.Large
            }
        }

        private fun RemoteViews.setActionIfPresent(
            context: Context,
            appWidgetId: Int,
            isPresent: Boolean,
            viewId: Int,
            action: String
        ) {
            if (!isPresent) return
            setOnClickPendingIntent(viewId, actionPendingIntent(context, appWidgetId, action))
        }

        private fun actionPendingIntent(
            context: Context,
            appWidgetId: Int,
            action: String
        ): PendingIntent {
            val intent = Intent(context, HomeWidgetActionActivity::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                putExtra(HomeWidgetActionActivity.EXTRA_ACTION, action)
                data = Uri.parse("freechat://home-widget/$appWidgetId/$action")
            }
            return PendingIntent.getActivity(
                context,
                31 * appWidgetId + action.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun WidgetSize.withDefaults(): WidgetSize {
            return WidgetSize(
                minWidth = minWidth.takeIf { it > 0 } ?: DEFAULT_MIN_WIDTH_DP,
                minHeight = minHeight.takeIf { it > 0 } ?: DEFAULT_MIN_HEIGHT_DP,
                maxWidth = maxWidth.takeIf { it > 0 } ?: minWidth.takeIf { it > 0 }
                    ?: DEFAULT_MIN_WIDTH_DP,
                maxHeight = maxHeight.takeIf { it > 0 } ?: minHeight.takeIf { it > 0 }
                    ?: DEFAULT_MIN_HEIGHT_DP
            )
        }

        private enum class WidgetLayout(
            val layoutResId: Int,
            val hasInput: Boolean,
            val hasCamera: Boolean,
            val hasGallery: Boolean,
            val hasDocument: Boolean,
            val hasMic: Boolean,
            val hasInputText: Boolean
        ) {
            TallLarge(
                R.layout.widget_attachment_panel,
                hasInput = true,
                hasCamera = true,
                hasGallery = true,
                hasDocument = true,
                hasMic = true,
                hasInputText = true
            ),
            TallCompact(
                R.layout.widget_attachment_panel_compact,
                hasInput = true,
                hasCamera = true,
                hasGallery = true,
                hasDocument = false,
                hasMic = true,
                hasInputText = true
            ),
            TallNarrow(
                R.layout.widget_attachment_panel_narrow,
                hasInput = true,
                hasCamera = true,
                hasGallery = false,
                hasDocument = false,
                hasMic = true,
                hasInputText = true
            ),
            TallSmall(
                R.layout.widget_attachment_panel_small,
                hasInput = false,
                hasCamera = true,
                hasGallery = false,
                hasDocument = false,
                hasMic = true,
                hasInputText = false
            ),
            TallTiny(
                R.layout.widget_attachment_panel_tiny,
                hasInput = false,
                hasCamera = false,
                hasGallery = false,
                hasDocument = false,
                hasMic = true,
                hasInputText = false
            ),
            Large(
                R.layout.widget_attachment_panel_one_row,
                hasInput = true,
                hasCamera = true,
                hasGallery = true,
                hasDocument = true,
                hasMic = true,
                hasInputText = true
            ),
            Compact(
                R.layout.widget_attachment_panel_one_row_compact,
                hasInput = true,
                hasCamera = true,
                hasGallery = true,
                hasDocument = false,
                hasMic = true,
                hasInputText = true
            ),
            Narrow(
                R.layout.widget_attachment_panel_one_row_narrow,
                hasInput = true,
                hasCamera = true,
                hasGallery = false,
                hasDocument = false,
                hasMic = true,
                hasInputText = false
            ),
            Small(
                R.layout.widget_attachment_panel_one_row_small,
                hasInput = true,
                hasCamera = false,
                hasGallery = false,
                hasDocument = false,
                hasMic = true,
                hasInputText = false
            ),
            Tiny(
                R.layout.widget_attachment_panel_one_row_tiny,
                hasInput = true,
                hasCamera = false,
                hasGallery = false,
                hasDocument = false,
                hasMic = false,
                hasInputText = false
            )
        }
    }
}
