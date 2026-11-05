package com.pulseloop.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Always on": its foreground-service type, its off time and when it comes back by itself. */
class McpAlwaysOnTest {

    @Test
    fun `always on uses specialUse from Android 14, dataSync before and for timed sessions`() {
        assertTrue(McpConfig.usesSpecialUse(alwaysOn = true, sdkInt = 34))
        assertTrue(McpConfig.usesSpecialUse(alwaysOn = true, sdkInt = 35))
        assertFalse(McpConfig.usesSpecialUse(alwaysOn = true, sdkInt = 33))
        assertFalse(McpConfig.usesSpecialUse(alwaysOn = false, sdkInt = 35))
    }

    @Test
    fun `always on never reaches its off time, a timed session does`() {
        val now = 1_000_000L
        assertEquals(McpConfig.NEVER, McpConfig.offAt(now, alwaysOn = true, hours = 4))
        assertEquals(now + 4 * 3_600_000L, McpConfig.offAt(now, alwaysOn = false, hours = 4))
        assertTrue(now < McpConfig.offAt(now, alwaysOn = true, hours = 1))
    }

    @Test
    fun `only an always-on server left on comes back after a reboot`() {
        assertTrue(McpConfig.restartAfterBoot(enabled = true, alwaysOn = true))
        assertFalse(McpConfig.restartAfterBoot(enabled = true, alwaysOn = false))
        assertFalse(McpConfig.restartAfterBoot(enabled = false, alwaysOn = true))
        assertFalse(McpConfig.restartAfterBoot(enabled = false, alwaysOn = false))
    }
}
