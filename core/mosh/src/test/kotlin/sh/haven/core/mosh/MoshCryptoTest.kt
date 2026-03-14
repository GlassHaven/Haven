package sh.haven.core.mosh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Test
import sh.haven.core.mosh.crypto.MoshCrypto
import java.util.Base64

class MoshCryptoTest {

    private fun randomKey(): String {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    @Test
    fun `encrypt decrypt roundtrip`() {
        val key = randomKey()
        val crypto = MoshCrypto(key)
        val plaintext = "Hello, mosh!".toByteArray()
        val nonce = MoshCrypto.DIRECTION_TO_SERVER or 42L

        val packet = crypto.encrypt(nonce, plaintext)
        val (decNonce, decPlaintext) = crypto.decrypt(packet)

        assertEquals(nonce, decNonce)
        assertArrayEquals(plaintext, decPlaintext)
    }

    @Test
    fun `different nonces produce different ciphertext`() {
        val key = randomKey()
        val crypto = MoshCrypto(key)
        val plaintext = "test".toByteArray()

        val packet1 = crypto.encrypt(1L, plaintext)
        val packet2 = crypto.encrypt(2L, plaintext)

        assertNotEquals(packet1.toList(), packet2.toList())
    }

    @Test
    fun `tampered ciphertext fails authentication`() {
        val key = randomKey()
        val crypto = MoshCrypto(key)
        val packet = crypto.encrypt(1L, "secret".toByteArray())

        // Flip a bit in the ciphertext
        val tampered = packet.copyOf()
        tampered[MoshCrypto.WIRE_NONCE_LEN + 2] =
            (tampered[MoshCrypto.WIRE_NONCE_LEN + 2].toInt() xor 0x01).toByte()

        try {
            crypto.decrypt(tampered)
            fail("Should have thrown on tampered ciphertext")
        } catch (_: Exception) {
            // expected
        }
    }

    @Test
    fun `wrong key fails authentication`() {
        val key1 = randomKey()
        val key2 = randomKey()
        val crypto1 = MoshCrypto(key1)
        val crypto2 = MoshCrypto(key2)

        val packet = crypto1.encrypt(1L, "secret".toByteArray())

        try {
            crypto2.decrypt(packet)
            fail("Should have thrown with wrong key")
        } catch (_: Exception) {
            // expected
        }
    }

    @Test
    fun `nonce encodes direction bit correctly`() {
        val key = randomKey()
        val crypto = MoshCrypto(key)

        val clientNonce = MoshCrypto.DIRECTION_TO_SERVER or 5L
        val serverNonce = MoshCrypto.DIRECTION_TO_CLIENT or 5L

        // TO_SERVER (client sends): high bit clear
        assertEquals(0L, (clientNonce ushr 63) and 1L)
        // TO_CLIENT (server sends): high bit set
        assertEquals(1L, (serverNonce ushr 63) and 1L)

        // Both should encrypt/decrypt successfully
        val p1 = crypto.encrypt(clientNonce, "a".toByteArray())
        val p2 = crypto.encrypt(serverNonce, "b".toByteArray())

        val (n1, d1) = crypto.decrypt(p1)
        val (n2, d2) = crypto.decrypt(p2)

        assertEquals(clientNonce, n1)
        assertEquals(serverNonce, n2)
        assertArrayEquals("a".toByteArray(), d1)
        assertArrayEquals("b".toByteArray(), d2)
    }

    @Test
    fun `empty plaintext roundtrip`() {
        val key = randomKey()
        val crypto = MoshCrypto(key)
        val packet = crypto.encrypt(0L, ByteArray(0))
        val (_, decrypted) = crypto.decrypt(packet)
        assertEquals(0, decrypted.size)
    }

    @Test
    fun `key without base64 padding accepted`() {
        // 16 bytes = 22 base64 chars without padding
        val keyBytes = ByteArray(16) { it.toByte() }
        val noPad = Base64.getEncoder().withoutPadding().encodeToString(keyBytes)
        assertEquals(22, noPad.length)

        val crypto = MoshCrypto(noPad)
        val packet = crypto.encrypt(1L, "test".toByteArray())
        val (_, dec) = crypto.decrypt(packet)
        assertArrayEquals("test".toByteArray(), dec)
    }
}
