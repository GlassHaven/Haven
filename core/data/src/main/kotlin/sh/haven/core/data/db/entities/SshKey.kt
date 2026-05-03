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
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SshKey) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
