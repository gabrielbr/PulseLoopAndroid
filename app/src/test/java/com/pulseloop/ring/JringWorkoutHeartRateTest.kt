package com.pulseloop.ring

import org.junit.Assert.assertEquals
import org.junit.Test

/** Workout heart rate on jring follows the vendor's run screen (`RunRecordActivity`). */
class JringWorkoutHeartRateTest {
    private class FakeWriter : RingCommandWriter {
        val sent = mutableListOf<String>()
        override fun enqueue(command: ByteArray) { sent += command.joinToString("") { "%02x".format(it) } }
    }

    /** The owner's ring's `0x20` reply (2026-11-11 export): sets bit 56, sport from app. */
    private val ownerRing = RingDecodedEvent.BandFunction(
        JringBandCapabilities(hex("62ec0738090abebb02001b0000000000000000")),
    )
    private val noSportBit = RingDecodedEvent.BandFunction(JringBandCapabilities(ByteArray(19)))

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun cmd(op: String, mode: Int) = op + "00000000" + "%02x".format(mode) + "00".repeat(14)

    @Test
    fun `owner ring sets the sport bit`() {
        assertEquals(true, ownerRing.capabilities.supportsSportFromApp)
    }

    @Test
    fun `a workout runs continuously in its sport mode and stops in the same mode`() {
        val w = FakeWriter()
        val e = JringSyncEngine(w)
        e.handle(ownerRing)
        w.sent.clear()
        e.startWorkoutHeartRate("gym")
        assertEquals(listOf(cmd("14", 1)), w.sent)
        w.sent.clear()
        e.stopWorkoutHeartRate()
        assertEquals(cmd("15", 1), w.sent.first())
        assertEquals("19", w.sent[1].take(2))
    }

    @Test
    fun `sport modes follow the vendor mapping`() {
        assertEquals(2, RingEncoder.jringSportMode("run"))
        assertEquals(3, RingEncoder.jringSportMode("cycle"))
        assertEquals(4, RingEncoder.jringSportMode("hike"))
        assertEquals(1, RingEncoder.jringSportMode("walk"))
        assertEquals(1, RingEncoder.jringSportMode("other"))
    }

    @Test
    fun `without the sport bit the old start is kept`() {
        val w = FakeWriter()
        val e = JringSyncEngine(w)
        e.handle(noSportBit)
        w.sent.clear()
        e.startWorkoutHeartRate("run")
        assertEquals(listOf("14b4" + "00".repeat(18)), w.sent)
    }

    @Test
    fun `a feature reply after a reconnect moves the workout onto the continuous stream`() {
        val w = FakeWriter()
        val e = JringSyncEngine(w)
        e.startWorkoutHeartRate("run")
        assertEquals("14b4", w.sent.single().take(4))
        e.handle(ownerRing)
        assertEquals(cmd("14", 2), w.sent.last())
        e.handle(ownerRing)
        assertEquals(2, w.sent.size)
    }
}
