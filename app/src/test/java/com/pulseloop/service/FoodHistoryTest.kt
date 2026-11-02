package com.pulseloop.service

import com.pulseloop.data.entity.MealEntryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Suggesting a food again from what the user logged before. */
class FoodHistoryTest {
    private fun meal(name: String, kcal: Double, at: Long) =
        MealEntryEntity(date = 0, timestamp = at, name = name, calories = kcal)

    @Test
    fun oneSuggestionPerFoodWithItsNewestNumbers() {
        val history = listOf(
            meal("Pão de queijo", 120.0, at = 1),
            meal("pao de queijo ", 150.0, at = 3),
            meal("Pão francês", 140.0, at = 2),
        )
        val s = FoodHistory.suggest(history, "pao")
        assertEquals(2, s.size)
        // Accents and case don't split a food; the newest log's numbers come back.
        assertEquals(150.0, s.first().entry.calories, 0.0)
        assertEquals(2, s.first().count)
    }

    @Test
    fun aNameThatStartsWithTheTextRanksAboveAWordInside() {
        val history = listOf(
            meal("Grilled chicken salad", 400.0, at = 5),
            meal("Chicken and rice", 600.0, at = 1),
        )
        val s = FoodHistory.suggest(history, "chick")
        assertEquals(listOf("Chicken and rice", "Grilled chicken salad"), s.map { it.entry.name })
    }

    @Test
    fun theMostLoggedComesFirstWithinARank() {
        val history = listOf(
            meal("Yogurt", 100.0, at = 9),
            meal("Yoghurt bowl", 300.0, at = 1),
            meal("Yoghurt bowl", 300.0, at = 2),
        )
        assertEquals("Yoghurt bowl", FoodHistory.suggest(history, "yo").first().entry.name)
    }

    @Test
    fun nothingForOneLetterAnExactMatchOrAZeroCalorieLog() {
        val history = listOf(meal("Banana", 90.0, at = 1), meal("Bacon", 0.0, at = 2))
        assertTrue(FoodHistory.suggest(history, "b").isEmpty())
        assertTrue(FoodHistory.suggest(history, "banana").isEmpty())
        assertEquals(listOf("Banana"), FoodHistory.suggest(history, "ba").map { it.entry.name })
    }
}
