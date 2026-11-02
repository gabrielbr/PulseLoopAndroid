package com.pulseloop.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The pure parts of the self-update rules (Gabriel Design System, docs/self-update.md). */
class UpdateCheckerTest {
    private val sum = "a".repeat(64)
    private val other = "B".repeat(64)

    @Test
    fun `sha256sum output maps file names to lowercase sums`() {
        val sums = UpdateChecker.parseSums("$sum  pulseloop-2.11.54+129-universal.apk\n$other *GMind-0.5.246.apk\n\nnot a line\n")
        assertEquals(sum, sums["pulseloop-2.11.54+129-universal.apk"])
        assertEquals("b".repeat(64), sums["GMind-0.5.246.apk"])
        assertEquals(2, sums.size)
    }

    @Test
    fun `universal APK first, then this phone's ABI, then any, and only over https`() {
        fun a(name: String, url: String = "https://example.test/$name") = GithubAsset(name, url)
        val assets = listOf(a("x-arm64-v8a.apk"), a("x-universal.apk"), a("SHA256SUMS.txt"))
        assertEquals("x-universal.apk", UpdateChecker.chooseApk(assets, listOf("arm64-v8a"))?.name)
        assertEquals("x-arm64-v8a.apk", UpdateChecker.chooseApk(assets.take(1) + a("x-x86.apk"), listOf("arm64-v8a"))?.name)
        assertEquals("x-x86.apk", UpdateChecker.chooseApk(listOf(a("x-x86.apk")), listOf("arm64-v8a"))?.name)
        assertNull(UpdateChecker.chooseApk(listOf(a("x-universal.apk", "http://example.test/x")), listOf("arm64-v8a")))
        assertNull(UpdateChecker.chooseApk(listOf(a("notes.txt")), listOf("arm64-v8a")))
    }

    @Test
    fun `size label for the Update button`() {
        assertEquals("38 MB", UpdateChecker.sizeLabel(38_200_000))
        assertEquals("1 MB", UpdateChecker.sizeLabel(10_000))
        assertEquals("1.2 GB", UpdateChecker.sizeLabel(1_200_000_000))
    }

    @Test
    fun `digest as lowercase hex`() {
        assertEquals("00ff10", UpdateChecker.hex(byteArrayOf(0, -1, 16)))
    }

    @Test
    fun `version from the tag`() {
        assertEquals(129, UpdateChecker.versionCodeFromTag("v2.11.54+129"))
        assertEquals("2.11.54", UpdateChecker.versionNameFromTag("v2.11.54+129"))
    }
}
