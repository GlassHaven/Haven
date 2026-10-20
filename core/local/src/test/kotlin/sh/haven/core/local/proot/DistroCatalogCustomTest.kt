package sh.haven.core.local.proot

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runtime-extensible catalog coverage (#284): a user-imported distro must
 * become a first-class catalog entry (lookup / all) without colliding with
 * the built-ins, and clearing the registry must restore the built-ins-only
 * view. [DistroCatalog] is a process-global object, so each test restores
 * the empty registry afterwards.
 */
class DistroCatalogCustomTest {

    @After
    fun tearDown() {
        DistroCatalog.registerCustomDistros(emptyList())
    }

    @Test
    fun `builtins are recognised and not importable`() {
        assertTrue(DistroCatalog.isBuiltin("alpine-3.21"))
        assertTrue(DistroCatalog.isBuiltin("debian-bookworm"))
        assertFalse(DistroCatalog.isBuiltin("my-custom"))
    }

    @Test
    fun `registered custom distro appears in all and lookup`() {
        val custom = DistroCatalog.customDistro("ubuntu-trixie", "Ubuntu (imported)", PackageFamily.APT, 180)
        DistroCatalog.registerCustomDistros(listOf(custom))

        assertEquals(custom, DistroCatalog.lookup("ubuntu-trixie"))
        assertTrue(DistroCatalog.all.any { it.id == "ubuntu-trixie" })
        // Built-ins still present.
        assertNotNull(DistroCatalog.lookup("alpine-3.21"))
        assertEquals(DistroCatalog.builtins.size + 1, DistroCatalog.all.size)
        // A custom id is not a built-in.
        assertFalse(DistroCatalog.isBuiltin("ubuntu-trixie"))
    }

    @Test
    fun `customDistro has no download sources or hooks (raw mode)`() {
        val custom = DistroCatalog.customDistro("x", "X", PackageFamily.PACMAN, 50)
        assertTrue(custom.rootfsSources.isEmpty())
        assertTrue(custom.baselinePackages.isEmpty())
        assertTrue(custom.postExtractHooks.isEmpty())
        assertEquals(PackageFamily.PACMAN, custom.family)
    }

    @Test
    fun `customDistro for foreign-arch builtin inherits hooks and baselinePackages`() {
        val archForeign = DistroCatalog.customDistro(
            id = "archlinux-x86_64",
            label = "Arch Linux (x86_64 · QEMU)",
            family = PackageFamily.PACMAN,
            sizeMb = 620,
        )
        assertEquals(DistroCatalog.ARCH_LINUX, DistroCatalog.builtinForForeignId("archlinux-x86_64"))
        assertEquals(DistroCatalog.ARCH_LINUX.baselinePackages, archForeign.baselinePackages)
        assertEquals(DistroCatalog.ARCH_LINUX.postExtractHooks, archForeign.postExtractHooks)
        assertTrue("Expected sudo in Arch baselinePackages", archForeign.baselinePackages.contains("sudo"))

        val voidForeign = DistroCatalog.customDistro(
            id = "void-x86_64",
            label = "Void Linux (x86_64 · QEMU)",
            family = PackageFamily.XBPS,
            sizeMb = 100,
        )
        assertEquals(DistroCatalog.VOID_LINUX, DistroCatalog.builtinForForeignId("void-x86_64"))
        assertEquals(DistroCatalog.VOID_LINUX.postExtractHooks, voidForeign.postExtractHooks)
    }

    @Test
    fun `clearing the registry restores the builtins-only view`() {
        DistroCatalog.registerCustomDistros(listOf(DistroCatalog.customDistro("tmp", "Tmp", PackageFamily.APK, 1)))
        assertNotNull(DistroCatalog.lookup("tmp"))

        DistroCatalog.registerCustomDistros(emptyList())
        assertNull(DistroCatalog.lookup("tmp"))
        assertEquals(DistroCatalog.builtins.size, DistroCatalog.all.size)
    }
}

