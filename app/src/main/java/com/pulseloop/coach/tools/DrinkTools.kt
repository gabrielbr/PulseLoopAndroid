package com.pulseloop.coach.tools

import com.pulseloop.service.DrinkInsights
import com.pulseloop.service.DrinkPrefs
import com.pulseloop.service.DrinkType
import com.pulseloop.service.DrinksRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Drinks in chat (docs/drinks-plan.md): read the week, log a drink the user says they had, and set
 * tonight's plan once they agree to a number. All three refuse while Drinks is off, so the coach
 * never starts tracking alcohol for someone who didn't ask.
 */
object DrinkTools {

    val all: List<CoachToolDef> get() = listOf(makeSummary(), makeLog(), makePlan())

    private val TYPES = DrinkType.entries.map { it.key }
    private const val OFF = """{"error":"Drinks tracking is off. The user can turn it on from Today's tiles, Settings → Drinks, or the Journal."}"""

    private fun noArgs() = JsonObject(mapOf(
        "type" to JsonPrimitive("object"),
        "properties" to JsonObject(emptyMap()),
        "required" to JsonArray(emptyList()),
        "additionalProperties" to JsonPrimitive(false),
    ))

    private fun makeSummary() = CoachToolDef(
        name = "get_drinks",
        publicLabel = "Checking your drinks",
        description = "The user's drinks: this week in standard drinks against their weekly limit, dry days against their " +
            "target, the current and best dry streak, tonight's plan, the last 8 weeks, and what drinking did to their " +
            "nights (last night, after 1–2 and after 3+ drinks, compared with their dry nights).",
        parameters = noArgs(),
    ) { _, ctx ->
        val db = ctx.db ?: return@CoachToolDef ToolResult("""{"error":"database not available"}""", isError = true)
        if (!DrinkPrefs.enabled) return@CoachToolDef ToolResult(OFF, isError = true)
        val s = kotlinx.coroutines.runBlocking { DrinksRepository.load(db) }
        fun impact(i: DrinkInsights.Impact?) = i?.let {
            buildJsonObject {
                put("nights", it.nights)
                it.hrvPct?.let { v -> put("hrv_pct_vs_dry", v) }
                it.restingHr?.let { v -> put("resting_hr_bpm_vs_dry", v) }
                it.asleepMinutes?.let { v -> put("asleep_min_vs_dry", v) }
            }
        }
        ToolResult(buildJsonObject {
            put("standard_drink_grams", s.gramsPerStandard)
            put("week_standard_drinks", DrinkInsights.fmt(s.week.standardDrinks))
            put("weekly_limit", s.weeklyLimit)
            put("dry_days_this_week", s.week.dryDays)
            put("dry_days_target", s.dryDaysTarget)
            put("heavy_days_this_week", s.week.heavyDays)
            put("today_standard_drinks", DrinkInsights.fmt(s.todayStandard))
            s.planToday?.let { put("tonight_plan", it) }
            put("dry_streak_current", s.streaks.current)
            put("dry_streak_best", s.streaks.best)
            put("last_8_weeks", JsonArray(s.weeks.map { JsonPrimitive(DrinkInsights.fmt(it.second)) }))
            s.morningAfter?.let { put("last_night", DrinksRepository.morningAfterLine(it)) }
            impact(s.dose.light)?.let { put("after_1_to_2_drinks", it) }
            impact(s.dose.heavier)?.let { put("after_3_plus_drinks", it) }
            put("dry_nights_compared", s.dose.dryNights)
            s.recoveryNights?.let { put("nights_for_hrv_to_recover", "%.1f".format(it)) }
        }.toString())
    }

    private fun makeLog() = CoachToolDef(
        name = "log_drink",
        publicLabel = "Logging your drink",
        description = "Log drinks the user says they had. type: beer (350 ml can), beer_600, draft (chopp), wine (glass), " +
            "spirit (shot), caipirinha, cocktail, or other. count: how many (1–12). hours_ago: when, 0 for now.",
        parameters = JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(mapOf(
                "type" to JsonObject(mapOf("type" to JsonPrimitive("string"), "enum" to JsonArray(TYPES.map { JsonPrimitive(it) }))),
                "count" to JsonObject(mapOf("type" to JsonPrimitive("integer"))),
                "hours_ago" to JsonObject(mapOf("type" to JsonPrimitive("integer"))),
            )),
            "required" to JsonArray(listOf(JsonPrimitive("type"))),
            "additionalProperties" to JsonPrimitive(false),
        )),
        strict = false,
    ) { args, ctx ->
        val db = ctx.db ?: return@CoachToolDef ToolResult("""{"error":"database not available"}""", isError = true)
        if (!DrinkPrefs.enabled) return@CoachToolDef ToolResult(OFF, isError = true)
        val p = runCatching { Json.parseToJsonElement(args).jsonObject }.getOrNull()
            ?: return@CoachToolDef ToolResult("""{"error":"invalid arguments"}""", isError = true)
        val key = p["type"]?.jsonPrimitive?.contentOrNull?.takeIf { it in TYPES }
            ?: return@CoachToolDef ToolResult("""{"error":"type must be one of $TYPES"}""", isError = true)
        val count = (p["count"]?.jsonPrimitive?.intOrNull ?: 1).coerceIn(1, 12)
        val hoursAgo = (p["hours_ago"]?.jsonPrimitive?.intOrNull ?: 0).coerceIn(0, 36)
        val type = DrinkType.of(key)
        kotlinx.coroutines.runBlocking {
            DrinksRepository.log(db, type, count, System.currentTimeMillis() - hoursAgo * 3_600_000L)
        }
        val s = kotlinx.coroutines.runBlocking { DrinksRepository.load(db) }
        ToolResult(buildJsonObject {
            put("ok", true)
            put("logged", "$count × ${type.label}")
            put("today_standard_drinks", DrinkInsights.fmt(s.todayStandard))
            put("week_standard_drinks", DrinkInsights.fmt(s.week.standardDrinks))
            put("weekly_limit", s.weeklyLimit)
            s.planToday?.let { put("tonight_plan", it) }
        }.toString())
    }

    private fun makePlan() = CoachToolDef(
        name = "set_drink_plan",
        publicLabel = "Setting tonight's plan",
        description = "Save how many drinks the user plans to have tonight, once they have agreed to the number (0 for a " +
            "dry night). The Drinks screen then tracks tonight against it.",
        parameters = JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(mapOf("drinks" to JsonObject(mapOf("type" to JsonPrimitive("integer"))))),
            "required" to JsonArray(listOf(JsonPrimitive("drinks"))),
            "additionalProperties" to JsonPrimitive(false),
        )),
    ) { args, ctx ->
        if (ctx.db == null) return@CoachToolDef ToolResult("""{"error":"database not available"}""", isError = true)
        if (!DrinkPrefs.enabled) return@CoachToolDef ToolResult(OFF, isError = true)
        val n = runCatching { Json.parseToJsonElement(args).jsonObject["drinks"]?.jsonPrimitive?.intOrNull }.getOrNull()
            ?.takeIf { it in 0..12 }
            ?: return@CoachToolDef ToolResult("""{"error":"drinks must be 0–12"}""", isError = true)
        val tonight = com.pulseloop.service.MealJournal.eveningOf(System.currentTimeMillis())
        DrinkPrefs.setPlan(tonight, n)
        ToolResult(buildJsonObject { put("ok", true); put("tonight_plan", n) }.toString())
    }
}
