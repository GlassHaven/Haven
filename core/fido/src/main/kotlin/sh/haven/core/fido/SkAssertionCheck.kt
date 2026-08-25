package sh.haven.core.fido

/**
 * SSH SK signature flag bits (draft-miller-ssh-agent §3.1.4 / OpenSSH sk-api.h),
 * which are the same bits the authenticator sets in authenticatorData.
 */
object SkFlags {
    /** SSH_SK_USER_PRESENCE_REQUIRED — the key was touched. */
    const val USER_PRESENT: Int = 0x01

    /** SSH_SK_USER_VERIFICATION_REQUIRED — a PIN or biometric was checked. */
    const val USER_VERIFIED: Int = 0x04
}

/**
 * Check that an assertion actually carried the user verification Haven asked
 * for, and describe the mismatch if it did not.
 *
 * A key marked "Require PIN at sign-in" makes Haven run the CTAP2 clientPIN
 * exchange and attach a `pinUvAuthParam` to the GetAssertion. The authenticator
 * is then supposed to set the user-verified bit in the assertion, and OpenSSH
 * copies that bit into the signature blob. A server configured with
 * `verify-required` checks it and rejects the key if it is clear.
 *
 * Without this check the failure is invisible from the phone: the key is
 * touched, a signature is produced, Haven sends it, and the only symptom is the
 * server answering "Permission denied (publickey)" — which reads as the wrong
 * key rather than a ceremony that did not happen (#531).
 *
 * @param requestedUv whether the stored SK flags asked for user verification
 * @param assertionFlags the flags byte the authenticator returned
 * @return null when the assertion is consistent with what was asked, otherwise
 *   a sentence naming what is missing
 */
fun skAssertionUvMismatch(requestedUv: Boolean, assertionFlags: Byte): String? {
    if (!requestedUv) return null
    val flags = assertionFlags.toInt()
    if (flags and SkFlags.USER_VERIFIED != 0) return null
    return "This key is set to require a PIN at sign-in, but the security key " +
        "returned a signature without verifying you (flags=0x${"%02x".format(assertionFlags)}). " +
        "A server that enforces verify-required will reject it. Check that the " +
        "key has a PIN set, and that the credential was created with " +
        "verify-required if the server demands it."
}
