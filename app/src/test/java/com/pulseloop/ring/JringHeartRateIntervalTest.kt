package com.pulseloop.ring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The jring's background heart-rate interval: the vendor's 15/30/45/60 choices, gated on bit 18. */
class JringHeartRateIntervalTest {
    private class Recorder : RingCommandWriter {
        val sent = mutableListOf<ByteArray>()
        override fun enqueue(command: ByteArray) { sent += command }
    }

    private fun settings(minutes: Int, enabled: Boolean = true) = MeasurementSettings(
        hrEnabled = enabled, hrIntervalMinutes = minutes,
        spo2Enabled = true, stressEnabled = true, hrvEnabled = true, temperatureEnabled = true,
    )

    private fun cadences(w: Recorder) = w.sent.filter { it[0] == 0x19.toByte() }.map { it[6].toInt() }

    @Test fun snapsToTheVendorChoices() {
        assertEquals(30, JringHeartRateInterval.snap(null))
        assertEquals(15, JringHeartRateInterval.snap(5))
        assertEquals(15, JringHeartRateInterval.snap(15))
        assertEquals(45, JringHeartRateInterval.snap(40))
        assertEquals(60, JringHeartRateInterval.snap(120))
    }

    @Test fun neverSavedSendsTheVendorDefault() {
        val w = Recorder()
        JringSyncEngine(w).runStartup()
        assertEquals(listOf(30), cadences(w))
    }

    @Test fun theSavedIntervalGoesOutOnConnectAndOnSave() {
        val w = Recorder()
        val engine = JringSyncEngine(w)
        engine.setMeasurementSettings(settings(15))
        engine.runStartup()
        engine.applyMeasurementSettings(settings(45))
        assertEquals(listOf(15, 45), cadences(w))
    }

    @Test fun aStreamEndingRestoresTheSavedInterval() {
        val w = Recorder()
        val engine = JringSyncEngine(w)
        engine.setMeasurementSettings(settings(15))
        engine.stopHeartRate()
        assertEquals(listOf(15), cadences(w))
    }

    @Test fun switchedOffClearsTheEnableByte() {
        val w = Recorder()
        val engine = JringSyncEngine(w)
        engine.applyMeasurementSettings(settings(30, enabled = false))
        assertEquals(0, w.sent.single { it[0] == 0x19.toByte() }[5].toInt())
    }

    @Test fun bit18GrantsTheSetting() {
        // The owner's ring's 0x20 reply (2026-11-18): payload byte 2 = 0x07, so bit 18 is set.
        val reply = "2062ec0738090abebb02001b0000000000000000".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val caps = JringBandCapabilities(reply.copyOfRange(1, reply.size))
        assertTrue(caps.hasAutoHeartRateInterval)
        assertFalse(JringBandCapabilities(ByteArray(19)).hasAutoHeartRateInterval)
    }
}
