package sh.haven.core.security

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager

/**
 * Encrypts/decrypts SSH private key bytes using Tink AEAD backed by Android Keystore.
 *
 * Keys are encrypted with AES-256-GCM. The master key is stored in Android Keystore
 * (hardware-backed on devices with a secure element). This protects key material
 * at rest — even if the database is extracted, keys cannot be read without the
 * device's Keystore.
 */
object KeyEncryption {

    private const val KEYSET_NAME = "haven_ssh_key_keyset"
    private const val PREFERENCE_FILE = "haven_ssh_key_keyset_prefs"
    private const val MASTER_KEY_URI = "android-keystore://haven_ssh_key_master"

    // Associated data for AEAD — prevents ciphertext from being used in a different context
    private val ASSOCIATED_DATA = "haven-ssh-private-key".toByteArray()

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

    /** Encrypt private key bytes. Returns ciphertext that can only be decrypted on this device. */
    fun encrypt(context: Context, plaintext: ByteArray): ByteArray {
        return getAead(context).encrypt(plaintext, ASSOCIATED_DATA)
    }

    /** Decrypt private key bytes. Throws GeneralSecurityException if tampered or wrong device. */
    fun decrypt(context: Context, ciphertext: ByteArray): ByteArray {
        return getAead(context).decrypt(ciphertext, ASSOCIATED_DATA)
    }

    /**
     * Is the SSH-key master key usable right now? (#655)
     *
     * This keyset is separate from [CredentialEncryption]'s, so it fails
     * separately: switching install channels (F-Droid to a GitHub release, or
     * the reverse) replaces the APK signature, and Android discards the Keystore
     * entries belonging to the old signature. The master key
     * `haven_ssh_key_master` goes with it while the keyset prefs survive, so
     * every stored private key becomes undecryptable and — the part that reads
     * as a bug — generating a new key fails too, because generation writes
     * through the same broken AEAD. [resetSshKeyStorage] is the way back.
     */
    fun probe(context: Context): CredentialEncryption.Failure =
        CredentialEncryption.probeFailure { getAead(context) }

    /**
     * Drop the SSH-key master key and its keyset so the next call regenerates
     * both (#655).
     *
     * **This makes every stored private key permanently unreadable** — they were
     * already unreadable, which is what got us here — and unblocks key
     * generation, which is the point. Mirrors
     * [CredentialEncryption.resetCredentialStorage] including the ordering:
     * keyset first, so a crash between the two leaves the state we know how to
     * recover from rather than a fresh master key over an old keyset.
     *
     * Not called automatically. It destroys the user's keys, so it is their
     * decision, and it is only offered on a PERMANENT failure — on a transient
     * one (locked device) the keys are still recoverable.
     */
    fun resetSshKeyStorage(context: Context) {
        synchronized(this) {
            aead = null
            runCatching {
                context.getSharedPreferences(PREFERENCE_FILE, Context.MODE_PRIVATE)
                    .edit()
                    .remove(KEYSET_NAME)
                    .commit()
            }
            runCatching {
                val ks = java.security.KeyStore.getInstance("AndroidKeyStore")
                ks.load(null)
                if (ks.containsAlias("haven_ssh_key_master")) ks.deleteEntry("haven_ssh_key_master")
            }
        }
    }

    /**
     * Check if bytes look like they're already encrypted (Tink ciphertext).
     * Tink AEAD ciphertext starts with a version byte (0x01) followed by a 4-byte key ID.
     * Plain PEM/OpenSSH keys start with '-' (0x2D) or raw DER starts with 0x30.
     */
    fun isEncrypted(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        // Plain key formats start with '-' (PEM) or 0x30 (DER SEQUENCE)
        val first = bytes[0]
        return first != '-'.code.toByte() && first != 0x30.toByte()
    }
}
