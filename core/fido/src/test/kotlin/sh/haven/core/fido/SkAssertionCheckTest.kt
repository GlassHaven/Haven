package sh.haven.core.fido

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #531. A reporter's `sk-ecdsa-sha2-nistp256@openssh.com` key was touched, a
 * signature was produced, and the server answered "publickey,password" — the
 * same thing it says for a key it has never heard of. The one fact that would
 * have separated the two, whether the assertion carried the user-verified bit,
 * was only ever in logcat.
 */
class SkAssertionCheckTest {

    private val touchOnly: Byte = 0x01
    private val touchAndPin: Byte = 0x05

    @Test
    fun `asking for verification and not getting it is reported`() {
        val msg = skAssertionUvMismatch(requestedUv = true, assertionFlags = touchOnly)
        assertNotNull("a UV-less assertion for a verify-required key must be caught", msg)
        assertTrue("should name the flags it saw: $msg", msg!!.contains("0x01"))
        assertTrue("should say the server will reject it: $msg", msg.contains("verify-required"))
    }

    @Test
    fun `asking for verification and getting it passes`() {
        assertNull(skAssertionUvMismatch(requestedUv = true, assertionFlags = touchAndPin))
    }

    @Test
    fun `a touch-only key is not held to a bit it never asked for`() {
        assertNull(skAssertionUvMismatch(requestedUv = false, assertionFlags = touchOnly))
    }

    @Test
    fun `a touch-only key that verifies anyway is not an error`() {
        assertNull(skAssertionUvMismatch(requestedUv = false, assertionFlags = touchAndPin))
    }

    /**
     * Some authenticators set attested-credential-data (0x40) or extension-data
     * (0x80) alongside the presence bits, which makes the byte negative in
     * Kotlin. The check must look at the bit, not the sign.
     */
    @Test
    fun `high bits set alongside user verification still count as verified`() {
        assertNull(
            skAssertionUvMismatch(requestedUv = true, assertionFlags = 0x85.toByte()),
        )
    }

    @Test
    fun `high bits set without user verification are still a mismatch`() {
        assertNotNull(
            skAssertionUvMismatch(requestedUv = true, assertionFlags = 0x81.toByte()),
        )
    }
}
