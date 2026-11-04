package com.pulseloop.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class McpNetAndTokensTest {
    private fun ip(s: String) = InetAddress.getByName(s)

    @Test
    fun `private, link-local, loopback and Tailscale peers are answered`() {
        listOf("127.0.0.1", "10.0.0.5", "172.16.3.4", "172.31.255.1", "192.168.1.20", "169.254.10.1",
            "100.64.0.1", "100.101.1.2", "100.127.255.254", "::1", "fd12::1", "fe80::1")
            .forEach { assertTrue(it, McpNet.allowedPeer(ip(it))) }
    }

    @Test
    fun `public peers are refused`() {
        listOf("8.8.8.8", "1.1.1.1", "172.32.0.1", "100.63.255.255", "100.128.0.1", "100.200.1.1",
            "2001:4860::8888", "::ffff:8.8.8.8")
            .forEach { assertFalse(it, McpNet.allowedPeer(ip(it))) }
        assertFalse(McpNet.allowedPeer(null))
    }

    @Test
    fun `urls put IPv6 in brackets and drop the scope`() {
        assertEquals("http://192.168.1.20:8765/mcp", McpNet.url(ip("192.168.1.20")))
        assertEquals("http://[fd12:0:0:0:0:0:0:1]:8765/mcp", McpNet.url(ip("fd12::1")))
    }

    @Test
    fun `a token is long and random, and only its hash is kept`() {
        val storage = MemoryTokenStorage()
        val tokens = McpTokens(storage)
        val (tool, token) = tokens.create("  Claude Desktop  ", 1_000L)
        val (_, other) = tokens.create("", 1_000L)
        assertTrue(token.startsWith(McpTokens.PREFIX))
        assertTrue(token.length >= McpTokens.PREFIX.length + 43) // 32 bytes, base64url without padding
        assertNotEquals(token, other)
        assertEquals("Claude Desktop", tool.label)
        assertEquals(McpTokens.DEFAULT_LABEL, tokens.list().first { it.id != tool.id }.label)
        assertEquals(McpTokens.hash(token), storage.tools.first { it.id == tool.id }.hash)
        assertTrue(storage.tools.none { it.hash.contains(token) || it.label.contains(token) })
    }

    @Test
    fun `check matches the right tool, refuses guesses, and notes last use`() {
        val tokens = McpTokens(MemoryTokenStorage())
        val (tool, token) = tokens.create("Cursor", 1_000L)
        assertEquals(tool.id, tokens.check(token, 200_000L)?.id)
        assertEquals(200_000L, tokens.list().single().lastUsedAt)
        assertNull(tokens.check(null, 0L))
        assertNull(tokens.check("pulseloop_guess", 0L))
        assertNull(tokens.check(token.drop(McpTokens.PREFIX.length), 0L))
        assertNull(tokens.check(McpTokens.PREFIX + "a".repeat(300), 0L))
    }

    @Test
    fun `a revoked token is refused`() {
        val tokens = McpTokens(MemoryTokenStorage())
        val (tool, token) = tokens.create("Cursor", 1_000L)
        val (keep, kept) = tokens.create("Claude", 2_000L)
        tokens.revoke(tool.id)
        assertNull(tokens.check(token, 3_000L))
        assertNotNull(tokens.check(kept, 3_000L))
        assertEquals(listOf(keep.id), tokens.list().map { it.id })
    }

    @Test
    fun `bearer is read case-insensitively and nothing else is`() {
        assertEquals("abc", McpTokens.bearer("Bearer abc"))
        assertEquals("abc", McpTokens.bearer("bearer  abc "))
        assertNull(McpTokens.bearer("Basic abc"))
        assertNull(McpTokens.bearer(null))
    }

    @Test
    fun `the config to paste carries the url and the token`() {
        val bridge = McpConfig.stdioBridge("http://192.168.1.20:8765/mcp", "pulseloop_abc")
        assertTrue(bridge.contains("\"mcp-remote\", \"http://192.168.1.20:8765/mcp\", \"--allow-http\""))
        assertTrue(bridge.contains("\"Authorization:\${PULSELOOP_AUTH}\""))
        assertTrue(bridge.contains("\"PULSELOOP_AUTH\": \"Bearer pulseloop_abc\""))
        kotlinx.serialization.json.Json.parseToJsonElement(bridge) // valid JSON
        assertTrue(McpConfig.direct("http://x/mcp", "pulseloop_abc").contains("Authorization: Bearer pulseloop_abc"))
    }
}
