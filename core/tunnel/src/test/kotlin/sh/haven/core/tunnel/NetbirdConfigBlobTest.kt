package sh.haven.core.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetbirdConfigBlobTest {

    @Test
    fun `encode-decode round-trips setup key only`() {
        val blob = NetbirdConfigBlob(setupKey = "NBSETUPKEY-123")
        val parsed = NetbirdConfigBlob.parse(blob.encode())
        assertEquals("NBSETUPKEY-123", parsed!!.setupKey)
        assertEquals("", parsed.managementURL)
    }

    @Test
    fun `encode-decode round-trips setup key plus management url`() {
        val blob = NetbirdConfigBlob(
            setupKey = "NBSETUPKEY-123",
            managementURL = "https://netbird.example.com",
        )
        val parsed = NetbirdConfigBlob.parse(blob.encode())
        assertEquals("NBSETUPKEY-123", parsed!!.setupKey)
        assertEquals("https://netbird.example.com", parsed.managementURL)
    }

    @Test
    fun `blank managementURL is omitted from encoded JSON`() {
        val text = String(NetbirdConfigBlob(setupKey = "k").encode(), Charsets.UTF_8)
        assert(!text.contains("managementUrl")) {
            "managementUrl should not appear when blank, got: $text"
        }
    }

    @Test
    fun `encode-decode round-trips rosenpass flag`() {
        val on = NetbirdConfigBlob(setupKey = "k", rosenpass = true)
        assertEquals(true, NetbirdConfigBlob.parse(on.encode())!!.rosenpass)
        val off = NetbirdConfigBlob(setupKey = "k", rosenpass = false)
        assertEquals(false, NetbirdConfigBlob.parse(off.encode())!!.rosenpass)
    }

    @Test
    fun `rosenpass defaults to false`() {
        assertEquals(false, NetbirdConfigBlob(setupKey = "k").rosenpass)
    }

    @Test
    fun `old blob without rosenpass key parses as false`() {
        val json = """{"setupKey":"k","managementUrl":"https://x.example.com"}"""
        val parsed = NetbirdConfigBlob.parse(json.toByteArray())
        assertEquals(false, parsed!!.rosenpass)
    }

    @Test
    fun `unknown keys are ignored`() {
        val json = """{"setupKey":"k","futureField":42,"managementUrl":"https://x.example.com"}"""
        val parsed = NetbirdConfigBlob.parse(json.toByteArray())
        assertEquals("k", parsed!!.setupKey)
        assertEquals("https://x.example.com", parsed.managementURL)
    }

    @Test
    fun `non-JSON input returns null`() {
        // Unlike Tailscale there is no legacy fallback — the envelope is
        // the only format this type has ever had, so raw bytes must be
        // rejected rather than treated as a setup key.
        assertNull(NetbirdConfigBlob.parse("not json".toByteArray()))
    }

    @Test
    fun `empty and whitespace configs return null`() {
        assertNull(NetbirdConfigBlob.parse(ByteArray(0)))
        assertNull(NetbirdConfigBlob.parse("   ".toByteArray()))
    }

    @Test
    fun `JSON without a non-blank setupKey returns null`() {
        assertNull(NetbirdConfigBlob.parse("""{"managementUrl":"https://x"}""".toByteArray()))
        assertNull(NetbirdConfigBlob.parse("""{"setupKey":"  "}""".toByteArray()))
    }
}