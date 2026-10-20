package com.pulseloop.service

import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.FoodPreferenceEntity
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * The next-meal idea on the Nutrition screen: a plate of everyday Brazilian food and common fruit
 * that fits what is left of the day's calories, and closes the protein gap where it can.
 *
 * Rules, not a model, so the numbers always add up and the card works without an AI key. The
 * coach discusses it in chat and records what the user agrees to instead ([FoodPreferenceEntity]);
 * an agreed preference is used next time whenever it still fits the budget, so a choice the user
 * keeps making ("banana at night, not vegetables") becomes the default.
 */
object MealSuggester {

    enum class Role { PROTEIN, CARB, LEGUME, VEG, FRUIT, DAIRY, EXTRA }

    data class Food(
        val id: String,
        /** Portuguese name, as the user knows the dish. */
        val name: String,
        val english: String,
        val portion: String,
        val kcal: Double,
        val proteinG: Double,
        val carbsG: Double,
        val fatG: Double,
        val role: Role,
        val slots: Set<String>,
    )

    private val B = "breakfast"; private val L = "lunch"; private val S = "snack"; private val D = "dinner"

    /** Typical portions, TACO-style values, rounded. */
    val CATALOG: List<Food> = listOf(
        // Fruit
        Food("banana", "Banana", "banana", "1 medium", 105.0, 1.3, 27.0, 0.4, Role.FRUIT, setOf(B, S, D)),
        Food("maca", "Maçã", "apple", "1 medium", 95.0, 0.5, 25.0, 0.3, Role.FRUIT, setOf(B, S, D)),
        Food("mamao", "Mamão papaia", "papaya", "½ fruit", 60.0, 1.0, 15.0, 0.2, Role.FRUIT, setOf(B, S)),
        Food("laranja", "Laranja", "orange", "1 medium", 62.0, 1.2, 15.0, 0.2, Role.FRUIT, setOf(B, S)),
        Food("melancia", "Melancia", "watermelon", "2 slices", 60.0, 1.2, 15.0, 0.3, Role.FRUIT, setOf(S)),
        // Breakfast and snacks
        Food("pao_queijo_minas", "Pão francês com queijo minas", "bread roll with fresh cheese", "1 roll + 30 g", 220.0, 9.0, 30.0, 6.0, Role.CARB, setOf(B, S)),
        Food("tapioca_ovo", "Tapioca com ovo", "tapioca with egg", "1 medium", 230.0, 8.0, 32.0, 6.0, Role.CARB, setOf(B, S, D)),
        Food("cuscuz_ovo", "Cuscuz com ovo", "corn couscous with egg", "1 plate", 250.0, 9.0, 35.0, 7.0, Role.CARB, setOf(B, D)),
        Food("ovos_mexidos", "Ovos mexidos", "scrambled eggs", "2 eggs", 180.0, 12.0, 2.0, 14.0, Role.PROTEIN, setOf(B, D)),
        Food("iogurte", "Iogurte natural", "plain yogurt", "1 pot (170 g)", 100.0, 6.0, 8.0, 5.0, Role.DAIRY, setOf(B, S, D)),
        Food("cafe_leite", "Café com leite", "coffee with milk", "1 cup", 90.0, 6.0, 9.0, 3.0, Role.DAIRY, setOf(B, S)),
        Food("castanhas", "Castanhas", "brazil or cashew nuts", "30 g", 180.0, 4.0, 6.0, 16.0, Role.EXTRA, setOf(S)),
        Food("pao_de_queijo", "Pão de queijo", "cheese bread", "2 small", 180.0, 3.0, 18.0, 10.0, Role.EXTRA, setOf(B, S)),
        // Lunch and dinner
        Food("arroz", "Arroz branco", "white rice", "4 tbsp (100 g)", 130.0, 2.5, 28.0, 0.3, Role.CARB, setOf(L, D)),
        Food("feijao", "Feijão", "beans", "1 ladle (100 g)", 76.0, 4.8, 14.0, 0.5, Role.LEGUME, setOf(L, D)),
        Food("batata_doce", "Batata-doce", "sweet potato", "150 g", 130.0, 2.0, 30.0, 0.2, Role.CARB, setOf(L, D)),
        Food("mandioca", "Mandioca cozida", "boiled cassava", "100 g", 125.0, 1.0, 30.0, 0.3, Role.CARB, setOf(L, D)),
        Food("frango", "Frango grelhado", "grilled chicken", "120 g", 195.0, 37.0, 0.0, 4.0, Role.PROTEIN, setOf(L, D)),
        Food("tilapia", "Tilápia grelhada", "grilled tilapia", "120 g", 155.0, 31.0, 0.0, 3.0, Role.PROTEIN, setOf(L, D)),
        Food("patinho", "Patinho moído", "lean ground beef", "100 g", 220.0, 27.0, 0.0, 12.0, Role.PROTEIN, setOf(L, D)),
        Food("omelete", "Omelete de 2 ovos com queijo", "2-egg cheese omelette", "1", 250.0, 17.0, 2.0, 19.0, Role.PROTEIN, setOf(D)),
        Food("salada", "Salada verde com tomate", "green salad with tomato", "1 plate, 1 tsp olive oil", 60.0, 1.0, 5.0, 4.0, Role.VEG, setOf(L, D)),
        Food("legumes", "Legumes cozidos", "steamed vegetables (zucchini, carrot, broccoli)", "150 g", 60.0, 3.0, 10.0, 0.5, Role.VEG, setOf(L, D)),
        Food("sopa_legumes", "Sopa de legumes com frango", "vegetable and chicken soup", "1 bowl", 220.0, 18.0, 22.0, 6.0, Role.PROTEIN, setOf(D)),
    )

    /** The meal slot for a local time of day. */
    fun slotAt(time: LocalTime): String = when (time.hour) {
        in 4..9 -> B
        in 10..14 -> L
        in 15..17 -> S
        else -> D
    }

    /** Share of the day's calories a slot normally takes, for sizing one meal. */
    private val SLOT_SHARE = mapOf(B to 0.25, L to 0.35, S to 0.12, D to 0.30)

    @Serializable
    data class Item(val name: String, val english: String, val portion: String, val kcal: Int, val proteinG: Int, val carbsG: Int = 0, val fatG: Int = 0)

    @Serializable
    data class Suggestion(
        val slot: String,
        val items: List<Item>,
        val totalKcal: Int,
        val totalProteinG: Int,
        /** Calories left after this meal, against the day's target. */
        val leavesKcal: Int,
        /** Preferences this suggestion used, in words ("banana at dinner"). */
        val fromPreferences: List<String> = emptyList(),
        val note: String? = null,
    )

    data class Inputs(
        val slot: String,
        val targetKcal: Double,
        val eatenKcal: Double,
        val proteinTargetG: Double?,
        val proteinEatenG: Double,
        val preferences: List<FoodPreferenceEntity> = emptyList(),
        /** Varies the pick from day to day (day of year). */
        val seed: Int = 0,
    )

    private fun matches(food: Food, text: String): Boolean {
        val t = text.trim().lowercase()
        if (t.isEmpty()) return false
        return food.id == t || food.name.lowercase().contains(t) || food.english.lowercase().contains(t) ||
            t.contains(food.name.lowercase()) || t.contains(food.english.lowercase())
    }

    private fun roleOf(text: String): Role? = when (text.trim().lowercase()) {
        "vegetables", "veg", "veggies", "legumes", "verduras", "salad", "salada" -> Role.VEG
        "fruit", "fruta", "frutas" -> Role.FRUIT
        "carbs", "carb", "rice", "arroz", "carboidrato" -> Role.CARB
        "beans", "feijão", "feijao" -> Role.LEGUME
        "protein", "proteína", "proteina", "meat", "carne" -> Role.PROTEIN
        else -> null
    }

    /** The food a preference names: a catalog entry, or the user's own food with its nutrition. */
    private fun foodFor(p: FoodPreferenceEntity): Food? =
        CATALOG.firstOrNull { matches(it, p.item) }
            ?: if (p.kcal != null) Food("pref:${p.item}", p.item.replaceFirstChar { it.uppercase() }, p.item,
                p.portion ?: "1 portion", p.kcal, p.proteinG ?: 0.0, 0.0, 0.0, Role.EXTRA, setOf(B, L, S, D)) else null

    fun suggest(i: Inputs): Suggestion {
        val remaining = i.targetKcal - i.eatenKcal
        val slot = i.slot
        val slotPrefs = i.preferences.filter { it.slot == slot || it.slot == "any" }
        val avoided = slotPrefs.filter { it.action == "avoid" }
        val preferred = slotPrefs.filter { it.action == "prefer" }.sortedByDescending { it.timesAgreed }

        // An avoid names a food ("tapioca") or a whole role ("vegetables").
        fun allowed(f: Food) = slot in f.slots && avoided.none { matches(f, it.item) || roleOf(it.item) == f.role }
        fun pool(role: Role) = CATALOG.filter { it.role == role && allowed(it) }
            .let { list -> if (list.isEmpty()) list else list.indices.map { list[(it + i.seed) % list.size] } }

        if (remaining < 120) {
            val fruit = pool(Role.FRUIT).firstOrNull()
            return Suggestion(
                slot = slot, items = emptyList(), totalKcal = 0, totalProteinG = 0, leavesKcal = remaining.roundToInt(),
                note = if (remaining <= 0) "You've reached today's target. Water or tea if you're hungry" +
                    (fruit?.let { ", or a ${it.english} if you need something (${it.kcal.roundToInt()} kcal)." } ?: ".")
                else "Only ${remaining.roundToInt()} kcal left: " + (fruit?.let { "a ${it.english} fits." } ?: "keep it light."),
            )
        }

        val budget = minOf(remaining, i.targetKcal * (SLOT_SHARE[slot] ?: 0.3)).coerceAtLeast(120.0)
        val proteinGap = ((i.proteinTargetG ?: 0.0) - i.proteinEatenG).coerceAtLeast(0.0)

        // The plate's roles for this meal.
        val template = when (slot) {
            B -> listOf(Role.CARB, Role.DAIRY, Role.FRUIT)
            L -> listOf(Role.PROTEIN, Role.CARB, Role.LEGUME, Role.VEG)
            S -> listOf(Role.FRUIT, Role.DAIRY)
            else -> listOf(Role.PROTEIN, Role.VEG, Role.CARB)
        }.toMutableList()

        val plate = mutableListOf<Food>()
        val used = mutableListOf<String>()
        // Agreed preferences first, while they fit: replace what they stand in for.
        for (p in preferred) {
            val food = foodFor(p) ?: continue
            if (plate.sumOf { it.kcal } + food.kcal > budget) continue
            val replaces = p.insteadOf?.let { roleOf(it) ?: CATALOG.firstOrNull { f -> matches(f, it) }?.role }
            replaces?.let { template.remove(it) }
            template.remove(food.role)
            plate += food
            used += "${p.item} at ${if (p.slot == "any") "any meal" else p.slot}" + (p.insteadOf?.let { " instead of $it" } ?: "")
        }
        // Then fill the template, protein first when there's a gap, each within what's left.
        val order = if (proteinGap > 15) template.sortedBy { if (it == Role.PROTEIN || it == Role.DAIRY) 0 else 1 } else template
        for (role in order) {
            val left = budget - plate.sumOf { it.kcal }
            val pick = pool(role).filter { it.kcal <= left && plate.none { p -> p.id == it.id } }
                .maxByOrNull { if (role == Role.PROTEIN) it.proteinG else 0.0 } ?: continue
            plate += pick
        }

        val total = plate.sumOf { it.kcal }
        return Suggestion(
            slot = slot,
            items = plate.map { Item(it.name, it.english, it.portion, it.kcal.roundToInt(), it.proteinG.roundToInt(), it.carbsG.roundToInt(), it.fatG.roundToInt()) },
            totalKcal = total.roundToInt(),
            totalProteinG = plate.sumOf { it.proteinG }.roundToInt(),
            leavesKcal = (remaining - total).roundToInt(),
            fromPreferences = used,
            note = if (proteinGap > 30) "You're ${proteinGap.roundToInt()} g short of today's protein, so this leans on protein." else null,
        )
    }

    /** Whether the Nutrition screen offers the idea: once a third of the day's target is eaten. */
    const val SHOW_FROM_SHARE = 0.33
    fun shouldShow(targetKcal: Double, eatenKcal: Double): Boolean = targetKcal > 0 && eatenKcal >= targetKcal * SHOW_FROM_SHARE

    // ── Loading ──────────────────────────────────────────────────────────

    suspend fun load(db: PulseLoopDatabase, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Suggestion? {
        val local = Instant.ofEpochMilli(now).atZone(zone)
        val todayStart = local.toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        val meals = db.mealEntryDao().byDay(todayStart)
        val goal = db.userGoalDao().get()
        val target = goal?.intakeCalories ?: 2000.0
        return suggest(
            Inputs(
                slot = slotAt(local.toLocalTime()),
                targetKcal = target,
                eatenKcal = meals.sumOf { it.calories },
                proteinTargetG = goal?.intakeProteinG,
                proteinEatenG = meals.sumOf { it.proteinG },
                preferences = db.foodPreferenceDao().all(),
                seed = local.dayOfYear,
            ),
        )
    }

    /**
     * Records a preference the user and coach agreed on; agreeing to the same one again counts up.
     * Returns the stored row.
     */
    suspend fun agree(
        db: PulseLoopDatabase,
        slot: String,
        item: String,
        action: String,
        insteadOf: String?,
        portion: String?,
        kcal: Double?,
        proteinG: Double?,
        reason: String?,
    ): FoodPreferenceEntity {
        val id = FoodPreferenceEntity.idFor(slot, item, action)
        val existing = db.foodPreferenceDao().byId(id)
        val row = existing?.copy(
            insteadOf = insteadOf ?: existing.insteadOf,
            portion = portion ?: existing.portion,
            kcal = kcal ?: existing.kcal,
            proteinG = proteinG ?: existing.proteinG,
            reason = reason ?: existing.reason,
            timesAgreed = existing.timesAgreed + 1,
            updatedAt = System.currentTimeMillis(),
        ) ?: FoodPreferenceEntity(id, slot, item.trim(), action, insteadOf, portion, kcal, proteinG, reason)
        db.foodPreferenceDao().upsert(row)
        return row
    }
}
