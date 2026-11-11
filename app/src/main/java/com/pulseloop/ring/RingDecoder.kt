package com.pulseloop.ring

import java.time.Instant

/**
 * Decodes 20-byte raw BLE notification data into typed [RingDecodedEvent]s.
 *
 * All byte offsets and decoding logic match the official app's SXR KeepFit SDK
 * as documented in Gadgetbridge's [KeepFitDeviceSupport.java].
 * Multi-byte integers are little-endian.
 *
 * [clock] is the offset the ring's RTC was set to. Ring-stamped history timestamps are local
 * wall-clock epochs and must have it subtracted off; `null` (the debug/legacy-test path)
 * subtracts 0. One instance is shared per connection with [JringSyncEngine] — see [JringClock].
 */
class RingDecoder(private val clock: JringClock? = null) {

    /** Convert a ring-stamped epoch (local wall-clock seconds) into a true [Instant]. */
    private fun ringDate(rawEpochSeconds: Long): Instant =
        clock?.date(rawEpochSeconds) ?: Instant.ofEpochSecond(rawEpochSeconds)

    fun decode(data: ByteArray): List<RingDecodedEvent> {
        return try {
            decodeUnsafe(data)
        } catch (e: Exception) {
            listOf(RingDecodedEvent.Unknown(
                commandId = if (data.isNotEmpty()) data[0].toUByte() else 0u,
                raw = data
            ))
        }
    }

    private fun decodeUnsafe(data: ByteArray): List<RingDecodedEvent> {
        val packet = RingPacket.fromData(data).getOrElse {
            return listOf(RingDecodedEvent.Unknown(
                commandId = if (data.isNotEmpty()) data[0].toUByte() else 0u,
                raw = data
            ))
        }

        val bytes = packet.raw
        val now = Instant.now()

        return when (packet.commandId.toInt()) {
            0x01 -> listOf(decodeTimeSync(bytes))
            0x02 -> listOf(RingDecodedEvent.CommandAck(commandId = packet.commandId))
            0x03 -> listOf(decodeActivity(bytes))
            0x0B -> listOf(decodeBattery(bytes))
            0x0C -> listOf(decodeStatus(bytes))
            0x10 -> decodeActivityHistory(bytes)  // 15× 1-min buckets
            0x11 -> listOf(decodeSleepTimeline(bytes))
            0x14 -> listOf(decodeLiveHeartRate(bytes, now))
            0x16 -> decodeHeartRateHistory(bytes)  // multi-packet protocol
            0x20 -> decodeBandFunction(bytes)
            0x24 -> decodeCombinedSensor(bytes, now)  // 5 metrics in one packet
            0x27 -> listOf(RingDecodedEvent.HeartRateComplete(_timestamp = now))
            0x28 -> listOf(RingDecodedEvent.Spo2Complete(_timestamp = now))
            0x3F -> listOf(decodeSpo2Result(bytes, now))
            0x4B -> listOf(decodeBindNotify(bytes))  // ring-side bind/unbind handshake
            0x55 -> decodeSensorHistory(bytes)  // offline BP/stress/sugar/HRV, 3× 15-min slots
            0x39 -> decodeTemperatureHistory(bytes)  // offline skin temperature, 3× 5-min slots
            0x40 -> decodeOxygenHistory(bytes)  // offline SpO₂, 15× 1-min readings
            0xB9 -> listOf(RingDecodedEvent.HistorySyncProgress(stage = "day_unavailable"))
            0x25 -> decodeRingWorkouts(bytes)  // sport-mode minutes, 6 per frame
            0xA5 -> listOf(RingDecodedEvent.HistorySyncProgress(stage = "ring_workouts_done"))
            0xF6 -> listOf(decodeFirmwareVersion(bytes))
            else -> listOf(RingDecodedEvent.Unknown(commandId = packet.commandId, raw = bytes))
        }
    }

    // ── 0x03: Current Activity ────────────────────────────────────────────

    private fun decodeActivity(bytes: ByteArray): RingDecodedEvent {
        if (bytes.size < 17) return unknown(0x03, bytes)
        return RingDecodedEvent.ActivityUpdate(
            _timestamp = ringDate(u32le(bytes, 1).toLong()),
            steps = u32le(bytes, 5).toInt(),
            distanceMeters = u32le(bytes, 9).toInt(),
            calories = u32le(bytes, 13).toInt(),
        )
    }

    // ── 0x0B: Battery ─────────────────────────────────────────────────────

    private fun decodeBattery(bytes: ByteArray): RingDecodedEvent {
        if (bytes.size < 3) return unknown(0x0B, bytes)
        return RingDecodedEvent.Battery(
            percent = bytes[1].toInt() and 0xFF,
            charging = (bytes[2].toInt() and 0xFF) == 1,
        )
    }

    // ── 0x0C: Device Info + Firmware ──────────────────────────────────────

    private fun decodeStatus(bytes: ByteArray): RingDecodedEvent {
        val address = if (bytes.size >= 9) {
            bytes.slice(3..8).joinToString(":") { String.format("%02X", it) }
        } else null
        // Full firmware string, exactly as the official app builds it in onGetDeviceInfo:
        //   CID(bytes[9-10]) + DID(bytes[11-12]) + "V" + version(bytes[1-2], LE u16)
        // For 0c 8a 00 .. 3a 00 2a 00 → "003A" + "002A" + "V" + 138 = "003A002AV138".
        // The version is bytes[1-2], NOT the 0xF6 packet (which carries a different value).
        val fw = if (bytes.size >= 13) {
            val version = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
            val cid = ((bytes[10].toInt() and 0xFF) shl 8) or (bytes[9].toInt() and 0xFF)
            val did = ((bytes[12].toInt() and 0xFF) shl 8) or (bytes[11].toInt() and 0xFF)
            String.format("%04X%04XV%d", cid, did, version)
        } else null
        return RingDecodedEvent.Status(address = address, firmware = fw)
    }

    // ── 0x10: Activity History (15× 1-min buckets per packet) ─────────────

    /**
     * Each 0x10 packet carries 15 consecutive 1-minute step samples.
     * The ring sends multiple packets covering the requested day range.
     * End condition: timestamp hits 23:45 local time.
     */
    private fun decodeActivityHistory(bytes: ByteArray): List<RingDecodedEvent> {
        if (bytes.size < 20) return listOf(unknown(0x10, bytes))
        val baseTimestamp = Instant.ofEpochSecond(u32le(bytes, 1).toLong())
        return (5..19).map { i ->
            RingDecodedEvent.ActivityBucket(
                _timestamp = baseTimestamp.plusSeconds((i - 5) * 60L),
                steps = bytes[i].toInt() and 0xFF,
                distanceMeters = 0,  // 0x10 doesn't carry distance
            )
        }
    }

    // ── 0x11: Sleep Timeline ──────────────────────────────────────────────

    private fun decodeSleepTimeline(bytes: ByteArray): RingDecodedEvent {
        if (bytes.size < 20) return unknown(0x11, bytes)
        return RingDecodedEvent.SleepTimeline(
            _timestamp = ringDate(u32le(bytes, 1).toLong()),
            stages = bytes.slice(5..19).map { SleepStage.fromByte(it.toUByte()) }
        )
    }

    // ── 0x14: Live Heart Rate ─────────────────────────────────────────────

    /**
     * Live HR streaming notification (~1/sec during active measurement).
     * timestamp==0 means measurement error — discard the reading.
     */
    private fun decodeLiveHeartRate(bytes: ByteArray, fallbackNow: Instant): RingDecodedEvent {
        if (bytes.size < 7) return unknown(0x14, bytes)
        val tsRaw = u32le(bytes, 1).toLong()
        // timestamp == 0 is a measurement error from the ring
        if (tsRaw == 0L) return RingDecodedEvent.HeartRateSample(
            bpm = 0, _timestamp = fallbackNow, isError = true
        )
        return RingDecodedEvent.HeartRateSample(
            bpm = bytes[5].toInt() and 0xFF,
            _timestamp = Instant.ofEpochSecond(tsRaw),
            sleepStatus = bytes[6].toInt() and 0xFF,
            isError = false,
        )
    }

    // ── 0x16: Heart Rate History (multi-packet protocol) ──────────────────

    /**
     * 0x16 multi-packet protocol:
     * - byte[1] = 0xF0: header → total packet count at bytes[6-7]
     * - byte[1] = 0xAA: index block → current index + last block count
     * - byte[1] = 0xA0: data block → 2 averaged HR values (6 samples each)
     * - byte[1] = 0xFF: sync finished
     */
    private fun decodeHeartRateHistory(bytes: ByteArray): List<RingDecodedEvent> {
        if (bytes.size < 2) return listOf(unknown(0x16, bytes))
        val subType = bytes[1].toInt() and 0xFF
        return when (subType) {
            0xF0 -> listOf(RingDecodedEvent.HistorySyncProgress(stage = "hr_header"))
            0xAA -> listOf(RingDecodedEvent.HistorySyncProgress(stage = "hr_index"))
            0xFF -> listOf(RingDecodedEvent.HistorySyncFinished)
            0xA0 -> decodeHrDataBlock(bytes)
            else -> {
                // Legacy fallback: try to extract a single HR value
                if (bytes.size >= 9) {
                    val ts = ringDate(u32le(bytes, 2).toLong())
                    val v = bytes.drop(8).firstOrNull { it != 0.toByte() }
                    if (v != null) listOf(RingDecodedEvent.HistoryMeasurement(
                        kind_field = MeasurementKind.HEART_RATE,
                        value = (v.toInt() and 0xFF).toDouble(),
                        _timestamp = ts,
                    )) else listOf(RingDecodedEvent.CommandAck(commandId = 0x16u))
                } else listOf(unknown(0x16, bytes))
            }
        }
    }

    /**
     * 0xA0 data block: 12 HR samples (bytes[8-19]) → 2 averaged readings.
     * Each reading = average of 6 consecutive 1-min samples, rounded.
     */
    private fun decodeHrDataBlock(bytes: ByteArray): List<RingDecodedEvent> {
        if (bytes.size < 20) return listOf(unknown(0x16, bytes))
        val baseTimestamp = ringDate(u32le(bytes, 2).toLong())

        return buildList {
            // First 6 samples → 1 averaged HR at baseTimestamp
            val avg1 = (8..13).map { bytes[it].toInt() and 0xFF }.average().let {
                Math.round(it).toInt()
            }
            if (avg1 > 0) add(RingDecodedEvent.HistoryMeasurement(
                kind_field = MeasurementKind.HEART_RATE,
                value = avg1.toDouble(),
                _timestamp = baseTimestamp,
            ))

            // Next 6 samples → 1 averaged HR at baseTimestamp + 1 min
            val avg2 = (14..19).map { bytes[it].toInt() and 0xFF }.average().let {
                Math.round(it).toInt()
            }
            if (avg2 > 0) add(RingDecodedEvent.HistoryMeasurement(
                kind_field = MeasurementKind.HEART_RATE,
                value = avg2.toDouble(),
                _timestamp = baseTimestamp.plusSeconds(60),
            ))
        }
    }

    // ── 0x24: Combined Sensor Data ────────────────────────────────────────

    /**
     * Response to CMD_TOGGLE_BLOOD_PRESSURE (0x23) — the combined spot measurement.
     * Matches the official app's onReceiveSensorData(i, i2, i3, i4, i5, i6, i7, i8):
     *
     * Offset:  0    1    2         3          4       5        6       7            8
     * Value:  0x24 HR   systolic  diastolic  SpO2%   fatigue  stress  bloodSugar   HRV
     *
     * Blood sugar arrives as mmol/L × 10 (byte[7]); the official UI shows mg/dL via
     * `mmol × 18.016`. e.g. 51 → 5.1 mmol/L → 91.88 mg/dL. Only emit values > 0.
     */
    private fun decodeCombinedSensor(bytes: ByteArray, now: Instant): List<RingDecodedEvent> {
        if (bytes.size < 6) return listOf(unknown(0x24, bytes))

        return buildList {
            val hr = bytes[1].toInt() and 0xFF
            val systolic = bytes[2].toInt() and 0xFF
            val diastolic = bytes[3].toInt() and 0xFF
            val spo2 = bytes[4].toInt() and 0xFF
            val fatigue = bytes[5].toInt() and 0xFF
            val stress = if (bytes.size > 6) bytes[6].toInt() and 0xFF else 0
            val bloodSugarRaw = if (bytes.size > 7) bytes[7].toInt() and 0xFF else 0

            if (hr > 0) add(RingDecodedEvent.HeartRateSample(bpm = hr, _timestamp = now))
            if (systolic > 0 || diastolic > 0) add(RingDecodedEvent.HistoryMeasurement(
                kind_field = MeasurementKind.BLOOD_PRESSURE_SYSTOLIC,
                value = systolic.toDouble(),
                _timestamp = now,
            ))
            if (diastolic > 0) add(RingDecodedEvent.HistoryMeasurement(
                kind_field = MeasurementKind.BLOOD_PRESSURE_DIASTOLIC,
                value = diastolic.toDouble(),
                _timestamp = now,
            ))
            if (spo2 in 80..100) add(RingDecodedEvent.Spo2Result(value = spo2, _timestamp = now))
            // Fatigue (byte[5], i5) and stress (byte[6], i6) are distinct metrics in the
            // official app (TYPE_FATIGUE=13, TYPE_STRESS=17), both 0–100.
            if (fatigue > 0) add(RingDecodedEvent.HistoryMeasurement(
                kind_field = MeasurementKind.FATIGUE,
                value = fatigue.toDouble(),
                _timestamp = now,
            ))
            if (stress > 0) add(RingDecodedEvent.StressSample(value = stress, _timestamp = now))
            // Blood sugar: byte[7] = mmol/L × 10 → store mg/dL = (raw / 10) × 18.016
            if (bloodSugarRaw > 0) add(RingDecodedEvent.HistoryMeasurement(
                kind_field = MeasurementKind.BLOOD_SUGAR,
                value = (bloodSugarRaw / 10.0) * 18.016,
                _timestamp = now,
            ))
        }
    }

    // ── 0x55: Offline sensor history ──────────────────────────────────────

    /**
     * The ring's own every-15-minutes log of its estimates, from the vendor SDK's 0x55 branch
     * (`BluetoothLeService`, `str3 == "55"` → `onGetAdvSensorOfflineData`):
     *
     *   [0] 0x55, [1..4] slot time (u32 LE, the ring's local-wall-clock epoch, see [JringClock]),
     *   then three slots of five bytes at 5, 10, 15:
     *   systolic, diastolic, stress, blood sugar (mmol/L × 10), HRV.
     *   Slot n is 15 minutes after slot 0.
     *
     * The vendor app stores BP only when both halves are non-zero and every other value only when
     * non-zero (`DupMainActivity.onGetAdvSensorOfflineData`), with no other change; so do we.
     * These are the same figures the live 0x24 measurement returns, so they are estimates, and
     * the UI labels them as such.
     */
    private fun decodeSensorHistory(bytes: ByteArray): List<RingDecodedEvent> {
        if (bytes.size < 20) return listOf(unknown(0x55, bytes))
        val base = ringDate(u32le(bytes, 1).toLong())
        return buildList {
            for (slot in 0 until 3) {
                val o = 5 + slot * 5
                val at = base.plusSeconds(slot * 15L * 60L)
                val sys = bytes[o].toInt() and 0xFF
                val dia = bytes[o + 1].toInt() and 0xFF
                val stress = bytes[o + 2].toInt() and 0xFF
                val sugar = bytes[o + 3].toInt() and 0xFF
                val hrv = bytes[o + 4].toInt() and 0xFF
                if (sys > 0 && dia > 0) {
                    add(RingDecodedEvent.HistoryMeasurement(MeasurementKind.BLOOD_PRESSURE_SYSTOLIC, sys.toDouble(), at))
                    add(RingDecodedEvent.HistoryMeasurement(MeasurementKind.BLOOD_PRESSURE_DIASTOLIC, dia.toDouble(), at))
                }
                if (stress > 0) add(RingDecodedEvent.HistoryMeasurement(MeasurementKind.STRESS, stress.toDouble(), at))
                if (sugar > 0) add(RingDecodedEvent.HistoryMeasurement(MeasurementKind.BLOOD_SUGAR, (sugar / 10.0) * 18.016, at))
                if (hrv > 0) add(RingDecodedEvent.HistoryMeasurement(MeasurementKind.HRV, hrv.toDouble(), at))
            }
            // A frame of empty slots still belongs to the stream: say so, so it isn't logged as
            // `unknown` and exported in clear (AGENTS.md, diagnostics masking).
            if (isEmpty()) add(RingDecodedEvent.HistorySyncProgress(stage = "sensor_history"))
        }
    }

    // ── 0x25: Workouts recorded on the ring ───────────────────────────────

    /**
     * The vendor SDK's "25" branch → `onGetMultipleSportData(1, time, type, value)`, rebuilt from
     * a decompile jadx marks as "decompiled incorrectly", so the layout is read from the arithmetic:
     *
     *   [1..4] the first minute (u32 LE, ring-local epoch); six one-minute slots of two bytes at
     *   5, 7, 9, 11, 13, 15. For slot i with bytes (lo, hi):
     *   - sport type = (lo & 0x0F) | high nibble, the high nibbles packed in bytes 17–19:
     *     slot 0 `b17 & 0xF0`, slot 1 `(b17 & 0x0F) << 4`, slot 2 `b18 & 0xF0`,
     *     slot 3 `(b18 & 0x0F) << 4`, slot 4 `b19 & 0xF0`, slot 5 `(b19 & 0x0F) << 4`;
     *   - value = (hi << 4) + (lo >> 4), 12 bits.
     *
     * Type 0 is a minute outside any workout (the vendor's +100 maps it to 100, which it skips).
     * `A5 FF` ends the day.
     */
    private fun decodeRingWorkouts(bytes: ByteArray): List<RingDecodedEvent> {
        if (bytes.size < 20) return listOf(unknown(0x25, bytes))
        val base = ringDate(u32le(bytes, 1).toLong())
        val packed = intArrayOf(bytes[17].toInt() and 0xFF, bytes[18].toInt() and 0xFF, bytes[19].toInt() and 0xFF)
        return buildList {
            for (i in 0 until 6) {
                val lo = bytes[5 + i * 2].toInt() and 0xFF
                val hi = bytes[6 + i * 2].toInt() and 0xFF
                val p = packed[i / 2]
                val high = if (i % 2 == 0) p and 0xF0 else (p and 0x0F) shl 4
                val type = (lo and 0x0F) or high
                val value = (hi shl 4) + (lo shr 4)
                if (type in 1..139) add(RingDecodedEvent.RingWorkoutMinute(type, value, base.plusSeconds(i * 60L)))
            }
            if (isEmpty()) add(RingDecodedEvent.HistorySyncProgress(stage = "ring_workouts"))
        }
    }

    // ── 0x39: Offline temperature ─────────────────────────────────────────

    /**
     * The vendor SDK's "39" branch → `onGetDataByDay(12, t, surface, body)`:
     * `[1..4]` slot time (ring-local epoch), then three 5-minute slots of four bytes at 5, 9, 13:
     * surface temperature (u16 LE, °C × 100) and body temperature (u16 LE, °C × 100).
     *
     * Only the surface value is stored, as [MeasurementKind.TEMPERATURE]: that is what the card
     * shows for every other ring (skin temperature). The "body" figure is the ring's own model of
     * core temperature, a second estimate on top of the first, and is left out. Out of 25–45 °C
     * (0 included) means no reading.
     */
    private fun decodeTemperatureHistory(bytes: ByteArray): List<RingDecodedEvent> {
        if (bytes.size < 20) return listOf(unknown(0x39, bytes))
        val base = ringDate(u32le(bytes, 1).toLong())
        return buildList {
            for (slot in 0 until 3) {
                val o = 5 + slot * 4
                val surface = ((bytes[o].toInt() and 0xFF) or ((bytes[o + 1].toInt() and 0xFF) shl 8)) / 100.0
                if (surface in 25.0..45.0) add(RingDecodedEvent.HistoryMeasurement(
                    MeasurementKind.TEMPERATURE, surface, base.plusSeconds(slot * 300L),
                ))
            }
            if (isEmpty()) add(RingDecodedEvent.HistorySyncProgress(stage = "temperature_history"))
        }
    }

    // ── 0x40: Offline SpO₂ ────────────────────────────────────────────────

    /**
     * The vendor SDK's "40" branch → `onGetOxygenOfflineData(t, value)`: `[1..4]` slot time
     * (ring-local epoch), then fifteen one-minute readings at bytes 5–19, one byte each, percent.
     * The vendor keeps 1–100 (`FragmentMain`, type 13); we keep 70–100, the band used for every
     * other ring's SpO₂, so a stray low byte can't read as a desaturation.
     */
    private fun decodeOxygenHistory(bytes: ByteArray): List<RingDecodedEvent> {
        if (bytes.size < 20) return listOf(unknown(0x40, bytes))
        val base = ringDate(u32le(bytes, 1).toLong())
        return buildList {
            for (i in 0 until 15) {
                val v = bytes[5 + i].toInt() and 0xFF
                if (v in 70..100) add(RingDecodedEvent.HistoryMeasurement(
                    MeasurementKind.SPO2, v.toDouble(), base.plusSeconds(i * 60L),
                ))
            }
            if (isEmpty()) add(RingDecodedEvent.HistorySyncProgress(stage = "oxygen_history"))
        }
    }

    // ── 0x3F: SpO₂ Result ─────────────────────────────────────────────────

    private fun decodeSpo2Result(bytes: ByteArray, now: Instant): RingDecodedEvent {
        if (bytes.size < 2) return unknown(0x3F, bytes)
        val spo2 = bytes[1].toInt() and 0xFF
        return if (spo2 in 80..100) {
            RingDecodedEvent.Spo2Result(value = spo2, _timestamp = now)
        } else {
            RingDecodedEvent.Spo2Progress(percent = null, _timestamp = now)
        }
    }

    // ── 0x4B: Bind / Unbind handshake ─────────────────────────────────────

    /**
     * Ring-side bind notification. Mirrors the official SDK's onNotifyBindedInfo:
     * byte[1] = action, byte[2] = state. The ring sends this on connect (to drive
     * binding) and to acknowledge an app-initiated unbind on forget.
     */
    private fun decodeBindNotify(bytes: ByteArray): RingDecodedEvent {
        if (bytes.size < 2) return unknown(0x4B, bytes)
        val action = bytes[1].toInt() and 0xFF
        val state = if (bytes.size > 2) bytes[2].toInt() and 0xFF else 0
        return RingDecodedEvent.BindNotify(action = action, state = state)
    }

    // ── 0xF6: Firmware Version Number ─────────────────────────────────────

    private fun decodeFirmwareVersion(bytes: ByteArray): RingDecodedEvent {
        val version = if (bytes.size >= 6) {
            ((bytes[5].toInt() and 0xFF) shl 8) or (bytes[4].toInt() and 0xFF)
        } else null
        return RingDecodedEvent.FirmwareVersion(version = version)
    }

    // ── 0x01: Time Sync Ack ───────────────────────────────────────────────

    private fun decodeTimeSync(bytes: ByteArray): RingDecodedEvent {
        if (bytes.size >= 6) {
            return RingDecodedEvent.TimeSyncAck(
                _timestamp = ringDate(u32le(bytes, 1).toLong())
            )
        }
        return unknown(0x01, bytes)
    }

    // ── 0x20: Capability Bitmask ─────────────────────────────────────────

    private fun decodeBandFunction(bytes: ByteArray): List<RingDecodedEvent> {
        if (bytes.size < 2) return listOf(unknown(0x20, bytes))
        val caps = JringBandCapabilities(bytes.copyOfRange(1, bytes.size))
        // A unit that sets bit 10 has a temperature log the app can read (0x39), so it gets the
        // card; the family baseline leaves TEMPERATURE out because most of these rings don't.
        val reported = buildSet { if (caps.hasTemperature) add(WearableCapability.TEMPERATURE) }
        return listOfNotNull(
            RingDecodedEvent.BandFunction(caps),
            reported.takeIf { it.isNotEmpty() }?.let { RingDecodedEvent.SupportFunctions(it) },
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun u32le(bytes: ByteArray, offset: Int): UInt {
        if (bytes.size < offset + 4) return 0u
        return (bytes[offset].toUByte().toUInt() or
                (bytes[offset + 1].toUByte().toUInt() shl 8) or
                (bytes[offset + 2].toUByte().toUInt() shl 16) or
                (bytes[offset + 3].toUByte().toUInt() shl 24))
    }

    private fun unknown(cmd: Int, bytes: ByteArray) =
        RingDecodedEvent.Unknown(commandId = cmd.toUByte(), raw = bytes)
}
