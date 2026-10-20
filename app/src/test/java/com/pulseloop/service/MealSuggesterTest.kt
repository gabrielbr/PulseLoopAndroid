package com.pulseloop.service

import com.pulseloop.data.entity.FoodPreferenceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/** The next-meal idea: within budget, Brazilian staples, and agreed preferences winning when they fit. */
class MealSuggesterTest {

    private fun inputs(slot: String, eaten: Double, prefs: List<FoodPreferenceEntity> = emptyList(), protein: Double = 0.0) =
        MealSuggester.Inputs(slot = slot, targetKcal = 2000.0, eatenKcal = eaten, proteinTargetG = 130.0, proteinEatenG = protein, preferences = prefs)

    @Test
    fun `lunch is a rice-and-beans plate inside the slot budget`() {
        val s = MealSuggester.suggest(inputs("lunch", eaten = 500.0))
        val names = s.items.map { it.name }
        assertTrue(names.toString(), names.contains("Arroz branco") && names.contains("Feijão"))
        assertTrue(s.items.any { it.proteinG >= 25 })
        assertTrue(s.totalKcal <= 700) // 35% of 2,000
        assertEquals(1500 - s.totalKcal, s.leavesKcal)
    }

    @Test
    fun `little left means a fruit, not a plate`() {
        val s = MealSuggester.suggest(inputs("dinner", eaten = 1950.0))
        assertTrue(s.items.isEmpty())
        assertTrue(s.note!!.contains("50 kcal left"))
    }

    @Test
    fun `an agreed banana replaces vegetables at dinner when it fits`() {
        val pref = FoodPreferenceEntity(
            id = FoodPreferenceEntity.idFor("dinner", "banana", "prefer"), slot = "dinner", item = "banana",
            action = "prefer", insteadOf = "vegetables", timesAgreed = 3,
        )
        val s = MealSuggester.suggest(inputs("dinner", eaten = 1200.0, prefs = listOf(pref)))
        val names = s.items.map { it.name }
        assertTrue(names.contains("Banana"))
        assertFalse(names.toString(), names.any { it == "Legumes cozidos" || it == "Salada verde com tomate" })
        assertEquals(listOf("banana at dinner instead of vegetables"), s.fromPreferences)
    }

    @Test
    fun `a preference that doesn't fit the budget is skipped`() {
        val pref = FoodPreferenceEntity(
            id = "x", slot = "snack", item = "açaí bowl", action = "prefer", kcal = 500.0, proteinG = 5.0,
        )
        val s = MealSuggester.suggest(inputs("snack", eaten = 1500.0, prefs = listOf(pref)))
        assertTrue(s.fromPreferences.isEmpty())
        assertTrue(s.totalKcal <= 240) // 12% of 2,000
    }

    @Test
    fun `an avoided role never appears`() {
        val avoid = FoodPreferenceEntity(id = "y", slot = "lunch", item = "beans", action = "avoid")
        val s = MealSuggester.suggest(inputs("lunch", eaten = 400.0, prefs = listOf(avoid)))
        assertFalse(s.items.any { it.name == "Feijão" })
    }

    @Test
    fun `slots follow the clock and the card waits for a third of the day`() {
        assertEquals("breakfast", MealSuggester.slotAt(LocalTime.of(7, 30)))
        assertEquals("lunch", MealSuggester.slotAt(LocalTime.of(12, 0)))
        assertEquals("snack", MealSuggester.slotAt(LocalTime.of(16, 0)))
        assertEquals("dinner", MealSuggester.slotAt(LocalTime.of(20, 0)))
        assertFalse(MealSuggester.shouldShow(2000.0, 500.0))
        assertTrue(MealSuggester.shouldShow(2000.0, 700.0))
    }
}
