package com.twig.fs.nfs

import org.junit.Assert.*
import org.junit.Test

class NfsEndpointTest {
    @Test fun parsesHostsPortsAndIpv6WithoutResolvingNames() {
        assertEquals(NfsEndpoint("nas", 2049), NfsEndpoint.parse("nas"))
        assertEquals(NfsEndpoint("192.168.10.121", 3050), NfsEndpoint.parse(" 192.168.10.121:3050 "))
        assertEquals(NfsEndpoint("nas", 3050), NfsEndpoint.parse("nas", 3050))
        assertEquals(NfsEndpoint("nas", 3050), NfsEndpoint.parse("nfs://nas:3050", 2049))
        assertEquals(NfsEndpoint("2001:db8::1", 3050), NfsEndpoint.parse("[2001:db8::1]:3050"))
        assertEquals(NfsEndpoint("2001:db8::2049", 2049), NfsEndpoint.parse("2001:db8::2049"))
        assertEquals(NfsEndpoint("::1", 2049), NfsEndpoint.parse("[::1]"))
        assertEquals("[::1]:3050", NfsEndpoint("::1", 3050).authority())
        assertEquals("nas", NfsEndpoint("nas").authority())
    }

    @Test fun rejectsInvalidPortsAndAddresses() {
        listOf("", "nas:0", "nas:65536", "nas:-1", "nas:port", "nas:", "nas:999999999999",
            "[::1]:", "[::1]garbage", "[nas]:2049", "[invalid::ip]:2049", "user@nas",
            "nas/export", "nas?port=2049", "nas name", "nas\u0000").forEach { raw ->
            try { NfsEndpoint.parse(raw); fail("Accepted $raw") } catch (_: IllegalArgumentException) { }
        }
    }
}
