package com.pulseloop.health.accuracy

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.AccuracyCheckEntity
import com.pulseloop.data.entity.AccuracyPausedUnitEntity
import com.pulseloop.data.entity.DeviceEntity
import com.pulseloop.health.HealthConnectAvailability
import com.pulseloop.health.HealthConnectExportWorker
import com.pulseloop.health.HealthConnectPrefsStore
import com.pulseloop.health.HealthConnectSdk
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import kotlin.reflect.KClass

/** Which ring a check or a correction belongs to. */
object RingIdentity {
    const val DEMO_DEVICE_ID = "demo-device"

    /**
     * The BLE address when known: the device row is reused across pairings, so its id doesn't
     * change when the user switches rings, but the address does.
     */
    fun keyOf(d: DeviceEntity): String =
        (d.bleAddressHint ?: d.peripheralIdentifier ?: d.id).uppercase()

    fun labelOf(d: DeviceEntity): String =
        (d.advertisedName?.takeIf { it.isNotBlank() } ?: d.name).ifBlank { "Ring" }
}

/**
 * Every state change of an accuracy check goes through here (docs/accuracy-check-plan.md,
 * Phase 1): start, the pause set growing, ending (on time, early, or because a different ring
 * connected), and the resume choice that decides what happens to the check window's held-back
 * Health Connect export.
 */
object AccuracyCheckController {

    private const val TAG = "AccuracyCheck"
    val DURATIONS_DAYS = listOf(3, 5, 7, 14)
    const val DEFAULT_DAYS = 7
    private const val DAY_MS = 86_400_000L

    sealed interface StartResult {
        data class Started(val check: AccuracyCheckEntity) : StartResult
        data object AlreadyOpen : StartResult
        data object NoRing : StartResult
        data object NothingToCompare : StartResult
    }

    suspend fun start(
        context: Context,
        source: ReferenceSourceSurvey,
        referenceLabel: String,
        days: Int,
        now: Long = System.currentTimeMillis(),
    ): StartResult {
        val db = PulseLoopDatabase.getInstance(context)
        val dao = db.accuracyDao()
        if (dao.open() != null) return StartResult.AlreadyOpen
        val ring = db.deviceDao().currentReal() ?: return StartResult.NoRing
        val units = source.metrics.map { ExportUnit.of(it.metric) }.toSet()
        if (units.isEmpty()) return StartResult.NothingToCompare
        val check = AccuracyCheckEntity(
            deviceKey = RingIdentity.keyOf(ring),
            deviceLabel = RingIdentity.labelOf(ring),
            referencePackage = source.packageName,
            referenceLabel = referenceLabel.ifBlank { source.packageName },
            startedAt = now,
            plannedEndAt = now + days.coerceAtLeast(DURATIONS_DAYS.first()) * DAY_MS,
            pauseRefreshedAt = now,
            firmware = ring.firmwareVersion,
        )
        dao.upsert(check)
        dao.insertPausedUnits(units.map { AccuracyPausedUnitEntity(check.id, it.name, pausedFrom = now) })
        AccuracyCheckWorker.schedule(context, check.plannedEndAt)
        Log.i(TAG, "started ${check.id}: ${check.referencePackage}, ${units.joinToString()} paused until ${Instant.ofEpochMilli(check.plannedEndAt)}")
        return StartResult.Started(check)
    }

    /** RUNNING → AWAITING_RESUME. Export stays paused until [resolve]. */
    suspend fun end(context: Context, reason: String, at: Long = System.currentTimeMillis()): AccuracyCheckEntity? {
        val dao = PulseLoopDatabase.getInstance(context).accuracyDao()
        val check = dao.open()?.takeIf { it.state == AccuracyCheckEntity.STATE_RUNNING } ?: return null
        val ended = check.copy(
            state = AccuracyCheckEntity.STATE_AWAITING_RESUME,
            endedAt = at.coerceIn(check.startedAt, check.plannedEndAt),
            endReason = reason,
        )
        dao.upsert(ended)
        AccuracyCheckWorker.cancelEnd(context)
        Log.i(TAG, "ended ${check.id}: $reason")
        return ended
    }

    /**
     * AWAITING_RESUME → DONE. [AccuracyCheckEntity.RESUME_SKIP] closes the exclusions at the check's
     * end, and that is all it needs to do: the export already advanced past the held-back records.
     * [AccuracyCheckEntity.RESUME_EXPORT] drops the exclusions and rewinds each affected watermark to
     * where holding back began, so the window's rows re-select. Taken under the export's pass
     * mutex: a pass in flight could otherwise advance a watermark past the rewind.
     */
    suspend fun resolve(context: Context, choice: String) {
        val db = PulseLoopDatabase.getInstance(context)
        val dao = db.accuracyDao()
        val check = dao.open()?.takeIf { it.state == AccuracyCheckEntity.STATE_AWAITING_RESUME } ?: return
        HealthConnectExportWorker.passMutex.withLock {
            dao.upsert(check.copy(state = AccuracyCheckEntity.STATE_DONE, resumeChoice = choice))
            if (choice == AccuracyCheckEntity.RESUME_EXPORT) {
                val store = HealthConnectPrefsStore.get(context)
                dao.pausedUnits(check.id)
                    .mapNotNull { p -> ExportUnit.byName(p.unit)?.let { it.watermark to p.pausedFrom } }
                    .groupBy({ it.first }, { it.second })
                    .forEach { (key, froms) -> store.rewindWatermarks(setOf(key), froms.min()) }
            }
        }
        AccuracyCheckWorker.cancelAll(context)
        HealthConnectExportWorker.enqueue(context)
    }

    /** Ends a running check whose time is up. Returns the check it ended, if any. */
    suspend fun endIfDue(context: Context, now: Long = System.currentTimeMillis()): AccuracyCheckEntity? {
        val check = PulseLoopDatabase.getInstance(context).accuracyDao().open() ?: return null
        if (check.state != AccuracyCheckEntity.STATE_RUNNING || now < check.plannedEndAt) return null
        return end(context, AccuracyCheckEntity.END_COMPLETED, check.plannedEndAt)
    }

    /**
     * A different ring is now the paired one: data from here on isn't this check's ring, so the
     * check ends now (plan, "Changing rings"). The export pause follows the reference device and
     * stays until the resume choice.
     */
    suspend fun onRingChanged(context: Context, device: DeviceEntity, now: Long = System.currentTimeMillis()) {
        val check = PulseLoopDatabase.getInstance(context).accuracyDao().open() ?: return
        if (check.state != AccuracyCheckEntity.STATE_RUNNING) return
        val key = RingIdentity.keyOf(device)
        if (key == check.deviceKey) return
        // Same row, better key: the check started before the address was known and was keyed on
        // the row id or peripheral id. That's the same ring learning its address, not a new ring.
        val weakerKeys = listOfNotNull(device.id, device.peripheralIdentifier).map { it.uppercase() }
        if (check.deviceKey in weakerKeys) {
            PulseLoopDatabase.getInstance(context).accuracyDao().upsert(check.copy(deviceKey = key))
            return
        }
        end(context, AccuracyCheckEntity.END_RING_CHANGED, now)
    }

    /**
     * Grows the pause set with any type the reference started writing since the check began
     * (plan: the set comes from the reference device, never from the ring). Each joins from the
     * time of the reference's first record of it, not from the start of the check. Needs a
     * Health Connect read, so it runs in the foreground; background reads may be refused.
     */
    suspend fun refreshPauseSet(context: Context, now: Long = System.currentTimeMillis()): Set<ExportUnit> {
        val db = PulseLoopDatabase.getInstance(context)
        val dao = db.accuracyDao()
        val check = dao.open()?.takeIf { it.state == AccuracyCheckEntity.STATE_RUNNING } ?: return emptySet()
        if (HealthConnectSdk.availability(context) != HealthConnectAvailability.AVAILABLE) return emptySet()
        val client = HealthConnectClient.getOrCreate(context)
        val readable = AccuracyCheckPermissions.readable(ReferenceSourceScanner.grantedPermissions(context))
        val already = dao.pausedUnits(check.id).mapNotNull { ExportUnit.byName(it.unit) }.toSet()
        val added = mutableSetOf<ExportUnit>()
        for (unit in ExportUnit.entries) {
            if (unit in already || unit.metric !in readable) continue
            val first = runCatching {
                firstRecordTime(client, unit.metric.recordType, check.referencePackage, check.startedAt, now)
            }.onFailure { Log.w(TAG, "pause refresh ${unit.name} failed", it) }.getOrNull() ?: continue
            dao.insertPausedUnits(listOf(AccuracyPausedUnitEntity(check.id, unit.name, pausedFrom = maxOf(check.startedAt, first))))
            added += unit
        }
        dao.upsert(check.copy(pauseRefreshedAt = now))
        if (added.isNotEmpty()) Log.i(TAG, "pause set grew: ${added.joinToString()}")
        return added
    }

    private suspend fun <T : Record> firstRecordTime(
        client: HealthConnectClient,
        type: KClass<T>,
        pkg: String,
        from: Long,
        to: Long,
    ): Long? {
        val r = client.readRecords(
            ReadRecordsRequest(
                type,
                TimeRangeFilter.between(Instant.ofEpochMilli(from), Instant.ofEpochMilli(to)),
                dataOriginFilter = setOf(DataOrigin(pkg)),
                ascendingOrder = true,
                pageSize = 1,
            ),
        ).records.firstOrNull() ?: return null
        return ReferenceRecords.startOf(r)
    }

    /** Units the open check holds back right now, for the export settings screen. */
    suspend fun pausedNow(context: Context): Set<ExportUnit> {
        val dao = PulseLoopDatabase.getInstance(context).accuracyDao()
        return ExportPause.pausedNow(ExportPause.exclusions(dao.all(), dao.allPausedUnits()))
    }
}
