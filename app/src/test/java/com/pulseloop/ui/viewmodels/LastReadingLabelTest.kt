package com.pulseloop.ui.viewmodels

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

/**
 * The vital cards say when the newest reading was taken, not how long ago: "Updated 2h ago"
 * read as a stale sync when the ring had simply recorded nothing newer.
 */
class LastReadingLabelTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private lateinit var saved: Locale

    @Before fun setUp() { saved = Locale.getDefault(); Locale.setDefault(Locale.UK) }
    @After fun tearDown() { Locale.setDefault(saved) }

    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private val now = ms(2026, 11, 11, 17, 4)

    @Test fun todayShowsTheClockTime() {
        assertEquals("Last reading 14:28", VitalsCardFactory.lastUpdated(ms(2026, 11, 11, 14, 28), now, zone))
    }

    @Test fun earlierThisWeekAddsTheWeekday() {
        assertEquals("Last reading Mon 22:10", VitalsCardFactory.lastUpdated(ms(2026, 11, 9, 22, 10), now, zone))
    }

    @Test fun olderThanAWeekShowsTheDate() {
        assertEquals("Last reading 1 Nov", VitalsCardFactory.lastUpdated(ms(2026, 11, 1, 9, 0), now, zone))
    }

    @Test fun noReadingNoLabel() {
        assertNull(VitalsCardFactory.lastUpdated(null, now, zone))
    }
}
