package com.pulseloop.ui.i18n

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The pt-BR catalog (assets/i18n/pt-BR.json): every entry is a usable drop-in for its English key.
 * A key is the exact English text, so these are the properties that keep a translated screen intact
 * when a string is concatenated with numbers or formatted at runtime.
 */
class PortugueseCatalogTest {

    private val catalog: Map<String, String> = run {
        val f = listOf("src/main/assets/i18n/pt-BR.json", "app/src/main/assets/i18n/pt-BR.json").map(::File).first { it.exists() }
        Json.parseToJsonElement(f.readText()).jsonObject.mapValues { it.value.jsonPrimitive.content }
    }

    @Test
    fun `the catalog is substantial and has no empty entries`() {
        assertTrue(catalog.size > 900)
        assertTrue(catalog.values.none { it.isBlank() })
    }

    @Test
    fun `leading and trailing spaces survive translation`() {
        catalog.forEach { (en, pt) ->
            assertEquals("leading space: $en", en.startsWith(" "), pt.startsWith(" "))
            assertEquals("trailing space: $en", en.endsWith(" "), pt.endsWith(" "))
        }
    }

    @Test
    fun `format specifiers survive translation`() {
        val spec = Regex("%[0-9$,.]*[sdf]")
        catalog.forEach { (en, pt) ->
            assertEquals("specifiers: $en", spec.findAll(en).map { it.value }.sorted().toList(), spec.findAll(pt).map { it.value }.sorted().toList())
        }
    }

    @Test
    fun `an untranslated string falls back to English`() {
        assertEquals("Not in any catalog 123", tr("Not in any catalog 123"))
    }
}
