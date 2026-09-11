package sh.haven.feature.tunnel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import sh.haven.core.data.db.entities.ConnectionLog
import sh.haven.core.data.db.entities.TunnelConfig
import sh.haven.core.data.db.entities.TunnelConfigType
import sh.haven.core.data.repository.ConnectionLogRepository
import sh.haven.core.data.repository.TunnelConfigRepository
import sh.haven.core.tunnel.CloudflareAccessTunnel
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Backs the "Tunnels" management screen. List/add/delete tunnel configs
 * (WireGuard for now; Tailscale wiring follows in a second pass once the
 * tsnet bridge is in place).
 */
@HiltViewModel
class TunnelViewModel @Inject constructor(
    private val repository: TunnelConfigRepository,
    private val connectionLogRepository: ConnectionLogRepository,
    private val httpClient: OkHttpClient,
) : ViewModel() {

    /**
     * Result of a [testCloudflareAccess] call, surfaced inline next to
     * the "Test connection" button. Typealiased to the top-level
     * [CloudflareTunnelTestResult] so the same composable
     * ([CloudflareInlineFields]) renders results produced either here
     * or in another ViewModel (the SSH profile editor, GH #154).
     */
    @Deprecated(
        "Use CloudflareTunnelTestResult directly",
        ReplaceWith("CloudflareTunnelTestResult", "sh.haven.feature.tunnel.CloudflareTunnelTestResult"),
    )
    sealed class CloudflareAccessTestResult {
        data object Idle : CloudflareAccessTestResult()
        data object Running : CloudflareAccessTestResult()
        data class Success(val message: String) : CloudflareAccessTestResult()
        data class Failure(val message: String) : CloudflareAccessTestResult()
    }

    private val _cfTestResult = MutableStateFlow<CloudflareTunnelTestResult>(
        CloudflareTunnelTestResult.Idle,
    )
    val cfTestResult: StateFlow<CloudflareTunnelTestResult> = _cfTestResult.asStateFlow()

    fun resetCfTestResult() {
        _cfTestResult.value = CloudflareTunnelTestResult.Idle
    }

    /**
     * Build a transient [CloudflareAccessTunnel] from the form's current
     * state and try a single dial+close against the Cloudflare Tunnel
     * endpoint.
     *
     * Success means the WebSocket upgrade completed. Failure carries the
     * diagnostic detail produced by `CloudflareAccessTunnel.mapFailure` —
     * typically status code + `cf-ray` + a body excerpt for Cloudflare
     * error pages. The 302→`/cdn-cgi/access/login` case is mapped to a
     * "this route needs Access auth, sign in" hint.
     *
     * JWT is optional — unprotected Tunnel routes need only a hostname.
     *
     * Writes a row to the Connection Log either way so the user has a
     * persistent record after the inline UI clears. The synthetic
     * profileId `cf-tunnel-test:<hostname>` keeps the row out of the
     * per-profile log lookups but visible in the global summary view.
     */
    fun testCloudflareAccess(hostname: String, jwt: String, jumpDestination: String = "") {
        val trimmedHost = hostname.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
        if (trimmedHost.isEmpty()) {
            _cfTestResult.value = CloudflareTunnelTestResult.Failure(
                "Hostname required to test the Cloudflare Tunnel connection",
            )
            return
        }
        _cfTestResult.value = CloudflareTunnelTestResult.Running
        viewModelScope.launch {
            val syntheticId = "cf-tunnel-test:$trimmedHost"
            val started = System.currentTimeMillis()
            try {
                withContext(Dispatchers.IO) {
                    val tunnel = CloudflareAccessTunnel.forTest(
                        hostname = trimmedHost,
                        jwt = jwt.trim(),
                        jumpDestination = jumpDestination.trim(),
                        httpClient = httpClient.newBuilder()
                            .callTimeout(8, TimeUnit.SECONDS)
                            .build(),
                    )
                    try {
                        tunnel.dial(trimmedHost, 22, 8_000).close()
                    } finally {
                        tunnel.close()
                    }
                }
                val msg = if (jwt.isBlank()) {
                    "Cloudflare Tunnel reachable; WebSocket upgrade succeeded (no Access auth)."
                } else {
                    "Cloudflare Tunnel reachable; WebSocket upgrade succeeded with Access JWT."
                }
                _cfTestResult.value = CloudflareTunnelTestResult.Success(msg)
                connectionLogRepository.logEvent(
                    profileId = syntheticId,
                    status = ConnectionLog.Status.CONNECTED,
                    durationMs = System.currentTimeMillis() - started,
                    details = "Cloudflare Tunnel test: $msg",
                )
            } catch (e: Throwable) {
                val msg = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                _cfTestResult.value = CloudflareTunnelTestResult.Failure(msg)
                connectionLogRepository.logEvent(
                    profileId = syntheticId,
                    status = ConnectionLog.Status.FAILED,
                    durationMs = System.currentTimeMillis() - started,
                    details = "Cloudflare Tunnel test failed: $msg",
                )
            }
        }
    }

    /**
     * Standalone tunnels only — Cloudflare Tunnels embedded on an SSH
     * profile (GH #154) are hidden from this list; the user manages those
     * through the SSH profile editor.
     */
    val tunnels: StateFlow<List<TunnelConfig>> = repository.observeStandalone()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    fun dismissError() {
        _error.value = null
    }

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun dismissMessage() {
        _message.value = null
    }

    /**
     * Create a WireGuard tunnel config. Minimal validation — the Go-side
     * parser rejects malformed configs at connect time with a clear
     * message. Doing deep validation here would duplicate parser logic
     * and risk drifting out of sync.
     */
    fun addWireguardConfig(label: String, configText: String) {
        if (label.isBlank()) {
            _error.value = "Label is required"
            return
        }
        if (configText.isBlank()) {
            _error.value = "Config text is required"
            return
        }
        save(label, TunnelConfigType.WIREGUARD, configText.toByteArray())
    }

    /**
     * Create a Cloudflare Tunnel config (#154). JWT is **optional** —
     * unprotected Cloudflare Tunnel published-hostname routes need no
     * auth; only Access-protected ones do. When present, [jwt] is the
     * `CF_Authorization` JWT obtained either from the in-app WebView
     * login flow or pasted from a desktop `cloudflared access token`
     * run, and [expiresAtSeconds] is its `exp` claim (0 if unparseable).
     *
     * [jumpDestination] forwards as `Cf-Access-Jump-Destination` on the
     * WS upgrade — only meaningful for bastion-mode multi-target
     * tunnels. Blank for one-target routes.
     *
     * The config is **per-hostname**: dial() on the resulting tunnel
     * only accepts the configured hostname. This mirrors how
     * `cloudflared access ssh --hostname X` itself works — the server
     * side connector decides the upstream SSH target.
     */
    fun addCloudflareAccessConfig(
        label: String,
        hostname: String,
        teamDomain: String,
        jwt: String,
        expiresAtSeconds: Long,
        jumpDestination: String = "",
    ) {
        if (label.isBlank()) {
            _error.value = "Label is required"
            return
        }
        if (hostname.isBlank()) {
            _error.value = "Hostname is required"
            return
        }
        val blob = sh.haven.core.tunnel.CloudflareAccessConfigBlob(
            hostname = hostname.trim()
                .removePrefix("https://")
                .removePrefix("http://")
                .substringBefore('/'),
            teamDomain = teamDomain.trim().removePrefix("https://").removePrefix("http://"),
            jwt = jwt.trim(),
            jwtExpiresAt = expiresAtSeconds,
            jumpDestination = jumpDestination.trim(),
        )
        save(label, TunnelConfigType.CLOUDFLARE_ACCESS, blob.encode())
    }

    /**
     * Create a Tailscale tunnel config. The authkey joins the tailnet on
     * first use; tsnet persists node state under a per-config dir so
     * subsequent starts don't re-consume it.
     *
     * [controlURL] points at a self-hosted Headscale (or other compatible)
     * coordination server, e.g. "https://headscale.example.com". Leave
     * blank for the default Tailscale control plane (#124, mcbalaam).
     */
    fun addTailscaleConfig(label: String, authKey: String, controlURL: String = "") {
        if (label.isBlank()) {
            _error.value = "Label is required"
            return
        }
        if (authKey.isBlank()) {
            _error.value = "Auth key is required"
            return
        }
        val trimmedUrl = controlURL.trim()
        if (trimmedUrl.isNotEmpty() &&
            !trimmedUrl.startsWith("https://") &&
            !trimmedUrl.startsWith("http://")
        ) {
            _error.value = "Control plane URL must start with https:// (or http:// for local testing)"
            return
        }
        // Strip any leading/trailing whitespace paste artifacts — authkeys
        // are a single token with no internal spaces. Control URL likewise.
        val blob = sh.haven.core.tunnel.TailscaleConfigBlob(
            authKey = authKey.trim(),
            controlURL = trimmedUrl,
        )
        save(label, TunnelConfigType.TAILSCALE, blob.encode())
    }

    /**
     * Create a NetBird tunnel config. The setup key joins the network on
     * first use; the client embed persists peer state under a per-config
     * dir so subsequent starts don't re-consume it.
     *
     * [managementURL] points at a self-hosted NetBird management server,
     * e.g. "https://netbird.example.com". Leave blank for NetBird's
     * hosted default (#492).
     */
    fun addNetbirdConfig(label: String, setupKey: String, managementURL: String = "") {
        if (label.isBlank()) {
            _error.value = "Label is required"
            return
        }
        if (setupKey.isBlank()) {
            _error.value = "Setup key is required"
            return
        }
        val trimmedUrl = managementURL.trim()
        if (trimmedUrl.isNotEmpty() &&
            !trimmedUrl.startsWith("https://") &&
            !trimmedUrl.startsWith("http://")
        ) {
            _error.value = "Management URL must start with https:// (or http:// for local testing)"
            return
        }
        // Strip any leading/trailing whitespace paste artifacts — setup keys
        // are a single token with no internal spaces. Management URL likewise.
        val blob = sh.haven.core.tunnel.NetbirdConfigBlob(
            setupKey = setupKey.trim(),
            managementURL = trimmedUrl,
        )
        save(label, TunnelConfigType.NETBIRD, blob.encode())
    }

    private fun save(label: String, type: TunnelConfigType, bytes: ByteArray) {
        viewModelScope.launch {
            try {
                repository.save(
                    TunnelConfig(
                        label = label.trim(),
                        type = type.name,
                        configText = bytes,
                    ),
                )
                _message.value = "Tunnel \"${label.trim()}\" saved"
            } catch (e: Exception) {
                _error.value = "Save failed: ${e.message}"
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            try {
                repository.delete(id)
            } catch (e: Exception) {
                _error.value = "Delete failed: ${e.message}"
            }
        }
    }
}
