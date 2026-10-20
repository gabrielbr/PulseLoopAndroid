package com.pulseloop.coach.tools

import com.pulseloop.data.entity.CoachGoalEntity
import com.pulseloop.service.GoalPlan
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.ZoneId

/**
 * The user's goal, set in conversation ("lose 5 kg in 60 days") and planned against all the data
 * the app has: the scale's weight and body composition, the ring's burn, sleep and steps, and the
 * food log. `set_goal` only stores what the user said they want, so it applies immediately; the
 * plan numbers come from [GoalPlan], never from the model.
 */
object GoalTools {

    val all: List<CoachToolDef> get() = listOf(makeGetGoal(), makeSetGoal())

    private val json = Json { encodeDefaults = true }

    private fun makeGetGoal() = CoachToolDef(
        name = "get_goal",
        publicLabel = "Checking your goal",
        description = "Get the user's active goal with progress and a plan computed from their data: " +
            "current vs target weight, the weekly pace the date needs and whether it is safe, the scale's actual " +
            "trend, average calories burned and logged eaten, a suggested daily intake (never below the safe floor), " +
            "a protein target, and average sleep and steps. Returns has_goal=false when none is set.",
        parameters = JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(emptyMap()),
            "required" to JsonArray(emptyList()),
            "additionalProperties" to JsonPrimitive(false),
        )),
    ) { _, ctx ->
        val db = ctx.db ?: return@CoachToolDef ToolResult("""{"error":"database not available"}""", isError = true)
        val plan = kotlinx.coroutines.runBlocking { GoalPlan.load(db) }
            ?: return@CoachToolDef ToolResult("""{"has_goal":false}""")
        val body = json.encodeToJsonElement(GoalPlan.Plan.serializer(), plan).jsonObject
        ToolResult(JsonObject(mapOf("has_goal" to JsonPrimitive(true)) + body).toString())
    }

    private fun makeSetGoal() = CoachToolDef(
        name = "set_goal",
        publicLabel = "Saving your goal",
        description = "Save the goal the user stated, replacing any earlier one. Only call it after the user has " +
            "said what they want (and agreed to any adjusted date you proposed). kind: lose_weight, gain_weight, " +
            "maintain_weight, body_recomp, sleep, fitness or other. description: the goal in the user's words, short. " +
            "For weight goals give target_weight_kg (convert from lb if needed); give target_date as YYYY-MM-DD " +
            "when the user named a timeframe (\"in 60 days\" → today + 60).",
        parameters = JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(mapOf(
                "kind" to JsonObject(mapOf(
                    "type" to JsonPrimitive("string"),
                    "enum" to JsonArray(GoalPlan.ALL_KINDS.sorted().map { JsonPrimitive(it) }),
                )),
                "description" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "target_weight_kg" to JsonObject(mapOf("type" to JsonPrimitive("number"))),
                "target_date" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
            )),
            "required" to JsonArray(listOf(JsonPrimitive("kind"), JsonPrimitive("description"))),
            "additionalProperties" to JsonPrimitive(false),
        )),
        strict = false,
    ) { args, ctx ->
        val db = ctx.db ?: return@CoachToolDef ToolResult("""{"error":"database not available"}""", isError = true)
        val p = runCatching { Json.parseToJsonElement(args).jsonObject }.getOrNull()
            ?: return@CoachToolDef ToolResult("""{"error":"invalid arguments"}""", isError = true)
        val kind = p["kind"]?.jsonPrimitive?.contentOrNull?.takeIf { it in GoalPlan.ALL_KINDS }
            ?: return@CoachToolDef ToolResult("""{"error":"kind must be one of ${GoalPlan.ALL_KINDS.sorted()}"}""", isError = true)
        val description = p["description"]?.jsonPrimitive?.contentOrNull?.trim()?.take(140)?.takeIf { it.isNotEmpty() }
            ?: return@CoachToolDef ToolResult("""{"error":"description is required"}""", isError = true)
        val target = p["target_weight_kg"]?.jsonPrimitive?.doubleOrNull
        if (target != null && target !in 30.0..300.0) {
            return@CoachToolDef ToolResult("""{"error":"target_weight_kg looks wrong (expected kilograms)"}""", isError = true)
        }
        val zone = ZoneId.systemDefault()
        val targetDate = p["target_date"]?.jsonPrimitive?.contentOrNull?.let {
            runCatching { LocalDate.parse(it) }.getOrNull()
                ?: return@CoachToolDef ToolResult("""{"error":"target_date must be YYYY-MM-DD"}""", isError = true)
        }
        if (targetDate != null && !targetDate.isAfter(LocalDate.now(zone))) {
            return@CoachToolDef ToolResult("""{"error":"target_date must be in the future"}""", isError = true)
        }
        val result = kotlinx.coroutines.runBlocking {
            val profile = db.userProfileDao().get()
            GoalPlan.setActive(db, CoachGoalEntity(
                kind = kind,
                description = description,
                startWeightKg = profile?.weightKg?.takeIf { kind in GoalPlan.WEIGHT_KINDS },
                targetWeightKg = target?.takeIf { kind in GoalPlan.WEIGHT_KINDS },
                startDate = System.currentTimeMillis(),
                targetDate = targetDate?.atStartOfDay(zone)?.toInstant()?.toEpochMilli(),
            ))
            GoalPlan.load(db)
        }
        val body = result?.let { json.encodeToJsonElement(GoalPlan.Plan.serializer(), it).jsonObject } ?: JsonObject(emptyMap())
        ToolResult(JsonObject(mapOf("ok" to JsonPrimitive(true)) + body).toString())
    }
}
