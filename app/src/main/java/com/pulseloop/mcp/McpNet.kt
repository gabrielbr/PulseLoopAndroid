package com.pulseloop.mcp

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale

/**
 * Which addresses the MCP server answers and listens on. Plain Kotlin (java.net only), JVM-tested.
 */
object McpNet {
    const val PORT = 8765

    /**
     * A peer we answer: loopback, your LAN (10/8, 172.16/12, 192.168/16), link-local (169.254/16,
     * fe80::/10), Tailscale and other CGNAT (100.64/10), or IPv6 unique-local (fc00::/7).
     * Everything else, including every public address, is refused before a byte is read.
     */
    fun allowedPeer(a: InetAddress?): Boolean {
        if (a == null) return false
        if (a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress) return true
        val b = a.address
        return when (a) {
            is Inet4Address -> (b[0].toInt() and 0xff) == 100 && (b[1].toInt() and 0xc0) == 64
            is Inet6Address -> {
                // An IPv4-mapped IPv6 peer (::ffff:a.b.c.d) is judged as its IPv4 address.
                if (isV4Mapped(b)) allowedPeer(InetAddress.getByAddress(b.copyOfRange(12, 16)))
                else (b[0].toInt() and 0xfe) == 0xfc
            }
            else -> false
        }
    }

    private fun isV4Mapped(b: ByteArray): Boolean =
        b.size == 16 && (0 until 10).all { b[it].toInt() == 0 } && b[10].toInt() == -1 && b[11].toInt() == -1

    /**
     * This phone's addresses on your own networks: Wi-Fi, Ethernet, hotspot, and Tailscale's tunnel.
     * Never mobile data (`rmnet`, `ccmni`), whatever address it carries.
     */
    fun localAddresses(): List<InetAddress> {
        val out = ArrayList<InetAddress>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return out
            for (n in Collections.list(interfaces)) {
                val name = n.name.lowercase(Locale.ROOT)
                val lan = name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("ap") || name.startsWith("swlan")
                val tunnel = name.startsWith("tun") // Tailscale
                if (!n.isUp || n.isLoopback || !(lan || tunnel)) continue
                for (a in Collections.list(n.inetAddresses)) {
                    if (a.isLoopbackAddress || !allowedPeer(a)) continue
                    // Link-local IPv6 needs a scope id that client configs can't carry: skip it.
                    if (a is Inet6Address && a.isLinkLocalAddress) continue
                    out.add(a)
                }
            }
        } catch (_: Exception) { /* no network */ }
        // IPv4 first: it is what people type and what mcp-remote handles best.
        return out.sortedBy { if (it is Inet4Address) 0 else 1 }
    }

    /** `http://192.168.1.20:8765/mcp` (IPv6 in brackets, scope id dropped). */
    fun url(a: InetSocketAddress): String = url(a.address, a.port)

    fun url(a: InetAddress, port: Int = PORT): String {
        var host = a.hostAddress ?: ""
        val zone = host.indexOf('%')
        if (zone > 0) host = host.substring(0, zone)
        return "http://" + (if (host.contains(":")) "[$host]" else host) + ":" + port + "/mcp"
    }

    /** Tailscale hands out 100.64/10 on its tunnel; the settings page labels that address. */
    fun isTailscale(a: InetAddress): Boolean = a is Inet4Address && allowedPeer(a) &&
        (a.address[0].toInt() and 0xff) == 100
}
