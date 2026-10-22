package com.pulseloop.service

import android.content.Context
import android.content.SharedPreferences
import com.pulseloop.ring.SleepStage
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Postpartum and breastfeeding (docs/postpartum-breastfeeding-research.md). Opt-in from the user's
 * profile: the baby's birth date and how she feeds. The mode mostly changes defaults — cycle
 * predictions pause while breastfeeding, an alcohol estimate appears beside drinks, the coach knows
 * the life stage — and adds a postpartum view with recovery, nights, a mood check-in and warning
 * signs.
 *
 * What it must never claim (research §4): that the ring detects mastitis, fever, depression,
 * preeclampsia, milk supply or returning fertility; that an alcohol estimate makes milk "safe";
 * that the LAM criteria are contraception advice; that a medication is safe.
 */
object Postpartum {

    enum class Feeding(val key: String) {
        EXCLUSIVE("exclusive"), MIXED("mixed"), NONE("none");
        val breastfeeding: Boolean get() = this != NONE

        companion object {
            fun of(key: String?) = entries.firstOrNull { it.key == key } ?: EXCLUSIVE
        }
    }

    // ── Settings ─────────────────────────────────────────────────────────────────────────────

    @Volatile private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences("postpartum", Context.MODE_PRIVATE)
    }

    private fun p() = prefs

    val enabled: Boolean get() = p()?.getBoolean("enabled", false) == true

    val birthDate: LocalDate?
        get() = p()?.getString("birthDate", null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    val feeding: Feeding get() = Feeding.of(p()?.getString("feeding", null))

    fun set(enabled: Boolean, birthDate: LocalDate?, feeding: Feeding) {
        p()?.edit()?.putBoolean("enabled", enabled)
            ?.putString("birthDate", birthDate?.toString())
            ?.putString("feeding", feeding.key)
            ?.apply()
    }

    /** kcal this mode added to the intake goal, so turning it off can take exactly that back. */
    var appliedCalorieBonus: Int
        get() = p()?.getInt("appliedBonus", 0) ?: 0
        set(v) { p()?.edit()?.putInt("appliedBonus", v)?.apply() }

    /** On, with a birth date in the last two years. */
    fun active(today: LocalDate = LocalDate.now()): Boolean {
        val b = birthDate ?: return false
        return enabled && !b.isAfter(today) && b.isAfter(today.minusYears(2))
    }

    val breastfeeding: Boolean get() = enabled && feeding.breastfeeding

    // ── Pure rules (tested) ──────────────────────────────────────────────────────────────────

    fun weeksSince(birth: LocalDate, today: LocalDate): Int = (ChronoUnit.DAYS.between(birth, today) / 7).toInt()

    fun monthsSince(birth: LocalDate, today: LocalDate): Int = ChronoUnit.MONTHS.between(birth, today).toInt()

    /**
     * Cycle predictions pause while breastfeeding, and until a period after birth: a breastfeeding
     * body's first bleeds are often without ovulation, and the ring's temperature can't tell
     * (research §2, Natural Cycles' approach).
     */
    fun pausesCycle(enabled: Boolean, birth: LocalDate?, feeding: Feeding, periodStarts: List<LocalDate>): Boolean {
        if (!enabled || birth == null) return false
        if (feeding.breastfeeding) return true
        return periodStarts.none { it.isAfter(birth.plusDays(55)) }
    }

    /** Dietary Guidelines 2020–2025: +330 kcal a day in months 0–6, +400 in 7–12, for exclusive breastfeeding; half for mixed. */
    fun calorieBonus(birth: LocalDate, today: LocalDate, feeding: Feeding): Int {
        val months = monthsSince(birth, today)
        val full = when {
            months < 6 -> 330
            months < 12 -> 400
            else -> 0
        }
        return when (feeding) {
            Feeding.EXCLUSIVE -> full
            Feeding.MIXED -> full / 2
            Feeding.NONE -> 0
        }
    }

    /** The lactational amenorrhoea method's three conditions, each with whether it holds. Information, not advice. */
    data class Lam(val babyUnderSixMonths: Boolean, val noPeriodSinceBirth: Boolean, val fullBreastfeeding: Boolean, val daysToSixMonths: Long) {
        val allHold: Boolean get() = babyUnderSixMonths && noPeriodSinceBirth && fullBreastfeeding
    }

    fun lam(birth: LocalDate, today: LocalDate, feeding: Feeding, periodStarts: List<LocalDate>): Lam {
        val sixMonths = birth.plusMonths(6)
        return Lam(
            babyUnderSixMonths = today.isBefore(sixMonths),
            // Bleeding in the first 56 days after birth isn't counted as a return of menses.
            noPeriodSinceBirth = periodStarts.none { it.isAfter(birth.plusDays(55)) },
            fullBreastfeeding = feeding == Feeding.EXCLUSIVE,
            daysToSixMonths = ChronoUnit.DAYS.between(today, sixMonths).coerceAtLeast(0),
        )
    }

    /**
     * When a drink should be out of her milk, by CDC's rule of thumb: at least two hours per
     * standard drink (14 g of alcohol). Drinks are (time ms, grams); those more than 12 hours old
     * are done. Each drink clears two hours after the previous one has, so a drink taken while
     * earlier ones are still clearing adds its two hours on top. Null when nothing is pending.
     */
    fun alcoholClearAt(drinks: List<Pair<Long, Double>>, now: Long): Long? {
        var clear = 0L
        drinks.filter { it.first in (now - 12 * 3_600_000L)..now && it.second > 0 }.sortedBy { it.first }.forEach { (t, g) ->
            clear = maxOf(clear, t) + (g / 14.0 * 2 * 3_600_000L).toLong()
        }
        return clear.takeIf { it > now }
    }

    /** How broken a night was: separate wakings between stretches of sleep, and the longest unbroken stretch. */
    data class NightBreaks(val wakings: Int, val longestSleepMin: Int, val asleepMin: Int)

    /** [stages] is the night minute by minute. A waking is one or more awake minutes with sleep on both sides. */
    fun nightBreaks(stages: List<SleepStage>): NightBreaks {
        var wakings = 0; var run = 0; var longest = 0; var asleep = 0; var sawSleep = false; var inWake = false
        for (s in stages) {
            if (s == SleepStage.AWAKE) {
                if (sawSleep && !inWake) inWake = true
                run = 0
            } else {
                if (inWake) { wakings++; inWake = false }
                sawSleep = true; run++; asleep++
                longest = maxOf(longest, run)
            }
        }
        return NightBreaks(wakings, longest, asleep)
    }

    /** Median of each week since birth (week 0 = the first seven days), for a recovery trend. */
    fun weeklyMedians(values: List<Pair<LocalDate, Double>>, birth: LocalDate): List<Pair<Int, Double>> =
        values.filter { !it.first.isBefore(birth) }
            .groupBy { weeksSince(birth, it.first) }
            .toSortedMap()
            .map { (w, v) -> w to v.map { it.second }.sorted().let { s -> if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 } }

    // ── Mood check-in (EPDS) ─────────────────────────────────────────────────────────────────

    /** Score bands from the Brazilian validation (cut-off 10); item 10 is acted on whatever the total. */
    enum class EpdsBand { LOW, POSSIBLE, LIKELY }

    fun epdsBand(score: Int): EpdsBand = when {
        score >= 13 -> EpdsBand.LIKELY
        score >= 10 -> EpdsBand.POSSIBLE
        else -> EpdsBand.LOW
    }

    @Serializable
    data class EpdsResult(val date: String, val score: Int, val selfHarm: Int)

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /** Kept only on this phone: not in the data archive, the diagnostics export or Health Connect. */
    fun epdsHistory(): List<EpdsResult> =
        p()?.getString("epds", null)?.let { runCatching { json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(EpdsResult.serializer()), it) }.getOrNull() }.orEmpty()

    fun saveEpds(result: EpdsResult) {
        val all = (epdsHistory().filter { it.date != result.date } + result).sortedBy { it.date }.takeLast(24)
        p()?.edit()?.putString("epds", json.encodeToString(kotlinx.serialization.builtins.ListSerializer(EpdsResult.serializer()), all))?.apply()
    }

    /** Suggested check-ins at about 2 and 6 weeks, 3 and 6 months; the next one not yet answered near its time. */
    fun nextCheckIn(birth: LocalDate, today: LocalDate, history: List<EpdsResult>): LocalDate? {
        val points = listOf(birth.plusWeeks(2), birth.plusWeeks(6), birth.plusMonths(3), birth.plusMonths(6))
        val done = history.mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }
        return points.firstOrNull { pt -> done.none { d -> !d.isBefore(pt.minusDays(7)) } && pt.plusDays(21).isAfter(today) }
    }
}

/** The postpartum life stage as the coach sees it; null when the mode is off. */
@Serializable
data class PostpartumContext(
    val weeksSinceBirth: Int,
    val feeding: String,
    val note: String = "The user is postpartum and set this herself. Resting heart rate and HRV are still settling " +
        "after birth (RHR often dips below her old normal around week 7 and takes months to settle), and broken " +
        "sleep is expected. Never say the ring detects mastitis, fever, depression, preeclampsia, milk supply or " +
        "returning fertility. Alcohol while breastfeeding: not drinking is safest; CDC suggests waiting at least " +
        "2 hours per standard drink before nursing; pumping and dumping doesn't speed it up. Never call a " +
        "medication safe while breastfeeding: point to LactMed or e-lactancia and her clinician. No weight-loss " +
        "framing. Urgent warning signs (fever 38 °C or higher, severe headache, vision changes, chest pain, " +
        "trouble breathing, heavy bleeding, thoughts of harming herself or the baby) mean contacting a clinician " +
        "or emergency services now; in Brazil, SAMU 192 and CVV 188.",
) {
    companion object {
        fun build(today: LocalDate = LocalDate.now()): PostpartumContext? {
            if (!Postpartum.active(today)) return null
            val birth = Postpartum.birthDate ?: return null
            return PostpartumContext(Postpartum.weeksSince(birth, today), Postpartum.feeding.key)
        }
    }
}
