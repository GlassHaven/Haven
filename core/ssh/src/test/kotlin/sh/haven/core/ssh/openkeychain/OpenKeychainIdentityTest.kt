package sh.haven.core.ssh.openkeychain

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Haven hands JSch for a key held in another app (#487).
 *
 * The one rule that matters here: the provider has already encoded a complete
 * SSH signature blob, so anything this class did to those bytes would corrupt
 * them. That is the opposite of the FIDO path, where the raw signature has to
 * be wrapped by hand — hence a test that pins it.
 */
class OpenKeychainIdentityTest {

    private val keyData = OpenKeychainKeyData(
        providerPackage = "org.sufficientlysecure.keychain",
        keyId = "1234567890123456",
        algorithm = "ssh-rsa",
        publicKeyBlob = byteArrayOf(7, 7, 7),
        description = "ian@example.com",
    )

    private val client = mockk<OpenKeychainClient>()
    private val identity = OpenKeychainIdentity(keyData, client)

    @Test
    fun `the provider's signature is passed through untouched`() {
        val fromProvider = byteArrayOf(0, 0, 0, 7, 115, 115, 104, 45, 114, 115, 97, 9, 9)
        every { client.sign(any(), any(), any()) } returns fromProvider

        val signature = identity.getSignature(byteArrayOf(1, 2, 3), "rsa-sha2-512")

        assertArrayEquals(fromProvider, signature)
    }

    @Test
    fun `the algorithm the server negotiated reaches the provider`() {
        // Not cosmetic: for RSA this is what decides whether the provider
        // signs as rsa-sha2-512 or the legacy ssh-rsa a modern server refuses.
        val alg = slot<String>()
        every { client.sign(any(), any(), capture(alg)) } returns byteArrayOf(1)

        identity.getSignature(byteArrayOf(1), "rsa-sha2-256")

        assertEquals("rsa-sha2-256", alg.captured)
    }

    @Test
    fun `the challenge and key id are forwarded as given`() {
        val challenge = byteArrayOf(4, 5, 6, 7)
        every { client.sign(any(), any(), any()) } returns byteArrayOf(1)

        identity.getSignature(challenge, "ssh-rsa")

        verify { client.sign("1234567890123456", challenge, "ssh-rsa") }
    }

    @Test
    fun `the single-argument signature call uses the key's own algorithm`() {
        val alg = slot<String>()
        every { client.sign(any(), any(), capture(alg)) } returns byteArrayOf(1)

        identity.getSignature(byteArrayOf(1))

        assertEquals("ssh-rsa", alg.captured)
    }

    @Test
    fun `the identity offers the stored public key`() {
        assertArrayEquals(byteArrayOf(7, 7, 7), identity.publicKeyBlob)
        assertEquals("ssh-rsa", identity.algName)
    }

    @Test
    fun `the key is not reported as encrypted`() {
        // The provider deals with its own passphrase or PIN. Saying true here
        // would make JSch prompt for a passphrase Haven cannot use.
        assertFalse(identity.isEncrypted)
        assertEquals(true, identity.setPassphrase(null))
        assertEquals(true, identity.decrypt())
    }

    @Test
    fun `identities are named per key so two provider keys do not collide`() {
        val other = OpenKeychainIdentity(keyData.copy(keyId = "9999999999999999"), client)
        assertEquals("haven-openkeychain-1234567890123456", identity.name)
        assertEquals("haven-openkeychain-9999999999999999", other.name)
    }
    /**
     * #487: a reporter's reconnect logged "signing 148 bytes" and then nothing
     * whatsoever, followed by JSch's generic "Auth fail". JSch catches what an
     * Identity throws and moves silently to the next auth method, so the
     * provider's own reason never reached the log — and too many prompt rounds,
     * a cancelled prompt and a provider error are three different bugs that
     * look identical from outside.
     *
     * The failure still propagates; it just stops being invisible on the way.
     */
    @Test
    fun `a provider that refuses to sign says so in the log`() {
        // Capture the warning through the injected logging seam rather than
        // mockkStatic(android.util.Log): a global static mock shared with the
        // SshClient* tests flakes under parallel execution.
        var captured: String? = null
        val failingIdentity = OpenKeychainIdentity(keyData, client, logw = { _, msg -> captured = msg })
        every { client.sign(any(), any(), any()) } throws
            OpenKeychainException("asked for user interaction 4 times without completing the request")

        val thrown = runCatching { failingIdentity.getSignature(byteArrayOf(1, 2, 3), "ssh-rsa") }.exceptionOrNull()

        assertTrue("the failure must still reach JSch, got $thrown", thrown is OpenKeychainException)
        assertTrue(
            "the log must carry the provider's reason, got '$captured'",
            captured?.contains("user interaction 4 times") == true,
        )
    }

}
