package sh.haven.feature.keys

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.haven.core.data.db.entities.SshKey
import sh.haven.core.data.repository.SshKeyRepository
import sh.haven.core.fido.SkKeyData
import sh.haven.core.fido.SkKeyParser
import sh.haven.core.security.SshKeyGenerator
import sh.haven.core.ssh.SshCertificateParser
import sh.haven.core.ssh.SshKeyExporter
import sh.haven.core.ssh.SshKeyImporter
import javax.inject.Inject

@HiltViewModel
class KeysViewModel @Inject constructor(
    private val repository: SshKeyRepository,
) : ViewModel() {

    val keys: StateFlow<List<SshKey>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

    /** Attach a certificate file to an existing key. */
    fun attachCertificateFromUri(context: Context, keyId: String, uri: Uri) {
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }
                if (bytes == null || bytes.isEmpty()) {
                    _error.value = "Could not read certificate file"
                    return@launch
                }
                val certInfo = SshCertificateParser.parse(bytes)
                if (certInfo == null) {
                    _error.value = "Not a valid SSH certificate file"
                    return@launch
                }
                val key = repository.getById(keyId)
                if (key == null) {
                    _error.value = "Key not found"
                    return@launch
                }
                if (!SshCertificateParser.matchesKey(certInfo, key.fingerprintSha256)) {
                    _error.value = "Certificate does not match this key"
                    return@launch
                }
                if (!SshCertificateParser.isCurrentlyValid(certInfo)) {
                    _message.value = "Warning: certificate has expired"
                }
                repository.attachCertificate(keyId, certInfo.rawBlob)
                _message.value = "Certificate attached (principals: ${certInfo.validPrincipals.joinToString()})"
            } catch (e: Exception) {
                Log.e("KeysViewModel", "Certificate attach failed", e)
                _error.value = "Failed to attach certificate: ${e.message}"
            }
        }
    }

    /** Remove certificate from a key. */
    fun detachCertificate(keyId: String) {
        viewModelScope.launch {
            repository.detachCertificate(keyId)
            _message.value = "Certificate removed"
        }
    }

    /** Key ID pending certificate attachment — UI launches SAF file picker when set. */
    private val _pendingCertKeyId = MutableStateFlow<String?>(null)
    val pendingCertKeyId: StateFlow<String?> = _pendingCertKeyId.asStateFlow()

    fun requestAttachCertificate(keyId: String) {
        _pendingCertKeyId.value = keyId
    }

    fun clearPendingCert() {
        _pendingCertKeyId.value = null
    }

    fun deleteKey(id: String) {
        viewModelScope.launch {
            repository.delete(id)
        }
    }

    fun showError(msg: String) {
        _error.value = msg
    }

    fun dismissError() {
        _error.value = null
    }
}
