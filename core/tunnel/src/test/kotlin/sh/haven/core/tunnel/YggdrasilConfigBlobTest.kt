package sh.haven.core.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YggdrasilConfigBlobTest {

    @Test
    fun `encode-decode round-trips private key peers and listen`() {
        val blob = YggdrasilConfigBlob(
            privateKey = "abcd1234",
            peers = listOf("tls://192.168.0.180:9001", "tls://peer.example.com:443"),
            listen = listOf("tls://0.0.0.0:0"),
        )
        val parsed = YggdrasilConfigBlob.parse(blob.encode())!!
        assertEquals("abcd1234", parsed.privateKey)
        assertEquals(listOf("tls://192.168.0.180:9001", "tls://peer.example.com:443"), parsed.peers)
        assertEquals(listOf("tls://0.0.0.0:0"), parsed.listen)
    }

    @Test
    fun `empty config object parses to a generated-identity blob`() {
        // Yggdrasil has no required credential: an empty PrivateKey makes the
        // core generate an identity, so "{}" is a valid (if address-unstable) config.
        val parsed = YggdrasilConfigBlob.parse("{}".toByteArray())!!
        assertEquals("", parsed.privateKey)
        assertTrue(parsed.peers.isEmpty())
        assertTrue(parsed.listen.isEmpty())
    }

    @Test
    fun `encode uses yggdrasil PascalCase keys`() {
        val text = String(
            YggdrasilConfigBlob(privateKey = "k", peers = listOf("tls://h:1")).encode(),
            Charsets.UTF_8,
        )
        assertTrue("expected PrivateKey key, got: $text", text.contains("\"PrivateKey\""))
        assertTrue("expected Peers key, got: $text", text.contains("\"Peers\""))
    }

    @Test
    fun `blank private key and group password are omitted`() {
        val text = String(YggdrasilConfigBlob().encode(), Charsets.UTF_8)
        assertTrue("PrivateKey should be omitted when blank: $text", !text.contains("PrivateKey"))
        assertTrue("GroupPassword should be omitted when blank: $text", !text.contains("GroupPassword"))
    }

    @Test
    fun `ifMTU round-trips and defaults to zero`() {
        assertEquals(0, YggdrasilConfigBlob().ifMTU)
        val blob = YggdrasilConfigBlob(ifMTU = 1280)
        assertEquals(1280, YggdrasilConfigBlob.parse(blob.encode())!!.ifMTU)
    }

    @Test
    fun `group password round-trips`() {
        val blob = YggdrasilConfigBlob(groupPassword = "secret-mesh")
        assertEquals("secret-mesh", YggdrasilConfigBlob.parse(blob.encode())!!.groupPassword)
    }

    @Test
    fun `unknown keys are ignored`() {
        val json = """{"PrivateKey":"k","futureField":42,"Peers":["tls://h:1"]}"""
        val parsed = YggdrasilConfigBlob.parse(json.toByteArray())!!
        assertEquals("k", parsed.privateKey)
        assertEquals(listOf("tls://h:1"), parsed.peers)
    }

    @Test
    fun `non-JSON input returns null`() {
        assertNull(YggdrasilConfigBlob.parse("not json".toByteArray()))
    }

    @Test
    fun `empty and whitespace configs return null`() {
        assertNull(YggdrasilConfigBlob.parse(ByteArray(0)))
        assertNull(YggdrasilConfigBlob.parse("   ".toByteArray()))
    }

    @Test
    fun `underlayTunnelId round-trips and is omitted when blank`() {
        val directJson = String(YggdrasilConfigBlob(peers = listOf("tls://h:1")).encode(), Charsets.UTF_8)
        assertTrue(!directJson.contains("havenUnderlayTunnelId"))

        val routed = YggdrasilConfigBlob(
            peers = listOf("tls://192.168.0.180:9001"),
            underlayTunnelId = "carrier-wg-1",
        )
        val parsed = YggdrasilConfigBlob.parse(routed.encode())!!
        assertEquals("carrier-wg-1", parsed.underlayTunnelId)
    }

    @Test
    fun `withCarrierSocks rewrites tcp and tls peers to socks and sockstls while preserving SNI and non-TCP peers`() {
        val blob = YggdrasilConfigBlob(
            peers = listOf(
                "tcp://192.168.0.180:9001",
                "tls://192.168.0.180:9002?key=deadbeef",
                "tls://peer.home.arpa:9003",
                "tls://peer.home.arpa:9004?key=cafe&sni=override.home.arpa",
                "tls://[fd00::1]:9005?priority=1",
                "quic://192.168.0.180:9006",
                "sockstls://127.0.0.1:9999/already.proxied:9007?sni=already.proxied",
            ),
            underlayTunnelId = "carrier-wg-1",
        )
        val rewritten = blob.withCarrierSocks("127.0.0.1", 1080)
        assertEquals(
            listOf(
                "socks://127.0.0.1:1080/192.168.0.180:9001",
                "sockstls://127.0.0.1:1080/192.168.0.180:9002?key=deadbeef",
                "sockstls://127.0.0.1:1080/peer.home.arpa:9003?sni=peer.home.arpa",
                "sockstls://127.0.0.1:1080/peer.home.arpa:9004?key=cafe&sni=override.home.arpa",
                "sockstls://127.0.0.1:1080/[fd00::1]:9005?priority=1",
                "quic://192.168.0.180:9006",
                "sockstls://127.0.0.1:9999/already.proxied:9007?sni=already.proxied",
            ),
            rewritten.peers,
        )
    }
}
