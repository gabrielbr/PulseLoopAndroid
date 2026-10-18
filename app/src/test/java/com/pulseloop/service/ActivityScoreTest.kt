package com.pulseloop.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** Oura-style activity score (docs/oura-ux-analysis.md phase 4). */
class ActivityScoreTest {

    private val zone = ZoneOffset.UTC
    private val day = LocalDate.of(2026, 10, 18)
    private fun at(hour: Int, minute: Int = 0) = day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    /** Buckets of [steps] at :00 and :30 of every hour from 08:00 to [untilHour]. */
    private fun buckets(untilHour: Int, steps: Int = 300) =
        (8 until untilHour).flatMap { h -> listOf(at(h) to steps, at(h, 30) to steps) }

    private fun workouts(n: Int, minutes: Int = 45) = (1..n).map { ActivityScore.Workout(at(7) - it * 86_400_000L, minutes) }

    private fun inputs(
        stepsToday: Int? = 10_000,
        past: List<Int> = List(7) { 10_500 },
        todayBuckets: List<Pair<Long, Int>> = buckets(18),
        w: List<ActivityScore.Workout> = workouts(3),
        now: Long = at(18, 15),
    ) = ActivityScore.Inputs(stepsToday, 10_000, past, todayBuckets, w, now, zone)

    @Test
    fun `no step data at all gives no score rather than zero`() {
        assertNull(ActivityScore.compute(inputs(stepsToday = null, past = emptyList(), todayBuckets = emptyList(), w = emptyList())))
    }

    @Test
    fun `an active, balanced week scores optimal`() {
        val r = ActivityScore.compute(inputs())!!
        assertTrue("got ${r.value}", r.value >= 85)
        assertTrue(r.derived)
    }

    @Test
    fun `a sedentary afternoon shows up as inactive hours`() {
        val quiet = buckets(12) + (12 until 18).flatMap { h -> listOf(at(h) to 20, at(h, 30) to 20) }
        val c = ActivityScore.moveEveryHour(inputs(todayBuckets = quiet))!!
        assertEquals("6 inactive hours since 8:00", c.detail)
        assertTrue(c.score < 50)
    }

    @Test
    fun `move every hour waits until a couple of hours of the day have passed`() {
        assertNull(ActivityScore.moveEveryHour(inputs(now = at(9, 10), todayBuckets = buckets(9))))
    }

    @Test
    fun `training every single day costs recovery points`() {
        val daily = (0 until 7).map { ActivityScore.Workout(at(7) - it * 86_400_000L, 30) }
        assertEquals(50, ActivityScore.recoveryTime(inputs(w = daily)).score)
        assertEquals(100, ActivityScore.recoveryTime(inputs(w = workouts(3))).score)
    }

    @Test
    fun `meeting the goal on fewer days lowers meet daily goals`() {
        val c = ActivityScore.meetDailyGoals(inputs(past = listOf(12_000, 4_000, 3_000, 2_000, 11_000, 5_000, 6_000)))!!
        assertEquals("Goal met on 2 of the last 7 days", c.detail)
        assertEquals(55, c.score)
    }
}
