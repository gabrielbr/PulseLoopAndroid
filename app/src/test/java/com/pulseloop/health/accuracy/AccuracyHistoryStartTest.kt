package com.pulseloop.health.accuracy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** A check that reaches back grades from local midnight N days ago, never before the ring was paired. */
class AccuracyHistoryStartTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private fun at(y: Int, m: Int, d: Int, h: Int = 0) = LocalDateTime.of(y, m, d, h, 0).atZone(zone).toInstant().toEpochMilli()
    private val now = at(2026, 11, 18, 15)

    @Test fun noLookBackMeansNoHistory() {
        assertNull(AccuracyCheckController.historyStart(now, 0, at(2026, 1, 1), zone))
    }

    @Test fun startsAtLocalMidnightNDaysAgo() {
        assertEquals(at(2026, 11, 4), AccuracyCheckController.historyStart(now, 14, at(2026, 1, 1), zone))
    }

    @Test fun neverBeforeTheRingWasPaired() {
        val paired = at(2026, 11, 10, 9)
        assertEquals(paired, AccuracyCheckController.historyStart(now, 30, paired, zone))
    }

    @Test fun aRingPairedJustNowHasNoPast() {
        assertNull(AccuracyCheckController.historyStart(now, 7, now, zone))
    }
}
