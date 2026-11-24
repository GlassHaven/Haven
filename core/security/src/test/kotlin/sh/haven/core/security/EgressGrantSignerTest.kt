package sh.haven.core.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Byte-exact contract tests for the egress decision signature. The known-answer
 * vectors were computed independently with `openssl dgst -sha256 -hmac`, so a
 * pass proves the canonicalisation + HMAC agree with the conductor's stdlib
 * Python verifier (hmac + compare_digest) — not just with themselves.
 */
class EgressGrantSignerTest {

    private val secret = "test-secret".toByteArray(Charsets.UTF_8)

    @Test
    fun `canonical message is LF-delimited with empty null expiry and no trailing newline`() {
        val bytes = EgressGrantSigner.canonicalMessage(
            requestId = "req1", sandboxId = "sb1", host = "example.com", port = 443,
            decision = "session", expiryMs = null, grantId = "grant1", nonce = "nonce1",
        )
        assertEquals("req1\nsb1\nexample.com\n443\nsession\n\ngrant1\nnonce1", String(bytes, Charsets.UTF_8))
    }

    @Test
    fun `known-answer session vector with null expiry matches openssl`() {
        val sig = EgressGrantSigner.signDecision(
            secret, "req1", "sb1", "example.com", 443, "session", null, "grant1", "nonce1",
        )
        assertEquals("qVU6PE6THx4tTPWhMuvPI0jUCBaJvMozNnJVuwUTUgQ=", sig)
    }

    @Test
    fun `known-answer once vector with expiry matches openssl`() {
        val sig = EgressGrantSigner.signDecision(
            secret, "req1", "sb1", "example.com", 443, "once", 1_700_000_000_000L, "grant1", "nonce1",
        )
        assertEquals("B4VicH04L84C8VY85jaiTtuYzXiSsue31pHQ3rzFJZI=", sig)
    }

    @Test
    fun `sign then verify round-trips`() {
        val msg = EgressGrantSigner.canonicalMessage(
            "r", "s", "host.example", 8443, "deny", null, "g", "n",
        )
        val sig = EgressGrantSigner.sign(secret, msg)
        assertTrue(EgressGrantSigner.verify(secret, msg, sig))
    }

    @Test
    fun `tampered decision fails verification`() {
        val signed = EgressGrantSigner.canonicalMessage("r", "s", "h", 443, "deny", null, "g", "n")
        val sig = EgressGrantSigner.sign(secret, signed)
        val forged = EgressGrantSigner.canonicalMessage("r", "s", "h", 443, "session", null, "g", "n")
        assertFalse(EgressGrantSigner.verify(secret, forged, sig))
    }

    @Test
    fun `wrong host fails verification`() {
        val signed = EgressGrantSigner.canonicalMessage("r", "s", "good.com", 443, "once", 1L, "g", "n")
        val sig = EgressGrantSigner.sign(secret, signed)
        val forged = EgressGrantSigner.canonicalMessage("r", "s", "evil.com", 443, "once", 1L, "g", "n")
        assertFalse(EgressGrantSigner.verify(secret, forged, sig))
    }

    @Test
    fun `wrong request id fails verification`() {
        val signed = EgressGrantSigner.canonicalMessage("r1", "s", "h", 443, "once", 1L, "g", "n")
        val sig = EgressGrantSigner.sign(secret, signed)
        val forged = EgressGrantSigner.canonicalMessage("r2", "s", "h", 443, "once", 1L, "g", "n")
        assertFalse(EgressGrantSigner.verify(secret, forged, sig))
    }

    @Test
    fun `wrong secret fails verification`() {
        val msg = EgressGrantSigner.canonicalMessage("r", "s", "h", 443, "once", 1L, "g", "n")
        val sig = EgressGrantSigner.sign(secret, msg)
        assertFalse(EgressGrantSigner.verify("other-secret".toByteArray(), msg, sig))
    }

    @Test
    fun `signature base64 round-trips and malformed decodes to null`() {
        val sig = EgressGrantSigner.sign(secret, "x".toByteArray())
        val encoded = EgressGrantSigner.encodeSignature(sig)
        assertArrayEquals(sig, EgressGrantSigner.decodeSignature(encoded))
        assertNull(EgressGrantSigner.decodeSignature("!!!not-base64!!!"))
    }

    @Test
    fun `host is used verbatim without normalisation`() {
        val upper = EgressGrantSigner.canonicalMessage("r", "s", "Example.COM", 443, "once", 1L, "g", "n")
        val lower = EgressGrantSigner.canonicalMessage("r", "s", "example.com", 443, "once", 1L, "g", "n")
        assertFalse(upper.contentEquals(lower))
    }
}
