package com.pulseloop.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** One tool as MCP lists it. */
data class McpTool(val name: String, val description: String, val inputSchema: JsonObject)

/** What a tool call returned: text for the client, and whether it is an error. */
data class McpToolOutput(val text: String, val isError: Boolean = false)

/** The tools the server offers. Real: [McpCoachTools]; tests pass a fake. */
interface McpToolTable {
    val tools: List<McpTool>

    /** Runs [name] with [argumentsJson] (a JSON object); null when there is no such tool here. */
    fun call(name: String, argumentsJson: String): McpToolOutput?
}

/**
 * MCP over streamable HTTP, one JSON reply per request: `POST /mcp` with one JSON-RPC 2.0 message.
 * Refuses, in this order and before reading anything: a request with an `Origin` header (a web
 * page), a missing or unknown bearer token, while the server is off. Then only `POST /mcp`.
 * Methods: `initialize`, `ping`, `tools/list`, `tools/call`; notifications get 202.
 *
 * Plain Kotlin (no Android imports), JVM-tested (`McpHandlerTest`). Ported from GMind's
 * `McpServer.respond`.
 */
class McpHandler(
    private val table: McpToolTable,
    private val tokens: McpTokens,
    private val version: String,
    private val isOn: () -> Boolean = { true },
) {
    /** One tool call at a time: the database and the coach's tools are shared with the app. */
    private val calls = Semaphore(1)

    fun respond(request: McpHttp.Request, now: Long = System.currentTimeMillis()): McpHttp.Response = try {
        respondUnchecked(request, now)
    } catch (e: Exception) {
        json(error(JsonNull, -32603, "PulseLoop couldn't answer (${e.javaClass.simpleName})"))
    }

    private fun respondUnchecked(request: McpHttp.Request, now: Long): McpHttp.Response {
        val h = request.headers
        if (h.containsKey("origin")) return McpHttp.Response(403) // a web page: never
        if (!isOn() || tokens.check(McpTokens.bearer(h["authorization"]), now) == null) return McpHttp.Response(401)
        if (request.path != "/mcp") return McpHttp.Response(404)
        if (request.method != "POST") return McpHttp.Response(405)

        val message: JsonElement = try {
            Json.parseToJsonElement(request.body)
        } catch (_: Exception) {
            return json(error(JsonNull, -32700, "Parse error"))
        }
        if (message !is JsonObject) return json(error(JsonNull, -32600, "One request at a time"))
        val id = message["id"]?.takeIf { it !is JsonNull }
        val method = (message["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (method == null) {
            // A JSON-RPC response or result from the client: nothing to answer.
            return if (id == null) McpHttp.Response(202) else json(error(id, -32600, "Invalid request"))
        }
        if (id == null) return McpHttp.Response(202) // a notification, e.g. notifications/initialized
        val params = message["params"] as? JsonObject

        return when (method) {
            "initialize" -> json(result(id, initializeResult(params)))
            "ping" -> json(result(id, JsonObject(emptyMap())))
            "tools/list" -> json(result(id, buildJsonObject { put("tools", JsonArray(table.tools.map(::toolJson))) }))
            "tools/call" -> {
                val name = (params?.get("name") as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: return json(error(id, -32602, "Missing tool name"))
                val args = params?.get("arguments")
                val argsJson = if (args is JsonObject) args.toString() else "{}"
                json(result(id, callResult(call(name, argsJson))))
            }
            else -> json(error(id, -32601, "Method not found"))
        }
    }

    private fun call(name: String, argsJson: String): McpToolOutput {
        if (!calls.tryAcquire(CALL_WAIT_SECONDS, TimeUnit.SECONDS)) {
            return McpToolOutput("PulseLoop is busy with another request. Try again.", isError = true)
        }
        return try {
            table.call(name, argsJson)
                ?: McpToolOutput("No such tool here. PulseLoop's tools on the network only read.", isError = true)
        } catch (e: Exception) {
            McpToolOutput("That tool failed (${e.javaClass.simpleName}).", isError = true)
        } finally {
            calls.release()
        }
    }

    private fun initializeResult(params: JsonObject?): JsonObject {
        val asked = (params?.get("protocolVersion") as? JsonPrimitive)?.contentOrNull
        return buildJsonObject {
            put("protocolVersion", if (asked != null && asked in SUPPORTED) asked else PROTOCOL)
            put("capabilities", buildJsonObject { put("tools", buildJsonObject { put("listChanged", false) }) })
            put("serverInfo", buildJsonObject { put("name", "PulseLoop"); put("version", version) })
            put("instructions", INSTRUCTIONS)
        }
    }

    companion object {
        const val PROTOCOL = "2025-06-18"
        val SUPPORTED = setOf("2025-06-18", "2025-03-26", "2024-11-05")
        const val CALL_WAIT_SECONDS = 20L
        /** Big enough for a year of daily rows; a reply past it is cut, and says so. */
        const val MAX_TOOL_TEXT = 200_000

        val INSTRUCTIONS = "PulseLoop holds this user's own health data: readings from their smart ring (steps, " +
            "heart rate, SpO2, sleep, workouts) and what they logged in the app (meals, drinks, goals). " +
            "Read-only: nothing here can change or delete anything. It is not a diagnostic tool: the numbers " +
            "are estimates from a consumer wearable, not medical measurements. Don't diagnose or name " +
            "conditions; for anything worrying, suggest the user talks to their doctor. Dates are YYYY-MM-DD " +
            "in the user's time zone. Start with `summary` for today, last night and the week."

        fun toolJson(t: McpTool): JsonObject = buildJsonObject {
            put("name", t.name)
            put("description", t.description)
            put("inputSchema", t.inputSchema)
        }

        fun callResult(out: McpToolOutput): JsonObject {
            val text = if (out.text.length > MAX_TOOL_TEXT) out.text.take(MAX_TOOL_TEXT) + "\n[cut: reply too long]" else out.text
            return buildJsonObject {
                put("content", JsonArray(listOf(buildJsonObject { put("type", "text"); put("text", text) })))
                put("isError", out.isError)
            }
        }

        private fun result(id: JsonElement, result: JsonObject) = buildJsonObject {
            put("jsonrpc", "2.0"); put("id", id); put("result", result)
        }

        private fun error(id: JsonElement, code: Int, message: String) = buildJsonObject {
            put("jsonrpc", "2.0"); put("id", id)
            put("error", buildJsonObject { put("code", code); put("message", message) })
        }

        private fun json(reply: JsonObject) = McpHttp.Response(200, reply.toString())
    }
}
