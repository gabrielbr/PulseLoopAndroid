package com.pulseloop.coach.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDevicePromptTest {
    private val rules = OnDevicePrompt.instructions("")

    @Test fun the_prompt_is_in_gemmas_turn_format() {
        val p = OnDevicePrompt.build(rules, "{\"today\":\"2026-11-04\"}", emptyList(), "How did I sleep?")
        assertTrue(p.startsWith("<start_of_turn>user\n"))
        assertTrue(p.endsWith("<end_of_turn>\n<start_of_turn>model\n"))
        assertTrue("Question: How did I sleep?" in p)
        assertTrue("2026-11-04" in p)
    }

    @Test fun the_rules_keep_the_cloud_coachs_safety_lines() {
        assertTrue("Never diagnose" in rules)
        assertTrue("medicine, supplement or dose" in rules)
        assertTrue("clinician" in rules)
        assertTrue("at most 4 short paragraphs" in rules)
        // The language/voice instruction rides at the end.
        assertTrue(OnDevicePrompt.instructions(" Reply in pt-BR.").endsWith("Reply in pt-BR."))
    }

    @Test fun the_tools_line_is_dropped_from_the_context() {
        val dev = "Current context packet:\n{}\n\nConversation summary:\n(none)\n\nUse the provided tools to retrieve, analyze."
        val c = OnDevicePrompt.contextFrom(dev)
        assertFalse("tools" in c)
        assertTrue(c.startsWith("Current context packet:"))
    }

    @Test fun fit_drops_history_first_then_cuts_the_context_and_keeps_rules_and_question() {
        val history = (1..6).map { OnDevicePrompt.Turn(fromUser = it % 2 == 1, text = "message $it") }
        val context = "x".repeat(4000)
        val question = "How did I sleep?"
        // Fits once history is gone and the context is short.
        val prompt = OnDevicePrompt.fit(rules, context, history, question) { it.length < rules.length + 1200 }!!
        assertFalse("message" in prompt)
        assertTrue(rules in prompt)
        assertTrue("Question: $question" in prompt)
        assertTrue(prompt.length < rules.length + 1200)

        // Plenty of room: only the newest MAX_HISTORY messages ride along.
        val roomy = OnDevicePrompt.fit(rules, "ctx", history, question) { true }!!
        assertFalse("message 1\n" in roomy)
        assertFalse("message 2\n" in roomy)
        assertTrue("message 3" in roomy && "message 6" in roomy)

        // Nothing fits: null, so the client never sends an overflowing prompt.
        assertNull(OnDevicePrompt.fit(rules, context, history, question) { false })
    }

    @Test fun clean_strips_turn_markers_labels_and_quotes() {
        assertEquals("You slept 7 h.", OnDevicePrompt.clean("Answer: \"You slept 7 h.\"<end_of_turn>"))
        assertEquals("ok", OnDevicePrompt.clean("<start_of_turn>model\nok"))
        assertTrue(OnDevicePrompt.clean("a".repeat(5000)).length <= OnDevicePrompt.MAX_REPLY_CHARS + 1)
    }
}
