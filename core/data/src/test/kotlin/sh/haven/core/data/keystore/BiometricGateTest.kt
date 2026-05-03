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
}
