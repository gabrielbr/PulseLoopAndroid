package com.pulseloop.health.source

import android.content.Context
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.MenstruationPeriodRecord
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.pulseloop.health.accuracy.AccuracyCheckPermissions
import com.pulseloop.health.accuracy.ExportExclusion
import com.pulseloop.health.accuracy.ExportUnit
import com.pulseloop.health.accuracy.ReferenceAppLabels
import com.pulseloop.ring.SleepStage
import com.pulseloop.ring.WearableCapability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.TimeUnit

/**
 * "No ring, Health Connect instead" (docs/health-connect-source.md): the user wears another
 * device — an Oura, a Galaxy Ring, a Fitbit — whose app writes to Health Connect, and PulseLoop
 * reads that one app's records into the same tables a paired ring fills, so every screen works.
 *
 * Three rules hold the feature together:
 * - **One source app, by package.** Every read carries a `DataOrigin` filter. Reading "everything
 *   in Health Connect" would pull PulseLoop's own earlier exports back in, and mix two devices'
 *   steps into one day.
 * - **Exclusive with a paired ring.** Both would write the same 15-minute activity slots and the
 *   same nights; the screen refuses to switch on while a ring is paired.
 * - **Nothing goes back.** While this is on, every exported type the source writes is held back
 *   from the Health Connect export ([exportExclusions]), or the app would echo the source's own
 *   data to it under PulseLoop's name. Meals and workouts recorded in PulseLoop still export.
 */
object HealthConnectSource {

    /** How far back the first import reads. Health Connect itself only serves 30 days before the first grant. */
    const val FIRST_IMPORT_DAYS = 30L
    /** Later passes re-read this much, because source apps write late (a night lands in the morning). */
    const val OVERLAP_DAYS = 3L

    /** Source rows' marker in `activity_daily.source`, so the device's own calorie figure is used. */
    const val ACTIVITY_SOURCE = "health_connect"
    /** Prefix of a workout imported from Health Connect; the workout export skips these. */
    const val WORKOUT_ID_PREFIX = "hc-"

    fun isImportedWorkout(id: String): Boolean = id.startsWith(WORKOUT_ID_PREFIX)

    // ── Settings ─────────────────────────────────────────────────────────────────────────────

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("hc_source", Context.MODE_PRIVATE)

    @Volatile private var cachedEnabled: Boolean? = null
    @Volatile private var active: MutableStateFlow<String?>? = null

    /** The source app's package while the mode is on, null while off; for the header and settings. */
    fun activeSource(c: Context): StateFlow<String?> = active ?: synchronized(this) {
        active ?: MutableStateFlow(if (isEnabled(c)) sourcePackage(c) else null).also { active = it }
    }

    /** [isEnabled] for code with no Context (view models); warmed from the Application. */
    val enabledNow: Boolean get() = cachedEnabled == true

    /** What the cards can show while the mode is on: what Oura and its peers write to Health Connect. */
    val CAPABILITIES: Set<WearableCapability> = setOf(
        WearableCapability.HEART_RATE, WearableCapability.SPO2, WearableCapability.STEPS,
        WearableCapability.SLEEP, WearableCapability.REM_SLEEP, WearableCapability.HRV,
    )

    fun isEnabled(c: Context): Boolean = cachedEnabled ?: prefs(c).getBoolean("enabled", false).also { cachedEnabled = it }
    fun sourcePackage(c: Context): String? = prefs(c).getString("package", null)
    fun lastImportAt(c: Context): Long? = prefs(c).getLong("lastImportAt", 0L).takeIf { it > 0 }
    fun lastImportCount(c: Context): Int = prefs(c).getInt("lastImportCount", 0)
    fun lastError(c: Context): String? = prefs(c).getString("lastError", null)

    fun enable(c: Context, packageName: String) {
        val changed = sourcePackage(c) != packageName
        prefs(c).edit().putBoolean("enabled", true).putString("package", packageName).apply {
            // A different source starts from a full window, not the old one's watermark.
            if (changed) remove("lastImportAt").remove("seenWorkouts")
        }.apply()
        cachedEnabled = true
        activeSource(c); active?.value = packageName
        schedule(c)
        runNow(c)
    }

    fun disable(c: Context) {
        prefs(c).edit().putBoolean("enabled", false).apply()
        cachedEnabled = false
        activeSource(c); active?.value = null
        WorkManager.getInstance(c).cancelUniqueWork(PERIODIC)
    }

    internal fun recordPass(c: Context, at: Long, count: Int, error: String?) {
        prefs(c).edit().apply {
            if (error == null) putLong("lastImportAt", at).putInt("lastImportCount", count).remove("lastError")
            else putString("lastError", error)
        }.apply()
    }

    /** Workouts already imported once; the user deleting one must not bring it back. */
    internal fun seenWorkouts(c: Context): Set<String> = prefs(c).getStringSet("seenWorkouts", emptySet()).orEmpty()

    internal fun markWorkoutsSeen(c: Context, ids: Collection<String>) {
        if (ids.isEmpty()) return
        val keep = (seenWorkouts(c) + ids).toList().takeLast(500).toSet()
        prefs(c).edit().putStringSet("seenWorkouts", keep).apply()
    }

    fun label(c: Context, packageName: String): String = ReferenceAppLabels.label(c, packageName)

    // ── Permissions ──────────────────────────────────────────────────────────────────────────

    /** The accuracy check's reads plus what only this feature uses. Declared in the manifest too. */
    val readPermissions: Set<String> = AccuracyCheckPermissions.all + setOf(
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(RespiratoryRateRecord::class),
        HealthPermission.getReadPermission(MenstruationPeriodRecord::class),
    )

    /** What the screen asks for: the reads, plus background reading so imports run with the app closed. */
    val request: Set<String> = readPermissions + HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

    fun canReadInBackground(granted: Set<String>): Boolean =
        HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in granted

    // ── Export ───────────────────────────────────────────────────────────────────────────────

    /** While this is on, every unit the export could echo is held back, for all time. */
    fun exportExclusions(c: Context): List<ExportExclusion> =
        if (isEnabled(c)) exportExclusions() else emptyList()

    fun exportExclusions(): List<ExportExclusion> = ExportUnit.entries.map { ExportExclusion(it, Long.MIN_VALUE, null) }

    // ── Scheduling ───────────────────────────────────────────────────────────────────────────

    private const val PERIODIC = "hc_source_import"
    private const val ONE_SHOT = "hc_source_import_now"

    fun schedule(c: Context) {
        if (!isEnabled(c)) return
        val request = PeriodicWorkRequestBuilder<HealthConnectSourceWorker>(1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun runNow(c: Context) {
        if (!isEnabled(c)) return
        WorkManager.getInstance(c).enqueueUniqueWork(
            ONE_SHOT, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<HealthConnectSourceWorker>().build(),
        )
    }

    // ── Pure conversions (tested) ────────────────────────────────────────────────────────────

    /** Health Connect's stage constant as the app's stage. "Sleeping" with no stage is asleep, stage unknown. */
    fun stage(hc: Int): SleepStage = when (hc) {
        SleepSessionRecord.STAGE_TYPE_LIGHT -> SleepStage.LIGHT
        SleepSessionRecord.STAGE_TYPE_DEEP -> SleepStage.DEEP
        SleepSessionRecord.STAGE_TYPE_REM -> SleepStage.REM
        SleepSessionRecord.STAGE_TYPE_AWAKE,
        SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
        SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> SleepStage.AWAKE
        else -> SleepStage.UNKNOWN
    }

    data class Stage(val startMs: Long, val endMs: Long, val type: Int)

    /**
     * A night as one stage per minute from its start, the shape a ring's sleep record has. A night
     * with no stages at all is asleep throughout (stage unknown); with stages, a minute none of them
     * covers is awake. Each minute takes the stage covering most of it. Null for an empty or
     * over-long session.
     */
    fun minuteStages(startMs: Long, endMs: Long, stages: List<Stage>, maxMinutes: Int = 24 * 60): List<SleepStage>? {
        val minutes = ((endMs - startMs) / 60_000L).toInt()
        if (minutes <= 0 || minutes > maxMinutes) return null
        if (stages.isEmpty()) return List(minutes) { SleepStage.UNKNOWN }
        val sorted = stages.sortedBy { it.startMs }
        return List(minutes) { i ->
            val a = startMs + i * 60_000L
            val b = a + 60_000L
            var best: Stage? = null
            var bestOverlap = 0L
            for (s in sorted) {
                if (s.startMs >= b) break
                val o = minOf(b, s.endMs) - maxOf(a, s.startMs)
                if (o > bestOverlap) { bestOverlap = o; best = s }
            }
            best?.let { stage(it.type) } ?: SleepStage.AWAKE
        }
    }

    /** The app's workout type for a Health Connect exercise type; the inverse of the export's mapping. */
    fun workoutType(exerciseType: Int): String = when (exerciseType) {
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "walk"
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "run"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "cycle"
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
        ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> "gym"
        ExerciseSessionRecord.EXERCISE_TYPE_SQUASH -> "squash"
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "yoga"
        ExerciseSessionRecord.EXERCISE_TYPE_DANCING -> "dance"
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "hike"
        else -> "sport"
    }

    /** The window a pass reads: the first one goes back [FIRST_IMPORT_DAYS], later ones overlap the last. */
    fun window(lastImportAt: Long?, now: Long): LongRange {
        val from = if (lastImportAt == null) now - FIRST_IMPORT_DAYS * 86_400_000L
        else maxOf(now - FIRST_IMPORT_DAYS * 86_400_000L, lastImportAt - OVERLAP_DAYS * 86_400_000L)
        return from..now
    }
}
