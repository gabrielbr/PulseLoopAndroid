package com.pulseloop.ring

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The jring history pager: one day at a time, and the next request is held until the current
 * day's stream goes quiet.
 *
 * **Why this exists (issue #73, second half).** Asking for the right day was necessary but not
 * sufficient. With the window enqueued in one pass, an SR08 capture showed `10 00`, `10 01`,
 * `10 02` and `16 00` all going out inside **176 ms**, with `10 02` landing *between* two of
 * today's sleep packets — and only four `0x11` packets arriving in total, three of them today's.
 * The night displayed as 45 minutes against an expected 6h30. The ring answers one history
 * request at a time; a second request mid-stream truncates the first. `JringSyncEngine` used to
 * record that the vendor's reply-driven chain would be the fix "if a ring is ever seen truncating
 * a day's stream when the next request lands" — this is that ring.
 *
 * **Why time-settled rather than reply-driven for activity/sleep.** The vendor chains on a
 * per-day sync-end callback (`DupMainActivity.onGetMultipleSportData`), but nothing in the
 * `0x10`/`0x11` wire format marks the end of a day: `0x10` is a bare run of 15× 1-minute buckets
 * and `0x11` a bare run of 15× 1-minute sleep stages (see `RingDecoder.decodeActivityHistory` /
 * `decodeSleepTimeline`). So this leg advances when the stream settles, which is what JYouPro
 * itself does — it resets a 2000 ms idle timer on each `0x10`/`0x11` reply, the same constant
 * [settleMs] defaults to. [LuckRingHistorySync] is the same shape for the same reason and this
 * is a port of it, extended with a second leg per day.
 *
 * **Heart rate does have a real end marker**, so its leg doesn't guess: `0x16` subtype `0xFF`
 * decodes to [RingDecodedEvent.HistorySyncFinished] and advances the day immediately. The
 * settle/stall timers are only its fallback.
 *
 * Per day: `0x10/offset` → the day's activity+sleep stream → settles → `0x16/offset` → the day's
 * HR stream → `0xFF` (or settles) → each offline log the ring advertises, in the vendor's order:
 * temperature `0x39` ([temperatureHistory]), SpO₂ `0x40` ([oxygenHistory]), BP/stress/sugar/HRV
 * `0x55` ([sensorHistory]); each ends on its 23:45 slot or when it settles → next day. A day that answers nothing at all still gets its HR
 * request, because `16 00` was previously sent unconditionally and today's HR must not become
 * collateral of a silent `0x10`.
 *
 * Replays are safe, so a timer that fires early costs a re-request and nothing else: activity
 * buckets upsert by timestamp with the day total recomputed from distinct buckets, and sleep
 * reconciles one waking day at a time.
 */
class JringHistorySync(
    private val writer: RingCommandWriter?,
    /** Quiet period that ends a leg. JYouPro's own idle timer between `0x10`/`0x11` replies. */
    private val settleMs: Long = 2_000,
    /** Bound on a leg that never answers at all. Comfortably under the ring's ~20 s idle
     *  disconnect, so a stalled day can't cost the connection. */
    private val stallMs: Long = 6_000,
) {
    private val encoder = RingEncoder
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** The legs of one day, in the order the vendor asks for them (`FragmentMain`'s
     *  `ACTION_NOTIFY_BLE_SYNC_DATA_BY_DAY_END` chain: 1 → 2 → 12 → 13 → advanced sensor). */
    private enum class Leg { ACTIVITY, HEART_RATE, TEMPERATURE, OXYGEN, SENSOR }

    /** Offline skin temperature (0x39, `getDataByDay(12, day)`), gated on 0x20 bit 10. */
    @Volatile var temperatureHistory: Boolean = false

    /** Offline SpO₂ (0x40, `getDataByDay(13, day)`), gated on 0x20 bit 81. */
    @Volatile var oxygenHistory: Boolean = false

    /**
     * Whether to ask each day for the ring's offline sensor log (0x55: BP, stress, sugar, HRV).
     * Set from the 0x20 capability bit 83 ([JringBandCapabilities.hasPressureHistory]), which is
     * the vendor's own gate (`FragmentMain`, `FUNCTION_HAS_PRESSURE` → `getAdvSensorOfflineData`).
     * Read when a day's heart-rate leg ends, by which time the 0x20 reply has long arrived.
     */
    @Volatile var sensorHistory: Boolean = false

    private var queue = mutableListOf<Int>()
    private var currentDay: Int? = null
    private var leg: Leg = Leg.ACTIVITY
    private var settleJob: Job? = null
    private var stallJob: Job? = null
    /** Invalidates timers that fired for a leg which has since moved on — see [armTimer]. */
    private var epoch: Long = 0

    @get:Synchronized
    val isRunning: Boolean get() = currentDay != null

    /**
     * Seed the queue and request the first day. Returns whether this call began a pass.
     *
     * A pass already in flight wins, and the caller is told so: `runStartup` is also the
     * ~30-minute background sync (and `refresh()`/`querySleep()` route through it), so a second
     * pass landing mid-backfill is the normal case, not an error — and restarting would abandon
     * the in-flight day mid-stream, which is the very truncation this class exists to prevent.
     * The `false` is what keeps `JringSyncEngine`'s once-per-connection backfill gate from being
     * consumed by a pass that never ran.
     */
    @Synchronized
    fun start(dayOffsets: List<Int>): Boolean {
        if (isRunning || dayOffsets.isEmpty()) return false
        queue = dayOffsets.toMutableList()
        advance()
        return true
    }

    /** Abandon any in-flight pass (disconnect / teardown). A fresh driver — and so a fresh pager —
     *  is built per connection, so this is only reached mid-connection. */
    @Synchronized
    fun cancel() {
        cancelTimers()
        currentDay = null
        queue.clear()
    }

    /**
     * Called by the driver for every inbound history frame, so the pager can tell a live stream
     * from a finished one. Frames for a leg that isn't in flight are ignored.
     *
     * The packet-length guard mirrors [RingPacket.fromData]: the jring wire contract is one fixed
     * 20-byte packet per notify, and anything else is already rejected by the decoder. A fragment
     * must not re-arm the settle window, or a leg stays "live" long past its stream.
     */
    @Synchronized
    fun noteFrame(data: ByteArray) {
        if (!isRunning || data.size != RingPacket.PACKET_SIZE) return
        when (data[0].toInt() and 0xFF) {
            OPCODE_ACTIVITY, OPCODE_SLEEP -> if (leg == Leg.ACTIVITY) restartSettle()
            OPCODE_HEART_RATE -> {
                if (leg != Leg.HEART_RATE) return
                // The one genuine end-of-stream signal in this protocol — take it rather than
                // waiting out the settle window.
                if (data.size >= 2 && (data[1].toInt() and 0xFF) == HR_SYNC_FINISHED) nextLegAfter(Leg.HEART_RATE)
                else restartSettle()
            }
            OPCODE_TEMPERATURE -> if (leg == Leg.TEMPERATURE) restartSettle()
            // The vendor's "getdayabyday failed" reply: the ring has no temperature log for that
            // day (`BluetoothLeService` "b9" → `onGetDataByDayEnd(12, 0)`). Move on.
            OPCODE_DAY_FAILED -> if (leg == Leg.TEMPERATURE) nextLegAfter(Leg.TEMPERATURE)
            OPCODE_OXYGEN -> {
                if (leg != Leg.OXYGEN) return
                // 15 one-minute readings per frame; the frame holding 23:45 is the day's last.
                if (holdsLastSlot(data, slots = 15, stepMinutes = 1)) nextLegAfter(Leg.OXYGEN) else restartSettle()
            }
            OPCODE_SENSOR -> {
                if (leg != Leg.SENSOR) return
                // The vendor ends the day's log when a slot lands on 23:45 (the last of the day)
                // and otherwise on its 2000 ms idle timer; a past day ends here, today settles.
                if (holdsLastSlot(data, slots = 3, stepMinutes = 15)) advance() else restartSettle()
            }
        }
    }

    // MARK: - Driving the queue

    /** Move to the next day, or finish. Always entered with the lock held. */
    private fun advance() {
        cancelTimers()
        if (queue.isEmpty()) {
            currentDay = null
            return
        }
        val next = queue.removeAt(0)
        currentDay = next
        leg = Leg.ACTIVITY
        writer?.enqueue(encoder.makeHistoryQueryCommand(dayOffset = next))
        armStall()
    }

    /** End the activity/sleep leg and ask for the same day's heart rate, as the vendor does
     *  (`getDataByDay(2, day)`). Always entered with the lock held. */
    private fun beginHeartRateLeg() {
        val day = currentDay ?: return
        cancelTimers()
        leg = Leg.HEART_RATE
        writer?.enqueue(encoder.makeHistoryMeasurementQueryCommand(dayOffset = day))
        armStall()
    }

    /**
     * After [done], ask for the day's next offline log the ring keeps, in the vendor's order, or
     * move to the next day. Each is gated on the 0x20 bit the vendor gates it on.
     */
    private fun nextLegAfter(done: Leg) {
        val day = currentDay ?: return
        val optional = listOf(
            Leg.TEMPERATURE to temperatureHistory,
            Leg.OXYGEN to oxygenHistory,
            Leg.SENSOR to sensorHistory,
        )
        val next = optional.firstOrNull { (l, on) -> on && l.ordinal > done.ordinal }?.first
        if (next == null) { advance(); return }
        cancelTimers()
        leg = next
        writer?.enqueue(when (next) {
            Leg.TEMPERATURE -> encoder.makeTemperatureHistoryQueryCommand(dayOffset = day)
            Leg.OXYGEN -> encoder.makeOxygenHistoryQueryCommand(dayOffset = day)
            else -> encoder.makeSensorHistoryQueryCommand(dayOffset = day)
        })
        armStall()
    }

    /** A leg's stream went quiet: hand over to the next leg, or the next day. */
    private fun legSettled() {
        when (leg) {
            Leg.ACTIVITY -> beginHeartRateLeg()
            else -> nextLegAfter(leg)
        }
    }

    /** Whether a frame's slots include 23:45, read in the ring's own local clock. */
    private fun holdsLastSlot(data: ByteArray, slots: Int, stepMinutes: Int): Boolean {
        if (data.size < 5) return false
        val raw = (data[1].toLong() and 0xFF) or ((data[2].toLong() and 0xFF) shl 8) or
            ((data[3].toLong() and 0xFF) shl 16) or ((data[4].toLong() and 0xFF) shl 24)
        val minuteOfDay = Math.floorMod(raw, 86_400L) / 60
        return (0 until slots).any { minuteOfDay + it.toLong() * stepMinutes == LAST_SLOT_MINUTE.toLong() }
    }

    // MARK: - Timers

    /** Data arrived: the stall no longer applies, and the quiet period starts over. */
    private fun restartSettle() {
        cancelTimers()
        settleJob = armTimer(settleMs)
    }

    /** Nothing has answered this leg yet. Fires once, then moves on. */
    private fun armStall() {
        stallJob = armTimer(stallMs)
    }

    /**
     * A timer that can't act on a leg it no longer belongs to.
     *
     * `Job.cancel()` doesn't retract a coroutine that has already left its `delay` and is waiting
     * on this monitor, so the epoch — bumped by every [cancelTimers] — is what actually decides
     * whether a fired timer is still current. Without it a settle that lost the race to an
     * arriving frame would advance the day out from under a live stream: the truncation this
     * class exists to prevent, reintroduced from the inside.
     */
    private fun armTimer(delayMs: Long): Job {
        val firedFor = epoch
        return scope.launch {
            delay(delayMs)
            synchronized(this@JringHistorySync) {
                if (epoch == firedFor) legSettled()
            }
        }
    }

    private fun cancelTimers() {
        epoch++
        settleJob?.cancel(); settleJob = null
        stallJob?.cancel(); stallJob = null
    }

    companion object {
        private const val OPCODE_ACTIVITY = 0x10
        private const val OPCODE_SLEEP = 0x11
        private const val OPCODE_HEART_RATE = 0x16
        private const val OPCODE_SENSOR = 0x55
        private const val OPCODE_TEMPERATURE = 0x39
        private const val OPCODE_OXYGEN = 0x40
        private const val OPCODE_DAY_FAILED = 0xB9
        /** 23:45 — the vendor's end-of-day slot for the 0x55 log. */
        private const val LAST_SLOT_MINUTE = 23 * 60 + 45
        /** `0x16` subtype `0xFF` — "sync finished" (`RingDecoder.decodeHeartRateHistory`). */
        private const val HR_SYNC_FINISHED = 0xFF
    }
}
