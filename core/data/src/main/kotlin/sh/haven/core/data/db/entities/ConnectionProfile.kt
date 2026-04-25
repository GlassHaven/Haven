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
    val vncUsername: String? = null,
    val vncPassword: String? = null,
    val vncSshForward: Boolean = true,
    val vncSshProfileId: String? = null,
    /**
     * VNC pixel format negotiated with the server. One of "BPP_24_TRUE"
     * (default), "BPP_16_TRUE", "BPP_8_TRUE", or "BPP_8_INDEXED". Lower
     * depths use less bandwidth on slow links — RealVNC uses 8-bit
     * indexed for mobile data, which is what users on 3 Mbps paths
     * need to make Haven usable (Nesos-ita, #107).
     */
    val vncColorDepth: String = "BPP_24_TRUE",
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
    /**
     * Request Network Level Authentication (CredSSP) during the RDP
     * handshake. Default true. Turn off to force SSL-only security —
     * needed for some servers where ironrdp's CredSSP doesn't interop
     * (seen with Windows Server 2025 Datacenter on #109). The server
     * must have "Require Network Level Authentication" disabled for
     * SSL-only mode to connect.
     */
    val rdpUseNla: Boolean = true,
    /**
     * RDP colour depth in bits per pixel. Default 16 — the only
     * value that works against every server (Windows + xrdp).
     * Empirical matrix from #109:
     *   16: Windows ✓ (slow), xrdp ✓
     *   24: Windows ✗ (server resets connection), xrdp ✓
     *   32: Windows ✓ (smoother), xrdp ✗ (blank — custom RLE
     *        ironrdp can't decode)
     * Per-profile so users can pick 24 for xrdp or 32 for Windows
     * once they know their server type.
     */
    val rdpColorDepth: Int = 16,
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
    /** Use native Android shell instead of PRoot for local connections. */
    val useAndroidShell: Boolean = false,
    /** Custom mosh-server command (overrides the default `mosh-server new -s -c 256 -l LANG=en_US.UTF-8`). */
    val moshServerCommand: String? = null,
    /** Enable SSH agent forwarding (OpenSSH `ForwardAgent`) — exposes non-encrypted stored SSH keys to the remote session. */
    val forwardAgent: Boolean = false,
    /** IFAC network name for Reticulum gateway isolation (maps to ifacNetname). */
    val reticulumNetworkName: String? = null,
    /** IFAC passphrase for Reticulum gateway isolation (maps to ifacNetkey). */
    val reticulumPassphrase: String? = null,
    /** Command to send automatically after SSH login (e.g. "cd /app && clear"). */
    val postLoginCommand: String? = null,
    /** When true, post-login command runs before the session manager (default); when false, runs inside it. */
    val postLoginBeforeSessionManager: Boolean = true,
    /**
     * Preferred file-transfer transport for SSH profiles. "AUTO" (default)
     * tries SFTP and falls back to legacy SCP when the sftp-server subsystem
     * is unavailable; "SFTP" forces SFTP; "SCP" forces legacy scp -t/-f.
     * Ignored for non-SSH profiles.
     */
    val fileTransport: String = "AUTO",
    /**
     * Optional id of a [TunnelConfig] to route this profile's connection
     * through (per-app WireGuard / Tailscale — see #102). Null means
     * "connect directly via the system network".
     */
    val tunnelConfigId: String? = null,
) {
    enum class AuthType {
        PASSWORD,
        KEY,
    }

    /** Typed view of [fileTransport]. Unknown values fall back to [FileTransport.AUTO]. */
    enum class FileTransport { AUTO, SFTP, SCP }

    val fileTransportEnum: FileTransport
        get() = runCatching { FileTransport.valueOf(fileTransport) }.getOrDefault(FileTransport.AUTO)

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
