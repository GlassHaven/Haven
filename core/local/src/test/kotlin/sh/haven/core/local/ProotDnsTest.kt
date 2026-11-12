package sh.haven.core.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guest resolver selection (#446). Pure decision logic — the Android lookup is
 * injected — so the part that decides what lands in `/etc/resolv.conf` is verifiable
 * without a device.
 */
class ProotDnsTest {

    private val systemDns = listOf("192.168.1.1", "fe80::1")

    @Test
    fun `system mode uses the network's own resolvers`() {
        assertEquals(
            systemDns,
            ProotDns.nameservers(ProotDnsMode.SYSTEM, customServers = null) { systemDns },
        )
    }

    @Test
    fun `public mode uses the historical hardcoded pair`() {
        assertEquals(
            listOf("8.8.8.8", "1.1.1.1"),
            ProotDns.nameservers(ProotDnsMode.PUBLIC, customServers = null) { systemDns },
        )
    }

    @Test
    fun `custom mode uses the user's servers`() {
        assertEquals(
            listOf("10.0.0.53", "10.0.0.54"),
            ProotDns.nameservers(ProotDnsMode.CUSTOM, "10.0.0.53, 10.0.0.54") { systemDns },
        )
    }

    // --- Fallbacks: an empty resolv.conf resolves nothing, which is worse than
    // --- the failure this setting exists to fix. Every branch must yield servers.

    @Test
    fun `system mode falls back to public when the device reports no resolvers`() {
        assertEquals(
            ProotDns.PUBLIC_SERVERS,
            ProotDns.nameservers(ProotDnsMode.SYSTEM, customServers = null) { emptyList() },
        )
    }

    @Test
    fun `custom mode falls back to public when the entry is empty or unusable`() {
        for (entry in listOf(null, "", "   ", "not-an-ip", "example.com")) {
            assertEquals(
                "custom=<$entry> should fall back",
                ProotDns.PUBLIC_SERVERS,
                ProotDns.nameservers(ProotDnsMode.CUSTOM, entry) { emptyList() },
            )
        }
    }

    @Test
    fun `parseServers splits on commas and whitespace, dedupes, and drops non-IPs`() {
        assertEquals(
            listOf("1.1.1.1", "9.9.9.9", "2606:4700:4700::1111"),
            ProotDns.parseServers("1.1.1.1, 9.9.9.9  1.1.1.1\nexample.com;2606:4700:4700::1111"),
        )
    }

    @Test
    fun `hostnames are rejected because resolv conf cannot resolve them`() {
        // A hostname here would produce a config that silently never resolves —
        // the exact class of failure #446 reported.
        assertFalse(ProotDns.isIpLiteral("dns.google"))
        assertFalse(ProotDns.isIpLiteral(""))
        assertFalse(ProotDns.isIpLiteral("999.1.1.1"))
        assertFalse(ProotDns.isIpLiteral("1.1.1"))
        assertFalse(ProotDns.isIpLiteral("1.1.1.1.1"))
        assertTrue(ProotDns.isIpLiteral("8.8.8.8"))
        assertTrue(ProotDns.isIpLiteral("255.255.255.255"))
        assertTrue(ProotDns.isIpLiteral("fe80::1"))
        assertTrue(ProotDns.isIpLiteral("2606:4700:4700::1111"))
    }

    @Test
    fun `resolv conf body is one nameserver line per server`() {
        assertEquals(
            "nameserver 8.8.8.8\nnameserver 1.1.1.1\n",
            ProotDns.resolvConfText(listOf("8.8.8.8", "1.1.1.1")),
        )
    }

    @Test
    fun `mode ids round-trip and unknown values fall back to the default`() {
        for (mode in ProotDnsMode.entries) {
            assertEquals(mode, ProotDnsMode.fromId(mode.id))
        }
        assertEquals(ProotDnsMode.SYSTEM, ProotDnsMode.DEFAULT)
        assertEquals(ProotDnsMode.DEFAULT, ProotDnsMode.fromId(null))
        assertEquals(ProotDnsMode.DEFAULT, ProotDnsMode.fromId("nonsense"))
        // A stored value must survive a case change rather than silently resetting.
        assertEquals(ProotDnsMode.CUSTOM, ProotDnsMode.fromId("CUSTOM"))
    }

    @Test
    fun `custom ip-port endpoints write bare ip to resolv conf and emit port redirect specs`() {
        val raw = "127.0.0.1:5400, [::1]:5453, 10.0.0.53"
        assertEquals(
            listOf("127.0.0.1", "::1", "10.0.0.53"),
            ProotDns.nameservers(ProotDnsMode.CUSTOM, raw) { emptyList() },
        )
        assertEquals(
            listOf("127.0.0.1:5400", "[::1]:5453"),
            ProotDns.customPortRedirectSpecs(ProotDnsMode.CUSTOM, raw),
        )
        assertEquals(
            emptyList<String>(),
            ProotDns.customPortRedirectSpecs(ProotDnsMode.SYSTEM, raw),
        )
        assertEquals(
            emptyList<String>(),
            ProotDns.customPortRedirectSpecs(ProotDnsMode.CUSTOM, "127.0.0.1:53"),
        )
    }

    @Test
    fun `invalidTokens flags hostnames and out-of-range ports while accepting ip-port`() {
        assertEquals(emptyList<String>(), ProotDns.invalidTokens(null))
        assertEquals(emptyList<String>(), ProotDns.invalidTokens("   "))
        assertEquals(emptyList<String>(), ProotDns.invalidTokens("127.0.0.1:5400, [2606:4700::1111]:5353"))
        assertEquals(
            listOf("dns.google", "127.0.0.1:0", "127.0.0.1:70000", "[::1]:abc"),
            ProotDns.invalidTokens("127.0.0.1:5400, dns.google, 127.0.0.1:0; 127.0.0.1:70000 [::1]:abc"),
        )
    }
}
