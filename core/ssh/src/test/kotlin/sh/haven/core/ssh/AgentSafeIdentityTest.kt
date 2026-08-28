package sh.haven.core.ssh

import com.jcraft.jsch.Identity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

/**
 * #602: the failure contract that keeps a declined hardware ceremony from
 * killing the SSH session.
 *
 * JSch's ChannelAgentForwarding treats a NULL from getSignature as "decline"
 * (replies SSH_AGENT_FAILURE); a thrown exception escapes the channel loop
 * into the session I/O thread. FidoIdentity throws on every failure path
 * (PIN cancelled, touch timeout, NO_CREDENTIALS, uv-mismatch assert), so the
 * agent wrapper must convert throws to null — and pass successes through
 * untouched.
 */
class AgentSafeIdentityTest {

    /** Hand-rolled fake (not mockk): Identity has a Java default method,
     * and the overload dispatch is exactly what these tests pin down. */
    private class FakeIdentity(
        private val sig: ByteArray? = byteArrayOf(1, 2, 3),
        private val throws: Exception? = null,
    ) : Identity {
        var lastAlg: String? = null
        var plainCalls = 0
        var algCalls = 0

        override fun getAlgName(): String = "sk-ssh-ed25519@openssh.com"
        override fun getName(): String = "haven-fido-sk"
        override fun getPublicKeyBlob(): ByteArray = byteArrayOf(9, 9)
        override fun isEncrypted(): Boolean = false
        override fun setPassphrase(passphrase: ByteArray?): Boolean = true
        override fun decrypt(): Boolean = true
        override fun clear() {}

        override fun getSignature(data: ByteArray): ByteArray {
            plainCalls++
            throws?.let { throw it }
            return sig!!
        }

        override fun getSignature(data: ByteArray, alg: String): ByteArray {
            algCalls++
            lastAlg = alg
            throws?.let { throw it }
            return sig!!
        }
    }

    @Test
    fun `successes pass through unchanged`() {
        val fake = FakeIdentity()
        val safe = AgentSafeIdentity(fake)
        assertArrayEquals(byteArrayOf(1, 2, 3), safe.getSignature(byteArrayOf(0)))
        assertEquals(1, fake.plainCalls)
        assertEquals("sk-ssh-ed25519@openssh.com", safe.algName)
        assertEquals("haven-fido-sk", safe.name)
        assertFalse(safe.isEncrypted)
        assertArrayEquals(byteArrayOf(9, 9), safe.publicKeyBlob)
    }

    @Test
    fun `a thrown ceremony failure becomes null, not an escaped exception`() {
        val safe = AgentSafeIdentity(FakeIdentity(throws = IOException("PIN entry cancelled")))
        assertNull(safe.getSignature(byteArrayOf(0)))
        assertNull(safe.getSignature(byteArrayOf(0), "ssh-ed25519"))
    }

    @Test
    fun `the alg-specific overload is used when JSch negotiates an algorithm`() {
        val fake = FakeIdentity()
        val safe = AgentSafeIdentity(fake)
        assertArrayEquals(byteArrayOf(1, 2, 3), safe.getSignature(byteArrayOf(0), "rsa-sha2-512"))
        assertEquals(1, fake.algCalls)
        assertEquals("rsa-sha2-512", fake.lastAlg)
        assertEquals(0, fake.plainCalls)
    }
}
