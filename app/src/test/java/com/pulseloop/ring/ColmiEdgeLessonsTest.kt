package com.pulseloop.ring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Colmi sync behaviour learned from OpenStrap Edge's adapter (ideas reimplemented, not code):
 * refusals end a stage, future-dated buffer slots are dropped, a night whose header disagrees
 * with its blocks is laid back from its end, and an app-side hang-up after sync counts as idle.
 */
class ColmiEdgeLessonsTest {

    private class RecordingWriter : RingCommandWriter {
        val commands = mutableListOf<ByteArray>()
        override fun enqueue(command: ByteArray) { commands.add(command) }
    }

    private val utc = ZoneOffset.UTC

    @Test
    fun `a refused activity request moves straight on to heart rate`() {
        val writer = RecordingWriter()
        val driver = ColmiDriver(writer)
        val engine = driver.makeSyncEngine() as ColmiSyncEngine
        engine.runStartup()
        val before = writer.commands.size
        val refusal = ColmiPacket.frame(byteArrayOf((0x43 or 0x80).toByte()))
        val events = driver.ingest(refusal, ColmiUUIDs.NOTIFY_V1)
        assertTrue(events.single() is RingDecodedEvent.Unknown)
        assertEquals(before + 1, writer.commands.size)
        assertEquals(ColmiCommandID.SYNC_HEART_RATE.toByte(), writer.commands.last()[0])
        engine.destroy()
    }

    @Test
    fun `only history opcodes with the high bit read as refusals`() {
        assertEquals(ColmiCommandID.SYNC_HRV, ColmiSyncEngine.rejectedHistoryOpcode((0x39 or 0x80).toUByte()))
        assertEquals(ColmiCommandID.SYNC_HEART_RATE, ColmiSyncEngine.rejectedHistoryOpcode(0x95u))
        assertNull(ColmiSyncEngine.rejectedHistoryOpcode(0x15u))
        assertNull(ColmiSyncEngine.rejectedHistoryOpcode(ColmiCommandID.REALTIME_HEART_RATE_ERROR))
    }

    @Test
    fun `a refusal for another stage is ignored`() {
        val writer = RecordingWriter()
        val driver = ColmiDriver(writer)
        val engine = driver.makeSyncEngine() as ColmiSyncEngine
        engine.runStartup() // stage = ACTIVITY
        val before = writer.commands.size
        driver.ingest(ColmiPacket.frame(byteArrayOf((0x39 or 0x80).toByte())), ColmiUUIDs.NOTIFY_V1)
        assertEquals(before, writer.commands.size)
        engine.destroy()
    }

    @Test
    fun `heart-rate slots later than now and implausible bpm are dropped`() {
        val day = LocalDate.of(2026, 10, 21)
        val now = day.atTime(0, 20).toInstant(utc)
        val frame = ColmiPacket.frame(byteArrayOf(
            0x15, 0x01, 0, 0, 0, 0,
            70, 20, 70, 70, 70, 70, 70, 70, 70,
        ))
        val hr = ColmiDecoder.decodeHistory(frame, day = day, now = now, zone = utc, slotMinutes = 5)
            .filterIsInstance<RingDecodedEvent.HistoryMeasurement>()
        // 00:00 … 00:20 kept (the 20 bpm at 00:05 dropped); 00:25 onward is in the future.
        assertEquals(listOf(0L, 10L, 15L, 20L), hr.map { (it._timestamp.epochSecond - day.atStartOfDay(utc).toEpochSecond()) / 60 })
        assertTrue(hr.all { it.value == 70.0 })
    }

    @Test
    fun `a night whose header disagrees with its blocks is laid back from its end`() {
        val day = LocalDate.of(2026, 10, 21)
        val headerStart = day.minusDays(1).atTime(22, 0).atZone(utc)
        val headerEnd = day.atTime(6, 0).atZone(utc)
        assertEquals(day.atTime(5, 0).atZone(utc), ColmiDecoder.sleepAnchor(headerStart, headerEnd, 60))
        // Within the tolerance the header's start stands.
        assertEquals(headerStart, ColmiDecoder.sleepAnchor(headerStart, headerEnd, 470))
    }

    @Test
    fun `an app-side hang-up after a completed Colmi sync is an idle drop`() {
        assertTrue(IdleLinkPolicy.isIdleDrop(RingDeviceType.COLMI_R02, syncCompletedOnLink = true, status = 0, requestedByApp = true))
        assertFalse(IdleLinkPolicy.isIdleDrop(RingDeviceType.COLMI_R02, syncCompletedOnLink = true, status = 0))
        assertFalse(IdleLinkPolicy.isIdleDrop(RingDeviceType.COLMI_R02, syncCompletedOnLink = false, status = 0, requestedByApp = true))
        assertFalse(IdleLinkPolicy.isIdleDrop(RingDeviceType.JRING, syncCompletedOnLink = true, status = 0, requestedByApp = true))
    }

    @Test
    fun `isFutureSlot allows two minutes of clock skew`() {
        val now = Instant.parse("2026-10-21T10:00:00Z")
        assertFalse(ColmiDecoder.isFutureSlot(now.plusSeconds(90), now))
        assertTrue(ColmiDecoder.isFutureSlot(now.plusSeconds(180), now))
    }
}
