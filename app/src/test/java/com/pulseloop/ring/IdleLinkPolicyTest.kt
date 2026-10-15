package com.pulseloop.ring

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The R06 export behind [IdleLinkPolicy]: every connection synced, then ended with GATT status
 * 147 roughly 20 s after the last UART write. These pin which drops count as the ring idling.
 */
class IdleLinkPolicyTest {

    @Test
    fun `colmi timeout after a completed sync is an idle drop`() {
        assertTrue(IdleLinkPolicy.isIdleDrop(RingDeviceType.COLMI_R02, syncCompletedOnLink = true, status = 147))
        assertTrue(IdleLinkPolicy.isIdleDrop(RingDeviceType.COLMI_R02, syncCompletedOnLink = true, status = 8))
        assertTrue(IdleLinkPolicy.isIdleDrop(RingDeviceType.COLMI_R02, syncCompletedOnLink = true, status = 19))
    }

    @Test
    fun `a drop before the sync finished is still a failure`() {
        assertFalse(IdleLinkPolicy.isIdleDrop(RingDeviceType.COLMI_R02, syncCompletedOnLink = false, status = 147))
    }

    @Test
    fun `other statuses are not treated as idling`() {
        // 133 is the generic GATT_ERROR, and 0 is a disconnect the app asked for.
        assertFalse(IdleLinkPolicy.isIdleDrop(RingDeviceType.COLMI_R02, syncCompletedOnLink = true, status = 133))
        assertFalse(IdleLinkPolicy.isIdleDrop(RingDeviceType.COLMI_R02, syncCompletedOnLink = true, status = 0))
    }

    @Test
    fun `only the colmi family idles out without a keepalive`() {
        // Jring keeps its link up with pings, so a quiet drop there means something.
        assertFalse(IdleLinkPolicy.isIdleDrop(RingDeviceType.JRING, syncCompletedOnLink = true, status = 147))
        assertFalse(IdleLinkPolicy.isIdleDrop(RingDeviceType.CRP, syncCompletedOnLink = true, status = 147))
        assertFalse(IdleLinkPolicy.isIdleDrop(null, syncCompletedOnLink = true, status = 147))
    }

    @Test
    fun `the watchdog rests until the rest period ends`() {
        val dropAt = 1_000_000L
        val restUntil = dropAt + IdleLinkPolicy.REST_MS
        // The export's failed redials came 3-8 s after the drop.
        assertTrue(IdleLinkPolicy.isResting(restUntil, dropAt + 8_000))
        assertTrue(IdleLinkPolicy.isResting(restUntil, restUntil - 1))
        assertFalse(IdleLinkPolicy.isResting(restUntil, restUntil))
        // Cleared (0) by an explicit reconnect: never resting.
        assertFalse(IdleLinkPolicy.isResting(0L, dropAt))
    }

    @Test
    fun `a spent reconnect budget refills after the cool-down`() {
        val spentAt = 5_000_000L
        assertFalse(IdleLinkPolicy.budgetCooledDown(0L, spentAt))  // not spent
        assertFalse(IdleLinkPolicy.budgetCooledDown(spentAt, spentAt + 60_000))
        assertTrue(IdleLinkPolicy.budgetCooledDown(spentAt, spentAt + IdleLinkPolicy.BACKOFF_COOLDOWN_MS))
    }
}
