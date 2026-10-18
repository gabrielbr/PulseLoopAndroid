package com.pulseloop.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Journal tags → "what affects you" (docs/health-app-feature-research.md). */
class JournalInsightsTest {

    private val start = LocalDate.of(2026, 9, 1)

    /** 20 nights; nights after an "alcohol" day have lower HRV and higher resting HR. */
    private fun data(alcoholDays: Set<Int>): Pair<Map<LocalDate, Set<String>>, List<NightSummary>> {
        val tags = alcoholDays.associate { start.plusDays(it.toLong()) to setOf("alcohol") }
        val nights = (1..20).map { i ->
            val wake = start.plusDays(i.toLong())
            val after = (i - 1) in alcoholDays
            NightSummary(
                day = wake, startAt = 0, endAt = 8 * 3600_000L,
                asleepMinutes = if (after) 400 else 450,
                restingHr = if (after) 60.0 else 55.0,
                hrv = if (after) 32.0 else 40.0,
                coverage = 1.0,
            )
        }
        return tags to nights
    }

    @Test
    fun `a tag's effect is read from the night after it`() {
        val (tags, nights) = data(setOf(2, 6, 10, 14))
        val e = JournalInsights.effects(tags, nights).single()
        assertEquals(JournalInsights.Tag.ALCOHOL, e.tag)
        assertEquals(4, e.nightsWith)
        assertEquals(-20, e.hrvPct)
        assertEquals(5, e.restingHr)
        assertEquals(-50, e.sleepMinutes)
        assertTrue(e.summary(), e.summary().startsWith("HRV 20% lower, resting heart rate 5 bpm higher, 50 min less sleep"))
    }

    @Test
    fun `too few tagged nights shows no effect yet`() {
        val (tags, nights) = data(setOf(2, 6))
        val e = JournalInsights.effects(tags, nights).single()
        assertEquals(2, e.nightsWith)
        assertFalse(e.hasEffect)
        assertNull(e.hrvPct)
    }

    @Test
    fun `small differences are not reported as effects`() {
        val tags = setOf(2, 6, 10).associate { start.plusDays(it.toLong()) to setOf("stress") }
        val nights = (1..20).map { i ->
            NightSummary(start.plusDays(i.toLong()), 0, 8 * 3600_000L, 450, 55.0 + (i % 2), 40.0 + (i % 2), 1.0)
        }
        assertFalse(JournalInsights.effects(tags, nights).single().hasEffect)
    }
}
