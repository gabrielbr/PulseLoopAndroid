package com.pulseloop.service

import com.pulseloop.ui.i18n.tr
import com.pulseloop.ui.i18n.trf

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.roundToInt

/*
 * Daily guidance (docs/health-app-feature-research.md): small, concrete prompts built from data
 * the app already has. Pure logic; GuidanceWorker and the Today cards only deliver it.
 *
 *  - VitalsCheck:   Apple-Vitals-style "something's off" — last night's signals against the
 *                   user's own typical range; alert only when two or more are outside it.
 *  - Bedtime:       Samsung/Oura-style bedtime window from the usual wake time and sleep goal,
 *                   plus a caffeine cutoff (Ultrahuman) derived from it.
 *  - WeeklyReport:  this week vs last week, in plain words, with one focus.
 */

/** One overnight signal outside the user's typical range. */
data class OffSignal(val title: String, val detail: String)

object VitalsCheck {
    /** Nights needed before a typical range means anything. */
    const val MIN_NIGHTS = 7
    /** Signals that must be out of range together before alerting (Apple Vitals uses two). */
    const val ALERT_THRESHOLD = 2

    /**
     * Last night's resting HR, HRV, SpO₂ and sleep duration, each against the 10th–90th percentile
     * of the same signal over earlier nights. Outside that band is "unusual for you", not
     * "abnormal": one out-of-range night is common, which is why [shouldAlert] needs two at once.
     */
    fun offSignals(lastNight: NightSummary, history: List<NightSummary>): List<OffSignal> {
        val out = mutableListOf<OffSignal>()
        fun check(title: String, value: Double?, past: List<Double>, unit: String, higherIsWorse: Boolean, minDelta: Double) {
            if (value == null || past.size < MIN_NIGHTS) return
            val sorted = past.sorted()
            val lo = percentile(sorted, 0.10) - minDelta
            val hi = percentile(sorted, 0.90) + minDelta
            val usual = percentile(sorted, 0.5)
            val fmt = { v: Double -> if (unit == "h") SleepFormat.duration((v * 60).roundToInt()) else "${v.roundToInt()}$unit" }
            when {
                value > hi && higherIsWorse -> out += OffSignal(title, trf("{0}, higher than your usual {1}", fmt(value), fmt(usual)))
                value < lo && !higherIsWorse -> out += OffSignal(title, trf("{0}, lower than your usual {1}", fmt(value), fmt(usual)))
            }
        }
        check(tr("Resting heart rate"), lastNight.restingHr, history.mapNotNull { it.restingHr }, " bpm", higherIsWorse = true, minDelta = 2.0)
        check("HRV", lastNight.hrv, history.mapNotNull { it.hrv }, " ms", higherIsWorse = false, minDelta = 3.0)
        check(tr("Blood oxygen"), lastNight.spo2, history.mapNotNull { it.spo2 }, "%", higherIsWorse = false, minDelta = 1.0)
        check(
            tr("Sleep"), lastNight.asleepMinutes.takeIf { it > 0 }?.let { it / 60.0 },
            history.filter { it.asleepMinutes > 0 }.map { it.asleepMinutes / 60.0 }, "h", higherIsWorse = false, minDelta = 0.5,
        )
        return out
    }

    fun shouldAlert(signals: List<OffSignal>) = signals.size >= ALERT_THRESHOLD

    fun alertText(signals: List<OffSignal>): Pair<String, String> =
        tr("Some of your signals look unusual") to
            signals.joinToString("; ") { "${it.title}: ${it.detail}" } +
            ". This can happen with illness, stress, alcohol or a hard day. Consider taking it easy."
}

object BedtimeGuidance {
    const val MIN_NIGHTS = 5
    /** Common advice: no caffeine in the 8 hours before bed. */
    const val CAFFEINE_HOURS_BEFORE_BED = 8L

    data class Result(
        /** Start and end of the suggested window to be in bed, local time. */
        val windowStart: LocalTime,
        val windowEnd: LocalTime,
        val usualWake: LocalTime,
        val caffeineCutoff: LocalTime,
    ) {
        val bedtime: LocalTime get() = windowStart.plusMinutes(15)
    }

    /**
     * Bed window = usual wake time − (sleep goal ÷ usual efficiency), ±15 minutes. Using the
     * user's own efficiency means someone who takes a while to fall asleep is told to go to bed
     * earlier, not just told to sleep 8 hours. Null with fewer than [MIN_NIGHTS] recent nights.
     */
    fun compute(nights: List<NightSummary>, goalMinutes: Int, zone: ZoneId = ZoneId.systemDefault()): Result? {
        val recent = nights.sortedByDescending { it.day }.take(14).filter { it.asleepMinutes > 0 && it.endAt > it.startAt }
        if (recent.size < MIN_NIGHTS || goalMinutes <= 0) return null
        // Median wake time, in minutes past local midnight.
        val wakes = recent.map { Instant.ofEpochMilli(it.endAt).atZone(zone).toLocalTime().toSecondOfDay() / 60 }.sorted()
        val wake = wakes[wakes.size / 2]
        val effs = recent.map { it.asleepMinutes.toDouble() / ((it.endAt - it.startAt) / 60_000.0) }.filter { it in 0.5..1.0 }.sorted()
        val eff = if (effs.isEmpty()) 0.9 else effs[effs.size / 2]
        val inBed = (goalMinutes / eff).roundToInt()
        val bedtime = LocalTime.ofSecondOfDay(0).plusMinutes(wake.toLong()).minusMinutes(inBed.toLong())
        return Result(
            windowStart = bedtime.minusMinutes(15),
            windowEnd = bedtime.plusMinutes(15),
            usualWake = LocalTime.ofSecondOfDay(wake * 60L),
            caffeineCutoff = bedtime.minusHours(CAFFEINE_HOURS_BEFORE_BED),
        )
    }

    fun fmt(t: LocalTime): String = "%02d:%02d".format(t.hour, t.minute)
}

object WeeklyReport {

    data class Week(
        val avgAsleepMinutes: Int?,
        val avgRestingHr: Double?,
        val avgHrv: Double?,
        val avgSteps: Int?,
        val nights: Int,
    )

    fun summarize(nights: List<NightSummary>, dailySteps: List<Int>): Week = Week(
        avgAsleepMinutes = nights.filter { it.asleepMinutes > 0 }.map { it.asleepMinutes }.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
        avgRestingHr = nights.mapNotNull { it.restingHr }.takeIf { it.isNotEmpty() }?.average(),
        avgHrv = nights.mapNotNull { it.hrv }.takeIf { it.isNotEmpty() }?.average(),
        avgSteps = dailySteps.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
        nights = nights.size,
    )

    /**
     * Title and body for the weekly notification: up to four "this week vs last week" lines and
     * one focus, the area that moved most in the wrong direction. Null with fewer than 3 nights
     * this week: a report built on one night is noise.
     */
    fun text(thisWeek: Week, lastWeek: Week?, sleepGoalMinutes: Int): Pair<String, String>? {
        if (thisWeek.nights < 3) return null
        val lines = mutableListOf<String>()
        var focus: String? = null
        var worst = 0.0

        thisWeek.avgAsleepMinutes?.let { now ->
            val prev = lastWeek?.avgAsleepMinutes
            lines += trf("Sleep {0} a night", SleepFormat.duration(now)) + (prev?.let { trf(" ({0} min vs last week)", signed(now - it)) } ?: "")
            val short = (sleepGoalMinutes - now).toDouble() / sleepGoalMinutes
            if (short > 0.1 && short > worst) { worst = short; focus = trf("Aim for an earlier bedtime: you averaged {0} against a {1} goal.", SleepFormat.duration(now), SleepFormat.duration(sleepGoalMinutes)) }
        }
        thisWeek.avgRestingHr?.let { now ->
            val prev = lastWeek?.avgRestingHr
            lines += trf("Resting heart rate {0} bpm", now.roundToInt()) + (prev?.let { trf(" ({0} vs last week)", signed((now - it).roundToInt())) } ?: "")
            if (prev != null) {
                val rise = (now - prev) / prev
                if (rise > 0.05 && rise > worst) { worst = rise; focus = tr("Your resting heart rate is up on last week. Prioritise recovery and sleep.") }
            }
        }
        thisWeek.avgHrv?.let { now ->
            val prev = lastWeek?.avgHrv
            lines += "HRV ${now.roundToInt()} ms" + (prev?.let { trf(" ({0} vs last week)", signed((now - it).roundToInt())) } ?: "")
            if (prev != null && prev > 0) {
                val drop = (prev - now) / prev
                if (drop > 0.1 && drop > worst) { worst = drop; focus = tr("Your HRV dipped this week. Lighter days and consistent sleep help it recover.") }
            }
        }
        thisWeek.avgSteps?.let { now ->
            val prev = lastWeek?.avgSteps
            lines += tr("%,d steps a day").format(now) + (prev?.let { trf(" ({0} vs last week)", signed(now - it)) } ?: "")
            if (prev != null && prev > 0) {
                val drop = (prev - now).toDouble() / prev
                if (drop > 0.15 && drop > worst) { worst = drop; focus = tr("You moved less than last week. A daily walk is an easy way back.") }
            }
        }
        if (lines.isEmpty()) return null
        val body = lines.joinToString(". ") + ". " + (focus ?: tr("A steady week. Keep it up."))
        return "Your week in review" to body
    }

    private fun signed(v: Int) = if (v >= 0) "+%,d".format(v) else "%,d".format(v)
}

private fun percentile(sorted: List<Double>, f: Double): Double {
    if (sorted.isEmpty()) return 0.0
    val rank = f * (sorted.size - 1)
    val lo = rank.toInt()
    val hi = minOf(lo + 1, sorted.lastIndex)
    return sorted[lo] + (sorted[hi] - sorted[lo]) * (rank - lo)
}
