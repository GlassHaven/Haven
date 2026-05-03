package sh.haven.core.data.keystore

import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
import sh.haven.core.security.KeystoreFetch
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
        // No biometric tests in this overload — pass a gate with
        // foregroundActive=false so any accidental BIOMETRIC_PROTECTED
        // fetch short-circuits to UNAVAILABLE, surfaced as a Failed
        // result. Tests that exercise the gate use [newSectionWithGate].
        // KeyEncryption.decrypt is only reached on the encrypted-bytes
        // branch of fetch(); the tests below stick to legacy plaintext
        // and FIDO SK blobs so the Context never gets used. Mock it
        // anyway to satisfy the Hilt-shaped constructor.
        return SshKeySection(dao, mockk<Context>(relaxed = true), BiometricGate()) to dao
    }

    /** Variant that exposes the [BiometricGate] for tests that need to drive its decision. */
    private fun newSectionWithGate(
        rows: List<SshKey>,
        gate: BiometricGate,
    ): Pair<SshKeySection, SshKeyDao> {
        val dao = mockk<SshKeyDao>(relaxed = true)
        coEvery { dao.getAll() } returns rows
        for (row in rows) {
            coEvery { dao.getById(row.id) } returns row
        }
        coEvery { dao.upsert(any()) } returns Unit
        return SshKeySection(dao, mockk<Context>(relaxed = true), gate) to dao
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
    fun `fetch returns legacy plaintext bytes unchanged`() = runTest {
        // Bytes that don't start with the SK magic AND don't look like
        // Tink AEAD ciphertext (first byte 0x2D '-' hits the PEM
        // shortcut in KeyEncryption.isEncrypted, falling through to
        // legacy-passthrough).
        val pemPrefix = "-----BEGIN OPENSSH PRIVATE KEY-----\n…".toByteArray()
        val row = SshKey(
            id = "k-legacy", label = "legacy",
            keyType = "Ed25519",
            privateKeyBytes = pemPrefix,
            publicKeyOpenSsh = "ssh-ed25519 …",
            fingerprintSha256 = "SHA256:legacy",
        )
        val (section, _) = newSection(listOf(row))
        val result = section.fetch("k-legacy")
        assertTrue("expected Bytes, got: $result", result is KeystoreFetch.Bytes)
        assertTrue((result as KeystoreFetch.Bytes).data.contentEquals(pemPrefix))
    }

    @Test
    fun `fetch on FIDO SK returns the descriptor bytes unchanged`() = runTest {
        // SK blobs aren't encrypted — the descriptor (credentialId +
        // public key) is fetched verbatim. The signing material lives
        // on the security key, never here.
        val sk = SkKeyData(
            algorithmName = "sk-ssh-ed25519@openssh.com",
            publicKeyBlob = byteArrayOf(0x01, 0x02),
            application = "ssh:primary",
            credentialId = byteArrayOf(0x10, 0x20),
            flags = 0x05,
        )
        val skBytes = SkKeyData.serialize(sk)
        val row = SshKey(
            id = "fido-fetch", label = "yubikey",
            keyType = "ed25519-sk",
            privateKeyBytes = skBytes,
            publicKeyOpenSsh = "sk-ssh-ed25519@openssh.com …",
            fingerprintSha256 = "SHA256:sk",
        )
        val (section, _) = newSection(listOf(row))
        val result = section.fetch("fido-fetch")
        assertTrue("expected Bytes, got: $result", result is KeystoreFetch.Bytes)
        assertTrue((result as KeystoreFetch.Bytes).data.contentEquals(skBytes))
    }

    @Test
    fun `fetch on missing entry returns NotFound`() = runTest {
        val (section, dao) = newSection(emptyList())
        coEvery { dao.getById(any()) } returns null
        assertEquals(KeystoreFetch.NotFound, section.fetch("ghost"))
    }

    @Test
    fun `biometricProtected column surfaces BIOMETRIC_PROTECTED flag`() = runTest {
        val row = SshKey(
            id = "k-bio", label = "high-security",
            keyType = "Ed25519",
            privateKeyBytes = "-----BEGIN OPENSSH PRIVATE KEY-----".toByteArray(),
            publicKeyOpenSsh = "ssh-ed25519 …",
            fingerprintSha256 = "SHA256:bio",
            biometricProtected = true,
        )
        val (section, _) = newSection(listOf(row))
        val flags = section.enumerate().single().flags
        assertTrue("BIOMETRIC_PROTECTED must surface", KeystoreFlag.BIOMETRIC_PROTECTED in flags)
    }

    @Test
    fun `setBiometricProtected upserts the row with the new flag`() = runTest {
        val row = SshKey(
            id = "k-toggle", label = "toggle",
            keyType = "Ed25519",
            privateKeyBytes = "-----BEGIN".toByteArray(),
            publicKeyOpenSsh = "ssh-ed25519 …",
            fingerprintSha256 = "SHA256:toggle",
            biometricProtected = false,
        )
        val (section, dao) = newSectionWithGate(listOf(row), BiometricGate())
        val captured = slot<SshKey>()
        coEvery { dao.upsert(capture(captured)) } returns Unit

        assertTrue(section.setBiometricProtected("k-toggle", true))
        assertEquals(true, captured.captured.biometricProtected)
    }

    @Test
    fun `setBiometricProtected returns false on no-op write`() = runTest {
        val row = SshKey(
            id = "k-already", label = "already",
            keyType = "Ed25519",
            privateKeyBytes = "-----BEGIN".toByteArray(),
            publicKeyOpenSsh = "ssh-ed25519 …",
            fingerprintSha256 = "SHA256:al",
            biometricProtected = true,
        )
        val (section, dao) = newSectionWithGate(listOf(row), BiometricGate())
        assertFalse(section.setBiometricProtected("k-already", true))
        coVerify(exactly = 0) { dao.upsert(any()) }
    }

    @Test
    fun `setBiometricProtected on unknown id returns false`() = runTest {
        val (section, dao) = newSectionWithGate(emptyList(), BiometricGate())
        coEvery { dao.getById(any()) } returns null
        assertFalse(section.setBiometricProtected("ghost", true))
    }

    @Test
    fun `fetch on biometric-protected key with no foreground returns Failed`() = runTest {
        val row = SshKey(
            id = "k-bio-fail", label = "off",
            keyType = "Ed25519",
            privateKeyBytes = "-----BEGIN OPENSSH PRIVATE KEY-----".toByteArray(),
            publicKeyOpenSsh = "ssh-ed25519 …",
            fingerprintSha256 = "SHA256:bio-fail",
            biometricProtected = true,
        )
        // Gate is foreground-inactive — request() short-circuits to
        // UNAVAILABLE, which the section maps to Failed with a
        // biometric-required reason. Pins the fail-closed contract.
        val gate = BiometricGate() // foregroundActive=false by default
        val (section, _) = newSectionWithGate(listOf(row), gate)
        val result = section.fetch("k-bio-fail")
        assertTrue("expected Failed, got: $result", result is KeystoreFetch.Failed)
        assertTrue(
            "reason must mention biometric, got: ${(result as KeystoreFetch.Failed).reason}",
            result.reason.contains("biometric", ignoreCase = true) ||
                result.reason.contains("authentication", ignoreCase = true),
        )
    }

    @Test
    fun `fetch on biometric-protected key with allowed prompt returns Bytes`() = runTest {
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\n…".toByteArray()
        val row = SshKey(
            id = "k-bio-ok", label = "ok",
            keyType = "Ed25519",
            privateKeyBytes = pem,
            publicKeyOpenSsh = "ssh-ed25519 …",
            fingerprintSha256 = "SHA256:bio-ok",
            biometricProtected = true,
        )
        val gate = BiometricGate().apply { setForegroundActive(true) }
        val (section, _) = newSectionWithGate(listOf(row), gate)

        // Race: section.fetch suspends inside gate.request waiting for
        // a respond. Auto-respond ALLOW as soon as the request lands;
        // the launched collector runs concurrently with the fetch.
        val result = coroutineScope {
            val responder = launch {
                gate.pending.collect { pending ->
                    pending.firstOrNull()?.let { gate.respond(it.id, BiometricGate.Decision.ALLOW) }
                }
            }
            try {
                section.fetch("k-bio-ok")
            } finally {
                responder.cancel()
            }
        }
        assertTrue("expected Bytes, got: $result", result is KeystoreFetch.Bytes)
        assertTrue((result as KeystoreFetch.Bytes).data.contentEquals(pem))
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
