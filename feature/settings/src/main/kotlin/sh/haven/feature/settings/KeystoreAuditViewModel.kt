package sh.haven.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import sh.haven.core.security.Keystore
import sh.haven.core.security.KeystoreAuditSnapshot
import sh.haven.core.security.KeystoreEntry
import javax.inject.Inject

/**
 * Backs [KeystoreAuditScreen]. Wraps the unified [Keystore]
 * (#129 stage 1) plus its export-audit verb (#129 stage 2) — the
 * screen surfaces every key-material entry across SSH keys (regular +
 * FIDO SK) and per-profile passwords, lets the user audit at a glance
 * what's stored, and offers a per-entry wipe action.
 *
 * No plaintext secrets cross this layer; the [KeystoreEntry] is
 * metadata-only by contract.
 */
@HiltViewModel
class KeystoreAuditViewModel @Inject constructor(
    private val keystore: Keystore,
) : ViewModel() {

    private val _snapshot = MutableStateFlow<KeystoreAuditSnapshot?>(null)
    val snapshot: StateFlow<KeystoreAuditSnapshot?> = _snapshot.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    init {
        refresh()
    }

    /** Re-capture the snapshot. Called on screen open and after every wipe. */
    fun refresh() {
        viewModelScope.launch {
            _busy.value = true
            try {
                _snapshot.value = keystore.exportAudit()
            } finally {
                _busy.value = false
            }
        }
    }

    /**
     * Wipe a single entry, then refresh the snapshot so the screen
     * reflects the change. The user-facing confirm dialog lives in the
     * Composable; by the time we land here the user has already
     * agreed.
     */
    fun wipe(entry: KeystoreEntry, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            try {
                val ok = keystore.wipe(entry.store, entry.id)
                onResult(ok)
                if (ok) _snapshot.value = keystore.exportAudit()
            } finally {
                _busy.value = false
            }
        }
    }

    /**
     * Flip the BIOMETRIC_PROTECTED flag on [entry]. Only SSH-key
     * entries support this today (UnifiedKeystore returns false for
     * other stores); the screen hides the toggle for those, but if a
     * caller invokes this on an unsupported entry the no-op result
     * propagates back through `onResult`.
     */
    fun setBiometricProtected(
        entry: KeystoreEntry,
        protected: Boolean,
        onResult: (Boolean) -> Unit,
    ) {
        viewModelScope.launch {
            _busy.value = true
            try {
                val ok = keystore.setBiometricProtected(entry.store, entry.id, protected)
                onResult(ok)
                if (ok) _snapshot.value = keystore.exportAudit()
            } finally {
                _busy.value = false
            }
        }
    }
}
