package sh.haven.core.data.keystore

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import sh.haven.core.data.db.SshKeyDao
import sh.haven.core.data.db.entities.SshKey
import sh.haven.core.fido.SkKeyData
import sh.haven.core.security.KeyEncryption
import sh.haven.core.security.KeyKind
import sh.haven.core.security.KeystoreEntry
import sh.haven.core.security.KeystoreFetch
import sh.haven.core.security.KeystoreFlag
import sh.haven.core.security.KeystoreSection
import sh.haven.core.security.KeystoreStore
import javax.inject.Inject
import javax.inject.Singleton
import sh.haven.core.redact.LogRedact

private const val TAG = "SshKeySection"

/**
 * [KeystoreSection] over the `ssh_keys` Room table. The table holds
 * three kinds of entries:
 *
 * - **Regular SSH private keys** — encrypted with [KeyEncryption] (Tink
 *   AEAD over the Android Keystore master key).
 * - **FIDO2 SK credentials** — serialized [SkKeyData] blobs that hold
 *   the credential ID + public key only; the actual signing key lives
 *   on the security key, never on this device.
 * - **Hardware-resident keys** (`hardwareAlias != null`) — the signing
 *   key lives in this device's Android Keystore; the row carries only
 *   public material and never yields bytes from [fetch].
 *
 * Enumerate / wipe operate over all kinds uniformly. Distinguishing
 * SK vs regular happens via [SkKeyData.isSkKeyBlob].
 */
@Singleton
class SshKeySection @Inject constructor(
    private val sshKeyDao: SshKeyDao,
    @ApplicationContext private val appContext: Context,
    private val biometricGate: BiometricGate,
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

    /**
     * Fetch the row's stored bytes. For a regular SSH key the bytes
     * are decrypted via [KeyEncryption] (Tink AEAD); for a FIDO2 SK
     * credential the bytes are the serialized [SkKeyData] descriptor,
     * which contains no signing material. The caller's
     * [KeystoreEntry.keyKind] tells which kind they're looking at.
     *
     * Bytes returned here are secret-bearing for [KeyKind.SSH_PRIVATE]
     * — caller must treat as plaintext key material. For
     * [KeyKind.SSH_FIDO_SK] they're public-shaped (the descriptor) but
     * still kept inside the same envelope so callers don't have to
     * branch on kind to decrypt.
     */
    override suspend fun fetch(entryId: String): KeystoreFetch {
        val row = sshKeyDao.getById(entryId) ?: return KeystoreFetch.NotFound
        // Hardware-resident keys have no local private bytes to hand
        // out — the Android Keystore alias is the only handle.
        // [SshKeyRepository.fetchKeyMaterial] dispatches them to
        // KeyMaterial.Hardware before this section is consulted; this
        // guard keeps that invariant for any direct fetch caller too.
        row.hardwareAlias?.let {
            return KeystoreFetch.Failed("Hardware-resident key has no local material")
        }
        Log.d(TAG, "fetch(id=$entryId, label=${LogRedact.of(row.label)}, biometricProtected=${row.biometricProtected})")
        // Biometric gate runs *before* the decrypt, so a denied prompt
        // never asks Tink to unwrap the bytes.
        //
        // Both outcomes return Denied rather than Failed (#559): a denial
        // and a decrypt error need opposite responses from a connect path,
        // and merging them is what let a declined prompt fall through to
        // the next credential. Foreground-inactive is a denial too — the
        // human was never asked, so there is no ack — but it says so
        // separately, because "come back into the app" is a different
        // instruction from "you declined".
        if (row.biometricProtected) {
            Log.d(TAG, "fetch(id=$entryId): requesting biometric gate")
            val decision = biometricGate.request(
                label = "Unlock ${row.label}",
                detail = row.fingerprintSha256,
            )
            Log.d(TAG, "fetch(id=$entryId): gate returned $decision")
            when (decision) {
                BiometricGate.Decision.ALLOW -> { /* proceed */ }
                BiometricGate.Decision.DENY ->
                    return KeystoreFetch.Denied(
                        "Authentication was declined for key \"${row.label}\".",
                    )
                BiometricGate.Decision.UNAVAILABLE ->
                    return KeystoreFetch.Denied(
                        "Key \"${row.label}\" needs authentication, and the prompt " +
                            "could not be shown. Open Haven and try again.",
                    )
            }
        }
        return try {
            val raw = row.privateKeyBytes
            val out = when {
                SkKeyData.isSkKeyBlob(raw) -> raw
                KeyEncryption.isEncrypted(raw) -> KeyEncryption.decrypt(appContext, raw)
                // Legacy unencrypted row from before encryption was
                // added — pass through unchanged.
                else -> raw
            }
            KeystoreFetch.Bytes(out)
        } catch (e: Exception) {
            Log.w(TAG, "fetch failed for key id=${row.id}: ${e.message}")
            // Don't leak the cipher message verbatim; the audit row
            // and any UI surface keep it generic.
            KeystoreFetch.Failed("Decryption failed")
        }
    }

    /**
     * Toggle the [SshKey.biometricProtected] flag on a single row.
     * Called from the Settings → Security audit screen's per-entry
     * "Require biometric" switch. Returns true when the row exists
     * and the value changed; false for unknown id, no-op write, or a
     * denied disable-auth (see below).
     *
     * Disabling protection is itself a protected action (#252):
     * without this gate, anyone with the unlocked app could strip the
     * biometric requirement and then export the key with no prompt,
     * defeating the protection entirely. So clearing the flag requires
     * a fresh biometric auth and fails closed (flag stays on) on a
     * denied/unavailable prompt. Enabling needs no gate — there's no
     * protection to bypass yet.
     */
    suspend fun setBiometricProtected(entryId: String, protected: Boolean): Boolean {
        val row = sshKeyDao.getById(entryId) ?: return false
        if (row.biometricProtected == protected) return false
        if (row.biometricProtected && !protected) {
            val decision = biometricGate.request(
                label = "Disable biometric lock for ${row.label}",
                detail = row.fingerprintSha256,
            )
            if (decision != BiometricGate.Decision.ALLOW) return false
        }
        sshKeyDao.upsert(row.copy(biometricProtected = protected))
        return true
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
        // BIOMETRIC_PROTECTED is independent of FIDO / passphrase /
        // hardware-backed — it's an additional gate set by the user
        // through Settings → Security audit. Surfacing it in flags
        // lets the UI render the chip and the fetch path consult the
        // gate without re-reading the row.
        if (row.biometricProtected) flags.add(KeystoreFlag.BIOMETRIC_PROTECTED)

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
