package com.pulseloop.mcp

import com.pulseloop.coach.tools.CoachFeatureFlags
import com.pulseloop.coach.tools.ToolExecutionContext
import com.pulseloop.coach.tools.ToolRegistry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The tools offered on the network: the coach's own read tools, listed from the same
 * `CoachToolDef` schemas (nothing has a second definition), plus [SUMMARY].
 *
 * **An explicit allowlist, not "everything the read-only registry holds".** `ToolRegistry`'s
 * always-on set already contains writes (`log_meal`, `set_goal`, `save_food_preference`,
 * `log_drink`, `set_drink_plan`) and a network call that writes a cache (`search_food_database`),
 * so turning `writeToolsEnabled` off is not enough. Each name below was read in its
 * implementation and only reads Room (or, for the analysis tools, only computes over the numbers
 * the caller passes). `McpAllowlistTest` fails if a write tool's name ever lands here.
 *
 * Never offered: anything that writes, sets goals, logs, deletes, queues a PendingAction, starts
 * a live ring measurement or searches the web; the coach's API keys, raw ring packets,
 * diagnostics, lab-report PDFs and the EPDS answers have no tool at all.
 */
object McpCoachTools {
    const val SUMMARY = "summary"

    val ALLOWLIST: Set<String> = setOf(
        // Ring data (RetrievalTools): each reads Room through the DAOs, nothing else.
        "get_profile_context", "get_daily_summary", "get_range_summary", "get_metric_series",
        "get_activity_sessions", "summarize_activity_session", "get_sync_status",
        "get_data_availability", "get_sleep_summary", "get_sleep_trends",
        "get_goal_progress", "get_recent_anomalies",
        // Pure arithmetic over the values the caller passes (AnalysisTools): no data access at all.
        "analyze_trend", "compare_periods", "compute_correlation", "detect_outliers", "summarize_distribution",
        // Logged data, read only: the food log, the goal plan, the next-meal idea, agreed
        // preferences, and drinks (which refuses while Drinks is off).
        "get_nutrition_log", "get_goal", "suggest_next_meal", "get_food_preferences", "get_drinks",
    )

    /** The flags every network call runs with: no writes, no live measurements, no web. */
    val FLAGS = CoachFeatureFlags(writeToolsEnabled = false, liveMeasurementsEnabled = false, webSearchEnabled = false)

    private val SUMMARY_TOOL = McpTool(
        name = SUMMARY,
        description = "A compact picture in one call: today's activity against the goals, last night's sleep, " +
            "the latest heart rate and SpO2, the last 7 days, the ring's sync state, and data-quality notes.",
        inputSchema = JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(emptyMap()),
            "required" to JsonArray(emptyList()),
            "additionalProperties" to JsonPrimitive(false),
        )),
    )

    /**
     * The table, built from [registry] (always constructed with [FLAGS] in the app) and the
     * context the tools run with. [context] must carry `coordinator = null` and the [FLAGS].
     */
    fun table(
        context: ToolExecutionContext,
        registry: ToolRegistry = ToolRegistry(FLAGS),
        summary: () -> String,
    ): McpToolTable {
        val defs = ALLOWLIST.mapNotNull { registry.tool(it) }.sortedBy { it.name }
        val listed = defs.map { McpTool(it.name, it.description, it.parameters) } + SUMMARY_TOOL
        return object : McpToolTable {
            override val tools: List<McpTool> = listed

            override fun call(name: String, argumentsJson: String): McpToolOutput? {
                if (name == SUMMARY) return McpToolOutput(summary())
                if (name !in ALLOWLIST) return null
                val def = registry.tool(name) ?: return null
                // A fresh context per call: nothing a tool queues (it shouldn't) survives the call.
                val result = def.run(argumentsJson, context.copy(pendingActions = mutableListOf()))
                return McpToolOutput(result.jsonString, result.isError)
            }
        }
    }
}
