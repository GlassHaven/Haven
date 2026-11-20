package sh.haven.core.ssh.openkeychain

import android.util.Log
import com.jcraft.jsch.Identity

private const val TAG = "OpenKeychainIdentity"

/**
 * JSch [Identity] for a key held by an external provider (#487).
 *
 * The counterpart of `sh.haven.core.fido.FidoIdentity`: Haven offers the
 * public key and, when the server challenges it, hands the challenge to the
 * provider — OpenKeychain, and through it possibly a hardware token — and
 * returns whatever signature comes back. No private key passes through Haven
 * in either case.
 *
 * The provider encodes the SSH signature blob itself (algorithm name and
 * signature, RFC 4253 §6.6), which is precisely what JSch expects here, so
 * the bytes are returned untouched. This is the one place where the FIDO
 * analogy stops: an SK signature has to be assembled by hand because the
 * authenticator returns a bare signature and knows nothing about SSH.
 */
class OpenKeychainIdentity(
    private val keyData: OpenKeychainKeyData,
    private val client: OpenKeychainClient,
    // Logging seam (defaults to android.util.Log). Injected so a test can
    // capture the provider's failure reason without mockkStatic(Log) — a
    // global static mock shared across test classes that flakes under
    // parallel execution. Production callers use the defaults.
    private val logd: (String, String) -> Unit = { tag, msg -> Log.d(tag, msg) },
    private val logw: (String, String) -> Unit = { tag, msg -> Log.w(tag, msg) },
) : Identity {

    override fun getAlgName(): String = keyData.algorithm

    override fun getName(): String = "haven-openkeychain-${keyData.keyId}"

    override fun getPublicKeyBlob(): ByteArray = keyData.publicKeyBlob

    /**
     * False, though the key may well be passphrase- or PIN-protected: the
     * provider handles that itself when asked to sign. Reporting true would
     * make JSch prompt for a passphrase Haven has no use for.
     */
    override fun isEncrypted(): Boolean = false

    override fun setPassphrase(passphrase: ByteArray?): Boolean = true

    override fun decrypt(): Boolean = true

    override fun clear() {}

    override fun getSignature(data: ByteArray): ByteArray = getSignature(data, algName)

    /**
     * Blocks on JSch's I/O thread while the provider signs — which may mean
     * waiting for the user to enter a PIN and present a token. JSch's
     * authentication is synchronous, so there is nowhere else to wait.
     */
    override fun getSignature(data: ByteArray, alg: String): ByteArray {
        logd(TAG, "signing ${data.size} bytes as $alg via ${keyData.providerPackage}")
        val signature = try {
            client.sign(keyData.keyId, data, alg)
        } catch (e: Exception) {
            // JSch catches whatever an Identity throws and quietly moves to the
            // next auth method, so without this the provider's own reason never
            // reaches the log — a reporter's failing reconnect showed "signing
            // 148 bytes" and then nothing at all, followed by JSch's generic
            // "Auth fail", which says nothing about why (#487).
            //
            // The reason is the whole diagnosis here: too many interaction
            // rounds, a cancelled prompt and a provider error are three
            // different bugs that look identical from outside.
            logw(TAG, "provider ${keyData.providerPackage} did not sign: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
        logd(TAG, "provider returned a ${signature.size} byte signature")
        return signature
    }
}
