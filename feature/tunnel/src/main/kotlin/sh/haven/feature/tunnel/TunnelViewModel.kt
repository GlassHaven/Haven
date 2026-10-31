package sh.haven.feature.tunnel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import sh.haven.core.data.db.entities.ConnectionLog
import sh.haven.core.data.db.entities.TunnelConfig
import sh.haven.core.data.db.entities.TunnelConfigType
import sh.haven.core.data.db.entities.typeEnum
import sh.haven.core.data.repository.ConnectionLogRepository
import sh.haven.core.data.repository.TunnelConfigRepository
import sh.haven.core.tunnel.AnyConnectConfigBlob
import sh.haven.core.tunnel.AnyConnectSessionController
import sh.haven.core.tunnel.CloudflareAccessTunnel
import sh.haven.core.tunnel.TunnelManager
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Backs the "Tunnels" management screen. List/add/edit/delete tunnel
 * configs (WireGuard for now; Tailscale wiring follows in a second pass once
 * the tsnet bridge is in place).
 */
@HiltViewModel
class TunnelViewModel @Inject constructor(
    private val repository: TunnelConfigRepository,
    private val connectionLogRepository: ConnectionLogRepository,
    private val httpClient: OkHttpClient,
    tunnelManager: TunnelManager,
    private val anyConnectSessions: AnyConnectSessionController,
) : ViewModel() {

    /** Ids of tunnels that are up right now, for the list's "Connected" badge. */
    val connectedIds: StateFlow<Set<String>> = tunnelManager.live
        .map { it.keys }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /**
     * Disconnect a live tunnel. For AnyConnect, [signOut] also ends the
     * gateway session (next connect signs in, e.g. with a new SMS code);
     * without it the session stays resumable for the gateway's idle timeout.
     */
    fun disconnect(id: String, signOut: Boolean) {
        viewModelScope.launch {
            try {
                anyConnectSessions.disconnect(id, signOut)
            } catch (e: Exception) {
                _error.value = "Disconnect failed: ${e.message}"
            }
        }
    }

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

    /**
     * Create an OpenVPN tunnel config from a pasted `.ovpn` profile.
     * [username]/[password] are only needed when the profile's server uses
     * `auth-user-pass` without an inline credentials file — most profiles
     * carry a client certificate and need neither.
     */
    fun addOpenVpnConfig(label: String, ovpnConfigText: String, username: String = "", password: String = "") {
        if (label.isBlank()) {
            _error.value = "Label is required"
            return
        }
        if (ovpnConfigText.isBlank()) {
            _error.value = "OpenVPN profile text is required"
            return
        }
        val blob = sh.haven.core.tunnel.OpenVpnConfigBlob(
            ovpnConfigText = ovpnConfigText,
            username = username.trim(),
            password = password,
        )
        save(label, TunnelConfigType.OPENVPN, blob.encode())
    }

    /** Overwrite an existing OpenVPN tunnel's profile and credentials. */
    fun updateOpenVpnConfig(
        id: String,
        label: String,
        ovpnConfigText: String,
        username: String = "",
        password: String = "",
    ) {
        if (label.isBlank()) {
            _error.value = "Label is required"
            return
        }
        if (ovpnConfigText.isBlank()) {
            _error.value = "OpenVPN profile text is required"
            return
        }
        val blob = sh.haven.core.tunnel.OpenVpnConfigBlob(
            ovpnConfigText = ovpnConfigText,
            username = username.trim(),
            password = password,
        )
        update(id, label, blob.encode())
    }

    /**
     * Create a Cisco AnyConnect / ocserv tunnel config. Only shape is
     * validated here (the server is reachable over https, a username is
     * present); the native side reports auth problems — wrong password,
     * unknown group, missing second factor, untrusted certificate — with
     * actionable messages at connect time.
     */
    fun addAnyConnectConfig(label: String, form: AnyConnectForm) {
        val blob = validateAnyConnect(label, form) ?: return
        save(label, TunnelConfigType.ANYCONNECT, blob.encode())
    }

    /**
     * Overwrite an existing AnyConnect tunnel in place (same contract as #666
     * edits). A live or resumable session made with the old settings is
     * ended first, so the next connect uses the new ones: signed out on the
     * gateway when the account changed (server / user / group), merely
     * disconnected — still resumable — when only e.g. the password or pin
     * did. Label and "Stay connected" edits leave a live tunnel alone.
     */
    fun updateAnyConnectConfig(id: String, label: String, form: AnyConnectForm) {
        val blob = validateAnyConnect(label, form) ?: return
        viewModelScope.launch {
            val old = repository.getById(id)
                ?.takeIf { it.typeEnum == TunnelConfigType.ANYCONNECT }
                ?.let { AnyConnectConfigBlob.parse(it.configText) }
            if (old != null && old.toNativeConfig() != blob.toNativeConfig()) {
                val accountChanged = old.server != blob.server ||
                    !old.username.equals(blob.username, ignoreCase = true) ||
                    !old.authGroup.equals(blob.authGroup, ignoreCase = true)
                runCatching { anyConnectSessions.disconnect(id, signOut = accountChanged) }
            }
            update(id, label, blob.encode())
        }
    }

    private fun validateAnyConnect(label: String, form: AnyConnectForm): sh.haven.core.tunnel.AnyConnectConfigBlob? {
        if (label.isBlank()) {
            _error.value = "Label is required"
            return null
        }
        if (form.server.isBlank()) {
            _error.value = "Server is required"
            return null
        }
        val server = sh.haven.core.tunnel.AnyConnectConfigBlob.normalizeServer(form.server)
        if (server == null) {
            _error.value = "Server must use https:// (AnyConnect gateways never use plain http)"
            return null
        }
        if (form.username.isBlank()) {
            _error.value = "Username is required"
            return null
        }
        val cert = form.serverCert.trim()
        if (cert.isNotEmpty() && !CERT_PIN.matches(cert)) {
            _error.value = "Server certificate must look like pin-sha256:<base64>, sha256:<hex> or sha1:<hex>"
            return null
        }
        return sh.haven.core.tunnel.AnyConnectConfigBlob(
            server = server,
            username = form.username.trim(),
            // Passwords are taken verbatim — whitespace can be significant.
            password = form.password,
            authGroup = form.authGroup.trim(),
            otpSecret = form.otpSecret.trim(),
            secondaryPassword = form.secondaryPassword,
            serverCert = cert,
            stayConnected = form.stayConnected,
        )
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

    /**
     * Decrypted row for the edit dialog's prefill. Null when the row is
     * gone (deleted concurrently between the list render and the tap).
     */
    suspend fun getDecryptedTunnel(id: String): TunnelConfig? = repository.getById(id)

    /**
     * Overwrite an existing WireGuard tunnel in place (#666), keeping the
     * row's id, type, createdAt and owner so the encrypted-at-rest row stays
     * addressable and the list keeps its created ordering.
     */
    fun updateWireguardConfig(id: String, label: String, configText: String) {
        if (label.isBlank()) {
            _error.value = "Label is required"
            return
        }
        if (configText.isBlank()) {
            _error.value = "Config text is required"
            return
        }
        update(id, label, configText.toByteArray())
    }

    /** Edit an existing Tailscale tunnel's label / authkey / control plane (#666). */
    fun updateTailscaleConfig(id: String, label: String, authKey: String, controlURL: String = "") {
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
        val blob = sh.haven.core.tunnel.TailscaleConfigBlob(
            authKey = authKey.trim(),
            controlURL = trimmedUrl,
        )
        update(id, label, blob.encode())
    }

    /** Overwrite an existing NetBird tunnel's label / setup key / management URL (#666). */
    fun updateNetbirdConfig(id: String, label: String, setupKey: String, managementURL: String = "") {
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
        val blob = sh.haven.core.tunnel.NetbirdConfigBlob(
            setupKey = setupKey.trim(),
            managementURL = trimmedUrl,
        )
        update(id, label, blob.encode())
    }

    /**
     * Shared overwrite path. Id/key fields all come from the CURRENT row (a
     * re-fetched [TunnelConfig]), not from the caller — the dialog locks the
     * type in edit mode, so the payload never changes meaning under an
     * existing key.
     */
    private fun update(id: String, label: String, bytes: ByteArray) {
        viewModelScope.launch {
            try {
                val existing = repository.getById(id)
                if (existing == null) {
                    _error.value = "Tunnel no longer exists"
                    return@launch
                }
                repository.save(
                    TunnelConfig(
                        id = existing.id,
                        label = label.trim(),
                        type = existing.type,
                        configText = bytes,
                        createdAt = existing.createdAt,
                        ownerProfileId = existing.ownerProfileId,
                    ),
                )
                _message.value = "Tunnel \"${label.trim()}\" saved"
            } catch (e: Exception) {
                _error.value = "Save failed: ${e.message}"
            }
        }
    }

    private companion object {
        /** Shape check only; ocbridge validates the hash length precisely. */
        val CERT_PIN = Regex(
            """^(pin-sha256:[A-Za-z0-9+/=]+|(sha256:|sha1:)?[0-9A-Fa-f:]{40,95})$""",
            RegexOption.IGNORE_CASE,
        )
    }

    fun delete(id: String) {
        viewModelScope.launch {
            try {
                // Don't leave a VPN session behind on the gateway for a
                // tunnel that no longer exists.
                if (repository.getById(id)?.typeEnum == TunnelConfigType.ANYCONNECT) {
                    runCatching { anyConnectSessions.disconnect(id, signOut = true) }
                }
                repository.delete(id)
            } catch (e: Exception) {
                _error.value = "Delete failed: ${e.message}"
            }
        }
    }
}

/**
 * The AnyConnect add/edit form's fields, bundled because there are too
 * many to pass positionally without mixing them up. Blank optional fields
 * are omitted from the stored config.
 */
data class AnyConnectForm(
    val server: String,
    val username: String,
    val password: String,
    val authGroup: String = "",
    val otpSecret: String = "",
    val secondaryPassword: String = "",
    val serverCert: String = "",
    val stayConnected: Boolean = true,
) {
    companion object {
        fun from(blob: sh.haven.core.tunnel.AnyConnectConfigBlob?) = AnyConnectForm(
            server = blob?.server.orEmpty(),
            username = blob?.username.orEmpty(),
            password = blob?.password.orEmpty(),
            authGroup = blob?.authGroup.orEmpty(),
            otpSecret = blob?.otpSecret.orEmpty(),
            secondaryPassword = blob?.secondaryPassword.orEmpty(),
            serverCert = blob?.serverCert.orEmpty(),
            stayConnected = blob?.stayConnected ?: true,
        )
    }
}
