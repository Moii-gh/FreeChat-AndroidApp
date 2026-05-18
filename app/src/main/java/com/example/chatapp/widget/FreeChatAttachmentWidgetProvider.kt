package com.example.chatapp.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.RemoteViews
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

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        appWidgetManager.updateAppWidget(appWidgetId, buildRemoteViews(context, newOptions))
    }

    private fun updateWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
        appWidgetManager.updateAppWidget(appWidgetId, buildRemoteViews(context, options))
    }

    companion object {
        private const val DEFAULT_MIN_WIDTH_DP = 300
        private const val DEFAULT_MIN_HEIGHT_DP = 130

        fun buildRemoteViews(context: Context, options: Bundle? = null): RemoteViews {
            val layout = chooseLayout(options)
            return RemoteViews(context.packageName, layout.layoutResId).apply {
                setActionIfPresent(
                    context,
                    layout.hasInput,
                    R.id.widgetInput,
                    HomeWidgetActionActivity.ACTION_MESSAGE
                )
                setActionIfPresent(
                    context,
                    layout.hasCamera,
                    R.id.widgetCamera,
                    HomeWidgetActionActivity.ACTION_CAMERA
                )
                setActionIfPresent(
                    context,
                    layout.hasGallery,
                    R.id.widgetGallery,
                    HomeWidgetActionActivity.ACTION_GALLERY
                )
                setActionIfPresent(
                    context,
                    layout.hasDocument,
                    R.id.widgetDocument,
                    HomeWidgetActionActivity.ACTION_DOCUMENT
                )
                setActionIfPresent(
                    context,
                    layout.hasMic,
                    R.id.widgetMic,
                    HomeWidgetActionActivity.ACTION_MIC
                )
            }
        }

        private fun chooseLayout(options: Bundle?): WidgetLayout {
            val minWidth = options
                ?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
                ?.takeIf { it > 0 }
                ?: DEFAULT_MIN_WIDTH_DP
            val minHeight = options
                ?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
                ?.takeIf { it > 0 }
                ?: DEFAULT_MIN_HEIGHT_DP

            return when {
                minWidth <= 128 || minHeight <= 76 -> WidgetLayout.Tiny
                minWidth < 170 && minHeight < 120 -> WidgetLayout.Small
                minHeight < 116 -> when {
                    minWidth >= 330 -> WidgetLayout.Wide
                    minWidth >= 190 -> WidgetLayout.InputOnly
                    else -> WidgetLayout.Small
                }
                minWidth < 190 -> WidgetLayout.Narrow
                minWidth < 300 || minHeight < 128 -> WidgetLayout.Compact
                else -> WidgetLayout.Large
            }
        }

        private fun RemoteViews.setActionIfPresent(
            context: Context,
            isPresent: Boolean,
            viewId: Int,
            action: String
        ) {
            if (!isPresent) return
            setOnClickPendingIntent(viewId, actionPendingIntent(context, action))
        }

        private fun actionPendingIntent(context: Context, action: String): PendingIntent {
            val intent = Intent(context, HomeWidgetActionActivity::class.java).apply {
                putExtra(HomeWidgetActionActivity.EXTRA_ACTION, action)
                data = Uri.parse("freechat://home-widget/$action")
            }
            return PendingIntent.getActivity(
                context,
                action.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private enum class WidgetLayout(
            val layoutResId: Int,
            val hasInput: Boolean,
            val hasCamera: Boolean,
            val hasGallery: Boolean,
            val hasDocument: Boolean,
            val hasMic: Boolean
        ) {
            Large(
                R.layout.widget_attachment_panel,
                hasInput = true,
                hasCamera = true,
                hasGallery = true,
                hasDocument = true,
                hasMic = true
            ),
            Wide(
                R.layout.widget_attachment_panel_wide,
                hasInput = true,
                hasCamera = true,
                hasGallery = true,
                hasDocument = false,
                hasMic = true
            ),
            Compact(
                R.layout.widget_attachment_panel_compact,
                hasInput = true,
                hasCamera = true,
                hasGallery = true,
                hasDocument = false,
                hasMic = true
            ),
            InputOnly(
                R.layout.widget_attachment_panel_input_only,
                hasInput = true,
                hasCamera = false,
                hasGallery = false,
                hasDocument = false,
                hasMic = false
            ),
            Narrow(
                R.layout.widget_attachment_panel_narrow,
                hasInput = true,
                hasCamera = true,
                hasGallery = false,
                hasDocument = false,
                hasMic = true
            ),
            Small(
                R.layout.widget_attachment_panel_small,
                hasInput = false,
                hasCamera = true,
                hasGallery = false,
                hasDocument = false,
                hasMic = true
            ),
            Tiny(
                R.layout.widget_attachment_panel_tiny,
                hasInput = false,
                hasCamera = false,
                hasGallery = false,
                hasDocument = false,
                hasMic = true
            )
        }
    }
}
