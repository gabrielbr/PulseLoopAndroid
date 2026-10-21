package com.pulseloop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoBackupTest {
    private val day = 24 * 3_600_000L

    @Test
    fun `a backup is due after its interval, with half an hour of slack`() {
        val now = 100 * day
        assertTrue(AutoBackup.isDue(0L, 1, now))
        assertTrue(AutoBackup.isDue(now - day + 10 * 60_000L, 1, now))
        assertFalse(AutoBackup.isDue(now - 12 * 3_600_000L, 1, now))
        assertFalse(AutoBackup.isDue(now - 3 * day, 7, now))
    }

    @Test
    fun `only our own backups beyond the newest few are pruned`() {
        val names = listOf(
            "pulseloop-backup-2026-10-01-0300.json", "pulseloop-backup-2026-10-03-0300.json",
            "pulseloop-backup-2026-10-02-0300.json", "holiday.jpg", "pulseloop-export-2026-09-01-1200.json",
        )
        assertEquals(listOf("pulseloop-backup-2026-10-01-0300.json"), AutoBackup.toPrune(names, 2))
    }
}
