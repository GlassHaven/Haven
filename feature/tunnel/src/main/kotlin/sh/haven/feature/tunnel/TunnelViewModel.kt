package sh.haven.feature.tunnel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import sh.haven.core.data.db.entities.TunnelConfig
import sh.haven.core.data.db.entities.TunnelConfigType
import sh.haven.core.data.repository.TunnelConfigRepository
import javax.inject.Inject

/**
 * Backs the "Tunnels" management screen. List/add/delete tunnel configs
 * (WireGuard for now; Tailscale wiring follows in a second pass once the
 * tsnet bridge is in place).
 */
@HiltViewModel
class TunnelViewModel @Inject constructor(
    private val repository: TunnelConfigRepository,
) : ViewModel() {

    val tunnels: StateFlow<List<TunnelConfig>> = repository.observeAll()
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
