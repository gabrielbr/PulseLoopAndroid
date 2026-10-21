package com.pulseloop.health.accuracy

import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.AccuracyCheckEntity
import com.pulseloop.data.entity.AccuracyCorrectionEntity
import com.pulseloop.data.entity.DeviceEntity
import com.pulseloop.service.DailyCalorieEstimator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt

/**
 * The corrections in force for the paired ring (docs/accuracy-check-plan.md, "Corrections"),
 * applied at read time only. Stored rows stay as the ring sent them — it re-sends its history on
 * every sync, so a rewritten row wouldn't stay rewritten anyway — and switching a correction off
 * puts every figure back. Nothing corrected reaches Health Connect: the exporters read the raw
 * rows and never call this.
 *
 * Applied to everything shown while that ring is paired, history included. Stored data carries no
 * ring id, so "this ring's readings" can't be told apart from an earlier ring's; the corrections
 * follow the ring that is paired now, and a different ring starts with none.
 */
data class Corrections(
    val deviceKey: String?,
    val items: Map<ComparisonMetric, AccuracyCorrectionEntity>,
    /** For the rest/active split of heart rate; see [heartRate]. */
    val age: Int? = null,
) {
    val isEmpty: Boolean get() = items.isEmpty()

    fun has(m: ComparisonMetric): Boolean = m in items

    private fun scale(m: ComparisonMetric): Double = items[m]?.value ?: 1.0
    private fun offset(m: ComparisonMetric): Double = items[m]?.value ?: 0.0

    fun steps(v: Int): Int = if (has(ComparisonMetric.STEPS)) (v * scale(ComparisonMetric.STEPS)).roundToInt() else v
    fun distanceMeters(v: Double): Double = v * scale(ComparisonMetric.DISTANCE)

    /**
     * A heart-rate reading. A single sample's context isn't known at read time, so the split
     * between the at-rest and active offsets is the Flex-HR threshold — the same one
     * [DailyCalorieEstimator] uses to decide which samples are exertion.
     */
    fun heartRate(bpm: Double): Double {
        if (!has(ComparisonMetric.HR_REST) && !has(ComparisonMetric.HR_ACTIVE)) return bpm
        val active = bpm >= DailyCalorieEstimator.flexHR(age ?: DEFAULT_AGE)
        val m = if (active && has(ComparisonMetric.HR_ACTIVE)) ComparisonMetric.HR_ACTIVE
            else if (!active && has(ComparisonMetric.HR_REST)) ComparisonMetric.HR_REST
            else if (has(ComparisonMetric.HR_REST)) ComparisonMetric.HR_REST else ComparisonMetric.HR_ACTIVE
        return (bpm + offset(m)).coerceAtLeast(1.0)
    }

    /** A resting figure: its own correction when one was fitted, else the at-rest heart-rate offset. */
    fun restingHr(bpm: Double): Double = when {
        has(ComparisonMetric.RESTING_HR) -> bpm + offset(ComparisonMetric.RESTING_HR)
        has(ComparisonMetric.HR_REST) -> bpm + offset(ComparisonMetric.HR_REST)
        else -> bpm
    }

    /**
     * The resting-HR baseline behind the HR zones. The baseline is a percentile and the at-rest
     * correction an offset, and an offset moves a percentile by exactly itself, so the adjusted
     * baseline needs no recompute (plan, Corrections rule 6).
     */
    fun baseline(bpm: Double): Double = restingHr(bpm)

    fun spo2(v: Double): Double = if (has(ComparisonMetric.SPO2)) (v + offset(ComparisonMetric.SPO2)).coerceIn(0.0, 100.0) else v

    fun asleepMinutes(v: Int): Int =
        if (has(ComparisonMetric.SLEEP_ASLEEP)) (v + offset(ComparisonMetric.SLEEP_ASLEEP)).roundToInt().coerceAtLeast(0) else v

    /** A day's activity totals as shown: steps and distance corrected, the stored row untouched. */
    fun day(row: com.pulseloop.data.entity.ActivityDailyEntity): com.pulseloop.data.entity.ActivityDailyEntity =
        if (!has(ComparisonMetric.STEPS) && !has(ComparisonMetric.DISTANCE)) row
        else row.copy(steps = steps(row.steps), distanceMeters = distanceMeters(row.distanceMeters))

    /** A night as shown: its time asleep corrected. Stage blocks are never corrected. */
    fun sleep(s: com.pulseloop.data.entity.SleepSessionEntity): com.pulseloop.data.entity.SleepSessionEntity =
        if (!has(ComparisonMetric.SLEEP_ASLEEP)) s else s.copy(totalMinutes = asleepMinutes(s.totalMinutes))

    /** A stored reading as shown: heart rate and SpO₂ corrected, any other kind unchanged. */
    fun measurement(m: com.pulseloop.data.entity.MeasurementEntity): com.pulseloop.data.entity.MeasurementEntity = when (m.kindRaw) {
        com.pulseloop.ring.MeasurementKind.HEART_RATE.name -> if (has(ComparisonMetric.HR_REST) || has(ComparisonMetric.HR_ACTIVE)) m.copy(value = heartRate(m.value)) else m
        com.pulseloop.ring.MeasurementKind.SPO2.name -> if (has(ComparisonMetric.SPO2)) m.copy(value = spo2(m.value)) else m
        else -> m
    }

    /**
     * The line the coach is told when it's handed corrected figures, so it neither presents them
     * as raw ring readings nor "corrects" them a second time.
     */
    fun coachNote(): String? = describe()?.let {
        "Some figures are adjusted by the user's accuracy check against another device ($it). " +
            "They are what the app shows; the ring's stored readings are unchanged."
    }

    /** "Steps ×1.18 · heart rate at rest −3.0 bpm" — the caption a corrected screen carries. */
    fun describe(only: Set<ComparisonMetric>? = null): String? = items.values
        .mapNotNull { c -> ComparisonMetric.byName(c.metric)?.let { it to c } }
        .filter { (m, _) -> only == null || m in only }
        .sortedBy { it.first.ordinal }
        .joinToString(" · ") { (m, c) -> "${m.label.lowercase()} ${AccuracyCorrections.formatValue(m, c.value)}" }
        .ifEmpty { null }

    companion object {
        const val DEFAULT_AGE = 35
        val NONE = Corrections(null, emptyMap())
    }
}

object AccuracyCorrections {

    /** A correction older than this, or fitted on other firmware, is flagged for a re-check. */
    const val STALE_MS = 90L * 86_400_000L

    val CALORIE_INPUTS = setOf(ComparisonMetric.STEPS, ComparisonMetric.HR_REST, ComparisonMetric.HR_ACTIVE)

    fun formatValue(m: ComparisonMetric, v: Double): String = when (m.correction) {
        CorrectionKind.SCALE -> "×%.2f".format(v)
        CorrectionKind.OFFSET -> AccuracyReportText.signed(m, v)
        null -> v.toString()
    }

    fun of(device: DeviceEntity?, all: List<AccuracyCorrectionEntity>, age: Int?): Corrections {
        val d = device?.takeIf { it.id != RingIdentity.DEMO_DEVICE_ID } ?: return Corrections.NONE
        val key = RingIdentity.keyOf(d)
        val items = all.filter { it.deviceKey == key && it.enabled }
            .mapNotNull { c -> ComparisonMetric.byName(c.metric)?.let { it to c } }
            .toMap()
        return Corrections(key, items, age)
    }

    suspend fun load(db: PulseLoopDatabase): Corrections = runCatching {
        of(db.deviceDao().currentReal(), db.accuracyDao().allCorrections(), db.userProfileDao().get()?.age)
    }.getOrDefault(Corrections.NONE)

    fun flow(db: PulseLoopDatabase): Flow<Corrections> =
        combine(db.deviceDao().currentFlow(), db.accuracyDao().allCorrectionsFlow()) { d, all ->
            of(d, all, null)
        }.distinctUntilChanged()

    fun isStale(c: AccuracyCorrectionEntity, device: DeviceEntity?, now: Long = System.currentTimeMillis()): Boolean =
        now - c.createdAt > STALE_MS ||
            (c.firmware != null && device?.firmwareVersion != null && device.firmwareVersion != c.firmware)

    /**
     * Saves a correction for the check's ring, from the check's fitted suggestion or the value the
     * user moved it to. Only for the ring that is paired now: a correction fitted on another ring
     * would be invented data for this one.
     */
    suspend fun accept(
        db: PulseLoopDatabase,
        check: AccuracyCheckEntity,
        summary: AccuracyComparison.Summary,
        value: Double,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val kind = summary.metric.correction ?: return false
        val fitted = summary.suggestedCorrection ?: return false
        val ring = db.deviceDao().currentReal() ?: return false
        if (RingIdentity.keyOf(ring) != check.deviceKey) return false
        val existing = db.accuracyDao().corrections(check.deviceKey).firstOrNull { it.metric == summary.metric.name }
        db.accuracyDao().upsertCorrection(
            AccuracyCorrectionEntity(
                deviceKey = check.deviceKey,
                metric = summary.metric.name,
                kind = if (kind == CorrectionKind.SCALE) AccuracyCorrectionEntity.KIND_SCALE else AccuracyCorrectionEntity.KIND_OFFSET,
                value = value,
                fittedValue = fitted,
                fromCheckId = check.id,
                enabled = true,
                firmware = check.firmware ?: ring.firmwareVersion,
                createdAt = existing?.createdAt?.takeIf { existing.fromCheckId == check.id } ?: now,
                updatedAt = now,
            ),
        )
        if (summary.metric in CALORIE_INPUTS) recomputeAdjustedCalories(db)
        return true
    }

    suspend fun setEnabled(db: PulseLoopDatabase, c: AccuracyCorrectionEntity, enabled: Boolean) {
        db.accuracyDao().upsertCorrection(c.copy(enabled = enabled, updatedAt = System.currentTimeMillis()))
        if (ComparisonMetric.byName(c.metric) in CALORIE_INPUTS) recomputeAdjustedCalories(db)
    }

    suspend fun remove(db: PulseLoopDatabase, c: AccuracyCorrectionEntity) {
        db.accuracyDao().deleteCorrection(c.deviceKey, c.metric)
        if (ComparisonMetric.byName(c.metric) in CALORIE_INPUTS) recomputeAdjustedCalories(db)
    }

    /**
     * Re-runs the calorie estimate over recent days, so the corrected copy follows the
     * corrections exactly and switching them off restores it (Corrections rule 6). The raw
     * estimate — what Health Connect gets — is recomputed alongside from the same inputs and
     * comes out unchanged.
     */
    suspend fun recomputeAdjustedCalories(db: PulseLoopDatabase, days: Int = 90) {
        val profile = db.userProfileDao().get()?.let { DailyCalorieEstimator.profileOf(it) } ?: return
        db.activityDailyDao().recentReal(days).forEach { day ->
            runCatching { DailyCalorieEstimator.recompute(day.date, db, profile, touchUpdatedAt = false) }
        }
    }
}
