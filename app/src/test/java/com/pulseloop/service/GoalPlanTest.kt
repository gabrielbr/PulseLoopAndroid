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

    private fun habit(kind: String, target: Double? = null) = CoachGoalEntity(
        kind = kind, description = kind, startDate = start, targetValue = target,
    )

    @Test
    fun `get stronger is tracked by workouts, protein and a small surplus`() {
        val p = GoalPlan.compute(
            GoalPlan.Inputs(habit("build_muscle", 4.0), now = start, currentWeightKg = 75.0, leanMassKg = 60.0,
                avgBurnKcal = 2500.0, avgIntakeKcal = 2300.0, loggedDays = 6, avgProteinG = 90.0, workoutsPerWeek = 2.0),
            zone,
        )
        assertEquals(2750, p.suggestedIntakeKcal)
        val levers = p.levers.associateBy { it.name }
        assertEquals("short", levers.getValue("workouts").status)
        assertEquals(4.0, levers.getValue("workouts").target!!, 0.01)
        assertEquals("short", levers.getValue("protein").status) // 90 of 120 g
        assertEquals("short", levers.getValue("calories_eaten").status)
        assertEquals("Focus: workouts (2 of 4 per week)", GoalPlan.focusLine(p))
    }

    @Test
    fun `sleep better uses its own hours target and bedtime spread`() {
        val p = GoalPlan.compute(
            GoalPlan.Inputs(habit("sleep_better", 7.5), now = start, currentWeightKg = null, avgSleepMin = 400.0, bedtimeSpreadMin = 55.0),
            zone,
        )
        assertNull(p.suggestedIntakeKcal)
        val levers = p.levers.associateBy { it.name }
        assertEquals(7.5, levers.getValue("sleep").target!!, 0.01)
        assertEquals("short", levers.getValue("sleep").status)
        assertEquals("short", levers.getValue("bedtime_regularity").status)
    }

    @Test
    fun `reduce stress watches HRV rising and resting HR falling`() {
        val p = GoalPlan.compute(
            GoalPlan.Inputs(habit("reduce_stress"), now = start, currentWeightKg = null, hrv7 = 45.0, hrvBase = 40.0, restingHr7 = 58.0, restingHrBase = 56.0),
            zone,
        )
        val levers = p.levers.associateBy { it.name }
        assertEquals("on_target", levers.getValue("hrv").status)
        assertEquals("short", levers.getValue("resting_hr").status)
    }

    @Test
    fun `every intent has levers`() {
        for (kind in GoalPlan.ALL_KINDS) {
            val p = GoalPlan.compute(GoalPlan.Inputs(habit(kind), now = start, currentWeightKg = 80.0), zone)
            assertTrue(kind, p.levers.isNotEmpty())
        }
    }
}
