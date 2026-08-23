package sh.haven.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.KeyStoreException
import java.security.UnrecoverableKeyException

/**
 * #579: a Keystore that will not serve `haven_credential_master` used to kill
 * the process from inside a save. This classifier is the decision between
 * "tell the user the keystore is broken" and "let it surface as itself", so it
 * is the part worth pinning — the Keystore call around it cannot run on a JVM.
 */
class CredentialKeystoreFailureTest {

    /** The exact exception from the #579 report. */
    @Test
    fun `the reported InvalidKeyException is recognised`() {
        val reported = InvalidKeyException(
            "Keystore cannot load the key with ID: haven_credential_master",
        )
        assertTrue(CredentialEncryption.isKeystoreUnavailable(reported))
    }

    /**
     * Tink does not throw the Keystore's exception directly — it wraps it. A
     * classifier that only looked at the top frame would have missed the real
     * report entirely.
     */
    @Test
    fun `a wrapped keystore failure is recognised through the cause chain`() {
        val wrapped = GeneralSecurityException(
            "keyset read failed",
            IllegalStateException("tink", InvalidKeyException("Keystore cannot load the key")),
        )
        assertTrue(CredentialEncryption.isKeystoreUnavailable(wrapped))
    }

    @Test
    fun `other keystore failure types are recognised`() {
        assertTrue(CredentialEncryption.isKeystoreUnavailable(KeyStoreException("uninitialised")))
        assertTrue(CredentialEncryption.isKeystoreUnavailable(UnrecoverableKeyException("bad")))
    }

    /**
     * Corrupt ciphertext and IO failures are NOT a broken keystore. Classifying
     * them as one would tell the user their device is at fault and, worse,
     * would mask a real bug behind a soothing message.
     */
    @Test
    fun `data and IO failures are not treated as a keystore failure`() {
        assertFalse(CredentialEncryption.isKeystoreUnavailable(IOException("keyset file truncated")))
        assertFalse(CredentialEncryption.isKeystoreUnavailable(IllegalArgumentException("bad base64")))
        assertFalse(CredentialEncryption.isKeystoreUnavailable(GeneralSecurityException("decryption failed")))
    }

    /** A self-referencing cause chain must not hang the classifier. */
    @Test
    fun `a cyclic cause chain terminates`() {
        val a = IllegalStateException("a")
        val b = IllegalStateException("b", a)
        a.initCause(b)
        assertFalse(CredentialEncryption.isKeystoreUnavailable(b))
    }

    // ---- permanent vs transient: the distinction that decides data loss ----

    /**
     * ★The most important test here. `UserNotAuthenticatedException` extends
     * `InvalidKeyException` (verified against android-36), so a classifier that
     * matched the parent first would call a merely-locked device a dead key.
     * Recovery wipes the master key, so getting this wrong destroys credentials
     * that were perfectly recoverable once the user unlocked the phone.
     */
    @Test
    fun `a locked device is TRANSIENT, never permanent`() {
        assertEquals(
            CredentialEncryption.Failure.TRANSIENT,
            CredentialEncryption.classifyByName(
                "android.security.keystore.UserNotAuthenticatedException",
            ),
        )
    }

    @Test
    fun `a permanently invalidated key is PERMANENT`() {
        assertEquals(
            CredentialEncryption.Failure.PERMANENT,
            CredentialEncryption.classifyByName(
                "android.security.keystore.KeyPermanentlyInvalidatedException",
            ),
        )
    }

    @Test
    fun `other transient keystore states are not treated as permanent`() {
        for (name in listOf(
            "android.security.keystore.KeyNotYetValidException",
            "android.security.keystore.BackendBusyException",
        )) {
            assertEquals(
                "$name must not authorise a reset",
                CredentialEncryption.Failure.TRANSIENT,
                CredentialEncryption.classifyByName(name),
            )
        }
    }

    /** An unrelated class name must not decide anything on its own. */
    @Test
    fun `an unknown class name yields no verdict`() {
        assertNull(CredentialEncryption.classifyByName("java.lang.IllegalStateException"))
        assertNull(CredentialEncryption.classifyByName(""))
    }

    /** The #579 exception itself: a bare InvalidKeyException from Tink. */
    @Test
    fun `the reported failure classifies as PERMANENT so recovery is offered`() {
        assertEquals(
            CredentialEncryption.Failure.PERMANENT,
            CredentialEncryption.classifyFailure(
                InvalidKeyException("Keystore cannot load the key with ID: haven_credential_master"),
            ),
        )
    }

    @Test
    fun `a non-keystore failure classifies as NONE`() {
        assertEquals(
            CredentialEncryption.Failure.NONE,
            CredentialEncryption.classifyFailure(IOException("keyset file truncated")),
        )
    }

    /**
     * ★The test that guards against data loss.
     *
     * A locked device raises `UserNotAuthenticatedException`, which IS an
     * `InvalidKeyException`. If classifyFailure checked the parent type before
     * the specific name, it would report PERMANENT, recovery would wipe the
     * master key, and credentials that only needed an unlock would be gone.
     *
     * The platform class cannot be constructed here, so the name is injected —
     * the throwable stands in for one whose class name is the transient type.
     */
    @Test
    fun `classifyFailure reads a locked device as TRANSIENT despite it being an InvalidKeyException`() {
        val locked = InvalidKeyException("user not authenticated")
        assertEquals(
            "an InvalidKeyException subclass that means 'locked' must never authorise a reset",
            CredentialEncryption.Failure.TRANSIENT,
            CredentialEncryption.classifyFailure(locked) {
                "android.security.keystore.UserNotAuthenticatedException"
            },
        )
    }

    /** The same seam, proving a permanently invalidated key still reaches PERMANENT. */
    @Test
    fun `classifyFailure reads a permanently invalidated key as PERMANENT`() {
        val dead = InvalidKeyException("key permanently invalidated")
        assertEquals(
            CredentialEncryption.Failure.PERMANENT,
            CredentialEncryption.classifyFailure(dead) {
                "android.security.keystore.KeyPermanentlyInvalidatedException"
            },
        )
    }
}
