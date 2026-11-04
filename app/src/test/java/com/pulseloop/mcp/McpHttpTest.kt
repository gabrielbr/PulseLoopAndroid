package com.pulseloop.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class McpHttpTest {
    private fun read(raw: String) = McpHttp.read(ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8)))

    @Test
    fun `a request is read with lower-case headers and its body`() {
        val body = """{"a":1}"""
        val parsed = read("POST /mcp?x=1 HTTP/1.1\r\nHost: phone\r\nAuthorization: Bearer t\r\nContent-Length: ${body.length}\r\n\r\n$body")
        val r = (parsed as McpHttp.Parsed.Ok).request
        assertEquals("POST", r.method)
        assertEquals("/mcp", r.path)
        assertEquals("Bearer t", r.headers["authorization"])
        assertEquals(body, r.body)
    }

    @Test
    fun `a body over the limit is refused without being read`() {
        val parsed = read("POST /mcp HTTP/1.1\r\nContent-Length: ${McpHttp.MAX_BODY + 1}\r\n\r\n")
        assertEquals(McpHttp.Parsed.Refused(413), parsed)
    }

    @Test
    fun `a head over the limit is refused`() {
        val huge = "X-Pad: " + "a".repeat(McpHttp.MAX_HEADER) + "\r\n"
        assertEquals(McpHttp.Parsed.Refused(431), read("POST /mcp HTTP/1.1\r\n$huge\r\n"))
    }

    @Test
    fun `chunked bodies, bad lengths, short bodies and junk are refused`() {
        assertEquals(McpHttp.Parsed.Refused(400), read("POST /mcp HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n"))
        assertEquals(McpHttp.Parsed.Refused(400), read("POST /mcp HTTP/1.1\r\nContent-Length: lots\r\n\r\n"))
        assertEquals(McpHttp.Parsed.Refused(400), read("POST /mcp HTTP/1.1\r\nContent-Length: -1\r\n\r\n"))
        assertEquals(McpHttp.Parsed.Refused(400), read("POST /mcp HTTP/1.1\r\nContent-Length: 10\r\n\r\n{}"))
        assertEquals(McpHttp.Parsed.Refused(400), read("hello\r\n\r\n"))
        assertEquals(McpHttp.Parsed.Refused(400), read(""))
    }

    @Test
    fun `a reply carries its length, no-store and close, and a 401 asks for a bearer`() {
        val out = ByteArrayOutputStream()
        McpHttp.write(out, McpHttp.Response(401))
        val text = out.toString(Charsets.UTF_8.name())
        assertTrue(text.startsWith("HTTP/1.1 401 Unauthorized\r\n"))
        assertTrue(text.contains("WWW-Authenticate: Bearer\r\n"))
        assertTrue(text.contains("Content-Length: 0\r\n"))
        assertTrue(text.contains("Cache-Control: no-store\r\n"))
        assertTrue(text.contains("Connection: close\r\n"))
    }
}
