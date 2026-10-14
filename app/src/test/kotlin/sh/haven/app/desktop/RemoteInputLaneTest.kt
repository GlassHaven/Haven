package sh.haven.app.desktop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Collections

/**
 * #504: a key release that overtakes its own press leaves the key held on
 * the server, which then auto-repeats until the session is closed.
 *
 * The first event is slow and the rest are not, so anything that runs
 * events concurrently finishes them out of order.
 */
class RemoteInputLaneTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `events run in submission order even when an early one is slow`() {
        val lane = RemoteInputLane(scope)
        val arrivals = Collections.synchronizedList(mutableListOf<String>())
        val done = CompletableDeferred<Unit>()

        repeat(PAIRS) { i ->
            lane.submit {
                if (i == 0) Thread.sleep(120)
                arrivals.add("down$i")
            }
            lane.submit { arrivals.add("up$i") }
        }
        lane.submit { done.complete(Unit) }
        runBlocking { withTimeout(5_000) { done.await() } }

        val expected = (0 until PAIRS).flatMap { listOf("down$it", "up$it") }
        assertEquals(expected, arrivals.toList())
    }

    @Test
    fun `a failing event does not stop later events`() {
        val lane = RemoteInputLane(scope)
        val arrivals = Collections.synchronizedList(mutableListOf<Int>())
        val done = CompletableDeferred<Unit>()

        lane.submit { arrivals.add(1) }
        lane.submit { throw IllegalStateException("socket closed") }
        lane.submit { arrivals.add(2) }
        lane.submit { done.complete(Unit) }
        runBlocking { withTimeout(5_000) { done.await() } }

        assertEquals(listOf(1, 2), arrivals.toList())
    }

    private companion object {
        const val PAIRS = 20
    }
}
