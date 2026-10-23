package sh.haven.core.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "ssh_keys")
data class SshKey(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val keyType: String,
    val privateKeyBytes: ByteArray,
    val publicKeyOpenSsh: String,
    val fingerprintSha256: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** True if privateKeyBytes are passphrase-encrypted. Passphrase prompted at connect time. */
    val isEncrypted: Boolean = false,
    /**
     * True if a successful biometric authentication is required before
     * the key bytes can be fetched (#129 stage 5). Set/cleared via the
     * Settings → Security audit screen toggle. Unprotected by default
     * — existing keys keep working without an extra prompt.
     */
    val biometricProtected: Boolean = false,
    /**
     * Optional OpenSSH certificate bytes (the contents of an
     * `id_xxx-cert.pub` file) signed by a CA the server trusts.
     * When present, [SshClient] wraps the private key + cert in
     * [com.jcraft.jsch.OpenSshCertificateAwareIdentityFile] so the
     * server's CA-pinned auth flow accepts the connection. Stored as
     * raw text bytes — certificates are public material, no encryption
     * applied. Null for the common case (#133 phase 1).
     */
    val certificateBytes: ByteArray? = null,
    /**
     * If this key was minted via step-ca (#133 phase 2), points at the
     * `StepCaConfig.id` that was used. Carries no auth weight — kept so
     * the Keys screen can show "Valid until …" and so phase 2b's
     * renewal job can find the right CA to re-sign against. Null for
     * locally-generated and manually-imported keys.
     */
    val caConfigId: String? = null,
    /**
     * Wall-clock millis when [certificateBytes] was issued. Used purely
     * for the UI badge — actual cert validity comes from the cert blob
     * itself (which step-ca controls). Null for keys without a cert.
     */
    val certIssuedAt: Long? = null,
    /**
     * Whether this key takes part in "any saved key" auto-auth (the OpenSSH-
     * style offer-all default). When false the key is skipped by the try-all
     * bundle, the jump-host auto-key heuristic, and agent forwarding — it is
     * still used when a profile pins it explicitly. Default true; toggled per
     * key on the Keys screen.
     */
    val enabledForAuth: Boolean = true,
    /**
     * Optional passphrase for an encrypted key, stored encrypted-at-rest via
     * [sh.haven.core.security.CredentialEncryption] (the `"ENC:…"` string).
     * Opt-in per key on the Keys screen — when set, the connect flow uses it
     * instead of prompting, so one encrypted key works across all profiles
     * without remembering the passphrase on each (#290). Null = not stored.
     */
    val passphraseEncrypted: String? = null,
    /**
     * Non-null when this key's private half lives in the device's
     * Android Keystore (secure chip) instead of the `privateKeyBytes`
     * column — the value is the Keystore **alias** the signing
     * operation is dispatched to. Storage convention for such rows:
     * `privateKeyBytes` is empty, `isEncrypted` false,
     * `biometricProtected` true (each signature is gated per-op), and
     * `keyType` is `ecdsa-sha2-nistp256` (the only curve the hardware
     * backend supports). Null = ordinary software key whose bytes are
     * Tink-encrypted in [privateKeyBytes].
     */
    val hardwareAlias: String? = null,
) {
    // Structural equality — NOT id-only. An id-only equals() means a row
    // whose label (or cert) changed is `.equals()` to its old self, so a
    // `StateFlow<List<SshKey>>` from `stateIn` conflates the change away
    // and the Keys screen never re-renders the new label (#231 rename
    // landed in the DB but the row stayed stale until a fresh load). The
    // ByteArray fields use contentEquals so two reads of the same row
    // still compare equal (plain `==` on ByteArray is reference identity).
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SshKey) return false
        return id == other.id &&
            label == other.label &&
            keyType == other.keyType &&
            privateKeyBytes.contentEquals(other.privateKeyBytes) &&
            publicKeyOpenSsh == other.publicKeyOpenSsh &&
            fingerprintSha256 == other.fingerprintSha256 &&
            createdAt == other.createdAt &&
            isEncrypted == other.isEncrypted &&
            biometricProtected == other.biometricProtected &&
            certificateBytes.contentEquals(other.certificateBytes) &&
            caConfigId == other.caConfigId &&
            certIssuedAt == other.certIssuedAt &&
            enabledForAuth == other.enabledForAuth &&
            passphraseEncrypted == other.passphraseEncrypted &&
            hardwareAlias == other.hardwareAlias
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + label.hashCode()
        result = 31 * result + keyType.hashCode()
        result = 31 * result + privateKeyBytes.contentHashCode()
        result = 31 * result + publicKeyOpenSsh.hashCode()
        result = 31 * result + fingerprintSha256.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + isEncrypted.hashCode()
        result = 31 * result + biometricProtected.hashCode()
        result = 31 * result + (certificateBytes?.contentHashCode() ?: 0)
        result = 31 * result + (caConfigId?.hashCode() ?: 0)
        result = 31 * result + (certIssuedAt?.hashCode() ?: 0)
        result = 31 * result + enabledForAuth.hashCode()
        result = 31 * result + (passphraseEncrypted?.hashCode() ?: 0)
        result = 31 * result + (hardwareAlias?.hashCode() ?: 0)
        return result
    }

    companion object {
        /**
         * Label of every ephemeral key `UsbDriveVmManager` mints for an
         * opened USB drive's VM (unchanged since #287). It is the marker
         * both for keeping such keys out of the try-every-key auth pool
         * and for the Keys screen grouping them into their own section —
         * shared here because the minting (app) and grouping (feature:keys)
         * modules can't see each other.
         */
        const val USB_DRIVE_VM_LABEL = "USB drive VM (ephemeral)"
    }
}
