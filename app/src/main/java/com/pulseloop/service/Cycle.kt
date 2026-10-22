package com.pulseloop.service

import com.pulseloop.ui.i18n.tr

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Cycle phase from the dates the user logs, nothing else (OpenStrap Edge's rule): the day of the
 * cycle, whether a period is on, and when the next one is likely from this user's own cycle
 * lengths. Deliberately no fertile window: a population-average estimate presented on a health
 * screen would be read as a measurement.
 */
object Cycle {
    enum class Phase(private val labelEn: String, private val noteEn: String) {
        MENSTRUAL("Period", "Energy and sleep can dip; resting heart rate is often at its lowest."),
        FOLLICULAR("Follicular", "After the period, until about two weeks before the next one."),
        LUTEAL("Luteal", "The two weeks before a period: skin temperature and resting heart rate often run a little higher."),
        LATE("Period later than usual", "Later than your usual cycle length. Log it when it starts."),
        UNKNOWN("Cycle", "Log two periods to see phases and an estimate of the next one.");
        val label: String get() = tr(labelEn)
        val note: String get() = tr(noteEn)
    }

    data class Period(val start: LocalDate, val end: LocalDate?)

    data class Status(
        val cycleDay: Int,
        val phase: Phase,
        val inPeriod: Boolean,
        val usualLength: Int?,
        val nextStart: LocalDate?,
        val daysToNext: Int?,
    )

    /** Cycle lengths between consecutive starts, keeping plausible ones (15–60 days). */
    fun lengths(periods: List<Period>): List<Int> =
        periods.map { it.start }.distinct().sorted().zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b).toInt() }
            .filter { it in 15..60 }

    fun status(periods: List<Period>, today: LocalDate): Status? {
        val past = periods.filter { !it.start.isAfter(today) }.sortedBy { it.start }
        val last = past.lastOrNull() ?: return null
        val day = ChronoUnit.DAYS.between(last.start, today).toInt() + 1
        // A period with no end logged is taken as on for up to 7 days.
        val inPeriod = last.end?.let { !today.isAfter(it) } ?: (day <= 7)
        val usual = lengths(past).takeLast(6).sorted().takeIf { it.isNotEmpty() }?.let { it[it.size / 2] }
        val next = usual?.let { last.start.plusDays(it.toLong()) }
        val toNext = next?.let { ChronoUnit.DAYS.between(today, it).toInt() }
        val phase = when {
            inPeriod -> Phase.MENSTRUAL
            toNext == null -> Phase.UNKNOWN
            toNext < 0 -> Phase.LATE
            toNext <= 13 -> Phase.LUTEAL
            else -> Phase.FOLLICULAR
        }
        return Status(day, phase, inPeriod, usual, next, toNext)
    }
}
