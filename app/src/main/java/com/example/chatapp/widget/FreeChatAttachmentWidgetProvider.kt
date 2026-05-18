package com.example.chatapp.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import com.example.chatapp.R

class FreeChatAttachmentWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { appWidgetId ->
            appWidgetManager.updateAppWidget(appWidgetId, buildRemoteViews(context))
        }
    }

    companion object {
        fun buildRemoteViews(context: Context): RemoteViews {
            return RemoteViews(context.packageName, R.layout.widget_attachment_panel).apply {
                setOnClickPendingIntent(
                    R.id.widgetInput,
                    actionPendingIntent(context, HomeWidgetActionActivity.ACTION_MESSAGE)
                )
                setOnClickPendingIntent(
                    R.id.widgetCamera,
                    actionPendingIntent(context, HomeWidgetActionActivity.ACTION_CAMERA)
                )
                setOnClickPendingIntent(
                    R.id.widgetGallery,
                    actionPendingIntent(context, HomeWidgetActionActivity.ACTION_GALLERY)
                )
                setOnClickPendingIntent(
                    R.id.widgetDocument,
                    actionPendingIntent(context, HomeWidgetActionActivity.ACTION_DOCUMENT)
                )
                setOnClickPendingIntent(
                    R.id.widgetMic,
                    actionPendingIntent(context, HomeWidgetActionActivity.ACTION_MIC)
                )
            }
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
    }
}
