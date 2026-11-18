package com.pulseloop.health.accuracy

import com.pulseloop.data.entity.AccuracyCheckDayEntity
import com.pulseloop.ring.SleepStage
import com.pulseloop.util.TimeUtil
import java.time.ZoneId
import kotlin.math.abs

/**
 * The accuracy check for the second ring (docs/ring-comparison.md, "Graded against a watch"). The
 * comparison ring is scored against the same reference in the same check, so the owner sees which
 * of his two rings is closer to the watch he trusts.
 *
 * Its rows sit in `accuracy_check_days` beside the main ring's, under [PREFIX] + the metric name,
 * so everything that reads the main ring's rows (the report cards, corrections, the export pause)
 * never sees them. Pure, so it is unit-tested (`ComparisonRingScoringTest`).
 */
object ComparisonRingScoring {
    const val PREFIX = "CMP:"

    fun tag(row: AccuracyCheckDayEntity) = row.copy(metric = PREFIX + row.metric)

    /** The comparison ring's rows, renamed back to plain metric names for [AccuracyComparison.summarize]. */
    fun comparisonRows(days: List<AccuracyCheckDayEntity>) =
        days.filter { it.metric.startsWith(PREFIX) }.map { it.copy(metric = it.metric.removePrefix(PREFIX)) }

    /** One night from the comparison ring's per-minute sleep rows. */
    data class Night(val start: Long, val end: Long, val asleepMinutes: Int, val onset: Long, val wake: Long)

    /**
     * Nights by waking epoch day, from `(minute start, SleepStage ordinal)` rows. A night with no
     * asleep minute is dropped. Onset is the first asleep minute, wake the end of the last.
     */
    fun nights(minutes: List<Pair<Long, Int>>, zone: ZoneId): Map<Long, Night> =
        minutes.groupBy { AccuracyComparison.epochDay(TimeUtil.wakingDayLocal(it.first, zone), zone) }
            .mapNotNull { (day, rows) ->
                val asleep = rows.filter { it.second != SleepStage.AWAKE.ordinal }.map { it.first }.sorted()
                if (asleep.isEmpty()) return@mapNotNull null
                day to Night(
                    start = rows.minOf { it.first }, end = rows.maxOf { it.first } + MINUTE,
                    asleepMinutes = asleep.size, onset = asleep.first(), wake = asleep.last() + MINUTE,
                )
            }.toMap()

    /**
     * Steps per local epoch day: the larger of the 15-minute buckets' sum and the ring's own
     * running total for that day ([totals], keyed by the day's local start), since a sync can
     * deliver one before the other.
     */
    fun dailySteps(buckets: List<Pair<Long, Double>>, totals: Map<Long, Double>, zone: ZoneId): Map<Long, Double> {
        val fromBuckets = buckets.groupBy { AccuracyComparison.epochDay(it.first, zone) }.mapValues { (_, b) -> b.sumOf { it.second } }
        val fromTotals = totals.mapKeys { AccuracyComparison.epochDay(it.key, zone) }
        return (fromBuckets.keys + fromTotals.keys).associateWith { maxOf(fromBuckets[it] ?: 0.0, fromTotals[it] ?: 0.0) }
    }

    enum class Closer { MAIN, COMPARISON, SIMILAR }

    /**
     * Which ring sat closer to the reference on one metric, by the typical gap ([AccuracyComparison.Summary.mae]).
     * Null unless both have enough data. "Similar" when the two gaps are within a fifth of the
     * metric's band or 10 % of each other: a smaller difference than that isn't worth a winner.
     */
    fun closer(main: AccuracyComparison.Summary?, cmp: AccuracyComparison.Summary?): Closer? {
        if (main == null || cmp == null) return null
        if (main.verdict == AccuracyComparison.Verdict.NOT_ENOUGH_DATA || cmp.verdict == AccuracyComparison.Verdict.NOT_ENOUGH_DATA) return null
        val a = main.mae
        val b = cmp.mae
        val margin = maxOf(main.band * 0.2, 0.1 * maxOf(a, b))
        return when {
            abs(a - b) <= margin -> Closer.SIMILAR
            a < b -> Closer.MAIN
            else -> Closer.COMPARISON
        }
    }

    private const val MINUTE = 60_000L
}
