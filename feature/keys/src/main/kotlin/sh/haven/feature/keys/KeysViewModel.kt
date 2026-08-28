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
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.haven.core.data.agent.AgentUiCommand
import sh.haven.core.data.agent.AgentUiCommandBus
import sh.haven.core.data.db.entities.SshKey
import sh.haven.core.data.db.entities.StepCaConfig
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.data.repository.SshKeyRepository
import sh.haven.core.data.repository.StepCaConfigRepository
import sh.haven.core.fido.SkKeyData
import sh.haven.core.fido.SkKeyParser
import sh.haven.core.security.Keystore
import sh.haven.core.security.KeystoreEntry
import sh.haven.core.security.KeystoreFlag
import sh.haven.core.security.KeystoreStore
import sh.haven.core.security.SshKeyGenerator
import sh.haven.core.ssh.SshCertificateParser
import sh.haven.core.ssh.openkeychain.OpenKeychainApi
import sh.haven.core.ssh.openkeychain.OpenKeychainClient
import sh.haven.core.ssh.openkeychain.OpenKeychainKeyData
import sh.haven.core.ssh.openkeychain.OpenKeychainProvider
import sh.haven.core.ssh.SshKeyExporter
import sh.haven.core.ssh.SshKeyImporter
import sh.haven.core.stepca.StepCaSignFlow
import javax.inject.Inject
import sh.haven.core.redact.LogRedact

/**
 * One row in the discovery picker after [KeysViewModel.discoverFromSecurityKey]
 * completes. Carries enough to render and identify each candidate, plus
 * the underlying [SkKeyData] to persist on selection.
 */
data class DiscoveredSkCredential(
    val id: String,
    val rpId: String,
    val algorithmName: String,
    val fingerprint: String,
    val data: SkKeyData,
    /**
     * The name stored on the credential — the label the key was created with.
     * Null when the authenticator reports none, in which case the import
     * dialog falls back to a generated "FIDO2: <rpId>" (#449).
     */
    val userName: String? = null,
) {
    /**
     * The label this credential imports under unless the user edits it.
     * The picker shows this as the row's leading line, so the name on
     * screen and the name saved are the same string by construction —
     * they used to be two copies of this expression that could drift.
     */
    val defaultLabel: String
        get() = userName?.takeIf { it.isNotBlank() } ?: "FIDO2: $rpId"
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class KeysViewModel @Inject constructor(
    private val repository: SshKeyRepository,
    private val connectionRepository: ConnectionRepository,
    private val keystore: Keystore,
    private val stepCaConfigRepository: StepCaConfigRepository,
    private val stepCaSignFlow: StepCaSignFlow,
    private val fidoAuthenticator: sh.haven.core.fido.FidoAuthenticator,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: Context,
    private val totpSecretRepository: sh.haven.core.data.repository.TotpSecretRepository,
    private val ageIdentityRepository: sh.haven.core.data.repository.AgeIdentityRepository,
    private val sshIdentityRepository: sh.haven.core.data.repository.SshIdentityRepository,
    private val barcodeDecoder: sh.haven.core.scan.BarcodeDecoder,
    private val preferencesRepository: sh.haven.core.data.preferences.UserPreferencesRepository,
    agentUiCommandBus: AgentUiCommandBus,
) : ViewModel() {

    init {
        // Notification deep-link → MainActivity emits RegenerateStepCaCert
        // → we react. (#133 phase 2b)
        viewModelScope.launch {
            agentUiCommandBus.commands.collect { command ->
                if (command is AgentUiCommand.RegenerateStepCaCert) {
                    regenerateViaStepCa(command.keyId)
                }
            }
        }
    }

    /** Registered step-ca CAs (#133 phase 2). The Keys "Generate via
     *  step-ca" affordance disables itself when this is empty and links
     *  the user to Settings. */
    val stepCaConfigs: StateFlow<List<StepCaConfig>> = stepCaConfigRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** age file-encryption identities (VISION §2). Only the public recipient is shown. */
    val ageIdentities: StateFlow<List<sh.haven.core.data.db.entities.AgeIdentityEntity>> =
        ageIdentityRepository.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun generateAgeIdentity(label: String) {
        viewModelScope.launch {
            try {
                ageIdentityRepository.create(label.ifBlank { "age identity" })
            } catch (e: Exception) {
                _error.value = e.message ?: "age identity generation failed"
            }
        }
    }

    fun deleteAgeIdentity(id: String) {
        viewModelScope.launch { ageIdentityRepository.delete(id) }
    }

    /** Reusable SSH identities (#360) — password left encrypted for display. */
    val sshIdentities: StateFlow<List<sh.haven.core.data.db.entities.SshIdentity>> =
        sshIdentityRepository.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Save an identity (#360). [password] is the plaintext from the editor:
     * a non-null value (including blank = clear to key-only) is stored;
     * null means "leave the stored password untouched" (edit where the user
     * didn't retype it). The repository encrypts before persisting.
     */
    fun saveSshIdentity(
        id: String?,
        name: String,
        username: String,
        password: String?,
        keyId: String?,
    ) {
        viewModelScope.launch {
            sshIdentityRepository.upsertFromEditor(
                id = id,
                name = name.trim(),
                username = username.trim(),
                newPassword = password,
                keyId = keyId,
            )
        }
    }

    fun deleteSshIdentity(id: String) {
        viewModelScope.launch { sshIdentityRepository.delete(id) }
    }

    /** How the list is sorted (#460). [KeySort.MANUAL] = the #238 order. */
    val sortMode: StateFlow<KeySort> = preferencesRepository.keysSortMode
        .map { KeySort.parse(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), KeySort.MANUAL)

    fun setSortMode(sort: KeySort) {
        viewModelScope.launch { preferencesRepository.setKeysSortMode(sort.name) }
    }

    val keys: StateFlow<List<SshKey>> = combine(
        repository.observeAll(),
        preferencesRepository.sshKeyOrder,
        preferencesRepository.keysSortMode,
    ) { list, order, sortName ->
        // Manual order first, then the sort as a view on top of it (#460) —
        // KeySort.MANUAL is the identity, so the #238 order is what shows
        // when no sort is chosen.
        sortKeys(
            sh.haven.core.data.preferences.KeyOrdering.applyOrder(list, order) { it.id },
            KeySort.parse(sortName),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Move a key one step up/down in the user-defined order (#238).
     *
     * Refuses under a non-manual sort: [keys] is then a computed view, so
     * persisting its order as the manual order would overwrite the user's
     * arrangement with the sort's — and the move itself would be invisible.
     * The UI hides the actions in that mode; this is the backstop.
     */
    fun moveKey(keyId: String, up: Boolean) {
        if (sortMode.value != KeySort.MANUAL) return
        viewModelScope.launch {
            val currentIds = keys.value.map { it.id }
            val newOrder = sh.haven.core.data.preferences.KeyOrdering.move(currentIds, keyId, up)
            if (newOrder != currentIds) preferencesRepository.setSshKeyOrder(newOrder)
        }
    }

    /**
     * Section ids the user has collapsed on this screen (#460).
     *
     * First run collapses everything except TOTP: the codes are the one thing on this screen
     * you open it *to read*, and they roll every 30 seconds, so making you scroll past five
     * sections of keys to reach them is the wrong default. Everything else is reference
     * material you go looking for deliberately.
     *
     * A null from the repository means the user has never toggled a section, which is the
     * only time this default applies — an empty set is someone who expanded everything on
     * purpose, and that has to stick.
     */
    val collapsedSections: StateFlow<Set<String>> = preferencesRepository.keysCollapsedSections
        .map { it ?: DEFAULT_COLLAPSED_SECTIONS }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DEFAULT_COLLAPSED_SECTIONS)

    fun toggleSection(id: String) {
        viewModelScope.launch {
            val current = collapsedSections.value
            preferencesRepository.setKeysCollapsedSections(
                if (id in current) current - id else current + id,
            )
        }
    }

    /**
     * Ids of keys some saved connection authenticates with — so the
     * multi-select delete confirmation can name them instead of quietly
     * breaking those profiles (#460). Reads [ConnectionProfile.authMethodSpecs],
     * which folds in the legacy `authType`/`keyId` pair for pre-#166 rows.
     */
    val keysInUse: StateFlow<Set<String>> = connectionRepository.observeAll()
        .map { profiles ->
            profiles.flatMap { profile ->
                profile.authMethodSpecs
                    .filterIsInstance<sh.haven.core.data.db.entities.ConnectionProfile.AuthMethodSpec.Key>()
                    .mapNotNull { it.keyId?.takeIf(String::isNotBlank) }
            }.toSet()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /**
     * Per-SK-key verify-required (PIN) state, for the Keys-tab toggle. Derived
     * by decrypting each non-biometric SK blob's flags (the 0x04 UV bit) when
     * the keys list changes — not on every row render, and biometric-protected
     * keys are skipped so reading the state never triggers a biometric prompt
     * (toggling such a key still works; setSkVerifyRequired decrypts on demand).
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val skVerifyRequired: StateFlow<Map<String, Boolean>> = keys
        .mapLatest { list ->
            list.filter { it.keyType.startsWith("sk-") && !it.biometricProtected }
                .associate { key ->
                    key.id to (
                        runCatching {
                            repository.getDecryptedKeyBytes(key.id)
                                ?.let { SkKeyData.deserialize(it).flags.toInt() and 0x04 != 0 }
                        }.getOrNull() ?: false
                        )
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

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

    /**
     * Generate an Ed25519 keypair locally, run the OIDC handshake against
     * [caConfigId], post the public key to step-ca, and persist the
     * resulting key+cert as a single [SshKey] row. (#133 phase 2)
     *
     * @param principalsOverride if non-empty, replaces the CA's
     *   defaultPrincipals for this one mint.
     */
    fun generateViaStepCa(
        label: String,
        caConfigId: String,
        principalsOverride: List<String> = emptyList(),
    ) {
        viewModelScope.launch {
            _generating.value = true
            _error.value = null
            try {
                val caConfig = stepCaConfigRepository.getById(caConfigId)
                    ?: run {
                        _error.value = "step-ca config not found"
                        return@launch
                    }
                val generated = withContext(Dispatchers.Default) {
                    SshKeyGenerator.generate(SshKeyGenerator.KeyType.ED25519, label)
                }
                val signResult = stepCaSignFlow.run(
                    caConfig = caConfig,
                    publicKeyOpenSsh = generated.publicKeyOpenSsh,
                    keyLabel = label,
                    principalsOverride = principalsOverride.takeIf { it.isNotEmpty() },
                )
                when (signResult) {
                    is StepCaSignFlow.Result.Failure -> {
                        _error.value = signResult.message
                        return@launch
                    }
                    is StepCaSignFlow.Result.Success -> {
                        val entity = SshKey(
                            label = label,
                            keyType = generated.type.sshName,
                            privateKeyBytes = generated.privateKeyBytes,
                            publicKeyOpenSsh = generated.publicKeyOpenSsh,
                            fingerprintSha256 = generated.fingerprintSha256,
                            certificateBytes = signResult.certBytes,
                            caConfigId = caConfig.id,
                            certIssuedAt = System.currentTimeMillis(),
                        )
                        repository.save(entity)
                    }
                }
            } catch (e: Exception) {
                Log.e("KeysViewModel", "step-ca generate failed", e)
                _error.value = e.message ?: "step-ca generation failed"
            } finally {
                _generating.value = false
            }
        }
    }

    /**
     * Re-mint the cert for an existing step-ca-minted [SshKey]. Same
     * shape as [generateViaStepCa] but updates the existing row rather
     * than inserting a new one — preserves the id, label, isEncrypted,
     * and biometricProtected flags. (#133 phase 2b)
     *
     * Triggered by:
     *  - the "Regenerate" action on a key row's overflow menu
     *  - a renewal-notification deep-link (via AgentUiCommandBus)
     */
    fun regenerateViaStepCa(keyId: String) {
        viewModelScope.launch {
            _generating.value = true
            _error.value = null
            try {
                val existing = repository.getById(keyId) ?: run {
                    _error.value = "Key no longer exists"
                    return@launch
                }
                val caConfigId = existing.caConfigId ?: run {
                    _error.value = "Key was not minted via step-ca; nothing to regenerate"
                    return@launch
                }
                val caConfig = stepCaConfigRepository.getById(caConfigId) ?: run {
                    _error.value =
                        "step-ca CA used to mint '${existing.label}' has been removed"
                    return@launch
                }
                val generated = withContext(Dispatchers.Default) {
                    SshKeyGenerator.generate(SshKeyGenerator.KeyType.ED25519, existing.label)
                }
                val signResult = stepCaSignFlow.run(
                    caConfig = caConfig,
                    publicKeyOpenSsh = generated.publicKeyOpenSsh,
                    keyLabel = existing.label,
                )
                when (signResult) {
                    is StepCaSignFlow.Result.Failure -> {
                        _error.value = signResult.message
                        return@launch
                    }
                    is StepCaSignFlow.Result.Success -> {
                        val updated = existing.copy(
                            privateKeyBytes = generated.privateKeyBytes,
                            publicKeyOpenSsh = generated.publicKeyOpenSsh,
                            fingerprintSha256 = generated.fingerprintSha256,
                            certificateBytes = signResult.certBytes,
                            certIssuedAt = System.currentTimeMillis(),
                        )
                        repository.save(updated)
                    }
                }
            } catch (e: Exception) {
                Log.e("KeysViewModel", "step-ca regenerate failed", e)
                _error.value = e.message ?: "step-ca regeneration failed"
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
                if (saveSkKey(skData) != null) {
                    _message.value = "That security key is already saved"
                    return@launch
                }
                _message.value = "FIDO2 security key imported"
                Log.d("KeysViewModel", "SK key imported: ${skData.algorithmName}, app=${skData.application}")
            } catch (e: Exception) {
                Log.e("KeysViewModel", "SK key import failed", e)
                _error.value = "FIDO2 key import failed: ${e.message}"
            }
        }
    }

    /**
     * Apps installed right now that can hold SSH keys on Haven's behalf
     * (#487) — OpenKeychain and anything else implementing the SSH
     * Authentication API. Empty when none is installed, which is what hides
     * the option rather than offering something that cannot work.
     */
    fun openKeychainProviders(): List<OpenKeychainProvider> = OpenKeychainApi.providers(appContext)

    /**
     * Add a key held by [providerPackage], via its own key chooser.
     *
     * Haven stores the public key, the provider's name for it, and enough to
     * ask that provider for a signature later. There is no private key to
     * store — that is the point of the feature.
     *
     * Silent when the user backs out of the chooser: cancelling is not an
     * error, and saying so would be noise on a deliberate action.
     */
    fun addProviderKey(providerPackage: String) {
        viewModelScope.launch {
            try {
                // Pair(added, duplicateSeen) — null/null means the user
                // backed out of the chooser, which stays silent (#487).
                val (added, duplicate) = withContext(Dispatchers.IO) {
                    val client = OpenKeychainClient(appContext, providerPackage)
                    try {
                        val selection = client.selectKey() ?: return@withContext null to false
                        val publicKey = client.fetchPublicKey(selection.keyId)
                        val fingerprint = SkKeyParser.fingerprintSha256(publicKey.blob)
                        if (findDuplicateByKeyType(repository.getAll(), fingerprint, OpenKeychainKeyData.KEY_TYPE) != null) {
                            return@withContext null to true
                        }
                        val data = OpenKeychainKeyData(
                            providerPackage = providerPackage,
                            keyId = selection.keyId,
                            algorithm = publicKey.algorithm,
                            publicKeyBlob = publicKey.blob,
                            description = selection.description,
                        )
                        repository.save(
                            SshKey(
                                label = selection.description.ifBlank { "Key in $providerPackage" },
                                keyType = OpenKeychainKeyData.KEY_TYPE,
                                privateKeyBytes = OpenKeychainKeyData.serialize(data),
                                publicKeyOpenSsh = "${publicKey.algorithm} " +
                                    java.util.Base64.getEncoder().encodeToString(publicKey.blob),
                                fingerprintSha256 = fingerprint,
                            ),
                        )
                        data to false
                    } finally {
                        client.close()
                    }
                }
                if (added != null) {
                    _message.value = "Added ${added.algorithm} key from the provider"
                    Log.d("KeysViewModel", "Provider key added: ${added.algorithm} via $providerPackage")
                } else if (duplicate) {
                    _message.value = "That provider key is already saved"
                }
            } catch (e: Exception) {
                Log.e("KeysViewModel", "Provider key import failed", e)
                _error.value = "Could not add the key: ${e.message}"
            }
        }
    }

    /**
     * Save an SK key, refusing a duplicate (#531): the primary key is a
     * fresh UUID and upsert conflicts only on id, so importing the same
     * security key twice used to mint two rows sharing one fingerprint —
     * and the verify-required toggle could then land on the row the
     * connect never signs with. Returns the existing row when the import
     * was skipped, null when saved.
     */
    private suspend fun saveSkKey(skData: SkKeyData, label: String = "FIDO2: ${skData.application}"): SshKey? {
        val fingerprint = SkKeyParser.fingerprintSha256(skData.publicKeyBlob)
        findDuplicateByKeyType(repository.getAll(), fingerprint, skData.algorithmName)?.let { return it }
        val entity = SshKey(
            label = label,
            keyType = skData.algorithmName,
            privateKeyBytes = SkKeyData.serialize(skData),
            publicKeyOpenSsh = SkKeyParser.formatPublicKeyLine(skData),
            fingerprintSha256 = fingerprint,
        )
        repository.save(entity)
        return null
    }

    /**
     * Rename an existing key (#231). Covers every key kind — generated,
     * imported, and FIDO2/SK — since the only thing that changes is the
     * display [SshKey.label]. Blank input is ignored so a key can't be
     * left nameless. The keys list is Room-backed and observed, so the
     * row re-renders with the new label without an explicit refresh.
     */
    fun renameKey(keyId: String, newLabel: String) {
        val trimmed = newLabel.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            repository.rename(keyId, trimmed)
        }
    }

    /**
     * Enable/disable a key's participation in "any saved key" auto-auth.
     * A disabled key is skipped by the offer-all bundle, the jump-host
     * heuristic, and agent forwarding; it still works when a profile pins it.
     */
    fun setKeyEnabledForAuth(keyId: String, enabled: Boolean) {
        viewModelScope.launch {
            repository.setEnabledForAuth(keyId, enabled)
        }
    }

    /**
     * Store (or clear, when [passphrase] is null) the opt-in passphrase for an
     * encrypted key (#290). A non-null passphrase is validated against the key
     * first — a wrong one sets [error] and stores nothing, so the connect flow
     * never gets a passphrase that silently fails auth. The repository encrypts
     * the value at rest. Validating fetches the decrypted key bytes, which
     * triggers the biometric prompt if the key is biometric-protected.
     */
    fun setKeyStoredPassphrase(keyId: String, passphrase: String?) {
        viewModelScope.launch {
            if (passphrase == null) {
                repository.setStoredPassphrase(keyId, null)
                return@launch
            }
            try {
                val keyBytes = repository.getDecryptedKeyBytes(keyId)
                if (keyBytes == null) {
                    _error.value = "Could not read key"
                    return@launch
                }
                // Throws IllegalArgumentException("Incorrect passphrase") on mismatch.
                withContext(Dispatchers.Default) { SshKeyImporter.import(keyBytes, passphrase) }
                repository.setStoredPassphrase(keyId, passphrase)
            } catch (e: Exception) {
                _error.value = e.message ?: "Incorrect passphrase"
            }
        }
    }

    /**
     * Flip a FIDO2/SK key's verify-required (PIN) flag in place without
     * re-registering: SK flags 0x01 (touch only) ↔ 0x05 (touch + PIN). The
     * credential was made with a PIN token so it can do UV at assertion; this
     * just sets the stored SK flag that drives getAssertion's `requireUv`.
     * Decrypts the SK blob, rewrites the flag, and saves (which re-encrypts).
     */
    fun setSkVerifyRequired(keyId: String, required: Boolean) {
        viewModelScope.launch {
            try {
                val key = repository.getById(keyId) ?: return@launch
                if (!key.keyType.startsWith("sk-")) return@launch
                val plain = repository.getDecryptedKeyBytes(keyId) ?: run {
                    _error.value = "Couldn't read the security key"
                    return@launch
                }
                val sk = SkKeyData.deserialize(plain)
                val newFlags: Byte = if (required) 0x05 else 0x01
                // #531: if other rows share this fingerprint, the toggle may
                // be landing on a duplicate the connect never uses. Warn on
                // both the flip and the already-set early return.
                val ambiguity = verifyRequiredAmbiguityWarning(
                    key,
                    repository.getAll().filter { it.id != keyId },
                )
                if (sk.flags == newFlags) {
                    _message.value = (if (required) "Key already requires a PIN" else "Key is already touch-only")
                        .plusAmbiguity(ambiguity)
                    return@launch
                }
                repository.save(key.copy(privateKeyBytes = SkKeyData.serialize(sk.copy(flags = newFlags))))
                _message.value = (if (required) "Key now requires a PIN at sign-in" else "Key is now touch-only")
                    .plusAmbiguity(ambiguity)
                Log.d("KeysViewModel", "SK verify-required set to $required for ${LogRedact.of(key.label)}")
            } catch (e: Exception) {
                Log.e("KeysViewModel", "setSkVerifyRequired failed", e)
                _error.value = "Couldn't update the key: ${e.message}"
            }
        }
    }

    // ---------- FIDO resident key discovery (#152) ----------

    /** Set true while [discoverFromSecurityKey] is running. UI hides the
     *  Add menu and shows the FIDO touch/PIN dialog while non-zero. */
    private val _discoverInProgress = MutableStateFlow(false)
    val discoverInProgress: StateFlow<Boolean> = _discoverInProgress.asStateFlow()

    /** Passthrough to FidoAuthenticator's touch-prompt state so the UI
     *  can render the same waiting/touch/PIN dialog as the connect path
     *  during a discovery enumeration. */
    val fidoTouchPrompt: StateFlow<sh.haven.core.fido.FidoTouchPrompt?> =
        fidoAuthenticator.touchPrompt

    /** Cancel an in-flight FIDO key wait/touch (the dialog's Cancel button). */
    fun cancelFido() = fidoAuthenticator.cancelPending()

    /**
     * Discovered SSH-SK credentials staged for the user to pick from.
     * Non-empty when enumeration succeeded; the UI shows a picker dialog
     * and calls [importDiscoveredCredentials] with the user's selection
     * or [dismissDiscoveryPicker] to discard.
     */
    private val _discoveredCredentials = MutableStateFlow<List<DiscoveredSkCredential>>(emptyList())
    val discoveredCredentials: StateFlow<List<DiscoveredSkCredential>> = _discoveredCredentials.asStateFlow()

    /**
     * Enumerate resident SSH-SK credentials off a connected security key
     * via CTAP 2.1 `authenticatorCredentialManagement` (issue #152). Drives
     * the existing FIDO touch/PIN flow ([FidoAuthenticator.touchPrompt]);
     * once the enumeration completes, populates [discoveredCredentials]
     * for the picker.
     */
    fun discoverFromSecurityKey() {
        if (_discoverInProgress.value) return
        viewModelScope.launch {
            _discoverInProgress.value = true
            try {
                val results = fidoAuthenticator.enumerateResidentCredentials(rpIdPrefix = "ssh")
                if (results.isEmpty()) {
                    _message.value = "No SSH resident keys found on this security key"
                } else {
                    _discoveredCredentials.value = results.mapIndexed { i, cred ->
                        val rpId = cred.rpId
                        val sk = cred.key
                        DiscoveredSkCredential(
                            id = "$i",
                            rpId = rpId,
                            algorithmName = sk.algorithmName,
                            fingerprint = SkKeyParser.fingerprintSha256(sk.publicKeyBlob),
                            data = sk,
                            userName = cred.userName,
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("KeysViewModel", "discoverFromSecurityKey failed", e)
                _error.value = sh.haven.core.fido.fidoFailureMessage(e)
                    ?: "FIDO discovery failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                _discoverInProgress.value = false
            }
        }
    }

    /**
     * Save the selected discovered credentials, each under the label the
     * user chose in the picker (#231). [labels] maps the
     * [DiscoveredSkCredential.id] of every credential to import to its
     * label; credentials absent from the map are dropped. A blank label
     * falls back to [DiscoveredSkCredential.defaultLabel] — the same
     * string the picker showed for that row, so clearing the field
     * restores what was there rather than dropping to a generated name.
     */
    fun importDiscoveredCredentials(labels: Map<String, String>) {
        val selected = _discoveredCredentials.value.filter { it.id in labels.keys }
        _discoveredCredentials.value = emptyList()
        if (selected.isEmpty()) return
        viewModelScope.launch {
            var ok = 0
            var skipped = 0
            for (cred in selected) {
                try {
                    val label = labels[cred.id]?.trim()?.ifBlank { null }
                        ?: cred.defaultLabel
                    if (saveSkKey(cred.data, label) != null) skipped++ else ok++
                } catch (e: Exception) {
                    Log.e("KeysViewModel", "Save of discovered ${cred.rpId} failed", e)
                }
            }
            _message.value = when {
                ok == selected.size -> "Imported $ok resident SSH key${if (ok == 1) "" else "s"}"
                skipped > 0 && ok == 0 -> "Already saved: $skipped key${if (skipped == 1) "" else "s"}"
                skipped > 0 -> "Imported $ok, already saved $skipped, failed ${selected.size - ok - skipped}"
                else -> "Imported $ok of ${selected.size} keys; check log for failures"
            }
        }
    }

    fun dismissDiscoveryPicker() {
        _discoveredCredentials.value = emptyList()
    }

    // ---------- FIDO on-device registration (CTAP2 MakeCredential) ----------

    /** True while [registerOnSecurityKey] is running; UI shows the FIDO
     *  touch/PIN dialog ([fidoTouchPrompt]) while set. */
    private val _registerInProgress = MutableStateFlow(false)
    val registerInProgress: StateFlow<Boolean> = _registerInProgress.asStateFlow()

    /**
     * Register (create) a NEW resident SSH-SK credential directly on a
     * connected security key via CTAP2 MakeCredential, then save it as an
     * SSH key under [label]. [pin] is collected up front (in the dialog) and
     * used to set the key's PIN if it has none, or to unlock it if it already
     * has one — so the whole CTAP exchange runs as one continuous tap (USB or
     * NFC) with no mid-exchange prompt. When [verifyRequired], the key requires
     * the PIN at every SSH sign (stored UV flag). Lets a user enrol fresh
     * YubiKeys on the phone with no laptop or `ssh-keygen`. Re-runnable across
     * several keys (swap and register again).
     */
    fun registerOnSecurityKey(label: String, verifyRequired: Boolean, pin: String) {
        if (_registerInProgress.value || _discoverInProgress.value) return
        viewModelScope.launch {
            _registerInProgress.value = true
            try {
                val trimmed = label.trim().ifBlank { "FIDO2 key" }
                val sk = fidoAuthenticator.makeCredential(
                    application = "ssh:",
                    userName = trimmed,
                    verifyRequired = verifyRequired,
                    pin = pin,
                    keyLabel = trimmed,
                )
                if (saveSkKey(sk, trimmed) != null) {
                    _message.value = "A key with this fingerprint is already saved"
                } else {
                    _message.value = "Registered \"$trimmed\" on the security key"
                }
                Log.d("KeysViewModel", "Registered SK key: ${sk.algorithmName}, verifyRequired=$verifyRequired")
            } catch (e: Exception) {
                Log.e("KeysViewModel", "registerOnSecurityKey failed", e)
                _error.value = "Security key registration failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                _registerInProgress.value = false
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

    /** Key ID pending certificate export — UI launches SAF when set. (#185) */
    private val _pendingCertExportKeyId = MutableStateFlow<String?>(null)
    val pendingCertExportKeyId: StateFlow<String?> = _pendingCertExportKeyId.asStateFlow()

    fun requestCertExport(keyId: String) {
        _pendingCertExportKeyId.value = keyId
    }

    fun clearPendingCertExport() {
        _pendingCertExportKeyId.value = null
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

    /** Suggested filename for an exported certificate: `id_<label>-cert.pub`. */
    fun getCertExportFileName(keyId: String): String = "${getExportFileName(keyId)}-cert.pub"

    /**
     * Write the attached OpenSSH certificate to [destinationUri] so the
     * user can inspect it (`ssh-keygen -L -f …`) or copy it to a server.
     * Certificates are public material — no biometric gate, no decryption.
     * (#185 — the reporter had no way to verify the cert Haven holds.)
     */
    fun exportCertificate(context: Context, keyId: String, destinationUri: Uri) {
        viewModelScope.launch {
            try {
                val certBytes = withContext(Dispatchers.IO) { repository.getCertificateBytes(keyId) }
                if (certBytes == null) {
                    _error.value = "This key has no certificate attached"
                    return@launch
                }
                // We persist the raw binary cert blob, but a usable
                // `*-cert.pub` file is the OpenSSH text line ("<type> <base64>")
                // — render that so the export round-trips through
                // `ssh-keygen -L -f` and copies onto a server. (#185)
                val line = sh.haven.core.ssh.SshCertificateParser.toOpenSshPublicKeyLine(certBytes)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(destinationUri)?.use { out ->
                        out.write(line)
                    } ?: throw IllegalStateException("Cannot open output stream")
                }
                _message.value = "Certificate exported"
            } catch (e: Exception) {
                Log.e("KeysViewModel", "Certificate export failed", e)
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
     * Delete a multi-select selection (#460). Sequential, in one coroutine —
     * Room emits per delete, so the list shrinks a row at a time rather than
     * all at once.
     */
    fun deleteKeys(ids: Set<String>) {
        viewModelScope.launch {
            ids.forEach { repository.delete(it) }
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
     * pending key. Validation happens at attach time via [SshCertificateParser]:
     *   - parser rejects anything that isn't a valid `-cert.pub` blob
     *   - the cert's embedded public key fingerprint must match the
     *     target key's [SshKey.fingerprintSha256] — catches the "picked
     *     the wrong cert for this key" mistake here rather than at
     *     connect time, where the failure surface is opaque
     *   - expired certs still attach but with a warning (the user may
     *     be prepping for a renewal that hasn't run yet)
     * On success, the principals list is surfaced so the user can
     * confirm the cert lets them log in as the expected role.
     */
    fun importCertificateFromUri(context: Context, keyId: String, uri: Uri) {
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: throw IllegalStateException("Could not read certificate file")
                val certInfo = SshCertificateParser.parse(bytes)
                if (certInfo == null) {
                    _error.value = "File doesn't look like an OpenSSH certificate (id_xxx-cert.pub)"
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
                repository.setCertificateBytes(keyId, certInfo.rawBlob)
                refreshTicker.value = System.nanoTime()
                _message.value = if (!SshCertificateParser.isCurrentlyValid(certInfo)) {
                    "Certificate attached (warning: outside its validity window)"
                } else if (certInfo.validPrincipals.isNotEmpty()) {
                    "Certificate attached (principals: ${certInfo.validPrincipals.joinToString()})"
                } else {
                    "Certificate attached"
                }
            } catch (e: Exception) {
                Log.e("KeysViewModel", "Attach certificate failed", e)
                _error.value = "Attach failed: ${e.message}"
            }
        }
    }

    fun removeCertificate(keyId: String) {
        viewModelScope.launch {
            repository.setCertificateBytes(keyId, null)
            refreshTicker.value = System.nanoTime()
            _message.value = "Certificate removed"
        }
    }

    // ---------- OATH-TOTP secrets (#178) ----------

    val totpSecrets: StateFlow<List<sh.haven.core.data.db.entities.TotpSecret>> =
        totpSecretRepository.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Add a TOTP secret from a pasted `otpauth://` URI or bare base32 string. */
    fun addTotpFromText(text: String) {
        val parsed = sh.haven.core.security.OtpAuthUri.parse(text)
        if (parsed == null) {
            _error.value = "Not a valid otpauth:// URI or base32 secret"
            return
        }
        saveTotp(parsed)
    }

    /** Decode an `otpauth://` QR from a picked image, then store the secret. */
    fun addTotpFromImage(context: Context, uri: Uri) {
        viewModelScope.launch {
            try {
                val decoded = withContext(Dispatchers.Default) {
                    val bitmap = context.contentResolver.openInputStream(uri)?.use {
                        android.graphics.BitmapFactory.decodeStream(it)
                    } ?: throw IllegalStateException("Could not read image")
                    barcodeDecoder.decode(bitmap)
                }
                if (decoded == null) {
                    _error.value = "No QR code found in the image"
                    return@launch
                }
                val parsed = sh.haven.core.security.OtpAuthUri.parse(decoded)
                if (parsed == null) {
                    _error.value = "QR code is not a TOTP (otpauth://totp) code"
                    return@launch
                }
                saveTotp(parsed)
            } catch (e: Exception) {
                Log.e("KeysViewModel", "TOTP QR scan failed", e)
                _error.value = "Could not scan QR: ${e.message}"
            }
        }
    }

    private fun saveTotp(parsed: sh.haven.core.security.OtpAuthUri.Parsed) {
        viewModelScope.launch {
            try {
                totpSecretRepository.save(
                    sh.haven.core.data.db.entities.TotpSecret(
                        label = parsed.label,
                        secret = parsed.secret,
                        issuer = parsed.issuer,
                        accountName = parsed.accountName,
                        algorithm = parsed.algorithm.name,
                        digits = parsed.digits,
                        periodSeconds = parsed.periodSeconds,
                    ),
                )
                _message.value = "Authenticator added: ${parsed.label}"
            } catch (e: Exception) {
                _error.value = "Could not save authenticator: ${e.message}"
            }
        }
    }

    fun deleteTotp(id: String) {
        viewModelScope.launch { totpSecretRepository.delete(id) }
    }

    /**
     * Rename an authenticator entry. Enrolling the same client on several routers produces
     * identical labels from the issuer alone, and an entry you cannot tell apart from its
     * neighbour is one you have to identify by trying its code somewhere — so being able to
     * fix the label after the fact matters more here than for keys.
     *
     * A blank label is ignored rather than accepted: an unnamed row is worse than the
     * duplicate it was meant to fix.
     */
    fun renameTotp(id: String, newLabel: String) {
        val trimmed = newLabel.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { totpSecretRepository.rename(id, trimmed) }
    }

    fun showError(msg: String) {
        _error.value = msg
    }

    fun dismissError() {
        _error.value = null
    }
}
