package sh.haven.feature.keys

import sh.haven.core.data.db.entities.SshKey

/**
 * #531 wrong-row guard. `SshKey.id` defaults to a fresh UUID and
 * `SshKeyDao.upsert` conflicts only on id, so importing the same security
 * key twice used to mint two rows with an identical fingerprint. Toggling
 * "Require PIN" on the row the user does not sign with then changes
 * nothing at authentication time. These helpers let the ViewModel refuse a
 * duplicate import and warn when a toggle is ambiguous.
 */

/** First stored row with the same [fingerprint] AND [keyType], if any. */
internal fun findDuplicateByKeyType(
    rows: List<SshKey>,
    fingerprint: String,
    keyType: String,
): SshKey? = rows.firstOrNull {
    it.fingerprintSha256 == fingerprint && it.keyType == keyType
}

/** Appends [warning] to a base message when non-null, separated by a space. */
internal fun String.plusAmbiguity(warning: String?): String =
    if (warning == null) this else "$this $warning"

/**
 * Message surfaced when the verify-required toggle lands on a row whose
 * fingerprint is shared by [count] other rows, or null when the toggle is
 * unambiguous. The count must name the OTHER rows, not the total.
 */
internal fun verifyRequiredAmbiguityWarning(target: SshKey, others: List<SshKey>): String? {
    val count = others.count {
        it.fingerprintSha256 == target.fingerprintSha256 && it !== target
    }
    if (count == 0) return null
    return "$count other saved key(s) share this fingerprint - if the PIN prompt " +
        "still does not appear on connect, you may have toggled a duplicate row. " +
        "Delete the extra copies and keep the one the connection uses."
}
