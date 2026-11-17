package com.pulseloop.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RingComparisonTest {
    private val min = 60_000L
    private fun r(m: Double, v: Double) = RingComparison.Reading((m * min).toLong(), v)

    @Test
    fun `coverage counts slots with a reading, not readings`() {
        // A stream every second for 10 minutes and nothing else, over an hour of 5-minute slots.
        val stream = (0 until 600).map { RingComparison.Reading(it * 1000L, 80.0) }
        val c = RingComparison.coverage(stream, 0, 60 * min, 5 * min)
        assertEquals(2, c.slotsWithReadings)
        assertEquals(12, c.slots)
    }

    @Test
    fun `agreement pairs nearest readings within the tolerance only`() {
        val first = listOf(r(0.0, 70.0), r(10.0, 80.0), r(20.0, 90.0))
        val second = listOf(r(1.0, 74.0), r(10.5, 78.0), r(40.0, 100.0))
        val a = RingComparison.agreement(first, second, 2 * min)
        assertEquals(2, a.pairs)
        assertEquals(3.0, a.medianAbsDiff!!, 1e-9)   // |4| and |-2| → 3
        assertEquals(1.0, a.meanDiff!!, 1e-9)        // (4 - 2) / 2
    }

    @Test
    fun `no overlap means no agreement figure`() {
        val a = RingComparison.agreement(listOf(r(0.0, 70.0)), listOf(r(30.0, 70.0)), 2 * min)
        assertEquals(0, a.pairs)
        assertNull(a.medianAbsDiff)
    }

    @Test
    fun `day average is weighted by time`() {
        // Ten minutes streamed at 120, and a log of 60 every five minutes for the rest of the hour.
        val stream = (0 until 600).map { RingComparison.Reading(it * 1000L, 120.0) }
        val log = (2..11).map { r(it * 5.0, 60.0) }
        val avg = RingComparison.averageHeartRate(stream + log, 0, 60 * min)!!
        // Per reading it would be ~119; by time the log's 50 minutes count (2 min each, capped).
        assertEquals(true, avg < 100)
    }
}
