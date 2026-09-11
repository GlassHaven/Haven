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
import sh.haven.core.data.repository.ProotInstallLogRepository
import sh.haven.core.local.proot.Arch
import sh.haven.core.local.proot.CustomBind
import sh.haven.core.local.proot.DesktopCatalog
import sh.haven.core.local.proot.downloadCleartextFallback
import sh.haven.core.local.proot.downloadViaPlatform
import sh.haven.core.local.proot.DesktopEnvironmentSpec
import sh.haven.core.local.proot.Distro
import sh.haven.core.local.proot.DistroCatalog
import sh.haven.core.local.proot.LaunchSpec
import sh.haven.core.local.proot.MirrorCatalog
import sh.haven.core.local.proot.MirrorRegion
import sh.haven.core.local.proot.PackageFamily
import sh.haven.core.local.proot.PackageOps
import sh.haven.core.local.proot.RootfsFormat
import sh.haven.core.local.proot.RootfsSource
import sh.haven.core.security.CredentialEncryption
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ProotManager"
private const val PREF_ACTIVE_DISTRO_ID = "active_distro_id"
private const val PREF_MIRROR_REGION = "mirror_region"

/** Proot launch toggles for the local Linux sessions (#300 / #301). */
private const val PREF_REMAP_LOW_PORTS = "remap_low_ports"
private const val PREF_SHARE_STORAGE = "share_storage_with_guest"
private const val PREF_BIND_ANDROID_SYSTEM = "bind_android_system"
private const val PREF_DNS_MODE = "proot_dns_mode"
private const val PREF_DNS_SERVERS = "proot_dns_servers"

/** Per-distro custom bind mounts (#301). Key suffix is the distro id. */
private const val PREF_CUSTOM_BINDS_PREFIX = "custom_binds_"

/** User-imported distros registry (#284), stored as a JSON array. */
private const val PREF_CUSTOM_DISTROS = "custom_distros_json"

/** One-shot guard for the #262 install-marker backfill. */
private const val PREF_MARKERS_BACKFILLED = "install_markers_backfilled_v1"

/**
 * File written at the rootfs root only after extract + hooks + baseline
 * all succeed. Its presence is what distinguishes a *complete* install
 * from a partial one left by an extract interrupted mid-flight (#262).
 */
internal const val ROOTFS_READY_MARKER = ".haven-rootfs-ready"

/**
 * Foreign-arch marker (#325): holds the rootfs ABI (an [Arch.abi] value, e.g.
 * `x86_64`) when the rootfs is a different architecture than the host and its
 * binaries must be run through a host-side qemu-user loader. Absent = host-arch
 * rootfs (every install before #325), so its absence keeps the qemu path inert.
 */
internal const val ROOTFS_ARCH_MARKER = ".haven-rootfs-arch"

/**
 * Whether the extracted rootfs *files* are present (busybox `bin/sh`).
 * Necessary but not sufficient for a usable install — an extract killed
 * partway can have `bin/sh` and little else, so callers that need a
 * usable rootfs want [isRootfsComplete] instead.
 */
internal fun rootfsFilesPresent(rootfsDir: File): Boolean =
    java.nio.file.Files.exists(
        File(rootfsDir, "bin/sh").toPath(),
        java.nio.file.LinkOption.NOFOLLOW_LINKS,
    )

/**
 * Whether [rootfsDir] holds a *complete* install: files present AND the
 * [ROOTFS_READY_MARKER] written at the end of a successful install. An
 * install interrupted before the marker (app killed mid-extract) reads
 * as incomplete and is re-run cleanly rather than mistaken for ready
 * (#262).
 */
internal fun isRootfsComplete(rootfsDir: File): Boolean =
    rootfsFilesPresent(rootfsDir) && File(rootfsDir, ROOTFS_READY_MARKER).exists()

/**
 * Delete [root] and everything under it, even when in-proot package
 * scripts left directories read-only.
 *
 * Plain [File.deleteRecursively] can't unlink a file whose parent
 * directory lacks the write bit — e.g. Arch's `ca-certificates`
 * /`update-ca-trust` makes `etc/ca-certificates/extracted/cadir/`
 * mode 0555. That trapped a broken rootfs forever and broke the next
 * install (#174). So restore owner rwx on every directory first;
 * removing a file needs write on its *parent* dir, not on the file.
 *
 * `walkTopDown()` does not follow symlinks, so we never chmod or
 * descend through links pointing outside the rootfs; the delete pass
 * unlinks symlinks without following them.
 */
internal fun forceDeleteRecursively(root: File): Boolean {
    val path = root.toPath()
    // A symlink (even a dangling one): unlink the link itself and never follow
    // it — otherwise both the chmod pass and the delete would operate on
    // whatever it points at, which a rootfs link can aim outside the tree.
    // Kotlin's own walkTopDown()/deleteRecursively() follow directory symlinks
    // (isDirectory/listFiles resolve the link), so we recurse by hand.
    // (security-review #22)
    if (java.nio.file.Files.isSymbolicLink(path)) return root.delete()
    if (!root.exists()) return true
    if (root.isDirectory) {
        // Unlinking a child needs write+exec on the parent dir; some rootfs
        // dirs ship 0555 (#174 — Arch's update-ca-trust), so restore owner rwx
        // before recursing.
        root.setReadable(true, false)
        root.setExecutable(true, false)
        root.setWritable(true, false)
        root.listFiles()?.forEach { forceDeleteRecursively(it) }
    }
    return root.delete()
}

/**
 * True if [child] resolves to a location inside [base] (or is [base] itself).
 * Uses canonical paths so it also catches `..` traversal and symlinks that
 * escape the tree — the extraction guard against zip-slip. (security-review #21)
 */
internal fun isWithinDir(base: File, child: File): Boolean {
    val basePath = base.canonicalPath
    val childPath = child.canonicalPath
    return childPath == basePath || childPath.startsWith(basePath + File.separator)
}

/**
 * Clear whatever is at [outFile] if it's the wrong type for the tar entry
 * about to be written there (a directory where a file is wanted, or vice
 * versa). A malformed or duplicate tar entry can reuse a path with a
 * different type than what's already there — without this, `mkdirs()` /
 * `FileOutputStream` silently no-op or throw on the stale type instead of
 * letting the new entry win.
 */
internal fun clearPathIfWrongType(outFile: File, entryIsDir: Boolean) {
    if (outFile.exists() && outFile.isDirectory != entryIsDir) {
        if (outFile.isDirectory) outFile.deleteRecursively() else outFile.delete()
    }
}

/**
 * Apply tar `--strip-components=N` to a hard-link entry's link target (#328).
 *
 * A tar hard-link target is an archive-relative path that includes the same
 * leading components as entry names, so it must be stripped identically.
 * Without this, every hard link in a wrapped tarball (stripComponents=1 — the
 * shape of every proot-distro import) resolved to a nonexistent path and the
 * linked file was silently missing from the extracted rootfs. Pure so the
 * strip rule is unit-testable next to [clearPathIfWrongType].
 */
internal fun resolveTarLinkTarget(linkTarget: String, stripComponents: Int): String =
    if (stripComponents > 0) {
        linkTarget.split('/').drop(stripComponents).joinToString("/")
    } else {
        linkTarget
    }

/**
 * Flatten proot link2symlink artifacts in an extracted rootfs (#328).
 *
 * A rootfs BUILT under a proot (Termux proot-distro, or a Haven guest) has
 * every hard link dpkg ever made rewritten by `--link2symlink` into
 * `name -> .l2s.nameNNNN [-> chain] -> payload`, where the symlink target is
 * an ABSOLUTE path of the build system (e.g. `/data/data/com.termux/...`).
 * Tar that tree and import it here and those links resolve to nothing — or
 * to another app's private storage. The payload sits either in the same
 * directory as the link (sibling rule) or — for a rootfs built under a
 * Haven guest with `PROOT_L2S_DIR` — in the rootfs-top `.l2s/` directory,
 * so: resolve by target basename in both places, follow `.l2s.` chains, and
 * materialize a real copy in place of the link. Dangling links (payload
 * never made it into the archive) are left as-is — dpkg unlinks and
 * rewrites its `-old` backups, device-verified harmless. A leftover
 * top-level `.l2s/` is deleted afterwards so stale payloads don't ship
 * into the fresh install (the runtime recreates it on demand).
 * Returns the number of links materialized. Pure-JVM so it unit-tests next
 * to [clearPathIfWrongType].
 */
internal fun flattenL2sLinks(root: File): Int {
    var fixed = 0
    val links = mutableListOf<File>()
    root.walkTopDown()
        .onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }
        .forEach { f ->
            val p = f.toPath()
            if (!java.nio.file.Files.isSymbolicLink(p)) return@forEach
            val targetName = try {
                java.nio.file.Files.readSymbolicLink(p).fileName?.toString()
            } catch (_: Exception) {
                null
            }
            if (targetName?.startsWith(".l2s.") == true) links.add(f)
        }
    val l2sDir = File(root, ".l2s")
    fun resolveChain(baseDir: File?, targetName: String): File? {
        if (baseDir == null) return null
        var candidate = File(baseDir, targetName)
        var hops = 0
        while (java.nio.file.Files.isSymbolicLink(candidate.toPath()) && hops++ < 8) {
            val next = try {
                java.nio.file.Files.readSymbolicLink(candidate.toPath()).fileName?.toString()
            } catch (_: Exception) {
                null
            } ?: break
            candidate = File(candidate.parentFile, next)
        }
        return candidate.takeIf { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
    }
    for (link in links) {
        val targetName = try {
            java.nio.file.Files.readSymbolicLink(link.toPath()).fileName?.toString()
        } catch (_: Exception) {
            null
        } ?: continue
        val payload = resolveChain(link.parentFile, targetName)
            ?: resolveChain(l2sDir, targetName)
        if (payload != null) {
            val perms = try {
                java.nio.file.Files.getPosixFilePermissions(payload.toPath())
            } catch (_: Exception) {
                null
            }
            link.delete()
            payload.copyTo(link)
            perms?.let {
                try {
                    java.nio.file.Files.setPosixFilePermissions(link.toPath(), it)
                } catch (_: Exception) {}
            }
            fixed++
        }
    }
    if (l2sDir.isDirectory) l2sDir.deleteRecursively()
    return fixed
}

/**
 * Return the subset of [binaries] (rootfs-relative paths like
 * `usr/bin/xfwm4`) that are missing under [rootfs].
 *
 * A desktop whose primary `verifyBinary` is present can still be
 * unstartable: when a package install half-completes it can leave the
 * components the desktop's start command actually launches (the window
 * manager, panel, …) uninstalled. Checking only one binary then reports
 * "installed" for a desktop that renders nothing (#254). Pure +
 * filesystem-only so the post-install gate is unit-testable over a temp
 * rootfs.
 */
internal fun missingDesktopBinaries(rootfs: File, binaries: List<String>): List<String> =
    binaries.filterNot { File(rootfs, it).exists() }

/**
 * Packages to actually remove when uninstalling one desktop, given the
 * package lists of the *other* desktops still installed on the same distro.
 * A package another installed desktop also declares is retained — removing
 * it would delete a binary that desktop's install-detection relies on and
 * corrupt its state (#368: Custom-X11 and Native-X11 both ship `xterm`, and
 * every VNC desktop ships `tigervnc`, so a naive `apk del <full list>` broke
 * a sibling and set off the uninstall loop). Pure set arithmetic so it is
 * unit-testable without a guest.
 */
internal fun desktopPackagesToRemove(
    targetPackages: List<String>,
    otherInstalledPackages: Collection<List<String>>,
): List<String> {
    val retained = otherInstalledPackages.flatten().toSet()
    return targetPackages.filterNot { it in retained }
}

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
    private val installLogRepository: ProotInstallLogRepository,
) {
    /**
     * Which phase of the OS install pipeline the state belongs to.
     * Used by [SetupState.Error] to attribute failures, so the UI
     * can render a "Bootstrap hook: void-xbps-bootstrap" chip
     * instead of an anonymous "Installation failed". The five
     * phases correspond 1:1 to the steps in [installRootfs].
     */
    enum class Phase {
        RootfsDownload,
        RootfsExtract,
        BootstrapHook,
        Baseline,
    }

    sealed class SetupState {
        data object NotInstalled : SetupState()
        data class Downloading(val progress: Int) : SetupState()
        data object Extracting : SetupState()
        /**
         * Post-extract setup — running distro post-extract hooks
         * (locale-gen on Arch, ca-cert refresh on Void) and the
         * baseline-package install (pacman -Sy / apt-get install
         * tmux etc.). Distinct from [Extracting] so the UI doesn't
         * mislabel the slow distro-prep phase as "extraction".
         */
        data class Initializing(val step: String) : SetupState()
        data object Ready : SetupState()
        /**
         * Failure attributed to a specific [Phase]. [logTail] is the
         * last ~1500 chars of the failing command's output — empty
         * for failures that aren't shell-exec related (e.g. network
         * error during download). The UI renders [phase] as a chip
         * and [logTail] in a scroll box.
         */
        data class Error(
            val phase: Phase,
            val message: String,
            val logTail: String = "",
        ) : SetupState()
    }

    private val _state = MutableStateFlow<SetupState>(SetupState.NotInstalled)
    val state: StateFlow<SetupState> = _state.asStateFlow()

    private val prefs by lazy {
        context.getSharedPreferences("proot-manager", Context.MODE_PRIVATE)
    }

    private val _activeDistroId = MutableStateFlow(
        prefs.getString(PREF_ACTIVE_DISTRO_ID, null) ?: DistroCatalog.DEFAULT_ID,
    )

    /**
     * Currently-active distro id. Backed by SharedPreferences so
     * the user's choice survives app restarts. Defaults to
     * [DistroCatalog.DEFAULT_ID] (Alpine 3.21) on fresh installs.
     */
    val activeDistroIdFlow: StateFlow<String> = _activeDistroId.asStateFlow()
    val activeDistroId: String
        get() = _activeDistroId.value

    /**
     * Set the active distro. The id must resolve in [DistroCatalog].
     * Persists immediately; subsequent calls to [activeRootfsDir] /
     * [installRootfs] / DE operations route to the new distro.
     *
     * Setting this to a distro that has no rootfs installed yet is
     * fine — the next [installRootfs] call will download it.
     */
    fun setActiveDistroId(id: String) {
        require(DistroCatalog.lookup(id) != null) { "Unknown distro id: $id" }
        if (id == _activeDistroId.value) return
        prefs.edit().putString(PREF_ACTIVE_DISTRO_ID, id).apply()
        _activeDistroId.value = id
        // Re-evaluate ready state for the new active rootfs
        _state.value = if (isReady) SetupState.Ready else SetupState.NotInstalled
        Log.d(TAG, "Active distro switched to $id")
    }

    /** The active [Distro] looked up from [DistroCatalog]. */
    val activeDistro: Distro
        get() = DistroCatalog.lookup(activeDistroId)
            ?: error("Unknown distro id: $activeDistroId")

    // --- Package mirror region (#263 / issue #162) ---

    private val _mirrorRegion = MutableStateFlow(
        prefs.getString(PREF_MIRROR_REGION, null)
            ?.let { runCatching { MirrorRegion.valueOf(it) }.getOrNull() }
            ?: MirrorRegion.DEFAULT,
    )

    /**
     * Selected package-mirror region. Applied to a rootfs at install time
     * (in [runPostExtractSetup], before the baseline + DE installs) and
     * re-applied to every installed distro when changed via
     * [setMirrorRegion], so a Settings change takes effect without a
     * reinstall. [MirrorRegion.DEFAULT] keeps each distro's shipped CDN.
     */
    val mirrorRegionFlow: StateFlow<MirrorRegion> = _mirrorRegion.asStateFlow()
    val mirrorRegion: MirrorRegion get() = _mirrorRegion.value

    /**
     * Change the package-mirror region. Persists the choice and rewrites
     * the repo config of every already-installed distro in place (cheap
     * local file edits; idempotent). Future installs pick it up in
     * [runPostExtractSetup].
     */
    fun setMirrorRegion(region: MirrorRegion) {
        if (region == _mirrorRegion.value) return
        prefs.edit().putString(PREF_MIRROR_REGION, region.name).apply()
        _mirrorRegion.value = region
        for (distro in installedDistros) {
            val changed = MirrorCatalog.apply(rootfsDirFor(distro.id), distro.id, region)
            if (changed.isNotEmpty()) {
                Log.d(TAG, "Mirror region $region applied to ${distro.id}: $changed")
            }
        }
    }

    // --- Local-session proot launch toggles (#300 / #301) ---

    private val _remapLowPorts = MutableStateFlow(prefs.getBoolean(PREF_REMAP_LOW_PORTS, false))

    /**
     * #300: when on, interactive proot launches add proot's `-p` flag, which
     * shifts any privileged (<1024) `bind()` up by +2000 so guest services
     * can listen on what looks like a low port (e.g. `:80` is reachable at
     * `:2080`) despite Android forbidding the app uid from binding <1024.
     * Outbound connections to non-localhost are left untouched. Default off
     * (it remaps *every* privileged bind, including a guest sshd's `:22`).
     */
    val remapLowPortsFlow: StateFlow<Boolean> = _remapLowPorts.asStateFlow()
    val remapLowPorts: Boolean get() = _remapLowPorts.value

    fun setRemapLowPorts(enabled: Boolean) {
        if (enabled == _remapLowPorts.value) return
        prefs.edit().putBoolean(PREF_REMAP_LOW_PORTS, enabled).apply()
        _remapLowPorts.value = enabled
    }

    private val _shareStorageWithGuest = MutableStateFlow(prefs.getBoolean(PREF_SHARE_STORAGE, true))

    /**
     * #301: when off, the local proot shell no longer binds `/storage` and
     * `/storage/emulated/0` into the guest, so the guest can't see the user's
     * photos/downloads. Default on (preserves the original behaviour where the
     * guest reaches shared files via `/storage` and `/sdcard`).
     */
    val shareStorageWithGuestFlow: StateFlow<Boolean> = _shareStorageWithGuest.asStateFlow()
    val shareStorageWithGuest: Boolean get() = _shareStorageWithGuest.value

    fun setShareStorageWithGuest(enabled: Boolean) {
        if (enabled == _shareStorageWithGuest.value) return
        prefs.edit().putBoolean(PREF_SHARE_STORAGE, enabled).apply()
        _shareStorageWithGuest.value = enabled
    }

    private val _bindAndroidSystem = MutableStateFlow(prefs.getBoolean(PREF_BIND_ANDROID_SYSTEM, false))

    /**
     * #304 (part 2): when on, the proot launches also bind Android's own system
     * partitions ([androidSystemPaths]) into the guest at the same paths, so guest
     * software can run Android's native binaries — e.g. `/system/bin/getprop`, which
     * dynamically links via `/system/bin/linker64` → `/apex` and reads
     * `/linkerconfig/ld.config.txt`. Default **off**: these partitions expose device
     * and vendor internals, and their layout/SELinux labels vary by device and OS
     * version, so it's opt-in. The partitions are kernel-mounted read-only, so the
     * bind can't modify them regardless.
     */
    val bindAndroidSystemFlow: StateFlow<Boolean> = _bindAndroidSystem.asStateFlow()
    val bindAndroidSystem: Boolean get() = _bindAndroidSystem.value

    fun setBindAndroidSystem(enabled: Boolean) {
        if (enabled == _bindAndroidSystem.value) return
        prefs.edit().putBoolean(PREF_BIND_ANDROID_SYSTEM, enabled).apply()
        _bindAndroidSystem.value = enabled
    }

    private val _dnsMode = MutableStateFlow(ProotDnsMode.fromId(prefs.getString(PREF_DNS_MODE, null)))

    /**
     * #446: which resolvers go into the guest's `/etc/resolv.conf`. Android has none,
     * so Haven writes the file; it used to hardcode public servers, which fails
     * *silently* wherever outbound port 53 is restricted to the network's own resolver
     * — package installs simply hang. Defaults to [ProotDnsMode.SYSTEM], the only
     * choice that works on every network.
     */
    val dnsModeFlow: StateFlow<ProotDnsMode> = _dnsMode.asStateFlow()
    val dnsMode: ProotDnsMode get() = _dnsMode.value

    fun setDnsMode(mode: ProotDnsMode) {
        if (mode == _dnsMode.value) return
        prefs.edit().putString(PREF_DNS_MODE, mode.id).apply()
        _dnsMode.value = mode
    }

    private val _dnsServers = MutableStateFlow(prefs.getString(PREF_DNS_SERVERS, "").orEmpty())

    /** Custom nameservers (comma/space separated IP literals) used when [dnsMode] is CUSTOM. */
    val dnsServersFlow: StateFlow<String> = _dnsServers.asStateFlow()
    val dnsServers: String get() = _dnsServers.value

    fun setDnsServers(servers: String) {
        val trimmed = servers.trim()
        if (trimmed == _dnsServers.value) return
        prefs.edit().putString(PREF_DNS_SERVERS, trimmed).apply()
        _dnsServers.value = trimmed
    }

    /**
     * Write `etc/resolv.conf` under [rootfsDir] from the current DNS setting, returning
     * the servers used. Called on every guest launch as well as at install time, so
     * changing the setting takes effect without reinstalling the distro.
     */
    fun applyResolvConf(rootfsDir: File): List<String> =
        ProotDns.write(rootfsDir, context, dnsMode, dnsServers)

    /**
     * Android system partitions exposed by [bindAndroidSystem]. `/apex` carries the
     * dynamic linker (`/system/bin/linker64` → `/apex/com.android.runtime/bin/linker64`)
     * and the runtime libs; the rest carry the binaries and their libraries. Only
     * those readable on this device are bound (proot errors on a missing source).
     *
     * The last entry is the linker's generated config, bound as a **file**. The
     * directory `/linkerconfig` cannot be bound: the app's SELinux domain can't stat
     * it, so proot refuses with `can't sanitize binding "/linkerconfig": Permission
     * denied` — but the file *inside* it reads fine, and binding that is enough.
     * Without it every Android binary run in the guest opens with "failed to find
     * generated linker configuration from /linkerconfig/ld.config.txt" (#384).
     *
     * Device-verified (OnePlus 13, Android 16): with the file bound the warning is
     * gone and getprop/toybox/`cmd package` behave exactly as before — the real
     * config changes nothing else, it just stops the linker complaining.
     */
    private val androidSystemPaths = listOf(
        "/system", "/vendor", "/apex", "/product", "/system_ext", "/odm",
        "/linkerconfig/ld.config.txt",
    )

    /**
     * proot bind args exposing Android's system partitions (#304 part 2), or an empty
     * array when the toggle is off. [longForm] selects `--bind=P` vs `-b P` to match
     * each launch path's arg style. Bound at the same path in the guest (guests have
     * no `/system` etc., so nothing is shadowed).
     */
    fun androidSystemBindArgs(longForm: Boolean): Array<String> {
        if (!bindAndroidSystem) return emptyArray()
        return androidSystemPaths
            .filter(::bindableFromAppDomain)
            .flatMap { if (longForm) listOf("--bind=$it") else listOf("-b", it) }
            .toTypedArray()
    }

    /**
     * True when proot can bind [path] from this app's SELinux domain.
     *
     * `exists()` stats the path, and that is NOT enough here: the app may not stat
     * `/linkerconfig/ld.config.txt` (stat is denied) even though it reads it perfectly
     * well, so an exists()-only filter drops the one bind that silences the linker
     * warning (#384). Accept a path that is either stat-able or readable.
     */
    private fun bindableFromAppDomain(path: String): Boolean =
        File(path).let { it.exists() || it.canRead() }

    // --- Per-distro custom bind mounts (#301) ---

    private val _customBindsRev = MutableStateFlow(0)

    /**
     * Bumped whenever any distro's custom binds change, so the Manage UI
     * (which reads [customBinds] imperatively, keyed by distro id) recomposes.
     */
    val customBindsRev: StateFlow<Int> = _customBindsRev.asStateFlow()

    /**
     * Extra user-defined bind mounts for [distroId] (#301) — absolute Android
     * paths exposed inside that distro's guest, in addition to the fixed
     * system binds. Read at every proot launch (interactive shell, desktop,
     * one-shot command) so a path the user adds is reachable everywhere the
     * distro runs. Empty by default.
     */
    fun customBinds(distroId: String): List<CustomBind> =
        (prefs.getString("$PREF_CUSTOM_BINDS_PREFIX$distroId", "") ?: "")
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { CustomBind.parse(it) }

    /** Replace the custom binds for [distroId]. Persists immediately. */
    fun setCustomBinds(distroId: String, binds: List<CustomBind>) {
        val joined = binds
            .map { it.spec().trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
        prefs.edit().putString("$PREF_CUSTOM_BINDS_PREFIX$distroId", joined).apply()
        _customBindsRev.value++
    }

    /** Custom binds for [distroId] as proot short-form args (`-b spec …`). */
    fun customBindShortArgs(distroId: String): List<String> =
        customBinds(distroId).flatMap { listOf("-b", it.spec()) }

    /**
     * Shared-storage binds as proot short-form args (`-b spec …`), or empty when
     * the user has opted out ([shareStorageWithGuest], default on). Binds Android's
     * `/storage` plus a `/sdcard` alias (#256) so the guest reaches the user's
     * files; needs Haven's storage permission for content to actually show.
     *
     * Centralised here because EVERY proot launch path that should expose storage
     * must add the identical binds — the interactive shell had them but the desktop
     * launchers didn't, so files showed in the terminal but the desktop file
     * manager saw an empty `/sdcard` (#420).
     */
    fun storageBindShortArgs(): List<String> =
        if (shareStorageWithGuest) {
            listOf("-b", "/storage", "-b", "/storage/emulated/0:/sdcard")
        } else {
            emptyList()
        }

    /** Shared-storage binds as proot long-form args (`--bind=spec`); see [storageBindShortArgs]. */
    fun storageBindLongArgs(): List<String> =
        if (shareStorageWithGuest) {
            listOf("--bind=/storage", "--bind=/storage/emulated/0:/sdcard")
        } else {
            emptyList()
        }

    /** Custom binds for [distroId] as proot long-form args (`--bind=spec`). */
    fun customBindLongArgs(distroId: String): List<String> =
        customBinds(distroId).map { "--bind=${it.spec()}" }

    /**
     * Distros that are installed on this device — derived from the
     * filesystem rather than persisted state, so it's robust to
     * marker-file drift. A rootfs counts as installed only when it is
     * *complete* (see [isRootfsComplete]): a partial extract left by an
     * interrupted install does not qualify, so the picker offers it for
     * (re)install and [reconcileActiveDistro] won't fall back to it.
     */
    val installedDistros: List<Distro>
        get() = DistroCatalog.all.filter { isRootfsComplete(rootfsDirFor(it.id)) }

    /**
     * Catalog entries the user could install: arch-compatible, not
     * already installed. Used by the distro picker's
     * "+ Add another distro" affordance.
     */
    val availableDistros: List<Distro>
        get() {
            val arch = Arch.current() ?: return emptyList()
            val installed = installedDistros.map { it.id }.toSet()
            return DistroCatalog.all.filter { distro ->
                distro.id !in installed && distro.rootfsSources.containsKey(arch)
            }
        }

    /**
     * Foreign-arch catalog installs the picker can offer (#325 UX): a
     * (distro, arch) pair for every catalog rootfs of a NON-host arch whose
     * qemu-user loader is bundled in this build. Installed through the
     * import path under [foreignDistroId] — which auto-detects the arch and
     * arms qemu at launch — so this is discovery only, no new mechanism.
     */
    val availableForeignDistros: List<Pair<Distro, Arch>>
        get() {
            val host = Arch.current() ?: return emptyList()
            val known = DistroCatalog.all.map { it.id }.toSet()
            return DistroCatalog.builtins.flatMap { distro ->
                distro.rootfsSources.keys
                    .filter { it != host && qemuLoaderAvailable(it) }
                    .filter { foreignDistroId(distro, it) !in known }
                    .map { distro to it }
            }
        }

    /** Import id a foreign-arch catalog install registers under (e.g. "debian-x86_64"). */
    fun foreignDistroId(distro: Distro, arch: Arch): String = "${distro.id}-${arch.slug}"

    /**
     * Resolve the rootfs directory for a specific distro id.
     *
     * For the default Alpine distro, falls back to the legacy
     * `proot/rootfs/alpine/` path if [migrateLegacyAlpineDir] could
     * not perform the rename — keeps existing installs working
     * even when the migration is blocked.
     */
    fun rootfsDirFor(distroId: String): File {
        val expected = File(context.filesDir, "proot/rootfs/$distroId")
        if (distroId == DistroCatalog.DEFAULT_ID && !expected.exists()) {
            val legacy = File(context.filesDir, "proot/rootfs/alpine")
            if (legacy.exists()) return legacy
        }
        return expected
    }

    /** Rootfs directory for the active distro. */
    val activeRootfsDir: File
        get() = rootfsDirFor(activeDistroId)

    /**
     * The rootfs ABI of [distroId] when it is FOREIGN to the host — i.e. its
     * binaries need qemu-user translation — or null for a host-arch rootfs
     * (#325). Read from [ROOTFS_ARCH_MARKER]; an absent/blank/unknown marker,
     * or one that names the host arch, returns null so the qemu path stays off.
     */
    fun foreignRootfsArch(distroId: String): Arch? {
        val abi = runCatching {
            File(rootfsDirFor(distroId), ROOTFS_ARCH_MARKER).readText().trim()
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        val arch = Arch.entries.firstOrNull { it.abi == abi } ?: return null
        return arch.takeIf { it != Arch.current() }
    }

    /**
     * qemu-user loader jniLib basename for [arch]. Underscores, not qemu's
     * canonical `qemu-x86_64` hyphen, so the APK packager reliably extracts
     * the `lib<name>.so` to nativeLibraryDir (cf. `libproot_loader.so`).
     */
    private fun qemuLoaderName(arch: Arch): String = when (arch) {
        Arch.X86_64 -> "qemu_x86_64"
        Arch.AARCH64 -> "qemu_aarch64"
        Arch.ARM -> "qemu_arm"
    }

    /**
     * proot options that route foreign-arch execs through a bundled qemu-user
     * loader (#325). Empty unless [distroId] carries a foreign-arch marker AND
     * the matching loader is bundled at `nativeLibraryDir/lib<qemu-arch>.so`.
     *
     * `--qemu` takes an absolute HOST path — proot resolves it host-side
     * (cli/proot.c `handle_option_q`) and execs it in place of every foreign
     * ELF, so nativeLibraryDir (the one path our uid may exec from — app data
     * is `noexec`) is passed directly; no bind needed. `-q` also auto-binds
     * host `/` at `/host-rootfs` inside the guest, which qemu's own exec needs.
     */
    internal fun qemuUserArgs(distroId: String): List<String> {
        val arch = foreignRootfsArch(distroId) ?: return emptyList()
        val loader = qemuLoaderFile(arch)
        if (!loader.canExecute()) return emptyList()
        return listOf("--qemu=${loader.absolutePath}")
    }

    /**
     * Host directory where proot's link2symlink extension parks hard-link
     * payloads for [distroId] — guest-visible as `/.l2s`.
     *
     * Without it the payload lands next to the link *source*, so deleting
     * the source's directory (a temp dir, most famously Nix/libgit2's
     * tarball-cache flow, discussion #536) destroys data other referents
     * still need, leaving them dangling.
     *
     * Deliberately INSIDE the rootfs: chains must be reachable in both the
     * guest and host namespaces, and the dir is deleted with the distro.
     */
    fun l2sDirFor(distroId: String): File =
        File(rootfsDirFor(distroId), ".l2s").apply { mkdirs() }

    /**
     * The `PROOT_L2S_DIR` env value for [distroId]: the GUEST path of
     * [l2sDirFor] (which this ensures exists host-side). A guest path —
     * not the host path — because the extension writes it into chain
     * symlink *content*, and only guest-absolute content can be
     * dereferenced by proot's in-guest canonicalizer (device-verified:
     * host-absolute symlink targets fail every open() through a stub
     * with ENOENT on Android, while the extension's host-side walks
     * translate guest content back via translate_path).
     */
    fun l2sEnvValue(distroId: String): String {
        l2sDirFor(distroId)
        return "/.l2s"
    }

    private fun qemuLoaderFile(arch: Arch): File =
        File(context.applicationInfo.nativeLibraryDir, "lib${qemuLoaderName(arch)}.so")

    /** True when this build bundles an executable qemu-user loader for [arch]. */
    fun qemuLoaderAvailable(arch: Arch): Boolean = qemuLoaderFile(arch).canExecute()

    /**
     * Host dir for rasterized guest app icons. cacheDir is bound into the guest
     * at `/tmp` (see the proot `--bind` below), so a PNG written here by an
     * in-guest rasterizer is also readable app-side off disk. Used by
     * [GuestAppScanner] to turn SVG `.desktop` icons into decodable PNGs.
     */
    val iconCacheDir: File
        get() = File(context.cacheDir, "guest-app-icons")

    /**
     * Back-compat alias for [activeRootfsDir]. Kept for callers
     * that have not yet migrated to the distro-aware API.
     */
    @Deprecated(
        "Use activeRootfsDir or rootfsDirFor(distroId).",
        ReplaceWith("activeRootfsDir"),
    )
    internal val rootfsDir: File
        get() = activeRootfsDir

    val isRootfsInstalled: Boolean
        get() = isRootfsInstalledFor(activeDistroId)

    /**
     * Whether [distroId]'s rootfs is a *complete* install, independent of
     * the active distro — lets a LOCAL profile target a specific distro's
     * shell (`ConnectionProfile.prootDistroId`) without switching the
     * active one. Requires the [ROOTFS_READY_MARKER] (#262), so a rootfs
     * left half-extracted by an interrupted install reads as not-installed
     * and is re-run rather than treated as ready.
     */
    fun isRootfsInstalledFor(distroId: String): Boolean =
        isRootfsComplete(rootfsDirFor(distroId))

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
        de in installedDesktops &&
            File(activeRootfsDir, de.verifyBinary).exists() &&
            // Self-heal a marked-but-broken desktop (#254): if the marker
            // says installed and verifyBinary exists but a start-command
            // component is missing (a half-completed install), report NOT
            // installed so setupDesktop re-runs the package step and the
            // post-install gate then either repairs it or fails clearly,
            // rather than launching a desktop that renders a blank screen.
            missingDesktopBinaries(activeRootfsDir, de.extraBinaries).isEmpty()

    /** Compat alias — true if any DE is installed. */
    val isDesktopInstalled: Boolean
        get() = hasAnyDesktopInstalled

    /**
     * Legacy desktop-environment enum. Each entry exposes a
     * stable [id] and a parallel [spec] in [DesktopCatalog] — new
     * internals route through the spec (see DesktopManager); UI
     * call-sites still consume the enum until Phase 2 introduces a
     * distro picker.
     */
    enum class DesktopEnvironment(
        val id: String,
        val label: String,
        val packages: String,
        val verifyBinary: String,
        val startCommands: String,
        val sizeEstimate: String,
        /**
         * Binaries beyond [verifyBinary] that this DE's [startCommands]
         * launch and so must exist for it to actually render (#254).
         * Rootfs-relative paths. Checked by the post-install gate so a
         * half-installed desktop fails loudly instead of "installing"
         * and then showing a blank screen. Left empty for DEs whose
         * single [verifyBinary] is a sufficient liveness proxy (e.g. the
         * nested-Wayland compositors, whose render path is shim/wayvnc-
         * dependent rather than a fixed binary set).
         */
        val extraBinaries: List<String> = emptyList(),
        val isWayland: Boolean = false,
        val isNative: Boolean = false,
        /** Hidden DEs are not shown in the Desktop Manager UI. */
        val hidden: Boolean = false,
    ) {
        OPENBOX(
            id = "openbox",
            label = "Openbox (VNC)",
            packages = "tigervnc openbox xterm xsetroot font-noto",
            verifyBinary = "usr/bin/openbox",
            startCommands = "xsetroot -solid '#2e3440'; openbox & xterm &",
            sizeEstimate = "~10MB",
            extraBinaries = listOf("usr/bin/xterm", "usr/bin/xsetroot"),
        ),
        XFCE4(
            id = "xfce4",
            label = "Xfce4 (VNC)",
            packages = "tigervnc xfce4 xfce4-terminal dbus-x11 font-noto",
            verifyBinary = "usr/bin/startxfce4",
            startCommands = "xfwm4 & xfce4-panel & xfdesktop &",
            sizeEstimate = "~100MB",
            extraBinaries = listOf("usr/bin/xfwm4", "usr/bin/xfce4-panel", "usr/bin/xfdesktop"),
        ),
        // User-defined X11 session (#361): startCommands stays empty here —
        // DesktopManager substitutes UserPreferencesRepository.customDesktopCommand
        // at launch. Packages are only the X server + dbus glue + xterm; the
        // user installs their own WM/DE in the distro.
        CUSTOM_X11(
            id = "custom-x11",
            label = "Custom command (X11)",
            packages = "tigervnc dbus-x11 xterm font-noto",
            verifyBinary = "usr/bin/xterm",
            startCommands = "",
            sizeEstimate = "~15MB",
        ),
        WAYLAND_NATIVE(
            id = "labwc-native",
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
        // Native X11 (#268): same JNI labwc bridge + GPU + Xwayland as
        // WAYLAND_NATIVE, but DesktopManager.startNativeCompositor autostarts
        // an X terminal so the user lands in an X11 session — X apps reach the
        // native surface with no VNC. isNative/isWayland match labwc-native so
        // it routes through startNativeCompositor + the DesktopTab.Wayland viewer.
        X11_NATIVE(
            id = "x11-native",
            label = "Native X11 (GPU)",
            packages = "xterm xwayland mesa-dri-gallium mesa-gl mesa-demos " +
                "xkeyboard-config font-noto",
            verifyBinary = "usr/bin/xterm",
            startCommands = "",
            sizeEstimate = "~45MB",
            isWayland = true,
            isNative = true,
        ),

        // Phase 4 nested wlroots compositors — run inside the rootfs on
        // wlroots' headless backend and surface via wayvnc on the same
        // VNC port a Xvnc desktop would. `isWayland = true` so callers
        // that route around the X11 startup script (e.g. setupDesktop's
        // xstartup writer) treat them like the labwc native path. Unlike
        // labwc, these are NOT `isNative` — they live entirely inside
        // the rootfs with no JNI bridge.
        //
        // [packages] mirrors the APK list for backwards-compat with
        // call-sites that still consult the legacy string; the
        // authoritative per-family lists live on the parallel
        // [DesktopEnvironmentSpec.packagesPerFamily].
        SWAY(
            id = "sway",
            label = "Sway (nested Wayland)",
            packages = "sway wayvnc foot xkeyboard-config font-noto",
            verifyBinary = "usr/bin/sway",
            startCommands = "",
            sizeEstimate = "~60MB",
            isWayland = true,
        ),
        HYPRLAND(
            id = "hyprland",
            label = "Hyprland (nested Wayland)",
            packages = "hyprland wayvnc foot xkeyboard-config font-noto",
            verifyBinary = "usr/bin/Hyprland",
            startCommands = "",
            sizeEstimate = "~90MB",
            isWayland = true,
        ),
        NIRI(
            id = "niri",
            label = "Niri (nested Wayland, scrolling tile)",
            packages = "niri wayvnc foot noto-fonts",
            verifyBinary = "usr/bin/niri",
            startCommands = "",
            sizeEstimate = "~70MB",
            isWayland = true,
        ),
        // Cage single-app kiosk — de-risk scaffold for the "app windows"
        // feature. Same nested-Wayland launch path; pins the app to foot
        // for now (compositorCmd = "cage -- foot" in DesktopCatalog.CAGE).
        CAGE(
            id = "cage",
            label = "Cage (single-app kiosk)",
            packages = "cage wayvnc foot noto-fonts",
            verifyBinary = "usr/bin/cage",
            startCommands = "",
            sizeEstimate = "~30MB",
            isWayland = true,
        );

        /**
         * Look up the parallel [DesktopEnvironmentSpec] in
         * [DesktopCatalog]. Each enum [id] is guaranteed to
         * resolve — the catalog is the source of truth.
         */
        val spec: DesktopEnvironmentSpec
            get() = DesktopCatalog.lookup(id)
                ?: error("No spec registered for DE id=$id")
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
            val marker = File(activeRootfsDir, "root/.haven-addons")
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
            val file = File(activeRootfsDir, "root/.haven-vnc-password")
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
            val file = File(activeRootfsDir, "root/.haven-vnc-password")
            if (value != null) {
                file.writeText(CredentialEncryption.encrypt(context, value))
            } else {
                file.delete()
            }
        }

    /** All installed DEs on the active distro — verifyBinary on filesystem. */
    val installedDesktops: Set<DesktopEnvironment>
        get() = installedDesktopsFor(activeDistroId)

    /**
     * Installed DEs for a *specific* distro, detected from that distro's
     * own rootfs rather than the active one. Lets callers report which
     * desktops are installed on every distro without switching the active
     * distro first. Returns empty if the distro's rootfs isn't present.
     */
    fun installedDesktopsFor(distroId: String): Set<DesktopEnvironment> {
        val rootfs = rootfsDirFor(distroId)
        val binSh = File(rootfs, "bin/sh").toPath()
        if (!java.nio.file.Files.exists(binSh, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return emptySet()
        }
        // Prefer Haven's `.haven-desktop` marker — written by [setupDesktop]
        // only after a DE's packages AND config both succeed — over raw
        // verifyBinary existence. apt unpacks a DE's binary partway through a
        // multi-package install, so binary detection alone reports the DE as
        // installed while setupDesktop is still running (and `desktopSetupState`
        // is still "Installing") — the state-reporting lag. Marker absent =
        // legacy rootfs from before this gating; fall back to binary detection.
        val markedNames = File(rootfs, "root/.haven-desktop").takeIf { it.exists() }
            ?.readText()?.lineSequence()
            ?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
        return DesktopEnvironment.entries.filter { de ->
            val binaryPresent = File(rootfs, de.verifyBinary).exists()
            if (markedNames != null) de.name in markedNames && binaryPresent else binaryPresent
        }.toSet()
    }

    /** Compat alias — returns the first installed DE. */
    val installedDesktop: DesktopEnvironment?
        get() = installedDesktops.firstOrNull()

    /**
     * Which phase of the DE install pipeline the state belongs to.
     * Used by [DesktopSetupState.Error] to attribute failures so the
     * UI can distinguish "package install failed" (mirror flake,
     * xbps nested-chroot INSTALL script) from "VNC config failed"
     * (xstartup write, vncpasswd) from "marker file failed".
     */
    enum class DePhase {
        Packages,
        VncConfig,
        Marker,
    }

    sealed class DesktopSetupState {
        data object Idle : DesktopSetupState()
        /**
         * [de] is the desktop this work belongs to, or null when it belongs to
         * none of them (add-on installs). Without it the Manage screen could
         * only ask "is *something* installing", so starting one desktop
         * replaced every other desktop's controls with a spinner — they all
         * looked like they had been started too (#502).
         */
        data class Installing(val step: String, val de: DesktopEnvironment? = null) : DesktopSetupState()
        data object Complete : DesktopSetupState()
        /**
         * Failure attributed to a specific [DePhase]. [logTail] is
         * the last ~1500 chars of the failing command's output. The
         * UI renders [phase] as a chip and [logTail] in a scroll
         * box, so the user/maintainer can see which layer failed
         * without reading source.
         */
        data class Error(
            val phase: DePhase,
            val message: String,
            val logTail: String = "",
        ) : DesktopSetupState()
    }

    private val _desktopState = MutableStateFlow<DesktopSetupState>(DesktopSetupState.Idle)
    val desktopState: StateFlow<DesktopSetupState> = _desktopState.asStateFlow()

    init {
        // Register imported distros (#284) FIRST: reconcileActiveDistro and
        // the ready check below resolve the active distro through
        // DistroCatalog, so a custom distro that was active must be known
        // before they run.
        loadCustomDistros()
        migrateLegacyAlpineDir()
        backfillInstallMarkers()
        reconcileActiveDistro()
        writeDeviceModelInfo()
        _state.value = if (isReady) SetupState.Ready else SetupState.NotInstalled
    }

    /**
     * Seed `/tmp/sysinfo/model` with this device's model so neofetch/fastfetch
     * show a real "Host:" line instead of blank (#304). Every proot launch path
     * binds cacheDir→/tmp, so writing it once here surfaces it in every guest,
     * every distro. neofetch reads /tmp/sysinfo/model as its final get_model
     * fallback — under proot on Android the DMI and devicetree branches it tries
     * first are empty (no DMI) or SELinux-unreadable (devicetree), so this is the
     * branch that actually fires. Best-effort; a failure just leaves Host blank.
     */
    private fun writeDeviceModelInfo() {
        try {
            val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
            val model = Build.MODEL?.trim().orEmpty()
            if (model.isEmpty()) return
            val host = if (manufacturer.isEmpty() || model.startsWith(manufacturer, ignoreCase = true)) {
                model
            } else {
                "$manufacturer $model"
            }
            val dir = File(context.cacheDir, "sysinfo").apply { mkdirs() }
            File(dir, "model").writeText("$host\n")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write device model info: ${e.message}")
        }
    }

    /**
     * proot bind spec that surfaces the seeded device model at the devicetree
     * path fastfetch reads for its "Host:" line (`/sys/firmware/devicetree/base/model`)
     * — or null if [writeDeviceModelInfo] didn't produce the file. neofetch already
     * gets the model from `/tmp/sysinfo/model` directly; this extends coverage to
     * fastfetch, which reads DMI/devicetree only. proot overlays our host file onto
     * the (SELinux-unreadable) real node exactly like the `/proc/.loadavg` binds, so
     * the real node's permissions don't matter. Bind AFTER `/sys` so it shadows it.
     */
    fun deviceModelDevicetreeBind(): String? {
        val f = File(context.cacheDir, "sysinfo/model")
        return if (f.exists()) "${f.absolutePath}:/sys/firmware/devicetree/base/model" else null
    }

    // --- User-imported distros (#284) ---

    /**
     * Load persisted custom distros and register them with [DistroCatalog]
     * so every `lookup`/`all` caller sees them. Tolerant of a malformed
     * entry (skips it) rather than failing the whole manager init.
     */
    private fun loadCustomDistros() {
        val raw = prefs.getString(PREF_CUSTOM_DISTROS, null) ?: return
        val parsed = runCatching {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val family = runCatching { PackageFamily.valueOf(o.optString("family")) }.getOrNull()
                    ?: return@mapNotNull null
                DistroCatalog.customDistro(
                    id = id,
                    label = o.optString("label", id),
                    family = family,
                    sizeMb = o.optInt("sizeMb", 0),
                )
            }
        }.getOrElse {
            Log.w(TAG, "Failed to parse custom distros: ${it.message}")
            emptyList()
        }
        DistroCatalog.registerCustomDistros(parsed)
        if (parsed.isNotEmpty()) Log.d(TAG, "Registered ${parsed.size} custom distro(s): ${parsed.map { it.id }}")
    }

    /** Persist the current custom-distro set and re-register it. */
    private fun persistCustomDistros(distros: List<Distro>) {
        val arr = org.json.JSONArray()
        for (d in distros) {
            arr.put(
                org.json.JSONObject().apply {
                    put("id", d.id)
                    put("label", d.label)
                    put("family", d.family.name)
                    put("sizeMb", d.sizeEstimateMb)
                },
            )
        }
        prefs.edit().putString(PREF_CUSTOM_DISTROS, arr.toString()).apply()
        DistroCatalog.registerCustomDistros(distros)
    }

    /** Custom (user-imported) distros currently registered. */
    private val customDistros: List<Distro>
        get() = DistroCatalog.all.filter { !DistroCatalog.isBuiltin(it.id) }

    /**
     * Import an already-built rootfs tarball as a new distro (#284) — the
     * "bring your own rootfs" path. [source] is an http(s) URL or a local
     * file path. The tarball is extracted to `proot/rootfs/<id>` in **raw
     * mode** (no baseline packages, no distro hooks): the rootfs is used
     * exactly as shipped. [family] still routes PackageOps so later package
     * installs work.
     *
     * Drives the same [_state] progress (Downloading → Extracting → Ready /
     * Error) as [installRootfs], so the existing Manage UI and
     * `inspect_proot.osSetupState` MCP polling cover imports unchanged.
     *
     * [expectedSha256] is optional — verified when given (the user can pin
     * a published checksum), skipped otherwise (it's the user's own rootfs).
     * [stripComponents] defaults to auto: extract is retried with strip=1 if
     * the tarball turns out to wrap its files in a single top-level dir
     * (the proot-distro convention).
     */
    suspend fun importRootfs(
        id: String,
        label: String,
        family: PackageFamily,
        source: String,
        format: RootfsFormat,
        stripComponents: Int = 0,
        expectedSha256: String? = null,
    ) {
        val slug = id.trim()
        require(slug.matches(Regex("[a-z0-9][a-z0-9._-]*"))) {
            "Distro id must be a slug (lowercase letters, digits, ., _, -): got '$id'"
        }
        require(!DistroCatalog.isBuiltin(slug)) { "'$slug' is a built-in distro id — pick another" }

        val installStartedAt = System.currentTimeMillis()
        var currentPhase: Phase = Phase.RootfsDownload
        try {
            _state.value = SetupState.Downloading(0)
            installLogRepository.logEvent(distroId = slug, phase = "RootfsDownload", message = "Import: $source")

            // Obtain the tarball: download a URL, or use a local file as-is.
            // See [ImportSource] for why file:// and content:// are handled
            // rather than falling through to File(source) (#560).
            val tarball: File
            val resolved = ImportSource.of(source)
            if (resolved is ImportSource.Unsupported) {
                throw IllegalArgumentException(resolved.reason)
            }
            val isUrl = resolved is ImportSource.Remote
            if (isUrl) {
                tarball = File(context.cacheDir, "$slug-import.tar")
                withContext(Dispatchers.IO) {
                    try {
                        downloadViaPlatform(source, tarball) { pct -> _state.value = SetupState.Downloading(pct) }
                    } catch (e: java.io.IOException) {
                        // A user-typed http:// mirror (e.g. a self-hosted LAN
                        // rootfs server) is blocked by Haven's app-wide
                        // cleartext policy (#284) — the declarative
                        // network-security-config can only allowlist literal
                        // domains, not arbitrary private-network IPs. This
                        // ONE explicit, user-typed URL is the informed
                        // consent; fall back to a raw-socket GET, which isn't
                        // intercepted by the platform's cleartext check
                        // (that check lives in HttpURLConnection/OkHttp, not
                        // java.net.Socket). Never used for https:// — those
                        // don't hit this exception.
                        if (source.startsWith("http://") && e.message?.contains("Cleartext", ignoreCase = true) == true) {
                            Log.i(TAG, "[import $slug] cleartext blocked by platform policy, retrying via raw socket")
                            downloadCleartextFallback(source, tarball) { pct -> _state.value = SetupState.Downloading(pct) }
                        } else {
                            throw e
                        }
                    }
                }
            } else {
                val path = (resolved as ImportSource.LocalFile).path
                tarball = File(path)
                if (!tarball.isFile) throw IllegalArgumentException("Local rootfs not found: $path")
            }

            if (expectedSha256 != null) {
                withContext(Dispatchers.IO) {
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    tarball.inputStream().buffered().use { input ->
                        val buf = ByteArray(8192)
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) digest.update(buf, 0, n)
                    }
                    val actual = digest.digest().joinToString("") { "%02x".format(it) }
                    if (!actual.equals(expectedSha256.trim(), ignoreCase = true)) {
                        if (isUrl) tarball.delete()
                        throw SecurityException("Rootfs checksum mismatch — expected $expectedSha256 but got $actual.")
                    }
                }
            }

            currentPhase = Phase.RootfsExtract
            _state.value = SetupState.Extracting
            val targetDir = File(context.filesDir, "proot/rootfs/$slug")
            withContext(Dispatchers.IO) {
                File(targetDir, ROOTFS_READY_MARKER).delete()
                // Try the requested strip first; on a bin/sh-not-found failure
                // with strip=0, retry with strip=1 (proot-distro tarballs wrap
                // the rootfs in a single top-level dir). Saves the user from
                // having to know their tarball's layout.
                try {
                    extractTarball(tarball, targetDir, RootfsSource(url = "", sha256 = "", format = format, stripComponents = stripComponents))
                } catch (e: RuntimeException) {
                    if (stripComponents == 0 && (e.message?.contains("bin/sh not found") == true)) {
                        Log.d(TAG, "[import $slug] strip=0 had no bin/sh — retrying with strip=1")
                        forceDeleteRecursively(targetDir)
                        extractTarball(tarball, targetDir, RootfsSource(url = "", sha256 = "", format = format, stripComponents = 1))
                    } else {
                        throw e
                    }
                }
                if (isUrl) tarball.delete()

                applyResolvConf(targetDir)
                seedRootHome(targetDir)

                // #325: record the rootfs ELF arch so a foreign-arch import
                // launches through the bundled qemu-user loader. Best-effort:
                // no marker (or a host-arch value) keeps the qemu path inert.
                detectRootfsArch(targetDir)?.let { arch ->
                    runCatching { File(targetDir, ROOTFS_ARCH_MARKER).writeText("${arch.abi}\n") }
                    if (arch != Arch.current()) {
                        Log.i(TAG, "[import $slug] foreign-arch rootfs (${arch.abi}) — will run under qemu-user")
                        installLogRepository.logEvent(
                            distroId = slug, phase = "RootfsExtract",
                            message = "Foreign-arch rootfs (${arch.abi}): runs under qemu-user emulation",
                        )
                    }
                }
            }

            // Register the distro and write the completion marker only now
            // that extract succeeded (#262). Size = the extracted tree's size.
            val sizeMb = runCatching { (folderSize(targetDir) / (1024 * 1024)).toInt() }.getOrDefault(0)
            val distro = DistroCatalog.customDistro(slug, label.ifBlank { slug }, family, sizeMb)
            persistCustomDistros((customDistros.filter { it.id != slug }) + distro)
            runCatching { File(targetDir, ROOTFS_READY_MARKER).writeText("ready\n") }
                .onFailure { Log.w(TAG, "import marker write failed for $slug: ${it.message}") }

            setActiveDistroId(slug)
            _state.value = SetupState.Ready
            installLogRepository.logEvent(
                distroId = slug, phase = "Ready", exit = 0, ok = true,
                message = "Imported rootfs ($sizeMb MB, ${System.currentTimeMillis() - installStartedAt}ms)",
            )
            Log.d(TAG, "[import $slug] complete ($sizeMb MB)")
        } catch (e: Exception) {
            Log.e(TAG, "[import $currentPhase] failed", e)
            installLogRepository.logEvent(distroId = slug, phase = currentPhase.name, ok = false, message = e.message ?: "Import failed")
            _state.value = SetupState.Error(phase = currentPhase, message = e.message ?: "Import failed", logTail = "")
        }
    }

    private fun folderSize(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()

    /** [Arch] from an ELF header's e_machine, or null if not a known ELF. */
    private fun elfArch(file: File): Arch? {
        val b = ByteArray(20)
        val n = runCatching { file.inputStream().use { it.read(b) } }.getOrDefault(-1)
        if (n < 20 || b[0] != 0x7F.toByte() || b[1] != 'E'.code.toByte() ||
            b[2] != 'L'.code.toByte() || b[3] != 'F'.code.toByte()
        ) return null
        // e_machine, little-endian u16 at offset 18 (all supported arches are LE).
        return when ((b[19].toInt() and 0xFF shl 8) or (b[18].toInt() and 0xFF)) {
            0x3E -> Arch.X86_64
            0xB7 -> Arch.AARCH64
            0x28 -> Arch.ARM
            else -> null
        }
    }

    /**
     * Best-effort ELF arch of an extracted rootfs (#325), from the first
     * recognisable ELF among a few well-known binaries. Symlinks (Alpine's
     * `bin/sh` → `/bin/busybox`) are resolved WITHIN the rootfs — an absolute
     * target must not escape to the host filesystem. Null when nothing
     * readable is found; callers treat that as "assume host arch".
     */
    internal fun detectRootfsArch(rootfsDir: File): Arch? {
        for (rel in listOf("bin/busybox", "usr/bin/env", "bin/ls", "bin/sh")) {
            var f = File(rootfsDir, rel)
            var hops = 0
            while (hops++ < 4 && java.nio.file.Files.isSymbolicLink(f.toPath())) {
                val target = java.nio.file.Files.readSymbolicLink(f.toPath()).toString()
                f = if (target.startsWith("/")) File(rootfsDir, target.trimStart('/'))
                else File(f.parentFile, target)
            }
            if (f.isFile) elfArch(f)?.let { return it }
        }
        return null
    }

    /**
     * One-shot backfill for installs predating the rootfs completion
     * marker (#262). Before the marker, a rootfs counted as installed the
     * instant `bin/sh` appeared, so an extract interrupted partway (app
     * backgrounded / process killed mid-install) left a partial tree that
     * read as complete forever. Now [installRootfs] writes
     * [ROOTFS_READY_MARKER] only after extract + hooks + baseline all
     * succeed, and [isRootfsComplete] requires it.
     *
     * This runs exactly once (guarded by [PREF_MARKERS_BACKFILLED]): any
     * rootfs already on disk at upgrade time is assumed complete and gets
     * a marker. After that, a missing marker reliably means "partial /
     * never finished", because only the success path writes it.
     */
    private fun backfillInstallMarkers() {
        if (prefs.getBoolean(PREF_MARKERS_BACKFILLED, false)) return
        for (distro in DistroCatalog.all) {
            val dir = rootfsDirFor(distro.id)
            if (rootfsFilesPresent(dir) && !File(dir, ROOTFS_READY_MARKER).exists()) {
                runCatching { File(dir, ROOTFS_READY_MARKER).writeText("legacy\n") }
                    .onFailure { Log.w(TAG, "Backfill marker failed for ${distro.id}: ${it.message}") }
            }
        }
        prefs.edit().putBoolean(PREF_MARKERS_BACKFILLED, true).apply()
    }

    /**
     * If the persisted active distro id points at a rootfs that
     * isn't installed (e.g. a previous addDistro failed mid-flight
     * and the revert-to-previous didn't fire), fall back to the
     * first installed distro — or to the default if none are
     * installed. Keeps users out of an "active but missing" hole
     * where the local shell silently falls back to Android shell.
     */
    private fun reconcileActiveDistro() {
        val current = _activeDistroId.value
        val currentInstalled = installedDistros.any { it.id == current }
        if (currentInstalled) return
        val fallback = installedDistros.firstOrNull()?.id ?: DistroCatalog.DEFAULT_ID
        if (fallback != current) {
            prefs.edit().putString(PREF_ACTIVE_DISTRO_ID, fallback).apply()
            _activeDistroId.value = fallback
            Log.d(TAG, "Active distro $current has no rootfs; falling back to $fallback")
        }
    }

    /**
     * One-shot migration for installs predating issue #162: rename
     * `proot/rootfs/alpine/ → proot/rootfs/alpine-3.21/` so the
     * directory layout matches the new distro-id scheme. No-op if
     * the legacy directory doesn't exist, or if the target already
     * exists, or if the legacy directory isn't a real rootfs
     * (bin/sh missing). If the rename fails for any reason the
     * fallback in [rootfsDirFor] keeps the legacy path usable.
     */
    private fun migrateLegacyAlpineDir() {
        val legacy = File(context.filesDir, "proot/rootfs/alpine")
        val target = File(context.filesDir, "proot/rootfs/${DistroCatalog.DEFAULT_ID}")
        if (!legacy.exists() || target.exists()) return
        val binSh = File(legacy, "bin/sh").toPath()
        if (!java.nio.file.Files.exists(binSh, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        try {
            target.parentFile?.mkdirs()
            if (legacy.renameTo(target)) {
                Log.d(TAG, "Migrated legacy rootfs: alpine/ → ${DistroCatalog.DEFAULT_ID}/")
            } else {
                Log.w(TAG, "Legacy rootfs rename failed; falling back to alpine/ path")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Legacy rootfs migration failed: ${e.message}")
        }
    }

    /**
     * Download and extract the rootfs for the active distro.
     * Safe to call if already installed — returns immediately.
     */
    suspend fun installRootfs() {
        if (isRootfsInstalled) {
            _state.value = SetupState.Ready
            return
        }

        // Track which phase is active so the outer catch can attribute
        // any thrown exception to the right step. Mutates as we move
        // through download → extract → hooks → baseline.
        var currentPhase: Phase = Phase.RootfsDownload
        val installStartedAt = System.currentTimeMillis()
        try {
            _state.value = SetupState.Downloading(0)

            val distro = activeDistro
            installLogRepository.logEvent(
                distroId = distro.id,
                phase = "RootfsDownload",
                message = "Starting download",
            )
            val arch = Arch.current()
                ?: throw IllegalStateException("Unsupported ABI: ${android.os.Build.SUPPORTED_ABIS.toList()}")
            val source = distro.rootfsSources[arch]
                ?: throw IllegalStateException(
                    "${distro.label} has no rootfs for $arch — supported: ${distro.rootfsSources.keys}"
                )
            val url = source.url
            val expectedSha256 = source.sha256
            val tarball = File(context.cacheDir, "${distro.id}-rootfs.tar")

            // Download
            withContext(Dispatchers.IO) {
                Log.d(TAG, "[download ${distro.id}] start url=$url")
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
                Log.d(TAG, "[download ${distro.id}] done bytes=${tarball.length()}")
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
                Log.d(TAG, "[download ${distro.id}] sha256-ok $actualSha256")
            }

            installLogRepository.logEvent(
                distroId = distro.id,
                phase = "RootfsDownload",
                exit = 0,
                ok = true,
                message = "Download + sha256 OK (${tarball.length()} bytes)",
            )

            // Extract
            currentPhase = Phase.RootfsExtract
            _state.value = SetupState.Extracting
            installLogRepository.logEvent(
                distroId = distro.id,
                phase = "RootfsExtract",
                message = "Starting extract",
            )
            val targetDir = File(context.filesDir, "proot/rootfs/${distro.id}")
            withContext(Dispatchers.IO) {
                // Clear any stale completion marker before (re-)extracting,
                // so the rootfs can't read as complete until this install
                // finishes (#262).
                File(targetDir, ROOTFS_READY_MARKER).delete()
                Log.d(TAG, "[extract ${distro.id}] start")
                extractTarball(tarball, targetDir, source)
                tarball.delete()
                Log.d(TAG, "[extract ${distro.id}] done dir=${targetDir.absolutePath}")

                // Android has no /etc/resolv.conf, so write one from the user's DNS
                // setting (#446) rather than hardcoding public resolvers.
                val dnsServers = applyResolvConf(targetDir)
                Log.d(TAG, "[extract ${distro.id}] wrote resolv.conf: $dnsServers")

                // Drop a generic shell profile and a welcome README into
                // /root/. These are vendor-neutral — they explain what the
                // environment is, how to install tools, and how the "phone
                // is the point of presence, remote machines are the compute"
                // pattern composes with Haven's SSH primitives. No vendor
                // CLIs, no cloned repositories, no hardcoded hosts.
                seedRootHome(targetDir)
            }

            // Run distro-specific post-extract hooks before baseline:
            // locale-gen on Arch, ca-cert refresh on Void, etc. The
            // state.value advance to Initializing so the UI doesn't
            // keep saying "Extracting rootfs…" through a multi-minute
            // setup phase that has nothing to do with extraction.
            //
            // Hooks are now HARD-FAIL: a non-zero exit aborts the
            // install with Phase.BootstrapHook attribution. Previous
            // behaviour was silent-continue, which hid Void's three-
            // step xbps bootstrap fragility until a much later DE
            // install failed with a downstream symptom. The user
            // sees the hook id (e.g. "void-xbps-bootstrap") in the
            // error chip and the log tail of the failing script.
            installLogRepository.logEvent(
                distroId = distro.id,
                phase = "RootfsExtract",
                exit = 0,
                ok = true,
                message = "Extracted to ${targetDir.absolutePath}",
            )

            // Post-extract setup (hooks + baseline) — factored out so
            // `retry()` can re-run just these phases when the user
            // hits Retry on a BootstrapHook or Baseline error chip.
            // Hooks are required to be idempotent (RootfsHook.idempotent
            // defaults to true; documented contract). On hook failure,
            // SetupState.Error is set and we return early.
            if (!runPostExtractSetup(distro, installStartedAt)) return
        } catch (e: Exception) {
            Log.e(TAG, "[install ${currentPhase}] failed", e)
            installLogRepository.logEvent(
                distroId = activeDistro.id,
                phase = currentPhase.name,
                ok = false,
                message = e.message ?: "Installation failed",
            )
            _state.value = SetupState.Error(
                phase = currentPhase,
                message = e.message ?: "Installation failed",
                logTail = "",
            )
        }
    }

    /**
     * Run all post-extract bootstrap hooks, then the baseline package
     * install. Sets [_state] to [SetupState.Ready] on success, or
     * [SetupState.Error] on hook failure. Returns true if everything
     * succeeded, false if any hook failed (caller in [installRootfs]
     * uses this to return early so the outer catch doesn't fire).
     *
     * Idempotent contract: every hook is required to be safe to re-
     * run, so [retry] can call this method again after a failure
     * without wiping the rootfs. Hooks that aren't idempotent should
     * set [sh.haven.core.local.proot.RootfsHook.idempotent] = false
     * and the UI's retry path will offer Wipe & retry instead.
     */
    private suspend fun runPostExtractSetup(distro: Distro, installStartedAt: Long): Boolean {
        // Apply the selected package-mirror region BEFORE hooks + baseline
        // so every network fetch in this install (and later DE installs)
        // uses the chosen mirror. No-op when region is DEFAULT or the
        // distro has no mirror config. (#263 / issue #162.)
        val rewritten = MirrorCatalog.apply(rootfsDirFor(distro.id), distro.id, _mirrorRegion.value)
        if (rewritten.isNotEmpty()) {
            Log.d(TAG, "[mirror ${distro.id}] region=${_mirrorRegion.value} rewrote $rewritten")
        }

        for (hook in distro.postExtractHooks) {
            _state.value = SetupState.Initializing(hook.id)
            Log.d(TAG, "[hook ${hook.id}] start")
            installLogRepository.logEvent(
                distroId = distro.id,
                phase = "BootstrapHook:${hook.id}",
                message = "Starting hook",
            )
            val (output, code) = runCommandInProot(hook.command)
            if (code == 0) {
                Log.d(TAG, "[hook ${hook.id}] ok bytes=${output.length}")
                installLogRepository.logEvent(
                    distroId = distro.id,
                    phase = "BootstrapHook:${hook.id}",
                    exit = 0,
                    ok = true,
                )
            } else {
                Log.w(TAG, "[hook ${hook.id}] exit=$code tail=${output.takeLast(1500)}")
                installLogRepository.logEvent(
                    distroId = distro.id,
                    phase = "BootstrapHook:${hook.id}",
                    exit = code,
                    ok = false,
                    message = "Hook failed (exit $code)",
                    logTail = output.takeLast(1500),
                )
                _state.value = SetupState.Error(
                    phase = Phase.BootstrapHook,
                    message = "Bootstrap hook '${hook.id}' failed (exit $code). " +
                        "This is usually a transient network issue (mirror down, " +
                        "stale package DB) — tap Retry. If it persists, the distro " +
                        "tarball may need a rebuild.",
                    logTail = output.takeLast(1500),
                )
                return false
            }
        }

        // Install a minimal baseline of packages so the environment is
        // immediately usable: bash (readline + .inputrc), curl +
        // ca-certificates (for the user to follow install instructions
        // over HTTPS), and openssh-client (for the remote-compute
        // composition pattern documented in /root/README.md). Best-
        // effort — if network is unavailable the rootfs is still fully
        // usable with busybox and the user can run `apk add` later.
        _state.value = SetupState.Initializing("baseline packages")
        installLogRepository.logEvent(
            distroId = distro.id,
            phase = "Baseline",
            message = "Installing baseline packages: ${distro.baselinePackages.joinToString(" ")}",
        )
        installBaseline()

        // Commit the install: write the completion marker only now that
        // extract + hooks + baseline have all succeeded. isRootfsComplete
        // requires this, so an install interrupted before this point reads
        // as not-installed and re-runs cleanly instead of being mistaken
        // for ready (#262).
        runCatching { File(rootfsDirFor(distro.id), ROOTFS_READY_MARKER).writeText("ready\n") }
            .onFailure { Log.w(TAG, "Failed to write rootfs ready marker for ${distro.id}: ${it.message}") }

        _state.value = SetupState.Ready
        installLogRepository.logEvent(
            distroId = distro.id,
            phase = "Ready",
            exit = 0,
            ok = true,
            message = "Rootfs install complete (${System.currentTimeMillis() - installStartedAt}ms total)",
        )
        return true
    }

    /**
     * Retry the failed phase of the most recent install attempt.
     *
     * Behaviour by phase:
     *  - [Phase.RootfsDownload], [Phase.RootfsExtract] — wipes the
     *    partial rootfs (the extract may have left a half-state) then
     *    re-runs the full install. Network-side retries can land in
     *    either bucket; in both cases the safest move is to start over
     *    from the tarball.
     *  - [Phase.BootstrapHook], [Phase.Baseline] — the rootfs is on
     *    disk, hooks are idempotent (see [RootfsHook.idempotent] —
     *    every shipped hook today qualifies). Re-runs hooks + baseline
     *    without touching the rootfs files.
     *
     * No-op if current state isn't Error (already Ready, or in-flight).
     * Issue #162 Phase 3d.
     */
    suspend fun retry() {
        val err = _state.value as? SetupState.Error ?: return
        val distro = activeDistro
        when (err.phase) {
            Phase.RootfsDownload, Phase.RootfsExtract -> {
                Log.d(TAG, "[retry] wipe + reinstall for phase=${err.phase}")
                deleteDistro(distro.id)
                setActiveDistroId(distro.id)
                installRootfs()
            }
            Phase.BootstrapHook, Phase.Baseline -> {
                // Files-present (not isRootfsComplete): a hook/baseline
                // failure leaves the extracted rootfs on disk but no
                // completion marker, so re-run just the idempotent
                // post-extract setup, which writes the marker on success.
                if (!rootfsFilesPresent(activeRootfsDir)) {
                    Log.d(TAG, "[retry] rootfs missing; falling through to full install")
                    installRootfs()
                    return
                }
                Log.d(TAG, "[retry] re-running post-extract setup for phase=${err.phase}")
                val started = System.currentTimeMillis()
                runPostExtractSetup(distro, started)
            }
        }
    }

    /**
     * Extract a tar.gz / tar.xz file to a directory using Java
     * streams. Implements minimal POSIX tar parsing (512-byte
     * headers, ustar format) and honours [RootfsSource.format] +
     * [RootfsSource.stripComponents].
     *
     * stripComponents removes the leading N path components from
     * each entry name, matching `tar --strip-components=N`. Used
     * for tarballs that wrap the rootfs in a top-level directory
     * (e.g. proot-distro's `debian-bookworm-aarch64/`).
     */
    // internal for the #546 importer regression test (same module).
    internal fun extractTarball(tarball: File, destDir: File, source: RootfsSource) {
        destDir.mkdirs()
        var fileCount = 0
        var symlinkCount = 0
        // Directory modes are applied AFTER all files are written (deferred),
        // so a read-only dir in the tar can't block writing its contents. (#328)
        val deferredDirModes = ArrayList<Pair<File, Int>>()

        val rawInput = tarball.inputStream().buffered()
        val decompressed: java.io.InputStream = when (source.format) {
            RootfsFormat.TAR_GZ -> java.util.zip.GZIPInputStream(rawInput)
            RootfsFormat.TAR_XZ -> org.tukaani.xz.XZInputStream(rawInput)
            RootfsFormat.TAR_ZSTD -> error(
                "TAR_ZSTD support not yet wired — add a zstd decoder dep to core/local first.",
            )
        }
        decompressed.use { gzIn ->
            val header = ByteArray(512)
            var pendingLongName: String? = null
            var pendingLongLink: String? = null

            while (true) {
                val headerRead = readFully(gzIn, header)
                if (headerRead < 512) break
                if (header.all { it == 0.toByte() }) break

                val name = extractString(header, 0, 100)
                if (name.isEmpty() && pendingLongName == null) break

                val modeStr = extractString(header, 100, 8)
                val sizeStr = extractString(header, 124, 12)
                val typeFlag = header[156]

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
                // GNU long LINK target: type 'K' — same shape as 'L' but for
                // the next entry's symlink/hardlink target. Without this a
                // >100-char target is silently TRUNCATED to the 100-byte
                // header field — e.g. every Termux proot-distro absolute link
                // path (/data/data/com.termux/.../installed-rootfs/...),
                // which is how #328's l2s chains dodged the import flattening.
                if (typeFlag == 'K'.code.toByte()) {
                    val linkBytes = ByteArray(size.toInt())
                    readFully(gzIn, linkBytes)
                    skipToBlock(gzIn, size)
                    pendingLongLink = String(linkBytes).trimEnd('\u0000')
                    continue // next header is the actual entry
                }

                val linkTarget = pendingLongLink ?: extractString(header, 157, 100)
                pendingLongLink = null

                // Resolve final name
                val rawEntryName = pendingLongName ?: run {
                    val prefix = extractString(header, 345, 155)
                    if (prefix.isNotEmpty()) "$prefix/$name" else name
                }
                pendingLongName = null

                // Some producers write directory entries as `dir/.` (and
                // even regular-file entries that can only be directories).
                // Writing through such a name resolves to the existing
                // directory and dies with EISDIR mid-import (#546) —
                // collapse the trailing `/` or `/.` here, before
                // strip-components or anything touches the path.
                val (collapsedName, nameMarksDir) = TarEntryNames.normalize(rawEntryName)

                // Apply tar --strip-components=N: drop the first N
                // path components. If the entry has fewer than N
                // components left after stripping it's a no-op (the
                // wrapper-dir itself becomes the empty string, which
                // we skip).
                val entryName = if (source.stripComponents > 0) {
                    val parts = collapsedName.trimEnd('/').split('/').drop(source.stripComponents)
                    if (parts.isEmpty()) {
                        // Wrapper directory itself — skip header data
                        if (size > 0) skipToBlock(gzIn, size)
                        continue
                    }
                    parts.joinToString("/")
                } else {
                    collapsedName
                }
                if (entryName.isEmpty()) {
                    if (size > 0) skipToBlock(gzIn, size)
                    continue
                }

                val outFile = File(destDir, entryName)
                // Zip-slip guard: reject any entry whose resolved path escapes
                // the install dir — via `..` traversal or by writing through a
                // symlink that points outside. A malicious imported rootfs could
                // otherwise clobber Haven's own files. (security-review #21)
                if (!isWithinDir(destDir, outFile)) {
                    Log.w(TAG, "Rejecting rootfs tar entry escaping the install dir: $entryName")
                    if (size > 0) skipToBlock(gzIn, size)
                    continue
                }
                val entryIsDir = typeFlag == '5'.code.toByte() || nameMarksDir
                clearPathIfWrongType(outFile, entryIsDir = entryIsDir)

                when {
                    entryIsDir -> {
                        // A trailing `/` or `/.` marks a directory even when
                        // the typeflag says regular file (#546). Consume the
                        // entry data: normally 0, but a producer that sets a
                        // size on a dir entry would otherwise leave it to be
                        // misread as the next header.
                        outFile.mkdirs()
                        modeStr.trim().toIntOrNull(8)?.let {
                            deferredDirModes.add(outFile to (it and 0xFFF))
                        }
                        if (size > 0) skipToBlock(gzIn, size)
                    }
                    typeFlag == '2'.code.toByte() -> {
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
                    typeFlag == '1'.code.toByte() -> {
                        // Hard link — copy the target file. The tar's link target
                        // is an unstripped archive path, so apply the same
                        // --strip-components as entry names; without this every
                        // hard link in a wrapped (stripComponents=1) tarball —
                        // i.e. a typical proot-distro import — pointed at a
                        // nonexistent path and the file was SILENTLY MISSING
                        // from the rootfs. (#328)
                        outFile.parentFile?.mkdirs()
                        try {
                            val strippedTarget = resolveTarLinkTarget(linkTarget, source.stripComponents)
                            val targetFile = File(destDir, strippedTarget)
                            if (targetFile.exists()) {
                                targetFile.copyTo(outFile, overwrite = true)
                                // Restore the entry's mode — copyTo leaves the
                                // app-umask 0600, dropping exec bits from linked
                                // binaries. Same restore as the regular-file
                                // branch. (#328)
                                modeStr.trim().toIntOrNull(8)?.let { m ->
                                    try {
                                        android.system.Os.chmod(outFile.absolutePath, m and 0xFFF)
                                    } catch (_: Exception) {}
                                }
                                fileCount++
                            } else {
                                Log.w(TAG, "Hard link target missing: $entryName -> $strippedTarget")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Hard link failed: $entryName -> $linkTarget: ${e.message}")
                        }
                    }
                    typeFlag == '0'.code.toByte() || typeFlag == 0.toByte() -> {
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
                        // Restore the tar entry's mode exactly. The Android app
                        // process umask is 0077, so FileOutputStream would leave
                        // every file 0600 regardless of the tar — under-
                        // permissioning the rootfs (e.g. dpkg failing to back up
                        // files in /var/lib/dpkg). chmod works because the app
                        // owns the file. (#328)
                        modeStr.trim().toIntOrNull(8)?.let { m ->
                            try {
                                android.system.Os.chmod(outFile.absolutePath, m and 0xFFF)
                            } catch (_: Exception) {}
                        }
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
            }
        }

        // Apply directory modes now that all files are written. Deepest-first is
        // safe (chmod needs only ownership, not parent write). Without this dirs
        // stay at the app umask (0700) instead of the tar's mode (e.g. 0755),
        // breaking tools that rely on 0755 traversal/perms. (#328)
        //
        // Owner rwx is always OR'd in: the app uid must be able to operate
        // inside every rootfs dir regardless of what the tar says — Alpine's
        // minirootfs ships `proc/` as 0555, which broke proot's fake-/proc
        // fabrication (`proc/.loadavg` EACCES) on devices where SELinux hides
        // the real /proc/loadavg (#325 import testing). Guest-visible modes
        // are unaffected in practice: proot fake-root reports what matters,
        // and /proc is bind-mounted over anyway.
        for ((dir, mode) in deferredDirModes.asReversed()) {
            try { android.system.Os.chmod(dir.absolutePath, (mode or 0b111_000_000)) } catch (_: Exception) {}
        }

        Log.d(TAG, "Extracted $fileCount files, $symlinkCount symlinks to ${destDir.absolutePath}")

        val l2sFixed = flattenL2sLinks(destDir)
        if (l2sFixed > 0) {
            Log.i(TAG, "Flattened $l2sFixed link2symlink artifacts from a proot-built rootfs (#328)")
        }

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

        // /usr/local/bin/fakeroot — PATH shim preferring fakeroot-tcp: the
        // distro default (sysv) needs SysV IPC, which Android lacks (#375).
        val localBin = File(rootfsDir, "usr/local/bin").apply { mkdirs() }
        val fakerootShim = File(localBin, "fakeroot")
        copyAssetIfAbsent(
            "proot/usr/local/bin/fakeroot",
            fakerootShim,
            "/usr/local/bin/fakeroot",
        )
        if (fakerootShim.exists()) {
            try {
                android.system.Os.chmod(fakerootShim.absolutePath, 0b111_101_101) // 0755
            } catch (_: Throwable) {
                fakerootShim.setExecutable(true, false)
            }
        }
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
     *
     * tmux is the one exception: when the user picks a session manager
     * in Settings (default NONE), the local-session launcher needs the
     * binary to be present, otherwise it falls back to a plain shell
     * and the "survives Haven restarts" promise is silently broken.
     * tmux is ~250KB, so the cost of bundling it is negligible.
     */
    private suspend fun installBaseline() {
        val distro = activeDistro
        try {
            val ops = PackageOps.forFamily(distro.family)
            val install = ops.installCmd(distro.baselinePackages)
            // Don't pre-truncate with `| tail -N` — that hides the
            // actual error line on a failed install. Kotlin-side
            // takeLast() keeps the log size bounded.
            val (output, code) = runCommandInProot(
                "${ops.updateCmd()} >/dev/null 2>&1 && $install 2>&1",
            )
            if (code == 0) {
                Log.d(TAG, "[baseline ${distro.id}] ok pkgs=${distro.baselinePackages.size}")
                installLogRepository.logEvent(
                    distroId = distro.id,
                    phase = "Baseline",
                    exit = 0,
                    ok = true,
                    message = "Installed ${distro.baselinePackages.size} packages",
                )
            } else {
                Log.w(TAG, "[baseline ${distro.id}] exit=$code tail=${output.takeLast(1500)}")
                installLogRepository.logEvent(
                    distroId = distro.id,
                    phase = "Baseline",
                    exit = code,
                    ok = false,
                    message = "Baseline install failed (exit $code) — non-fatal, rootfs is usable",
                    logTail = output.takeLast(1500),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "[baseline ${distro.id}] threw (non-fatal): ${e.message}")
            installLogRepository.logEvent(
                distroId = distro.id,
                phase = "Baseline",
                ok = false,
                message = "Baseline install threw: ${e.message}",
            )
        }
    }

    /**
     * Run a command inside the PRoot rootfs (non-interactive).
     * Returns (stdout+stderr, exitCode).
     */
    suspend fun runCommandInProot(command: String, timeoutMs: Long? = null): Pair<String, Int> =
        withContext(Dispatchers.IO) {
            val process = startCommandInProot(command)
            if (timeoutMs == null) {
                // Unbounded, as every caller but one still is. Bounding a
                // package install would turn a slow network into a failure,
                // which is a worse trade than waiting; #503 is only fixed for
                // the step that is already best-effort.
                val output = process.inputStream.bufferedReader().readText()
                Pair(output, process.waitFor())
            } else {
                val run = readProcessBounded(process, timeoutMs)
                if (run.timedOut) {
                    // Program name only. Which command hung is the diagnostic; its
                    // arguments are where the secrets are — `mysql -p…`, an auth
                    // header, a token in a URL (#518).
                    Log.w(
                        TAG,
                        "Guest command exceeded ${timeoutMs}ms and was killed: " +
                            "${command.substringBefore(' ')} (${command.length} chars)",
                    )
                }
                Pair(run.output, run.exitCode)
            }
        }

    /**
     * Ensure a writable host dir to overlay at the guest's `/dev/shm`. Android's
     * `/dev` (bound into the guest) is read-only and has no `shm`, so POSIX
     * shared memory (`shm_open`) fails for Mono/.NET, Chromium, PostgreSQL, etc.
     * cacheDir-backed and world-rwx so the fake-root guest process can use it.
     * Returns the absolute host path to bind at `/dev/shm`.
     */
    fun ensureDevShm(): String {
        val d = File(context.cacheDir, "devshm")
        if (!d.exists()) d.mkdirs()
        d.setReadable(true, false)
        d.setWritable(true, false)
        d.setExecutable(true, false)
        return d.absolutePath
    }

    /**
     * Bind value that masks the guest's `/sys/fs/selinux` with an empty dir,
     * i.e. `"<host>:/sys/fs/selinux"`.
     *
     * Android runs SELinux **enforcing**, so binding the real `/sys` into the
     * guest exposes `/sys/fs/selinux/enforce=1`. That makes guest coreutils
     * believe SELinux is active, so `cp -Z` / `mkdir -Z` / `restorecon` in
     * package maintainer scripts try to set a security context and *fail*
     * (proot can't `setxattr security.selinux`). Under `set -e` that aborts
     * the script — e.g. openssh-server's postinst dies before writing
     * `/etc/ssh/sshd_config` (#283). The one-shot install path already overlays
     * this empty dir; the interactive shell/desktop paths must too.
     */
    fun selinuxMaskBind(rootfsDir: File): String {
        val empty = File(rootfsDir, "sys/.empty").apply { mkdirs() }
        return "${empty.absolutePath}:/sys/fs/selinux"
    }

    /**
     * Build and start a PRoot process running [command] in the active
     * rootfs, with combined stdout+stderr on the returned Process's
     * inputStream. Callers that need to stream output incrementally (a
     * long apt install surfaced through an async MCP job) read the stream
     * themselves; [runCommandInProot] is the read-to-completion wrapper.
     */
    fun startCommandInProot(command: String): Process {
        val prootBin = prootBinary ?: throw IllegalStateException("PRoot not available")
        // Refresh the guest resolver before every guest command (#446). This is the
        // path package installs run through — the exact thing that hangs when
        // resolv.conf points at resolvers the network won't route to — so it must
        // reflect the current DNS setting, not whatever was written at install time.
        applyResolvConf(activeRootfsDir)
        val loaderPath = File(context.applicationInfo.nativeLibraryDir, "libproot_loader.so").absolutePath
        // Mirror termux/proot-distro v4.29.0's `run_proot_cmd` invocation
        // (distro-plugins runner). Empirically that's the proven pattern
        // for running xbps-install / apt-get / pacman inside this exact
        // Void tarball — Termux users install Xfce4 on Void daily via
        // `proot-distro login void` + `xbps-install -Sy xfce4`. The
        // missing /dev/fd, /dev/std{in,out,err}, /dev/random bindings
        // and the fake /proc files are what breaks fontconfig's
        // pre-INSTALL when invoked via the bare ProcessBuilder we used
        // before. See proot-distro/proot-distro.sh:run_proot_cmd().
        val procDir = File(activeRootfsDir, "proc").apply { mkdirs() }
        // Write minimal fake /proc replacements once per rootfs. proot
        // can't read the host's real /proc/{loadavg,stat,uptime,…}
        // through its bind mount, so apps that parse them (rpm post-
        // install, locale generation, etc.) need plausible-looking
        // values to make progress.
        fun writeIfMissing(name: String, content: String) {
            val f = File(procDir, name)
            if (!f.exists()) f.writeText(content)
        }
        writeIfMissing(".loadavg", "0.12 0.07 0.02 2/165 765\n")
        writeIfMissing(".uptime", "284.83 1391.42\n")
        writeIfMissing(".version", "Linux version 6.2.1 (proot@haven) #1 SMP PREEMPT_DYNAMIC\n")
        writeIfMissing(".vmstat", "nr_free_pages 1000\n")
        writeIfMissing(".stat", "cpu  1957 0 2877 93280 262 342 254 87 0 0\nbtime 1680020856\n")
        writeIfMissing(".sysctl_entry_cap_last_cap", "40\n")
        writeIfMissing(".sysctl_inotify_max_user_watches", "65536\n")

        val rootfsPath = activeRootfsDir.absolutePath
        val devShm = ensureDevShm()
        val sysSelinuxMask = selinuxMaskBind(activeRootfsDir)
        val args = mutableListOf(
            prootBin,
            "-L",
            "--kernel-release=6.2.1",
            "--link2symlink",
            // #375: emulate SysV IPC (msgget/semget/shmget) in proot — Android
            // kernels ship without it, which broke fakeroot's default sysv
            // faked transport (Arch makepkg). The bundled termux proot fork
            // carries the extension; it was just never enabled.
            "--sysvipc",
            "--kill-on-exit",
            // #325: route foreign-arch execs through a bundled qemu-user loader.
            // Inert unless the active rootfs is marked foreign AND its loader is
            // bundled — otherwise an empty spread.
            *qemuUserArgs(activeDistroId).toTypedArray(),
            "--rootfs=$rootfsPath",
            "--root-id",
            "--cwd=/root",
            "--bind=/dev",
            "--bind=/dev/urandom:/dev/random",
            // Writable /dev/shm — Android's /dev (bound above) is read-only and
            // has no shm, so POSIX shared memory (shm_open) fails for Mono/.NET,
            // Chromium, etc. Overlay a cacheDir-backed dir so it works.
            "--bind=$devShm:/dev/shm",
            "--bind=/proc",
            "--bind=/proc/self/fd:/dev/fd",
            "--bind=/proc/self/fd/0:/dev/stdin",
            "--bind=/proc/self/fd/1:/dev/stdout",
            "--bind=/proc/self/fd/2:/dev/stderr",
            "--bind=/sys",
            "--bind=$rootfsPath/proc/.loadavg:/proc/loadavg",
            "--bind=$rootfsPath/proc/.stat:/proc/stat",
            "--bind=$rootfsPath/proc/.uptime:/proc/uptime",
            "--bind=$rootfsPath/proc/.version:/proc/version",
            "--bind=$rootfsPath/proc/.vmstat:/proc/vmstat",
            "--bind=$rootfsPath/proc/.sysctl_entry_cap_last_cap:/proc/sys/kernel/cap_last_cap",
            "--bind=$rootfsPath/proc/.sysctl_inotify_max_user_watches:/proc/sys/fs/inotify/max_user_watches",
            "--bind=$sysSelinuxMask",
            "--bind=${context.cacheDir.absolutePath}:/tmp",
            // #304: surface the device model at the devicetree path fastfetch reads.
            *(deviceModelDevicetreeBind()?.let { arrayOf("--bind=$it") } ?: emptyArray()),
            // #304 part 2: optionally expose Android's system partitions (opt-in).
            *androidSystemBindArgs(longForm = true),
            // #420: expose the user's shared storage (/storage, /sdcard), so a
            // one-shot command, an app launched into a desktop, and MCP run_in_proot
            // all reach the user's files like the shell/desktop do.
            *storageBindLongArgs().toTypedArray(),
            // #301: per-distro user-defined extra binds, so a one-shot command
            // (and MCP run_in_proot) sees the same mounts as the shell/desktop.
            *customBindLongArgs(activeDistroId).toTypedArray(),
            "/usr/bin/env", "-i",
            "HOME=/root",
            "LANG=C.UTF-8",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "TMPDIR=/tmp",
            "/bin/sh", "-lc", command,
        )
        return ProcessBuilder(args).apply {
            // proot/PROOT_LOADER vars must live in the OUTER env so
            // the proot binary itself finds its loader. The tracee's
            // env is reset by `env -i` inside proot.
            environment().apply {
                put("PROOT_TMP_DIR", context.cacheDir.absolutePath)
                put("PROOT_LOADER", loaderPath)
                put("PROOT_L2S_DIR", l2sEnvValue(activeDistroId))
            }
            redirectErrorStream(true)
        }.start()
    }

    /**
     * Ensure the guest has the tools an agent needs to screenshot a
     * desktop and enumerate its windows: `xdotool` (window list +
     * geometry) and ImageMagick's `import` (root capture).
     *
     * Installed on demand the first time a capture is requested rather
     * than baked into every desktop's package list — adding packages to
     * an already-installed DE silently no-ops because the install marker
     * short-circuits setupDesktop. A presence check + targeted install is
     * the reliable path. Returns (ready, detail) where detail is a short
     * human-readable status (or the install-failure tail).
     */
    suspend fun ensureCaptureTools(): Pair<Boolean, String> {
        val probeCmd =
            "command -v xdotool >/dev/null 2>&1 && command -v import >/dev/null 2>&1 && echo HAVE || echo MISSING"
        val (probe, _) = runCommandInProot(probeCmd)
        if (probe.contains("HAVE")) return true to "capture tools already present"

        val family = activeDistro.family
        val ops = PackageOps.forFamily(family)
        // Void packages ImageMagick with a capital name; everyone else
        // ships lowercase `imagemagick`.
        val imagemagick = if (family == PackageFamily.XBPS) "ImageMagick" else "imagemagick"
        val pkgs = listOf("xdotool", imagemagick)
        val (installOut, _) = runCommandInProot("${ops.updateCmd()} && ${ops.installCmd(pkgs)}")
        val (recheck, _) = runCommandInProot(probeCmd)
        return if (recheck.contains("HAVE")) {
            true to "installed ${pkgs.joinToString(" ")}"
        } else {
            false to "capture-tool install failed: ${installOut.takeLast(800)}"
        }
    }

    /**
     * Ensure `grim` — the wlroots screencopy screenshotter — is present, for
     * capturing nested-Wayland desktops (Sway / Hyprland / niri / cage). The
     * wayland counterpart to [ensureCaptureTools]' X11 ImageMagick: those
     * desktops have no X display, but grim grabs frames straight from the
     * compositor over wlr-screencopy (the same path wayvnc falls back to).
     * grim is packaged as "grim" on every supported family. Probe-then-
     * install like [ensureCaptureTools]; returns (ready, detail).
     */
    suspend fun ensureWaylandCaptureTools(): Pair<Boolean, String> {
        val probeCmd = "command -v grim >/dev/null 2>&1 && echo HAVE || echo MISSING"
        val (probe, _) = runCommandInProot(probeCmd)
        if (probe.contains("HAVE")) return true to "grim already present"

        val ops = PackageOps.forFamily(activeDistro.family)
        val (installOut, _) = runCommandInProot("${ops.updateCmd()} && ${ops.installCmd(listOf("grim"))}")
        val (recheck, _) = runCommandInProot(probeCmd)
        return if (recheck.contains("HAVE")) {
            true to "installed grim"
        } else {
            false to "grim install failed: ${installOut.takeLast(800)}"
        }
    }

    /**
     * Ensure the headless render tools used by the `view_file` MCP tool are
     * present — `rsvg-convert` (SVG→PNG) and/or `pdftoppm` (PDF→PNG). Only the
     * binaries in [needed] are probed and, if missing, installed. Mirrors
     * [ensureCaptureTools]; returns (ready, detail). Package names differ per
     * family, so if a binary is still absent after the install attempt the
     * caller gets an honest failure rather than a silent miss. `kicad-cli` is
     * NOT handled here — it ships with KiCad and is too heavy to auto-install.
     */
    suspend fun ensureRenderTools(needed: List<String>): Pair<Boolean, String> {
        if (needed.isEmpty()) return true to "no render tools needed"
        val probeCmd = buildString {
            append("for b in ${needed.joinToString(" ")}; do ")
            append("command -v \$b >/dev/null 2>&1 || { echo MISSING; exit 0; }; ")
            append("done; echo HAVE")
        }
        val (probe, _) = runCommandInProot(probeCmd)
        if (probe.contains("HAVE")) return true to "render tools already present"

        val family = activeDistro.family
        fun pkgFor(bin: String): String? = when (bin) {
            "rsvg-convert" -> when (family) {
                PackageFamily.APT -> "librsvg2-bin"
                PackageFamily.PACMAN -> "librsvg"
                PackageFamily.XBPS -> "librsvg-utils"
                PackageFamily.APK -> "rsvg-convert"
                PackageFamily.NIX -> null
            }
            "pdftoppm" -> when (family) {
                PackageFamily.PACMAN -> "poppler"
                PackageFamily.APT, PackageFamily.XBPS, PackageFamily.APK -> "poppler-utils"
                PackageFamily.NIX -> null
            }
            else -> null
        }
        val pkgs = needed.mapNotNull { pkgFor(it) }.distinct()
        if (pkgs.isEmpty()) {
            return false to "no known package mapping for ${needed.joinToString(" ")} on $family"
        }
        val ops = PackageOps.forFamily(family)
        val (installOut, _) = runCommandInProot("${ops.updateCmd()} && ${ops.installCmd(pkgs)}")
        val (recheck, _) = runCommandInProot(probeCmd)
        return if (recheck.contains("HAVE")) {
            true to "installed ${pkgs.joinToString(" ")}"
        } else {
            false to "render-tool install failed (need ${needed.joinToString(" ")}): ${installOut.takeLast(600)}"
        }
    }

    /**
     * Run `update && install` inside the proot, and if the install
     * fails with the family's stale-package-DB signature, run a
     * full system upgrade once and retry the install. Self-repair
     * for the long-tail case where the rootfs tarball is from a
     * snapshot whose package names / dependency chains have moved
     * on in the live repos (e.g. Arch renamed libstdc++ → gcc-libs
     * weeks after the proot-distro tarball was built; subsequent
     * `pacman -S xfce4` fails until `pacman -Syu` realigns
     * everything).
     */
    private suspend fun runInstallWithSelfRepair(
        ops: PackageOps,
        pkgs: List<String>,
        de: DesktopEnvironment? = null,
    ): Pair<String, Int> {
        val installCmd = "${ops.updateCmd()} && ${ops.installCmd(pkgs)}"
        val (output, code) = runCommandInProot(installCmd)
        if (code == 0 || !ops.looksLikeStaleDb(output)) {
            return Pair(output, code)
        }
        Log.d(TAG, "Install hit stale-DB symptom; auto-upgrading and retrying")
        _desktopState.value = DesktopSetupState.Installing(
            "Refreshing package database (one-time upgrade)…",
            de,
        )
        val (upgOut, upgCode) = runCommandInProot(ops.upgradeCmd())
        Log.d(TAG, "Auto-upgrade exit=$upgCode tail=${upgOut.takeLast(300)}")
        return runCommandInProot(installCmd)
    }

    /**
     * Install X11 + VNC + Xfce4 desktop inside the PRoot rootfs.
     */
    suspend fun setupDesktop(vncPassword: String, de: DesktopEnvironment = DesktopEnvironment.XFCE4) {
        try {
            // Ensure rootfs is installed first. If it failed, the
            // root cause already lives in _state as SetupState.Error
            // with a Phase. Don't shadow it with a vague DE-level
            // error — keep the OS-layer attribution visible so the
            // user sees "Bootstrap hook void-xbps-bootstrap failed"
            // rather than the misleading "Rootfs install failed"
            // under a Desktop installation banner.
            if (!isRootfsInstalled) {
                installRootfs()
                if (_state.value is SetupState.Error) {
                    _desktopState.value = DesktopSetupState.Idle
                    return
                }
            }

            // Install if this DE is not yet installed
            val needsInstall = !isDesktopInstalled(de)
            if (needsInstall) {
                _desktopState.value = DesktopSetupState.Installing(
                    "Installing ${de.label} (${de.sizeEstimate} download)...",
                    de,
                )

            val distro = activeDistro
            val ops = PackageOps.forFamily(distro.family)
            val pkgs = de.spec.packagesPerFamily[distro.family]
                ?: error("${de.spec.id} has no package list for ${distro.family} — supported: ${de.spec.packagesPerFamily.keys}")
            installLogRepository.logEvent(
                distroId = distro.id,
                phase = "DePackage:${de.spec.id}",
                deId = de.spec.id,
                message = "Installing ${de.label}: ${pkgs.joinToString(" ")}",
            )
            val (installOutput, installExit) = runInstallWithSelfRepair(ops, pkgs, de)
            Log.d(TAG, "[de-package ${de.spec.id}] family=${distro.family} exit=$installExit tail=${installOutput.takeLast(1500)}")

            // Check if key binaries were installed — package managers may
            // return non-zero for non-fatal trigger errors (gtk icon cache,
            // fontscale, etc.). For marker-based DEs, fall back to the
            // family-specific success heuristic on stdout.
            val checkInstalled = if (de.verifyBinary.startsWith("root/.haven-")) {
                ops.installSucceeded(installOutput)
            } else {
                File(activeRootfsDir, de.verifyBinary).exists()
            }
            if (!checkInstalled) {
                // If the failure looks like a transient mirror sync
                // issue, surface a clearer "try again later" message
                // rather than dumping raw apt/pacman tail at the
                // user. Self-repair will have already retried once
                // with a forced DB refresh, so a remaining failure
                // means the mirror is genuinely out of sync — usually
                // resolves itself within minutes.
                val message = if (ops.looksLikeStaleDb(installOutput)) {
                    "Distro mirror is mid-sync (a package version listed in the " +
                        "index isn't on disk yet). Wait a few minutes and tap " +
                        "Install again — usually self-resolves."
                } else {
                    "Package install failed for ${de.label} on ${distro.label}."
                }
                installLogRepository.logEvent(
                    distroId = distro.id,
                    phase = "DePackage:${de.spec.id}",
                    deId = de.spec.id,
                    exit = installExit,
                    ok = false,
                    message = message,
                    logTail = installOutput.takeLast(1500),
                )
                _desktopState.value = DesktopSetupState.Error(
                    phase = DePhase.Packages,
                    message = message,
                    logTail = installOutput.takeLast(1500),
                )
                return
            }

            // A present verifyBinary is necessary but not sufficient: a
            // half-completed package install can leave the components the
            // start command launches (window manager, panel, …) missing,
            // so the desktop "installs" then renders a blank screen (#254).
            // Reject that loudly, naming what's missing, instead of writing
            // the installed marker for a broken desktop.
            val missingComponents = missingDesktopBinaries(activeRootfsDir, de.extraBinaries)
            if (missingComponents.isNotEmpty()) {
                val names = missingComponents.joinToString(", ") { it.substringAfterLast('/') }
                val message = "${de.label} installed but these components are missing: " +
                    "$names. The package install may have been interrupted — uninstall " +
                    "the desktop and install it again."
                installLogRepository.logEvent(
                    distroId = distro.id,
                    phase = "DePackage:${de.spec.id}",
                    deId = de.spec.id,
                    exit = installExit,
                    ok = false,
                    message = message,
                    logTail = installOutput.takeLast(1500),
                )
                _desktopState.value = DesktopSetupState.Error(
                    phase = DePhase.Packages,
                    message = message,
                    logTail = installOutput.takeLast(1500),
                )
                return
            }
            installLogRepository.logEvent(
                distroId = distro.id,
                phase = "DePackage:${de.spec.id}",
                deId = de.spec.id,
                exit = installExit,
                ok = true,
                message = "Packages installed",
            )

            Log.d(TAG, "${de.label} packages installed")
            // The "installed" markers (.haven-desktop list + per-DE marker)
            // are written at the END of setupDesktop, after config succeeds —
            // NOT here — so installedDesktops/list_desktop_environments don't
            // report this DE while config still runs or if config later fails.
            // (state-reporting lag fix)
            }

            if (de.spec.launch is LaunchSpec.X11Vnc) {
                _desktopState.value = DesktopSetupState.Installing("Configuring VNC...", de)

                // Write VNC password — use a temp file to avoid leaking
                // the password in process arguments (visible in /proc)
                runCommandInProot("mkdir -p /root/.vnc")
                if (vncPassword.isNotEmpty()) {
                    val tmpPwd = File(activeRootfsDir, "root/.vnc/.pwd_tmp")
                    try {
                        tmpPwd.writeText(vncPassword)
                    } finally { /* deleted below after use */ }
                    val (pwdOut, pwdExit) = runCommandInProot(
                        "vncpasswd -f < /root/.vnc/.pwd_tmp > /root/.vnc/passwd && chmod 600 /root/.vnc/passwd; rm -f /root/.vnc/.pwd_tmp"
                    )
                    tmpPwd.delete() // also delete from host side
                    Log.d(TAG, "vncpasswd exit=$pwdExit output=$pwdOut")
                    val passwdFile = File(activeRootfsDir, "root/.vnc/passwd")
                    Log.d(TAG, "passwd file exists=${passwdFile.exists()} size=${passwdFile.length()}")
                    // Store encrypted for the VNC viewer to use on subsequent starts
                    storedVncPassword = vncPassword
                } else {
                    // No password — remove any existing passwd file so server uses None
                    File(activeRootfsDir, "root/.vnc/passwd").delete()
                    File(activeRootfsDir, "root/.haven-vnc-password").delete()
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

            // Seed minimum-viable config files (Phase 4 nested wlroots
            // compositors need at least a headless output declaration to
            // render anything). Write-if-absent so user edits survive a
            // re-install. NativeCompositor and X11Vnc DEs declare an
            // empty configSeed map today and skip this loop unchanged.
            for ((relPath, content) in de.spec.configSeed) {
                val target = File(activeRootfsDir, relPath)
                if (target.exists()) {
                    Log.d(TAG, "[de-config ${de.spec.id}] keeping existing $relPath")
                    continue
                }
                target.parentFile?.mkdirs()
                target.writeText(content)
                Log.d(TAG, "[de-config ${de.spec.id}] seeded $relPath (${content.length} bytes)")
            }

            // Nested-Wayland DEs (Sway / Hyprland / Niri) need the
            // wayvnc capture-fallback shim staged in the rootfs so the
            // launch script's LD_PRELOAD picks it up. The shim blocks
            // ext-image-copy-capture-v1 from wayvnc so it falls back to
            // zwlr_screencopy, which the wlroots headless backend
            // actually advertises buffer formats for. Overwrite on each
            // install so a Haven update can ship a newer shim without
            // user intervention.
            if (de.spec.launch is LaunchSpec.NestedWayland) {
                stageWayvncShim(de)

                // Seed fuzzel + foot configs and a small .desktop set so
                // the auto-launched fuzzel picker has something to show
                // on first connect. labwc-native uses writeDesktopConfigs
                // for the same purpose; the nested-Wayland flow doesn't
                // call that path because it pulls in waybar / labwc-menu
                // bits that don't apply.
                writeNestedWaylandFuzzelConfig()

                // Replace a too-old packaged wayvnc with a pinned source
                // build (#473). Debian trixie's 0.9.1 SIGSEGVs on roughly
                // one app-window launch in three and upstream will not fix
                // an old version; 0.10.1 does not. The script gates itself
                // on the installed version, so this is a sub-second no-op
                // wherever the distro already ships >= 0.10 — but it is a
                // multi-minute compile where it doesn't, hence its own
                // progress step rather than silence.
                //
                // Deliberately non-fatal: if the build fails the guest is
                // left with the distro's wayvnc, which is exactly what it
                // would have had anyway. A desktop that mostly works beats
                // an install marked failed.
                stageWayvncBuildScript()
                _desktopState.value = DesktopSetupState.Installing("Checking wayvnc version...", de)
                // Bounded, unlike the rest: this compiles wayvnc from source
                // and is the one step a reporter watched sit at "Checking
                // wayvnc version..." with nothing running in the guest behind
                // it (#503). It is already best-effort — a failure leaves the
                // distro's own wayvnc in place — so a bound costs nothing that
                // waiting forever was buying.
                val (wayvncOut, wayvncExit) =
                    runCommandInProot(wayvncBuildCommand, timeoutMs = WAYVNC_BUILD_TIMEOUT_MS)
                if (wayvncExit != 0) {
                    Log.w(
                        TAG,
                        "[wayvnc-build] failed (exit $wayvncExit) — " +
                            "keeping the packaged wayvnc: ${wayvncOut.takeLast(500)}",
                    )
                    installLogRepository.logEvent(
                        distroId = activeDistro.id,
                        phase = "DeConfig:${de.spec.id}",
                        deId = de.spec.id,
                        exit = wayvncExit,
                        ok = false,
                        message = if (wayvncExit == -1) {
                            "wayvnc source build exceeded ${WAYVNC_BUILD_TIMEOUT_MS / 60_000} " +
                                "minutes and was stopped; using the distro's wayvnc"
                        } else {
                            "wayvnc source build failed; using the distro's wayvnc"
                        },
                    )
                } else {
                    Log.d(TAG, "[wayvnc-build] ${wayvncOut.trim().takeLast(300)}")
                }
            }

            Log.d(TAG, "[de-config ${de.spec.id}] complete")
            // Flag the DE installed only now that packages AND config both
            // succeeded — this is the authoritative signal installedDesktopsFor
            // reads, so the catalog never reports a DE that's still installing
            // (apt may have unpacked its binary) or one whose config failed.
            File(activeRootfsDir, "root").mkdirs()
            File(activeRootfsDir, "root/.haven-desktop")
                .writeText((installedDesktops + de).joinToString("\n") { it.name })
            if (de.verifyBinary.startsWith("root/.haven-")) {
                File(activeRootfsDir, de.verifyBinary).writeText(de.name)
            }
            installLogRepository.logEvent(
                distroId = activeDistro.id,
                phase = "DeConfig:${de.spec.id}",
                deId = de.spec.id,
                exit = 0,
                ok = true,
                message = "${de.label} setup complete",
            )
            _desktopState.value = DesktopSetupState.Complete
        } catch (e: Exception) {
            Log.e(TAG, "[de-setup ${de.spec.id}] failed", e)
            installLogRepository.logEvent(
                distroId = activeDistro.id,
                phase = "DeConfig:${de.spec.id}",
                deId = de.spec.id,
                ok = false,
                message = e.message ?: "Setup failed",
            )
            _desktopState.value = DesktopSetupState.Error(
                phase = DePhase.VncConfig,
                message = e.message ?: "Setup failed",
                logTail = "",
            )
        }
    }

    /**
     * Install optional desktop add-ons (panel, file manager, etc.) into the PRoot rootfs.
     * Packages are installed via apk and config files are written for labwc integration.
     */
    suspend fun installAddons(addons: Set<DesktopAddon>) {
        if (addons.isEmpty()) {
            // Remove addons marker and configs if user unchecked everything
            File(activeRootfsDir, "root/.haven-addons").delete()
            return
        }

        try {
            _desktopState.value = DesktopSetupState.Installing("Installing desktop features...")

            val ops = PackageOps.forFamily(activeDistro.family)
            val packages = addons.flatMap { it.packages.split(" ").filter { p -> p.isNotBlank() } }
            val (installOutput, installExit) = runInstallWithSelfRepair(ops, packages)
            Log.d(TAG, "addon install exit=$installExit output(last 300)=${installOutput.takeLast(300)}")

            writeDesktopConfigs()

            // Write marker
            File(activeRootfsDir, "root/.haven-addons")
                .writeText(addons.joinToString("\n") { it.name })
            Log.d(TAG, "Desktop addons installed: ${addons.map { it.name }}")

            _desktopState.value = DesktopSetupState.Complete
        } catch (e: Exception) {
            Log.e(TAG, "[addon-install] failed", e)
            _desktopState.value = DesktopSetupState.Error(
                phase = DePhase.Packages,
                message = e.message ?: "Addon install failed",
                logTail = "",
            )
        }
    }

    /**
     * Uninstall a desktop environment from the PRoot rootfs.
     * Removes packages and updates the marker file.
     */
    /**
     * Re-seed a DE's [configSeed] files when the on-disk copy is missing
     * OR still carries a superseded Haven default (matched by
     * [DesktopEnvironmentSpec.legacyConfigMarkers]). Called at launch so
     * a config fix reaches existing installs whose write-if-absent config
     * would otherwise stay frozen — without clobbering user edits, since
     * a config that no longer contains a legacy marker is left alone.
     * No-op for DEs with an empty configSeed.
     */
    /**
     * Copy the wayvnc capture-fallback shim from APK assets into the rootfs,
     * overwriting any existing copy. Called both at install time and on every
     * desktop start (via [migrateDesktopConfigs]) so a Haven update that ships
     * a newer/fixed shim reaches existing installs without a reinstall — e.g.
     * the v5.43.8 musl-compat rebuild (#162): the install-time copy alone left
     * the old glibc-fortify shim in place on Alpine, where it failed to
     * relocate `__fprintf_chk`.
     */
    private fun stageWayvncShim(de: DesktopEnvironment) {
        if (de.spec.launch !is LaunchSpec.NestedWayland) return
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull {
            it == "arm64-v8a" || it == "x86_64" || it == "armeabi-v7a"
        }
        if (abi == null) {
            Log.w(TAG, "[de-config ${de.spec.id}] no wayvnc shim asset for ABI ${android.os.Build.SUPPORTED_ABIS.toList()}")
            return
        }
        val asset = "wayvnc-shim/$abi/libhaven_wayvnc_shim.so"
        val target = File(activeRootfsDir, "usr/local/lib/haven/libhaven_wayvnc_shim.so")
        target.parentFile?.mkdirs()
        try {
            context.assets.open(asset).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target.setReadable(true, false)
            Log.d(TAG, "[de-config ${de.spec.id}] staged wayvnc shim from $asset (${target.length()} bytes)")
        } catch (e: Exception) {
            Log.w(TAG, "[de-config ${de.spec.id}] failed to stage wayvnc shim: ${e.message}")
        }
    }

    /**
     * Stage the haven-usb guest artifacts into the active rootfs:
     *  - `/usr/local/bin/haven-usb-probe`        (Slice-2 reachability probe)
     *  - `/usr/local/bin/haven-usb-serial`       (CDC-ACM serial<->PTY bridge)
     *  - `/usr/local/lib/haven/libhaven_usb.so`  (Slice-3 LD_PRELOAD/DllMap shim)
     *  - `/usr/local/bin/haven-hidraw-test`      (Slice-3 verification harness)
     *
     * Re-copies on every call so an app update refreshes them. Returns the
     * in-guest path of the probe (the Slice-2 entry point), or null if no asset
     * exists for this ABI. World-readable + executable so the fake-root guest
     * process can run them.
     */
    fun stageHavenUsbArtifacts(): String? {
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull {
            it == "arm64-v8a" || it == "x86_64" || it == "armeabi-v7a"
        }
            ?: run { Log.w(TAG, "[haven-usb] no artifact for ABI ${android.os.Build.SUPPORTED_ABIS.toList()}"); return null }

        fun copy(assetRel: String, target: File, executable: Boolean): Boolean = try {
            target.parentFile?.mkdirs()
            context.assets.open("haven-usb/$abi/$assetRel").use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target.setReadable(true, false)
            if (executable) target.setExecutable(true, false)
            Log.d(TAG, "[haven-usb] staged $assetRel (${target.length()} bytes)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "[haven-usb] failed to stage $assetRel: ${e.message}")
            false
        }

        val probeOk = copy("haven-usb-probe", File(activeRootfsDir, "usr/local/bin/haven-usb-probe"), true)
        copy("haven-usb-serial", File(activeRootfsDir, "usr/local/bin/haven-usb-serial"), true)
        copy("libhaven_usb.so", File(activeRootfsDir, "usr/local/lib/haven/libhaven_usb.so"), false)
        copy("haven-hidraw-test", File(activeRootfsDir, "usr/local/bin/haven-hidraw-test"), true)
        return if (probeOk) "/usr/local/bin/haven-usb-probe" else null
    }

    /**
     * Stage the venus windowed-GL fix assets into the active rootfs:
     *  - /usr/local/share/haven/mesa-venus-fix/mesa-venus-gl.patch
     *  - /usr/local/share/haven/mesa-venus-fix/venus-vtest-coherency.patch
     *  - /usr/local/share/haven/mesa-venus-fix/build.sh
     *
     * These let the guest build a patched Mesa (libgallium + libEGL + the venus
     * ICD libvulkan_virtio) once per distro so windowed zink+venus GL both
     * PRESENTS over the wl_shm cage (platform_wayland.c routing → kopper sw-WSI)
     * and stops flickering: the per-frame UBO re-issued through the command
     * stream (zink), and — for geometry-animating scenes the UBO fix can't reach
     * — a guest CPU cache writeback of mapped host-visible memory before each
     * venus submit (the ICD patch), because venus host-visible memory isn't
     * reliably GPU-visible over vtest. Device-verified (project_virgl_cage_gpu_accel
     * R5/R6: static scenes 0% flat vs ~42%; jellyfish 24/24 vs 5/24 frames).
     * ABI-independent text; re-copied on every desktop start so an app update
     * refreshes the patch/script. The built+cached `.so` and `preload` under
     * /usr/local/lib/haven/mesa-venus-fix/ are NOT assets and are preserved.
     * Built only when explicitly triggered ([mesaVenusFixBuildCommand]); never
     * on the launch hot path. gpuPassthroughEnv LD_PRELOADs the cache if present.
     */
    fun stageMesaVenusFix() {
        val destDir = File(activeRootfsDir, "usr/local/share/haven/mesa-venus-fix")
        fun copy(name: String) = try {
            val target = File(destDir, name)
            target.parentFile?.mkdirs()
            context.assets.open("mesa-venus-fix/$name").use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target.setReadable(true, false)
            Log.d(TAG, "[mesa-venus-fix] staged $name (${target.length()} bytes)")
        } catch (e: Exception) {
            Log.w(TAG, "[mesa-venus-fix] failed to stage $name: ${e.message}")
        }
        copy("mesa-venus-gl.patch")
        copy("venus-vtest-coherency.patch")
        copy("build.sh")
    }

    /** In-guest command that builds + caches the patched zink (idempotent, slow — ~20-40 min, needs network). */
    val mesaVenusFixBuildCommand: String get() = "sh /usr/local/share/haven/mesa-venus-fix/build.sh"

    /**
     * Stage the wayvnc-from-source build script into the active rootfs at
     * /usr/local/share/haven/wayvnc-build/build.sh.
     *
     * Why (#473): Debian trixie pins wayvnc 0.9.1, which SIGSEGVs on roughly
     * one app-window launch in three, and upstream declined to fix an old
     * version. 0.10.1 fixes it (8/8 launches, 0 SIGSEGV, device-measured).
     * The fix is packaged only in Debian forky/sid and Arch, and apt-pinning a
     * moving testing archive onto a user's stable guest is not something to do
     * for a display-server bug — so the three pinned tags are built instead.
     *
     * Text asset, ABI-independent; re-copied on every install so an app update
     * ships a newer pin without a reinstall. The script itself is the gate: it
     * exits without building when wayvnc is already >= 0.10, so distros that
     * ship a fixed version (Arch) and any future stable pay nothing.
     */
    fun stageWayvncBuildScript() {
        val target = File(activeRootfsDir, "usr/local/share/haven/wayvnc-build/build.sh")
        try {
            target.parentFile?.mkdirs()
            context.assets.open("wayvnc-build/build.sh").use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target.setReadable(true, false)
            Log.d(TAG, "[wayvnc-build] staged build.sh (${target.length()} bytes)")
        } catch (e: Exception) {
            Log.w(TAG, "[wayvnc-build] failed to stage build.sh: ${e.message}")
        }
    }

    /**
     * In-guest command that builds wayvnc 0.10.1 from pinned source when the
     * distro's own is too old. Idempotent and self-gating — a no-op in well
     * under a second on a guest that already has >= 0.10.
     */
    val wayvncBuildCommand: String get() = "sh /usr/local/share/haven/wayvnc-build/build.sh"

    /** Absolute in-guest path of the LD_PRELOAD list written by [mesaVenusFixBuildCommand]. */
    val mesaVenusFixPreloadPath: String get() = "/usr/local/lib/haven/mesa-venus-fix/preload"

    /** Absolute in-guest path of the LD_PRELOAD/DllMap shim staged by [stageHavenUsbArtifacts]. */
    val havenUsbShimGuestPath: String get() = "/usr/local/lib/haven/libhaven_usb.so"

    /** Absolute in-guest path of the CDC-ACM serial<->PTY bridge staged by [stageHavenUsbArtifacts]. */
    val havenUsbSerialGuestPath: String get() = "/usr/local/bin/haven-usb-serial"

    fun migrateDesktopConfigs(de: DesktopEnvironment) {
        // Refresh the shim on every start so an app update's newer shim
        // replaces a stale one in an existing rootfs (#162).
        stageWayvncShim(de)
        // Refresh the venus GL coherency-fix assets too (patch + build script);
        // the built .so cache under the same dir is preserved.
        stageMesaVenusFix()
        // Keep the wayvnc build script current too, so an app update ships a
        // newer pin to an existing rootfs (#473). Staging only — this method
        // runs on every desktop start and is not suspending, and the build is
        // minutes long on a guest that needs it. An existing install therefore
        // keeps its old wayvnc until the DE is reinstalled or the user runs
        // the staged script themselves.
        stageWayvncBuildScript()
        for ((relPath, content) in de.spec.configSeed) {
            val target = File(activeRootfsDir, relPath)
            val existing = if (target.exists()) {
                runCatching { target.readText() }.getOrNull()
            } else null
            if (existing == content) continue
            val migrate = existing != null &&
                de.spec.legacyConfigMarkers.any { existing.contains(it) }
            if (existing == null || migrate) {
                target.parentFile?.mkdirs()
                target.writeText(content)
                Log.d(
                    TAG,
                    "[de-config ${de.spec.id}] ${if (migrate) "migrated" else "seeded"} " +
                        "$relPath (${content.length} bytes)",
                )
            }
        }
    }

    suspend fun uninstallDesktop(de: DesktopEnvironment) {
        try {
            _desktopState.value = DesktopSetupState.Installing("Removing ${de.label}...", de)
            val distro = activeDistro
            val ops = PackageOps.forFamily(distro.family)
            val pkgs = de.spec.packagesPerFamily[distro.family]
                ?: error("${de.spec.id} has no package list for ${distro.family}")
            // Snapshot the other installed desktops BEFORE removing anything:
            // once a shared package is gone the detection binary check flips
            // the sibling to "uninstalled", so both this retain-set and the
            // marker rewrite must be computed from the pre-removal state. (#368)
            val others = installedDesktops - de
            val toRemove = desktopPackagesToRemove(
                targetPackages = pkgs,
                otherInstalledPackages = others.map { it.spec.packagesPerFamily[distro.family] ?: emptyList() },
            )
            if (toRemove.isNotEmpty()) {
                val (output, exit) = runCommandInProot(ops.removeCmd(toRemove))
                Log.d(TAG, "remove ${de.label}: removing $toRemove exit=$exit output(last 300)=${output.takeLast(300)}")
            } else {
                Log.d(TAG, "remove ${de.label}: all packages shared with ${others.map { it.name }}, none removed")
            }

            val marker = File(activeRootfsDir, "root/.haven-desktop")
            if (others.isEmpty()) {
                marker.delete()
            } else {
                marker.writeText(others.joinToString("\n") { it.name })
            }
            // Remove DE-specific marker file
            if (de.verifyBinary.startsWith("root/.haven-")) {
                File(activeRootfsDir, de.verifyBinary).delete()
            }
            Log.d(TAG, "${de.label} uninstalled, remaining: ${others.map { it.name }}")
            _desktopState.value = DesktopSetupState.Complete
        } catch (e: Exception) {
            Log.e(TAG, "[de-uninstall ${de.spec.id}] failed", e)
            _desktopState.value = DesktopSetupState.Error(
                phase = DePhase.Packages,
                message = e.message ?: "Uninstall failed",
                logTail = "",
            )
        }
    }

    /** Write mobile-optimized config files for waybar, fuzzel, and labwc menu. */
    /**
     * Configs the Phase-4 nested-Wayland compositors (Sway / Hyprland /
     * Niri) need so the auto-launched `fuzzel` picker has visible
     * entries on first connect. Distinct from the labwc-native
     * [writeDesktopConfigs] path: no waybar, no labwc menu, no
     * `launch-prefix=/usr/local/bin/launch` (that script doesn't exist
     * in the rootfs and would crash the picker on a selection).
     *
     * Always-on entries: foot (terminal), htop. Conditional entries:
     * thunar, mousepad, imv-wayland, mpv if those binaries are
     * present in the rootfs at config time.
     */
    private fun writeNestedWaylandFuzzelConfig() {
        val rootfsDir = activeRootfsDir
        val root = File(rootfsDir, "root")

        // fuzzel — large enough text for VNC at 1280x720; no
        // launch-prefix because the labwc /usr/local/bin/launch hook
        // isn't shipped (would crash on every selection).
        File(root, ".config/fuzzel").mkdirs()
        File(root, ".config/fuzzel/fuzzel.ini").writeText(
            """
            |[main]
            |font=Noto Sans:size=14
            |width=30
            |lines=12
            |prompt=>
            |layer=overlay
            """.trimMargin()
        )

        // foot — borderless, mobile-friendly font size.
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

        // .desktop entries — write to both user and system dirs so
        // fuzzel finds them regardless of $XDG_DATA_HOME / $XDG_DATA_DIRS.
        val appsDir = File(root, ".local/share/applications").apply { mkdirs() }
        val sysAppsDir = File(rootfsDir, "usr/share/applications").apply { mkdirs() }
        val entries = mutableMapOf(
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
        data class AppEntry(val file: String, val binary: String, val content: String)
        val conditional = listOf(
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
                """.trimMargin()),
            AppEntry("mpv.desktop", "usr/bin/mpv", """
                |[Desktop Entry]
                |Name=Media Player
                |Exec=mpv --player-operation-mode=pseudo-gui
                |Icon=multimedia-video-player
                |Type=Application
                |Categories=AudioVideo;Video;
                """.trimMargin()),
        )
        for (app in conditional) {
            if (File(rootfsDir, app.binary).exists()) entries[app.file] = app.content
        }
        for ((name, content) in entries) {
            File(appsDir, name).writeText(content)
            File(sysAppsDir, name).writeText(content)
        }
        Log.d(TAG, "Seeded fuzzel + ${entries.size} .desktop entries for nested-Wayland")
    }

    private fun writeDesktopConfigs() {
        val rootfsDir = activeRootfsDir
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
            |        "format": "Apps",
            |        "on-click": "fuzzel"
            |    },
            |    "custom/terminal": {
            |        "format": "Terminal",
            |        "on-click": "xfce4-terminal || foot"
            |    },
            |    "custom/files": {
            |        "format": "Files",
            |        "on-click": "thunar || foot -e ls"
            |    },
            |    "custom/editor": {
            |        "format": "Edit",
            |        "on-click": "mousepad"
            |    },
            |    "cpu": {
            |        "format": "CPU {usage}%",
            |        "interval": 5,
            |        "tooltip": true
            |    },
            |    "memory": {
            |        "format": "RAM {percentage}%",
            |        "interval": 5,
            |        "tooltip-format": "{used:0.1f}G / {total:0.1f}G"
            |    },
            |    "clock": {
            |        "format": "{:%H:%M}",
            |        "format-alt": "{:%a %d %b %H:%M}",
            |        "tooltip-format": "<tt>{calendar}</tt>"
            |    }
            |}
            """.trimMargin()
        )
        File(root, ".config/waybar/style.css").writeText(
            """
            |* {
            |    font-family: "Noto Sans", sans-serif;
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
        val labwcDir = File(activeRootfsDir, "root/.config/labwc")
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
     * Delete the rootfs to free space. Also cleans up any pre-issue-#162
     * legacy `alpine/` directory if the migration was skipped.
     */
    fun deleteRootfs() {
        if (!forceDeleteRecursively(activeRootfsDir)) {
            Log.w(TAG, "deleteRootfs: could not fully remove ${activeRootfsDir.absolutePath}")
        }
        // Belt-and-braces: if the legacy alpine/ dir was never renamed
        // (rare migration failure path), free it too.
        if (activeDistroId == DistroCatalog.DEFAULT_ID) {
            forceDeleteRecursively(File(context.filesDir, "proot/rootfs/alpine"))
        }
        _state.value = SetupState.NotInstalled
    }

    /**
     * Delete a specific distro's rootfs by id. If the deleted distro
     * was active, switches active to the first remaining installed
     * distro (or DEFAULT_ID if none remain) and re-evaluates ready
     * state. Used by the distro picker's per-row delete button —
     * the recovery path for a broken-state install.
     */
    fun deleteDistro(distroId: String) {
        val dir = rootfsDirFor(distroId)
        if (!forceDeleteRecursively(dir)) {
            Log.w(TAG, "deleteDistro: could not fully remove ${dir.absolutePath}")
        }
        // Belt-and-braces: nuke the legacy alpine/ path if we were
        // asked to delete the default distro and it still exists
        // there too (one-off migration edge case).
        if (distroId == DistroCatalog.DEFAULT_ID) {
            forceDeleteRecursively(File(context.filesDir, "proot/rootfs/alpine"))
        }
        // #284: a user-imported distro is gone for good once deleted (no
        // pinned source to re-install from), so drop it from the registry
        // too — otherwise it lingers as a phantom catalog entry.
        if (!DistroCatalog.isBuiltin(distroId)) {
            persistCustomDistros(customDistros.filter { it.id != distroId })
        }
        if (activeDistroId == distroId) {
            reconcileActiveDistro()
        }
        _state.value = if (isReady) SetupState.Ready else SetupState.NotInstalled
        Log.d(TAG, "Deleted distro: $distroId")
    }
}

/**
 * How long the from-source wayvnc build may run before it is stopped (#503).
 *
 * Generous, because on a phone this really is a multi-minute compile and
 * cutting a working build short would be worse than the bug. The point is only
 * that "still going" and "never going to finish" stop looking identical.
 */
internal const val WAYVNC_BUILD_TIMEOUT_MS: Long = 20 * 60 * 1000

/** Outcome of [readProcessBounded]. [timedOut] means the bound was hit, not that the command failed. */
internal data class BoundedRun(val output: String, val exitCode: Int, val timedOut: Boolean)

/**
 * Read [process] to completion, giving up after [timeoutMs].
 *
 * The unbounded version of this — `readText()` then `waitFor()` — can wait
 * forever in two separate ways, and a guest step that used it left the desktop
 * install wedged with no way out (#503). `waitFor()` never returns if the
 * command genuinely hangs; and `readText()` waits for the *pipe* to close, not
 * for the command to exit, so a background grandchild still holding the write
 * end keeps it blocked long after its parent is gone.
 *
 * So the wait is on the process, not on the pipe: a reader thread accumulates
 * output, the bound applies to the process, and after it exits the reader is
 * given [drainGraceMs] to finish rather than however long a stray grandchild
 * feels like holding the pipe.
 */
internal fun readProcessBounded(
    process: Process,
    timeoutMs: Long,
    drainGraceMs: Long = 2_000,
): BoundedRun {
    val out = StringBuilder()
    val reader = Thread {
        try {
            process.inputStream.bufferedReader().forEachLine { out.appendLine(it) }
        } catch (_: Exception) {
            // Pipe torn down by destroyForcibly — whatever arrived is still worth keeping.
        }
    }
    reader.isDaemon = true
    reader.start()
    val finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    if (!finished) {
        process.destroyForcibly()
        process.waitFor(drainGraceMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    }
    reader.join(drainGraceMs)
    val exit = if (finished) runCatching { process.exitValue() }.getOrDefault(-1) else -1
    return BoundedRun(out.toString(), exit, timedOut = !finished)
}
