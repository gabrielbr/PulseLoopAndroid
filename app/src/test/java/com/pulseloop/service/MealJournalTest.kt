package com.pulseloop.service

import com.pulseloop.data.entity.MealEntryEntity
import com.pulseloop.notifications.MealReminder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

/** Logged meals tagging the journal, and when the meal reminder is due. */
class MealJournalTest {

    private val zone = ZoneOffset.UTC
    private val day = LocalDate.of(2026, 10, 19)

    private fun meal(name: String, at: LocalDateTime) = MealEntryEntity(
        date = at.toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli(),
        timestamp = at.toInstant(zone).toEpochMilli(),
        name = name,
        calories = 500.0,
    )

    @Test
    fun `a dinner at 21h30 is a late meal on its own day`() {
        val tags = MealJournal.tagsFor(meal("Pasta", day.atTime(21, 30)), zone)
        assertEquals(mapOf(day to setOf("late_meal")), tags)
    }

    @Test
    fun `a 01h00 snack is a late meal of the evening before`() {
        val tags = MealJournal.tagsFor(meal("Toast", day.plusDays(1).atTime(1, 0)), zone)
        assertEquals(mapOf(day to setOf("late_meal")), tags)
    }

    @Test
    fun `lunch tags nothing`() {
        assertTrue(MealJournal.tagsFor(meal("Chicken salad", day.atTime(12, 30)), zone).isEmpty())
    }

    @Test
    fun `drinks are recognised, alcohol-free ones are not`() {
        assertEquals(mapOf(day to setOf("alcohol")), MealJournal.tagsFor(meal("2 glasses of red wine", day.atTime(19, 0)), zone))
        assertEquals(
            mapOf(day to setOf("late_meal", "alcohol")),
            MealJournal.tagsFor(meal("Caipirinha", day.atTime(22, 0)), zone),
        )
        assertFalse(MealJournal.isAlcohol("Non-alcoholic beer"))
        assertFalse(MealJournal.isAlcohol("Ginger tea"))
        assertFalse(MealJournal.isAlcohol("Rumbled eggs"))
    }

    @Test
    fun `the lunch reminder waits for 13h30 and stays quiet once lunch is logged`() {
        assertNull(MealReminder.due(LocalTime.of(13, 0), emptyList()))
        assertEquals(MealReminder.Slot.LUNCH, MealReminder.due(LocalTime.of(13, 45), listOf(LocalTime.of(8, 0))))
        assertNull(MealReminder.due(LocalTime.of(13, 45), listOf(LocalTime.of(12, 15))))
        assertNull(MealReminder.due(LocalTime.of(16, 0), emptyList()))
    }

    @Test
    fun `the dinner reminder counts anything since 17h00`() {
        assertEquals(MealReminder.Slot.DINNER, MealReminder.due(LocalTime.of(20, 40), listOf(LocalTime.of(12, 0))))
        assertNull(MealReminder.due(LocalTime.of(20, 40), listOf(LocalTime.of(19, 30))))
    }
}
