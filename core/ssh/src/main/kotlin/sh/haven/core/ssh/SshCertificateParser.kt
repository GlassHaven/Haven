package sh.haven.core.ssh

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64

/**
 * Parses OpenSSH user certificate files (-cert.pub) and extracts metadata
 * for validation and display.
 *
 * Certificate wire format (RFC draft-miller-ssh-cert):
 *   string  cert_type (e.g. "ssh-ed25519-cert-v01@openssh.com")
 *   string  nonce
 *   ...key-specific fields...
 *   uint64  serial
 *   uint32  type (1=user, 2=host)
 *   string  key_id
 *   string  valid_principals (packed list)
 *   uint64  valid_after
 *   uint64  valid_before
 *   string  critical_options
 *   string  extensions
 *   string  reserved
 *   string  signature_key
 *   string  signature
 */
object SshCertificateParser {

    private const val CERT_SUFFIX = "-cert-v01@openssh.com"

    data class CertificateInfo(
        val certKeyType: String,
        val serial: Long,
        val keyId: String,
        val validPrincipals: List<String>,
        val validAfter: Long,
        val validBefore: Long,
        val rawBlob: ByteArray,
        val embeddedPublicKeyFingerprint: String,
    )

    /** Parse a -cert.pub file (text format: "type base64 [comment]"). Returns null if not a cert. */
    fun parse(fileBytes: ByteArray): CertificateInfo? {
        val text = fileBytes.decodeToString().trim()
        val parts = text.split(Regex("\\s+"), limit = 3)
        if (parts.size < 2) return null
        val certKeyType = parts[0]
        if (!certKeyType.endsWith(CERT_SUFFIX)) return null

        val blob = try {
            Base64.getDecoder().decode(parts[1])
        } catch (_: Exception) {
            return null
        }

        return parseBlob(certKeyType, blob)
    }

    /** Check if file content looks like an SSH certificate. */
    fun isCertificateFile(fileBytes: ByteArray): Boolean {
        val text = fileBytes.decodeToString().trim()
        val firstSpace = text.indexOf(' ')
        if (firstSpace <= 0) return false
        return text.substring(0, firstSpace).endsWith(CERT_SUFFIX)
    }

    /** Get the base key type from a cert key type (strips -cert-v01@openssh.com). */
    fun getBaseKeyType(certKeyType: String): String {
        return if (certKeyType.endsWith(CERT_SUFFIX)) {
            certKeyType.removeSuffix(CERT_SUFFIX)
        } else certKeyType
    }

    /** Get the cert key type from a base key type. */
    fun getCertKeyType(baseKeyType: String): String {
        return if (baseKeyType.endsWith(CERT_SUFFIX)) baseKeyType
        else "$baseKeyType$CERT_SUFFIX"
    }

    /** Check if a cert matches a key by comparing the embedded public key fingerprint. */
    fun matchesKey(cert: CertificateInfo, keyFingerprintSha256: String): Boolean {
        return cert.embeddedPublicKeyFingerprint == keyFingerprintSha256
    }

    /** Check if cert is currently valid. */
    fun isCurrentlyValid(cert: CertificateInfo): Boolean {
        val now = System.currentTimeMillis() / 1000
        val afterOk = cert.validAfter == 0L || now >= cert.validAfter
        val beforeOk = cert.validBefore == 0L
            || cert.validBefore == -1L  // 0xFFFFFFFFFFFFFFFF = forever
            || now <= cert.validBefore
        return afterOk && beforeOk
    }

    private fun parseBlob(certKeyType: String, blob: ByteArray): CertificateInfo? {
        val buf = ByteBuffer.wrap(blob)
        buf.order(ByteOrder.BIG_ENDIAN)

        try {
            // string cert_type
            val encodedType = readString(buf)
            if (encodedType != certKeyType) return null

            // string nonce
            readBytes(buf)

            // Skip key-specific fields to get to serial/type/principals.
            // The key fields vary by type, but we can extract the public key
            // blob by reconstructing it from known structure.
            val publicKeyBlob = extractPublicKeyBlob(certKeyType, blob)

            // Skip to after key fields — find serial by scanning structure.
            // Easier approach: skip key-type-specific fields
            skipKeyFields(buf, certKeyType)

            // uint64 serial
            val serial = buf.long

            // uint32 type (1=user, 2=host)
            val type = buf.int

            // string key_id
            val keyId = readString(buf)

            // string valid_principals (packed string list)
            val principalsBlob = readBytes(buf)
            val principals = parsePrincipalsList(principalsBlob)

            // uint64 valid_after, uint64 valid_before
            val validAfter = buf.long
            val validBefore = buf.long

            val fingerprint = if (publicKeyBlob != null) {
                val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyBlob)
                "SHA256:${Base64.getEncoder().withoutPadding().encodeToString(digest)}"
            } else ""

            return CertificateInfo(
                certKeyType = certKeyType,
                serial = serial,
                keyId = keyId,
                validPrincipals = principals,
                validAfter = validAfter,
                validBefore = validBefore,
                rawBlob = blob,
                embeddedPublicKeyFingerprint = fingerprint,
            )
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * Extract the embedded public key blob from a certificate.
     * The public key blob is the SSH wire-format public key (type + key data)
     * that would appear in authorized_keys.
     */
    private fun extractPublicKeyBlob(certKeyType: String, certBlob: ByteArray): ByteArray? {
        // The public key blob for fingerprinting is: string(baseKeyType) + key-specific-fields
        // We reconstruct it by reading the cert fields after the nonce.
        val baseType = getBaseKeyType(certKeyType)
        val buf = ByteBuffer.wrap(certBlob)
        buf.order(ByteOrder.BIG_ENDIAN)

        try {
            readBytes(buf) // cert type string
            readBytes(buf) // nonce

            // Read key-specific fields and build public key blob
            val pubKeyBuf = java.io.ByteArrayOutputStream()
            writeString(pubKeyBuf, baseType.toByteArray())

            when {
                baseType.contains("ed25519") -> {
                    val pk = readBytes(buf) // 32-byte public key
                    writeBytes(pubKeyBuf, pk)
                }
                baseType.contains("ecdsa") -> {
                    val curve = readBytes(buf) // curve name
                    val point = readBytes(buf) // EC point
                    writeBytes(pubKeyBuf, curve)
                    writeBytes(pubKeyBuf, point)
                }
                baseType.contains("rsa") -> {
                    val e = readBytes(buf) // exponent
                    val n = readBytes(buf) // modulus
                    writeBytes(pubKeyBuf, e)
                    writeBytes(pubKeyBuf, n)
                }
                else -> return null
            }
            return pubKeyBuf.toByteArray()
        } catch (_: Exception) {
            return null
        }
    }

    private fun skipKeyFields(buf: ByteBuffer, certKeyType: String) {
        val baseType = getBaseKeyType(certKeyType)
        when {
            baseType.contains("ed25519") -> {
                readBytes(buf) // public key (32 bytes)
            }
            baseType.contains("ecdsa") -> {
                readBytes(buf) // curve name
                readBytes(buf) // EC point
            }
            baseType.contains("rsa") -> {
                readBytes(buf) // e
                readBytes(buf) // n
            }
            baseType.contains("dss") || baseType.contains("dsa") -> {
                readBytes(buf) // p
                readBytes(buf) // q
                readBytes(buf) // g
                readBytes(buf) // y
            }
        }
    }

    private fun parsePrincipalsList(data: ByteArray): List<String> {
        if (data.isEmpty()) return emptyList()
        val buf = ByteBuffer.wrap(data)
        buf.order(ByteOrder.BIG_ENDIAN)
        val result = mutableListOf<String>()
        while (buf.hasRemaining()) {
            result.add(readString(buf))
        }
        return result
    }

    private fun readString(buf: ByteBuffer): String = String(readBytes(buf))

    private fun readBytes(buf: ByteBuffer): ByteArray {
        val len = buf.int
        if (len < 0 || len > buf.remaining()) {
            throw IllegalArgumentException("Invalid length: $len (remaining: ${buf.remaining()})")
        }
        val data = ByteArray(len)
        buf.get(data)
        return data
    }

    private fun writeString(out: java.io.ByteArrayOutputStream, data: ByteArray) {
        writeBytes(out, data)
    }

    private fun writeBytes(out: java.io.ByteArrayOutputStream, data: ByteArray) {
        val lenBuf = ByteBuffer.allocate(4)
        lenBuf.order(ByteOrder.BIG_ENDIAN)
        lenBuf.putInt(data.size)
        out.write(lenBuf.array())
        out.write(data)
    }
}
