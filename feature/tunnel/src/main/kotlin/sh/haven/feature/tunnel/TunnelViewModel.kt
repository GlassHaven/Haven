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
        viewModelScope.launch {
            try {
                repository.save(
                    TunnelConfig(
                        label = label.trim(),
                        type = TunnelConfigType.WIREGUARD.name,
                        configText = configText.toByteArray(),
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
