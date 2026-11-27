package sh.haven.core.data.repository

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import sh.haven.core.data.db.ConnectionGroupDao
import sh.haven.core.data.db.SshIdentityDao
import sh.haven.core.data.db.entities.ConnectionGroup
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.db.entities.SshIdentity
import sh.haven.core.security.CredentialEncryption

/**
 * The #360 resolution rules — profile-over-group precedence, the explicit
 * NONE opt-out, dangling-reference fallback, and the credential substitution
 * [SshIdentityRepository.applyTo] performs at connect time.
 */
@RunWith(RobolectricTestRunner::class)
class SshIdentityResolutionTest {

    private val context: android.content.Context = RuntimeEnvironment.getApplication()

    private fun repo(
        identities: Map<String, SshIdentity> = emptyMap(),
        groups: Map<String, ConnectionGroup> = emptyMap(),
    ): SshIdentityRepository {
        val identityDao = mockk<SshIdentityDao>()
        val groupDao = mockk<ConnectionGroupDao>()
        coEvery { identityDao.getById(any()) } answers { identities[firstArg()] }
        coEvery { groupDao.getById(any()) } answers { groups[firstArg()] }
        return SshIdentityRepository(identityDao, groupDao, context)
    }

    private val base = ConnectionProfile(id = "c", label = "c", host = "h", username = "inline")

    @Test
    fun `no identity assigned resolves to null`() = runTest {
        assertNull(repo().effectiveIdentity(base))
    }

    @Test
    fun `profile identity wins`() = runTest {
        val ident = SshIdentity(id = "i1", name = "prod", username = "root")
        val r = repo(identities = mapOf("i1" to ident))
        assertEquals(ident, r.effectiveIdentity(base.copy(identityId = "i1")))
    }

    @Test
    fun `NONE opts out even inside a group with an identity`() = runTest {
        val ident = SshIdentity(id = "g1i", name = "grp", username = "root")
        val r = repo(
            identities = mapOf("g1i" to ident),
            groups = mapOf("g1" to ConnectionGroup(id = "g1", label = "G", identityId = "g1i")),
        )
        val profile = base.copy(groupId = "g1", identityId = SshIdentity.NONE_ID)
        assertNull(r.effectiveIdentity(profile))
    }

    @Test
    fun `group identity is inherited when profile has none`() = runTest {
        val ident = SshIdentity(id = "g1i", name = "grp", username = "root")
        val r = repo(
            identities = mapOf("g1i" to ident),
            groups = mapOf("g1" to ConnectionGroup(id = "g1", label = "G", identityId = "g1i")),
        )
        assertEquals(ident, r.effectiveIdentity(base.copy(groupId = "g1")))
    }

    @Test
    fun `dangling profile reference falls back to inline credentials`() = runTest {
        // Identity was deleted; applyTo must return the profile untouched.
        val r = repo()
        val profile = base.copy(identityId = "deleted")
        assertNull(r.effectiveIdentity(profile))
        assertEquals(profile, r.applyTo(profile))
    }

    @Test
    fun `applyTo substitutes username password and key`() = runTest {
        val encrypted = CredentialEncryption.encrypt(context, "s3cret")
        val ident = SshIdentity(id = "i1", name = "prod", username = "root", password = encrypted, keyId = "k9")
        val r = repo(identities = mapOf("i1" to ident))
        val applied = r.applyTo(base.copy(identityId = "i1", username = "inline", sshPassword = null))
        assertEquals("root", applied.username)
        assertEquals("s3cret", applied.sshPassword)
        assertEquals("k9", applied.keyId)
        assertEquals(ConnectionProfile.AuthType.KEY, applied.authType)
    }

    @Test
    fun `password-only identity applies PASSWORD auth type`() = runTest {
        val ident = SshIdentity(
            id = "i2", name = "pw", username = "root",
            password = CredentialEncryption.encrypt(context, "pw"), keyId = null,
        )
        val r = repo(identities = mapOf("i2" to ident))
        val applied = r.applyTo(base.copy(identityId = "i2"))
        assertEquals(ConnectionProfile.AuthType.PASSWORD, applied.authType)
        assertNull(applied.keyId)
    }

    @Test
    fun `key-only identity preserves inline sshPassword and multi-factor TOTP chain`() = runTest {
        val ident = SshIdentity(id = "i-key", name = "ops-key", username = "ops", password = null, keyId = "k-ops")
        val r = repo(
            identities = mapOf("i-key" to ident),
            groups = mapOf("g1" to ConnectionGroup(id = "g1", label = "Prod", identityId = "i-key")),
        )
        val profile = base.copy(
            groupId = "g1",
            sshPassword = "inline-pam-password",
            authType = ConnectionProfile.AuthType.KEY,
            authMethods = "KEY\nTOTP:totp-1\nPASSWORD",
        )
        val applied = r.applyTo(profile)
        assertEquals("ops", applied.username)
        assertEquals("k-ops", applied.keyId)
        assertEquals("inline-pam-password", applied.sshPassword)
        assertEquals(
            listOf(
                ConnectionProfile.AuthMethodSpec.Key("k-ops"),
                ConnectionProfile.AuthMethodSpec.Totp("totp-1"),
                ConnectionProfile.AuthMethodSpec.Password,
            ),
            applied.authMethodSpecs,
        )
    }
}
