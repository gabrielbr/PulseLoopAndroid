package com.pulseloop.health.accuracy

import com.pulseloop.data.entity.AccuracyCheckDayEntity
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * What the report compares (docs/accuracy-check-plan.md, "Per metric"). [band] is the default
 * agreement band — how close counts as agreeing — in [unit], or in percent when [percentBand].
 * [correction] is the form a fix for a consistent bias takes; null means the metric is
 * report-only, because a difference there doesn't show which device is wrong (HRV and calories
 * are computed differently by every vendor; sleep timing and stages are described, not adjusted).
 */
enum class ComparisonMetric(
    val label: String,
    val unit: String,
    val kind: Kind,
    val source: ReferenceMetric,
    val band: Double,
    val percentBand: Boolean,
    val correction: CorrectionKind?,
    /** Valid days (or nights) needed before a verdict. */
    val minDays: Int,
    /** For paired metrics: pairs needed across the check. */
    val minPairs: Int = 0,
) {
    STEPS("Steps", "steps", Kind.DAILY, ReferenceMetric.STEPS, 10.0, true, CorrectionKind.SCALE, 3),
    DISTANCE("Distance", "m", Kind.DAILY, ReferenceMetric.DISTANCE, 15.0, true, CorrectionKind.SCALE, 3),
    ACTIVE_CALORIES("Active calories", "kcal", Kind.DAILY, ReferenceMetric.ACTIVE_CALORIES, 25.0, true, null, 3),
    SLEEP_ASLEEP("Time asleep", "min", Kind.DAILY, ReferenceMetric.SLEEP, 30.0, false, CorrectionKind.OFFSET, 3),
    SLEEP_ONSET("Fell asleep", "min", Kind.DAILY, ReferenceMetric.SLEEP, 20.0, false, null, 3),
    SLEEP_WAKE("Woke up", "min", Kind.DAILY, ReferenceMetric.SLEEP, 20.0, false, null, 3),
    SLEEP_STAGES("Sleep stages", "min", Kind.STAGES, ReferenceMetric.SLEEP, 0.0, false, null, 3),
    HR_REST("Heart rate at rest", "bpm", Kind.PAIRED, ReferenceMetric.HEART_RATE, 5.0, false, CorrectionKind.OFFSET, 3, 500),
    HR_ACTIVE("Heart rate when active", "bpm", Kind.PAIRED, ReferenceMetric.HEART_RATE, 5.0, false, CorrectionKind.OFFSET, 2, 100),
    RESTING_HR("Resting heart rate", "bpm", Kind.DAILY, ReferenceMetric.RESTING_HEART_RATE, 3.0, false, CorrectionKind.OFFSET, 3),
    SPO2("Blood oxygen", "%", Kind.PAIRED, ReferenceMetric.OXYGEN_SATURATION, 2.0, false, CorrectionKind.OFFSET, 3, 30),
    HRV("HRV", "ms", Kind.DAILY, ReferenceMetric.HRV, 10.0, false, null, 3),
    ;

    enum class Kind { DAILY, PAIRED, STAGES }

    companion object {
        fun byName(name: String): ComparisonMetric? = entries.firstOrNull { it.name == name }
        fun forSource(m: ReferenceMetric): List<ComparisonMetric> = entries.filter { it.source == m }
    }
}

enum class CorrectionKind { SCALE, OFFSET }

/** Sleep stages as both sides can express them. */
enum class Stage { AWAKE, LIGHT, DEEP, REM, ASLEEP }

data class StageSpan(val start: Long, val end: Long, val stage: Stage)

/** One night, from either device. Without stage spans, the whole interval counts as asleep. */
data class SleepNight(val start: Long, val end: Long, val spans: List<StageSpan>) {
    private val asleepSpans get() = spans.filter { it.stage != Stage.AWAKE }
    val asleepMinutes: Int get() =
        if (spans.isEmpty()) ((end - start) / 60_000L).toInt()
        else (asleepSpans.sumOf { it.end - it.start } / 60_000L).toInt()
    val onset: Long get() = asleepSpans.minOfOrNull { it.start } ?: start
    val wake: Long get() = asleepSpans.maxOfOrNull { it.end } ?: end
    val hasStages: Boolean get() = spans.any { it.stage == Stage.LIGHT || it.stage == Stage.DEEP || it.stage == Stage.REM }

    fun stageAt(t: Long): Stage? = spans.firstOrNull { t >= it.start && t < it.end }?.stage
}

/** Running sums for paired samples, so days combine exactly. */
data class PairSums(val n: Int = 0, val sumDiff: Double = 0.0, val sumSq: Double = 0.0, val sumAbs: Double = 0.0) {
    operator fun plus(d: Double) = PairSums(n + 1, sumDiff + d, sumSq + d * d, sumAbs + abs(d))
    operator fun plus(o: PairSums) = PairSums(n + o.n, sumDiff + o.sumDiff, sumSq + o.sumSq, sumAbs + o.sumAbs)
    val mean: Double get() = if (n == 0) 0.0 else sumDiff / n
    val sd: Double get() = if (n < 2) 0.0 else sqrt(((sumSq - n * mean * mean) / (n - 1)).coerceAtLeast(0.0))
    val mae: Double get() = if (n == 0) 0.0 else sumAbs / n
}

/**
 * The comparison itself, pure (plan, "Comparison engine"): pairing, the sleep reductions, and the
 * verdict. [AccuracyScorer] reads both devices' data and stores what this returns.
 */
object AccuracyComparison {

    const val DAY_MS = 86_400_000L

    fun epochDay(t: Long, zone: ZoneId): Long = Instant.ofEpochMilli(t).atZone(zone).toLocalDate().toEpochDay()

    // ── Pairing ────────────────────────────────────────────────────────────────────────────

    /**
     * Pairs each ring sample with the nearest reference sample within [toleranceMs] (each
     * reference sample used once at most), and sums the differences `ring − reference` per
     * (day, context). [contextOf] returns null to skip a sample. Both lists sorted by time.
     */
    fun <C> pair(
        ring: List<TimedValue>,
        ref: List<TimedValue>,
        toleranceMs: Long,
        zone: ZoneId,
        contextOf: (Long) -> C?,
    ): Map<Pair<Long, C>, PairSums> {
        val out = linkedMapOf<Pair<Long, C>, PairSums>()
        if (ring.isEmpty() || ref.isEmpty()) return out
        val used = BooleanArray(ref.size)
        var j = 0
        for (s in ring) {
            while (j < ref.size && ref[j].at < s.at - toleranceMs) j++
            var best = -1
            var bestDt = Long.MAX_VALUE
            var k = j
            while (k < ref.size && ref[k].at <= s.at + toleranceMs) {
                val dt = abs(ref[k].at - s.at)
                if (!used[k] && dt < bestDt) { best = k; bestDt = dt }
                k++
            }
            if (best < 0) continue
            val ctx = contextOf(s.at) ?: continue
            used[best] = true
            val key = epochDay(s.at, zone) to ctx
            out[key] = (out[key] ?: PairSums()) + (s.value - ref[best].value)
        }
        return out
    }

    /**
     * Pairing tolerance from the reference's own cadence: a sparse reference (a bridge relaying
     * one heart rate every few minutes) gets a wider window, up to [maxMs], since a fixed 60 s
     * would pair almost nothing; a dense one keeps it tight.
     */
    fun tolerance(refMedianIntervalMs: Long?, minMs: Long, maxMs: Long): Long =
        ((refMedianIntervalMs ?: minMs) / 2).coerceIn(minMs, maxMs)

    // ── Reference totals ───────────────────────────────────────────────────────────────────

    /** An interval total (steps, metres, kcal) split across local days in proportion to overlap. */
    data class IntervalAmount(val start: Long, val end: Long, val amount: Double)

    fun dailyTotals(items: List<IntervalAmount>, zone: ZoneId): Map<Long, Double> {
        val out = mutableMapOf<Long, Double>()
        for (it in items) {
            if (it.end <= it.start) {
                out.merge(epochDay(it.start, zone), it.amount, Double::plus)
                continue
            }
            var t = it.start
            val len = (it.end - it.start).toDouble()
            while (t < it.end) {
                val day = epochDay(t, zone)
                val dayEnd = java.time.LocalDate.ofEpochDay(day).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val segEnd = minOf(dayEnd, it.end)
                out.merge(day, it.amount * (segEnd - t) / len, Double::plus)
                t = segEnd
            }
        }
        return out
    }

    // ── Sleep ──────────────────────────────────────────────────────────────────────────────

    data class StageAgreement(val overlapMinutes: Int, val agreeMinutes: Int, val confusion: Map<Pair<Stage, Stage>, Int>) {
        /** "LIGHT>DEEP:30,…" — ring stage > reference stage : minutes. */
        fun encode(): String = confusion.entries.sortedBy { it.key.first.ordinal * 10 + it.key.second.ordinal }
            .joinToString(",") { "${it.key.first}>${it.key.second}:${it.value}" }

        companion object {
            fun decode(s: String?): Map<Pair<Stage, Stage>, Int> = s.orEmpty().split(',').mapNotNull { part ->
                val (k, v) = part.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
                val (a, b) = k.split('>').takeIf { it.size == 2 } ?: return@mapNotNull null
                val sa = runCatching { Stage.valueOf(a) }.getOrNull() ?: return@mapNotNull null
                val sb = runCatching { Stage.valueOf(b) }.getOrNull() ?: return@mapNotNull null
                (sa to sb) to (v.toIntOrNull() ?: return@mapNotNull null)
            }.toMap()
        }
    }

    /**
     * Minute by minute over the stretch both nights cover, counting only minutes where both name
     * a stage (an unstaged "asleep" can't agree or disagree with "deep"). Null when either side
     * has no stages.
     */
    fun stageAgreement(ring: SleepNight, ref: SleepNight): StageAgreement? {
        if (!ring.hasStages || !ref.hasStages) return null
        val from = maxOf(ring.start, ref.start)
        val to = minOf(ring.end, ref.end)
        val confusion = mutableMapOf<Pair<Stage, Stage>, Int>()
        var overlap = 0
        var agree = 0
        var t = from
        while (t + 60_000L <= to) {
            val mid = t + 30_000L
            val a = ring.stageAt(mid)
            val b = ref.stageAt(mid)
            if (a != null && b != null && a != Stage.ASLEEP && b != Stage.ASLEEP) {
                overlap++
                if (a == b) agree++
                confusion.merge(a to b, 1, Int::plus)
            }
            t += 60_000L
        }
        return StageAgreement(overlap, agree, confusion)
    }

    // ── Day rows ───────────────────────────────────────────────────────────────────────────

    /** A daily-total row: one value each side; the difference is in percent for a percent metric. */
    fun dailyRow(
        checkId: String,
        metric: ComparisonMetric,
        day: Long,
        ring: Double?,
        ref: Double?,
        valid: Boolean,
        note: String? = null,
        now: Long,
    ): AccuracyCheckDayEntity {
        val d = diff(metric, ring, ref)
        return AccuracyCheckDayEntity(
            checkId = checkId, metric = metric.name, day = day, ringValue = ring, refValue = ref,
            pairs = if (d != null) 1 else 0, sumDiff = d ?: 0.0, sumSqDiff = (d ?: 0.0) * (d ?: 0.0), sumAbsDiff = abs(d ?: 0.0),
            inBand = if (d != null && abs(d) <= metric.band) 1 else 0,
            valid = valid && d != null, note = note, computedAt = now,
        )
    }

    fun pairedRow(checkId: String, metric: ComparisonMetric, day: Long, sums: PairSums, valid: Boolean, now: Long, note: String? = null) =
        AccuracyCheckDayEntity(
            checkId = checkId, metric = metric.name, day = day, pairs = sums.n,
            sumDiff = sums.sumDiff, sumSqDiff = sums.sumSq, sumAbsDiff = sums.sumAbs,
            valid = valid, note = note, computedAt = now,
        )

    fun diff(metric: ComparisonMetric, ring: Double?, ref: Double?): Double? {
        if (ring == null || ref == null) return null
        return if (metric.percentBand) {
            if (ref <= 0.0) null else (ring - ref) / ref * 100.0
        } else ring - ref
    }

    // ── Verdict ────────────────────────────────────────────────────────────────────────────

    enum class Verdict { AGREES, CONSISTENTLY_HIGH, CONSISTENTLY_LOW, INCONSISTENT, NOT_ENOUGH_DATA }

    data class Summary(
        val metric: ComparisonMetric,
        val verdict: Verdict,
        val validDays: Int,
        /** Mean difference ring − reference (percent for a percent metric). */
        val bias: Double,
        /** Spread of the differences: SD across days (daily) or across pairs (paired). */
        val sd: Double,
        val mae: Double,
        val pairs: Int,
        /** Share of days whose own difference points the same way as [bias]. */
        val sameSignShare: Double,
        /** Suggested correction (scale factor or offset), when the verdict is a consistent bias. */
        val suggestedCorrection: Double?,
        val band: Double,
        /** Sleep stages only: minutes on the same stage over minutes compared. */
        val stageAgreement: Double? = null,
    ) {
        /** Bland-Altman 95 % limits of agreement. */
        val limits: Pair<Double, Double> get() = (bias - 1.96 * sd) to (bias + 1.96 * sd)
    }

    /**
     * Bias vs noise (plan, "Two ideas behind every metric"). Agreement needs the average inside
     * the band and the spread not far outside it. A consistent bias needs the average outside the
     * band, most days pointing the same way, and the day-to-day wobble of that bias smaller than
     * the bias itself — only then will a single correction help. Anything else is inconsistent:
     * no correction is offered, because one would just move the average of an erratic ring.
     */
    fun summarize(metric: ComparisonMetric, days: List<AccuracyCheckDayEntity>, band: Double = metric.band): Summary {
        val valid = days.filter { it.valid && it.metric == metric.name && it.pairs > 0 }
        if (metric.kind == ComparisonMetric.Kind.STAGES) {
            val overlap = valid.sumOf { it.pairs }
            val agree = valid.sumOf { it.inBand }
            val share = if (overlap > 0) agree.toDouble() / overlap else null
            return Summary(
                metric, if (valid.size < metric.minDays) Verdict.NOT_ENOUGH_DATA else Verdict.AGREES,
                valid.size, 0.0, 0.0, 0.0, overlap, 0.0, null, band, stageAgreement = share,
            )
        }
        val dayBiases = valid.map { it.sumDiff / it.pairs }
        val (bias, sd, mae, pairs) = if (metric.kind == ComparisonMetric.Kind.PAIRED) {
            val s = valid.fold(PairSums()) { acc, d -> acc + PairSums(d.pairs, d.sumDiff, d.sumSqDiff, d.sumAbsDiff) }
            Quad(s.mean, s.sd, s.mae, s.n)
        } else {
            val m = dayBiases.average().takeIf { !it.isNaN() } ?: 0.0
            Quad(m, sdOf(dayBiases), dayBiases.map { abs(it) }.average().takeIf { !it.isNaN() } ?: 0.0, valid.size)
        }
        val sameSign = if (dayBiases.isEmpty() || bias == 0.0) 0.0
            else dayBiases.count { it * bias > 0 }.toDouble() / dayBiases.size
        val enough = valid.size >= metric.minDays && pairs >= metric.minPairs
        val noiseOk = if (metric.kind == ComparisonMetric.Kind.PAIRED) mae <= band * 1.5 else sd <= band * 1.5
        val verdict = when {
            !enough -> Verdict.NOT_ENOUGH_DATA
            abs(bias) <= band && noiseOk -> Verdict.AGREES
            abs(bias) > band && sameSign >= 0.8 && sdOf(dayBiases) < abs(bias) ->
                if (bias > 0) Verdict.CONSISTENTLY_HIGH else Verdict.CONSISTENTLY_LOW
            else -> Verdict.INCONSISTENT
        }
        val suggestion = if (metric.correction != null && (verdict == Verdict.CONSISTENTLY_HIGH || verdict == Verdict.CONSISTENTLY_LOW)) {
            when (metric.correction) {
                CorrectionKind.SCALE -> {
                    val ring = valid.sumOf { it.ringValue ?: 0.0 }
                    val ref = valid.sumOf { it.refValue ?: 0.0 }
                    if (ring > 0) ref / ring else null
                }
                CorrectionKind.OFFSET -> -bias
            }
        } else null
        return Summary(metric, verdict, valid.size, bias, sd, mae, pairs, sameSign, suggestion, band)
    }

    private data class Quad(val a: Double, val b: Double, val c: Double, val d: Int)

    private fun sdOf(xs: List<Double>): Double {
        if (xs.size < 2) return 0.0
        val m = xs.average()
        return sqrt(xs.sumOf { (it - m) * (it - m) } / (xs.size - 1))
    }

    // ── Bands ──────────────────────────────────────────────────────────────────────────────

    fun parseBands(raw: String?): Map<ComparisonMetric, Double> = raw.orEmpty().split(';').mapNotNull { part ->
        val (k, v) = part.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
        val m = ComparisonMetric.byName(k) ?: return@mapNotNull null
        m to (v.toDoubleOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null)
    }.toMap()

    fun encodeBands(bands: Map<ComparisonMetric, Double>): String? =
        bands.filter { (m, v) -> v != m.band }.entries.joinToString(";") { "${it.key.name}=${it.value}" }.ifEmpty { null }
}
