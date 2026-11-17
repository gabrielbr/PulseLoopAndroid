package com.pulseloop.service

import android.content.Context
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.ComparisonReadingEntity
import com.pulseloop.ring.ComparisonBus
import com.pulseloop.ring.MeasurementKind
import com.pulseloop.ring.PulseEvent
import com.pulseloop.ring.RingBLEClient
import com.pulseloop.ring.RingConnectionState
import com.pulseloop.ring.RingRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * A second ring worn alongside the app's own, connected only to compare the two
 * (docs/ring-comparison.md). It runs its own [RingBLEClient] in [RingRole.COMPARISON]: its own
 * connection slot and saved ring, and its events on [ComparisonBus], which only this class reads.
 * Its readings go to `comparison_readings` and nowhere else: no score, no Health Connect, no
 * Strava, no backup, no device row.
 *
 * On each connect it runs the ring's normal startup (time, settings, history), so its log for the
 * day arrives like the app ring's does. During a recorded workout it streams heart rate too, so a
 * workout can be compared ring against ring.
 */
class ComparisonRing(context: Context, private val db: PulseLoopDatabase) {
    val client = RingBLEClient(context.applicationContext, role = RingRole.COMPARISON)
    val state get() = client.state
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false
    /** The recording workout's type while one runs, so a reconnect restarts its stream. */
    @Volatile private var workoutType: String? = null

    val address: String? get() = client.lastKnownAddress
    val isSetUp: Boolean get() = client.hasLastKnownRing

    fun start() {
        if (started) return
        started = true
        client.onConnected = {
            val engine = client.syncEngine
            engine?.runStartup()
            workoutType?.let { engine?.startWorkoutHeartRate(it) }
        }
        scope.launch { ComparisonBus.events.collect { persist(it) } }
        if (client.hasLastKnownRing) client.connectLastKnown()
    }

    fun startScanning() = client.startScanning()
    fun stopScanning() = client.stopScanning()

    fun pair(id: String) {
        client.stopScanning()
        client.connectTo(id)
    }

    /** Stops comparing: disconnects and forgets the saved ring without unbinding it, so the ring
     *  is still the vendor app's (or the app's, after a swap). The readings already kept stay
     *  until [clearReadings]. */
    fun stopComparing() {
        workoutType = null
        client.userDisconnect()
        client.clearSavedRing()
    }

    suspend fun clearReadings(address: String) = db.comparisonDao().clear(address)

    fun startWorkout(activityType: String) {
        workoutType = activityType
        if (client.state.value.connectionState == RingConnectionState.CONNECTED) {
            client.syncEngine?.startWorkoutHeartRate(activityType)
        }
    }

    fun stopWorkout() {
        if (workoutType == null) return
        workoutType = null
        if (client.state.value.connectionState == RingConnectionState.CONNECTED) {
            client.syncEngine?.stopWorkoutHeartRate()
        }
    }

    private suspend fun persist(event: PulseEvent) {
        val addr = address ?: return
        val rows = ComparisonReadings.rowsFor(addr, event)
        if (rows.isNotEmpty()) runCatching { db.comparisonDao().upsert(rows) }
    }
}

/** Which comparison rows an event becomes. Pure, so it is tested (`ComparisonReadingsTest`). */
object ComparisonReadings {
    fun rowsFor(address: String, event: PulseEvent): List<ComparisonReadingEntity> {
        fun row(kind: String, value: Double, ts: Long, source: String) =
            ComparisonReadingEntity("$address:$kind:$ts", address, kind, value, ts, source)
        return when (event) {
            is PulseEvent.HeartRateSample -> if (event.bpm > 0 && !event.spot)
                listOf(row(MeasurementKind.HEART_RATE.name, event.bpm.toDouble(), event.timestamp.toEpochMilli(), "live")) else emptyList()
            is PulseEvent.HistoryMeasurement -> if (event.value > 0)
                listOf(row(event.kind.name, event.value, event.timestamp.toEpochMilli(), "history")) else emptyList()
            is PulseEvent.Spo2Result -> if (event.value in 70..100)
                listOf(row(MeasurementKind.SPO2.name, event.value.toDouble(), event.timestamp.toEpochMilli(), "live")) else emptyList()
            is PulseEvent.HrvSample -> if (event.value > 0)
                listOf(row(MeasurementKind.HRV.name, event.value.toDouble(), event.timestamp.toEpochMilli(), "live")) else emptyList()
            is PulseEvent.ActivityBucket -> if (event.steps > 0)
                listOf(row(STEPS, event.steps.toDouble(), event.timestamp.toEpochMilli(), "history")) else emptyList()
            is PulseEvent.ActivityUpdate -> {
                // The ring's running total for its day, kept once per local day (the latest wins).
                val day = com.pulseloop.util.TimeUtil.startOfDayLocal(event.timestamp.toEpochMilli())
                listOf(row(STEPS_TOTAL, event.steps.toDouble(), day, "live"))
            }
            is PulseEvent.SleepTimeline -> {
                val start = event.timestamp.toEpochMilli()
                event.stages.mapIndexed { i, stage -> row(SLEEP, stage.ordinal.toDouble(), start + i * 60_000L, "history") }
            }
            else -> emptyList()
        }
    }

    /** One activity bucket's steps. */
    const val STEPS = "STEPS"
    /** The ring's own running step total for a local day, stamped at the day's start. */
    const val STEPS_TOTAL = "STEPS_TOTAL"
    /** One minute of sleep; the value is the `SleepStage` ordinal. */
    const val SLEEP = "SLEEP"
}
