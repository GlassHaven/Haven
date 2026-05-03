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
}
