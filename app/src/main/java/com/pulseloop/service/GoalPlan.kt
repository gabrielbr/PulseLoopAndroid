package com.pulseloop.service

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

    val WEIGHT_KINDS = setOf("lose_weight", "gain_weight", "maintain_weight", "body_recomp")
    val ALL_KINDS = WEIGHT_KINDS + setOf("sleep", "fitness", "other")

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
            if (daysLeft <= 0) notes += "The target date has passed; agree a new one."
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
                    notes += "At 1% of body weight a week this takes about $safeDays days."
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
        if (actual == null && weightGoal) notes += "Not enough recent weigh-ins for a trend yet (needs 3 across a week)."

        val floor = maxOf(i.bmrKcal ?: 0.0, if (i.sex?.lowercase() == "female") 1200.0 else 1500.0)
        var suggested: Double? = null
        if (weightGoal && i.avgBurnKcal != null) {
            val dailyDelta = (required ?: 0.0) * KCAL_PER_KG / 7.0
            suggested = i.avgBurnKcal + dailyDelta
            if (suggested < floor) {
                notes += "The date needs more of a deficit than is safe; the suggestion is held at the floor."
                suggested = floor
            }
        }
        if (i.loggedDays in 1..3) notes += "Only ${i.loggedDays} day(s) of food logged this week; intake may be incomplete."

        val protein = when {
            !weightGoal -> null
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
            notes = notes,
        )
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
        p.kgDone?.takeIf { abs(it) >= 0.1 }?.let { add("${abs(it)} kg ${if (it < 0) "down" else "up"}") }
        p.daysLeft?.takeIf { it >= 0 }?.let { add("$it days left") }
    }.joinToString(" · ")

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
