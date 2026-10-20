package sh.haven.app.launcher

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import sh.haven.app.MainActivity
import sh.haven.app.R
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.db.entities.WorkspaceProfile
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.data.repository.WorkspaceRepository
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LauncherShortcuts"

/** Sealed spec for the pure selection policy — the unit-testable half of [LauncherShortcutManager]. */
sealed interface LauncherShortcut {
    val label: String
    /** Stable shortcut id; prefixes keep Android's pinned-shortcut re-resolution intact. */
    val shortcutId: String
}

/** A saved connection offered as "Connect <label>". Fires the direct-dial command (no drive-by confirm — app-issued intent). */
data class ConnectionShortcut(
    val profileId: String,
    override val label: String,
) : LauncherShortcut {
    override val shortcutId: String = "connection-$profileId"
}

/** A saved workspace offered as "Open <name>". */
data class WorkspaceShortcut(
    val workspaceId: String,
    override val label: String,
) : LauncherShortcut {
    override val shortcutId: String = "workspace-$workspaceId"
}

/**
 * Reflects saved connections (#699) and workspaces into Android launcher
 * long-press shortcuts. Started from [sh.haven.app.HavenApp.onCreate] —
 * observes both repos and rewrites the dynamic-shortcut set when either
 * changes.
 *
 * One publisher owns the whole dynamic set (replaces the previous
 * workspace-only manager) because the two writers would otherwise race
 * through `setDynamicShortcuts` and clobber each other. Connections come
 * first in the interleaved list, per #699: most-recently-connected (via
 * lastConnected, never-connected profiles after — Room's stable listing
 * order), then workspaces most-recently-updated, alternating so neither
 * category can evict the other from the launcher's ~4–5 visible slots.
 * OpenAI endpoints and EMAIL mailboxes are excluded — a launcher tap
 * dials a terminal/desktop session, and those are neither.
 *
 * Connection taps route through [ACTION_LAUNCH_CONNECTION] and the
 * existing `AgentUiCommand.ConnectProfile` dial (same command an MCP
 * `connect_profile` uses), which connects straight away — unlike the
 * `haven://connect` deep link there is no confirm sheet, matching the
 * workspace shortcut precedent (the intent is app-issued, so a web page
 * cannot fire it by tapping a link).
 */
@Singleton
class LauncherShortcutManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connectionRepository: ConnectionRepository,
    private val workspaceRepository: WorkspaceRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start() {
        combine(connectionRepository.observeAll(), workspaceRepository.observeAll(), ::Pair)
            .distinctUntilChanged()
            .onEach { (connections, workspaces) -> rebuildShortcuts(connections, workspaces) }
            .launchIn(scope)
    }

    private fun rebuildShortcuts(connections: List<ConnectionProfile>, workspaces: List<WorkspaceProfile>) {
        val shortcuts = selectShortcuts(connections, workspaces).map { spec ->
            when (spec) {
                is ConnectionShortcut -> ShortcutInfoCompat.Builder(context, spec.shortcutId)
                    .setShortLabel(spec.label)
                    .setLongLabel(spec.label)
                    .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                    .setIntent(connectionLaunchIntent(spec.profileId))
                    .build()
                is WorkspaceShortcut -> ShortcutInfoCompat.Builder(context, spec.shortcutId)
                    .setShortLabel(spec.label)
                    .setLongLabel(spec.label)
                    .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                    .setIntent(workspaceLaunchIntent(spec.workspaceId))
                    .build()
            }
        }
        try {
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
            Log.d(TAG, "published ${shortcuts.size} launcher shortcut(s)")
        } catch (e: IllegalStateException) {
            // Some launchers throw when called too early in the app
            // lifetime or when the device is locked; not actionable.
            Log.w(TAG, "setDynamicShortcuts failed: ${e.message}")
        }
    }

    private fun connectionLaunchIntent(profileId: String): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = ACTION_LAUNCH_CONNECTION
            putExtra(EXTRA_CONNECTION_ID, profileId)
            // CLEAR_TOP + SINGLE_TOP so an already-open MainActivity
            // routes through onNewIntent rather than spawning a duplicate.
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

    private fun workspaceLaunchIntent(workspaceId: String): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = ACTION_LAUNCH_WORKSPACE
            putExtra(EXTRA_WORKSPACE_ID, workspaceId)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

    companion object {
        const val ACTION_LAUNCH_CONNECTION = "sh.haven.action.LAUNCH_CONNECTION"
        const val EXTRA_CONNECTION_ID = "sh.haven.extra.CONNECTION_ID"
        const val ACTION_LAUNCH_WORKSPACE = "sh.haven.action.LAUNCH_WORKSPACE"
        const val EXTRA_WORKSPACE_ID = "sh.haven.extra.WORKSPACE_ID"

        /**
         * Cap on visible dynamic shortcuts. Android documents 4–5 visible
         * long-press shortcuts per activity on most launchers; any extras
         * are silently dropped.
         */
        private const val MAX_SHORTCUTS = 5

        /**
         * The pure selection policy: dialable connections
         * (most-recently-connected first, never-connected keeping their
         * listing order), alternating with workspaces (most-recently-
         * [WorkspaceProfile.updatedAt] first), capped at [MAX_SHORTCUTS].
         * Pure — unit-tested in LauncherShortcutManagerTest.
         */
        internal fun selectShortcuts(
            connections: List<ConnectionProfile>,
            workspaces: List<WorkspaceProfile>,
        ): List<LauncherShortcut> {
            val recentConnections = connections.filterNot { it.connectionType == "OPENAI" || it.connectionType == "EMAIL" }
                .sortedByDescending { it.lastConnected ?: 0L }
            val recentWorkspaces = workspaces.sortedByDescending { it.updatedAt }
            val merged = interleave(
                recentConnections.map { ConnectionShortcut(it.id, it.label) },
                recentWorkspaces.map { WorkspaceShortcut(it.id, it.name) },
            )
            return merged.take(MAX_SHORTCUTS)
        }

        /**
         * Alternating merge [a0, b0, a1, b1, a2]: when one list runs out
         * the other continues into the freed slots, so a single busy
         * category can't empty the other but an unused category costs
         * nothing.
         */
        internal fun interleave(a: List<LauncherShortcut>, b: List<LauncherShortcut>): List<LauncherShortcut> =
            (0 until maxOf(a.size, b.size)).flatMap { i -> listOfNotNull(a.getOrNull(i), b.getOrNull(i)) }
    }
}