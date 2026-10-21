package com.pulseloop.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One accuracy check: the ring compared against another device's Health Connect data for a few
 * days (docs/accuracy-check-plan.md). At most one check is open (RUNNING or AWAITING_RESUME) at a
 * time; [com.pulseloop.health.accuracy.AccuracyCheckController] owns every state change.
 */
@Entity(tableName = "accuracy_checks", indices = [Index("state")])
data class AccuracyCheckEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    /** The ring this check belongs to ([com.pulseloop.health.accuracy.RingIdentity.keyOf]). */
    val deviceKey: String,
    val deviceLabel: String,
    val referencePackage: String,
    /** What the user called the reference ("Health Sync (Huawei Watch GT 4)"). */
    val referenceLabel: String,
    val startedAt: Long,
    val plannedEndAt: Long,
    val endedAt: Long? = null,
    /** RUNNING → AWAITING_RESUME (ended, export choice pending) → DONE. */
    val state: String = STATE_RUNNING,
    /** Why it ended: COMPLETED, ENDED_EARLY, RING_CHANGED. */
    val endReason: String? = null,
    /** The export choice made on resume: SKIP (the default) or EXPORT. */
    val resumeChoice: String? = null,
    /** User-edited agreement bands, `metric=value;…`; null = defaults. */
    val bandsRaw: String? = null,
    /** Last time the pause set was re-read from the reference's Health Connect records. */
    val pauseRefreshedAt: Long? = null,
    /** Last time the days were scored. */
    val scoredAt: Long? = null,
    /** The ring's firmware when the check started, so a correction can tell it went stale. */
    val firmware: String? = null,
) {
    val isOpen: Boolean get() = state == STATE_RUNNING || state == STATE_AWAITING_RESUME

    companion object {
        const val STATE_RUNNING = "RUNNING"
        const val STATE_AWAITING_RESUME = "AWAITING_RESUME"
        const val STATE_DONE = "DONE"

        const val END_COMPLETED = "COMPLETED"
        const val END_EARLY = "ENDED_EARLY"
        const val END_RING_CHANGED = "RING_CHANGED"

        const val RESUME_SKIP = "SKIP"
        const val RESUME_EXPORT = "EXPORT"
    }
}

/**
 * One Health Connect export unit held back during a check, because the reference device writes
 * the same type ([com.pulseloop.health.accuracy.ExportUnit]). [pausedFrom] is when holding back
 * began: the check's start for types seen at start, later for a type the reference only started
 * writing mid-check.
 */
@Entity(tableName = "accuracy_paused_units", primaryKeys = ["checkId", "unit"])
data class AccuracyPausedUnitEntity(
    val checkId: String,
    val unit: String,
    val pausedFrom: Long,
)

/**
 * One scored day of one metric in one check — aggregates only, never the reference's readings
 * (plan, "Reading the reference data"). Paired metrics (heart rate, SpO₂) keep sums so days
 * combine exactly; daily totals keep the two values.
 */
@Entity(tableName = "accuracy_check_days", primaryKeys = ["checkId", "metric", "day"])
data class AccuracyCheckDayEntity(
    val checkId: String,
    /** [com.pulseloop.health.accuracy.ComparisonMetric] name. */
    val metric: String,
    /** Epoch day (local). For sleep, the waking day. */
    val day: Long,
    val ringValue: Double? = null,
    val refValue: Double? = null,
    /** Pairs compared (samples, or minutes for sleep stages); 1 for a daily total. */
    val pairs: Int = 0,
    val sumDiff: Double = 0.0,
    val sumSqDiff: Double = 0.0,
    val sumAbsDiff: Double = 0.0,
    /** Pairs inside the agreement band at scoring time (sleep stages: minutes on the same stage). */
    val inBand: Int = 0,
    /** False for a day either device clearly wasn't worn, or the reference hasn't delivered yet. */
    val valid: Boolean = true,
    /** Why a day doesn't count, or extra detail (stage confusion counts). */
    val note: String? = null,
    val computedAt: Long = System.currentTimeMillis(),
)

/**
 * A correction fitted from a check, for one ring and one metric (plan, "Corrections"). Applied at
 * read time only — stored rows stay exactly as the ring sent them, and nothing corrected reaches
 * Health Connect.
 */
@Entity(tableName = "accuracy_corrections", primaryKeys = ["deviceKey", "metric"])
data class AccuracyCorrectionEntity(
    val deviceKey: String,
    /** [com.pulseloop.health.accuracy.CorrectionMetric] name. */
    val metric: String,
    /** SCALE (multiply) or OFFSET (add). */
    val kind: String,
    /** The value in use — the fitted one, or what the user moved the slider to. */
    val value: Double,
    val fittedValue: Double,
    val fromCheckId: String,
    val enabled: Boolean = true,
    /** The ring's firmware when fitted; a different firmware makes the correction stale. */
    val firmware: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val KIND_SCALE = "SCALE"
        const val KIND_OFFSET = "OFFSET"
    }
}
