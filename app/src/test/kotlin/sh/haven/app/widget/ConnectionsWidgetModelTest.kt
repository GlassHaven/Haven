package sh.haven.app.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.data.db.entities.ConnectionProfile

class ConnectionsWidgetModelTest {

    @Test
    fun `excludes ephemeral openai and email profiles and preserves saved sortOrder`() {
        val profiles = listOf(
            ConnectionProfile(
                id = "b",
                label = "Beta",
                host = "beta.internal",
                username = "ian",
                sortOrder = 2,
                sshPassword = "SUPER_SECRET_PASSWORD",
            ),
            ConnectionProfile(
                id = "a",
                label = "Alpha",
                host = "alpha.internal",
                port = 2222,
                username = "root",
                sortOrder = 1,
                useMosh = true,
            ),
            ConnectionProfile(
                id = "ai",
                label = "Local Ollama",
                host = "127.0.0.1",
                port = 11434,
                username = "",
                connectionType = "OPENAI",
            ),
            ConnectionProfile(
                id = "mail",
                label = "Inbox",
                host = "imap.example.com",
                username = "u@example.com",
                connectionType = "EMAIL",
            ),
            ConnectionProfile(
                id = "tmp",
                label = "Temp quick connect",
                host = "10.0.0.9",
                username = "root",
            ).apply { ephemeral = true },
        )

        val rows = ConnectionsWidgetModel.buildRows(profiles)
        assertEquals(listOf("a", "b"), rows.map { it.profileId })
        assertEquals("MOSH", rows[0].badge)
        assertEquals("root@alpha.internal:2222", rows[0].subtitle)
        assertEquals("SSH", rows[1].badge)
        assertEquals("ian@beta.internal", rows[1].subtitle)
        assertTrue(rows.none { it.subtitle.contains("SUPER_SECRET_PASSWORD") })
    }

    @Test
    fun `filters by groupId and sorts recent first with never-connected in saved order`() {
        val profiles = listOf(
            ConnectionProfile(
                id = "p1",
                label = "Router",
                host = "192.168.1.1",
                username = "root",
                groupId = "homelab",
                sortOrder = 1,
                lastConnected = 100L,
            ),
            ConnectionProfile(
                id = "p2",
                label = "NAS",
                host = "192.168.1.10",
                username = "admin",
                groupId = "homelab",
                sortOrder = 2,
                lastConnected = 500L,
            ),
            ConnectionProfile(
                id = "p3",
                label = "Backup Box",
                host = "192.168.1.20",
                username = "backup",
                groupId = "homelab",
                sortOrder = 0,
                lastConnected = null,
            ),
            ConnectionProfile(
                id = "work",
                label = "Prod Bastion",
                host = "bastion.corp",
                username = "ops",
                groupId = "work",
                sortOrder = 0,
                lastConnected = 999L,
            ),
        )

        val rows = ConnectionsWidgetModel.buildRows(
            profiles = profiles,
            config = WidgetConfig(
                groupId = "homelab",
                sortOrder = WidgetSortOrder.RECENT_FIRST,
            ),
        )
        assertEquals(listOf("p2", "p1", "p3"), rows.map { it.profileId })
    }

    @Test
    fun `formats protocol badges and subtitles across RDP VNC GLKVM RCLONE and SMB`() {
        val profiles = listOf(
            ConnectionProfile(
                id = "rdp1",
                label = "Workstation",
                host = "win.lan",
                username = "",
                rdpUsername = "ian",
                rdpPort = 3389,
                connectionType = "RDP",
                rdpPassword = "HIDDEN_RDP_SECRET",
                sortOrder = 1,
            ),
            ConnectionProfile(
                id = "vnc1",
                label = "Pi Desktop",
                host = "pi.lan",
                username = "",
                vncPort = 5901,
                connectionType = "VNC",
                sortOrder = 2,
            ),
            ConnectionProfile(
                id = "kvm1",
                label = "Comet KVM",
                host = "kvm.lan",
                username = "admin",
                glkvmPort = 443,
                connectionType = "GLKVM",
                sortOrder = 3,
            ),
            ConnectionProfile(
                id = "rc1",
                label = "Drive",
                host = "",
                username = "",
                connectionType = "RCLONE",
                rcloneProvider = "drive",
                rcloneRemoteName = "work-drive",
                sortOrder = 4,
            ),
            ConnectionProfile(
                id = "smb1",
                label = "Media Share",
                host = "nas.lan",
                username = "media",
                connectionType = "SMB",
                smbShare = "movies",
                sortOrder = 5,
            ),
        )

        val rows = ConnectionsWidgetModel.buildRows(profiles).associateBy { it.profileId }
        assertEquals("RDP" to "ian@win.lan", rows.getValue("rdp1").let { it.badge to it.subtitle })
        assertEquals("VNC" to "pi.lan:5901", rows.getValue("vnc1").let { it.badge to it.subtitle })
        assertEquals("KVM" to "kvm.lan", rows.getValue("kvm1").let { it.badge to it.subtitle })
        assertEquals("CLOUD" to "drive · work-drive", rows.getValue("rc1").let { it.badge to it.subtitle })
        assertEquals("SMB" to "nas.lan/movies", rows.getValue("smb1").let { it.badge to it.subtitle })
        assertFalse(rows.values.any { it.subtitle.contains("HIDDEN_RDP_SECRET") })
    }
}
