package sh.haven.app.agent

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * The pairing listener is advertised only while the system dialog is open and
 * on a fresh port each time, so everything here is about the live case: it must
 * be found while it exists, and its absence must fail in bounded time rather
 * than hanging an MCP call.
 */
class AdbPairingDiscoveryTest {

    /** Drives the NsdManager callbacks by hand — no multicast stack involved. */
    private class FakeNsd : AdbPairingDiscovery.Nsd {
        var discoveredType: String? = null
        var stopped = false
        private var listener: NsdManager.DiscoveryListener? = null
        private var resolveResult: NsdServiceInfo? = null
        private var resolveError: Int? = null

        override fun discover(serviceType: String, listener: NsdManager.DiscoveryListener) {
            discoveredType = serviceType
            this.listener = listener
        }

        override fun stopDiscovery(listener: NsdManager.DiscoveryListener) {
            stopped = true
        }

        override fun resolve(info: NsdServiceInfo, listener: NsdManager.ResolveListener) {
            resolveError?.let { listener.onResolveFailed(info, it); return }
            resolveResult?.let { listener.onServiceResolved(it) }
        }

        fun willResolveTo(info: NsdServiceInfo) { resolveResult = info }
        fun willFailResolve(code: Int) { resolveError = code }
        fun emitFound(info: NsdServiceInfo) = listener!!.onServiceFound(info)
        fun emitStartFailed(code: Int) =
            listener!!.onStartDiscoveryFailed(AdbPairingDiscovery.PAIRING_SERVICE_TYPE, code)
    }

    private fun serviceInfo(host: String?, port: Int): NsdServiceInfo = mockk(relaxed = true) {
        every { serviceName } returns "adb-pairing"
        every { this@mockk.host } returns host?.let { InetAddress.getByName(it) }
        every { this@mockk.port } returns port
    }

    @Test
    fun `resolves the pairing service to host and port`() = runTest {
        val nsd = FakeNsd()
        val resolved = serviceInfo("192.168.0.193", 41234)
        nsd.willResolveTo(resolved)
        val discovery = AdbPairingDiscovery(nsd)

        val deferred = async { discovery.awaitPairingEndpoint(5_000) }
        yield()
        nsd.emitFound(resolved)

        assertEquals(AdbPairingDiscovery.Endpoint("192.168.0.193", 41234), deferred.await())
    }

    /**
     * The pairing service type is NOT the one `adb mdns services` shows in the
     * steady state — pairing against the connect port always fails, so this
     * pins which type is queried.
     */
    @Test
    fun `queries the pairing service type, not the connect type`() = runTest {
        val nsd = FakeNsd()
        val discovery = AdbPairingDiscovery(nsd)

        val deferred = async { discovery.awaitPairingEndpoint(50) }
        yield()
        deferred.await()

        assertEquals("_adb-tls-pairing._tcp", nsd.discoveredType)
        assertTrue(
            "must not query the always-on connect service",
            nsd.discoveredType != AdbPairingDiscovery.CONNECT_SERVICE_TYPE,
        )
    }

    @Test
    fun `returns null when the dialog never opens`() = runTest {
        val nsd = FakeNsd()
        val discovery = AdbPairingDiscovery(nsd)

        assertNull(discovery.awaitPairingEndpoint(50))
        assertTrue("discovery must be torn down", nsd.stopped)
    }

    /** A failed start would otherwise burn the whole timeout with the dialog open. */
    @Test
    fun `gives up immediately when discovery cannot start`() = runTest {
        val nsd = FakeNsd()
        val discovery = AdbPairingDiscovery(nsd)

        val deferred = async { discovery.awaitPairingEndpoint(60_000) }
        yield()
        nsd.emitStartFailed(NsdManager.FAILURE_INTERNAL_ERROR)

        assertNull(deferred.await())
    }

    @Test
    fun `ignores a service that resolves without a usable address`() = runTest {
        val nsd = FakeNsd()
        val broken = serviceInfo(null, 0)
        nsd.willResolveTo(broken)
        val discovery = AdbPairingDiscovery(nsd)

        val deferred = async { discovery.awaitPairingEndpoint(50) }
        yield()
        nsd.emitFound(broken)

        assertNull(deferred.await())
    }

    @Test
    fun `a resolve failure does not end the wait`() = runTest {
        val nsd = FakeNsd()
        nsd.willFailResolve(NsdManager.FAILURE_INTERNAL_ERROR)
        val discovery = AdbPairingDiscovery(nsd)

        val deferred = async { discovery.awaitPairingEndpoint(50) }
        yield()
        nsd.emitFound(serviceInfo("192.168.0.193", 41234))

        // Times out rather than throwing — another announcement may still land.
        assertNull(deferred.await())
    }
}

/**
 * `overlayShown: false` on its own is a dead end — the caller cannot tell "you
 * did not ask for it" from "one tap would fix this". These pin the mapping that
 * makes the difference actionable.
 */
class AdbPairingOverlayReasonTest {

    @Test
    fun `no reason when the box actually showed`() {
        assertNull(AdbPairingOverlay.skipReason(wantOverlay = true, canDraw = true, added = true))
    }

    @Test
    fun `missing permission is reported as the fixable case`() {
        assertEquals(
            AdbPairingOverlay.REASON_PERMISSION,
            AdbPairingOverlay.skipReason(wantOverlay = true, canDraw = false, added = false),
        )
    }

    @Test
    fun `caller opting out is not reported as a permission problem`() {
        assertEquals(
            AdbPairingOverlay.REASON_DISABLED,
            AdbPairingOverlay.skipReason(wantOverlay = false, canDraw = false, added = false),
        )
    }

    /** Permission held but the window still refused — a different bug entirely. */
    @Test
    fun `add failure is distinguished from a permission problem`() {
        assertEquals(
            AdbPairingOverlay.REASON_ADD_FAILED,
            AdbPairingOverlay.skipReason(wantOverlay = true, canDraw = true, added = false),
        )
    }
}

/**
 * Under Android's restricted-settings block the overlay toggle cannot move, so
 * routing there is a dead end that looks like an action. These pin that the
 * blocked case goes to the App info page instead — that is where the ⋮ "Allow
 * restricted settings" item lives.
 */
class AdbPairingGrantRouteTest {

    @Test
    fun `a plain denial routes to the overlay toggle`() {
        assertEquals(
            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            AdbPairingOverlay.routeFor(AdbPairingOverlay.STATE_DENIED),
        )
    }

    @Test
    fun `a restricted-settings block routes to App info, not the inert toggle`() {
        assertEquals(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            AdbPairingOverlay.routeFor(AdbPairingOverlay.STATE_RESTRICTED),
        )
    }

    @Test
    fun `nothing to open once it is granted`() {
        assertNull(AdbPairingOverlay.routeFor(AdbPairingOverlay.STATE_GRANTED))
    }
}
