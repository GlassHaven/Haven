package sh.haven.core.security

/**
 * Conceptual store an entry lives in. Today there are two physical
 * tables backing key material — the `ssh_keys` Room table (carrying
 * both regular SSH private keys and FIDO2 SK credentials) and the
 * `connection_profiles` table's password fields. The [KeystoreStore]
 * label expresses the conceptual grouping so audit / wipe / export
 * code can reason about each independently without caring about the
 * storage layout underneath.
 */
enum class KeystoreStore {
    /** Local SSH key material — both regular private keys and FIDO2 SK credentials. */
    SSH_KEYS,
    /** Per-profile passwords (sshPassword, vncPassword, smbPassword, rdpPassword). */
    PROFILE_CREDENTIALS,
}

/**
 * Specific kind of key material an entry represents. Within a single
 * [KeystoreStore] the kinds may differ: e.g. [KeystoreStore.SSH_KEYS]
 * holds both [SSH_PRIVATE] and [SSH_FIDO_SK].
 */
enum class KeyKind {
    /** Regular SSH private key (Ed25519, RSA, ECDSA). Encrypted at rest with [KeyEncryption]. */
    SSH_PRIVATE,
    /** FIDO2 SK SSH credential — credential ID + public key. The actual signing key lives on the security key, not on this device. */
    SSH_FIDO_SK,
    /** A profile password (SSH/VNC/SMB/RDP). Encrypted at rest with [CredentialEncryption]. */
    PROFILE_PASSWORD,
}

/**
 * Capability / protection flag attached to a [KeystoreEntry]. Aggregated
 * for audit display ("this key requires biometric unlock", "this FIDO
 * credential demands user verification").
 */
enum class KeystoreFlag {
    /** Master key lives in Android Keystore (hardware-backed when the device has a TEE / secure element). */
    HARDWARE_BACKED,
    /** Private key bytes are passphrase-encrypted; user is prompted at use time. */
    REQUIRES_PASSPHRASE,
    /** FIDO2 SK key has the user-presence-required flag set (touch the key). */
    REQUIRES_USER_PRESENCE,
    /** FIDO2 SK key has the user-verification-required flag set (PIN or biometric). */
    REQUIRES_USER_VERIFICATION,
    /** User has opted this entry into a per-fetch BiometricPrompt (#129 stage 5). */
    BIOMETRIC_PROTECTED,
}

/**
 * One entry in the unified keystore. Carries enough metadata for the
 * security audit screen to render the entry plus dispatch wipe/export
 * back to the right [KeystoreStore]. Never carries plaintext key
 * material — auditors see what's there, not the secrets.
 */
data class KeystoreEntry(
    val id: String,
    val store: KeystoreStore,
    val keyKind: KeyKind,
    val label: String,
    /** "Ed25519", "RSA-2048", "ed25519-sk", "AES-256-GCM" (for credentials), etc. */
    val algorithm: String,
    /** OpenSSH `ssh-ed25519 …` line for SSH keys; null for opaque credentials. */
    val publicMaterial: String? = null,
    /** SHA256 fingerprint where derivable (SSH keys); null for credentials. */
    val fingerprint: String? = null,
    /** Epoch millis when the entry was created; null when not tracked. */
    val createdAt: Long? = null,
    /** Capability / protection flags. See [KeystoreFlag]. */
    val flags: Set<KeystoreFlag> = emptySet(),
)

/**
 * Snapshot of every [KeystoreEntry] across every section at a single
 * point in time, suitable for export, audit-log archival, or
 * round-tripping into a JSON backup. Carries only metadata — the same
 * "no plaintext key material" invariant [KeystoreEntry] enforces is
 * preserved here. The user can hand this file to anyone (an auditor,
 * a security review, themselves on another device) without leaking
 * secrets.
 */
data class KeystoreAuditSnapshot(
    /** Epoch millis when the snapshot was taken. */
    val capturedAt: Long,
    /** Optional app version label. Useful for distinguishing snapshots taken across upgrades; null when not supplied. */
    val appVersion: String? = null,
    val entries: List<KeystoreEntry>,
) {
    /** Counts per [KeystoreStore], for at-a-glance audit summaries. */
    val countsByStore: Map<KeystoreStore, Int>
        get() = entries.groupingBy { it.store }.eachCount()

    /** Counts per [KeyKind], distinguishing SSH_PRIVATE vs SSH_FIDO_SK vs PROFILE_PASSWORD. */
    val countsByKind: Map<KeyKind, Int>
        get() = entries.groupingBy { it.keyKind }.eachCount()
}

/**
 * Result of a [KeystoreSection.fetch] / [Keystore.fetch] call. The
 * sealed shape forces callers to handle every state explicitly: a
 * successful fetch returns either decrypted bytes or a decrypted
 * password value, a missing entry returns [NotFound], and a decrypt /
 * IO error surfaces as [Failed].
 *
 * The plaintext-bearing variants ([Bytes], [Password]) are the *only*
 * surface in this package that exposes secret material. Callers MUST
 * treat them as secret — never log, never persist outside the source
 * store, and zero / overwrite when done where feasible.
 */
sealed class KeystoreFetch {
    /**
     * Plaintext key material. For [KeyKind.SSH_PRIVATE] the bytes are
     * the decrypted private key (PEM / OpenSSH); for
     * [KeyKind.SSH_FIDO_SK] the bytes are the serialized SK descriptor
     * (credential ID + public key, no signing material). The
     * corresponding [KeystoreEntry.keyKind] tells callers which they
     * have.
     */
    class Bytes(val data: ByteArray) : KeystoreFetch()

    /** Plaintext password value for a profile credential. */
    class Password(val value: String) : KeystoreFetch()

    /** Entry id not registered in this section / store. */
    data object NotFound : KeystoreFetch()

    /**
     * Fetch failed. [reason] is a short human-readable label suitable
     * for surfacing in the audit log; it never carries cipher state or
     * the secret itself.
     */
    class Failed(val reason: String) : KeystoreFetch()
}

/**
 * One conceptual region of the unified [Keystore]. Each region wraps a
 * concrete persistent store (Room DAO / SharedPrefs / etc.) and
 * translates between its native shape and [KeystoreEntry].
 *
 * Implementations live in higher modules where the relevant DAO is
 * accessible (e.g. `core/data` for `ssh_keys` and `connection_profiles`
 * — `core/security` is dep-leaf and intentionally cannot reach Room
 * entities).
 */
interface KeystoreSection {
    val store: KeystoreStore
    suspend fun enumerate(): List<KeystoreEntry>
    /**
     * Wipe the entry identified by [entryId] from this section. For SSH
     * keys this deletes the row. For profile credentials it clears the
     * password field but leaves the profile in place. Returns true when
     * something was actually wiped.
     */
    suspend fun wipe(entryId: String): Boolean

    /**
     * Retrieve the secret material for [entryId]. Implementations
     * decrypt at-rest material and return the plaintext via
     * [KeystoreFetch.Bytes] or [KeystoreFetch.Password]; missing or
     * failed lookups surface through [KeystoreFetch.NotFound] /
     * [KeystoreFetch.Failed].
     *
     * Stage 4 of issue #129 will layer per-entry biometric gating on
     * top of this method. The current contract is unconditional:
     * whoever calls [fetch] gets the material if the lookup succeeds.
     */
    suspend fun fetch(entryId: String): KeystoreFetch
}

/**
 * Unified read-only view across every [KeystoreSection]. Composed at
 * the application layer (typically `core/data`) so the security audit
 * screen, agent transport, and any future export tooling all consult
 * the same surface.
 *
 * Adapters that convert between section-native shapes (e.g. [SshKey]
 * rows, [ConnectionProfile] password columns, FIDO `SkKeyData` blobs)
 * and [KeystoreEntry] live in their respective modules; this interface
 * is the seam.
 */
interface Keystore {
    suspend fun enumerate(): List<KeystoreEntry>
    /**
     * Wipe the entry identified by [entryId] in [store]. Routes to the
     * matching [KeystoreSection]. Returns true when something was
     * actually wiped — false for unknown ids or no-op stores.
     */
    suspend fun wipe(store: KeystoreStore, entryId: String): Boolean

    /**
     * Capture a [KeystoreAuditSnapshot] of every entry across every
     * section. Intended for export, periodic audit archival, or backup
     * to a host that's syncing the user's metadata (without the
     * secrets themselves). Implementations should populate
     * [KeystoreAuditSnapshot.appVersion] when they have access to a
     * stable version label; otherwise leave it null and let the caller
     * supply one.
     */
    suspend fun exportAudit(): KeystoreAuditSnapshot

    /**
     * Retrieve secret material for a single entry. Routes to the
     * matching [KeystoreSection]. Returns [KeystoreFetch.NotFound] for
     * unknown stores or missing entry ids; [KeystoreFetch.Failed] for
     * decrypt errors (with a human-readable, secret-free reason);
     * [KeystoreFetch.Failed] with a "biometric required" reason when an
     * entry is gated behind biometric auth and the prompt was denied
     * or unavailable.
     */
    suspend fun fetch(store: KeystoreStore, entryId: String): KeystoreFetch

    /**
     * Toggle [KeystoreFlag.BIOMETRIC_PROTECTED] on a single entry.
     * Currently supported only on [KeystoreStore.SSH_KEYS]; other
     * stores return false (no-op). Returns true when the value
     * actually changed.
     */
    suspend fun setBiometricProtected(
        store: KeystoreStore,
        entryId: String,
        protected: Boolean,
    ): Boolean
}
