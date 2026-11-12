package sh.haven.core.local.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [MirrorCatalog] host-base rewrite coverage (#263 / issue #162).
 *
 * The repo-file fixtures below are the actual shipped contents read off
 * each installed rootfs on-device (2026-06-24), so the substitution is
 * exercised against the real format it must edit, not an idealised one.
 */
class MirrorCatalogTest {

    private fun rootfs(): File = Files.createTempDirectory("mirror-test").toFile()

    private fun write(root: File, rel: String, content: String) {
        File(root, rel).apply { parentFile?.mkdirs(); writeText(content) }
    }

    private fun read(root: File, rel: String) = File(root, rel).readText()

    // --- Alpine -----------------------------------------------------------

    private val alpineDefault =
        "https://dl-cdn.alpinelinux.org/alpine/v3.21/main\n" +
            "https://dl-cdn.alpinelinux.org/alpine/v3.21/community\n"

    @Test
    fun `alpine europe swaps host and keeps suite suffix`() {
        val root = rootfs()
        write(root, "etc/apk/repositories", alpineDefault)
        val changed = MirrorCatalog.apply(root, "alpine-3.21", MirrorRegion.EUROPE)
        assertEquals(listOf("etc/apk/repositories"), changed)
        assertEquals(
            "https://ftp.halifax.rwth-aachen.de/alpine/v3.21/main\n" +
                "https://ftp.halifax.rwth-aachen.de/alpine/v3.21/community\n",
            read(root, "etc/apk/repositories"),
        )
    }

    @Test
    fun `default on a pristine file is a no-op (no change reported)`() {
        val root = rootfs()
        write(root, "etc/apk/repositories", alpineDefault)
        val changed = MirrorCatalog.apply(root, "alpine-3.21", MirrorRegion.DEFAULT)
        assertTrue(changed.isEmpty())
        assertEquals(alpineDefault, read(root, "etc/apk/repositories"))
    }

    @Test
    fun `region switch and revert-to-default both work on an already-rewritten file`() {
        val root = rootfs()
        write(root, "etc/apk/repositories", alpineDefault)
        MirrorCatalog.apply(root, "alpine-3.21", MirrorRegion.ASIA)
        assertTrue(read(root, "etc/apk/repositories").contains("mirrors.tuna.tsinghua.edu.cn"))
        // Asia -> Americas
        MirrorCatalog.apply(root, "alpine-3.21", MirrorRegion.AMERICAS)
        assertTrue(read(root, "etc/apk/repositories").contains("mirror.csclub.uwaterloo.ca"))
        assertFalse(read(root, "etc/apk/repositories").contains("tuna"))
        // Americas -> back to default
        MirrorCatalog.apply(root, "alpine-3.21", MirrorRegion.DEFAULT)
        assertEquals(alpineDefault, read(root, "etc/apk/repositories"))
    }

    @Test
    fun `apply is idempotent`() {
        val root = rootfs()
        write(root, "etc/apk/repositories", alpineDefault)
        MirrorCatalog.apply(root, "alpine-3.21", MirrorRegion.EUROPE)
        val once = read(root, "etc/apk/repositories")
        val secondChange = MirrorCatalog.apply(root, "alpine-3.21", MirrorRegion.EUROPE)
        assertTrue(secondChange.isEmpty())
        assertEquals(once, read(root, "etc/apk/repositories"))
    }

    // --- Debian: main swapped, security CDN preserved ---------------------

    @Test
    fun `debian swaps main archive but leaves security cdn untouched`() {
        val root = rootfs()
        val src =
            "deb [signed-by=\"/usr/share/keyrings/debian-archive-keyring.gpg\"] http://deb.debian.org/debian bookworm main contrib\n" +
                "deb [signed-by=\"/usr/share/keyrings/debian-archive-keyring.gpg\"] http://deb.debian.org/debian bookworm-updates main contrib\n" +
                "deb [signed-by=\"/usr/share/keyrings/debian-archive-keyring.gpg\"] http://security.debian.org/debian-security bookworm-security main contrib\n"
        write(root, "etc/apt/sources.list", src)
        MirrorCatalog.apply(root, "debian-bookworm", MirrorRegion.AMERICAS)
        val out = read(root, "etc/apt/sources.list")
        assertTrue(out.contains("http://ftp.us.debian.org/debian bookworm main"))
        assertTrue(out.contains("http://ftp.us.debian.org/debian bookworm-updates"))
        // security host preserved
        assertTrue(out.contains("http://security.debian.org/debian-security bookworm-security"))
    }

    // --- Ubuntu ports & archive (deb822 + legacy) -------------------------

    @Test
    fun `ubuntu rewrites the ports tree host`() {
        val root = rootfs()
        val src =
            "deb [signed-by=\"/usr/share/keyrings/ubuntu-archive-keyring.gpg\"] http://ports.ubuntu.com/ubuntu-ports noble main universe multiverse\n" +
                "deb [signed-by=\"/usr/share/keyrings/ubuntu-archive-keyring.gpg\"] http://ports.ubuntu.com/ubuntu-ports noble-updates main universe multiverse\n"
        write(root, "etc/apt/sources.list", src)
        MirrorCatalog.apply(root, "ubuntu-noble", MirrorRegion.EUROPE)
        val out = read(root, "etc/apt/sources.list")
        assertTrue(out.contains("http://de.ports.ubuntu.com/ubuntu-ports noble main"))
        assertFalse(out.contains("http://ports.ubuntu.com"))
    }

    @Test
    fun `ubuntu resolute rewrites deb822 ubuntu sources for both archive and ports`() {
        val root = rootfs()
        val deb822 = """
            Types: deb
            URIs: http://archive.ubuntu.com/ubuntu/
            Suites: resolute resolute-updates resolute-backports
            Components: main universe restricted multiverse
            Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg

            Types: deb
            URIs: http://ports.ubuntu.com/ubuntu-ports/
            Suites: resolute-security
            Components: main universe restricted multiverse
            Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
        """.trimIndent() + "\n"
        write(root, "etc/apt/sources.list.d/ubuntu.sources", deb822)
        val changed = MirrorCatalog.apply(root, "ubuntu-resolute", MirrorRegion.ASIA)
        assertTrue(changed.contains("etc/apt/sources.list.d/ubuntu.sources"))
        val out = read(root, "etc/apt/sources.list.d/ubuntu.sources")
        assertTrue(out.contains("URIs: http://mirrors.tuna.tsinghua.edu.cn/ubuntu/"))
        assertTrue(out.contains("URIs: http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/"))
        // Revert to default
        MirrorCatalog.apply(root, "ubuntu-resolute", MirrorRegion.DEFAULT)
        assertEquals(deb822, read(root, "etc/apt/sources.list.d/ubuntu.sources"))
    }

    @Test
    fun `ubuntu resolute is registered in DistroCatalog builtins with verified sources`() {
        assertTrue(DistroCatalog.isBuiltin("ubuntu-resolute"))
        val resolute = DistroCatalog.lookup("ubuntu-resolute")!!
        assertEquals("Ubuntu 26.04 LTS (Resolute)", resolute.label)
        assertEquals(PackageFamily.APT, resolute.family)
        assertEquals(3, resolute.rootfsSources.size)
        assertTrue(resolute.rootfsSources.values.all { it.sha256.length == 64 && it.stripComponents == 0 })
        assertTrue("locales" in resolute.baselinePackages)
    }

    // --- Arch: ASIA falls back to the geo-redirect default ----------------

    @Test
    fun `arch europe swaps host keeping pacman variables`() {
        val root = rootfs()
        write(root, "etc/pacman.d/mirrorlist", "Server = http://mirror.archlinuxarm.org/\$arch/\$repo\n")
        MirrorCatalog.apply(root, "archlinux", MirrorRegion.EUROPE)
        assertEquals(
            "Server = http://de.mirror.archlinuxarm.org/\$arch/\$repo\n",
            read(root, "etc/pacman.d/mirrorlist"),
        )
    }

    @Test
    fun `arch asia falls back to default host (no asia mirror defined)`() {
        val root = rootfs()
        val src = "Server = http://mirror.archlinuxarm.org/\$arch/\$repo\n"
        write(root, "etc/pacman.d/mirrorlist", src)
        val changed = MirrorCatalog.apply(root, "archlinux", MirrorRegion.ASIA)
        assertTrue(changed.isEmpty())
        assertEquals(src, read(root, "etc/pacman.d/mirrorlist"))
    }

    @Test
    fun `arch x86_64 default replaces broken rackspace mirror with geo pkgbuild and fastly fallback (#840)`() {
        val root = rootfs()
        write(root, "etc/pacman.d/mirrorlist", "Server = http://mirror.rackspace.com/archlinux/\$repo/os/\$arch\n")
        val changed = MirrorCatalog.apply(root, "archlinux-x86_64", MirrorRegion.DEFAULT)
        assertEquals(listOf("etc/pacman.d/mirrorlist"), changed)
        val expected =
            "Server = https://geo.mirror.pkgbuild.com/\$repo/os/\$arch\n" +
                "Server = https://fastly.mirror.pkgbuild.com/\$repo/os/\$arch\n"
        assertEquals(expected, read(root, "etc/pacman.d/mirrorlist"))

        // Second apply on DEFAULT is idempotent.
        val second = MirrorCatalog.apply(root, "archlinux-x86_64", MirrorRegion.DEFAULT)
        assertTrue(second.isEmpty())
        assertEquals(expected, read(root, "etc/pacman.d/mirrorlist"))
    }

    @Test
    fun `arch x86_64 region switch and revert preserve single fastly fallback without reverting to rackspace (#840)`() {
        val root = rootfs()
        write(root, "etc/pacman.d/mirrorlist", "Server = http://mirror.rackspace.com/archlinux/\$repo/os/\$arch\n")
        MirrorCatalog.apply(root, "archlinux-x86_64", MirrorRegion.EUROPE)
        assertEquals(
            "Server = https://ftp.halifax.rwth-aachen.de/archlinux/\$repo/os/\$arch\n" +
                "Server = https://fastly.mirror.pkgbuild.com/\$repo/os/\$arch\n",
            read(root, "etc/pacman.d/mirrorlist"),
        )
        MirrorCatalog.apply(root, "archlinux-x86_64", MirrorRegion.ASIA)
        assertEquals(
            "Server = https://mirrors.tuna.tsinghua.edu.cn/archlinux/\$repo/os/\$arch\n" +
                "Server = https://fastly.mirror.pkgbuild.com/\$repo/os/\$arch\n",
            read(root, "etc/pacman.d/mirrorlist"),
        )
        MirrorCatalog.apply(root, "archlinux-x86_64", MirrorRegion.AMERICAS)
        assertEquals(
            "Server = https://mirrors.kernel.org/archlinux/\$repo/os/\$arch\n" +
                "Server = https://fastly.mirror.pkgbuild.com/\$repo/os/\$arch\n",
            read(root, "etc/pacman.d/mirrorlist"),
        )
        MirrorCatalog.apply(root, "archlinux-x86_64", MirrorRegion.DEFAULT)
        assertEquals(
            "Server = https://geo.mirror.pkgbuild.com/\$repo/os/\$arch\n" +
                "Server = https://fastly.mirror.pkgbuild.com/\$repo/os/\$arch\n",
            read(root, "etc/pacman.d/mirrorlist"),
        )
    }

    @Test
    fun `custom user arch mirrorlist is left untouched (#840)`() {
        val root = rootfs()
        val custom = "Server = https://mirror.23m.com/archlinux/\$repo/os/\$arch\n"
        write(root, "etc/pacman.d/mirrorlist", custom)
        assertTrue(MirrorCatalog.apply(root, "archlinux-x86_64", MirrorRegion.DEFAULT).isEmpty())
        assertTrue(MirrorCatalog.apply(root, "archlinux-x86_64", MirrorRegion.EUROPE).isEmpty())
        assertEquals(custom, read(root, "etc/pacman.d/mirrorlist"))
    }

    @Test
    fun `foreign-arch catalog distros inherit parent mirror definitions (#840)`() {
        assertTrue(MirrorCatalog.hasMirrors("archlinux-x86_64"))
        assertTrue(MirrorCatalog.hasMirrors("debian-trixie-x86_64"))
        assertTrue(MirrorCatalog.hasMirrors("ubuntu-noble-x86_64"))
        assertTrue(MirrorCatalog.hasMirrors("void-x86_64"))
        assertTrue(MirrorCatalog.hasMirrors("alpine-3.21-x86_64"))
        assertFalse(MirrorCatalog.hasMirrors("my-custom-tarball"))
    }

    // --- Void -------------------------------------------------------------

    @Test
    fun `void swaps repo host keeping current arch path`() {
        val root = rootfs()
        write(
            root,
            "usr/share/xbps.d/00-repository-main.conf",
            "repository=https://repo-default.voidlinux.org/current/aarch64\n",
        )
        MirrorCatalog.apply(root, "void", MirrorRegion.ASIA)
        assertEquals(
            "repository=https://repo-sg.voidlinux.org/current/aarch64\n",
            read(root, "usr/share/xbps.d/00-repository-main.conf"),
        )
    }

    // --- Misc -------------------------------------------------------------

    @Test
    fun `unknown distro has no mirrors and apply is a no-op`() {
        val root = rootfs()
        assertFalse(MirrorCatalog.hasMirrors("fedora"))
        assertTrue(MirrorCatalog.apply(root, "fedora", MirrorRegion.EUROPE).isEmpty())
    }

    @Test
    fun `missing repo file is skipped without error`() {
        val root = rootfs()
        // No file written.
        assertTrue(MirrorCatalog.apply(root, "alpine-3.21", MirrorRegion.EUROPE).isEmpty())
    }
}
