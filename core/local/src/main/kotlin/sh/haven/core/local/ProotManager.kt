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
        get() = File(rootfsDir, "usr/bin/Xvnc").exists() &&
            File(rootfsDir, "usr/bin/startxfce4").exists()

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
    suspend fun setupDesktop(vncPassword: String) {
        try {
            // Ensure rootfs is installed first
            if (!isRootfsInstalled) {
                installRootfs()
                if (_state.value is SetupState.Error) {
                    _desktopState.value = DesktopSetupState.Error("Rootfs install failed")
                    return
                }
            }

            if (!isDesktopInstalled) {
                _desktopState.value = DesktopSetupState.Installing("Installing packages (~100MB download)...")

            val (installOutput, installExit) = runCommandInProot(
                "apk update && apk add tigervnc xfce4 xfce4-terminal dbus-x11 font-noto"
            )
            Log.d(TAG, "apk install exit=$installExit output(last 300)=${installOutput.takeLast(300)}")

            // Check if key binaries were installed — apk may return exit 1 for
            // non-fatal trigger errors (gtk icon cache, fontscale, etc.)
            val checkInstalled = File(rootfsDir, "usr/bin/Xvnc").exists() &&
                File(rootfsDir, "usr/bin/startxfce4").exists()
            if (!checkInstalled) {
                _desktopState.value = DesktopSetupState.Error(
                    "Package install failed: ${installOutput.takeLast(300)}"
                )
                return
            }
            Log.d(TAG, "Desktop packages installed")
            }

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
    fun startVncServer() {
        // Kill any existing VNC process (our handle + orphans from previous app instances)
        vncProcess?.destroyForcibly()
        killOrphanedXvnc()

        val prootBin = prootBinary ?: return
        val loaderPath = File(context.applicationInfo.nativeLibraryDir, "libproot_loader.so").absolutePath

        // Clean up stale lock files
        File(context.cacheDir, ".X1-lock").delete()

        // Ensure /root and session files exist on the host filesystem
        // (PRoot's --link2symlink can't always create them inside the rootfs)
        val rootHome = File(rootfsDir, "root")
        rootHome.mkdirs()
        File(rootHome, ".ICEauthority").apply { if (!exists()) createNewFile() }
        File(rootHome, ".Xauthority").apply { if (!exists()) createNewFile() }

        // Use VncAuth if password file exists and has content, otherwise None
        val passwdFile = File(rootfsDir, "root/.vnc/passwd")
        val useAuth = passwdFile.exists() && passwdFile.length() >= 8
        val securityArg = if (useAuth) {
            "-SecurityTypes VncAuth -PasswordFile /root/.vnc/passwd"
        } else {
            "-SecurityTypes None"
        }
        Log.d(TAG, "Starting Xvnc: useAuth=$useAuth passwdSize=${passwdFile.length()}")

        val process = ProcessBuilder(
            prootBin, "-0", "--link2symlink",
            "-r", rootfsDir.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "${context.cacheDir.absolutePath}:/tmp",
            "-w", "/root",
            "/bin/busybox", "sh", "-c",
            "rm -f /tmp/.X1-lock /tmp/.X11-unix/X1 && " +
                "Xvnc :1 -geometry 1280x720 " +
                "$securityArg " +
                "-BlacklistThreshold 10000 " +
                "-localhost 0 & " +
                "sleep 3; " +
                "export DISPLAY=:1; " +
                "export HOME=/root; " +
                "xfwm4 & " +
                "xfce4-panel & " +
                "xfdesktop & " +
                "wait",
        ).apply {
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
        Thread({
            try {
                process.inputStream.bufferedReader().forEachLine { line ->
                    Log.d(TAG, "Xvnc: $line")
                }
            } catch (_: Exception) {}
            Log.d(TAG, "VNC process exited: ${process.waitFor()}")
        }, "vnc-server-log").apply { isDaemon = true }.start()
    }

    fun stopVncServer() {
        vncProcess?.destroyForcibly()
        vncProcess = null
        killOrphanedXvnc()
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
                "ps -A 2>/dev/null | grep -E 'Xvnc|proot' | grep -v grep | awk '{print \$2}'"
            ).redirectErrorStream(true).start()
            val pids = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            if (pids.isNotEmpty()) {
                Log.d(TAG, "Killing orphaned VNC/proot PIDs: $pids")
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
