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
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SshKey) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
