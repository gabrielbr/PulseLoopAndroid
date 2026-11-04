package com.pulseloop.coach.ondevice

import com.pulseloop.coach.openai.MessageOutput
import com.pulseloop.coach.openai.OpenAIResponse
import com.pulseloop.coach.openai.ResponsesClient
import com.pulseloop.coach.openai.ResponsesError
import com.pulseloop.coach.openai.TextContent
import com.pulseloop.coach.usage.CoachTokenUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.util.UUID

/**
 * The model the on-device client talks to: loaded once, used one answer at a time.
 * [OnDeviceModelStore] is the real one; tests pass a fake.
 */
interface OnDeviceEngine {
    /**
     * Runs [block] with the loaded model, one call at a time (a MediaPipe engine is not safe to
     * share between two generations). Throws [ResponsesError] when the model isn't on the phone.
     */
    suspend fun <T> use(block: (LocalModel) -> T): T
}

/**
 * The "On this phone" coach provider (docs/on-device-model-and-mcp-plan.md, Part 1), behind the
 * same [ResponsesClient] interface as every cloud provider so `CoachOrchestrator` runs it
 * unchanged. It answers grounded and without tools:
 *
 * - **Tools are ignored.** A 1B model can't run the tool loop, so the request's `tools` are never
 *   offered and the reply never holds a function call: the orchestrator's loop ends after one
 *   round, and no write tool or confirm card can come from this provider.
 * - **The cloud system prompt is replaced** by [OnDevicePrompt.instructions] (its rules, shortened
 *   to fit the window), and the developer message's context packet is kept as the facts. When the
 *   prompt is too long, earlier chat messages go first, then the context is cut; the instructions
 *   and the question always stay.
 * - **Safety comes first.** [OnDeviceSafety] checks the question before the model is loaded; a
 *   crisis, an emergency or a medicine question gets its fixed reply.
 * - **The reply is valid `coach_response` JSON** wrapping the model's plain text ([coachResponseJson]),
 *   with every key `CoachResponseParser` requires, so `parseFinal` accepts it on the first try and
 *   never spends a repair round.
 *
 * Callers that ask for a different JSON schema (meal estimates, lab reports, summaries) are
 * refused with a [ResponsesError.Decoding]: a 1B model can't be trusted to read a lab report.
 */
class OnDeviceResponsesClient(
    private val engine: OnDeviceEngine,
    private val portuguese: () -> Boolean = { com.pulseloop.ui.i18n.L10n.isPortuguese },
    private val languageInstruction: () -> String = { com.pulseloop.ui.i18n.L10n.coachInstruction() },
) : ResponsesClient {
    private val json = Json { ignoreUnknownKeys = true }

    /** The last answer, replayed if the orchestrator ever sends a continuation (it shouldn't: no tools, valid JSON). */
    private var last: OpenAIResponse? = null

    override suspend fun send(requestBody: ByteArray): OpenAIResponse {
        val req = try {
            json.parseToJsonElement(String(requestBody)).jsonObject
        } catch (_: Exception) {
            throw ResponsesError.Decoding("OnDeviceResponsesClient: invalid request body")
        }
        if (!isCoachChat(req)) throw ResponsesError.Decoding(UNSUPPORTED)

        val previous = (req["previous_response_id"] as? JsonPrimitive)?.contentOrNull
        if (previous != null) last?.let { return it }

        val parsed = parseInput(req)
        val pt = portuguese()

        OnDeviceSafety.route(parsed.question, pt)?.let { route ->
            return remember(response(coachResponseJson(route.reply, safety = true), usage = null))
        }

        val instructions = OnDevicePrompt.instructions(languageInstruction())
        val budget = LocalModel.MAX_TOKENS - LocalModel.REPLY_TOKENS
        val answer = try {
            withContext(Dispatchers.Default) {
                engine.use { model ->
                    val prompt = OnDevicePrompt.fit(instructions, parsed.context, parsed.history, parsed.question) {
                        model.tokens(it) <= budget
                    } ?: throw ResponsesError.Decoding(TOO_LONG)
                    val raw = model.generate(prompt)
                    Answer(OnDevicePrompt.clean(raw), model.tokens(prompt), model.tokens(raw))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ResponsesError) {
            throw e
        } catch (e: Throwable) {
            // A native failure or running out of memory must end the turn with a message, not
            // take the app down: the orchestrator only catches Exception.
            throw ResponsesError.Decoding(FAILED + (e.message?.let { " ($it)" } ?: ""))
        }
        if (answer.text.isBlank()) throw ResponsesError.EmptyOutput
        return remember(response(
            coachResponseJson(answer.text, safety = false),
            usage = CoachTokenUsage(inputTokens = answer.inputTokens, outputTokens = answer.outputTokens),
        ))
    }

    private data class Answer(val text: String, val inputTokens: Int, val outputTokens: Int)

    internal data class Parsed(val context: String, val history: List<OnDevicePrompt.Turn>, val question: String)

    /**
     * The newest developer message becomes the context; system messages are dropped (replaced by
     * the on-device instructions); user and assistant messages before the last user one are the
     * history; the last user message is the question. Images are ignored: this model reads text.
     */
    internal fun parseInput(req: JsonObject): Parsed {
        val input = (req["input"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        var context = ""
        val chat = mutableListOf<OnDevicePrompt.Turn>()
        for (item in input) {
            val role = (item["role"] as? JsonPrimitive)?.contentOrNull ?: continue
            val text = flattenText(item["content"])
            when (role) {
                "developer" -> if (text.isNotBlank()) context = OnDevicePrompt.contextFrom(text)
                "user" -> chat.add(OnDevicePrompt.Turn(fromUser = true, text = text))
                "assistant" -> chat.add(OnDevicePrompt.Turn(fromUser = false, text = text))
                // "system": the cloud prompt, too long for this window; OnDevicePrompt restates its rules.
            }
        }
        val lastUser = chat.indexOfLast { it.fromUser }
        val question = if (lastUser >= 0) chat[lastUser].text else ""
        val history = if (lastUser >= 0) chat.subList(0, lastUser).filter { it.text.isNotBlank() } else emptyList()
        return Parsed(context, history.toList(), question)
    }

    /** True for the coach chat: no `text.format`, or the `coach_response` one. */
    internal fun isCoachChat(req: JsonObject): Boolean {
        val format = (req["text"] as? JsonObject)?.get("format") as? JsonObject ?: return true
        if ((format["type"] as? JsonPrimitive)?.contentOrNull != "json_schema") return true
        return (format["name"] as? JsonPrimitive)?.contentOrNull == "coach_response"
    }

    private fun flattenText(content: JsonElement?): String {
        if (content is JsonPrimitive && content.isString) return content.content
        val parts = (content as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return ""
        return parts.mapNotNull { p ->
            val type = (p["type"] as? JsonPrimitive)?.contentOrNull
            if (type == "input_text" || type == "text") (p["text"] as? JsonPrimitive)?.contentOrNull else null
        }.joinToString("\n")
    }

    private fun remember(r: OpenAIResponse): OpenAIResponse { last = r; return r }

    private fun response(text: String, usage: CoachTokenUsage?) = OpenAIResponse(
        id = "ondevice_" + UUID.randomUUID().toString().replace("-", "").take(16),
        output = listOf(MessageOutput(role = "assistant", content = listOf(TextContent(text)))),
        usage = usage,
    )

    companion object {
        const val UNSUPPORTED =
            "The model on this phone only answers coach questions. Reading photos, meals or lab reports needs a cloud or self-hosted coach (Settings → AI Coach)."
        const val TOO_LONG = "That message is too long for the model on this phone. Try a shorter question."
        const val FAILED = "The model on this phone couldn't answer. Try again, or close other apps to free memory."

        /**
         * A `coach_response` object with every key the schema lists (and so every key
         * `CoachResponseParser` requires: response_type, title, summary, confidence), carrying
         * [summary] as the answer. [safety] marks a fixed safety reply.
         */
        fun coachResponseJson(summary: String, safety: Boolean): String = JsonObject(mapOf(
            "response_type" to JsonPrimitive(if (safety) "safety_guidance" else "insight"),
            "title" to JsonPrimitive(""),
            "summary" to JsonPrimitive(summary),
            "bullets" to JsonArray(emptyList()),
            "chart" to JsonNull,
            "safety_note" to JsonNull,
            "data_quality_note" to JsonNull,
            "sources" to JsonArray(emptyList()),
            "follow_up_chips" to JsonArray(emptyList()),
            "actions_taken" to JsonArray(emptyList()),
            "confidence" to JsonPrimitive(if (safety) "high" else "medium"),
        )).toString()
    }
}
