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
import sh.haven.core.security.CredentialEncryption
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
        OPENBOX(
            label = "Openbox (VNC)",
            packages = "tigervnc openbox xterm xsetroot font-noto",
            verifyBinary = "usr/bin/openbox",
            startCommands = "xsetroot -solid '#2e3440'; openbox & xterm &",
            sizeEstimate = "~10MB",
        ),
        XFCE4(
            label = "Xfce4 (VNC)",
            packages = "tigervnc xfce4 xfce4-terminal dbus-x11 font-noto",
            verifyBinary = "usr/bin/startxfce4",
            startCommands = "xfwm4 & xfce4-panel & xfdesktop &",
            sizeEstimate = "~100MB",
        ),
        WAYLAND_NATIVE(
            label = "Native Wayland",
            packages = "foot font-noto font-awesome adwaita-icon-theme " +
                "xkeyboard-config xwayland mesa-dri-gallium mesa-gbm mesa-gl " +
                "waybar fuzzel xfce4-terminal thunar mousepad htop dbus-x11",
            verifyBinary = "usr/bin/foot",
            startCommands = "",
            sizeEstimate = "~80MB",
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
            packages = "waybar fuzzel dbus font-awesome font-noto adwaita-icon-theme",
            sizeEstimate = "~40MB",
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
            packages = "waybar fuzzel dbus thunar mousepad foot firefox gnome-calculator imv font-noto-emoji adwaita-icon-theme font-awesome",
            sizeEstimate = "~120MB",
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

    /** Stored VNC password for desktop viewer (encrypted at rest via Tink/Android Keystore). */
    var storedVncPassword: String?
        get() {
            val file = File(rootfsDir, "root/.haven-vnc-password")
            if (!file.exists()) return null
            val stored = file.readText().trim().ifEmpty { return null }
            return try {
                CredentialEncryption.decrypt(context, stored)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decrypt VNC password, treating as legacy plaintext", e)
                stored
            }
        }
        set(value) {
            val file = File(rootfsDir, "root/.haven-vnc-password")
            if (value != null) {
                file.writeText(CredentialEncryption.encrypt(context, value))
            } else {
                file.delete()
            }
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

                // Drop a generic shell profile and a welcome README into
                // /root/. These are vendor-neutral — they explain what the
                // environment is, how to install tools, and how the "phone
                // is the point of presence, remote machines are the compute"
                // pattern composes with Haven's SSH primitives. No vendor
                // CLIs, no cloned repositories, no hardcoded hosts.
                seedRootHome(rootfsDir)
            }

            // Install a minimal baseline of packages so the environment is
            // immediately usable: bash (readline + .inputrc), curl +
            // ca-certificates (for the user to follow install instructions
            // over HTTPS), and openssh-client (for the remote-compute
            // composition pattern documented in /root/README.md). Best-
            // effort — if network is unavailable the rootfs is still fully
            // usable with busybox and the user can run `apk add` later.
            installBaseline()

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
     * Seed a freshly-extracted rootfs with Haven's vendor-neutral
     * defaults:
     *
     *  - `/root/.profile`     — interactive shell defaults + rexec helper
     *  - `/root/README.md`    — welcome doc explaining the environment
     *  - `/root/.inputrc`     — readline config (honoured once bash is installed)
     *  - `/root/.ssh/`        — empty directory with 0700 permissions
     *  - `/etc/profile.d/haven.sh` — universal PATH for any future user
     *
     * All files are only written if they don't already exist, so
     * future rootfs refreshes never clobber user edits.
     */
    private fun seedRootHome(rootfsDir: File) {
        val rootHome = File(rootfsDir, "root").apply { mkdirs() }
        val rootAssets = mapOf(
            "proot/root/profile" to ".profile",
            "proot/root/README.md" to "README.md",
            "proot/root/inputrc" to ".inputrc",
        )
        for ((assetPath, targetName) in rootAssets) {
            copyAssetIfAbsent(assetPath, File(rootHome, targetName), "/root/$targetName")
        }

        // Create /root/.ssh with owner-only permissions so the user
        // doesn't have to remember chmod 700 before putting keys here.
        val sshDir = File(rootHome, ".ssh")
        if (!sshDir.exists()) {
            try {
                sshDir.mkdirs()
                // Java File API can't fully express 0700, so use the
                // POSIX chmod syscall from android.system.Os
                try {
                    android.system.Os.chmod(sshDir.absolutePath, 0b111_000_000) // 0700
                } catch (_: Throwable) {
                    // Fall back to best-effort via File API
                    sshDir.setReadable(true, true)
                    sshDir.setWritable(true, true)
                    sshDir.setExecutable(true, true)
                    sshDir.setReadable(false, false)
                    sshDir.setWritable(false, false)
                    sshDir.setExecutable(false, false)
                }
                Log.d(TAG, "Created /root/.ssh (0700)")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create /root/.ssh: ${e.message}")
            }
        }

        // /etc/profile.d/haven.sh — system-wide snippet for any shell
        // account that ever gets created in this rootfs
        val profileD = File(rootfsDir, "etc/profile.d").apply { mkdirs() }
        copyAssetIfAbsent(
            "proot/etc/profile.d/haven.sh",
            File(profileD, "haven.sh"),
            "/etc/profile.d/haven.sh",
        )
    }

    /** Copy an asset into [target] unless [target] already exists. Best-effort. */
    private fun copyAssetIfAbsent(assetPath: String, target: File, displayPath: String) {
        if (target.exists()) {
            Log.d(TAG, "Preserving existing $displayPath")
            return
        }
        try {
            target.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            Log.d(TAG, "Seeded $displayPath from $assetPath")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to seed $displayPath: ${e.message}")
        }
    }

    /**
     * Install a minimal baseline of packages into the fresh rootfs.
     * Best-effort — if network or mirrors are unavailable the rootfs
     * is still fully usable with just the busybox utilities Alpine's
     * minirootfs ships. Failure here never fails the overall install.
     *
     * The baseline is deliberately tiny: just enough for an interactive
     * session and to follow the SSH setup instructions in the README.
     * Everything else (git, vim, compilers, language runtimes) is the
     * user's choice and stays an explicit `apk add` away.
     */
    private suspend fun installBaseline() {
        try {
            val (output, code) = runCommandInProot(
                "apk update >/dev/null 2>&1 && " +
                    "apk add --no-cache bash curl ca-certificates openssh-client 2>&1 | tail -5",
            )
            if (code == 0) {
                Log.d(TAG, "Baseline packages installed")
            } else {
                Log.w(TAG, "Baseline install exit=$code tail=${output.takeLast(200)}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Baseline install failed (non-fatal): ${e.message}")
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

                // Write VNC password — use a temp file to avoid leaking
                // the password in process arguments (visible in /proc)
                runCommandInProot("mkdir -p /root/.vnc")
                if (vncPassword.isNotEmpty()) {
                    val tmpPwd = File(rootfsDir, "root/.vnc/.pwd_tmp")
                    try {
                        tmpPwd.writeText(vncPassword)
                    } finally { /* deleted below after use */ }
                    val (pwdOut, pwdExit) = runCommandInProot(
                        "vncpasswd -f < /root/.vnc/.pwd_tmp > /root/.vnc/passwd && chmod 600 /root/.vnc/passwd; rm -f /root/.vnc/.pwd_tmp"
                    )
                    tmpPwd.delete() // also delete from host side
                    Log.d(TAG, "vncpasswd exit=$pwdExit output=$pwdOut")
                    val passwdFile = File(rootfsDir, "root/.vnc/passwd")
                    Log.d(TAG, "passwd file exists=${passwdFile.exists()} size=${passwdFile.length()}")
                    // Store encrypted for the VNC viewer to use on subsequent starts
                    storedVncPassword = vncPassword
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

        // waybar config — Xfce-style panel with quick-launch buttons and system info
        File(root, ".config/waybar").mkdirs()
        File(root, ".config/waybar/config").writeText(
            """
            |{
            |    "layer": "top",
            |    "position": "bottom",
            |    "height": 26,
            |    "spacing": 0,
            |    "modules-left": [
            |        "custom/apps",
            |        "custom/terminal",
            |        "custom/files",
            |        "custom/editor"
            |    ],
            |    "modules-center": [],
            |    "modules-right": [
            |        "cpu",
            |        "memory",
            |        "clock"
            |    ],
            |    "custom/apps": {
            |        "format": "\uF0C9  Apps",
            |        "on-click": "fuzzel"
            |    },
            |    "custom/terminal": {
            |        "format": "\uF120  Terminal",
            |        "on-click": "xfce4-terminal || foot"
            |    },
            |    "custom/files": {
            |        "format": "\uF07B  Files",
            |        "on-click": "thunar || foot -e ls"
            |    },
            |    "custom/editor": {
            |        "format": "\uF044  Edit",
            |        "on-click": "mousepad"
            |    },
            |    "cpu": {
            |        "format": "\uF2DB {usage}%",
            |        "interval": 5,
            |        "tooltip": true
            |    },
            |    "memory": {
            |        "format": "\uF538 {percentage}%",
            |        "interval": 5,
            |        "tooltip-format": "{used:0.1f}G / {total:0.1f}G"
            |    },
            |    "clock": {
            |        "format": "\uF017 {:%H:%M}",
            |        "format-alt": "\uF073 {:%a %d %b %H:%M}",
            |        "tooltip-format": "<tt>{calendar}</tt>"
            |    }
            |}
            """.trimMargin()
        )
        File(root, ".config/waybar/style.css").writeText(
            """
            |* {
            |    font-family: "Font Awesome 6 Free", "Font Awesome 5 Free", "FontAwesome", "Noto Sans", sans-serif;
            |    font-size: 13px;
            |    min-height: 0;
            |}
            |window#waybar {
            |    background-color: rgba(43, 48, 59, 0.95);
            |    color: #d8dee9;
            |    border-top: 1px solid rgba(100, 114, 125, 0.4);
            |}
            |button {
            |    border: none;
            |    border-radius: 0;
            |}
            |button:hover {
            |    background: rgba(255, 255, 255, 0.1);
            |}
            |#custom-apps {
            |    padding: 0 8px;
            |    font-weight: bold;
            |    background-color: rgba(94, 129, 172, 0.3);
            |    border-right: 1px solid rgba(100, 114, 125, 0.3);
            |}
            |#custom-apps:hover {
            |    background-color: rgba(94, 129, 172, 0.5);
            |}
            |#custom-terminal, #custom-files, #custom-editor {
            |    padding: 0 12px;
            |}
            |#cpu, #memory {
            |    padding: 0 10px;
            |    color: #a3be8c;
            |}
            |#memory {
            |    color: #ebcb8b;
            |}
            |#clock {
            |    padding: 0 8px;
            |    font-weight: bold;
            |}
            """.trimMargin()
        )

        // fuzzel config — large font for touch
        File(root, ".config/fuzzel").mkdirs()
        File(root, ".config/fuzzel/fuzzel.ini").writeText(
            """
            |[main]
            |font=Noto Sans:size=11
            |width=30
            |lines=15
            |prompt=>
            |layer=overlay
            |launch-prefix=/usr/local/bin/launch
            """.trimMargin()
        )

        // foot terminal config — no title bar (SSD wastes space on mobile)
        File(root, ".config/foot").mkdirs()
        File(root, ".config/foot/foot.ini").writeText(
            """
            |[main]
            |font=monospace:size=11
            |pad=2x2
            |
            |[csd]
            |preferred=none
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
