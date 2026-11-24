package sh.haven.core.security

import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Signs and verifies controlled-egress consent decisions for the conductor's
 * root egress proxy (Phase 2 of the internet-egress gate).
 *
 * ### Why a signature at all
 *
 * The host-side Haven MCP endpoint (loopback 8788, SSH `-R` failover 8730) is
 * owned by uid 1000 — the same uid as the sandboxed agent. Even though the
 * jail netns already blocks the agent from binding host loopback ports, a
 * signature is defense-in-depth: the proxy verifies every decision against a
 * root-owned shared secret (`/usr/local/lib/claude-egress/haven.key`, 0600)
 * that the uid-1000 agent cannot read, so a forged or impersonated "allow"
 * is rejected. Fail-closed: an unsigned or bad-signature decision is a deny.
 *
 * ### Scheme
 *
 * HMAC-SHA256 over a canonical, LF-delimited UTF-8 message. Chosen over
 * Ed25519 because the proxy is stdlib-only Python (`hmac` + `compare_digest`),
 * so both sides reproduce the digest byte-exact with no external subprocess.
 * The secret is 32 random bytes, Keystore-AES-GCM-wrapped at rest on the phone
 * ([EgressSigningKeyStore]) and root-owned on the host.
 *
 * This object is pure (secret bytes in, signature out) so the canonicalisation
 * and sign/verify logic unit-test on the JVM without Android.
 */
object EgressGrantSigner {

    const val ALGORITHM = "HMAC-SHA256"
    private const val MAC_ALGORITHM = "HmacSHA256"

    /** Decision tokens — must match the proxy verifier exactly. */
    const val DECISION_ONCE = "once"
    const val DECISION_SESSION = "session"
    const val DECISION_DENY = "deny"

    /**
     * The exact bytes both sides sign. Field order and delimiters are part of
     * the contract (see /tmp/haven-egress-contract.txt): UTF-8, LF-delimited,
     * NO trailing newline, empty string for a null [expiryMs]. [host] is used
     * verbatim — no lowercasing or normalisation — so the proxy must echo it
     * back exactly as it sent it.
     */
    fun canonicalMessage(
        requestId: String,
        sandboxId: String,
        host: String,
        port: Int,
        decision: String,
        expiryMs: Long?,
        grantId: String,
        nonce: String,
    ): ByteArray {
        val expiry = expiryMs?.toString() ?: ""
        val message = buildString {
            append(requestId).append('\n')
            append(sandboxId).append('\n')
            append(host).append('\n')
            append(port).append('\n')
            append(decision).append('\n')
            append(expiry).append('\n')
            append(grantId).append('\n')
            append(nonce)
        }
        return message.toByteArray(Charsets.UTF_8)
    }

    /** Raw HMAC-SHA256 digest of [message] under [secret]. */
    fun sign(secret: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance(MAC_ALGORITHM)
        mac.init(SecretKeySpec(secret, MAC_ALGORITHM))
        return mac.doFinal(message)
    }

    /**
     * Constant-time verification of a raw [signature] over [message]. Returns
     * false (never throws) on any mismatch so the caller fails closed.
     */
    fun verify(secret: ByteArray, message: ByteArray, signature: ByteArray): Boolean = try {
        val expected = sign(secret, message)
        java.security.MessageDigest.isEqual(expected, signature)
    } catch (_: Exception) {
        false
    }

    /** Standard base64 (padded, not URL-safe) — the wire encoding of the signature. */
    fun encodeSignature(signature: ByteArray): String =
        Base64.getEncoder().encodeToString(signature)

    /** Decode a wire signature; null when malformed (caller treats as deny). */
    fun decodeSignature(encoded: String): ByteArray? = try {
        Base64.getDecoder().decode(encoded)
    } catch (_: IllegalArgumentException) {
        null
    }

    /**
     * Convenience: sign the canonical message for a decision and return the
     * base64 wire signature. The tool handler uses this; the proxy reproduces
     * [canonicalMessage] + [sign] to verify.
     */
    fun signDecision(
        secret: ByteArray,
        requestId: String,
        sandboxId: String,
        host: String,
        port: Int,
        decision: String,
        expiryMs: Long?,
        grantId: String,
        nonce: String,
    ): String = encodeSignature(
        sign(
            secret,
            canonicalMessage(requestId, sandboxId, host, port, decision, expiryMs, grantId, nonce),
        ),
    )
}
