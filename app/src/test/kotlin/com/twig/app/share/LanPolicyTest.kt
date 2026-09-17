package com.twig.app.share

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/** The share's network boundary ([LanPolicy]); see its doc for why each rule exists. */
class LanPolicyTest {

    private fun ip(s: String) = InetAddress.getByName(s)

    @Test
    fun `LAN, loopback, Tailscale and ULA peers are accepted`() {
        for (a in listOf(
            "127.0.0.1", "::1", "192.168.1.20", "10.0.0.5", "172.20.1.1", "169.254.3.4",
            "100.64.0.17", "100.127.255.1", "fd7a:115c:a1e0::1", "fe80::1", "::ffff:192.168.1.2",
        )) {
            assertTrue(a, LanPolicy.allowedPeer(ip(a)))
        }
    }

    @Test
    fun `public addresses are refused`() {
        for (a in listOf("8.8.8.8", "100.128.0.1", "100.63.255.255", "2001:4860:4860::8888", "::ffff:8.8.8.8")) {
            assertFalse(a, LanPolicy.allowedPeer(ip(a)))
        }
        assertFalse(LanPolicy.allowedPeer(null))
    }

    @Test
    fun `IP literals and local names pass the Host check`() {
        for (h in listOf(
            null, "", "192.168.1.20:8080", "127.0.0.1", "[fe80::1%wlan0]:8080", "[::1]",
            "localhost:8080", "pixel", "pixel.local", "phone.lan:8080", "phone.home.arpa",
            "phone.tail1234.ts.net", "PIXEL.LOCAL.",
        )) {
            assertTrue("$h", LanPolicy.allowedHost(h))
        }
    }

    /** DNS rebinding needs a public, attacker-controlled name. */
    @Test
    fun `public names fail the Host check`() {
        for (h in listOf("evil.example.com", "evil.example.com:8080", "local.evil.com", "lan.attacker.net", "[evil]", ":8080")) {
            assertFalse(h, LanPolicy.allowedHost(h))
        }
    }
}
