package com.pulseloop.mcp

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** The whole path over a real loopback socket. */
class McpServerSocketTest {
    private val tokens = McpTokens(MemoryTokenStorage())
    private val token = tokens.create("Cursor", 1_000L).second
    private val server = McpServer(McpHandler(FakeToolTable(), tokens, "1.0"))

    @After fun stop() = server.stop()

    private fun http(at: InetSocketAddress, raw: String): String = Socket().use { s ->
        s.connect(at, 2_000)
        s.soTimeout = 5_000
        s.getOutputStream().apply { write(raw.toByteArray(Charsets.UTF_8)); flush() }
        String(s.getInputStream().readBytes(), Charsets.UTF_8)
    }

    @Test
    fun `never listens on a public address`() {
        assertTrue(McpServer(McpHandler(FakeToolTable(), tokens, "1.0")).start(listOf(InetAddress.getByName("8.8.8.8")), 0).isEmpty())
    }

    @Test
    fun `follows the phone's addresses while running`() {
        val loopback = InetAddress.getLoopbackAddress()
        // Before start (and after stop) a network change binds nothing.
        assertTrue(server.rebind(listOf(loopback), 0).isEmpty())
        val first = server.start(listOf(loopback), 0)
        assertEquals(1, first.size)
        // The same addresses again: the listener is kept, not re-made on a new port.
        assertEquals(first, server.rebind(listOf(loopback), 0))
        // A public address arriving is still never listened on.
        assertEquals(first, server.rebind(listOf(loopback, InetAddress.getByName("8.8.8.8")), 0))
        // The address went away: nothing listens there any more.
        assertTrue(server.rebind(emptyList(), 0).isEmpty())
        assertFalse(runCatching { Socket().use { it.connect(first[0], 500) } }.isSuccess)
        // It came back: listening again, and answering.
        val again = server.rebind(listOf(loopback), 0)
        assertEquals(1, again.size)
        val body = rpc(1, "tools/list")
        val ok = http(again[0], "POST /mcp HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer $token\r\nContent-Type: application/json\r\n" +
            "Content-Length: ${body.toByteArray().size}\r\n\r\n$body")
        assertTrue(ok, ok.startsWith("HTTP/1.1 200 OK"))
        server.stop()
        assertTrue(server.rebind(listOf(loopback), 0).isEmpty())
    }

    @Test
    fun `a real request, a refused one, and a body too large`() {
        val at = server.start(listOf(InetAddress.getLoopbackAddress()), 0)
        assertTrue(at.size == 1)
        val body = rpc(1, "tools/list")
        val ok = http(at[0], "POST /mcp HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer $token\r\nContent-Type: application/json\r\n" +
            "Content-Length: ${body.toByteArray().size}\r\n\r\n$body")
        assertTrue(ok, ok.startsWith("HTTP/1.1 200 OK") && ok.contains("\"tools\"") && ok.contains("\"echo\""))
        assertTrue(http(at[0], "POST /mcp HTTP/1.1\r\nContent-Length: 2\r\n\r\n{}").startsWith("HTTP/1.1 401"))
        assertTrue(http(at[0], "POST /mcp HTTP/1.1\r\nOrigin: http://evil\r\nAuthorization: Bearer $token\r\nContent-Length: 2\r\n\r\n{}")
            .startsWith("HTTP/1.1 403"))
        assertTrue(http(at[0], "POST /mcp HTTP/1.1\r\nAuthorization: Bearer $token\r\nContent-Length: 999999999\r\n\r\n")
            .startsWith("HTTP/1.1 413"))
    }
}
