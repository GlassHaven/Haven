package sh.haven.core.openai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The AI-route handle registry — the teardown contract both disconnect
 * paths share (ConnectionsViewModel and the agent's `disconnect_profile`).
 * The on-device leak this closes: `disconnect_profile` on a routed endpoint
 * left the carrier's LOCAL forward LISTENing because the handle store was
 * UI-scoped and unreachable from the programmatic path.
 */
class AiRouteRegistryTest {

    private class Recorder {
        val released = mutableListOf<String>()
        val failed = mutableListOf<String>()
        fun handle(owner: String, carrier: String, throwOnRelease: Boolean = false) =
            AiRouteRegistry.Handle(
                ownerProfileId = owner,
                carrierProfileId = carrier,
                release = {
                    if (throwOnRelease) throw IllegalStateException("release blew up")
                    released += owner
                },
                onUnreachable = { failed += owner },
            )
    }

    @Test
    fun `teardownFor releases the route the profile owns`() {
        val registry = AiRouteRegistry()
        val rec = Recorder()
        registry.register(rec.handle("endpoint", "carrier"))
        registry.teardownFor("endpoint")
        assertEquals(listOf("endpoint"), rec.released)
        assertEquals(emptyList<String>(), rec.failed) // not a carrier event
        // Idempotent: a second teardown is a no-op.
        registry.teardownFor("endpoint")
        assertEquals(listOf("endpoint"), rec.released)
    }

    @Test
    fun `teardownFor cascades routes the profile carries`() {
        val registry = AiRouteRegistry()
        val rec = Recorder()
        registry.register(rec.handle("endpoint-a", "carrier"))
        registry.register(rec.handle("endpoint-b", "carrier"))
        registry.register(rec.handle("unrelated", "other-carrier"))
        registry.teardownFor("carrier")
        // Both carried routes release AND fail closed; the unrelated one and
        // the carrier itself (not an endpoint) are untouched.
        assertEquals(listOf("endpoint-a", "endpoint-b"), rec.released.sorted())
        assertEquals(listOf("endpoint-a", "endpoint-b"), rec.failed.sorted())
        assertTrue("unrelated" !in rec.released && "unrelated" !in rec.failed)
    }

    @Test
    fun `carrierGone releases and fails without touching the carrier's own route`() {
        val registry = AiRouteRegistry()
        val rec = Recorder()
        registry.register(rec.handle("endpoint", "carrier"))
        registry.carrierGone("carrier")
        assertEquals(listOf("endpoint"), rec.released)
        assertEquals(listOf("endpoint"), rec.failed)
    }

    @Test
    fun `a throwing release does not block the cascade`() {
        val registry = AiRouteRegistry()
        val rec = Recorder()
        registry.register(rec.handle("endpoint-a", "carrier", throwOnRelease = true))
        registry.register(rec.handle("endpoint-b", "carrier"))
        registry.teardownFor("carrier")
        // endpoint-a's release blew up (not recorded) but its sessions still
        // fail closed — a failed resource release must not skip the
        // onUnreachable cascade.
        assertEquals(listOf("endpoint-b"), rec.released)
        assertEquals(listOf("endpoint-a", "endpoint-b"), rec.failed.sorted())
        // The broken handle is still gone — no retry, no resurrection.
        registry.teardownFor("carrier")
        assertEquals(1, rec.released.size)
    }

    @Test
    fun `re-registration replaces the previous handle`() {
        val registry = AiRouteRegistry()
        val rec = Recorder()
        registry.register(rec.handle("endpoint", "carrier-1"))
        registry.register(rec.handle("endpoint", "carrier-2"))
        registry.release("endpoint")
        // Only the newest handle's release ran — a retry must not mint a
        // second live route for the same endpoint.
        assertEquals(listOf("endpoint"), rec.released)
        registry.carrierGone("carrier-1")
        assertTrue(rec.failed.isEmpty())
    }

    @Test
    fun `unknown profiles are no-ops`() {
        val registry = AiRouteRegistry()
        registry.release("nobody")
        registry.teardownFor("nobody")
        registry.carrierGone("nobody")
    }
}