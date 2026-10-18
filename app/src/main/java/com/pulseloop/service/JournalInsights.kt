package com.pulseloop.service

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Journal tags and "what affects you" (docs/health-app-feature-research.md; WHOOP Journal, Oura
 * tags). The user tags a day; the effect is read from the **night that follows** it, the one whose
 * waking day is the next date, compared with nights after untagged days.
 *
 * Deliberately conservative: an effect is only shown with at least [MIN_NIGHTS] nights on each
 * side and when it clears a minimum size, because two noisy nights would otherwise "prove"
 * anything. It is a pattern in the user's own data, not cause and effect, and the copy says so.
 */
object JournalInsights {

    const val MIN_NIGHTS = 3

    enum class Tag(val key: String, val label: String) {
        ALCOHOL("alcohol", "Alcohol"),
        LATE_CAFFEINE("late_caffeine", "Caffeine after 2 pm"),
        LATE_MEAL("late_meal", "Late meal"),
        HARD_WORKOUT("hard_workout", "Hard workout"),
        STRESS("stress", "Stressful day"),
        SCREENS("screens", "Screens in bed"),
        UNWELL("unwell", "Feeling unwell"),
        TRAVEL("travel", "Travel"),
        RELAXATION("relaxation", "Meditation or relaxing"),
        NAP("nap", "Daytime nap");

        companion object {
            fun of(key: String) = entries.firstOrNull { it.key == key }
        }
    }

    data class Effect(
        val tag: Tag,
        val nightsWith: Int,
        val nightsWithout: Int,
        /** Difference in minutes asleep (with − without), when large enough to mention. */
        val sleepMinutes: Int?,
        /** Relative HRV difference in percent, when large enough to mention. */
        val hrvPct: Int?,
        /** Resting HR difference in bpm, when large enough to mention. */
        val restingHr: Int?,
    ) {
        val hasEffect: Boolean get() = sleepMinutes != null || hrvPct != null || restingHr != null

        /** e.g. "HRV 18% lower, resting heart rate 4 bpm higher on the night after (5 nights)". */
        fun summary(): String {
            val parts = buildList {
                hrvPct?.let { add("HRV ${abs(it)}% ${if (it < 0) "lower" else "higher"}") }
                restingHr?.let { add("resting heart rate ${abs(it)} bpm ${if (it > 0) "higher" else "lower"}") }
                sleepMinutes?.let { add("${abs(it)} min ${if (it < 0) "less" else "more"} sleep") }
            }
            val what = if (parts.isEmpty()) "no clear effect yet" else parts.joinToString(", ")
            return "$what on the night after ($nightsWith nights)"
        }
    }

    /**
     * One [Effect] per tag the user has used, strongest first; tags with too few nights on either
     * side are returned with no effect so the UI can say how many more it needs.
     */
    fun effects(tagsByDay: Map<LocalDate, Set<String>>, nights: List<NightSummary>): List<Effect> {
        val nightByDay = nights.associateBy { it.day }
        // Only days followed by a recorded night can say anything.
        val pairs = nightByDay.keys.map { wakeDay -> wakeDay.minusDays(1) to nightByDay.getValue(wakeDay) }
        val used = tagsByDay.values.flatten().toSet().mapNotNull { Tag.of(it) }
        return used.map { tag ->
            val (with, without) = pairs.partition { (day, _) -> tagsByDay[day]?.contains(tag.key) == true }
            val w = with.map { it.second }
            val wo = without.map { it.second }
            if (w.size < MIN_NIGHTS || wo.size < MIN_NIGHTS) {
                Effect(tag, w.size, wo.size, null, null, null)
            } else {
                fun avg(list: List<NightSummary>, f: (NightSummary) -> Double?) = list.mapNotNull(f).takeIf { it.size >= MIN_NIGHTS }?.average()
                val sleepD = avg(w) { it.asleepMinutes.toDouble().takeIf { m -> m > 0 } }?.let { a -> avg(wo) { it.asleepMinutes.toDouble().takeIf { m -> m > 0 } }?.let { a - it } }
                val hrvW = avg(w) { it.hrv }
                val hrvWo = avg(wo) { it.hrv }
                val hrvPct = if (hrvW != null && hrvWo != null && hrvWo > 0) (hrvW - hrvWo) / hrvWo * 100 else null
                val rhrD = avg(w) { it.restingHr }?.let { a -> avg(wo) { it.restingHr }?.let { a - it } }
                Effect(
                    tag, w.size, wo.size,
                    sleepMinutes = sleepD?.takeIf { abs(it) >= 15 }?.roundToInt(),
                    hrvPct = hrvPct?.takeIf { abs(it) >= 5 }?.roundToInt(),
                    restingHr = rhrD?.takeIf { abs(it) >= 2 }?.roundToInt(),
                )
            }
        }.sortedWith(compareByDescending<Effect> { it.hasEffect }.thenByDescending { strength(it) })
    }

    private fun strength(e: Effect): Double =
        abs(e.hrvPct ?: 0) / 5.0 + abs(e.restingHr ?: 0) / 2.0 + abs(e.sleepMinutes ?: 0) / 15.0
}
