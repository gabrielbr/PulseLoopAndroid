package com.pulseloop.service

import com.pulseloop.ui.i18n.tr
import com.pulseloop.ui.i18n.trf

import com.pulseloop.data.PulseLoopDatabase
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * An Oura-style daily activity score (docs/oura-ux-analysis.md phase 4), built from what the ring
 * and the app record: daily step totals, today's 15-minute step buckets and logged workouts.
 *
 * Oura's six contributors are inactivity (stay active, move every hour), amount (meet daily goals,
 * training frequency, training volume) and recovery time. Without minute-level intensity, "stay
 * active" becomes today's progress toward the step goal; the rest map directly. Like readiness this
 * is computed by the app, not measured by the ring, and says so.
 */
object ActivityScore {

    data class Result(val value: Int, val contributors: List<ScoreContributor>) {
        val band: Readiness.Band get() = Readiness.Band.of(value)
        val derived: Boolean = true
    }

    data class Workout(val startAt: Long, val minutes: Int)

    data class Inputs(
        val stepsToday: Int?,
        val stepGoal: Int,
        /** Completed days before today, most recent first. Days without a ring record are absent. */
        val pastDailySteps: List<Int>,
        /** Today's step buckets: (bucket start millis, steps). */
        val todayBuckets: List<Pair<Long, Int>>,
        /** Finished workouts in the last 7 days. */
        val workouts: List<Workout>,
        val now: Long,
        val zone: ZoneId = ZoneId.systemDefault(),
    )

    /** Waking window that "move every hour" judges, in local hours. */
    private const val DAY_START_HOUR = 8
    private const val DAY_END_HOUR = 21
    /** Steps in an hour below this count as an inactive hour (about 3 minutes of walking). */
    const val ACTIVE_HOUR_STEPS = 250

    fun compute(inputs: Inputs): Result? {
        if (inputs.stepsToday == null && inputs.pastDailySteps.isEmpty()) return null
        val parts = listOfNotNull(
            dailyGoal(inputs),
            meetDailyGoals(inputs),
            moveEveryHour(inputs),
            trainingFrequency(inputs),
            trainingVolume(inputs),
            recoveryTime(inputs),
        )
        val value = ScoreContributor.combine(parts) ?: return null
        return Result(value, parts)
    }

    internal fun dailyGoal(i: Inputs): ScoreContributor? {
        val steps = i.stepsToday ?: return null
        if (i.stepGoal <= 0) return null
        val r = steps.toDouble() / i.stepGoal
        return ScoreContributor(
            tr("Daily goal"),
            Readiness.curve(r, listOf(0.0 to 0.0, 0.5 to 50.0, 0.8 to 80.0, 1.0 to 100.0)),
            tr("%,d of %,d steps").format(steps, i.stepGoal),
            tr("Today's steps against your step goal. It fills up as the day goes on."),
            0.25,
        )
    }

    internal fun meetDailyGoals(i: Inputs): ScoreContributor? {
        val week = i.pastDailySteps.take(7)
        if (week.size < 3 || i.stepGoal <= 0) return null
        val met = week.count { it >= i.stepGoal }
        return ScoreContributor(
            tr("Meet daily goals"),
            Readiness.curve(met.toDouble(), listOf(0.0 to 20.0, 1.0 to 40.0, 2.0 to 55.0, 3.0 to 70.0, 5.0 to 100.0)),
            trf("Goal met on {0} of the last {1} days", met, week.size),
            tr("How many recent days you reached your step goal. Aim for at least 3 days a week, ideally 5 or more."),
            0.20,
        )
    }

    internal fun moveEveryHour(i: Inputs): ScoreContributor? {
        if (i.todayBuckets.isEmpty()) return null
        val nowLocal = Instant.ofEpochMilli(i.now).atZone(i.zone)
        val lastFullHour = minOf(nowLocal.hour, DAY_END_HOUR)
        val hours = (DAY_START_HOUR until lastFullHour).toList()
        if (hours.size < 2) return null
        val byHour = i.todayBuckets.groupBy { Instant.ofEpochMilli(it.first).atZone(i.zone).hour }
            .mapValues { (_, b) -> b.sumOf { it.second } }
        val inactive = hours.count { (byHour[it] ?: 0) < ACTIVE_HOUR_STEPS }
        return ScoreContributor(
            tr("Move every hour"),
            Readiness.curve(inactive.toDouble(), listOf(0.0 to 100.0, 1.0 to 90.0, 3.0 to 70.0, 5.0 to 45.0, 8.0 to 10.0)),
            if (inactive == 0) trf("Active every hour since {0}:00", DAY_START_HOUR) else trf("{0} inactive {1} since {2}:00", inactive, if (inactive == 1) tr("hour") else tr("hours"), DAY_START_HOUR),
            tr("Long stretches without moving are linked to poorer health, even on days you exercise. A short walk every hour helps."),
            0.15,
        )
    }

    private fun recentWorkouts(i: Inputs) = i.workouts.filter { it.startAt >= i.now - 7 * 86_400_000L && it.minutes > 0 }

    internal fun trainingFrequency(i: Inputs): ScoreContributor {
        val n = recentWorkouts(i).size
        return ScoreContributor(
            tr("Training frequency"),
            Readiness.curve(n.toDouble(), listOf(0.0 to 30.0, 1.0 to 55.0, 2.0 to 75.0, 3.0 to 100.0)),
            trf("{0} {1} in the last 7 days", n, if (n == 1) tr("workout") else tr("workouts")),
            tr("How often you exercised this week. Three or more sessions a week is a good target."),
            0.15,
        )
    }

    internal fun trainingVolume(i: Inputs): ScoreContributor {
        val minutes = recentWorkouts(i).sumOf { it.minutes }
        return ScoreContributor(
            tr("Training volume"),
            Readiness.curve(minutes.toDouble(), listOf(0.0 to 30.0, 60.0 to 60.0, 150.0 to 100.0)),
            trf("{0} of exercise in the last 7 days", SleepFormat.duration(minutes)),
            tr("Total workout time this week. Health guidelines suggest at least 150 minutes of moderate activity a week."),
            0.15,
        )
    }

    internal fun recoveryTime(i: Inputs): ScoreContributor {
        val days = recentWorkouts(i).map { Instant.ofEpochMilli(it.startAt).atZone(i.zone).toLocalDate() }.toSet().size
        val rest = 7 - days
        return ScoreContributor(
            tr("Recovery time"),
            Readiness.curve(rest.toDouble(), listOf(0.0 to 50.0, 1.0 to 80.0, 2.0 to 100.0)),
            trf("{0} rest {1} in the last 7", rest, if (rest == 1) tr("day") else tr("days")),
            tr("Rest days let your body adapt to training. One or two easier days a week is enough for most people."),
            0.10,
        )
    }

    /** Loads today's inputs from Room (real rows only) and computes the score. */
    suspend fun load(db: PulseLoopDatabase, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Result? {
        val todayStart = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        val daily = db.activityDailyDao().recentReal(limit = 8)
        val today = daily.firstOrNull { it.date == todayStart }
        val past = daily.filter { it.date < todayStart && it.steps > 0 }.sortedByDescending { it.date }.map { it.steps }
        val buckets = db.activityBucketDao().byDay(todayStart)
            .filterNot { com.pulseloop.data.DemoDataPolicy.isDemo(it.source) }
            .map { it.startEpoch to it.steps }
        val workouts = db.activitySessionDao().finishedSince(now - 7 * 86_400_000L).mapNotNull { w ->
            val end = w.endedAt ?: return@mapNotNull null
            Workout(w.startedAt, (((end - w.startedAt) / 1000.0 - w.totalPauseSeconds) / 60.0).roundToInt())
        }
        val goal = db.userGoalDao().get()?.steps ?: com.pulseloop.data.entity.UserGoalEntity.DEFAULT_STEPS
        return compute(Inputs(today?.steps, goal, past, buckets, workouts, now, zone))
    }
}
