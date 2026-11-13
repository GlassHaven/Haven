package sh.haven.app.desktop

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.local.GuestServiceManager
import sh.haven.core.local.ProotManager
import sh.haven.core.local.proot.GuestServiceCatalog

class GuestServicePresetTest {

    @Test
    fun `GuestServiceManager persists MariaDB spec and bumps registryRevision`() {
        val rootfs = Files.createTempDirectory("haven-svc-reg").toFile()
        try {
            val proot = mockk<ProotManager>(relaxed = true) {
                every { activeRootfsDir } returns rootfs
            }
            val mgr = GuestServiceManager(mockk<Context>(relaxed = true), proot)
            val rev0 = mgr.registryRevision.value
            val preset = GuestServiceCatalog.MARIADB
            mgr.register(
                GuestServiceManager.GuestServiceSpec(
                    id = preset.id,
                    label = preset.label,
                    command = preset.serviceCommand,
                    port = preset.port,
                    autostart = preset.autostart,
                ),
            )
            assertEquals(rev0 + 1, mgr.registryRevision.value)
            val saved = mgr.registered().single()
            assertEquals("mariadb", saved.id)
            assertEquals(3306, saved.port)

            mgr.unregister("mariadb")
            assertEquals(rev0 + 2, mgr.registryRevision.value)
            assertTrue(mgr.registered().isEmpty())
        } finally {
            rootfs.deleteRecursively()
        }
    }
}
