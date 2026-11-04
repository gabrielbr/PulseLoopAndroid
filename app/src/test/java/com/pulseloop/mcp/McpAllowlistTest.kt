package com.pulseloop.mcp

import com.pulseloop.coach.tools.ActionTools
import com.pulseloop.coach.tools.CoachFeatureFlags
import com.pulseloop.coach.tools.MemoryTools
import com.pulseloop.coach.tools.NutritionTools
import com.pulseloop.coach.tools.ToolExecutionContext
import com.pulseloop.coach.tools.ToolRegistry
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The network gets read tools only. If a new coach tool appears, [EXCLUDED] or the allowlist has
 * to name it, so someone reads its implementation before it can reach the network.
 */
class McpAllowlistTest {
    /** Every tool the coach can ever be given, with all flags on. */
    private val everything: Set<String> = ToolRegistry(
        CoachFeatureFlags(writeToolsEnabled = true, liveMeasurementsEnabled = true, webSearchEnabled = true),
    ).toolSpecs.mapNotNull { (it["name"] as? JsonPrimitive)?.contentOrNull }.toSet()

    /** Tools that write, log, set, delete, measure, go to the network, or show UI. */
    private val writes: Set<String> =
        (ActionTools.writeTools + ActionTools.measurementTools + MemoryTools.all + NutritionTools.writeTools).map { it.name }.toSet() +
            setOf("log_meal", "set_goal", "save_food_preference", "log_drink", "set_drink_plan", "search_food_database")

    private val EXCLUDED = writes + "prepare_chart" // a chart for the coach's own UI: no data in it

    @Test
    fun `no write tool is offered`() {
        val leaked = McpCoachTools.ALLOWLIST intersect writes
        assertTrue("write tools on the network: $leaked", leaked.isEmpty())
        assertTrue(McpCoachTools.SUMMARY !in everything) // the summary can't shadow a coach tool
    }

    @Test
    fun `every coach tool is either allowed or excluded on purpose`() {
        val unreviewed = everything - McpCoachTools.ALLOWLIST - EXCLUDED
        assertTrue("review these tools before they reach the network: $unreviewed", unreviewed.isEmpty())
        val missing = McpCoachTools.ALLOWLIST - everything
        assertTrue("allowlisted names that are no coach tool: $missing", missing.isEmpty())
    }

    @Test
    fun `the read-only registry still has every allowed tool`() {
        val readOnly = ToolRegistry(McpCoachTools.FLAGS)
        McpCoachTools.ALLOWLIST.forEach { assertTrue(it, readOnly.tool(it) != null) }
        assertEquals(false, McpCoachTools.FLAGS.writeToolsEnabled)
        assertEquals(false, McpCoachTools.FLAGS.liveMeasurementsEnabled)
        assertEquals(false, McpCoachTools.FLAGS.webSearchEnabled)
    }

    @Test
    fun `the table lists the allowlist and the summary, and refuses anything else`() {
        val table = McpCoachTools.table(ToolExecutionContext(db = null, flags = McpCoachTools.FLAGS, coordinator = null)) { "SUMMARY" }
        val names = table.tools.map { it.name }.toSet()
        assertEquals(McpCoachTools.ALLOWLIST + McpCoachTools.SUMMARY, names)
        table.tools.forEach { assertEquals(JsonPrimitive("object"), it.inputSchema["type"]) }
        assertEquals("SUMMARY", table.call(McpCoachTools.SUMMARY, "{}")?.text)
        writes.forEach { assertNull(it, table.call(it, "{}")) }
        // With no database a read tool answers an error, through the same path the network uses.
        assertEquals(true, table.call("get_sync_status", "{}")?.isError)
    }
}
