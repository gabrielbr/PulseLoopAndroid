package com.pulseloop.service

import com.pulseloop.ui.i18n.tr
import com.pulseloop.ui.i18n.trf

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * "What changed": each night compared with the user's own trailing history, written down so it
 * can be browsed later instead of living only in a notification (OpenStrap Edge's findings log).
 * A day where nothing stood out is a complete answer, not an empty state.
 */

data class Finding(
    val day: LocalDate,
    /** Stable per day: one row per kind per day, so a re-run replaces rather than duplicates. */
    val kind: Kind,
    val title: String,
    val detail: String,
) {
    enum class Kind(val positive: Boolean) {
        RESTING_HR_UP(false), RESTING_HR_DOWN(true),
        HRV_DOWN(false), HRV_UP(true),
        TEMPERATURE_UP(false),
        SLEEP_SHORT(false), SLEEP_LONGER(true),
        RADAR(false),
    }
}

object Findings {
    /** Nights of history needed before any comparison is made. */
    const val MIN_BASELINE = 14
    private const val BASELINE_DAYS = 28L

    private fun median(v: List<Double>): Double? {
        if (v.isEmpty()) return null
        val s = v.sorted(); val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
    }

    /** Median absolute deviation, floored so a very steady baseline can't make noise look big. */
    private fun mad(v: List<Double>, floor: Double): Double {
        val m = median(v) ?: return floor
        return maxOf(median(v.map { abs(it - m) }) ?: floor, floor)
    }

    /**
     * Findings for [day] (a waking day), from nights up to and including it. Recent windows are
     * compared with the 28 nights before them; each detector needs a 14-night baseline and a
     * change that is both big in absolute terms and large against the user's usual spread.
     */
    fun forDay(day: LocalDate, nights: List<NightSummary>, radar: SymptomRadar.Level? = null): List<Finding> {
        val upTo = nights.filter { !it.day.isAfter(day) }.sortedBy { it.day }
        fun recent(n: Long) = upTo.filter { it.day.isAfter(day.minusDays(n)) }
        fun baseline(n: Long) = upTo.filter { !it.day.isAfter(day.minusDays(n)) && it.day.isAfter(day.minusDays(n + BASELINE_DAYS)) }
        val out = mutableListOf<Finding>()

        // Resting heart rate: the last 3 nights against the 28 before.
        run {
            val now = recent(3).mapNotNull { it.restingHr }
            val base = baseline(3).mapNotNull { it.restingHr }
            if (now.size < 2 || base.size < MIN_BASELINE) return@run
            val m = median(now)!!; val b = median(base)!!
            val delta = m - b
            if (delta >= 3 && delta >= 2 * mad(base, 1.0)) {
                out += Finding(day, Finding.Kind.RESTING_HR_UP, tr("Resting heart rate up"),
                    trf("{0} bpm over the last 3 nights against your usual {1}. Illness, alcohol, stress, heat or hard training can all raise it.", m.roundToInt(), b.roundToInt()))
            }
        }
        // Resting heart rate trending down over two weeks: usually fitness or recovery.
        run {
            val now = recent(14).mapNotNull { it.restingHr }
            val base = baseline(14).mapNotNull { it.restingHr }
            if (now.size < 10 || base.size < MIN_BASELINE) return@run
            val delta = median(now)!! - median(base)!!
            if (delta <= -3) {
                out += Finding(day, Finding.Kind.RESTING_HR_DOWN, tr("Resting heart rate trending down"),
                    trf("{0} bpm lower over two weeks than the four before. Often a sign of improving fitness or recovery.", abs(delta).roundToInt()))
            }
        }
        // HRV: the last 3 nights against the 28 before.
        run {
            val now = recent(3).mapNotNull { it.hrv }
            val base = baseline(3).mapNotNull { it.hrv }
            if (now.size < 2 || base.size < MIN_BASELINE) return@run
            val m = median(now)!!; val b = median(base)!!
            if (b <= 0) return@run
            if (m <= b * 0.85 && b - m >= 2 * mad(base, 2.0)) {
                out += Finding(day, Finding.Kind.HRV_DOWN, tr("HRV lower than usual"),
                    trf("{0} ms over the last 3 nights against your usual {1} ({2}%). Your body may be under extra load.", m.roundToInt(), b.roundToInt(), ((m - b) / b * 100).roundToInt()))
            }
        }
        run {
            val now = recent(14).mapNotNull { it.hrv }
            val base = baseline(14).mapNotNull { it.hrv }
            if (now.size < 10 || base.size < MIN_BASELINE) return@run
            val m = median(now)!!; val b = median(base)!!
            if (b > 0 && m >= b * 1.10) {
                out += Finding(day, Finding.Kind.HRV_UP, tr("HRV trending up"),
                    trf("{0}% higher over two weeks than the four before.", ((m - b) / b * 100).roundToInt()))
            }
        }
        // Skin temperature: last night against the 28 before.
        run {
            val t = upTo.lastOrNull { it.day == day }?.temperature ?: return@run
            val base = baseline(1).mapNotNull { it.temperature }
            if (base.size < MIN_BASELINE) return@run
            val b = median(base)!!
            val delta = t - b
            if (delta >= 0.5 && delta >= 2 * mad(base, 0.1)) {
                out += Finding(day, Finding.Kind.TEMPERATURE_UP, tr("Skin temperature up"),
                    tr("+%.1f °C last night against your usual. Can come with illness, a late workout, alcohol or the menstrual cycle.").format(delta))
            }
        }
        // Sleep: the last 7 nights against the 28 before.
        run {
            val now = recent(7).map { it.asleepMinutes.toDouble() }.filter { it > 0 }
            val base = baseline(7).map { it.asleepMinutes.toDouble() }.filter { it > 0 }
            if (now.size < 5 || base.size < MIN_BASELINE) return@run
            val delta = now.average() - median(base)!!
            if (delta <= -45) {
                out += Finding(day, Finding.Kind.SLEEP_SHORT, tr("Sleeping less this week"),
                    trf("{0} min a night less than your usual over the last 7 nights.", abs(delta).roundToInt()))
            } else if (delta >= 45) {
                out += Finding(day, Finding.Kind.SLEEP_LONGER, tr("Sleeping more this week"),
                    trf("{0} min a night more than your usual over the last 7 nights.", delta.roundToInt()))
            }
        }
        if (radar != null && radar != SymptomRadar.Level.NONE) {
            out += Finding(day, Finding.Kind.RADAR, trf("Symptom Radar: {0}", radar.label.lowercase()), radar.summary)
        }
        return out
    }
}
