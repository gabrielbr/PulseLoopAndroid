package com.pulseloop.service

import com.pulseloop.ring.MeasurementKind
import com.pulseloop.ring.PulseEvent
import com.pulseloop.ring.SleepStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ComparisonReadingsTest {
    private val addr = "AA:BB"

    @Test
    fun `a re-synced history reading keeps its id`() {
        val e = PulseEvent.HistoryMeasurement(MeasurementKind.HEART_RATE, 72.0, Instant.ofEpochMilli(1_000))
        assertEquals(ComparisonReadings.rowsFor(addr, e).single().id, ComparisonReadings.rowsFor(addr, e).single().id)
    }

    @Test
    fun `a sleep record becomes one row per minute`() {
        val e = PulseEvent.SleepTimeline(Instant.ofEpochMilli(0), listOf(SleepStage.LIGHT, SleepStage.DEEP, SleepStage.AWAKE))
        val rows = ComparisonReadings.rowsFor(addr, e)
        assertEquals(listOf(0L, 60_000L, 120_000L), rows.map { it.timestamp })
        assertTrue(rows.all { it.kind == ComparisonReadings.SLEEP })
    }

    @Test
    fun `our own settled spot value and empty readings are not kept`() {
        assertTrue(ComparisonReadings.rowsFor(addr, PulseEvent.HeartRateSample(80, Instant.now(), spot = true)).isEmpty())
        assertTrue(ComparisonReadings.rowsFor(addr, PulseEvent.HeartRateSample(0, Instant.now())).isEmpty())
        assertTrue(ComparisonReadings.rowsFor(addr, PulseEvent.Spo2Result(0, Instant.now())).isEmpty())
    }

    @Test
    fun `connection and device events are not readings`() {
        assertTrue(ComparisonReadings.rowsFor(addr, PulseEvent.BatteryLevel(50)).isEmpty())
    }
}
