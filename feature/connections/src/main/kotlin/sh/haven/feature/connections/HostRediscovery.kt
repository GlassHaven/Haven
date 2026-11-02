package sh.haven.feature.connections

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import sh.haven.core.data.db.KnownHostDao
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.ssh.KnownHostEntry
import sh.haven.core.ssh.SshClient
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton
import sh.haven.core.redact.LogRedact

/**
 * Per-profile policy for #376 host rediscovery (#776), stored as a
 * `HavenHostRediscovery` (or `HavenFollowAddressChanges`) directive in
 * [ConnectionProfile.sshOptions].
 */
internal enum class HostRediscoveryMode {
    /** Default: connect to the rediscovered host, seed known_hosts, and update `profile.host`. */
    PERSIST,
    /** Connect to the rediscovered host for this session and seed known_hosts, but keep `profile.host` unchanged (#776). */
    SESSION_ONLY,
    /** Do not scan or follow host-key matches on network errors. */
    OFF,
}

/**
 * Parse the host-rediscovery policy from [options] (`ConnectionProfile.sshOptions`).
 * Recognises `HavenHostRediscovery <persist|session|off>` and the alias
 * `HavenFollowAddressChanges <yes|no|off>` (#776). Defaults to [HostRediscoveryMode.PERSIST].
 */
internal fun hostRediscoveryMode(options: String?): HostRediscoveryMode {
    if (options.isNullOrBlank()) return HostRediscoveryMode.PERSIST
    var mode = HostRediscoveryMode.PERSIST
    for (rawLine in options.lineSequence()) {
        val trimmed = rawLine.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
        val sep = trimmed.indexOfFirst { it == ' ' || it == '\t' || it == '=' }
        if (sep <= 0) continue
        val key = trimmed.substring(0, sep).trim()
        val value = trimmed.substring(sep + 1).trim().lowercase()
        if (key.equals("HavenHostRediscovery", ignoreCase = true)) {
            mode = when (value) {
                "session", "session-only", "ephemeral", "no-persist", "nopersist" -> HostRediscoveryMode.SESSION_ONLY
                "off", "no", "false", "disabled", "never" -> HostRediscoveryMode.OFF
                else -> HostRediscoveryMode.PERSIST
            }
        } else if (key.equals("HavenFollowAddressChanges", ignoreCase = true)) {
            mode = when (value) {
                "no", "false", "session", "session-only", "ephemeral", "no-persist", "nopersist" -> HostRediscoveryMode.SESSION_ONLY
                "off", "disabled", "never" -> HostRediscoveryMode.OFF
                else -> HostRediscoveryMode.PERSIST
            }
        }
    }
    return mode
}

/**
 * Rewrite [options] with the given [mode] (`PERSIST` drops the directive).
 */
internal fun setHostRediscoveryInOptions(options: String, mode: HostRediscoveryMode): String {
    val kept = options.lines().filterNot { line ->
        val trimmed = line.trim()
        if (trimmed.startsWith("#")) return@filterNot false
        val sep = trimmed.indexOfFirst { it == ' ' || it == '\t' || it == '=' }
        if (sep <= 0) return@filterNot false
        val key = trimmed.substring(0, sep).trim()
        key.equals("HavenHostRediscovery", ignoreCase = true) ||
            key.equals("HavenFollowAddressChanges", ignoreCase = true)
    }
    val base = kept.joinToString("\n").trim('\n')
    val directive = when (mode) {
        HostRediscoveryMode.PERSIST -> null
        HostRediscoveryMode.SESSION_ONLY -> "HavenHostRediscovery session"
        HostRediscoveryMode.OFF -> "HavenHostRediscovery off"
    }
    return when {
        directive == null -> base
        base.isBlank() -> directive
        else -> "$base\n$directive"
    }
}

/**
 * Follows a device across DHCP address changes by its SSH host key (#376).
 *
 * A profile's saved IP is just a hint — the verified host key is the device's
 * identity. When a connect fails with a network error on a private address,
 * [rediscover] sweeps the local /24 on the profile's port, keyscans the
 * responders, and — only when EXACTLY ONE machine presents the profile's
 * stored known-host key — persists the new address (and seeds the known-hosts
 * entry for it, so the next TOFU check matches instead of re-prompting for a
 * host we just identified BY that key). Ambiguity (two machines with the same
 * key, e.g. cloned VMs) fails closed.
 *
 * No stored key ⇒ no rediscovery: the key is the only identity we trust, so a
 * never-trusted profile can't be followed.
 */
@Singleton
class HostRediscovery @Inject constructor(
    private val knownHostDao: KnownHostDao,
    private val connectionRepository: ConnectionRepository,
) {
    /** Test seams — the real implementations dial sockets / read interfaces. */
    internal var probe: (host: String, port: Int, timeoutMs: Int) -> Boolean = ::probePort
    internal var keyScan: (host: String, port: Int) -> KnownHostEntry? =
        { h, p -> SshClient.keyScan(h, p) }
    /** /24 bases of every private-IPv4 interface the phone has right now. */
    internal var localBases: () -> Set<String> = ::localInterfaceBases

    /**
     * Returns the device's new address after persisting it, or null when
     * rediscovery doesn't apply (public/hostname address, no stored key, no
     * subnet) or finds no unambiguous match.
     */
    suspend fun rediscover(profile: ConnectionProfile): String? = withContext(Dispatchers.IO) {
        val mode = hostRediscoveryMode(profile.sshOptions)
        if (mode == HostRediscoveryMode.OFF) return@withContext null
        // #777: never scan the local LAN directly when the profile routes
        // through a proxy, tunnel, or jump host — the target's RFC1918 address
        // belongs to the remote network behind that route, and scanning local
        // /24 subnets directly when the proxy dial fails bypasses the proxy.
        if (!profile.proxyType.isNullOrBlank() ||
            !profile.tunnelConfigId.isNullOrBlank() ||
            !profile.jumpProfileId.isNullOrBlank()
        ) {
            return@withContext null
        }
        if (!isPrivateIpv4(profile.host)) return@withContext null
        val stored = knownHostDao.findByHostPort(profile.host, profile.port)
            ?: return@withContext null

        // Which /24s to sweep. The device's new address is unknown, so scan every
        // subnet the phone can reach a local device on:
        //  - each of the phone's OWN interface /24s (localBases). This is the case
        //    #367 needs: the phone is the DHCP server for the device over its
        //    hotspot, and Android hands the AP a *fresh, random* subnet each
        //    session (reporter: 10.235.30.x → 10.50.150.x), so the device is on
        //    the phone's tether interface — NOT its old /24, and NOT the phone's
        //    default network (the cellular uplink, which is what the old
        //    activeNetwork-only scan wrongly swept).
        //  - the device's last-known /24 (profile.host's /24): covers a genuine
        //    within-subnet DHCP renewal on a normal router, where the phone may
        //    not itself hold an address on that subnet.
        // Deduped, so the common single-LAN case is one sweep.
        val bases = buildSet {
            addAll(localBases())
            ipv4Base(profile.host)?.let { add(it) }
        }
        if (bases.isEmpty()) return@withContext null
        Log.d(TAG, "rediscover ${LogRedact.of(profile.label)}: scanning ${bases.size} subnet(s) $bases on :${profile.port}")

        val candidates = coroutineScope {
            bases.flatMap { base ->
                (1..254).map { i ->
                    async {
                        val ip = "$base.$i"
                        ip.takeIf { it != profile.host && probe(it, profile.port, PROBE_TIMEOUT_MS) }
                    }
                }
            }.awaitAll().filterNotNull()
        }.distinct()
        if (candidates.isEmpty()) return@withContext null
        Log.d(TAG, "rediscover ${LogRedact.of(profile.label)}: ${candidates.size} responder(s) on :${profile.port}")

        val matches = coroutineScope {
            candidates.map { ip ->
                async {
                    val key = keyScan(ip, profile.port)
                    ip.takeIf {
                        key != null &&
                            key.keyType == stored.keyType &&
                            key.publicKeyBase64 == stored.publicKeyBase64
                    }
                }
            }.awaitAll().filterNotNull()
        }
        val newHost = matches.singleOrNull() ?: run {
            Log.i(TAG, "rediscover ${LogRedact.of(profile.label)}: ${matches.size} key match(es) — not following")
            return@withContext null
        }

        if (mode == HostRediscoveryMode.PERSIST) {
            connectionRepository.updateHost(profile.id, newHost)
        }
        knownHostDao.deleteByHostPort(newHost, profile.port)
        knownHostDao.upsert(stored.copy(id = 0, hostname = newHost))
        Log.i(
            TAG,
            "rediscover ${LogRedact.of(profile.label)}: ${LogRedact.of(profile.host)} → " +
                "${LogRedact.of(newHost)} (host key matched, mode=$mode)",
        )
        newHost
    }

    /** The /24 base ("a.b.c") of a dotted-quad IPv4, or null if [host] isn't one. */
    private fun ipv4Base(host: String): String? = host.split(".")
        .takeIf { it.size == 4 && it.all { part -> part.toIntOrNull() in 0..255 } }
        ?.let { "${it[0]}.${it[1]}.${it[2]}" }

    private fun isPrivateIpv4(host: String): Boolean {
        val p = host.split(".").mapNotNull { it.toIntOrNull() }
        if (p.size != 4 || p.any { it !in 0..255 }) return false
        return p[0] == 10 ||
            (p[0] == 192 && p[1] == 168) ||
            (p[0] == 172 && p[1] in 16..31)
    }

    /**
     * The /24 base of every private-IPv4 address the phone currently holds on any
     * up interface — Wi-Fi, and crucially the SoftAP/tether interface when the
     * phone is running a hotspot (#367). `ConnectivityManager.activeNetwork` only
     * ever names the single DEFAULT network (the internet uplink), so it can't see
     * a tether the phone is the server for; `NetworkInterface` enumerates the
     * kernel's netdevs directly and does. Loopback and non-RFC1918 addresses
     * (e.g. carrier CGNAT) are excluded.
     */
    private fun localInterfaceBases(): Set<String> = try {
        NetworkInterface.getNetworkInterfaces()?.asSequence().orEmpty()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress && isPrivateIpv4(it.hostAddress.orEmpty()) }
            .mapNotNull { ipv4Base(it.hostAddress.orEmpty()) }
            .toSet()
    } catch (e: Exception) {
        Log.e(TAG, "localInterfaceBases failed", e)
        emptySet()
    }

    private fun probePort(host: String, port: Int, timeoutMs: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs); true }
    } catch (_: Exception) {
        false
    }

    private companion object {
        const val TAG = "HostRediscovery"
        const val PROBE_TIMEOUT_MS = 400
    }
}
