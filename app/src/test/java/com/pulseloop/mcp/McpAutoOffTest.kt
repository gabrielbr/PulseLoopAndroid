package com.pulseloop.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Android 15 caps a dataSync foreground service at about six hours a day (McpConfig.autoOffHours). */
class McpAutoOffTest {
    private val android14 = 34
    private val android15 = 35

    @Test
    fun `older Android keeps the 1, 4 and 8 hour choices with 8 as the default`() {
        assertEquals(listOf(1, 4, 8), McpConfig.autoOffChoices(android14))
        assertEquals(8, McpConfig.defaultAutoOffHours(android14))
        listOf(1, 4, 8).forEach { assertEquals(it, McpConfig.autoOffHours(it, android14)) }
    }

    @Test
    fun `Android 15 and later offer nothing above six hours`() {
        listOf(android15, 36).forEach { sdk ->
            val choices = McpConfig.autoOffChoices(sdk)
            assertTrue(choices.all { it <= McpConfig.DATA_SYNC_LIMIT_HOURS })
            assertEquals(listOf(1, 4, 6), choices)
            assertEquals(6, McpConfig.defaultAutoOffHours(sdk))
        }
    }

    @Test
    fun `a stored eight hours reads as six on Android 15`() {
        assertEquals(6, McpConfig.autoOffHours(8, android15))
        assertEquals(6, McpConfig.autoOffHours(24, android15))
        assertEquals(4, McpConfig.autoOffHours(4, android15))
        assertEquals(1, McpConfig.autoOffHours(1, android15))
    }

    @Test
    fun `a value that is not a choice falls back to the default`() {
        assertEquals(8, McpConfig.autoOffHours(0, android14))
        assertEquals(8, McpConfig.autoOffHours(6, android14))
        assertEquals(6, McpConfig.autoOffHours(0, android15))
        assertEquals(6, McpConfig.autoOffHours(-3, android15))
    }
}
