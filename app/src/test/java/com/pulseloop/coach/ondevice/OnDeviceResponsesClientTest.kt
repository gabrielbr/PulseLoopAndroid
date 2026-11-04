package com.pulseloop.coach.ondevice

import com.pulseloop.coach.config.CoachClientResolver
import com.pulseloop.coach.config.CoachProviderMode
import com.pulseloop.coach.config.CoachProviderSettings
import com.pulseloop.coach.config.ReasoningEfforts
import com.pulseloop.coach.context.CoachContextPacket
import com.pulseloop.coach.openai.OpenAIRequestBuilder
import com.pulseloop.coach.openai.ResponsesError
import com.pulseloop.coach.orchestration.CoachOrchestrator
import com.pulseloop.coach.orchestration.CoachResponseParser
import com.pulseloop.coach.schema.CoachResponseType
import com.pulseloop.coach.tools.CoachFeatureFlags
import com.pulseloop.coach.tools.ToolExecutionContext
import com.pulseloop.coach.usage.CoachPricingCatalog
import com.pulseloop.coach.usage.CoachTokenUsage
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OnDeviceResponsesClientTest {

    /** A model that answers [reply] and counts tokens as words. */
    private class FakeModel(private val reply: String) : LocalModel {
        val prompts = mutableListOf<String>()
        override fun generate(prompt: String): String { prompts += prompt; return reply }
        override fun tokens(text: String): Int = text.split(Regex("\\s+")).size
        override fun close() {}
    }

    private class FakeEngine(val model: FakeModel) : OnDeviceEngine {
        var uses = 0
        override suspend fun <T> use(block: (LocalModel) -> T): T { uses++; return block(model) }
    }

    private fun client(engine: OnDeviceEngine, pt: Boolean = false) =
        OnDeviceResponsesClient(engine, portuguese = { pt }, languageInstruction = { "" })

    private fun body(question: String, format: String = "coach_response", tools: Boolean = true): ByteArray {
        val input = JsonArray(listOf(
            OpenAIRequestBuilder.message("system", "LONG CLOUD SYSTEM PROMPT"),
            OpenAIRequestBuilder.message("developer", "Current context packet:\n{\"sleepAvgMin\":420}\n\nUse the provided tools to retrieve."),
            OpenAIRequestBuilder.message("user", "earlier question"),
            OpenAIRequestBuilder.message("assistant", "earlier answer"),
            OpenAIRequestBuilder.message("user", question),
        ))
        val tool = JsonObject(mapOf("type" to JsonPrimitive("function"), "name" to JsonPrimitive("get_sleep")))
        return JsonObject(mapOf(
            "model" to JsonPrimitive(ModelDownload.MODEL_SLUG),
            "input" to input,
            "tools" to JsonArray(if (tools) listOf(tool) else emptyList()),
            "text" to JsonObject(mapOf("format" to JsonObject(mapOf(
                "type" to JsonPrimitive("json_schema"), "name" to JsonPrimitive(format),
            )))),
        )).toString().toByteArray()
    }

    @Test fun the_answer_is_valid_coach_response_json_with_no_tool_calls() = runTest {
        val engine = FakeEngine(FakeModel("You slept about 7 hours.<end_of_turn>"))
        val r = client(engine).send(body("How did I sleep?"))
        assertTrue(r.functionCalls.isEmpty())
        val parsed = CoachResponseParser.parse(r.outputText)!!
        assertEquals("You slept about 7 hours.", parsed.summary)
        assertEquals(CoachResponseType.INSIGHT, parsed.responseType)
        assertTrue((r.usage?.inputTokens ?: 0) > 0)

        val prompt = engine.model.prompts.single()
        assertFalse("LONG CLOUD SYSTEM PROMPT" in prompt)   // replaced by the on-device rules
        assertTrue("sleepAvgMin" in prompt)                 // the context packet is kept
        assertFalse("Use the provided tools" in prompt)
        assertTrue("User: earlier question" in prompt)
        assertTrue("Question: How did I sleep?" in prompt)
    }

    @Test fun a_safety_question_never_reaches_the_model() = runTest {
        val engine = FakeEngine(FakeModel("should not be used"))
        val r = client(engine, pt = true).send(body("Qual dose de melatonina eu tomo?"))
        assertEquals(0, engine.uses)
        val parsed = CoachResponseParser.parse(r.outputText)!!
        assertEquals(CoachResponseType.SAFETY_GUIDANCE, parsed.responseType)
        assertEquals(OnDeviceSafety.reply(OnDeviceSafety.Kind.MEDICINE, portuguese = true), parsed.summary)
        assertNull(r.usage)
    }

    @Test fun other_schemas_are_refused() = runTest {
        val engine = FakeEngine(FakeModel("{}"))
        try {
            client(engine).send(body("Estimate this meal", format = "meal_estimate"))
            fail("expected a refusal")
        } catch (e: ResponsesError.Decoding) {
            assertEquals(OnDeviceResponsesClient.UNSUPPORTED, e.msg)
        }
        assertEquals(0, engine.uses)
    }

    @Test fun a_native_failure_becomes_a_coach_error_not_a_crash() = runTest {
        val engine = object : OnDeviceEngine {
            override suspend fun <T> use(block: (LocalModel) -> T): T = throw OutOfMemoryError("no room")
        }
        try {
            client(engine).send(body("How did I sleep?"))
            fail("expected an error")
        } catch (e: ResponsesError.Decoding) {
            assertTrue(e.msg.startsWith(OnDeviceResponsesClient.FAILED))
        }
    }

    @Test fun an_empty_answer_is_no_output() = runTest {
        try {
            client(FakeEngine(FakeModel("  <end_of_turn>"))).send(body("How did I sleep?"))
            fail("expected EmptyOutput")
        } catch (e: ResponsesError) {
            assertEquals(ResponsesError.EmptyOutput, e)
        }
    }

    /** The orchestrator runs one round and parseFinal accepts the reply with no repair round. */
    @Test fun the_orchestrator_finishes_in_one_call() = runTest {
        val engine = FakeEngine(FakeModel("Your sleep was steady this week."))
        val orchestrator = CoachOrchestrator(
            clientFactory = { client(engine) },
            flagsProvider = { CoachFeatureFlags(model = ModelDownload.MODEL_SLUG) },
            toolContextFactory = { ToolExecutionContext(flags = it) },
        )
        val result = orchestrator.runTurn("How was my sleep?", CoachContextPacket())
        assertNull(result.error)
        assertEquals(1, engine.uses)
        assertEquals("Your sleep was steady this week.", result.assistant.summary)
        assertTrue(result.trace.isEmpty())
        assertTrue(result.pendingActions.isEmpty())
    }

    @Test fun the_resolver_gates_on_the_model_file_and_prices_it_at_zero() {
        val s = CoachProviderSettings(providerMode = CoachProviderMode.ON_DEVICE)
        val absent = CoachClientResolver.resolve(s, openAIKey = "sk-ignored", geminiKey = null, openRouterKey = null)
        assertNull(absent.key)
        assertTrue(absent.client is OnDeviceResponsesClient)
        val ready = CoachClientResolver.resolve(s, openAIKey = null, geminiKey = null, openRouterKey = null, onDeviceReady = true)
        assertEquals(CoachClientResolver.ON_DEVICE_KEY, ready.key)
        assertEquals(ModelDownload.MODEL_SLUG, CoachClientResolver.activeModel(s, "gpt-5.5"))
        val cost = CoachPricingCatalog.cost(ModelDownload.MODEL_SLUG, CoachTokenUsage(1000, 300), CoachProviderMode.ON_DEVICE.rawValue)
        assertEquals(0.0, cost!!, 0.0)
        assertTrue(ReasoningEfforts.options(CoachProviderMode.ON_DEVICE, ModelDownload.MODEL_SLUG).isEmpty())
        assertEquals(CoachProviderMode.ON_DEVICE, CoachProviderMode.fromRaw("onDevice"))
    }
}
