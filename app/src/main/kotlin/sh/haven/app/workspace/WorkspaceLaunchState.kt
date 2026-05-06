package sh.haven.app.workspace

import sh.haven.core.data.db.entities.WorkspaceItem

/**
 * Coarse-grained state of an in-flight or recently-completed workspace
 * launch. Exposed as a [kotlinx.coroutines.flow.StateFlow] so the
 * Connections screen can render a progress snackbar without polling.
 */
sealed class WorkspaceLaunchState {
    data object Idle : WorkspaceLaunchState()

    data class Launching(
        val workspaceId: String,
        val workspaceName: String,
        val items: List<ItemProgress>,
    ) : WorkspaceLaunchState()

    data class Completed(
        val workspaceId: String,
        val workspaceName: String,
        val items: List<ItemProgress>,
    ) : WorkspaceLaunchState()

    data class Cancelled(
        val workspaceId: String,
        val workspaceName: String,
        val items: List<ItemProgress>,
    ) : WorkspaceLaunchState()

    data class Failed(
        val workspaceId: String,
        val workspaceName: String,
        val reason: String,
        val items: List<ItemProgress>,
    ) : WorkspaceLaunchState()
}

/**
 * Per-item tracker. [status] advances Pending → Running → Succeeded |
 * Failed | Skipped. [message] is non-null when something noteworthy
 * happened (e.g. "profile deleted", "tunnel failed").
 */
data class ItemProgress(
    val itemId: String,
    val kind: WorkspaceItem.Kind,
    val connectionProfileId: String?,
    val status: Status,
    val message: String? = null,
) {
    enum class Status { Pending, Running, Succeeded, Failed, Skipped }
}
