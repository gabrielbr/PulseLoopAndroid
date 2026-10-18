package com.pulseloop.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** "Your usual range" chart bands (docs/oura-ux-analysis.md phase 5). */
class UsualRangeTest {

    private val day = 86_400_000L

    @Test
    fun `the band is the middle half of the user's own readings`() {
        // 40 readings over 10 days, values 50..89.
        val samples = (0 until 40).map { VitalSample(it * day / 4, 50.0 + it) }
        val r = UsualRange.of(samples)
        assertNotNull(r)
        assertEquals(59.75, r!!.start, 0.01)
        assertEquals(79.25, r.endInclusive, 0.01)
    }

    @Test
    fun `no band until there is a week of history`() {
        val threeDays = (0 until 40).map { VitalSample(it * day / 14, 60.0 + it % 5) }
        assertNull(UsualRange.of(threeDays))
    }

    @Test
    fun `no band from too few readings`() {
        val sparse = (0 until 10).map { VitalSample(it * day, 60.0 + it) }
        assertNull(UsualRange.of(sparse))
    }
}
