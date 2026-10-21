package com.pulseloop.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class FindingsTest {
    private val day = LocalDate.of(2026, 10, 21)

    private fun nights(n: Int, recent: (Int) -> Pair<Double, Double>? = { null }): List<NightSummary> =
        (0 until n).map { back ->
            val even = back % 2 == 0
            val (rhr, hrv) = recent(back) ?: ((if (even) 54.0 else 56.0) to (if (even) 38.0 else 42.0))
            NightSummary(day.minusDays(back.toLong()), 0, 8 * 3600_000L, 450, rhr, hrv, 1.0)
        }

    @Test
    fun `an ordinary month finds nothing`() {
        assertTrue(Findings.forDay(day, nights(31)).isEmpty())
    }

    @Test
    fun `three nights of higher resting heart rate and lower HRV are found`() {
        val f = Findings.forDay(day, nights(31) { back -> if (back < 3) 62.0 to 30.0 else null })
        assertEquals(setOf(Finding.Kind.RESTING_HR_UP, Finding.Kind.HRV_DOWN), f.map { it.kind }.toSet())
        assertTrue(f.first { it.kind == Finding.Kind.RESTING_HR_UP }.detail.startsWith("62 bpm"))
    }

    @Test
    fun `nothing is judged without two weeks of baseline`() {
        assertTrue(Findings.forDay(day, nights(10) { back -> if (back < 3) 62.0 to 30.0 else null }).isEmpty())
    }

    @Test
    fun `a symptom radar sign is logged as a finding`() {
        val f = Findings.forDay(day, nights(31), radar = SymptomRadar.Level.MINOR)
        assertEquals(listOf(Finding.Kind.RADAR), f.map { it.kind })
    }
}
