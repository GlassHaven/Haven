package sh.haven.feature.keys

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.haven.core.data.db.entities.SshKey
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.data.repository.SshKeyRepository
import sh.haven.core.fido.SkKeyData
import sh.haven.core.fido.SkKeyParser
import sh.haven.core.security.Keystore
import sh.haven.core.security.KeystoreEntry
import sh.haven.core.security.KeystoreFlag
import sh.haven.core.security.KeystoreStore
import sh.haven.core.security.SshKeyGenerator
import sh.haven.core.ssh.SshKeyExporter
import sh.haven.core.ssh.SshKeyImporter
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class KeysViewModel @Inject constructor(
    private val repository: SshKeyRepository,
    private val connectionRepository: ConnectionRepository,
    private val keystore: Keystore,
) : ViewModel() {

    val keys: StateFlow<List<SshKey>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Per-key audit metadata indexed by [SshKey.id]. Refreshed whenever
     * the underlying key list changes (insert / delete / passphrase
     * toggle / biometric toggle). The Keys screen looks up flags +
     * KeyKind from here while still rendering the [SshKey] row's
     * key-specific actions (copy public, export private).
     */
    private val refreshTicker = MutableStateFlow(0L)

    val keyEntries: StateFlow<Map<String, KeystoreEntry>> = combine(
        repository.observeAll(),
        refreshTicker,
    ) { _, _ -> Unit }
        .flatMapLatest {
            flow {
                emit(
                    keystore.enumerate()
                        .filter { it.store == KeystoreStore.SSH_KEYS }
                        .associateBy { it.id },
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /**
     * Profile-password entries. Sourced from [Keystore.enumerate] for
     * [KeystoreStore.PROFILE_CREDENTIALS] — gives us the same audit
     * metadata as SSH keys (HARDWARE_BACKED chip, plaintext detection,
     * fingerprint-shaped id) without surfacing the actual password
     * value.
     */
    val passwordEntries: StateFlow<List<KeystoreEntry>> = combine(
        connectionRepository.observeAll(),
        refreshTicker,
    ) { _, _ -> Unit }
        .flatMapLatest {
            flow {
                emit(
                    keystore.enumerate()
                        .filter { it.store == KeystoreStore.PROFILE_CREDENTIALS },
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Toggle the BIOMETRIC_PROTECTED flag on an SSH key. Refreshes the
     * audit-metadata flow on success so the screen reflects the change.
     */
    fun setBiometricProtected(keyId: String, protected: Boolean) {
        viewModelScope.launch {
            val ok = keystore.setBiometricProtected(KeystoreStore.SSH_KEYS, keyId, protected)
            if (ok) refreshTicker.value = System.nanoTime()
        }
    }

    /** Wipe a stored profile password (clears the column without removing the profile). */
    fun wipePasswordEntry(entry: KeystoreEntry) {
        viewModelScope.launch {
            val ok = keystore.wipe(entry.store, entry.id)
            if (ok) {
                refreshTicker.value = System.nanoTime()
                _message.value = "Cleared ${entry.label}"
            } else {
                _error.value = "Could not clear ${entry.label}"
            }
        }
    }

    private val _generating = MutableStateFlow(false)
    val generating: StateFlow<Boolean> = _generating.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun dismissMessage() { _message.value = null }

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // Import flow state
    private val _importResult = MutableStateFlow<SshKeyImporter.ImportedKey?>(null)
    val importResult: StateFlow<SshKeyImporter.ImportedKey?> = _importResult.asStateFlow()

    private val _needsPassphrase = MutableStateFlow(false)
    val needsPassphrase: StateFlow<Boolean> = _needsPassphrase.asStateFlow()

    private var pendingImportBytes: ByteArray? = null

    fun generateKey(label: String, keyType: SshKeyGenerator.KeyType) {
        viewModelScope.launch {
            _generating.value = true
            _error.value = null
            try {
                val generated = withContext(Dispatchers.Default) {
                    SshKeyGenerator.generate(keyType, label)
                }
                val entity = SshKey(
                    label = label,
                    keyType = generated.type.sshName,
                    privateKeyBytes = generated.privateKeyBytes,
                    publicKeyOpenSsh = generated.publicKeyOpenSsh,
                    fingerprintSha256 = generated.fingerprintSha256,
                )
                repository.save(entity)
            } catch (e: Exception) {
                _error.value = e.message ?: "Key generation failed"
            } finally {
                _generating.value = false
            }
        }
    }

    fun importFromUri(context: android.content.Context, uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }
                if (bytes == null || bytes.isEmpty()) {
                    _error.value = "Could not read key file"
                    return@launch
                }
                startImport(bytes)
            } catch (e: Exception) {
                _error.value = "Failed to read file: ${e.message}"
            }
        }
    }

    fun startImport(fileBytes: ByteArray) {
        viewModelScope.launch {
            _generating.value = true
            _error.value = null
            try {
                val imported = withContext(Dispatchers.Default) {
                    SshKeyImporter.import(fileBytes)
                }
                _importResult.value = imported
            } catch (e: SshKeyImporter.SkKeyDetectedException) {
                handleSkKeyImport(e.fileBytes)
            } catch (_: SshKeyImporter.EncryptedKeyException) {
                pendingImportBytes = fileBytes
                _needsPassphrase.value = true
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to read key file"
            } finally {
                _generating.value = false
            }
        }
    }

    fun retryImportWithPassphrase(passphrase: String) {
        val bytes = pendingImportBytes ?: return
        _needsPassphrase.value = false
        viewModelScope.launch {
            _generating.value = true
            _error.value = null
            try {
                val imported = withContext(Dispatchers.Default) {
                    SshKeyImporter.import(bytes, passphrase)
                }
                _importResult.value = imported
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to decrypt key"
            } finally {
                _generating.value = false
            }
        }
    }

    fun saveImportedKey(label: String) {
        val imported = _importResult.value ?: return
        _importResult.value = null
        pendingImportBytes = null
        viewModelScope.launch {
            try {
                val entity = SshKey(
                    label = label,
                    keyType = imported.keyType,
                    privateKeyBytes = imported.privateKeyBytes,
                    publicKeyOpenSsh = imported.publicKeyOpenSsh,
                    fingerprintSha256 = imported.fingerprintSha256,
                    isEncrypted = imported.isEncrypted,
                )
                repository.save(entity)
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to save key"
            }
        }
    }

    private fun handleSkKeyImport(fileBytes: ByteArray) {
        viewModelScope.launch {
            try {
                val skData = SkKeyParser.parse(fileBytes)
                val entity = SshKey(
                    label = "FIDO2: ${skData.application}",
                    keyType = skData.algorithmName,
                    privateKeyBytes = SkKeyData.serialize(skData),
                    publicKeyOpenSsh = SkKeyParser.formatPublicKeyLine(skData),
                    fingerprintSha256 = SkKeyParser.fingerprintSha256(skData.publicKeyBlob),
                )
                repository.save(entity)
                _message.value = "FIDO2 security key imported"
                Log.d("KeysViewModel", "SK key imported: ${skData.algorithmName}, app=${skData.application}")
            } catch (e: Exception) {
                Log.e("KeysViewModel", "SK key import failed", e)
                _error.value = "FIDO2 key import failed: ${e.message}"
            }
        }
    }

    fun cancelImport() {
        _importResult.value = null
        _needsPassphrase.value = false
        pendingImportBytes = null
    }

    /** Key ID pending export — UI launches SAF file picker when set. */
    private val _pendingExportKeyId = MutableStateFlow<String?>(null)
    val pendingExportKeyId: StateFlow<String?> = _pendingExportKeyId.asStateFlow()

    fun requestExport(keyId: String) {
        _pendingExportKeyId.value = keyId
    }

    fun clearPendingExport() {
        _pendingExportKeyId.value = null
    }

    fun getExportFileName(keyId: String): String {
        val key = keys.value.firstOrNull { it.id == keyId } ?: return "id_key"
        val sanitized = key.label.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return "id_$sanitized"
    }

    fun exportPrivateKey(context: Context, keyId: String, destinationUri: Uri) {
        viewModelScope.launch {
            try {
                val pemBytes = withContext(Dispatchers.IO) {
                    val decrypted = repository.getDecryptedKeyBytes(keyId)
                        ?: throw IllegalStateException("Key not found")
                    val key = keys.value.firstOrNull { it.id == keyId }
                        ?: throw IllegalStateException("Key not found")
                    SshKeyExporter.toPem(decrypted, key.keyType)
                }
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(destinationUri)?.use { out ->
                        out.write(pemBytes)
                    } ?: throw IllegalStateException("Cannot open output stream")
                }
                _message.value = "Private key exported"
            } catch (e: Exception) {
                Log.e("KeysViewModel", "Export failed", e)
                _error.value = "Export failed: ${e.message}"
            }
        }
    }

    fun deleteKey(id: String) {
        viewModelScope.launch {
            repository.delete(id)
        }
    }

    /**
     * Pending key id for "attach certificate" — UI launches the SAF
     * file picker when this is set, then calls
     * [importCertificateFromUri] with the chosen Uri.
     */
    private val _pendingCertKeyId = MutableStateFlow<String?>(null)
    val pendingCertKeyId: StateFlow<String?> = _pendingCertKeyId.asStateFlow()

    fun requestAttachCertificate(keyId: String) {
        _pendingCertKeyId.value = keyId
    }

    fun clearPendingCertificate() {
        _pendingCertKeyId.value = null
    }

    /**
     * Read the certificate file the user picked and attach it to the
     * pending key. Lightweight validation: the file must start with
     * one of the OpenSSH cert key-type prefixes
     * (e.g. `ssh-ed25519-cert-v01@openssh.com`); otherwise we fail
     * loudly so the user knows they picked the wrong file.
     */
    fun importCertificateFromUri(context: Context, keyId: String, uri: Uri) {
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: throw IllegalStateException("Could not read certificate file")
                if (!looksLikeOpenSshCertificate(bytes)) {
                    _error.value = "File doesn't look like an OpenSSH certificate (id_xxx-cert.pub)"
                    return@launch
                }
                repository.setCertificateBytes(keyId, bytes)
                refreshTicker.value = System.nanoTime()
                _message.value = "Certificate attached"
            } catch (e: Exception) {
                Log.e("KeysViewModel", "Attach certificate failed", e)
                _error.value = "Attach failed: ${e.message}"
            }
        }
    }

    /**
     * Header-prefix check for an OpenSSH certificate `.pub` file.
     * Files start with the cert key type followed by a space, e.g.
     * `ssh-ed25519-cert-v01@openssh.com AAAAB3...`. Tolerates leading
     * whitespace and works on the first ~80 bytes; we don't fully parse.
     */
    private fun looksLikeOpenSshCertificate(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val head = bytes.copyOf(minOf(bytes.size, 80)).toString(Charsets.US_ASCII).trimStart()
        return CERT_KEY_TYPE_PREFIXES.any { head.startsWith("$it ") }
    }

    private companion object {
        val CERT_KEY_TYPE_PREFIXES = listOf(
            "ssh-rsa-cert-v01@openssh.com",
            "ssh-dss-cert-v01@openssh.com",
            "ecdsa-sha2-nistp256-cert-v01@openssh.com",
            "ecdsa-sha2-nistp384-cert-v01@openssh.com",
            "ecdsa-sha2-nistp521-cert-v01@openssh.com",
            "ssh-ed25519-cert-v01@openssh.com",
            "sk-ecdsa-sha2-nistp256-cert-v01@openssh.com",
            "sk-ssh-ed25519-cert-v01@openssh.com",
        )
    }

    fun removeCertificate(keyId: String) {
        viewModelScope.launch {
            repository.setCertificateBytes(keyId, null)
            refreshTicker.value = System.nanoTime()
            _message.value = "Certificate removed"
        }
    }

    fun showError(msg: String) {
        _error.value = msg
    }

    fun dismissError() {
        _error.value = null
    }
}
