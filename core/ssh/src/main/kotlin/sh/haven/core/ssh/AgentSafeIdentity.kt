package sh.haven.core.ssh

import android.util.Log
import com.jcraft.jsch.Identity

private const val TAG = "AgentSafeIdentity"

/**
 * #602: adapts a hardware-backed identity ([FidoIdentity]) for JSch's
 * forwarded agent channel.
 *
 * The failure contract matters here. JSch's `ChannelAgentForwarding.run()`
 * only treats a *null* return from [Identity.getSignature] as "decline" (it
 * replies SSH_AGENT_FAILURE). A thrown exception escapes the channel loop
 * into the session I/O thread — a declined or failed hardware ceremony
 * (user walked away, NFC lifted, PIN wrong) would take the whole SSH
 * connection down instead of answering the remote with a failure. So this
 * wrapper converts every throw into null, and logs the cause for the
 * verbose connection log.
 *
 * Presence is enforced by the wrapped identity itself: every signature
 * runs a CTAP assertion, so each forwarded `ssh` invocation prompts for a
 * touch on the phone. The #377 "no hook to prompt at sign-request time"
 * limitation that made sk-keys unforwardable is what this closes — the
 * prompt *is* the signing ceremony.
 */
class AgentSafeIdentity(
    private val delegate: Identity,
) : Identity {

    override fun getAlgName(): String = delegate.algName

    override fun getName(): String = delegate.name

    override fun getPublicKeyBlob(): ByteArray = delegate.publicKeyBlob

    override fun isEncrypted(): Boolean = delegate.isEncrypted

    override fun setPassphrase(passphrase: ByteArray?): Boolean = delegate.setPassphrase(passphrase)

    override fun decrypt(): Boolean = delegate.decrypt()

    override fun clear() = delegate.clear()

    override fun getSignature(data: ByteArray): ByteArray? =
        try {
            delegate.getSignature(data)
        } catch (e: Exception) {
            Log.w(TAG, "Signature request declined (${e.javaClass.simpleName}): ${e.message}")
            null
        }

    override fun getSignature(data: ByteArray, alg: String): ByteArray? =
        try {
            delegate.getSignature(data, alg)
        } catch (e: Exception) {
            Log.w(TAG, "Signature request declined for $alg (${e.javaClass.simpleName}): ${e.message}")
            null
        }
}
