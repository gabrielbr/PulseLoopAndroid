package com.pulseloop.service

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * Readiness: an Oura-style morning score built from what a ring actually measures.
 * Design and the reasoning behind every threshold: docs/oura-ux-analysis.md.
 *
 * Three layers, each usable on its own:
 *  1. [ReadingQuality]  — drop isolated spikes from a vital series before anything reads it.
 *  2. [NightlyVitals]   — resting HR and HRV from the sleep window only, plus how much of the
 *                         night the ring actually covered.
 *  3. [Readiness]       — contributors scored against the user's own baseline, combined into one
 *                         0–100 score, or an explicit "still learning" state.
 *
 * Like DerivedStress, this is an inference, not a measurement: every result says so
 * ([Readiness.Result.Score.derived]), and a thin baseline yields Learning, never a default number.
 */

/** Spike removal for ring vital series. */
object ReadingQuality {

    /**
     * Two-sided Hampel filter: drops a sample only when it sits more than [threshold] robust
     * deviations from the median of its [halfWindow] neighbours on the left AND from those on the
     * right. Optical rings produce isolated spikes (a 140 bpm sample between two 55s while the
     * wearer is asleep, an HRV of 180 among 40s) that a minimum or a mean would otherwise latch
     * onto. A spike disagrees with both sides; the first or last sample of a genuine change agrees
     * with one of them, which a single centred window gets wrong. The first and last samples have
     * only one side and are always kept.
     *
     * Input order does not matter; output is sorted by time.
     */
    fun dropSpikes(
        samples: List<VitalSample>,
        halfWindow: Int = 3,
        threshold: Double = 3.0,
        /** Floor for the deviation so a perfectly flat neighbourhood doesn't reject ±1 bpm. */
        minDeviation: Double = 2.0,
    ): List<VitalSample> {
        val sorted = samples.sortedBy { it.timestampMs }
        if (sorted.size < 3) return sorted
        fun farFrom(value: Double, side: List<Double>): Boolean {
            val median = median(side) ?: return false
            val mad = median(side.map { abs(it - median) }) ?: 0.0
            // 1.4826 scales MAD to a standard deviation for normally distributed data.
            val spread = (1.4826 * mad).coerceAtLeast(minDeviation)
            return abs(value - median) > threshold * spread
        }
        return sorted.filterIndexed { i, s ->
            if (i == 0 || i == sorted.lastIndex) return@filterIndexed true
            val left = sorted.subList((i - halfWindow).coerceAtLeast(0), i).map { it.value }
            val right = sorted.subList(i + 1, (i + 1 + halfWindow).coerceAtMost(sorted.size)).map { it.value }
            !(farFrom(s.value, left) && farFrom(s.value, right))
        }
    }
}

/** One night's vitals, read only from samples inside the sleep window. */
data class NightSummary(
    /** The waking day this night belongs to (the morning it ended). */
    val day: LocalDate,
    val startAt: Long,
    val endAt: Long,
    val asleepMinutes: Int,
    /** Lowest sustained heart rate of the night, or null when the night has too few samples. */
    val restingHr: Double?,
    /** Median HRV of the night, or null when too few samples. */
    val hrv: Double?,
    /**
     * Share (0–1) of the night's half-hour slots that hold at least one heart-rate sample. A night
     * the ring covered 30% of is not the same evidence as one it covered fully, and the UI says so.
     */
    val coverage: Double,
    /** Median SpO₂ inside the sleep window (70–100 only), or null when too few readings. */
    val spo2: Double? = null,
    /** Median skin temperature inside the sleep window (°C), null on rings without the sensor. */
    val temperature: Double? = null,
    /** When the night's sustained lowest heart rate happened (recovery index). */
    val minHrAt: Long? = null,
    /** Readings behind the night's numbers, for the "data behind this score" view. */
    val hrSamples: Int = 0,
    val hrvSamples: Int = 0,
    /**
     * Numbers withheld tonight and why ("hrv" → "…"), so the UI says "not measured" with a
     * reason instead of guessing (OpenStrap Edge refuses an artefact night the same way).
     */
    val withheld: Map<String, String> = emptyMap(),
) {
    /** Clock midpoint of the night, in minutes past local midnight of the waking day (may be negative). */
    fun midpointMinutes(zone: ZoneId): Int {
        val midpoint = Instant.ofEpochMilli((startAt + endAt) / 2).atZone(zone)
        val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
        return ((midpoint.toInstant().toEpochMilli() - dayStart) / 60_000L).toInt()
    }
}

object NightlyVitals {
    /** ~30 minutes of a 5-minute all-day HR grid. Fewer than this is not a night's resting HR. */
    const val MIN_HR_SAMPLES = 6
    const val MIN_HRV_SAMPLES = 3
    private const val SLOT_MS = 30 * 60_000L

    fun summarize(
        day: LocalDate,
        startAt: Long,
        endAt: Long,
        asleepMinutes: Int,
        heartRate: List<VitalSample>,
        hrv: List<VitalSample>,
        spo2: List<VitalSample> = emptyList(),
        temperature: List<VitalSample> = emptyList(),
    ): NightSummary {
        fun inNight(s: VitalSample) = s.timestampMs in startAt..endAt && s.value > 0
        val hr = ReadingQuality.dropSpikes(heartRate.filter(::inNight))
        val hrvNight = ReadingQuality.dropSpikes(hrv.filter(::inNight), minDeviation = 4.0)

        // Lowest 3-sample rolling mean, not the single lowest sample: Oura's "lowest heart rate"
        // is a sustained low, and one dropped beat must not set the night's number.
        val windows = hr.windowed(3)
        val lowest = if (hr.size >= MIN_HR_SAMPLES) windows.minByOrNull { w -> w.sumOf { it.value } } else null
        val restingHr = lowest?.map { it.value }?.average()

        val hrvGate = hrvQuality(hrvNight.map { it.value })
        val hrvValue = if (hrvGate == null) median(hrvNight.map { it.value }) else null
        val withheld = buildMap {
            hrvGate?.let { put("hrv", it) }
            if (hr.size < MIN_HR_SAMPLES) put("restingHr", "Only ${hr.size} heart-rate reading${if (hr.size == 1) "" else "s"} during the night ($MIN_HR_SAMPLES needed)")
        }

        val slots = ((endAt - startAt) / SLOT_MS).toInt().coerceAtLeast(1)
        val covered = hr.map { ((it.timestampMs - startAt) / SLOT_MS).toInt().coerceIn(0, slots - 1) }.toSet().size
        return NightSummary(
            day = day,
            startAt = startAt,
            endAt = endAt,
            asleepMinutes = asleepMinutes,
            restingHr = restingHr?.let { (it * 10).roundToInt() / 10.0 },
            hrv = hrvValue?.let { (it * 10).roundToInt() / 10.0 },
            coverage = (covered.toDouble() / slots).coerceIn(0.0, 1.0),
            spo2 = spo2.filter { it.timestampMs in startAt..endAt && it.value in 70.0..100.0 }
                .takeIf { it.size >= MIN_HRV_SAMPLES }
                ?.let { s -> median(s.map { it.value }) }?.let { (it * 10).roundToInt() / 10.0 },
            temperature = temperature.filter { it.timestampMs in startAt..endAt && it.value in 30.0..42.0 }
                .takeIf { it.size >= MIN_HRV_SAMPLES }
                ?.let { s -> median(s.map { it.value }) }?.let { (it * 100).roundToInt() / 100.0 },
            minHrAt = lowest?.get(1)?.timestampMs,
            hrSamples = hr.size,
            hrvSamples = hrvNight.size,
            withheld = withheld,
        )
    }
}

/**
 * Whether a night's HRV readings can stand for the night, or why not. A ring reports HRV as a
 * few vendor values a night, so the checks are about those values, not beat intervals: too few,
 * all identical (a stored value repeated, not measurements), or scattered so widely that their
 * middle means nothing. Null when the night's HRV can be used.
 */
internal fun hrvQuality(values: List<Double>): String? {
    if (values.isEmpty()) return null
    if (values.size < NightlyVitals.MIN_HRV_SAMPLES) {
        return "Only ${values.size} HRV reading${if (values.size == 1) "" else "s"} during the night (${NightlyVitals.MIN_HRV_SAMPLES} needed)"
    }
    if (values.size >= 4 && values.distinct().size == 1) {
        return "Every HRV reading was the same value, which looks like a repeated stored number rather than a measurement"
    }
    val sorted = values.sorted()
    val med = sorted[sorted.size / 2]
    val mad = values.map { kotlin.math.abs(it - med) }.sorted()[values.size / 2]
    if (med > 0 && mad / med > HRV_MAX_SPREAD) {
        return "HRV readings were scattered too widely to trust (half were more than ${(mad / med * 100).roundToInt()}% from the middle)"
    }
    return null
}

/** Median absolute deviation over median above which a night's HRV is noise, not a level. */
internal const val HRV_MAX_SPREAD = 0.5

object Readiness {

    /** Readiness needs at least this many contributors carrying at least this share of the weight. */
    const val MIN_CONTRIBUTORS = 2
    const val MIN_WEIGHT_SHARE = 0.5

    /** Oura asks for "up to two weeks"; a week of nights is the least that is a baseline at all. */
    const val MIN_BASELINE_NIGHTS = 7
    const val MIN_REGULARITY_NIGHTS = 5
    const val MIN_ACTIVITY_DAYS = 7

    enum class Band(val label: String) {
        OPTIMAL("Optimal"), GOOD("Good"), FAIR("Fair"), PAY_ATTENTION("Pay attention");

        companion object {
            /** Oura's published bands: 85+ optimal, 70–84 good, 60–69 fair, below 60 pay attention. */
            fun of(score: Int): Band = when {
                score >= 85 -> OPTIMAL
                score >= 70 -> GOOD
                score >= 60 -> FAIR
                else -> PAY_ATTENTION
            }
        }
    }

    /**
     * Oura's nine contributors. Body temperature and recovery index need a temperature sensor and
     * dense night heart rate; when a ring never provides them they are left out (not "missing")
     * and the rest are renormalised.
     */
    enum class ContributorKind(val title: String, val weight: Double, val optionalSensor: Boolean = false) {
        // The original seven keep their proportions (×0.8), so a ring without the optional
        // sensors scores exactly as it did before they existed.
        RESTING_HR("Resting heart rate", 0.16),
        HRV_BALANCE("HRV balance", 0.16),
        BODY_TEMPERATURE("Body temperature", 0.10, optionalSensor = true),
        RECOVERY_INDEX("Recovery index", 0.10, optionalSensor = true),
        SLEEP("Sleep", 0.16),
        SLEEP_BALANCE("Sleep balance", 0.12),
        SLEEP_REGULARITY("Sleep regularity", 0.04),
        PREVIOUS_DAY_ACTIVITY("Previous day activity", 0.08),
        ACTIVITY_BALANCE("Activity balance", 0.08),
    }

    data class Contributor(
        val kind: ContributorKind,
        val score: Int,
        /** Plain-language reason, e.g. "4 bpm above your usual 56". */
        val detail: String,
    ) {
        val band: Band get() = Band.of(score)
    }

    sealed class Result {
        data class Score(
            val value: Int,
            val contributors: List<Contributor>,
            /** Contributors that couldn't be scored today, so the UI can say what's missing. */
            val missing: List<ContributorKind>,
            /** Last night's ring coverage (0–1); low coverage lowers how much the score means. */
            val nightCoverage: Double?,
            /** Always true: readiness is computed by the app, the ring never measured it. */
            val derived: Boolean = true,
        ) : Result() {
            val band: Band get() = Band.of(value)
        }

        /** Not enough nights yet for a personal baseline. */
        data class Learning(val nightsCollected: Int, val nightsNeeded: Int) : Result()

        /** No sleep recorded for last night: there is nothing to be ready *from*. */
        data object NoSleep : Result()

        /**
         * The baseline exists but last night's readings can't carry a score: shown as "—" with the
         * reasons rather than a number built from whatever happened to survive.
         */
        data class Withheld(val reasons: List<String>, val nightCoverage: Double?) : Result()
    }

    data class Inputs(
        /** Last night, if the ring recorded one. */
        val lastNight: NightSummary?,
        /** Earlier nights, any order, last night excluded. Up to ~60 is useful. */
        val history: List<NightSummary>,
        val sleepGoalMinutes: Int,
        /** Daily step totals for completed days before today, most recent first (yesterday at 0). */
        val dailySteps: List<Int>,
        val zone: ZoneId = ZoneId.systemDefault(),
    )

    fun compute(inputs: Inputs): Result {
        val night = inputs.lastNight ?: return Result.NoSleep
        val nightsWithVitals = inputs.history.count { it.restingHr != null || it.hrv != null }
        if (nightsWithVitals < MIN_BASELINE_NIGHTS) {
            return Result.Learning(nightsWithVitals, MIN_BASELINE_NIGHTS)
        }

        val contributors = listOfNotNull(
            restingHr(night, inputs.history),
            hrvBalance(night, inputs.history),
            bodyTemperature(night, inputs.history),
            recoveryIndex(night),
            sleep(night, inputs.sleepGoalMinutes),
            sleepBalance(night, inputs.history, inputs.sleepGoalMinutes),
            sleepRegularity(night, inputs.history, inputs.zone),
            previousDayActivity(inputs.dailySteps),
            activityBalance(inputs.dailySteps),
        )
        // A score from sleep alone is a sleep score; readiness needs the body's own signal, and
        // enough of the whole that what survived isn't a guess.
        val weightSum = contributors.sumOf { it.kind.weight }
        val applicableWeight = ContributorKind.entries
            .filter { !(it.optionalSensor && !sensorSeen(it, night, inputs.history)) }
            .sumOf { it.weight }
        if (contributors.none { it.kind == ContributorKind.RESTING_HR || it.kind == ContributorKind.HRV_BALANCE } ||
            contributors.size < MIN_CONTRIBUTORS || weightSum < applicableWeight * MIN_WEIGHT_SHARE
        ) {
            val reasons = night.withheld.values.toList().ifEmpty {
                listOf("Last night's heart rate and HRV readings weren't enough to score recovery")
            }
            return Result.Withheld(reasons, night.coverage)
        }

        val value = (contributors.sumOf { it.score * it.kind.weight } / weightSum).roundToInt().coerceIn(0, 100)
        val present = contributors.map { it.kind }.toSet()
        return Result.Score(
            value = value,
            contributors = contributors,
            missing = ContributorKind.entries.filter { k ->
                k !in present && !(k.optionalSensor && !sensorSeen(k, night, inputs.history))
            },
            nightCoverage = night.coverage,
        )
    }

    /** Whether the ring has ever supplied what an optional-sensor contributor needs. */
    private fun sensorSeen(k: ContributorKind, night: NightSummary, history: List<NightSummary>): Boolean =
        (listOf(night) + history).any {
            when (k) {
                ContributorKind.BODY_TEMPERATURE -> it.temperature != null
                ContributorKind.RECOVERY_INDEX -> it.minHrAt != null
                else -> true
            }
        }

    // ── Contributors ─────────────────────────────────────────────────────────────────────────
    // Each maps a deviation from the user's own baseline onto 0–100 with a piecewise-linear curve:
    // a band around "usual" scores full marks, and the score falls off as the night moves away.

    internal fun restingHr(night: NightSummary, history: List<NightSummary>): Contributor? {
        val rhr = night.restingHr ?: return null
        val base = median(history.mapNotNull { it.restingHr }.takeIf { it.size >= MIN_BASELINE_NIGHTS }) ?: return null
        val delta = rhr - base
        // Oura: the score drops when RHR sits 3–5 bpm above, or 10–15 below, the usual average.
        val score = curve(delta, listOf(-15.0 to 60.0, -10.0 to 100.0, 1.0 to 100.0, 3.0 to 85.0, 5.0 to 65.0, 8.0 to 40.0, 12.0 to 15.0))
        val d = delta.roundToInt()
        val detail = when {
            d == 0 -> "Right at your usual ${base.roundToInt()} bpm"
            d > 0 -> "$d bpm above your usual ${base.roundToInt()}"
            else -> "${-d} bpm below your usual ${base.roundToInt()}"
        }
        return Contributor(ContributorKind.RESTING_HR, score, detail)
    }

    internal fun hrvBalance(night: NightSummary, history: List<NightSummary>): Contributor? {
        val hrv = night.hrv ?: return null
        val values = history.sortedByDescending { it.day }.mapNotNull { it.hrv }
        if (values.size < MIN_BASELINE_NIGHTS) return null
        val base = median(values) ?: return null
        if (base <= 0) return null
        // Oura weighs recent nights more: blend last night with the two before it.
        val recent = listOf(hrv) + values.take(2)
        val weights = listOf(0.5, 0.3, 0.2).take(recent.size)
        val recentHrv = recent.zip(weights).sumOf { (v, w) -> v * w } / weights.sum()
        val ratio = recentHrv / base
        val score = curve(ratio, listOf(0.6 to 20.0, 0.75 to 50.0, 0.85 to 70.0, 0.95 to 90.0, 1.0 to 100.0))
        val pct = ((ratio - 1) * 100).roundToInt()
        val detail = when {
            abs(pct) < 3 -> "In line with your usual ${base.roundToInt()} ms"
            pct > 0 -> "$pct% above your usual ${base.roundToInt()} ms"
            else -> "${-pct}% below your usual ${base.roundToInt()} ms"
        }
        return Contributor(ContributorKind.HRV_BALANCE, score, detail)
    }

    /** Skin temperature against the user's usual: within ±0.3 °C is full marks, either way off costs. */
    internal fun bodyTemperature(night: NightSummary, history: List<NightSummary>): Contributor? {
        val t = night.temperature ?: return null
        val base = median(history.mapNotNull { it.temperature }.takeIf { it.size >= MIN_BASELINE_NIGHTS }) ?: return null
        val delta = t - base
        val score = curve(abs(delta), listOf(0.3 to 100.0, 0.5 to 80.0, 1.0 to 45.0, 1.5 to 15.0))
        val detail = when {
            abs(delta) < 0.1 -> "At your usual"
            delta > 0 -> "+%.1f °C from your usual".format(delta)
            else -> "−%.1f °C from your usual".format(-delta)
        }
        return Contributor(ContributorKind.BODY_TEMPERATURE, score, detail)
    }

    /**
     * How early in the night the heart rate bottomed out. Oura: the lowest heart rate in the first
     * half of the night means the body had time to recover; reaching it just before waking means it
     * was still working (a late meal, alcohol, a hard evening session).
     */
    internal fun recoveryIndex(night: NightSummary): Contributor? {
        val at = night.minHrAt ?: return null
        val span = (night.endAt - night.startAt).takeIf { it > 0 } ?: return null
        val fraction = ((at - night.startAt).toDouble() / span).coerceIn(0.0, 1.0)
        val score = curve(fraction, listOf(0.5 to 100.0, 0.7 to 75.0, 0.85 to 55.0, 1.0 to 35.0))
        val beforeWake = ((night.endAt - at) / 60_000L).toInt()
        return Contributor(ContributorKind.RECOVERY_INDEX, score, "Lowest heart rate ${SleepFormat.duration(beforeWake)} before waking")
    }

    internal fun sleep(night: NightSummary, goalMinutes: Int): Contributor? {
        if (goalMinutes <= 0 || night.asleepMinutes <= 0) return null
        val score = sleepCurve(night.asleepMinutes.toDouble() / goalMinutes)
        return Contributor(ContributorKind.SLEEP, score, "${SleepFormat.duration(night.asleepMinutes)} of ${SleepFormat.duration(goalMinutes)} goal")
    }

    internal fun sleepBalance(night: NightSummary, history: List<NightSummary>, goalMinutes: Int): Contributor? {
        if (goalMinutes <= 0) return null
        val nights = (listOf(night) + history.sortedByDescending { it.day }.take(13)).filter { it.asleepMinutes > 0 }
        if (nights.size < MIN_BASELINE_NIGHTS) return null
        val avg = nights.map { it.asleepMinutes }.average()
        val score = sleepCurve(avg / goalMinutes)
        return Contributor(ContributorKind.SLEEP_BALANCE, score, "Averaging ${SleepFormat.duration(avg.roundToInt())} over ${nights.size} nights")
    }

    internal fun sleepRegularity(night: NightSummary, history: List<NightSummary>, zone: ZoneId): Contributor? {
        val nights = listOf(night) + history.sortedByDescending { it.day }.take(13)
        if (nights.size < MIN_REGULARITY_NIGHTS) return null
        val mids = nights.map { it.midpointMinutes(zone).toDouble() }
        val mean = mids.average()
        val sd = sqrt(mids.sumOf { (it - mean) * (it - mean) } / mids.size)
        val score = curve(sd, listOf(30.0 to 100.0, 60.0 to 75.0, 90.0 to 50.0, 120.0 to 25.0, 180.0 to 0.0))
        return Contributor(ContributorKind.SLEEP_REGULARITY, score, "Sleep midpoint varies by about ${sd.roundToInt()} min")
    }

    internal fun previousDayActivity(dailySteps: List<Int>): Contributor? {
        if (dailySteps.size < MIN_ACTIVITY_DAYS + 1) return null
        val yesterday = dailySteps.first()
        val usual = dailySteps.drop(1).take(28).average()
        if (usual <= 0) return null
        val ratio = yesterday / usual
        // Both ends cost points: a sedentary day, and a very heavy one that calls for recovery.
        val score = curve(ratio, listOf(0.0 to 30.0, 0.3 to 50.0, 0.7 to 100.0, 1.5 to 100.0, 2.5 to 60.0))
        return Contributor(ContributorKind.PREVIOUS_DAY_ACTIVITY, score, "%,d steps yesterday vs your usual %,d".format(yesterday, usual.roundToInt()))
    }

    internal fun activityBalance(dailySteps: List<Int>): Contributor? {
        if (dailySteps.size < 14) return null
        val week = dailySteps.take(7).average()
        val month = dailySteps.take(28).average()
        if (month <= 0) return null
        val ratio = week / month
        val score = curve(ratio, listOf(0.3 to 30.0, 0.5 to 60.0, 0.8 to 100.0, 1.3 to 100.0, 2.0 to 60.0))
        val pct = ((ratio - 1) * 100).roundToInt()
        val detail = when {
            abs(pct) < 5 -> "This week matches your monthly rhythm"
            pct > 0 -> "This week is $pct% more active than your monthly average"
            else -> "This week is ${-pct}% less active than your monthly average"
        }
        return Contributor(ContributorKind.ACTIVITY_BALANCE, score, detail)
    }

    private fun sleepCurve(ratioOfGoal: Double): Int =
        curve(ratioOfGoal, listOf(0.0 to 0.0, 0.5 to 30.0, 0.7 to 60.0, 0.85 to 80.0, 1.0 to 100.0))

    /** Piecewise-linear interpolation through [points] (sorted by x); flat beyond both ends. */
    internal fun curve(x: Double, points: List<Pair<Double, Double>>): Int {
        if (x <= points.first().first) return points.first().second.roundToInt()
        if (x >= points.last().first) return points.last().second.roundToInt()
        val i = points.indexOfFirst { it.first >= x }
        val (x0, y0) = points[i - 1]
        val (x1, y1) = points[i]
        return (y0 + (y1 - y0) * (x - x0) / (x1 - x0)).roundToInt().coerceIn(0, 100)
    }
}

private fun median(values: List<Double>?): Double? {
    if (values.isNullOrEmpty()) return null
    val s = values.sorted()
    val mid = s.size / 2
    return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2
}

/**
 * The words around the score, kept beside the engine so they're testable and every surface says
 * the same thing. Tone follows Oura's: plain, about the user's own normal, never a diagnosis, and
 * a low score suggests rest rather than assigning blame.
 */
object ReadinessCopy {

    /** One sentence for under the score. */
    fun headline(result: Readiness.Result): String = when (result) {
        is Readiness.Result.Score -> {
            val weakest = result.contributors.minByOrNull { it.score }
                ?.takeIf { it.score < 70 }?.kind?.title?.lowercase()
            when (result.band) {
                Readiness.Band.OPTIMAL -> "You're well recovered. A good day to take on something demanding."
                Readiness.Band.GOOD -> "You're in good shape today. Your usual plans should feel fine."
                Readiness.Band.FAIR -> if (weakest != null)
                    "Your body is still recovering. Go a little easier today and watch your $weakest."
                else "Your body is still recovering. Go a little easier today."
                Readiness.Band.PAY_ATTENTION -> if (weakest != null)
                    "Your body is asking for rest. Make recovery today's priority: your $weakest is furthest from your usual."
                else "Your body is asking for rest. Make recovery today's priority."
            }
        }
        is Readiness.Result.Learning ->
            "Learning your baseline: ${result.nightsCollected} of ${result.nightsNeeded} nights. Keep wearing your ring to bed."
        Readiness.Result.NoSleep -> "No sleep recorded for last night yet. Sync your ring after you wake up."
        is Readiness.Result.Withheld ->
            "No readiness today: ${result.reasons.first().replaceFirstChar { it.lowercase() }}. Wear the ring snugly tonight."
    }

    /** A visible caution when the ring missed much of the night, or null when coverage is fine. */
    fun coverageNote(result: Readiness.Result.Score): String? {
        val c = result.nightCoverage ?: return null
        if (c >= LOW_COVERAGE) return null
        return "Your ring captured only ${(c * 100).roundToInt()}% of last night, so today's score is less certain."
    }

    const val LOW_COVERAGE = 0.6

    const val DERIVED_FOOTER =
        "Readiness is estimated by PulseLoop from your ring's sleep, heart rate, HRV and activity, compared with your own recent nights. It is not a medical measurement."

    /** What each contributor measures, for its detail sheet. */
    fun explanation(kind: Readiness.ContributorKind): String = when (kind) {
        Readiness.ContributorKind.RESTING_HR ->
            "Your lowest sustained heart rate last night, compared with your usual over recent nights. A resting heart rate a few beats above normal can mean your body is working on recovery, stress, or an oncoming illness."
        Readiness.ContributorKind.HRV_BALANCE ->
            "Your heart rate variability over the last few nights, weighted toward last night, compared with your usual. Higher than usual generally means you're well recovered; a drop suggests strain."
        Readiness.ContributorKind.BODY_TEMPERATURE ->
            "Your skin temperature last night compared with your usual. A rise of half a degree or more can come with illness, a hard training load, alcohol or the menstrual cycle."
        Readiness.ContributorKind.RECOVERY_INDEX ->
            "How early in the night your heart rate reached its lowest. An early low means your body had time to recover; a low just before waking means it was still working, often after a late meal, alcohol or an evening workout."
        Readiness.ContributorKind.SLEEP ->
            "How much you slept last night compared with your sleep need, learned from the nights you woke up recovered (your sleep goal until then)."
        Readiness.ContributorKind.SLEEP_BALANCE ->
            "Your average sleep over the last two weeks compared with your goal. It shows whether sleep debt is building up."
        Readiness.ContributorKind.SLEEP_REGULARITY ->
            "How consistent the timing of your sleep has been over the last two weeks. A steady rhythm helps recovery."
        Readiness.ContributorKind.PREVIOUS_DAY_ACTIVITY ->
            "Yesterday's steps compared with your usual. Both a very inactive day and an unusually heavy one lower this."
        Readiness.ContributorKind.ACTIVITY_BALANCE ->
            "This week's activity compared with your monthly average. It flags both overdoing it and a drop in activity."
    }
}
