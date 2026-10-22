package com.pulseloop.notifications

import com.pulseloop.ui.i18n.tr
import com.pulseloop.ui.i18n.trf

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
    var mealReminder: Boolean
        get() = p.getBoolean("mealReminder", false)
        set(v) = p.edit().putBoolean("mealReminder", v).apply()

    internal var lastVitalsAlertDay: String?
        get() = p.getString("lastVitalsAlertDay", null)
        set(v) = p.edit().putString("lastVitalsAlertDay", v).apply()
    internal var lastWeeklyReportWeek: String?
        get() = p.getString("lastWeeklyReportWeek", null)
        set(v) = p.edit().putString("lastWeeklyReportWeek", v).apply()
    internal var lastBedtimeReminderDay: String?
        get() = p.getString("lastBedtimeReminderDay", null)
        set(v) = p.edit().putString("lastBedtimeReminderDay", v).apply()
    var bedtimeBattery: Boolean
        get() = p.getBoolean("bedtimeBattery", true)
        set(v) = p.edit().putBoolean("bedtimeBattery", v).apply()
    var inactivityAlerts: Boolean
        get() = p.getBoolean("inactivityAlerts", false)
        set(v) = p.edit().putBoolean("inactivityAlerts", v).apply()
    var activityProgress: Boolean
        get() = p.getBoolean("activityProgress", false)
        set(v) = p.edit().putBoolean("activityProgress", v).apply()

    internal var lastBatteryReminderDay: String?
        get() = p.getString("lastBatteryReminderDay", null)
        set(v) = p.edit().putString("lastBatteryReminderDay", v).apply()
    internal var lastInactivityAt: Long
        get() = p.getLong("lastInactivityAt", 0L)
        set(v) = p.edit().putLong("lastInactivityAt", v).apply()
    var medReminders: Boolean
        get() = p.getBoolean("medReminders", true)
        set(v) = p.edit().putBoolean("medReminders", v).apply()
    internal var remindedDoses: Set<String>
        get() = p.getStringSet("remindedDoses", emptySet()).orEmpty()
        set(v) = p.edit().putStringSet("remindedDoses", v).apply()
    internal var lastProgressDay: String?
        get() = p.getString("lastProgressDay", null)
        set(v) = p.edit().putString("lastProgressDay", v).apply()
    /** "<date>:lunch" / "<date>:dinner" of the last meal reminder sent. */
    internal var lastMealReminder: String?
        get() = p.getString("lastMealReminder", null)
        set(v) = p.edit().putString("lastMealReminder", v).apply()
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
        // Settings goals follow the coach goal's plan, re-checked weekly as the scale moves. Before
        // the notification gate: it changes no notification and must run without that permission.
        runCatching { com.pulseloop.service.GoalSync.sync(ctx, PulseLoopDatabase.getInstance(ctx)) }
        if (!canNotify(ctx)) return Result.success()
        val prefs = GuidancePrefs(ctx)
        val db = PulseLoopDatabase.getInstance(ctx)
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val local = Instant.ofEpochMilli(now).atZone(zone)
        val today = local.toLocalDate()
        val inputs = runCatching { ReadinessRepository.loadInputs(db, now, zone) }.getOrNull() ?: return Result.success()
        runCatching { com.pulseloop.service.StepCalibration.sample(ctx, now, zone) }
        // Keep the "what changed" log current even when the screen isn't opened.
        runCatching { com.pulseloop.service.FindingsRepository.refresh(db, now, zone) }

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
            WeeklyReport.text(thisWeek, lastWeek, inputs.sleepGoalMinutes)?.let { (title, report) ->
                // Drinks, for users who track them: the week that is ending, in one line.
                val drinks = if (com.pulseloop.service.DrinkPrefs.enabled) runCatching {
                    val d = com.pulseloop.service.DrinksRepository.load(db, now, zone)
                    trf(" Drinks: {0} of {1}, ", com.pulseloop.service.DrinkInsights.fmt(d.week.standardDrinks), com.pulseloop.service.DrinkInsights.fmt(d.weeklyLimit)) +
                        trf("{0} dry day{1}.", d.week.dryDays, if (d.week.dryDays == 1) "" else "s")
                }.getOrNull().orEmpty() else ""
                val body = report + drinks
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
                        ctx, ID_BEDTIME, tr("Time to wind down"),
                        trf("Aim to be in bed by {0} to get your {1} hours before your usual {2} wake-up.", BedtimeGuidance.fmt(g.bedtime), inputs.sleepGoalMinutes / 60, BedtimeGuidance.fmt(g.usualWake)),
                    )
                    prefs.lastBedtimeReminderDay = today.toString()
                }
            }
        }
        // 5. Charge before bed — the ring can't track a night it runs out on.
        val bedtime = BedtimeGuidance.compute(inputs.history + listOfNotNull(night), inputs.sleepGoalMinutes, zone)?.bedtime
        if (prefs.bedtimeBattery && prefs.lastBatteryReminderDay != today.toString()) {
            val battery = db.deviceDao().currentReal()?.batteryPercent
            val minsToBed = bedtime?.let { minutesUntil(local.toLocalTime(), it) }
            if (Nudges.chargeBeforeBed(battery, minsToBed)) {
                notify(ctx, ID_BATTERY, tr("Charge your ring before bed"), trf("It's at {0}%. A short charge now keeps tonight's sleep tracked.", battery))
                prefs.lastBatteryReminderDay = today.toString()
            }
        }

        // 6 and 7 are nudges to move, so Rest Mode silences them.
        val resting = com.pulseloop.service.RestMode.isOn(ctx)
        val todayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
        if (prefs.inactivityAlerts && !resting && now - prefs.lastInactivityAt >= 2 * 3_600_000L) {
            val device = db.deviceDao().currentReal()
            val buckets = db.activityBucketDao().byDay(todayStart)
            val fresh = device?.lastSyncAt?.let { now - it <= 40 * 60_000L } == true && buckets.isNotEmpty()
            val lastHour = buckets.filter { it.startEpoch >= now - 3_600_000L }.sumOf { it.steps }
            if (Nudges.inactive(local.hour, lastHour, fresh)) {
                notify(ctx, ID_INACTIVE, tr("Time to move"), tr("Under 100 steps in the last hour. A few minutes on your feet helps your energy and your sleep."))
                prefs.lastInactivityAt = now
            }
        }
        if (prefs.activityProgress && !resting && prefs.lastProgressDay != today.toString()) {
            val goal = db.userGoalDao().get()?.steps ?: 0
            val steps = db.activityDailyDao().byDay(todayStart)?.steps ?: 0
            Nudges.progress(local.hour, steps, goal)?.let { (title, body) ->
                notify(ctx, ID_PROGRESS, title, body)
                prefs.lastProgressDay = today.toString()
            }
        }

        // 8. Medication doses: once per dose, from its time until it would count as missed.
        runCatching {
            val meds = db.medDao().active()
            if (meds.isNotEmpty() && prefs.medReminders) {
                val answers = db.medDao().dosesSince(today.toEpochDay())
                val due = com.pulseloop.service.Meds.dosesOn(today, meds, answers, local.toLocalDateTime())
                    .filter { it.state == com.pulseloop.service.Meds.State.DUE && !local.toLocalTime().isBefore(java.time.LocalTime.parse(it.time)) }
                val fresh = due.filter { it.id !in prefs.remindedDoses }
                if (fresh.isNotEmpty()) {
                    val names = fresh.joinToString(", ") { it.med.name + (it.med.dose?.let { d -> " $d" } ?: "") }
                    notify(ctx, ID_MEDS, trf("Time for {0}", fresh.first().med.name) + if (fresh.size > 1) " and more" else "",
                        trf("{0}. Open PulseLoop to mark it taken or skipped.", names))
                    prefs.remindedDoses = (prefs.remindedDoses + fresh.map { it.id }).toList().takeLast(50).toSet()
                }
            }
        }
        // 4. Meal reminder — optional, and only while nothing has been logged for that meal.
        if (prefs.mealReminder && db.userGoalDao().get()?.nutritionEnabled == true) {
            val meals = db.mealEntryDao().byDay(todayStart).map { it.timestamp }
            MealReminder.due(local.toLocalTime(), meals.map { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() })?.let { slot ->
                val key = "$today:$slot"
                if (prefs.lastMealReminder != key) {
                    val (title, body) = MealReminder.text(slot)
                    notify(ctx, ID_MEAL, title, body, openMealLog = true)
                    prefs.lastMealReminder = key
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
        private const val ID_MEAL = 3004
        private const val ID_BATTERY = 3005
        private const val ID_INACTIVE = 3006
        private const val ID_PROGRESS = 3007
        private const val ID_MEDS = 3008

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
                    description = tr("Unusual-signal alerts, your weekly report and wind-down reminders")
                }
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
            }
        }

        private fun canNotify(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        @android.annotation.SuppressLint(tr("MissingPermission")) // checked by canNotify before every call
        private fun notify(context: Context, id: Int, title: String, body: String, openMealLog: Boolean = false) {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                if (openMealLog) action = MainActivity.ACTION_LOG_MEAL
            }
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

/** The decisions behind the battery, inactivity and step-progress nudges. */
object Nudges {
    /** Battery at or under 30 % between one and two and a half hours before the usual bedtime. */
    fun chargeBeforeBed(battery: Int?, minsToBed: Int?): Boolean =
        battery != null && battery in 0..30 && minsToBed != null && minsToBed in 60..150

    /** Between 09:00 and 19:59, under 100 steps in the last hour, on data synced recently enough to trust. */
    fun inactive(hour: Int, stepsLastHour: Int, dataFresh: Boolean): Boolean =
        dataFresh && hour in 9..19 && stepsLastHour < 100

    /** At 18:00–19:59, when the day is under 70 % of the step goal: how far there is still to go. */
    fun progress(hour: Int, steps: Int, goal: Int): Pair<String, String>? {
        if (hour !in 18..19 || goal <= 0 || steps >= goal * 0.7) return null
        val left = goal - steps
        val minutes = (left / 100.0).let { kotlin.math.ceil(it).toInt() }
        return "${"%,d".format(left)} steps to your goal" to
            trf("You're at {0} of {1}. About {2} minutes of walking closes it.", "%,d".format(steps), "%,d".format(goal), minutes)
    }
}

/**
 * When a meal reminder is due: lunch from 13:30 if nothing has been logged since 11:00, dinner
 * from 20:30 if nothing since 17:00. Each window closes 90 minutes later so a phone that was
 * asleep doesn't nag about lunch at 17:00.
 */
object MealReminder {
    enum class Slot { LUNCH, DINNER }

    private data class Window(val slot: Slot, val from: LocalTime, val until: LocalTime, val coversSince: LocalTime)

    private val WINDOWS = listOf(
        Window(Slot.LUNCH, LocalTime.of(13, 30), LocalTime.of(15, 0), LocalTime.of(11, 0)),
        Window(Slot.DINNER, LocalTime.of(20, 30), LocalTime.of(22, 0), LocalTime.of(17, 0)),
    )

    fun due(now: LocalTime, mealsToday: List<LocalTime>): Slot? {
        val w = WINDOWS.firstOrNull { !now.isBefore(it.from) && now.isBefore(it.until) } ?: return null
        val logged = mealsToday.any { !it.isBefore(w.coversSince) && !it.isAfter(now) }
        return if (logged) null else w.slot
    }

    fun text(slot: Slot): Pair<String, String> = when (slot) {
        Slot.LUNCH -> "Log your lunch?" to "Snap a photo or scan a barcode. It takes a few seconds and helps PulseLoop link food to your sleep."
        Slot.DINNER -> "Log your dinner?" to "A quick photo is enough. Late meals show up in your sleep and resting heart rate."
    }
}
