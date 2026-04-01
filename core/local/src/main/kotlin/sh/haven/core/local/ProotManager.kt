package sh.haven.core.local

import android.content.Context
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ProotManager"

/**
 * Manages the PRoot binary and Alpine Linux rootfs.
 *
 * PRoot is bundled as libproot.so in jniLibs (extracted to nativeLibraryDir
 * by Android, executable on Android 14+).
 *
 * The Alpine rootfs is downloaded on first use (~3MB compressed) and
 * extracted to filesDir/proot/rootfs/alpine/.
 */
@Singleton
class ProotManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    sealed class SetupState {
        data object NotInstalled : SetupState()
        data class Downloading(val progress: Int) : SetupState()
        data object Extracting : SetupState()
        data object Ready : SetupState()
        data class Error(val message: String) : SetupState()
    }

    private val _state = MutableStateFlow<SetupState>(SetupState.NotInstalled)
    val state: StateFlow<SetupState> = _state.asStateFlow()

    private val rootfsDir: File
        get() = File(context.filesDir, "proot/rootfs/alpine")

    val isRootfsInstalled: Boolean
        get() = java.nio.file.Files.exists(
            File(rootfsDir, "bin/sh").toPath(),
            java.nio.file.LinkOption.NOFOLLOW_LINKS,
        )

    val prootBinary: String?
        get() {
            val nativeDir = context.applicationInfo.nativeLibraryDir
            val proot = File(nativeDir, "libproot.so")
            return if (proot.canExecute()) proot.absolutePath else null
        }

    val isReady: Boolean
        get() = prootBinary != null && isRootfsInstalled

    val isDesktopInstalled: Boolean
        get() {
            val de = installedDesktop ?: return false
            return File(rootfsDir, de.verifyBinary).exists()
        }

    enum class DesktopEnvironment(
        val label: String,
        val packages: String,
        val verifyBinary: String,
        val startCommands: String,
        val sizeEstimate: String,
        val isWayland: Boolean = false,
        val isNative: Boolean = false,
    ) {
        XFCE4(
            label = "Xfce4",
            packages = "tigervnc xfce4 xfce4-terminal dbus-x11 font-noto",
            verifyBinary = "usr/bin/startxfce4",
            startCommands = "xfwm4 & xfce4-panel & xfdesktop &",
            sizeEstimate = "~100MB",
        ),
        OPENBOX(
            label = "Openbox",
            packages = "tigervnc openbox xterm xsetroot font-noto",
            verifyBinary = "usr/bin/openbox",
            startCommands = "xsetroot -solid '#2e3440'; openbox & xterm &",
            sizeEstimate = "~10MB",
        ),
        LABWC(
            label = "Labwc (Wayland)",
            packages = "labwc wayvnc foot font-noto",
            verifyBinary = "usr/bin/labwc",
            startCommands = "export XDG_RUNTIME_DIR=/tmp/xdg-runtime; " +
                "mkdir -p \$XDG_RUNTIME_DIR; " +
                "rm -f \$XDG_RUNTIME_DIR/wayland-0 \$XDG_RUNTIME_DIR/wayland-0.lock; " +
                "export WLR_BACKENDS=headless; " +
                "export WLR_RENDERER=pixman; " +
                "export WLR_LIBINPUT_NO_DEVICES=1; " +
                "export WLR_SHM_DIR=/tmp; " +
                "export LIBSEAT_BACKEND=noop; " +
                "labwc 2>&1 & " +
                "i=0; while [ ! -e \$XDG_RUNTIME_DIR/wayland-0 ] && [ \$i -lt 10 ]; do sleep 1; i=\$((i+1)); done; " +
                "export WAYLAND_DISPLAY=wayland-0; " +
                "wayvnc 0.0.0.0 5901 2>&1 & " +
                "foot 2>&1 &",
            sizeEstimate = "~15MB",
            isWayland = true,
        ),
        WAYLAND_NATIVE(
            label = "Native Wayland",
            packages = "foot font-noto xkeyboard-config xvfb mesa-dri-gallium mesa-gbm mesa-gl mesa-demos",
            verifyBinary = "usr/bin/foot",
            startCommands = "", // compositor runs natively via WaylandBridge, not in PRoot
            sizeEstimate = "~5MB",
            isWayland = true,
            isNative = true,
        ),
    }

    /** Which DE was last installed (persisted as a file in the rootfs). */
    val installedDesktop: DesktopEnvironment?
        get() {
            val marker = File(rootfsDir, "root/.haven-desktop")
            if (!marker.exists()) return null
            return try {
                // Marker format: "DE_NAME\npackage list" — match both name and packages
                val lines = marker.readText().trim().lines()
                val de = DesktopEnvironment.valueOf(lines.first())
                if (lines.size >= 2 && lines[1] == de.packages) de else null
            } catch (_: Exception) { null }
        }

    sealed class DesktopSetupState {
        data object Idle : DesktopSetupState()
        data class Installing(val step: String) : DesktopSetupState()
        data object Complete : DesktopSetupState()
        data class Error(val message: String) : DesktopSetupState()
    }

    private val _desktopState = MutableStateFlow<DesktopSetupState>(DesktopSetupState.Idle)
    val desktopState: StateFlow<DesktopSetupState> = _desktopState.asStateFlow()

    init {
        _state.value = if (isReady) SetupState.Ready else SetupState.NotInstalled
    }

    /**
     * Download and extract the Alpine Linux rootfs.
     * Safe to call if already installed — returns immediately.
     */
    suspend fun installRootfs() {
        if (isRootfsInstalled) {
            _state.value = SetupState.Ready
            return
        }

        try {
            _state.value = SetupState.Downloading(0)

            val arch = when {
                Build.SUPPORTED_ABIS.contains("arm64-v8a") -> "aarch64"
                Build.SUPPORTED_ABIS.contains("x86_64") -> "x86_64"
                else -> throw IllegalStateException("Unsupported ABI: ${Build.SUPPORTED_ABIS.toList()}")
            }

            val expectedSha256 = when (arch) {
                "aarch64" -> "ead8a4b37867bd19e7417dd078748e2312c0aea364403d96758d63ea8ff261ea"
                "x86_64" -> "1a694899e406ce55d32334c47ac0b2efb6c06d7e878102d1840892ad44cd5239"
                else -> throw IllegalStateException("No checksum for arch: $arch")
            }

            val url = "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/$arch/alpine-minirootfs-3.21.3-$arch.tar.gz"
            val tarball = File(context.cacheDir, "alpine-minirootfs.tar.gz")

            // Download
            withContext(Dispatchers.IO) {
                Log.d(TAG, "Downloading rootfs: $url")
                val conn = URL(url).openConnection()
                val totalSize = conn.contentLength
                BufferedInputStream(conn.getInputStream()).use { input ->
                    FileOutputStream(tarball).use { output ->
                        val buf = ByteArray(8192)
                        var downloaded = 0L
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) {
                            output.write(buf, 0, n)
                            downloaded += n
                            if (totalSize > 0) {
                                _state.value = SetupState.Downloading(
                                    (downloaded * 100 / totalSize).toInt()
                                )
                            }
                        }
                    }
                }
                Log.d(TAG, "Download complete: ${tarball.length()} bytes")
            }

            // Verify SHA-256 checksum
            withContext(Dispatchers.IO) {
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                tarball.inputStream().buffered().use { input ->
                    val buf = ByteArray(8192)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        digest.update(buf, 0, n)
                    }
                }
                val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
                if (actualSha256 != expectedSha256) {
                    tarball.delete()
                    throw SecurityException(
                        "Rootfs checksum mismatch — expected $expectedSha256 but got $actualSha256. " +
                            "Download deleted. This may indicate a corrupted download or tampered file."
                    )
                }
                Log.d(TAG, "SHA-256 verified: $actualSha256")
            }

            // Extract
            _state.value = SetupState.Extracting
            withContext(Dispatchers.IO) {
                extractTarGz(tarball, rootfsDir)
                tarball.delete()
                Log.d(TAG, "Rootfs extracted to ${rootfsDir.absolutePath}")

                // Android doesn't have /etc/resolv.conf — write one with public DNS
                val resolvConf = File(rootfsDir, "etc/resolv.conf")
                resolvConf.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
                Log.d(TAG, "Wrote resolv.conf")
            }

            _state.value = SetupState.Ready
        } catch (e: Exception) {
            Log.e(TAG, "Rootfs install failed", e)
            _state.value = SetupState.Error(e.message ?: "Installation failed")
        }
    }

    /**
     * Extract a .tar.gz file to a directory using Java streams.
     * Implements minimal POSIX tar parsing (512-byte headers, ustar format).
     */
    private fun extractTarGz(tarball: File, destDir: File) {
        destDir.mkdirs()
        var fileCount = 0
        var symlinkCount = 0

        java.util.zip.GZIPInputStream(tarball.inputStream().buffered()).use { gzIn ->
            val header = ByteArray(512)
            var pendingLongName: String? = null

            while (true) {
                val headerRead = readFully(gzIn, header)
                if (headerRead < 512) break
                if (header.all { it == 0.toByte() }) break

                val name = extractString(header, 0, 100)
                if (name.isEmpty() && pendingLongName == null) break

                val modeStr = extractString(header, 100, 8)
                val sizeStr = extractString(header, 124, 12)
                val typeFlag = header[156]
                val linkTarget = extractString(header, 157, 100)

                val size = try {
                    sizeStr.trim().toLong(8)
                } catch (_: Exception) { 0L }

                // GNU long name: type 'L' means the data is a long filename
                // for the NEXT entry
                if (typeFlag == 'L'.code.toByte()) {
                    val nameBytes = ByteArray(size.toInt())
                    readFully(gzIn, nameBytes)
                    skipToBlock(gzIn, size)
                    pendingLongName = String(nameBytes).trimEnd('\u0000')
                    continue // next header is the actual entry
                }

                // Resolve final name
                val entryName = pendingLongName ?: run {
                    val prefix = extractString(header, 345, 155)
                    if (prefix.isNotEmpty()) "$prefix/$name" else name
                }
                pendingLongName = null

                val outFile = File(destDir, entryName)

                when (typeFlag) {
                    '5'.code.toByte() -> {
                        outFile.mkdirs()
                    }
                    '2'.code.toByte() -> {
                        // Symlink
                        outFile.parentFile?.mkdirs()
                        try {
                            outFile.delete()
                            java.nio.file.Files.createSymbolicLink(
                                outFile.toPath(),
                                java.nio.file.Paths.get(linkTarget),
                            )
                            symlinkCount++
                        } catch (e: Exception) {
                            Log.w(TAG, "Symlink failed: $entryName -> $linkTarget: ${e.message}")
                        }
                    }
                    '1'.code.toByte() -> {
                        // Hard link — copy the target file
                        outFile.parentFile?.mkdirs()
                        try {
                            val targetFile = File(destDir, linkTarget)
                            if (targetFile.exists()) {
                                targetFile.copyTo(outFile, overwrite = true)
                            }
                        } catch (e: Exception) {
                            Log.d(TAG, "Hard link failed: $entryName -> $linkTarget: ${e.message}")
                        }
                    }
                    '0'.code.toByte(), 0.toByte() -> {
                        // Regular file
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            var remaining = size
                            val copyBuf = ByteArray(8192)
                            while (remaining > 0) {
                                val toRead = minOf(remaining.toInt(), copyBuf.size)
                                val n = gzIn.read(copyBuf, 0, toRead)
                                if (n <= 0) break
                                fos.write(copyBuf, 0, n)
                                remaining -= n
                            }
                        }
                        try {
                            val mode = modeStr.trim().toIntOrNull(8) ?: 0
                            if (mode and 0x49 != 0) {
                                outFile.setExecutable(true, false)
                            }
                        } catch (_: Exception) {}
                        skipToBlock(gzIn, size)
                        fileCount++
                    }
                    else -> {
                        // Skip unknown types (but still consume data)
                        if (size > 0) {
                            var remaining = size
                            val skipBuf = ByteArray(8192)
                            while (remaining > 0) {
                                val toRead = minOf(remaining.toInt(), skipBuf.size)
                                val n = gzIn.read(skipBuf, 0, toRead)
                                if (n <= 0) break
                                remaining -= n
                            }
                            skipToBlock(gzIn, size)
                        }
                    }
                }

                // Also handle directory entries without explicit type flag
                if (typeFlag != '5'.code.toByte() && entryName.endsWith("/")) {
                    outFile.mkdirs()
                }
            }
        }

        Log.d(TAG, "Extracted $fileCount files, $symlinkCount symlinks to ${destDir.absolutePath}")

        // Check bin/sh exists — it's a symlink to /bin/busybox so we must
        // not follow the link (the target is inside the rootfs, not the host)
        val binSh = File(destDir, "bin/sh").toPath()
        if (!java.nio.file.Files.exists(binSh, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            val binDir = File(destDir, "bin")
            val binContents = if (binDir.isDirectory) binDir.list()?.toList() else null
            throw RuntimeException(
                "Extracted $fileCount files, $symlinkCount symlinks but bin/sh not found. " +
                    "bin/ contents: $binContents"
            )
        }
    }

    private fun readFully(input: java.io.InputStream, buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val n = input.read(buf, total, buf.size - total)
            if (n <= 0) break
            total += n
        }
        return total
    }

    private fun extractString(buf: ByteArray, offset: Int, length: Int): String {
        var end = offset + length
        for (i in offset until offset + length) {
            if (buf[i] == 0.toByte()) { end = i; break }
        }
        return String(buf, offset, end - offset, Charsets.US_ASCII).trim()
    }

    /** Skip remaining bytes in the current tar block (blocks are 512-byte aligned). */
    private fun skipToBlock(input: java.io.InputStream, dataSize: Long) {
        val remainder = (512 - (dataSize % 512)) % 512
        if (remainder > 0) {
            val skip = ByteArray(remainder.toInt())
            readFully(input, skip)
        }
    }

    /**
     * Run a command inside the PRoot rootfs (non-interactive).
     * Returns (stdout+stderr, exitCode).
     */
    suspend fun runCommandInProot(command: String): Pair<String, Int> = withContext(Dispatchers.IO) {
        val prootBin = prootBinary ?: throw IllegalStateException("PRoot not available")
        val loaderPath = File(context.applicationInfo.nativeLibraryDir, "libproot_loader.so").absolutePath
        val process = ProcessBuilder(
            prootBin, "-0", "--link2symlink",
            "-r", rootfsDir.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "${context.cacheDir.absolutePath}:/tmp",
            "-w", "/root",
            "/bin/busybox", "sh", "-c", command,
        ).apply {
            environment().apply {
                put("HOME", "/root")
                put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
                put("PROOT_TMP_DIR", context.cacheDir.absolutePath)
                put("PROOT_LOADER", loaderPath)
            }
            redirectErrorStream(true)
        }.start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        Pair(output, exitCode)
    }

    /**
     * Install X11 + VNC + Xfce4 desktop inside the PRoot rootfs.
     */
    suspend fun setupDesktop(vncPassword: String, de: DesktopEnvironment = DesktopEnvironment.XFCE4) {
        try {
            // Ensure rootfs is installed first
            if (!isRootfsInstalled) {
                installRootfs()
                if (_state.value is SetupState.Error) {
                    _desktopState.value = DesktopSetupState.Error("Rootfs install failed")
                    return
                }
            }

            // Reinstall if switching DE or not yet installed
            val needsInstall = installedDesktop != de
            if (needsInstall) {
                _desktopState.value = DesktopSetupState.Installing(
                    "Installing ${de.label} (${de.sizeEstimate} download)..."
                )

            val (installOutput, installExit) = runCommandInProot(
                "apk update && apk add ${de.packages}"
            )
            Log.d(TAG, "apk install exit=$installExit output(last 300)=${installOutput.takeLast(300)}")

            // Check if key binaries were installed — apk may return exit 1 for
            // non-fatal trigger errors (gtk icon cache, fontscale, etc.)
            val checkInstalled = File(rootfsDir, de.verifyBinary).exists()
            if (!checkInstalled) {
                _desktopState.value = DesktopSetupState.Error(
                    "Package install failed: ${installOutput.takeLast(300)}"
                )
                return
            }

            // Write marker so we know which DE is installed
            File(rootfsDir, "root").mkdirs()
            File(rootfsDir, "root/.haven-desktop").writeText("${de.name}\n${de.packages}")
            Log.d(TAG, "${de.label} packages installed")
            }

            if (!de.isWayland) {
                _desktopState.value = DesktopSetupState.Installing("Configuring VNC...")

                // Write VNC password
                runCommandInProot("mkdir -p /root/.vnc")
                if (vncPassword.isNotEmpty()) {
                    val (pwdOut, pwdExit) = runCommandInProot(
                        "echo '$vncPassword' | vncpasswd -f > /root/.vnc/passwd && chmod 600 /root/.vnc/passwd"
                    )
                    Log.d(TAG, "vncpasswd exit=$pwdExit output=$pwdOut")
                    val passwdFile = File(rootfsDir, "root/.vnc/passwd")
                    Log.d(TAG, "passwd file exists=${passwdFile.exists()} size=${passwdFile.length()}")
                } else {
                    // No password — remove any existing passwd file so server uses None
                    File(rootfsDir, "root/.vnc/passwd").delete()
                    Log.d(TAG, "No VNC password set, using SecurityTypes None")
                }

                // Write xstartup
                runCommandInProot("""cat > /root/.vnc/xstartup << 'XEOF'
#!/bin/sh
unset SESSION_MANAGER
unset DBUS_SESSION_BUS_ADDRESS
exec startxfce4
XEOF
chmod +x /root/.vnc/xstartup""")
            }

            Log.d(TAG, "Desktop setup complete")
            _desktopState.value = DesktopSetupState.Complete
        } catch (e: Exception) {
            Log.e(TAG, "Desktop setup failed", e)
            _desktopState.value = DesktopSetupState.Error(e.message ?: "Setup failed")
        }
    }

    private var vncProcess: Process? = null

    /**
     * Start the VNC server as a background proot process.
     * Xvnc runs directly (not via vncserver wrapper) to avoid lock file issues.
     * The process stays alive until explicitly killed or the app exits.
     */
    fun startVncServer(waylandShellCommand: String = "/bin/sh -l") {
        // Kill any existing VNC process (our handle + orphans from previous app instances)
        vncProcess?.destroyForcibly()
        killOrphanedXvnc()

        val de = installedDesktop ?: DesktopEnvironment.XFCE4

        if (de.isNative) {
            startNativeCompositor(de, waylandShellCommand)
            return
        }

        val prootBin = prootBinary ?: return
        val loaderPath = File(context.applicationInfo.nativeLibraryDir, "libproot_loader.so").absolutePath

        // Ensure /root exists on the host filesystem
        val rootHome = File(rootfsDir, "root")
        rootHome.mkdirs()

        val shellCommand = if (de.isWayland) {
            Log.d(TAG, "Starting Wayland desktop: ${de.label}")
            // Wayland: labwc + wayvnc — no X11 lock files or Xvnc needed
            "export HOME=/root; ${de.startCommands} wait"
        } else {
            // X11: Xvnc + traditional desktop environment
            File(context.cacheDir, ".X1-lock").delete()
            File(rootHome, ".ICEauthority").apply { if (!exists()) createNewFile() }
            File(rootHome, ".Xauthority").apply { if (!exists()) createNewFile() }

            val passwdFile = File(rootfsDir, "root/.vnc/passwd")
            val useAuth = passwdFile.exists() && passwdFile.length() >= 8
            val securityArg = if (useAuth) {
                "-SecurityTypes VncAuth -PasswordFile /root/.vnc/passwd"
            } else {
                "-SecurityTypes None"
            }
            Log.d(TAG, "Starting Xvnc: useAuth=$useAuth passwdSize=${passwdFile.length()}")

            "rm -f /tmp/.X1-lock /tmp/.X11-unix/X1 && " +
                "Xvnc :1 -geometry 1280x720 " +
                "$securityArg " +
                "-BlacklistThreshold 10000 " +
                "-localhost 0 & " +
                "sleep 3; " +
                "export DISPLAY=:1; " +
                "export HOME=/root; " +
                // virgl GPU passthrough for GL apps (if virgl_test_server is running)
                "export GALLIUM_DRIVER=virpipe; " +
                "export VTEST_SOCKET=/tmp/.virgl_test; " +
                "${de.startCommands} " +
                "wait"
        }

        // Start virgl_test_server for GPU-accelerated OpenGL in PRoot apps
        val virglBin = File(context.applicationInfo.nativeLibraryDir, "libvirgl_test_server.so")
        val virglSocket = File(context.cacheDir, ".virgl_test")
        virglSocket.delete()
        if (virglBin.canExecute()) {
            Log.d(TAG, "Starting virgl_test_server for ${de.label}...")
            sh.haven.core.wayland.WaylandBridge.nativeStartVirglServer(
                virglBin.absolutePath, virglSocket.absolutePath
            )
        }

        val prootArgs = mutableListOf(
            prootBin, "-0", "--link2symlink",
            "-r", rootfsDir.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "${context.cacheDir.absolutePath}:/tmp",
        )
        prootArgs.addAll(listOf(
            "-w", "/root",
            "/bin/busybox", "sh", "-c",
            shellCommand,
        ))

        val process = ProcessBuilder(prootArgs).apply {
            environment().apply {
                put("HOME", "/root")
                put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
                put("PROOT_TMP_DIR", context.cacheDir.absolutePath)
                put("PROOT_LOADER", loaderPath)
            }
            redirectErrorStream(true)
        }.start()

        vncProcess = process

        // Log output on a background thread
        val logPrefix = if (de.isWayland) "Wayland" else "Xvnc"
        Thread({
            try {
                process.inputStream.bufferedReader().forEachLine { line ->
                    Log.d(TAG, "$logPrefix: $line")
                }
            } catch (_: Exception) {}
            Log.d(TAG, "$logPrefix process exited: ${process.waitFor()}")
        }, "desktop-server-log").apply { isDaemon = true }.start()
    }

    fun stopVncServer() {
        if (sh.haven.core.wayland.WaylandBridge.nativeIsRunning()) {
            sh.haven.core.wayland.WaylandBridge.nativeStop()
        }
        sh.haven.core.wayland.WaylandBridge.nativeStopVirglServer()
        vncProcess?.destroyForcibly()
        vncProcess = null
        killOrphanedXvnc()
    }

    /**
     * Start the native Wayland compositor (embedded in Haven's process).
     * PRoot clients connect via a bind-mounted Wayland socket.
     */
    /** Recursively extract an assets directory to the filesystem. */
    private fun extractAssetsDir(ctx: Context, assetPath: String, destDir: File) {
        val assets = ctx.assets
        val list = assets.list(assetPath) ?: return
        if (list.isEmpty()) {
            // It's a file
            destDir.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                destDir.outputStream().use { output -> input.copyTo(output) }
            }
        } else {
            destDir.mkdirs()
            for (child in list) {
                extractAssetsDir(ctx, "$assetPath/$child", File(destDir, child))
            }
        }
    }

    private fun startNativeCompositor(de: DesktopEnvironment, shellCommand: String = "/bin/sh -l") {
        val bridge = sh.haven.core.wayland.WaylandBridge

        // Prepare XDG runtime dir (must be mode 0700, owned by app)
        val xdgDir = File(context.cacheDir, "wayland-xdg").apply {
            mkdirs()
            setReadable(true, true)
            setWritable(true, true)
            setExecutable(true, true)
        }
        // Clean stale sockets
        File(xdgDir, "wayland-0").delete()
        File(xdgDir, "wayland-0.lock").delete()

        // Extract XKB data from assets on first use
        val xkbDir = File(context.filesDir, "xkb")
        if (!File(xkbDir, "rules/evdev").exists()) {
            Log.d(TAG, "Extracting XKB data...")
            extractAssetsDir(context, "xkb", xkbDir)
        }
        // Fontconfig pointing to system fonts
        val fontconfFile = File(context.cacheDir, "fonts.conf")
        if (!fontconfFile.exists()) {
            fontconfFile.writeText("""
                <?xml version="1.0"?>
                <!DOCTYPE fontconfig SYSTEM "urn:fontconfig:fonts.dtd">
                <fontconfig>
                  <dir>/system/fonts</dir>
                  <cachedir>${context.cacheDir.absolutePath}/fontconfig-cache</cachedir>
                </fontconfig>
            """.trimIndent())
            File(context.cacheDir, "fontconfig-cache").mkdirs()
        }

        Log.d(TAG, "Starting native compositor: XDG_RUNTIME_DIR=${xdgDir.absolutePath}")
        bridge.nativeStart(
            xdgRuntimeDir = xdgDir.absolutePath,
            xkbConfigRoot = xkbDir.absolutePath,
            fontconfigFile = fontconfFile.absolutePath,
        )

        // Wait for socket to appear
        val socket = File(xdgDir, "wayland-0")
        var waited = 0
        while (!socket.exists() && waited < 10) {
            Thread.sleep(500)
            waited++
        }
        if (socket.exists()) {
            Log.d(TAG, "Native compositor started, socket: ${socket.absolutePath}")
            // Try to create a symlink in /data/local/tmp/ via Shizuku for Termux access
            WaylandSocketHelper.tryCreateSymlink(socket.absolutePath)
        } else {
            Log.e(TAG, "Native compositor socket not created after ${waited * 500}ms")
        }

        // Start virgl_test_server for GPU-accelerated OpenGL in PRoot apps
        val virglBin = File(context.applicationInfo.nativeLibraryDir, "libvirgl_test_server.so")
        val virglSocket = File(context.cacheDir, ".virgl_test")
        virglSocket.delete()
        if (virglBin.canExecute()) {
            Log.d(TAG, "Starting virgl_test_server...")
            bridge.nativeStartVirglServer(virglBin.absolutePath, virglSocket.absolutePath)
        }

        // Start PRoot with Wayland clients, bind-mounting the native socket
        val prootBin = prootBinary ?: return
        val loaderPath = File(context.applicationInfo.nativeLibraryDir, "libproot_loader.so").absolutePath
        val rootHome = File(rootfsDir, "root").apply { mkdirs() }

        val process = ProcessBuilder(
            prootBin, "-0", "--link2symlink",
            "-r", rootfsDir.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "${context.cacheDir.absolutePath}:/tmp",
            "-b", "${xdgDir.absolutePath}:/tmp/xdg-runtime",
            "-w", "/root",
            "/bin/busybox", "sh", "-c",
            "export HOME=/root; " +
                "export XDG_RUNTIME_DIR=/tmp/xdg-runtime; " +
                "export WAYLAND_DISPLAY=wayland-0; " +
                "unset FONTCONFIG_FILE; " +
                "unset XKB_CONFIG_ROOT; " +
                "export TERM=xterm-256color; " +
                "export SHELL=${shellCommand.split(" ").first()}; " +
                // virgl GPU passthrough env vars
                "export GALLIUM_DRIVER=virpipe; " +
                "export VTEST_SOCKET=/tmp/.virgl_test; " +
                // Start XWayland for X11 apps (renders into Wayland compositor)
                // Xvfb for X11 apps. XWayland would be better (visible in
                // compositor) but needs labwc rebuilt with -Dxwayland=enabled.
                "mkdir -p /tmp/.X11-unix; " +
                "Xvfb :0 -screen 0 1280x720x24 >/dev/null 2>&1 & " +
                "sleep 1; " +
                "export DISPLAY=:0; " +
                "foot -e $shellCommand 2>&1; " +
                "wait",
        ).apply {
            environment().apply {
                put("HOME", "/root")
                put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
                put("PROOT_TMP_DIR", context.cacheDir.absolutePath)
                put("PROOT_LOADER", loaderPath)
                remove("FONTCONFIG_FILE")
                remove("XKB_CONFIG_ROOT")
            }
            redirectErrorStream(true)
        }.start()

        vncProcess = process

        Thread({
            try {
                process.inputStream.bufferedReader().forEachLine { line ->
                    Log.d(TAG, "NativeWayland: $line")
                }
            } catch (_: Exception) {}
            Log.d(TAG, "NativeWayland PRoot exited: ${process.waitFor()}")
        }, "native-wayland-log").apply { isDaemon = true }.start()
    }

    /**
     * Kill any Xvnc processes that survived a previous app instance.
     * PRoot child processes can outlive the Java Process handle.
     * Android's toolbox ps doesn't support -eo, so we grep the default output.
     */
    private fun killOrphanedXvnc() {
        try {
            // Android ps: columns are USER PID PPID VSZ RSS WCHAN ADDR S NAME (or similar)
            // Use grep to find Xvnc or proot lines, awk to get PID (field 2)
            val proc = ProcessBuilder("sh", "-c",
                "ps -A 2>/dev/null | grep 'Xvnc' | grep -v grep | awk '{print \$2}'"
            ).redirectErrorStream(true).start()
            val pids = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            if (pids.isNotEmpty()) {
                Log.d(TAG, "Killing orphaned Xvnc PIDs: $pids")
                for (pid in pids.lines()) {
                    try {
                        ProcessBuilder("kill", "-9", pid.trim()).start().waitFor()
                    } catch (_: Exception) {}
                }
            } else {
                Log.d(TAG, "No orphaned Xvnc/proot processes found")
            }
        } catch (e: Exception) {
            Log.w(TAG, "killOrphanedXvnc failed: ${e.message}")
        }
    }

    fun resetDesktopState() {
        _desktopState.value = DesktopSetupState.Idle
    }

    /**
     * Delete the rootfs to free space.
     */
    fun deleteRootfs() {
        rootfsDir.deleteRecursively()
        _state.value = SetupState.NotInstalled
    }
}
