package com.pulseloop.ui.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FairyModeTest {
    @Test
    fun `her names match, whatever the case, accents or surname`() {
        listOf("Tatá", "Tata", "tata", "TATÁ", "Thaissa", "thaíssa", "Thaissa Silva", "  Tatá  ").forEach {
            assertTrue(it, FairyMode.matches(it))
        }
    }

    @Test
    fun `other names don't`() {
        listOf(null, "", "Gabriel", "Tatiana", "Ana Tata", "Thais").forEach {
            assertFalse(it.toString(), FairyMode.matches(it))
        }
    }
}
