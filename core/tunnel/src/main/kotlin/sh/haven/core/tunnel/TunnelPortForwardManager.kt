package sh.haven.core.tunnel

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TunnelPortForward"

enum class TunnelForwardProtocol {
    TCP,
    UDP,
}

/**
 * Plain TCP or UDP local port-forward rule bound to a configured [Tunnel] (#792).
 *
 * Binds `127.0.0.1:[localPort]` in Haven's network namespace — directly reachable
 * inside local PRoot Linux at `127.0.0.1:[localPort]` and inside local QEMU SLIRP
 * VMs at `10.0.2.2:[localPort]` — and forwards traffic to `[remoteHost]:[remotePort]`
 * through `[tunnelConfigId]` via [Tunnel.dial] (TCP) or [Tunnel.listenUdp] (UDP),
 * with no SSH/RDP/Mosh authentication layer required.
 */
data class TunnelPortForwardRule(
    val id: String = UUID.randomUUID().toString(),
    val tunnelConfigId: String,
    val protocol: TunnelForwardProtocol = TunnelForwardProtocol.TCP,
    val localPort: Int,
    val remoteHost: String,
    val remotePort: Int,
)

/**
 * Manages standalone tunnel connections, localhost SOCKS5 exposure for local
 * PRoot Linux / QEMU guests, and plain TCP/UDP local port forwarding over any
 * configured [Tunnel] (#792).
 */
@Singleton
class TunnelPortForwardManager(
    private val tunnelManager: TunnelManager,
    private val cacheDir: File? = null,
    private val prefs: SharedPreferences? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        tunnelManager: TunnelManager,
    ) : this(
        tunnelManager = tunnelManager,
        cacheDir = context.cacheDir,
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        ioDispatcher = Dispatchers.IO,
    )

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val mutex = Mutex()

    private val _rules = MutableStateFlow<List<TunnelPortForwardRule>>(emptyList())
    val rules: StateFlow<List<TunnelPortForwardRule>> = _rules.asStateFlow()

    /** ruleId -> actual bound local port (matches [TunnelPortForwardRule.localPort] or OS-assigned when 0). */
    private val _boundPorts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val boundPorts: StateFlow<Map<String, Int>> = _boundPorts.asStateFlow()

    /** ruleId -> error message if the listener or tunnel acquire failed. */
    private val _ruleErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val ruleErrors: StateFlow<Map<String, String>> = _ruleErrors.asStateFlow()

    /** tunnelConfigId -> active localhost SOCKS5 port (`127.0.0.1:<port>`). */
    private val _socksPorts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val socksPorts: StateFlow<Map<String, Int>> = _socksPorts.asStateFlow()

    /**
     * When true, `<cacheDir>/.haven_tunnel_auto_proxy` is written so local PRoot
     * shells and desktops automatically export `ALL_PROXY`/`HTTP_PROXY`/`HTTPS_PROXY`
     * pointing at the active tunnel's localhost SOCKS5 listener.
     */
    private val _autoProxyGuests = MutableStateFlow(
        prefs?.getBoolean(KEY_AUTO_PROXY_GUESTS, false) ?: false,
    )
    val autoProxyGuests: StateFlow<Boolean> = _autoProxyGuests.asStateFlow()

    private class RunningForward(
        val rule: TunnelPortForwardRule,
        val job: Job,
        val closeListener: () -> Unit,
    )

    private val runningForwards = ConcurrentHashMap<String, RunningForward>()
    private val standaloneConnectedIds = ConcurrentHashMap.newKeySet<String>()

    init {
        syncAutoProxyMarker(_autoProxyGuests.value)
        val initialRules = loadRulesFromPrefs()
        _rules.value = initialRules
        scope.launch {
            for (rule in initialRules) {
                startForwardInternal(rule)
            }
        }
        scope.launch {
            tunnelManager.live.collect { liveMap ->
                refreshSocksPorts(liveMap)
            }
        }
    }

    /**
     * Connect a standalone tunnel directly from `Settings -> Tunnels` without
     * needing an SSH/RDP connection profile (#792), and start its localhost
     * SOCKS5 listener so local PRoot Linux (`127.0.0.1:<port>`) and QEMU VMs
     * (`10.0.2.2:<port>`) can route traffic through it immediately.
     */
    suspend fun connectStandalone(configId: String): Tunnel? = withContext(ioDispatcher) {
        val tunnel = tunnelManager.acquire(configId, standaloneDependentId(configId))
            ?: return@withContext null
        standaloneConnectedIds.add(configId)
        runCatching { tunnel.socksAddress() }.getOrNull()?.let { addr ->
            val updated = _socksPorts.value.toMutableMap()
            updated[configId] = addr.port
            _socksPorts.value = updated
            syncGuestSocksMarker(updated)
        }
        tunnel
    }

    /** Release the standalone dependent hold on [configId] and tear down the tunnel if unused. */
    suspend fun disconnectStandalone(configId: String) {
        if (!standaloneConnectedIds.remove(configId)) return
        withContext(ioDispatcher) {
            tunnelManager.release(standaloneDependentId(configId))
        }
    }

    fun setAutoProxyGuests(enabled: Boolean) {
        _autoProxyGuests.value = enabled
        prefs?.edit()?.putBoolean(KEY_AUTO_PROXY_GUESTS, enabled)?.apply()
        syncAutoProxyMarker(enabled)
    }

    /**
     * Add and start a plain TCP or UDP port-forward rule over [tunnelConfigId].
     */
    suspend fun addRule(
        tunnelConfigId: String,
        protocol: TunnelForwardProtocol,
        localPort: Int,
        remoteHost: String,
        remotePort: Int,
    ): TunnelPortForwardRule {
        require(localPort in 0..65535) { "Local port must be between 0 and 65535" }
        require(remoteHost.isNotBlank()) { "Remote host is required" }
        require(remotePort in 1..65535) { "Remote port must be between 1 and 65535" }
        val rule = TunnelPortForwardRule(
            tunnelConfigId = tunnelConfigId,
            protocol = protocol,
            localPort = localPort,
            remoteHost = remoteHost.trim(),
            remotePort = remotePort,
        )
        mutex.withLock {
            val next = _rules.value + rule
            _rules.value = next
            saveRulesToPrefs(next)
        }
        startForwardInternal(rule)
        return rule
    }

    /** Remove and stop a port-forward rule. */
    suspend fun removeRule(ruleId: String) {
        mutex.withLock {
            val next = _rules.value.filterNot { it.id == ruleId }
            _rules.value = next
            saveRulesToPrefs(next)
        }
        stopForwardInternal(ruleId)
    }

    /** Remove and stop all port-forward rules belonging to [tunnelConfigId]. */
    suspend fun removeRulesForTunnel(tunnelConfigId: String) {
        if (_rules.value.none { it.tunnelConfigId == tunnelConfigId }) return
        val toRemove = mutex.withLock {
            val matching = _rules.value.filter { it.tunnelConfigId == tunnelConfigId }
            if (matching.isNotEmpty()) {
                val next = _rules.value.filterNot { it.tunnelConfigId == tunnelConfigId }
                _rules.value = next
                saveRulesToPrefs(next)
            }
            matching
        }
        for (rule in toRemove) {
            stopForwardInternal(rule.id)
        }
    }

    private suspend fun startForwardInternal(rule: TunnelPortForwardRule) {
        stopForwardInternal(rule.id)
        _ruleErrors.value = _ruleErrors.value - rule.id
        val ready = kotlinx.coroutines.CompletableDeferred<Unit>()
        val job = scope.launch {
            val depId = forwardDependentId(rule.id)
            try {
                val tunnel = tunnelManager.acquire(rule.tunnelConfigId, depId)
                    ?: throw IOException("Tunnel config not found")
                when (rule.protocol) {
                    TunnelForwardProtocol.TCP -> runTcpForward(rule, tunnel, ready)
                    TunnelForwardProtocol.UDP -> runUdpForward(rule, tunnel, ready)
                }
            } catch (e: Throwable) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    Log.w(TAG, "Forward rule ${rule.id} (${rule.protocol} :${rule.localPort} -> ${rule.remoteHost}:${rule.remotePort}) failed: ${e.message}")
                    _ruleErrors.value = _ruleErrors.value + (rule.id to (e.message ?: e.javaClass.simpleName))
                }
                ready.complete(Unit)
            } finally {
                _boundPorts.value = _boundPorts.value - rule.id
                tunnelManager.release(depId)
            }
        }
        ready.await()
    }

    private suspend fun stopForwardInternal(ruleId: String) {
        val running = runningForwards.remove(ruleId)
        if (running != null) {
            runCatching { running.closeListener() }
            running.job.cancelAndJoin()
        }
        _boundPorts.value = _boundPorts.value - ruleId
        _ruleErrors.value = _ruleErrors.value - ruleId
        tunnelManager.release(forwardDependentId(ruleId))
    }

    private suspend fun runTcpForward(
        rule: TunnelPortForwardRule,
        tunnel: Tunnel,
        ready: kotlinx.coroutines.CompletableDeferred<Unit>,
    ) = withContext(ioDispatcher) {
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), rule.localPort), 50)
        val actualPort = server.localPort
        _boundPorts.value = _boundPorts.value + (rule.id to actualPort)
        runningForwards[rule.id] = RunningForward(
            rule = rule,
            job = kotlin.coroutines.coroutineContext[Job]!!,
            closeListener = { runCatching { server.close() } },
        )
        ready.complete(Unit)

        val activeClients = ConcurrentHashMap.newKeySet<Socket>()
        try {
            while (isActive && !server.isClosed) {
                val client = try {
                    server.accept()
                } catch (e: IOException) {
                    if (server.isClosed || !isActive) break
                    throw e
                }
                activeClients.add(client)
                launch {
                    try {
                        bridgeTcpClient(client, tunnel, rule.remoteHost, rule.remotePort)
                    } finally {
                        activeClients.remove(client)
                        runCatching { client.close() }
                    }
                }
            }
        } finally {
            runCatching { server.close() }
            activeClients.forEach { runCatching { it.close() } }
        }
    }

    private suspend fun bridgeTcpClient(
        client: Socket,
        tunnel: Tunnel,
        remoteHost: String,
        remotePort: Int,
    ) = withContext(ioDispatcher) {
        client.tcpNoDelay = true
        val remote = try {
            tunnel.dial(remoteHost, remotePort, 30_000)
        } catch (e: Throwable) {
            runCatching { client.close() }
            return@withContext
        }
        try {
            val upJob = launch {
                try {
                    val buf = ByteArray(16 * 1024)
                    val input = client.getInputStream()
                    val output = remote.outputStream
                    while (isActive) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n > 0) {
                            output.write(buf, 0, n)
                            output.flush()
                        }
                    }
                } catch (_: Throwable) {
                } finally {
                    runCatching { remote.close() }
                }
            }
            val downJob = launch {
                try {
                    val buf = ByteArray(16 * 1024)
                    val input = remote.inputStream
                    val output = client.getOutputStream()
                    while (isActive) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n > 0) {
                            output.write(buf, 0, n)
                            output.flush()
                        }
                    }
                } catch (_: Throwable) {
                } finally {
                    runCatching { client.close() }
                }
            }
            upJob.join()
            downJob.join()
        } finally {
            runCatching { remote.close() }
            runCatching { client.close() }
        }
    }

    private suspend fun runUdpForward(
        rule: TunnelPortForwardRule,
        tunnel: Tunnel,
        ready: kotlinx.coroutines.CompletableDeferred<Unit>,
    ) = withContext(ioDispatcher) {
        val tunnelUdp = tunnel.listenUdp()
            ?: throw IOException("This tunnel backend does not support UDP forwarding")
        val localSocket = DatagramSocket(null)
        localSocket.reuseAddress = true
        localSocket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), rule.localPort))
        val actualPort = localSocket.localPort
        _boundPorts.value = _boundPorts.value + (rule.id to actualPort)
        runningForwards[rule.id] = RunningForward(
            rule = rule,
            job = kotlin.coroutines.coroutineContext[Job]!!,
            closeListener = {
                runCatching { localSocket.close() }
                runCatching { tunnelUdp.close() }
            },
        )
        ready.complete(Unit)

        val lastClientAddr = AtomicReference<SocketAddress?>(null)
        val targetHost = runCatching {
            InetAddress.getByName(rule.remoteHost).hostAddress ?: rule.remoteHost
        }.getOrDefault(rule.remoteHost)

        try {
            val txJob = launch {
                val buf = ByteArray(65507)
                val packet = DatagramPacket(buf, buf.size)
                while (isActive && !localSocket.isClosed) {
                    try {
                        localSocket.receive(packet)
                        lastClientAddr.set(packet.socketAddress)
                        val payload = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                        tunnelUdp.send(payload, targetHost, rule.remotePort)
                    } catch (e: IOException) {
                        if (localSocket.isClosed || !isActive) break
                    }
                }
            }
            val rxJob = launch {
                val buf = ByteArray(65507)
                while (isActive && !localSocket.isClosed) {
                    try {
                        val received = tunnelUdp.receive(buf, 250) ?: continue
                        val clientAddr = lastClientAddr.get() ?: continue
                        val outPacket = DatagramPacket(buf, received.length, clientAddr)
                        localSocket.send(outPacket)
                    } catch (_: SocketTimeoutException) {
                    } catch (e: Throwable) {
                        if (localSocket.isClosed || !isActive) break
                    }
                }
            }
            txJob.join()
            rxJob.cancelAndJoin()
        } finally {
            runCatching { localSocket.close() }
            runCatching { tunnelUdp.close() }
        }
    }

    private suspend fun refreshSocksPorts(liveMap: Map<String, Tunnel>) = withContext(ioDispatcher) {
        val next = linkedMapOf<String, Int>()
        for ((configId, tunnel) in liveMap) {
            val addr = runCatching { tunnel.socksAddress() }.getOrNull()
            if (addr != null && addr.port > 0) {
                next[configId] = addr.port
            }
        }
        _socksPorts.value = next
        syncGuestSocksMarker(next)
    }

    private fun syncGuestSocksMarker(socksMap: Map<String, Int>) {
        val dir = cacheDir ?: return
        runCatching {
            val marker = File(dir, GUEST_SOCKS_MARKER)
            val firstPort = socksMap.values.firstOrNull()
            if (firstPort != null) {
                marker.writeText("127.0.0.1:$firstPort\n")
            } else {
                marker.delete()
            }
        }
    }

    private fun syncAutoProxyMarker(enabled: Boolean) {
        val dir = cacheDir ?: return
        runCatching {
            val marker = File(dir, GUEST_AUTO_PROXY_MARKER)
            if (enabled) {
                marker.writeText("1\n")
            } else {
                marker.delete()
            }
        }
    }

    private fun loadRulesFromPrefs(): List<TunnelPortForwardRule> {
        val raw = prefs?.getString(KEY_RULES_JSON, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("id").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
                    val tunnelConfigId = obj.optString("tunnelConfigId").takeIf { it.isNotBlank() } ?: continue
                    val protocol = runCatching {
                        TunnelForwardProtocol.valueOf(obj.optString("protocol", "TCP"))
                    }.getOrDefault(TunnelForwardProtocol.TCP)
                    val localPort = obj.optInt("localPort", -1)
                    val remoteHost = obj.optString("remoteHost").trim()
                    val remotePort = obj.optInt("remotePort", -1)
                    if (localPort in 0..65535 && remoteHost.isNotEmpty() && remotePort in 1..65535) {
                        add(
                            TunnelPortForwardRule(
                                id = id,
                                tunnelConfigId = tunnelConfigId,
                                protocol = protocol,
                                localPort = localPort,
                                remoteHost = remoteHost,
                                remotePort = remotePort,
                            ),
                        )
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveRulesToPrefs(list: List<TunnelPortForwardRule>) {
        val p = prefs ?: return
        val arr = JSONArray()
        for (rule in list) {
            arr.put(
                JSONObject().apply {
                    put("id", rule.id)
                    put("tunnelConfigId", rule.tunnelConfigId)
                    put("protocol", rule.protocol.name)
                    put("localPort", rule.localPort)
                    put("remoteHost", rule.remoteHost)
                    put("remotePort", rule.remotePort)
                },
            )
        }
        p.edit().putString(KEY_RULES_JSON, arr.toString()).apply()
    }

    companion object {
        internal const val PREFS_NAME = "haven_tunnel_port_forwards"
        internal const val KEY_RULES_JSON = "rules_json"
        internal const val KEY_AUTO_PROXY_GUESTS = "auto_proxy_guests"
        internal const val GUEST_SOCKS_MARKER = ".haven_tunnel_socks5"
        internal const val GUEST_AUTO_PROXY_MARKER = ".haven_tunnel_auto_proxy"

        fun standaloneDependentId(configId: String): String = "standalone:$configId"
        fun forwardDependentId(ruleId: String): String = "tunnel-fwd:$ruleId"
    }
}
