package com.pulseloop.coach.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

/** Copied from Cochilo's ModelFileTest (the `.task` parts), plus the free-space rule. */
class ModelFileTest {
    private val zip = byteArrayOf('P'.code.toByte(), 'K'.code.toByte())

    @Test fun only_a_task_file_of_a_real_size_is_taken() {
        assertNull(ModelFile.check(zip, 600L * 1024 * 1024))
        // Google's current Gemma .task isn't a zip: a binary header is fine.
        assertNull(ModelFile.check(byteArrayOf(0x1C, 0x00, 0x00, 0x00, 'T'.code.toByte()), 600L * 1024 * 1024))
        assertEquals(ModelState.Reason.NOT_A_MODEL, ModelFile.check("{}".toByteArray(), 600L * 1024 * 1024))
        assertEquals(ModelState.Reason.NOT_A_MODEL, ModelFile.check("  <!DOCTYPE html>".toByteArray(), 600L * 1024 * 1024))
        assertEquals(ModelState.Reason.TOO_SMALL, ModelFile.check(zip, 1024))
        assertFalse(ModelFile.looksLikeTask(ByteArray(0)))
    }

    @Test fun the_copy_lands_whole_and_reports_progress() {
        val dir = Files.createTempDirectory("ondevice").toFile()
        try {
            val target = File(dir, "models/assistant.task")
            val data = ByteArray(3 * (1 shl 20) + 7) { it.toByte() }
            val seen = mutableListOf<Long>()
            val n = ModelFile.copy(ByteArrayInputStream(data), target) { seen += it }
            assertEquals(data.size.toLong(), n)
            assertTrue(target.readBytes().contentEquals(data))
            assertEquals(data.size.toLong(), seen.last())
            assertFalse(File(target.parentFile, "assistant.task.part").exists())
            // A second copy replaces the first.
            val again = ByteArray(10) { 1 }
            ModelFile.copy(ByteArrayInputStream(again), target) { }
            assertTrue(target.readBytes().contentEquals(again))
        } finally { dir.deleteRecursively() }
    }

    @Test fun free_space_keeps_a_margin() {
        val mb = 1024L * 1024
        assertTrue(ModelFile.hasSpaceFor(600 * mb, 700 * mb))
        assertFalse(ModelFile.hasSpaceFor(600 * mb, 620 * mb))
    }
}
