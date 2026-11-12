package sh.haven.feature.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.rclone.ParsedRemote
import sh.haven.core.rclone.ProviderOption
import sh.haven.core.rclone.RcloneClient
import sh.haven.core.rclone.RcloneConfigParseResult
import sh.haven.core.rclone.RcloneConfigParser
import sh.haven.core.session.SessionManagerRegistry
import javax.inject.Inject

/**
 * Drives credential entry for NON-OAuth rclone providers (#181). OAuth
 * providers (see [sh.haven.core.rclone.RCLONE_OAUTH_PROVIDERS]) obtain their
 * token via the browser flow on connect and need no fields here; everything
 * else (Filen, s3, b2, mega, sftp, webdav, ftp) declares required config
 * options that this view-model surfaces and then writes via `config/create`.
 *
 * Kept separate from the heavyweight ConnectionsViewModel so the editor can
 * `hiltViewModel()` it cheaply (mirrors how the Cloudflare fields use
 * TunnelViewModel).
 */
@HiltViewModel
class RcloneConfigViewModel @Inject constructor(
    private val rcloneClient: RcloneClient,
    private val connectionRepository: ConnectionRepository,
    private val sessionManagerRegistry: SessionManagerRegistry,
) : ViewModel() {

    /** Status of the most recent [configure] attempt, for inline UI feedback. */
    sealed interface Status {
        data object Idle : Status
        data object Working : Status
        data object Success : Status
        data class Error(val message: String) : Status
    }

    private val _options = MutableStateFlow<List<ProviderOption>>(emptyList())

    /** Required, non-advanced credential fields for the selected provider. */
    val options: StateFlow<List<ProviderOption>> = _options.asStateFlow()

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    private var loadedProvider: String? = null

    /**
     * Load the basic config fields for [provider] (all non-advanced options).
     * Not just the `required` ones: rclone marks many essential fields optional
     * because they have defaults (e.g. FTP's user/port/pass), so a required-only
     * filter would hide them and leave the provider unconfigurable. Safe to call
     * repeatedly; only re-queries rclone when the provider changes.
     */
    fun loadOptions(provider: String) {
        if (provider == loadedProvider) return
        loadedProvider = provider
        _status.value = Status.Idle
        if (provider.isBlank()) {
            _options.value = emptyList()
            return
        }
        viewModelScope.launch {
            val opts = withContext(Dispatchers.IO) {
                runCatching {
                    rcloneClient.initialize()
                    rcloneClient.listProviders()
                        .firstOrNull { it.name == provider }
                        ?.options
                        .orEmpty()
                        .filter { !it.advanced }
                }.getOrDefault(emptyList())
            }
            // Ignore a stale result if the user changed provider mid-load.
            if (provider == loadedProvider) _options.value = opts
        }
    }

    /**
     * Create/replace the rclone remote [remoteName] of type [provider] with the
     * collected [params] (rclone obscures password fields server-side), then
     * verify it by listing the root. On success the remote is persisted in
     * rclone.conf and a normal connect just finds it.
     */
    fun configure(remoteName: String, provider: String, params: Map<String, String>) {
        _status.value = Status.Working
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    rcloneClient.initialize()
                    if (remoteName in rcloneClient.listRemotes()) {
                        rcloneClient.deleteRemote(remoteName)
                    }
                    val state = rcloneClient.createRemote(remoteName, provider, params)
                    if (state.error.isNotEmpty()) error(state.error)
                    // A non-OAuth create with all params supplied should finish
                    // (option == null). A lingering prompt means a field is
                    // still missing — surface it rather than silently half-config.
                    state.option?.let { error("rclone still needs '${it.name}'") }
                    // Verify the credentials actually work.
                    rcloneClient.listDirectory(remoteName, "")
                }
            }
            _status.value = result.fold(
                onSuccess = { Status.Success },
                onFailure = { Status.Error(it.message ?: "Configuration failed") },
            )
        }
    }

    /** Reset status (e.g. when the user edits a field after an error). */
    fun clearStatus() {
        if (_status.value != Status.Working) _status.value = Status.Idle
    }

    // ── Import from a Linux rclone.conf (#251, #839) ────────────────────────

    /** State of the "import remotes from an rclone.conf" flow (#251, #839). */
    sealed interface ImportState {
        data object Idle : ImportState

        /**
         * Parsed remotes ready to choose from:
         * - [existing] already have a visible RCLONE [ConnectionProfile] in the UI
         *   (unchecked by default, but selectable to overwrite credentials).
         * - [orphaned] exist in `rclone.conf` without a matching UI profile (#839)
         *   (pre-selected so importing repairs the missing card).
         */
        data class Loaded(
            val remotes: List<ParsedRemote>,
            val existing: Set<String>,
            val orphaned: Set<String> = emptySet(),
        ) : ImportState

        /** The pasted/picked file is a password-encrypted rclone config. */
        data object Encrypted : ImportState

        data class Failed(val message: String) : ImportState
        data object Importing : ImportState

        /** Per-remote outcome: created/updated remote names, skipped (typeless), failed→reason. */
        data class Done(
            val created: List<String>,
            val skipped: List<String>,
            val failed: Map<String, String>,
        ) : ImportState

        /** Outcome of clearing existing rclone profiles and `rclone.conf` sections (#839). */
        data class ResetDone(
            val removedProfiles: Int,
            val removedRemotes: Int,
        ) : ImportState
    }

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    /**
     * Surface a file-picking failure in the same place parse failures appear.
     *
     * #468: every failure path from the picker was silent — a cancelled pick, a
     * file that could not be read, and an empty file all did nothing at all, so
     * a reporter whose picker was being intercepted by another app saw only a
     * button that appeared dead. A dialog that cannot report failure cannot be
     * debugged from the outside.
     */
    fun reportImportFailed(message: String) {
        _importState.value = ImportState.Failed(message)
    }

    /** Parse [text] (an rclone.conf) and move to [ImportState.Loaded] for selection. */
    fun loadConfig(text: String) {
        _importState.value = ImportState.Importing
        viewModelScope.launch {
            when (val parsed = RcloneConfigParser.parse(text)) {
                is RcloneConfigParseResult.Encrypted -> _importState.value = ImportState.Encrypted
                is RcloneConfigParseResult.Success -> {
                    if (parsed.remotes.isEmpty()) {
                        _importState.value = ImportState.Failed("No remotes found in that file.")
                        return@launch
                    }
                    val (existing, orphaned) = withContext(Dispatchers.IO) {
                        val inDb = runCatching {
                            connectionRepository.getAll()
                                .filter { it.connectionType == "RCLONE" }
                                .mapNotNull { it.rcloneRemoteName?.takeIf(String::isNotBlank) }
                                .toSet()
                        }.getOrDefault(emptySet())
                        val inConfig = runCatching {
                            rcloneClient.initialize()
                            rcloneClient.listRemotes().toSet()
                        }.getOrDefault(emptySet())
                        inDb to (inConfig - inDb)
                    }
                    _importState.value = ImportState.Loaded(parsed.remotes, existing, orphaned)
                }
            }
        }
    }

    /**
     * Create or overwrite each selected remote in rclone (values copied verbatim
     * via `noObscure` — they're already obscured) and create or update the
     * matching RCLONE [ConnectionProfile] (#251, #839). Skips typeless sections.
     */
    fun importRemotes(selected: List<ParsedRemote>) {
        _importState.value = ImportState.Importing
        viewModelScope.launch {
            val created = mutableListOf<String>()
            val skipped = mutableListOf<String>()
            val failed = linkedMapOf<String, String>()
            withContext(Dispatchers.IO) {
                runCatching { rcloneClient.initialize() }
                val existingRemotes = runCatching { rcloneClient.listRemotes().toSet() }.getOrDefault(emptySet())
                val existingProfilesByRemote = runCatching {
                    connectionRepository.getAll()
                        .filter { it.connectionType == "RCLONE" && !it.rcloneRemoteName.isNullOrBlank() }
                        .groupBy { it.rcloneRemoteName!! }
                }.getOrDefault(emptyMap())

                for (remote in selected) {
                    if (remote.type.isBlank()) { skipped += remote.name; continue }
                    val hadConfig = remote.name in existingRemotes
                    val priorConfig = if (hadConfig) {
                        runCatching { rcloneClient.getRemoteConfig(remote.name) }.getOrNull()
                    } else {
                        null
                    }
                    val result = runCatching {
                        if (hadConfig) {
                            rcloneClient.deleteRemote(remote.name)
                        }
                        // nonInteractive so an OAuth remote (token already in
                        // params) doesn't block config/create on the browser
                        // flow and hang the import dialog (#269).
                        val state = rcloneClient.createRemote(
                            remote.name, remote.type, remote.params,
                            noObscure = true, nonInteractive = true,
                        )
                        if (state.error.isNotEmpty()) error(state.error)
                        val existingProfile = existingProfilesByRemote[remote.name]?.firstOrNull()
                        connectionRepository.save(
                            existingProfile?.copy(
                                rcloneProvider = remote.type,
                            ) ?: ConnectionProfile(
                                label = remote.name,
                                host = "",
                                username = "",
                                connectionType = "RCLONE",
                                rcloneRemoteName = remote.name,
                                rcloneProvider = remote.type,
                            ),
                        )
                    }
                    result.fold(
                        onSuccess = { created += remote.name },
                        onFailure = {
                            // Roll back any half-created rclone remote; if overwriting an
                            // existing remote failed, restore its previous rclone.conf entry.
                            runCatching { rcloneClient.deleteRemote(remote.name) }
                            val priorType = priorConfig?.get("type")?.takeIf { it.isNotBlank() }
                            if (priorType != null) {
                                runCatching {
                                    rcloneClient.createRemote(
                                        remote.name,
                                        priorType,
                                        priorConfig - "type",
                                        noObscure = true,
                                        nonInteractive = true,
                                    )
                                }
                            }
                            failed[remote.name] = it.message ?: "failed"
                        },
                    )
                }
            }
            _importState.value = ImportState.Done(created, skipped, failed)
        }
    }

    /**
     * Clear all saved RCLONE connection profiles and all `rclone.conf` remotes (#839)
     * so a user can start clean from a desktop `rclone.conf` without affecting SSH
     * keys, tunnels, or non-rclone connections.
     */
    fun resetRcloneStorage() {
        _importState.value = ImportState.Importing
        viewModelScope.launch {
            val (removedProfiles, removedRemotes) = withContext(Dispatchers.IO) {
                val rcloneProfiles = runCatching {
                    connectionRepository.getAll().filter { it.connectionType == "RCLONE" }
                }.getOrDefault(emptyList())
                for (profile in rcloneProfiles) {
                    runCatching { sessionManagerRegistry.disconnectProfile(profile.id) }
                    runCatching { connectionRepository.delete(profile.id) }
                }
                val remotes = runCatching {
                    rcloneClient.initialize()
                    rcloneClient.listRemotes()
                }.getOrDefault(emptyList())
                for (name in remotes) {
                    runCatching { rcloneClient.deleteRemote(name) }
                }
                rcloneProfiles.size to remotes.size
            }
            _importState.value = ImportState.ResetDone(
                removedProfiles = removedProfiles,
                removedRemotes = removedRemotes,
            )
        }
    }

    fun resetImport() {
        _importState.value = ImportState.Idle
    }
}
