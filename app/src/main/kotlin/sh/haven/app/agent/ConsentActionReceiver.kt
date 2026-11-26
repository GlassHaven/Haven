package sh.haven.app.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import sh.haven.core.data.agent.AgentConsentManager
import sh.haven.core.data.agent.EgressDecision
import javax.inject.Inject

/**
 * Handles the **Allow / Deny** action buttons on the backgrounded-consent
 * notification (raised by [sh.haven.app.HavenApp] from
 * [AgentConsentManager.blockedWhileBackground]).
 *
 * The original tool call already failed closed (instant DENY) — the consent
 * gate never blocks. Tapping **Allow** here arms a short retry window for the
 * (client, tool) via [AgentConsentManager.armRetryWindow], so the agent's
 * retry of that action proceeds. **Deny** just dismisses the notification (the
 * call was already denied). This is how the user gets a real choice without
 * the consent gate ever waiting — the queue-and-wait approach was reverted
 * because it broke the fail-closed contract.
 *
 * The Allow action is marked `setAuthenticationRequired` (API 31+) by the
 * notification builder, so approving requires the device to be unlocked.
 */
@AndroidEntryPoint
class ConsentActionReceiver : BroadcastReceiver() {

    @Inject lateinit var consentManager: AgentConsentManager

    override fun onReceive(context: Context, intent: Intent) {
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, -1)
        if (notifId != -1) NotificationManagerCompat.from(context).cancel(notifId)

        val action = intent.action
        val client = intent.getStringExtra(EXTRA_CLIENT)
        val tool = intent.getStringExtra(EXTRA_TOOL)
        val egressRequestId = intent.getLongExtra(EXTRA_EGRESS_REQUEST_ID, -1L)

        if (action == ACTION_DENY) {
            Log.i(TAG, "consent notification: Deny/dismiss (call already failed closed)")
            return
        }
        if (action != ACTION_ALLOW &&
            action != ACTION_EGRESS_ONCE &&
            action != ACTION_EGRESS_SESSION &&
            action != ACTION_EGRESS_DENY
        ) {
            return
        }

        // respondEgress / armRetryWindow are suspend; keep the receiver alive across them.
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                dispatchConsentAction(
                    consentManager = consentManager,
                    action = action,
                    clientHint = client,
                    toolName = tool,
                    egressRequestId = egressRequestId,
                )
            } catch (e: Exception) {
                Log.w(TAG, "consent action ($action) failed: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "ConsentAction"
        const val ACTION_ALLOW = "sh.haven.app.agent.CONSENT_ALLOW"
        const val ACTION_DENY = "sh.haven.app.agent.CONSENT_DENY"
        const val ACTION_EGRESS_ONCE = "sh.haven.app.agent.CONSENT_EGRESS_ONCE"
        const val ACTION_EGRESS_SESSION = "sh.haven.app.agent.CONSENT_EGRESS_SESSION"
        const val ACTION_EGRESS_DENY = "sh.haven.app.agent.CONSENT_EGRESS_DENY"
        const val EXTRA_CLIENT = "consent_client"
        const val EXTRA_TOOL = "consent_tool"
        const val EXTRA_NOTIF_ID = "consent_notif_id"
        const val EXTRA_EGRESS_REQUEST_ID = "consent_egress_request_id"

        internal suspend fun dispatchConsentAction(
            consentManager: AgentConsentManager,
            action: String?,
            clientHint: String?,
            toolName: String?,
            egressRequestId: Long,
        ) {
            when (action) {
                ACTION_EGRESS_ONCE -> if (egressRequestId >= 0L) {
                    consentManager.respondEgress(egressRequestId, EgressDecision.ONCE)
                }
                ACTION_EGRESS_SESSION -> if (egressRequestId >= 0L) {
                    consentManager.respondEgress(egressRequestId, EgressDecision.SESSION)
                }
                ACTION_EGRESS_DENY -> if (egressRequestId >= 0L) {
                    consentManager.respondEgress(egressRequestId, EgressDecision.DENY)
                }
                ACTION_ALLOW -> {
                    if (!toolName.isNullOrEmpty() && toolName != AgentConsentManager.EGRESS_TOOL_NAME) {
                        consentManager.armRetryWindow(clientHint, toolName)
                    }
                }
            }
        }
    }
}
