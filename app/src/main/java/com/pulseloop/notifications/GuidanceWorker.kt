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
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pulseloop.MainActivity
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.service.BedtimeGuidance
import com.pulseloop.service.ReadinessRepository
import com.pulseloop.service.VitalsCheck
import com.pulseloop.service.WeeklyReport
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.IsoFields
import java.util.concurrent.TimeUnit

/** On/off switches for daily guidance notifications, plus once-per-period bookkeeping. */
class GuidancePrefs(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("guidance", Context.MODE_PRIVATE)

    var vitalsAlerts: Boolean
        get() = p.getBoolean("vitalsAlerts", true)
        set(v) = p.edit().putBoolean("vitalsAlerts", v).apply()
    var weeklyReport: Boolean
        get() = p.getBoolean("weeklyReport", true)
        set(v) = p.edit().putBoolean("weeklyReport", v).apply()
    var bedtimeReminder: Boolean
        get() = p.getBoolean("bedtimeReminder", false)
        set(v) = p.edit().putBoolean("bedtimeReminder", v).apply()

    internal var lastVitalsAlertDay: String?
        get() = p.getString("lastVitalsAlertDay", null)
        set(v) = p.edit().putString("lastVitalsAlertDay", v).apply()
    internal var lastWeeklyReportWeek: String?
        get() = p.getString("lastWeeklyReportWeek", null)
        set(v) = p.edit().putString("lastWeeklyReportWeek", v).apply()
    internal var lastBedtimeReminderDay: String?
        get() = p.getString("lastBedtimeReminderDay", null)
        set(v) = p.edit().putString("lastBedtimeReminderDay", v).apply()
}

/**
 * Hourly delivery of daily guidance (docs/health-app-feature-research.md): the "something's off"
 * alert once a day after last night is synced, the weekly report on Sunday evening, and an optional
 * wind-down reminder before the suggested bedtime. All decisions are in service/DailyGuidance.kt;
 * this only decides *when* and sends. Times are the phone's local time.
 */
class GuidanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!canNotify(ctx)) return Result.success()
        val prefs = GuidancePrefs(ctx)
        val db = PulseLoopDatabase.getInstance(ctx)
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val local = Instant.ofEpochMilli(now).atZone(zone)
        val today = local.toLocalDate()
        val inputs = runCatching { ReadinessRepository.loadInputs(db, now, zone) }.getOrNull() ?: return Result.success()

        // 1. Something's off — once a day, during waking hours, once last night has synced.
        val night = inputs.lastNight
        if (prefs.vitalsAlerts && night != null && prefs.lastVitalsAlertDay != today.toString() && local.hour in 6..21) {
            val signals = VitalsCheck.offSignals(night, inputs.history)
            if (VitalsCheck.shouldAlert(signals)) {
                val (title, body) = VitalsCheck.alertText(signals)
                notify(ctx, ID_VITALS, title, body)
                prefs.lastVitalsAlertDay = today.toString()
            }
        }

        // 2. Weekly report — Sunday from 18:00, once per ISO week.
        val weekKey = "${today.get(IsoFields.WEEK_BASED_YEAR)}-${today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}"
        if (prefs.weeklyReport && local.dayOfWeek == DayOfWeek.SUNDAY && local.hour >= 18 && prefs.lastWeeklyReportWeek != weekKey) {
            val all = inputs.history + listOfNotNull(night)
            val thisWeekNights = all.filter { !it.day.isBefore(today.minusDays(6)) }
            val lastWeekNights = all.filter { it.day.isBefore(today.minusDays(6)) && !it.day.isBefore(today.minusDays(13)) }
            val days = db.activityDailyDao().recentReal(limit = 15)
            fun stepsBetween(fromDaysAgo: Long, toDaysAgo: Long): List<Int> {
                val from = today.minusDays(fromDaysAgo).atStartOfDay(zone).toInstant().toEpochMilli()
                val to = today.minusDays(toDaysAgo).atStartOfDay(zone).toInstant().toEpochMilli()
                return days.filter { it.date in from..to && it.steps > 0 }.map { it.steps }
            }
            val thisWeek = WeeklyReport.summarize(thisWeekNights, stepsBetween(6, 0))
            val lastWeek = WeeklyReport.summarize(lastWeekNights, stepsBetween(13, 7)).takeIf { it.nights >= 3 }
            WeeklyReport.text(thisWeek, lastWeek, inputs.sleepGoalMinutes)?.let { (title, body) ->
                notify(ctx, ID_WEEKLY, title, body)
                prefs.lastWeeklyReportWeek = weekKey
            }
        }

        // 3. Wind-down reminder — optional, 15–45 minutes before the suggested bedtime.
        if (prefs.bedtimeReminder && prefs.lastBedtimeReminderDay != today.toString()) {
            BedtimeGuidance.compute(inputs.history + listOfNotNull(night), inputs.sleepGoalMinutes, zone)?.let { g ->
                val minsToBed = minutesUntil(local.toLocalTime(), g.bedtime)
                if (minsToBed in 15..45) {
                    notify(
                        ctx, ID_BEDTIME, "Time to wind down",
                        "Aim to be in bed by ${BedtimeGuidance.fmt(g.bedtime)} to get your ${inputs.sleepGoalMinutes / 60} hours before your usual ${BedtimeGuidance.fmt(g.usualWake)} wake-up.",
                    )
                    prefs.lastBedtimeReminderDay = today.toString()
                }
            }
        }
        return Result.success()
    }

    companion object {
        private const val CHANNEL_ID = "guidance"
        private const val WORK_NAME = "daily_guidance"
        private const val ID_VITALS = 3001
        private const val ID_WEEKLY = 3002
        private const val ID_BEDTIME = 3003

        /** Minutes from [now] until [target] later today or, if already past, tomorrow. */
        internal fun minutesUntil(now: LocalTime, target: LocalTime): Int {
            val diff = (target.toSecondOfDay() - now.toSecondOfDay()) / 60
            return if (diff < 0) diff + 24 * 60 else diff
        }

        fun schedule(context: Context) {
            createChannel(context)
            val request = PeriodicWorkRequestBuilder<GuidanceWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        private fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(CHANNEL_ID, "Daily guidance", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Unusual-signal alerts, your weekly report and wind-down reminders"
                }
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
            }
        }

        private fun canNotify(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        @android.annotation.SuppressLint("MissingPermission") // checked by canNotify before every call
        private fun notify(context: Context, id: Int, title: String, body: String) {
            val intent = Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            val pending = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build()
            NotificationManagerCompat.from(context).notify(id, n)
        }
    }
}
