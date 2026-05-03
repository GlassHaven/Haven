package sh.haven.core.data.keystore

import sh.haven.core.data.db.ConnectionDao
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.security.CredentialEncryption
import sh.haven.core.security.KeyKind
import sh.haven.core.security.KeystoreEntry
import sh.haven.core.security.KeystoreFlag
import sh.haven.core.security.KeystoreSection
import sh.haven.core.security.KeystoreStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [KeystoreSection] over the password fields on [ConnectionProfile]
 * rows. Each non-null password column emits one [KeystoreEntry] keyed
 * as `"<profileId>/<fieldName>"`. Wiping clears the column on the
 * profile but never removes the profile itself — different invariant
 * from the SSH-keys section, where wipe deletes the row.
 *
 * The plaintext password is *never* surfaced through [enumerate];
 * auditors see "this profile has an SSH password set" / "encrypted"
 * but the value stays in the DB.
 */
@Singleton
class ProfileCredentialSection @Inject constructor(
    private val connectionDao: ConnectionDao,
) : KeystoreSection {

    override val store: KeystoreStore = KeystoreStore.PROFILE_CREDENTIALS

    private data class Field(
        val name: String,
        val displaySuffix: String,
        val read: (ConnectionProfile) -> String?,
        val write: (ConnectionProfile, String?) -> ConnectionProfile,
    )

    private val fields = listOf(
        Field("sshPassword", "SSH password",
            { it.sshPassword },
            { p, v -> p.copy(sshPassword = v) }),
        Field("vncPassword", "VNC password",
            { it.vncPassword },
            { p, v -> p.copy(vncPassword = v) }),
        Field("rdpPassword", "RDP password",
            { it.rdpPassword },
            { p, v -> p.copy(rdpPassword = v) }),
        Field("smbPassword", "SMB password",
            { it.smbPassword },
            { p, v -> p.copy(smbPassword = v) }),
    )

    override suspend fun enumerate(): List<KeystoreEntry> {
        val profiles = connectionDao.getAll()
        val out = mutableListOf<KeystoreEntry>()
        for (p in profiles) {
            for (field in fields) {
                val raw = field.read(p) ?: continue
                if (raw.isEmpty()) continue
                val flags = mutableSetOf(KeystoreFlag.HARDWARE_BACKED)
                // A legacy plaintext value (no ENC: prefix) is a security
                // gap worth surfacing — the audit screen can highlight it
                // by the absence of HARDWARE_BACKED. The migration path
                // already lives in ConnectionRepository.init.
                if (!CredentialEncryption.isEncrypted(raw)) {
                    flags.remove(KeystoreFlag.HARDWARE_BACKED)
                }
                out.add(
                    KeystoreEntry(
                        id = "${p.id}/${field.name}",
                        store = KeystoreStore.PROFILE_CREDENTIALS,
                        keyKind = KeyKind.PROFILE_PASSWORD,
                        label = "${p.label} — ${field.displaySuffix}",
                        algorithm = if (CredentialEncryption.isEncrypted(raw)) "AES-256-GCM" else "plaintext (legacy)",
                        publicMaterial = null,
                        fingerprint = null,
                        // ConnectionProfile carries lastConnected but not
                        // a per-credential createdAt; leave it null
                        // rather than fabricate a value.
                        createdAt = null,
                        flags = flags,
                    ),
                )
            }
        }
        return out
    }

    override suspend fun wipe(entryId: String): Boolean {
        val (profileId, fieldName) = entryId.split('/', limit = 2).takeIf { it.size == 2 }
            ?: return false
        val field = fields.firstOrNull { it.name == fieldName } ?: return false
        val profile = connectionDao.getById(profileId) ?: return false
        if (field.read(profile) == null) return false
        connectionDao.upsert(field.write(profile, null))
        return true
    }
}
