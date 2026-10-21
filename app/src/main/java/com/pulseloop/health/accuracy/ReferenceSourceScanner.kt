package com.pulseloop.health.accuracy

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.health.HealthConnectAvailability
import com.pulseloop.health.HealthConnectSdk
import com.pulseloop.ring.MeasurementKind
import java.time.Instant
import kotlin.reflect.KClass

/**
 * Reads the last week of Health Connect for every type the accuracy check could compare and hands
 * it to [ReferenceSurveyBuilder] (docs/accuracy-check-plan.md, Phase 0). Foreground only — it runs
 * when the user taps Scan — because background reads get a much smaller rate-limit quota.
 */
object ReferenceSourceScanner {

    private const val TAG = "AccuracyScan"
    const val LOOKBACK_DAYS = 7L
    /** Pages per type (1 000 records each). A week of anything fits well inside this; the cap is
     *  only there so a source writing absurd volumes can't keep the screen spinning. */
    private const val MAX_PAGES = 20

    sealed interface Result {
        data object Unavailable : Result
        data class Done(
            val sources: List<ReferenceSourceSurvey>,
            val readable: Set<ReferenceMetric>,
            val failed: Set<ReferenceMetric>,
            val scannedAt: Long,
        ) : Result {
            val unreadable: Set<ReferenceMetric> get() = ReferenceMetric.entries.toSet() - readable
        }
    }

    suspend fun grantedPermissions(context: Context): Set<String> {
        if (HealthConnectSdk.availability(context) != HealthConnectAvailability.AVAILABLE) return emptySet()
        return runCatching {
            HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions()
        }.getOrDefault(emptySet())
    }

    suspend fun scan(context: Context, now: Long = System.currentTimeMillis()): Result {
        if (HealthConnectSdk.availability(context) != HealthConnectAvailability.AVAILABLE) return Result.Unavailable
        val client = HealthConnectClient.getOrCreate(context)
        val readable = AccuracyCheckPermissions.readable(grantedPermissions(context))
        val since = now - LOOKBACK_DAYS * 86_400_000L
        val range = TimeRangeFilter.between(Instant.ofEpochMilli(since), Instant.ofEpochMilli(now))
        val builder = ReferenceSurveyBuilder(ownPackage = context.packageName)
        val failed = mutableSetOf<ReferenceMetric>()

        for (metric in readable) {
            runCatching {
                when (metric) {
                    ReferenceMetric.STEPS -> readAll(client, StepsRecord::class, range) {
                        builder.add(interval(metric, it.metadata, it.startTime, it.endTime))
                    }
                    ReferenceMetric.DISTANCE -> readAll(client, DistanceRecord::class, range) {
                        builder.add(interval(metric, it.metadata, it.startTime, it.endTime))
                    }
                    ReferenceMetric.ACTIVE_CALORIES -> readAll(client, ActiveCaloriesBurnedRecord::class, range) {
                        builder.add(interval(metric, it.metadata, it.startTime, it.endTime))
                    }
                    ReferenceMetric.HEART_RATE -> readAll(client, HeartRateRecord::class, range) { r ->
                        builder.add(
                            record(metric, r.metadata, r.samples.map { it.time.toEpochMilli() }, r.endTime).copy(
                                heartRate = r.samples.map { TimedValue(it.time.toEpochMilli(), it.beatsPerMinute.toDouble()) },
                            ),
                        )
                    }
                    ReferenceMetric.RESTING_HEART_RATE -> readAll(client, RestingHeartRateRecord::class, range) {
                        builder.add(record(metric, it.metadata, listOf(it.time.toEpochMilli()), it.time))
                    }
                    ReferenceMetric.SLEEP -> readAll(client, SleepSessionRecord::class, range) { r ->
                        builder.add(
                            interval(metric, r.metadata, r.startTime, r.endTime)
                                .copy(sleepStageTypes = r.stages.map { it.stage }.toSet()),
                        )
                    }
                    ReferenceMetric.OXYGEN_SATURATION -> readAll(client, OxygenSaturationRecord::class, range) {
                        builder.add(record(metric, it.metadata, listOf(it.time.toEpochMilli()), it.time))
                    }
                    ReferenceMetric.HRV -> readAll(client, HeartRateVariabilityRmssdRecord::class, range) {
                        builder.add(record(metric, it.metadata, listOf(it.time.toEpochMilli()), it.time))
                    }
                }
            }.onFailure {
                Log.w(TAG, "read ${metric.name} failed", it)
                failed += metric
            }
        }

        val ringHr = runCatching {
            PulseLoopDatabase.getInstance(context).measurementDao()
                .rangeReal(MeasurementKind.HEART_RATE.name, since, now)
                .map { TimedValue(it.timestamp, it.value) }
        }.getOrDefault(emptyList())

        return Result.Done(builder.build(ringHr), readable, failed, now)
    }

    private fun interval(metric: ReferenceMetric, meta: Metadata, start: Instant, end: Instant) =
        record(metric, meta, listOf(start.toEpochMilli()), end)

    private fun record(metric: ReferenceMetric, meta: Metadata, times: List<Long>, end: Instant) = SurveyRecord(
        packageName = meta.dataOrigin.packageName,
        metric = metric,
        sampleTimes = times,
        endTime = end.toEpochMilli(),
        lastModified = meta.lastModifiedTime.toEpochMilli(),
        device = meta.device?.let { DeviceInfo(it.manufacturer, it.model, it.type) },
    )

    private suspend fun <T : Record> readAll(
        client: HealthConnectClient,
        type: KClass<T>,
        range: TimeRangeFilter,
        consume: (T) -> Unit,
    ) {
        var token: String? = null
        var pages = 0
        do {
            val response = client.readRecords(ReadRecordsRequest(type, range, pageSize = 1000, pageToken = token))
            response.records.forEach(consume)
            token = response.pageToken
            pages++
        } while (token != null && pages < MAX_PAGES)
    }
}
