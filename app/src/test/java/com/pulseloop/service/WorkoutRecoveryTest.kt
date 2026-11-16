package com.pulseloop.service

import com.pulseloop.data.entity.ActivitySessionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkoutRecoveryTest {
    private val start = 1_700_000_000_000L
    private val min = 60_000L
    private fun session(status: String = "recording", endedAt: Long? = null) =
        ActivitySessionEntity(type = "running", statusRaw = status, startedAt = start, endedAt = endedAt, useGps = false)

    @Test
    fun `a workout ends at its last sample before a quiet stretch`() {
        // Samples every 2 min for 30 min, then all-day monitoring 40 min later.
        val samples = (1..15).map { start + it * 2 * min } + (start + 70 * min)
        assertEquals(start + 30 * min, WorkoutRecovery.endAt(session(), samples, now = start + 5 * 60 * min))
    }

    @Test
    fun `nothing recorded means nothing to report`() {
        assertNull(WorkoutRecovery.endAt(session(), emptyList(), now = start + 60 * min))
        // A sample only after a long silence is not this workout's.
        assertNull(WorkoutRecovery.endAt(session(), listOf(start + 30 * min), now = start + 60 * min))
    }

    @Test
    fun `a paused workout ends where it was paused`() {
        assertEquals(start + 20 * min, WorkoutRecovery.endAt(session("paused", endedAt = start + 20 * min), listOf(start + 50 * min), now = start + 90 * min))
    }

    @Test
    fun `a workout never runs past six hours or into the future`() {
        val samples = (1..600).map { start + it * min }
        assertEquals(start + WorkoutRecovery.MAX_LENGTH_MS, WorkoutRecovery.endAt(session(), samples, now = start + 20 * 60 * min))
        assertEquals(start + 45 * min, WorkoutRecovery.endAt(session(), samples, now = start + 45 * min))
    }
}
