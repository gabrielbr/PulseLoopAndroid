package com.pulseloop.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.BodyMeasurementEntity
import com.pulseloop.data.entity.UserProfileEntity
import java.time.Instant
import java.time.Year
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass

/**
 * Reads body measurements a smart scale writes to Health Connect (Fitdays and the like): weight,
 * body fat, lean, bone and water mass, BMR, and height. This is the app's only Health Connect
 * *read* path — the export side stays write-only — and it is opted into separately, from the
 * profile screen, with its own permission request.
 *
 * Each pass stores the readings it finds and moves the profile to the newest weight, height and
 * body composition, so calories follow the scale. A type the scale never writes (most write no
 * height) simply stays as the user entered it. Muscle mass and BMI have no Health Connect type.
 */
object HealthConnectBodyImport {

    private const val TAG = "HCBodyImport"
    private const val LOOKBACK_DAYS = 180L

    private val TYPES: List<Pair<KClass<out Record>, String>> = listOf(
        WeightRecord::class to BodyMeasurementEntity.TYPE_WEIGHT,
        HeightRecord::class to BodyMeasurementEntity.TYPE_HEIGHT,
        BodyFatRecord::class to BodyMeasurementEntity.TYPE_BODY_FAT,
        LeanBodyMassRecord::class to BodyMeasurementEntity.TYPE_LEAN_MASS,
        BoneMassRecord::class to BodyMeasurementEntity.TYPE_BONE_MASS,
        BodyWaterMassRecord::class to BodyMeasurementEntity.TYPE_BODY_WATER,
        BasalMetabolicRateRecord::class to BodyMeasurementEntity.TYPE_BMR,
    )

    /** The read permissions this import asks for. Also declared in the manifest. */
    val permissions: Set<String> = TYPES.map { HealthPermission.getReadPermission(it.first) }.toSet()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("hc_body_import", Context.MODE_PRIVATE)

    /** Whether the user switched the import on (the permission can still be revoked under it). */
    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean("enabled", false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("enabled", enabled).apply()
        if (enabled) {
            schedule(context)
            runNow(context)
        } else {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
        }
    }

    private const val PERIODIC = "hc_body_import"
    private const val ONE_SHOT = "hc_body_import_now"

    fun schedule(context: Context) {
        if (!isEnabled(context)) return
        val request = PeriodicWorkRequestBuilder<HealthConnectBodyImportWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun runNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_SHOT, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<HealthConnectBodyImportWorker>().build(),
        )
    }

    /** One import pass. Returns how many readings were stored. */
    suspend fun run(context: Context, now: Long = System.currentTimeMillis()): Int {
        if (HealthConnectSdk.availability(context) != HealthConnectAvailability.AVAILABLE) return 0
        val client = HealthConnectClient.getOrCreate(context)
        val granted = runCatching { client.permissionController.getGrantedPermissions() }.getOrDefault(emptySet())
        val db = PulseLoopDatabase.getInstance(context)
        val since = Instant.ofEpochMilli(now).minusSeconds(LOOKBACK_DAYS * 86_400)
        var stored = 0
        suspend fun <T : Record> read(cls: KClass<T>): List<Record> =
            client.readRecords(ReadRecordsRequest(cls, TimeRangeFilter.after(since), pageSize = 1000)).records
        for ((cls, type) in TYPES) {
            if (HealthPermission.getReadPermission(cls) !in granted) continue
            val rows = runCatching {
                // One concrete call per type: the generic request can't be built from the
                // list's KClass<out Record>.
                val records = when (type) {
                    BodyMeasurementEntity.TYPE_WEIGHT -> read(WeightRecord::class)
                    BodyMeasurementEntity.TYPE_HEIGHT -> read(HeightRecord::class)
                    BodyMeasurementEntity.TYPE_BODY_FAT -> read(BodyFatRecord::class)
                    BodyMeasurementEntity.TYPE_LEAN_MASS -> read(LeanBodyMassRecord::class)
                    BodyMeasurementEntity.TYPE_BONE_MASS -> read(BoneMassRecord::class)
                    BodyMeasurementEntity.TYPE_BODY_WATER -> read(BodyWaterMassRecord::class)
                    else -> read(BasalMetabolicRateRecord::class)
                }
                records.mapNotNull { toEntity(it, type) }
            }.onFailure { Log.w(TAG, "read $type failed", it) }.getOrDefault(emptyList())
            if (rows.isNotEmpty()) {
                db.bodyMeasurementDao().upsertAll(rows)
                stored += rows.size
            }
        }
        applyToProfile(db)
        prefs(context).edit().putLong("lastRunAt", now).apply()
        return stored
    }

    private fun toEntity(r: Record, type: String): BodyMeasurementEntity? {
        val (value, time) = when (r) {
            is WeightRecord -> r.weight.inKilograms to r.time
            is HeightRecord -> r.height.inMeters to r.time
            is BodyFatRecord -> r.percentage.value to r.time
            is LeanBodyMassRecord -> r.mass.inKilograms to r.time
            is BoneMassRecord -> r.mass.inKilograms to r.time
            is BodyWaterMassRecord -> r.mass.inKilograms to r.time
            is BasalMetabolicRateRecord -> r.basalMetabolicRate.inKilocaloriesPerDay to r.time
            else -> return null
        }
        if (!value.isFinite() || value <= 0) return null
        return BodyMeasurementEntity(
            id = "hc:$type:${r.metadata.id}",
            type = type,
            value = value,
            measuredAt = time.toEpochMilli(),
            source = r.metadata.dataOrigin.packageName,
        )
    }

    /**
     * Moves the profile to the newest readings. Weight and body composition follow the scale only
     * when the reading is newer than what the profile already has from it, so a value typed by
     * hand after the last weigh-in stands until the next one.
     */
    suspend fun applyToProfile(db: PulseLoopDatabase) {
        val dao = db.bodyMeasurementDao()
        val profile = db.userProfileDao().get() ?: return
        val weight = dao.latest(BodyMeasurementEntity.TYPE_WEIGHT)
        val height = dao.latest(BodyMeasurementEntity.TYPE_HEIGHT)
        val fat = dao.latest(BodyMeasurementEntity.TYPE_BODY_FAT)
        val lean = dao.latest(BodyMeasurementEntity.TYPE_LEAN_MASS)
        val updated = merge(profile, weight, height, fat, lean)
        val aged = ProfileAging.refreshed(updated)
        if (aged != profile) db.userProfileDao().upsert(aged.copy(updatedAt = System.currentTimeMillis()))
    }

    /** Pure: the profile with any newer scale readings applied. */
    fun merge(
        profile: UserProfileEntity,
        weight: BodyMeasurementEntity?,
        height: BodyMeasurementEntity?,
        fat: BodyMeasurementEntity?,
        lean: BodyMeasurementEntity?,
    ): UserProfileEntity {
        var p = profile
        val since = p.bodyUpdatedAt ?: Long.MIN_VALUE
        if (weight != null && weight.measuredAt > since && weight.value in 20.0..400.0) {
            p = p.copy(weightKg = weight.value, bodyUpdatedAt = weight.measuredAt)
            // Body composition is only meaningful alongside the weight it was measured with, so
            // take it from the same weigh-in (within an hour) or clear what the old one left.
            fun sameWeighIn(m: BodyMeasurementEntity?) = m?.takeIf { kotlin.math.abs(it.measuredAt - weight.measuredAt) <= 3_600_000L }
            p = p.copy(
                bodyFatPct = sameWeighIn(fat)?.value?.takeIf { it in 3.0..70.0 },
                leanMassKg = sameWeighIn(lean)?.value?.takeIf { it in 20.0..150.0 },
            )
        }
        if (height != null && height.value in 1.0..2.6) {
            val cm = height.value * 100
            if (p.heightCm == null || kotlin.math.abs(p.heightCm!! - cm) >= 0.5) p = p.copy(heightCm = cm)
        }
        return p
    }
}

/** Keeps a profile's age current from its year of birth (Health Connect has no age type). */
object ProfileAging {
    fun refreshed(p: UserProfileEntity, year: Int = Year.now().value): UserProfileEntity = when {
        p.birthYear != null -> (year - p.birthYear).let { if (p.age != it) p.copy(age = it) else p }
        p.age != null -> p.copy(birthYear = year - p.age)
        else -> p
    }

    suspend fun refresh(db: PulseLoopDatabase) {
        val p = db.userProfileDao().get() ?: return
        val r = refreshed(p)
        if (r != p) db.userProfileDao().upsert(r)
    }
}

class HealthConnectBodyImportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!HealthConnectBodyImport.isEnabled(applicationContext)) return Result.success()
        return runCatching { HealthConnectBodyImport.run(applicationContext) }
            .fold({ Result.success() }, { Log.w("HCBodyImport", "pass failed", it); Result.success() })
    }
}
