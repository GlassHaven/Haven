package sh.haven.core.ssh

import com.jcraft.jsch.JSchAlgoNegoFailException
import com.jcraft.jsch.JSchException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Constructor

class LegacyHostKeyFallbackTest {

    /** JSch's real default proposal on 2.28.8 — the list that has no ssh-rsa. */
    private val defaultProposal =
        "ssh-ed25519-cert-v01@openssh.com,ecdsa-sha2-nistp256-cert-v01@openssh.com," +
            "ecdsa-sha2-nistp384-cert-v01@openssh.com,ecdsa-sha2-nistp521-cert-v01@openssh.com," +
            "rsa-sha2-512-cert-v01@openssh.com,rsa-sha2-256-cert-v01@openssh.com," +
            "ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521," +
            "rsa-sha2-512,rsa-sha2-256"

    /**
     * Build a [JSchAlgoNegoFailException] the way JSch does.
     *
     * Its constructor is package-private in `com.jcraft.jsch` and derives the
     * algorithm name from a slot index (0 = kex, 1 = server_host_key, …), so a
     * test outside that package has to go through reflection rather than
     * `new`. Going through the real constructor keeps the message and the
     * `getAlgorithmName()` value exactly what production sees.
     */
    private fun algoNegoFail(index: Int, jschProposal: String, serverProposal: String): JSchAlgoNegoFailException {
        val ctor: Constructor<JSchAlgoNegoFailException> =
            JSchAlgoNegoFailException::class.java.getDeclaredConstructor(
                Int::class.javaPrimitiveType,
                String::class.java,
                String::class.java,
            )
        ctor.isAccessible = true
        return ctor.newInstance(index, jschProposal, serverProposal)
    }

    /** Slot 1 is `server_host_key`; the server offers only the SHA-1 algorithm. */
    private fun hostKeyFailure() = algoNegoFail(1, defaultProposal, "ssh-rsa")

    // --- the failure shape ---

    @Test
    fun `host key negotiation failure is recognised`() {
        assertTrue(LegacyHostKeyFallback.isHostKeyNegotiationFailure(hostKeyFailure()))
    }

    @Test
    fun `kex negotiation failure is not mistaken for a host key failure`() {
        val kexFail = algoNegoFail(0, "curve25519-sha256,ecdh-sha2-nistp256", "diffie-hellman-group1-sha1")
        assertFalse(LegacyHostKeyFallback.isHostKeyNegotiationFailure(kexFail))
    }

    @Test
    fun `plain JSchException is not a negotiation failure`() {
        assertFalse(LegacyHostKeyFallback.isHostKeyNegotiationFailure(JSchException("Auth fail")))
    }

    @Test
    fun `wrapped negotiation failure is still recognised`() {
        val wrapped = JSchException("Session.connect: wrapped", hostKeyFailure())
        assertTrue(LegacyHostKeyFallback.isHostKeyNegotiationFailure(wrapped))
    }

    // --- the retry proposal ---

    @Test
    fun `retry appends ssh-rsa after the modern algorithms`() {
        val proposal = LegacyHostKeyFallback.retryProposal(hostKeyFailure(), defaultProposal)
        assertEquals("$defaultProposal,ssh-rsa", proposal)
    }

    @Test
    fun `retry keeps modern algorithms first so a SHA-2 server still wins`() {
        val proposal = LegacyHostKeyFallback.retryProposal(hostKeyFailure(), defaultProposal)!!
        val items = proposal.split(",")
        assertEquals("ssh-rsa", items.last())
        assertEquals("ssh-ed25519-cert-v01@openssh.com", items.first())
        assertTrue(
            "rsa-sha2-512 still ahead of ssh-rsa",
            items.indexOf("rsa-sha2-512") < items.indexOf("ssh-rsa"),
        )
    }

    @Test
    fun `no retry when the failure is not a host key mismatch`() {
        val kexFail = algoNegoFail(0, "a,b", "c")
        assertNull(LegacyHostKeyFallback.retryProposal(kexFail, defaultProposal))
    }

    @Test
    fun `no retry when ssh-rsa is already in the proposal`() {
        assertNull(
            LegacyHostKeyFallback.retryProposal(hostKeyFailure(), "$defaultProposal,ssh-rsa"),
        )
    }

    @Test
    fun `no second retry once already retried`() {
        assertNull(
            LegacyHostKeyFallback.retryProposal(hostKeyFailure(), defaultProposal, alreadyRetried = true),
        )
    }

    @Test
    fun `empty proposal still yields ssh-rsa`() {
        assertEquals("ssh-rsa", LegacyHostKeyFallback.retryProposal(hostKeyFailure(), null))
        assertEquals("ssh-rsa", LegacyHostKeyFallback.retryProposal(hostKeyFailure(), ""))
    }

    // --- the per-connection switch ---

    @Test
    fun `fallback is enabled by default`() {
        assertTrue(LegacyHostKeyFallback.isEnabled(emptyMap()))
        assertTrue(LegacyHostKeyFallback.isEnabledFromOptionsText(null))
    }

    @Test
    fun `explicit no disables the fallback`() {
        assertFalse(LegacyHostKeyFallback.isEnabled(mapOf("HavenLegacyHostKeyFallback" to "no")))
    }

    @Test
    fun `directive is case-insensitive and accepts ssh_config spelling`() {
        assertFalse(LegacyHostKeyFallback.isEnabledFromOptionsText("havenlegacyhostkeyfallback=off"))
        assertTrue(LegacyHostKeyFallback.isEnabledFromOptionsText("LegacyHostKeyFallback yes"))
    }

    @Test
    fun `an unparseable value leaves the default enabled`() {
        assertTrue(LegacyHostKeyFallback.isEnabled(mapOf("HavenLegacyHostKeyFallback" to "maybe")))
    }

    @Test
    fun `the directive coexists with other ssh options`() {
        val text = """
            ServerAliveInterval 60
            HostKeyAlgorithms +ssh-rsa
            HavenLegacyHostKeyFallback no
        """.trimIndent()
        assertFalse(LegacyHostKeyFallback.isEnabledFromOptionsText(text))
    }

    @Test
    fun `warning names the host and the weakened algorithm`() {
        val warning = LegacyHostKeyFallback.degradedWarning("example.com", 22)
        assertTrue(warning.contains("example.com:22"))
        assertTrue(warning.contains("ssh-rsa"))
        assertTrue(warning.contains("SHA-1"))
    }
}
