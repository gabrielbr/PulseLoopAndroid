package com.pulseloop.ring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.TimeZone

/**
 * 0x55, the ring's every-15-minutes log of its estimates, decoded the way the vendor SDK reads it
 * (`BluetoothLeService` "55" branch → `onGetAdvSensorOfflineData(ts, sys, dia, stress, sugar, hrv)`).
 */
class JringSensorHistoryTest {
    // UTC−3, the owner's zone: the ring's clock holds local wall time.
    private val clock = JringClock(TimeZone.getTimeZone("America/Sao_Paulo"), Instant.parse("2026-11-11T12:00:00Z"))
    private val decoder = RingDecoder(clock)

    private fun frame(localEpoch: Long, vararg slots: IntArray): ByteArray = ByteArray(20).also {
        it[0] = 0x55
        for (b in 0 until 4) it[1 + b] = ((localEpoch shr (8 * b)) and 0xFF).toByte()
        slots.forEachIndexed { i, s -> s.forEachIndexed { j, v -> it[5 + i * 5 + j] = v.toByte() } }
    }

    private fun history(events: List<RingDecodedEvent>) = events.filterIsInstance<RingDecodedEvent.HistoryMeasurement>()

    @Test fun threeSlotsFifteenMinutesApartInTrueTime() {
        val local0915 = 1_794_355_200L + (9 * 60 + 15) * 60L   // 2026-11-11 09:15 on the ring
        val out = history(decoder.decode(frame(local0915,
            intArrayOf(118, 76, 40, 55, 42),
            intArrayOf(120, 78, 35, 56, 0),
            intArrayOf(0, 0, 0, 0, 0),
        )))
        val first = Instant.parse("2026-11-11T12:15:00Z")   // 09:15 at UTC−3
        val sys = out.filter { it.kind_field == MeasurementKind.BLOOD_PRESSURE_SYSTOLIC }
        assertEquals(listOf(first, first.plusSeconds(900)), sys.map { it._timestamp })
        assertEquals(listOf(118.0, 120.0), sys.map { it.value })
        val sugar = out.first { it.kind_field == MeasurementKind.BLOOD_SUGAR }
        assertEquals(5.5 * 18.016, sugar.value, 1e-9)
        assertEquals(1, out.count { it.kind_field == MeasurementKind.HRV })
        assertEquals(2, out.count { it.kind_field == MeasurementKind.STRESS })
    }

    @Test fun bloodPressureNeedsBothHalves() {
        val out = history(decoder.decode(frame(1_794_355_200L, intArrayOf(118, 0, 0, 0, 0))))
        assertTrue(out.none { it.kind_field == MeasurementKind.BLOOD_PRESSURE_SYSTOLIC })
    }

    @Test fun anEmptyFrameIsNotUnknown() {
        val events = decoder.decode(frame(1_794_355_200L))
        assertTrue(events.none { it is RingDecodedEvent.Unknown })
    }

    @Test fun queryIsOpcodeAndDayOffset() {
        val cmd = RingEncoder.makeSensorHistoryQueryCommand(2)
        assertEquals(0x55, cmd[0].toInt() and 0xFF)
        assertEquals(2, cmd[1].toInt())
        assertEquals(20, cmd.size)
    }

    // ── 0x39 offline temperature, 0x40 offline SpO₂, and the owner's 0x20 reply ──

    @Test fun temperatureKeepsSkinValuesFiveMinutesApart() {
        val f = ByteArray(20).also {
            it[0] = 0x39
            val t = 1_794_355_200L + 9 * 3600L
            for (b in 0 until 4) it[1 + b] = ((t shr (8 * b)) and 0xFF).toByte()
            fun put(o: Int, v: Int) { it[o] = (v and 0xFF).toByte(); it[o + 1] = (v shr 8).toByte() }
            put(5, 3412); put(7, 3655)      // slot 0: 34.12 skin, 36.55 body
            put(9, 0); put(11, 0)           // slot 1: no reading
            put(13, 3398); put(15, 3650)    // slot 2
        }
        val temps = history(decoder.decode(f)).filter { it.kind_field == MeasurementKind.TEMPERATURE }
        assertEquals(listOf(34.12, 33.98), temps.map { it.value })
        assertEquals(600L, temps[1]._timestamp.epochSecond - temps[0]._timestamp.epochSecond)
    }

    @Test fun oxygenIsFifteenOneMinuteReadingsInBand() {
        val f = ByteArray(20).also {
            it[0] = 0x40
            val t = 1_794_355_200L + 2 * 3600L
            for (b in 0 until 4) it[1 + b] = ((t shr (8 * b)) and 0xFF).toByte()
            for (k in 0 until 15) it[5 + k] = 97.toByte()
            it[5 + 3] = 0; it[5 + 4] = 12   // empty and implausible
        }
        val spo2 = history(decoder.decode(f)).filter { it.kind_field == MeasurementKind.SPO2 }
        assertEquals(13, spo2.size)
        assertEquals(14 * 60L, spo2.last()._timestamp.epochSecond - spo2.first()._timestamp.epochSecond)
    }

    @Test fun ownersBandFunctionGrantsTemperature() {
        // The 0x20 reply from the owner's ring (firmware 003A002AV138), 2026-11-11.
        val raw = ByteArray(20)
        "2062ec0738090abebb02001b".chunked(2).forEachIndexed { i, h -> raw[i] = h.toInt(16).toByte() }
        val events = decoder.decode(raw)
        val caps = events.filterIsInstance<RingDecodedEvent.BandFunction>().single().capabilities
        assertTrue(caps.hasTemperature && caps.hasOxygenOfflineHistory && caps.hasPressureHistory)
        val granted = events.filterIsInstance<RingDecodedEvent.SupportFunctions>().single().capabilities
        assertEquals(setOf(WearableCapability.TEMPERATURE), granted)
        assertTrue(WearableCapability.TEMPERATURE in JringCoordinator.bitmapGatedCapabilities)
    }
}
