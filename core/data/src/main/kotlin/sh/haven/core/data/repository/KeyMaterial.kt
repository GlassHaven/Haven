package sh.haven.core.data.repository

import sh.haven.core.data.db.entities.SshKey

/**
 * The result of asking for a stored key's private bytes.
 *
 * Exists so that "the user declined the prompt" survives the trip up to
 * whoever has to act on it. It used to be flattened into a null, which
 * read identically to "no such key", and so an SSH connect responded to
 * a declined prompt by offering the server a different credential
 * (#559).
 */
sealed class KeyMaterial {

    /** Decrypted private key material. Secret — do not log or persist. */
    class Available(val bytes: ByteArray) : KeyMaterial()

    /**
     * The private key lives in the device's Android Keystore under
     * [alias]; there are no local bytes to decrypt, ever. Callers sign
     * by handing the alias to the Keystore-backed signing path, not by
     * asking for material out of this branch.
     */
    data class Hardware(val alias: String) : KeyMaterial()

    /** No such key, or its bytes could not be decrypted. */
    data object Missing : KeyMaterial()

    /**
     * The key is gated behind a human ack that was not given. [reason]
     * is user-facing and safe to show; it names the key but carries no
     * key material.
     */
    data class Declined(val reason: String) : KeyMaterial()
}

/**
 * Keys that could be decrypted, and the ones the user declined to
 * unlock. Auth paths must not treat [declined] as absence — see
 * [KeyMaterial.Declined].
 */
data class DecryptedKeys(
    val keys: List<SshKey>,
    val declined: List<KeyMaterial.Declined>,
)

/**
 * Thrown when a credential the user declined to unlock is required to
 * carry on. Caught by the connect paths, whose job is to report it and
 * stop rather than to try the next credential.
 */
class KeyUnlockDeclinedException(val reason: String) : Exception(reason)
