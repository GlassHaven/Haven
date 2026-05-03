package sh.haven.core.data.keystore

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.data.db.SshKeyDao
import sh.haven.core.data.db.entities.SshKey
import sh.haven.core.fido.SkKeyData
import sh.haven.core.security.KeyKind
import sh.haven.core.security.KeystoreFlag
import sh.haven.core.security.KeystoreStore

/**
 * Pin [SshKeySection]'s entry classification — particularly that
 * regular SSH keys and FIDO2 SK credentials end up with the right
 * [KeyKind] and the right combination of [KeystoreFlag]s. The
 * security audit screen relies on these flags to render correctly.
 */
class SshKeySectionTest {

    private fun newSection(rows: List<SshKey>): Pair<SshKeySection, SshKeyDao> {
        val dao = mockk<SshKeyDao>(relaxed = true)
        coEvery { dao.getAll() } returns rows
        for (row in rows) {
            coEvery { dao.getById(row.id) } returns row
        }
        return SshKeySection(dao) to dao
    }

    @Test
    fun `regular SSH key maps to SSH_PRIVATE with HARDWARE_BACKED only`() = runTest {
        val row = SshKey(
            id = "k1",
            label = "ed25519",
            keyType = "Ed25519",
            // Tink AEAD ciphertext shape — first byte is the version
            // marker (0x01) so isSkKeyBlob's HAVEN_SK magic check fails
            // and we land on SSH_PRIVATE.
            privateKeyBytes = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05),
            publicKeyOpenSsh = "ssh-ed25519 AAAA…",
            fingerprintSha256 = "SHA256:fp1",
            isEncrypted = false,
        )
        val (section, _) = newSection(listOf(row))
        val entries = section.enumerate()
        assertEquals(1, entries.size)
        val e = entries[0]
        assertEquals(KeyKind.SSH_PRIVATE, e.keyKind)
        assertEquals("Ed25519", e.algorithm)
        assertEquals("ssh-ed25519 AAAA…", e.publicMaterial)
        assertEquals("SHA256:fp1", e.fingerprint)
        assertEquals(setOf(KeystoreFlag.HARDWARE_BACKED), e.flags)
    }

    @Test
    fun `passphrase-protected SSH key adds REQUIRES_PASSPHRASE flag`() = runTest {
        val row = SshKey(
            id = "k2",
            label = "rsa",
            keyType = "RSA",
            privateKeyBytes = byteArrayOf(0x01, 0x02, 0x03),
            publicKeyOpenSsh = "ssh-rsa AAAA…",
            fingerprintSha256 = "SHA256:fp2",
            isEncrypted = true,
        )
        val (section, _) = newSection(listOf(row))
        val flags = section.enumerate().single().flags
        assertTrue(KeystoreFlag.REQUIRES_PASSPHRASE in flags)
        assertTrue(KeystoreFlag.HARDWARE_BACKED in flags)
    }

    @Test
    fun `FIDO SK credential maps to SSH_FIDO_SK and parses flags`() = runTest {
        val sk = SkKeyData(
            algorithmName = "sk-ssh-ed25519@openssh.com",
            publicKeyBlob = byteArrayOf(0x01, 0x02),
            application = "ssh:primary",
            credentialId = byteArrayOf(0x10, 0x20, 0x30),
            // 0x05 = USER_PRESENCE_REQUIRED (0x01) | USER_VERIFICATION_REQUIRED (0x04)
            flags = 0x05,
        )
        val row = SshKey(
            id = "fido1",
            label = "yubikey",
            keyType = "ed25519-sk",
            privateKeyBytes = SkKeyData.serialize(sk),
            publicKeyOpenSsh = "sk-ssh-ed25519@openssh.com AAAA…",
            fingerprintSha256 = "SHA256:fp-fido",
        )
        val (section, _) = newSection(listOf(row))
        val e = section.enumerate().single()
        assertEquals(KeyKind.SSH_FIDO_SK, e.keyKind)
        assertEquals("sk-ssh-ed25519@openssh.com", e.algorithm)
        assertTrue("UV flag must surface", KeystoreFlag.REQUIRES_USER_VERIFICATION in e.flags)
        assertTrue("UP flag must surface", KeystoreFlag.REQUIRES_USER_PRESENCE in e.flags)
        assertTrue(KeystoreFlag.HARDWARE_BACKED in e.flags)
        // Passphrase doesn't apply to SK creds — the security key holds
        // the signing material; there's no local passphrase to prompt for.
        assertFalse(KeystoreFlag.REQUIRES_PASSPHRASE in e.flags)
    }

    @Test
    fun `FIDO SK credential without UV does not add the UV flag`() = runTest {
        val sk = SkKeyData(
            algorithmName = "sk-ssh-ed25519@openssh.com",
            publicKeyBlob = byteArrayOf(0x01),
            application = "ssh:primary",
            credentialId = byteArrayOf(0x10),
            flags = 0x01, // user-presence only
        )
        val row = SshKey(
            id = "fido2",
            label = "yubikey-up",
            keyType = "ed25519-sk",
            privateKeyBytes = SkKeyData.serialize(sk),
            publicKeyOpenSsh = "sk-ssh-ed25519@openssh.com AAAA…",
            fingerprintSha256 = "SHA256:fp-fido2",
        )
        val (section, _) = newSection(listOf(row))
        val flags = section.enumerate().single().flags
        assertTrue(KeystoreFlag.REQUIRES_USER_PRESENCE in flags)
        assertFalse(KeystoreFlag.REQUIRES_USER_VERIFICATION in flags)
    }

    @Test
    fun `wipe deletes existing key and returns true`() = runTest {
        val row = SshKey(
            id = "k3", label = "x", keyType = "Ed25519",
            privateKeyBytes = byteArrayOf(0x01),
            publicKeyOpenSsh = "ssh-ed25519 …",
            fingerprintSha256 = "SHA256:fp3",
        )
        val (section, dao) = newSection(listOf(row))
        assertTrue(section.wipe("k3"))
        coVerify { dao.deleteById("k3") }
    }

    @Test
    fun `wipe of unknown id returns false without touching dao`() = runTest {
        val (section, dao) = newSection(emptyList())
        coEvery { dao.getById(any()) } returns null
        assertFalse(section.wipe("ghost"))
        coVerify(exactly = 0) { dao.deleteById(any()) }
    }

    @Test
    fun `section reports its store`() {
        val (section, _) = newSection(emptyList())
        assertEquals(KeystoreStore.SSH_KEYS, section.store)
    }

    @Test
    fun `entry id matches the source row id`() = runTest {
        val row = SshKey(
            id = "specific-uuid", label = "x", keyType = "Ed25519",
            privateKeyBytes = byteArrayOf(0x01),
            publicKeyOpenSsh = "ssh-ed25519 …",
            fingerprintSha256 = "SHA256:fp",
        )
        val (section, _) = newSection(listOf(row))
        assertEquals("specific-uuid", section.enumerate().single().id)
    }

    @Test
    fun `enumerate preserves dao-supplied row order`() = runTest {
        val rows = listOf("a", "b", "c").map {
            SshKey(
                id = it, label = it, keyType = "Ed25519",
                privateKeyBytes = byteArrayOf(0x01),
                publicKeyOpenSsh = "ssh-ed25519 …",
                fingerprintSha256 = "SHA256:fp-$it",
            )
        }
        val (section, _) = newSection(rows)
        assertEquals(listOf("a", "b", "c"), section.enumerate().map { it.id })
    }

    @Test
    fun `entry never carries plaintext key bytes`() = runTest {
        // Nothing in KeystoreEntry is shaped to hold raw bytes — this
        // pins the contract that auditors only ever see metadata.
        val row = SshKey(
            id = "k", label = "x", keyType = "Ed25519",
            privateKeyBytes = "PLAINTEXT_SECRET".toByteArray(),
            publicKeyOpenSsh = "ssh-ed25519 AAAA…",
            fingerprintSha256 = "SHA256:fp",
        )
        val (section, _) = newSection(listOf(row))
        val e = section.enumerate().single()
        // Walk every String-typed field and assert "PLAINTEXT_SECRET"
        // didn't slip through. Cheap insurance against a future schema
        // change that accidentally exposes the bytes.
        val haystack = listOfNotNull(
            e.id, e.label, e.algorithm, e.publicMaterial, e.fingerprint,
        ).joinToString("|")
        assertFalse("audit must not surface key bytes: $haystack",
            haystack.contains("PLAINTEXT_SECRET"))
        // No createdAt expected for a row with no value either.
        assertEquals(row.createdAt, e.createdAt)
        assertNull(e.publicMaterial?.let { if (it.contains("PLAINTEXT")) it else null })
    }
}
