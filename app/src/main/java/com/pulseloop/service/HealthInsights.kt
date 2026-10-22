package com.pulseloop.service

import com.pulseloop.ui.i18n.tr

import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * Longer-range health insights, ported in approach from anikbharadwaj129/Colmi (Apache-2.0), which
 * models Oura's published definitions where Oura gives them and documented research where it
 * doesn't. Every number here is a PulseLoop estimate from ring data against the user's own normal,
 * never a population threshold, and every surface that shows one says so.
 */

private fun mean(v: List<Double>) = if (v.isEmpty()) null else v.average()
private fun sd(v: List<Double>): Double? {
    if (v.size < 2) return null
    val m = v.average()
    return sqrt(v.sumOf { (it - m) * (it - m) } / v.size)
}
private fun medianOf(v: List<Double>): Double? {
    if (v.isEmpty()) return null
    val s = v.sorted(); val m = s.size / 2
    return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
}

// ── Sleep need and sleep debt ─────────────────────────────────────────────────────────────────

/**
 * How much sleep this user needs: the typical length of recent nights after which their body read
 * as recovered (resting HR at or below their normal, HRV at or above it). Judged on physiology,
 * not on a score, because the score is what this feeds. Until five such nights in the last 30,
 * the user's own sleep goal.
 */
data class SleepNeed(val minutes: Int, val isLearned: Boolean) {
    companion object {
        const val MIN_RECOVERED_NIGHTS = 5

        fun estimate(nights: List<NightSummary>, fallbackMinutes: Int, today: LocalDate): SleepNeed {
            val window = nights.filter { !it.day.isAfter(today) && it.day.isAfter(today.minusDays(30)) }
            val rhrBase = mean(nights.mapNotNull { it.restingHr })
            val hrvBase = mean(nights.mapNotNull { it.hrv })
            val recovered = window.mapNotNull { n ->
                if (n.asleepMinutes <= 0) return@mapNotNull null
                val checks = listOfNotNull(
                    n.restingHr?.let { v -> rhrBase?.let { v <= it } },
                    n.hrv?.let { v -> hrvBase?.let { v >= it } },
                )
                if (checks.isNotEmpty() && checks.all { it }) n.asleepMinutes.toDouble() else null
            }
            if (recovered.size < MIN_RECOVERED_NIGHTS) return SleepNeed(fallbackMinutes, false)
            return SleepNeed(medianOf(recovered)!!.roundToInt().coerceIn(390, 570), true)
        }
    }
}

/**
 * Accumulated shortfall over the last 14 nights: each night adds what it fell short of the need,
 * or pays back its surplus, and older debt fades by 10% a night so recent nights count most.
 * Needs five nights in the window, like Oura's.
 */
data class SleepDebt(val minutes: Int, val needMinutes: Int, val nights: Int) {
    enum class Level(private val labelEn: String) { NONE("None"), LOW("Low"), MODERATE("Moderate"), HIGH("High");
        val label: String get() = tr(labelEn)
    }

    val level: Level get() = when {
        minutes < 15 -> Level.NONE
        minutes < 120 -> Level.LOW
        minutes <= 300 -> Level.MODERATE
        else -> Level.HIGH
    }

    companion object {
        /** [asleepByDay]: the last 14 days oldest first, null for a night with no record. */
        fun compute(asleepByDay: List<Int?>, need: SleepNeed): SleepDebt? {
            val nights = asleepByDay.count { it != null }
            if (nights < 5) return null
            var debt = 0.0
            for (asleep in asleepByDay) {
                debt *= 0.9
                if (asleep != null) debt = maxOf(0.0, debt + need.minutes - asleep)
            }
            return SleepDebt(debt.roundToInt(), need.minutes, nights)
        }

        fun from(nights: List<NightSummary>, need: SleepNeed, today: LocalDate): SleepDebt? {
            val byDay = nights.associateBy { it.day }
            return compute((13 downTo 0).map { byDay[today.minusDays(it.toLong())]?.asleepMinutes?.takeIf { m -> m > 0 } }, need)
        }
    }
}

// ── Symptom Radar ─────────────────────────────────────────────────────────────────────────────

/**
 * Signs that something is straining the body, read the way UCSF's TemPredict and Stanford's
 * wearable work do: several signals moving together against the user's own last four weeks, not
 * one number crossing a line. Temperature and HRV weigh most. "Major" needs two nights in a row.
 * It is never a diagnosis, and the copy says so.
 */
object SymptomRadar {
    enum class Level(private val labelEn: String, private val summaryEn: String) {
        NONE("No signs", "No obvious signs in your biometrics of something straining your body."),
        MINOR("Minor signs", "Small signs in your biometrics of something straining your body."),
        MAJOR("Major signs", "Stronger signs in your biometrics of something straining your body.");
        val label: String get() = tr(labelEn)
        val summary: String get() = tr(summaryEn)
    }

    enum class Metric(private val titleEn: String, val weight: Double, val strainUp: Boolean, val minSd: Double, val unit: String) {
        TEMPERATURE("Body temperature", 1.0, true, 0.15, "°C"),
        TEMPERATURE_TREND("Temperature trend", 1.0, true, 0.12, "°C"),
        RESTING_HR("Resting heart rate", 0.8, true, 1.5, "bpm"),
        HRV("Heart rate variability", 1.0, false, 3.0, "ms"),
        RESPIRATORY_RATE("Respiratory rate", 0.8, true, 0.5, "/min"),
        INACTIVE("Inactivity", 0.5, false, 800.0, "steps");
        val title: String get() = tr(titleEn)
    }

    data class Signal(val metric: Metric, val value: Double, val usual: Double, val z: Double) {
        /** Strain-direction deviations of 1.5 SD or more count as a sign. */
        val flagged: Boolean get() = z >= 1.5
        val detail: String get() {
            val fmt = { v: Double -> if (metric.unit == "steps") "%,d".format(v.roundToInt()) else "%.1f".format(v) }
            val dir = if ((value > usual) == metric.strainUp) (if (metric.strainUp) "higher" else "lower") else "in range"
            return "${fmt(value)} ${metric.unit}, $dir than your usual ${fmt(usual)}"
        }
    }

    data class Result(val level: Level, val signals: List<Signal>, val strain: Double, val baselineNights: Int) {
        val flagged: List<Signal> get() = signals.filter { it.flagged }
    }

    data class Night(
        val day: LocalDate,
        val temperature: Double? = null,
        val restingHr: Double? = null,
        val hrv: Double? = null,
        val respiratoryRate: Double? = null,
        /** Steps the day before the night (low = inactive). */
        val stepsDayBefore: Double? = null,
    )

    const val MIN_BASELINE = 14

    /** Signals for [night] against the 28 nights before it. */
    fun signals(night: Night, history: List<Night>): List<Signal> {
        val base = history.filter { it.day.isBefore(night.day) && it.day.isAfter(night.day.minusDays(29)) }
        fun signal(metric: Metric, value: Double?, past: List<Double>): Signal? {
            if (value == null || past.size < MIN_BASELINE / 2) return null
            val m = past.average()
            val s = maxOf(sd(past) ?: 0.0, metric.minSd)
            val z = (value - m) / s * (if (metric.strainUp) 1 else -1)
            return Signal(metric, value, m, z)
        }
        val recentTemps = (listOf(night) + history.filter { it.day.isBefore(night.day) }.sortedByDescending { it.day })
            .take(3).mapNotNull { it.temperature }
        return listOfNotNull(
            signal(Metric.TEMPERATURE, night.temperature, base.mapNotNull { it.temperature }),
            signal(Metric.TEMPERATURE_TREND, recentTemps.takeIf { it.size == 3 }?.average(), base.mapNotNull { it.temperature }),
            signal(Metric.RESTING_HR, night.restingHr, base.mapNotNull { it.restingHr }),
            signal(Metric.HRV, night.hrv, base.mapNotNull { it.hrv }),
            signal(Metric.RESPIRATORY_RATE, night.respiratoryRate, base.mapNotNull { it.respiratoryRate }),
            signal(Metric.INACTIVE, night.stepsDayBefore, base.mapNotNull { it.stepsDayBefore }),
        )
    }

    private fun strain(signals: List<Signal>) = signals.filter { it.flagged }.sumOf { it.metric.weight * minOf(it.z, 4.0) / 1.5 }

    private fun levelFor(signals: List<Signal>): Level {
        val flagged = signals.filter { it.flagged }
        val s = strain(signals)
        return when {
            flagged.size >= 3 || s >= 3.0 -> Level.MAJOR
            flagged.size >= 2 || s >= 1.8 -> Level.MINOR
            else -> Level.NONE
        }
    }

    /** Today's radar: "major" only when the night before also showed signs. */
    fun compute(nights: List<Night>, today: LocalDate): Result? {
        val sorted = nights.sortedBy { it.day }
        val last = sorted.lastOrNull { it.day == today } ?: return null
        val baseCount = sorted.count { it.day.isBefore(today) && (it.restingHr != null || it.hrv != null) }
        if (baseCount < MIN_BASELINE) return Result(Level.NONE, emptyList(), 0.0, baseCount)
        val sig = signals(last, sorted)
        var level = levelFor(sig)
        if (level == Level.MAJOR) {
            val prev = sorted.lastOrNull { it.day == today.minusDays(1) }
            val prevLevel = prev?.let { levelFor(signals(it, sorted)) } ?: Level.NONE
            if (prevLevel == Level.NONE) level = Level.MINOR
        }
        return Result(level, sig, strain(sig), baseCount)
    }
}

// ── Stress: daytime zones, resilience ─────────────────────────────────────────────────────────

/** The day's stress readings in Oura's four zones (the ring logs 1–100). */
data class DaytimeStress(val restoredMin: Int, val relaxedMin: Int, val engagedMin: Int, val stressedMin: Int) {
    enum class Summary(private val labelEn: String) { RESTORED("Restored"), NORMAL("Normal"), STRESSFUL("Stressful");
        val label: String get() = tr(labelEn)
    }

    val totalMin: Int get() = restoredMin + relaxedMin + engagedMin + stressedMin
    val summary: Summary get() = when {
        totalMin == 0 -> Summary.NORMAL
        stressedMin > restoredMin + 30 && stressedMin >= totalMin * 0.25 -> Summary.STRESSFUL
        restoredMin >= stressedMin * 2 && restoredMin >= totalMin * 0.3 -> Summary.RESTORED
        else -> Summary.NORMAL
    }

    companion object {
        /** [samples] are stress readings (1–100) during the waking day; each stands for [slotMin] minutes. */
        fun compute(samples: List<Double>, slotMin: Int = 30): DaytimeStress? {
            val v = samples.filter { it in 1.0..100.0 }
            if (v.size < 3) return null
            fun count(r: ClosedFloatingPointRange<Double>) = v.count { it in r } * slotMin
            return DaytimeStress(count(1.0..29.999), count(30.0..59.999), count(60.0..79.999), count(80.0..100.0))
        }
    }
}

/**
 * How well the body recovers from stress over the last 14 days: daytime stress against daytime
 * recovery and the nights' restoration (HRV relative to normal). Oura's five levels.
 */
data class Resilience(val level: Level, val daysUsed: Int) {
    enum class Level(private val labelEn: String) { LIMITED("Limited"), ADEQUATE("Adequate"), SOLID("Solid"), STRONG("Strong"), EXCEPTIONAL("Exceptional");
        val label: String get() = tr(labelEn)
    }

    data class Day(val stressedMin: Int, val restoredMin: Int, val hrvRatio: Double?)

    companion object {
        fun compute(days: List<Day>): Resilience? {
            if (days.size < 5) return null
            val stress = days.sumOf { it.stressedMin }.toDouble()
            val restore = days.sumOf { it.restoredMin }.toDouble()
            val dayBalance = if (stress + restore == 0.0) 0.5 else restore / (stress + restore)
            val night = days.mapNotNull { it.hrvRatio }.takeIf { it.size >= 3 }?.average()?.let { ((it - 0.85) / 0.3).coerceIn(0.0, 1.0) } ?: 0.5
            val score = 0.6 * dayBalance + 0.4 * night
            val level = when {
                score >= 0.8 -> Level.EXCEPTIONAL
                score >= 0.65 -> Level.STRONG
                score >= 0.5 -> Level.SOLID
                score >= 0.35 -> Level.ADEQUATE
                else -> Level.LIMITED
            }
            return Resilience(level, days.size)
        }
    }
}

// ── Body clock ────────────────────────────────────────────────────────────────────────────────

/** Chronotype from the usual sleep midpoint, and whether recent nights keep to it. */
data class BodyClock(val chronotype: Chronotype, val usualMidpointMin: Int, val recentShiftMin: Int, val alignment: Alignment) {
    enum class Chronotype(private val labelEn: String) { EARLY("Early bird"), MORNING("Morning type"), INTERMEDIATE("In between"), EVENING("Evening type"), LATE("Night owl");
        val label: String get() = tr(labelEn)
    }
    enum class Alignment(private val labelEn: String) { ALIGNED("In sync"), SLIGHTLY_OFF("Slightly off"), MISALIGNED("Out of sync");
        val label: String get() = tr(labelEn)
    }

    /** "3:15" for a midpoint in minutes past midnight. */
    fun midpointLabel(): String {
        val m = ((usualMidpointMin % 1440) + 1440) % 1440
        return "%d:%02d".format(m / 60, m % 60)
    }

    companion object {
        fun compute(nights: List<NightSummary>, zone: ZoneId = ZoneId.systemDefault()): BodyClock? {
            val sorted = nights.sortedByDescending { it.day }
            if (sorted.size < 10) return null
            val mids = sorted.map { it.midpointMinutes(zone).toDouble() }
            val usual = medianOf(mids.drop(7).ifEmpty { mids })!!
            val recent = mids.take(7).average()
            val shift = (recent - usual).roundToInt()
            val chronotype = when {
                usual < 150 -> Chronotype.EARLY
                usual < 195 -> Chronotype.MORNING
                usual < 255 -> Chronotype.INTERMEDIATE
                usual < 315 -> Chronotype.EVENING
                else -> Chronotype.LATE
            }
            val alignment = when {
                abs(shift) < 30 -> Alignment.ALIGNED
                abs(shift) < 60 -> Alignment.SLIGHTLY_OFF
                else -> Alignment.MISALIGNED
            }
            return BodyClock(chronotype, usual.roundToInt(), shift, alignment)
        }
    }
}

// ── Cardio capacity and cardiovascular age ────────────────────────────────────────────────────

/**
 * VO₂max estimated from resting and maximum heart rate (Uth et al., 2004: 15.3 × HRmax/HRrest,
 * HRmax = 208 − 0.7 × age), and a cardiovascular age read off where that and HRV sit against
 * age norms. Both are coarse estimates — Oura uses pulse-wave velocity, which needs a raw PPG
 * waveform this ring doesn't share — and are labelled so. A ring's own VO₂max wins when present.
 */
data class CardioCapacity(val vo2max: Double, val level: Level, val fromRing: Boolean, val cardioAge: Int?) {
    enum class Level(private val labelEn: String) { LOW("Below average"), AVERAGE("Average"), GOOD("Good"), EXCELLENT("Excellent");
        val label: String get() = tr(labelEn)
    }

    companion object {
        fun expectedVo2(age: Int, female: Boolean): Double = (if (female) 42.0 else 48.0) - 0.32 * (age - 25).coerceAtLeast(0)

        fun compute(restingHr: Double?, hrv: Double?, age: Int?, sex: String?, ringVo2: Double? = null): CardioCapacity? {
            if (age == null || age !in 15..100) return null
            val female = sex?.lowercase() == "female"
            val vo2 = ringVo2?.takeIf { it in 15.0..90.0 }
                ?: restingHr?.takeIf { it in 35.0..110.0 }?.let { 15.3 * (208 - 0.7 * age) / it }
                ?: return null
            val expected = expectedVo2(age, female)
            val ratio = vo2 / expected
            val level = when {
                ratio >= 1.2 -> Level.EXCELLENT
                ratio >= 1.05 -> Level.GOOD
                ratio >= 0.9 -> Level.AVERAGE
                else -> Level.LOW
            }
            // Each ~0.32 ml/kg/min of VO₂max is about a year of age; HRV nudges it ±3 years.
            var cardioAge = age - (vo2 - expected) / 0.32
            if (hrv != null) {
                val expectedHrv = (75 - 0.9 * age).coerceAtLeast(15.0)
                cardioAge -= ((hrv - expectedHrv) / expectedHrv * 6).coerceIn(-3.0, 3.0)
            }
            return CardioCapacity(vo2, level, ringVo2 != null, cardioAge.roundToInt().coerceIn(18, 100))
        }
    }
}

// ── Health areas ──────────────────────────────────────────────────────────────────────────────

/** A long-term area rated on Oura's scale, from a 0–100 score over the last 90 days. */
data class HealthArea(val title: String, val rating: Rating, val score: Int, val trend: List<Double>, val detail: String) {
    enum class Rating(private val labelEn: String) { THRIVING("Thriving"), GOOD("Good"), FAIR("Fair"), NEEDS_CARE("Needs care");
        val label: String get() = tr(labelEn)
    }

    companion object {
        fun rate(score: Int): Rating = when {
            score >= 80 -> Rating.THRIVING
            score >= 65 -> Rating.GOOD
            score >= 50 -> Rating.FAIR
            else -> Rating.NEEDS_CARE
        }

        /** A smooth 0–100 from a ratio of value to target, with 1.0 at about 85. */
        fun scoreOf(ratio: Double): Int = (100 / (1 + exp(-4 * (ratio - 0.6)))).roundToInt().coerceIn(0, 100)
    }
}

// ── Trends ────────────────────────────────────────────────────────────────────────────────────

/** Averages a daily series by week, month or year for the Trends view. */
object Trends {
    enum class Period(private val labelEn: String, val days: Long) { DAY("Day", 30), WEEK("Week", 7 * 26), MONTH("Month", 365), YEAR("Year", 365 * 3);
        val label: String get() = tr(labelEn)
    }

    data class Point(val start: LocalDate, val value: Double)

    fun aggregate(daily: Map<LocalDate, Double>, period: Period): List<Point> {
        val grouped = daily.entries.groupBy { (d, _) ->
            when (period) {
                Period.DAY -> d
                Period.WEEK -> d.minusDays((d.dayOfWeek.value - 1).toLong())
                Period.MONTH -> d.withDayOfMonth(1)
                Period.YEAR -> d.withDayOfYear(1)
            }
        }
        return grouped.map { (k, v) -> Point(k, v.map { it.value }.average()) }.sortedBy { it.start }
    }
}
