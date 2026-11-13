package sh.haven.core.local.proot

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestServiceCatalogTest {

    @Test
    fun `MariaDB preset covers APK APT PACMAN and XBPS on loopback 3306`() {
        val preset = GuestServiceCatalog.MARIADB
        assertEquals("mariadb", preset.id)
        assertEquals(3306, preset.port)
        assertEquals(
            setOf(
                PackageFamily.APK,
                PackageFamily.APT,
                PackageFamily.PACMAN,
                PackageFamily.XBPS,
            ),
            preset.packages.keys,
        )
        assertTrue(preset.serviceCommand.contains("--no-defaults"))
        assertTrue(preset.serviceCommand.contains("--user=root"))
        assertTrue(preset.serviceCommand.contains("--bind-address=127.0.0.1"))
        assertTrue(preset.serviceCommand.contains("--port=3306"))
        assertTrue(preset.serviceCommand.contains("--innodb-use-native-aio=0"))
    }

    @Test
    fun `buildServicePresetInstallScript emits family ops config guard and datadir init`() {
        val preset = GuestServiceCatalog.MARIADB
        val script = buildServicePresetInstallScript(
            preset = preset,
            pkgs = preset.packages.getValue(PackageFamily.APT),
            family = PackageFamily.APT,
        )
        assertTrue(script.startsWith("set -e\n"))
        assertTrue(script.contains("apt-get install -y --no-install-recommends --fix-missing mariadb-server mariadb-client"))
        assertTrue(script.contains("grep -qxF '[client]' '/root/.my.cnf'"))
        assertTrue(script.contains("mariadb-install-db"))
        assertTrue(script.contains("--datadir=/var/lib/haven-mariadb"))
        assertTrue(script.contains("--auth-root-authentication-method=normal"))
    }

    @Test
    fun `isBinaryPresent checks usr bin and usr sbin mariadbd locations`() {
        val rootfs = Files.createTempDirectory("haven-mariadb-rootfs").toFile()
        try {
            assertFalse(GuestServiceCatalog.MARIADB.isBinaryPresent(rootfs))
            val sbinBin = File(rootfs, "usr/sbin/mariadbd").apply {
                parentFile?.mkdirs()
                writeText("#!/bin/sh\n")
            }
            assertTrue(sbinBin.exists())
            assertTrue(GuestServiceCatalog.MARIADB.isBinaryPresent(rootfs))
        } finally {
            rootfs.deleteRecursively()
        }
    }
}
