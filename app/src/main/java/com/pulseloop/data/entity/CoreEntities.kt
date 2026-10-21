package com.pulseloop.data.entity

import androidx.room.*
import com.pulseloop.ring.RingConnectionState
import com.pulseloop.ring.RingDeviceType
import com.pulseloop.ring.WearableCapability
import java.time.Instant

/**
 * Ported from [Device] in PulseModels.swift.
 * The connected wearable ring.
 */
@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "SMART_RING",
    val advertisedName: String? = null,
    val peripheralIdentifier: String? = null,
    val bleAddressHint: String? = null,
    val batteryPercent: Int? = null,
    val stateRaw: String = RingConnectionState.IDLE.name,
    val deviceTypeRaw: String = RingDeviceType.JRING.name,
    /** Exact catalog model (e.g. `colmi-r10`), separate from the protocol/driver family (iOS #49). */
    val wearableModelID: String? = null,
    val capabilitiesRaw: String = "",   // CSV of WearableCapability names
    val lastConnectedAt: Long? = null,
    val lastDisconnectedAt: Long? = null,
    val lastSyncAt: Long? = null,
    /** Stamped only when a history sync actually completes (`SyncProgress("done")`) — unlike
     *  [lastSyncAt], which the ring re-stamps on every bare CONNECT before any data streams.
     *  The coach-notification freshness gate reads this one (iOS #61c). */
    val lastFullSyncAt: Long? = null,
    val firmwareVersion: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val state: RingConnectionState get() = try { RingConnectionState.valueOf(stateRaw) } catch (_: Exception) { RingConnectionState.IDLE }
    val deviceType: RingDeviceType get() = try { RingDeviceType.valueOf(deviceTypeRaw) } catch (_: Exception) { RingDeviceType.JRING }
    val capabilities: Set<WearableCapability> get() = WearableCapability.fromCsv(capabilitiesRaw)
}

/**
 * Ported from [Measurement] in PulseModels.swift.
 * A single sensor reading (HR, SpO2, stress, HRV, temperature).
 */
@Entity(
    tableName = "measurements",
    indices = [
        Index("timestamp"),
        Index("activitySessionId"),
        Index("kindRaw"),
        Index(value = ["kindRaw", "timestamp", "sourceRaw"]),
    ],
)
data class MeasurementEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val kindRaw: String,           // MeasurementKind ordinal
    val value: Double,
    val unit: String,
    val timestamp: Long,            // epoch millis
    val sourceRaw: String,          // MeasurementSource ordinal
    val confidenceRaw: String = "known",
    val activitySessionId: String? = null,
    val rawPacketId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * Ported from [ActivityDaily] in PulseModels.swift.
 * Aggregated daily activity totals.
 */
@Entity(
    tableName = "activity_daily",
    indices = [Index("date", unique = true)],
)
data class ActivityDailyEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val date: Long,                 // epoch millis, start of day
    val steps: Int = 0,
    val calories: Double = 0.0,
    val distanceMeters: Double = 0.0,
    val activeMinutes: Int = 0,
    val source: String = "mock",
    val syncedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** Ported from iOS #98: net active calories estimated from all-day HR + step buckets when the
     *  ring does not report device-side calories. Read through [effectiveActiveCalories]. */
    val estimatedActiveCalories: Double? = null,
    /**
     * [estimatedActiveCalories] recomputed with the ring's accuracy corrections applied to its
     * inputs (docs/accuracy-check-plan.md, Corrections rule 6), or null when no correction applies.
     * The app shows this one; Health Connect gets the raw one, never this.
     */
    val estimatedActiveCaloriesAdjusted: Double? = null,
    /**
     * What this day's deleted activity buckets contributed, kept so the ring's own cumulative
     * counter can be corrected for them (issue #70).
     *
     * `activity_buckets` holds only the buckets that survive, so a sum over them restates the day
     * — but the live `ActivityUpdate` total the ring pushes all day is a *cumulative* figure that
     * still includes the deleted block, and the live path ratchets the day up against it. Without
     * this the deletion was undone by the next frame, seconds later.
     *
     * See [com.pulseloop.data.ActivityBucketDeletion].
     */
    val deletedSteps: Int = 0,
    val deletedDistanceMeters: Double = 0.0,
)

/**
 * Ported from [ActivityBucketSample] in PulseModels.swift.
 * One intraday activity bucket from a ring's history sync (a Colmi `0x43` sample).
 * Keyed by `startEpoch` (the bucket's start time, epoch millis) so re-syncing the same bucket
 * **replaces** it rather than accumulating — the daily total is then the sum of distinct buckets.
 * This is the GadgetBridge model and the fix for daily totals drifting across repeated syncs.
 */
@Entity(
    tableName = "activity_buckets",
    indices = [Index("date")],
)
data class ActivityBucketEntity(
    /** Bucket start time in epoch millis — the primary key, so the same bucket upserts. */
    @PrimaryKey val startEpoch: Long,
    val date: Long,                 // epoch millis, local start of day, for per-day queries
    val steps: Int = 0,
    val distanceMeters: Double = 0.0,
    val source: String = "ring_history",
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * Ported from [BatterySample] in PulseModels.swift (iOS #61b). A throttled log of the ring's
 * battery level over time, feeding the Wearable-settings drainage chart. Written by
 * [com.pulseloop.service.EventPersistenceSubscriber] on change or a 30-min floor — not every
 * `BatteryLevel` event, so the table stays a few dozen rows/day.
 */
@Entity(
    tableName = "battery_samples",
    indices = [Index("timestamp")],
)
data class BatterySampleEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val percent: Int,
    val timestamp: Long,            // epoch millis
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * Ported from [DeviceMeasurementConfig] in PulseModels.swift (iOS PR #19).
 * Per-device background-measurement preferences pushed to the ring on save and on connect:
 * all-day HR sampling interval plus per-vital enable toggles.
 */
@Entity(tableName = "device_measurement_configs")
data class DeviceMeasurementConfigEntity(
    /** The DeviceEntity id this config belongs to. */
    @PrimaryKey val deviceId: String,
    /** All-day HR sampling interval, minutes. 5-minute steps, 5..60. */
    val hrIntervalMinutes: Int = 5,
    val hrEnabled: Boolean = true,
    val spo2Enabled: Boolean = true,
    /**
     * All-day SpO₂ sampling interval, minutes — 0 means "follow the heart-rate interval" (issue #66).
     *
     * The monitor command takes the same shape for every vital (`01 <key> {enable, interval}`), so
     * an interval was always settable for blood oxygen; it simply wasn't surfaced, and inherited
     * whatever heart rate was set to. On a ring running both monitors hourly that is most of the
     * battery, which is why the reporter asked for it separately.
     */
    val spo2IntervalMinutes: Int = 0,
    val stressEnabled: Boolean = true,
    val hrvEnabled: Boolean = true,
    val temperatureEnabled: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * Ported from [ActivitySession] in PulseModels.swift.
 * A workout recording session.
 */
@Entity(tableName = "activity_sessions", indices = [Index("startedAt")])
data class ActivitySessionEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val type: String,
    val statusRaw: String = "recording",
    val startedAt: Long,            // epoch millis
    val endedAt: Long? = null,
    val totalPauseSeconds: Double = 0.0,
    val calories: Double? = null,
    val distanceMeters: Double? = null,
    val avgHeartRate: Double? = null,
    val minHeartRate: Double? = null,
    val maxHeartRate: Double? = null,
    val avgSpO2: Double? = null,
    val latestSpO2: Double? = null,
    val notes: String? = null,
    val useGps: Boolean = true,
    val perceivedEffort: String? = null,
    val gpsPointCount: Int = 0,
    val rejectedGpsPointCount: Int = 0,
    val hrPollCount: Int = 0,
    val hrPollFailureCount: Int = 0,
    val spo2PollCount: Int = 0,
    val spo2PollFailureCount: Int = 0,
    val liveActivityID: String? = null,
    val lastSensorPollAt: Long? = null,
    val lastGpsPointAt: Long? = null,
    /** iOS #100: Strava activity ID after successful upload. null = not uploaded yet. */
    val stravaActivityId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * Ported from [ActivityGpsPoint] in PulseModels.swift.
 */
@Entity(tableName = "activity_gps_points", indices = [Index("sessionId"), Index("timestamp")])
data class ActivityGpsPointEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val sessionId: String,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double? = null,
    val horizontalAccuracy: Double? = null,
    val speed: Double? = null,
    val course: Double? = null,
    val timestamp: Long,
    val accepted: Boolean = true,
    val rejectionReason: String? = null,
)

/**
 * Ported from [UserProfile] in PulseModels.swift.
 */
@Entity(tableName = "user_profiles")
data class UserProfileEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val name: String? = null,
    val age: Int? = null,
    val sex: String? = null,
    val heightCm: Double? = null,
    val weightKg: Double? = null,
    /**
     * v30: the year of birth, so [age] can be kept current instead of going stale every birthday.
     * Derived from the age the user enters (Health Connect has no age or birth-date type).
     */
    val birthYear: Int? = null,
    /** v30: latest body fat % and lean mass, from a scale via Health Connect. */
    val bodyFatPct: Double? = null,
    val leanMassKg: Double? = null,
    /** v30: when [weightKg] was last set from Health Connect (null = entered by hand). */
    val bodyUpdatedAt: Long? = null,
    val onboardingCompleted: Boolean = false,
    val baselineCompleted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** iOS #95: HR zone mode — "standard", "auto" (from baseline), or "custom". Default "auto". */
    val hrZoneModeRaw: String = "auto",
    /** iOS #95: Resting-HR baseline learned from 30-day p10. */
    val hrRestingBaseline: Double? = null,
    val hrRestingBaselineUpdatedAt: Long? = null,
    /** iOS #95: Custom HR zone boundaries (only used when hrZoneModeRaw == "custom"). */
    val hrCustomLowUpper: Double? = null,
    val hrCustomAthleticUpper: Double? = null,
    val hrCustomElevatedStart: Double? = null,
    val hrCustomHighStart: Double? = null,
)

/**
 * Ported from [UserGoal] in PulseModels.swift.
 */
@Entity(tableName = "user_goals")
data class UserGoalEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val steps: Int = DEFAULT_STEPS,
    /** Daily distance goal in meters (iOS UserGoal.distanceMeters, added in iOS #48). */
    @ColumnInfo(defaultValue = "8000.0") val distanceMeters: Double = DEFAULT_DISTANCE_METERS,
    /** Daily active-calorie goal in kcal (iOS UserGoal.calories, added in iOS #48). */
    @ColumnInfo(defaultValue = "500") val calories: Int = DEFAULT_CALORIES,
    val sleepMinutes: Int = 480,
    val activeMinutes: Int = 45,
    val workoutsPerWeek: Int = 4,
    /** iOS #96: Nutrition intake goals + master toggle. */
    val intakeCalories: Double? = null,
    val intakeProteinG: Double? = null,
    val intakeCarbsG: Double? = null,
    val intakeFatG: Double? = null,
    val nutritionEnabled: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        // Canonical no-goal-row fallbacks (match iOS UserGoal). Every surface that
        // renders ring progress against a goal — Today tiles, Activity screen, the
        // widget snapshot — must fall back through these, not a local literal, or the
        // widget and the app disagree the day one copy changes.
        const val DEFAULT_STEPS = 10_000
        const val DEFAULT_DISTANCE_METERS = 8_000.0
        const val DEFAULT_CALORIES = 500
    }
}

/**
 * A reading the user deleted (issue #60).
 *
 * Removing a row is not enough on its own. History-sourced measurements are keyed by
 * `history:<kind>:<timestamp>` and written with `upsert`, precisely so a re-sync of a day the ring
 * still holds is idempotent — which also means the next sync would put a deleted reading straight
 * back. This table is the memory that says not to: the deletion outlives the row, so the same
 * reading stays gone across every later sync of the same day.
 *
 * Only rows the ring can regenerate need an entry; a live reading's id is a fresh UUID that will
 * never be written again. [MeasurementDeletionDao.record] applies that rule.
 */
@Entity(tableName = "measurement_deletions")
data class MeasurementDeletionEntity(
    /** The deleted measurement's primary key — deterministic, or this row would be pointless. */
    @PrimaryKey val measurementId: String,
    val kindRaw: String,
    /** The reading's own timestamp, kept so a future retention sweep can age these out. */
    val timestamp: Long,
    val deletedAt: Long = System.currentTimeMillis(),
)

/**
 * One journal tag on one day ("Alcohol" on 2026-10-17), for "what affects you" insights
 * (docs/health-app-feature-research.md). [day] is the local midnight of the day the behaviour
 * happened; its effect is read from the night that follows. The id is "<day>:<tag>", so tagging
 * the same thing twice on one day is idempotent and untagging is a delete by id.
 */
@Entity(tableName = "journal_entries", indices = [Index("day")])
data class JournalEntryEntity(
    @PrimaryKey val id: String,
    val day: Long,
    val tag: String,
    val createdAt: Long = System.currentTimeMillis(),
) {
    companion object {
        fun idFor(day: Long, tag: String) = "$day:$tag"
    }
}

/**
 * v30: body measurements imported from Health Connect (a smart scale's weight, body fat, lean,
 * bone and water mass, BMR; height). Keyed by the Health Connect record id, so a re-import
 * updates rather than duplicates. Not part of the data archive: Health Connect still holds every
 * row, and the next import restores them.
 */
@Entity(tableName = "body_measurements", indices = [Index(value = ["type", "measuredAt"])])
data class BodyMeasurementEntity(
    @PrimaryKey val id: String,
    /** One of [BodyMeasurementEntity.Companion] TYPE_* constants. */
    val type: String,
    /** kg for the masses, % for body fat, m for height, kcal/day for BMR. */
    val value: Double,
    val measuredAt: Long,
    /** The package that wrote it to Health Connect (e.g. the scale's app). */
    val source: String,
) {
    companion object {
        const val TYPE_WEIGHT = "weight"
        const val TYPE_HEIGHT = "height"
        const val TYPE_BODY_FAT = "body_fat"
        const val TYPE_LEAN_MASS = "lean_mass"
        const val TYPE_BONE_MASS = "bone_mass"
        const val TYPE_BODY_WATER = "body_water"
        const val TYPE_BMR = "bmr"
    }
}

/**
 * v30: the goal the user set with the coach ("lose 5 kg in 60 days"). One active at a time; an
 * older one is kept inactive when a new one replaces it. The weight fields are filled for weight
 * goals only; [description] is the user's own words for every kind.
 */
@Entity(tableName = "coach_goals")
data class CoachGoalEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    /** One of GoalPlan.ALL_KINDS (lose_weight, build_muscle, sleep_better, move_more, …). */
    val kind: String,
    val description: String,
    val startWeightKg: Double? = null,
    val targetWeightKg: Double? = null,
    /**
     * The habit target for non-weight goals, in the kind's unit: hours a night (sleep_better),
     * steps a day (move_more), workouts a week (get_fitter, build_muscle). Null = the app's default.
     */
    val targetValue: Double? = null,
    val startDate: Long,
    val targetDate: Long? = null,
    val active: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * v31: a food preference the user and the coach agreed on ("banana instead of vegetables at
 * night"). Meal suggestions use it whenever it still fits the day's budget. [timesAgreed] grows
 * each time the same preference is agreed again, so a repeated choice weighs more.
 */
@Entity(tableName = "food_preferences")
data class FoodPreferenceEntity(
    @PrimaryKey val id: String,
    /** breakfast, lunch, snack, dinner, or any. */
    val slot: String,
    /** The food, in the user's words or a catalog name. */
    val item: String,
    /** prefer or avoid. */
    val action: String,
    /** What it replaces in a suggestion (a food or a role like "vegetables"), if anything. */
    val insteadOf: String? = null,
    /** Portion and its nutrition when the food isn't in the catalog. */
    val portion: String? = null,
    val kcal: Double? = null,
    val proteinG: Double? = null,
    val reason: String? = null,
    val timesAgreed: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        /** One row per slot + item + action, so agreeing again counts up instead of duplicating. */
        fun idFor(slot: String, item: String, action: String) = "$slot:${item.trim().lowercase()}:$action"
    }
}

/**
 * One "what changed" finding (service/Findings.kt): one row per day and kind, stamped with the
 * formula version that produced it. Derived and recomputable, so it isn't in the backup archive.
 */
@Entity(tableName = "findings", indices = [Index("day")])
data class FindingEntity(
    @PrimaryKey val id: String,
    val day: Long,
    val kind: String,
    val title: String,
    val detail: String,
    val positive: Boolean,
    val algorithmVersion: Int,
    val createdAt: Long = System.currentTimeMillis(),
)

/** A logged period (service/Cycle.kt); days are epoch days in the user's calendar. */
@Entity(tableName = "periods")
data class PeriodEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val startDay: Long,
    val endDay: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/** A medication or supplement on a daily schedule (service/Meds.kt). [timesCsv] is "08:00,20:00". */
@Entity(tableName = "meds")
data class MedEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val dose: String? = null,
    /** "medication" or "supplement". */
    val kind: String = "medication",
    val timesCsv: String,
    val active: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
)

/** One answered dose: taken or skipped. A past dose with no row is missed; nothing is stored for it. */
@Entity(tableName = "med_doses", indices = [Index("day")])
data class MedDoseEntity(
    @PrimaryKey val id: String,
    val medId: String,
    /** Epoch day of the scheduled dose. */
    val day: Long,
    val time: String,
    /** "taken" or "skipped". */
    val status: String,
    val at: Long = System.currentTimeMillis(),
) {
    companion object { fun idFor(medId: String, day: Long, time: String) = "$medId:$day:$time" }
}
