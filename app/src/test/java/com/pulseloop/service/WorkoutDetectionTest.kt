package com.pulseloop.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The heart-rate rule on a 5-minute grid, and workouts another device recorded. */
class WorkoutDetectionTest {
    private val min = 60_000L
    private val base = 1_700_000_000_000L
    private val quiet = 65.0
    private val hrMax = 190.0 // enter ≈ 102.5 bpm, stretch mean must reach ≈ 96 bpm

    private fun grid(vararg bpm: Double) = bpm.mapIndexed { i, v -> base + i * 5 * min to v }

    @Test
    fun `a strength session with rests between sets is one workout`() {
        // 45 minutes: sets around 110-125, rests dipping to 92-98.
        val hr = grid(112.0, 95.0, 118.0, 92.0, 121.0, 98.0, 115.0, 94.0, 125.0, 110.0)
        val p = ActivityDetection.heartSessions(hr, quiet, hrMax)
        assertEquals(1, p.size)
        assertEquals(base, p.single().startAt)
        assertTrue(p.single().minutes >= 40)
    }

    @Test
    fun `one high reading in a calm hour is not a workout`() {
        val hr = grid(70.0, 72.0, 115.0, 71.0, 69.0, 73.0, 70.0)
        assertTrue(ActivityDetection.heartSessions(hr, quiet, hrMax).isEmpty())
    }

    @Test
    fun `a long rest ends the stretch, and a short piece doesn't count`() {
        // 15 min raised, then 20 min at rest, then 10 min raised: neither piece reaches 20 minutes.
        val hr = grid(110.0, 112.0, 111.0, 108.0, 80.0, 78.0, 79.0, 80.0, 110.0, 112.0, 111.0)
        assertTrue(ActivityDetection.heartSessions(hr, quiet, hrMax).isEmpty())
    }

    @Test
    fun `a gap in readings ends the stretch`() {
        val first = grid(110.0, 112.0, 111.0)
        val later = (0..2).map { base + 40 * min + it * 5 * min to 112.0 }
        assertTrue(ActivityDetection.heartSessions(first + later, quiet, hrMax).isEmpty())
    }

    @Test
    fun `steady cardio still counts`() {
        val hr = grid(130.0, 132.0, 131.0, 135.0, 133.0, 130.0)
        assertEquals(ActivityDetection.Proposal.Kind.CARDIO, ActivityDetection.heartSessions(hr, quiet, hrMax).single().kind)
    }

    private fun found(type: String, start: Long, minutes: Int) =
        OtherDeviceWorkouts.Found("hc-abc$start", type, start, start + minutes * min, "Watch")

    @Test
    fun `another device's workout is offered with its own type first and keeps its id`() {
        val p = OtherDeviceWorkouts.proposals(listOf(found("gym", base, 45))).single()
        assertEquals("gym", p.bestType)
        assertEquals("gym", p.guesses.first())
        assertEquals(ActivityClassifier.TYPES.size, p.guesses.size)
        assertEquals("hc-abc$base", p.external?.id)
        assertEquals(45, p.minutes)
    }

    @Test
    fun `a run from another device keeps the run kind, and tiny records are skipped`() {
        val ps = OtherDeviceWorkouts.proposals(listOf(found("run", base, 30), found("walk", base + 90 * min, 3)))
        assertEquals(1, ps.size)
        assertEquals(ActivityDetection.Proposal.Kind.RUN, ps.single().kind)
    }

    @Test
    fun `the ring's guess for the same time gives way to the device's record`() {
        val external = OtherDeviceWorkouts.proposals(listOf(found("gym", base, 45)))
        val overlapping = ActivityDetection.Proposal(ActivityDetection.Proposal.Kind.CARDIO, base + 10 * min, base + 50 * min, "")
        val separate = ActivityDetection.Proposal(ActivityDetection.Proposal.Kind.WALK, base + 120 * min, base + 150 * min, "")
        assertEquals(listOf(separate), OtherDeviceWorkouts.withoutOverlaps(listOf(overlapping, separate), external))
    }
}
