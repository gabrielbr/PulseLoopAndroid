package com.pulseloop.service

import com.pulseloop.notifications.GuidanceWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/** Daily guidance: unusual-signal check, bedtime and caffeine timing, weekly report. */
class DailyGuidanceTest {

    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 10, 18)

    /** A night from 23:00 to 07:00 (8 h in bed) ending on [day]. */
    private fun night(
        day: LocalDate, rhr: Double? = 55.0, hrv: Double? = 40.0, spo2: Double? = 96.0, asleep: Int = 450,
        wake: LocalTime = LocalTime.of(7, 0),
    ): NightSummary {
        val end = day.atTime(wake).atZone(zone).toInstant().toEpochMilli()
        return NightSummary(day, end - 8 * 3600_000L, end, asleep, rhr, hrv, 1.0, spo2)
    }

    /** Earlier nights with a little natural variation. */
    private fun history(n: Int = 14) = (1..n).map {
        night(today.minusDays(it.toLong()), rhr = 54.0 + it % 3, hrv = 38.0 + it % 5, spo2 = 95.0 + it % 3, asleep = 430 + (it % 4) * 10)
    }

    @Test
    fun `a usual night raises nothing`() {
        assertTrue(VitalsCheck.offSignals(night(today), history()).isEmpty())
    }

    @Test
    fun `one unusual signal is listed but does not alert`() {
        val s = VitalsCheck.offSignals(night(today, rhr = 64.0), history())
        assertEquals(listOf("Resting heart rate"), s.map { it.title })
        assertFalse(VitalsCheck.shouldAlert(s))
    }

    @Test
    fun `two unusual signals alert, with the usual value in the detail`() {
        val s = VitalsCheck.offSignals(night(today, rhr = 64.0, hrv = 25.0), history())
        assertTrue(VitalsCheck.shouldAlert(s))
        assertEquals("25 ms, lower than your usual 40 ms", s.first { it.title == "HRV" }.detail)
    }

    @Test
    fun `no typical range without a week of nights`() {
        assertTrue(VitalsCheck.offSignals(night(today, rhr = 80.0, hrv = 10.0), history(4)).isEmpty())
    }

    @Test
    fun `bedtime is the usual wake time minus the goal stretched by the user's efficiency`() {
        // Wake 07:00; median night is 450 asleep of 480 in bed (93.75%), so a 450-minute goal
        // needs 480 minutes in bed → 23:00.
        val g = BedtimeGuidance.compute(history(), goalMinutes = 450, zone = zone)!!
        assertEquals(LocalTime.of(7, 0), g.usualWake)
        assertEquals(LocalTime.of(23, 0), g.bedtime)
        assertEquals(g.bedtime.minusMinutes(15), g.windowStart)
        assertEquals(g.bedtime.minusHours(8), g.caffeineCutoff)
    }

    @Test
    fun `bedtime needs a few nights first`() {
        assertNull(BedtimeGuidance.compute(history(3), 450, zone))
    }

    @Test
    fun `the weekly report compares with last week and picks one focus`() {
        val thisWeek = WeeklyReport.Week(avgAsleepMinutes = 360, avgRestingHr = 58.0, avgHrv = 38.0, avgSteps = 7000, nights = 7)
        val lastWeek = WeeklyReport.Week(avgAsleepMinutes = 420, avgRestingHr = 56.0, avgHrv = 40.0, avgSteps = 8000, nights = 7)
        val (title, body) = WeeklyReport.text(thisWeek, lastWeek, sleepGoalMinutes = 480)!!
        assertEquals("Your week in review", title)
        assertTrue(body, body.contains("Sleep 6h 00m a night (-60 min vs last week)"))
        assertTrue(body, body.contains("Aim for an earlier bedtime"))
    }

    @Test
    fun `no weekly report from fewer than three nights`() {
        assertNull(WeeklyReport.text(WeeklyReport.Week(400, 55.0, 40.0, 8000, nights = 2), null, 480))
    }

    @Test
    fun `minutes until bedtime wraps past midnight`() {
        assertEquals(30, GuidanceWorker.minutesUntil(LocalTime.of(22, 30), LocalTime.of(23, 0)))
        assertEquals(45, GuidanceWorker.minutesUntil(LocalTime.of(23, 30), LocalTime.of(0, 15)))
    }

    @Test
    fun `nightly SpO2 is the median of plausible readings inside the night`() {
        val start = 0L
        val end = 8 * 3600_000L
        val s = listOf(95.0, 96.0, 97.0, 50.0).mapIndexed { i, v -> VitalSample(start + i * 3600_000L, v) }
        val n = NightlyVitals.summarize(today, start, end, 450, emptyList(), emptyList(), s)
        assertNotNull(n.spo2)
        assertEquals(96.0, n.spo2!!, 0.01)
    }
}
