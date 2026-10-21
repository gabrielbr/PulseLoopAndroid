package com.pulseloop.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** Cardio load, cycle phases, step calibration and the breathing effect. */
class ReleaseCTest {

    // ── Cardio load ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `quiet waking heart rate is the day's 20th percentile, and needs 3 days`() {
        assertEquals(54.0, CardioLoad.quietOfDay((50..69).map { it.toDouble() })!!, 0.0)
        assertNull(CardioLoad.quietOfDay(listOf(60.0, 61.0)))
        assertNull(CardioLoad.quietLevel(listOf(55.0, 56.0)))
        assertEquals(56.0, CardioLoad.quietLevel(listOf(55.0, 56.0, 58.0))!!, 0.0)
    }

    @Test
    fun `load grows with heart-rate reserve and is banded against your usual day`() {
        val (load, elevated) = CardioLoad.load(listOf(0L to 120.0, 600_000L to 120.0), quiet = 60.0, hrMax = 180.0)
        assertEquals(8.36, load, 0.01)
        assertEquals(10, elevated)
        assertEquals(0.0, CardioLoad.load(listOf(0L to 55.0, 600_000L to 58.0), 60.0, 180.0).first, 0.0)
        assertEquals(CardioLoad.Band.HIGH, CardioLoad.band(100.0, 50.0))
        assertEquals(CardioLoad.Band.USUAL, CardioLoad.band(30.0, 50.0))
        assertNull(CardioLoad.band(30.0, null))
    }

    // ── Cycle ────────────────────────────────────────────────────────────────────────────────

    private val periods = listOf(
        Cycle.Period(LocalDate.of(2026, 8, 4), LocalDate.of(2026, 8, 8)),
        Cycle.Period(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 5)),
        Cycle.Period(LocalDate.of(2026, 9, 29), null),
    )

    @Test
    fun `phase comes from logged dates and the user's own cycle length`() {
        assertEquals(Cycle.Phase.MENSTRUAL, Cycle.status(periods, LocalDate.of(2026, 9, 30))!!.phase)
        val mid = Cycle.status(periods, LocalDate.of(2026, 10, 10))!!
        assertEquals(12, mid.cycleDay)
        assertEquals(28, mid.usualLength)
        assertEquals(LocalDate.of(2026, 10, 27), mid.nextStart)
        assertEquals(Cycle.Phase.FOLLICULAR, mid.phase)
        assertEquals(Cycle.Phase.LUTEAL, Cycle.status(periods, LocalDate.of(2026, 10, 20))!!.phase)
        assertEquals(Cycle.Phase.LATE, Cycle.status(periods, LocalDate.of(2026, 10, 30))!!.phase)
    }

    @Test
    fun `one logged period gives the day but no phase estimate`() {
        val s = Cycle.status(listOf(Cycle.Period(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 5))), LocalDate.of(2026, 10, 12))!!
        assertEquals(12, s.cycleDay)
        assertEquals(Cycle.Phase.UNKNOWN, s.phase)
        assertNull(s.nextStart)
        assertNull(Cycle.status(emptyList(), LocalDate.of(2026, 10, 12)))
    }

    // ── Step calibration ─────────────────────────────────────────────────────────────────────

    @Test
    fun `phone counter readings add up per day and survive a reboot`() {
        val d = LocalDate.of(2026, 10, 20)
        var days = StepCalibration.accumulate(emptyMap(), null, 1000, d)
        assertEquals(0, days[d])
        days = StepCalibration.accumulate(days, 1000, 1500, d)
        days = StepCalibration.accumulate(days, 1500, 200, d) // reboot: 200 since boot
        assertEquals(700, days[d])
    }

    @Test
    fun `the factor needs five comparable days and a plausible ratio`() {
        val today = LocalDate.of(2026, 10, 21)
        val phone = (1..5).associate { today.minusDays(it.toLong()) to 5500 }
        val ring = (1..5).associate { today.minusDays(it.toLong()) to 5000 }
        val r = StepCalibration.factor(phone, ring, today)
        assertNotNull(r)
        assertEquals(1.1, r!!.factor, 0.001)
        assertNull(StepCalibration.factor(phone.entries.take(4).associate { it.key to it.value }, ring, today))
        assertNull(StepCalibration.factor(phone.mapValues { 12000 }, ring, today))
    }

    // ── Breathing effect ─────────────────────────────────────────────────────────────────────

    @Test
    fun `breathing effect compares after with before and needs three sessions`() {
        val pair = listOf(40.0) to listOf(44.0)
        assertEquals(MindfulSessions.Effect(3, 10), MindfulSessions.effectFrom(listOf(pair, pair, pair)))
        assertNull(MindfulSessions.effectFrom(listOf(pair, pair, emptyList<Double>() to listOf(50.0))))
    }
}
