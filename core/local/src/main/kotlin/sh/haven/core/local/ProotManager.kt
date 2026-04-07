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

    internal val rootfsDir: File
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

    val hasAnyDesktopInstalled: Boolean
        get() = installedDesktops.isNotEmpty()

    fun isDesktopInstalled(de: DesktopEnvironment): Boolean =
        de in installedDesktops && File(rootfsDir, de.verifyBinary).exists()

    /** Compat alias — true if any DE is installed. */
    val isDesktopInstalled: Boolean
        get() = hasAnyDesktopInstalled

    enum class DesktopEnvironment(
        val label: String,
        val packages: String,
        val verifyBinary: String,
        val startCommands: String,
        val sizeEstimate: String,
        val isWayland: Boolean = false,
        val isNative: Boolean = false,
        /** Hidden DEs are not shown in the Desktop Manager UI. */
        val hidden: Boolean = false,
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
            hidden = true,
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
        XFCE4_WAYLAND(
            label = "Xfce4 (Wayland)",
            packages = "foot font-noto xkeyboard-config xwayland mesa-dri-gallium mesa-gbm mesa-gl " +
                "xfce4-panel xfce4-terminal xfdesktop thunar xfce4-settings mousepad " +
                "xfce4-appfinder dbus-x11",
            verifyBinary = "root/.haven-xfce4-wayland",
            startCommands = "", // compositor runs natively via WaylandBridge, not in PRoot
            sizeEstimate = "~110MB",
            isWayland = true,
            isNative = true,
        ),
        WAYLAND_NATIVE(
            label = "Native Wayland",
            packages = "foot font-noto xkeyboard-config xwayland mesa-dri-gallium mesa-gbm mesa-gl htop",
            verifyBinary = "usr/bin/foot",
            startCommands = "", // compositor runs natively via WaylandBridge, not in PRoot
            sizeEstimate = "~5MB",
            isWayland = true,
            isNative = true,
        ),
    }

    enum class DesktopAddon(
        val label: String,
        val description: String,
        val packages: String,
        val sizeEstimate: String,
    ) {
        PANEL(
            label = "Panel",
            description = "Taskbar with clock and app launcher",
            packages = "waybar fuzzel dbus",
            sizeEstimate = "~25MB",
        ),
        FILE_MANAGER(
            label = "File Manager",
            description = "Graphical file browser",
            packages = "thunar",
            sizeEstimate = "~10MB",
        ),
        APPS(
            label = "Desktop Apps",
            description = "Text editor, image viewer, media player",
            packages = "mousepad imv mpv",
            sizeEstimate = "~15MB",
        ),
        STARTER_PACK(
            label = "Starter Pack",
            description = "Panel + file manager + editor + terminal + browser + calculator",
            packages = "waybar fuzzel dbus thunar mousepad foot firefox gnome-calculator imv font-noto-emoji adwaita-icon-theme",
            sizeEstimate = "~120MB",
        ),
        VNC_DESKTOP(
            label = "VNC Desktop",
            description = "Xfce4 + VNC server — access PRoot desktop from Haven's Desktop tab",
            packages = "tigervnc xfce4 xfce4-terminal dbus-x11",
            sizeEstimate = "~100MB",
        ),
    }

    /** Which add-ons are installed (persisted as a file in the rootfs). */
    val installedAddons: Set<DesktopAddon>
        get() {
            val marker = File(rootfsDir, "root/.haven-addons")
            if (!marker.exists()) return emptySet()
            return try {
                marker.readText().trim().lines().mapNotNull { line ->
                    try { DesktopAddon.valueOf(line) } catch (_: Exception) { null }
                }.toSet()
            } catch (_: Exception) { emptySet() }
        }

    /** Stored VNC password for desktop viewer. */
    var storedVncPassword: String?
        get() {
            val file = File(rootfsDir, "root/.haven-vnc-password")
            return if (file.exists()) file.readText().trim().ifEmpty { null } else null
        }
        set(value) {
            val file = File(rootfsDir, "root/.haven-vnc-password")
            if (value != null) file.writeText(value) else file.delete()
        }

    /** All installed DEs — detected by checking verifyBinary on filesystem. */
    val installedDesktops: Set<DesktopEnvironment>
        get() {
            if (!isRootfsInstalled) return emptySet()
            return DesktopEnvironment.entries.filter { de ->
                File(rootfsDir, de.verifyBinary).exists()
            }.toSet()
        }

    /** Compat alias — returns the first installed DE. */
    val installedDesktop: DesktopEnvironment?
        get() = installedDesktops.firstOrNull()

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

            // Install if this DE is not yet installed
            val needsInstall = !isDesktopInstalled(de)
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
            // For marker-based DEs, check that the output contains "OK:" (apk success)
            val checkInstalled = if (de.verifyBinary.startsWith("root/.haven-")) {
                installOutput.contains("OK:")
            } else {
                File(rootfsDir, de.verifyBinary).exists()
            }
            if (!checkInstalled) {
                _desktopState.value = DesktopSetupState.Error(
                    "Package install failed: ${installOutput.takeLast(300)}"
                )
                return
            }

            // Update marker — append this DE to the installed set
            File(rootfsDir, "root").mkdirs()
            val updated = installedDesktops + de
            File(rootfsDir, "root/.haven-desktop").writeText(updated.joinToString("\n") { it.name })
            // Create DE-specific marker file for installedDesktops check
            if (de.verifyBinary.startsWith("root/.haven-")) {
                File(rootfsDir, de.verifyBinary).writeText(de.name)
            }
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
                    // Store plaintext for the VNC viewer to use on subsequent starts
                    File(rootfsDir, "root/.haven-vnc-password").writeText(vncPassword)
                } else {
                    // No password — remove any existing passwd file so server uses None
                    File(rootfsDir, "root/.vnc/passwd").delete()
                    File(rootfsDir, "root/.haven-vnc-password").delete()
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

    /**
     * Install optional desktop add-ons (panel, file manager, etc.) into the PRoot rootfs.
     * Packages are installed via apk and config files are written for labwc integration.
     */
    suspend fun installAddons(addons: Set<DesktopAddon>) {
        if (addons.isEmpty()) {
            // Remove addons marker and configs if user unchecked everything
            File(rootfsDir, "root/.haven-addons").delete()
            return
        }

        try {
            _desktopState.value = DesktopSetupState.Installing("Installing desktop features...")

            val packages = addons.joinToString(" ") { it.packages }
            val (installOutput, installExit) = runCommandInProot(
                "apk update && apk add $packages"
            )
            Log.d(TAG, "addon apk install exit=$installExit output(last 300)=${installOutput.takeLast(300)}")

            writeDesktopConfigs()

            // Configure VNC if VNC_DESKTOP addon is being installed
            if (DesktopAddon.VNC_DESKTOP in addons) {
                _desktopState.value = DesktopSetupState.Installing("Configuring VNC...")
                runCommandInProot("mkdir -p /root/.vnc")
                // Set a default VNC password
                val (_, pwdExit) = runCommandInProot(
                    "echo 'haven' | vncpasswd -f > /root/.vnc/passwd && chmod 600 /root/.vnc/passwd"
                )
                Log.d(TAG, "VNC password set (exit=$pwdExit)")
                // Write xstartup for Xfce4
                runCommandInProot("""cat > /root/.vnc/xstartup << 'XEOF'
#!/bin/sh
unset SESSION_MANAGER
unset DBUS_SESSION_BUS_ADDRESS
exec startxfce4
XEOF
chmod +x /root/.vnc/xstartup""")
            }

            // Write marker
            File(rootfsDir, "root/.haven-addons")
                .writeText(addons.joinToString("\n") { it.name })
            Log.d(TAG, "Desktop addons installed: ${addons.map { it.name }}")

            _desktopState.value = DesktopSetupState.Complete
        } catch (e: Exception) {
            Log.e(TAG, "Addon install failed", e)
            _desktopState.value = DesktopSetupState.Error(e.message ?: "Addon install failed")
        }
    }

    /**
     * Uninstall a desktop environment from the PRoot rootfs.
     * Removes packages and updates the marker file.
     */
    suspend fun uninstallDesktop(de: DesktopEnvironment) {
        try {
            _desktopState.value = DesktopSetupState.Installing("Removing ${de.label}...")
            val (output, exit) = runCommandInProot("apk del ${de.packages}")
            Log.d(TAG, "apk del ${de.label} exit=$exit output(last 300)=${output.takeLast(300)}")

            val remaining = installedDesktops - de
            val marker = File(rootfsDir, "root/.haven-desktop")
            if (remaining.isEmpty()) {
                marker.delete()
            } else {
                marker.writeText(remaining.joinToString("\n") { it.name })
            }
            // Remove DE-specific marker file
            if (de.verifyBinary.startsWith("root/.haven-")) {
                File(rootfsDir, de.verifyBinary).delete()
            }
            Log.d(TAG, "${de.label} uninstalled, remaining: ${remaining.map { it.name }}")
            _desktopState.value = DesktopSetupState.Complete
        } catch (e: Exception) {
            Log.e(TAG, "Desktop uninstall failed", e)
            _desktopState.value = DesktopSetupState.Error(e.message ?: "Uninstall failed")
        }
    }

    /** Write mobile-optimized config files for waybar, fuzzel, and labwc menu. */
    private fun writeDesktopConfigs() {
        val root = File(rootfsDir, "root")

        // waybar config — bottom bar with app launcher and clock
        File(root, ".config/waybar").mkdirs()
        File(root, ".config/waybar/config").writeText(
            """
            |{
            |    "layer": "top",
            |    "position": "bottom",
            |    "height": 40,
            |    "modules-left": ["custom/launcher"],
            |    "modules-right": ["clock"],
            |    "custom/launcher": {
            |        "format": "Apps",
            |        "on-click": "fuzzel"
            |    },
            |    "clock": {
            |        "format": "{:%H:%M}",
            |        "format-alt": "{:%Y-%m-%d %H:%M}"
            |    }
            |}
            """.trimMargin()
        )
        File(root, ".config/waybar/style.css").writeText(
            """
            |* {
            |    font-family: "Noto Sans", sans-serif;
            |    font-size: 16px;
            |    min-height: 0;
            |}
            |window#waybar {
            |    background-color: rgba(30, 30, 46, 0.9);
            |    color: #cdd6f4;
            |}
            |#custom-launcher {
            |    padding: 0 16px;
            |    font-weight: bold;
            |}
            |#clock {
            |    padding: 0 16px;
            |}
            """.trimMargin()
        )

        // fuzzel config — large font for touch
        File(root, ".config/fuzzel").mkdirs()
        File(root, ".config/fuzzel/fuzzel.ini").writeText(
            """
            |[main]
            |font=Noto Sans:size=14
            |width=40
            |lines=15
            |prompt=>
            |launch-prefix=/usr/local/bin/launch
            """.trimMargin()
        )

        writeLabwcMenu()

        // .desktop files so fuzzel has something to show.
        // Write to both user and system dirs — fuzzel may not resolve
        // $HOME inside dbus-run-session depending on environment.
        val appsDir = File(root, ".local/share/applications").apply { mkdirs() }
        val sysAppsDir = File(rootfsDir, "usr/share/applications").apply { mkdirs() }
        // Always-available entries
        val desktopEntries = mutableMapOf(
            "foot.desktop" to """
                |[Desktop Entry]
                |Name=Terminal
                |Exec=foot
                |Icon=utilities-terminal
                |Type=Application
                |Categories=System;TerminalEmulator;
                """.trimMargin(),
            "htop.desktop" to """
                |[Desktop Entry]
                |Name=System Monitor
                |Exec=foot -e htop
                |Icon=utilities-system-monitor
                |Type=Application
                |Categories=System;Monitor;
                """.trimMargin(),
        )

        // Conditional entries — only written if the binary exists
        data class AppEntry(val file: String, val binary: String, val content: String)
        val conditionalApps = listOf(
            AppEntry("thunar.desktop", "usr/bin/thunar", """
                |[Desktop Entry]
                |Name=File Manager
                |Exec=thunar
                |Icon=system-file-manager
                |Type=Application
                |Categories=System;FileManager;
                """.trimMargin()),
            AppEntry("mousepad.desktop", "usr/bin/mousepad", """
                |[Desktop Entry]
                |Name=Text Editor
                |Exec=mousepad
                |Icon=accessories-text-editor
                |Type=Application
                |Categories=Utility;TextEditor;
                """.trimMargin()),
            AppEntry("imv.desktop", "usr/bin/imv-wayland", """
                |[Desktop Entry]
                |Name=Image Viewer
                |Exec=imv-wayland
                |Icon=image-x-generic
                |Type=Application
                |Categories=Graphics;Viewer;
                |MimeType=image/png;image/jpeg;image/gif;image/bmp;image/webp;
                """.trimMargin()),
            AppEntry("mpv.desktop", "usr/bin/mpv", """
                |[Desktop Entry]
                |Name=Media Player
                |Exec=mpv --player-operation-mode=pseudo-gui
                |Icon=multimedia-video-player
                |Type=Application
                |Categories=AudioVideo;Video;
                |MimeType=video/mp4;video/webm;audio/mp3;audio/ogg;
                """.trimMargin()),
        )
        for (app in conditionalApps) {
            if (File(rootfsDir, app.binary).exists()) {
                desktopEntries[app.file] = app.content
            }
        }

        for ((name, content) in desktopEntries) {
            File(appsDir, name).writeText(content)
            File(sysAppsDir, name).writeText(content)
        }

        Log.d(TAG, "Desktop config files written")
    }

    /** Write labwc right-click desktop menu. References apps that may or may not be installed. */
    private fun writeLabwcMenu() {
        val labwcDir = File(rootfsDir, "root/.config/labwc")
        labwcDir.mkdirs()
        File(labwcDir, "menu.xml").writeText(
            """
            |<?xml version="1.0" ?>
            |<openbox_menu>
            |  <menu id="root-menu" label="">
            |    <item label="Terminal"><action name="Execute" command="foot"/></item>
            |    <item label="File Manager"><action name="Execute" command="thunar"/></item>
            |    <separator />
            |    <item label="Apps"><action name="Execute" command="fuzzel"/></item>
            |  </menu>
            |</openbox_menu>
            """.trimMargin()
        )
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
