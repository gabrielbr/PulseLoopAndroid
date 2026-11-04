package com.pulseloop.mcp

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpHandlerTest {
    private val table = FakeToolTable()
    private val tokens = McpTokens(MemoryTokenStorage())
    private val token = tokens.create("Claude Desktop", 1_000L).second
    private var on = true
    private val handler = McpHandler(table, tokens, "9.9", isOn = { on })

    private fun post(body: String, t: String? = token) = handler.respond(request(body, t), now = 5_000L)

    @Test
    fun `a web page is refused before anything else, even with a good token`() {
        val r = handler.respond(request(rpc(1, "tools/list"), token, extra = mapOf("origin" to "http://evil.example")))
        assertEquals(403, r.status)
        assertEquals(0, table.calls)
    }

    @Test
    fun `no token, a wrong token, or the server off is 401`() {
        assertEquals(401, post(rpc(1, "tools/list"), t = null).status)
        assertEquals(401, post(rpc(1, "tools/list"), t = "pulseloop_guess").status)
        assertEquals(401, post(rpc(1, "tools/list"), t = token.dropLast(1)).status)
        on = false
        assertEquals(401, post(rpc(1, "tools/list")).status)
    }

    @Test
    fun `only POST to mcp`() {
        assertEquals(405, handler.respond(request("", token, method = "GET")).status)
        assertEquals(404, handler.respond(request(rpc(1, "ping"), token, path = "/other")).status)
    }

    @Test
    fun `initialize names the server, echoes a known protocol and carries the instructions`() {
        val r = post(rpc(1, "initialize", """{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"t","version":"1"}}"""))
        assertEquals(200, r.status)
        val result = parse(r.body).obj("result")
        assertEquals("2025-03-26", result.str("protocolVersion"))
        assertEquals("PulseLoop", result.obj("serverInfo").str("name"))
        assertEquals("9.9", result.obj("serverInfo").str("version"))
        val instructions = result.str("instructions")!!
        assertTrue(instructions.contains("not a diagnostic tool"))
        assertTrue(instructions.contains("estimates"))
        assertTrue(instructions.contains("Read-only"))
        val unknown = parse(post(rpc(2, "initialize", """{"protocolVersion":"1999-01-01"}""")).body).obj("result")
        assertEquals(McpHandler.PROTOCOL, unknown.str("protocolVersion"))
    }

    @Test
    fun `notifications and client responses get 202 and no body`() {
        val n = post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        assertEquals(202, n.status)
        assertEquals("", n.body)
        assertEquals(202, post("""{"jsonrpc":"2.0","result":{}}""").status)
    }

    @Test
    fun `tools list and call round trip`() {
        val tools = parse(post(rpc(1, "tools/list")).body).obj("result").arr("tools")
        assertEquals(listOf("echo", "boom"), tools.map { (it as kotlinx.serialization.json.JsonObject).str("name") })
        assertTrue((tools[0] as kotlinx.serialization.json.JsonObject).containsKey("inputSchema"))

        val call = parse(post(rpc(2, "tools/call", """{"name":"echo","arguments":{"date":"2026-11-04"}}""")).body)
        assertEquals(2, call["id"]!!.jsonPrimitive.int)
        val result = call.obj("result")
        assertEquals(JsonPrimitive(false), result["isError"])
        assertEquals("""args={"date":"2026-11-04"}""", (result.arr("content")[0] as kotlinx.serialization.json.JsonObject).str("text"))

        val noArgs = parse(post(rpc(3, "tools/call", """{"name":"echo"}""")).body).obj("result")
        assertEquals("args={}", (noArgs.arr("content")[0] as kotlinx.serialization.json.JsonObject).str("text"))
    }

    @Test
    fun `an unknown or failing tool is an error result, not a crash`() {
        val unknown = parse(post(rpc(1, "tools/call", """{"name":"set_goal","arguments":{"steps":1}}""")).body).obj("result")
        assertEquals(JsonPrimitive(true), unknown["isError"])
        val boom = parse(post(rpc(2, "tools/call", """{"name":"boom"}""")).body).obj("result")
        assertEquals(JsonPrimitive(true), boom["isError"])
        assertTrue((boom.arr("content")[0] as kotlinx.serialization.json.JsonObject).str("text")!!.contains("IllegalStateException"))
    }

    @Test
    fun `json-rpc errors`() {
        fun code(body: String) = parse(post(body).body).obj("error")["code"]!!.jsonPrimitive.int
        assertEquals(-32700, code("{oops"))
        assertEquals(-32600, code("[1,2]"))
        assertEquals(-32601, code(rpc(1, "resources/list")))
        assertEquals(-32602, code(rpc(1, "tools/call", "{}")))
        assertEquals(-32600, code("""{"jsonrpc":"2.0","id":4}"""))
    }

    @Test
    fun `ping answers an empty result`() {
        val r = parse(post(rpc(7, "ping")).body)
        assertEquals(0, r.obj("result").size)
    }
}
