package com.twig.app.share

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Who may talk to the share at all (2026-09-17 review).
 *
 * The server binds the wildcard address, which includes the mobile-data interface and
 * global IPv6 — on carriers that hand out public IPv6 without inbound filtering, the share
 * was reachable from the internet. And it answered any `Host:`, so a web page the user
 * visited could DNS-rebind its own name onto the device and read the share same-origin.
 * Both checks live here as plain functions so they can be tested without sockets.
 */
object LanPolicy {

    /**
     * Whether a connection from [addr] is accepted: loopback, private ranges (RFC 1918,
     * link-local, IPv6 ULA) and the shared-address space 100.64.0.0/10 — carrier NAT and,
     * more to the point, Tailscale, which is a common way to reach one's own phone.
     * Anything else is the public internet.
     */
    fun allowedPeer(addr: InetAddress?): Boolean {
        if (addr == null) return false
        if (addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress) return true
        val b = addr.address
        return when (addr) {
            is Inet4Address -> b[0].toInt() and 0xFF == 100 && (b[1].toInt() and 0xC0) == 64
            // fc00::/7 unique-local; an IPv4-mapped form (::ffff:a.b.c.d) is judged by its IPv4 part
            is Inet6Address -> (b[0].toInt() and 0xFE) == 0xFC || mappedV4(b)?.let { allowedPeer(it) } == true
            else -> false
        }
    }

    private fun mappedV4(b: ByteArray): InetAddress? {
        if (b.size != 16) return null
        for (i in 0 until 10) if (b[i].toInt() != 0) return null
        if (b[10].toInt() and 0xFF != 0xFF || b[11].toInt() and 0xFF != 0xFF) return null
        return InetAddress.getByAddress(b.copyOfRange(12, 16))
    }

    /**
     * Whether a request's `Host:` header is one a LAN client would send.
     *
     * DNS rebinding needs a name the attacker controls, which is always a dotted public
     * name. So IP literals, single-label names (`localhost`, a NetBIOS/mDNS short name) and
     * the local-only suffixes pass; any other dotted name is refused. A missing header
     * (HTTP/1.0) passes — a browser always sends one.
     */
    fun allowedHost(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return true
        val h = raw.trim().lowercase()
        if (h.startsWith("[")) {
            // [v6]:port — a literal, as long as it looks like one
            // (the zone id after '%' is an interface name and may be anything)
            val inner = h.substringAfter('[').substringBefore(']').substringBefore('%')
            return ':' in inner && inner.all { it.isDigit() || it in 'a'..'f' || it == ':' || it == '.' }
        }
        val host = h.substringBefore(':').trimEnd('.')
        if (host.isEmpty()) return false
        if (IPV4.matches(host)) return true
        if ('.' !in host) return true
        return LOCAL_SUFFIXES.any { host.endsWith(it) }
    }

    private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

    /** Names that public DNS never resolves (mDNS, common router domains, RFC 8375, Tailscale MagicDNS). */
    private val LOCAL_SUFFIXES = listOf(
        ".local", ".lan", ".home", ".home.arpa", ".internal", ".localdomain", ".ts.net",
    )
}
