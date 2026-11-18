package sh.haven.core.ssh

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import sh.haven.core.fido.SkKeyParser
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64

/**
 * Parses SSH private key files using JSch.
 * Supports PEM (PKCS#8, PKCS#1, EC), OpenSSH, PuTTY PPK, and Dropbear formats.
 * The original file bytes are stored as-is — JSch parses them again at connect time.
 * Dropbear keys are converted to OpenSSH format on import.
 */
object SshKeyImporter {

    data class ImportedKey(
        val keyType: String,
        val privateKeyBytes: ByteArray,
        val publicKeyOpenSsh: String,
        val fingerprintSha256: String,
        val isEncrypted: Boolean = false,
    )

    class EncryptedKeyException : Exception("Key is encrypted — passphrase required")

    /** Thrown when the key file is a FIDO2 SK key that needs special handling. */
    class SkKeyDetectedException(val fileBytes: ByteArray) :
        Exception("FIDO2 security key file detected")

    /**
     * Parse a private key file and extract metadata for storage.
     *
     * @throws EncryptedKeyException if the key is encrypted and no passphrase given
     * @throws SkKeyDetectedException if the file is a FIDO2 SK key
     * @throws IllegalArgumentException if the passphrase is wrong or key is unreadable
     */
    fun import(fileBytes: ByteArray, passphrase: String? = null): ImportedKey {
        // Detect FIDO2 SK keys before JSch (which doesn't support them)
        if (SkKeyParser.isSkKeyFile(fileBytes)) {
            throw SkKeyDetectedException(fileBytes)
        }

        // Convert Dropbear keys to OpenSSH format (JSch doesn't support Dropbear)
        val keyBytes = if (DropbearKeyConverter.isDropbearKey(fileBytes)) {
            DropbearKeyConverter.toOpenSsh(fileBytes)
        } else {
            fileBytes
        }

        val jsch = JSch()
        val kpair = try {
            KeyPair.load(jsch, keyBytes, null)
        } catch (e: Exception) {
            // JSch does not know newer key types such as the hybrid
            // post-quantum ssh-mldsa44-ed25519 (#859). The OpenSSH v1 format
            // keeps the public-key section in plaintext for any key type, so
            // fall back to a structural parse for the metadata. The original
            // bytes are stored as-is and re-parsed by the sshlib engine at
            // connect time — JSch (the default engine) cannot use these types.
            return importOpenSshStructural(keyBytes, passphrase, e)
        }

        val encrypted = kpair.isEncrypted

        try {
            // Temporarily decrypt to extract public key metadata, but store
            // the original encrypted bytes. The passphrase will be prompted
            // at connection time — private keys never stored in plaintext.
            if (encrypted) {
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

            // Store original key bytes as-is. For encrypted keys, the passphrase
            // is prompted at connection time and passed to JSch's addIdentity().
            // For Dropbear keys, store the converted OpenSSH bytes (JSch can't parse Dropbear).
            val storedBytes = keyBytes

            return ImportedKey(
                keyType = keyTypeName,
                privateKeyBytes = storedBytes,
                publicKeyOpenSsh = publicKeyOpenSsh,
                fingerprintSha256 = fingerprint,
                isEncrypted = encrypted,
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

    /**
     * Structural fallback for OpenSSH v1 private keys whose type JSch cannot
     * parse (#859, e.g. ssh-mldsa44-ed25519). The format is
     * `"openssh-key-v1\0" || cipher || kdf || kdf_options || num_keys ||
     * public_section || private_section`; the public section is plaintext even
     * when the private section is encrypted, so the type, public key and
     * fingerprint can be read without any key-type-specific crypto.
     *
     * Encrypted keys are stored as-is with [ImportedKey.isEncrypted] set; the
     * passphrase cannot be validated here (no crypto for the unknown type) and
     * is checked by the sshlib engine at connect time.
     */
    private fun importOpenSshStructural(
        keyBytes: ByteArray,
        passphrase: String?,
        cause: Exception,
    ): ImportedKey {
        fun unrecognized(): Nothing =
            throw IllegalArgumentException("Unrecognised key format: ${cause.message}", cause)

        val text = keyBytes.decodeToString()
        val start = text.indexOf("-----BEGIN OPENSSH PRIVATE KEY-----")
        val end = text.indexOf("-----END OPENSSH PRIVATE KEY-----")
        if (start < 0 || end < 0) unrecognized()
        val b64 = text.substring(start + "-----BEGIN OPENSSH PRIVATE KEY-----".length, end)
            .replace(Regex("\\s+"), "")
        val raw = try {
            Base64.getDecoder().decode(b64)
        } catch (_: Exception) {
            unrecognized()
        }

        val magic = "openssh-key-v1\u0000"
        if (raw.size < magic.length) unrecognized()
        val buf = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
        val magicBytes = ByteArray(magic.length)
        buf.get(magicBytes)
        if (String(magicBytes) != magic) unrecognized()

        val cipherName = readSshString(buf)
        readSshString(buf) // kdfname
        readSshBytes(buf)  // kdfoptions
        val numKeys = buf.int
        if (numKeys != 1) throw IllegalArgumentException("Expected a single key, got $numKeys")
        val pubBlob = readSshBytes(buf)

        val keyType = readKeyTypeName(pubBlob)
        val encrypted = cipherName != "none"
        if (encrypted && passphrase.isNullOrEmpty()) throw EncryptedKeyException()

        val pubB64 = Base64.getEncoder().encodeToString(pubBlob)
        val digest = MessageDigest.getInstance("SHA-256").digest(pubBlob)
        val fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)

        return ImportedKey(
            keyType = keyType,
            privateKeyBytes = keyBytes,
            publicKeyOpenSsh = "$keyType $pubB64",
            fingerprintSha256 = fingerprint,
            isEncrypted = encrypted,
        )
    }

    private fun readSshBytes(buf: ByteBuffer): ByteArray {
        val len = buf.int
        require(len >= 0 && len <= buf.remaining()) { "Invalid SSH string length: $len" }
        val data = ByteArray(len)
        buf.get(data)
        return data
    }

    private fun readSshString(buf: ByteBuffer): String = String(readSshBytes(buf), Charsets.US_ASCII)
}
