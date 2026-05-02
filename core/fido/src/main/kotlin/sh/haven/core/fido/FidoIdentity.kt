package sh.haven.core.fido

import android.util.Log
import com.jcraft.jsch.Identity
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "FidoIdentity"

/**
 * JSch Identity implementation for FIDO2 SK keys.
 *
 * When JSch calls getSignature(), this delegates to the FIDO2 authenticator
 * (USB/NFC security key) and assembles the SSH SK signature wire format.
 *
 * Wire format for SSH SK signatures:
 *   string  algorithm_name (e.g. "sk-ssh-ed25519@openssh.com")
 *   string  raw_signature
 *   byte    flags
 *   uint32  counter
 */
class FidoIdentity(
    private val skKeyData: SkKeyData,
    private val authenticator: FidoAuthenticator,
) : Identity {

    override fun getAlgName(): String = skKeyData.algorithmName

    override fun getName(): String = "haven-fido-${skKeyData.algorithmName}"

    override fun getPublicKeyBlob(): ByteArray = skKeyData.publicKeyBlob

    override fun isEncrypted(): Boolean = false

    override fun setPassphrase(passphrase: ByteArray?): Boolean = true

    override fun decrypt(): Boolean = true

    override fun clear() {}

    /**
     * Sign SSH authentication data using the FIDO2 hardware authenticator.
     *
     * This is called on JSch's I/O thread and will block until:
     * 1. A security key is connected (USB) or tapped (NFC)
     * 2. The user physically touches the security key
     */
    override fun getSignature(data: ByteArray): ByteArray = getSignature(data, algName)

    override fun getSignature(data: ByteArray, alg: String): ByteArray {
        // SSH SK key flags (drafts-miller-ssh-agent §3.1.4 / sk-api.h):
        //   0x01 = SSH_SK_USER_PRESENCE_REQUIRED  — always set; CTAP2 up:true.
        //   0x04 = SSH_SK_USER_VERIFICATION_REQUIRED  — `ssh-keygen -O verify-required`.
        //          We must do CTAP2 PIN protocol before GetAssertion, otherwise
        //          the authenticator filters this credential out of the allowList
        //          and returns CTAP2_ERR_NO_CREDENTIALS (0x2E).
        val requireUv = (skKeyData.flags.toInt() and 0x04) != 0

        Log.d(TAG, "getSignature called: alg=$alg, dataLen=${data.size}")
        Log.d(TAG, "Requesting FIDO2 assertion from security key...")
        Log.d(TAG, "  rpId (application): ${skKeyData.application}")
        Log.d(TAG, "  credentialId: ${skKeyData.credentialId.size} bytes")
        Log.d(TAG, "  flags=0x${"%02x".format(skKeyData.flags)} requireUv=$requireUv")

        // Block the JSch thread while waiting for FIDO2 hardware response.
        // This is intentional — JSch's auth is synchronous.
        val result = try {
            runBlocking {
                authenticator.getAssertion(
                    rpId = skKeyData.application,
                    message = data,
                    credentialId = skKeyData.credentialId,
                    requireUv = requireUv,
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "FIDO2 assertion failed: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }

        Log.d(TAG, "FIDO2 assertion received: sig=${result.signature.size}b, " +
            "flags=0x${"%02x".format(result.flags)}, counter=${result.counter}")

        // Assemble SSH SK signature wire format
        val sigBlob = assembleSshSkSignature(alg, result.signature, result.flags, result.counter)
        Log.d(TAG, "Assembled SSH SK signature: ${sigBlob.size} bytes")
        return sigBlob
    }

    /**
     * Assemble the SSH SK signature wire format:
     *   string  algorithm_name
     *   string  raw_signature
     *   byte    flags
     *   uint32  counter
     */
    private fun assembleSshSkSignature(
        algName: String,
        rawSignature: ByteArray,
        flags: Byte,
        counter: Int,
    ): ByteArray {
        val out = ByteArrayOutputStream()

        // string algorithm_name
        writeString(out, algName.toByteArray())

        // string raw_signature
        writeString(out, rawSignature)

        // byte flags
        out.write(flags.toInt() and 0xFF)

        // uint32 counter
        val counterBuf = ByteBuffer.allocate(4)
        counterBuf.order(ByteOrder.BIG_ENDIAN)
        counterBuf.putInt(counter)
        out.write(counterBuf.array())

        return out.toByteArray()
    }

    private fun writeString(out: ByteArrayOutputStream, data: ByteArray) {
        val lenBuf = ByteBuffer.allocate(4)
        lenBuf.order(ByteOrder.BIG_ENDIAN)
        lenBuf.putInt(data.size)
        out.write(lenBuf.array())
        out.write(data)
    }
}
