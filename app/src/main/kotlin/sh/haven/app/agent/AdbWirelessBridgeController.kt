package sh.haven.app.agent

import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import sh.haven.core.mcp.McpError
import sh.haven.core.security.adb.AdbKeyPairStore
import sh.haven.core.security.adb.AdbPairingClient
import sh.haven.core.security.adb.AdbTlsBridge

/**
 * Process-wide owner of Haven's persistent ADB RSA keypair, in-app SPAKE2+
 * pairing client, and local `127.0.0.1:<port>` -> `_adb-tls-connect._tcp`
 * bridge (#575 v2).
 *
 * Shared between [McpTools] (`await_adb_pairing_code`, `expose_adb`,
 * `unexpose_adb`, `list_bridges`) and [McpTunnelManager] so a persisted
 * non-root `expose_adb` port re-arms its local TLS bridge across app restarts
 * without touching root `adbd` if classic `service.adb.tcp.port` is active.
 */
@Singleton
class AdbWirelessBridgeController(
    private val context: Context,
    keyPairStoreOverride: AdbKeyPairStore? = null,
    pairingClientOverride: AdbPairingClient? = null,
    tlsBridgeOverride: AdbTlsBridge? = null,
    internal var inAppPairingSupportedOverride: Boolean? = null,
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context = context, keyPairStoreOverride = null)

    internal var discoveryFactory: () -> AdbPairingDiscovery = { AdbPairingDiscovery(context) }

    val keyPairStore: AdbKeyPairStore by lazy { keyPairStoreOverride ?: AdbKeyPairStore(context) }

    val pairingClient: AdbPairingClient by lazy { pairingClientOverride ?: AdbPairingClient(keyPairStore) }

    val tlsBridge: AdbTlsBridge by lazy {
        tlsBridgeOverride ?: AdbTlsBridge(
            keyPairStore = keyPairStore,
            resolveConnectEndpoint = { resolveConnectEndpoint() },
        )
    }

    val isInAppPairingSupported: Boolean
        get() = inAppPairingSupportedOverride ?: (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)

    suspend fun resolveConnectEndpoint(
        timeoutMs: Long = AdbPairingDiscovery.DEFAULT_CONNECT_TIMEOUT_MS,
    ): AdbTlsBridge.ConnectEndpoint? =
        discoveryFactory().awaitConnectEndpoint(timeoutMs)?.let {
            AdbTlsBridge.ConnectEndpoint(it.host, it.port)
        }

    internal suspend fun buildPairingCodeResponse(
        code: String,
        endpoint: AdbPairingDiscovery.Endpoint?,
        pairInApp: Boolean,
        requestedPubKey: String?,
    ): JSONObject {
        val pairOutcome = if (pairInApp && endpoint != null && isInAppPairingSupported) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val keyLine = requestedPubKey ?: keyPairStore.adbPublicKeyLine()
                    pairingClient.pair(
                        host = endpoint.host,
                        port = endpoint.port,
                        pairingCode = code,
                        publicKeyLine = keyLine,
                    )
                }
            }
        } else {
            null
        }

        return JSONObject().apply {
            put("code", code)
            if (endpoint != null) {
                put("host", endpoint.host)
                put("port", endpoint.port)
                put("pairCommand", "adb pair ${endpoint.host}:${endpoint.port} $code")
            }
            when {
                pairOutcome == null -> {
                    put("pairedInApp", false)
                    if (pairInApp && endpoint != null && !isInAppPairingSupported) {
                        put("pairError", "In-app ADB wireless pairing requires Android 11 (API 30) or newer")
                    }
                }
                pairOutcome.isSuccess -> {
                    put("pairedInApp", true)
                    put("guid", pairOutcome.getOrThrow().deviceGuid)
                    put("pairedKeyTarget", if (requestedPubKey != null) "workstation" else "haven-bridge")
                }
                else -> {
                    put("pairedInApp", false)
                    put("pairError", pairOutcome.exceptionOrNull()?.message ?: "In-app pairing failed")
                }
            }
        }
    }

    internal data class Activation(
        val method: String,
        val wirelessEndpoint: String?,
    )

    internal suspend fun activateLocalAdbEndpoint(
        port: Int,
        enableShizukuTcpip: (Int) -> Unit,
    ): Activation {
        return try {
            tlsBridge.stop()
            enableShizukuTcpip(port)
            Activation(method = "tcpip", wirelessEndpoint = null)
        } catch (shizukuErr: McpError) {
            val target = resolveConnectEndpoint()
                ?: throw McpError(
                    -32603,
                    "${shizukuErr.message} Non-root Wireless Debugging bridge also unavailable: " +
                        "_adb-tls-connect._tcp was not found on mDNS. Enable Wireless debugging " +
                        "and pair Haven once via start_adb_pairing + await_adb_pairing_code.",
                )
            try {
                tlsBridge.start(port)
            } catch (ioe: IOException) {
                throw McpError(
                    -32603,
                    "Failed to bind local Wireless Debugging TLS bridge on 127.0.0.1:$port: ${ioe.message}",
                )
            }
            Activation(
                method = "wireless-tls-bridge",
                wirelessEndpoint = "${target.host}:${target.port}",
            )
        }
    }

    /**
     * Re-arm [tlsBridge] on `127.0.0.1:[port]` during headless SSH tunnel
     * establishment if nothing is currently listening on loopback `:[port]`
     * (i.e. classic root `service.adb.tcp.port` is not active) and Wireless
     * Debugging is advertising `_adb-tls-connect._tcp`.
     */
    suspend fun ensureBridgeForReconnect(port: Int) {
        if (tlsBridge.boundPort == port) return
        val alreadyListening = runCatching {
            Socket().use { probe ->
                probe.connect(InetSocketAddress("127.0.0.1", port), 200)
            }
            true
        }.getOrDefault(false)
        if (alreadyListening) return

        if (resolveConnectEndpoint(3_000L) != null) {
            runCatching { tlsBridge.start(port) }
        }
    }
}
