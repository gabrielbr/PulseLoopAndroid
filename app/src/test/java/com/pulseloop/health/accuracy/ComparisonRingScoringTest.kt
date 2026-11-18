package com.pulseloop.health.accuracy

import com.pulseloop.data.entity.AccuracyCheckDayEntity
import com.pulseloop.ring.SleepStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class ComparisonRingScoringTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private fun at(d: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 11, d, h, m).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `comparison rows are kept apart and read back under plain names`() {
        val main = AccuracyCheckDayEntity(checkId = "c", metric = "STEPS", day = 1)
        val cmp = ComparisonRingScoring.tag(main)
        assertEquals("CMP:STEPS", cmp.metric)
        assertEquals(listOf("STEPS"), ComparisonRingScoring.comparisonRows(listOf(main, cmp)).map { it.metric })
        // The main ring's report filters by exact name, so it never sees the tagged row.
        assertEquals(1, listOf(main, cmp).count { it.metric == "STEPS" })
    }

    @Test
    fun `a night's onset, wake and asleep minutes come from its asleep minutes`() {
        val start = at(17, 23, 0)
        val minutes = (0 until 10).map { i ->
            val stage = if (i < 2 || i == 9) SleepStage.AWAKE else SleepStage.LIGHT
            (start + i * 60_000L) to stage.ordinal
        }
        val night = ComparisonRingScoring.nights(minutes, zone).values.single()
        assertEquals(7, night.asleepMinutes)
        assertEquals(start + 2 * 60_000L, night.onset)
        assertEquals(start + 9 * 60_000L, night.wake)
    }

    @Test
    fun `daily steps take the larger of buckets and the ring's own total`() {
        val day = at(17, 0)
        val steps = ComparisonRingScoring.dailySteps(listOf(at(17, 9) to 1000.0, at(17, 10) to 500.0), mapOf(day to 1800.0), zone)
        assertEquals(1800.0, steps.values.single(), 0.0)
    }

    private fun summary(mae: Double, verdict: AccuracyComparison.Verdict = AccuracyComparison.Verdict.AGREES) =
        AccuracyComparison.Summary(ComparisonMetric.HR_REST, verdict, 5, 0.0, 0.0, mae, 600, 0.0, null, 5.0)

    @Test
    fun `the closer ring wins only by a real margin`() {
        assertEquals(ComparisonRingScoring.Closer.MAIN, ComparisonRingScoring.closer(summary(3.0), summary(8.0)))
        assertEquals(ComparisonRingScoring.Closer.COMPARISON, ComparisonRingScoring.closer(summary(8.0), summary(3.0)))
        assertEquals(ComparisonRingScoring.Closer.SIMILAR, ComparisonRingScoring.closer(summary(4.0), summary(4.5)))
        assertNull(ComparisonRingScoring.closer(summary(3.0), summary(8.0, AccuracyComparison.Verdict.NOT_ENOUGH_DATA)))
    }
}
