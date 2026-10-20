package com.pulseloop.ui.screens

import com.pulseloop.ui.screens.TodayMoments.Moment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which time-of-day cards Today shows, never more than two. */
class TodayMomentsTest {

    @Test
    fun `morning leads with last night`() {
        assertEquals(listOf(Moment.LAST_NIGHT), TodayMoments.at(8, hasSleep = true, mealsToday = 0, hasBedtime = true))
        assertTrue(TodayMoments.at(8, hasSleep = false, mealsToday = 0, hasBedtime = true).isEmpty())
    }

    @Test
    fun `midday asks about food only until a meal is logged`() {
        assertEquals(listOf(Moment.FOOD), TodayMoments.at(13, hasSleep = true, mealsToday = 0, hasBedtime = true))
        assertTrue(TodayMoments.at(13, hasSleep = true, mealsToday = 1, hasBedtime = true).isEmpty())
    }

    @Test
    fun `evening shows tonight, then the day's tags`() {
        assertEquals(listOf(Moment.TONIGHT), TodayMoments.at(17, hasSleep = true, mealsToday = 2, hasBedtime = true))
        assertEquals(listOf(Moment.TONIGHT, Moment.TAG_DAY), TodayMoments.at(21, hasSleep = true, mealsToday = 2, hasBedtime = true))
        assertEquals(listOf(Moment.TAG_DAY), TodayMoments.at(1, hasSleep = true, mealsToday = 0, hasBedtime = false))
    }

    @Test
    fun `the afternoon gap stays clear`() {
        assertTrue(TodayMoments.at(16, hasSleep = true, mealsToday = 0, hasBedtime = true).isEmpty())
    }

    @Test
    fun `never more than two`() {
        for (h in 0..23) assertTrue(TodayMoments.at(h, true, 0, true).size <= TodayMoments.MAX)
    }
}
