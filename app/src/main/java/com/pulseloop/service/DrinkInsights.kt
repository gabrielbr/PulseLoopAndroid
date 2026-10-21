package com.pulseloop.service

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.roundToInt

/*
 * Drinks (docs/drinks-plan.md): what was drunk, against the user's own plan, and what it did to
 * their nights. Everything here is pure so it can be tested without a device. Alcohol is counted
 * in grams; a "standard drink" is the user's chosen size (10 g by default, WHO's).
 */

/** A drink as logged, with typical Brazilian and international sizes. */
/** [carbsG] per drink, rough label values, so a drink logged from the Drinks card fills the meal log's macros too. */
enum class DrinkType(val key: String, val label: String, val volumeMl: Double, val abvPct: Double, val kcal: Double, val carbsG: Double = 0.0) {
    BEER("beer", "Beer (can)", 350.0, 5.0, 150.0, 12.0),
    BEER_600("beer_600", "Beer (600 ml)", 600.0, 5.0, 255.0, 21.0),
    DRAFT("draft", "Chopp", 300.0, 5.0, 130.0, 11.0),
    WINE("wine", "Wine", 150.0, 12.0, 125.0, 4.0),
    SPIRIT("spirit", "Spirit (shot)", 40.0, 40.0, 95.0),
    CAIPIRINHA("caipirinha", "Caipirinha", 50.0, 40.0, 240.0, 25.0),
    COCKTAIL("cocktail", "Cocktail", 50.0, 40.0, 200.0, 15.0),
    OTHER("other", "Cider / other", 330.0, 4.5, 140.0, 12.0);

    val grams: Double get() = DrinkInsights.grams(volumeMl, abvPct)

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: OTHER

        private val PATTERNS = listOf(
            CAIPIRINHA to Regex("\\b(caipirinha|caipiroska|caipivodka)s?\\b", RegexOption.IGNORE_CASE),
            COCKTAIL to Regex("\\b(cocktail|drinque|margarita|mojito|spritz|aperol|negroni|martini|gin t[oô]nica|gin tonic|sangria)s?\\b", RegexOption.IGNORE_CASE),
            DRAFT to Regex("\\b(chopp|chope|draft|pint)s?\\b", RegexOption.IGNORE_CASE),
            BEER_600 to Regex("\\b600\\s?ml\\b|\\bgarrafa\\b", RegexOption.IGNORE_CASE),
            BEER to Regex("\\b(beer|cerveja|ipa|lager|stout|pilsen|latinha|long neck)s?\\b", RegexOption.IGNORE_CASE),
            WINE to Regex("\\b(wine|vinho|prosecco|champagne|espumante|ros[eé])s?\\b", RegexOption.IGNORE_CASE),
            SPIRIT to Regex("\\b(vodka|whisk(e)?y|gin|rum|tequila|cacha[cç]a|bourbon|shot|dose|conhaque|brandy)s?\\b", RegexOption.IGNORE_CASE),
        )

        /** The closest type for a free-text drink ("2 taças de vinho" → WINE). */
        fun guess(text: String): DrinkType {
            val beer = PATTERNS.first { it.first == BEER }.second.containsMatchIn(text)
            for ((type, re) in PATTERNS) {
                if (type == BEER_600 && !beer) continue
                if (re.containsMatchIn(text)) return type
            }
            return OTHER
        }

        private val WORD_NUMBERS = mapOf(
            "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
            "um" to 1, "uma" to 1, "dois" to 2, "duas" to 2, "três" to 3, "tres" to 3, "quatro" to 4,
            "cinco" to 5, "seis" to 6,
        )

        /** How many drinks a free-text entry names ("3 beers", "duas taças"), 1 when it doesn't say. */
        fun count(text: String): Int {
            val t = text.trim().lowercase()
            Regex("^(\\d{1,2})\\s*(x\\b)?").find(t)?.let { return it.groupValues[1].toInt().coerceIn(1, 12) }
            val first = t.split(Regex("\\s+")).firstOrNull()
            return WORD_NUMBERS[first] ?: 1
        }
    }
}

object DrinkInsights {
    /** Density of ethanol, g/ml. */
    const val ETHANOL_DENSITY = 0.789
    /** WHO's heavy episodic drinking threshold: 60 g of pure alcohol on one occasion. */
    const val HEAVY_DAY_GRAMS = 60.0
    const val MIN_NIGHTS = 3

    fun grams(volumeMl: Double, abvPct: Double): Double = volumeMl * abvPct / 100.0 * ETHANOL_DENSITY

    fun standardDrinks(grams: Double, gramsPerStandard: Double): Double =
        if (gramsPerStandard <= 0) 0.0 else grams / gramsPerStandard

    /** "2", "1.5" — standard drinks to one decimal, without a trailing ".0". */
    fun fmt(std: Double): String {
        val r = (std * 10).roundToInt() / 10.0
        return if (r == r.toLong().toDouble()) r.toLong().toString() else "%.1f".format(r)
    }

    fun weekStart(day: LocalDate): LocalDate = day.minusDays((day.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())

    // ── This week ────────────────────────────────────────────────────────────────────────────

    data class Week(
        val start: LocalDate,
        val standardDrinks: Double,
        val drinkingDays: Int,
        /** Completed days this week (through yesterday) with no drinks, since tracking began. */
        val dryDays: Int,
        val heavyDays: Int,
        /** Whether anything has been logged for today's evening yet. */
        val drankToday: Boolean,
    )

    /** [gramsByDay] maps each evening to the alcohol logged for it. Days before [trackedSince] are unknown, not dry. */
    fun week(gramsByDay: Map<LocalDate, Double>, today: LocalDate, gramsPerStandard: Double, trackedSince: LocalDate): Week {
        val start = weekStart(today)
        val days = generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }.toList()
        val grams = days.map { gramsByDay[it] ?: 0.0 }
        val completedKnown = days.filter { it.isBefore(today) && !it.isBefore(trackedSince) }
        return Week(
            start = start,
            standardDrinks = standardDrinks(grams.sum(), gramsPerStandard),
            drinkingDays = grams.count { it > 0 },
            dryDays = completedKnown.count { (gramsByDay[it] ?: 0.0) <= 0 },
            heavyDays = grams.count { it >= HEAVY_DAY_GRAMS },
            drankToday = (gramsByDay[today] ?: 0.0) > 0,
        )
    }

    /** The week at a glance: a headline about the limit, and a line about dry days. */
    fun headline(week: Week, limit: Double, dryTarget: Int, today: LocalDate): Pair<String, String> {
        val total = week.standardDrinks
        val title = when {
            limit <= 0 -> if (total <= 0) "No drinks this week" else "${fmt(total)} drinks this week"
            total > limit -> "${fmt(total - limit)} over your limit"
            total <= 0 -> "No drinks yet this week"
            limit - total < 0.05 -> "Right at your limit"
            else -> "${fmt(limit - total)} left this week"
        }
        // Days still to come this week, today included, while today is still dry.
        val daysLeft = 7 - (today.dayOfWeek.value - 1)
        val canStillBeDry = if (week.drankToday) daysLeft - 1 else daysLeft
        val needed = (dryTarget - week.dryDays).coerceAtLeast(0)
        val sub = when {
            dryTarget <= 0 -> "${week.dryDays} dry day${if (week.dryDays == 1) "" else "s"} so far"
            needed == 0 -> "Dry-day goal reached: ${week.dryDays} of $dryTarget"
            needed <= canStillBeDry -> "$needed more dry day${if (needed == 1) "" else "s"} to reach $dryTarget"
            else -> "${week.dryDays} of $dryTarget dry days; a fresh week starts Monday"
        }
        return title to sub
    }

    /** Standard drinks per week, oldest first, for the [weeks] weeks ending with this one. */
    fun weeklyTotals(gramsByDay: Map<LocalDate, Double>, today: LocalDate, gramsPerStandard: Double, weeks: Int): List<Pair<LocalDate, Double>> {
        val thisWeek = weekStart(today)
        return (weeks - 1 downTo 0).map { w ->
            val start = thisWeek.minusWeeks(w.toLong())
            val g = (0 until 7).sumOf { gramsByDay[start.plusDays(it.toLong())] ?: 0.0 }
            start to standardDrinks(g, gramsPerStandard)
        }
    }

    // ── Streaks ──────────────────────────────────────────────────────────────────────────────

    data class Streaks(val current: Int, val best: Int)

    /** Runs of dry days through yesterday (today isn't over), counted from [trackedSince]. */
    fun streaks(drinkDays: Set<LocalDate>, today: LocalDate, trackedSince: LocalDate): Streaks {
        if (drinkDays.contains(today)) {
            return Streaks(0, bestRun(drinkDays, today.minusDays(1), trackedSince))
        }
        var current = 0
        var d = today.minusDays(1)
        while (!d.isBefore(trackedSince) && d !in drinkDays) { current++; d = d.minusDays(1) }
        return Streaks(current, maxOf(current, bestRun(drinkDays, today.minusDays(1), trackedSince)))
    }

    private fun bestRun(drinkDays: Set<LocalDate>, through: LocalDate, from: LocalDate): Int {
        var best = 0; var run = 0
        var d = from
        while (!d.isAfter(through)) {
            if (d in drinkDays) run = 0 else { run++; best = maxOf(best, run) }
            d = d.plusDays(1)
        }
        return best
    }

    // ── Savings ──────────────────────────────────────────────────────────────────────────────

    data class Savings(val drinksAvoided: Double, val money: Double?, val kcal: Double)

    /**
     * Against the user's typical week before tracking: each completed week counts what it came in
     * under [baselinePerWeek]; a week over it doesn't count against the others.
     */
    fun savings(completedWeeks: List<Double>, baselinePerWeek: Double, pricePerDrink: Double?, kcalPerDrink: Double): Savings {
        val avoided = completedWeeks.sumOf { (baselinePerWeek - it).coerceAtLeast(0.0) }
        return Savings(avoided, pricePerDrink?.let { it * avoided }, avoided * kcalPerDrink)
    }

    // ── Nights ───────────────────────────────────────────────────────────────────────────────

    data class Impact(val nights: Int, val hrvPct: Int?, val restingHr: Int?, val asleepMinutes: Int?)

    data class MorningAfter(val standardDrinks: Double, val impact: Impact, val dryNights: Int)

    private fun median(v: List<Double>): Double? {
        if (v.isEmpty()) return null
        val s = v.sorted(); val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
    }

    /** Nights whose evening is known (on or after [trackedSince]) and had no drinks. */
    fun dryNights(nights: List<NightSummary>, gramsByDay: Map<LocalDate, Double>, trackedSince: LocalDate): List<NightSummary> =
        nights.filter { n ->
            val evening = n.day.minusDays(1)
            !evening.isBefore(trackedSince) && (gramsByDay[evening] ?: 0.0) <= 0
        }

    /** How a set of nights compares with the dry ones: HRV %, resting HR bpm, minutes asleep. */
    fun compare(group: List<NightSummary>, dry: List<NightSummary>): Impact {
        fun pct(a: Double?, b: Double?) = if (a == null || b == null || b <= 0) null else ((a - b) / b * 100).roundToInt()
        fun diff(a: Double?, b: Double?) = if (a == null || b == null) null else (a - b).roundToInt()
        return Impact(
            nights = group.size,
            hrvPct = pct(median(group.mapNotNull { it.hrv }), median(dry.mapNotNull { it.hrv })),
            restingHr = diff(median(group.mapNotNull { it.restingHr }), median(dry.mapNotNull { it.restingHr })),
            asleepMinutes = diff(
                median(group.map { it.asleepMinutes.toDouble() }.filter { it > 0 }),
                median(dry.map { it.asleepMinutes.toDouble() }.filter { it > 0 }),
            ),
        )
    }

    /** Last night against the user's dry nights, when last evening had drinks and there are enough dry nights. */
    fun morningAfter(
        lastNight: NightSummary?,
        history: List<NightSummary>,
        gramsByDay: Map<LocalDate, Double>,
        gramsPerStandard: Double,
        trackedSince: LocalDate,
    ): MorningAfter? {
        val night = lastNight ?: return null
        val grams = gramsByDay[night.day.minusDays(1)] ?: 0.0
        if (grams <= 0) return null
        val dry = dryNights(history.filter { it.day != night.day }, gramsByDay, trackedSince)
        if (dry.size < MIN_NIGHTS) return MorningAfter(standardDrinks(grams, gramsPerStandard), Impact(1, null, null, null), dry.size)
        return MorningAfter(standardDrinks(grams, gramsPerStandard), compare(listOf(night), dry), dry.size)
    }

    data class Dose(val light: Impact?, val heavier: Impact?, val dryNights: Int)

    /** Nights after 1–2 and after more than 2 standard drinks, each against dry nights; a group needs [MIN_NIGHTS]. */
    fun dose(nights: List<NightSummary>, gramsByDay: Map<LocalDate, Double>, gramsPerStandard: Double, trackedSince: LocalDate): Dose {
        val dry = dryNights(nights, gramsByDay, trackedSince)
        if (dry.size < MIN_NIGHTS) return Dose(null, null, dry.size)
        fun stdOf(n: NightSummary) = standardDrinks(gramsByDay[n.day.minusDays(1)] ?: 0.0, gramsPerStandard)
        val light = nights.filter { stdOf(it) > 0 && stdOf(it) <= 2.0 }
        val heavier = nights.filter { stdOf(it) > 2.0 }
        return Dose(
            light = light.takeIf { it.size >= MIN_NIGHTS }?.let { compare(it, dry) },
            heavier = heavier.takeIf { it.size >= MIN_NIGHTS }?.let { compare(it, dry) },
            dryNights = dry.size,
        )
    }

    /**
     * Average nights for HRV to come back within 5 % of the dry-night median after a drinking
     * evening, counting only stretches where the following evenings stayed dry. Null below two
     * such stretches.
     */
    fun recoveryNights(nights: List<NightSummary>, gramsByDay: Map<LocalDate, Double>, trackedSince: LocalDate): Double? {
        val byDay = nights.associateBy { it.day }
        val dryHrv = median(dryNights(nights, gramsByDay, trackedSince).mapNotNull { it.hrv }) ?: return null
        val episodes = mutableListOf<Int>()
        gramsByDay.filter { it.value > 0 && !it.key.isBefore(trackedSince) }.keys.sorted().forEach { evening ->
            for (k in 1..7) {
                val night = byDay[evening.plusDays(k.toLong())] ?: return@forEach
                val hrv = night.hrv ?: return@forEach
                if (hrv >= dryHrv * 0.95) { episodes += k; return@forEach }
                // A drink on a following evening starts a new stretch; this one is inconclusive.
                if ((gramsByDay[evening.plusDays(k.toLong())] ?: 0.0) > 0) return@forEach
            }
        }
        return episodes.takeIf { it.size >= 2 }?.average()
    }
}

/**
 * AUDIT-C, the three-question alcohol screen (WHO AUDIT items 1–3). Each answer scores 0–4.
 * A screen, not a diagnosis, and the result copy says so.
 */
object AuditC {
    val QUESTIONS = listOf(
        "How often do you have a drink containing alcohol?" to listOf(
            "Never", "Monthly or less", "2–4 times a month", "2–3 times a week", "4 or more times a week",
        ),
        "How many standard drinks do you have on a typical day when you are drinking?" to listOf(
            "1 or 2", "3 or 4", "5 or 6", "7 to 9", "10 or more",
        ),
        "How often do you have six or more drinks on one occasion?" to listOf(
            "Never", "Less than monthly", "Monthly", "Weekly", "Daily or almost daily",
        ),
    )

    enum class Level(val label: String) { LOW("Lower risk"), INCREASING("Increasing risk"), HIGHER("Higher risk") }

    /** Retake after this many days. */
    const val RETAKE_DAYS = 90L

    fun score(answers: List<Int>): Int = answers.take(3).sumOf { it.coerceIn(0, 4) }

    /** Positive at 4 for men, 3 for women or when sex isn't known; 8 and above is higher risk. */
    fun level(score: Int, sex: String?): Level {
        val threshold = if (sex?.lowercase() == "male") 4 else 3
        return when {
            score >= 8 -> Level.HIGHER
            score >= threshold -> Level.INCREASING
            else -> Level.LOW
        }
    }

    fun advice(level: Level): String = when (level) {
        Level.LOW -> "Your answers suggest lower-risk drinking. Keeping some dry days each week helps keep it that way."
        Level.INCREASING -> "Your answers suggest drinking at a level that raises health risks. Cutting down gradually, with a weekly limit and dry days, is the usual first step."
        Level.HIGHER -> "Your answers suggest drinking at a level that is likely affecting your health. Please talk to a doctor. If you drink most days, don't stop all at once: stopping suddenly can be dangerous, and a doctor can help you cut down safely."
    }
}
