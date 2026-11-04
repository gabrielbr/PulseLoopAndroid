package com.pulseloop.coach.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceSafetyTest {
    private fun kind(q: String) = OnDeviceSafety.route(q, portuguese = false)?.kind

    @Test fun crisis_in_both_languages_with_or_without_accents() {
        assertEquals(OnDeviceSafety.Kind.CRISIS, kind("I've been thinking about suicide"))
        assertEquals(OnDeviceSafety.Kind.CRISIS, kind("Sometimes I want to die"))
        assertEquals(OnDeviceSafety.Kind.CRISIS, kind("I don't want to live anymore"))
        assertEquals(OnDeviceSafety.Kind.CRISIS, kind("Tenho pensado em suicídio"))
        assertEquals(OnDeviceSafety.Kind.CRISIS, kind("tenho pensado em suicidio"))
        assertEquals(OnDeviceSafety.Kind.CRISIS, kind("Não quero mais viver"))
        assertEquals(OnDeviceSafety.Kind.CRISIS, kind("QUERO MORRER"))
    }

    @Test fun crisis_outranks_medicine_and_emergency() {
        assertEquals(OnDeviceSafety.Kind.CRISIS, kind("I took pills to end my life"))
        assertEquals(OnDeviceSafety.Kind.CRISIS, kind("chest pain and I want to die"))
    }

    @Test fun emergencies() {
        assertEquals(OnDeviceSafety.Kind.EMERGENCY, kind("I have chest pain since this morning"))
        assertEquals(OnDeviceSafety.Kind.EMERGENCY, kind("I can't breathe well"))
        assertEquals(OnDeviceSafety.Kind.EMERGENCY, kind("I fainted at the gym"))
        assertEquals(OnDeviceSafety.Kind.EMERGENCY, kind("Estou com dor no peito"))
        assertEquals(OnDeviceSafety.Kind.EMERGENCY, kind("não consigo respirar"))
        assertEquals(OnDeviceSafety.Kind.EMERGENCY, kind("Desmaiei ontem"))
    }

    @Test fun emergency_outranks_medicine() {
        assertEquals(OnDeviceSafety.Kind.EMERGENCY, kind("chest pain, should I take aspirin?"))
    }

    @Test fun medicines_doses_and_supplements() {
        assertEquals(OnDeviceSafety.Kind.MEDICINE, kind("How much melatonin should I take?"))
        assertEquals(OnDeviceSafety.Kind.MEDICINE, kind("What dose of ibuprofen is safe?"))
        assertEquals(OnDeviceSafety.Kind.MEDICINE, kind("Should I take a magnesium supplement?"))
        assertEquals(OnDeviceSafety.Kind.MEDICINE, kind("Posso tomar remédio para dormir?"))
        assertEquals(OnDeviceSafety.Kind.MEDICINE, kind("qual a dosagem de creatina"))
        assertEquals(OnDeviceSafety.Kind.MEDICINE, kind("Meus suplementos atrapalham o sono?"))
    }

    @Test fun ordinary_questions_go_to_the_model() {
        assertNull(kind("How did I sleep last night?"))
        assertNull(kind("Why is my resting heart rate higher this week?"))
        assertNull(kind("Como foi meu sono?"))
        assertNull(kind("My glucose was 110 mg/dL after lunch"))
        // Whole words only: "dosed" off, "medic" inside another word, "pill" in "spill".
        assertNull(kind("I spilled water on the ring"))
        assertNull(kind("Is my stride okay when I swim breaststroke?"))
        assertNull(kind(""))
    }

    @Test fun replies_carry_the_numbers_people_need() {
        val crisisEn = OnDeviceSafety.reply(OnDeviceSafety.Kind.CRISIS, portuguese = false)
        val crisisPt = OnDeviceSafety.reply(OnDeviceSafety.Kind.CRISIS, portuguese = true)
        for (r in listOf(crisisEn, crisisPt)) {
            assertTrue(r, "188" in r)
            assertTrue(r, "192" in r)
        }
        assertTrue("192" in OnDeviceSafety.reply(OnDeviceSafety.Kind.EMERGENCY, portuguese = false))
        assertTrue("192" in OnDeviceSafety.reply(OnDeviceSafety.Kind.EMERGENCY, portuguese = true))
        assertTrue("pharmacist" in OnDeviceSafety.reply(OnDeviceSafety.Kind.MEDICINE, portuguese = false))
        assertTrue("farmacêutico" in OnDeviceSafety.reply(OnDeviceSafety.Kind.MEDICINE, portuguese = true))
        // The reply follows the app's language, not the question's.
        assertEquals(crisisPt, OnDeviceSafety.route("I want to die", portuguese = true)?.reply)
    }

    @Test fun normalize_drops_accents_case_and_apostrophes() {
        assertEquals("nao consigo respirar", OnDeviceSafety.normalize("  Não  consigo RESPIRAR "))
        assertEquals("i cant breathe", OnDeviceSafety.normalize("I can’t breathe"))
    }
}
