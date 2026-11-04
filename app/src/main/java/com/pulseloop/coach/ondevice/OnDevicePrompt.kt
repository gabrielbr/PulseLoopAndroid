package com.pulseloop.coach.ondevice

/**
 * The prompt the on-device model gets, and how it is cut to fit the model's window
 * (docs/on-device-model-and-mcp-plan.md, Part 1). Cochilo's `Grounding`, adapted: the app gathers
 * the facts (the coach's context packet), the model only phrases the answer from them.
 *
 * The cloud system prompt is not sent: at about two thousand tokens it alone would overflow a
 * 1280-token window. [instructions] restates its rules that matter without tools — never diagnose,
 * never dose, send people to a clinician, use only the data given — in a few lines. Pure, so it's
 * unit-tested.
 */
object OnDevicePrompt {
    /** Longest question kept; a pasted essay would otherwise push out the context. */
    const val MAX_QUESTION_CHARS = 1000
    /** Each earlier chat message is cut to this, and at most [MAX_HISTORY] of them are kept. */
    const val MAX_HISTORY_CHARS = 300
    const val MAX_HISTORY = 4
    /** Longest answer shown; past this the model wandered. */
    const val MAX_REPLY_CHARS = 2000

    /** One earlier message of the chat. [fromUser] false is the coach's own reply. */
    data class Turn(val fromUser: Boolean, val text: String)

    /** The coach's rules on this phone. [languageInstruction] is `L10n.coachInstruction()` (language and voice). */
    fun instructions(languageInstruction: String): String = buildString {
        append(
            """
You are PulseLoop Coach, running on the user's phone. Answer the user's question about their own ring data in at most 4 short paragraphs, in plain words.
Use ONLY the facts in the context below. Use numbers exactly as they are written there. If the context doesn't answer the question, say so plainly. Never invent numbers, readings or studies.
Never diagnose a condition. Never name a medicine, supplement or dose. For chest pain, fainting, trouble breathing, very low SpO2, or readings that stay abnormal, tell the user to see a clinician or call emergency services.
Be warm and specific, and never judge the user's body. You can't change goals or log anything here; if asked, say they can do it in the app.
Write plain text only: no JSON, no code, no headings.
            """.trim(),
        )
        append(languageInstruction)
    }

    /** The developer message, minus its closing line about tools (there are none on this phone). */
    fun contextFrom(developerMessage: String): String {
        val tools = developerMessage.indexOf("Use the provided tools")
        return (if (tools >= 0) developerMessage.substring(0, tools) else developerMessage).trim()
    }

    /** The whole prompt in Gemma's turn format. */
    fun build(instructions: String, context: String, history: List<Turn>, question: String): String = buildString {
        append("<start_of_turn>user\n")
        append(instructions.trim())
        append("\n\n")
        if (context.isNotBlank()) {
            append("Context from the app:\n")
            append(context.trim())
            append("\n\n")
        }
        if (history.isNotEmpty()) {
            append("Earlier in this chat:\n")
            history.forEach { t ->
                append(if (t.fromUser) "User: " else "Coach: ")
                append(t.text.trim().replace(Regex("\\s+"), " ").take(MAX_HISTORY_CHARS))
                append('\n')
            }
            append('\n')
        }
        append("Question: ")
        append(question.trim().take(MAX_QUESTION_CHARS))
        append("<end_of_turn>\n<start_of_turn>model\n")
    }

    /**
     * The fullest prompt that [fits]: a prompt longer than the model's window crashes natively
     * (Cochilo 0.39.1). Earlier messages go first, oldest first; then the context is cut down a
     * quarter at a time, then dropped. The instructions and the question are always kept; null
     * when even those two alone don't fit.
     */
    fun fit(instructions: String, context: String, history: List<Turn>, question: String, fits: (String) -> Boolean): String? {
        var h = history.takeLast(MAX_HISTORY)
        var c = context.trim()
        while (true) {
            val prompt = build(instructions, c, h, question)
            if (fits(prompt)) return prompt
            when {
                h.isNotEmpty() -> h = h.drop(1)
                c.length > 200 -> c = c.take(c.length * 3 / 4).trimEnd() + " …"
                c.isNotEmpty() -> c = ""
                else -> return null
            }
        }
    }

    /** The model's text without turn markers, a leading label or surrounding quotes, and capped. */
    fun clean(reply: String): String {
        var r = reply
            .replace("<end_of_turn>", "")
            .replace("<start_of_turn>model", "")
            .replace("<start_of_turn>", "")
            .trim()
        for (label in listOf("Answer:", "Resposta:", "Coach:")) r = r.removePrefix(label).trim()
        r = r.trim('"').trim()
        if (r.length > MAX_REPLY_CHARS) r = r.take(MAX_REPLY_CHARS).trimEnd() + "…"
        return r
    }
}
