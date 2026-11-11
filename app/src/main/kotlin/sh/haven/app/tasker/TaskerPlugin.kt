package sh.haven.app.tasker

import android.content.Intent
import android.os.Bundle

/**
 * Locale/Tasker/MacroDroid plugin contract for the "Run command on a Haven
 * server" action (#367).
 *
 * Implements the twofortyfouram Locale Developer Platform *setting* contract
 * directly (just intent actions + a Bundle) rather than pulling in the
 * `com.twofortyfouram` library — the surface we use is tiny and stable:
 *
 * - The host (Tasker/MacroDroid) launches [ACTION_EDIT_SETTING] on our
 *   [TaskerEditActivity]; we return `RESULT_OK` with [EXTRA_BUNDLE] (our
 *   config) and [EXTRA_BLURB] (a short human summary shown on the action).
 * - When the macro fires, the host broadcasts [ACTION_FIRE_SETTING] to
 *   [TaskerFireReceiver] with the same [EXTRA_BUNDLE].
 *
 * The config [Bundle] carries [BUNDLE_PROFILE_ID] / [BUNDLE_PROFILE_LABEL]
 * (the selected Haven connection), [BUNDLE_COMMAND] (a shell command — the
 * host substitutes its own variables into this string before firing),
 * [BUNDLE_OVERLAY] (show the command live in Haven's terminal), and
 * [BUNDLE_BLOCK] (hold the macro until the command finishes).
 */
object TaskerPlugin {
    const val ACTION_EDIT_SETTING = "com.twofortyfouram.locale.intent.action.EDIT_SETTING"
    const val ACTION_FIRE_SETTING = "com.twofortyfouram.locale.intent.action.FIRE_SETTING"

    /** The config Bundle, both directions. */
    const val EXTRA_BUNDLE = "com.twofortyfouram.locale.intent.extra.BUNDLE"

    /** Short human summary of the action (≤ ~60 chars), edit → host only. */
    const val EXTRA_BLURB = "com.twofortyfouram.locale.intent.extra.BLURB"

    // Tasker/Locale variable passback (net.dinglisch.android.tasker.TaskerPlugin).
    // In "wait until finished" mode the fire receiver returns the command's
    // output to the host as local variables the macro can use downstream.
    /** Result-extras key holding the returned-variables Bundle. */
    const val EXTRA_VARIABLES_BUNDLE = "net.dinglisch.android.tasker.extras.VARIABLES"
    /**
     * Both directions on the same key. As an EDIT-result extra (String[]) it
     * declares which variables this action *sets*; on the incoming EDIT intent
     * the host uses it to advertise the variables *available* at this point in
     * the macro (see [hostVariables]).
     */
    const val BUNDLE_KEY_RELEVANT_VARIABLES = "net.dinglisch.android.tasker.RELEVANT_VARIABLES"

    /**
     * Config-bundle key (space-separated list of bundle keys) telling the host
     * to scan those values for its own variables and substitute them *before*
     * firing. Declaring [BUNDLE_COMMAND] here is what makes a typed Tasker
     * `%var` / MacroDroid magic-text token in the command actually get replaced
     * at fire time rather than run literally (#367). Lives inside the config
     * bundle per the Tasker plugin API, not on the result intent.
     */
    const val BUNDLE_VARIABLE_REPLACE_KEYS = "net.dinglisch.android.tasker.extras.VARIABLE_REPLACE_KEYS"

    const val VAR_STDOUT = "%hstdout"
    const val VAR_STDERR = "%hstderr"
    const val VAR_EXIT = "%hexit"
    val RELEVANT_VARIABLES = arrayOf(VAR_STDOUT, VAR_STDERR, VAR_EXIT)

    const val BUNDLE_PROFILE_ID = "sh.haven.tasker.PROFILE_ID"
    const val BUNDLE_PROFILE_LABEL = "sh.haven.tasker.PROFILE_LABEL"
    const val BUNDLE_COMMAND = "sh.haven.tasker.COMMAND"
    const val BUNDLE_OVERLAY = "sh.haven.tasker.OVERLAY"
    const val BUNDLE_BLOCK = "sh.haven.tasker.BLOCK"
    const val BUNDLE_CAPABILITY_TOKEN = "sh.haven.tasker.TOKEN"

    /** Build the config Bundle the edit activity hands back to the host. */
    fun buildBundle(
        profileId: String,
        profileLabel: String,
        command: String,
        overlay: Boolean,
        block: Boolean,
        secret: String? = null,
    ): Bundle = populateBundle(Bundle(), profileId, profileLabel, command, overlay, block, secret)

    internal fun populateBundle(
        target: Bundle,
        profileId: String,
        profileLabel: String,
        command: String,
        overlay: Boolean,
        block: Boolean,
        secret: String? = null,
    ): Bundle = target.apply {
        putString(BUNDLE_PROFILE_ID, profileId)
        putString(BUNDLE_PROFILE_LABEL, profileLabel)
        putString(BUNDLE_COMMAND, command)
        putBoolean(BUNDLE_OVERLAY, overlay)
        putBoolean(BUNDLE_BLOCK, block)
        if (!secret.isNullOrBlank()) {
            // Bind the capability token to the target profile + execution mode,
            // intentionally excluding BUNDLE_COMMAND so Tasker/MacroDroid can
            // substitute %vars into BUNDLE_COMMAND at fire time (#367).
            putString(BUNDLE_CAPABILITY_TOKEN, computeCapabilityToken(secret, profileId, overlay, block))
        }
        // Let the host substitute its own variables into the command at fire
        // time (Tasker %vars / MacroDroid magic text). Harmless if the host
        // ignores it; the receiver just sees the already-substituted command.
        putString(BUNDLE_VARIABLE_REPLACE_KEYS, BUNDLE_COMMAND)
    }

    internal fun computeCapabilityToken(
        secret: String,
        profileId: String,
        overlay: Boolean,
        block: Boolean,
    ): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val payload = "v1\u0000$profileId\u0000$overlay\u0000$block".toByteArray(Charsets.UTF_8)
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload))
    }

    /**
     * Variables the host advertises as available in this macro, read from the
     * incoming EDIT intent. Tasker always populates this; other hosts may or
     * may not — an empty list means the edit screen shows no insert button
     * rather than an empty picker. The tokens are inserted verbatim (already
     * carrying the host's own prefix/syntax), so nothing here guesses format.
     */
    fun hostVariables(intent: Intent): List<String> =
        intent.getStringArrayExtra(BUNDLE_KEY_RELEVANT_VARIABLES)
            ?.filter { it.isNotBlank() }
            .orEmpty()

    /**
     * A config Bundle is valid only if it carries a non-blank profile id and
     * command — guards against a host replaying a Bundle from an older/other
     * plugin version (the contract's "reject unknown Bundle" rule).
     */
    fun isValid(bundle: Bundle?): Boolean =
        bundle != null &&
            !bundle.getString(BUNDLE_PROFILE_ID).isNullOrBlank() &&
            !bundle.getString(BUNDLE_COMMAND).isNullOrBlank()

    /**
     * Resolve the config for a fired [intent]. Prefers the Locale nested
     * [EXTRA_BUNDLE] (how Tasker/MacroDroid's plugin flow passes it); falls
     * back to the same keys as **flat** intent extras, so the action also
     * works from a generic "Send Intent" step (and is reachable via `adb
     * shell am broadcast`, which can't build a nested Bundle). Returns null
     * if neither form carries a valid profile id + command.
     */
    fun configFrom(intent: Intent): Bundle? {
        intent.getBundleExtra(EXTRA_BUNDLE)?.let { if (isValid(it)) return it }
        val flat = Bundle().apply {
            putString(BUNDLE_PROFILE_ID, intent.getStringExtra(BUNDLE_PROFILE_ID))
            putString(BUNDLE_PROFILE_LABEL, intent.getStringExtra(BUNDLE_PROFILE_LABEL))
            putString(BUNDLE_COMMAND, intent.getStringExtra(BUNDLE_COMMAND))
            putBoolean(BUNDLE_OVERLAY, intent.getBooleanExtra(BUNDLE_OVERLAY, false))
            putBoolean(BUNDLE_BLOCK, intent.getBooleanExtra(BUNDLE_BLOCK, false))
        }
        return if (isValid(flat)) flat else null
    }

    /**
     * Resolve and cryptographically authenticate a fired [intent] against the
     * device-local [secret] minted when the user saved an action in
     * [TaskerEditActivity]. Rejects:
     * - all broadcasts when [secret] is null/blank (plugin never configured);
     * - flat intent extras without a nested [EXTRA_BUNDLE];
     * - bundles missing or failing constant-time HMAC verification on
     *   `(profileId, overlay, block)`.
     */
    fun verifiedConfigFrom(intent: Intent, secret: String?): Bundle? {
        if (secret.isNullOrBlank()) return null
        val bundle = intent.getBundleExtra(EXTRA_BUNDLE) ?: return null
        if (!isValid(bundle)) return null
        val token = bundle.getString(BUNDLE_CAPABILITY_TOKEN)?.takeIf { it.isNotBlank() } ?: return null
        val profileId = bundle.getString(BUNDLE_PROFILE_ID) ?: return null
        val overlay = bundle.getBoolean(BUNDLE_OVERLAY, false)
        val block = bundle.getBoolean(BUNDLE_BLOCK, false)
        val expected = computeCapabilityToken(secret, profileId, overlay, block)
        val ok = java.security.MessageDigest.isEqual(
            expected.toByteArray(Charsets.US_ASCII),
            token.toByteArray(Charsets.US_ASCII),
        )
        return if (ok) bundle else null
    }
}
