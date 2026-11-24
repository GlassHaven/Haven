package sh.haven.core.data.agent

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Non-bypassable controlled-egress consent (Phase 2 of the internet-egress
 * gate). The critical property: a paired-client bypass, a session bypass, or
 * a windowed allow must NEVER short-circuit an egress grant — otherwise the
 * sandboxed agent could self-approve open internet.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EgressConsentTest {

    private fun target(host: String = "registry.example", port: Int = 443) = EgressTarget(
        requestId = "req-1", sandboxId = "sb-1", host = host, port = port,
        scheme = "https", reason = "Bash egress to $host",
    )

    @Test
    fun `allow once resolves ONCE`() = runTest(UnconfinedTestDispatcher()) {
        val mgr = AgentConsentManager()
        mgr.setForegroundActive(true)
        val call = async { mgr.requestEgressConsent(target(), "claude-egress-proxy") }
        val req = mgr.pending.value.single()
        assertEquals(AgentConsentManager.EGRESS_TOOL_NAME, req.toolName)
        assertEquals("registry.example", req.egress?.host)
        mgr.respondEgress(req.id, EgressDecision.ONCE)
        assertEquals(EgressDecision.ONCE, call.await())
        assertTrue(mgr.pending.value.isEmpty())
    }

    @Test
    fun `allow session resolves SESSION`() = runTest(UnconfinedTestDispatcher()) {
        val mgr = AgentConsentManager()
        mgr.setForegroundActive(true)
        val call = async { mgr.requestEgressConsent(target(), "claude-egress-proxy") }
        mgr.respondEgress(mgr.pending.value.single().id, EgressDecision.SESSION)
        assertEquals(EgressDecision.SESSION, call.await())
    }

    @Test
    fun `deny resolves DENY`() = runTest(UnconfinedTestDispatcher()) {
        val mgr = AgentConsentManager()
        mgr.setForegroundActive(true)
        val call = async { mgr.requestEgressConsent(target(), "claude-egress-proxy") }
        mgr.respondEgress(mgr.pending.value.single().id, EgressDecision.DENY)
        assertEquals(EgressDecision.DENY, call.await())
    }

    @Test
    fun `persistent client bypass does NOT short-circuit egress`() = runTest(UnconfinedTestDispatcher()) {
        val mgr = AgentConsentManager()
        mgr.setForegroundActive(true)
        mgr.setPersistentBypassClients(setOf("claude-egress-proxy"))
        val call = async { mgr.requestEgressConsent(target(), "claude-egress-proxy", timeoutMs = 1_000) }
        // Despite the persistent bypass, a prompt is queued (not auto-allowed).
        assertEquals("egress must still prompt under a persistent bypass", 1, mgr.pending.value.size)
        assertFalse(call.isCompleted)
        advanceTimeBy(1_001)
        assertEquals("unanswered egress fails closed", EgressDecision.DENY, call.await())
    }

    @Test
    fun `windowed allow does NOT short-circuit egress`() = runTest(UnconfinedTestDispatcher()) {
        val mgr = AgentConsentManager()
        mgr.setForegroundActive(true)
        // Arm a windowed allow for the same client+tool the egress request uses.
        mgr.armRetryWindow("claude-egress-proxy", AgentConsentManager.EGRESS_TOOL_NAME)
        val call = async { mgr.requestEgressConsent(target(), "claude-egress-proxy", timeoutMs = 1_000) }
        assertEquals("egress must still prompt under a windowed allow", 1, mgr.pending.value.size)
        advanceTimeBy(1_001)
        assertEquals(EgressDecision.DENY, call.await())
    }

    @Test
    fun `backgrounded egress holds then denies on timeout`() = runTest(UnconfinedTestDispatcher()) {
        val mgr = AgentConsentManager()
        // foreground=false: the request holds (observable), never auto-allows.
        val call = async { mgr.requestEgressConsent(target(), "claude-egress-proxy", timeoutMs = 1_000) }
        assertEquals("held egress request must be observable", 1, mgr.pending.value.size)
        assertFalse(call.isCompleted)
        advanceTimeBy(1_001)
        assertEquals(EgressDecision.DENY, call.await())
        assertTrue(mgr.pending.value.isEmpty())
    }

    @Test
    fun `ordinary respond cannot resolve an egress request`() = runTest(UnconfinedTestDispatcher()) {
        val mgr = AgentConsentManager()
        mgr.setForegroundActive(true)
        val call = async { mgr.requestEgressConsent(target(), "claude-egress-proxy", timeoutMs = 1_000) }
        val id = mgr.pending.value.single().id
        // The binary respond path must not complete the three-way egress deferred.
        mgr.respond(id, ConsentDecision.ALLOW)
        assertFalse("ordinary ALLOW must not resolve egress", call.isCompleted)
        mgr.respondEgress(id, EgressDecision.ONCE)
        assertEquals(EgressDecision.ONCE, call.await())
    }
}
