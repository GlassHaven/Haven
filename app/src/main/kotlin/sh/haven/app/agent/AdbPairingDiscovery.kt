package sh.haven.app.agent

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Finds the port Android's wireless-debugging *pairing* listener is on (#575).
 *
 * The pairing port is not the connect port and is not the one shown by
 * `adb mdns services` in the steady state: `_adb-tls-connect._tcp` is
 * advertised continuously, while `_adb-tls-pairing._tcp` exists **only while
 * the system pairing dialog is open**, on a fresh ephemeral port each time.
 * That is why a port written down once is always stale, and why the port has
 * to be discovered live rather than remembered.
 *
 * Both services are plain DNS-SD, so `NsdManager` finds them with no Shizuku
 * and no root — the privileged part of the flow is only opening the dialog.
 */
internal class AdbPairingDiscovery(
    private val nsd: Nsd,
) {

    constructor(context: Context) : this(RealNsd(context))

    data class Endpoint(val host: String, val port: Int)

    /**
     * The slice of [NsdManager] this uses, behind an interface so the
     * resolve/timeout logic is testable without a real multicast stack —
     * Robolectric's NsdManager shadow does not drive resolve callbacks.
     */
    internal interface Nsd {
        fun discover(serviceType: String, listener: NsdManager.DiscoveryListener)
        fun stopDiscovery(listener: NsdManager.DiscoveryListener)
        fun resolve(info: NsdServiceInfo, listener: NsdManager.ResolveListener)
    }

    private class RealNsd(context: Context) : Nsd {
        private val mgr =
            context.getSystemService(Context.NSD_SERVICE) as NsdManager

        override fun discover(serviceType: String, listener: NsdManager.DiscoveryListener) {
            mgr.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        }

        override fun stopDiscovery(listener: NsdManager.DiscoveryListener) {
            // Throws if discovery already stopped itself (start failure, or a
            // second stop). Never let teardown mask the result we came for.
            runCatching { mgr.stopServiceDiscovery(listener) }
        }

        @Suppress("DEPRECATION")
        override fun resolve(info: NsdServiceInfo, listener: NsdManager.ResolveListener) {
            // registerServiceInfoCallback is API 34+; minSdk here is 26 and the
            // deprecated resolve still works on every version we ship to.
            mgr.resolveService(info, listener)
        }
    }

    /**
     * Wait for the pairing listener to appear and return its address, or null
     * if it does not show up within [timeoutMs].
     *
     * Call this *around* opening the system dialog, not after: the service
     * appears when the dialog opens and vanishes when it closes, so a caller
     * that opens the dialog first and starts looking second can miss it on a
     * fast device.
     */
    suspend fun awaitPairingEndpoint(timeoutMs: Long = DEFAULT_TIMEOUT_MS): Endpoint? {
        val found = CompletableDeferred<Endpoint>()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onServiceFound(info: NsdServiceInfo) {
                // Resolution is a second round trip; onServiceFound carries the
                // name only, never the port.
                nsd.resolve(
                    info,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                            Log.d(TAG, "resolve failed for ${info.serviceName}: $errorCode")
                        }

                        @Suppress("DEPRECATION")
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val host = info.host?.hostAddress
                            val port = info.port
                            if (host.isNullOrBlank() || port <= 0) {
                                Log.d(TAG, "resolved ${info.serviceName} with no usable address")
                                return
                            }
                            // complete() is idempotent — several adbd instances
                            // (or a re-advertise) must not throw here.
                            found.complete(Endpoint(host, port))
                        }
                    },
                )
            }

            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "discovery failed to start: $errorCode")
                // Nothing will ever arrive — fail fast instead of burning the
                // whole timeout while the dialog sits open in front of the user.
                found.completeExceptionally(
                    IllegalStateException("mDNS discovery failed to start (error $errorCode)"),
                )
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }

        nsd.discover(PAIRING_SERVICE_TYPE, listener)
        return try {
            withTimeoutOrNull(timeoutMs) { found.await() }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "pairing discovery: ${e.message}")
            null
        } finally {
            nsd.stopDiscovery(listener)
        }
    }

    internal companion object {
        const val TAG = "AdbPairingDiscovery"

        /** Advertised only while the system pairing dialog is open. */
        const val PAIRING_SERVICE_TYPE = "_adb-tls-pairing._tcp"

        /** Advertised continuously once wireless debugging is on. */
        const val CONNECT_SERVICE_TYPE = "_adb-tls-connect._tcp"

        /**
         * Long enough for a user to find the dialog and for mDNS to settle,
         * short enough that a failed flow does not hang an MCP call past its
         * client timeout.
         */
        const val DEFAULT_TIMEOUT_MS = 30_000L
    }
}
