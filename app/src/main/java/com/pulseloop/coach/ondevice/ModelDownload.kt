package com.pulseloop.coach.ondevice

import java.io.File
import java.security.MessageDigest

/**
 * Where the on-device coach's model comes from, and how what arrived is checked. Adapted from
 * Cochilo's `ModelDownload`: the model sits on a GitHub **pre-release** (so the self-updater,
 * which reads only full releases, never offers it), with a `sha256sum` line beside it.
 *
 * For now PulseLoop downloads Cochilo's published file ([REPO], [TAG]); moving to PulseLoop's own
 * pre-release later is a change to those two constants. Pure, so it's unit-tested.
 */
object ModelDownload {
    /** The repository whose pre-release holds the model. */
    const val REPO = "gabrielbr/Cochilo"
    const val TAG = "assistant-model-v1"
    /** Gemma 3 1B, int4, for MediaPipe LLM Inference. */
    const val FILE = "gemma3-1b-it-int4.task"
    /** What Settings promises before anything moves (the file is about 555–585 MB). */
    const val APPROX_MB = 600
    /** Bytes the free-space check plans for: [APPROX_MB], rounded up a little. */
    const val APPROX_BYTES = 620L * 1024 * 1024

    /** The model's name on the coach screen and in Settings. A product name: not translated. */
    const val DISPLAY_NAME = "Gemma 3 1B"
    /** The slug a turn is attributed to (usage sheet, pricing). */
    const val MODEL_SLUG = "gemma3-1b-on-device"

    /** Below this much memory the phone isn't offered the model: loading it would starve the app. */
    const val MIN_TOTAL_MEMORY_BYTES = 4L * 1000 * 1000 * 1000 - 400L * 1024 * 1024

    /** The Gemma licence, linked before the download. */
    const val TERMS_URL = "https://ai.google.dev/gemma/terms"

    fun url(repo: String, name: String) = "https://github.com/$repo/releases/download/$TAG/$name"
    fun modelUrl(repo: String = REPO) = url(repo, FILE)
    fun sumUrl(repo: String = REPO) = modelUrl(repo) + ".sha256"

    /**
     * Whether a phone with [totalMemoryBytes] is offered the model. "4 GB" phones report a little
     * under 4 GiB once the kernel takes its share, hence the margin in [MIN_TOTAL_MEMORY_BYTES].
     * 0 (unknown) is not offered.
     */
    fun meetsMemoryFloor(totalMemoryBytes: Long): Boolean = totalMemoryBytes >= MIN_TOTAL_MEMORY_BYTES

    /** The hash in a `sha256sum` line ("<64 hex>  <name>"), or null when it isn't one. */
    fun parseSum(text: String): String? =
        text.trim().split(Regex("\\s+")).firstOrNull()?.lowercase()
            ?.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { s ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** What's wrong with a finished download, or null when it's the model we published. */
    fun verify(file: File, expectedSha: String): ModelState.Reason? {
        val head = file.inputStream().use { s -> ByteArray(16).also { s.read(it) } }
        ModelFile.check(head, file.length())?.let { return it }
        return if (sha256(file) == expectedSha.lowercase()) null else ModelState.Reason.CHECK
    }
}
