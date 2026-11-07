package sh.haven.core.local.proot

import java.io.File

/**
 * Selectable package-mirror region for proot distros (issue #162 / #263).
 *
 * [DEFAULT] keeps each distro's shipped CDN. The others repoint the
 * distro's repo config at a regional mirror so users far from the
 * default CDN edge (or behind a slow path to it) can fetch faster, and
 * to side-step the occasional default-mirror sync race.
 */
enum class MirrorRegion { DEFAULT, EUROPE, ASIA, AMERICAS }

/**
 * One host-base substitution for a distro's repo config files.
 *
 * [files] are paths relative to the rootfs root. [bases] maps each
 * region to the URL base that should appear in those files. The rewrite
 * ([MirrorCatalog.apply]) matches **any** base present in [bases] and
 * swaps it for the chosen region's base — so it is idempotent and works
 * in any direction (region→region, region→DEFAULT). Only the host+path
 * base is touched; suite / arch / component suffixes after it are left
 * verbatim, so the rewrite survives tarball changes to those.
 */
data class MirrorSub(
    val files: List<String>,
    val bases: Map<MirrorRegion, String>,
)

/**
 * Per-distro mirror definitions + the rootfs rewrite.
 *
 * Mirror hosts were verified reachable (HTTP 200 against each repo's
 * index / Release file) on 2026-06-24. The default entries match the
 * base the proot-distro / minirootfs tarballs actually ship (confirmed
 * by reading each installed rootfs), so DEFAULT is a true no-op.
 */
object MirrorCatalog {
    private val ALPINE = MirrorSub(
        files = listOf("etc/apk/repositories"),
        bases = mapOf(
            MirrorRegion.DEFAULT to "https://dl-cdn.alpinelinux.org/alpine",
            MirrorRegion.EUROPE to "https://ftp.halifax.rwth-aachen.de/alpine",
            MirrorRegion.ASIA to "https://mirrors.tuna.tsinghua.edu.cn/alpine",
            MirrorRegion.AMERICAS to "https://mirror.csclub.uwaterloo.ca/alpine",
        ),
    )

    // Debian security stays on its own CDN (security.debian.org) in every
    // region — it's small and already global — so only the main archive
    // base is swapped here.
    private val DEBIAN = MirrorSub(
        files = listOf("etc/apt/sources.list"),
        bases = mapOf(
            MirrorRegion.DEFAULT to "http://deb.debian.org/debian",
            MirrorRegion.EUROPE to "http://ftp.de.debian.org/debian",
            MirrorRegion.ASIA to "http://mirrors.tuna.tsinghua.edu.cn/debian",
            MirrorRegion.AMERICAS to "http://ftp.us.debian.org/debian",
        ),
    )

    // Ubuntu arm64/armhf packages on 24.04 (and armhf on 26.04) come from
    // ports.ubuntu.com/ubuntu-ports; Ubuntu 26.04 ubuntu-base also uses
    // deb822 /etc/apt/sources.list.d/ubuntu.sources and archive.ubuntu.com.
    private val UBUNTU = MirrorSub(
        files = listOf("etc/apt/sources.list", "etc/apt/sources.list.d/ubuntu.sources"),
        bases = mapOf(
            MirrorRegion.DEFAULT to "http://ports.ubuntu.com/ubuntu-ports",
            MirrorRegion.EUROPE to "http://de.ports.ubuntu.com/ubuntu-ports",
            MirrorRegion.ASIA to "http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports",
            MirrorRegion.AMERICAS to "http://us.ports.ubuntu.com/ubuntu-ports",
        ),
    )

    private val UBUNTU_ARCHIVE = MirrorSub(
        files = listOf("etc/apt/sources.list", "etc/apt/sources.list.d/ubuntu.sources"),
        bases = mapOf(
            MirrorRegion.DEFAULT to "http://archive.ubuntu.com/ubuntu",
            MirrorRegion.EUROPE to "http://de.archive.ubuntu.com/ubuntu",
            MirrorRegion.ASIA to "http://mirrors.tuna.tsinghua.edu.cn/ubuntu",
            MirrorRegion.AMERICAS to "http://us.archive.ubuntu.com/ubuntu",
        ),
    )

    // The default Arch Linux ARM host geo-redirects already, so ASIA is
    // intentionally absent (falls back to DEFAULT → nearest mirror).
    private val ARCH = MirrorSub(
        files = listOf("etc/pacman.d/mirrorlist"),
        bases = mapOf(
            MirrorRegion.DEFAULT to "http://mirror.archlinuxarm.org",
            MirrorRegion.EUROPE to "http://de.mirror.archlinuxarm.org",
            MirrorRegion.AMERICAS to "http://fl.us.mirror.archlinuxarm.org",
        ),
    )

    // Void's default repo conf lives under /usr/share; xbps reads it
    // directly. Rewriting it in place is simplest (an /etc override would
    // also work but leaves the picker unable to revert to DEFAULT cleanly).
    private val VOID = MirrorSub(
        files = listOf("usr/share/xbps.d/00-repository-main.conf"),
        bases = mapOf(
            MirrorRegion.DEFAULT to "https://repo-default.voidlinux.org",
            MirrorRegion.EUROPE to "https://repo-fi.voidlinux.org",
            MirrorRegion.ASIA to "https://repo-sg.voidlinux.org",
            MirrorRegion.AMERICAS to "https://mirrors.servercentral.com/voidlinux",
        ),
    )

    /** Subs for [distroId], or empty if the distro has no mirror options. */
    fun forDistro(distroId: String): List<MirrorSub> = when (distroId) {
        "alpine-3.21" -> listOf(ALPINE)
        "debian-trixie" -> listOf(DEBIAN)
        "debian-bookworm" -> listOf(DEBIAN)
        "ubuntu-noble" -> listOf(UBUNTU, UBUNTU_ARCHIVE)
        "ubuntu-resolute" -> listOf(UBUNTU, UBUNTU_ARCHIVE)
        "archlinux" -> listOf(ARCH)
        "void" -> listOf(VOID)
        else -> emptyList()
    }

    /** Whether [distroId] offers a mirror picker at all. */
    fun hasMirrors(distroId: String): Boolean = forDistro(distroId).isNotEmpty()

    /**
     * Rewrite [distroId]'s repo config files under [rootfsDir] to point at
     * [region]'s mirror. Idempotent and reversible: every sub matches any
     * known base, so re-applying or switching back to DEFAULT works on an
     * already-rewritten file. A region absent for a distro falls back to
     * that distro's DEFAULT base. Missing files are skipped (e.g. before
     * extract). Returns the relative paths actually changed.
     */
    fun apply(rootfsDir: File, distroId: String, region: MirrorRegion): List<String> {
        val changed = mutableListOf<String>()
        for (sub in forDistro(distroId)) {
            val chosen = sub.bases[region] ?: sub.bases[MirrorRegion.DEFAULT] ?: continue
            // Longest-first so a base that's a prefix of another can't
            // shadow it; lambda replacement avoids $-group interpretation.
            val pattern = sub.bases.values.distinct()
                .sortedByDescending { it.length }
                .joinToString("|") { Regex.escape(it) }
            val regex = Regex(pattern)
            for (rel in sub.files) {
                val f = File(rootfsDir, rel)
                if (!f.isFile) continue
                val before = f.readText()
                val after = regex.replace(before) { chosen }
                if (after != before) {
                    f.writeText(after)
                    changed += rel
                }
            }
        }
        return changed
    }
}
