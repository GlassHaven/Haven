package sh.haven.core.local

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ShizukuPtyClient"

class ShizukuUnavailableException(message: String) : IllegalStateException(message)
class ShizukuPermissionDeniedException(message: String) : IllegalStateException(message)

/**
 * Manages binding Haven's [ShizukuPtyUserService] via Shizuku and creating
 * privileged PTY / pipe sessions for both direct `LOCAL` Shizuku profiles and
 * `/usr/local/bin/shizuku` (`rish`) inside PRoot (#837).
 */
@Singleton
class ShizukuPtyClient @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val bindMutex = Mutex()

    @Volatile
    private var cachedService: IShizukuPtyService? = null

    @Volatile
    private var activeDeferred: CompletableDeferred<IShizukuPtyService>? = null

    private val userServiceArgs: Shizuku.UserServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(context.packageName, ShizukuPtyUserService::class.java.name))
            .daemon(false)
            .processNameSuffix("shizuku_pty")
            .tag("haven_pty")
            .version(1)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && binder.pingBinder()) {
                val svc = IShizukuPtyService.Stub.asInterface(binder)
                cachedService = svc
                activeDeferred?.complete(svc)
                Log.d(TAG, "ShizukuPtyUserService connected")
            } else {
                activeDeferred?.completeExceptionally(
                    ShizukuUnavailableException("Shizuku returned a dead UserService binder.")
                )
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "ShizukuPtyUserService disconnected")
            cachedService = null
        }
    }

    /**
     * Validate that Shizuku is running, request/verify permission, and bind
     * [IShizukuPtyService] off the blocking path. Safe to call from any
     * coroutine context across primary (`0`) and secondary (`10+`) Android
     * user profiles.
     */
    suspend fun ensureReady(): IShizukuPtyService {
        if (!WaylandSocketHelper.isShizukuAvailable()) {
            throw ShizukuUnavailableException(WaylandSocketHelper.diagnoseUnavailable(context))
        }
        if (runCatching { Shizuku.isPreV11() }.getOrDefault(false)) {
            throw ShizukuUnavailableException("Shizuku server is outdated (pre-v11). Please update Shizuku.")
        }
        if (!WaylandSocketHelper.hasShizukuPermission()) {
            val granted = WaylandSocketHelper.awaitPermissionGrant()
            if (!granted) {
                throw ShizukuPermissionDeniedException(WaylandSocketHelper.diagnosePermissionDenied())
            }
        }

        cachedService?.takeIf { it.asBinder().pingBinder() }?.let { return it }

        return bindMutex.withLock {
            cachedService?.takeIf { it.asBinder().pingBinder() }?.let { return@withLock it }

            val deferred = CompletableDeferred<IShizukuPtyService>()
            activeDeferred = deferred
            try {
                withContext(Dispatchers.Main) {
                    Shizuku.bindUserService(userServiceArgs, serviceConnection)
                }
                withTimeout(15_000L) {
                    deferred.await()
                }
            } catch (t: Throwable) {
                activeDeferred = null
                when (t) {
                    is ShizukuUnavailableException,
                    is ShizukuPermissionDeniedException -> throw t
                    else -> throw ShizukuUnavailableException(
                        "Failed to bind Shizuku terminal service: ${t.message ?: t.javaClass.simpleName}"
                    )
                }
            } finally {
                if (activeDeferred === deferred) {
                    activeDeferred = null
                }
            }
        }
    }

    fun buildShellCommand(extraArgs: List<String> = emptyList()): Pair<String, Array<String>> {
        val sh = "/system/bin/sh"
        return if (extraArgs.isEmpty()) {
            sh to arrayOf(
                sh,
                "-c",
                "cd /data/local/tmp 2>/dev/null || cd /; exec /system/bin/sh -l",
            )
        } else {
            sh to (listOf(sh) + extraArgs).toTypedArray()
        }
    }

    fun buildShellEnv(
        locale: String = "en_US.UTF-8",
        term: String = "xterm-256color",
    ): Array<String> = arrayOf(
        "HOME=/data/local/tmp",
        "TMPDIR=/data/local/tmp",
        "SHELL=/system/bin/sh",
        "TERM=${term.ifBlank { "xterm-256color" }}",
        "LANG=$locale",
        "LC_ALL=$locale",
        "PATH=/system/bin:/system/system_ext/bin:/system/xbin:/vendor/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin",
        "RISH_APPLICATION_ID=${context.packageName}",
    )

    /**
     * Create a [LocalSession.CustomPtyBackend] for a direct `LOCAL` Shizuku
     * terminal tab. `open()` runs on `LocalSession`'s background reader thread,
     * never blocking the main Looper.
     */
    fun createSessionBackend(
        locale: String,
        extraArgs: List<String> = emptyList(),
    ): LocalSession.CustomPtyBackend = object : LocalSession.CustomPtyBackend {
        private val remoteSessionId = UUID.randomUUID().toString()
        @Volatile
        private var boundService: IShizukuPtyService? = null

        override fun open(rows: Int, cols: Int): ParcelFileDescriptor {
            val svc = runBlocking(Dispatchers.IO) { ensureReady() }
            boundService = svc
            val (cmd, args) = buildShellCommand(extraArgs)
            val env = buildShellEnv(locale = locale)
            return svc.openPtySession(remoteSessionId, cmd, args, env, rows, cols)
        }

        override fun resize(rows: Int, cols: Int) {
            val svc = boundService ?: cachedService ?: return
            runCatching { svc.resizeSession(remoteSessionId, rows, cols) }
        }

        override fun waitExit(): Int {
            val svc = boundService ?: cachedService ?: return -1
            return runCatching { svc.waitExit(remoteSessionId) }.getOrDefault(-1)
        }

        override fun close() {
            val svc = boundService ?: cachedService ?: return
            runCatching { svc.closeSession(remoteSessionId) }
        }
    }
}
