package sh.haven.core.data.repository

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import sh.haven.core.data.db.SshKeyDao
import sh.haven.core.data.db.entities.SshKey
import sh.haven.core.security.CredentialEncryption
import sh.haven.core.security.HardwareKeySource
import sh.haven.core.security.KeyEncryption
import sh.haven.core.security.Keystore
import sh.haven.core.security.KeystoreFetch
import sh.haven.core.security.KeystoreStore
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SshKeyRepository"

@Singleton
class SshKeyRepository @Inject constructor(
    private val sshKeyDao: SshKeyDao,
    @ApplicationContext private val context: Context,
    private val keystore: Keystore,
    /** 硬件密钥 Keystore 交互; 接口注入以保 JVM 测试可替换 (见 HardwareKeySource)。 */
    private val hardwareKeys: HardwareKeySource,
) {
    fun observeAll(): Flow<List<SshKey>> = sshKeyDao.observeAll()

    suspend fun getAll(): List<SshKey> = sshKeyDao.getAll()

    suspend fun getById(id: String): SshKey? = sshKeyDao.getById(id)

    /**
     * Save a key, encrypting the private key bytes at rest. Empty bytes
     * pass through unencrypted: that is the storage convention for
     * hardware-resident keys (`hardwareAlias != null` — no local
     * material to protect), and encrypting them would only produce
     * misleading ciphertext that decrypts back to nothing.
     */
    suspend fun save(key: SshKey): Unit = sshKeyDao.upsert(
        key.copy(
            privateKeyBytes =
                if (key.privateKeyBytes.isEmpty()) key.privateKeyBytes
                else KeyEncryption.encrypt(context, key.privateKeyBytes)
        )
    )

    /**
     * Get decrypted private key bytes, keeping a declined prompt
     * distinguishable from a missing key.
     *
     * Hardware-resident keys (`hardwareAlias != null`) short-circuit to
     * [KeyMaterial.Hardware] — no decrypt, no keystore round-trip;
     * their private half is in the Android Keystore and the biometric
     * prompt belongs to the signing operation itself.
     *
     * Software keys route through the unified [Keystore.fetch] so any
     * biometric gating set on the entry (#129 stage 5) actually fires
     * before the bytes are returned. Prefer this over
     * [getDecryptedKeyBytes] anywhere the answer changes what happens
     * next — above all on the SSH auth path, where a declined prompt
     * must stop the connect rather than hand it the next credential
     * (#559).
     */
    suspend fun fetchKeyMaterial(id: String): KeyMaterial {
        val key = sshKeyDao.getById(id) ?: return KeyMaterial.Missing
        key.hardwareAlias?.let { return KeyMaterial.Hardware(it) }
        return when (val result = keystore.fetch(KeystoreStore.SSH_KEYS, id)) {
            is KeystoreFetch.Bytes -> KeyMaterial.Available(result.data)
            is KeystoreFetch.NotFound -> KeyMaterial.Missing
            is KeystoreFetch.Denied -> KeyMaterial.Declined(result.reason)
            is KeystoreFetch.Failed -> {
                Log.w(TAG, "fetch for key $id failed: ${result.reason}")
                KeyMaterial.Missing
            }
            // Password is the wrong shape for SSH-keys; treat as missing.
            is KeystoreFetch.Password -> KeyMaterial.Missing
        }
    }

    /**
     * Get decrypted private key bytes, or null.
     *
     * Lenient: a declined biometric prompt is flattened back to null,
     * the same as a missing key. That is only correct where "skip this
     * key" is a sensible response — listing, export, a backup that
     * should still run. **Auth paths must use [fetchKeyMaterial]**, or
     * they reintroduce #559, where declining the prompt just moved the
     * connect on to the next credential.
     */
    suspend fun getDecryptedKeyBytes(id: String): ByteArray? =
        (fetchKeyMaterial(id) as? KeyMaterial.Available)?.bytes

    /**
     * Every stored software key with decrypted private bytes, plus the
     * keys the user declined to unlock, plus the hardware-resident rows
     * (`hardwareAlias != null`) classified into [DecryptedKeys.hardware] —
     * they have no local private bytes, so they must never appear in
     * [keys] (which backs export / backup / PEM paths), but they ARE
     * auth-usable via their Keystore alias.
     *
     * Each software row goes through [Keystore.fetch], so a
     * biometric-protected entry prompts before its bytes are returned.
     * Rows that are simply unreadable (missing, decrypt error) are dropped
     * as before, since a caller walking the list cannot do anything with
     * them. Declined rows are reported separately: dropping those silently
     * is what turned "I said no" into "try the next key" on the auth path
     * (#559), and only the caller knows whether that matters.
     *
     * Hardware rows need no fetch and can produce no decline: their
     * biometric ceremony is per-signature, later, inside
     * [sh.haven.core.ssh.KeystoreSshIdentity].
     */
    suspend fun getAllDecryptedDetailed(): DecryptedKeys {
        val usable = mutableListOf<SshKey>()
        val declined = mutableListOf<KeyMaterial.Declined>()
        val hardware = mutableListOf<SshKey>()
        for (key in sshKeyDao.getAll()) {
            // Hardware-resident keys join auto-auth via KeystoreSshIdentity
            // (alias + public line), not via decrypted bytes — so they land
            // in their own pool, never the decrypted-bytes pool.
            if (key.hardwareAlias != null) {
                hardware += key
                continue
            }
            when (val r = keystore.fetch(KeystoreStore.SSH_KEYS, key.id)) {
                is KeystoreFetch.Bytes -> usable += key.copy(privateKeyBytes = r.data)
                is KeystoreFetch.Denied -> {
                    Log.w(TAG, "getAllDecrypted: ${key.id} declined")
                    declined += KeyMaterial.Declined(r.reason)
                }
                is KeystoreFetch.NotFound -> Unit
                is KeystoreFetch.Failed ->
                    Log.w(TAG, "getAllDecrypted: fetch for ${key.id} failed (${r.reason})")
                is KeystoreFetch.Password -> Unit
            }
        }
        return DecryptedKeys(keys = usable, declined = declined, hardware = hardware)
    }

    /**
     * Every stored key that could be decrypted, declined ones dropped.
     *
     * Lenient in the same way as [getDecryptedKeyBytes], and with the
     * same rule: fine for backup and listing, wrong for auth. Auth
     * paths want [getAllDecryptedDetailed] so they can refuse to
     * proceed when the user declined a prompt.
     */
    suspend fun getAllDecrypted(): List<SshKey> = getAllDecryptedDetailed().keys

    /**
     * Delete a key row, and with it every trace of the key. SSOT for the
     * hardware-resident branch: a row with [SshKey.hardwareAlias] also has
     * its AndroidKeyStore alias removed here, so *every* delete path — the
     * Keys screen (single and multi-select), MCP tools, and USB-drive VM
     * cleanup — cleans the secure chip without duplicating the logic at the
     * call sites.
     *
     * Order: alias first, row second. [HardwareKeySource.delete] is
     * idempotent, so the two steps converge on retry either way — but the
     * failure asymmetry is not symmetric: an interrupted delete that leaves
     * the row would show a key whose connect fails opaquely (its alias is
     * gone), and deleting that row again repairs everything; the reverse
     * order would strand an invisible alias in scarce secure-chip storage
     * with no row left to retry through.
     */
    suspend fun delete(id: String) {
        val key = sshKeyDao.getById(id)
        key?.hardwareAlias?.let(hardwareKeys::delete)
        sshKeyDao.deleteById(id)
    }

    /**
     * Read the optional OpenSSH certificate bytes attached to [id]
     * (#133 phase 1). Certificates are public material, no decryption
     * applied. Null when the key has no cert attached.
     */
    suspend fun getCertificateBytes(id: String): ByteArray? =
        sshKeyDao.getById(id)?.certificateBytes

    /** Attach (or replace) the certificate bytes for an existing key. */
    suspend fun setCertificateBytes(id: String, certBytes: ByteArray?) {
        val key = sshKeyDao.getById(id) ?: return
        sshKeyDao.upsert(key.copy(certificateBytes = certBytes))
    }

    /**
     * Change the user-facing [SshKey.label] for [id] (#231). Direct
     * upsert of the already-stored row — unlike [save], this deliberately
     * does NOT run [KeyEncryption.encrypt] over [SshKey.privateKeyBytes]:
     * those bytes are the encrypted-at-rest form read straight back from
     * the DB, so re-encrypting would corrupt the key (same reasoning as
     * [setCertificateBytes]). No-op if the key was deleted meanwhile.
     */
    suspend fun rename(id: String, label: String) {
        val key = sshKeyDao.getById(id) ?: return
        sshKeyDao.upsert(key.copy(label = label))
    }

    /** Enable/disable a key's participation in "any saved key" auto-auth. */
    suspend fun setEnabledForAuth(id: String, enabled: Boolean) {
        val key = sshKeyDao.getById(id) ?: return
        sshKeyDao.upsert(key.copy(enabledForAuth = enabled))
    }

    /**
     * Store (or clear, when [passphrase] is null) the opt-in passphrase for an
     * encrypted key (#290). Encrypted at rest via [CredentialEncryption] — the
     * same Android-Keystore-backed scheme used for remembered host passwords.
     * Direct upsert, so it does NOT re-encrypt [SshKey.privateKeyBytes] (same
     * reasoning as [rename]). No-op if the key was deleted meanwhile.
     */
    suspend fun setStoredPassphrase(id: String, passphrase: String?) {
        val key = sshKeyDao.getById(id) ?: return
        sshKeyDao.upsert(
            key.copy(passphraseEncrypted = passphrase?.let { CredentialEncryption.encrypt(context, it) })
        )
    }

    /** Decrypt the stored passphrase for [id], or null if none is stored. */
    suspend fun getStoredPassphrase(id: String): String? =
        sshKeyDao.getById(id)?.passphraseEncrypted?.let { CredentialEncryption.decrypt(context, it) }
}
