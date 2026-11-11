package sh.haven.core.security

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parsed data from an OpenSSH SK private key file.
 * Contains the credential handle and public key — no actual private key material.
 */
data class SkKeyData(
    val algorithmName: String,
    val publicKeyBlob: ByteArray,
    val application: String,
    val credentialId: ByteArray,
    val flags: Byte,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SkKeyData) return false
        return algorithmName == other.algorithmName &&
            publicKeyBlob.contentEquals(other.publicKeyBlob) &&
            application == other.application &&
            credentialId.contentEquals(other.credentialId) &&
            flags == other.flags
    }

    override fun hashCode(): Int = credentialId.contentHashCode()

    companion object {
        private val MAGIC = "HAVEN_SK\u0000".toByteArray()

        /** Serialize for storage in SshKey.privateKeyBytes. */
        fun serialize(data: SkKeyData): ByteArray {
            val buf = ByteBuffer.allocate(
                MAGIC.size +
                    4 + data.algorithmName.toByteArray().size +
                    4 + data.publicKeyBlob.size +
                    4 + data.application.toByteArray().size +
                    4 + data.credentialId.size +
                    1
            )
            buf.order(ByteOrder.BIG_ENDIAN)
            buf.put(MAGIC)
            putString(buf, data.algorithmName.toByteArray())
            putBytes(buf, data.publicKeyBlob)
            putString(buf, data.application.toByteArray())
            putBytes(buf, data.credentialId)
            buf.put(data.flags)
            return buf.array().copyOf(buf.position())
        }

        /** Deserialize from SshKey.privateKeyBytes. */
        fun deserialize(bytes: ByteArray): SkKeyData {
            val buf = ByteBuffer.wrap(bytes)
            buf.order(ByteOrder.BIG_ENDIAN)
            val magic = ByteArray(MAGIC.size)
            buf.get(magic)
            require(magic.contentEquals(MAGIC)) { "Not an SK key blob" }
            val algorithmName = String(readBytes(buf))
            val publicKeyBlob = readBytes(buf)
            val application = String(readBytes(buf))
            val credentialId = readBytes(buf)
            val flags = buf.get()
            return SkKeyData(algorithmName, publicKeyBlob, application, credentialId, flags)
        }

        fun isSkKeyBlob(bytes: ByteArray): Boolean {
            if (bytes.size < MAGIC.size) return false
            return bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)
        }

        private fun putString(buf: ByteBuffer, data: ByteArray) = putBytes(buf, data)

        private fun putBytes(buf: ByteBuffer, data: ByteArray) {
            buf.putInt(data.size)
            buf.put(data)
        }

        private fun readBytes(buf: ByteBuffer): ByteArray {
            val len = buf.int
            val data = ByteArray(len)
            buf.get(data)
            return data
        }
    }
}
