package com.pulseloop.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutHeartTest {
    private val min = 60_000L
    private fun s(atMin: Double, bpm: Double) = WorkoutHeart.Sample((atMin * min).toLong(), bpm)

    @Test
    fun `a burst of stream readings does not outvote the rest of the session`() {
        // Three minutes of 1 Hz stream at 70, then the ring's log every 5 min at 120 for 40 min.
        val stream = (0 until 180).map { WorkoutHeart.Sample(it * 1000L, 70.0) }
        val log = (1..8).map { s(3.0 + (it - 1) * 5, 120.0) }
        val end = (43 * min)
        val summary = WorkoutHeart.summary(stream + log, end, age = 40)!!
        // Per row the mean would be ~72; by time it is near 120 for the 40 logged minutes, but
        // each log reading only stands for two minutes, so it lands between the two.
        assertTrue("was ${summary.average}", summary.average > 100)
        assertEquals(120.0, summary.max, 0.0)
    }

    @Test
    fun `a gap is not credited to the reading before it`() {
        val w = WorkoutHeart.weighted(listOf(s(0.0, 100.0), s(30.0, 100.0)), endMs = 31 * min)
        assertEquals(2.0, w[0].minutes, 1e-9)
        assertEquals(1.0, w[1].minutes, 1e-9)
    }

    @Test
    fun `noise outside the plausible band is dropped`() {
        val w = WorkoutHeart.weighted(listOf(s(0.0, 0.0), s(1.0, 12.0), s(2.0, 250.0), s(3.0, 90.0)), 4 * min)
        assertEquals(listOf(90.0), w.map { it.bpm })
    }

    @Test
    fun `zone minutes add up to the covered time`() {
        val samples = (0 until 30).map { s(it.toDouble(), if (it < 10) 100.0 else 150.0) }
        val summary = WorkoutHeart.summary(samples, 30 * min, age = 40)!! // max 180
        assertEquals(30.0, summary.zoneMinutes.values.sum(), 1e-9)
        assertEquals(10.0, summary.zoneMinutes[HeartRateZones.Zone.REST]!!, 1e-9)   // 55%
        assertEquals(20.0, summary.zoneMinutes[HeartRateZones.Zone.PEAK]!!, 1e-9)   // 83%
    }

    @Test
    fun `training load lands near a watch's for the 12 Nov gym session`() {
        // 46 minutes averaging 116 bpm, man of about 40 resting at 60: the watch said 34.
        val samples = (0 until 46).map { s(it.toDouble(), 116.0) }
        val load = WorkoutHeart.trainingLoad(samples, 46 * min, age = 40, sex = "male", restingBpm = 60.0)!!
        assertEquals(34.0, load, 8.0)
    }

    @Test
    fun `no readings means no load and no summary`() {
        assertNull(WorkoutHeart.trainingLoad(emptyList(), min, 40, "male", 60.0))
        assertNull(WorkoutHeart.summary(emptyList(), min, 40))
    }

    @Test
    fun `resting calories need the whole profile`() {
        assertNull(WorkoutHeart.restingCalories(60.0, "male", 40, 80.0, null))
        // Mifflin-St Jeor: 10*80 + 6.25*180 - 5*40 + 5 = 1730 kcal/day.
        assertEquals(1730.0 / 24, WorkoutHeart.restingCalories(60.0, "male", 40, 80.0, 180.0)!!, 1e-9)
    }
}
