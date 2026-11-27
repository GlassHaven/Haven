package sh.haven.core.data.repository

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import sh.haven.core.data.db.ConnectionGroupDao
import sh.haven.core.data.db.SshIdentityDao
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.db.entities.SshIdentity
import sh.haven.core.security.CredentialEncryption
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SshIdentityRepository"

/**
 * CRUD for [SshIdentity] rows plus the connect-time resolver (#360).
 *
 * The password is encrypted at rest with [CredentialEncryption];
 * [observeAll] returns rows with it still encrypted (the UI needs name /
 * username / key only). [save] re-encrypts, [applyTo] decrypts.
 */
@Singleton
class SshIdentityRepository @Inject constructor(
    private val sshIdentityDao: SshIdentityDao,
    private val connectionGroupDao: ConnectionGroupDao,
    @ApplicationContext private val context: Context,
) {
    /** Observe all identities (password left encrypted — UI shows name/username/key). */
    fun observeAll(): Flow<List<SshIdentity>> = sshIdentityDao.observeAll()

    suspend fun getById(id: String): SshIdentity? = sshIdentityDao.getById(id)

    /** Store [identity], encrypting a (re)supplied plaintext password. */
    suspend fun save(identity: SshIdentity) {
        sshIdentityDao.upsert(
            identity.copy(
                password = identity.password
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { CredentialEncryption.encrypt(context, it) },
            ),
        )
    }

    suspend fun delete(id: String) = sshIdentityDao.deleteById(id)

    /**
     * Upsert from the editor (#360). [newPassword]: non-null (incl. blank =
     * clear) replaces the password and is encrypted here; null keeps the
     * stored ciphertext untouched (edit without retyping). A new identity
     * (id null / unknown) with a null password saves as key-only.
     */
    suspend fun upsertFromEditor(
        id: String?,
        name: String,
        username: String,
        newPassword: String?,
        keyId: String?,
    ) {
        val existing = id?.let { sshIdentityDao.getById(it) }
        val storedPassword = when {
            newPassword == null -> existing?.password // keep ciphertext as-is
            newPassword.isEmpty() -> null
            else -> CredentialEncryption.encrypt(context, newPassword)
        }
        sshIdentityDao.upsert(
            SshIdentity(
                id = existing?.id ?: id ?: java.util.UUID.randomUUID().toString(),
                name = name,
                username = username,
                password = storedPassword,
                keyId = keyId,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            ),
        )
    }

    /** All identities with passwords decrypted — for the encrypted backup export only. */
    suspend fun getAllDecrypted(): List<SshIdentity> = sshIdentityDao.getAll().map { row ->
        row.copy(
            password = row.password?.let {
                try {
                    CredentialEncryption.decrypt(context, it)
                } catch (e: Exception) {
                    Log.w(TAG, "identity '${row.name}' password decrypt failed: ${e.message}")
                    null
                }
            },
        )
    }

    /**
     * The identity [profile] should connect with, or null for inline
     * credentials. Resolution: the profile's own [ConnectionProfile.identityId]
     * wins ([SshIdentity.NONE_ID] = explicit opt-out); otherwise the group's
     * identity when one is assigned; otherwise none. A dangling reference
     * (identity deleted) falls back to inline credentials rather than failing
     * the connect.
     */
    suspend fun effectiveIdentity(profile: ConnectionProfile): SshIdentity? {
        profile.identityId?.let { own ->
            return if (own == SshIdentity.NONE_ID) null else sshIdentityDao.getById(own)
        }
        val groupIdentityId = profile.groupId
            ?.let { connectionGroupDao.getById(it) }?.identityId
            ?: return null
        return sshIdentityDao.getById(groupIdentityId)
    }

    /**
     * Return [profile] with the effective identity's credentials substituted
     * for the inline ones — the single connect-time seam (#360). Identity
     * auth is deliberately simple: the identity's key (when set) then its
     * password, via the legacy [ConnectionProfile.authType]/keyId path —
     * [ConnectionProfile.authMethods] chains stay a per-connection concern.
     * No identity resolves → the profile is returned unchanged, so existing
     * per-host credentials behave exactly as before.
     */
    suspend fun applyTo(profile: ConnectionProfile): ConnectionProfile {
        val identity = effectiveIdentity(profile) ?: return profile
        val password = identity.password?.let {
            try {
                CredentialEncryption.decrypt(context, it)
            } catch (e: Exception) {
                Log.w(TAG, "identity '${identity.name}' password decrypt failed: ${e.message}")
                null
            }
        }
        val existingSpecs = ConnectionProfile.AuthMethodSpec.parseList(profile.authMethods)
        val hasMultiFactorChain = existingSpecs.any {
            it is ConnectionProfile.AuthMethodSpec.Totp ||
                it is ConnectionProfile.AuthMethodSpec.KeyboardInteractive
        }
        val mergedAuthMethods = if (hasMultiFactorChain) {
            val mapped = existingSpecs.map { spec ->
                if (spec is ConnectionProfile.AuthMethodSpec.Key && identity.keyId != null) {
                    ConnectionProfile.AuthMethodSpec.Key(identity.keyId)
                } else {
                    spec
                }
            }.toMutableList()
            if (identity.keyId != null && mapped.none { it is ConnectionProfile.AuthMethodSpec.Key }) {
                mapped.add(0, ConnectionProfile.AuthMethodSpec.Key(identity.keyId))
            }
            ConnectionProfile.AuthMethodSpec.serializeList(mapped)
        } else {
            ""
        }
        return profile.copy(
            username = identity.username,
            sshPassword = password ?: profile.sshPassword,
            keyId = identity.keyId,
            authType = if (identity.keyId != null) {
                ConnectionProfile.AuthType.KEY
            } else {
                ConnectionProfile.AuthType.PASSWORD
            },
            authMethods = mergedAuthMethods,
        )
    }
}
