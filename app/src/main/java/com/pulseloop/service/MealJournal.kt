package com.pulseloop.service

import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.JournalEntryEntity
import com.pulseloop.data.entity.MealEntryEntity
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * What a logged meal means for the rest of the app. The food log pays off in a ring app when it
 * meets the night after it, so a logged meal tags the journal itself ("Late meal", "Alcohol")
 * instead of waiting for the user to tick the same thing by hand, and the coach sees the day's
 * intake.
 *
 * Tags are only ever added. A user who unticks one keeps it unticked until another meal of the
 * same kind is logged.
 */
object MealJournal {

    /** A meal at or after this hour counts as late. */
    const val LATE_HOUR = 21

    /** Before this hour a meal still belongs to the previous evening (a 01:00 snack is a late dinner). */
    const val EVENING_ENDS_HOUR = 4

    private val ALCOHOL = Regex(
        "\\b(beer|wine|vodka|whisk(e)?y|gin|rum|tequila|cocktail|sake|cider|prosecco|champagne|" +
            "margarita|mojito|sangria|spritz|aperol|negroni|martini|ipa|lager|stout|bourbon|" +
            "cerveja|chopp|vinho|caipirinha|cacha[cç]a|espumante)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val ALCOHOL_FREE = Regex(
        "\\b(non[- ]?alcoholic|alcohol[- ]free|0[.,]0\\s?%|zero alcohol|sem [aá]lcool)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** The evening a meal belongs to: its own date, or the day before when eaten before [EVENING_ENDS_HOUR]. */
    fun eveningOf(timestamp: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate {
        val local = Instant.ofEpochMilli(timestamp).atZone(zone)
        return if (local.hour < EVENING_ENDS_HOUR) local.toLocalDate().minusDays(1) else local.toLocalDate()
    }

    fun isLate(timestamp: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val hour = Instant.ofEpochMilli(timestamp).atZone(zone).hour
        return hour >= LATE_HOUR || hour < EVENING_ENDS_HOUR
    }

    fun isAlcohol(name: String, notes: String? = null): Boolean {
        val text = listOfNotNull(name, notes).joinToString(" ")
        return ALCOHOL.containsMatchIn(text) && !ALCOHOL_FREE.containsMatchIn(text)
    }

    /** The journal tags one meal implies, keyed by the day they belong to. */
    fun tagsFor(meal: MealEntryEntity, zone: ZoneId = ZoneId.systemDefault()): Map<LocalDate, Set<String>> {
        val tags = buildSet {
            if (isLate(meal.timestamp, zone)) add(JournalInsights.Tag.LATE_MEAL.key)
            if (isAlcohol(meal.name, meal.notes)) add(JournalInsights.Tag.ALCOHOL.key)
        }
        if (tags.isEmpty()) return emptyMap()
        // A daytime drink belongs to its own day; anything in the late window to its evening.
        val day = if (isLate(meal.timestamp, zone)) eveningOf(meal.timestamp, zone)
        else Instant.ofEpochMilli(meal.timestamp).atZone(zone).toLocalDate()
        return mapOf(day to tags)
    }

    /**
     * Call after a new meal is saved, from any path (manual, barcode, photo, coach). Adds the
     * meal's journal tags and switches nutrition tracking on: logging a meal is the opt-in.
     */
    suspend fun afterLog(db: PulseLoopDatabase, meal: MealEntryEntity, zone: ZoneId = ZoneId.systemDefault()) {
        runCatching {
            tagsFor(meal, zone).forEach { (date, tags) ->
                val day = date.atStartOfDay(zone).toInstant().toEpochMilli()
                tags.forEach { tag ->
                    db.journalDao().upsert(JournalEntryEntity(id = JournalEntryEntity.idFor(day, tag), day = day, tag = tag))
                }
            }
        }
        runCatching {
            val goal = db.userGoalDao().get()
            if (goal != null && !goal.nutritionEnabled) db.userGoalDao().upsert(goal.copy(nutritionEnabled = true))
        }
    }
}

/**
 * Today's food as the coach sees it. Null when nothing has been logged recently, so a user who
 * doesn't track food never gets a check-in about it.
 */
@Serializable
data class NutritionContext(
    val caloriesEatenToday: Int,
    val proteinGToday: Int,
    val mealsToday: Int,
    /** Local "HH:mm" of the most recent meal today, if any. */
    val lastMealToday: String? = null,
    val calorieGoal: Int? = null,
    /** Whether yesterday evening had a meal at or after 21:00. */
    val lateMealLastNight: Boolean = false,
    val alcoholLastNight: Boolean = false,
    val note: String = "Food the user logged themselves; totals are only as complete as the log.",
) {
    companion object {
        suspend fun build(db: PulseLoopDatabase, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): NutritionContext? =
            runCatching {
                val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
                fun start(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()
                val todays = db.mealEntryDao().byDay(start(today))
                val yesterdays = db.mealEntryDao().byDay(start(today.minusDays(1)))
                // Meals after midnight of yesterday's evening are stored under today.
                val lastEvening = (yesterdays + todays).filter { MealJournal.eveningOf(it.timestamp, zone) == today.minusDays(1) }
                if (todays.isEmpty() && lastEvening.isEmpty()) return@runCatching null
                val goal = db.userGoalDao().get()
                NutritionContext(
                    caloriesEatenToday = todays.sumOf { it.calories }.toInt(),
                    proteinGToday = todays.sumOf { it.proteinG }.toInt(),
                    mealsToday = todays.size,
                    lastMealToday = todays.maxByOrNull { it.timestamp }?.let {
                        Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalTime().withSecond(0).withNano(0).toString()
                    },
                    calorieGoal = goal?.takeIf { it.nutritionEnabled }?.intakeCalories?.toInt(),
                    lateMealLastNight = lastEvening.any { MealJournal.isLate(it.timestamp, zone) },
                    alcoholLastNight = lastEvening.any { MealJournal.isAlcohol(it.name, it.notes) },
                )
            }.getOrNull()
    }
}
