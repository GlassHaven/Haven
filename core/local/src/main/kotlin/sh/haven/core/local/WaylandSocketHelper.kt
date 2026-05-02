package sh.haven.core.local

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Creates a symlink to the Wayland socket in /data/local/tmp/ via Shizuku
 * so external clients (Termux, chroot) can connect.
 *
 * Requires Shizuku to be installed and permission granted.
 * Gracefully no-ops if Shizuku is unavailable.
 */
object WaylandSocketHelper {
    private const val TAG = "WaylandSocket"
    private const val LINK_DIR = "/data/local/tmp/haven-wayland"

    /**
     * Tracks Shizuku binder availability reactively.
     * pingBinder() is unreliable at startup because the binder connection
     * is asynchronous. We register listeners once and update this flag.
     */
    @Volatile
    private var shizukuBinderAlive = false

    @Volatile
    private var shizukuListenersRegistered = false

    fun initShizukuListeners() {
        if (shizukuListenersRegistered) return
        try {
            val clazz = Class.forName("rikka.shizuku.Shizuku")

            // Check current state first. This is usually false at app
            // startup because the binder connection is async — the
            // listeners below are the load-bearing path.
            val pingMethod = clazz.getMethod("pingBinder")
            shizukuBinderAlive = pingMethod.invoke(null) as Boolean
            Log.d(TAG, "Shizuku initial pingBinder: $shizukuBinderAlive")

            // Register for binder received events. The Sticky variant
            // fires the callback immediately on the calling thread if
            // the binder is already alive, so a successful invoke()
            // here is usually enough to update shizukuBinderAlive for
            // users who started Shizuku before launching Haven.
            registerListener(
                clazz = clazz,
                addMethodName = "addBinderReceivedListenerSticky",
                listenerIface = "rikka.shizuku.Shizuku\$OnBinderReceivedListener",
                callbackMethod = "onBinderReceived",
            ) {
                Log.d(TAG, "Shizuku binder received")
                shizukuBinderAlive = true
            }

            registerListener(
                clazz = clazz,
                addMethodName = "addBinderDeadListener",
                listenerIface = "rikka.shizuku.Shizuku\$OnBinderDeadListener",
                callbackMethod = "onBinderDead",
            ) {
                Log.d(TAG, "Shizuku binder dead")
                shizukuBinderAlive = false
            }

            shizukuListenersRegistered = true
            Log.d(TAG, "Shizuku binder listeners registered (alive=$shizukuBinderAlive)")
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku listener registration failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Build a proxy for a Shizuku listener interface and register it.
     *
     * The tricky part: Shizuku's `addBinderReceivedListenerSticky`
     * internally stores listeners in a collection, which calls
     * `equals()` / `hashCode()` on the proxy during registration.
     * Our InvocationHandler used to return `null` for everything,
     * which causes a NullPointerException when Proxy tries to unbox
     * `null` to `boolean` / `int`. That NPE was caught by the outer
     * try and logged as "listener registration failed", silently
     * leaving the binder-alive flag stuck at `false` even though
     * Shizuku was running — this is the root cause of issue #82.
     */
    private inline fun registerListener(
        clazz: Class<*>,
        addMethodName: String,
        listenerIface: String,
        callbackMethod: String,
        crossinline onInvoked: () -> Unit,
    ) {
        val iface = Class.forName(listenerIface)
        val proxy = java.lang.reflect.Proxy.newProxyInstance(
            clazz.classLoader,
            arrayOf(iface),
        ) { self, method, args ->
            when (method.name) {
                "equals" -> self === args?.getOrNull(0)
                "hashCode" -> System.identityHashCode(self)
                "toString" -> "HavenShizukuListener(${iface.simpleName})"
                callbackMethod -> {
                    onInvoked()
                    null
                }
                else -> null
            }
        }
        clazz.getMethod(addMethodName, iface).invoke(null, proxy)
    }

    // --- Logcat capture ---

    @Volatile
    private var logcatProcess: Process? = null

    val isCapturingLogcat: Boolean get() = logcatProcess != null

    /**
     * Start capturing logcat to /sdcard/Download/haven-logcat.txt.
     * Uses Shizuku for all-process logs if available, otherwise captures
     * this app's own logs (no permissions needed).
     */
    fun startLogcatCapture(): Boolean {
        if (logcatProcess != null) return true
        return try {
            if (isShizukuAvailable() && hasShizukuPermission()) {
                newShizukuProcess("logcat -c").waitFor()
                logcatProcess = newShizukuProcess(
                    "logcat -v threadtime > /sdcard/Download/haven-logcat.txt"
                )
            } else {
                // App-own logs — no special permissions needed
                val pid = android.os.Process.myPid()
                Runtime.getRuntime().exec("logcat -c").waitFor()
                logcatProcess = Runtime.getRuntime().exec(arrayOf(
                    "sh", "-c",
                    "logcat -v threadtime --pid=$pid > /sdcard/Download/haven-logcat.txt"
                ))
            }
            Log.d(TAG, "Logcat capture started")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start logcat capture", e)
            false
        }
    }

    fun stopLogcatCapture() {
        logcatProcess?.let { proc ->
            try {
                proc.destroy()
            } catch (_: Exception) {}
            logcatProcess = null
            Log.d(TAG, "Logcat capture stopped")
        }
    }

    /**
     * Install an APK using the standard Android package installer intent.
     * Works without Shizuku — shows the system install confirmation dialog.
     */
    fun installApkViaIntent(context: Context, apkFile: File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile,
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * Try to create a symlink at /data/local/tmp/haven-wayland/wayland-0
     * pointing to the app's Wayland socket. Returns true if successful.
     */
    fun tryCreateSymlink(socketPath: String): Boolean {
        if (!isShizukuAvailable()) {
            Log.d(TAG, "Shizuku not available — external socket access disabled")
            return false
        }
        if (!hasShizukuPermission()) {
            Log.d(TAG, "Shizuku permission not granted")
            return false
        }
        return try {
            val cmd = "mkdir -p $LINK_DIR && chmod 0755 $LINK_DIR && " +
                "rm -f $LINK_DIR/wayland-0 && " +
                "ln -s $socketPath $LINK_DIR/wayland-0"
            val result = runShizukuCommand(cmd)
            if (result == 0) {
                Log.i(TAG, "Symlink created: $LINK_DIR/wayland-0 → $socketPath")
                true
            } else {
                Log.w(TAG, "Symlink command failed with exit code $result")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku symlink failed: ${e.message}")
            false
        }
    }

    /** Clean up the symlink when the compositor stops. */
    fun tryRemoveSymlink() {
        if (!isShizukuAvailable() || !hasShizukuPermission()) return
        try {
            runShizukuCommand("rm -f $LINK_DIR/wayland-0")
        } catch (_: Exception) {}
    }

    /** Shizuku is installed AND its binder service is running. */
    fun isShizukuAvailable(): Boolean {
        initShizukuListeners()
        return shizukuBinderAlive
    }

    /** Shizuku APK is installed on the device (may not be running). */
    fun isShizukuInstalled(context: android.content.Context): Boolean {
        return try {
            context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun hasShizukuPermission(): Boolean {
        return try {
            val clazz = Class.forName("rikka.shizuku.Shizuku")
            val method = clazz.getMethod("checkSelfPermission")
            val result = (method.invoke(null) as Int) == android.content.pm.PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "Shizuku permission: $result")
            result
        } catch (e: Exception) {
            Log.d(TAG, "Shizuku permission check failed: ${e.message}")
            false
        }
    }

    fun requestPermission() {
        try {
            val clazz = Class.forName("rikka.shizuku.Shizuku")
            val method = clazz.getMethod("requestPermission", Int::class.java)
            method.invoke(null, 42)
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku permission request failed: ${e.message}")
        }
    }

    /**
     * Forward a local TCP port to the Android Terminal VM's guest port via Shizuku.
     * Equivalent to: adb forward tcp:<localPort> vsock:<cid>:<guestPort>
     *
     * The Terminal VM uses vsock for communication. CID 2 is the standard guest CID
     * in Android's pKVM implementation.
     *
     * @return true if the forward was created
     */
    fun tryVsockForward(localPort: Int, guestPort: Int, cid: Int = 2): Boolean {
        if (!isShizukuAvailable() || !hasShizukuPermission()) return false
        return try {
            // The Terminal app's port forwarding uses iptables + socat internally.
            // With shell access we can create a socat relay directly.
            val result = runShizukuCommand(
                "nohup socat TCP-LISTEN:$localPort,fork,reuseaddr VSOCK-CONNECT:$cid:$guestPort " +
                    "</dev/null >/dev/null 2>&1 &"
            )
            if (result == 0) {
                Log.i(TAG, "Vsock forward created: localhost:$localPort → vsock:$cid:$guestPort")
                true
            } else {
                Log.w(TAG, "Vsock forward failed with exit code $result")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vsock forward via Shizuku failed: ${e.message}")
            false
        }
    }

    /**
     * Stop a vsock port forward previously created by [tryVsockForward].
     */
    fun tryStopVsockForward(localPort: Int) {
        if (!isShizukuAvailable() || !hasShizukuPermission()) return
        try {
            runShizukuCommand("pkill -f 'socat TCP-LISTEN:$localPort'")
        } catch (_: Exception) {}
    }

    /**
     * Disable battery optimization for the given package via Shizuku.
     * Equivalent to: adb shell cmd deviceidle whitelist +<package>
     * Returns true if successful.
     */
    fun tryDisableBatteryOptimization(packageName: String): Boolean {
        if (!isShizukuAvailable() || !hasShizukuPermission()) return false
        return try {
            val result = runShizukuCommand("cmd deviceidle whitelist +$packageName")
            if (result == 0) {
                Log.i(TAG, "Battery optimization disabled for $packageName via Shizuku")
                true
            } else {
                Log.w(TAG, "Battery optimization command failed with exit code $result")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Battery optimization via Shizuku failed: ${e.message}")
            false
        }
    }

    /**
     * Download an APK from [url] and install it via Shizuku's shell.
     * Uses `pm install -S <size>` with stdin piping to avoid file permission issues.
     * Calls [onProgress] with status updates and [onResult] with (success, message).
     */
    fun installApkFromUrl(
        context: Context,
        url: String,
        onProgress: (String) -> Unit,
        onResult: (Boolean, String) -> Unit,
    ) {
        val useShizuku = isShizukuAvailable() && hasShizukuPermission()
        Thread {
            try {
                onProgress("Downloading...")
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 60000
                connection.connect()

                if (connection.responseCode != 200) {
                    onResult(false, "HTTP ${connection.responseCode}: ${connection.responseMessage}")
                    return@Thread
                }

                val tempFile = File(context.cacheDir, "dev-install.apk")
                val contentLength = connection.contentLength
                var downloaded = 0L
                connection.inputStream.use { input ->
                    tempFile.outputStream().use { output ->
                        val buf = ByteArray(8192)
                        var n: Int
                        while (input.read(buf).also { n = it } >= 0) {
                            output.write(buf, 0, n)
                            downloaded += n
                            if (contentLength > 0) {
                                val pct = (downloaded * 100 / contentLength).toInt()
                                onProgress("Downloading... $pct%")
                            }
                        }
                    }
                }

                val fileSize = tempFile.length()

                if (useShizuku) {
                    onProgress("Installing via Shizuku (${fileSize / 1024}KB)...")
                    val process = newShizukuProcess("pm install -S $fileSize")
                    tempFile.inputStream().use { input ->
                        input.copyTo(process.outputStream)
                    }
                    process.outputStream.close()

                    val exitCode = process.waitFor()
                    val stdout = process.inputStream.bufferedReader().readText().trim()
                    val stderr = process.errorStream.bufferedReader().readText().trim()
                    tempFile.delete()

                    if (exitCode == 0 && stdout.contains("Success")) {
                        onResult(true, "Installed. Restart the app.")
                    } else {
                        onResult(false, "pm install failed: $stdout $stderr".trim())
                    }
                } else {
                    // No Shizuku — use system package installer intent
                    onProgress("Opening installer...")
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        try {
                            installApkViaIntent(context, tempFile)
                            onResult(true, "System installer opened. Tap Install.")
                        } catch (e: Exception) {
                            onResult(false, "Intent failed: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Dev install failed", e)
                onResult(false, "Error: ${e.message}")
            }
        }.start()
    }

    /**
     * Result of a Shizuku-issued shell command. [exitCode] is the
     * `waitFor` value (0 success); [output] is stdout+stderr merged so
     * the caller sees errno-style messages alongside any normal output.
     * On a non-zero exit, [output] is the most useful diagnostic.
     */
    data class ShizukuExecResult(val exitCode: Int, val output: String)

    /**
     * Run [cmd] as the Shizuku-bound shell user (`sh -c "$cmd"`) and
     * collect its output. Throws [IllegalStateException] when Shizuku
     * isn't running or hasn't granted the app permission — the caller
     * should surface a clean message and let the user fix Shizuku.
     *
     * Public so MCP tools (and any other in-process caller that needs
     * to run a privileged shell command) can reuse the same plumbing
     * the Wayland helper already wired up.
     */
    fun execAsShizuku(cmd: String): ShizukuExecResult {
        if (!isShizukuAvailable()) {
            throw IllegalStateException("Shizuku is not running")
        }
        if (!hasShizukuPermission()) {
            throw IllegalStateException("Shizuku permission not granted to Haven")
        }
        val process = newShizukuProcess(cmd)
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val err = process.errorStream.bufferedReader().use { it.readText() }
        val exit = process.waitFor()
        val merged = listOf(output.trim(), err.trim()).filter { it.isNotEmpty() }.joinToString("\n")
        return ShizukuExecResult(exitCode = exit, output = merged)
    }

    /**
     * Run [cmd] as the Shizuku shell user with [stdin] piped into the
     * process's standard input. Used for `pm install -S <size>` which
     * reads APK bytes from stdin — sidesteps the cacheDir-permissions
     * problem (Shizuku's shell uid can't read app-private storage)
     * because the shell process inherits stdin from us.
     *
     * The caller's [stdin] is fully consumed and closed; stdout + stderr
     * are merged into [ShizukuExecResult.output].
     */
    fun execAsShizukuWithStdin(cmd: String, stdin: java.io.InputStream): ShizukuExecResult {
        if (!isShizukuAvailable()) {
            throw IllegalStateException("Shizuku is not running")
        }
        if (!hasShizukuPermission()) {
            throw IllegalStateException("Shizuku permission not granted to Haven")
        }
        val process = newShizukuProcess(cmd)
        // Pipe stdin on a worker thread so we can drain stdout/stderr in
        // parallel — `pm install` doesn't return until it's read every
        // byte and we've seen its result line, so blocking on either
        // side independently would deadlock.
        val stdinThread = Thread({
            try {
                stdin.use { input ->
                    process.outputStream.use { it.write(input.readBytes()) }
                }
            } catch (_: Exception) {
                // Best-effort; if the process died early we just stop feeding.
            }
        }, "shizuku-stdin").apply { isDaemon = true }
        stdinThread.start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val err = process.errorStream.bufferedReader().use { it.readText() }
        val exit = process.waitFor()
        stdinThread.join(2_000)
        val merged = listOf(output.trim(), err.trim()).filter { it.isNotEmpty() }.joinToString("\n")
        return ShizukuExecResult(exitCode = exit, output = merged)
    }

    private fun newShizukuProcess(cmd: String): Process {
        val clazz = Class.forName("rikka.shizuku.Shizuku")
        val method = clazz.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
    }

    private fun runShizukuCommand(cmd: String): Int {
        return newShizukuProcess(cmd).waitFor()
    }
}
