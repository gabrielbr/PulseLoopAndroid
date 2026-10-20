package com.pulseloop.service

import com.pulseloop.data.entity.UserGoalEntity
import com.pulseloop.service.GoalSync.Field
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings goals following the coach goal's plan, and letting go after a manual edit. */
class GoalSyncTest {

    private fun plan(kind: String, kcal: Int? = null, protein: Int? = null) = GoalPlan.Plan(
        kind = kind, description = kind, startDate = "2026-10-01", daysElapsed = 0,
        suggestedIntakeKcal = kcal, proteinTargetG = protein,
    )

    @Test
    fun `a weight plan sets calories and splits the macros`() {
        val t = GoalSync.targets(plan("lose_weight", kcal = 1847, protein = 132), null)
        assertEquals(1850.0, t.getValue(Field.CALORIES), 0.01)
        assertEquals(132.0, t.getValue(Field.PROTEIN), 0.01)
        assertEquals(62.0, t.getValue(Field.FAT), 0.01) // 30% of 1,850 / 9
        // (1850 - 132*4 - 62*9) / 4 = 191
        assertEquals(191.0, t.getValue(Field.CARBS), 0.01)
    }

    @Test
    fun `habit goals set their own field only`() {
        assertEquals(mapOf(Field.SLEEP to 450.0), GoalSync.targets(plan("sleep_better"), 7.5))
        assertEquals(mapOf(Field.STEPS to 10000.0), GoalSync.targets(plan("move_more"), 10000.0))
        assertEquals(mapOf(Field.WORKOUTS to 4.0), GoalSync.targets(plan("build_muscle"), 4.0))
    }

    @Test
    fun `merge writes followed fields and turns nutrition on`() {
        val m = GoalSync.merge(
            UserGoalEntity(intakeCalories = 2000.0),
            mapOf(Field.CALORIES to 1850.0, Field.PROTEIN to 132.0),
            lastApplied = emptyMap(),
            following = Field.entries.toSet(),
        )
        assertEquals(1850.0, m.goal.intakeCalories!!, 0.01)
        assertEquals(132.0, m.goal.intakeProteinG!!, 0.01)
        assertTrue(m.goal.nutritionEnabled)
        assertEquals(setOf(Field.CALORIES, Field.PROTEIN), m.changed.keys)
    }

    @Test
    fun `a field edited by hand stops following`() {
        val m = GoalSync.merge(
            UserGoalEntity(intakeCalories = 2100.0, intakeProteinG = 132.0),
            mapOf(Field.CALORIES to 1800.0, Field.PROTEIN to 130.0),
            lastApplied = mapOf(Field.CALORIES to 1850.0, Field.PROTEIN to 132.0),
            following = Field.entries.toSet(),
        )
        assertEquals(2100.0, m.goal.intakeCalories!!, 0.01) // the user's 2,100 stands
        assertFalse(Field.CALORIES in m.following)
        assertEquals(130.0, m.goal.intakeProteinG!!, 0.01) // untouched field keeps following
        assertTrue(Field.PROTEIN in m.following)
    }

    @Test
    fun `a field the user took over is left alone`() {
        val m = GoalSync.merge(
            UserGoalEntity(steps = 8000),
            mapOf(Field.STEPS to 12000.0),
            lastApplied = emptyMap(),
            following = emptySet(),
        )
        assertEquals(8000, m.goal.steps)
        assertTrue(m.changed.isEmpty())
    }
}
