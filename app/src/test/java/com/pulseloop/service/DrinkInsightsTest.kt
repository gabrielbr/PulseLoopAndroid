package com.pulseloop.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** Drinks: types, the week, streaks, savings, what drinking does to the nights, and AUDIT-C. */
class DrinkInsightsTest {

    private val zone = ZoneOffset.UTC
    private fun d(month: Int, day: Int) = LocalDate.of(2026, month, day)

    private fun night(day: LocalDate, hrv: Double? = 50.0, rhr: Double? = 55.0, asleep: Int = 450): NightSummary {
        val end = day.atTime(7, 0).atZone(zone).toInstant().toEpochMilli()
        return NightSummary(day, end - 8 * 3600_000L, end, asleep, rhr, hrv, 1.0)
    }

    // ── Types ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a can of beer is about 14 g of alcohol`() {
        assertEquals(13.8, DrinkType.BEER.grams, 0.05)
        assertEquals(1.38, DrinkInsights.standardDrinks(DrinkType.BEER.grams, 10.0), 0.01)
        assertEquals("1.5", DrinkInsights.fmt(1.46))
        assertEquals("2", DrinkInsights.fmt(2.0))
    }

    @Test
    fun `drink type and count are read from free text`() {
        assertEquals(DrinkType.WINE, DrinkType.guess("2 taças de vinho"))
        assertEquals(DrinkType.CAIPIRINHA, DrinkType.guess("Caipirinha de limão"))
        assertEquals(DrinkType.DRAFT, DrinkType.guess("Chopp"))
        assertEquals(DrinkType.BEER_600, DrinkType.guess("Cerveja 600ml"))
        assertEquals(DrinkType.BEER, DrinkType.guess("3 beers"))
        assertEquals(DrinkType.COCKTAIL, DrinkType.guess("Gin tônica"))
        assertEquals(DrinkType.SPIRIT, DrinkType.guess("Whisky on the rocks"))
        assertEquals(DrinkType.OTHER, DrinkType.guess("Hard cider"))
        assertEquals(2, DrinkType.count("2 taças de vinho"))
        assertEquals(2, DrinkType.count("duas cervejas"))
        assertEquals(3, DrinkType.count("3x beer"))
        assertEquals(1, DrinkType.count("Wine"))
    }

    // ── The week ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the week counts drinks from Monday and dry days through yesterday`() {
        val today = d(10, 21) // Wednesday
        val w = DrinkInsights.week(mapOf(d(10, 19) to 20.0, d(10, 21) to 13.8), today, 10.0, trackedSince = d(10, 1))
        assertEquals(d(10, 19), w.start)
        assertEquals(3.38, w.standardDrinks, 0.01)
        assertEquals(2, w.drinkingDays)
        assertEquals(1, w.dryDays) // Tuesday; today isn't over
        assertTrue(w.drankToday)
        assertEquals(0, w.heavyDays)
        assertEquals(1, DrinkInsights.week(mapOf(d(10, 19) to 70.0), today, 10.0, d(10, 1)).heavyDays)
    }

    @Test
    fun `days before tracking began are not counted as dry`() {
        val w = DrinkInsights.week(emptyMap(), d(10, 21), 10.0, trackedSince = d(10, 20))
        assertEquals(1, w.dryDays)
    }

    @Test
    fun `weekly totals cover the last weeks oldest first`() {
        val totals = DrinkInsights.weeklyTotals(mapOf(d(10, 13) to 20.0, d(10, 20) to 10.0), d(10, 21), 10.0, 3)
        assertEquals(listOf(d(10, 5), d(10, 12), d(10, 19)), totals.map { it.first })
        assertEquals(listOf(0.0, 2.0, 1.0), totals.map { it.second })
    }

    @Test
    fun `the headline says what is left and how many dry days are still needed`() {
        val wed = d(10, 21)
        fun week(total: Double, dry: Int, drank: Boolean) = DrinkInsights.Week(d(10, 19), total, 0, dry, 0, drank)
        assertEquals("3.6 left this week" to "3 more dry days to reach 4", DrinkInsights.headline(week(3.38, 1, true), 7.0, 4, wed))
        assertEquals("2 over your limit", DrinkInsights.headline(week(9.0, 1, false), 7.0, 4, wed).first)
        assertEquals("No drinks yet this week", DrinkInsights.headline(week(0.0, 2, false), 7.0, 4, wed).first)
        assertEquals("Dry-day goal reached: 4 of 4", DrinkInsights.headline(week(2.0, 4, false), 7.0, 4, wed).second)
        val sun = d(10, 25)
        assertEquals("1 of 4 dry days; a fresh week starts Monday", DrinkInsights.headline(week(5.0, 1, false), 7.0, 4, sun).second)
    }

    @Test
    fun `the one-tap row ranks the drinks the user logs most`() {
        assertEquals(
            listOf(DrinkType.WINE, DrinkType.DRAFT, DrinkType.BEER, DrinkType.CAIPIRINHA),
            DrinksRepository.topTypes(listOf(DrinkType.WINE, DrinkType.WINE, DrinkType.DRAFT)),
        )
        assertEquals(listOf(DrinkType.BEER, DrinkType.WINE, DrinkType.CAIPIRINHA, DrinkType.SPIRIT), DrinksRepository.topTypes(emptyList()))
    }

    // ── Streaks and savings ──────────────────────────────────────────────────────────────────

    @Test
    fun `dry streaks run through yesterday`() {
        val s = DrinkInsights.streaks(setOf(d(10, 15), d(10, 19)), d(10, 21), trackedSince = d(10, 10))
        assertEquals(1, s.current)
        assertEquals(5, s.best)
        assertEquals(0, DrinkInsights.streaks(setOf(d(10, 21)), d(10, 21), d(10, 10)).current)
        assertEquals(3, DrinkInsights.streaks(emptySet(), d(10, 21), d(10, 18)).current)
    }

    @Test
    fun `savings count only weeks under the typical week`() {
        val s = DrinkInsights.savings(listOf(5.0, 12.0), baselinePerWeek = 10.0, pricePerDrink = 8.0, kcalPerDrink = 150.0)
        assertEquals(5.0, s.drinksAvoided, 0.0)
        assertEquals(40.0, s.money!!, 0.0)
        assertEquals(750.0, s.kcal, 0.0)
        assertNull(DrinkInsights.savings(listOf(5.0), 10.0, null, 150.0).money)
    }

    // ── Nights ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the morning after compares last night with dry nights`() {
        val since = d(10, 10)
        val dry = (11..18).map { night(d(10, it)) }
        val last = night(d(10, 21), hrv = 40.0, rhr = 60.0, asleep = 400)
        val m = DrinkInsights.morningAfter(last, dry, mapOf(d(10, 20) to 30.0), 10.0, since)!!
        assertEquals(3.0, m.standardDrinks, 0.0)
        assertEquals(-20, m.impact.hrvPct)
        assertEquals(5, m.impact.restingHr)
        assertEquals(-50, m.impact.asleepMinutes)
        val line = DrinksRepository.morningAfterLine(m)
        assertTrue(line, line.startsWith("Last night: 3 drinks."))
        assertTrue(line, line.contains("HRV 20% below your dry nights"))
    }

    @Test
    fun `no morning after without drinks, and no comparison below three dry nights`() {
        val since = d(10, 10)
        assertNull(DrinkInsights.morningAfter(night(d(10, 21)), emptyList(), emptyMap(), 10.0, since))
        val few = DrinkInsights.morningAfter(night(d(10, 21)), listOf(night(d(10, 19))), mapOf(d(10, 20) to 20.0), 10.0, since)!!
        assertNull(few.impact.hrvPct)
        assertTrue(DrinksRepository.morningAfterLine(few).contains("A few dry nights"))
    }

    @Test
    fun `dose separates one or two drinks from three or more`() {
        val since = d(9, 30)
        val nights = (1..5).map { night(d(10, it)) } +
            (6..8).map { night(d(10, it), hrv = 45.0) } +
            (9..11).map { night(d(10, it), hrv = 35.0) }
        val grams = (5..7).associate { d(10, it) to 15.0 } + (8..10).associate { d(10, it) to 40.0 }
        val dose = DrinkInsights.dose(nights, grams, 10.0, since)
        assertEquals(5, dose.dryNights)
        assertEquals(-10, dose.light!!.hrvPct)
        assertEquals(-30, dose.heavier!!.hrvPct)
        assertEquals(3, dose.heavier!!.nights)
    }

    @Test
    fun `recovery is the nights until HRV is back within 5 percent`() {
        val since = d(9, 30)
        val hrv = mapOf(6 to 40.0, 7 to 49.0, 11 to 48.0)
        val nights = (1..12).map { night(d(10, it), hrv = hrv[it] ?: 50.0) }
        val r = DrinkInsights.recoveryNights(nights, mapOf(d(10, 5) to 20.0, d(10, 10) to 20.0), since)
        assertNotNull(r)
        assertEquals(1.5, r!!, 0.0)
        assertNull(DrinkInsights.recoveryNights(nights, mapOf(d(10, 5) to 20.0), since))
    }

    // ── AUDIT-C ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `audit-c thresholds differ by sex and 8 is higher risk`() {
        assertEquals(3, AuditC.score(listOf(1, 1, 1)))
        assertEquals(AuditC.Level.INCREASING, AuditC.level(3, "female"))
        assertEquals(AuditC.Level.INCREASING, AuditC.level(3, null))
        assertEquals(AuditC.Level.LOW, AuditC.level(3, "male"))
        assertEquals(AuditC.Level.HIGHER, AuditC.level(8, "male"))
        assertEquals(AuditC.Level.LOW, AuditC.level(0, null))
        assertTrue(AuditC.advice(AuditC.Level.HIGHER).contains("don't stop all at once"))
        assertFalse(AuditC.advice(AuditC.Level.LOW).contains("doctor"))
    }
}
