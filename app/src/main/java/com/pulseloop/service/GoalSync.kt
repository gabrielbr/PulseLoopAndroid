package com.pulseloop.service

import android.content.Context
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.UserGoalEntity
import kotlin.math.roundToInt

/**
 * Keeps the Settings goals (Nutrition intake and macros; Goals' steps, sleep and workouts) in step
 * with the goal set with the coach, so the Nutrition screen, Today's Food card and the coach's
 * plan all show one set of numbers.
 *
 * Each field follows the plan until the user changes it by hand: a sync compares the stored
 * value with the one it last wrote, and a difference means a manual edit, so that field stops
 * following. The Settings screens show which fields are managed and can hand them back.
 */
object GoalSync {

    enum class Field(val key: String, val label: String) {
        CALORIES("intakeCalories", "daily calories"),
        PROTEIN("intakeProteinG", "protein"),
        CARBS("intakeCarbsG", "carbs"),
        FAT("intakeFatG", "fat"),
        SLEEP("sleepMinutes", "sleep"),
        STEPS("steps", "steps"),
        WORKOUTS("workoutsPerWeek", "workouts a week"),
    }

    val NUTRITION_FIELDS = setOf(Field.CALORIES, Field.PROTEIN, Field.CARBS, Field.FAT)
    val GOALS_FIELDS = setOf(Field.SLEEP, Field.STEPS, Field.WORKOUTS)

    /** Share of calories from fat in the macro split; carbs take what protein and fat leave. */
    const val FAT_SHARE = 0.30
    private const val WEEK_MS = 7 * 86_400_000L

    /** The targets a plan implies, per field. Fields the plan says nothing about are absent. */
    fun targets(plan: GoalPlan.Plan, goalTargetValue: Double?): Map<Field, Double> = buildMap {
        val kcal = plan.suggestedIntakeKcal?.let { (it / 10.0).roundToInt() * 10.0 }
        val protein = plan.proteinTargetG?.toDouble()
        if (kcal != null) {
            put(Field.CALORIES, kcal)
            val p = protein ?: 0.0
            val fat = (kcal * FAT_SHARE / 9).roundToInt().toDouble()
            put(Field.FAT, fat)
            put(Field.CARBS, ((kcal - p * 4 - fat * 9) / 4).coerceAtLeast(0.0).roundToInt().toDouble())
        }
        if (protein != null) put(Field.PROTEIN, protein)
        if (goalTargetValue != null) when (plan.kind) {
            "sleep_better" -> put(Field.SLEEP, goalTargetValue * 60)
            "move_more" -> put(Field.STEPS, goalTargetValue)
            "get_fitter", "build_muscle" -> put(Field.WORKOUTS, goalTargetValue)
        }
    }

    fun read(g: UserGoalEntity, f: Field): Double? = when (f) {
        Field.CALORIES -> g.intakeCalories
        Field.PROTEIN -> g.intakeProteinG
        Field.CARBS -> g.intakeCarbsG
        Field.FAT -> g.intakeFatG
        Field.SLEEP -> g.sleepMinutes.toDouble()
        Field.STEPS -> g.steps.toDouble()
        Field.WORKOUTS -> g.workoutsPerWeek.toDouble()
    }

    fun write(g: UserGoalEntity, f: Field, v: Double): UserGoalEntity = when (f) {
        Field.CALORIES -> g.copy(intakeCalories = v)
        Field.PROTEIN -> g.copy(intakeProteinG = v)
        Field.CARBS -> g.copy(intakeCarbsG = v)
        Field.FAT -> g.copy(intakeFatG = v)
        Field.SLEEP -> g.copy(sleepMinutes = v.roundToInt())
        Field.STEPS -> g.copy(steps = v.roundToInt())
        Field.WORKOUTS -> g.copy(workoutsPerWeek = v.roundToInt())
    }

    /** Result of a pure merge: the new settings row, what changed, and the follow state after it. */
    data class Merge(
        val goal: UserGoalEntity,
        val changed: Map<Field, Double>,
        val applied: Map<Field, Double>,
        val following: Set<Field>,
    )

    /**
     * Pure: apply [targets] to [current]. A field the user edited since the last sync (its value
     * no longer equals [lastApplied]) is dropped from [following] and left alone.
     */
    fun merge(
        current: UserGoalEntity,
        targets: Map<Field, Double>,
        lastApplied: Map<Field, Double>,
        following: Set<Field>,
    ): Merge {
        var g = current
        val stillFollowing = following.toMutableSet()
        // Only fields this plan sets stay managed; a field the previous goal set is let go.
        val applied = mutableMapOf<Field, Double>()
        val changed = mutableMapOf<Field, Double>()
        for ((f, v) in targets) {
            if (f !in stillFollowing) continue
            val last = lastApplied[f]
            val now = read(g, f)
            if (last != null && now != null && kotlin.math.abs(now - last) > 0.5) {
                stillFollowing -= f // edited by hand since we last wrote it
                continue
            }
            if (now == null || kotlin.math.abs(now - v) > 0.5) {
                g = write(g, f, v)
                changed[f] = v
            }
            applied[f] = v
        }
        if (changed.keys.any { it in NUTRITION_FIELDS }) g = g.copy(nutritionEnabled = true)
        if (changed.isNotEmpty()) g = g.copy(updatedAt = System.currentTimeMillis())
        return Merge(g, changed, applied, stillFollowing)
    }

    // ── State ────────────────────────────────────────────────────────────

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("goal_sync", Context.MODE_PRIVATE)

    fun following(context: Context): Set<Field> {
        val p = prefs(context)
        return Field.entries.filter { p.getBoolean("follow.${it.key}", true) }.toSet()
    }

    /** Fields the current plan manages right now (following, and with a value written). */
    fun managed(context: Context): Set<Field> {
        val p = prefs(context)
        return following(context).filter { p.contains("applied.${it.key}") }.toSet()
    }

    /** Hand fields back to the plan (or take them away), from the Settings screens. */
    fun setFollowing(context: Context, fields: Set<Field>, follow: Boolean) {
        prefs(context).edit().apply {
            fields.forEach {
                putBoolean("follow.${it.key}", follow)
                if (!follow) remove("applied.${it.key}")
            }
        }.apply()
    }

    /**
     * Called when the user saves Settings goals by hand: any managed field whose new value differs
     * from what the plan wrote stops following, straight away rather than at the next sync.
     */
    fun noteEdits(context: Context, saved: UserGoalEntity) {
        val edited = lastApplied(context).filter { (f, v) ->
            val now = read(saved, f)
            now == null || kotlin.math.abs(now - v) > 0.5
        }.keys
        if (edited.isNotEmpty()) setFollowing(context, edited, false)
    }

    private fun lastApplied(context: Context): Map<Field, Double> {
        val p = prefs(context)
        return Field.entries.mapNotNull { f ->
            if (p.contains("applied.${f.key}")) f to p.getFloat("applied.${f.key}", 0f).toDouble() else null
        }.toMap()
    }

    /**
     * Syncs Settings from the active goal's plan. Runs when a goal is set, and from the hourly
     * guidance worker no more than weekly ([force] skips that wait). Returns what changed.
     */
    suspend fun sync(context: Context, db: PulseLoopDatabase, force: Boolean = false, now: Long = System.currentTimeMillis()): Map<Field, Double> {
        val p = prefs(context)
        if (!force && now - p.getLong("lastSyncAt", 0L) < WEEK_MS) return emptyMap()
        val goal = db.coachGoalDao().active() ?: return emptyMap()
        val plan = GoalPlan.load(db, now) ?: return emptyMap()
        val current = db.userGoalDao().get() ?: UserGoalEntity()
        val m = merge(current, targets(plan, goal.targetValue), lastApplied(context), following(context))
        if (m.changed.isNotEmpty()) db.userGoalDao().upsert(m.goal)
        p.edit().apply {
            putLong("lastSyncAt", now)
            Field.entries.forEach { f ->
                putBoolean("follow.${f.key}", f in m.following)
                val v = m.applied[f]
                if (v != null) putFloat("applied.${f.key}", v.toFloat()) else remove("applied.${f.key}")
            }
        }.apply()
        return m.changed
    }

    /** "daily calories 1,850 kcal, protein 132 g" — for the coach to tell the user. */
    fun describe(changed: Map<Field, Double>): String = changed.entries.joinToString(", ") { (f, v) ->
        when (f) {
            Field.CALORIES -> "daily calories ${"%,d".format(v.roundToInt())} kcal"
            Field.PROTEIN, Field.CARBS, Field.FAT -> "${f.label} ${v.roundToInt()} g"
            Field.SLEEP -> "sleep ${"%.1f".format(v / 60)} h"
            Field.STEPS -> "steps ${"%,d".format(v.roundToInt())} a day"
            Field.WORKOUTS -> "${v.roundToInt()} workouts a week"
        }
    }
}
