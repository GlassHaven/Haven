package sh.haven.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import java.io.File
import javax.inject.Inject

@HiltAndroidApp
class HavenApp : Application() {

    @Inject lateinit var mcpServer: sh.haven.app.agent.McpServer
    @Inject lateinit var preferencesRepository: sh.haven.core.data.preferences.UserPreferencesRepository
    @Inject lateinit var workspaceShortcutManager: sh.haven.app.workspace.WorkspaceShortcutManager

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Register Shizuku binder listeners early so the async callback
        // has time to fire before any UI checks isShizukuAvailable().
        sh.haven.core.local.WaylandSocketHelper.initShizukuListeners()

        // Mirror saved workspaces into Android launcher long-press
        // shortcuts so the home-screen icon offers "Open <workspace>"
        // entries. Self-observing — recomputes on every repo change.
        workspaceShortcutManager.start()

        // MCP agent endpoint is OFF by default — it exposes state that
        // local processes (or an AI agent you've pointed at it) can
        // query, so it must be an explicit opt-in. When the user toggles
        // it in Settings we react by starting or stopping the server.
        //
        // We also advertise the endpoint to the PRoot rootfs by writing
        // a ready-to-merge MCP server config JSON to
        // /root/.config/haven/mcp-servers.json, so any MCP client the
        // user has installed in PRoot can pick it up with a one-liner.
        // When the endpoint is disabled the file is removed again.
        preferencesRepository.mcpAgentEndpointEnabled
            .distinctUntilChanged()
            .onEach { enabled ->
                if (enabled) {
                    mcpServer.start()
                    advertiseEndpointToProot()
                } else {
                    mcpServer.stop()
                    removeEndpointFromProot()
                }
            }
            .launchIn(appScope)
    }

    /**
     * Path to the advertised MCP config file inside the extracted
     * Alpine rootfs. The file is visible as `/root/.config/haven/
     * mcp-servers.json` from inside PRoot.
     */
    private val prootMcpConfigFile: File
        get() = File(
            filesDir,
            "proot/rootfs/alpine/root/.config/haven/mcp-servers.json",
        )

    private fun advertiseEndpointToProot() {
        val rootfsDir = File(filesDir, "proot/rootfs/alpine")
        if (!rootfsDir.exists()) return
        val json = mcpServer.mcpServerConfigJson ?: return
        try {
            val target = prootMcpConfigFile
            target.parentFile?.mkdirs()
            target.writeText(json)
            android.util.Log.d("HavenApp", "advertised MCP endpoint to ${target.absolutePath}")
        } catch (e: Exception) {
            android.util.Log.w("HavenApp", "failed to advertise MCP endpoint to PRoot: ${e.message}")
        }
    }

    private fun removeEndpointFromProot() {
        try {
            val target = prootMcpConfigFile
            if (target.exists()) {
                target.delete()
                android.util.Log.d("HavenApp", "removed advertised MCP endpoint from PRoot")
            }
        } catch (_: Exception) {
            // Best-effort cleanup
        }
    }
}
