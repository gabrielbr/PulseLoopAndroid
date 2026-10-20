package com.pulseloop.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.pulseloop.MainActivity
import com.pulseloop.R

/**
 * A single-button home-screen widget: tap it and the camera opens to photograph a meal for AI
 * analysis, the same path as the "Log meal" icon shortcut (MainActivity.ACTION_LOG_MEAL →
 * Nutrition, photo flow). Plain RemoteViews rather than Glance: it shows no data, so there is
 * nothing to refresh.
 */
class SnapMealWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_LOG_MEAL)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val views = RemoteViews(context.packageName, R.layout.widget_snap_meal).apply {
            setOnClickPendingIntent(R.id.snap_meal_root, pending)
        }
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    private companion object {
        const val REQUEST_CODE = 4101
    }
}
