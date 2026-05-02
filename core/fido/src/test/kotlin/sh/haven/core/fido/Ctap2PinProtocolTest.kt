package sh.haven.core.fido

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey

class Ctap2PinProtocolTest {

    @Test
    fun `v2 ecdh round-trip yields equal shared secret on both sides`() {
        // Two parties each generate ephemeral P-256 keys and run ECDH with
        // the v2 KDF; both must arrive at the same 64-byte shared secret.
        val alice = Ctap2PinProtocol.V2.generateEphemeralKeyPair()
        val bob = Ctap2PinProtocol.V2.generateEphemeralKeyPair()

        val zAlice = Ctap2PinProtocol.V2.ecdh(
            alice.private as java.security.interfaces.ECPrivateKey,
            bob.public as ECPublicKey,
        )
        val zBob = Ctap2PinProtocol.V2.ecdh(
            bob.private as java.security.interfaces.ECPrivateKey,
            alice.public as ECPublicKey,
        )
        assertArrayEquals("ECDH symmetry", zAlice, zBob)

        val ssAlice = Ctap2PinProtocol.V2.deriveSharedSecret(zAlice)
        val ssBob = Ctap2PinProtocol.V2.deriveSharedSecret(zBob)
        assertEquals(64, ssAlice.size)
        assertArrayEquals("v2 shared secret symmetry", ssAlice, ssBob)
    }

    @Test
    fun `v2 encrypt-decrypt round-trips a 16-byte pin hash`() {
        val keyA = Ctap2PinProtocol.V2.generateEphemeralKeyPair()
        val keyB = Ctap2PinProtocol.V2.generateEphemeralKeyPair()
        val z = Ctap2PinProtocol.V2.ecdh(
            keyA.private as java.security.interfaces.ECPrivateKey,
            keyB.public as ECPublicKey,
        )
        val ss = Ctap2PinProtocol.V2.deriveSharedSecret(z)

        val pinHash = MessageDigest.getInstance("SHA-256")
            .digest("hunter2".toByteArray()).copyOfRange(0, 16)

        val ct = Ctap2PinProtocol.V2.encrypt(ss, pinHash)
        // v2 prepends a 16-byte IV
        assertEquals(32, ct.size)
        val pt = Ctap2PinProtocol.V2.decrypt(ss, ct)
        assertArrayEquals(pinHash, pt)
    }

    @Test
    fun `v2 encrypt produces fresh IV across calls`() {
        val keyA = Ctap2PinProtocol.V2.generateEphemeralKeyPair()
        val keyB = Ctap2PinProtocol.V2.generateEphemeralKeyPair()
        val z = Ctap2PinProtocol.V2.ecdh(
            keyA.private as java.security.interfaces.ECPrivateKey,
            keyB.public as ECPublicKey,
        )
        val ss = Ctap2PinProtocol.V2.deriveSharedSecret(z)

        val pt = ByteArray(16)
        val ct1 = Ctap2PinProtocol.V2.encrypt(ss, pt)
        val ct2 = Ctap2PinProtocol.V2.encrypt(ss, pt)
        // Same plaintext + key, different random IVs → different ciphertext blobs
        assertNotEquals(ct1.toList(), ct2.toList())
    }

    @Test
    fun `v2 hmac matches across both parties`() {
        val keyA = Ctap2PinProtocol.V2.generateEphemeralKeyPair()
        val keyB = Ctap2PinProtocol.V2.generateEphemeralKeyPair()
        val z = Ctap2PinProtocol.V2.ecdh(
            keyA.private as java.security.interfaces.ECPrivateKey,
            keyB.public as ECPublicKey,
        )
        val ss = Ctap2PinProtocol.V2.deriveSharedSecret(z)
        val msg = "clientDataHash".toByteArray()
        val a = Ctap2PinProtocol.V2.authenticate(ss, msg)
        val b = Ctap2PinProtocol.V2.authenticate(ss, msg)
        assertEquals(32, a.size)
        assertArrayEquals(a, b)
    }

    @Test
    fun `v1 round-trip pin hash with zero-iv aes`() {
        val keyA = Ctap2PinProtocol.V1.generateEphemeralKeyPair()
        val keyB = Ctap2PinProtocol.V1.generateEphemeralKeyPair()
        val z = Ctap2PinProtocol.V1.ecdh(
            keyA.private as java.security.interfaces.ECPrivateKey,
            keyB.public as ECPublicKey,
        )
        val ss = Ctap2PinProtocol.V1.deriveSharedSecret(z)
        assertEquals(32, ss.size)

        val pinHash = MessageDigest.getInstance("SHA-256")
            .digest("password".toByteArray()).copyOfRange(0, 16)
        val ct = Ctap2PinProtocol.V1.encrypt(ss, pinHash)
        // v1 has no IV prefix; one AES block
        assertEquals(16, ct.size)
        assertArrayEquals(pinHash, Ctap2PinProtocol.V1.decrypt(ss, ct))
    }

    @Test
    fun `v1 hmac is truncated to 16 bytes`() {
        val ss = ByteArray(32) { it.toByte() }
        val tag = Ctap2PinProtocol.V1.authenticate(ss, ByteArray(32))
        assertEquals(16, tag.size)
    }

    @Test
    fun `protocol pick prefers v2 when both supported`() {
        assertEquals(Ctap2PinProtocol.V2, Ctap2PinProtocol.pick(listOf(1, 2)))
        assertEquals(Ctap2PinProtocol.V2, Ctap2PinProtocol.pick(listOf(2)))
        assertEquals(Ctap2PinProtocol.V1, Ctap2PinProtocol.pick(listOf(1)))
        // Empty list — some keys omit the field but support v2
        assertEquals(Ctap2PinProtocol.V2, Ctap2PinProtocol.pick(emptyList()))
    }

    @Test
    fun `cose key round-trip preserves coords`() {
        val pair = Ctap2PinProtocol.V2.generateEphemeralKeyPair()
        val pub = pair.public as ECPublicKey
        val (x, y) = Ctap2PinProtocol.V2.ecPublicToCoseCoords(pub)
        assertEquals(32, x.size)
        assertEquals(32, y.size)

        // Reconstruct from coords and confirm ECDH still works
        val reconstructed = Ctap2PinProtocol.V2.coseKeyToEcPublic(x, y)
        val other = Ctap2PinProtocol.V2.generateEphemeralKeyPair()
        val zViaOriginal = Ctap2PinProtocol.V2.ecdh(
            other.private as java.security.interfaces.ECPrivateKey,
            pub,
        )
        val zViaReconstructed = Ctap2PinProtocol.V2.ecdh(
            other.private as java.security.interfaces.ECPrivateKey,
            reconstructed,
        )
        assertArrayEquals(zViaOriginal, zViaReconstructed)
    }
}
