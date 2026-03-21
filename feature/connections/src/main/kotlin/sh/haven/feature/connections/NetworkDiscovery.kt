package sh.haven.feature.connections

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.FileReader
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket

private const val TAG = "NetworkDiscovery"

data class DiscoveredHost(
    val address: String,
    val hostname: String?,
    val port: Int = 22,
    val source: String, // "mDNS", "scan", or "localhost"
)

data class LocalVmStatus(
    val terminalAppInstalled: Boolean = false,
    val sshPort: Int? = null,
    val vncPort: Int? = null,
    val directIp: String? = null,
    val directSshPort: Int? = null,
    val directVncPort: Int? = null,
)

/**
 * Discovers SSH hosts on the local network via:
 * 1. mDNS/DNS-SD: listens for _ssh._tcp services (Avahi/Bonjour)
 * 2. ARP scan: reads /proc/net/arp and probes port 22
 */
class NetworkDiscovery(private val context: Context) {

    private val _hosts = MutableStateFlow<List<DiscoveredHost>>(emptyList())
    val hosts: StateFlow<List<DiscoveredHost>> = _hosts.asStateFlow()

    private val _smbHosts = MutableStateFlow<List<DiscoveredHost>>(emptyList())
    val smbHosts: StateFlow<List<DiscoveredHost>> = _smbHosts.asStateFlow()

    private val _localVm = MutableStateFlow(LocalVmStatus())
    val localVm: StateFlow<LocalVmStatus> = _localVm.asStateFlow()

    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val mdnsHosts = mutableSetOf<DiscoveredHost>()
    private val arpHosts = mutableSetOf<DiscoveredHost>()
    private val smbScanHosts = mutableSetOf<DiscoveredHost>()
    private var vmPollingJob: Job? = null

    fun start() {
        startMdns()
    }

    fun stop() {
        stopVmPolling()
        stopMdns()
        mdnsHosts.clear()
        arpHosts.clear()
        smbScanHosts.clear()
        _hosts.value = emptyList()
        _smbHosts.value = emptyList()
        _localVm.value = LocalVmStatus()
    }

    fun startVmPolling(scope: kotlinx.coroutines.CoroutineScope) {
        stopVmPolling()
        vmPollingJob = scope.launch {
            while (true) {
                scanLocalVm()
                delay(5_000)
            }
        }
    }

    fun stopVmPolling() {
        vmPollingJob?.cancel()
        vmPollingJob = null
    }

    /**
     * Probe localhost for SSH/VNC ports commonly used by the Android Linux VM.
     * The Terminal app auto-forwards guest TCP ports to localhost via vsock.
     * Also probes the VM's direct IP on the avf_tap_fixed interface.
     */
    suspend fun scanLocalVm() {
        withContext(Dispatchers.IO) {
            val terminalInstalled = isTerminalAppInstalled()
            // Use 100ms timeout for localhost — connections are instant or not there
            val sshPorts = listOf(8022, 2222, 22)
            val vncPorts = listOf(5900, 5901, 5902)

            // Probe localhost forwarded ports
            val sshPort = sshPorts.firstOrNull { probePort("127.0.0.1", it, 100) }
            val vncPort = vncPorts.firstOrNull { probePort("127.0.0.1", it, 100) }

            // Probe VM's direct IP (avf_tap_fixed interface)
            val vmIp = discoverVmDirectIp()
            var directSshPort: Int? = null
            var directVncPort: Int? = null
            if (vmIp != null) {
                directSshPort = if (probePort(vmIp, 22, 100)) 22 else null
                directVncPort = vncPorts.firstOrNull { probePort(vmIp, it, 100) }
            }

            _localVm.value = LocalVmStatus(
                terminalAppInstalled = terminalInstalled,
                sshPort = sshPort,
                vncPort = vncPort,
                directIp = if (directSshPort != null || directVncPort != null) vmIp else null,
                directSshPort = directSshPort,
                directVncPort = directVncPort,
            )
            if (terminalInstalled) {
                Log.d(TAG, "Android Terminal app detected")
            }
            if (sshPort != null) {
                Log.d(TAG, "Local VM SSH detected on port $sshPort")
            }
            if (vncPort != null) {
                Log.d(TAG, "Local VM VNC detected on port $vncPort")
            }
            if (vmIp != null && (directSshPort != null || directVncPort != null)) {
                Log.d(TAG, "VM direct IP $vmIp: SSH=$directSshPort VNC=$directVncPort")
            }
        }
    }

    /**
     * Discover the VM's direct IP by reading /proc/net/route for avf_tap interfaces.
     * Returns the gateway IP or derived host IP (e.g. 10.255.32.2).
     */
    private fun discoverVmDirectIp(): String? {
        return try {
            BufferedReader(FileReader("/proc/net/route")).use { reader ->
                reader.readLine() // skip header
                var line = reader.readLine()
                while (line != null) {
                    val fields = line.split("\t")
                    if (fields.size >= 3 && fields[0].startsWith("avf_tap")) {
                        // Gateway field is in little-endian hex
                        val gatewayHex = fields[2]
                        if (gatewayHex != "00000000") {
                            val ip = hexToIp(gatewayHex)
                            if (ip != null) return@use ip
                        }
                        // Try destination field as network base, VM is typically at .2
                        val destHex = fields[1]
                        if (destHex != "00000000") {
                            val baseIp = hexToIp(destHex)
                            if (baseIp != null) {
                                val parts = baseIp.split(".")
                                if (parts.size == 4) {
                                    return@use "${parts[0]}.${parts[1]}.${parts[2]}.2"
                                }
                            }
                        }
                    }
                    line = reader.readLine()
                }
                null
            }
        } catch (e: Exception) {
            Log.d(TAG, "Could not read /proc/net/route for VM IP: ${e.message}")
            null
        }
    }

    /** Convert little-endian hex IP from /proc/net/route to dotted notation. */
    private fun hexToIp(hex: String): String? {
        if (hex.length != 8) return null
        return try {
            val n = hex.toLong(16)
            "${n and 0xFF}.${(n shr 8) and 0xFF}.${(n shr 16) and 0xFF}.${(n shr 24) and 0xFF}"
        } catch (_: Exception) {
            null
        }
    }

    private fun isTerminalAppInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(
                "com.android.virtualization.terminal", 0,
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Scan the local /24 subnet for hosts with SSH on port 22.
     * Uses ConnectivityManager to determine the local IP, then probes
     * all 254 addresses in parallel with a short timeout.
     */
    suspend fun scanSubnet() {
        withContext(Dispatchers.IO) {
            try {
                val baseIp = getLocalSubnetBase() ?: run {
                    Log.d(TAG, "Could not determine local subnet")
                    return@withContext
                }
                Log.d(TAG, "Scanning subnet $baseIp.1-254 for SSH")

                coroutineScope {
                    val jobs = (1..254).map { i ->
                        async(Dispatchers.IO) {
                            val ip = "$baseIp.$i"
                            if (probePort(ip, 22, timeoutMs = 400)) {
                                val hostname = resolveHostname(ip)
                                val host = DiscoveredHost(
                                    address = ip,
                                    hostname = hostname,
                                    port = 22,
                                    source = "scan",
                                )
                                synchronized(arpHosts) {
                                    arpHosts.add(host)
                                }
                                mergeAndEmit()
                                Log.d(TAG, "SSH found: $ip ($hostname)")
                            }
                        }
                    }
                    jobs.awaitAll()
                }
                Log.d(TAG, "Subnet scan complete: ${arpHosts.size} SSH hosts found")
            } catch (e: Exception) {
                Log.e(TAG, "Subnet scan failed", e)
            }
        }
    }

    /**
     * Scan the local /24 subnet for hosts with SMB on port 445.
     */
    suspend fun scanSubnetSmb() {
        withContext(Dispatchers.IO) {
            try {
                val baseIp = getLocalSubnetBase() ?: run {
                    Log.d(TAG, "Could not determine local subnet")
                    return@withContext
                }
                Log.d(TAG, "Scanning subnet $baseIp.1-254 for SMB")

                coroutineScope {
                    val jobs = (1..254).map { i ->
                        async(Dispatchers.IO) {
                            val ip = "$baseIp.$i"
                            if (probePort(ip, 445, timeoutMs = 400)) {
                                val hostname = resolveHostname(ip)
                                val host = DiscoveredHost(
                                    address = ip,
                                    hostname = hostname,
                                    port = 445,
                                    source = "scan",
                                )
                                synchronized(smbScanHosts) {
                                    smbScanHosts.add(host)
                                }
                                mergeSmbAndEmit()
                                Log.d(TAG, "SMB found: $ip ($hostname)")
                            }
                        }
                    }
                    jobs.awaitAll()
                }
                Log.d(TAG, "SMB subnet scan complete: ${smbScanHosts.size} hosts found")
            } catch (e: Exception) {
                Log.e(TAG, "SMB subnet scan failed", e)
            }
        }
    }

    private fun mergeSmbAndEmit() {
        val all = smbScanHosts.toList()
            .distinctBy { it.address }
            .sortedWith(compareBy(
                { it.hostname == null },
                { it.address },
            ))
        _smbHosts.value = all
    }

    @SuppressLint("MissingPermission") // ACCESS_NETWORK_STATE declared in app manifest
    private fun getLocalSubnetBase(): String? {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return null
            val props: LinkProperties = cm.getLinkProperties(network) ?: return null
            val ipv4 = props.linkAddresses
                .map { it.address }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
                ?: return null
            val parts = ipv4.hostAddress?.split(".") ?: return null
            if (parts.size != 4) return null
            return "${parts[0]}.${parts[1]}.${parts[2]}"
        } catch (e: Exception) {
            Log.e(TAG, "getLocalSubnetBase failed", e)
            return null
        }
    }

    private fun startMdns() {
        try {
            val mgr = context.getSystemService(Context.NSD_SERVICE) as NsdManager
            nsdManager = mgr

            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {
                    Log.d(TAG, "mDNS discovery started for $serviceType")
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    Log.d(TAG, "mDNS found: ${serviceInfo.serviceName}")
                    mgr.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                            Log.d(TAG, "mDNS resolve failed: ${info.serviceName} error=$errorCode")
                        }

                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val addr = info.host?.hostAddress ?: return
                            val host = DiscoveredHost(
                                address = addr,
                                hostname = info.serviceName,
                                port = info.port,
                                source = "mDNS",
                            )
                            Log.d(TAG, "mDNS resolved: $addr (${info.serviceName}:${info.port})")
                            mdnsHosts.add(host)
                            mergeAndEmit()
                        }
                    })
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    mdnsHosts.removeAll { it.hostname == serviceInfo.serviceName }
                    mergeAndEmit()
                }

                override fun onDiscoveryStopped(serviceType: String) {
                    Log.d(TAG, "mDNS discovery stopped")
                }

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.e(TAG, "mDNS start failed: error=$errorCode")
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.e(TAG, "mDNS stop failed: error=$errorCode")
                }
            }

            discoveryListener = listener
            mgr.discoverServices("_ssh._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(TAG, "mDNS init failed", e)
        }
    }

    private fun stopMdns() {
        try {
            discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
        } catch (_: Exception) {
            // Already stopped
        }
        discoveryListener = null
        nsdManager = null
    }

    private fun mergeAndEmit() {
        val all = (mdnsHosts + arpHosts)
            .distinctBy { it.address }
            .sortedWith(compareBy(
                { it.port != 22 },       // port 22 first
                { it.source != "mDNS" }, // mDNS before ARP
                { it.hostname == null },  // named hosts before bare IPs
                { it.address },
            ))
        _hosts.value = all
    }

    private fun probePort(host: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun resolveHostname(ip: String): String? {
        return try {
            val addr = java.net.InetAddress.getByName(ip)
            val name = addr.canonicalHostName
            if (name != ip) name else null
        } catch (_: Exception) {
            null
        }
    }
}
