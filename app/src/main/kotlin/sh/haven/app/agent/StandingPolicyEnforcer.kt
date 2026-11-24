package sh.haven.app.agent

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import sh.haven.core.data.db.entities.StandingPolicy
import sh.haven.core.data.repository.StandingPolicyRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Evaluates Tier-3 standing policies (VISION.md "Consent tiers") at the MCP
 * consent gate: when an installed, enabled, unexpired policy covers
 * (client, tool, args) and its rate ceiling has room, the call proceeds
 * without a consent prompt — still fully audited by the dispatch-level
 * [AgentAuditRecorder].
 *
 * Fail-closed by construction: any non-match (unknown client, tool not
 * listed, constraint mismatch, expired, disabled, rate exceeded, denylisted
 * tool) simply falls through to the normal consent prompt — a policy can
 * only ever *remove* a prompt that the user pre-authorized, never widen
 * beyond it.
 *
 * Rate ceilings are enforced with an in-memory rolling 60s window per
 * policy id. Process-scoped on purpose: a restart resets the window, which
 * errs on the side of allowing what the user already granted.
 */
@Singleton
class StandingPolicyEnforcer @Inject constructor(
    private val repository: StandingPolicyRepository,
) {

    /** Per-policy timestamps of covered calls inside the rolling window. */
    private val rateWindows = HashMap<String, ArrayDeque<Long>>()
    private val rateLock = Any()

    /**
     * True when an active standing policy covers this exact call and has
     * rate-ceiling room (the tick is recorded). False = fall back to the
     * consent prompt; never throws.
     */
    suspend fun permits(
        clientHint: String?,
        toolName: String,
        args: JSONObject,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (clientHint.isNullOrBlank()) return false
        if (toolName in NEVER_COVERABLE_TOOLS) return false
        val policy = repository.activePolicies(now).firstOrNull { p ->
            p.clientHint == clientHint &&
                toolName in parseToolNames(p.toolNamesJson) &&
                constraintsMatch(p.argConstraintsJson, args)
        } ?: return false
        return synchronized(rateLock) {
            val window = rateWindows.getOrPut(policy.id) { ArrayDeque() }
            while (window.isNotEmpty() && window.first() <= now - 60_000L) window.removeFirst()
            if (window.size >= policy.maxCallsPerMinute) {
                Log.i(TAG, "policy ${policy.id} rate ceiling hit (${policy.maxCallsPerMinute}/min) — falling back to prompt")
                false
            } else {
                window.addLast(now)
                true
            }
        }
    }

    companion object {
        private const val TAG = "StandingPolicy"

        /**
         * Tools a standing policy may never cover — Tier-4-shaped verbs
         * (replace the running app, rewire agent trust) plus the policy
         * tools themselves, so a reflex can never escalate or renew itself.
         * Enforced both at create_standing_policy and (defence in depth)
         * at evaluation here.
         */
        val NEVER_COVERABLE_TOOLS: Set<String> = setOf(
            "create_standing_policy",
            "revoke_standing_policy",
            "install_apk_from_url",
            "install_apk_from_backend",
            "unpair_mcp_client",
            "request_egress_grant",
            "get_egress_signing_secret",
        )

        /** Parse the JSON-array tool list; malformed JSON yields an empty set (no coverage). */
        fun parseToolNames(json: String): Set<String> = try {
            val arr = JSONArray(json)
            buildSet { for (i in 0 until arr.length()) add(arr.getString(i)) }
        } catch (e: Exception) {
            emptySet()
        }

        /**
         * Every key in [constraintsJson] must string-equal the call's argument
         * of the same name. Null/blank = unconstrained; malformed JSON = no
         * match (fail closed).
         */
        fun constraintsMatch(constraintsJson: String?, args: JSONObject): Boolean {
            if (constraintsJson.isNullOrBlank()) return true
            val constraints = try {
                JSONObject(constraintsJson)
            } catch (e: Exception) {
                return false
            }
            for (key in constraints.keys()) {
                val want = constraints.opt(key)?.toString() ?: return false
                val got = args.opt(key)?.toString()
                if (got != want) return false
            }
            return true
        }
    }
}
