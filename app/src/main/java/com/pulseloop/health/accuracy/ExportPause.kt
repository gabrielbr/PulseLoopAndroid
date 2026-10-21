package com.pulseloop.health.accuracy

import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import com.pulseloop.data.entity.AccuracyCheckEntity
import com.pulseloop.data.entity.AccuracyPausedUnitEntity
import com.pulseloop.health.HealthConnectPermissions
import com.pulseloop.health.HealthConnectWatermarks

/**
 * One kind of record PulseLoop exports that a reference device can also write — the granularity
 * the accuracy check pauses at (plan, "Export pause and resume"). Finer than the export's settings
 * rows: steps, distance and active calories are one row there but separate here, because a
 * reference that writes steps and not calories should only hold back steps.
 */
enum class ExportUnit(
    val metric: ReferenceMetric,
    val row: HealthConnectPermissions.DataTypeRow,
    val watermark: HealthConnectWatermarks.Key,
) {
    HEART_RATE(ReferenceMetric.HEART_RATE, HealthConnectPermissions.DataTypeRow.HEART_RATE, HealthConnectWatermarks.Key.VITALS),
    OXYGEN_SATURATION(ReferenceMetric.OXYGEN_SATURATION, HealthConnectPermissions.DataTypeRow.OXYGEN_SATURATION, HealthConnectWatermarks.Key.VITALS),
    HRV(ReferenceMetric.HRV, HealthConnectPermissions.DataTypeRow.HEART_RATE_VARIABILITY, HealthConnectWatermarks.Key.VITALS),
    SLEEP(ReferenceMetric.SLEEP, HealthConnectPermissions.DataTypeRow.SLEEP, HealthConnectWatermarks.Key.SLEEP),
    STEPS(ReferenceMetric.STEPS, HealthConnectPermissions.DataTypeRow.STEPS_AND_ACTIVITY, HealthConnectWatermarks.Key.ACTIVITY),
    DISTANCE(ReferenceMetric.DISTANCE, HealthConnectPermissions.DataTypeRow.STEPS_AND_ACTIVITY, HealthConnectWatermarks.Key.ACTIVITY),
    ACTIVE_CALORIES(ReferenceMetric.ACTIVE_CALORIES, HealthConnectPermissions.DataTypeRow.STEPS_AND_ACTIVITY, HealthConnectWatermarks.Key.ACTIVITY),
    RESTING_HEART_RATE(ReferenceMetric.RESTING_HEART_RATE, HealthConnectPermissions.DataTypeRow.RESTING_HEART_RATE, HealthConnectWatermarks.Key.RESTING_HR),
    ;

    companion object {
        fun of(metric: ReferenceMetric): ExportUnit = entries.first { it.metric == metric }
        fun byName(name: String): ExportUnit? = entries.firstOrNull { it.name == name }

        /**
         * The unit a record PulseLoop is about to export belongs to, by record type — so a
         * workout's calorie and distance siblings are held back with the daily ones, which is
         * right: the reference device writes those too. The workout session itself has no unit
         * and always exports.
         */
        fun ofRecord(r: Record): ExportUnit? = when (r) {
            is HeartRateRecord -> HEART_RATE
            is OxygenSaturationRecord -> OXYGEN_SATURATION
            is HeartRateVariabilityRmssdRecord -> HRV
            is SleepSessionRecord -> SLEEP
            is StepsRecord -> STEPS
            is DistanceRecord -> DISTANCE
            is ActiveCaloriesBurnedRecord -> ACTIVE_CALORIES
            is RestingHeartRateRecord -> RESTING_HEART_RATE
            else -> null
        }
    }
}

/** Records of [unit] whose data time overlaps `[from, until)` are not exported. Open while [until] is null. */
data class ExportExclusion(val unit: ExportUnit, val from: Long, val until: Long?) {
    fun overlaps(start: Long, end: Long): Boolean = end >= from && (until == null || start < until)
}

/**
 * The export side of the accuracy check: which records to hold back, and how the exporter skips
 * them without stalling its watermarks. Pure apart from the record-time accessors.
 *
 * **Filtered by data time, not by watermark.** A paused record is dropped from the pass and its
 * watermark still advances past it, exactly like a never-exportable row. That is what makes
 * "skip the check window" the default with nothing to do on resume, and what keeps it skipped:
 * a record from the window that is updated later (a re-synced night, a day whose total grows)
 * re-selects, meets the same closed exclusion, and is dropped again. "Export them too" removes
 * the exclusion and rewinds the affected watermarks to the window's start instead.
 *
 * A record is held back when its interval *overlaps* the window, so a whole-day activity record
 * for the day a check starts or ends is held back too: its total includes steps from inside the
 * window, which the reference device also wrote.
 */
object ExportPause {

    /**
     * Exclusions in force: open for a check that is running or waiting for the resume choice,
     * closed at its end for a check resolved with SKIP, none for one resolved with EXPORT.
     */
    fun exclusions(checks: List<AccuracyCheckEntity>, paused: List<AccuracyPausedUnitEntity>): List<ExportExclusion> {
        val byCheck = paused.groupBy { it.checkId }
        return checks.flatMap { c ->
            val until: Long? = when {
                c.isOpen -> null
                c.state == AccuracyCheckEntity.STATE_DONE && c.resumeChoice == AccuracyCheckEntity.RESUME_SKIP -> c.endedAt ?: return@flatMap emptyList()
                else -> return@flatMap emptyList()
            }
            byCheck[c.id].orEmpty().mapNotNull { p ->
                val unit = ExportUnit.byName(p.unit) ?: return@mapNotNull null
                // A unit that joined the pause set after the check ended (can't happen through the
                // controller) would make an inverted window; drop it rather than exclude nothing oddly.
                if (until != null && until <= p.pausedFrom) null else ExportExclusion(unit, p.pausedFrom, until)
            }
        }
    }

    /** Units currently held back (an open window). */
    fun pausedNow(exclusions: List<ExportExclusion>): Set<ExportUnit> =
        exclusions.filter { it.until == null }.map { it.unit }.toSet()

    data class Filtered(
        val records: List<Record>,
        val highWaters: List<Long>,
        /** Max high water among the held-back records, for the watermark advance; null if none. */
        val heldBackHighWater: Long?,
        val heldBack: Int,
    )

    fun filter(records: List<Record>, highWaters: List<Long>, exclusions: List<ExportExclusion>): Filtered {
        require(records.size == highWaters.size) { "records/highWaters must be parallel" }
        if (exclusions.isEmpty()) return Filtered(records, highWaters, null, 0)
        val keptR = ArrayList<Record>(records.size)
        val keptH = ArrayList<Long>(records.size)
        var heldHigh: Long? = null
        var held = 0
        for (i in records.indices) {
            val r = records[i]
            if (isHeldBack(r, exclusions)) {
                held++
                heldHigh = maxOf(heldHigh ?: Long.MIN_VALUE, highWaters[i])
            } else {
                keptR += r
                keptH += highWaters[i]
            }
        }
        return Filtered(keptR, keptH, heldHigh, held)
    }

    fun isHeldBack(r: Record, exclusions: List<ExportExclusion>): Boolean {
        val unit = ExportUnit.ofRecord(r) ?: return false
        val (start, end) = span(r) ?: return false
        return exclusions.any { it.unit == unit && it.overlaps(start, end) }
    }

    /**
     * A record's data time, start to end. Spelled out per type because the library keeps its
     * `InstantaneousRecord` / `IntervalRecord` interfaces internal.
     */
    fun span(r: Record): Pair<Long, Long>? = when (r) {
        is HeartRateRecord -> r.startTime.toEpochMilli() to r.endTime.toEpochMilli()
        is SleepSessionRecord -> r.startTime.toEpochMilli() to r.endTime.toEpochMilli()
        is StepsRecord -> r.startTime.toEpochMilli() to r.endTime.toEpochMilli()
        is DistanceRecord -> r.startTime.toEpochMilli() to r.endTime.toEpochMilli()
        is ActiveCaloriesBurnedRecord -> r.startTime.toEpochMilli() to r.endTime.toEpochMilli()
        is OxygenSaturationRecord -> r.time.toEpochMilli().let { it to it }
        is HeartRateVariabilityRmssdRecord -> r.time.toEpochMilli().let { it to it }
        is RestingHeartRateRecord -> r.time.toEpochMilli().let { it to it }
        else -> null
    }

    /** Max of two nullable high waters — how a held-back high water joins an exporter's dropped one. */
    fun maxOfNullable(a: Long?, b: Long?): Long? = when {
        a == null -> b
        b == null -> a
        else -> maxOf(a, b)
    }
}
