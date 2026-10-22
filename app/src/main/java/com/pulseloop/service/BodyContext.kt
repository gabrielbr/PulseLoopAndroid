package com.pulseloop.service

import com.pulseloop.ui.i18n.tr

import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.BodyMeasurementEntity
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * The scale's readings as the coach sees them: a trend, never a single weigh-in on its own.
 * Null without at least two weigh-ins in the last 30 days.
 */
@Serializable
data class BodyContext(
    val latestWeightKg: Double,
    /** Change across the last 30 days of weigh-ins, kg (negative = lost). */
    val weightChange30dKg: Double,
    val weighIns30d: Int,
    val bodyFatPct: Double? = null,
    val note: String = tr("From the user's smart scale via Health Connect. Day-to-day weight swings are mostly water; talk about the trend only."),
) {
    companion object {
        suspend fun build(db: PulseLoopDatabase, now: Long = System.currentTimeMillis()): BodyContext? = runCatching {
            val rows = db.bodyMeasurementDao().since(BodyMeasurementEntity.TYPE_WEIGHT, now - 30L * 86_400_000L)
            if (rows.size < 2) return@runCatching null
            BodyContext(
                latestWeightKg = (rows.last().value * 10).roundToInt() / 10.0,
                weightChange30dKg = ((rows.last().value - rows.first().value) * 10).roundToInt() / 10.0,
                weighIns30d = rows.size,
                bodyFatPct = db.userProfileDao().get()?.bodyFatPct?.let { (it * 10).roundToInt() / 10.0 },
            )
        }.getOrNull()
    }
}
