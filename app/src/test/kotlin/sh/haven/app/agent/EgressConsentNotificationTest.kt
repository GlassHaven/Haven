package sh.haven.app.agent

import androidx.core.app.NotificationCompat
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.app.HavenApp
import sh.haven.core.data.agent.AgentConsentManager
import sh.haven.core.data.agent.ConsentRequest
import sh.haven.core.data.agent.EgressDecision
import sh.haven.core.data.agent.EgressTarget

/**
 * Regression suite for #889: backgrounded non-bypassable egress consent
 * (`request_egress_grant`) must:
 * 1. Use a dedicated high-urgency notification channel + `CATEGORY_CALL` /
 *    `PRIORITY_MAX` (not `CATEGORY_RECOMMENDATION`, which OEMs suppress from
 *    heads-up banners) and attach `fullScreenIntent` only when permitted and
 *    only for egress requests.
 * 2. Wire 3 direct notification actions (`Allow once`, `Allow session`, `Deny`)
 *    that resolve `AgentConsentManager.respondEgress(requestId, ...)` directly,
 *    rather than `armRetryWindow` (which `requestEgressConsent` ignores).
 * 3. Cancel active egress notifications once their request leaves `pending`
 *    (answered on foreground sheet or 60s fail-closed timeout).
 */
class EgressConsentNotificationTest {

    private fun sampleEgressRequest(id: Long = 42L): ConsentRequest {
        val target = EgressTarget(
            requestId = "req-$id",
            sandboxId = "sbx-1",
            host = "api.example.com",
            port = 443,
            scheme = "https",
            reason = "curl https://api.example.com",
        )
        return ConsentRequest(
            id = id,
            toolName = AgentConsentManager.EGRESS_TOOL_NAME,
            clientHint = "claude-egress-proxy",
            summary = "Bash egress to api.example.com:443",
            egress = target,
        )
    }

    @Test
    fun `egress notification spec uses dedicated channel CATEGORY_CALL PRIORITY_MAX and 3 actions`() {
        val req = sampleEgressRequest(77L)
        val specNoFsi = HavenApp.consentNotificationSpec(req, fullScreenPermitted = false)
        assertEquals(HavenApp.EGRESS_CONSENT_CHANNEL_ID, specNoFsi.channelId)
        assertEquals(NotificationCompat.CATEGORY_CALL, specNoFsi.category)
        assertEquals(NotificationCompat.PRIORITY_MAX, specNoFsi.priority)
        assertFalse(specNoFsi.attachFullScreenIntent)
        assertEquals(
            listOf(
                ConsentActionReceiver.ACTION_EGRESS_ONCE,
                ConsentActionReceiver.ACTION_EGRESS_SESSION,
                ConsentActionReceiver.ACTION_EGRESS_DENY,
            ),
            specNoFsi.actionIntents,
        )

        val specWithFsi = HavenApp.consentNotificationSpec(req, fullScreenPermitted = true)
        assertTrue(specWithFsi.attachFullScreenIntent)

        // Ordinary tool consent stays on CONSENT_CHANNEL_ID without fullScreenIntent.
        val ordinary = ConsentRequest(
            id = 78L,
            toolName = "run_command",
            clientHint = "cli",
            summary = "Run uname -a",
        )
        val ordinarySpec = HavenApp.consentNotificationSpec(ordinary, fullScreenPermitted = true)
        assertEquals(HavenApp.CONSENT_CHANNEL_ID, ordinarySpec.channelId)
        assertFalse(ordinarySpec.attachFullScreenIntent)
        assertEquals(
            listOf(
                ConsentActionReceiver.ACTION_ALLOW,
                ConsentActionReceiver.ACTION_DENY,
            ),
            ordinarySpec.actionIntents,
        )
    }

    @Test
    fun `egress notification actions resolve parked background requestEgressConsent directly`() = runTest {
        for ((action, expected) in listOf(
            ConsentActionReceiver.ACTION_EGRESS_ONCE to EgressDecision.ONCE,
            ConsentActionReceiver.ACTION_EGRESS_SESSION to EgressDecision.SESSION,
            ConsentActionReceiver.ACTION_EGRESS_DENY to EgressDecision.DENY,
        )) {
            val mgr = AgentConsentManager()
            mgr.setForegroundActive(false)
            val target = EgressTarget(
                requestId = "r-${expected.name}",
                sandboxId = "sbx",
                host = "example.org",
                port = 443,
                scheme = "https",
                reason = "fetch",
            )
            val waiter = async {
                mgr.requestEgressConsent(target, clientHint = "proxy", timeoutMs = 10_000L)
            }
            val queued = mgr.pending.first { it.isNotEmpty() }.single()

            ConsentActionReceiver.dispatchConsentAction(
                consentManager = mgr,
                action = action,
                clientHint = queued.clientHint,
                toolName = queued.toolName,
                egressRequestId = queued.id,
            )

            assertEquals(expected, waiter.await())
            assertTrue(mgr.pending.value.isEmpty())
        }
    }

    @Test
    fun `stale egress notification tracker cancels ids removed from pending`() {
        val active = linkedSetOf(10L, 11L, 12L)
        val cancelled = HavenApp.reconcileActiveEgressNotifs(
            trackedIds = active,
            currentPending = listOf(sampleEgressRequest(11L)),
        )
        assertEquals(listOf(10L, 12L), cancelled)
        assertEquals(setOf(11L), active)
    }
}
