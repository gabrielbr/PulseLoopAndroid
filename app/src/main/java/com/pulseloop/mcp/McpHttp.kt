package com.pulseloop.mcp

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

/**
 * The little HTTP/1.1 the MCP server needs: one request per connection, a bounded head, a bounded
 * `Content-Length` body, no chunked bodies, then `Connection: close`. Plain Kotlin, no Android
 * imports, so it is JVM-tested (`McpHttpTest`). Ported from GMind's `McpServer.readHead/readBody`.
 */
object McpHttp {
    const val MAX_HEADER = 8 * 1024
    const val MAX_BODY = 256 * 1024

    /** A request as read. Header names are lower-case. */
    data class Request(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String,
    )

    /** A reply. An empty body sends no Content-Type. */
    data class Response(val status: Int, val body: String = "")

    /** What reading produced: a request, or the status to refuse it with. */
    sealed class Parsed {
        data class Ok(val request: Request) : Parsed()
        data class Refused(val status: Int) : Parsed()
    }

    fun read(input: InputStream): Parsed {
        val head = readHead(input) ?: return Parsed.Refused(431)
        if (head.isEmpty()) return Parsed.Refused(400)
        val lines = head.split("\r\n")
        val start = lines[0].split(" ")
        if (start.size < 3 || !start[2].startsWith("HTTP/")) return Parsed.Refused(400)
        val headers = LinkedHashMap<String, String>()
        for (i in 1 until lines.size) {
            val colon = lines[i].indexOf(':')
            if (colon > 0) {
                headers[lines[i].substring(0, colon).trim().lowercase(Locale.ROOT)] = lines[i].substring(colon + 1).trim()
            }
        }
        if (headers.containsKey("transfer-encoding")) return Parsed.Refused(400)
        val length = headers["content-length"]?.let { it.toIntOrNull() ?: return Parsed.Refused(400) } ?: 0
        if (length < 0) return Parsed.Refused(400)
        if (length > MAX_BODY) return Parsed.Refused(413)
        val body = readBody(input, length) ?: return Parsed.Refused(400)
        val path = start[1].substringBefore('?')
        return Parsed.Ok(Request(start[0], path, headers, String(body, Charsets.UTF_8)))
    }

    /** The head up to the blank line, or null when it runs past [MAX_HEADER]; "" when the client left. */
    private fun readHead(input: InputStream): String? {
        val head = ByteArrayOutputStream()
        var last4 = 0
        while (head.size() < MAX_HEADER) {
            val b = input.read()
            if (b < 0) return ""
            head.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0d0a0d0a) return String(head.toByteArray(), Charsets.ISO_8859_1).trim()
        }
        return null
    }

    private fun readBody(input: InputStream, length: Int): ByteArray? {
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) return null
            read += n
        }
        return body
    }

    fun reason(status: Int): String = when (status) {
        200 -> "OK"
        202 -> "Accepted"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        413 -> "Payload Too Large"
        431 -> "Request Header Fields Too Large"
        503 -> "Service Unavailable"
        else -> "Error"
    }

    fun write(out: OutputStream, r: Response) {
        val body = r.body.toByteArray(Charsets.UTF_8)
        val head = StringBuilder()
            .append("HTTP/1.1 ").append(r.status).append(' ').append(reason(r.status)).append("\r\n")
            .append(if (body.isNotEmpty()) "Content-Type: application/json\r\n" else "")
            .append(if (r.status == 401) "WWW-Authenticate: Bearer\r\n" else "")
            .append(if (r.status == 405) "Allow: POST\r\n" else "")
            .append("Content-Length: ").append(body.size).append("\r\n")
            .append("Cache-Control: no-store\r\nConnection: close\r\n\r\n")
            .toString()
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }
}
