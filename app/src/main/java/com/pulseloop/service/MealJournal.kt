package com.pulseloop.service

import com.pulseloop.ui.i18n.tr

import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.JournalEntryEntity
import com.pulseloop.data.entity.MealEntryEntity
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * What a logged meal means for the rest of the app. The food log pays off in a ring app when it
 * meets the night after it, so a logged meal tags the journal itself ("Late meal", "Alcohol",
 * "Caffeine after 2 pm") instead of waiting for the user to tick the same thing by hand, and the
 * coach sees the day's intake.
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
            "cerveja|chopp|vinho|caipirinha|cacha[cç]a|espumante)s?\\b",
        RegexOption.IGNORE_CASE,
    )
    private val ALCOHOL_FREE = Regex(
        "\\b(non[- ]?alcoholic|alcohol[- ]free|0[.,]0\\s?%|zero alcohol|sem [aá]lcool)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** From this hour, caffeine counts as "Caffeine after 2 pm". */
    const val CAFFEINE_CUTOFF_HOUR = 14

    // Coffee, caffeinated teas, mate, colas, guaraná and energy drinks, in English and Portuguese.
    // Plain "tea"/"chá" is left out: chamomile and most herbal teas have no caffeine.
    private val CAFFEINE = Regex(
        "\\b(coffee|caf[eé]|cafezinho|pingado|espresso|expresso|cappuccino|latte|macchiato|mocha|" +
            "americano|cold brew|frappuccino|ristretto|cortado|flat white|black tea|green tea|matcha|" +
            "chai|earl grey|ch[aá] (preto|verde|mate)|mate|chimarr[aã]o|terer[eé]|yerba|" +
            "cola|coke|pepsi|guaran[aá]|energy drink|energ[eé]tico|red bull|monster)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val CAFFEINE_FREE = Regex(
        "\\b(decaf|decaffeinated|descafeinado|caffeine[- ]free|sem cafe[ií]na|zero cafe[ií]na)\\b",
        RegexOption.IGNORE_CASE,
    )
    /** "Café da manhã" is breakfast, not coffee; drop it before looking for caffeine. */
    private val BREAKFAST_PT = Regex("caf[eé] da manh[aã]", RegexOption.IGNORE_CASE)

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

    fun isCaffeinated(name: String, notes: String? = null): Boolean {
        val text = BREAKFAST_PT.replace(listOfNotNull(name, notes).joinToString(" "), " ")
        return CAFFEINE.containsMatchIn(text) && !CAFFEINE_FREE.containsMatchIn(text)
    }

    /** 14:00 onwards, through the small hours that still belong to the evening. */
    fun isAfterCaffeineCutoff(timestamp: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val hour = Instant.ofEpochMilli(timestamp).atZone(zone).hour
        return hour >= CAFFEINE_CUTOFF_HOUR || hour < EVENING_ENDS_HOUR
    }

    /** The journal tags one meal implies, keyed by the day they belong to. */
    fun tagsFor(meal: MealEntryEntity, zone: ZoneId = ZoneId.systemDefault()): Map<LocalDate, Set<String>> {
        val tags = buildSet {
            if (isLate(meal.timestamp, zone)) add(JournalInsights.Tag.LATE_MEAL.key)
            if (isAlcohol(meal.name, meal.notes)) add(JournalInsights.Tag.ALCOHOL.key)
            if (isAfterCaffeineCutoff(meal.timestamp, zone) && isCaffeinated(meal.name, meal.notes)) {
                add(JournalInsights.Tag.LATE_CAFFEINE.key)
            }
        }
        if (tags.isEmpty()) return emptyMap()
        // A meal belongs to its own day; one in the small hours to the evening before.
        val day = eveningOf(meal.timestamp, zone)
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
        // A drink in the meal is also a drink in Drinks, when the user tracks them.
        runCatching { DrinksRepository.fromMeal(db, meal, zone) }
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
    val note: String = tr("Food the user logged themselves; totals are only as complete as the log."),
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
