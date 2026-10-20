package sh.haven.core.local.proot

/**
 * Package-manager strategy interface for the [PackageFamily] enum.
 *
 * A template string isn't enough on its own — different families
 * have distinct invocation conventions (apt's `DEBIAN_FRONTEND`,
 * pacman's `--noconfirm`, etc.) and distinct stdout heuristics for
 * detecting "the package install succeeded but printed warnings".
 * This interface captures both.
 *
 * Phase 1 ships [Apk] only. [Apt], [Pacman], [Xbps], [Nix] land in
 * Phases 2-5.
 */
interface PackageOps {
    /** Command that refreshes the local package index. */
    fun updateCmd(): String

    /**
     * Command that does a full upgrade of the existing installation
     * (Arch's `pacman -Syu`, Debian's `apt-get full-upgrade`, etc).
     * Used by the self-repair retry path when an install fails
     * because the rootfs tarball captured stale package metadata
     * (e.g. Arch renamed `libstdc++` → `gcc-libs` after the
     * proot-distro snapshot was taken).
     */
    fun upgradeCmd(): String

    /** Command that installs [pkgs] and prints progress. */
    fun installCmd(pkgs: List<String>): String

    /** Command that removes [pkgs]. */
    fun removeCmd(pkgs: List<String>): String

    /**
     * Detect "install succeeded" from the combined stdout+stderr of
     * an install run. Used as a fallback when a file-existence
     * check is unreliable (e.g. marker-file DEs).
     */
    fun installSucceeded(output: String): Boolean

    /**
     * Heuristic: does this install-failure output look like the
     * rootfs's package metadata is too old for the live repos?
     * Symptoms differ by family — Arch says "unable to satisfy
     * dependency", apt says "has no installation candidate" or
     * "Package … is not available". When this returns true the
     * setup flow auto-runs [upgradeCmd] and retries once before
     * surfacing the error.
     */
    fun looksLikeStaleDb(output: String): Boolean

    companion object {
        fun forFamily(family: PackageFamily): PackageOps = when (family) {
            PackageFamily.APK -> Apk
            PackageFamily.APT -> Apt
            PackageFamily.PACMAN -> Pacman
            PackageFamily.XBPS -> Xbps
            PackageFamily.NIX -> error("Nix support lands in Phase 5 — see issue #162")
        }
    }
}

object Apk : PackageOps {
    override fun updateCmd(): String = "apk update"

    override fun upgradeCmd(): String = "apk update && apk upgrade --no-cache"

    override fun installCmd(pkgs: List<String>): String =
        "apk add ${pkgs.joinToString(" ")}"

    override fun removeCmd(pkgs: List<String>): String =
        "apk del ${pkgs.joinToString(" ")}"

    override fun installSucceeded(output: String): Boolean =
        output.contains("OK:")

    override fun looksLikeStaleDb(output: String): Boolean =
        // apk's "package is broken" / dep solver phrases when an
        // installed package conflicts with the live index.
        output.contains("unable to select packages") ||
            output.contains("conflicts with") ||
            output.contains("masked in:")
}

/**
 * Debian / Ubuntu apt-get strategy.
 *
 * `DEBIAN_FRONTEND=noninteractive` suppresses dpkg's debconf
 * prompts; `-y` auto-confirms; `--no-install-recommends` keeps the
 * footprint sane. Ubuntu enables Recommends by default, so a bare
 * `apt-get install xfce4 …` pulls the metapackage's entire Recommends
 * closure — ~250 packages / 396 MB download / 1.6 GB on disk, vs the
 * ~100 MB the UI advertises. The DE package lists in [DesktopEnvironment]
 * already name the essentials explicitly (the DE metapackage, the VNC
 * server, a terminal, dbus, fonts), and a metapackage's hard Depends —
 * the actual desktop — install regardless, so the core session stays
 * functional; only the optional goodies are dropped. Users who want a
 * specific extra can add it later.
 *
 * Success heuristic: apt-get prints `Setting up <pkg> (<ver>) ...`
 * for each installed package. This is more reliable than the
 * exit code because apt returns non-zero on warnings (e.g.
 * post-install trigger failures from systemd assumptions) that
 * don't actually break the install.
 */
object Apt : PackageOps {
    private const val ENV = "DEBIAN_FRONTEND=noninteractive"

    /**
     * dpkg + apt both keep lock files that block subsequent runs if
     * the previous invocation was killed (Android suspended the app
     * mid-install). Inside our proot we only ever run one apt at a
     * time so it's always safe to remove stale locks first.
     */
    private const val CLEAR_LOCK =
        "rm -f /var/lib/dpkg/lock /var/lib/dpkg/lock-frontend /var/cache/apt/archives/lock;"

    override fun updateCmd(): String =
        "$CLEAR_LOCK $ENV apt-get update -y"

    override fun upgradeCmd(): String =
        "$CLEAR_LOCK $ENV apt-get update -y && $CLEAR_LOCK $ENV apt-get full-upgrade -y --fix-missing"

    /**
     * `--fix-missing` lets a single mirror flake (transient 400 / 503
     * on one .deb out of hundreds) skip that package instead of
     * tanking the whole install. The user can retry to pick up
     * stragglers. Without it, mid-install network blips force the
     * user to restart the entire 1-2 GB desktop fetch.
     */
    override fun installCmd(pkgs: List<String>): String =
        "$CLEAR_LOCK $ENV apt-get install -y --no-install-recommends --fix-missing ${pkgs.joinToString(" ")}"

    override fun removeCmd(pkgs: List<String>): String =
        "$CLEAR_LOCK $ENV apt-get remove -y ${pkgs.joinToString(" ")}"

    override fun installSucceeded(output: String): Boolean =
        output.contains("Setting up ")

    override fun looksLikeStaleDb(output: String): Boolean =
        // Debian Release file expired / archive moved typically
        // surfaces as one of these. Also catches the "Package X
        // has no installation candidate" path when a renamed
        // package was referenced.
        output.contains("Unable to locate package") ||
            output.contains("has no installation candidate") ||
            output.contains("Release file") && output.contains("expired") ||
            output.contains("does not have a Release file") ||
            output.contains("404  Not Found")
}

/**
 * Arch Linux / Arch Linux ARM pacman strategy.
 *
 * `--noconfirm` auto-answers prompts; `--needed` skips packages
 * that are already installed (idempotent reinstalls). `-Syu` would
 * be the canonical full upgrade, but on a phone we don't want every
 * desktop install to trigger a multi-hundred-megabyte system
 * upgrade — `-Sy` refreshes the package DB without upgrading, then
 * `-S` installs. The downside is partial-upgrade risk; mitigated
 * for fresh installs by the proot-distro tarball already being
 * close to current.
 *
 * Success heuristic: pacman prints `installing <pkg>...` per
 * package and `Total Installed Size:` near the end. The latter is
 * a reliable signal that the transaction reached the apply stage.
 */
object Pacman : PackageOps {
    /**
     * If a previous pacman invocation was killed mid-flight (e.g.
     * Android suspended the app during a 100 MB download) the
     * `/var/lib/pacman/db.lck` file is left behind and every
     * subsequent run hard-fails with "unable to lock database".
     * Inside our proot we only ever run one pacman at a time
     * (runCommandInProot is synchronous), so it's always safe to
     * remove a stale lock before running.
     */
    private const val CLEAR_LOCK = "rm -f /var/lib/pacman/db.lck;"

    override fun updateCmd(): String = "$CLEAR_LOCK pacman -Sy --noconfirm"

    /**
     * Full system upgrade — the canonical Arch fix for the
     * "unable to satisfy dependency" symptoms that surface when the
     * rootfs tarball's package names have drifted from current
     * repos. Costly (~200-500 MB on first run) but the only correct
     * recovery; the self-repair retry path runs this exactly once
     * per failing install attempt.
     *
     * Double-`y` forces a complete re-download of the package
     * databases even if they look fresh. Needed when a mirror
     * pushed a new index but Haven's local cache still references
     * the previous one — the "ffmpeg-2:8.0.1-2 not found" symptom
     * from a partially-synced mirror.
     */
    override fun upgradeCmd(): String = "$CLEAR_LOCK pacman -Syyu --noconfirm"

    override fun installCmd(pkgs: List<String>): String =
        "$CLEAR_LOCK pacman -S --noconfirm --needed ${pkgs.joinToString(" ")}"

    override fun removeCmd(pkgs: List<String>): String =
        "$CLEAR_LOCK pacman -Rsu --noconfirm ${pkgs.joinToString(" ")}"

    override fun installSucceeded(output: String): Boolean =
        output.contains("Total Installed Size:") || output.contains("installing ")

    override fun looksLikeStaleDb(output: String): Boolean =
        // The exact symptom from the field: "unable to satisfy
        // dependency 'libstdc++' required by spirv-tools" after
        // Arch renamed libstdc++ → gcc-libs in the live repos. Also
        // catches the equivalent for renamed pkgs and conflict
        // chains the tarball's metadata can't see, including the
        // gcc-libs → libgcc/libstdc++ split where `pacman -Sy && pacman -S`
        // fails with `conflicting files` / `exists in filesystem (owned by ...)`.
        output.contains("unable to satisfy dependency") ||
            output.contains("target not found") ||
            output.contains("could not be found") ||
            output.contains("conflicting dependencies") ||
            output.contains("conflicting files") ||
            output.contains("exists in filesystem (owned by") ||
            // Mirror sync race: the mirror's DB references a
            // package version whose .pkg.tar.xz is no longer on
            // disk (typical when a new ffmpeg or similar high-
            // churn pkg pushed). Forcing a -Syyu pulls a fresher
            // DB which usually references the new version that IS
            // on the mirror.
            output.contains("failed retrieving file") ||
            // ALPM error number on retrieve failures, plus the
            // raw HTTP code in case curl's stderr passes through.
            output.contains("failed to retrieve some files") ||
            (output.contains("error: 404") && output.contains("pkg.tar"))
}

/**
 * Void Linux xbps strategy.
 *
 * `-Sy` refreshes the index then installs; `-y` auto-confirms.
 * `-R` (capital) is for repository selection, not removal — Void
 * uses `xbps-remove` for that.
 *
 * Success heuristic: xbps prints `xbps-install: <pkg>-<ver>:
 * unpacked.` per installed package. The exact "installed" string
 * is dropped on partial failures so it's a reliable signal of a
 * fully-successful unpack stage.
 */
object Xbps : PackageOps {
    /**
     * xbps keeps a lock under /var/db/xbps and the cache dir
     * `/var/cache/xbps`. Stale lockfiles after a killed install
     * block subsequent runs — clear them defensively.
     */
    private const val CLEAR_LOCK =
        "rm -f /var/db/xbps/*.lock /var/cache/xbps/*.lock 2>/dev/null; " +
            // Belt-and-suspenders for the proot-distro Void tarball,
            // which doesn't include /var/db/xbps/metadata/. Without
            // this dir, xbps's per-package script extraction silently
            // drops the INSTALL/REMOVE files and the kernel reports
            // ENOENT on the next execve. Cheap to mkdir before every
            // op regardless of whether the bootstrap hook ran.
            "mkdir -p /var/db/xbps/metadata 2>/dev/null;"

    /**
     * Void's xbps refuses to install any user package until xbps
     * itself is current — it errors out with
     *     "The 'xbps' package must be updated, please run
     *      `xbps-install -u xbps`"
     * For the proot-distro Void tarball this happens basically
     * every install since the tarball's xbps was captured at build
     * time and Void rolls fast. Chaining `xbps-install -uy xbps`
     * after `-Sy` makes the self-update idempotent (no-op if
     * already current) and unblocks all subsequent installs.
     */
    /**
     * Void's self-update is genuinely circular: `xbps-install -u xbps`
     * starts the transaction (collects new libxbps, xbps, libssl3),
     * begins unpacking, then partway through the OLD xbps binary
     * detects it's not yet current and aborts with the same "must
     * be updated" error. The second invocation against the
     * partially-updated state succeeds (or no-ops if the first
     * actually completed). Use `;` not `&&` so the retry runs even
     * when the first attempt returns non-zero.
     */
    override fun updateCmd(): String =
        "$CLEAR_LOCK xbps-install -Sy; " +
            "$CLEAR_LOCK xbps-install -uy xbps; " +
            "$CLEAR_LOCK xbps-install -uy xbps"

    override fun upgradeCmd(): String =
        "$CLEAR_LOCK xbps-install -Sy; " +
            "$CLEAR_LOCK xbps-install -uy xbps; " +
            "$CLEAR_LOCK xbps-install -uy xbps; " +
            "$CLEAR_LOCK xbps-install -uy"

    /**
     * Plain `xbps-install -Sy` runs pre/post INSTALL scripts
     * correctly under proot — provided `/var/db/xbps/metadata/`
     * exists so xbps can write each package's INSTALL/REMOVE
     * scripts there. The bootstrap hook mkdir's that directory
     * (the proot-distro Void tarball omits it). Without the dir,
     * xbps surfaces a misleading "INSTALL script failed to execute
     * pre ACTION: No such file or directory" — the symptom that
     * drove the earlier (now removed) `-Uy + xbps-reconfigure`
     * workaround.
     */
    override fun installCmd(pkgs: List<String>): String =
        "$CLEAR_LOCK xbps-install -Sy ${pkgs.joinToString(" ")}"

    override fun removeCmd(pkgs: List<String>): String =
        "$CLEAR_LOCK xbps-remove -y ${pkgs.joinToString(" ")}"

    override fun installSucceeded(output: String): Boolean =
        output.contains("unpacked.") || output.contains("installed successfully")

    override fun looksLikeStaleDb(output: String): Boolean =
        output.contains("Missing package") ||
            output.contains("Unsatisfied dependency") ||
            output.contains("No matching package") ||
            // The "xbps must be updated" symptom — falls through to
            // upgradeCmd (which now self-updates xbps) and retries.
            output.contains("must be updated, please run") ||
            output.contains("failed to download") ||
            output.contains("404 Not Found")
}
