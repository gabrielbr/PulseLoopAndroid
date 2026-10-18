package com.pulseloop.service

import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.ring.MeasurementKind

/**
 * "Your usual range" for a vital: the middle half (25th–75th percentile) of the user's own real
 * readings over the last [DAYS] days, drawn as a shaded band on charts (docs/oura-ux-analysis.md
 * §1.5, phase 5). Personal, like everything else Oura-style here: a point outside the band means
 * "unusual for you", not "abnormal".
 *
 * Null until the history is a baseline at all (BaselineStats.isEstablished: ≥20 readings over ≥7
 * days), so a new user sees the population bands the charts already draw rather than a band built
 * from three readings.
 */
object UsualRange {
    const val DAYS = 30L

    fun of(samples: List<VitalSample>): ClosedFloatingPointRange<Double>? {
        val b = BaselineStats.compute(samples) ?: return null
        if (!b.isEstablished || b.p75 <= b.p25) return null
        return b.p25..b.p75
    }

    /** Real rows only (DemoDataPolicy): a seeded month would otherwise define a real user's normal. */
    suspend fun load(db: PulseLoopDatabase, kind: MeasurementKind, now: Long = System.currentTimeMillis()): ClosedFloatingPointRange<Double>? =
        runCatching {
            of(db.measurementDao().rangeReal(kind.name, now - DAYS * 86_400_000L, now).map { VitalSample(it.timestamp, it.value) })
        }.getOrNull()
}
