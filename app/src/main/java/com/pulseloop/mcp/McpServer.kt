package com.pulseloop.mcp

import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore

/**
 * A small, bounded HTTP listener for [McpHandler]: at most [MAX_CONNECTIONS] at once, a
 * [TIMEOUT_MS] socket timeout, one request per connection, and only on private addresses — each
 * connection's peer is checked again before anything is read. Plain Kotlin (java.net only),
 * JVM-tested over loopback (`McpServerSocketTest`). Ported from GMind's `McpServer`.
 */
class McpServer(private val handler: McpHandler) {
    private val sockets = ArrayList<ServerSocket>()
    private val pool: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "pulseloop-mcp").apply { isDaemon = true }
    }
    private val slots = Semaphore(MAX_CONNECTIONS)
    @Volatile private var running = false

    /** Listens on these addresses (each must be private); returns the ones it got. */
    @Synchronized
    fun start(addresses: List<InetAddress>, port: Int): List<InetSocketAddress> {
        running = true
        val bound = ArrayList<InetSocketAddress>()
        for (a in addresses) bind(a, port)?.let { bound.add(it) }
        return bound
    }

    /**
     * Follows the phone's addresses while running (Wi-Fi joined or left, a DHCP renewal, Tailscale
     * up or down): stops listening on an address the phone no longer has, starts on a new one, and
     * leaves the rest alone so a tool connected through them isn't dropped. The same rule as
     * [start]: only private addresses. Returns everything it listens on afterwards — empty while
     * the phone is on no network of its own, until one comes back. Does nothing once stopped.
     */
    @Synchronized
    fun rebind(addresses: List<InetAddress>, port: Int): List<InetSocketAddress> {
        if (!running) return emptyList()
        val wanted = addresses.filter { McpNet.allowedPeer(it) }.distinct()
        val iter = sockets.iterator()
        while (iter.hasNext()) {
            val s = iter.next()
            if (s.inetAddress !in wanted) {
                try { s.close() } catch (_: IOException) {} // its accept thread ends on the close
                iter.remove()
            }
        }
        val listening = sockets.map { it.inetAddress }
        for (a in wanted) if (a !in listening) bind(a, port)
        return sockets.map { it.localSocketAddress as InetSocketAddress }
    }

    /** One listening socket on [a], with its accept thread; null when [a] is public or taken. */
    private fun bind(a: InetAddress, port: Int): InetSocketAddress? {
        if (!McpNet.allowedPeer(a)) return null // never on a public address
        val s = ServerSocket()
        try {
            s.reuseAddress = true
            s.bind(InetSocketAddress(a, port), 8)
        } catch (e: IOException) {
            try { s.close() } catch (_: IOException) {}
            return null
        }
        sockets.add(s)
        Thread({ accept(s) }, "pulseloop-mcp-accept").apply { isDaemon = true }.start()
        return s.localSocketAddress as InetSocketAddress
    }

    @Synchronized
    fun stop() {
        running = false
        for (s in sockets) try { s.close() } catch (_: IOException) {}
        sockets.clear()
        pool.shutdownNow()
    }

    fun isRunning(): Boolean = running && sockets.isNotEmpty()

    private fun accept(server: ServerSocket) {
        while (running && !server.isClosed) {
            val socket = try { server.accept() } catch (_: IOException) { return }
            if (!McpNet.allowedPeer(socket.inetAddress) || !slots.tryAcquire()) {
                close(socket)
                continue
            }
            try {
                pool.execute {
                    try { serve(socket) } finally { slots.release(); close(socket) }
                }
            } catch (_: RejectedExecutionException) { // stopping
                slots.release()
                close(socket)
                return
            }
        }
    }

    /** One request on one connection, then close. */
    fun serve(socket: Socket) {
        try {
            socket.soTimeout = TIMEOUT_MS
            val input = BufferedInputStream(socket.getInputStream())
            val reply = when (val parsed = McpHttp.read(input)) {
                is McpHttp.Parsed.Refused -> McpHttp.Response(parsed.status)
                is McpHttp.Parsed.Ok -> handler.respond(parsed.request)
            }
            McpHttp.write(socket.getOutputStream(), reply)
        } catch (_: IOException) { /* the client left or timed out */ }
    }

    private fun close(s: Socket) { try { s.close() } catch (_: IOException) {} }

    companion object {
        const val MAX_CONNECTIONS = 4
        const val TIMEOUT_MS = 15_000
    }
}
