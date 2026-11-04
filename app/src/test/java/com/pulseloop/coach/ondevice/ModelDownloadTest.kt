package com.pulseloop.coach.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Copied from Cochilo's ModelDownloadTest, adapted to PulseLoop's constants. */
class ModelDownloadTest {
    @Test fun urls_point_at_the_prerelease() {
        assertEquals(
            "https://github.com/gabrielbr/Cochilo/releases/download/assistant-model-v1/gemma3-1b-it-int4.task",
            ModelDownload.modelUrl(),
        )
        assertTrue(ModelDownload.sumUrl().endsWith(".task.sha256"))
        // Moving to PulseLoop's own pre-release is a change of repository only.
        assertEquals(
            "https://github.com/gabrielbr/PulseLoopAndroid/releases/download/assistant-model-v1/gemma3-1b-it-int4.task",
            ModelDownload.modelUrl("gabrielbr/PulseLoopAndroid"),
        )
    }

    @Test fun reads_a_sha256sum_line_and_refuses_anything_else() {
        val h = "a".repeat(64)
        assertEquals(h, ModelDownload.parseSum("$h  gemma3-1b-it-int4.task\n"))
        assertEquals(h, ModelDownload.parseSum(h.uppercase()))
        assertNull(ModelDownload.parseSum("Not Found"))
        assertNull(ModelDownload.parseSum("<html>"))
        assertNull(ModelDownload.parseSum(""))
        assertNull(ModelDownload.parseSum("a".repeat(63)))
    }

    @Test fun verify_checks_the_header_the_size_and_the_hash() {
        val f = File.createTempFile("model", ".task")
        try {
            f.writeBytes(byteArrayOf('P'.code.toByte(), 'K'.code.toByte()) + ByteArray(10))
            assertEquals(ModelState.Reason.TOO_SMALL, ModelDownload.verify(f, ModelDownload.sha256(f)))
            f.writeBytes("<html>Not Found</html>".toByteArray())
            assertEquals(ModelState.Reason.NOT_A_MODEL, ModelDownload.verify(f, ModelDownload.sha256(f)))
            assertFalse(ModelDownload.sha256(f) == "0".repeat(64))
        } finally { f.delete() }
    }

    @Test fun sha256_matches_a_known_digest() {
        val f = File.createTempFile("abc", ".txt")
        try {
            f.writeText("abc")
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ModelDownload.sha256(f))
        } finally { f.delete() }
    }

    @Test fun the_memory_floor_takes_a_4_gb_phone_and_refuses_a_3_gb_one() {
        val gib = 1024L * 1024 * 1024
        // A "4 GB" phone reports a little under 4 GiB once the kernel takes its share.
        assertTrue(ModelDownload.meetsMemoryFloor(3_700_000_000L))
        assertTrue(ModelDownload.meetsMemoryFloor(8 * gib))
        assertFalse(ModelDownload.meetsMemoryFloor(3 * gib))
        assertFalse(ModelDownload.meetsMemoryFloor(0))
    }
}
