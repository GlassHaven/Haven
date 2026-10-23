package sh.haven.core.ssh

import com.jcraft.jsch.Identity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import sh.haven.core.data.keystore.BiometricGate
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature

/**
 * [KeystoreSshIdentity] — the security-chip signing ceremony behind JSch
 * publickey auth. Pins four contracts:
 *
 * 1. **Wire format**: getSignature returns `string(alg) ‖ string(mpint r ‖
 *    mpint s)` (RFC 5656), the composite JSch puts straight on the wire.
 * 2. **Fail-closed** (#559): every refused/unavailable/invalidated ceremony
 *    THROWS — a null return would make JSch slide the connect to the next
 *    credential. The exceptions are RuntimeExceptions because JSch's auth
 *    loop rethrows those raw and folds checked ones into a generic
 *    "Auth fail".
 * 3. **Understandable invalidation**: a KeyPermanentlyInvalidatedException
 *    (biometric re-enrollment) or a vanished alias must tell the user to
 *    delete and regenerate, not speak Keystore.
 * 4. **Wrapper compatibility**: CertificateWrappedIdentity (CA cert path)
 *    and AgentSafeIdentity (forwarded agent path) compose with the identity.
 *
 * Hand-rolled fakes (not mockk), same convention as [AgentSafeIdentityTest]:
 * the dispatch behaviour of the Identity/Signature overloads is exactly what
 * these tests pin down.
 */
class KeystoreSshIdentityTest {

    // ---------------------------------------------------------------- fakes

    /** Records updates; hands back a fixed DER SEQUENCE{r,s}. */
    private class FakeSignature(
        private val der: ByteArray = byteArrayOf(),
        private val signFailure: Exception? = null,
    ) : Signature("SHA256withECDSA") {
        val signed = ByteArrayOutputStream()

        init {
            // Production gets a Signature already initialized by
            // HardwareSshKeyManager.initSignature; the fake marks itself
            // sign-ready so the JDK's state checks in update()/sign() pass.
            state = SIGN
        }

        override fun engineInitSign(privateKey: PrivateKey?) { }
        override fun engineInitVerify(publicKey: PublicKey?) { }
        override fun engineVerify(sigToVerify: ByteArray?): Boolean = false

        @Suppress("DEPRECATION")
        override fun engineSetParameter(param: String?, value: Any?) { }

        @Suppress("DEPRECATION")
        override fun engineGetParameter(param: String?): Any? = null

        override fun engineUpdate(b: Byte) {
            signed.write(byteArrayOf(b))
        }

        override fun engineUpdate(data: ByteArray?, off: Int, len: Int) {
            if (data != null) signed.write(data, off, len)
        }

        override fun engineSign(): ByteArray {
            signFailure?.let { throw it }
            return der
        }
    }

    /** Fake [HardwareSshKeySigner]: scripted init outcome + gate decision. */
    private class FakeSigner(
        private val signature: Signature = FakeSignature(),
        private val initFailure: Exception? = null,
        private val decision: BiometricGate.Decision = BiometricGate.Decision.ALLOW,
    ) : HardwareSshKeySigner {
        var requestedLabel: String? = null
        var requestedDetail: String? = null
        var authorizedSignature: Signature? = null
        var initCalls = 0

        override fun initSignature(alias: String): Signature {
            initCalls++
            initFailure?.let { throw it }
            return signature
        }

        override suspend fun authorize(
            label: String,
            detail: String?,
            signature: Signature,
        ): BiometricGate.Decision {
            requestedLabel = label
            requestedDetail = detail
            authorizedSignature = signature
            return decision
        }
    }

    // -------------------------------------------------------------- helpers

    private fun identity(
        signer: HardwareSshKeySigner,
        label: String? = "chip key",
        blob: ByteArray = BLOB,
    ) = KeystoreSshIdentity(
        alias = "haven-hw-1",
        publicKeyBlob = blob,
        signer = signer,
        keyLabel = label,
        fingerprintSha256 = "SHA256:abc",
    )

    /** Minimal DER INTEGER for a positive value (leading 0x00 when needed). */
    private fun derInt(v: BigInteger): ByteArray {
        val b = v.toByteArray()
        return byteArrayOf(0x02, b.size.toByte()) + b
    }

    private fun derSig(r: BigInteger, s: BigInteger): ByteArray {
        val body = derInt(r) + derInt(s)
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    private fun sshString(data: ByteArray): ByteArray =
        ByteBuffer.allocate(4 + data.size).putInt(data.size).put(data).array()

    private fun sshString(text: String): ByteArray = sshString(text.toByteArray(Charsets.US_ASCII))

    /** A real-shaped P-256 public blob: string(type) ‖ string(curve) ‖ string(Q). */
    private fun ecdsaPublicKeyBlob(type: String = HARDWARE_SSH_KEY_ALG): ByteArray {
        val point = ByteArray(65) { 0x04 } // uncompressed-point marker + fake X‖Y
        return sshString(type) + sshString("nistp256") + sshString(point)
    }

    private fun publicKeyLine(blob: ByteArray): String =
        "$HARDWARE_SSH_KEY_ALG ${java.util.Base64.getEncoder().encodeToString(blob)} chip"

    private val BLOB: ByteArray by lazy { ecdsaPublicKeyBlob() }

    // ------------------------------------------------- 1. wire-format contract

    @Test
    fun `signature blob is string alg plus string r-or-s mpints`() {
        val r = BigInteger(1, byteArrayOf(0x01, 0x02))           // high bit clear
        val s = BigInteger(1, byteArrayOf(0x80.toByte(), 0x01))  // high bit set → 0x00 pad
        val fake = FakeSignature(der = derSig(r, s))
        val id = identity(FakeSigner(signature = fake))

        val out = id.getSignature(byteArrayOf(7, 7, 7))

        val expected = sshString(HARDWARE_SSH_KEY_ALG) +
            sshString(sshString(r.toByteArray()) + sshString(s.toByteArray()))
        assertArrayEquals(expected, out)
    }

    @Test
    fun `the auth payload is exactly what gets signed`() {
        val fake = FakeSignature(der = derSig(BigInteger.TEN, BigInteger.ONE))
        val id = identity(FakeSigner(signature = fake))
        val sessionData = byteArrayOf(1, 2, 3, 4, 5)

        id.getSignature(sessionData, HARDWARE_SSH_KEY_ALG)

        assertArrayEquals(sessionData, fake.signed.toByteArray())
    }

    // Signature blob is checked separately; this test only cares that the
    // ceremony request reached the gate with the right label/detail.
    @Test
    fun `prompt carries the key label and fingerprint`() {
        val signer = FakeSigner(signature = FakeSignature(der = derSig(BigInteger.TEN, BigInteger.ONE)))
        identity(signer).getSignature(byteArrayOf(1))
        assertEquals("Sign with chip key", signer.requestedLabel)
        assertEquals("SHA256:abc", signer.requestedDetail)
    }

    // ---------------------------------------------------- 2. fail-closed (#559)

    @Test
    fun `a denied ceremony throws instead of returning null`() {
        val id = identity(FakeSigner(decision = BiometricGate.Decision.DENY))
        try {
            id.getSignature(byteArrayOf(1))
            fail("a declined prompt must abort the connect, not return null")
        } catch (e: HardwareKeySignDeclinedException) {
            assertTrue(e.message!!.contains("declined"))
        }
    }

    @Test
    fun `an unavailable ceremony (no foreground) throws`() {
        val id = identity(FakeSigner(decision = BiometricGate.Decision.UNAVAILABLE))
        try {
            id.getSignature(byteArrayOf(1))
            fail("no foreground host must abort the connect")
        } catch (e: HardwareKeySignDeclinedException) {
            assertTrue(e.message!!.contains("could not be shown"))
        }
    }

    @Test
    fun `a sign-time UserNotAuthenticatedException fails closed as a decline`() {
        val fake = FakeSignature(
            der = derSig(BigInteger.TEN, BigInteger.ONE),
            signFailure = android.security.keystore.UserNotAuthenticatedException(),
        )
        val id = identity(FakeSigner(signature = fake))
        try {
            id.getSignature(byteArrayOf(1))
            fail("an unauthorized sign must not produce a signature")
        } catch (e: HardwareKeySignDeclinedException) {
            // expected: refused, not corrupted
        }
    }

    // ------------------------------------------- 3. understandable invalidation

    @Test
    fun `biometric re-enrollment invalidation tells the user to regenerate`() {
        val id = identity(
            FakeSigner(initFailure = android.security.keystore.KeyPermanentlyInvalidatedException()),
        )
        try {
            id.getSignature(byteArrayOf(1))
            fail("an invalidated key must never sign")
        } catch (e: HardwareKeyInvalidatedException) {
            assertTrue(e.message!!.contains("invalidated"))
            assertTrue(e.message!!.contains("generate"))
        }
    }

    @Test
    fun `a vanished alias (restored backup row) tells the user to regenerate`() {
        val id = identity(FakeSigner(initFailure = IllegalArgumentException("No hardware SSH key")))
        try {
            id.getSignature(byteArrayOf(1))
            fail("a missing chip key must never sign")
        } catch (e: HardwareKeyInvalidatedException) {
            assertTrue(e.message!!.contains("no longer present"))
        }
    }

    // The three remaining checked failures HardwareSshKeyManager.initSignature
    // can surface (KeyStore.getKey / Signature.initSign): JSch's Session.connect
    // folds any checked exception into a generic "Auth fail" JSchException, so
    // a leaked one neither matches isKeyRefusal nor tells the user anything —
    // the connect would slide on to the password fallback (#559 shape).
    @Test
    fun `keystore hand-out failures (InvalidKey, Unrecoverable, KeyStore) translate to invalidated`() {
        val failures = listOf(
            java.security.InvalidKeyException("key corrupted"),
            java.security.UnrecoverableKeyException("key locked"),
            java.security.KeyStoreException("keystore unavailable"),
        )
        for (failure in failures) {
            val id = identity(FakeSigner(initFailure = failure))
            try {
                id.getSignature(byteArrayOf(1))
                fail("${failure.javaClass.simpleName} must not escape raw")
            } catch (e: HardwareKeyInvalidatedException) {
                assertTrue(e.message!!.contains("generate"))
            }
        }
    }

    @Test
    fun `a sign-time SignatureException fails closed as a decline`() {
        val fake = FakeSignature(
            der = derSig(BigInteger.TEN, BigInteger.ONE),
            signFailure = java.security.SignatureException("not initialized"),
        )
        val id = identity(FakeSigner(signature = fake))
        try {
            id.getSignature(byteArrayOf(1))
            fail("a broken signature engine must not produce a signature")
        } catch (e: HardwareKeySignDeclinedException) {
            // expected: refused, not corrupted
        }
    }

    // Same #559 shape at sign time: a binder-level keystore failure is
    // checked, so JSch would fold a leaked one into "Auth fail" and the
    // connect would slide to the password fallback.
    @Test
    fun `a sign-time KeyStoreException fails closed as a decline`() {
        val fake = FakeSignature(
            der = derSig(BigInteger.TEN, BigInteger.ONE),
            signFailure = java.security.KeyStoreException("keystore busy"),
        )
        val id = identity(FakeSigner(signature = fake))
        try {
            id.getSignature(byteArrayOf(1))
            fail("a keystore failure mid-sign must not produce a signature")
        } catch (e: HardwareKeySignDeclinedException) {
            // expected: refused, not corrupted
        }
    }

    // ------------------------------------------- 4. wrapper compatibility

    @Test
    fun `CertificateWrappedIdentity strips the cert suffix and signs with the bare alg`() {
        val fake = FakeSignature(der = derSig(BigInteger.TEN, BigInteger.ONE))
        val wrapped = CertificateWrappedIdentity(
            identity(FakeSigner(signature = fake)),
            certBlob = byteArrayOf(9, 9, 9),
            certKeyType = SshCertificateParser.getCertKeyType(HARDWARE_SSH_KEY_ALG),
        )

        val out = wrapped.getSignature(byteArrayOf(1), "ecdsa-sha2-nistp256-cert-v01@openssh.com")

        // Advertisement is the cert type; the signature names the bare alg.
        assertEquals("ecdsa-sha2-nistp256-cert-v01@openssh.com", wrapped.algName)
        assertArrayEquals(byteArrayOf(9, 9, 9), wrapped.publicKeyBlob)
        val outSshString = sshString(HARDWARE_SSH_KEY_ALG)
        assertTrue(
            "inner signature must open with the bare alg string",
            out.copyOfRange(0, outSshString.size).contentEquals(outSshString),
        )
    }

    @Test
    fun `AgentSafeIdentity converts a declined chip ceremony to null for the agent path`() {
        val declined = identity(FakeSigner(decision = BiometricGate.Decision.DENY))
        val safe = AgentSafeIdentity(declined)
        assertNull(safe.getSignature(byteArrayOf(1)))
        assertNull(safe.getSignature(byteArrayOf(1), HARDWARE_SSH_KEY_ALG))
    }

    // ------------------------------------------- 5. Identity surface

    @Test
    fun `identity surface is unencrypted with the stored blob`() {
        val id = identity(FakeSigner())
        assertEquals(HARDWARE_SSH_KEY_ALG, id.algName)
        assertArrayEquals(BLOB, id.publicKeyBlob)
        assertFalse(id.isEncrypted)
        assertTrue(id.setPassphrase(null))
        assertTrue(id.decrypt())
        id.clear() // must not throw
    }

    // ------------------------------------------- 6. public line parsing

    @Test
    fun `parseHardwarePublicKeyBlob decodes the stored public line`() {
        val blob = ecdsaPublicKeyBlob()
        assertArrayEquals(blob, parseHardwarePublicKeyBlob(publicKeyLine(blob)))
        // comment variants and stray whitespace are tolerated
        assertArrayEquals(
            blob,
            parseHardwarePublicKeyBlob("  $HARDWARE_SSH_KEY_ALG  ${java.util.Base64.getEncoder().encodeToString(blob)}  "),
        )
    }

    @Test
    fun `parseHardwarePublicKeyBlob rejects a line of another key type`() {
        try {
            parseHardwarePublicKeyBlob("ssh-ed25519 AAAAchip chip")
            fail("a non-ecdsa line must be refused at add time")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("ssh-ed25519"))
        }
    }

    @Test
    fun `parseHardwarePublicKeyBlob rejects malformed base64`() {
        try {
            parseHardwarePublicKeyBlob("$HARDWARE_SSH_KEY_ALG !!!not-base64!!! chip")
            fail("garbage must not reach JSch")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `parseHardwarePublicKeyBlob rejects a blob whose inner type is wrong`() {
        // Base64 of a valid ssh-string prefix that is NOT the P-256 type.
        val wrongInner = sshString("ecdsa-sha2-nistp384") + sshString("nistp384") + sshString(ByteArray(97))
        val line = "$HARDWARE_SSH_KEY_ALG ${java.util.Base64.getEncoder().encodeToString(wrongInner)}"
        try {
            parseHardwarePublicKeyBlob(line)
            fail("the line type and blob type must agree")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `parseHardwarePublicKeyBlob rejects a line with no base64 field`() {
        try {
            parseHardwarePublicKeyBlob(HARDWARE_SSH_KEY_ALG)
            fail("a type-only line is not a key")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
