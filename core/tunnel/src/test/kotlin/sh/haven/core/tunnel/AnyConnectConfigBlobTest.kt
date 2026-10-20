package sh.haven.core.tunnel

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AnyConnectConfigBlobTest {

    @Test
    fun `encode-decode round-trips every field`() {
        val blob = AnyConnectConfigBlob(
            server = "vpn.example.com:8443/eng",
            username = "alice",
            password = "p\"w{d}",
            authGroup = "Engineering",
            otpSecret = "otpauth://totp/corp:alice?secret=JBSWY3DPEHPK3PXP",
            secondaryPassword = "push",
            serverCert = "pin-sha256:AAAA",
            userAgent = "AnyConnect Linux_64 4.10.07061",
        )
        assertEquals(blob, AnyConnectConfigBlob.parse(blob.encode()))
    }

    @Test
    fun `blank optional fields are omitted from the native JSON`() {
        val json = JSONObject(AnyConnectConfigBlob("vpn.example.com", "alice", "pw").toNativeConfig())
        // org.json on Android has keys(): Iterator<String>, not Java's keySet().
        assertEquals(setOf("server", "username", "password"), json.keys().asSequence().toSet())
    }

    @Test
    fun `native JSON uses the keys ocbridge Config expects`() {
        // Must stay in sync with the json tags in rclone-android/go/ocbridge/config.go.
        val json = JSONObject(
            AnyConnectConfigBlob("s", "u", "p", "g", "o", "x", "c", "a").toNativeConfig(),
        )
        assertEquals(
            setOf("server", "username", "password", "authGroup", "otpSecret",
                "secondaryPassword", "serverCert", "userAgent"),
            json.keys().asSequence().toSet(),
        )
    }

    @Test
    fun `empty password is allowed`() {
        val parsed = AnyConnectConfigBlob.parse("""{"server":"vpn","username":"u"}""".toByteArray())
        assertNotNull(parsed)
        assertEquals("", parsed!!.password)
    }

    @Test
    fun `missing server or username or non-JSON returns null`() {
        assertNull(AnyConnectConfigBlob.parse("""{"username":"u","password":"p"}""".toByteArray()))
        assertNull(AnyConnectConfigBlob.parse("""{"server":"vpn","username":"  "}""".toByteArray()))
        assertNull(AnyConnectConfigBlob.parse("garbage".toByteArray()))
        assertNull(AnyConnectConfigBlob.parse(ByteArray(0)))
    }

    @Test
    fun `normalizeServer keeps port and group path, drops https and trailing slash`() {
        assertEquals("vpn.example.com:8443/eng", AnyConnectConfigBlob.normalizeServer(" https://vpn.example.com:8443/eng/ "))
        assertEquals("vpn.example.com", AnyConnectConfigBlob.normalizeServer("vpn.example.com"))
        assertNull(AnyConnectConfigBlob.normalizeServer("http://vpn.example.com"))
        assertNull(AnyConnectConfigBlob.normalizeServer("   "))
    }

    @Test
    fun `stayConnected defaults to true, round-trips false, and stays out of the native JSON`() {
        assertEquals(true, AnyConnectConfigBlob.parse("""{"server":"s","username":"u"}""".toByteArray())!!.stayConnected)
        val off = AnyConnectConfigBlob("s", "u", "p", stayConnected = false)
        assertEquals(false, AnyConnectConfigBlob.parse(off.encode())!!.stayConnected)
        assertEquals(false, JSONObject(off.toNativeConfig()).has("stayConnected"))
    }

    @Test
    fun `password with whitespace is preserved verbatim`() {
        // Unlike the other fields, passwords are never trimmed.
        val parsed = AnyConnectConfigBlob.parse(AnyConnectConfigBlob("s", "u", " pw ").encode())
        assertEquals(" pw ", parsed!!.password)
    }
}
