package com.pulseloop.notifications

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Battery-before-bed, inactivity and step-progress nudges. */
class NudgesTest {

    @Test
    fun `charge reminder fires on low battery a couple of hours before bed`() {
        assertTrue(Nudges.chargeBeforeBed(battery = 25, minsToBed = 90))
        assertFalse(Nudges.chargeBeforeBed(battery = 45, minsToBed = 90))
        assertFalse(Nudges.chargeBeforeBed(battery = 25, minsToBed = 20))
        assertFalse(Nudges.chargeBeforeBed(battery = null, minsToBed = 90))
        assertFalse(Nudges.chargeBeforeBed(battery = 25, minsToBed = null))
    }

    @Test
    fun `inactivity needs fresh data and waking hours`() {
        assertTrue(Nudges.inactive(hour = 14, stepsLastHour = 40, dataFresh = true))
        assertFalse(Nudges.inactive(hour = 14, stepsLastHour = 40, dataFresh = false))
        assertFalse(Nudges.inactive(hour = 22, stepsLastHour = 40, dataFresh = true))
        assertFalse(Nudges.inactive(hour = 14, stepsLastHour = 500, dataFresh = true))
    }

    @Test
    fun `step progress speaks only early evening and under 70 percent`() {
        assertNotNull(Nudges.progress(hour = 18, steps = 3000, goal = 8000))
        assertNull(Nudges.progress(hour = 18, steps = 7000, goal = 8000))
        assertNull(Nudges.progress(hour = 12, steps = 1000, goal = 8000))
        assertNull(Nudges.progress(hour = 18, steps = 1000, goal = 0))
    }
}
