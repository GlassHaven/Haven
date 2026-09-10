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

    // Repo CRUD runs on the default viewModelScope dispatcher: the only
    // blocking work is the Room suspend DAOs, which already hop to Room's
    // own executor off the calling thread. Pinning these to Dispatchers.IO
    // offloaded nothing and made them untestable (a real thread pool the
    // test scheduler can't advance — see WorkspaceViewModelTest flakiness).
    fun delete(workspaceId: String) {
        viewModelScope.launch {
            workspaceRepository.delete(workspaceId)
        }
    }

    fun rename(workspaceId: String, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch {
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
        viewModelScope.launch {
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
     * flag — into a draft list. The dialog renders each as a checkable
     * row (see [CapturedDraft.host]/[CapturedDraft.sessionType] for its
     * "&lt;host&gt; tmux &lt;name&gt;" label), so the user picks which to
     * keep, then calls [save] with the selected subset.
     */
    suspend fun captureFromSingletons(workspaceId: String): List<CapturedDraft> =
        withContext(Dispatchers.IO) {
            val sessions = sessionManagerRegistry.allSessions
                .filter { it.status == SessionStatus.CONNECTED }
            val drafts = mutableListOf<CapturedDraft>()

            for ((index, session) in sessions.withIndex()) {
                val kind = session.transport.toWorkspaceKind() ?: continue
                val isTerminal = kind == WorkspaceItem.Kind.TERMINAL
                val host = connectionRepository.getById(session.profileId)?.host
                    ?.takeIf { it.isNotBlank() }
                    ?: session.label
                drafts += CapturedDraft(
                    item = WorkspaceItem(
                        workspaceId = workspaceId,
                        kind = kind,
                        connectionProfileId = session.profileId,
                        // Remember the tmux/zellij session (terminals only) so restore
                        // reattaches to it by name rather than showing the picker.
                        sessionName = if (isTerminal) session.sessionName else null,
                        sortOrder = index,
                    ),
                    host = host,
                    sessionType = if (isTerminal) session.sessionManagerLabel else null,
                )
            }

            if (waylandIsRunning()) {
                drafts += CapturedDraft(
                    item = WorkspaceItem(
                        workspaceId = workspaceId,
                        kind = WorkspaceItem.Kind.WAYLAND,
                        connectionProfileId = null,
                        sortOrder = drafts.size,
                    ),
                    host = null,
                    sessionType = null,
                )
            }

            Log.d(TAG, "captureFromSingletons → ${drafts.size} draft items")
            drafts
        }

    private fun waylandIsRunning(): Boolean = try {
        WaylandBridge.isCompositorRunning()
    } catch (e: UnsatisfiedLinkError) {
        // The Wayland native lib isn't loaded under unit tests. Treat
        // as "not running" rather than failing the whole capture.
        Log.d(TAG, "Wayland native lib unavailable; treating as not running: ${e.message}")
        false
    }
}

/**
 * A capture-time draft: the [WorkspaceItem] to persist, plus the display
 * bits the save dialog needs but the item doesn't store — the resolved
 * [host] and the terminal's session-manager type ([sessionType], e.g.
 * "tmux"). Together with [WorkspaceItem.sessionName] they render as
 * "<host> tmux <name>".
 */
data class CapturedDraft(
    val item: WorkspaceItem,
    val host: String?,
    val sessionType: String?,
)

/**
 * Map the unified [Transport] enum to its [WorkspaceItem.Kind]:
 * - SSH / Mosh / ET / Reticulum / Local → TERMINAL
 * - SMB → FILE_BROWSER
 * - RDP → DESKTOP
 *
 * VNC is not in [SessionManagerRegistry] (no session manager), so it
 * isn't represented here — automatic capture skips VNC tabs in v1.
 * MAIL has no workspace Kind yet, so it's likewise skipped (null).
 * RCLONE never reaches here: it contributes no sessions to the registry
 * (storage handles, not live sessions — #363), so nothing carries that
 * transport into a workspace item.
 */
private fun Transport.toWorkspaceKind(): WorkspaceItem.Kind? = when (this) {
    Transport.SSH, Transport.MOSH, Transport.ET, Transport.RETICULUM, Transport.LOCAL, Transport.GUEST,
    Transport.BTSERIAL, Transport.BLESERIAL, Transport.USBSERIAL ->
        WorkspaceItem.Kind.TERMINAL
    Transport.SMB -> WorkspaceItem.Kind.FILE_BROWSER
    Transport.RDP -> WorkspaceItem.Kind.DESKTOP
    Transport.MAIL, Transport.RCLONE -> null
}
