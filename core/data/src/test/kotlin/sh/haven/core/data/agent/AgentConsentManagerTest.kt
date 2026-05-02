package sh.haven.core.data.agent

import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConsentManagerTest {

    @Test
    fun `NEVER returns ALLOW without prompting`() = runTest {
        val mgr = AgentConsentManager()
        val decision = mgr.requestConsent(
            toolName = "list_connections",
            clientHint = "test",
            summary = "should not appear",
            level = ConsentLevel.NEVER,
        )
        assertEquals(ConsentDecision.ALLOW, decision)
        assertTrue(mgr.pending.value.isEmpty())
    }

    @Test
    fun `non-NEVER without foreground returns DENY immediately`() = runTest {
        val mgr = AgentConsentManager()
        // foregroundActive defaults to false — fail-closed.
        val decision = mgr.requestConsent(
            toolName = "delete_sftp_file",
            clientHint = "test",
            summary = "delete /tmp/x",
            level = ConsentLevel.EVERY_CALL,
        )
        assertEquals(ConsentDecision.DENY, decision)
    }

    @Test
    fun `ONCE_PER_SESSION memoises ALLOW across calls`() = runTest(UnconfinedTestDispatcher()) {
        val mgr = AgentConsentManager()
        mgr.setForegroundActive(true)

        // First call: pending request appears, user taps allow.
        val first = async {
            mgr.requestConsent(
                toolName = "add_port_forward",
                clientHint = "agent-A",
                summary = "first",
                level = ConsentLevel.ONCE_PER_SESSION,
                timeoutMs = Long.MAX_VALUE,
            )
        }
        // UnconfinedTestDispatcher runs the async block inline up to its
        // first suspension point — the deferred.await inside
        // requestConsent — so by the time control returns here, the
        // pending request is queued and ready to inspect.
        val pending = mgr.pending.value.single()
        mgr.respond(pending.id, ConsentDecision.ALLOW)
        assertEquals(ConsentDecision.ALLOW, first.await())

        // Second call with same (client, tool) skips the prompt.
        val second = mgr.requestConsent(
            toolName = "add_port_forward",
            clientHint = "agent-A",
            summary = "second",
            level = ConsentLevel.ONCE_PER_SESSION,
        )
        assertEquals(ConsentDecision.ALLOW, second)
        assertTrue(mgr.pending.value.isEmpty())
    }

    @Test
    fun `EVERY_CALL re-prompts even after a prior allow`() = runTest(UnconfinedTestDispatcher()) {
        val mgr = AgentConsentManager()
        mgr.setForegroundActive(true)

        val first = async {
            mgr.requestConsent("delete_sftp_file", "agent-A", "first", ConsentLevel.EVERY_CALL, timeoutMs = Long.MAX_VALUE)
        }
        // UnconfinedTestDispatcher runs the async block inline up to its
        // first suspension point — the deferred.await inside
        // requestConsent — so by the time control returns here, the
        // pending request is queued and ready to inspect.
        mgr.respond(mgr.pending.value.single().id, ConsentDecision.ALLOW)
        assertEquals(ConsentDecision.ALLOW, first.await())

        // Second call must prompt again — verify pending is non-empty
        // before responding.
        val second = async {
            mgr.requestConsent("delete_sftp_file", "agent-A", "second", ConsentLevel.EVERY_CALL, timeoutMs = Long.MAX_VALUE)
        }
        // UnconfinedTestDispatcher runs the async block inline up to its
        // first suspension point — the deferred.await inside
        // requestConsent — so by the time control returns here, the
        // pending request is queued and ready to inspect.
        assertEquals(1, mgr.pending.value.size)
        mgr.respond(mgr.pending.value.single().id, ConsentDecision.DENY)
        assertEquals(ConsentDecision.DENY, second.await())
    }

    @Test
    fun `DENY is never memoised for ONCE_PER_SESSION`() = runTest(UnconfinedTestDispatcher()) {
        val mgr = AgentConsentManager()
        mgr.setForegroundActive(true)

        val first = async {
            mgr.requestConsent("add_port_forward", "agent-A", "first", ConsentLevel.ONCE_PER_SESSION, timeoutMs = Long.MAX_VALUE)
        }
        // UnconfinedTestDispatcher runs the async block inline up to its
        // first suspension point — the deferred.await inside
        // requestConsent — so by the time control returns here, the
        // pending request is queued and ready to inspect.
        mgr.respond(mgr.pending.value.single().id, ConsentDecision.DENY)
        assertEquals(ConsentDecision.DENY, first.await())

        // A misclick must not lock the agent out forever — second call
        // re-prompts.
        val second = async {
            mgr.requestConsent("add_port_forward", "agent-A", "second", ConsentLevel.ONCE_PER_SESSION, timeoutMs = Long.MAX_VALUE)
        }
        // UnconfinedTestDispatcher runs the async block inline up to its
        // first suspension point — the deferred.await inside
        // requestConsent — so by the time control returns here, the
        // pending request is queued and ready to inspect.
        assertEquals(1, mgr.pending.value.size)
        mgr.respond(mgr.pending.value.single().id, ConsentDecision.ALLOW)
        assertEquals(ConsentDecision.ALLOW, second.await())
    }

    @Test
    fun `clearMemoised forces a re-prompt for ONCE_PER_SESSION`() = runTest(UnconfinedTestDispatcher()) {
        val mgr = AgentConsentManager()
        mgr.setForegroundActive(true)

        val first = async {
            mgr.requestConsent("convert_file", "agent-A", "first", ConsentLevel.ONCE_PER_SESSION, timeoutMs = Long.MAX_VALUE)
        }
        // UnconfinedTestDispatcher runs the async block inline up to its
        // first suspension point — the deferred.await inside
        // requestConsent — so by the time control returns here, the
        // pending request is queued and ready to inspect.
        mgr.respond(mgr.pending.value.single().id, ConsentDecision.ALLOW)
        first.await()

        mgr.clearMemoised()

        val second = async {
            mgr.requestConsent("convert_file", "agent-A", "second", ConsentLevel.ONCE_PER_SESSION, timeoutMs = Long.MAX_VALUE)
        }
        // UnconfinedTestDispatcher runs the async block inline up to its
        // first suspension point — the deferred.await inside
        // requestConsent — so by the time control returns here, the
        // pending request is queued and ready to inspect.
        assertEquals(1, mgr.pending.value.size)
        mgr.respond(mgr.pending.value.single().id, ConsentDecision.ALLOW)
        second.await()
    }

    @Test
    fun `memo is keyed on clientHint plus toolName`() = runTest(UnconfinedTestDispatcher()) {
        val mgr = AgentConsentManager()
        mgr.setForegroundActive(true)

        // Approve for client A.
        val a = async {
            mgr.requestConsent("add_port_forward", "agent-A", "a", ConsentLevel.ONCE_PER_SESSION, timeoutMs = Long.MAX_VALUE)
        }
        // UnconfinedTestDispatcher runs the async block inline up to its
        // first suspension point — the deferred.await inside
        // requestConsent — so by the time control returns here, the
        // pending request is queued and ready to inspect.
        mgr.respond(mgr.pending.value.single().id, ConsentDecision.ALLOW)
        a.await()

        // A different client must still be prompted — the memo key
        // includes the client hint so a compromised secondary agent
        // doesn't inherit a primary agent's blanket allow.
        val b = async {
            mgr.requestConsent("add_port_forward", "agent-B", "b", ConsentLevel.ONCE_PER_SESSION, timeoutMs = Long.MAX_VALUE)
        }
        // UnconfinedTestDispatcher runs the async block inline up to its
        // first suspension point — the deferred.await inside
        // requestConsent — so by the time control returns here, the
        // pending request is queued and ready to inspect.
        assertEquals(1, mgr.pending.value.size)
        mgr.respond(mgr.pending.value.single().id, ConsentDecision.DENY)
        assertEquals(ConsentDecision.DENY, b.await())
    }
}
