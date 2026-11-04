package com.pulseloop.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** The SHA-256 check before an update installs (design system docs/self-update.md, check 3). */
class UpdateChecksumTest {
    private val h1 = "a".repeat(64)
    private val h2 = "0123456789ABCDEF".repeat(4)

    @Test fun `finds the line for the apk, in sha256sum's format`() {
        val sums = "$h1  other.apk\n$h2  pulseloop-2.11.56+131-universal.apk\n"
        assertEquals(h2.lowercase(), UpdateChecker.sha256For(sums, "pulseloop-2.11.56+131-universal.apk"))
    }

    @Test fun `accepts the binary-mode star`() {
        assertEquals(h1, UpdateChecker.sha256For("$h1 *app.apk", "app.apk"))
    }

    @Test fun `no line for the file means no sum, so no install`() {
        assertNull(UpdateChecker.sha256For("$h1  other.apk", "app.apk"))
        assertNull(UpdateChecker.sha256For("", "app.apk"))
        assertNull(UpdateChecker.sha256For("abc  app.apk", "app.apk"))
    }

    @Test fun `a name is matched whole, not as a suffix`() {
        assertNull(UpdateChecker.sha256For("$h1  evil-app.apk", "app.apk"))
    }

    @Test fun `hashes a file`() {
        val f = File.createTempFile("sum", ".bin").apply { writeText("abc"); deleteOnExit() }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", UpdateChecker.sha256Hex(f))
    }
}
