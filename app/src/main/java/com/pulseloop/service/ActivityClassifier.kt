package com.pulseloop.service

import kotlin.math.sqrt

/**
 * What a stretch of movement probably was, from what every ring syncs: step cadence and how
 * bursty it is, and how high and how uneven heart rate ran. See docs/activity-recognition-plan.md.
 *
 * Rules give the first guess; the user's own earlier answers win once there are some close to
 * this stretch. Every type is returned, best first, because the user always picks: this only
 * decides which one is selected and what order the rest come in.
 */
object ActivityClassifier {

    data class Features(
        val minutes: Double,
        /** Steps per minute over the whole stretch. */
        val cadence: Double,
        /** Coefficient of variation of the per-bucket cadence: sets with steps between them read high. */
        val stepBurst: Double,
        /** Mean heart rate as a share of heart-rate reserve (0 = quiet level, 1 = HRmax). */
        val hrMeanPct: Double,
        /** 90th-percentile heart rate as a share of heart-rate reserve. */
        val hrPeakPct: Double,
        /** Coefficient of variation of the heart-rate readings: intervals and sets read high. */
        val hrCv: Double,
    )

    /** A stretch the user labelled, kept to steer later guesses. */
    data class Example(val features: Features, val type: String)

    /** The types a guess can name, in ActivityMeta's own order for the ones rules can't tell apart. */
    val TYPES = listOf("walk", "run", "cycle", "gym", "sport", "hike", "dance", "yoga", "squash", "other")

    fun features(
        startAt: Long,
        endAt: Long,
        steps: List<Pair<Long, Int>>,
        hr: List<Pair<Long, Double>>,
        quiet: Double,
        hrMax: Double,
    ): Features {
        val minutes = ((endAt - startAt) / 60_000.0).coerceAtLeast(1.0)
        val inSteps = steps.filter { it.first in startAt until endAt }.sortedBy { it.first }
        val cadence = inSteps.sumOf { it.second } / minutes
        // Bucket length from the data itself: families sync 1- to 15-minute buckets.
        val bucketMin = inSteps.zipWithNext { a, b -> (b.first - a.first) / 60_000.0 }.filter { it > 0 }.minOrNull() ?: 15.0
        val perBucket = inSteps.map { it.second / bucketMin }
        val stepBurst = cv(perBucket).takeIf { perBucket.size >= 3 } ?: 0.0
        val bpm = hr.filter { it.first in startAt..endAt }.map { it.second }
        val reserve = (hrMax - quiet).coerceAtLeast(20.0)
        fun pct(v: Double) = ((v - quiet) / reserve).coerceIn(-0.5, 1.2)
        val mean = if (bpm.isEmpty()) 0.0 else pct(bpm.average())
        val peak = if (bpm.isEmpty()) 0.0 else pct(bpm.sorted()[((bpm.size - 1) * 0.9).toInt()])
        val hrCv = cv(bpm).takeIf { bpm.size >= 3 } ?: 0.0
        return Features(minutes, cadence, stepBurst, mean, peak, hrCv)
    }

    /** Every type, most likely first. */
    fun rank(f: Features, learned: List<Example> = emptyList()): List<String> {
        val rules = ruleScores(f)
        val personal = personalScores(f, learned)
        val weight = personal.second
        val blended = TYPES.associateWith { t -> (1 - weight) * (rules[t] ?: 0.0) + weight * (personal.first[t] ?: 0.0) }
        return TYPES.sortedWith(compareByDescending<String> { blended[it] ?: 0.0 }.thenBy { TYPES.indexOf(it) })
    }

    internal fun ruleScores(f: Features): Map<String, Double> {
        fun ramp(x: Double, from: Double, to: Double) = ((x - from) / (to - from)).coerceIn(0.0, 1.0)
        val low = ramp(f.cadence, 60.0, 20.0)           // few steps for the time spent
        val long = ramp(f.minutes, 15.0, 40.0)
        val scores = mapOf(
            "run" to ramp(f.cadence, 110.0, 145.0),
            "walk" to (ramp(f.cadence, 55.0, 85.0) * ramp(f.cadence, 140.0, 115.0)),
            // A ride: heart rate held up and steady, for a while, with few steps.
            "cycle" to low * ramp(f.hrMeanPct, 0.25, 0.5) * ramp(f.hrCv, 0.15, 0.07) * long,
            // Strength: heart rate up but uneven (sets and rests), often with bursts of steps between sets.
            "gym" to low * ramp(f.hrMeanPct, 0.12, 0.35) * (0.7 * ramp(f.hrCv, 0.07, 0.15) + 0.3 * ramp(f.stepBurst, 0.4, 1.2)),
            // Intervals or a game: high peaks with deep drops.
            "sport" to ramp(f.cadence, 100.0, 60.0) * ramp(f.hrPeakPct, 0.6, 0.85) * ramp(f.hrCv, 0.1, 0.2),
            "other" to 0.12,
        )
        val sum = scores.values.sum().takeIf { it > 0 } ?: 1.0
        return scores.mapValues { it.value / sum }
    }

    /** Votes from the nearest earlier answers, and how much they should count (0..0.8). */
    internal fun personalScores(f: Features, learned: List<Example>): Pair<Map<String, Double>, Double> {
        if (learned.isEmpty()) return emptyMap<String, Double>() to 0.0
        val near = learned.map { it to distance(f, it.features) }.filter { it.second < 1.5 }.sortedBy { it.second }.take(5)
        if (near.isEmpty()) return emptyMap<String, Double>() to 0.0
        val votes = near.groupBy { it.first.type }.mapValues { (_, v) -> v.sumOf { 1.0 / (it.second + 0.25) } }
        val sum = votes.values.sum()
        return votes.mapValues { it.value / sum } to (0.25 * near.size).coerceAtMost(0.8)
    }

    internal fun distance(a: Features, b: Features): Double {
        fun d(x: Double, y: Double, scale: Double) = (x - y) / scale
        val parts = listOf(
            d(a.minutes, b.minutes, 45.0), d(a.cadence, b.cadence, 30.0), d(a.stepBurst, b.stepBurst, 0.8),
            d(a.hrMeanPct, b.hrMeanPct, 0.2), d(a.hrPeakPct, b.hrPeakPct, 0.2), d(a.hrCv, b.hrCv, 0.08),
        )
        return sqrt(parts.sumOf { it * it })
    }

    private fun cv(v: List<Double>): Double {
        if (v.isEmpty()) return 0.0
        val m = v.average()
        if (m <= 0) return 0.0
        return sqrt(v.sumOf { (it - m) * (it - m) } / v.size) / m
    }
}
