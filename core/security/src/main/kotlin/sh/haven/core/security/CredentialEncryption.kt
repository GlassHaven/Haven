package sh.haven.core.security

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.KeyStoreException
import java.security.UnrecoverableKeyException

/**
 * Encrypts/decrypts credential strings (passwords) using Tink AEAD backed by Android Keystore.
 *
 * Uses its own Keystore master key (`haven_credential_master`), separate from
 * [KeyEncryption]'s `haven_ssh_key_master`, and distinct associated data, so
 * password ciphertext cannot be confused with SSH key ciphertext. (An earlier
 * version of this comment claimed the two shared a master key. They do not —
 * which matters when diagnosing a Keystore failure, because only one of the
 * two aliases may be broken.)
 *
 * Encrypted values are stored as "ENC:" + Base64(ciphertext) so they can live in
 * TEXT columns alongside legacy plaintext values. Plaintext values are migrated
 * transparently on first read/write.
 */
object CredentialEncryption {

    private const val KEYSET_NAME = "haven_credential_keyset"
    private const val PREFERENCE_FILE = "haven_credential_keyset_prefs"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val MASTER_KEY_ALIAS = "haven_credential_master"
    private const val MASTER_KEY_URI = "android-keystore://$MASTER_KEY_ALIAS"

    private val ASSOCIATED_DATA = "haven-credential".toByteArray()
    private const val ENCRYPTED_PREFIX = "ENC:"

    // Tink AEAD (AES256_GCM, TINK output prefix) ciphertext starts with a
    // 1-byte version 0x01 + 4-byte key id, followed by the 12-byte GCM nonce,
    // ciphertext, and 16-byte tag — so a genuine value is at least 33 bytes and
    // begins with 0x01. Used to reject a plaintext that merely happens to start
    // with "ENC:" (security-review #18).
    private const val TINK_PREFIX_VERSION: Byte = 0x01
    private const val TINK_MIN_CIPHERTEXT_LEN = 5 + 12 + 16

    @Volatile
    private var aead: Aead? = null

    /**
     * The Android Keystore refused to hand back the credential master key.
     *
     * Reported as #579: on some devices `haven_credential_master` becomes
     * unloadable, and Tink surfaces that as `InvalidKeyException: Keystore
     * cannot load the key with ID`. It used to escape [encrypt] uncaught and
     * kill the process on the main thread the moment anyone typed a passphrase
     * and pressed save.
     *
     * Extends `GeneralSecurityException` on purpose: callers that already catch
     * crypto failures broadly keep working unchanged.
     */
    class KeystoreUnavailableException internal constructor(
        cause: Throwable,
        /**
         * True when the key is gone for good and [resetCredentialStorage] is
         * the only way forward. False means "try again once the device is
         * unlocked" — resetting then would destroy recoverable credentials.
         */
        val permanent: Boolean,
    ) : GeneralSecurityException(
        "Android Keystore cannot serve Haven's credential master key",
        cause,
    )

    /** How a Keystore failure should be treated. */
    enum class Failure {
        /** Not a Keystore problem — the data or something else is at fault. */
        NONE,

        /**
         * The key exists but cannot be used *right now* — typically the device
         * is locked and the key needs user authentication.
         *
         * ★Never reset on this. The credentials are perfectly recoverable once
         * the device is unlocked, and wiping the master key here would destroy
         * them for the sake of a lock screen.
         */
        TRANSIENT,

        /**
         * The key is gone or permanently unusable. Anything encrypted under it
         * is already undecryptable, so a reset costs nothing that was not
         * already lost — which is what makes recovery safe at all.
         */
        PERMANENT,
    }

    /**
     * Classify a failure from [getAead].
     *
     * Walks the cause chain because Tink wraps the Keystore's exception in its
     * own. Kept pure and separate from the Keystore call so the decision is
     * unit-testable — none of the surrounding code is, on a JVM with no Android
     * Keystore in it.
     *
     * ★Transient subclasses are checked FIRST and deliberately. Both
     * `UserNotAuthenticatedException` and `KeyPermanentlyInvalidatedException`
     * extend `InvalidKeyException` (verified against android-36), so a
     * classifier that matched the parent first would call a locked device a
     * dead key and wipe recoverable credentials.
     */
    internal fun classifyFailure(
        t: Throwable,
        /**
         * How to read a throwable's class name. Injectable ONLY so the ordering
         * below can be tested: the platform Keystore exceptions cannot be
         * instantiated on a JVM (android.jar ships stubs that throw), so without
         * this seam the one decision that risks data loss would have no test
         * that could fail.
         */
        nameOf: (Throwable) -> String = { it::class.java.name },
    ): Failure {
        var cause: Throwable? = t
        var hops = 0
        while (cause != null && hops < 16) {
            // ★Name check FIRST. UserNotAuthenticatedException and
            // KeyPermanentlyInvalidatedException both extend InvalidKeyException
            // (verified against android-36), so matching the parent first would
            // read a merely-locked device as a dead key — and recovery wipes the
            // master key, destroying credentials that were recoverable.
            classifyByName(nameOf(cause))?.let { return it }
            if (cause is UnrecoverableKeyException || cause is KeyStoreException) {
                return Failure.PERMANENT
            }
            // Tink reports a missing/unusable alias as a bare InvalidKeyException
            // ("Keystore cannot load the key with ID: ..."), which is #579.
            if (cause is InvalidKeyException) return Failure.PERMANENT
            cause = cause.cause
            hops++
        }
        return Failure.NONE
    }

    /**
     * Classify the platform Keystore exceptions by name, because they cannot be
     * referenced from a JVM unit test. Returns null when the name says nothing.
     */
    internal fun classifyByName(className: String): Failure? = when (className) {
        "android.security.keystore.UserNotAuthenticatedException",
        "android.security.keystore.KeyNotYetValidException",
        "android.security.keystore.BackendBusyException",
        -> Failure.TRANSIENT

        "android.security.keystore.KeyPermanentlyInvalidatedException",
        "android.security.keystore.KeyExpiredException",
        -> Failure.PERMANENT

        else -> null
    }

    /** True when [t] means the Keystore cannot serve our master key at all. */
    internal fun isKeystoreUnavailable(t: Throwable): Boolean =
        classifyFailure(t) != Failure.NONE

    private fun getAead(context: Context): Aead {
        aead?.let { return it }
        synchronized(this) {
            aead?.let { return it }
            try {
                AeadConfig.register()
                val keysetHandle = AndroidKeysetManager.Builder()
                    .withSharedPref(context, KEYSET_NAME, PREFERENCE_FILE)
                    .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
                    .withMasterKeyUri(MASTER_KEY_URI)
                    .build()
                    .keysetHandle
                return keysetHandle.getPrimitive(Aead::class.java).also { aead = it }
            } catch (t: Throwable) {
                // Classify rather than swallow: a broken Keystore is a distinct
                // condition the UI can explain, and anything else still deserves
                // to surface as itself.
                val failure = classifyFailure(t)
                if (failure != Failure.NONE) {
                    throw KeystoreUnavailableException(t, permanent = failure == Failure.PERMANENT)
                }
                throw t
            }
        }
    }

    /**
     * Drop the credential master key and the keyset it protected, so the next
     * call regenerates both (#579).
     *
     * **This makes every existing `ENC:` value permanently unreadable**, and it
     * is the only way back from a master key the Keystore will not serve.
     *
     * That reads worse than it is. If the master key cannot be loaded, the
     * keyset it wraps cannot be decrypted, so every credential under it is
     * *already* unreadable — this does not destroy recoverable data, it
     * restores the app's ability to store anything at all. That argument only
     * holds for [Failure.PERMANENT]; on a [Failure.TRANSIENT] failure the data
     * IS recoverable and calling this would be the thing that loses it.
     *
     * Deliberately not called automatically anywhere. The user re-enters their
     * passwords afterwards, so it is their decision to make, not a repair to
     * spring on them.
     */
    fun resetCredentialStorage(context: Context) {
        synchronized(this) {
            aead = null
            // Order matters: drop the keyset first. If the process dies between
            // the two, a stale keyset with no master key is the state we already
            // know how to recover from, whereas a fresh master key over an old
            // keyset would look healthy and fail on every decrypt.
            runCatching {
                context.getSharedPreferences(PREFERENCE_FILE, Context.MODE_PRIVATE)
                    .edit()
                    .remove(KEYSET_NAME)
                    .commit()
            }
            runCatching {
                val ks = java.security.KeyStore.getInstance(ANDROID_KEYSTORE)
                ks.load(null)
                if (ks.containsAlias(MASTER_KEY_ALIAS)) ks.deleteEntry(MASTER_KEY_ALIAS)
            }
        }
    }

    /** Encrypt a password string. Returns "ENC:" + Base64(ciphertext). */
    fun encrypt(context: Context, plaintext: String): String {
        val ciphertext = getAead(context).encrypt(plaintext.toByteArray(Charsets.UTF_8), ASSOCIATED_DATA)
        return ENCRYPTED_PREFIX + Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }

    /**
     * Is credential storage usable right now?
     *
     * [Failure.NONE] when the master key loads. Anything else is the reason it
     * does not, and [Failure.PERMANENT] is the only state in which
     * [resetCredentialStorage] is the answer.
     */
    fun probe(context: Context): Failure =
        try {
            getAead(context)
            Failure.NONE
        } catch (e: KeystoreUnavailableException) {
            if (e.permanent) Failure.PERMANENT else Failure.TRANSIENT
        } catch (t: Throwable) {
            classifyFailure(t)
        }

    /**
     * Decrypt, returning null when the value can never be read again.
     *
     * Null means *permanently* unreadable — the master key that wrapped this
     * ciphertext is gone, so no amount of retrying brings it back and the
     * credential has to be re-entered. Callers can safely treat that as "no
     * credential stored".
     *
     * A TRANSIENT failure throws instead, and that asymmetry is the whole
     * point. Returning null for a locked device would let the next save write
     * that null over ciphertext which was about to become readable again —
     * turning a lock screen into permanent data loss.
     */
    fun decryptOrNull(context: Context, stored: String): String? =
        try {
            decrypt(context, stored)
        } catch (e: KeystoreUnavailableException) {
            if (e.permanent) null else throw e
        } catch (_: Exception) {
            // Corrupt ciphertext, bad Base64, an unparseable restored keyset:
            // all mean this particular value is not coming back.
            null
        }

    /** Decrypt a password string. Handles both encrypted ("ENC:...") and legacy plaintext. */
    fun decrypt(context: Context, stored: String): String {
        if (!stored.startsWith(ENCRYPTED_PREFIX)) return stored // legacy plaintext
        val ciphertext = Base64.decode(stored.removePrefix(ENCRYPTED_PREFIX), Base64.NO_WRAP)
        return String(getAead(context).decrypt(ciphertext, ASSOCIATED_DATA), Charsets.UTF_8)
    }

    /**
     * True if the value is Haven-encrypted ciphertext. Requires the "ENC:"
     * prefix AND a well-formed Tink AEAD body, so a plaintext credential that
     * merely starts with "ENC:" is (correctly) treated as plaintext and gets
     * encrypted on the next save rather than being left in the clear (#18).
     */
    fun isEncrypted(stored: String): Boolean {
        if (!stored.startsWith(ENCRYPTED_PREFIX)) return false
        return try {
            // java.util.Base64 (not android.util.Base64) so this stays a pure
            // predicate callable from plain JVM unit tests; the standard-alphabet
            // decoder round-trips the NO_WRAP output that encrypt() writes.
            val body = java.util.Base64.getDecoder().decode(stored.removePrefix(ENCRYPTED_PREFIX))
            body.size >= TINK_MIN_CIPHERTEXT_LEN && body[0] == TINK_PREFIX_VERSION
        } catch (_: Exception) {
            false
        }
    }
}
