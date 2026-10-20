package com.pulseloop.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Sleep need and debt, Symptom Radar, stress zones, body clock, cardio capacity and trends. */
class HealthInsightsTest {

    private val zone: ZoneId = ZoneOffset.UTC
    private val today: LocalDate = LocalDate.of(2026, 10, 18)

    /** A night ending at [wakeHour]:30 on [day], eight hours long, asleep [asleep] minutes. */
    private fun night(
        day: LocalDate, rhr: Double? = 55.0, hrv: Double? = 40.0, asleep: Int = 450, wakeHour: Int = 7,
    ): NightSummary {
        val end = day.atTime(wakeHour, 30).atZone(zone).toInstant().toEpochMilli()
        return NightSummary(day, end - 8 * 3600_000L, end, asleep, rhr, hrv, 1.0)
    }

    // ── Sleep need ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `sleep need is the typical length of nights the body recovered from`() {
        val recovered = (1..10).map { night(today.minusDays(it * 2L), rhr = 52.0, hrv = 45.0, asleep = 480) }
        val strained = (1..10).map { night(today.minusDays(it * 2L - 1), rhr = 60.0, hrv = 35.0, asleep = 400) }
        val need = SleepNeed.estimate(recovered + strained, fallbackMinutes = 450, today = today)
        assertTrue(need.isLearned)
        assertEquals(480, need.minutes)
    }

    @Test
    fun `sleep need falls back to the goal until five recovered nights`() {
        val nights = (1..4).map { night(today.minusDays(it.toLong()), rhr = 50.0, hrv = 50.0, asleep = 500) } +
            (5..14).map { night(today.minusDays(it.toLong()), rhr = 60.0, hrv = 30.0) }
        val need = SleepNeed.estimate(nights, fallbackMinutes = 450, today = today)
        assertFalse(need.isLearned)
        assertEquals(450, need.minutes)
    }

    // ── Sleep debt ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `nights at the need carry no debt`() {
        val debt = SleepDebt.compute(List(14) { 480 }, SleepNeed(480, true))!!
        assertEquals(0, debt.minutes)
        assertEquals(SleepDebt.Level.NONE, debt.level)
    }

    @Test
    fun `an hour short every night builds high debt that fades with age`() {
        val debt = SleepDebt.compute(List(14) { 420 }, SleepNeed(480, true))!!
        // 60 × (1 − 0.9¹⁴) / 0.1 ≈ 463, well under the undecayed 840.
        assertEquals(463, debt.minutes)
        assertEquals(SleepDebt.Level.HIGH, debt.level)
    }

    @Test
    fun `a surplus pays debt back but never below zero`() {
        val debt = SleepDebt.compute(List(10) { 600 } + List(4) { 480 }, SleepNeed(480, true))!!
        assertEquals(0, debt.minutes)
    }

    @Test
    fun `sleep debt needs five nights`() {
        assertNull(SleepDebt.compute(List(10) { null } + List(4) { 400 }, SleepNeed(480, true)))
    }

    // ── Symptom Radar ────────────────────────────────────────────────────────────────────────

    /** 20 ordinary nights before [today]: RHR 54/56, HRV 38/42, temperature 33.9/34.1. */
    private fun baseline(n: Int = 20) = (1..n).map { i ->
        val even = i % 2 == 0
        SymptomRadar.Night(
            today.minusDays(i.toLong()),
            temperature = if (even) 33.9 else 34.1,
            restingHr = if (even) 54.0 else 56.0,
            hrv = if (even) 38.0 else 42.0,
        )
    }

    private fun strained(day: LocalDate) = SymptomRadar.Night(day, temperature = 34.6, restingHr = 60.0, hrv = 30.0)

    @Test
    fun `an ordinary night shows no signs`() {
        val tonight = SymptomRadar.Night(today, temperature = 34.0, restingHr = 55.0, hrv = 40.0)
        val r = SymptomRadar.compute(baseline() + tonight, today)!!
        assertEquals(SymptomRadar.Level.NONE, r.level)
        assertTrue(r.flagged.isEmpty())
    }

    @Test
    fun `three signals on one night are minor until the night before also shows signs`() {
        val one = SymptomRadar.compute(baseline() + strained(today), today)!!
        assertEquals(3, one.flagged.size)
        assertEquals(SymptomRadar.Level.MINOR, one.level)

        val history = baseline().filter { it.day != today.minusDays(1) } + strained(today.minusDays(1))
        val two = SymptomRadar.compute(history + strained(today), today)!!
        assertEquals(SymptomRadar.Level.MAJOR, two.level)
    }

    @Test
    fun `a perfectly steady baseline does not turn a tiny change into a sign`() {
        val steady = (1..20).map { SymptomRadar.Night(today.minusDays(it.toLong()), temperature = 34.0, restingHr = 55.0, hrv = 40.0) }
        val tonight = SymptomRadar.Night(today, temperature = 34.1, restingHr = 56.0, hrv = 38.0)
        val r = SymptomRadar.compute(steady + tonight, today)!!
        assertTrue(r.flagged.isEmpty())
    }

    @Test
    fun `the radar waits for two weeks of baseline`() {
        val r = SymptomRadar.compute(baseline(10) + strained(today), today)!!
        assertEquals(SymptomRadar.Level.NONE, r.level)
        assertEquals(10, r.baselineNights)
        assertTrue(r.signals.isEmpty())
    }

    // ── Stress ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `stress readings fall into the four zones`() {
        val s = DaytimeStress.compute(listOf(10.0, 20.0, 50.0, 70.0, 90.0, 95.0))!!
        assertEquals(60, s.restoredMin)
        assertEquals(30, s.relaxedMin)
        assertEquals(30, s.engagedMin)
        assertEquals(60, s.stressedMin)
        assertEquals(DaytimeStress.Summary.NORMAL, s.summary)
    }

    @Test
    fun `a day mostly in the stressed zone reads stressful`() {
        assertEquals(DaytimeStress.Summary.STRESSFUL, DaytimeStress.compute(listOf(85.0, 85.0, 85.0, 85.0, 10.0))!!.summary)
        assertEquals(DaytimeStress.Summary.RESTORED, DaytimeStress.compute(listOf(10.0, 15.0, 20.0, 50.0))!!.summary)
        assertNull(DaytimeStress.compute(listOf(50.0, 60.0)))
    }

    @Test
    fun `resilience rises with recovery and needs five days`() {
        assertNull(Resilience.compute(List(4) { Resilience.Day(60, 60, 1.0) }))
        val strong = Resilience.compute(List(7) { Resilience.Day(10, 120, 1.15) })!!
        val limited = Resilience.compute(List(7) { Resilience.Day(180, 10, 0.8) })!!
        assertTrue(strong.level.ordinal > limited.level.ordinal)
        assertEquals(Resilience.Level.LIMITED, limited.level)
    }

    // ── Body clock ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `a 3 30 midpoint is in between and steady nights are in sync`() {
        val c = BodyClock.compute((1..14).map { night(today.minusDays(it.toLong())) }, zone)!!
        assertEquals(210, c.usualMidpointMin)
        assertEquals("3:30", c.midpointLabel())
        assertEquals(BodyClock.Chronotype.INTERMEDIATE, c.chronotype)
        assertEquals(BodyClock.Alignment.ALIGNED, c.alignment)
    }

    @Test
    fun `two hours later this week is out of sync`() {
        val recent = (1..7).map { night(today.minusDays(it.toLong()), wakeHour = 9) }
        val older = (8..14).map { night(today.minusDays(it.toLong())) }
        val c = BodyClock.compute(recent + older, zone)!!
        assertEquals(120, c.recentShiftMin)
        assertEquals(BodyClock.Alignment.MISALIGNED, c.alignment)
        assertNull(BodyClock.compute(older, zone))
    }

    // ── Cardio capacity ──────────────────────────────────────────────────────────────────────

    @Test
    fun `cardio capacity uses the Uth estimate and age norms`() {
        val c = CardioCapacity.compute(restingHr = 60.0, hrv = null, age = 30, sex = "male")!!
        assertEquals(47.7, c.vo2max, 0.1)
        assertEquals(CardioCapacity.Level.AVERAGE, c.level)
        assertFalse(c.fromRing)

        val fit = CardioCapacity.compute(restingHr = 45.0, hrv = 70.0, age = 30, sex = "male")!!
        assertEquals(CardioCapacity.Level.EXCELLENT, fit.level)
        assertTrue(fit.cardioAge!! < 30)
    }

    @Test
    fun `a ring's own VO2max wins and missing inputs give nothing`() {
        assertTrue(CardioCapacity.compute(60.0, null, 30, "female", ringVo2 = 50.0)!!.fromRing)
        assertNull(CardioCapacity.compute(60.0, null, null, "male"))
        assertNull(CardioCapacity.compute(null, null, 30, "male"))
    }

    // ── Health areas and trends ──────────────────────────────────────────────────────────────

    @Test
    fun `health area ratings follow the score`() {
        assertEquals(HealthArea.Rating.THRIVING, HealthArea.rate(HealthArea.scoreOf(1.2)))
        assertEquals(HealthArea.Rating.NEEDS_CARE, HealthArea.rate(HealthArea.scoreOf(0.3)))
        assertTrue(HealthArea.scoreOf(1.0) in 80..90)
    }

    @Test
    fun `trends average by week starting Monday and by month`() {
        val start = LocalDate.of(2026, 10, 12)
        val daily = (0 until 14).associate { start.plusDays(it.toLong()) to (if (it < 7) 1.0 else 3.0) }
        val weeks = Trends.aggregate(daily, Trends.Period.WEEK)
        assertTrue(weeks.all { it.start.dayOfWeek == DayOfWeek.MONDAY })
        assertEquals(listOf(1.0, 3.0), weeks.map { it.value })

        val months = Trends.aggregate(mapOf(LocalDate.of(2026, 9, 30) to 2.0, LocalDate.of(2026, 10, 1) to 4.0, LocalDate.of(2026, 10, 2) to 6.0), Trends.Period.MONTH)
        assertEquals(listOf(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1)), months.map { it.start })
        assertEquals(5.0, months[1].value, 0.0)
        assertNotNull(Trends.aggregate(emptyMap(), Trends.Period.YEAR))
    }
}
