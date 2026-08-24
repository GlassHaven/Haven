package sh.haven.app.reticulum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the decision [NativeReticulumTransport.init] makes about the
 * process-wide Reticulum stack (#588).
 *
 * The fault these cover is not a networking one: the transport used to latch
 * on a bare boolean that ignored the host and port it was given, so whichever
 * profile connected first decided what the rest of the process could reach.
 * Every case below is a connect that used to return early having done nothing
 * while reporting success.
 */
class StackPlanTest {

    private fun gateway(host: String = "rns.example.org", port: Int = 4242, netname: String? = null) =
        GatewaySpec(host, port, netname)

    // --- classification ---

    @Test
    fun `localhost on 37428 is a shared-instance request`() {
        for (host in listOf("127.0.0.1", "localhost", "::1")) {
            assertEquals(
                "$host:37428 should be read as a shared instance",
                StackRequest.SharedInstance,
                classifyStackRequest(host, 37428, null),
            )
        }
    }

    @Test
    fun `localhost on another port is a gateway, not a shared instance`() {
        assertEquals(
            StackRequest.Gateway(GatewaySpec("127.0.0.1", 4242, null)),
            classifyStackRequest("127.0.0.1", 4242, null),
        )
    }

    @Test
    fun `a remote host on 37428 is a gateway`() {
        assertEquals(
            StackRequest.Gateway(GatewaySpec("rns.example.org", 37428, null)),
            classifyStackRequest("rns.example.org", 37428, null),
        )
    }

    // --- cold start ---

    @Test
    fun `first shared-instance connect starts the shared stack`() {
        assertEquals(StackAction.StartShared, planStackAction(null, StackRequest.SharedInstance))
    }

    @Test
    fun `first gateway connect starts the stack with that interface`() {
        val spec = gateway()
        assertEquals(
            StackAction.StartGateway(spec),
            planStackAction(null, StackRequest.Gateway(spec)),
        )
    }

    // --- the #588 regression: a second profile must contribute an interface ---

    @Test
    fun `a second gateway is added to the running stack`() {
        val first = gateway(host = "one.example.org")
        val second = gateway(host = "two.example.org")
        val state = StackState(StackMode.GATEWAY, setOf(first))

        assertEquals(
            "a second gateway profile must add its own interface, not be dropped",
            StackAction.AddGateway(second),
            planStackAction(state, StackRequest.Gateway(second)),
        )
    }

    @Test
    fun `a different port on the same host is a second interface`() {
        val state = StackState(StackMode.GATEWAY, setOf(gateway(port = 4242)))
        assertEquals(
            StackAction.AddGateway(gateway(port = 4965)),
            planStackAction(state, StackRequest.Gateway(gateway(port = 4965))),
        )
    }

    @Test
    fun `the same host and port on a different IFAC network is a second interface`() {
        val state = StackState(StackMode.GATEWAY, setOf(gateway(netname = "alpha")))
        assertEquals(
            StackAction.AddGateway(gateway(netname = "beta")),
            planStackAction(state, StackRequest.Gateway(gateway(netname = "beta"))),
        )
    }

    @Test
    fun `reconnecting the same gateway does not add a duplicate interface`() {
        val spec = gateway()
        val state = StackState(StackMode.GATEWAY, setOf(spec))
        assertEquals(
            StackAction.AlreadySatisfied,
            planStackAction(state, StackRequest.Gateway(spec)),
        )
    }

    @Test
    fun `reconnecting to the shared instance is satisfied by the running one`() {
        assertEquals(
            StackAction.AlreadySatisfied,
            planStackAction(StackState(StackMode.SHARED_INSTANCE), StackRequest.SharedInstance),
        )
    }

    // --- the two modes are mutually exclusive, and must say so ---

    @Test
    fun `a gateway profile is rejected while a shared instance owns the interfaces`() {
        val action = planStackAction(
            StackState(StackMode.SHARED_INSTANCE),
            StackRequest.Gateway(gateway()),
        )
        val reason = (action as? StackAction.Reject)?.reason
            ?: fail("expected a Reject, got $action")
        assertTrue(
            "the message must name the shared instance as the reason: $reason",
            reason.contains("shared instance"),
        )
    }

    @Test
    fun `a shared-instance profile is rejected while a gateway is running`() {
        val action = planStackAction(
            StackState(StackMode.GATEWAY, setOf(gateway())),
            StackRequest.SharedInstance,
        )
        val reason = (action as? StackAction.Reject)?.reason
            ?: fail("expected a Reject, got $action")
        assertTrue(
            "the message must name the gateway as the reason: $reason",
            reason.contains("gateway"),
        )
    }

    private fun fail(message: String): Nothing = throw AssertionError(message)
}
