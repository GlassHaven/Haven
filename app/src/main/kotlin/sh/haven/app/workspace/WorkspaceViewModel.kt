package sh.haven.app.workspace

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.haven.core.data.db.entities.WorkspaceItem
import sh.haven.core.data.db.entities.WorkspaceProfile
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.data.repository.WorkspaceRepository
import sh.haven.core.ssh.SessionManagerRegistry
import sh.haven.core.ssh.SessionStatus
import sh.haven.core.ssh.Transport
import sh.haven.core.wayland.WaylandBridge
import javax.inject.Inject

private const val TAG = "WorkspaceViewModel"

/**
 * Connections-screen-scoped ViewModel for workspace profile management.
 * Wraps [WorkspaceRepository] (CRUD) and [WorkspaceLauncher]
 * (orchestration) so the UI has a single observable surface.
 *
 * The "save current" flow snapshots state visible from app-singleton
 * sources only — [SessionManagerRegistry] for live transport sessions
 * and [WaylandBridge] for the compositor. VNC tabs and SFTP active
 * profile/path live in page-scoped ViewModels and are intentionally
 * out of scope for v1's automatic capture; users can add those items
 * manually in the save dialog (Stage 4).
 */
@HiltViewModel
class WorkspaceViewModel @Inject constructor(
    private val workspaceRepository: WorkspaceRepository,
    private val launcher: WorkspaceLauncher,
    private val sessionManagerRegistry: SessionManagerRegistry,
    private val connectionRepository: ConnectionRepository,
) : ViewModel() {

    val workspaces: StateFlow<List<WorkspaceProfile>> =
        workspaceRepository.observeAll().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList(),
        )

    val launchState: StateFlow<WorkspaceLaunchState> = launcher.state

    /**
     * Live item list for [workspaceId]. Used by the workspace card UI
     * to render kind chips with counts that follow the repo even when
     * a referenced connection profile is deleted (FK SET NULL).
     */
    fun observeItems(workspaceId: String) =
        workspaceRepository.observeItems(workspaceId)

    fun launch(workspaceId: String) {
        viewModelScope.launch { launcher.launch(workspaceId) }
    }

    fun cancel() = launcher.cancel()

    fun acknowledge() = launcher.acknowledge()

    fun delete(workspaceId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            workspaceRepository.delete(workspaceId)
        }
    }

    fun rename(workspaceId: String, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val existing = workspaceRepository.getWorkspace(workspaceId) ?: return@launch
            workspaceRepository.save(
                profile = existing.profile.copy(name = newName.trim()),
                items = existing.items,
            )
        }
    }

    /**
     * Persist [items] as a new or replacement workspace named [name].
     * The dialog typically calls this with the union of
     * [captureFromSingletons] and any manually-added items the user
     * selected.
     */
    fun save(
        name: String,
        items: List<WorkspaceItem>,
        existingId: String? = null,
    ) {
        if (name.isBlank()) return
        if (items.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val profile = if (existingId != null) {
                workspaceRepository.getWorkspace(existingId)?.profile?.copy(name = name.trim())
                    ?: WorkspaceProfile(id = existingId, name = name.trim())
            } else {
                WorkspaceProfile(name = name.trim())
            }
            workspaceRepository.save(profile, items.map { it.copy(workspaceId = profile.id) })
        }
    }

    /**
     * Snapshot the live state visible to app singletons — every
     * connected transport session and the Wayland compositor running
     * flag — into a draft list of [WorkspaceItem]s. The dialog renders
     * each as a checkable row so the user picks which to keep, then
     * calls [save] with the selected subset (plus any manual additions).
     */
    suspend fun captureFromSingletons(workspaceId: String): List<WorkspaceItem> =
        withContext(Dispatchers.IO) {
            val sessions = sessionManagerRegistry.allSessions
                .filter { it.status == SessionStatus.CONNECTED }
            val drafts = mutableListOf<WorkspaceItem>()

            for ((index, session) in sessions.withIndex()) {
                val kind = session.transport.toWorkspaceKind() ?: continue
                drafts += WorkspaceItem(
                    workspaceId = workspaceId,
                    kind = kind,
                    connectionProfileId = session.profileId,
                    sortOrder = index,
                )
            }

            if (waylandIsRunning()) {
                drafts += WorkspaceItem(
                    workspaceId = workspaceId,
                    kind = WorkspaceItem.Kind.WAYLAND,
                    connectionProfileId = null,
                    sortOrder = drafts.size,
                )
            }

            Log.d(TAG, "captureFromSingletons → ${drafts.size} draft items")
            drafts
        }

    private fun waylandIsRunning(): Boolean = try {
        WaylandBridge.nativeIsRunning()
    } catch (e: UnsatisfiedLinkError) {
        // The Wayland native lib isn't loaded under unit tests. Treat
        // as "not running" rather than failing the whole capture.
        Log.d(TAG, "Wayland native lib unavailable; treating as not running: ${e.message}")
        false
    }
}

/**
 * Map the unified [Transport] enum to its [WorkspaceItem.Kind]:
 * - SSH / Mosh / ET / Reticulum / Local → TERMINAL
 * - SMB → FILE_BROWSER
 * - RDP → DESKTOP
 *
 * VNC is not in [SessionManagerRegistry] (no session manager), so it
 * isn't represented here — automatic capture skips VNC tabs in v1.
 */
private fun Transport.toWorkspaceKind(): WorkspaceItem.Kind? = when (this) {
    Transport.SSH, Transport.MOSH, Transport.ET, Transport.RETICULUM, Transport.LOCAL ->
        WorkspaceItem.Kind.TERMINAL
    Transport.SMB -> WorkspaceItem.Kind.FILE_BROWSER
    Transport.RDP -> WorkspaceItem.Kind.DESKTOP
}
