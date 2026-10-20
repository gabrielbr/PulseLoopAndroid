package com.pulseloop.coach.tools

import com.pulseloop.service.MealSuggester
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The next-meal idea, discussed in chat. `suggest_next_meal` returns the same suggestion the
 * Nutrition screen shows (Brazilian everyday food and fruit, sized to what is left of the day);
 * `save_food_preference` records an alternative the user and coach agreed on, so later
 * suggestions use it whenever it still fits the budget.
 */
object MealSuggestionTools {

    val all: List<CoachToolDef> get() = listOf(makeSuggest(), makeSavePreference(), makeListPreferences())

    private val json = Json { encodeDefaults = true }
    private val SLOTS = listOf("breakfast", "lunch", "snack", "dinner", "any")

    private fun makeSuggest() = CoachToolDef(
        name = "suggest_next_meal",
        publicLabel = "Planning your next meal",
        description = "The next-meal suggestion for right now: everyday Brazilian food and common fruit, sized to the " +
            "calories left today and the protein gap, using the user's agreed food preferences. Returns the slot, the " +
            "items with portion, kcal and protein, the total, the kcal it leaves, and which preferences it used.",
        parameters = JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(emptyMap()),
            "required" to JsonArray(emptyList()),
            "additionalProperties" to JsonPrimitive(false),
        )),
    ) { _, ctx ->
        val db = ctx.db ?: return@CoachToolDef ToolResult("""{"error":"database not available"}""", isError = true)
        val s = kotlinx.coroutines.runBlocking { MealSuggester.load(db) }
            ?: return@CoachToolDef ToolResult("""{"error":"no suggestion"}""", isError = true)
        ToolResult(json.encodeToString(MealSuggester.Suggestion.serializer(), s))
    }

    private fun makeSavePreference() = CoachToolDef(
        name = "save_food_preference",
        publicLabel = "Remembering your preference",
        description = "Record a food preference the user and you agreed on while discussing a meal suggestion, e.g. " +
            "banana instead of vegetables at dinner. Only save it once you have checked it still fits their goal " +
            "(calories left, protein) and the user agreed. slot: breakfast, lunch, snack, dinner or any. action: prefer " +
            "or avoid. instead_of: the food or role it replaces (e.g. \"vegetables\"). For a food that isn't common " +
            "Brazilian fare or fruit, give portion, kcal and protein_g for one portion. Saving the same preference " +
            "again strengthens it.",
        parameters = JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(mapOf(
                "slot" to JsonObject(mapOf("type" to JsonPrimitive("string"), "enum" to JsonArray(SLOTS.map { JsonPrimitive(it) }))),
                "item" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "action" to JsonObject(mapOf("type" to JsonPrimitive("string"), "enum" to JsonArray(listOf(JsonPrimitive("prefer"), JsonPrimitive("avoid"))))),
                "instead_of" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "portion" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "kcal" to JsonObject(mapOf("type" to JsonPrimitive("number"))),
                "protein_g" to JsonObject(mapOf("type" to JsonPrimitive("number"))),
                "reason" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
            )),
            "required" to JsonArray(listOf(JsonPrimitive("slot"), JsonPrimitive("item"), JsonPrimitive("action"))),
            "additionalProperties" to JsonPrimitive(false),
        )),
        strict = false,
    ) { args, ctx ->
        val db = ctx.db ?: return@CoachToolDef ToolResult("""{"error":"database not available"}""", isError = true)
        val p = runCatching { Json.parseToJsonElement(args).jsonObject }.getOrNull()
            ?: return@CoachToolDef ToolResult("""{"error":"invalid arguments"}""", isError = true)
        fun str(k: String) = p[k]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val slot = str("slot")?.takeIf { it in SLOTS }
            ?: return@CoachToolDef ToolResult("""{"error":"slot must be one of $SLOTS"}""", isError = true)
        val item = str("item")?.take(60) ?: return@CoachToolDef ToolResult("""{"error":"item is required"}""", isError = true)
        val action = str("action")?.takeIf { it == "prefer" || it == "avoid" }
            ?: return@CoachToolDef ToolResult("""{"error":"action must be prefer or avoid"}""", isError = true)
        val kcal = p["kcal"]?.jsonPrimitive?.doubleOrNull?.takeIf { it in 1.0..2000.0 }
        val row = kotlinx.coroutines.runBlocking {
            MealSuggester.agree(
                db, slot, item, action, str("instead_of")?.take(60), str("portion")?.take(40),
                kcal, p["protein_g"]?.jsonPrimitive?.doubleOrNull?.takeIf { it in 0.0..200.0 }, str("reason")?.take(140),
            )
        }
        ToolResult(buildJsonObject {
            put("ok", true)
            put("slot", row.slot)
            put("item", row.item)
            put("action", row.action)
            row.insteadOf?.let { put("instead_of", it) }
            put("times_agreed", row.timesAgreed)
        }.toString())
    }

    private fun makeListPreferences() = CoachToolDef(
        name = "get_food_preferences",
        publicLabel = "Checking your food preferences",
        description = "List the food preferences already agreed with the user (slot, item, prefer/avoid, what it replaces, times agreed).",
        parameters = JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(emptyMap()),
            "required" to JsonArray(emptyList()),
            "additionalProperties" to JsonPrimitive(false),
        )),
    ) { _, ctx ->
        val db = ctx.db ?: return@CoachToolDef ToolResult("""{"error":"database not available"}""", isError = true)
        val rows = kotlinx.coroutines.runBlocking { db.foodPreferenceDao().all() }
        ToolResult(JsonArray(rows.map { r ->
            buildJsonObject {
                put("slot", r.slot); put("item", r.item); put("action", r.action)
                r.insteadOf?.let { put("instead_of", it) }
                put("times_agreed", r.timesAgreed)
            }
        }).toString())
    }
}
