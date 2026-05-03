package sh.haven.core.ssh

import com.jcraft.jsch.Identity

/**
 * Wraps any [Identity] (typically FidoIdentity) and overrides [getPublicKeyBlob]
 * to return the certificate blob. The server verifies the cert against
 * TrustedUserCAKeys while the signature still comes from the underlying identity
 * (hardware authenticator or software key).
 *
 * [getAlgName] returns the certificate key type (e.g.
 * "sk-ssh-ed25519-cert-v01@openssh.com") so that JSch's UserAuthPublicKey
 * negotiation advertises the cert type to the server.
 */
class CertificateWrappedIdentity(
    private val delegate: Identity,
    private val certBlob: ByteArray,
    private val certKeyType: String,
) : Identity {

    override fun getAlgName(): String = certKeyType

    override fun getName(): String = delegate.name

    override fun getPublicKeyBlob(): ByteArray = certBlob

    override fun isEncrypted(): Boolean = delegate.isEncrypted

    override fun setPassphrase(passphrase: ByteArray?): Boolean = delegate.setPassphrase(passphrase)

    override fun decrypt(): Boolean = delegate.decrypt()

    override fun clear() = delegate.clear()

    override fun getSignature(data: ByteArray): ByteArray = delegate.getSignature(data)

    override fun getSignature(data: ByteArray, alg: String): ByteArray {
        // JSch may pass the cert key type as alg; strip to base algorithm for signing.
        val baseAlg = if (alg.endsWith("-cert-v01@openssh.com")) {
            alg.removeSuffix("-cert-v01@openssh.com")
        } else {
            alg
        }
        return delegate.getSignature(data, baseAlg)
    }
}
