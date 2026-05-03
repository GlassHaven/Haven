package sh.haven.core.data.keystore

import android.util.Log
import sh.haven.core.data.db.SshKeyDao
import sh.haven.core.data.db.entities.SshKey
import sh.haven.core.fido.SkKeyData
import sh.haven.core.security.KeyEncryption
import sh.haven.core.security.KeyKind
import sh.haven.core.security.KeystoreEntry
import sh.haven.core.security.KeystoreFlag
import sh.haven.core.security.KeystoreSection
import sh.haven.core.security.KeystoreStore
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SshKeySection"

/**
 * [KeystoreSection] over the `ssh_keys` Room table. The table holds two
 * different kinds of entries discriminated by the byte content of
 * `privateKeyBytes`:
 *
 * - **Regular SSH private keys** — encrypted with [KeyEncryption] (Tink
 *   AEAD over the Android Keystore master key).
 * - **FIDO2 SK credentials** — serialized [SkKeyData] blobs that hold
 *   the credential ID + public key only; the actual signing key lives
 *   on the security key, never on this device.
 *
 * Enumerate / wipe operate over both kinds uniformly. Distinguishing
 * SK vs regular happens via [SkKeyData.isSkKeyBlob].
 */
@Singleton
class SshKeySection @Inject constructor(
    private val sshKeyDao: SshKeyDao,
) : KeystoreSection {

    override val store: KeystoreStore = KeystoreStore.SSH_KEYS

    override suspend fun enumerate(): List<KeystoreEntry> {
        return sshKeyDao.getAll().map { row -> toEntry(row) }
    }

    override suspend fun wipe(entryId: String): Boolean {
        // No "did this row exist?" probe — the DAO's deleteById is
        // idempotent and we read via the same lookup primary auditors
        // would use. A non-existent id quietly deletes nothing and we
        // surface that as `false`.
        val existed = sshKeyDao.getById(entryId) != null
        if (existed) sshKeyDao.deleteById(entryId)
        return existed
    }

    private fun toEntry(row: SshKey): KeystoreEntry {
        val isFido = SkKeyData.isSkKeyBlob(row.privateKeyBytes)
        val flags = mutableSetOf<KeystoreFlag>()
        // Both regular keys and SK credentials enjoy hardware-backed
        // protection: regular keys via the Tink master key (Keystore-
        // bound), SK credentials because the signing key itself is on
        // the security key.
        flags.add(KeystoreFlag.HARDWARE_BACKED)

        val kind: KeyKind
        val algorithm: String = if (isFido) {
            kind = KeyKind.SSH_FIDO_SK
            // SkKeyData.deserialize is cheap (header + four lengthed
            // byte arrays + 1 flag byte). Worst case on a malformed
            // blob it throws — log and fall back to the row's keyType.
            try {
                val sk = SkKeyData.deserialize(row.privateKeyBytes)
                if ((sk.flags.toInt() and 0x01) != 0) flags.add(KeystoreFlag.REQUIRES_USER_PRESENCE)
                if ((sk.flags.toInt() and 0x04) != 0) flags.add(KeystoreFlag.REQUIRES_USER_VERIFICATION)
                sk.algorithmName
            } catch (e: Exception) {
                Log.w(TAG, "SK blob parse failed for key id=${row.id}: ${e.message}")
                row.keyType
            }
        } else {
            kind = KeyKind.SSH_PRIVATE
            if (row.isEncrypted) flags.add(KeystoreFlag.REQUIRES_PASSPHRASE)
            row.keyType
        }

        return KeystoreEntry(
            id = row.id,
            store = KeystoreStore.SSH_KEYS,
            keyKind = kind,
            label = row.label,
            algorithm = algorithm,
            publicMaterial = row.publicKeyOpenSsh,
            fingerprint = row.fingerprintSha256,
            createdAt = row.createdAt,
            flags = flags,
        )
    }
}
