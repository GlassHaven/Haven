package sh.haven.core.data.keystore

import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.data.db.ConnectionDao
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.security.KeyKind
import sh.haven.core.security.KeystoreFetch
import sh.haven.core.security.KeystoreFlag
import sh.haven.core.security.KeystoreStore

/**
 * Pin [ProfileCredentialSection]'s shape: one entry per non-null
 * password column, legacy plaintext drops the HARDWARE_BACKED flag,
 * wipe clears a single column without touching the surrounding
 * profile.
 */
class ProfileCredentialSectionTest {

    private fun profile(
        id: String = "p1",
        label: String = "test-host",
        sshPassword: String? = null,
        vncPassword: String? = null,
        rdpPassword: String? = null,
        smbPassword: String? = null,
    ) = ConnectionProfile(
        id = id, label = label, host = "10.0.0.1", username = "u",
        sshPassword = sshPassword,
        vncPassword = vncPassword,
        rdpPassword = rdpPassword,
        smbPassword = smbPassword,
    )

    private fun newSection(profiles: List<ConnectionProfile>): Pair<ProfileCredentialSection, ConnectionDao> {
        val dao = mockk<ConnectionDao>(relaxed = true)
        coEvery { dao.getAll() } returns profiles
        for (p in profiles) coEvery { dao.getById(p.id) } returns p
        return ProfileCredentialSection(dao, mockk<Context>(relaxed = true)) to dao
    }

    @Test
    fun `enumerate emits one entry per non-null password field`() = runTest {
        val p = profile(
            sshPassword = "ENC:x",
            vncPassword = "ENC:y",
            // rdpPassword null → skipped
            smbPassword = "ENC:z",
        )
        val (section, _) = newSection(listOf(p))
        val ids = section.enumerate().map { it.id }
        assertEquals(setOf("p1/sshPassword", "p1/vncPassword", "p1/smbPassword"), ids.toSet())
    }

    @Test
    fun `empty password string is treated as absent`() = runTest {
        val p = profile(sshPassword = "")
        val (section, _) = newSection(listOf(p))
        assertTrue(section.enumerate().isEmpty())
    }

    @Test
    fun `encrypted password carries HARDWARE_BACKED flag and AES algorithm`() = runTest {
        val p = profile(sshPassword = "ENC:secret-cipher-text")
        val (section, _) = newSection(listOf(p))
        val e = section.enumerate().single()
        assertEquals(KeyKind.PROFILE_PASSWORD, e.keyKind)
        assertEquals("AES-256-GCM", e.algorithm)
        assertTrue(KeystoreFlag.HARDWARE_BACKED in e.flags)
    }

    @Test
    fun `legacy plaintext password drops HARDWARE_BACKED flag`() = runTest {
        // Migration to ENC: prefix happens lazily in ConnectionRepository's
        // init block; the audit screen must surface still-plaintext rows
        // distinctly so the user can spot any that haven't been read yet.
        val p = profile(sshPassword = "plaintext-not-yet-migrated")
        val (section, _) = newSection(listOf(p))
        val e = section.enumerate().single()
        assertFalse("plaintext must not claim HARDWARE_BACKED", KeystoreFlag.HARDWARE_BACKED in e.flags)
        assertTrue("algorithm must reveal the legacy state", e.algorithm.contains("plaintext", ignoreCase = true))
    }

    @Test
    fun `entry label combines profile label and field name`() = runTest {
        val p = profile(label = "prod-server", sshPassword = "ENC:x")
        val (section, _) = newSection(listOf(p))
        val label = section.enumerate().single().label
        assertTrue("got: $label", label.contains("prod-server"))
        assertTrue("got: $label", label.contains("SSH"))
    }

    @Test
    fun `wipe clears one field and upserts the rest unchanged`() = runTest {
        val p = profile(
            sshPassword = "ENC:keep-me",
            vncPassword = "ENC:remove-me",
            rdpPassword = "ENC:keep-me-too",
        )
        val (section, dao) = newSection(listOf(p))
        val captured = slot<ConnectionProfile>()
        coEvery { dao.upsert(capture(captured)) } returns Unit

        assertTrue(section.wipe("p1/vncPassword"))
        coVerify { dao.upsert(any()) }

        val saved = captured.captured
        assertEquals("ENC:keep-me", saved.sshPassword)
        assertEquals(null, saved.vncPassword)
        assertEquals("ENC:keep-me-too", saved.rdpPassword)
    }

    @Test
    fun `wipe of unknown profile returns false without dao writes`() = runTest {
        val (section, dao) = newSection(emptyList())
        coEvery { dao.getById(any()) } returns null
        assertFalse(section.wipe("nope/sshPassword"))
        coVerify(exactly = 0) { dao.upsert(any()) }
    }

    @Test
    fun `wipe of unknown field on a real profile returns false`() = runTest {
        val p = profile(sshPassword = "ENC:x")
        val (section, dao) = newSection(listOf(p))
        assertFalse(section.wipe("p1/notARealField"))
        coVerify(exactly = 0) { dao.upsert(any()) }
    }

    @Test
    fun `wipe with malformed entryId returns false`() = runTest {
        val (section, dao) = newSection(emptyList())
        assertFalse(section.wipe("no-slash-here"))
        coVerify(exactly = 0) { dao.upsert(any()) }
    }

    @Test
    fun `wipe of an already-null field returns false`() = runTest {
        // The contract is "did we change anything?" — wiping a column
        // that's already null is a no-op, so return false. Keeps the
        // audit log honest about whether action was actually taken.
        val p = profile(sshPassword = "ENC:x") // vncPassword is null
        val (section, dao) = newSection(listOf(p))
        assertFalse(section.wipe("p1/vncPassword"))
        coVerify(exactly = 0) { dao.upsert(any()) }
    }

    @Test
    fun `fetch on legacy plaintext password returns the value unchanged`() = runTest {
        // Legacy plaintext (no ENC: prefix) bypasses CredentialEncryption
        // entirely. Tests cover the migration-incomplete state without
        // needing the Android Keystore to spin up Tink.
        val p = profile(sshPassword = "legacy-plaintext")
        val (section, _) = newSection(listOf(p))
        val result = section.fetch("p1/sshPassword")
        assertTrue("expected Password, got: $result", result is KeystoreFetch.Password)
        assertEquals("legacy-plaintext", (result as KeystoreFetch.Password).value)
    }

    @Test
    fun `fetch on missing profile returns NotFound`() = runTest {
        val (section, dao) = newSection(emptyList())
        coEvery { dao.getById(any()) } returns null
        assertEquals(KeystoreFetch.NotFound, section.fetch("ghost/sshPassword"))
    }

    @Test
    fun `fetch with malformed entryId returns NotFound`() = runTest {
        val (section, _) = newSection(emptyList())
        assertEquals(KeystoreFetch.NotFound, section.fetch("no-slash-here"))
    }

    @Test
    fun `fetch on null password returns NotFound`() = runTest {
        // Profile exists but the field is null. Distinguish from "wrong
        // entryId" — both surface as NotFound but the dao gets touched
        // here, not in the malformed-id case.
        val p = profile(sshPassword = "ENC:x") // vncPassword is null
        val (section, _) = newSection(listOf(p))
        assertEquals(KeystoreFetch.NotFound, section.fetch("p1/vncPassword"))
    }

    @Test
    fun `fetch on unknown field name returns NotFound`() = runTest {
        val p = profile(sshPassword = "ENC:x")
        val (section, _) = newSection(listOf(p))
        assertEquals(KeystoreFetch.NotFound, section.fetch("p1/notARealField"))
    }

    @Test
    fun `entry never carries plaintext password value`() = runTest {
        val p = profile(sshPassword = "PLAINTEXT_NEVER_LEAK")
        val (section, _) = newSection(listOf(p))
        val e = section.enumerate().single()
        val haystack = listOfNotNull(
            e.id, e.label, e.algorithm, e.publicMaterial, e.fingerprint,
        ).joinToString("|")
        assertFalse("audit must not surface password value: $haystack",
            haystack.contains("PLAINTEXT_NEVER_LEAK"))
    }

    @Test
    fun `section reports its store`() {
        val (section, _) = newSection(emptyList())
        assertEquals(KeystoreStore.PROFILE_CREDENTIALS, section.store)
    }
}
