package com.pulseloop.health.accuracy

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import kotlin.reflect.KClass

/**
 * What the accuracy check can compare, one entry per Health Connect record type it reads
 * (docs/accuracy-check-plan.md, "Per metric"). Display order.
 */
enum class ReferenceMetric(val label: String, val recordType: KClass<out Record>) {
    STEPS("Steps", StepsRecord::class),
    DISTANCE("Distance", DistanceRecord::class),
    ACTIVE_CALORIES("Active calories", ActiveCaloriesBurnedRecord::class),
    HEART_RATE("Heart rate", HeartRateRecord::class),
    RESTING_HEART_RATE("Resting heart rate", RestingHeartRateRecord::class),
    SLEEP("Sleep", SleepSessionRecord::class),
    OXYGEN_SATURATION("Blood oxygen", OxygenSaturationRecord::class),
    HRV("HRV", HeartRateVariabilityRmssdRecord::class),
}

/**
 * The accuracy check's Health Connect **read** permissions — a third set, kept apart from the
 * export's write set ([com.pulseloop.health.HealthConnectPermissions.all]) and the scale import's
 * reads ([com.pulseloop.health.HealthConnectBodyImport.permissions]). The export's reconcile
 * filters the granted set down to its own permissions, so granting these never looks like an
 * export grant or revoke. Derived from the record classes, never hardcoded; also declared in the
 * manifest.
 */
object AccuracyCheckPermissions {

    fun readPermission(metric: ReferenceMetric): String = HealthPermission.getReadPermission(metric.recordType)

    val all: Set<String> = ReferenceMetric.entries.map(::readPermission).toSet()

    /**
     * What the screen asks for: the types, plus background reading so the 6-hourly worker can
     * grow the pause set without the app open. Optional — where Health Connect doesn't offer it,
     * or the user declines, the pause set is refreshed whenever the screen is opened instead.
     */
    val request: Set<String> = all + HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

    /** The metrics [granted] lets the scan read. */
    fun readable(granted: Set<String>): Set<ReferenceMetric> =
        ReferenceMetric.entries.filter { readPermission(it) in granted }.toSet()
}
