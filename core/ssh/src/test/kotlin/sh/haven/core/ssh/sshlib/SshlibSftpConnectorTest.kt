package sh.haven.core.ssh.sshlib

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import sh.haven.core.ssh.ConnectionConfig
import sh.haven.core.ssh.ConnectionConfig.AuthMethod

/** The phase-1 capability gate (#58): what may dial sshlib, what falls back. */
class SshlibSftpConnectorTest {

    private fun config(auth: AuthMethod) =
        ConnectionConfig(host = "h", username = "u", authMethod = auth)

    private fun reason(
        auth: AuthMethod = AuthMethod.Password("p"),
        hasJump: Boolean = false,
        hasProxy: Boolean = false,
    ) = SshlibSftpConnector.unsupportedReason(config(auth), hasJump, hasProxy)

    @Test
    fun `direct password connection is supported`() = assertNull(reason())

    @Test
    fun `plain private key is supported`() =
        assertNull(reason(AuthMethod.PrivateKey(byteArrayOf(1), "pw")))

    @Test
    fun `key pool without certificates is supported`() = assertNull(
        reason(
            AuthMethod.PrivateKeys(
                listOf(AuthMethod.PrivateKeys.KeyEntry("k", byteArrayOf(1))),
            ),
        ),
    )

    @Test
    fun `jump hosts fall back`() = assertNotNull(reason(hasJump = true))

    @Test
    fun `proxied connections fall back`() = assertNotNull(reason(hasProxy = true))

    @Test
    fun `FIDO keys fall back`() =
        assertNotNull(reason(AuthMethod.FidoKey(skKeyData = byteArrayOf(1))))

    /**
     * Security-chip keys fall back the same way FIDO keys do: their private
     * half only signs via KeystoreSshIdentity on the JSch engine, so sshlib
     * must refuse them up front rather than fail to authenticate with a
     * perfectly valid key.
     */
    @Test
    fun `security-chip keys fall back`() = assertNotNull(
        reason(
            AuthMethod.HardwareKey(
                alias = "haven-ssh-ab12cd34",
                publicKeyOpenSsh = "ecdsa-sha2-nistp256 AAAA haven-ssh-ab12cd34",
            ),
        ),
    )

    @Test
    fun `certificate-carrying keys fall back`() {
        assertNotNull(
            reason(AuthMethod.PrivateKey(byteArrayOf(1), certificateBytes = byteArrayOf(2))),
        )
        assertNotNull(
            reason(
                AuthMethod.PrivateKeys(
                    listOf(
                        AuthMethod.PrivateKeys.KeyEntry("plain", byteArrayOf(1)),
                        AuthMethod.PrivateKeys.KeyEntry("cert", byteArrayOf(1), certificateBytes = byteArrayOf(2)),
                    ),
                ),
            ),
        )
    }

    /**
     * Chains run as their sub-methods in order plus a keyboard-interactive
     * follow-up, so a chain is supported exactly when every sub-method is.
     */
    @Test
    fun `multi-method chain of supported methods is supported`() = assertNull(
        reason(
            AuthMethod.Multi(
                listOf(AuthMethod.PrivateKey(byteArrayOf(1)), AuthMethod.Password("p")),
            ),
        ),
    )

    @Test
    fun `multi-method chain falls back when a sub-method does`() = assertNotNull(
        reason(
            AuthMethod.Multi(
                listOf(AuthMethod.Password("p"), AuthMethod.FidoKey(skKeyData = byteArrayOf(1))),
            ),
        ),
    )
}
