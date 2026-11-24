package sh.haven.app.agent

import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.data.agent.AgentConsentManager
import sh.haven.core.data.agent.EgressDecision
import sh.haven.core.mcp.McpError
import sh.haven.core.security.EgressGrantSigner

/**
 * Handler-glue tests for the controlled-egress tool: the decision→result
 * mapping, the HMAC signature over the canonical message (verified with the
 * same secret the proxy would hold), fail-closed timeout, and arg validation.
 * The secret source is injected so this runs on the JVM without Keystore.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EgressToolProviderTest {

    private val secret = "unit-test-secret".toByteArray()

    private fun provider(mgr: AgentConsentManager): EgressToolProvider =
        EgressToolProvider(mgr, mockk<Context>(relaxed = true), { "claude-egress-proxy" }, { secret })

    private fun args(
        requestId: String = "req-1",
        host: String = "registry.example",
        port: Int = 443,
    ) = JSONObject().apply {
        put("request_id", requestId)
        put("sandbox_id", "sb-1")
        put("host", host)
        put("port", port)
        put("scheme", "https")
        put("reason", "Bash egress to $host")
    }

    private fun verifySignature(result: JSONObject) {
        val expiry = if (result.isNull("expiry_ms")) null else result.getLong("expiry_ms")
        val canonical = EgressGrantSigner.canonicalMessage(
            result.getString("request_id"),
            result.getString("sandbox_id"),
            result.getString("host"),
            result.getInt("port"),
            result.getString("decision"),
            expiry,
            result.getString("grant_id"),
            result.getString("nonce"),
        )
        val sig = EgressGrantSigner.decodeSignature(result.getString("signature"))
        assertTrue("signature must verify with the provisioned secret", EgressGrantSigner.verify(secret, canonical, sig!!))
    }

    @Test
    fun `allow once returns a signed once grant with a short expiry`() = runTest(UnconfinedTestDispatcher()) {
        val mgr = AgentConsentManager()
        mgr.setForegroundActive(true)
        val handler = provider(mgr).tools()["request_egress_grant"]!!
        val call = async { handler.handle(args()) }
        mgr.respondEgress(mgr.pending.value.single().id, EgressDecision.ONCE)
        val result = (call.await() as ToolResult.Structured).structured
        assertEquals("once", result.getString("decision"))
        assertEquals("registry.example", result.getString("host"))
        assertEquals(443, result.getInt("port"))
        assertFalse(result.isNull("expiry_ms"))
        assertEquals(32, result.getString("nonce").length)
        verifySignature(result)
    }

    @Test
    fun `allow session returns a signed session grant with a long expiry`() = runTest(UnconfinedTestDispatcher()) {
        val mgr = AgentConsentManager()
        mgr.setForegroundActive(true)
        val handler = provider(mgr).tools()["request_egress_grant"]!!
        val call = async { handler.handle(args()) }
        mgr.respondEgress(mgr.pending.value.single().id, EgressDecision.SESSION)
        val result = (call.await() as ToolResult.Structured).structured
        assertEquals("session", result.getString("decision"))
        val expiry = result.getLong("expiry_ms")
        assertTrue("session expiry should be hours out", expiry - System.currentTimeMillis() > 60_000)
        verifySignature(result)
    }

    @Test
    fun `deny returns a signed deny with null expiry`() = runTest(UnconfinedTestDispatcher()) {
        val mgr = AgentConsentManager()
        mgr.setForegroundActive(true)
        val handler = provider(mgr).tools()["request_egress_grant"]!!
        val call = async { handler.handle(args()) }
        mgr.respondEgress(mgr.pending.value.single().id, EgressDecision.DENY)
        val result = (call.await() as ToolResult.Structured).structured
        assertEquals("deny", result.getString("decision"))
        assertTrue("deny carries a null expiry", result.isNull("expiry_ms"))
        verifySignature(result)
    }

    @Test
    fun `no answer fails closed to a signed deny`() = runTest(UnconfinedTestDispatcher()) {
        val mgr = AgentConsentManager()
        mgr.setForegroundActive(true)
        val handler = provider(mgr).tools()["request_egress_grant"]!!
        val call = async { handler.handle(args()) }
        assertEquals(1, mgr.pending.value.size)
        advanceTimeBy(60_001)
        val result = (call.await() as ToolResult.Structured).structured
        assertEquals("deny", result.getString("decision"))
        verifySignature(result)
    }

    @Test
    fun `persistent client bypass still prompts for egress`() = runTest(UnconfinedTestDispatcher()) {
        val mgr = AgentConsentManager()
        mgr.setForegroundActive(true)
        mgr.setPersistentBypassClients(setOf("claude-egress-proxy"))
        val handler = provider(mgr).tools()["request_egress_grant"]!!
        val call = async { handler.handle(args()) }
        assertEquals("egress must prompt despite a persistent bypass", 1, mgr.pending.value.size)
        assertFalse(call.isCompleted)
        mgr.respondEgress(mgr.pending.value.single().id, EgressDecision.DENY)
        assertEquals("deny", (call.await() as ToolResult.Structured).structured.getString("decision"))
    }

    @Test
    fun `missing host is invalid params`() = runTest {
        val mgr = AgentConsentManager()
        val handler = provider(mgr).tools()["request_egress_grant"]!!
        val bad = args().apply { remove("host") }
        val err = runCatching { handler.handle(bad) }.exceptionOrNull()
        assertTrue(err is McpError)
        assertEquals(-32602, (err as McpError).code)
    }

    @Test
    fun `tool is registered non-bypassable at EVERY_CALL`() {
        val mgr = AgentConsentManager()
        val handler = provider(mgr).tools()["request_egress_grant"]!!
        assertTrue(handler.nonBypassable)
        assertEquals(sh.haven.core.data.agent.ConsentLevel.EVERY_CALL, handler.consentLevel)
    }
}
