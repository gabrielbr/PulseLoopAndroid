package com.pulseloop.coach.ondevice

import java.text.Normalizer

/**
 * Safety comes before the on-device model (docs/on-device-model-and-mcp-plan.md, Part 1, "The
 * hard part: tools", step 3). A 1B model can't be trusted with a crisis, an emergency or a
 * medicine question, so those get a fixed, reviewed reply and the model never sees them, as in
 * Cochilo's Ask (`Assistant.ask`).
 *
 * Matching is on whole words, case- and accent-insensitive, in English and Portuguese ("remédio"
 * and "remedio" both match). Order matters: a crisis outranks an emergency, which outranks a
 * medicine question, so "I took too many pills to end my life" gets the crisis lines.
 *
 * The replies are written here, not through `tr()`: they are safety text, which no voice catalog
 * may rewrite (AGENTS.md, "A voice is a catalog layered on a language"). Pure, so unit-tested.
 */
object OnDeviceSafety {

    enum class Kind { CRISIS, EMERGENCY, MEDICINE }

    data class Route(val kind: Kind, val reply: String)

    /** The fixed reply for [question], or null when the model may answer it. */
    fun route(question: String, portuguese: Boolean): Route? {
        val text = normalize(question)
        if (text.isBlank()) return null
        val kind = when {
            CRISIS.any { it.containsMatchIn(text) } -> Kind.CRISIS
            EMERGENCY.any { it.containsMatchIn(text) } -> Kind.EMERGENCY
            MEDICINE.any { it.containsMatchIn(text) } -> Kind.MEDICINE
            else -> return null
        }
        return Route(kind, reply(kind, portuguese))
    }

    fun reply(kind: Kind, portuguese: Boolean): String = when (kind) {
        Kind.CRISIS -> if (portuguese)
            "Sinto muito que você esteja passando por isso. Você não precisa enfrentar isso sozinho. " +
                "O CVV atende de graça, a qualquer hora, no 188 (ou em cvv.org.br). " +
                "Se você estiver em perigo agora, ligue para o SAMU no 192 ou vá ao pronto-socorro mais próximo."
        else
            "I'm really sorry you're going through this. You don't have to face it alone. " +
                "In Brazil, CVV answers for free, at any hour, on 188 (or at cvv.org.br). " +
                "If you're in danger right now, call SAMU on 192 or go to the nearest emergency room. " +
                "Outside Brazil, call your local emergency number."
        Kind.EMERGENCY -> if (portuguese)
            "Isso pode ser urgente. Com dor no peito, falta de ar ou desmaio, ligue agora para o SAMU no 192 " +
                "ou vá ao pronto-socorro mais próximo. Não espere pelas leituras do anel."
        else
            "This could be urgent. For chest pain, trouble breathing or fainting, call SAMU on 192 now " +
                "(or your local emergency number), or go to the nearest emergency room. " +
                "Don't wait for the ring's readings."
        Kind.MEDICINE -> if (portuguese)
            "Eu não oriento sobre remédios, doses ou suplementos. Pergunte ao seu médico ou farmacêutico, " +
                "que conhecem o seu histórico. Posso olhar com você os dados do seu anel, como sono, " +
                "frequência cardíaca e atividade."
        else
            "I don't advise on medicines, doses or supplements. Please ask your doctor or pharmacist, " +
                "who know your history. I can look at your ring data with you, like sleep, heart rate " +
                "and activity."
    }

    /** Lower case, accents gone, apostrophes dropped ("can't" → "cant"), spaces collapsed. */
    internal fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("['’`´]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * Whole-word patterns. A trailing `*` makes a stem ("suicid*" matches "suicide", "suicídio",
     * "suicidal"). Keys are written already normalized (no accents, no apostrophes).
     */
    private fun patterns(vararg keys: String): List<Regex> = keys.map { k ->
        val stem = k.endsWith("*")
        val body = Regex.escape(k.removeSuffix("*"))
        Regex("(^|[^a-z0-9])$body" + if (stem) "" else "($|[^a-z0-9])")
    }

    private val CRISIS = patterns(
        // English
        "suicid*", "kill myself", "killing myself", "end my life", "ending my life", "take my life",
        "self harm", "self-harm", "selfharm", "hurt myself", "hurting myself", "cut myself", "cutting myself",
        "want to die", "wanna die", "dont want to live", "dont want to be alive", "no reason to live",
        "better off dead",
        // Portuguese
        "me matar", "tirar minha vida", "tirar a minha vida", "acabar com a minha vida", "acabar com minha vida",
        "quero morrer", "queria morrer", "nao quero mais viver", "nao quero viver", "me machucar", "me cortar",
        "automutila*", "autolesao", "nao aguento mais viver", "sem motivo para viver",
    )

    private val EMERGENCY = patterns(
        // English
        "chest pain", "pain in my chest", "chest hurts", "chest is hurting", "chest pressure", "chest tightness",
        "cant breathe", "cannot breathe", "can not breathe", "trouble breathing", "hard to breathe",
        "short of breath", "shortness of breath", "fainted", "fainting", "faint", "passed out", "pass out",
        "passing out", "emergency", "heart attack", "having a stroke", "had a stroke", "stroke symptoms",
        // Portuguese
        "dor no peito", "dor peito", "aperto no peito", "pressao no peito", "nao consigo respirar",
        "falta de ar", "dificuldade para respirar", "dificuldade de respirar", "desmai*", "emergencia",
        "infarto", "ataque cardiaco", "avc",
    )

    private val MEDICINE = patterns(
        // English
        "medicine*", "medication*", "meds", "pill", "pills", "tablet", "tablets", "dose", "doses", "dosage",
        "dosing", "milligram*", "supplement*", "prescription*", "prescribe*", "drug", "drugs",
        "ibuprofen", "paracetamol", "acetaminophen", "tylenol", "aspirin", "melatonin", "creatine",
        "antidepressant*", "beta blocker*", "beta-blocker*", "insulin", "statin*", "antibiotic*",
        "painkiller*", "sleeping pill*",
        // Portuguese
        "remedio*", "medicamento*", "medicacao", "medicacoes", "comprimido*", "pilula*", "dosagem",
        "suplemento*", "suplementacao", "dipirona", "ibuprofeno", "melatonina", "creatina",
        "antidepressivo*", "betabloqueador*", "insulina", "estatina*", "antibiotico*", "analgesico*",
        "tarja preta", "receitar", "prescrever", "prescricao",
    )
}
