package sh.haven.core.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenVpnConfigBlobTest {

    @Test
    fun `encode-decode round-trips profile text only`() {
        // No trailing newline: parse() trims ovpnConfigText (same idiom as
        // NetbirdConfigBlob.parse trimming setupKey) to detect a blank
        // profile, which also discards trailing whitespace from the stored
        // value -- harmless for a line-based .ovpn profile, but means a
        // round-trip test can't assert an exact trailing newline survives.
        val blob = OpenVpnConfigBlob(ovpnConfigText = "client\ndev tun")
        val parsed = OpenVpnConfigBlob.parse(blob.encode())
        assertEquals("client\ndev tun", parsed!!.ovpnConfigText)
        assertEquals("", parsed.username)
        assertEquals("", parsed.password)
    }

    @Test
    fun `encode-decode round-trips profile text plus credentials`() {
        val blob = OpenVpnConfigBlob(
            ovpnConfigText = "client\ndev tun",
            username = "alice",
            password = "hunter2",
        )
        val parsed = OpenVpnConfigBlob.parse(blob.encode())
        assertEquals("client\ndev tun", parsed!!.ovpnConfigText)
        assertEquals("alice", parsed.username)
        assertEquals("hunter2", parsed.password)
    }

    @Test
    fun `blank username and password are omitted from encoded JSON`() {
        val text = String(OpenVpnConfigBlob(ovpnConfigText = "client").encode(), Charsets.UTF_8)
        assert(!text.contains("username") && !text.contains("password")) {
            "username/password should not appear when blank, got: $text"
        }
    }

    @Test
    fun `unknown keys are ignored`() {
        val json = """{"ovpnConfigText":"client","futureField":42,"username":"bob"}"""
        val parsed = OpenVpnConfigBlob.parse(json.toByteArray())
        assertEquals("client", parsed!!.ovpnConfigText)
        assertEquals("bob", parsed.username)
    }

    @Test
    fun `non-JSON input returns null`() {
        assertNull(OpenVpnConfigBlob.parse("not json".toByteArray()))
    }

    @Test
    fun `empty and whitespace configs return null`() {
        assertNull(OpenVpnConfigBlob.parse(ByteArray(0)))
        assertNull(OpenVpnConfigBlob.parse("   ".toByteArray()))
    }

    @Test
    fun `JSON without a non-blank ovpnConfigText returns null`() {
        assertNull(OpenVpnConfigBlob.parse("""{"username":"bob"}""".toByteArray()))
        assertNull(OpenVpnConfigBlob.parse("""{"ovpnConfigText":"  "}""".toByteArray()))
    }
}
