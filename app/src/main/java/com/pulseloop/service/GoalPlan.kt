package com.pulseloop.service

import com.pulseloop.ui.i18n.tr
import com.pulseloop.ui.i18n.trf

import androidx.room.withTransaction
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.BodyMeasurementEntity
import com.pulseloop.data.entity.CoachGoalEntity
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The user's goal set with the coach, turned into numbers the coach can plan with: progress, the
 * pace the date needs, whether that pace is sensible, what the scale says is actually happening,
 * and an intake that would get there given what the ring says they burn.
 *
 * The coach is the one talking; this only does the arithmetic, so a reply never invents a rate or
 * a calorie target. Safety lives here too: an intake is never suggested below the larger of BMR
 * and a floor (1,200 kcal women, 1,500 otherwise), and a pace over 1% of body weight a week is
 * labelled aggressive.
 */
object GoalPlan {

    /** Energy in one kg of body weight change (the usual planning figure). */
    const val KCAL_PER_KG = 7700.0
    const val DAY_MS = 86_400_000L

    /** Goals measured on the scale; they get an intake plan. */
    val WEIGHT_KINDS = setOf("lose_weight", "gain_weight", "build_muscle", "maintain_weight", "body_recomp")

    /**
     * Every intent the coach can set, with what it is tracked by:
     *  - lose_weight / gain_weight / maintain_weight: scale weight, intake vs burn
     *  - build_muscle ("get stronger"): workouts a week, protein, lean mass, a small surplus
     *  - body_recomp (lose fat, keep muscle): body fat % and lean mass, protein, workouts
     *  - sleep_better: hours a night, bedtime regularity, late meals and drinks
     *  - get_fitter: workouts a week, active minutes, resting heart rate falling
     *  - move_more: steps a day
     *  - reduce_stress: HRV rising and resting heart rate falling against the user's own baseline, sleep
     *  - eat_better: days logged, protein, late meals, drinks
     *  - other: the user's own words, with the general levers
     */
    val ALL_KINDS = WEIGHT_KINDS + setOf("sleep_better", "get_fitter", "move_more", "reduce_stress", "eat_better", "other")

    data class Inputs(
        val goal: CoachGoalEntity,
        val now: Long,
        val currentWeightKg: Double?,
        /** Weigh-ins of the last 30 days, oldest first: (epoch ms, kg). */
        val weighIns: List<Pair<Long, Double>> = emptyList(),
        val leanMassKg: Double? = null,
        val bodyFatPct: Double? = null,
        val sex: String? = null,
        val bmrKcal: Double? = null,
        /** Average daily calories burned over the last complete days (ring or estimate). */
        val avgBurnKcal: Double? = null,
        /** Average daily logged intake, over days that have at least one meal. */
        val avgIntakeKcal: Double? = null,
        val loggedDays: Int = 0,
        val avgProteinG: Double? = null,
        val avgSleepMin: Double? = null,
        val avgSteps: Double? = null,
        /** Spread of bedtimes over the last week, minutes (lower = more regular). */
        val bedtimeSpreadMin: Double? = null,
        val workoutsPerWeek: Double? = null,
        val avgActiveMin: Double? = null,
        /** Resting HR and HRV: last 7 nights against the 3 weeks before. */
        val restingHr7: Double? = null,
        val restingHrBase: Double? = null,
        val hrv7: Double? = null,
        val hrvBase: Double? = null,
        /** Change over the last 30 days of scale readings. */
        val leanChange30d: Double? = null,
        val fatPctChange30d: Double? = null,
        /** Journal tags in the last 7 days. */
        val lateMeals7d: Int = 0,
        val drinks7d: Int = 0,
        /** The user's standing app goals, used when the coach goal sets no target of its own. */
        val sleepGoalMin: Int = 480,
        val stepsGoal: Int = 8000,
        val workoutsGoal: Int = 3,
        val activeMinGoal: Int = 30,
    )

    /**
     * One measurable thing that moves the goal, with where the user is against it. [status] is
     * "on_target", "short" (below/above on the wrong side) or "unknown" (no data yet).
     */
    @Serializable
    data class Lever(
        val name: String,
        val value: Double? = null,
        val target: Double? = null,
        val unit: String,
        val status: String,
        val note: String? = null,
    )

    @Serializable
    data class Plan(
        val kind: String,
        val description: String,
        val startDate: String,
        val targetDate: String? = null,
        val daysElapsed: Int,
        val daysLeft: Int? = null,
        val startWeightKg: Double? = null,
        val currentWeightKg: Double? = null,
        val targetWeightKg: Double? = null,
        val kgDone: Double? = null,
        val kgToGo: Double? = null,
        /** Weekly change the remaining time needs (negative = losing). */
        val requiredKgPerWeek: Double? = null,
        val requiredPctBodyWeightPerWeek: Double? = null,
        /** gentle (<0.5 %/wk), moderate (≤1 %), aggressive (≤1.5 %), unrealistic (more). */
        val pace: String? = null,
        /** What the scale shows over the last 30 days, kg/week; null without enough weigh-ins. */
        val actualKgPerWeek: Double? = null,
        val onTrack: Boolean? = null,
        val bodyFatPct: Double? = null,
        val leanMassKg: Double? = null,
        val avgBurnKcal: Int? = null,
        val avgIntakeKcal: Int? = null,
        val loggedDays7d: Int = 0,
        val suggestedIntakeKcal: Int? = null,
        val intakeFloorKcal: Int? = null,
        val proteinTargetG: Int? = null,
        val avgProteinG: Int? = null,
        val avgSleepMin: Int? = null,
        val avgSteps: Int? = null,
        /** What moves this goal, each against its target; the coach picks the weakest ones. */
        val levers: List<Lever> = emptyList(),
        val notes: List<String> = emptyList(),
    )

    fun compute(i: Inputs, zone: ZoneId = ZoneId.systemDefault()): Plan {
        val g = i.goal
        fun date(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        val today = date(i.now)
        val daysElapsed = (today.toEpochDay() - date(g.startDate).toEpochDay()).toInt().coerceAtLeast(0)
        val daysLeft = g.targetDate?.let { (date(it).toEpochDay() - today.toEpochDay()).toInt() }
        val notes = mutableListOf<String>()

        val weightGoal = g.kind in WEIGHT_KINDS
        val current = i.currentWeightKg
        val target = g.targetWeightKg?.takeIf { weightGoal }
        val kgToGo = if (current != null && target != null) round1(target - current) else null
        val kgDone = if (current != null && g.startWeightKg != null && weightGoal) round1(current - g.startWeightKg) else null

        var required: Double? = null
        var pct: Double? = null
        var pace: String? = null
        if (kgToGo != null && current != null && daysLeft != null) {
            if (daysLeft <= 0) notes += tr("The target date has passed; agree a new one.")
            else {
                required = kgToGo / (daysLeft / 7.0)
                pct = abs(required) / current * 100
                pace = when {
                    pct < 0.5 -> "gentle"
                    pct <= 1.0 -> "moderate"
                    pct <= 1.5 -> "aggressive"
                    else -> "unrealistic"
                }
                if (pct > 1.0) {
                    val safeDays = (abs(kgToGo) / (current * 0.01) * 7).roundToInt()
                    notes += trf("At 1% of body weight a week this takes about {0} days.", safeDays)
                }
            }
        }

        val actual = trendKgPerWeek(i.weighIns)
        val onTrack = if (actual != null && required != null) {
            when {
                abs(required) < 0.05 -> abs(actual) < 0.25
                required < 0 -> actual <= required * 0.8
                else -> actual >= required * 0.8
            }
        } else null
        if (actual == null && weightGoal) notes += tr("Not enough recent weigh-ins for a trend yet (needs 3 across a week).")

        val floor = maxOf(i.bmrKcal ?: 0.0, if (i.sex?.lowercase() == "female") 1200.0 else 1500.0)
        var suggested: Double? = null
        if (weightGoal && i.avgBurnKcal != null) {
            val dailyDelta = when (g.kind) {
                // Muscle needs a small surplus whatever the scale target says.
                "build_muscle" -> maxOf((required ?: 0.0) * KCAL_PER_KG / 7.0, 250.0)
                // Recomposition: a small deficit, held by protein and training.
                "body_recomp" -> -250.0
                "maintain_weight" -> 0.0
                else -> (required ?: 0.0) * KCAL_PER_KG / 7.0
            }
            suggested = i.avgBurnKcal + dailyDelta
            if (suggested < floor) {
                notes += tr("The date needs more of a deficit than is safe; the suggestion is held at the floor.")
                suggested = floor
            }
        }
        if (i.loggedDays in 1..3) notes += trf("Only {0} day(s) of food logged this week; intake may be incomplete.", i.loggedDays)

        val protein = when {
            !weightGoal && g.kind != "eat_better" -> null
            i.leanMassKg != null -> 2.0 * i.leanMassKg
            current != null -> 1.6 * current
            else -> null
        }

        return Plan(
            kind = g.kind,
            description = g.description,
            startDate = date(g.startDate).toString(),
            targetDate = g.targetDate?.let { date(it).toString() },
            daysElapsed = daysElapsed,
            daysLeft = daysLeft,
            startWeightKg = g.startWeightKg?.let(::round1),
            currentWeightKg = current?.let(::round1),
            targetWeightKg = target?.let(::round1),
            kgDone = kgDone,
            kgToGo = kgToGo,
            requiredKgPerWeek = required?.let(::round2),
            requiredPctBodyWeightPerWeek = pct?.let(::round2),
            pace = pace,
            actualKgPerWeek = actual?.let(::round2),
            onTrack = onTrack,
            bodyFatPct = i.bodyFatPct?.let(::round1),
            leanMassKg = i.leanMassKg?.let(::round1),
            avgBurnKcal = i.avgBurnKcal?.roundToInt(),
            avgIntakeKcal = i.avgIntakeKcal?.roundToInt(),
            loggedDays7d = i.loggedDays,
            suggestedIntakeKcal = suggested?.roundToInt(),
            intakeFloorKcal = if (weightGoal) floor.roundToInt() else null,
            proteinTargetG = protein?.roundToInt(),
            avgProteinG = i.avgProteinG?.roundToInt(),
            avgSleepMin = i.avgSleepMin?.roundToInt(),
            avgSteps = i.avgSteps?.roundToInt(),
            levers = levers(i, suggested, protein, required),
            notes = notes,
        )
    }

    /** The levers for the goal's kind, in priority order. */
    fun levers(i: Inputs, suggestedIntake: Double?, proteinTarget: Double?, requiredKgPerWeek: Double?): List<Lever> {
        val g = i.goal
        fun higher(name: String, v: Double?, t: Double?, unit: String, note: String? = null, slack: Double = 0.9) =
            Lever(name, v?.let(::round1), t?.let(::round1), unit,
                if (v == null || t == null) "unknown" else if (v >= t * slack) "on_target" else "short", note)
        fun lower(name: String, v: Double?, t: Double?, unit: String, note: String? = null, slack: Double = 1.05) =
            Lever(name, v?.let(::round1), t?.let(::round1), unit,
                if (v == null || t == null) "unknown" else if (v <= t * slack) "on_target" else "short", note)
        fun trend(name: String, recent: Double?, base: Double?, unit: String, wantDown: Boolean, note: String) =
            Lever(name, recent?.let(::round1), base?.let(::round1), unit,
                when {
                    recent == null || base == null -> "unknown"
                    wantDown && recent <= base -> "on_target"
                    !wantDown && recent >= base -> "on_target"
                    else -> "short"
                }, note)

        val sleepTargetH = (if (g.kind == "sleep_better") g.targetValue else null) ?: (i.sleepGoalMin / 60.0)
        val sleep = higher("sleep", i.avgSleepMin?.div(60), sleepTargetH, "h/night")
        val regularity = lower("bedtime_regularity", i.bedtimeSpreadMin, 30.0, "min spread", tr("Bedtimes within about 30 minutes help sleep quality"))
        val workoutsTarget = (if (g.kind in setOf("get_fitter", "build_muscle")) g.targetValue else null) ?: i.workoutsGoal.toDouble()
        val workouts = higher("workouts", i.workoutsPerWeek, workoutsTarget, "per week")
        val stepsTarget = (if (g.kind == "move_more") g.targetValue else null) ?: i.stepsGoal.toDouble()
        val steps = higher("steps", i.avgSteps, stepsTarget, "per day")
        val active = higher("active_minutes", i.avgActiveMin, i.activeMinGoal.toDouble(), "min/day")
        val protein = higher("protein", i.avgProteinG, proteinTarget, "g/day", if (i.loggedDays == 0) tr("No meals logged this week") else null)
        val eaten = i.avgIntakeKcal
        val intake = when {
            suggestedIntake == null || eaten == null -> Lever("calories_eaten", eaten?.let(::round1), suggestedIntake?.let(::round1), "kcal/day", "unknown",
                if (i.loggedDays == 0) tr("Log meals to compare intake with the plan") else null)
            g.kind == "build_muscle" || g.kind == "gain_weight" -> higher("calories_eaten", eaten, suggestedIntake, "kcal/day", slack = 0.95)
            g.kind == "maintain_weight" -> Lever("calories_eaten", round1(eaten), round1(suggestedIntake), "kcal/day",
                if (abs(eaten - suggestedIntake) <= 200) "on_target" else "short")
            else -> lower("calories_eaten", eaten, suggestedIntake, "kcal/day")
        }
        val rhr = trend("resting_hr", i.restingHr7, i.restingHrBase, "bpm", wantDown = true, note = tr("Last 7 nights against the 3 weeks before; falling means fitter or better recovered"))
        val hrv = trend("hrv", i.hrv7, i.hrvBase, "ms", wantDown = false, note = tr("Last 7 nights against the 3 weeks before; rising means better recovery"))
        val leanD = i.leanChange30d
        val fatD = i.fatPctChange30d
        val lean = Lever("lean_mass_change_30d", leanD?.let(::round1), null, "kg",
            when { leanD == null -> "unknown"; leanD >= 0 -> "on_target"; else -> "short" })
        val fat = Lever("body_fat_change_30d", fatD?.let(::round1), null, "% points",
            when { fatD == null -> "unknown"; fatD <= 0 -> "on_target"; else -> "short" })
        val late = lower("late_meals", i.lateMeals7d.toDouble(), 1.0, "nights this week", slack = 1.0)
        val drinks = lower("drinking_days", i.drinks7d.toDouble(), 2.0, "days this week", slack = 1.0)
        val logged = higher("days_logged", i.loggedDays.toDouble(), 5.0, "of 7 days", slack = 1.0)
        val actual = trendKgPerWeek(i.weighIns)
        val pace = requiredKgPerWeek?.let { req ->
            Lever("weekly_change", actual?.let(::round2), round2(req), "kg/week",
                when {
                    actual == null -> "unknown"
                    abs(req) < 0.05 -> if (abs(actual) < 0.25) "on_target" else "short"
                    req < 0 && actual <= req * 0.8 -> "on_target"
                    req > 0 && actual >= req * 0.8 -> "on_target"
                    else -> "short"
                })
        }

        return when (g.kind) {
            "lose_weight" -> listOfNotNull(pace, intake, protein, steps, sleep)
            "gain_weight" -> listOfNotNull(pace, intake, protein, workouts, sleep)
            "build_muscle" -> listOf(workouts, protein, intake, lean, sleep)
            "body_recomp" -> listOf(fat, lean, protein, workouts, intake, sleep)
            "maintain_weight" -> listOfNotNull(intake, pace, steps, sleep)
            "sleep_better" -> listOf(sleep, regularity, late, drinks, hrv)
            "get_fitter" -> listOf(workouts, active, rhr, steps, sleep)
            "move_more" -> listOf(steps, active, workouts)
            "reduce_stress" -> listOf(hrv, rhr, sleep, regularity, drinks)
            "eat_better" -> listOf(logged, protein, late, drinks, intake)
            else -> listOf(sleep, steps, workouts, hrv)
        }
    }

    /** Least-squares slope of the weigh-ins, kg/week; null under 3 weigh-ins or a 7-day span. */
    fun trendKgPerWeek(points: List<Pair<Long, Double>>): Double? {
        if (points.size < 3) return null
        val t0 = points.first().first
        if (points.last().first - t0 < 7 * DAY_MS) return null
        val xs = points.map { (it.first - t0).toDouble() / (7 * DAY_MS) }
        val ys = points.map { it.second }
        val mx = xs.average(); val my = ys.average()
        val den = xs.sumOf { (it - mx) * (it - mx) }
        if (den == 0.0) return null
        return xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / den
    }

    private fun round1(v: Double) = (v * 10).roundToInt() / 10.0
    private fun round2(v: Double) = (v * 100).roundToInt() / 100.0

    /** Short line for the coach screen's banner and check-ins, e.g. "Lose 5 kg by 18 Dec · 2.1 kg down · 38 days left". */
    fun headline(p: Plan): String = buildList {
        add(p.description.replaceFirstChar { it.uppercase() })
        p.kgDone?.takeIf { abs(it) >= 0.1 }?.let { add("${abs(it)} kg ${if (it < 0) tr("down") else tr("up")}") }
        p.daysLeft?.takeIf { it >= 0 }?.let { add(trf("{0} days left", it)) }
    }.joinToString(" · ")

    private val LEVER_LABELS = mapOf(
        "sleep" to "sleep", "bedtime_regularity" to "regular bedtime", "workouts" to "workouts",
        "steps" to "steps", "active_minutes" to "active minutes", "protein" to "protein",
        "calories_eaten" to "calories", "resting_hr" to "resting HR", "hrv" to "HRV",
        "lean_mass_change_30d" to "lean mass", "body_fat_change_30d" to "body fat",
        "late_meals" to "late meals", "drinking_days" to "drinks", "days_logged" to "food logging",
        "weekly_change" to "weekly pace",
    )

    /** The banner's second line: the first lever that is short, or "On track" when none is. */
    fun focusLine(p: Plan): String {
        val short = p.levers.firstOrNull { it.status == "short" }
        if (short != null) {
            val label = LEVER_LABELS[short.name] ?: short.name
            val nums = if (short.value != null && short.target != null) " (${fmt(short.value)} of ${fmt(short.target)} ${short.unit})" else ""
            return "Focus: $label$nums"
        }
        return if (p.levers.any { it.status == "on_target" }) tr("On track") else tr("Building a picture from your data")
    }

    private fun fmt(v: Double) = if (v >= 100 || v == v.roundToInt().toDouble()) v.roundToInt().toString() else v.toString()

    // ── Loading ──────────────────────────────────────────────────────────

    suspend fun load(db: PulseLoopDatabase, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Plan? {
        val goal = db.coachGoalDao().active() ?: return null
        val profile = db.userProfileDao().get()
        val weighIns = db.bodyMeasurementDao().since(BodyMeasurementEntity.TYPE_WEIGHT, now - 30 * DAY_MS)
            .map { it.measuredAt to it.value }
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        fun start(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()
        val pastDays = (1..7).map { today.minusDays(it.toLong()) }

        val estimator = profile?.let { DailyCalorieEstimator.profileOf(it) }
        val burns = if (estimator == null) emptyList() else pastDays.mapNotNull { d ->
            db.activityDailyDao().byDay(start(d))?.let { DailyCalorieEstimator.effectiveCalories(it, estimator, now) }
        }.filter { it > 0 }
        val mealDays = pastDays.map { db.mealEntryDao().byDay(start(it)) }.filter { it.isNotEmpty() }
        val sleeps = db.sleepSessionDao().recentReal(7).map { it.totalMinutes }.filter { it > 0 }
        val steps = db.activityDailyDao().recentReal(8).filter { it.date < start(today) && it.steps > 0 }.map { it.steps }
        // Nights: the same summaries readiness uses (resting HR and HRV per night).
        val nights = runCatching { ReadinessRepository.loadInputs(db, now, zone) }.getOrNull()
            ?.let { it.history + listOfNotNull(it.lastNight) }.orEmpty()
            .sortedByDescending { it.day }
        val week = nights.filter { !it.day.isBefore(today.minusDays(7)) }
        val base = nights.filter { it.day.isBefore(today.minusDays(7)) && !it.day.isBefore(today.minusDays(28)) }
        fun avgOf(list: List<NightSummary>, f: (NightSummary) -> Double?) = list.mapNotNull(f).takeIf { it.size >= 3 }?.average()
        val bedtimes = week.map {
            // Minutes from 18:00, so a bedtime either side of midnight stays on one scale.
            val t = Instant.ofEpochMilli(it.startAt).atZone(zone).toLocalTime()
            ((t.hour * 60 + t.minute) - 18 * 60 + 1440) % 1440.0
        }
        val spread = if (bedtimes.size >= 3) {
            val m = bedtimes.average(); kotlin.math.sqrt(bedtimes.sumOf { (it - m) * (it - m) } / bedtimes.size)
        } else null
        val workouts = db.activitySessionDao().recent(100)
            .count { it.statusRaw == "finished" && it.startedAt >= now - 14 * DAY_MS } / 2.0
        val activeMins = db.activityDailyDao().recentReal(8).filter { it.date < start(today) }.map { it.activeMinutes }.filter { it > 0 }
        suspend fun change30(type: String): Double? {
            val rows = db.bodyMeasurementDao().since(type, now - 30 * DAY_MS)
            return if (rows.size >= 2) rows.last().value - rows.first().value else null
        }
        val tags = db.journalDao().since(start(today.minusDays(7))).groupBy { it.tag }
        val userGoal = db.userGoalDao().get()

        val bmr = estimator?.let { p ->
            p.leanMassKg?.let { DailyCalorieEstimator.katchMcArdle(it) }
                ?: if (p.weightKg != null && p.heightCm != null && p.age != null)
                    DailyCalorieEstimator.bmr(p.weightKg, p.heightCm, p.age, p.sex) else null
        }

        return compute(
            Inputs(
                goal = goal,
                now = now,
                currentWeightKg = weighIns.lastOrNull()?.second ?: profile?.weightKg,
                weighIns = weighIns,
                leanMassKg = estimator?.leanMassKg,
                bodyFatPct = profile?.bodyFatPct,
                sex = profile?.sex,
                bmrKcal = bmr,
                avgBurnKcal = burns.takeIf { it.isNotEmpty() }?.average(),
                avgIntakeKcal = mealDays.takeIf { it.isNotEmpty() }?.map { d -> d.sumOf { it.calories } }?.average(),
                loggedDays = mealDays.size,
                avgProteinG = mealDays.takeIf { it.isNotEmpty() }?.map { d -> d.sumOf { it.proteinG } }?.average(),
                avgSleepMin = sleeps.takeIf { it.isNotEmpty() }?.average(),
                avgSteps = steps.takeIf { it.isNotEmpty() }?.average(),
                bedtimeSpreadMin = spread,
                workoutsPerWeek = workouts,
                avgActiveMin = activeMins.takeIf { it.isNotEmpty() }?.average(),
                restingHr7 = avgOf(week) { it.restingHr },
                restingHrBase = avgOf(base) { it.restingHr },
                hrv7 = avgOf(week) { it.hrv },
                hrvBase = avgOf(base) { it.hrv },
                leanChange30d = change30(BodyMeasurementEntity.TYPE_LEAN_MASS),
                fatPctChange30d = change30(BodyMeasurementEntity.TYPE_BODY_FAT),
                lateMeals7d = tags["late_meal"]?.size ?: 0,
                drinks7d = tags["alcohol"]?.size ?: 0,
                sleepGoalMin = userGoal?.sleepMinutes ?: 480,
                stepsGoal = userGoal?.steps ?: 8000,
                workoutsGoal = userGoal?.workoutsPerWeek ?: 3,
                activeMinGoal = userGoal?.activeMinutes ?: 30,
            ),
            zone,
        )
    }

    /** Sets [goal] as the one active goal, keeping earlier ones as history. */
    suspend fun setActive(db: PulseLoopDatabase, goal: CoachGoalEntity) {
        db.withTransaction {
            db.coachGoalDao().deactivateAll()
            db.coachGoalDao().upsert(goal)
        }
    }
}
