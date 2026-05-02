package sh.haven.core.tunnel

import org.junit.Assert.assertEquals
import org.junit.Test

class TailscaleConfigBlobTest {

    @Test
    fun `encode-decode round-trips authkey only`() {
        val blob = TailscaleConfigBlob(authKey = "tskey-auth-abc123")
        val parsed = TailscaleConfigBlob.parse(blob.encode())
        assertEquals("tskey-auth-abc123", parsed.authKey)
        assertEquals("", parsed.controlURL)
    }

    @Test
    fun `encode-decode round-trips authkey plus headscale url`() {
        val blob = TailscaleConfigBlob(
            authKey = "tskey-auth-abc123",
            controlURL = "https://headscale.example.com",
        )
        val parsed = TailscaleConfigBlob.parse(blob.encode())
        assertEquals("tskey-auth-abc123", parsed.authKey)
        assertEquals("https://headscale.example.com", parsed.controlURL)
    }

    @Test
    fun `legacy raw-authkey blobs decode with empty controlURL`() {
        // Pre-v5.24.86: configText was the raw authkey bytes (no JSON envelope).
        // Existing users must not have to re-add their tunnels.
        val legacy = "tskey-auth-legacy-format-1234".toByteArray()
        val parsed = TailscaleConfigBlob.parse(legacy)
        assertEquals("tskey-auth-legacy-format-1234", parsed.authKey)
        assertEquals("", parsed.controlURL)
    }

    @Test
    fun `legacy bytes that happen to start with brace fall back when JSON has no authKey`() {
        // Defensive: an authkey that genuinely starts with "{" is unlikely
        // (Tailscale keys are tskey-…), but a malformed/partial JSON should
        // still come through as the raw payload.
        val almostJson = "{not actually json}".toByteArray()
        val parsed = TailscaleConfigBlob.parse(almostJson)
        assertEquals("{not actually json}", parsed.authKey)
        assertEquals("", parsed.controlURL)
    }

    @Test
    fun `blank controlURL is omitted from encoded JSON`() {
        val encoded = TailscaleConfigBlob(authKey = "tskey-auth-x").encode()
        val text = String(encoded, Charsets.UTF_8)
        // No controlUrl field at all when it's empty — keeps the blob small
        // and matches the v1 default behaviour.
        assert(!text.contains("controlUrl")) {
            "controlUrl should not appear when blank, got: $text"
        }
    }
}
