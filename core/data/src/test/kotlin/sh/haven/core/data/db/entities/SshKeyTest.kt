package sh.haven.core.data.db.entities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Guards the structural-equality contract on [SshKey] (#231). The Keys
 * screen renders from a `StateFlow<List<SshKey>>` produced by `stateIn`,
 * which conflates emissions by `equals`. An id-only `equals` made a
 * renamed row compare equal to its old self, so the rename persisted to
 * the DB but the row never re-rendered. These tests pin the fix: a row
 * that differs in any user-visible field must NOT be equal to the old one.
 */
class SshKeyTest {

    private fun key(
        id: String = "key-1",
        label: String = "001",
        cert: ByteArray? = null,
        alias: String? = null,
    ) = SshKey(
        id = id,
        label = label,
        keyType = "ssh-rsa",
        privateKeyBytes = byteArrayOf(1, 2, 3),
        publicKeyOpenSsh = "ssh-rsa AAAA",
        fingerprintSha256 = "SHA256:abc",
        createdAt = 42L,
        certificateBytes = cert,
        hardwareAlias = alias,
    )

    @Test
    fun differsByLabel_notEqual() {
        val a = key(label = "001")
        val b = key(label = "renamed231")
        // The exact regression: same id, different label → must be unequal
        // so the keys StateFlow actually emits the rename.
        assertNotEquals(a, b)
        assertNotEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun identicalContent_equal() {
        // Two independent reads of the same row (fresh ByteArray instances)
        // must still compare equal so genuine no-op emissions are conflated.
        assertEquals(key(), key())
        assertEquals(key().hashCode(), key().hashCode())
    }

    @Test
    fun differsByCertificate_notEqual() {
        assertNotEquals(key(cert = null), key(cert = byteArrayOf(9, 9)))
    }

    @Test
    fun differsByHardwareAlias_notEqual() {
        // Hardware vs software is user-visible (Keys screen badge), so a
        // row flipping hardwareAlias must re-render like any other field.
        assertNotEquals(key(alias = null), key(alias = "haven_hw_key-1"))
    }
}
