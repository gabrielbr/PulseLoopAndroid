package com.pulseloop.service

import com.pulseloop.data.entity.MealEntryEntity
import java.text.Normalizer

/**
 * Foods the user has logged before, offered again as they type a meal's name.
 *
 * One suggestion per food (names compared without case, accents or extra spaces), carrying the
 * numbers from its most recent log, so a portion the user corrected last time is the one they
 * get back. A name that starts with what was typed ranks first, then a name with a word that
 * starts with it; within each, the food logged most often, then most recently.
 */
object FoodHistory {
    data class Suggestion(
        /** The newest log of this food: its name as last written and its numbers. */
        val entry: MealEntryEntity,
        /** How many times it has been logged. */
        val count: Int,
    )

    /** Typed text shorter than this suggests nothing: one letter matches half the history. */
    const val MIN_QUERY = 2

    fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase().trim(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("\\s+"), " ")

    fun suggest(history: List<MealEntryEntity>, query: String, limit: Int = 5): List<Suggestion> {
        val q = normalize(query)
        if (q.length < MIN_QUERY) return emptyList()
        val foods = history
            .filter { it.name.isNotBlank() && it.calories > 0 }
            .groupBy { normalize(it.name) }
        return foods.mapNotNull { (key, logs) ->
            // Typed in full already: nothing to offer but the same name.
            if (key == q) return@mapNotNull null
            val rank = when {
                key.startsWith(q) -> 0
                key.split(' ', ',', '(', '-', '/').any { it.startsWith(q) } -> 1
                else -> return@mapNotNull null
            }
            Triple(rank, logs.maxBy { it.timestamp }, logs.size)
        }
            .sortedWith(compareBy<Triple<Int, MealEntryEntity, Int>> { it.first }
                .thenByDescending { it.third }
                .thenByDescending { it.second.timestamp })
            .take(limit)
            .map { Suggestion(it.second, it.third) }
    }
}
