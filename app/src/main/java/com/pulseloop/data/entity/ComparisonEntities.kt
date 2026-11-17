package com.pulseloop.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One reading from the ring connected only for comparison (docs/ring-comparison.md). Kept apart
 * from `measurements` on purpose: nothing here feeds a score, a chart outside the comparison
 * screen, Health Connect, Strava or the backup. The id is `<kind>:<timestamp>` within one ring, so
 * a history re-sync updates a row instead of adding one.
 *
 * [kind] is a `MeasurementKind` name, or `STEPS` (one activity bucket's steps) or `SLEEP` (one
 * minute, with the `SleepStage` ordinal as the value).
 */
@Entity(
    tableName = "comparison_readings",
    indices = [Index(value = ["deviceAddress", "kind", "timestamp"])],
)
data class ComparisonReadingEntity(
    @PrimaryKey val id: String,
    val deviceAddress: String,
    val kind: String,
    val value: Double,
    val timestamp: Long,
    /** "live" for a streamed sample, "history" for one the ring logged. */
    val source: String,
)
