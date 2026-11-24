package sh.haven.core.security

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Holds the shared HMAC secret used to sign controlled-egress consent decisions
 * ([EgressGrantSigner]). The same 32 bytes are provisioned root-owned (0600) to
 * the host's `/usr/local/lib/claude-egress/haven.key`; the uid-1000 sandboxed
 * agent cannot read that file, so it cannot forge a signed "allow".
 *
 * At rest the secret is encrypted with Tink AES-256-GCM under a dedicated
 * Android Keystore master key ([MASTER_KEY_URI]) — the same device-bound
 * protection pattern as [KeyEncryption], but a separate keyset so egress
 * signing keys and SSH private keys fail/reset independently. Stored in the
 * app's private files dir; the host agent has no access to the phone filesystem.
 *
 * Provisioning is a manual step surfaced to the user ([secretBase64] + [keyId]
 * via the `get_egress_signing_secret` tool); Haven never writes host root files.
 */
object EgressSigningKeyStore {

    private const val KEYSET_NAME = "haven_egress_keyset"
    private const val PREFERENCE_FILE = "haven_egress_keyset_prefs"
    private const val MASTER_KEY_URI = "android-keystore://haven_egress_master"
    private const val FILE_NAME = "egress-signing-key.enc"
    private val ASSOCIATED_DATA = "haven-egress-signing-secret".toByteArray()
    private const val SECRET_BYTES = 32

    @Volatile
    private var aead: Aead? = null

    private fun getAead(context: Context): Aead {
        aead?.let { return it }
        synchronized(this) {
            aead?.let { return it }
            AeadConfig.register()
            val keysetHandle = AndroidKeysetManager.Builder()
                .withSharedPref(context, KEYSET_NAME, PREFERENCE_FILE)
                .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
                .withMasterKeyUri(MASTER_KEY_URI)
                .build()
                .keysetHandle
            return keysetHandle.getPrimitive(Aead::class.java).also { aead = it }
        }
    }

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    /**
     * The signing secret, generating and persisting it on first use. If the
     * stored blob can no longer be decrypted (e.g. an install-channel switch
     * discarded the Keystore master, #655), a fresh secret is generated — the
     * [keyId] changes, signalling the host must be re-provisioned.
     */
    fun getOrCreateSecret(context: Context): ByteArray = synchronized(this) {
        val f = file(context)
        if (f.exists()) {
            try {
                return@synchronized getAead(context).decrypt(f.readBytes(), ASSOCIATED_DATA)
            } catch (_: Exception) {
                // Undecryptable — fall through to regenerate.
            }
        }
        val secret = ByteArray(SECRET_BYTES).also { SecureRandom().nextBytes(it) }
        f.writeBytes(getAead(context).encrypt(secret, ASSOCIATED_DATA))
        secret
    }

    /** Base64 transport encoding of the secret for the provisioning reveal. */
    fun secretBase64(context: Context): String =
        Base64.getEncoder().encodeToString(getOrCreateSecret(context))

    /**
     * Stable short identifier for the current secret (first 16 hex of its
     * SHA-256). Lets the host/Ian confirm which key is provisioned; changes if
     * the secret is regenerated.
     */
    fun keyId(context: Context): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(getOrCreateSecret(context))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    /** Drop the stored secret so the next [getOrCreateSecret] regenerates it. */
    fun reset(context: Context) = synchronized(this) {
        file(context).delete()
        Unit
    }
}
