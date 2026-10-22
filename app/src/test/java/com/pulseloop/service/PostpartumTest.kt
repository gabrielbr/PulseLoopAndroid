package com.pulseloop.service

import com.pulseloop.ring.SleepStage
import com.pulseloop.ring.SleepStage.AWAKE
import com.pulseloop.ring.SleepStage.DEEP
import com.pulseloop.ring.SleepStage.LIGHT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Postpartum mode's rules (docs/postpartum-breastfeeding-research.md). */
class PostpartumTest {

    private val birth = LocalDate.of(2026, 6, 1)
    private val h = 3_600_000L

    @Test
    fun `cycle predictions pause while breastfeeding, and until a period after birth otherwise`() {
        assertTrue(Postpartum.pausesCycle(true, birth, Postpartum.Feeding.EXCLUSIVE, listOf(birth.plusDays(90))))
        assertTrue(Postpartum.pausesCycle(true, birth, Postpartum.Feeding.NONE, emptyList()))
        // Bleeding in the first 8 weeks isn't a period's return.
        assertTrue(Postpartum.pausesCycle(true, birth, Postpartum.Feeding.NONE, listOf(birth.plusDays(20))))
        assertFalse(Postpartum.pausesCycle(true, birth, Postpartum.Feeding.NONE, listOf(birth.plusDays(70))))
        assertFalse(Postpartum.pausesCycle(false, birth, Postpartum.Feeding.EXCLUSIVE, emptyList()))
    }

    @Test
    fun `breastfeeding calories follow the guidelines, halved for mixed feeding`() {
        assertEquals(330, Postpartum.calorieBonus(birth, birth.plusMonths(2), Postpartum.Feeding.EXCLUSIVE))
        assertEquals(400, Postpartum.calorieBonus(birth, birth.plusMonths(7), Postpartum.Feeding.EXCLUSIVE))
        assertEquals(165, Postpartum.calorieBonus(birth, birth.plusMonths(2), Postpartum.Feeding.MIXED))
        assertEquals(0, Postpartum.calorieBonus(birth, birth.plusMonths(13), Postpartum.Feeding.EXCLUSIVE))
        assertEquals(0, Postpartum.calorieBonus(birth, birth.plusMonths(2), Postpartum.Feeding.NONE))
    }

    @Test
    fun `LAM holds only while all three conditions do`() {
        val ok = Postpartum.lam(birth, birth.plusMonths(3), Postpartum.Feeding.EXCLUSIVE, emptyList())
        assertTrue(ok.allHold)
        assertFalse(Postpartum.lam(birth, birth.plusMonths(7), Postpartum.Feeding.EXCLUSIVE, emptyList()).allHold)
        assertFalse(Postpartum.lam(birth, birth.plusMonths(3), Postpartum.Feeding.MIXED, emptyList()).allHold)
        assertFalse(Postpartum.lam(birth, birth.plusMonths(3), Postpartum.Feeding.EXCLUSIVE, listOf(birth.plusDays(70))).allHold)
    }

    @Test
    fun `alcohol clears two hours per standard drink, stacking drinks still clearing`() {
        val now = 100 * h
        // One standard drink (14 g) an hour ago: clear in another hour.
        assertEquals(now + h, Postpartum.alcoholClearAt(listOf(now - h to 14.0), now))
        // Two drinks at the same time: four hours from then.
        assertEquals(now + 3 * h, Postpartum.alcoholClearAt(listOf(now - h to 14.0, now - h to 14.0), now))
        // A second drink while the first is clearing starts after it.
        assertEquals(now + 2 * h, Postpartum.alcoholClearAt(listOf(now - 2 * h to 14.0, now - h to 14.0), now))
        // Long done.
        assertNull(Postpartum.alcoholClearAt(listOf(now - 5 * h to 14.0), now))
    }

    @Test
    fun `a broken night counts wakings between sleep and its longest stretch`() {
        val night = listOf(AWAKE, AWAKE) + List(90) { LIGHT } + listOf(AWAKE, AWAKE) + List(150) { DEEP } + listOf(AWAKE) + List(30) { LIGHT } + listOf(AWAKE)
        val b = Postpartum.nightBreaks(night)
        assertEquals(2, b.wakings)
        assertEquals(150, b.longestSleepMin)
        assertEquals(270, b.asleepMin)
        assertEquals(0, Postpartum.nightBreaks(List<SleepStage>(60) { LIGHT }).wakings)
    }

    @Test
    fun `EPDS bands follow the Brazilian cut-off`() {
        assertEquals(Postpartum.EpdsBand.LOW, Postpartum.epdsBand(9))
        assertEquals(Postpartum.EpdsBand.POSSIBLE, Postpartum.epdsBand(10))
        assertEquals(Postpartum.EpdsBand.LIKELY, Postpartum.epdsBand(13))
    }

    @Test
    fun `check-ins are suggested at two and six weeks, three and six months`() {
        assertEquals(birth.plusWeeks(2), Postpartum.nextCheckIn(birth, birth.plusDays(10), emptyList()))
        val answered = listOf(Postpartum.EpdsResult(birth.plusWeeks(2).toString(), 4, 0))
        assertEquals(birth.plusWeeks(6), Postpartum.nextCheckIn(birth, birth.plusWeeks(3), answered))
    }

    @Test
    fun `weekly medians group readings by week since birth`() {
        val v = listOf(birth.plusDays(1) to 60.0, birth.plusDays(3) to 64.0, birth.plusDays(8) to 58.0, birth.minusDays(3) to 70.0)
        assertEquals(listOf(0 to 62.0, 1 to 58.0), Postpartum.weeklyMedians(v, birth))
    }
}
