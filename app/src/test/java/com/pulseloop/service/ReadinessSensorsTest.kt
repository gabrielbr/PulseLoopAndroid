package com.pulseloop.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** The optional-sensor contributors: body temperature and recovery index. */
class ReadinessSensorsTest {

    private val zone: ZoneId = ZoneOffset.UTC
    private val today: LocalDate = LocalDate.of(2026, 10, 18)

    private fun night(day: LocalDate, temp: Double? = null, minHrFraction: Double? = null): NightSummary {
        val end = day.atTime(7, 30).atZone(zone).toInstant().toEpochMilli()
        val start = end - 8 * 3600_000L
        val minAt = minHrFraction?.let { start + ((end - start) * it).toLong() }
        return NightSummary(day, start, end, 450, 55.0, 40.0, 1.0, temperature = temp, minHrAt = minAt)
    }

    private fun history(temp: Double? = null) = (1..14).map { night(today.minusDays(it.toLong()), temp) }

    @Test
    fun `a usual skin temperature scores full marks and a degree up costs`() {
        assertEquals(100, Readiness.bodyTemperature(night(today, 34.1), history(34.0))!!.score)
        val warm = Readiness.bodyTemperature(night(today, 35.2), history(34.0))!!
        assertTrue("got ${warm.score}", warm.score < 50)
        assertTrue(warm.detail.startsWith("+1.2"))
        assertNull(Readiness.bodyTemperature(night(today, 34.0), history(null)))
    }

    @Test
    fun `an early heart rate low recovers best`() {
        assertEquals(100, Readiness.recoveryIndex(night(today, minHrFraction = 0.3))!!.score)
        assertTrue(Readiness.recoveryIndex(night(today, minHrFraction = 0.95))!!.score < 50)
        assertNull(Readiness.recoveryIndex(night(today)))
    }

    @Test
    fun `a ring without a temperature sensor is not told a contributor is missing`() {
        val r = Readiness.compute(Readiness.Inputs(night(today), history(), 450, List(28) { 8000 }, zone)) as Readiness.Result.Score
        assertTrue(r.missing.isEmpty())
        assertTrue(r.contributors.none { it.kind == Readiness.ContributorKind.BODY_TEMPERATURE })
    }

    @Test
    fun `a ring that has reported temperature before is told when tonight lacks it`() {
        val r = Readiness.compute(Readiness.Inputs(night(today), history(34.0), 450, List(28) { 8000 }, zone)) as Readiness.Result.Score
        assertTrue(Readiness.ContributorKind.BODY_TEMPERATURE in r.missing)
    }
}
