package com.pulseloop.ui.i18n

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The F*cking Health voice (assets/i18n/en-FH.json). The joke wraps the content, so every entry
 * must keep its values and spacing, and the safety text must not be in it at all: an entry
 * missing here means the plain wording shows, which is what crisis lines and warnings need.
 */
class FuckingHealthCatalogTest {

    private val catalog: Map<String, String> = run {
        val f = listOf("src/main/assets/i18n/en-FH.json", "app/src/main/assets/i18n/en-FH.json").map(::File).first { it.exists() }
        Json.parseToJsonElement(f.readText()).jsonObject.mapValues { it.value.jsonPrimitive.content }
    }

    @Test
    fun `safety, medical and crisis text keeps its plain wording`() {
        val serious = Regex(
            "CVV|\\b188\\b|\\b192\\b|SAMU|emergenc|clinician|doctor|pharmacist|medical|contracept|pregnan|postpartum|" +
                "self-harm|suicid|AUDIT|dependen|fever|crisis|Alcoólicos|CAPS|interaction|not a diagnosis",
            RegexOption.IGNORE_CASE,
        )
        val offenders = catalog.keys.filter { serious.containsMatchIn(it) }
        assertTrue("rewritten safety text: $offenders", offenders.isEmpty())
    }

    @Test
    fun `values, format specifiers and spacing survive the attitude`() {
        val ph = Regex("\\{\\d+\\}")
        val spec = Regex("%[0-9$,.]*[sdf]")
        catalog.forEach { (en, fh) ->
            assertEquals("placeholders: $en", ph.findAll(en).map { it.value }.sorted().toList(), ph.findAll(fh).map { it.value }.sorted().toList())
            assertEquals("specifiers: $en", spec.findAll(en).map { it.value }.sorted().toList(), spec.findAll(fh).map { it.value }.sorted().toList())
            assertEquals("leading space: $en", en.startsWith(" "), fh.startsWith(" "))
            assertEquals("trailing space: $en", en.endsWith(" "), fh.endsWith(" "))
        }
    }

    @Test
    fun `the swearing is censored`() {
        val raw = Regex("\\b(fuck\\w*|shit\\w*|asshole|bitch\\w*|cunt|dick)\\b", RegexOption.IGNORE_CASE)
        val uncensored = catalog.values.filter { raw.containsMatchIn(it) }
        assertTrue("uncensored: ${uncensored.take(5)}", uncensored.isEmpty())
    }

    @Test
    fun `the voice actually has a voice`() {
        assertTrue(catalog.size > 300)
    }
}
