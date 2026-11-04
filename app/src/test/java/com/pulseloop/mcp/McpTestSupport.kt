package com.pulseloop.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Pairings kept in memory. */
class MemoryTokenStorage : McpTokenStorage {
    var tools: List<PairedTool> = emptyList()
    override fun loadTools(): List<PairedTool> = tools
    override fun saveTools(tools: List<PairedTool>) { this.tools = tools }
}

/** A two-tool table: `echo` returns its arguments, `boom` throws. */
class FakeToolTable : McpToolTable {
    var calls = 0
    private val schema = JsonObject(mapOf("type" to JsonPrimitive("object"), "properties" to JsonObject(emptyMap())))
    override val tools = listOf(McpTool("echo", "Echoes its arguments.", schema), McpTool("boom", "Fails.", schema))
    override fun call(name: String, argumentsJson: String): McpToolOutput? {
        calls++
        return when (name) {
            "echo" -> McpToolOutput("args=$argumentsJson")
            "boom" -> throw IllegalStateException("kaboom")
            else -> null
        }
    }
}

fun rpc(id: Int, method: String, params: String? = null): String =
    """{"jsonrpc":"2.0","id":$id,"method":"$method"${if (params == null) "" else ""","params":$params"""}}"""

fun request(body: String, token: String?, method: String = "POST", path: String = "/mcp", extra: Map<String, String> = emptyMap()) =
    McpHttp.Request(
        method = method,
        path = path,
        headers = buildMap {
            if (token != null) put("authorization", "Bearer $token")
            putAll(extra)
        },
        body = body,
    )

fun parse(body: String): JsonObject = Json.parseToJsonElement(body) as JsonObject

fun JsonObject.obj(key: String): JsonObject = this[key] as JsonObject
fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content
fun JsonObject.arr(key: String): JsonArray = this[key] as JsonArray
