package com.pulseloop.health.accuracy

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.health.connect.client.records.Record
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pulseloop.MainActivity
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.AccuracyCheckEntity
import java.util.concurrent.TimeUnit

/** Record time accessors shared by the controller and the comparison reader. */
internal object ReferenceRecords {
    fun startOf(r: Record): Long? = ExportPause.span(r)?.first
}

/**
 * Ends a check on time even if the app is never opened, and tells the user the report is ready.
 * A one-shot at the planned end, backed by a 6-hourly periodic run (WorkManager can defer the
 * one-shot under Doze). It also tries the pause-set refresh; a Health Connect read from the
 * background may be refused, in which case the next foreground open does it.
 */
class AccuracyCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val open = PulseLoopDatabase.getInstance(applicationContext).accuracyDao().open()
        if (open == null) {
            cancelAll(applicationContext)
            return Result.success()
        }
        AccuracyCheckController.endIfDue(applicationContext)?.let { AccuracyNotifications.reportReady(applicationContext, it) }
        runCatching { AccuracyCheckController.refreshPauseSet(applicationContext) }
            .onFailure { Log.i(TAG, "background pause refresh not possible: ${it.message}") }
        return Result.success()
    }

    companion object {
        private const val TAG = "AccuracyCheckWorker"
        private const val PERIODIC = "accuracy_check"
        private const val END = "accuracy_check_end"

        fun schedule(context: Context, plannedEndAt: Long, now: Long = System.currentTimeMillis()) {
            val wm = WorkManager.getInstance(context.applicationContext)
            wm.enqueueUniquePeriodicWork(
                PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<AccuracyCheckWorker>(6, TimeUnit.HOURS).build(),
            )
            wm.enqueueUniqueWork(
                END, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<AccuracyCheckWorker>()
                    .setInitialDelay((plannedEndAt - now).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                    .build(),
            )
        }

        fun cancelEnd(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(END)
        }

        fun cancelAll(context: Context) {
            val wm = WorkManager.getInstance(context.applicationContext)
            wm.cancelUniqueWork(END)
            wm.cancelUniqueWork(PERIODIC)
        }
    }
}

object AccuracyNotifications {
    private const val CHANNEL_ID = "accuracy_check"
    private const val NOTIFICATION_ID = 2101

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Accuracy check", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When an accuracy check against another device has finished"
            }
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    fun reportReady(context: Context, check: AccuracyCheckEntity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        createChannel(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            action = MainActivity.ACTION_OPEN_ACCURACY_CHECK
        }
        val pending = PendingIntent.getActivity(context, NOTIFICATION_ID, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Your accuracy report is ready")
            .setContentText("Your ring vs ${check.referenceLabel}. Health Connect export stays paused until you choose what to do with these days.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "Your ring vs ${check.referenceLabel}. Health Connect export stays paused until you choose what to do with these days.",
            ))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }
}
