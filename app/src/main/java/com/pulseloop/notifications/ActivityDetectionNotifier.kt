package com.pulseloop.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.pulseloop.MainActivity
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.ring.PulseEvent
import com.pulseloop.ring.PulseEventBus
import com.pulseloop.service.ActivityDetection
import com.pulseloop.ui.i18n.tr
import com.pulseloop.ui.i18n.trf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * After each completed sync, says what the ring saw the user doing ("Looks like strength
 * training · 45 min at 18:10"). One notification per new suggestion, only for activity that ended
 * in the last 12 hours, never twice for the same stretch. Tapping opens the Activity tab, where
 * the user confirms, changes the type or dismisses it. See docs/activity-recognition-plan.md.
 */
object ActivityDetectionNotifier {
    private const val CHANNEL_ID = "detected_workouts"
    private const val RECENT_MS = 12 * 60 * 60_000L

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, tr("Detected workouts"), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = tr("What your ring saw you doing, to add with a tap") }
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    /** Application-scoped, like the accuracy watcher: runs whether or not a screen is open. */
    fun start(context: Context, scope: CoroutineScope) {
        val app = context.applicationContext
        scope.launch {
            PulseEventBus.events
                .filter { it is PulseEvent.SyncProgress && it.stage == CoachNotificationDataTrigger.DONE_STAGE }
                .collectLatest {
                    // Let the sync's last rows land and back-to-back completions coalesce.
                    delay(5_000L)
                    runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { check(app) } }
                }
        }
    }

    suspend fun check(context: Context, now: Long = System.currentTimeMillis()) {
        if (!ActivityDetection.notifyEnabled(context)) return
        val db = PulseLoopDatabase.getInstance(context)
        val already = ActivityDetection.notified(context)
        val fresh = ActivityDetection.load(context, db, days = 1, now = now)
            .filter { it.kind != ActivityDetection.Proposal.Kind.NAP && it.endAt >= now - RECENT_MS && it.key !in already }
        if (fresh.isEmpty()) return
        // Marked before delivery: a missing permission must not make every later sync retry it.
        ActivityDetection.markNotified(context, fresh.map { it.key })
        fresh.take(2).forEach { deliver(context, it) }
    }

    /** "a ride", "strength training": how the notification names a guessed type. */
    fun phrase(type: String): String = when (type) {
        "walk" -> tr("Looks like a walk")
        "run" -> tr("Looks like a run")
        "cycle" -> tr("Looks like a ride")
        "gym" -> tr("Looks like strength training")
        "hike" -> tr("Looks like a hike")
        else -> tr("Looks like a workout")
    }

    private fun deliver(context: Context, p: ActivityDetection.Proposal) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val time = Instant.ofEpochMilli(p.startAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
        val intent = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(context, p.key.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(phrase(p.bestType))
            .setContentText(trf("{0} min at {1}. Tap to add it, or say it wasn't.", p.minutes, time))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(context).notify(p.key.hashCode(), n)
    }
}
