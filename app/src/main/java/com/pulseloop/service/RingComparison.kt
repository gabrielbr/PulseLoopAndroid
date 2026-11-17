package com.pulseloop.service

import kotlin.math.abs

/**
 * How two rings worn together compare (docs/ring-comparison.md). Pure, so it is unit-tested
 * (`RingComparisonTest`). There is no ground truth here: two rings can agree and both be wrong,
 * which is why the screen says so and points at the accuracy check for a watch.
 */
object RingComparison {
    data class Reading(val atMs: Long, val value: Double)

    data class Coverage(val slotsWithReadings: Int, val slots: Int) {
        val fraction: Double get() = if (slots == 0) 0.0 else slotsWithReadings.toDouble() / slots
    }

    data class Agreement(
        /** Readings from the second ring that had one from the first within the tolerance. */
        val pairs: Int,
        /** Median of |second − first|, the typical gap. */
        val medianAbsDiff: Double?,
        /** Mean of (second − first): positive when the second ring reads higher. */
        val meanDiff: Double?,
    )

    /**
     * How much of [start, end) has at least one reading, in slots of [slotMs]. Slots, not each
     * reading's time: a ring that logs every five minutes and one that streams every second both
     * "cover" a five-minute slot, which is the honest comparison for an all-day log.
     */
    fun coverage(readings: List<Reading>, start: Long, end: Long, slotMs: Long): Coverage {
        if (end <= start || slotMs <= 0) return Coverage(0, 0)
        val slots = ((end - start + slotMs - 1) / slotMs).toInt()
        val filled = readings.asSequence()
            .filter { it.atMs in start until end }
            .map { ((it.atMs - start) / slotMs).toInt() }
            .toSet().size
        return Coverage(filled, slots)
    }

    /** Pairs each second-ring reading with the first ring's nearest one within [toleranceMs]. */
    fun agreement(first: List<Reading>, second: List<Reading>, toleranceMs: Long): Agreement {
        val sorted = first.sortedBy { it.atMs }
        if (sorted.isEmpty() || second.isEmpty()) return Agreement(0, null, null)
        val times = sorted.map { it.atMs }.toLongArray()
        val diffs = second.mapNotNull { r ->
            var i = times.binarySearch(r.atMs).let { if (it < 0) -it - 1 else it }
            val candidates = listOfNotNull(sorted.getOrNull(i - 1), sorted.getOrNull(i))
            val nearest = candidates.minByOrNull { abs(it.atMs - r.atMs) } ?: return@mapNotNull null
            if (abs(nearest.atMs - r.atMs) <= toleranceMs) r.value - nearest.value else null
        }
        if (diffs.isEmpty()) return Agreement(0, null, null)
        val absSorted = diffs.map { abs(it) }.sorted()
        val median = if (absSorted.size % 2 == 1) absSorted[absSorted.size / 2]
            else (absSorted[absSorted.size / 2 - 1] + absSorted[absSorted.size / 2]) / 2
        return Agreement(diffs.size, median, diffs.average())
    }

    /** Heart rate averaged by time over [start, end), like a workout's (WorkoutHeart): a ring that
     *  streamed for an hour must not outvote one that logged all day. Null when there are none. */
    fun averageHeartRate(readings: List<Reading>, start: Long, end: Long): Double? {
        val w = WorkoutHeart.weighted(
            readings.filter { it.atMs in start until end }.map { WorkoutHeart.Sample(it.atMs, it.value) }, end,
        )
        val minutes = w.sumOf { it.minutes }
        return when {
            w.isEmpty() -> null
            minutes <= 0.0 -> w.map { it.bpm }.average()
            else -> w.sumOf { it.bpm * it.minutes } / minutes
        }
    }
}
