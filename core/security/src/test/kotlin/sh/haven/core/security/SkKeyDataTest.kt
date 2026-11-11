package sh.haven.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkKeyDataTest {

    @Test
    fun `serialize and deserialize round-trip preserves all fields`() {
        val original = SkKeyData(
            algorithmName = "sk-ssh-ed25519@openssh.com",
            publicKeyBlob = byteArrayOf(1, 2, 3, 4),
            application = "ssh:prod",
            credentialId = byteArrayOf(9, 8, 7, 6, 5),
            flags = 0x05,
        )

        val encoded = SkKeyData.serialize(original)
        assertTrue(SkKeyData.isSkKeyBlob(encoded))

        val decoded = SkKeyData.deserialize(encoded)
        assertEquals(original, decoded)
        assertEquals(original.hashCode(), decoded.hashCode())
    }

    @Test
    fun `isSkKeyBlob rejects empty and non-SK payloads`() {
        assertFalse(SkKeyData.isSkKeyBlob(byteArrayOf()))
        assertFalse(SkKeyData.isSkKeyBlob("-----BEGIN OPENSSH PRIVATE KEY-----".toByteArray()))
    }
}
