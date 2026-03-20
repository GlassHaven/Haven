package sh.haven.core.ssh

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64

/**
 * Parses SSH private key files using JSch.
 * Supports PEM (PKCS#8, PKCS#1, EC), OpenSSH, and PuTTY PPK formats.
 * The original file bytes are stored as-is — JSch parses them again at connect time.
 */
object SshKeyImporter {

    data class ImportedKey(
        val keyType: String,
        val privateKeyBytes: ByteArray,
        val publicKeyOpenSsh: String,
        val fingerprintSha256: String,
    )

    class EncryptedKeyException : Exception("Key is encrypted — passphrase required")

    /**
     * Parse a private key file and extract metadata for storage.
     *
     * @throws EncryptedKeyException if the key is encrypted and no passphrase given
     * @throws IllegalArgumentException if the passphrase is wrong or key is unreadable
     */
    fun import(fileBytes: ByteArray, passphrase: String? = null): ImportedKey {
        val jsch = JSch()
        val kpair = try {
            KeyPair.load(jsch, fileBytes, null)
        } catch (e: Exception) {
            throw IllegalArgumentException("Unrecognised key format: ${e.message}", e)
        }

        try {
            if (kpair.isEncrypted) {
                if (passphrase.isNullOrEmpty()) {
                    throw EncryptedKeyException()
                }
                if (!kpair.decrypt(passphrase)) {
                    throw IllegalArgumentException("Incorrect passphrase")
                }
            }

            val pubBlob = kpair.publicKeyBlob
                ?: throw IllegalArgumentException("Could not extract public key")

            val keyTypeName = readKeyTypeName(pubBlob)
            val pubB64 = Base64.getEncoder().encodeToString(pubBlob)
            val publicKeyOpenSsh = "$keyTypeName $pubB64"

            val digest = MessageDigest.getInstance("SHA-256").digest(pubBlob)
            val fpB64 = Base64.getEncoder().withoutPadding().encodeToString(digest)
            val fingerprint = "SHA256:$fpB64"

            // Store decrypted key so passphrase isn't needed at connect time.
            // For unencrypted keys, store the original bytes unchanged.
            val storedBytes = if (passphrase != null) {
                val out = ByteArrayOutputStream()
                kpair.writePrivateKey(out)
                out.toByteArray()
            } else {
                fileBytes
            }

            return ImportedKey(
                keyType = keyTypeName,
                privateKeyBytes = storedBytes,
                publicKeyOpenSsh = publicKeyOpenSsh,
                fingerprintSha256 = fingerprint,
            )
        } finally {
            kpair.dispose()
        }
    }

    /** Read the key type name from the first field of an SSH wire format public key blob. */
    private fun readKeyTypeName(pubBlob: ByteArray): String {
        if (pubBlob.size < 4) throw IllegalArgumentException("Public key blob too short")
        val len = ((pubBlob[0].toInt() and 0xFF) shl 24) or
                ((pubBlob[1].toInt() and 0xFF) shl 16) or
                ((pubBlob[2].toInt() and 0xFF) shl 8) or
                (pubBlob[3].toInt() and 0xFF)
        if (len <= 0 || 4 + len > pubBlob.size) throw IllegalArgumentException("Invalid public key blob")
        return String(pubBlob, 4, len, Charsets.US_ASCII)
    }
}
