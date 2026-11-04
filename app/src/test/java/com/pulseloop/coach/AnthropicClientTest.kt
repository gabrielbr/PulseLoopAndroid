package com.pulseloop.coach.anthropic

import com.pulseloop.coach.config.CoachClientResolver
import com.pulseloop.coach.config.CoachProviderMode
import com.pulseloop.coach.config.CoachProviderSettings
import com.pulseloop.coach.config.ReasoningEfforts
import com.pulseloop.coach.openai.FunctionCallOutput
import com.pulseloop.coach.openai.MessageOutput
import com.pulseloop.coach.openai.ResponsesError
import com.pulseloop.coach.openai.TextContent
import com.pulseloop.coach.orchestration.CoachResponseSchema
import com.pulseloop.coach.usage.CoachPricingCatalog
import com.pulseloop.coach.usage.CoachTokenUsage
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Responses request → Messages request and back, with no network. */
class AnthropicClientTest {

    private fun msg(role: String, content: JsonElement) = JsonObject(mapOf(
        "role" to JsonPrimitive(role), "content" to content,
    ))
    private fun msg(role: String, content: String) = msg(role, JsonPrimitive(content))

    private fun request(
        input: List<JsonObject>,
        tools: List<JsonObject> = emptyList(),
        schema: JsonObject? = null,
        previous: String? = null,
        effort: String? = null,
    ) = JsonObject(buildMap {
        put("model", JsonPrimitive("claude-opus-5-5"))
        put("input", JsonArray(input))
        put("tools", JsonArray(tools))
        schema?.let { put("text", JsonObject(mapOf("format" to JsonObject(mapOf(
            "type" to JsonPrimitive("json_schema"), "name" to JsonPrimitive("x"),
            "strict" to JsonPrimitive(true), "schema" to it,
        ))))) }
        previous?.let { put("previous_response_id", JsonPrimitive(it)) }
        effort?.let { put("reasoning", JsonObject(mapOf("effort" to JsonPrimitive(it)))) }
    })

    private val tool = JsonObject(mapOf(
        "type" to JsonPrimitive("function"),
        "name" to JsonPrimitive("get_hr"),
        "description" to JsonPrimitive("Heart rate"),
        "parameters" to JsonObject(mapOf("type" to JsonPrimitive("object"), "properties" to JsonObject(emptyMap()))),
        "strict" to JsonPrimitive(true),
    ))

    private fun reply(vararg blocks: JsonObject, id: String = "msg_1", stop: String = "end_turn") = JsonObject(mapOf(
        "id" to JsonPrimitive(id),
        "type" to JsonPrimitive("message"),
        "role" to JsonPrimitive("assistant"),
        "content" to JsonArray(blocks.toList()),
        "stop_reason" to JsonPrimitive(stop),
        "usage" to JsonObject(mapOf(
            "input_tokens" to JsonPrimitive(100),
            "cache_read_input_tokens" to JsonPrimitive(900),
            "cache_creation_input_tokens" to JsonPrimitive(0),
            "output_tokens" to JsonPrimitive(50),
        )),
    ))

    private fun text(t: String) = JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive(t)))
    private val thinking = JsonObject(mapOf(
        "type" to JsonPrimitive("thinking"), "thinking" to JsonPrimitive(""), "signature" to JsonPrimitive("sig"),
    ))
    private fun toolUse(id: String) = JsonObject(mapOf(
        "type" to JsonPrimitive("tool_use"), "id" to JsonPrimitive(id), "name" to JsonPrimitive("get_hr"),
        "input" to JsonObject(mapOf("days" to JsonPrimitive(7))),
    ))

    private fun JsonObject.messages() = this["messages"]!!.jsonArray.map { it.jsonObject }

    @Test
    fun systemItemsBecomeTopLevelSystemAndNoSamplingParams() {
        val body = AnthropicClient("k").buildRequestBody(request(listOf(
            msg("developer", "Be a coach."), msg("system", "Context."), msg("user", "Hi"),
        )))
        assertEquals("Be a coach.\n\nContext.", body["system"]!!.jsonPrimitive.content)
        val messages = body.messages()
        assertEquals(1, messages.size)
        assertEquals("user", messages[0]["role"]!!.jsonPrimitive.content)
        assertEquals(16000, body["max_tokens"]!!.jsonPrimitive.int)
        for (k in listOf("temperature", "top_p", "top_k", "tool_choice", "thinking")) assertFalse(k, k in body)
        assertTrue("cache_control" in body)
    }

    @Test
    fun toolsTranslateWithoutStrictAndWebSearchIsDropped() {
        val webSearch = JsonObject(mapOf("type" to JsonPrimitive("web_search")))
        val body = AnthropicClient("k").buildRequestBody(request(listOf(msg("user", "Hi")), listOf(tool, webSearch)))
        val tools = body["tools"]!!.jsonArray.map { it.jsonObject }
        assertEquals(1, tools.size)
        assertEquals("get_hr", tools[0]["name"]!!.jsonPrimitive.content)
        assertEquals("object", tools[0]["input_schema"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse("strict" in tools[0])
    }

    @Test
    fun effortAndSchemaGoInOutputConfig() {
        val schema = JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(mapOf("a" to JsonObject(mapOf("type" to JsonPrimitive("string"))))),
        ))
        val body = AnthropicClient("k").buildRequestBody(request(listOf(msg("user", "Hi")), schema = schema, effort = "high"))
        val config = body["output_config"]!!.jsonObject
        assertEquals("high", config["effort"]!!.jsonPrimitive.content)
        assertEquals("json_schema", config["format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(false), config["format"]!!.jsonObject["schema"]!!.jsonObject["additionalProperties"])
    }

    @Test
    fun anEffortClaudeDoesNotTakeIsDropped() {
        val body = AnthropicClient("k").buildRequestBody(request(listOf(msg("user", "Hi")), effort = "none"))
        assertFalse("output_config" in body)
    }

    @Test
    fun schemaLosesUnsupportedKeywordsButKeepsPropertyNames() {
        val schema = Json.parseToJsonElement("""
            {"type":"object","additionalProperties":true,"properties":{
              "format":{"type":"string","maxLength":40,"format":"date"},
              "pattern":{"type":"integer","minimum":0,"maximum":10},
              "list":{"type":"array","minItems":1,"maxItems":3,"items":{"type":"object","properties":{"x":{"type":"number","multipleOf":1}}}},
              "pick":{"oneOf":[{"type":"string","pattern":"^a"},{"type":"null"}]},
              "odd":{"type":"string","format":"phone"}
            },"required":["format"]}
        """).jsonObject
        val safe = AnthropicClient.claudeSafeSchema(schema).toString()
        for (k in listOf("maxLength", "minimum", "maximum", "minItems", "maxItems", "multipleOf", "\"pattern\":\"", "oneOf", "phone"))
            assertFalse(k, k in safe)
        val props = AnthropicClient.claudeSafeSchema(schema)["properties"]!!.jsonObject
        assertEquals(setOf("format", "pattern", "list", "pick", "odd"), props.keys)
        assertEquals("date", props["format"]!!.jsonObject["format"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(false), props["list"]!!.jsonObject["items"]!!.jsonObject["additionalProperties"])
        assertTrue("anyOf" in props["pick"]!!.jsonObject)
        assertTrue("required" in AnthropicClient.claudeSafeSchema(schema))
    }

    @Test
    fun theCoachResponseSchemaComesOutClaudeSafe() {
        val safe = AnthropicClient.claudeSafeSchema(CoachResponseSchema.schema).toString()
        for (k in listOf("\"maxLength\"", "\"minLength\"", "\"minimum\"", "\"maximum\"", "\"minItems\"", "\"maxItems\"", "\"additionalProperties\":true"))
            assertFalse(k, k in safe)
    }

    @Test
    fun imagesBecomeBase64Blocks() {
        val parts = JsonArray(listOf(
            JsonObject(mapOf("type" to JsonPrimitive("input_text"), "text" to JsonPrimitive("What is this?"))),
            JsonObject(mapOf("type" to JsonPrimitive("input_image"), "image_url" to JsonPrimitive("data:image/jpeg;base64,QUJD"))),
        ))
        val body = AnthropicClient("k").buildRequestBody(request(listOf(msg("user", parts))))
        val blocks = body.messages()[0]["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals("text", blocks[0]["type"]!!.jsonPrimitive.content)
        val source = blocks[1]["source"]!!.jsonObject
        assertEquals("base64", source["type"]!!.jsonPrimitive.content)
        assertEquals("image/jpeg", source["media_type"]!!.jsonPrimitive.content)
        assertEquals("QUJD", source["data"]!!.jsonPrimitive.content)
    }

    @Test
    fun historyStartingOnTheCoachGetsAUserOpenerAndEmptyTextIsDropped() {
        val body = AnthropicClient("k").buildRequestBody(request(listOf(
            msg("assistant", "Good morning!"), msg("user", ""), msg("user", "Hi"),
        )))
        val messages = body.messages()
        assertEquals(listOf("user", "assistant", "user"), messages.map { it["role"]!!.jsonPrimitive.content })
    }

    @Test
    fun toolLoopReplaysClaudesContentUnchangedThenResults() {
        val client = AnthropicClient("k")
        client.buildRequestBody(request(listOf(msg("user", "How did I sleep?")), listOf(tool)))
        val first = client.ingestResponse(reply(thinking, text("Let me check."), toolUse("toolu_1"), stop = "tool_use"))
        val call = first.output.filterIsInstance<FunctionCallOutput>().single()
        assertEquals("toolu_1", call.callId)
        assertEquals("""{"days":7}""", call.arguments)

        val output = JsonObject(mapOf(
            "type" to JsonPrimitive("function_call_output"),
            "call_id" to JsonPrimitive("toolu_1"),
            "output" to JsonPrimitive("""{"hr":60}"""),
        ))
        val body = client.buildRequestBody(request(listOf(output), listOf(tool), previous = first.id))
        val messages = body.messages()
        assertEquals(3, messages.size)
        // The assistant turn goes back exactly as it came, thinking block and all.
        assertEquals(JsonArray(listOf(thinking, text("Let me check."), toolUse("toolu_1"))), messages[1]["content"])
        val result = messages[2]["content"]!!.jsonArray[0].jsonObject
        assertEquals("tool_result", result["type"]!!.jsonPrimitive.content)
        assertEquals("toolu_1", result["tool_use_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun aRepairPromptJoinsTheUserTurnAfterToolResults() {
        val client = AnthropicClient("k")
        client.buildRequestBody(request(listOf(msg("user", "Hi"))))
        val first = client.ingestResponse(reply(text("not json")))
        val body = client.buildRequestBody(request(listOf(msg("user", "Return JSON.")), previous = first.id))
        assertEquals(listOf("user", "assistant", "user"), body.messages().map { it["role"]!!.jsonPrimitive.content })
    }

    @Test
    fun textAndUsageAreRead() {
        val res = AnthropicClient("k").ingestResponse(reply(thinking, text("{\"a\":"), text("1}")))
        val msg = res.output.single() as MessageOutput
        assertEquals("{\"a\":1}", (msg.content.single() as TextContent).text)
        assertEquals(CoachTokenUsage(inputTokens = 1000, outputTokens = 50, cachedInputTokens = 900), res.usage)
    }

    @Test
    fun refusalAndCutOffAreErrors() {
        val refusal = JsonObject(mapOf("id" to JsonPrimitive("m"), "content" to JsonArray(emptyList()), "stop_reason" to JsonPrimitive("refusal")))
        val e = assertThrows(ResponsesError.Decoding::class.java) { AnthropicClient("k").ingestResponse(refusal) }
        assertTrue(e.msg.contains("Claude"))
        assertThrows(ResponsesError.Decoding::class.java) {
            AnthropicClient("k").ingestResponse(reply(thinking, stop = "max_tokens"))
        }
    }

    @Test
    fun resolverPicksClaudeWithItsKey() {
        val s = CoachProviderSettings(providerMode = CoachProviderMode.USER_ANTHROPIC_KEY)
        val res = CoachClientResolver.resolve(s, openAIKey = "sk", geminiKey = null, openRouterKey = null, anthropicKey = "sk-ant")
        assertEquals("sk-ant", res.key)
        assertTrue(res.client is AnthropicClient)
        assertNull(CoachClientResolver.resolve(s, openAIKey = "sk", geminiKey = null, openRouterKey = null, anthropicKey = " ").key)
        assertEquals("claude-opus-5-5", CoachClientResolver.activeModel(s, "gpt-6.1-sol"))
    }

    @Test
    fun claudeModelsHaveEffortsAndPrices() {
        assertEquals(5, ReasoningEfforts.options(CoachProviderMode.USER_ANTHROPIC_KEY, "claude-sonnet-5-5").size)
        val usage = CoachTokenUsage(inputTokens = 1_000_000, outputTokens = 0)
        assertEquals(4.0, CoachPricingCatalog.cost("claude-opus-5-5", usage)!!, 1e-9)
    }
}
