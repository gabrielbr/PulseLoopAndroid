package com.pulseloop.service

import com.pulseloop.ui.i18n.tr
import com.pulseloop.ui.i18n.trf

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

    enum class Tag(val key: String, private val labelEn: String) {
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
        val label: String get() = tr(labelEn)

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
                hrvPct?.let { add("HRV ${abs(it)}% ${if (it < 0) tr("lower") else tr("higher")}") }
                restingHr?.let { add(trf("resting heart rate {0} bpm {1}", abs(it), if (it > 0) tr("higher") else tr("lower"))) }
                sleepMinutes?.let { add(trf("{0} min {1} sleep", abs(it), if (it < 0) tr("less") else tr("more"))) }
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

        // Every tag × outcome comparison, with its size and its p-value; the corrected set of
        // survivors is decided across all of them at once.
        class Test(val tag: Tag, val outcome: Int, val diff: Double, val p: Double)
        val tests = mutableListOf<Test>()
        val counts = mutableMapOf<Tag, Pair<Int, Int>>()
        for (tag in used) {
            val (with, without) = pairs.partition { (day, _) -> tagsByDay[day]?.contains(tag.key) == true }
            val w = with.map { it.second }
            val wo = without.map { it.second }
            counts[tag] = w.size to wo.size
            if (w.size < MIN_NIGHTS || wo.size < MIN_NIGHTS) continue
            fun values(list: List<NightSummary>, f: (NightSummary) -> Double?) = list.mapNotNull(f)
            val outcomes: List<(NightSummary) -> Double?> = listOf(
                { it.asleepMinutes.toDouble().takeIf { m -> m > 0 } },
                { it.hrv },
                { it.restingHr },
            )
            outcomes.forEachIndexed { o, f ->
                val a = values(w, f); val b = values(wo, f)
                if (a.size < MIN_NIGHTS || b.size < MIN_NIGHTS) return@forEachIndexed
                val diff = if (o == 1) {
                    val base = b.average()
                    if (base <= 0) return@forEachIndexed
                    (a.average() - base) / base * 100
                } else a.average() - b.average()
                tests += Test(tag, o, diff, Stats.welchP(a, b))
            }
        }
        val significant = Stats.benjaminiHochberg(tests.map { it.p }, FALSE_DISCOVERY_RATE)
        val kept = tests.filterIndexed { i, _ -> significant[i] }

        return used.map { tag ->
            val (nw, nwo) = counts.getValue(tag)
            fun diff(o: Int, minimum: Double) = kept.firstOrNull { it.tag == tag && it.outcome == o }?.diff
                ?.takeIf { abs(it) >= minimum }?.roundToInt()
            Effect(
                tag, nw, nwo,
                sleepMinutes = diff(0, 15.0),
                hrvPct = diff(1, 5.0),
                restingHr = diff(2, 2.0),
            )
        }.sortedWith(compareByDescending<Effect> { it.hasEffect }.thenByDescending { strength(it) })
    }

    /** Comparisons [effects] runs for these tags and nights: what the correction is spread over. */
    fun comparisonCount(tagsByDay: Map<LocalDate, Set<String>>): Int =
        tagsByDay.values.flatten().toSet().mapNotNull { Tag.of(it) }.size * 3

    /** Benjamini-Hochberg false-discovery rate for "what affects you". */
    const val FALSE_DISCOVERY_RATE = 0.10

    private fun strength(e: Effect): Double =
        abs(e.hrvPct ?: 0) / 5.0 + abs(e.restingHr ?: 0) / 2.0 + abs(e.sleepMinutes ?: 0) / 15.0
}
