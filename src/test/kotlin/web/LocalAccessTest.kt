package org.example.web

import kotlin.test.*

class LocalAccessTest {
    @Test
    fun `default access remains loopback only`() {
        val access = LocalAccess()

        assertTrue(access.allows("localhost:8080", null, null, null, null))
        assertTrue(access.allows("127.0.0.1:8080", "http://127.0.0.1:8080", null, null, null))
        assertFalse(access.allows("workbench.example.ts.net", null, null, null, null))
        assertFalse(access.allows("localhost:8080", "https://evil.example", null, null, null))
    }

    @Test
    fun `tailscale access requires exact https proxy and authenticated identity`() {
        val host = "macbook.example-tailnet.ts.net"
        val access = LocalAccess(tailscaleHost = "$host.")

        assertEquals(host, access.tailscaleHost)
        assertTrue(access.allows(host, null, host, "https", "owner@example.com"))
        assertTrue(access.allows("$host:443", "https://$host", "$host:443", "https", "owner@example.com"))
        assertFalse(access.allows(host, null, host, "https", null))
        assertFalse(access.allows(host, null, host, "http", "owner@example.com"))
        assertFalse(access.allows(host, null, "other.example-tailnet.ts.net", "https", "owner@example.com"))
        assertFalse(access.allows(host, "https://evil.example", host, "https", "owner@example.com"))
    }

    @Test
    fun `tailscale hostname validation rejects URLs ports and non tailscale names`() {
        assertFailsWith<IllegalArgumentException> { normalizeTailscaleHost("https://macbook.example.ts.net") }
        assertFailsWith<IllegalArgumentException> { normalizeTailscaleHost("macbook.example.ts.net:443") }
        assertFailsWith<IllegalArgumentException> { normalizeTailscaleHost("macbook.example.com") }
        assertFailsWith<IllegalArgumentException> { normalizeTailscaleHost("example.ts.net") }
    }
}
