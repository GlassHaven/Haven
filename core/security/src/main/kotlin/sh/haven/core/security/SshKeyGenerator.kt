package sh.haven.core.security

import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/**
 * Generates SSH keypairs using BouncyCastle (Ed25519) or JCA (RSA, ECDSA).
 * Private keys are returned as raw bytes — caller is responsible for encryption.
 *
 * Also hosts the OpenSSH wire-format encoders shared by software keys and
 * hardware-resident keys ([HardwareSshKeyManager]): ECDSA public key blobs
 * ([encodeEcdsaPublicKey], curve-parameterized P-256/P-384) and the
 * authorized_keys line builder ([openSshPublicKeyLine]).
 */
object SshKeyGenerator {

    /**
     * ECDSA curves supported in OpenSSH wire encoding. [sshKeyType] is the
     * key type string, [wireName] the curve name string inside the blob, and
     * [coordinateBytes] the fixed width each coordinate half is padded to in
     * the uncompressed EC point.
     */
    enum class EcdsaCurve(val sshKeyType: String, val wireName: String, val coordinateBytes: Int) {
        NIST_P256("ecdsa-sha2-nistp256", "nistp256", 32),
        NIST_P384("ecdsa-sha2-nistp384", "nistp384", 48),
    }

    enum class KeyType(val displayName: String, val sshName: String) {
        ED25519("Ed25519", "ssh-ed25519"),
        RSA_4096("RSA 4096", "ssh-rsa"),
        ECDSA_384("ECDSA P-384", EcdsaCurve.NIST_P384.sshKeyType),
    }

    data class GeneratedKey(
        val type: KeyType,
        val privateKeyBytes: ByteArray,
        val publicKeyOpenSsh: String,
        val fingerprintSha256: String,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is GeneratedKey) return false
            return type == other.type &&
                    privateKeyBytes.contentEquals(other.privateKeyBytes) &&
                    publicKeyOpenSsh == other.publicKeyOpenSsh
        }

        override fun hashCode(): Int = privateKeyBytes.contentHashCode()
    }

    fun generate(type: KeyType, comment: String = ""): GeneratedKey {
        return when (type) {
            KeyType.ED25519 -> generateEd25519(comment)
            KeyType.RSA_4096 -> generateRsa(comment)
            KeyType.ECDSA_384 -> generateEcdsa(comment)
        }
    }

    private fun generateEd25519(comment: String): GeneratedKey {
        val generator = Ed25519KeyPairGenerator()
        generator.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val keyPair = generator.generateKeyPair()

        val privateKey = keyPair.private as Ed25519PrivateKeyParameters
        val publicKey = keyPair.public as Ed25519PublicKeyParameters

        val pubKeyBlob = encodeEd25519PublicKey(publicKey.encoded)
        val pubLine = openSshPublicKeyLine("ssh-ed25519", pubKeyBlob, comment)

        val fingerprint = fingerprintSha256(pubKeyBlob)

        return GeneratedKey(
            type = KeyType.ED25519,
            privateKeyBytes = privateKey.encoded,
            publicKeyOpenSsh = pubLine,
            fingerprintSha256 = fingerprint,
        )
    }

    private fun generateRsa(comment: String): GeneratedKey {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(4096, SecureRandom())
        val keyPair: KeyPair = kpg.generateKeyPair()

        val rsaPub = keyPair.public as RSAPublicKey
        val pubKeyBlob = encodeRsaPublicKey(rsaPub)
        val pubLine = openSshPublicKeyLine("ssh-rsa", pubKeyBlob, comment)

        val fingerprint = fingerprintSha256(pubKeyBlob)

        return GeneratedKey(
            type = KeyType.RSA_4096,
            privateKeyBytes = keyPair.private.encoded,
            publicKeyOpenSsh = pubLine,
            fingerprintSha256 = fingerprint,
        )
    }

    private fun generateEcdsa(comment: String): GeneratedKey {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(384, SecureRandom())
        val keyPair: KeyPair = kpg.generateKeyPair()

        val ecPub = keyPair.public as ECPublicKey
        val pubKeyBlob = encodeEcdsaPublicKey(ecPub, EcdsaCurve.NIST_P384)
        val pubLine = openSshPublicKeyLine(EcdsaCurve.NIST_P384.sshKeyType, pubKeyBlob, comment)

        val fingerprint = fingerprintSha256(pubKeyBlob)

        return GeneratedKey(
            type = KeyType.ECDSA_384,
            privateKeyBytes = keyPair.private.encoded,
            publicKeyOpenSsh = pubLine,
            fingerprintSha256 = fingerprint,
        )
    }

    // SSH wire encoding helpers

    private fun encodeEd25519PublicKey(rawPublicKey: ByteArray): ByteArray {
        val keyType = "ssh-ed25519".toByteArray()
        val buf = ByteArray(4 + keyType.size + 4 + rawPublicKey.size)
        var offset = 0
        offset = writeBytes(buf, offset, keyType)
        writeBytes(buf, offset, rawPublicKey)
        return buf
    }

    private fun encodeRsaPublicKey(key: RSAPublicKey): ByteArray {
        val keyType = "ssh-rsa".toByteArray()
        val e = key.publicExponent.toByteArray()
        val n = key.modulus.toByteArray()
        val buf = ByteArray(4 + keyType.size + 4 + e.size + 4 + n.size)
        var offset = 0
        offset = writeBytes(buf, offset, keyType)
        offset = writeBytes(buf, offset, e)
        writeBytes(buf, offset, n)
        return buf
    }

    /**
     * OpenSSH ECDSA public key blob:
     * `string keyType || string curveName || string point`, where point is the
     * uncompressed `0x04 || X || Y` with each coordinate zero-padded on the
     * left to [EcdsaCurve.coordinateBytes]. Curve-parameterized so hardware
     * P-256 keys and software P-384 keys share one encoder.
     */
    internal fun encodeEcdsaPublicKey(key: ECPublicKey, curve: EcdsaCurve): ByteArray {
        val keyType = curve.sshKeyType.toByteArray()
        val curveName = curve.wireName.toByteArray()
        val point = key.w
        // BigInteger.toByteArray() is minimal two's-complement: a coordinate
        // with the top bit set arrives with an extra 0x00 sign byte (dropped
        // by takeLast), a small one shorter than the field width (left-padded).
        val xPad = point.affineX.toByteArray().padStartTo(curve.coordinateBytes)
        val yPad = point.affineY.toByteArray().padStartTo(curve.coordinateBytes)
        val pointBytes = ByteArray(1 + 2 * curve.coordinateBytes)
        pointBytes[0] = 0x04
        xPad.copyInto(pointBytes, 1)
        yPad.copyInto(pointBytes, 1 + curve.coordinateBytes)

        val buf = ByteArray(4 + keyType.size + 4 + curveName.size + 4 + pointBytes.size)
        var offset = 0
        offset = writeBytes(buf, offset, keyType)
        offset = writeBytes(buf, offset, curveName)
        writeBytes(buf, offset, pointBytes)
        return buf
    }

    /** authorized_keys line: `<sshName> <base64 blob>[ <comment>]`. */
    internal fun openSshPublicKeyLine(sshName: String, blob: ByteArray, comment: String): String {
        val b64 = Base64.getEncoder().encodeToString(blob)
        return "$sshName $b64${if (comment.isNotEmpty()) " $comment" else ""}"
    }

    private fun writeBytes(buf: ByteArray, offset: Int, data: ByteArray): Int {
        buf[offset] = (data.size shr 24 and 0xFF).toByte()
        buf[offset + 1] = (data.size shr 16 and 0xFF).toByte()
        buf[offset + 2] = (data.size shr 8 and 0xFF).toByte()
        buf[offset + 3] = (data.size and 0xFF).toByte()
        data.copyInto(buf, offset + 4)
        return offset + 4 + data.size
    }

    private fun ByteArray.padStartTo(length: Int): ByteArray {
        if (this.size >= length) return this.takeLast(length).toByteArray()
        val padded = ByteArray(length)
        this.copyInto(padded, length - this.size)
        return padded
    }

    internal fun fingerprintSha256(publicKeyBlob: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(publicKeyBlob)
        val b64 = Base64.getEncoder().withoutPadding().encodeToString(digest)
        return "SHA256:$b64"
    }
}
