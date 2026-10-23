package sh.haven.core.ssh

import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import android.util.Log
import com.jcraft.jsch.Identity
import sh.haven.core.data.keystore.BiometricGate
import sh.haven.core.security.HardwareSshKeyManager
import sh.haven.core.security.derEcdsaToSshBlob
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.Signature
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "KeystoreSshIdentity"

/** The only key algorithm hardware-resident SSH keys use (KeyMint: no ed25519/rsa). */
const val HARDWARE_SSH_KEY_ALG = "ecdsa-sha2-nistp256"

/**
 * The two AndroidKeyStore touchpoints a [KeystoreSshIdentity] needs, behind
 * one port so the identity is unit-testable with hand-rolled fakes — the
 * production collaborators are Hilt singletons with final classes.
 */
interface HardwareSshKeySigner {
    /**
     * A SHA256withECDSA [Signature] bound to the private key under [alias],
     * still awaiting its per-operation biometric authorization.
     *
     * @throws KeyPermanentlyInvalidatedException new biometric enrollment
     *   invalidated the key.
     * @throws IllegalArgumentException no hardware key exists under [alias].
     */
    fun initSignature(alias: String): Signature

    /**
     * Authorize [signature] through the BiometricPrompt CryptoObject path
     * ([BiometricGate.request] with a signature skips the 30s session window
     * — per-op Keystore keys demand a fresh auth every use). Returns the
     * gate's decision; never throws for a user outcome.
     */
    suspend fun authorize(label: String, detail: String?, signature: Signature): BiometricGate.Decision
}

/**
 * [HardwareSshKeySigner] over the real [HardwareSshKeyManager] +
 * [BiometricGate]. Pure glue: both are @Singleton stateful collaborators
 * (the gate is the one the foreground Activity observes), so callers inject
 * this adapter rather than the pair.
 */
@Singleton
class KeystoreSshKeySigner @Inject constructor(
    private val manager: HardwareSshKeyManager,
    private val gate: BiometricGate,
) : HardwareSshKeySigner {
    override fun initSignature(alias: String): Signature = manager.initSignature(alias)
    override suspend fun authorize(
        label: String,
        detail: String?,
        signature: Signature,
    ): BiometricGate.Decision = gate.request(label = label, detail = detail, signature = signature)
}

/**
 * The per-signature ceremony for a hardware-resident key was refused or
 * could not run: the user denied the biometric prompt, it timed out, or no
 * Activity was foreground to render it. Fail-closed: the connect must stop,
 * not slide to the next credential (#559 semantics for security-chip keys).
 */
class HardwareKeySignDeclinedException(message: String) : RuntimeException(message)

/**
 * The hardware key under this alias can no longer sign at all — invalidated
 * by a biometric re-enrollment, or gone from the chip entirely (deleted, or
 * the DB row was restored onto a different device). The remedy is the same
 * either way: delete the row and generate the key again.
 */
class HardwareKeyInvalidatedException(message: String) : RuntimeException(message)

/**
 * JSch [Identity] whose private key lives in this device's Android Keystore
 * (StrongBox/TEE, `hardwareAlias` on the [sh.haven.core.data.db.entities.SshKey]
 * row). Mirror of [sh.haven.core.fido.FidoIdentity]: the private half never
 * exists as bytes, and [getSignature] runs the ceremony —
 *
 *  1. [HardwareSshKeySigner.initSignature] binds a Signature to the alias;
 *  2. [HardwareSshKeySigner.authorize] drives the BiometricPrompt
 *     CryptoObject (BIOMETRIC_STRONG, per-op, no session window);
 *  3. update+sign yields an ASN.1 DER `SEQUENCE{r,s}`;
 *  4. [derEcdsaToSshBlob] + the wire assembly below produce what JSch puts
 *     on the wire: `string "ecdsa-sha2-nistp256", string( mpint r ‖ mpint s )`
 *     (RFC 5656; the same composite JSch's KeyPairECDSA returns).
 *
 * ### Failure contract (fail-closed, #559)
 *
 * JSch's UserAuthPublicKey treats a **null** return from getSignature as
 * "skip this identity, offer the next one", while any **thrown exception**
 * aborts the whole connect. A declined biometric prompt must therefore THROW
 * — returning null would quietly move the connect on to the next credential,
 * which is exactly the bug #559 fixed for software keys. Both exception
 * types extend [RuntimeException] deliberately: JSch's auth loop rethrows
 * RuntimeExceptions raw but folds checked exceptions into a generic
 * "Auth fail" [com.jcraft.jsch.JSchException], losing the message that tells
 * the user what to do next.
 *
 * The **forwarded agent** path is the one deliberate exception to throwing:
 * there [AgentSafeIdentity] wraps this class and converts throws to null,
 * because an exception escaping ChannelAgentForwarding would kill the whole
 * SSH session instead of failing the single remote request.
 */
class KeystoreSshIdentity(
    /** AndroidKeyStore alias of the hardware-resident private key. */
    private val alias: String,
    /** The key's SSH wire public blob (as stored in the entity's public line). */
    private val publicKeyBlob: ByteArray,
    private val signer: HardwareSshKeySigner,
    /** Key name for the biometric prompt so the user knows what they are unlocking. */
    private val keyLabel: String? = null,
    /** Second prompt line (fingerprint), matching the software-key unlock prompt. */
    private val fingerprintSha256: String? = null,
) : Identity {

    private val promptName: String get() = keyLabel ?: alias

    override fun getAlgName(): String = HARDWARE_SSH_KEY_ALG

    override fun getName(): String = "haven-keystore-$alias"

    override fun getPublicKeyBlob(): ByteArray = publicKeyBlob

    override fun isEncrypted(): Boolean = false

    override fun setPassphrase(passphrase: ByteArray?): Boolean = true

    override fun decrypt(): Boolean = true

    override fun clear() {}

    /**
     * Sign SSH auth data with the security chip. Called on JSch's I/O
     * thread; blocks on the biometric ceremony exactly like [sh.haven.core.fido.FidoIdentity]
     * blocks on the FIDO2 touch (JSch auth is synchronous).
     */
    override fun getSignature(data: ByteArray): ByteArray = getSignature(data, HARDWARE_SSH_KEY_ALG)

    override fun getSignature(data: ByteArray, alg: String): ByteArray {
        val signature = initOrThrow()

        val decision = runBlocking { signer.authorize("Sign with $promptName", fingerprintSha256, signature) }
        when (decision) {
            BiometricGate.Decision.ALLOW -> { /* ceremony done — the Signature is now authorized */ }
            BiometricGate.Decision.DENY -> throw HardwareKeySignDeclinedException(
                "Authentication was declined for security-chip key \"$promptName\".",
            )
            BiometricGate.Decision.UNAVAILABLE -> throw HardwareKeySignDeclinedException(
                "Security-chip key \"$promptName\" needs authentication, and the prompt " +
                    "could not be shown. Open Haven and try again.",
            )
        }

        val der = signOrThrow(signature, data)
        Log.d(TAG, "hardware key '$promptName' signed ${data.size}b via Keystore")
        return assembleSshEcdsaSignature(alg, derEcdsaToSshBlob(der))
    }

    /** [HardwareSshKeySigner.initSignature] with the invalidated/missing-key failures translated. */
    private fun initOrThrow(): Signature = try {
        signer.initSignature(alias)
    } catch (e: KeyPermanentlyInvalidatedException) {
        Log.e(TAG, "hardware key '$promptName' invalidated by biometric re-enrollment", e)
        throw HardwareKeyInvalidatedException(
            "Security-chip key \"$promptName\" was invalidated because a new biometric " +
                "was enrolled on this device. Delete the key in Haven (Keys tab) and " +
                "generate it again, then update the server's authorized_keys.",
        )
    } catch (e: IllegalArgumentException) {
        Log.e(TAG, "no hardware key under alias '$alias' in the security chip", e)
        throw HardwareKeyInvalidatedException(
            "Security-chip key \"$promptName\" is no longer present in this device's " +
                "security chip (it was deleted, or the key list was restored from a " +
                "backup to another device). Delete the key in Haven and generate it again.",
        )
    }

    /** update+sign with the per-op auth failures translated (both are fail-closed). */
    private fun signOrThrow(signature: Signature, data: ByteArray): ByteArray = try {
        signature.update(data)
        signature.sign()
    } catch (e: KeyPermanentlyInvalidatedException) {
        throw HardwareKeyInvalidatedException(
            "Security-chip key \"$promptName\" was invalidated because a new biometric " +
                "was enrolled on this device. Delete the key in Haven (Keys tab) and " +
                "generate it again, then update the server's authorized_keys.",
        )
    } catch (e: UserNotAuthenticatedException) {
        // ALLOW arrived but the Keystore did not attach the auth to this
        // Signature object — nothing to fall back to, refuse the signature.
        Log.e(TAG, "Keystore demanded a fresh auth at sign time for '$promptName'", e)
        throw HardwareKeySignDeclinedException(
            "Security-chip key \"$promptName\" could not be authorized for signing. " +
                "Try connecting again.",
        )
    }
}

/**
 * `string algorithm_name ‖ string signature` — the SSH "signature" field for
 * publickey auth ([com.jcraft.jsch.Buffer.fromBytes] shape JSch expects from
 * [Identity.getSignature]). [rAndS] is the mpint pair from [derEcdsaToSshBlob].
 */
internal fun assembleSshEcdsaSignature(algName: String, rAndS: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    writeSshString(out, algName.toByteArray(Charsets.US_ASCII))
    writeSshString(out, rAndS)
    return out.toByteArray()
}

internal fun writeSshString(out: ByteArrayOutputStream, data: ByteArray) {
    val len = ByteBuffer.allocate(4)
    len.order(ByteOrder.BIG_ENDIAN)
    len.putInt(data.size)
    out.write(len.array())
    out.write(data)
}

/**
 * Parse the entity's OpenSSH public key line (`"ecdsa-sha2-nistp256 <base64> [comment]"`)
 * into the wire blob JSch's `Identity.getPublicKeyBlob` must return, and
 * check it really is the one algorithm hardware keys use. Centralised here
 * because both the auth path and the agent-forwarding path register the same
 * line from the DB row; a row restored from another device could carry
 * anything, and failing loudly at add time beats a mystery auth failure.
 */
internal fun parseHardwarePublicKeyBlob(publicKeyOpenSsh: String): ByteArray {
    val parts = publicKeyOpenSsh.trim().split(Regex("\\s+"), limit = 3)
    require(parts.size >= 2) { "public key line has no base64 field: '$publicKeyOpenSsh'" }
    require(parts[0] == HARDWARE_SSH_KEY_ALG) {
        "hardware-resident keys are $HARDWARE_SSH_KEY_ALG, this row says '${parts[0]}'"
    }
    val blob = try {
        Base64.getDecoder().decode(parts[1])
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("public key line's base64 field is malformed", e)
    }
    // RFC 5656 blob: string "ecdsa-sha2-nistp256" first — a mismatched (or
    // truncated) decode means the stored line is not the key the chip holds.
    // The type's CONTENT is compared, not just its length: nistp256/p384
    // type strings are the same length, so a length check alone would wave
    // a p384 line through.
    require(blob.size > 4 + HARDWARE_SSH_KEY_ALG.length) {
        "decoded blob is too short to be an $HARDWARE_SSH_KEY_ALG public key"
    }
    val typeLen = ((blob[0].toInt() and 0xFF) shl 24) or ((blob[1].toInt() and 0xFF) shl 16) or
        ((blob[2].toInt() and 0xFF) shl 8) or (blob[3].toInt() and 0xFF)
    require(
        typeLen == HARDWARE_SSH_KEY_ALG.length &&
            String(blob, 4, typeLen, Charsets.US_ASCII) == HARDWARE_SSH_KEY_ALG,
    ) {
        "decoded blob is not an $HARDWARE_SSH_KEY_ALG public key"
    }
    return blob
}
