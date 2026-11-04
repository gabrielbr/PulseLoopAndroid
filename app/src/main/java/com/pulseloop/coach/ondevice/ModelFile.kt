package com.pulseloop.coach.ondevice

import java.io.File
import java.io.InputStream

/** Checks on a model file before it's used, copied from Cochilo's `ModelFile`. Pure, so unit-tested. */
object ModelFile {
    /** A real model is hundreds of MB; anything this small was the wrong file. */
    const val MIN_BYTES = 50L * 1024 * 1024

    /**
     * Whether the first bytes could be a model. Google's current Gemma `.task` isn't a zip, so the
     * container isn't checked: only that it isn't text, the usual wrong file (an HTML error page,
     * JSON, a note). A download is checked against its published SHA-256 as well, and the runtime
     * refuses anything else it can't load.
     */
    fun looksLikeTask(head: ByteArray): Boolean {
        val first = head.firstOrNull {
            it != ' '.code.toByte() && it != '\n'.code.toByte() && it != '\r'.code.toByte() && it != '\t'.code.toByte()
        }?.toInt()?.toChar() ?: return false
        return first != '<' && first != '{' && first != '['
    }

    fun check(head: ByteArray, bytes: Long): ModelState.Reason? = when {
        !looksLikeTask(head) -> ModelState.Reason.NOT_A_MODEL
        bytes < MIN_BYTES -> ModelState.Reason.TOO_SMALL
        else -> null
    }

    /**
     * Whether a volume with [usableBytes] free can take a file of [fileBytes]: the file plus a
     * [MIN_BYTES] margin, so the phone isn't left with nothing.
     */
    fun hasSpaceFor(fileBytes: Long, usableBytes: Long): Boolean = usableBytes >= fileBytes + MIN_BYTES

    /**
     * Copies [input] to [target] through a temporary file, reporting bytes copied; the old file
     * stays until the copy is whole.
     */
    fun copy(input: InputStream, target: File, onBytes: (Long) -> Unit): Long {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".part")
        var total = 0L
        try {
            tmp.outputStream().use { out ->
                val buf = ByteArray(1 shl 20)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                    onBytes(total)
                }
            }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) { tmp.delete(); error("could not move the model into place") }
        return total
    }
}
