package com.pulseloop.coach.anthropic

import com.pulseloop.coach.config.ClaudeModel
import com.pulseloop.coach.openai.FunctionCallOutput
import com.pulseloop.coach.openai.MessageOutput
import com.pulseloop.coach.openai.OpenAIResponse
import com.pulseloop.coach.openai.ResponseOutputItem
import com.pulseloop.coach.openai.ResponsesClient
import com.pulseloop.coach.openai.ResponsesError
import com.pulseloop.coach.openai.ResponsesHttp
import com.pulseloop.coach.openai.ResponsesToolSpecs
import com.pulseloop.coach.openai.TextContent
import kotlinx.serialization.json.*
import java.util.UUID

/**
 * Runs the coach on Claude through Anthropic's **Messages API** (`POST /v1/messages`), with the
 * user's own key. Like the other adapters it takes the app's OpenAI Responses request and
 * translates it, so the orchestrator, tools and confirm cards are unchanged.
 *
 * | Responses request                   | Messages request                                     |
 * |-------------------------------------|------------------------------------------------------|
 * | `developer` / `system` items        | top-level `system` (merged, in order)                |
 * | `input_text` / `input_image` parts  | `text` / `image` blocks (data URLs become base64)    |
 * | `function` tools                    | `{name, description, input_schema}`                  |
 * | `function_call_output` items        | `tool_result` blocks in one user message             |
 * | `text.format` json_schema           | `output_config.format`, schema made Claude-safe      |
 * | `reasoning.effort`                  | `output_config.effort`                               |
 *
 * Rules the API sets, each of which is a 400 if broken:
 * - **No sampling parameters** (`temperature`, `top_p`, `top_k`) on API version 2026-10-21, and
 *   no prefill. Forced `tool_choice` is rejected by the 5.5 models, so it is never sent (`auto`).
 * - **The assistant's content goes back unchanged**, thinking blocks included: the 5.5 models tie
 *   thinking to the conversation, and an edited one fails the next request. Each reply's whole
 *   `content` array is stored by response id and replayed before its tool results.
 * - **Structured-output schemas** need `additionalProperties: false` on every object and take no
 *   numeric, length or array-size constraints ([claudeSafeSchema]).
 *
 * Not sent: hosted web search (Settings hides the switch for Claude, as for MiniMax), the server
 * fallback beta. Prompt caching is on (top-level `cache_control`): a coach turn re-sends the same
 * system prompt and tool list up to fifteen times in its tool loop.
 *
 * Stateful per turn, like `MiniMaxClient`; the orchestrator gets a fresh client per turn.
 */
class AnthropicClient(
    private val apiKey: String,
    private val model: String = ClaudeModel.DEFAULT.slug,
    private val endpoint: String = "https://api.anthropic.com/v1/messages",
    private val maxTokens: Int = DEFAULT_MAX_TOKENS,
) : ResponsesClient {
    private val json = Json { ignoreUnknownKeys = true }

    private var system: String = ""
    private var messages = mutableListOf<JsonObject>()
    /** Response id → the assistant message exactly as Claude returned it. */
    private val storedAssistant = mutableMapOf<String, JsonObject>()

    override suspend fun send(requestBody: ByteArray): OpenAIResponse {
        if (apiKey.isBlank()) throw ResponsesError.MissingAPIKey
        val req = try {
            json.parseToJsonElement(String(requestBody)).jsonObject
        } catch (_: Exception) {
            throw ResponsesError.Decoding("AnthropicClient: invalid request body")
        }
        val body = buildRequestBody(req)
        val responseBody = ResponsesHttp.post(
            endpoint,
            json.encodeToString(JsonObject.serializer(), body).toByteArray(),
            mapOf("x-api-key" to apiKey, "anthropic-version" to API_VERSION),
            readTimeoutSeconds = READ_TIMEOUT_SECONDS,
        )
        val root = try {
            json.parseToJsonElement(responseBody).jsonObject
        } catch (_: Exception) {
            throw ResponsesError.Decoding("AnthropicClient: response was not a JSON object")
        }
        return ingestResponse(root)
    }

    // ── Request (internal for unit tests) ────────────────────────────────

    internal fun buildRequestBody(req: JsonObject): JsonObject {
        val input = (req["input"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        val tools = (req["tools"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        val previousId = (req["previous_response_id"] as? JsonPrimitive)?.contentOrNull

        if (previousId == null) setupConversation(input) else appendContinuation(previousId, input)

        val body = mutableMapOf<String, JsonElement>(
            "model" to JsonPrimitive(model),
            "max_tokens" to JsonPrimitive(maxTokens),
            "messages" to JsonArray(merged(messages)),
            "cache_control" to JsonObject(mapOf("type" to JsonPrimitive("ephemeral"))),
        )
        if (system.isNotBlank()) body["system"] = JsonPrimitive(system)
        val claudeTools = convertTools(tools)
        if (claudeTools.isNotEmpty()) body["tools"] = JsonArray(claudeTools)

        val outputConfig = mutableMapOf<String, JsonElement>()
        effort(req)?.let { outputConfig["effort"] = JsonPrimitive(it) }
        callerSchema(req)?.let { schema ->
            outputConfig["format"] = JsonObject(mapOf(
                "type" to JsonPrimitive("json_schema"),
                "schema" to claudeSafeSchema(schema),
            ))
        }
        if (outputConfig.isNotEmpty()) body["output_config"] = JsonObject(outputConfig)
        return JsonObject(body)
    }

    private fun setupConversation(input: List<JsonObject>) {
        messages = mutableListOf()
        storedAssistant.clear()
        val systemParts = mutableListOf<String>()
        for (item in input) {
            val role = (item["role"] as? JsonPrimitive)?.contentOrNull ?: continue
            if (role == "system" || role == "developer") {
                textOf(item["content"]).takeIf { it.isNotBlank() }?.let { systemParts += it }
            } else {
                addMessage(role, item["content"])
            }
        }
        system = systemParts.joinToString("\n\n")
    }

    /** Replays Claude's own reply for [previousId], then the tool results and any new message. */
    private fun appendContinuation(previousId: String, input: List<JsonObject>) {
        storedAssistant[previousId]?.let { messages.add(it) }
        val results = mutableListOf<JsonObject>()
        for (item in input) {
            val type = (item["type"] as? JsonPrimitive)?.contentOrNull
            val callId = (item["call_id"] as? JsonPrimitive)?.contentOrNull
            val output = (item["output"] as? JsonPrimitive)?.contentOrNull
            if (type == "function_call_output" && callId != null) {
                results += JsonObject(mapOf(
                    "type" to JsonPrimitive("tool_result"),
                    "tool_use_id" to JsonPrimitive(callId),
                    "content" to JsonPrimitive(output?.ifEmpty { "(empty)" } ?: "(empty)"),
                ))
                continue
            }
            val role = (item["role"] as? JsonPrimitive)?.contentOrNull ?: continue
            if (results.isNotEmpty()) { messages.add(userBlocks(results.toList())); results.clear() }
            if (role == "system" || role == "developer") {
                // A mid-turn instruction rides as user text: editing `system` would reprocess the
                // whole cached prefix and break the thinking the history carries.
                addMessage("user", item["content"])
            } else addMessage(role, item["content"])
        }
        if (results.isNotEmpty()) messages.add(userBlocks(results))
    }

    private fun userBlocks(blocks: List<JsonObject>) = JsonObject(mapOf(
        "role" to JsonPrimitive("user"),
        "content" to JsonArray(blocks),
    ))

    private fun addMessage(role: String, content: JsonElement?) {
        val claudeRole = if (role == "assistant") "assistant" else "user"
        val blocks = blocksOf(content)
        if (blocks.isEmpty()) return
        messages.add(JsonObject(mapOf(
            "role" to JsonPrimitive(claudeRole),
            "content" to JsonArray(blocks),
        )))
    }

    /** Responses content (a string, or `input_text` / `input_image` parts) → Claude blocks. Empty
     *  text is dropped: the API rejects an empty text block. */
    private fun blocksOf(content: JsonElement?): List<JsonObject> {
        if (content is JsonPrimitive) {
            val text = content.contentOrNull.orEmpty()
            return if (text.isBlank()) emptyList() else listOf(textBlock(text))
        }
        val parts = (content as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return emptyList()
        return parts.mapNotNull { part ->
            when ((part["type"] as? JsonPrimitive)?.contentOrNull) {
                "input_text", "output_text", "text" ->
                    (part["text"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { textBlock(it) }
                "input_image" -> (part["image_url"] as? JsonPrimitive)?.contentOrNull?.let { imageBlock(it) }
                else -> null
            }
        }
    }

    private fun textOf(content: JsonElement?): String = when (content) {
        is JsonPrimitive -> content.contentOrNull.orEmpty()
        is JsonArray -> content.mapNotNull { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull }
            .joinToString("\n")
        else -> ""
    }

    private fun textBlock(text: String) = JsonObject(mapOf(
        "type" to JsonPrimitive("text"),
        "text" to JsonPrimitive(text),
    ))

    /** A `data:image/jpeg;base64,…` URL becomes a base64 source; anything else is passed as a URL. */
    internal fun imageBlock(url: String): JsonObject {
        val source = DATA_URL.matchEntire(url)?.let { m ->
            JsonObject(mapOf(
                "type" to JsonPrimitive("base64"),
                "media_type" to JsonPrimitive(m.groupValues[1]),
                "data" to JsonPrimitive(m.groupValues[2]),
            ))
        } ?: JsonObject(mapOf("type" to JsonPrimitive("url"), "url" to JsonPrimitive(url)))
        return JsonObject(mapOf("type" to JsonPrimitive("image"), "source" to source))
    }

    /** Consecutive messages of one role become one: tool results and a repair prompt can both land
     *  in the user turn, and the API wants turns to alternate. */
    internal fun merged(list: List<JsonObject>): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        // The conversation opens with the user; a history that starts on a coach check-in gets a
        // one-line opener rather than a 400.
        if (list.firstOrNull()?.get("role") == JsonPrimitive("assistant"))
            out.add(userBlocks(listOf(textBlock("(Earlier in this conversation.)"))))
        for (m in list) {
            val last = out.lastOrNull()
            if (last != null && last["role"] == m["role"]) {
                val blocks = (last["content"] as JsonArray) + (m["content"] as JsonArray)
                // Tool results must lead the user turn that answers them.
                val (results, rest) = blocks.partition { (it as? JsonObject)?.get("type") == JsonPrimitive("tool_result") }
                out[out.lastIndex] = JsonObject(mapOf("role" to m["role"]!!, "content" to JsonArray(results + rest)))
            } else out.add(m)
        }
        return out
    }

    private fun convertTools(tools: List<JsonObject>): List<JsonObject> =
        // Hosted web search is dropped: Settings hides it for this provider.
        ResponsesToolSpecs.parse(tools).functions.map { spec ->
            val t = mutableMapOf<String, JsonElement>("name" to JsonPrimitive(spec.name))
            spec.description?.let { t["description"] = JsonPrimitive(it) }
            t["input_schema"] = spec.parameters ?: JsonObject(mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(emptyMap()),
            ))
            JsonObject(t)
        }

    private fun effort(req: JsonObject): String? =
        ((req["reasoning"] as? JsonObject)?.get("effort") as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it in CLAUDE_EFFORTS }

    private fun callerSchema(req: JsonObject): JsonObject? {
        val format = (req["text"] as? JsonObject)?.get("format") as? JsonObject ?: return null
        if ((format["type"] as? JsonPrimitive)?.contentOrNull != "json_schema") return null
        return format["schema"] as? JsonObject
    }

    // ── Response (internal for unit tests) ───────────────────────────────

    internal fun ingestResponse(root: JsonObject): OpenAIResponse {
        if ((root["type"] as? JsonPrimitive)?.contentOrNull == "error") {
            val msg = ((root["error"] as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull ?: "unknown error"
            throw ResponsesError.Decoding("Claude error: $msg")
        }
        val stop = (root["stop_reason"] as? JsonPrimitive)?.contentOrNull
        // A refusal can come with no content at all; check it before reading any block.
        if (stop == "refusal") throw ResponsesError.Decoding(com.pulseloop.ui.i18n.tr(REFUSAL))

        val content = (root["content"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        val responseId = (root["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
            ?: UUID.randomUUID().toString()

        val output = mutableListOf<ResponseOutputItem>()
        val text = content.filter { it["type"] == JsonPrimitive("text") }
            .mapNotNull { (it["text"] as? JsonPrimitive)?.contentOrNull }
            .joinToString("").trim()
        if (text.isNotEmpty()) output += MessageOutput(role = "assistant", content = listOf(TextContent(text)))
        for (block in content) {
            if (block["type"] != JsonPrimitive("tool_use")) continue
            val id = (block["id"] as? JsonPrimitive)?.contentOrNull ?: continue
            val name = (block["name"] as? JsonPrimitive)?.contentOrNull ?: continue
            val args = (block["input"] as? JsonObject)?.toString() ?: "{}"
            output += FunctionCallOutput(id = id, callId = id, name = name, arguments = args)
        }
        if (output.isEmpty()) {
            if (stop == "max_tokens") throw ResponsesError.Decoding("Claude's reply was cut off before it finished.")
            throw ResponsesError.EmptyOutput
        }
        storedAssistant[responseId] = JsonObject(mapOf(
            "role" to JsonPrimitive("assistant"),
            "content" to JsonArray(content),
        ))
        return OpenAIResponse(id = responseId, output = output, usage = usage(root))
    }

    /** `input_tokens` excludes cached reads and writes, so the three are summed; the reads are the
     *  cached subset `CoachPricingCatalog` bills at the cache rate. */
    private fun usage(root: JsonObject): com.pulseloop.coach.usage.CoachTokenUsage? {
        val u = root["usage"] as? JsonObject ?: return null
        fun n(key: String) = (u[key] as? JsonPrimitive)?.intOrNull ?: 0
        val read = n("cache_read_input_tokens")
        return com.pulseloop.coach.usage.CoachTokenUsage(
            inputTokens = n("input_tokens") + read + n("cache_creation_input_tokens"),
            outputTokens = n("output_tokens"),
            cachedInputTokens = read,
        )
    }

    companion object {
        const val API_VERSION = "2026-10-21"
        /** Room for adaptive thinking plus the reply, still under the ~16K non-streaming ceiling. */
        const val DEFAULT_MAX_TOKENS = 16000
        /** Opus thinks before it answers; 60 s, the shared default, cuts long turns off. */
        const val READ_TIMEOUT_SECONDS = 180
        const val REFUSAL = "Claude declined to answer this. Try asking it another way."
        private val CLAUDE_EFFORTS = setOf("low", "medium", "high", "xhigh", "max")
        private val DATA_URL = Regex("^data:([^;,]+);base64,(.*)$", RegexOption.DOT_MATCHES_ALL)

        private val UNSUPPORTED = setOf(
            "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf",
            "minLength", "maxLength", "pattern",
            "minItems", "maxItems", "uniqueItems", "minProperties", "maxProperties",
        )
        private val FORMATS = setOf("date-time", "time", "date", "duration", "email", "hostname", "uri", "ipv4", "ipv6", "uuid")

        /**
         * The schema with what Claude's structured outputs reject taken out: numeric, length and
         * array-size constraints, unknown `format`s, and any `additionalProperties` other than
         * `false` (which every object gets). `oneOf` becomes `anyOf`. Property *names* are never
         * touched, so a field called `format` or `pattern` survives. The orchestrator still parses
         * and repairs the reply, so a dropped constraint costs nothing it was relying on.
         */
        fun claudeSafeSchema(schema: JsonObject): JsonObject {
            val out = mutableMapOf<String, JsonElement>()
            for ((key, value) in schema) {
                when (key) {
                    in UNSUPPORTED -> {}
                    "format" -> if ((value as? JsonPrimitive)?.contentOrNull in FORMATS) out[key] = value
                    "additionalProperties" -> {}
                    "properties", "\$defs", "definitions" ->
                        out[key] = JsonObject((value as? JsonObject)?.mapValues { (_, v) -> sub(v) } ?: emptyMap())
                    "items", "not" -> out[key] = sub(value)
                    "anyOf", "allOf", "oneOf" ->
                        out[if (key == "oneOf") "anyOf" else key] = JsonArray((value as? JsonArray)?.map { sub(it) } ?: emptyList())
                    else -> out[key] = value
                }
            }
            val type = schema["type"]
            val isObject = "properties" in schema || type == JsonPrimitive("object") ||
                (type as? JsonArray)?.contains(JsonPrimitive("object")) == true
            if (isObject) out["additionalProperties"] = JsonPrimitive(false)
            return JsonObject(out)
        }

        private fun sub(value: JsonElement): JsonElement = (value as? JsonObject)?.let { claudeSafeSchema(it) } ?: value
    }
}
