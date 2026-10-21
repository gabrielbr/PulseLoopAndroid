package com.pulseloop.health.accuracy

import com.pulseloop.health.accuracy.AccuracyComparison.Summary
import com.pulseloop.health.accuracy.AccuracyComparison.Verdict
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The report's words (plan, "The report"). Says "differs from <reference>", never "is wrong by":
 * the reference is the device the user chose to trust, not ground truth.
 */
object AccuracyReportText {

    fun verdictLabel(v: Verdict): String = when (v) {
        Verdict.AGREES -> "Agrees"
        Verdict.CONSISTENTLY_HIGH -> "Consistently high"
        Verdict.CONSISTENTLY_LOW -> "Consistently low"
        Verdict.INCONSISTENT -> "Inconsistent"
        Verdict.NOT_ENOUGH_DATA -> "Not enough data"
    }

    /** A signed difference in the metric's own terms: "−18 %", "+25 min", "+3.2 bpm". */
    fun signed(m: ComparisonMetric, d: Double): String {
        val sign = if (d < 0) "−" else "+"
        val a = abs(d)
        return sign + magnitude(m, a)
    }

    fun magnitude(m: ComparisonMetric, a: Double): String = when (m) {
        ComparisonMetric.STEPS, ComparisonMetric.DISTANCE, ComparisonMetric.ACTIVE_CALORIES -> "${a.roundToInt()} %"
        ComparisonMetric.SLEEP_ASLEEP, ComparisonMetric.SLEEP_ONSET, ComparisonMetric.SLEEP_WAKE -> minutes(a)
        ComparisonMetric.HR_REST, ComparisonMetric.HR_ACTIVE, ComparisonMetric.RESTING_HR -> "%.1f bpm".format(a)
        ComparisonMetric.SPO2 -> "%.1f points".format(a)
        ComparisonMetric.HRV -> "${a.roundToInt()} ms"
        ComparisonMetric.SLEEP_STAGES -> "${a.roundToInt()} min"
    }

    fun minutes(a: Double): String {
        val m = a.roundToInt()
        return if (m < 60) "$m min" else "${m / 60} h ${m % 60} min"
    }

    /** A value as one device read it, for the per-day rows. */
    fun value(m: ComparisonMetric, v: Double?): String {
        if (v == null) return "—"
        return when (m) {
            ComparisonMetric.STEPS -> "%,d".format(v.roundToInt())
            ComparisonMetric.DISTANCE -> "%.2f km".format(v / 1000.0)
            ComparisonMetric.ACTIVE_CALORIES -> "${v.roundToInt()} kcal"
            ComparisonMetric.SLEEP_ASLEEP -> minutes(v)
            ComparisonMetric.SLEEP_ONSET, ComparisonMetric.SLEEP_WAKE -> clock(v)
            ComparisonMetric.RESTING_HR -> "%.0f bpm".format(v)
            ComparisonMetric.HRV -> "${v.roundToInt()} ms"
            else -> "%.1f".format(v)
        }
    }

    /** Minutes from the waking day's midnight (negative = the evening before) as a clock time. */
    fun clock(minutesFromMidnight: Double): String {
        val m = Math.floorMod(minutesFromMidnight.roundToInt(), 24 * 60)
        return "%02d:%02d".format(m / 60, m % 60)
    }

    private fun higher(m: ComparisonMetric): Pair<String, String> = when (m) {
        ComparisonMetric.STEPS -> "more steps" to "fewer steps"
        ComparisonMetric.DISTANCE -> "more distance" to "less distance"
        ComparisonMetric.ACTIVE_CALORIES -> "more active calories" to "fewer active calories"
        ComparisonMetric.SLEEP_ASLEEP -> "more time asleep" to "less time asleep"
        ComparisonMetric.SLEEP_ONSET -> "you fell asleep later" to "you fell asleep earlier"
        ComparisonMetric.SLEEP_WAKE -> "you woke later" to "you woke earlier"
        else -> "higher" to "lower"
    }

    /** The one-line reading under a card's headline. */
    fun reading(s: Summary, ref: String): String {
        val m = s.metric
        if (m == ComparisonMetric.SLEEP_STAGES) {
            val share = s.stageAgreement
            return when {
                s.verdict == Verdict.NOT_ENOUGH_DATA -> "Not enough nights with stages from both devices yet (${s.validDays} of ${m.minDays})."
                share == null -> "No minutes to compare."
                else -> "Both name the same stage for ${(share * 100).roundToInt()} % of the night. Stages are described, not corrected: " +
                    "vendors stage sleep differently."
            }
        }
        val nightly = m.source == ReferenceMetric.SLEEP || m == ComparisonMetric.RESTING_HR || m == ComparisonMetric.HRV
        fun count(n: Int) = "$n ${if (nightly) "night" else "day"}${if (n == 1) "" else "s"}"
        val days = count(s.validDays)
        val (up, down) = higher(m)
        val dirWord = if (s.bias > 0) up else down
        val amount = magnitude(m, abs(s.bias))
        val isTiming = m == ComparisonMetric.SLEEP_ONSET || m == ComparisonMetric.SLEEP_WAKE
        return when (s.verdict) {
            Verdict.NOT_ENOUGH_DATA -> if (s.pairs < m.minPairs && s.validDays >= m.minDays) {
                "Not enough matching readings yet (${s.pairs} of ${m.minPairs})."
            } else "Not enough data yet: $days of ${m.minDays} needed."
            Verdict.AGREES -> "Agrees with $ref: ${signed(m, s.bias)} on average over $days, inside the ±${magnitude(m, s.band)} band."
            Verdict.CONSISTENTLY_HIGH, Verdict.CONSISTENTLY_LOW -> {
                val share = (s.sameSignShare * s.validDays).roundToInt()
                val what = when {
                    isTiming -> "By about $amount, the ring says $dirWord than $ref"
                    m.kind == ComparisonMetric.Kind.PAIRED || m == ComparisonMetric.RESTING_HR || m == ComparisonMetric.HRV ->
                        "Your ring reads about $amount $dirWord than $ref"
                    else -> "Your ring records about $amount $dirWord than $ref"
                }
                "$what, on $share of ${count(s.validDays)}." +
                    if (m.correction != null) " A correction would help." else ""
            }
            Verdict.INCONSISTENT -> {
                val (lo, hi) = s.limits
                "Differs from $ref by ${magnitude(m, s.mae)} on average, in no steady direction " +
                    "(usually between ${signed(m, lo)} and ${signed(m, hi)}). A single correction can't fix that."
            }
        }
    }

    /** Why a day doesn't count, for the per-day rows. */
    fun dayNote(note: String?, ref: String): String? = when (note) {
        AccuracyScorer.NOTE_WAITING -> "waiting for $ref to deliver"
        AccuracyScorer.NOTE_NOT_WORN -> "not worn on both"
        AccuracyScorer.NOTE_NO_REF_NIGHT -> "no night from $ref"
        AccuracyScorer.NOTE_NO_REF_STAGES -> "$ref gave no stages"
        AccuracyScorer.NOTE_FEW_PAIRS -> "too few matching readings"
        else -> null
    }

    /** Worst first: how far outside its band each failing metric is. */
    fun worstFirst(summaries: List<Summary>): List<Summary> = summaries
        .filter { it.verdict == Verdict.CONSISTENTLY_HIGH || it.verdict == Verdict.CONSISTENTLY_LOW || it.verdict == Verdict.INCONSISTENT }
        .filter { it.metric.kind != ComparisonMetric.Kind.STAGES }
        .sortedByDescending { maxOf(abs(it.bias), it.mae) / it.band.coerceAtLeast(1e-6) }

    /**
     * The shareable summary (plan, Phase 5): verdicts and headline differences with the ring's
     * model and firmware — the kind of line that helps in an issue ("my SR08 counts ~20 % fewer
     * steps"). Aggregates only, no day-by-day values and no readings from either device, for the
     * same reason the diagnostics export carries none: this is text people paste in public.
     */
    fun share(
        ringLabel: String,
        model: String?,
        firmware: String?,
        referenceLabel: String,
        span: String,
        summaries: List<Summary>,
    ): String = buildString {
        appendLine("PulseLoop accuracy check: $ringLabel vs $referenceLabel ($span)")
        val ring = listOfNotNull(model?.takeIf { it.isNotBlank() }, firmware?.takeIf { it.isNotBlank() }?.let { "firmware $it" })
        if (ring.isNotEmpty()) appendLine("Ring: " + ring.joinToString(", "))
        for (s in summaries) {
            val headline = when {
                s.verdict == Verdict.NOT_ENOUGH_DATA -> "not enough data"
                s.metric.kind == ComparisonMetric.Kind.STAGES -> s.stageAgreement?.let { "same stage ${(it * 100).roundToInt()} % of the night" } ?: "no overlap"
                else -> "${signed(s.metric, s.bias)} on average, ${verdictLabel(s.verdict).lowercase()} (${s.validDays} ${if (s.metric.source == ReferenceMetric.SLEEP || s.metric == ComparisonMetric.RESTING_HR || s.metric == ComparisonMetric.HRV) "nights" else "days"})"
            }
            appendLine("• ${s.metric.label}: $headline")
        }
        append("Agreement with the reference device, not a lab measurement.")
    }
}
