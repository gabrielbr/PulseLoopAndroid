package com.pulseloop.mcp

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/** A paired AI tool. Only the SHA-256 of its token is kept; the token itself is shown once. */
@Serializable
data class PairedTool(
    val id: String,
    val label: String,
    val hash: String,
    val createdAt: Long,
    val lastUsedAt: Long? = null,
)

/** Where pairings are kept: `McpPrefs` on the phone, a list in tests. */
interface McpTokenStorage {
    fun loadTools(): List<PairedTool>
    fun saveTools(tools: List<PairedTool>)
}

/**
 * Pairing for AI tools on your network: each tool gets its own random token (32 bytes,
 * base64url), shown once and stored only as its SHA-256, compared in constant time, revocable one
 * by one. Plain Kotlin, JVM-tested (`McpTokensTest`). Ported from GMind's `McpTokens`.
 *
 * Pairings are security material and device state: they never ride in the backup archive, so a
 * restore on a new phone starts with nothing paired.
 */
class McpTokens(private val storage: McpTokenStorage) {
    /** One lock for every instance in the process: the service and the settings screen each hold
     *  one over the same file, and a read-modify-write from each must not drop the other's. */
    private val lock = LOCK

    /** Pairs a tool; returns it and its token, which is never shown again. */
    fun create(label: String?, now: Long): Pair<PairedTool, String> = synchronized(lock) {
        val secret = ByteArray(32)
        RANDOM.nextBytes(secret)
        val token = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(secret)
        val name = label?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_LABEL) ?: DEFAULT_LABEL
        val tool = PairedTool(id = UUID.randomUUID().toString(), label = name, hash = hash(token), createdAt = now)
        storage.saveTools(storage.loadTools() + tool)
        tool to token
    }

    /** The paired tool a token belongs to, or null (unknown, revoked, or malformed). */
    fun check(token: String?, now: Long): PairedTool? = synchronized(lock) {
        if (token == null || !token.startsWith(PREFIX) || token.length > 200) return null
        val h = hash(token).toByteArray(Charsets.US_ASCII)
        val tools = storage.loadTools()
        // Compare against every pairing, in constant time, so timing says nothing about which matched.
        var found: PairedTool? = null
        for (t in tools) {
            if (MessageDigest.isEqual(h, t.hash.toByteArray(Charsets.US_ASCII)) && found == null) found = t
        }
        val match = found ?: return null
        // "Last used" to the minute: no need to write preferences on every request.
        if (match.lastUsedAt == null || now - match.lastUsedAt >= 60_000L) {
            storage.saveTools(tools.map { if (it.id == match.id) it.copy(lastUsedAt = now) else it })
        }
        match
    }

    fun revoke(id: String) = synchronized(lock) {
        storage.saveTools(storage.loadTools().filterNot { it.id == id })
    }

    /** Tools still paired, newest first. */
    fun list(): List<PairedTool> = synchronized(lock) { storage.loadTools().sortedByDescending { it.createdAt } }

    companion object {
        const val PREFIX = "pulseloop_"
        const val DEFAULT_LABEL = "AI tool"
        const val MAX_LABEL = 60
        private val RANDOM = SecureRandom()
        private val LOCK = Any()

        fun hash(token: String): String =
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }

        /** The token from an `Authorization: Bearer …` header, or null. */
        fun bearer(header: String?): String? =
            if (header != null && header.regionMatches(0, "Bearer ", 0, 7, ignoreCase = true)) header.substring(7).trim() else null
    }
}
