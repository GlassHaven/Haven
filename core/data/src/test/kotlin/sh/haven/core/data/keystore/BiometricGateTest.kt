package sh.haven.core.data.keystore

import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pin [BiometricGate]'s behaviour — most importantly the fail-closed
 * path when no Activity is foregrounded, since
 * [sh.haven.core.data.keystore.SshKeySection]'s biometric-gated fetch
 * relies on [BiometricGate.request] returning UNAVAILABLE rather than
 * blocking forever in that case.
 */
class BiometricGateTest {

    @Test
    fun `request without foreground returns UNAVAILABLE immediately`() = runTest {
        val gate = BiometricGate()
        // foregroundActive defaults to false — fail-closed.
        val decision = gate.request(label = "Unlock x")
        assertEquals(BiometricGate.Decision.UNAVAILABLE, decision)
    }

    @Test
    fun `request with foreground queues until respond`() = runTest(UnconfinedTestDispatcher()) {
        val gate = BiometricGate()
        gate.setForegroundActive(true)

        val outcome = async {
            gate.request(label = "Unlock x", timeoutMs = Long.MAX_VALUE)
        }
        // UnconfinedTestDispatcher runs the async body up to the first
        // suspension point inline — by the time control returns here,
        // the request is queued in `pending`.
        val pending = gate.pending.value.single()
        assertEquals("Unlock x", pending.label)
        gate.respond(pending.id, BiometricGate.Decision.ALLOW)

        assertEquals(BiometricGate.Decision.ALLOW, outcome.await())
        // Pending list clears after respond.
        assertTrue(gate.pending.value.isEmpty())
    }

    @Test
    fun `respond DENY surfaces as the request decision`() = runTest(UnconfinedTestDispatcher()) {
        val gate = BiometricGate()
        gate.setForegroundActive(true)

        val outcome = async {
            gate.request(label = "Unlock x", timeoutMs = Long.MAX_VALUE)
        }
        gate.respond(gate.pending.value.single().id, BiometricGate.Decision.DENY)
        assertEquals(BiometricGate.Decision.DENY, outcome.await())
    }

    @Test
    fun `multiple concurrent requests are tracked independently`() = runTest(UnconfinedTestDispatcher()) {
        val gate = BiometricGate()
        gate.setForegroundActive(true)

        val first = async { gate.request(label = "Key A", timeoutMs = Long.MAX_VALUE) }
        val second = async { gate.request(label = "Key B", timeoutMs = Long.MAX_VALUE) }

        assertEquals(2, gate.pending.value.size)
        val ids = gate.pending.value.map { it.id }
        // Resolve in reverse order — the responses must route by id,
        // not by queue position.
        gate.respond(ids[1], BiometricGate.Decision.DENY)
        gate.respond(ids[0], BiometricGate.Decision.ALLOW)

        assertEquals(BiometricGate.Decision.ALLOW, first.await())
        assertEquals(BiometricGate.Decision.DENY, second.await())
    }

    @Test
    fun `request returns DENY on timeout`() = runTest {
        val gate = BiometricGate()
        gate.setForegroundActive(true)
        // 1 ms timeout — the test scheduler advances virtual time
        // automatically inside runTest, so this resolves promptly.
        val decision = gate.request(label = "Unlock", timeoutMs = 1)
        assertEquals(BiometricGate.Decision.DENY, decision)
    }

    @Test
    fun `request after a fresh ALLOW skips the prompt`() = runTest(UnconfinedTestDispatcher()) {
        // A single connection walks multiple biometric-protected keys
        // back-to-back; the second key's request must not re-prompt
        // the user. This is what makes the per-key biometric model
        // tolerable for fallback auth.
        val gate = BiometricGate()
        gate.setForegroundActive(true)

        val first = async {
            gate.request(label = "Unlock A", timeoutMs = Long.MAX_VALUE)
        }
        gate.respond(gate.pending.value.single().id, BiometricGate.Decision.ALLOW)
        assertEquals(BiometricGate.Decision.ALLOW, first.await())

        // Second request lands within the unlock window — no pending
        // request queues, ALLOW returns immediately.
        val second = gate.request(label = "Unlock B", timeoutMs = Long.MAX_VALUE)
        assertEquals(BiometricGate.Decision.ALLOW, second)
        assertTrue("no pending request should have queued", gate.pending.value.isEmpty())
    }

    @Test
    fun `backgrounding clears the session-unlock window`() = runTest(UnconfinedTestDispatcher()) {
        // The whole point of the §85 fail-closed contract is that
        // backgrounding the app re-locks. Pin that the unlock cache
        // doesn't survive a setForegroundActive(false) transition.
        val gate = BiometricGate()
        gate.setForegroundActive(true)

        val first = async {
            gate.request(label = "Unlock", timeoutMs = Long.MAX_VALUE)
        }
        gate.respond(gate.pending.value.single().id, BiometricGate.Decision.ALLOW)
        first.await()

        // Background → foreground roundtrip clears the cache. A
        // backgrounded request would also fail-closed to UNAVAILABLE,
        // so we toggle back to foreground before the second call.
        gate.setForegroundActive(false)
        gate.setForegroundActive(true)

        val second = async {
            gate.request(label = "Unlock again", timeoutMs = Long.MAX_VALUE)
        }
        // A new pending request should queue — proves the window
        // was cleared.
        assertEquals(1, gate.pending.value.size)
        gate.respond(gate.pending.value.single().id, BiometricGate.Decision.ALLOW)
        second.await()
    }

    @Test
    fun `denied request does not start the unlock window`() = runTest(UnconfinedTestDispatcher()) {
        val gate = BiometricGate()
        gate.setForegroundActive(true)

        val first = async {
            gate.request(label = "Unlock", timeoutMs = Long.MAX_VALUE)
        }
        gate.respond(gate.pending.value.single().id, BiometricGate.Decision.DENY)
        assertEquals(BiometricGate.Decision.DENY, first.await())

        // The next request should re-prompt — DENY isn't a successful
        // auth and therefore shouldn't open the unlock window.
        val second = async {
            gate.request(label = "Unlock again", timeoutMs = Long.MAX_VALUE)
        }
        assertEquals(1, gate.pending.value.size)
        gate.respond(gate.pending.value.single().id, BiometricGate.Decision.ALLOW)
        second.await()
    }
}
