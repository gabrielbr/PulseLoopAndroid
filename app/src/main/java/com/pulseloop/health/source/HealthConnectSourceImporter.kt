package com.pulseloop.health.source

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.MenstruationPeriodRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.pulseloop.AppServices
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.ActivityDailyEntity
import com.pulseloop.data.entity.ActivitySessionEntity
import com.pulseloop.data.entity.PeriodEntity
import com.pulseloop.health.HealthConnectAvailability
import com.pulseloop.health.HealthConnectSdk
import com.pulseloop.ring.MeasurementKind
import com.pulseloop.ring.PulseEvent
import com.pulseloop.service.ActivityAggregates
import com.pulseloop.service.ActivityRollup
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId
import kotlin.reflect.KClass

/**
 * One import pass from the chosen source app ([HealthConnectSource]). Every reading becomes the
 * same [PulseEvent] a ring's history would produce and goes through the persistence subscriber,
 * so the import inherits everything that path already gets right: deterministic ids (a re-read
 * updates rather than duplicates), the user's deletions, sleep reconciliation by waking day, and
 * activity buckets summed per day.
 */
object HealthConnectSourceImporter {

    private const val TAG = "HCSource"
    private const val MAX_PAGES = 50
    private val passLock = Mutex()

    sealed interface Outcome {
        data object Off : Outcome
        data object Unavailable : Outcome
        data object NoPermission : Outcome
        data class Done(val stored: Int) : Outcome
        data class Failed(val message: String) : Outcome
    }

    suspend fun run(context: Context, now: Long = System.currentTimeMillis()): Outcome = passLock.withLock {
        if (!HealthConnectSource.isEnabled(context)) return Outcome.Off
        val pkg = HealthConnectSource.sourcePackage(context) ?: return Outcome.Off
        if (HealthConnectSdk.availability(context) != HealthConnectAvailability.AVAILABLE) return Outcome.Unavailable
        val client = HealthConnectClient.getOrCreate(context)
        val granted = runCatching { client.permissionController.getGrantedPermissions() }.getOrDefault(emptySet())
        if (granted.none { it in HealthConnectSource.readPermissions }) return Outcome.NoPermission

        val services = AppServices.get(context)
        val db = services.db
        val window = HealthConnectSource.window(HealthConnectSource.lastImportAt(context), now)
        val range = TimeRangeFilter.between(Instant.ofEpochMilli(window.first), Instant.ofEpochMilli(window.last))
        val origin = setOf(DataOrigin(pkg))
        val events = mutableListOf<PulseEvent>()
        val failures = mutableListOf<String>()
        fun can(type: KClass<out Record>) = HealthPermission.getReadPermission(type) in granted
        suspend fun step(name: String, block: suspend () -> Unit) {
            runCatching { block() }.onFailure {
                Log.w(TAG, "import $name failed", it)
                failures += name
            }
        }

        step("heart rate") {
            if (can(HeartRateRecord::class)) readAll(client, HeartRateRecord::class, range, origin) { r ->
                r.samples.forEach { s ->
                    val bpm = s.beatsPerMinute.toDouble()
                    if (bpm in 30.0..230.0) events += PulseEvent.HistoryMeasurement(MeasurementKind.HEART_RATE, bpm, s.time)
                }
            }
        }
        step("HRV") {
            if (can(HeartRateVariabilityRmssdRecord::class)) readAll(client, HeartRateVariabilityRmssdRecord::class, range, origin) { r ->
                if (r.heartRateVariabilityMillis in 1.0..250.0) {
                    events += PulseEvent.HistoryMeasurement(MeasurementKind.HRV, r.heartRateVariabilityMillis, r.time)
                }
            }
        }
        step("blood oxygen") {
            if (can(OxygenSaturationRecord::class)) readAll(client, OxygenSaturationRecord::class, range, origin) { r ->
                val pct = r.percentage.value
                if (pct in 70.0..100.0) events += PulseEvent.HistoryMeasurement(MeasurementKind.SPO2, pct, r.time)
            }
        }
        step("respiratory rate") {
            if (can(RespiratoryRateRecord::class)) readAll(client, RespiratoryRateRecord::class, range, origin) { r ->
                if (r.rate in 4.0..60.0) events += PulseEvent.HistoryMeasurement(MeasurementKind.RESPIRATORY_RATE, r.rate, r.time)
            }
        }
        step("sleep") {
            if (can(SleepSessionRecord::class)) readAll(client, SleepSessionRecord::class, range, origin) { r ->
                val start = r.startTime.toEpochMilli() / 60_000L * 60_000L
                val minutes = HealthConnectSource.minuteStages(
                    start, r.endTime.toEpochMilli(),
                    r.stages.map { HealthConnectSource.Stage(it.startTime.toEpochMilli(), it.endTime.toEpochMilli(), it.stage) },
                )
                if (minutes != null) events += PulseEvent.SleepTimeline(Instant.ofEpochMilli(start), minutes, completeSession = true)
            }
        }
        step("steps") {
            if (can(StepsRecord::class)) {
                val withDistance = can(DistanceRecord::class)
                val metrics = buildSet {
                    add(StepsRecord.COUNT_TOTAL)
                    if (withDistance) add(DistanceRecord.DISTANCE_TOTAL)
                }
                // A week per request keeps each response to a few hundred 15-minute groups.
                var from = window.first / 900_000L * 900_000L
                while (from < window.last) {
                    val to = minOf(window.last, from + 7 * 86_400_000L)
                    client.aggregateGroupByDuration(
                        AggregateGroupByDurationRequest(
                            metrics = metrics,
                            timeRangeFilter = TimeRangeFilter.between(Instant.ofEpochMilli(from), Instant.ofEpochMilli(to)),
                            timeRangeSlicer = Duration.ofMinutes(15),
                            dataOriginFilter = origin,
                        ),
                    ).forEach { g ->
                        val steps = g.result[StepsRecord.COUNT_TOTAL] ?: 0L
                        val meters = if (withDistance) g.result[DistanceRecord.DISTANCE_TOTAL]?.inMeters ?: 0.0 else 0.0
                        if (steps > 0 || meters > 0) {
                            events += PulseEvent.ActivityBucket(g.startTime, steps.toInt().coerceAtMost(50_000), meters)
                        }
                    }
                    from = to
                }
            }
        }

        val stored = events.size
        services.persistence.ingest(events)

        // After the buckets, so the day rows exist and carry the device's own calorie figure.
        step("calories") {
            val metric = when {
                can(TotalCaloriesBurnedRecord::class) -> TotalCaloriesBurnedRecord.ENERGY_TOTAL
                can(ActiveCaloriesBurnedRecord::class) -> ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL
                else -> null
            }
            if (metric != null) {
                val zone = ZoneId.systemDefault()
                val fromDay = Instant.ofEpochMilli(window.first).atZone(zone).toLocalDate()
                val toDay = Instant.ofEpochMilli(window.last).atZone(zone).toLocalDate().plusDays(1)
                client.aggregateGroupByPeriod(
                    AggregateGroupByPeriodRequest(
                        metrics = setOf(metric),
                        timeRangeFilter = TimeRangeFilter.between(fromDay.atStartOfDay(), LocalDateTime.of(toDay, java.time.LocalTime.MIDNIGHT)),
                        timeRangeSlicer = Period.ofDays(1),
                        dataOriginFilter = origin,
                    ),
                ).forEach { g ->
                    val kcal = g.result[metric]?.inKilocalories ?: return@forEach
                    if (kcal <= 0 || kcal > 20_000) return@forEach
                    val day = g.startTime.toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
                    val existing = db.activityDailyDao().byDay(day)
                    db.activityDailyDao().upsert(
                        (existing ?: ActivityDailyEntity(date = day)).copy(
                            calories = kcal,
                            source = HealthConnectSource.ACTIVITY_SOURCE,
                            syncedAt = now,
                            updatedAt = now,
                        ),
                    )
                }
            }
        }
        var workouts = 0
        step("workouts") {
            if (can(ExerciseSessionRecord::class)) {
                val seen = HealthConnectSource.seenWorkouts(context)
                val fresh = mutableListOf<String>()
                readAll(client, ExerciseSessionRecord::class, range, origin) { r ->
                    val id = HealthConnectSource.WORKOUT_ID_PREFIX + r.metadata.id
                    if (id !in seen) fresh += id
                }
                if (fresh.isNotEmpty()) {
                    readAll(client, ExerciseSessionRecord::class, range, origin) { r ->
                        val id = HealthConnectSource.WORKOUT_ID_PREFIX + r.metadata.id
                        if (id in fresh && db.activitySessionDao().byId(id) == null && r.endTime > r.startTime) {
                            val session = ActivitySessionEntity(
                                id = id,
                                type = HealthConnectSource.workoutType(r.exerciseType),
                                statusRaw = "finished",
                                startedAt = r.startTime.toEpochMilli(),
                                endedAt = r.endTime.toEpochMilli(),
                                notes = r.title ?: "From ${HealthConnectSource.label(context, pkg)}",
                                useGps = false,
                            )
                            val summarized = ActivityAggregates.recompute(db, session)
                            db.activitySessionDao().upsert(summarized)
                            ActivityRollup.credit(db, summarized)
                            workouts++
                        }
                    }
                    HealthConnectSource.markWorkoutsSeen(context, fresh)
                }
            }
        }
        step("periods") {
            if (can(MenstruationPeriodRecord::class)) {
                val zone = ZoneId.systemDefault()
                val known = db.periodDao().all().map { it.startDay }.toSet()
                readAll(client, MenstruationPeriodRecord::class, range, origin) { r ->
                    val start = r.startTime.atZone(zone).toLocalDate().toEpochDay()
                    // The record's end is exclusive: a period ending at midnight ended the day before.
                    val end = r.endTime.minusMillis(1).atZone(zone).toLocalDate().toEpochDay().coerceAtLeast(start)
                    if (known.none { kotlin.math.abs(it - start) <= 2 }) {
                        db.periodDao().upsert(PeriodEntity(id = "hc-${r.metadata.id}", startDay = start, endDay = end))
                    }
                }
            }
        }

        val total = stored + workouts
        val error = failures.takeIf { it.size >= 6 }?.let { "Couldn't read ${it.joinToString(", ")}" }
        HealthConnectSource.recordPass(context, now, total, error)
        return if (error != null) Outcome.Failed(error) else Outcome.Done(total)
    }

    private suspend fun <T : Record> readAll(
        client: HealthConnectClient,
        type: KClass<T>,
        range: TimeRangeFilter,
        origin: Set<DataOrigin>,
        consume: suspend (T) -> Unit,
    ) {
        var token: String? = null
        var pages = 0
        do {
            val response = client.readRecords(
                ReadRecordsRequest(type, range, dataOriginFilter = origin, pageSize = 1000, pageToken = token),
            )
            response.records.forEach { consume(it) }
            token = response.pageToken
            pages++
        } while (token != null && pages < MAX_PAGES)
    }
}

class HealthConnectSourceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!HealthConnectSource.isEnabled(applicationContext)) return Result.success()
        return runCatching { HealthConnectSourceImporter.run(applicationContext) }
            .fold({ Result.success() }, { Log.w("HCSource", "pass failed", it); Result.success() })
    }
}
