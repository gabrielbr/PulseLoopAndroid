package com.pulseloop.service

import org.junit.Assert.assertEquals
import org.junit.Test

class StatsTest {

    @Test
    fun `welch t-test matches a textbook value`() {
        // Means 3 and 5, variance 2.5 each, n = 5: t = -2 on 8 df, two-sided p ≈ 0.0805.
        assertEquals(0.0805, Stats.welchP(listOf(1.0, 2.0, 3.0, 4.0, 5.0), listOf(3.0, 4.0, 5.0, 6.0, 7.0)), 0.001)
        assertEquals(1.0, Stats.welchP(listOf(2.0, 2.0), listOf(2.0, 2.0)), 0.0)
        assertEquals(0.0, Stats.welchP(listOf(2.0, 2.0), listOf(3.0, 3.0)), 0.0)
    }

    @Test
    fun `benjamini-hochberg keeps the ranks under the line`() {
        assertEquals(listOf(true, true, true, false), Stats.benjaminiHochberg(listOf(0.01, 0.04, 0.03, 0.2), 0.10))
        assertEquals(listOf(false, false), Stats.benjaminiHochberg(listOf(0.3, 0.5), 0.10))
    }
}
