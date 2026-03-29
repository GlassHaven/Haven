package sh.haven.core.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "connection_profiles")
data class ConnectionProfile(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val sshPassword: String? = null,
    val authType: AuthType = AuthType.PASSWORD,
    val keyId: String? = null,
    val colorTag: Int = 0,
    val lastConnected: Long? = null,
    val sortOrder: Int = 0,
    val connectionType: String = "SSH",
    val destinationHash: String? = null,
    val reticulumHost: String = "127.0.0.1",
    val reticulumPort: Int = 37428,
    val jumpProfileId: String? = null,
    val sshOptions: String? = null,
    val vncPort: Int? = null,
    val vncPassword: String? = null,
    val vncSshForward: Boolean = true,
    val sessionManager: String? = null,
    val useMosh: Boolean = false,
    val useEternalTerminal: Boolean = false,
    val etPort: Int = 2022,
    val rdpPort: Int = 3389,
    val rdpUsername: String? = null,
    val rdpDomain: String? = null,
    val rdpPassword: String? = null,
    val rdpSshForward: Boolean = false,
    val rdpSshProfileId: String? = null,
    val smbPort: Int = 445,
    val smbShare: String? = null,
    val smbDomain: String? = null,
    val smbPassword: String? = null,
    val smbSshForward: Boolean = false,
    val smbSshProfileId: String? = null,
    val proxyType: String? = null,       // "SOCKS5", "SOCKS4", "HTTP", or null (none)
    val proxyHost: String? = null,
    val proxyPort: Int = 1080,
    val groupId: String? = null,
    /** Last session manager session name used (for group launch restore). */
    val lastSessionName: String? = null,
    /** Disable alternate screen buffer (DECSET 1049) so scrollback works in screen/vim. */
    val disableAltScreen: Boolean = false,
    /** rclone remote name (e.g. "gdrive"). */
    val rcloneRemoteName: String? = null,
    /** rclone provider type (e.g. "drive", "s3", "dropbox"). */
    val rcloneProvider: String? = null,
) {
    enum class AuthType {
        PASSWORD,
        KEY,
    }

    val isSsh: Boolean get() = connectionType == "SSH"
    val isReticulum: Boolean get() = connectionType == "RETICULUM"
    val isMosh: Boolean get() = isSsh && useMosh
    val isEternalTerminal: Boolean get() = isSsh && useEternalTerminal
    val isVnc: Boolean get() = connectionType == "VNC"
    val isRdp: Boolean get() = connectionType == "RDP"
    val isSmb: Boolean get() = connectionType == "SMB"
    val isLocal: Boolean get() = connectionType == "LOCAL"
    val isRclone: Boolean get() = connectionType == "RCLONE"
    val isDesktop: Boolean get() = isVnc || isRdp
    val isTerminal: Boolean get() = !isDesktop && !isSmb && !isRclone
}
