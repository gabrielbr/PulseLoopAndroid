package com.pulseloop.service

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.pulseloop.health.HealthConnectAvailability
import com.pulseloop.health.HealthConnectSdk
import com.pulseloop.health.source.HealthConnectSource
import com.pulseloop.ui.i18n.trf
import java.time.Instant

/**
 * Workouts another device or app saved to Health Connect (a watch, Strava, Samsung Health), offered
 * on the Activity tab as suggestions, with a ring paired too. The other device knew a workout was
 * happening, so its type and times beat any guess from the ring's 5-minute heart-rate grid.
 *
 * Rules, each of which fails silently if broken:
 * - **PulseLoop's own records are never read back.** Its exported workouts come back from Health
 *   Connect under its own package, which is filtered out here.
 * - **Nothing is saved without a tap**, like every detection (AGENTS.md "Detected workouts").
 * - **An accepted one keeps the `hc-` id** ([HealthConnectSource.WORKOUT_ID_PREFIX]), so the
 *   export skips it (`WorkoutExporter`) and it is never written back under PulseLoop's name.
 * - **Off while Health Connect is the data source**: that mode already imports these workouts.
 */
object OtherDeviceWorkouts {

    data class Found(
        /** `hc-` + the Health Connect record id: the session id if accepted. */
        val id: String,
        val type: String,
        val startAt: Long,
        val endAt: Long,
        /** The app that wrote it, as the user knows it ("Galaxy Wearable"). */
        val app: String,
    )

    val READ: String = HealthPermission.getReadPermission(ExerciseSessionRecord::class)

    /** What the Activity tab asks for: the read, and background reading so a sync can notify. */
    val REQUEST: Set<String> = setOf(READ, HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)

    /** Whether reading can work at all on this phone right now (Health Connect there, not in source mode). */
    fun applies(c: Context): Boolean =
        !HealthConnectSource.isEnabled(c) && HealthConnectSdk.availability(c) == HealthConnectAvailability.AVAILABLE

    suspend fun granted(c: Context): Boolean = applies(c) && runCatching {
        READ in HealthConnectClient.getOrCreate(c).permissionController.getGrantedPermissions()
    }.getOrDefault(false)

    /** Workouts in [from]..[to] written by any app but PulseLoop. Empty when not allowed or unavailable. */
    suspend fun read(c: Context, from: Long, to: Long): List<Found> {
        if (!granted(c)) return emptyList()
        val client = HealthConnectClient.getOrCreate(c)
        val out = mutableListOf<Found>()
        var token: String? = null
        var pages = 0
        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    ExerciseSessionRecord::class,
                    TimeRangeFilter.between(Instant.ofEpochMilli(from), Instant.ofEpochMilli(to)),
                    pageSize = 200, pageToken = token,
                ),
            )
            response.records.forEach { r ->
                val pkg = r.metadata.dataOrigin.packageName
                if (pkg != c.packageName && r.endTime > r.startTime) {
                    out += Found(
                        id = HealthConnectSource.WORKOUT_ID_PREFIX + r.metadata.id,
                        type = HealthConnectSource.workoutType(r.exerciseType),
                        startAt = r.startTime.toEpochMilli(),
                        endAt = r.endTime.toEpochMilli(),
                        app = HealthConnectSource.label(c, pkg),
                    )
                }
            }
            token = response.pageToken
            pages++
        } while (token != null && pages < 5)
        return out
    }

    /** Suggestions for [found], each offering its own type first. Pure, for tests. */
    fun proposals(found: List<Found>): List<ActivityDetection.Proposal> = found
        .filter { it.endAt - it.startAt >= 5 * 60_000L }
        .map { f ->
            ActivityDetection.Proposal(
                kind = when (f.type) {
                    "walk" -> ActivityDetection.Proposal.Kind.WALK
                    "run" -> ActivityDetection.Proposal.Kind.RUN
                    else -> ActivityDetection.Proposal.Kind.CARDIO
                },
                startAt = f.startAt,
                endAt = f.endAt,
                detail = trf("Recorded by {0}", f.app),
                guesses = listOf(f.type) + ActivityClassifier.TYPES.filter { it != f.type },
                external = ActivityDetection.External(f.id, f.app),
            )
        }

    /**
     * Ring-detected stretches that overlap a workout another device recorded are dropped: the
     * device's own record is the better answer for the same time.
     */
    fun withoutOverlaps(detected: List<ActivityDetection.Proposal>, external: List<ActivityDetection.Proposal>) =
        detected.filter { d -> external.none { e -> e.startAt < d.endAt && d.startAt < e.endAt } }
}
