package com.pulseloop.service

import com.pulseloop.data.entity.CoachGoalEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** The goal arithmetic the coach plans with. */
class GoalPlanTest {

    private val zone = ZoneOffset.UTC
    private val start = LocalDate.of(2026, 10, 1).atStartOfDay(zone).toInstant().toEpochMilli()
    private val day = GoalPlan.DAY_MS

    private fun goal(target: Double?, days: Long) = CoachGoalEntity(
        kind = "lose_weight", description = "lose 5 kg in 60 days",
        startWeightKg = 85.0, targetWeightKg = target,
        startDate = start, targetDate = start + days * day,
    )

    @Test
    fun `progress and the pace the date needs`() {
        val p = GoalPlan.compute(
            GoalPlan.Inputs(goal(80.0, 60), now = start + 18 * day, currentWeightKg = 83.0, avgBurnKcal = 2400.0, sex = "male", bmrKcal = 1750.0),
            zone,
        )
        assertEquals(-2.0, p.kgDone!!, 0.01)
        assertEquals(-3.0, p.kgToGo!!, 0.01)
        assertEquals(42, p.daysLeft)
        assertEquals(-0.5, p.requiredKgPerWeek!!, 0.01)
        assertEquals("moderate", p.pace)
        // 0.5 kg/week ≈ 550 kcal/day under 2,400.
        assertEquals(1850, p.suggestedIntakeKcal)
    }

    @Test
    fun `an aggressive date is flagged and intake never drops below the floor`() {
        val p = GoalPlan.compute(
            GoalPlan.Inputs(goal(75.0, 30), now = start, currentWeightKg = 85.0, avgBurnKcal = 2200.0, sex = "female", bmrKcal = 1500.0),
            zone,
        )
        assertEquals("unrealistic", p.pace)
        assertEquals(1500, p.suggestedIntakeKcal)
        assertEquals(1500, p.intakeFloorKcal)
        assertTrue(p.notes.any { it.contains("1% of body weight") })
    }

    @Test
    fun `the scale trend needs three weigh-ins across a week`() {
        assertNull(GoalPlan.trendKgPerWeek(listOf(start to 85.0, start + day to 84.8)))
        val trend = GoalPlan.trendKgPerWeek(listOf(start to 85.0, start + 7 * day to 84.5, start + 14 * day to 84.0))!!
        assertEquals(-0.5, trend, 0.001)
    }

    @Test
    fun `on track when the scale moves at least 80 percent of the needed pace`() {
        val weighIns = listOf(start to 85.0, start + 7 * day to 84.5, start + 14 * day to 84.0)
        val p = GoalPlan.compute(
            GoalPlan.Inputs(goal(80.0, 70), now = start + 14 * day, currentWeightKg = 84.0, weighIns = weighIns),
            zone,
        )
        assertEquals(true, p.onTrack)
    }

    @Test
    fun `protein follows lean mass when the scale knows it`() {
        val p = GoalPlan.compute(GoalPlan.Inputs(goal(80.0, 60), now = start, currentWeightKg = 85.0, leanMassKg = 66.0), zone)
        assertEquals(132, p.proteinTargetG)
    }
}
