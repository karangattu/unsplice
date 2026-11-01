package com.echomute

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class UnspliceWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        updateAll(context, appWidgetManager, appWidgetIds)
    }

    companion object {
        const val ACTION_TOGGLE_PLAY = "com.echomute.action.TOGGLE_PLAY"
        const val ACTION_REWIND_15 = "com.echomute.action.REWIND_15"
        const val ACTION_FORWARD_30 = "com.echomute.action.FORWARD_30"
        const val ACTION_SKIP_AD = "com.echomute.action.SKIP_AD"

        fun notifyChange(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, UnspliceWidgetProvider::class.java))
            if (ids.isNotEmpty()) updateAll(context, manager, ids)
        }

        private fun servicePendingIntent(context: Context, action: String, requestCode: Int): PendingIntent {
            val intent = Intent(context, PlaybackService::class.java).setAction(action)
            return PendingIntent.getService(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun updateAll(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val queuePrefs = context.getSharedPreferences("playback_queue", Context.MODE_PRIVATE)
            val playPrefs = context.getSharedPreferences("playback", Context.MODE_PRIVATE)
            val snapshot = queuePrefs.getString("snapshot", null)?.let(::decodePlaybackQueue)
            val currentItem = snapshot?.items?.getOrNull(snapshot.index)
            val isPlaying = playPrefs.getBoolean("isPlaying", false)
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val openPending = PendingIntent.getActivity(
                context,
                100,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            for (widgetId in ids) {
                val views = RemoteViews(context.packageName, R.layout.unsplice_widget)
                views.setTextViewText(R.id.widget_title, currentItem?.title ?: "Unsplice")
                views.setTextViewText(R.id.widget_subtitle, currentItem?.artist?.ifBlank { "Ready to play" } ?: "Tap to open player")
                views.setTextViewText(R.id.widget_play_pause, if (isPlaying) "Pause" else "Play")
                views.setOnClickPendingIntent(R.id.widget_root, openPending)
                views.setOnClickPendingIntent(R.id.widget_rewind, servicePendingIntent(context, ACTION_REWIND_15, 101))
                views.setOnClickPendingIntent(R.id.widget_play_pause, servicePendingIntent(context, ACTION_TOGGLE_PLAY, 102))
                views.setOnClickPendingIntent(R.id.widget_forward, servicePendingIntent(context, ACTION_FORWARD_30, 103))
                views.setOnClickPendingIntent(R.id.widget_skip_ad, servicePendingIntent(context, ACTION_SKIP_AD, 104))
                manager.updateAppWidget(widgetId, views)
            }
        }
    }
}
