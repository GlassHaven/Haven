package sh.haven.app.workspace

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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import sh.haven.app.MainActivity
import sh.haven.app.R
import sh.haven.core.data.db.entities.WorkspaceProfile
import sh.haven.core.data.repository.WorkspaceRepository
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "WorkspaceShortcuts"

/**
 * Reflects the user's workspaces into Android launcher shortcuts so a
 * long-press on the Haven icon offers "Open <workspace>" entries
 * straight from the home screen.
 *
 * Started from [sh.haven.app.HavenApp.onCreate] — observes the repo
 * and rewrites the dynamic-shortcut set whenever workspaces change.
 * Capped at [MAX_SHORTCUTS] most-recently-updated workspaces because
 * Android's per-activity shortcut limit is platform-defined (typically
 * 5 visible) and naïvely emitting every workspace would silently drop
 * the overflow.
 *
 * Shortcut ids embed the workspace UUID with a stable prefix so
 * Android's pinned-shortcut machinery can re-resolve them after a
 * rename.
 */
@Singleton
class WorkspaceShortcutManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: WorkspaceRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start() {
        repository.observeAll()
            .distinctUntilChanged()
            .onEach { rebuildShortcuts(it) }
            .launchIn(scope)
    }

    private fun rebuildShortcuts(workspaces: List<WorkspaceProfile>) {
        val visible = workspaces.sortedByDescending { it.updatedAt }.take(MAX_SHORTCUTS)
        val shortcuts = visible.map { ws ->
            ShortcutInfoCompat.Builder(context, "workspace-${ws.id}")
                .setShortLabel(ws.name)
                .setLongLabel(ws.name)
                .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                .setIntent(launchIntent(ws.id))
                .build()
        }
        try {
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
            Log.d(TAG, "published ${shortcuts.size} workspace shortcut(s)")
        } catch (e: IllegalStateException) {
            // Some launchers throw when called too early in the app
            // lifetime or when the device is locked; not actionable.
            Log.w(TAG, "setDynamicShortcuts failed: ${e.message}")
        }
    }

    private fun launchIntent(workspaceId: String): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = ACTION_LAUNCH_WORKSPACE
            putExtra(EXTRA_WORKSPACE_ID, workspaceId)
            // CLEAR_TOP + SINGLE_TOP so an already-open MainActivity
            // routes through onNewIntent rather than spawning a duplicate.
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

    companion object {
        const val ACTION_LAUNCH_WORKSPACE = "sh.haven.action.LAUNCH_WORKSPACE"
        const val EXTRA_WORKSPACE_ID = "sh.haven.extra.WORKSPACE_ID"

        /**
         * Cap on visible dynamic shortcuts. Android documents 4–5
         * visible long-press shortcuts per activity on most launchers;
         * any extras are silently dropped. We pick the most-recently-
         * updated workspaces because that's the closest proxy to "what
         * the user touched last".
         */
        private const val MAX_SHORTCUTS = 5
    }
}
