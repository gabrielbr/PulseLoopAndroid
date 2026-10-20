package com.pulseloop.service

import android.content.Context
import android.content.SharedPreferences
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.DrinkEntryEntity
import com.pulseloop.data.entity.JournalEntryEntity
import com.pulseloop.data.entity.MealEntryEntity
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The user's drinks settings (docs/drinks-plan.md). Initialised once from the Application, so the
 * meal log and the coach tools, which have a database but no Context, can check whether Drinks is on.
 */
object DrinkPrefs {
    @Volatile private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences("drinks", Context.MODE_PRIVATE)
    }

    private fun p(): SharedPreferences? = prefs

    val enabled: Boolean get() = p()?.getBoolean("enabled", false) ?: false

    fun setEnabled(on: Boolean, today: LocalDate = LocalDate.now()) {
        val e = p()?.edit() ?: return
        e.putBoolean("enabled", on)
        if (on && p()?.getString("trackedSince", null) == null) e.putString("trackedSince", today.toString())
        e.apply()
    }

    /** The first day drinks were tracked; days before it are unknown rather than dry. */
    val trackedSince: LocalDate
        get() = p()?.getString("trackedSince", null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()

    var weeklyLimit: Double
        get() = p()?.getFloat("weeklyLimit", 7f)?.toDouble() ?: 7.0
        set(v) { p()?.edit()?.putFloat("weeklyLimit", v.toFloat())?.apply() }

    var dryDaysTarget: Int
        get() = p()?.getInt("dryDaysTarget", 4) ?: 4
        set(v) { p()?.edit()?.putInt("dryDaysTarget", v)?.apply() }

    /** Grams of alcohol in one standard drink: 10 (WHO), 8 (UK unit) or 14 (US). */
    var gramsPerStandard: Double
        get() = p()?.getFloat("gramsPerStandard", 10f)?.toDouble() ?: 10.0
        set(v) { p()?.edit()?.putFloat("gramsPerStandard", v.toFloat())?.apply() }

    /** The user's typical week before tracking, in standard drinks; 0 = not given. */
    var baselinePerWeek: Double
        get() = p()?.getFloat("baselinePerWeek", 0f)?.toDouble() ?: 0.0
        set(v) { p()?.edit()?.putFloat("baselinePerWeek", v.toFloat())?.apply() }

    /** What one drink usually costs, in the user's currency; 0 = not given. */
    var pricePerDrink: Double
        get() = p()?.getFloat("pricePerDrink", 0f)?.toDouble() ?: 0.0
        set(v) { p()?.edit()?.putFloat("pricePerDrink", v.toFloat())?.apply() }

    var lastType: DrinkType
        get() = DrinkType.of(p()?.getString("lastType", DrinkType.BEER.key))
        set(v) { p()?.edit()?.putString("lastType", v.key)?.apply() }

    /** Tonight's plan: how many drinks, for which evening. */
    fun plan(day: LocalDate): Int? =
        if (p()?.getString("planDay", null) == day.toString()) p()?.getInt("planCount", 0) else null

    fun setPlan(day: LocalDate, count: Int?) {
        val e = p()?.edit() ?: return
        if (count == null) e.remove("planDay").remove("planCount") else e.putString("planDay", day.toString()).putInt("planCount", count)
        e.apply()
    }

    val auditDate: LocalDate? get() = p()?.getString("auditDate", null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val auditScore: Int? get() = p()?.getInt("auditScore", -1)?.takeIf { it >= 0 }

    fun setAudit(day: LocalDate, score: Int) {
        p()?.edit()?.putString("auditDate", day.toString())?.putInt("auditScore", score)?.apply()
    }
}

object DrinksRepository {

    private fun dayStart(d: LocalDate, zone: ZoneId) = d.atStartOfDay(zone).toInstant().toEpochMilli()
    private fun dateOf(ms: Long, zone: ZoneId) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    /**
     * Log [count] drinks of [type] at [timestamp]. Each drink is its own row, so one can be removed.
     * Ticks the journal's Alcohol tag for that evening, so "What affects you" keeps working.
     */
    suspend fun log(
        db: PulseLoopDatabase,
        type: DrinkType,
        count: Int = 1,
        timestamp: Long = System.currentTimeMillis(),
        price: Double? = DrinkPrefs.pricePerDrink.takeIf { it > 0 },
        mealId: String? = null,
        note: String? = null,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<DrinkEntryEntity> {
        val evening = MealJournal.eveningOf(timestamp, zone)
        val day = dayStart(evening, zone)
        val rows = (0 until count.coerceIn(1, 12)).map {
            DrinkEntryEntity(
                timestamp = timestamp, day = day, type = type.key, volumeMl = type.volumeMl, abvPct = type.abvPct,
                grams = type.grams, kcal = type.kcal, price = price, mealId = mealId, note = note,
            )
        }
        rows.forEach { db.drinkDao().upsert(it) }
        val tag = JournalInsights.Tag.ALCOHOL.key
        runCatching { db.journalDao().upsert(JournalEntryEntity(id = JournalEntryEntity.idFor(day, tag), day = day, tag = tag)) }
        if (mealId == null) DrinkPrefs.lastType = type
        return rows
    }

    /**
     * A logged meal that names a drink becomes drink entries too, so nobody logs twice. Skipped
     * when Drinks is off, when the meal already has drinks, or when the same kind of drink was
     * logged on its own within 30 minutes.
     */
    suspend fun fromMeal(db: PulseLoopDatabase, meal: MealEntryEntity, zone: ZoneId = ZoneId.systemDefault()) {
        if (!DrinkPrefs.enabled || !MealJournal.isAlcohol(meal.name, meal.notes)) return
        if (db.drinkDao().byMeal(meal.id).isNotEmpty()) return
        val text = listOfNotNull(meal.name, meal.notes).joinToString(" ")
        val type = DrinkType.guess(text)
        val evening = MealJournal.eveningOf(meal.timestamp, zone)
        val recent = db.drinkDao().between(dayStart(evening, zone), dayStart(evening.plusDays(1), zone))
        if (recent.any { it.mealId == null && it.type == type.key && kotlin.math.abs(it.timestamp - meal.timestamp) <= 30 * 60_000L }) return
        log(db, type, DrinkType.count(meal.name), meal.timestamp, mealId = meal.id, zone = zone)
    }

    suspend fun onMealDeleted(db: PulseLoopDatabase, mealId: String) {
        runCatching { db.drinkDao().deleteByMeal(mealId) }
    }

    /** Alcohol per evening for the last [days] days. */
    suspend fun gramsByDay(db: PulseLoopDatabase, today: LocalDate, days: Long, zone: ZoneId): Map<LocalDate, Double> =
        db.drinkDao().between(dayStart(today.minusDays(days), zone), dayStart(today.plusDays(1), zone))
            .groupBy { dateOf(it.day, zone) }
            .mapValues { (_, v) -> v.sumOf { it.grams } }

    data class Snapshot(
        val today: LocalDate,
        val trackedSince: LocalDate,
        val gramsPerStandard: Double,
        val week: DrinkInsights.Week,
        val weeklyLimit: Double,
        val dryDaysTarget: Int,
        val streaks: DrinkInsights.Streaks,
        val weeks: List<Pair<LocalDate, Double>>,
        val savings: DrinkInsights.Savings?,
        val morningAfter: DrinkInsights.MorningAfter?,
        val dose: DrinkInsights.Dose,
        val recoveryNights: Double?,
        /** Today's and yesterday's evenings, newest first. */
        val recent: List<DrinkEntryEntity>,
        val planToday: Int?,
        val todayStandard: Double,
        val auditDue: Boolean,
        /** Monday to Sunday of this week, with the alcohol logged for each evening (grams). */
        val weekDays: List<Pair<LocalDate, Double>>,
        /** The user's four most-logged drinks lately, for the one-tap row. */
        val topTypes: List<DrinkType>,
    )

    suspend fun load(db: PulseLoopDatabase, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Snapshot {
        // The evening in progress: before 04:00 the night still belongs to yesterday.
        val today = MealJournal.eveningOf(now, zone)
        val since = DrinkPrefs.trackedSince
        val std = DrinkPrefs.gramsPerStandard
        val byDay = gramsByDay(db, today, 120, zone)
        val inputs = runCatching { ReadinessRepository.loadInputs(db, now, zone) }.getOrNull()
        val nights = inputs?.let { it.history + listOfNotNull(it.lastNight) }.orEmpty()
        val weeks = DrinkInsights.weeklyTotals(byDay, today, std, 8)
        val completedSinceTracking = weeks.dropLast(1).filter { !it.first.isBefore(DrinkInsights.weekStart(since)) && it.first.plusDays(6).isAfter(since) }
        val all = db.drinkDao().between(dayStart(today.minusDays(120), zone), dayStart(today.plusDays(1), zone))
        val kcalPerStd = all.takeIf { it.isNotEmpty() }
            ?.let { list -> list.sumOf { it.kcal } / DrinkInsights.standardDrinks(list.sumOf { it.grams }, std).coerceAtLeast(0.1) }
            ?: 150.0
        val baseline = DrinkPrefs.baselinePerWeek
        val recent = all.filter { !dateOf(it.day, zone).isBefore(today.minusDays(1)) }.sortedByDescending { it.timestamp }
        return Snapshot(
            today = today,
            trackedSince = since,
            gramsPerStandard = std,
            week = DrinkInsights.week(byDay, today, std, since),
            weeklyLimit = DrinkPrefs.weeklyLimit,
            dryDaysTarget = DrinkPrefs.dryDaysTarget,
            streaks = DrinkInsights.streaks(byDay.filterValues { it > 0 }.keys, today, since),
            weeks = weeks,
            savings = if (baseline > 0 && completedSinceTracking.isNotEmpty())
                DrinkInsights.savings(completedSinceTracking.map { it.second }, baseline, DrinkPrefs.pricePerDrink.takeIf { it > 0 }, kcalPerStd)
            else null,
            morningAfter = DrinkInsights.morningAfter(inputs?.lastNight, nights, byDay, std, since),
            dose = DrinkInsights.dose(nights, byDay, std, since),
            recoveryNights = DrinkInsights.recoveryNights(nights, byDay, since),
            recent = recent,
            planToday = DrinkPrefs.plan(today),
            todayStandard = DrinkInsights.standardDrinks(byDay[today] ?: 0.0, std),
            auditDue = DrinkPrefs.auditDate?.let { it.plusDays(AuditC.RETAKE_DAYS) <= today } ?: true,
            weekDays = (0L until 7L).map { DrinkInsights.weekStart(today).plusDays(it) }.map { it to (byDay[it] ?: 0.0) },
            topTypes = topTypes(all.filter { it.mealId == null }.map { DrinkType.of(it.type) }),
        )
    }

    private val DEFAULT_TOP = listOf(DrinkType.BEER, DrinkType.WINE, DrinkType.CAIPIRINHA, DrinkType.SPIRIT)

    /** The four most-logged types, topped up with common ones so the row is always four wide. */
    fun topTypes(logged: List<DrinkType>): List<DrinkType> {
        val ranked = logged.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
        return (ranked + DEFAULT_TOP).distinct().take(4)
    }

    /** One line for a morning-after card or notification, null when there's nothing honest to say. */
    fun morningAfterLine(m: DrinkInsights.MorningAfter): String {
        val drinks = "${DrinkInsights.fmt(m.standardDrinks)} drink${if (m.standardDrinks == 1.0) "" else "s"}"
        val parts = buildList {
            m.impact.hrvPct?.let { add("HRV ${kotlin.math.abs(it)}% ${if (it < 0) "below" else "above"} your dry nights") }
            m.impact.restingHr?.let { if (it != 0) add("resting heart rate ${if (it > 0) "+" else ""}$it bpm") }
            m.impact.asleepMinutes?.let { if (kotlin.math.abs(it) >= 10) add("${kotlin.math.abs(it)} min ${if (it < 0) "less" else "more"} sleep") }
        }
        return if (parts.isEmpty()) {
            if (m.dryNights < DrinkInsights.MIN_NIGHTS) "Last night: $drinks. A few dry nights will show what it does to you."
            else "Last night: $drinks. Your night looked close to your dry ones."
        } else "Last night: $drinks. ${parts.joinToString(", ").replaceFirstChar { it.uppercase() }}."
    }
}

/** Drinks as the coach sees them; null when Drinks is off. */
@Serializable
data class DrinksContext(
    val weekStandardDrinks: Double,
    val weeklyLimit: Double,
    val dryDaysThisWeek: Int,
    val dryDaysTarget: Int,
    val currentDryStreak: Int,
    val tonightPlan: Int? = null,
    val todayStandardDrinks: Double,
    val lastNightStandardDrinks: Double? = null,
    val lastNightEffect: String? = null,
    val standardDrinkGrams: Double,
    val note: String = "Drinks the user logged themselves, in standard drinks of the given grams. Effects compare the user's own nights after drinking with their dry nights; a pattern, not proof.",
) {
    companion object {
        suspend fun build(db: PulseLoopDatabase, now: Long = System.currentTimeMillis()): DrinksContext? {
            if (!DrinkPrefs.enabled) return null
            return runCatching {
                val s = DrinksRepository.load(db, now)
                DrinksContext(
                    weekStandardDrinks = (s.week.standardDrinks * 10).toInt() / 10.0,
                    weeklyLimit = s.weeklyLimit,
                    dryDaysThisWeek = s.week.dryDays,
                    dryDaysTarget = s.dryDaysTarget,
                    currentDryStreak = s.streaks.current,
                    tonightPlan = s.planToday,
                    todayStandardDrinks = (s.todayStandard * 10).toInt() / 10.0,
                    lastNightStandardDrinks = s.morningAfter?.standardDrinks,
                    lastNightEffect = s.morningAfter?.let { DrinksRepository.morningAfterLine(it) },
                    standardDrinkGrams = s.gramsPerStandard,
                )
            }.getOrNull()
        }
    }
}
