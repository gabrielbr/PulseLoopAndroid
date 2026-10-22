package com.pulseloop.health.source

import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import com.pulseloop.health.accuracy.ExportUnit
import com.pulseloop.ring.SleepStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Health Connect as the data source: how its records become the app's (docs/health-connect-source.md). */
class HealthConnectSourceTest {

    private val t0 = 1_700_000_000_000L / 60_000L * 60_000L
    private fun min(n: Int) = t0 + n * 60_000L

    @Test
    fun `stages map to the app's, out of bed and awake in bed are awake, plain sleeping is unknown`() {
        assertEquals(SleepStage.DEEP, HealthConnectSource.stage(SleepSessionRecord.STAGE_TYPE_DEEP))
        assertEquals(SleepStage.REM, HealthConnectSource.stage(SleepSessionRecord.STAGE_TYPE_REM))
        assertEquals(SleepStage.LIGHT, HealthConnectSource.stage(SleepSessionRecord.STAGE_TYPE_LIGHT))
        assertEquals(SleepStage.AWAKE, HealthConnectSource.stage(SleepSessionRecord.STAGE_TYPE_OUT_OF_BED))
        assertEquals(SleepStage.AWAKE, HealthConnectSource.stage(SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED))
        assertEquals(SleepStage.UNKNOWN, HealthConnectSource.stage(SleepSessionRecord.STAGE_TYPE_SLEEPING))
    }

    @Test
    fun `a night becomes one stage per minute, gaps between stages are awake`() {
        val stages = listOf(
            HealthConnectSource.Stage(min(0), min(3), SleepSessionRecord.STAGE_TYPE_LIGHT),
            HealthConnectSource.Stage(min(5), min(8), SleepSessionRecord.STAGE_TYPE_DEEP),
        )
        val m = HealthConnectSource.minuteStages(min(0), min(8), stages)!!
        assertEquals(8, m.size)
        assertEquals(List(3) { SleepStage.LIGHT } + List(2) { SleepStage.AWAKE } + List(3) { SleepStage.DEEP }, m)
    }

    @Test
    fun `a minute split between stages takes the one covering most of it`() {
        val stages = listOf(
            HealthConnectSource.Stage(min(0), min(1) + 40_000, SleepSessionRecord.STAGE_TYPE_REM),
            HealthConnectSource.Stage(min(1) + 40_000, min(2), SleepSessionRecord.STAGE_TYPE_LIGHT),
        )
        assertEquals(listOf(SleepStage.REM, SleepStage.REM), HealthConnectSource.minuteStages(min(0), min(2), stages))
    }

    @Test
    fun `a night with no stages is asleep throughout, an empty or over-long one is dropped`() {
        assertEquals(List(90) { SleepStage.UNKNOWN }, HealthConnectSource.minuteStages(min(0), min(90), emptyList()))
        assertNull(HealthConnectSource.minuteStages(min(0), min(0), emptyList()))
        assertNull(HealthConnectSource.minuteStages(min(0), min(25 * 60), emptyList()))
    }

    @Test
    fun `workout types map back to the app's`() {
        assertEquals("run", HealthConnectSource.workoutType(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL))
        assertEquals("cycle", HealthConnectSource.workoutType(ExerciseSessionRecord.EXERCISE_TYPE_BIKING))
        assertEquals("walk", HealthConnectSource.workoutType(ExerciseSessionRecord.EXERCISE_TYPE_WALKING))
        assertEquals("sport", HealthConnectSource.workoutType(ExerciseSessionRecord.EXERCISE_TYPE_TENNIS))
    }

    @Test
    fun `the first pass reads 30 days, later ones overlap the last by 3`() {
        val now = 100L * 86_400_000L
        assertEquals(now - 30L * 86_400_000L, HealthConnectSource.window(null, now).first)
        assertEquals(now - 4L * 86_400_000L, HealthConnectSource.window(now - 86_400_000L, now).first)
        // A long gap never reads further back than Health Connect serves.
        assertEquals(now - 30L * 86_400_000L, HealthConnectSource.window(now - 60L * 86_400_000L, now).first)
    }

    @Test
    fun `while on, every exported type is held back for all time`() {
        val ex = HealthConnectSource.exportExclusions()
        assertEquals(ExportUnit.entries.toSet(), ex.map { it.unit }.toSet())
        assertTrue(ex.all { it.until == null && it.overlaps(0L, 0L) && it.overlaps(Long.MAX_VALUE - 1, Long.MAX_VALUE) })
    }

    @Test
    fun `imported workouts are recognised by id`() {
        assertTrue(HealthConnectSource.isImportedWorkout(HealthConnectSource.WORKOUT_ID_PREFIX + "abc"))
        assertTrue(!HealthConnectSource.isImportedWorkout("2b8c-uuid"))
    }
}
