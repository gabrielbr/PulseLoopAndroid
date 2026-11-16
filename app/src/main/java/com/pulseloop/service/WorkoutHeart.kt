package com.pulseloop.service

import kotlin.math.exp

/**
 * A workout's heart-rate figures, weighted by time rather than by sample.
 *
 * A workout's window holds rows at very different rates: the live stream about once a second, the
 * ring's own log every few minutes, a spot reading now and then. Averaged per row, three minutes
 * of stream outweigh forty minutes of log, so the summary said whatever those three minutes said
 * (the 12 Nov gym session read 76 bpm against a watch's 116). Each reading here counts for the time
 * until the next one, capped at [MAX_SAMPLE_WEIGHT_MS] so a gap with no readings is not credited
 * to the reading before it.
 *
 * Pure, so the rules are unit-tested (`WorkoutHeartTest`).
 */
object WorkoutHeart {
    /** Readings outside this band are sensor noise, not a heart rate. */
    const val MIN_BPM = 30.0
    const val MAX_BPM = 230.0

    /** The longest stretch one reading may stand for. Longer than the ring's 5-minute log is too
     *  generous for exercise; two minutes covers the burstiest live stream seen (issue #59). */
    const val MAX_SAMPLE_WEIGHT_MS = 120_000L

    data class Sample(val atMs: Long, val bpm: Double)

    /** One reading and the minutes it stands for. */
    data class Weighted(val bpm: Double, val minutes: Double)

    data class Summary(
        val average: Double,
        val max: Double,
        /** Minutes in each [HeartRateZones.Zone], in enum order. */
        val zoneMinutes: Map<HeartRateZones.Zone, Double>,
        /** Minutes the readings cover, at most the workout's length. */
        val coveredMinutes: Double,
    )

    fun weighted(samples: List<Sample>, endMs: Long): List<Weighted> {
        val clean = samples.filter { it.bpm in MIN_BPM..MAX_BPM }.sortedBy { it.atMs }
        return clean.mapIndexed { i, s ->
            val next = clean.getOrNull(i + 1)?.atMs ?: endMs
            val span = (next - s.atMs).coerceIn(0L, MAX_SAMPLE_WEIGHT_MS)
            Weighted(s.bpm, span / 60_000.0)
        }
    }

    fun summary(samples: List<Sample>, endMs: Long, age: Int?): Summary? {
        val w = weighted(samples, endMs)
        if (w.isEmpty()) return null
        val covered = w.sumOf { it.minutes }
        // A single reading at the very end stands for no time; fall back to a plain mean.
        val average = if (covered > 0) w.sumOf { it.bpm * it.minutes } / covered else w.map { it.bpm }.average()
        val zones = HeartRateZones.Zone.entries.associateWith { 0.0 }.toMutableMap()
        w.forEach { zones.merge(HeartRateZones.zoneFor(it.bpm.toInt(), age ?: DEFAULT_AGE), it.minutes, Double::plus) }
        return Summary(average, w.maxOf { it.bpm }, zones, covered)
    }

    /**
     * Banister's training impulse (TRIMP): minutes × heart-rate reserve fraction × a sex-specific
     * exponential weighting (Banister 1991; Morton, Fitz-Clarke & Banister 1990). An estimate of
     * how hard the session was for this person; watches publish their own variants of it as
     * "training load", which is why it can sit near theirs but is never the same number.
     *
     * Null without readings. [restingBpm] falls back to 60 and age to [DEFAULT_AGE].
     */
    fun trainingLoad(samples: List<Sample>, endMs: Long, age: Int?, sex: String?, restingBpm: Double?): Double? {
        val w = weighted(samples, endMs)
        if (w.isEmpty() || w.sumOf { it.minutes } <= 0.0) return null
        val max = HeartRateZones.maxHR(age ?: DEFAULT_AGE).toDouble()
        val rest = (restingBpm ?: 60.0).coerceIn(35.0, max - 20)
        val female = sex?.lowercase() == "female"
        val a = if (female) 0.86 else 0.64
        val b = if (female) 1.67 else 1.92
        return w.sumOf { s ->
            val reserve = ((s.bpm - rest) / (max - rest)).coerceIn(0.0, 1.0)
            s.minutes * reserve * a * exp(b * reserve)
        }
    }

    /**
     * Resting energy over [minutes] (Mifflin-St Jeor), so a workout's total can be split into
     * active and resting the way a watch does. Null when the profile lacks any input.
     */
    fun restingCalories(minutes: Double, sex: String?, age: Int?, weightKg: Double?, heightCm: Double?): Double? {
        val s = sex?.lowercase()?.takeIf { it == "male" || it == "female" } ?: return null
        if (age == null || weightKg == null || heightCm == null) return null
        val perDay = 10 * weightKg + 6.25 * heightCm - 5 * age + if (s == "male") 5 else -161
        return perDay / 1440.0 * minutes
    }

    const val DEFAULT_AGE = 30
}
