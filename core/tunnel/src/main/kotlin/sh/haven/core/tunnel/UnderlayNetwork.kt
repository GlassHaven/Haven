package sh.haven.core.tunnel

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-tunnel physical Android network preference (#876 Option B).
 *
 * When a phone is connected to a LAN Wi-Fi (for example an isolated lab or
 * router Wi-Fi with no internet) while mobile data or another interface holds
 * the system default route, binding the tunnel's underlay sockets and DNS
 * lookups to that specific [android.net.Network] reaches LAN endpoints without
 * taking the system VPN slot or changing other apps' routing.
 */
enum class UnderlayNetworkPreference(
    val storageValue: String,
    val displayName: String,
) {
    DEFAULT("", "Default"),
    WIFI("WIFI", "Wi-Fi"),
    CELLULAR("CELLULAR", "Cellular"),
    ETHERNET("ETHERNET", "Ethernet"),
    ;

    companion object {
        fun fromStorage(raw: String?): UnderlayNetworkPreference =
            when (raw?.trim()?.uppercase()) {
                "WIFI", "WI-FI" -> WIFI
                "CELLULAR", "MOBILE" -> CELLULAR
                "ETHERNET" -> ETHERNET
                else -> DEFAULT
            }

        fun parseStrict(raw: String?): UnderlayNetworkPreference? =
            when (raw?.trim()?.uppercase().orEmpty()) {
                "", "DEFAULT" -> DEFAULT
                "WIFI", "WI-FI" -> WIFI
                "CELLULAR", "MOBILE" -> CELLULAR
                "ETHERNET" -> ETHERNET
                else -> null
            }
    }
}

/**
 * Stores and extracts a WireGuard tunnel's [UnderlayNetworkPreference] as a
 * top-level `# haven-underlay-network: <TRANSPORT>` comment line inside its
 * encrypted `configText` payload, keeping the INI body valid `wg-quick` syntax
 * without requiring a Room schema migration.
 */
object WireguardUnderlayConfig {
    private val DIRECTIVE_LINE = Regex(
        """^\s*#\s*haven-underlay-network\s*:\s*(\S+)\s*$""",
        RegexOption.IGNORE_CASE,
    )

    fun extract(configText: String): UnderlayNetworkPreference {
        for (line in configText.lineSequence()) {
            val match = DIRECTIVE_LINE.matchEntire(line) ?: continue
            val pref = UnderlayNetworkPreference.fromStorage(match.groupValues[1])
            if (pref != UnderlayNetworkPreference.DEFAULT) return pref
        }
        return UnderlayNetworkPreference.DEFAULT
    }

    fun stripDirective(configText: String): String {
        val hadTrailingNewline = configText.endsWith('\n')
        val kept = configText.lineSequence()
            .filterNot { DIRECTIVE_LINE.matches(it) }
            .joinToString("\n")
        return if (hadTrailingNewline && kept.isNotEmpty() && !kept.endsWith('\n')) {
            "$kept\n"
        } else {
            kept
        }
    }

    fun withPreference(configText: String, pref: UnderlayNetworkPreference): String {
        val stripped = stripDirective(configText).trimStart('\n')
        if (pref == UnderlayNetworkPreference.DEFAULT) return stripped
        return "# haven-underlay-network: ${pref.storageValue}\n$stripped"
    }
}

internal data class CandidateUnderlayNetwork(
    val handle: Long,
    val isNotVpn: Boolean,
    val isValidated: Boolean,
    val hasInternet: Boolean,
    val hasWifi: Boolean,
    val hasCellular: Boolean,
    val hasEthernet: Boolean,
)

/**
 * Resolves an [UnderlayNetworkPreference] to a live `android.net.Network.networkHandle`
 * (`net_handle_t` in Android NDK `<android/multinetwork.h>`).
 */
interface UnderlayNetworkResolver {
    @Throws(IOException::class)
    fun resolveRequiredHandle(pref: UnderlayNetworkPreference, tunnelLabel: String): Long

    fun currentNetworkKey(pref: UnderlayNetworkPreference): Long
}

@Singleton
class AndroidUnderlayNetworkResolver internal constructor(
    private val context: Context?,
    private val snapshotProvider: (() -> Pair<Long, List<CandidateUnderlayNetwork>>)? = null,
) : UnderlayNetworkResolver {

    @Inject
    constructor(@ApplicationContext context: Context) : this(context, null)

    override fun resolveRequiredHandle(pref: UnderlayNetworkPreference, tunnelLabel: String): Long {
        if (pref == UnderlayNetworkPreference.DEFAULT) return 0L
        val (activeHandle, candidates) = snapshot()
        val handle = selectHandle(pref, activeHandle, candidates)
        if (handle == 0L) {
            val labelPart = tunnelLabel.takeIf { it.isNotBlank() }?.let { " '$it'" }.orEmpty()
            throw IOException(
                "Tunnel$labelPart is set to bind its underlay to ${pref.displayName}, " +
                    "but ${pref.displayName} is not connected",
            )
        }
        return handle
    }

    override fun currentNetworkKey(pref: UnderlayNetworkPreference): Long {
        val (activeHandle, candidates) = snapshot()
        return if (pref == UnderlayNetworkPreference.DEFAULT) {
            activeHandle
        } else {
            selectHandle(pref, activeHandle, candidates)
        }
    }

    @SuppressLint("MissingPermission")
    private fun snapshot(): Pair<Long, List<CandidateUnderlayNetwork>> {
        snapshotProvider?.let { return it() }
        return runCatching {
            val cm = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return 0L to emptyList()
            val active = cm.activeNetwork?.networkHandle ?: 0L
            @Suppress("DEPRECATION")
            val list = cm.allNetworks.mapNotNull { net ->
                val caps = cm.getNetworkCapabilities(net) ?: return@mapNotNull null
                val handle = net.networkHandle
                if (handle == 0L) return@mapNotNull null
                CandidateUnderlayNetwork(
                    handle = handle,
                    isNotVpn = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
                    isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                    hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                    hasWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                    hasCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                    hasEthernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                )
            }
            active to list
        }.getOrElse { 0L to emptyList() }
    }

    companion object {
        internal fun selectHandle(
            pref: UnderlayNetworkPreference,
            activeHandle: Long,
            candidates: List<CandidateUnderlayNetwork>,
        ): Long {
            if (pref == UnderlayNetworkPreference.DEFAULT) return 0L
            val matching = candidates.filter { c ->
                c.handle != 0L && c.isNotVpn && when (pref) {
                    UnderlayNetworkPreference.WIFI -> c.hasWifi
                    UnderlayNetworkPreference.CELLULAR -> c.hasCellular
                    UnderlayNetworkPreference.ETHERNET -> c.hasEthernet
                    UnderlayNetworkPreference.DEFAULT -> false
                }
            }
            if (matching.isEmpty()) return 0L
            return matching.firstOrNull { it.handle == activeHandle }?.handle
                ?: matching.firstOrNull { it.isValidated }?.handle
                ?: matching.firstOrNull { it.hasInternet }?.handle
                ?: matching.first().handle
        }
    }
}
