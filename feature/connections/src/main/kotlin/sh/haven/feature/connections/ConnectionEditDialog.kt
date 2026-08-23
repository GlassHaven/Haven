package sh.haven.feature.connections

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ListItem
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PlatformImeOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateMapOf
import androidx.hilt.navigation.compose.hiltViewModel
import sh.haven.core.data.NativeFeatures
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.rclone.RCLONE_OAUTH_PROVIDERS
import sh.haven.core.knock.KnockSequence
import sh.haven.core.spa.SpaConfig
import sh.haven.feature.tunnel.CloudflareAccessLoginContract
import sh.haven.feature.tunnel.CloudflareInlineFields
import sh.haven.feature.tunnel.TunnelViewModel

/** Profile group colors — matches PROFILE_COLORS in ConnectionsScreen. */
private val EDIT_DIALOG_COLORS = listOf(
    Color(0xFF42A5F5), // blue
    Color(0xFF66BB6A), // green
    Color(0xFFFF7043), // orange
    Color(0xFFAB47BC), // purple
    Color(0xFFFFCA28), // amber
    Color(0xFF26C6DA), // cyan
    Color(0xFFEF5350), // red
    Color(0xFF8D6E63), // brown
)

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ConnectionEditDialog(
    existing: ConnectionProfile? = null,
    /**
     * Seed values for a *new* connection (e.g. a `haven://connect` deep link,
     * #305). Unlike [existing] it does not put the dialog in edit mode — the
     * title stays "New connection" and saving inserts — it only pre-fills the
     * initial host/user/port/transport/session-manager fields.
     */
    prefill: ConnectionProfile? = null,
    /**
     * Gate for revealing a stored credential field's value (#274). Non-null
     * only when editing an existing profile, where the password fields are
     * pre-filled with the decrypted secret; the eye toggle awaits this
     * (a biometric prompt) before unmasking. Null for a new connection — the
     * user is typing the value, so revealing it needs no auth.
     */
    onRevealSavedSecret: (suspend () -> Boolean)? = null,
    discoveredDestinations: List<sh.haven.core.reticulum.DiscoveredDestination> = emptyList(),
    discoveredHosts: List<DiscoveredHost> = emptyList(),
    discoveredSmbHosts: List<DiscoveredHost> = emptyList(),
    sshProfiles: List<ConnectionProfile> = emptyList(),
    groups: List<sh.haven.core.data.db.entities.ConnectionGroup> = emptyList(),
    identities: List<sh.haven.core.data.db.entities.SshIdentity> = emptyList(),
    sshKeys: List<sh.haven.core.data.db.entities.SshKey> = emptyList(),
    totpSecrets: List<sh.haven.core.data.db.entities.TotpSecret> = emptyList(),
    tunnelConfigs: List<sh.haven.core.data.db.entities.TunnelConfig> = emptyList(),
    /** Installed distros `(id, label)` for a LOCAL profile's "open in" picker. */
    availableDistros: List<Pair<String, String>> = emptyList(),
    /** Attached phone USB devices `(vidPid, label)` for the SSH USB-forward picker. */
    usbDevices: List<Pair<String, String>> = emptyList(),
    /**
     * The Cloudflare Tunnel transport row owned by [existing], if any
     * (GH #154). When non-null, the SSH profile editor renders inline CF
     * fields pre-populated from the decrypted blob; when null, the user
     * can opt in by picking "Cloudflare Tunnel" in the route-through
     * dropdown.
     */
    embeddedCloudflareTunnel: sh.haven.core.data.db.entities.TunnelConfig? = null,
    /**
     * Whether [existing] currently carries the MCP reverse-tunnel
     * port-forward rule (`-R 8730:127.0.0.1:8730`). Drives the initial
     * state of the SSH "Expose MCP server" toggle; the resolved value is
     * handed back through [onSave]'s third argument.
     */
    mcpReverseTunnelEnabled: Boolean = false,
    /**
     * Opens the Tunnels screen. When the preselect is non-null, the
     * destination should auto-open its Add dialog with that type
     * pre-selected. Used by the Route-through dropdown's quick-add
     * affordances; "Manage tunnels…" passes null. CLOUDFLARE_ACCESS is
     * never passed any more — that route lives inline on the profile.
     */
    onManageTunnels: ((preselect: sh.haven.core.data.db.entities.TunnelConfigType?) -> Unit)? = null,
    globalSessionManagerLabel: String = "None",
    subnetScanning: Boolean = false,
    smbSubnetScanning: Boolean = false,
    reticulumScanning: Boolean = false,
    onScanSubnet: () -> Unit = {},
    onScanSubnetSmb: () -> Unit = {},
    /** Scanning the network behind the selected jump host (#NNN). Opt-in. */
    jumpScanning: Boolean = false,
    jumpScanError: String? = null,
    onScanSubnetViaJump: (jumpProfileId: String) -> Unit = {},
    onScanReticulum: (host: String, port: Int, networkName: String?, passphrase: String?) -> Unit = { _, _, _, _ -> },
    /** Optional callback wired by the screen to fire a test knock and
     *  return `(ok, message)`. When null the "Test knock" button is
     *  hidden — useful for previews / tests. */
    onTestKnock: (suspend (host: String, sequence: String, delayMs: Int) -> Pair<Boolean, String>)? = null,
    /** Optional callback to fire a test SPA packet, returning `(ok, message)`.
     *  When null the "Test SPA" button is hidden. */
    onTestSpa: (suspend (host: String, config: SpaConfig) -> Pair<Boolean, String>)? = null,
    onDismiss: () -> Unit,
    /**
     * Save callback. The second argument carries inline Cloudflare Tunnel
     * transport state (GH #154); non-null means "save the profile and
     * upsert a hidden tunnel row owned by it", null means "save the
     * profile and drop any embedded tunnel previously owned by it".
     * Wired via [ConnectionsViewModel.saveProfileWithEmbeddedCloudflareTunnel].
     * The third argument is the MCP reverse-tunnel toggle state, reconciled
     * into a port-forward rule by the screen.
     */
    onSave: (ConnectionProfile, EmbeddedCloudflareTunnelInput?, Boolean) -> Unit,
) {
    // Initial field values come from the edited profile or, for a new
    // connection, an optional deep-link [prefill] (#305). [existing] alone
    // drives edit-mode (title, save semantics); [seed] only drives initials.
    val seed = existing ?: prefill
    // Transport dropdown maps to: connectionType + useMosh + useEternalTerminal
    val initialTransport = when {
        seed?.isLocal == true -> "LOCAL"
        seed?.isBtSerial == true -> "BTSERIAL"
        seed?.isBleSerial == true -> "BLESERIAL"
        seed?.isUsbSerial == true -> "USBSERIAL"
        seed?.isVnc == true -> "VNC"
        seed?.isRdp == true -> "RDP"
        seed?.isSpice == true -> "SPICE"
        seed?.isSmb == true -> "SMB"
        seed?.isRclone == true -> "RCLONE"
        seed?.isEmail == true -> "EMAIL"
        seed?.isEternalTerminal == true -> "ET"
        seed?.isMosh == true -> "MOSH"
        seed?.isReticulum == true -> "RETICULUM"
        else -> "SSH"
    }
    var selectedTransport by rememberSaveable { mutableStateOf(initialTransport) }
    // Derived connectionType for field visibility
    val connectionType = when (selectedTransport) {
        "LOCAL" -> "LOCAL"
        "BTSERIAL" -> "BTSERIAL"
        "BLESERIAL" -> "BLESERIAL"
        "USBSERIAL" -> "USBSERIAL"
        "RETICULUM" -> "RETICULUM"
        "VNC" -> "VNC"
        "RDP" -> "RDP"
        "SPICE" -> "SPICE"
        "SMB" -> "SMB"
        "RCLONE" -> "RCLONE"
        "EMAIL" -> "EMAIL"
        else -> "SSH"
    }
    var label by rememberSaveable { mutableStateOf(existing?.label ?: "") }
    var colorTag by rememberSaveable { mutableIntStateOf(existing?.colorTag ?: 0) }
    var groupId by rememberSaveable { mutableStateOf(existing?.groupId) }
    var identityId by rememberSaveable { mutableStateOf(existing?.identityId) }
    var host by rememberSaveable { mutableStateOf(seed?.host ?: "") }
    // Bluetooth-serial selected device MAC (#406); reuses `host` on save.
    var btDevice by rememberSaveable { mutableStateOf(seed?.takeIf { it.isBtSerial }?.host ?: "") }
    // BLE-serial selected device MAC (#DoveBoy BLE FR); reuse `host` on save.
    var bleDevice by rememberSaveable { mutableStateOf(seed?.takeIf { it.isBleSerial }?.host ?: "") }
    // USB-serial selected device vid:pid and baud (#408); reuse `host`/`port` on save.
    var usbDevice by rememberSaveable { mutableStateOf(seed?.takeIf { it.isUsbSerial }?.host ?: "") }
    var usbBaud by rememberSaveable { mutableStateOf((seed?.takeIf { it.isUsbSerial }?.usbBaudRate ?: 115200).toString()) }
    // USB-serial line format — seeded from the profile's packed config (sshOptions),
    // held as enum names, re-encoded into sshOptions on save. No schema bump.
    val usbSeed = seed?.takeIf { it.isUsbSerial }?.let {
        sh.haven.core.usbserial.UsbSerialParams.fromConfigString(it.usbBaudRate, it.usbSerialConfig)
    } ?: sh.haven.core.usbserial.UsbSerialParams()
    var usbDataBits by rememberSaveable { mutableStateOf(usbSeed.dataBits.toString()) }
    var usbParity by rememberSaveable { mutableStateOf(usbSeed.parity.name) }
    var usbStopBits by rememberSaveable { mutableStateOf(usbSeed.stopBits.name) }
    var usbFlow by rememberSaveable { mutableStateOf(usbSeed.flowControl.name) }
    var port by rememberSaveable {
        mutableStateOf(
            when {
                seed?.isVnc == true -> (seed.vncPort ?: 5900).toString()
                seed?.isRdp == true -> seed.rdpPort.toString()
                seed?.isSpice == true -> (seed.spicePort ?: 5900).toString()
                seed?.isSmb == true -> seed.smbPort.toString()
                else -> seed?.port?.toString() ?: "22"
            }
        )
    }
    var username by rememberSaveable { mutableStateOf(seed?.username ?: "") }
    var rdpUsername by rememberSaveable { mutableStateOf(existing?.rdpUsername ?: "") }
    var rdpPassword by rememberSaveable { mutableStateOf(existing?.rdpPassword ?: "") }
    var rdpDomain by rememberSaveable { mutableStateOf(existing?.rdpDomain ?: "") }
    var rdpSshForward by rememberSaveable { mutableStateOf(existing?.rdpSshForward ?: false) }
    var rdpSshProfileId by rememberSaveable { mutableStateOf(existing?.rdpSshProfileId) }
    var rdpUseNla by rememberSaveable { mutableStateOf(existing?.rdpUseNla ?: true) }
    var rdpColorDepth by rememberSaveable { mutableStateOf(existing?.rdpColorDepth ?: 32) }
    var spicePassword by rememberSaveable { mutableStateOf(existing?.spicePassword ?: "") }
    var spiceSshForward by rememberSaveable {
        mutableStateOf(existing?.let { strictTunnelInitialEnabled(it.connectionType, "SPICE", it.spiceSshForward, it.spiceSshProfileId) } ?: false)
    }
    var spiceSshProfileId by rememberSaveable { mutableStateOf(existing?.spiceSshProfileId) }
    var smbShare by rememberSaveable { mutableStateOf(existing?.smbShare ?: "") }
    var smbPassword by rememberSaveable { mutableStateOf(existing?.smbPassword ?: "") }
    var smbDomain by rememberSaveable { mutableStateOf(existing?.smbDomain ?: "") }
    var smbSshForward by rememberSaveable { mutableStateOf(existing?.smbSshForward ?: false) }
    var smbSshProfileId by rememberSaveable { mutableStateOf(existing?.smbSshProfileId) }
    var vncUsername by rememberSaveable { mutableStateOf(existing?.vncUsername ?: "") }
    var vncPassword by rememberSaveable { mutableStateOf(existing?.vncPassword ?: "") }
    var vncSshForward by rememberSaveable {
        mutableStateOf(existing?.let { strictTunnelInitialEnabled(it.connectionType, "VNC", it.vncSshForward, it.vncSshProfileId) } ?: false)
    }
    var vncSshProfileId by rememberSaveable { mutableStateOf(existing?.vncSshProfileId) }
    var vncColorDepth by rememberSaveable { mutableStateOf(existing?.vncColorDepth ?: "BPP_24_TRUE") }
    // Saved VNC settings on an SSH profile — editable via the SSH section when
    // the user has ticked "Save for this connection" in the terminal's VNC
    // quick-dialog. `null` means no VNC settings are stored on this profile,
    // so the section stays hidden (#104).
    var vncSavedPort by rememberSaveable {
        mutableStateOf(existing?.vncPort?.toString() ?: "")
    }
    var vncSavedSshForward by rememberSaveable {
        mutableStateOf(existing?.vncSshForward ?: true)
    }
    var vncSettingsStored by rememberSaveable {
        mutableStateOf(existing?.connectionType == "SSH" && existing?.vncPort != null)
    }
    var destinationHash by rememberSaveable { mutableStateOf(existing?.destinationHash ?: "") }
    var jumpProfileId by rememberSaveable { mutableStateOf(existing?.jumpProfileId) }
    var proxyType by rememberSaveable { mutableStateOf(existing?.proxyType) }
    var proxyHost by rememberSaveable { mutableStateOf(existing?.proxyHost ?: "") }
    var proxyPort by rememberSaveable { mutableStateOf(existing?.proxyPort?.toString() ?: "1080") }
    var proxyUser by rememberSaveable { mutableStateOf(existing?.proxyUser ?: "") }
    var proxyPassword by rememberSaveable { mutableStateOf(existing?.proxyPassword ?: "") }
    var keyId by rememberSaveable { mutableStateOf(existing?.keyId) }
    // #166: ordered auth methods, serialised so rememberSaveable can persist
    // it across config changes. Seeded from the profile's parsed specs (which
    // fall back to its legacy authType/keyId for pre-#166 profiles).
    var authMethodsText by rememberSaveable {
        mutableStateOf(
            ConnectionProfile.AuthMethodSpec.serializeList(
                existing?.authMethodSpecs
                    ?: listOf(ConnectionProfile.AuthMethodSpec.Password),
            ),
        )
    }
    var totpConfirmBeforeSend by rememberSaveable { mutableStateOf(existing?.totpConfirmBeforeSend ?: false) }
    var ignoreSavedKeys by rememberSaveable { mutableStateOf(existing?.ignoreSavedKeys ?: false) }
    var tunnelConfigId by rememberSaveable { mutableStateOf(existing?.tunnelConfigId) }

    // Cloudflare Tunnel transport (GH #154). When `useCloudflareTunnel`
    // is true the SSH profile uses an embedded `TunnelConfig` whose
    // `ownerProfileId` matches this profile. The hostname mirrors the
    // SSH `host` field, so the inline form skips a duplicate hostname
    // input. Initial state comes from the decoded embedded blob (if any);
    // otherwise the user opts in by picking "Cloudflare Tunnel" in the
    // route-through dropdown.
    val initialCfBlob = remember(embeddedCloudflareTunnel?.id) {
        embeddedCloudflareTunnel?.configText?.let {
            runCatching { sh.haven.core.tunnel.CloudflareAccessConfigBlob.parse(it) }.getOrNull()
        }
    }
    var useCloudflareTunnel by rememberSaveable {
        mutableStateOf(initialCfBlob != null)
    }
    var cfTeamDomain by rememberSaveable { mutableStateOf(initialCfBlob?.teamDomain ?: "") }
    var cfJwt by rememberSaveable { mutableStateOf(initialCfBlob?.jwt ?: "") }
    var cfExpiresAt by rememberSaveable { mutableStateOf(initialCfBlob?.jwtExpiresAt ?: 0L) }
    var cfJumpDestination by rememberSaveable { mutableStateOf(initialCfBlob?.jumpDestination ?: "") }
    var cfAdvancedOpen by rememberSaveable {
        mutableStateOf(
            initialCfBlob != null && (
                initialCfBlob.teamDomain.isNotBlank() ||
                    initialCfBlob.jwt.isNotBlank() ||
                    initialCfBlob.jumpDestination.isNotBlank()
            ),
        )
    }
    var sshOptions by rememberSaveable { mutableStateOf(existing?.sshOptions ?: "") }
    var moshServerCommand by rememberSaveable { mutableStateOf(existing?.moshServerCommand ?: "") }
    var postLoginCommand by rememberSaveable { mutableStateOf(existing?.postLoginCommand ?: "") }
    var postLoginBeforeSessionManager by rememberSaveable { mutableStateOf(existing?.postLoginBeforeSessionManager ?: true) }
    var remoteCommand by rememberSaveable { mutableStateOf(existing?.remoteCommand ?: "") }
    var requestPty by rememberSaveable { mutableStateOf(existing?.requestPty ?: true) }
    // USB/IP auto-forward: VID:PID of a phone device to export on connect (null = off).
    var usbForwardVidPid by rememberSaveable { mutableStateOf(existing?.usbForwardVidPid) }
    var disableAltScreen by rememberSaveable { mutableStateOf(existing?.disableAltScreen ?: false) }
    var useAndroidShell by rememberSaveable { mutableStateOf(existing?.useAndroidShell ?: false) }
    // null = follow the active distro (the default). Set to a distro id to
    // pin this LOCAL profile to a specific OS. (#per-distro-local)
    var prootDistroId by rememberSaveable { mutableStateOf(existing?.prootDistroId) }
    // Backed by a port-forward rule, not a profile field — see onSave's
    // third argument and ConnectionsViewModel.reconcileMcpReverseTunnel.
    // The caller queries hasMcpReverseTunnel asynchronously and the value
    // arrives after the first composition, so a plain rememberSaveable on
    // the initial parameter would lock in the pre-query default (false)
    // and the dialog would render OFF for a profile that actually has the
    // rule — saving then deletes it. Sync from mcpReverseTunnelEnabled
    // whenever it changes so the first non-default value seeds the state.
    var mcpReverseTunnel by rememberSaveable { mutableStateOf(mcpReverseTunnelEnabled) }
    LaunchedEffect(mcpReverseTunnelEnabled) {
        mcpReverseTunnel = mcpReverseTunnelEnabled
    }
    var forwardAgent by rememberSaveable { mutableStateOf(existing?.forwardAgent ?: false) }
    var addressFamily by rememberSaveable { mutableStateOf(existing?.addressFamily ?: "AUTO") }
    var selectedSessionManager by rememberSaveable { mutableStateOf(seed?.sessionManager) }
    var etPort by rememberSaveable { mutableStateOf(existing?.etPort?.toString() ?: "2022") }
    var localSideband by rememberSaveable {
        mutableStateOf(
            existing != null &&
                existing.reticulumHost in listOf("127.0.0.1", "localhost", "::1") &&
                existing.reticulumPort == 37428,
        )
    }
    var rnsHost by rememberSaveable { mutableStateOf(existing?.reticulumHost ?: "") }
    var rcloneRemoteName by rememberSaveable { mutableStateOf(existing?.rcloneRemoteName ?: "") }
    var rcloneProvider by rememberSaveable { mutableStateOf(existing?.rcloneProvider ?: "") }
    // #410: a short token that makes a fresh rclone remote name unique, so two
    // connections of the same provider (e.g. two SFTP) don't both save as the
    // provider name, share one rclone.conf entry, and overwrite each other.
    val rcloneRemoteToken = rememberSaveable { java.util.UUID.randomUUID().toString().take(6) }
    // A non-OAuth rclone profile is only really saved once its remote has been
    // written to rclone.conf via the Configure button — saving a profile shell
    // that points at a never-created remote silently loses the config (#295).
    // Existing rclone profiles are already configured; a new one isn't until
    // Configure succeeds, and editing a credential field invalidates it.
    var rcloneConfigured by rememberSaveable { mutableStateOf(existing?.connectionType == "RCLONE") }
    // EMAIL engines: "imap" (JVM, password/app-password — the common case, listed
    // first) or "proton" (Go bridge, SRP). Gmail/Outlook (OAuth) land in stage 2b
    // on the same IMAP engine.
    var emailProvider by rememberSaveable { mutableStateOf(existing?.emailProvider ?: "imap") }
    var emailUsername by rememberSaveable { mutableStateOf(existing?.emailUsername ?: "") }
    var emailPassword by rememberSaveable { mutableStateOf(existing?.emailPassword ?: "") }
    var emailMailboxPassword by rememberSaveable { mutableStateOf(existing?.emailMailboxPassword ?: "") }
    var emailServer by rememberSaveable { mutableStateOf(existing?.emailServer ?: "") }
    var emailSmtpServer by rememberSaveable { mutableStateOf(existing?.emailSmtpServer ?: "") }
    var emailPort by rememberSaveable { mutableStateOf(existing?.emailPort?.toString() ?: "993") }
    var emailSmtpPort by rememberSaveable { mutableStateOf(existing?.emailSmtpPort?.toString() ?: "465") }
    var emailTls by rememberSaveable { mutableStateOf(existing?.emailTls ?: true) }
    // IMAP provider preset (UI-only prefill; not persisted). Re-derived from the
    // stored IMAP host so re-opening a Gmail profile re-selects "Gmail".
    var emailPreset by rememberSaveable {
        mutableStateOf(EmailProviderPreset.fromServer(existing?.emailServer).name)
    }
    var rnsPort by rememberSaveable { mutableStateOf(existing?.reticulumPort?.toString() ?: "4242") }
    var rnsNetworkName by rememberSaveable { mutableStateOf(existing?.reticulumNetworkName ?: "") }
    var rnsPassphrase by rememberSaveable { mutableStateOf(existing?.reticulumPassphrase ?: "") }
    var fileTransport by rememberSaveable { mutableStateOf(existing?.fileTransport ?: "AUTO") }
    // Per-profile terminal colour-scheme override (#144). null = inherit
    // the global preference; otherwise one of the
    // UserPreferencesRepository.TerminalColorScheme enum names.
    var terminalColorScheme by rememberSaveable { mutableStateOf(existing?.terminalColorScheme) }
    var showColorSchemeDialog by rememberSaveable { mutableStateOf(false) }
    // Per-profile terminal background opacity. Null = inherit the global pref.
    var terminalBackgroundOpacity by rememberSaveable {
        mutableStateOf(existing?.terminalBackgroundOpacity)
    }
    // Per-profile reconnect controls (#150). Defaults match the new
    // ConnectionProfile column defaults so the dialog reads "as before"
    // for existing profiles.
    var autoReconnect by rememberSaveable { mutableStateOf(existing?.autoReconnect ?: true) }
    var reconnectMaxAttempts by rememberSaveable {
        mutableStateOf((existing?.reconnectMaxAttempts ?: 5).toString())
    }
    var reconnectOnNetworkChange by rememberSaveable {
        mutableStateOf(existing?.reconnectOnNetworkChange ?: true)
    }
    var tunnelOnly by rememberSaveable { mutableStateOf(existing?.tunnelOnly ?: false) }
    var mcpEnabled by rememberSaveable { mutableStateOf(existing?.mcpEnabled ?: true) }
    // Port knocking — see core/knock module. Stored on ConnectionProfile,
    // applied per-protocol at the transport layer (ConnectionsViewModel
    // and DesktopViewModel). Honoured only on the direct-dial path.
    var portKnockSequence by rememberSaveable {
        mutableStateOf(existing?.portKnockSequence ?: "")
    }
    // Collapsible-section state for the SSH form (#dialog tidy-up). New
    // users see the essentials (Connection, Terminal, Authentication)
    // expanded; the rest start folded. rememberSaveable so toggles survive
    // rotation.
    var secConnectionExpanded by rememberSaveable { mutableStateOf(false) }
    var secTerminalExpanded by rememberSaveable { mutableStateOf(false) }
    var secAuthExpanded by rememberSaveable { mutableStateOf(false) }
    var secReliabilityExpanded by rememberSaveable { mutableStateOf(false) }
    var secEmbeddedVncExpanded by rememberSaveable { mutableStateOf(false) }
    var portKnockDelayMs by rememberSaveable {
        mutableStateOf((existing?.portKnockDelayMs ?: 100).toString())
    }
    // fwknop Single Packet Authorization (#156). Like the knock, only applied
    // on the direct-dial path; ignored on SSH/SOCKS-tunneled connects.
    var spaKey by rememberSaveable { mutableStateOf(existing?.spaKey ?: "") }
    var spaKeyBase64 by rememberSaveable { mutableStateOf(existing?.spaKeyBase64 ?: false) }
    var spaHmacKey by rememberSaveable { mutableStateOf(existing?.spaHmacKey ?: "") }
    var spaHmacKeyBase64 by rememberSaveable { mutableStateOf(existing?.spaHmacKeyBase64 ?: false) }
    var spaAccessSpec by rememberSaveable { mutableStateOf(existing?.spaAccessSpec ?: "") }
    var spaAllowMode by rememberSaveable { mutableStateOf(existing?.spaAllowMode ?: "SOURCE") }
    var spaExplicitIp by rememberSaveable { mutableStateOf(existing?.spaExplicitIp ?: "") }
    var spaPort by rememberSaveable {
        mutableStateOf((existing?.spaPort ?: SpaConfig.DEFAULT_SPA_PORT).toString())
    }

    val isEdit = existing != null
    val title = if (isEdit) stringResource(R.string.connections_dialog_edit) else stringResource(R.string.connections_dialog_new)

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.92f),
        title = { Text(title) },
        text = {
        // #166/dialog-tidy: routing + port-knock content as reusable
        // closures so SSH renders them inside Connection / Authentication
        // while VNC/RDP/SMB keep them as their own sections below.
        val routingBody: @Composable () -> Unit = {
                    var proxyExpanded by remember { mutableStateOf(false) }
                    val selectedTunnel = tunnelConfigs.firstOrNull { it.id == tunnelConfigId }
                    val noneDirectLabel = stringResource(R.string.connections_dropdown_none_direct)
                    val tunnelDropdownLabel = selectedTunnel?.let {
                        stringResource(R.string.connections_tunnel_dropdown_label, it.label)
                    }
                    val cfTunnelLabel = stringResource(R.string.connections_dropdown_cloudflare_tunnel)
                    val selectedLabel = when {
                        useCloudflareTunnel -> cfTunnelLabel
                        tunnelDropdownLabel != null -> tunnelDropdownLabel
                        proxyType != null -> proxyType!!
                        else -> noneDirectLabel
                    }
                    ExposedDropdownMenuBox(
                        expanded = proxyExpanded,
                        onExpandedChange = { proxyExpanded = it },
                    ) {
                        OutlinedTextField(
                            value = selectedLabel,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.connections_field_route_through)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = proxyExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        )
                        ExposedDropdownMenu(
                            expanded = proxyExpanded,
                            onDismissRequest = { proxyExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.connections_dropdown_none_direct)) },
                                onClick = {
                                    proxyType = null
                                    proxyHost = ""
                                    tunnelConfigId = null
                                    useCloudflareTunnel = false
                                    proxyExpanded = false
                                },
                            )
                            // Cloudflare Tunnel transport (GH #154) — the SSH
                            // profile owns a hidden TunnelConfig row, so only
                            // SSH-family profiles can opt in here. The form
                            // below shares the SSH `host` field as the
                            // tunnel hostname rather than duplicating it.
                            if (connectionType == "SSH") {
                                DropdownMenuItem(
                                    text = { Text(cfTunnelLabel) },
                                    onClick = {
                                        useCloudflareTunnel = true
                                        proxyType = null
                                        proxyHost = ""
                                        tunnelConfigId = null
                                        proxyExpanded = false
                                    },
                                )
                            }
                            listOf("SOCKS5", "SOCKS4", "HTTP").forEach { kind ->
                                DropdownMenuItem(
                                    text = { Text(kind) },
                                    onClick = {
                                        proxyType = kind
                                        tunnelConfigId = null
                                        useCloudflareTunnel = false
                                        if (kind == "HTTP" && proxyPort == "1080") {
                                            proxyPort = "8080"
                                        } else if (kind != "HTTP" && proxyPort == "8080") {
                                            proxyPort = "1080"
                                        }
                                        proxyExpanded = false
                                    },
                                )
                            }
                            if (tunnelConfigs.isNotEmpty()) {
                                HorizontalDivider()
                                tunnelConfigs.forEach { tunnel ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(stringResource(R.string.connections_tunnel_dropdown_label, tunnel.label))
                                                Text(
                                                    runCatching {
                                                        friendlyTunnelTypeLabel(
                                                            sh.haven.core.data.db.entities.TunnelConfigType.fromStorage(tunnel.type),
                                                        )
                                                    }.getOrDefault(tunnel.type),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        },
                                        onClick = {
                                            tunnelConfigId = tunnel.id
                                            proxyType = null
                                            proxyHost = ""
                                            useCloudflareTunnel = false
                                            proxyExpanded = false
                                        },
                                    )
                                }
                            }
                            if (onManageTunnels != null) {
                                // Quick-add for WireGuard (the only standalone
                                // backend with a usable add path right now)
                                // plus a manage-everything link. Cloudflare
                                // Tunnel used to live here too but is now an
                                // inline transport on the SSH profile itself
                                // (GH #154) — picking it from the route-through
                                // list above is the new path. Tailscale's add
                                // path is disabled in the Tunnels screen
                                // pending the tsnet bridge.
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "+ New WireGuard tunnel",
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    },
                                    onClick = {
                                        proxyExpanded = false
                                        onManageTunnels(sh.haven.core.data.db.entities.TunnelConfigType.WIREGUARD)
                                    },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(R.string.connections_action_manage_tunnels),
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    },
                                    onClick = {
                                        proxyExpanded = false
                                        onManageTunnels(null)
                                    },
                                )
                            }
                        }
                    }

                    // Inline Cloudflare Tunnel transport fields (GH #154).
                    // Shown when the route-through dropdown picks
                    // "Cloudflare Tunnel". The SSH `host` field above
                    // doubles as the tunnel hostname; the form here only
                    // adds the optional Access-auth bits, jump destination,
                    // and a Test connection button. JWT capture happens
                    // via the existing in-app WebView contract.
                    if (useCloudflareTunnel && connectionType == "SSH") {
                        Spacer(Modifier.height(8.dp))
                        val tunnelViewModel: TunnelViewModel = hiltViewModel()
                        val cfTestResult by tunnelViewModel.cfTestResult.collectAsState()
                        val cfLoginLauncher = rememberLauncherForActivityResult(
                            contract = CloudflareAccessLoginContract(),
                        ) { result ->
                            when (result) {
                                is CloudflareAccessLoginContract.Result.Success -> {
                                    cfJwt = result.jwt
                                    cfExpiresAt = result.expiresAtSeconds
                                }
                                is CloudflareAccessLoginContract.Result.Failed,
                                CloudflareAccessLoginContract.Result.Cancelled -> Unit
                            }
                        }
                        Text(
                            stringResource(R.string.connections_helper_cloudflare_routing),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        CloudflareInlineFields(
                            hostname = host,
                            // Hostname comes from the SSH `host` field —
                            // changes there flow through automatically.
                            onHostnameChange = { /* no-op; SSH host owns it */ },
                            teamDomain = cfTeamDomain,
                            onTeamDomainChange = { cfTeamDomain = it },
                            jwt = cfJwt,
                            jwtExpiresAt = cfExpiresAt,
                            jumpDestination = cfJumpDestination,
                            onJumpDestinationChange = { cfJumpDestination = it },
                            onSignInClick = {
                                val h = host.trim()
                                if (h.isNotEmpty()) {
                                    cfLoginLauncher.launch(
                                        CloudflareAccessLoginContract.Input(
                                            hostname = h,
                                            teamDomain = cfTeamDomain.trim(),
                                        ),
                                    )
                                }
                            },
                            advancedOpen = cfAdvancedOpen,
                            onAdvancedToggle = { cfAdvancedOpen = !cfAdvancedOpen },
                            onJwtPaste = { pasted, expiresAt ->
                                cfJwt = pasted
                                cfExpiresAt = expiresAt
                            },
                            testResult = cfTestResult,
                            onTestClick = {
                                tunnelViewModel.testCloudflareAccess(
                                    host.trim(),
                                    cfJwt.trim(),
                                    cfJumpDestination.trim(),
                                )
                            },
                            showHostname = false,
                            showIntroBlurb = false,
                        )
                    }

                    if (proxyType != null) {
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = proxyHost,
                                onValueChange = { proxyHost = it },
                                label = { Text(stringResource(R.string.connections_field_proxy_host)) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = proxyPort,
                                onValueChange = { proxyPort = it.filter { c -> c.isDigit() } },
                                label = { Text(stringResource(R.string.connections_field_port)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.width(80.dp),
                            )
                        }
                        // #227: optional proxy authentication. SOCKS4 carries
                        // a userid only (no password), so we hide the password
                        // field and note that for SOCKS4.
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = proxyUser,
                                onValueChange = { proxyUser = it },
                                label = { Text(stringResource(R.string.connections_field_proxy_username)) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            if (proxyType != "SOCKS4") {
                                OutlinedTextField(
                                    value = proxyPassword,
                                    onValueChange = { proxyPassword = it },
                                    label = { Text(stringResource(R.string.connections_field_proxy_password)) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        if (proxyType == "SOCKS4" && proxyUser.isNotBlank()) {
                            Text(
                                stringResource(R.string.connections_helper_proxy_socks4_userid),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        if (host.endsWith(".onion") && proxyType == "SOCKS5") {
                            Text(
                                "Tor .onion address detected — hostname will be resolved through the SOCKS5 proxy",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        } else if (host.endsWith(".onion") && proxyType != "SOCKS5") {
                            Text(
                                ".onion addresses require a SOCKS5 proxy (e.g. Orbot on localhost:9050)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }

                        // Visual chain indicator for proxy
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.PhoneAndroid, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(4.dp))
                            Text("$proxyType", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Filled.Storage, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else if (host.endsWith(".onion")) {
                        Text(
                            ".onion addresses require a SOCKS5 proxy (e.g. Orbot on localhost:9050)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
        }
        val portKnockBody: @Composable () -> Unit = {
                    val parsedKnock = KnockSequence.parse(
                        portKnockSequence,
                        portKnockDelayMs.toIntOrNull() ?: KnockSequence.DEFAULT_DELAY_MS,
                    )
                    val knockError = parsedKnock.exceptionOrNull()?.message
                    OutlinedTextField(
                        value = portKnockSequence,
                        onValueChange = { portKnockSequence = it },
                        label = { Text(stringResource(R.string.connections_field_port_knock_sequence)) },
                        placeholder = { Text(stringResource(R.string.connections_hint_port_knock_sequence)) },
                        isError = knockError != null,
                        supportingText = {
                            if (knockError != null) {
                                Text(knockError, color = MaterialTheme.colorScheme.error)
                            } else if (portKnockSequence.isNotBlank()) {
                                Text(stringResource(R.string.connections_helper_port_knock_active))
                            } else {
                                Text(stringResource(R.string.connections_helper_port_knock_blank))
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (portKnockSequence.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = portKnockDelayMs,
                            onValueChange = { v ->
                                portKnockDelayMs = v.filter { c -> c.isDigit() }.take(5)
                            },
                            label = { Text(stringResource(R.string.connections_field_port_knock_delay)) },
                            placeholder = { Text("100") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(200.dp),
                        )
                        if (onTestKnock != null) {
                            val scope = rememberCoroutineScope()
                            var testRunning by remember { mutableStateOf(false) }
                            var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(
                                    enabled = !testRunning && knockError == null && host.isNotBlank(),
                                    onClick = {
                                        testRunning = true
                                        testResult = null
                                        scope.launch {
                                            val r = onTestKnock(
                                                host,
                                                portKnockSequence,
                                                portKnockDelayMs.toIntOrNull()
                                                    ?: KnockSequence.DEFAULT_DELAY_MS,
                                            )
                                            testResult = r
                                            testRunning = false
                                        }
                                    },
                                ) {
                                    if (testRunning) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(14.dp),
                                            strokeWidth = 2.dp,
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(stringResource(R.string.connections_button_test_knock_running))
                                    } else {
                                        Text(stringResource(R.string.connections_button_test_knock))
                                    }
                                }
                                Spacer(Modifier.width(8.dp))
                                testResult?.let { (ok, msg) ->
                                    Text(
                                        msg,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (ok) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
        }
        val spaBody: @Composable () -> Unit = {
            val parsedSpa = SpaConfig.parse(
                key = spaKey,
                keyIsBase64 = spaKeyBase64,
                hmacKey = spaHmacKey,
                hmacKeyIsBase64 = spaHmacKeyBase64,
                accessSpec = spaAccessSpec,
                allowMode = spaAllowMode,
                explicitIp = spaExplicitIp,
                spaPort = spaPort.toIntOrNull() ?: SpaConfig.DEFAULT_SPA_PORT,
            )
            val spaError = parsedSpa.exceptionOrNull()?.message
            val spaConfig = parsedSpa.getOrNull()
            OutlinedTextField(
                value = spaKey,
                onValueChange = { spaKey = it },
                label = { Text(stringResource(R.string.connections_field_spa_key)) },
                supportingText = {
                    when {
                        spaError != null -> Text(spaError, color = MaterialTheme.colorScheme.error)
                        spaConfig != null -> Text(stringResource(R.string.connections_helper_spa_active))
                        else -> Text(stringResource(R.string.connections_helper_spa_blank))
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (spaKey.isNotBlank()) {
                BooleanToggleRow(
                    label = stringResource(R.string.connections_toggle_spa_key_base64),
                    checked = spaKeyBase64,
                    onCheckedChange = { spaKeyBase64 = it },
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = spaAccessSpec,
                    onValueChange = { spaAccessSpec = it },
                    label = { Text(stringResource(R.string.connections_field_spa_access_spec)) },
                    placeholder = { Text(stringResource(R.string.connections_hint_spa_access_spec)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = spaHmacKey,
                    onValueChange = { spaHmacKey = it },
                    label = { Text(stringResource(R.string.connections_field_spa_hmac_key)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (spaHmacKey.isNotBlank()) {
                    BooleanToggleRow(
                        label = stringResource(R.string.connections_toggle_spa_hmac_key_base64),
                        checked = spaHmacKeyBase64,
                        onCheckedChange = { spaHmacKeyBase64 = it },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.connections_field_spa_allow_mode),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = spaAllowMode == "SOURCE",
                        onClick = { spaAllowMode = "SOURCE" },
                        label = { Text(stringResource(R.string.connections_spa_allow_source)) },
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = spaAllowMode == "RESOLVE",
                        onClick = { spaAllowMode = "RESOLVE" },
                        label = { Text(stringResource(R.string.connections_spa_allow_resolve)) },
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = spaAllowMode == "EXPLICIT",
                        onClick = { spaAllowMode = "EXPLICIT" },
                        label = { Text(stringResource(R.string.connections_spa_allow_explicit)) },
                    )
                }
                if (spaAllowMode == "EXPLICIT") {
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = spaExplicitIp,
                        onValueChange = { spaExplicitIp = it },
                        label = { Text(stringResource(R.string.connections_field_spa_explicit_ip)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = spaPort,
                    onValueChange = { v -> spaPort = v.filter { c -> c.isDigit() }.take(5) },
                    label = { Text(stringResource(R.string.connections_field_spa_port)) },
                    placeholder = { Text(SpaConfig.DEFAULT_SPA_PORT.toString()) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(200.dp),
                )
                if (onTestSpa != null) {
                    val scope = rememberCoroutineScope()
                    var spaTestRunning by remember { mutableStateOf(false) }
                    var spaTestResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            enabled = !spaTestRunning && spaConfig != null && host.isNotBlank(),
                            onClick = {
                                val cfg = spaConfig ?: return@TextButton
                                spaTestRunning = true
                                spaTestResult = null
                                scope.launch {
                                    spaTestResult = onTestSpa(host, cfg)
                                    spaTestRunning = false
                                }
                            },
                        ) {
                            if (spaTestRunning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.connections_button_test_spa_running))
                            } else {
                                Text(stringResource(R.string.connections_button_test_spa))
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        spaTestResult?.let { (ok, msg) ->
                            Text(
                                msg,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (ok) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                ConnectionSection(stringResource(R.string.connections_section_general))
                // Transport selector. LOCAL is back in the list (was briefly
                // removed in v5.24.52 because the main list filtered Local
                // profiles out, making the "+" → Local flow a dead-end).
                // The list now shows Local profiles like every other
                // transport, so this dropdown entry produces a visible,
                // editable result again. (#114)
                val allTransportOptions = listOf(
                    "SSH" to "SSH",
                    "MOSH" to "Mosh",
                    "ET" to "Eternal Terminal",
                    "LOCAL" to "Local Shell (PRoot)",
                    "BTSERIAL" to "Bluetooth Serial",
                    "BLESERIAL" to "Bluetooth LE Serial",
                    "USBSERIAL" to "USB Serial",
                    "VNC" to "VNC (Desktop)",
                    "RDP" to "RDP (Desktop)",
                    "SPICE" to "SPICE (Desktop)",
                    "SMB" to "SMB (File Share)",
                    "RCLONE" to "Cloud Storage (rclone)",
                    "EMAIL" to "Email (IMAP / Proton)",
                    "RETICULUM" to "Reticulum",
                )
                // #510: the terminal build ships no RDP or SPICE client, so
                // offering them here would only produce a profile that fails
                // at connect with "native library failed to load".
                //
                // Only what is OFFERED is filtered. An existing profile of a
                // missing type still opens and still shows its own transport,
                // because the label lookup below reads the unfiltered list —
                // filtering that too would throw NoSuchElementException on a
                // profile the user already has. VNC stays: its client is
                // Kotlin and ships in every build.
                val dialogContext = LocalContext.current
                val transportOptions = remember(dialogContext) {
                    val native = NativeFeatures(dialogContext)
                    TransportAvailability.offered(
                        allTransportOptions,
                        rdp = native.rdp,
                        spice = native.spice,
                        // Probed, not looked for: libgojni.so is present in
                        // every build, but the terminal flavour's copy is
                        // built without rclone.
                        rclone = sh.haven.rclone.bridge.RcloneBridge.available,
                    )
                }
                var transportExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = transportExpanded,
                    onExpandedChange = { transportExpanded = it },
                ) {
                    OutlinedTextField(
                        // Unfiltered on purpose: a profile whose transport this
                        // build cannot run still names it rather than crashing
                        // the dialog on a missing entry (#510).
                        value = allTransportOptions.firstOrNull { it.first == selectedTransport }
                            ?.second ?: selectedTransport,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.connections_field_transport)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(transportExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(
                        expanded = transportExpanded,
                        onDismissRequest = { transportExpanded = false },
                    ) {
                        transportOptions.forEach { (value, displayLabel) ->
                            DropdownMenuItem(
                                text = { Text(displayLabel) },
                                onClick = {
                                    selectedTransport = value
                                    transportExpanded = false
                                    // Update port to transport default when switching
                                    val defaultPort = when (value) {
                                        "VNC" -> "5900"
                                        "RDP" -> "3389"
                                        "SPICE" -> "5900"
                                        "SMB" -> "445"
                                        "ET" -> "22"
                                        else -> "22"
                                    }
                                    if (port == "22" || port == "5900" || port == "3389" || port == "445" || port == "2022") {
                                        port = defaultPort
                                    }
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))

                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.connections_field_label)) },
                    placeholder = {
                        Text(
                            when (connectionType) {
                                "LOCAL" -> "Local Shell"
                                "VNC" -> "My VNC Desktop"
                                "RDP" -> "My RDP Desktop"
                                "SPICE" -> "My SPICE Desktop"
                                "SMB" -> "My File Share"
                                "RCLONE" -> "My Google Drive"
                                "EMAIL" -> "My Mail"
                                "RETICULUM" -> "My Node"
                                else -> "My Server"
                            }
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))

                // Color tag picker
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.connections_field_color), style = MaterialTheme.typography.bodyMedium)
                    // "None" option
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .border(
                                width = if (colorTag == 0) 2.dp else 1.dp,
                                color = if (colorTag == 0) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline,
                                shape = CircleShape,
                            )
                            .clickable { colorTag = 0 },
                    ) {
                        if (colorTag == 0) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = stringResource(R.string.connections_dropdown_none),
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    EDIT_DIALOG_COLORS.forEachIndexed { index, color ->
                        val tag = index + 1
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(color, CircleShape)
                                .then(
                                    if (colorTag == tag) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                    else Modifier,
                                )
                                .clickable { colorTag = tag },
                        ) {
                            if (colorTag == tag) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = Color.White,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))

                // Group picker
                if (groups.isNotEmpty()) {
                    var groupExpanded by remember { mutableStateOf(false) }
                    val selectedGroup = groups.firstOrNull { it.id == groupId }
                    ExposedDropdownMenuBox(
                        expanded = groupExpanded,
                        onExpandedChange = { groupExpanded = it },
                    ) {
                        OutlinedTextField(
                            value = selectedGroup?.label ?: stringResource(R.string.connections_dropdown_none),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.connections_field_group)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(groupExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        )
                        ExposedDropdownMenu(
                            expanded = groupExpanded,
                            onDismissRequest = { groupExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.connections_dropdown_none)) },
                                onClick = {
                                    groupId = null
                                    groupExpanded = false
                                },
                            )
                            groups.forEach { group ->
                                DropdownMenuItem(
                                    text = { Text(group.label) },
                                    onClick = {
                                        groupId = group.id
                                        groupExpanded = false
                                    },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }

                if (connectionType == "LOCAL") {
                    ConnectionSection(stringResource(R.string.connections_section_local_shell))
                    Text(
                        if (useAndroidShell) {
                            stringResource(R.string.connections_local_shell_android_desc)
                        } else {
                            stringResource(R.string.connections_local_shell_proot_desc)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { useAndroidShell = !useAndroidShell },
                    ) {
                        Checkbox(
                            checked = useAndroidShell,
                            onCheckedChange = { useAndroidShell = it },
                        )
                        Text(
                            stringResource(R.string.connections_use_android_shell),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    // Pin this Local profile to a specific OS (proot only).
                    // Default "Active distro" = follow the global active one.
                    if (!useAndroidShell && availableDistros.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        var distroExpanded by remember { mutableStateOf(false) }
                        val activeDefault = stringResource(R.string.connections_local_distro_active_default)
                        val selectedDistroLabel = availableDistros
                            .firstOrNull { it.first == prootDistroId }?.second
                            ?: activeDefault
                        ExposedDropdownMenuBox(
                            expanded = distroExpanded,
                            onExpandedChange = { distroExpanded = it },
                        ) {
                            OutlinedTextField(
                                value = selectedDistroLabel,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(stringResource(R.string.connections_local_distro_label)) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(distroExpanded) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                            )
                            ExposedDropdownMenu(
                                expanded = distroExpanded,
                                onDismissRequest = { distroExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(activeDefault) },
                                    onClick = {
                                        prootDistroId = null
                                        distroExpanded = false
                                    },
                                )
                                availableDistros.forEach { (id, distroLabel) ->
                                    DropdownMenuItem(
                                        text = { Text(distroLabel) },
                                        onClick = {
                                            prootDistroId = id
                                            distroExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                } else if (connectionType == "BTSERIAL") {
                    BtSerialDeviceField(
                        selectedAddress = btDevice,
                        onSelect = { address, name ->
                            btDevice = address
                            if (label.isBlank()) label = name
                        },
                    )
                } else if (connectionType == "BLESERIAL") {
                    BleSerialDeviceField(
                        selectedAddress = bleDevice,
                        onSelect = { address, name ->
                            bleDevice = address
                            if (label.isBlank()) label = name
                        },
                    )
                } else if (connectionType == "USBSERIAL") {
                    UsbSerialDeviceField(
                        selectedKey = usbDevice,
                        baud = usbBaud,
                        dataBits = usbDataBits,
                        parity = usbParity,
                        stopBits = usbStopBits,
                        flowControl = usbFlow,
                        onSelectDevice = { key, name ->
                            usbDevice = key
                            if (label.isBlank()) label = name
                        },
                        onBaudChange = { usbBaud = it },
                        onDataBitsChange = { usbDataBits = it },
                        onParityChange = { usbParity = it },
                        onStopBitsChange = { usbStopBits = it },
                        onFlowControlChange = { usbFlow = it },
                    )
                } else if (connectionType == "RCLONE") {
                    ConnectionSection(stringResource(R.string.connections_section_cloud_storage))
                    val providerOptions = listOf(
                        "drive" to "Google Drive",
                        "dropbox" to "Dropbox",
                        "onedrive" to "Microsoft OneDrive",
                        "s3" to "Amazon S3 / Compatible",
                        "b2" to "Backblaze B2",
                        "sftp" to "SFTP (remote)",
                        "webdav" to "WebDAV",
                        "ftp" to "FTP",
                        "mega" to "MEGA",
                        "pcloud" to "pCloud",
                        "box" to "Box",
                        "filen" to "Filen",
                    )
                    var providerExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = providerExpanded,
                        onExpandedChange = { providerExpanded = it },
                    ) {
                        OutlinedTextField(
                            value = providerOptions.firstOrNull { it.first == rcloneProvider }?.second
                                ?: rcloneProvider.ifEmpty { stringResource(R.string.connections_dropdown_select_provider) },
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.connections_field_provider)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(providerExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        )
                        ExposedDropdownMenu(
                            expanded = providerExpanded,
                            onDismissRequest = { providerExpanded = false },
                        ) {
                            providerOptions.forEach { (value, displayLabel) ->
                                DropdownMenuItem(
                                    text = { Text(displayLabel) },
                                    onClick = {
                                        rcloneProvider = value
                                        providerExpanded = false
                                        // A fresh connection gets a UNIQUE remote name
                                        // ("<provider>-<token>") so two of the same provider
                                        // don't collide on one rclone.conf entry (#410). An
                                        // existing connection keeps the name it was configured
                                        // under (renaming would orphan its rclone remote).
                                        if (existing?.isRclone != true) {
                                            rcloneRemoteName = "$value-$rcloneRemoteToken"
                                        }
                                    },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    if (rcloneProvider.isBlank() || rcloneProvider in RCLONE_OAUTH_PROVIDERS) {
                        // OAuth providers (Drive/Dropbox/…) authenticate via the
                        // browser flow on first connect — no fields to fill here.
                        Text(
                            stringResource(R.string.connections_helper_rclone_signin),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        // Non-OAuth providers (Filen, s3, b2, mega, sftp, …):
                        // collect the rclone-declared required fields and write
                        // the remote via config/create (#181).
                        val rcloneCfgVm: RcloneConfigViewModel = hiltViewModel()
                        val rcloneOptions by rcloneCfgVm.options.collectAsState()
                        val rcloneCfgStatus by rcloneCfgVm.status.collectAsState()
                        val rcloneParams = remember(rcloneProvider) { mutableStateMapOf<String, String>() }
                        LaunchedEffect(rcloneProvider) { rcloneCfgVm.loadOptions(rcloneProvider) }
                        // A successful Configure means the remote is now in
                        // rclone.conf — only then may the profile be saved (#295).
                        LaunchedEffect(rcloneCfgStatus) {
                            if (rcloneCfgStatus is RcloneConfigViewModel.Status.Success) {
                                rcloneConfigured = true
                            }
                        }
                        Text(
                            stringResource(R.string.connections_helper_rclone_credentials),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        rcloneOptions.forEach { opt ->
                            OutlinedTextField(
                                value = rcloneParams[opt.name] ?: opt.default,
                                onValueChange = {
                                    rcloneParams[opt.name] = it
                                    // Changing a credential invalidates the verified
                                    // remote — must re-Configure before saving (#295).
                                    rcloneConfigured = false
                                    rcloneCfgVm.clearStatus()
                                },
                                label = {
                                    Text(opt.name.replace('_', ' ').replaceFirstChar { c -> c.uppercase() })
                                },
                                singleLine = true,
                                supportingText = opt.help.takeIf { it.isNotBlank() }?.let {
                                    { Text(it, style = MaterialTheme.typography.labelSmall) }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                        val requiredFilled = rcloneOptions.filter { it.required }
                            .all { (rcloneParams[it.name] ?: it.default).isNotBlank() }
                        OutlinedButton(
                            onClick = {
                                val name = rcloneRemoteName.ifBlank { "$rcloneProvider-$rcloneRemoteToken" }
                                val params = rcloneOptions
                                    .associate { it.name to (rcloneParams[it.name] ?: it.default) }
                                    .filterValues { it.isNotBlank() }
                                rcloneCfgVm.configure(name, rcloneProvider, params)
                            },
                            enabled = requiredFilled &&
                                rcloneCfgStatus != RcloneConfigViewModel.Status.Working,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (rcloneCfgStatus == RcloneConfigViewModel.Status.Working) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(stringResource(R.string.connections_rclone_configure))
                        }
                        when (val s = rcloneCfgStatus) {
                            is RcloneConfigViewModel.Status.Success -> Text(
                                stringResource(R.string.connections_rclone_configured_ok),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            is RcloneConfigViewModel.Status.Error -> Text(
                                s.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            else -> {}
                        }
                        // Make the gated Save discoverable: until Configure
                        // succeeds, Save stays disabled for a non-OAuth remote (#295).
                        if (!rcloneConfigured && rcloneProvider.isNotBlank() &&
                            rcloneCfgStatus !is RcloneConfigViewModel.Status.Error
                        ) {
                            Text(
                                stringResource(R.string.connections_rclone_configure_first),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                } else if (connectionType == "EMAIL") {
                    ConnectionSection(stringResource(R.string.connections_section_email))
                    // Engine picker — one compact segmented row (portrait-friendly).
                    val emailProviderOptions = listOf(
                        "imap" to stringResource(R.string.connections_email_provider_imap_short),
                        "proton" to stringResource(R.string.connections_email_provider_proton_short),
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        emailProviderOptions.forEachIndexed { index, (key, label) ->
                            SegmentedButton(
                                selected = emailProvider == key,
                                onClick = { emailProvider = key },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = emailProviderOptions.size,
                                ),
                            ) { Text(label) }
                        }
                    }
                    val isImapProvider = emailProvider.equals("imap", ignoreCase = true)
                    if (!isImapProvider) {
                        Text(
                            stringResource(R.string.connections_email_proton_unofficial_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = emailUsername,
                        onValueChange = { emailUsername = it },
                        label = { Text(stringResource(R.string.connections_field_email_address)) },
                        placeholder = { Text(if (isImapProvider) "you@example.com" else "you@proton.me") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    sh.haven.core.ui.PasswordField(
                        value = emailPassword,
                        onValueChange = { emailPassword = it },
                        label = stringResource(R.string.connections_field_email_password),
                        modifier = Modifier.fillMaxWidth(),
                        onRevealRequest = onRevealSavedSecret,
                    )
                    if (isImapProvider) {
                        // Generic IMAP/SMTP fields — gated, so they never bloat the
                        // dialog for Proton (the common case).
                        val preset = EmailProviderPreset.valueOf(emailPreset)
                        Spacer(Modifier.height(4.dp))
                        // Provider preset: prefills server/ports/TLS for the major
                        // providers; "Custom" leaves the fields manual. UI-only —
                        // not persisted; re-derived from the saved IMAP host.
                        var presetExpanded by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(
                            expanded = presetExpanded,
                            onExpandedChange = { presetExpanded = it },
                        ) {
                            OutlinedTextField(
                                value = preset.displayName,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(stringResource(R.string.connections_email_preset_label)) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(presetExpanded) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                            )
                            ExposedDropdownMenu(
                                expanded = presetExpanded,
                                onDismissRequest = { presetExpanded = false },
                            ) {
                                EmailProviderPreset.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.displayName) },
                                        onClick = {
                                            emailPreset = option.name
                                            presetExpanded = false
                                            if (option != EmailProviderPreset.GENERIC) {
                                                option.imapServer?.let { emailServer = it }
                                                option.smtpServer?.let { emailSmtpServer = it }
                                                emailPort = option.imapPort.toString()
                                                emailSmtpPort = option.smtpPort.toString()
                                                emailTls = option.tls
                                            }
                                        },
                                    )
                                }
                            }
                        }
                        preset.appPasswordUrl?.let { url ->
                            val uriHandler = LocalUriHandler.current
                            TextButton(onClick = { uriHandler.openUri(url) }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    stringResource(
                                        R.string.connections_email_app_password_link,
                                        preset.displayName,
                                    ),
                                )
                            }
                        }
                        Text(
                            stringResource(R.string.connections_email_app_password_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = emailServer,
                            onValueChange = { emailServer = it },
                            label = { Text(stringResource(R.string.connections_field_imap_server)) },
                            placeholder = { Text("imap.example.com") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = emailSmtpServer,
                            onValueChange = { emailSmtpServer = it },
                            label = { Text(stringResource(R.string.connections_field_smtp_server)) },
                            placeholder = { Text("smtp.example.com") },
                            supportingText = {
                                Text(stringResource(R.string.connections_field_smtp_server_help))
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = emailPort,
                                onValueChange = { emailPort = it.filter(Char::isDigit) },
                                label = { Text(stringResource(R.string.connections_field_imap_port)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            OutlinedTextField(
                                value = emailSmtpPort,
                                onValueChange = { emailSmtpPort = it.filter(Char::isDigit) },
                                label = { Text(stringResource(R.string.connections_field_smtp_port)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        BooleanToggleRow(
                            label = stringResource(R.string.connections_toggle_imap_tls),
                            checked = emailTls,
                            onCheckedChange = { emailTls = it },
                        )
                    } else {
                        Spacer(Modifier.height(4.dp))
                        sh.haven.core.ui.PasswordField(
                            value = emailMailboxPassword,
                            onValueChange = { emailMailboxPassword = it },
                            label = stringResource(R.string.connections_field_email_mailbox_password),
                            modifier = Modifier.fillMaxWidth(),
                            onRevealRequest = onRevealSavedSecret,
                        )
                        Text(
                            stringResource(R.string.connections_email_mailbox_password_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        Text(
                            stringResource(R.string.connections_email_2fa_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                } else if (connectionType == "VNC") {
                    ConnectionSection(stringResource(R.string.connections_section_vnc))
                    // VNC: tunnel toggle first (it changes what Host means),
                    // then connection fields.
                    SshTunnelBlock(
                        enabled = vncSshForward,
                        carrierId = vncSshProfileId,
                        sshProfiles = sshProfiles,
                        onEnabledChange = { newValue ->
                            vncSshForward = newValue
                            if (!newValue) vncSshProfileId = null
                            host = tunnelHostOnToggle(newValue, host)
                        },
                        onCarrierChange = { vncSshProfileId = it },
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text(stringResource(if (vncSshForward) R.string.connections_field_vnc_host_via_ssh else R.string.common_host)) },
                        placeholder = { Text(if (vncSshForward) "127.0.0.1" else "192.168.1.100") },
                        supportingText = if (vncSshForward) {
                            {
                                Text(
                                    stringResource(R.string.connections_helper_vnc_host_via_ssh),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        } else null,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter { c -> c.isDigit() } },
                        label = { Text(stringResource(R.string.connections_field_port)) },
                        placeholder = { Text("5900") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(120.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = vncUsername,
                        onValueChange = { vncUsername = it },
                        label = { Text(stringResource(R.string.connections_field_username_vnc)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = vncPassword,
                        onValueChange = { vncPassword = it },
                        label = { Text(stringResource(R.string.connections_field_password_optional)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.connections_helper_vnc_auth),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    val depthOptions = listOf(
                        "BPP_24_TRUE" to stringResource(R.string.connections_vnc_depth_24),
                        "BPP_16_TRUE" to stringResource(R.string.connections_vnc_depth_16),
                        "BPP_8_INDEXED" to stringResource(R.string.connections_vnc_depth_8),
                    )
                    var depthExpanded by remember { mutableStateOf(false) }
                    val selectedDepth = depthOptions.firstOrNull { it.first == vncColorDepth } ?: depthOptions.first()
                    ExposedDropdownMenuBox(
                        expanded = depthExpanded,
                        onExpandedChange = { depthExpanded = it },
                    ) {
                        OutlinedTextField(
                            value = selectedDepth.second,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.connections_field_colour_depth)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(depthExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        )
                        ExposedDropdownMenu(
                            expanded = depthExpanded,
                            onDismissRequest = { depthExpanded = false },
                        ) {
                            depthOptions.forEach { (value, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        vncColorDepth = value
                                        depthExpanded = false
                                    },
                                )
                            }
                        }
                    }
                } else if (connectionType == "RDP") {
                    ConnectionSection(stringResource(R.string.connections_section_rdp))
                    // RDP: SSH tunnel toggle first (it changes what Host means),
                    // then host, port, username, domain.
                    SshTunnelBlock(
                        enabled = rdpSshForward,
                        carrierId = rdpSshProfileId,
                        sshProfiles = sshProfiles,
                        onEnabledChange = { newValue ->
                            rdpSshForward = newValue
                            if (!newValue) rdpSshProfileId = null
                            host = tunnelHostOnToggle(newValue, host)
                        },
                        onCarrierChange = { rdpSshProfileId = it },
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text(stringResource(if (rdpSshForward) R.string.connections_field_rdp_host_via_ssh else R.string.common_host)) },
                        placeholder = { Text(if (rdpSshForward) "127.0.0.1" else "192.168.1.100") },
                        supportingText = if (rdpSshForward) {
                            {
                                Text(
                                    stringResource(R.string.connections_helper_rdp_host_via_ssh),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        } else null,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = rdpUsername,
                            onValueChange = { rdpUsername = it },
                            label = { Text(stringResource(R.string.connections_field_username)) },
                            placeholder = { Text("user") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = port,
                            onValueChange = { port = it.filter { c -> c.isDigit() } },
                            label = { Text(stringResource(R.string.connections_field_port)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(80.dp),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    sh.haven.core.ui.PasswordField(
                        value = rdpPassword,
                        onValueChange = { rdpPassword = it },
                        label = stringResource(R.string.connections_field_password_optional),
                        modifier = Modifier.fillMaxWidth(),
                        onRevealRequest = onRevealSavedSecret,
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = rdpDomain,
                        onValueChange = { rdpDomain = it },
                        label = { Text(stringResource(R.string.connections_field_domain_optional)) },
                        placeholder = { Text("WORKGROUP") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(
                            checked = rdpUseNla,
                            onCheckedChange = { rdpUseNla = it },
                        )
                        Text(
                            stringResource(R.string.connections_toggle_rdp_nla),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text(
                        stringResource(R.string.connections_helper_rdp_nla),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    // Colour-depth picker. Empirical matrix from #109:
                    //   16-bit: works on everything (slow on Windows)
                    //   24-bit: works on xrdp, Windows resets connection
                    //   32-bit: works on Windows, xrdp blank screen
                    // No single value works smooth everywhere; default
                    // is the safe-everywhere 16-bit and users pick
                    // 24/32 if they know their server type.
                    val rdpDepthOptions = listOf(
                        16 to stringResource(R.string.connections_rdp_depth_16),
                        24 to stringResource(R.string.connections_rdp_depth_24),
                        32 to stringResource(R.string.connections_rdp_depth_32),
                    )
                    var rdpDepthExpanded by remember { mutableStateOf(false) }
                    val selectedRdpDepth = rdpDepthOptions.firstOrNull { it.first == rdpColorDepth }
                        ?: rdpDepthOptions.first()
                    ExposedDropdownMenuBox(
                        expanded = rdpDepthExpanded,
                        onExpandedChange = { rdpDepthExpanded = it },
                    ) {
                        OutlinedTextField(
                            value = selectedRdpDepth.second,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.connections_field_colour_depth)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(rdpDepthExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        )
                        ExposedDropdownMenu(
                            expanded = rdpDepthExpanded,
                            onDismissRequest = { rdpDepthExpanded = false },
                        ) {
                            rdpDepthOptions.forEach { (value, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        rdpColorDepth = value
                                        rdpDepthExpanded = false
                                    },
                                )
                            }
                        }
                    }
                } else if (connectionType == "SPICE") {
                    ConnectionSection(stringResource(R.string.connections_section_spice))
                    // SPICE: tunnel toggle first (changes what Host means), then
                    // host/port/password. Auth is a single optional ticket — no
                    // username/domain/colour-depth (the framebuffer is 32bpp).
                    SshTunnelBlock(
                        enabled = spiceSshForward,
                        carrierId = spiceSshProfileId,
                        sshProfiles = sshProfiles,
                        onEnabledChange = { newValue ->
                            spiceSshForward = newValue
                            if (!newValue) spiceSshProfileId = null
                            host = tunnelHostOnToggle(newValue, host)
                        },
                        onCarrierChange = { spiceSshProfileId = it },
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text(stringResource(R.string.common_host)) },
                        placeholder = { Text(if (spiceSshForward) "127.0.0.1" else "192.168.1.100") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter { c -> c.isDigit() } },
                        label = { Text(stringResource(R.string.connections_field_port)) },
                        placeholder = { Text("5900") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(120.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = spicePassword,
                        onValueChange = { spicePassword = it },
                        label = { Text(stringResource(R.string.connections_field_password_optional)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.connections_helper_spice_auth),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (connectionType == "SMB") {
                    ConnectionSection(stringResource(R.string.connections_section_smb))
                    // SMB: host (with discovery), share, username, port, password, domain, SSH tunnel
                    val filteredSmbHosts = remember(discoveredSmbHosts, host) {
                        val prefix = host.lowercase()
                        discoveredSmbHosts
                            .filter {
                                prefix.isEmpty() ||
                                    it.address.startsWith(prefix) ||
                                    it.hostname?.lowercase()?.contains(prefix) == true
                            }
                            .take(8)
                    }

                    // Scan network button
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (filteredSmbHosts.isNotEmpty()) {
                            Text(
                                stringResource(R.string.connections_discovered_count, filteredSmbHosts.size),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                        TextButton(
                            onClick = onScanSubnetSmb,
                            enabled = !smbSubnetScanning,
                        ) {
                            if (smbSubnetScanning) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.connections_scanning), style = MaterialTheme.typography.labelSmall)
                            } else {
                                Icon(Icons.Filled.Radar, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.connections_scan_network), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    var smbHostExpanded by remember { mutableStateOf(false) }
                    if (discoveredSmbHosts.size > 3) {
                        ExposedDropdownMenuBox(
                            expanded = smbHostExpanded,
                            onExpandedChange = { smbHostExpanded = it },
                        ) {
                            OutlinedTextField(
                                value = host,
                                onValueChange = {
                                    host = it
                                    smbHostExpanded = true
                                },
                                label = { Text(stringResource(R.string.connections_field_host)) },
                                placeholder = { Text("192.168.1.100") },
                                singleLine = true,
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = smbHostExpanded)
                                },
                                supportingText = if (filteredSmbHosts.isNotEmpty()) {{
                                    Text(stringResource(R.string.connections_hosts_discovered, filteredSmbHosts.size))
                                }} else null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(MenuAnchorType.PrimaryEditable),
                            )
                            if (filteredSmbHosts.isNotEmpty()) {
                                ExposedDropdownMenu(
                                    expanded = smbHostExpanded,
                                    onDismissRequest = { smbHostExpanded = false },
                                ) {
                                    filteredSmbHosts.forEach { disc ->
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text(disc.hostname ?: disc.address)
                                                    if (disc.hostname != null) {
                                                        Text(
                                                            disc.address,
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        )
                                                    }
                                                }
                                            },
                                            onClick = {
                                                host = disc.address
                                                if (label.isBlank() && disc.hostname != null) {
                                                    label = disc.hostname
                                                }
                                                smbHostExpanded = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        if (filteredSmbHosts.isNotEmpty()) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                filteredSmbHosts.forEach { disc ->
                                    val chipLabel = disc.hostname ?: disc.address
                                    SuggestionChip(
                                        onClick = {
                                            host = disc.address
                                            if (label.isBlank() && disc.hostname != null) {
                                                label = disc.hostname
                                            }
                                        },
                                        label = {
                                            Text(
                                                text = chipLabel,
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                        },
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                        OutlinedTextField(
                            value = host,
                            onValueChange = { host = it },
                            label = { Text(stringResource(R.string.connections_field_host)) },
                            placeholder = { Text("192.168.1.100") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = smbShare,
                        onValueChange = { smbShare = it },
                        label = { Text(stringResource(R.string.connections_field_share_name)) },
                        placeholder = { Text("shared") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = { Text(stringResource(R.string.connections_field_username)) },
                            placeholder = { Text("user") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = port,
                            onValueChange = { port = it.filter { c -> c.isDigit() } },
                            label = { Text(stringResource(R.string.connections_field_port)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(80.dp),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    sh.haven.core.ui.PasswordField(
                        value = smbPassword,
                        onValueChange = { smbPassword = it },
                        label = stringResource(R.string.connections_field_password_optional),
                        modifier = Modifier.fillMaxWidth(),
                        onRevealRequest = onRevealSavedSecret,
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = smbDomain,
                        onValueChange = { smbDomain = it },
                        label = { Text(stringResource(R.string.connections_field_domain_optional)) },
                        placeholder = { Text("WORKGROUP") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    SshTunnelBlock(
                        enabled = smbSshForward,
                        carrierId = smbSshProfileId,
                        sshProfiles = sshProfiles,
                        onEnabledChange = { newValue ->
                            smbSshForward = newValue
                            if (!newValue) smbSshProfileId = null
                            host = tunnelHostOnToggle(newValue, host)
                        },
                        onCarrierChange = { smbSshProfileId = it },
                    )
                } else if (connectionType == "SSH") {
                    CollapsibleSection(stringResource(R.string.connections_section_connection), secConnectionExpanded, { secConnectionExpanded = !secConnectionExpanded }) {
                    // Discovered hosts — filter by typed prefix
                    val filteredHosts = remember(discoveredHosts, host) {
                        val prefix = host.lowercase()
                        // Ambient discovery (mDNS, local scan) narrows as you type.
                        // A jump-host scan does not: it answers a button the user
                        // just pressed, and it runs on an EXISTING profile whose
                        // host field is already filled in — prefix-matching against
                        // that field hid every result on the jump host's network,
                        // which is the entire point of scanning through it.
                        val (explicit, ambient) = discoveredHosts.partition { it.source == "jump" }
                        (
                            explicit + ambient.filter {
                                prefix.isEmpty() ||
                                    it.address.startsWith(prefix) ||
                                    it.hostname?.lowercase()?.contains(prefix) == true
                            }
                            ).take(8)
                    }

                    // Scan buttons. The jump-host scan sits beside the local one
                    // rather than behind an opt-in switch: pressing it is itself
                    // the deliberate act, so a separate toggle only added a step.
                    val jumpForScan = sshProfiles.firstOrNull {
                        it.id == jumpProfileId && it.id != existing?.id && it.isSsh
                    }
                    if (filteredHosts.isNotEmpty()) {
                        Text(
                            stringResource(R.string.connections_discovered_count, filteredHosts.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // FlowRow so a long translated pair — de: "Scannen" +
                    // "Über Jump-Host scannen" — wraps instead of overflowing a
                    // narrow phone.
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            onClick = onScanSubnet,
                            enabled = !subnetScanning,
                        ) {
                            if (subnetScanning) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.connections_scanning), style = MaterialTheme.typography.labelSmall)
                            } else {
                                Icon(Icons.Filled.Radar, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.connections_scan_network), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if (jumpForScan != null) {
                            TextButton(
                                onClick = { onScanSubnetViaJump(jumpForScan.id) },
                                enabled = !jumpScanning,
                            ) {
                                if (jumpScanning) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        stringResource(R.string.connections_jump_scanning),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                } else {
                                    Icon(Icons.Filled.Radar, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        stringResource(R.string.connections_jump_scan_button),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                    }
                    jumpScanError?.let { err ->
                        Text(
                            err,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    // Use dropdown when there are many discovered hosts (decision
                    // based on unfiltered count to avoid switching widgets mid-typing)
                    var hostExpanded by remember { mutableStateOf(false) }
                    if (discoveredHosts.size > 3) {
                        ExposedDropdownMenuBox(
                            expanded = hostExpanded,
                            onExpandedChange = { hostExpanded = it },
                        ) {
                            OutlinedTextField(
                                value = host,
                                onValueChange = {
                                    host = it
                                    hostExpanded = true
                                },
                                label = { Text(stringResource(R.string.connections_field_host)) },
                                placeholder = { Text("192.168.1.1") },
                                singleLine = true,
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = hostExpanded)
                                },
                                supportingText = if (filteredHosts.isNotEmpty()) {{
                                    Text(stringResource(R.string.connections_hosts_discovered, filteredHosts.size))
                                }} else null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(MenuAnchorType.PrimaryEditable),
                            )
                            if (filteredHosts.isNotEmpty()) {
                                ExposedDropdownMenu(
                                    expanded = hostExpanded,
                                    onDismissRequest = { hostExpanded = false },
                                ) {
                                    filteredHosts.forEach { disc ->
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text(disc.hostname ?: disc.address)
                                                    if (disc.hostname != null) {
                                                        Text(
                                                            disc.address + if (disc.port != 22) ":${disc.port}" else "",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        )
                                                    }
                                                }
                                            },
                                            onClick = {
                                                host = disc.address
                                                if (disc.port != 22) port = disc.port.toString()
                                                if (label.isBlank() && disc.hostname != null) {
                                                    label = disc.hostname
                                                }
                                                hostExpanded = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        // Few or no discovered hosts — show chips + plain text field
                        if (filteredHosts.isNotEmpty()) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                filteredHosts.forEach { disc ->
                                    val chipLabel = disc.hostname ?: disc.address
                                    SuggestionChip(
                                        onClick = {
                                            host = disc.address
                                            if (disc.port != 22) port = disc.port.toString()
                                            if (label.isBlank() && disc.hostname != null) {
                                                label = disc.hostname
                                            }
                                        },
                                        label = {
                                            Text(
                                                text = chipLabel,
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                        },
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                        OutlinedTextField(
                            value = host,
                            onValueChange = { host = it },
                            label = { Text(stringResource(R.string.connections_field_host)) },
                            placeholder = { Text("192.168.1.1") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = { Text(stringResource(R.string.connections_field_username)) },
                            placeholder = { Text(stringResource(R.string.connections_username_placeholder_ask)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        // Port field hidden when this SSH profile routes via
                        // a Cloudflare Tunnel transport (GH #154) — the CF
                        // tunnel dial ignores port; the upstream SSH target
                        // is decided server-side from the published route.
                        if (!useCloudflareTunnel) {
                            OutlinedTextField(
                                value = port,
                                onValueChange = { port = it.filter { c -> c.isDigit() } },
                                label = { Text(stringResource(R.string.connections_field_port)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.width(80.dp),
                            )
                        }
                    }

                    // Jump host selector — exclude self to prevent circular references
                    val jumpCandidates = sshProfiles.filter { it.id != existing?.id && it.isSsh }
                    if (jumpCandidates.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        var jumpExpanded by remember { mutableStateOf(false) }
                        val selectedJump = jumpCandidates.firstOrNull { it.id == jumpProfileId }

                        ExposedDropdownMenuBox(
                            expanded = jumpExpanded,
                            onExpandedChange = { jumpExpanded = it },
                        ) {
                            OutlinedTextField(
                                value = selectedJump?.label ?: stringResource(R.string.connections_dropdown_none_direct),
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(stringResource(R.string.connections_field_jump_host)) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = jumpExpanded) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                            )
                            ExposedDropdownMenu(
                                expanded = jumpExpanded,
                                onDismissRequest = { jumpExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.connections_dropdown_none_direct)) },
                                    onClick = {
                                        jumpProfileId = null
                                        jumpExpanded = false
                                    },
                                )
                                jumpCandidates.forEach { candidate ->
                                    DropdownMenuItem(
                                        text = { Text(candidate.label) },
                                        onClick = {
                                            jumpProfileId = candidate.id
                                            jumpExpanded = false
                                        },
                                    )
                                }
                            }
                        }

                        // Visual chain indicator
                        if (selectedJump != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.PhoneAndroid, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(4.dp))
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(4.dp))
                                Text(selectedJump.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(4.dp))
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(4.dp))
                                Icon(Icons.Filled.Storage, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                    }


                    ConnectionSection(stringResource(R.string.connections_section_routing))
                    routingBody()
                    }
                    CollapsibleSection(stringResource(R.string.connections_section_terminal), secTerminalExpanded, { secTerminalExpanded = !secTerminalExpanded }) {
                    // Session manager
                    Spacer(Modifier.height(4.dp))
                    val defaultSessionLabel = stringResource(
                        R.string.connections_session_default_with,
                        globalSessionManagerLabel,
                    )
                    val noneSessionLabel = stringResource(R.string.connections_dropdown_none)
                    val sessionManagerOptions = listOf(
                        null to defaultSessionLabel,
                        "NONE" to noneSessionLabel,
                        "TMUX" to "tmux",
                        "ZELLIJ" to "zellij",
                        "SCREEN" to "screen",
                        "BYOBU" to "byobu",
                    )
                    var smExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = smExpanded,
                        onExpandedChange = { smExpanded = it },
                    ) {
                        OutlinedTextField(
                            value = sessionManagerOptions.firstOrNull { it.first == selectedSessionManager }?.second ?: defaultSessionLabel,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.connections_field_session_manager)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(smExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        )
                        ExposedDropdownMenu(
                            expanded = smExpanded,
                            onDismissRequest = { smExpanded = false },
                        ) {
                            sessionManagerOptions.forEach { (value, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        selectedSessionManager = value
                                        smExpanded = false
                                    },
                                )
                            }
                        }
                    }

                    // ET port (shown only for Eternal Terminal)
                    if (selectedTransport == "ET") {
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = etPort,
                            onValueChange = { etPort = it.filter { c -> c.isDigit() } },
                            label = { Text(stringResource(R.string.connections_field_et_port)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(120.dp),
                        )
                    }

                    // Transport helper text
                    if (selectedTransport == "MOSH") {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.connections_helper_mosh_required),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = moshServerCommand,
                            onValueChange = { moshServerCommand = it },
                            label = { Text(stringResource(R.string.connections_field_mosh_server_command)) },
                            placeholder = { Text("mosh-server new -s -c 256 -l LANG=en_US.UTF-8") },
                            supportingText = { Text(stringResource(R.string.connections_helper_mosh_server)) },
                            singleLine = false,
                            minLines = 1,
                            maxLines = 3,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (selectedTransport == "ET") {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(
                                R.string.connections_helper_et_required,
                                etPort.ifBlank { "2022" },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // SSH options
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = sshOptions,
                        onValueChange = { sshOptions = it },
                        label = { Text(stringResource(R.string.connections_field_ssh_options)) },
                        placeholder = { Text("ServerAliveInterval 60\nServerAliveCountMax 3") },
                        supportingText = { Text(stringResource(R.string.connections_helper_ssh_options)) },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // Post-login command
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = postLoginCommand,
                        onValueChange = { postLoginCommand = it },
                        label = { Text(stringResource(R.string.connections_field_post_login)) },
                        placeholder = { Text("cd /app && clear") },
                        supportingText = {
                            Text(
                                if (postLoginCommand.isNotBlank() && postLoginBeforeSessionManager) {
                                    stringResource(R.string.connections_helper_run_before_session_manager)
                                } else {
                                    stringResource(R.string.connections_helper_post_login)
                                },
                            )
                        },
                        singleLine = false,
                        minLines = 1,
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (postLoginCommand.isNotBlank()) {
                        BooleanToggleRow(
                            label = stringResource(R.string.connections_toggle_run_before_session_manager),
                            checked = postLoginBeforeSessionManager,
                            onCheckedChange = { postLoginBeforeSessionManager = it },
                        )
                    }

                    // Remote command runs as an SSH exec request, before any
                    // interactive shell startup file can take control. Mosh
                    // forwards it to mosh-server with `--`; ET has no matching
                    // bootstrap contract, so it intentionally does not expose it.
                    if (selectedTransport != "ET") {
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = remoteCommand,
                            onValueChange = { remoteCommand = it },
                            label = { Text(stringResource(R.string.connections_field_remote_command)) },
                            placeholder = { Text("tmux new -A -s work") },
                            supportingText = { Text(stringResource(R.string.connections_helper_remote_command)) },
                            singleLine = false,
                            minLines = 1,
                            maxLines = 3,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (remoteCommand.isNotBlank()) {
                            BooleanToggleRow(
                                label = stringResource(R.string.connections_toggle_request_pty),
                                checked = requestPty,
                                onCheckedChange = { requestPty = it },
                            )
                        }
                    }

                    // USB/IP device forwarding (SSH) — export a phone-attached USB
                    // device to the remote host on connect (e.g. a YubiKey for
                    // `ssh-keygen -t ed25519-sk`, touch on the phone).
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.connections_section_usb_forward),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(R.string.connections_helper_usb_forward),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    var usbExpanded by remember { mutableStateOf(false) }
                    val usbNoneLabel = stringResource(R.string.connections_usb_forward_none)
                    val usbSelectedLabel = usbDevices.firstOrNull { it.first == usbForwardVidPid }?.second
                        ?: usbForwardVidPid?.let { stringResource(R.string.connections_usb_forward_unattached, it) }
                        ?: usbNoneLabel
                    ExposedDropdownMenuBox(
                        expanded = usbExpanded,
                        onExpandedChange = { usbExpanded = it },
                    ) {
                        OutlinedTextField(
                            value = usbSelectedLabel,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.connections_field_usb_forward)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = usbExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        )
                        ExposedDropdownMenu(
                            expanded = usbExpanded,
                            onDismissRequest = { usbExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(usbNoneLabel) },
                                onClick = { usbForwardVidPid = null; usbExpanded = false },
                            )
                            usbDevices.forEach { (vidPid, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = { usbForwardVidPid = vidPid; usbExpanded = false },
                                )
                            }
                        }
                    }

                    // File transport picker — Auto / SFTP / SCP (legacy)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.connections_section_file_transport),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(R.string.connections_helper_file_transport),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(modifier = Modifier.fillMaxWidth()) {
                        listOf(
                            Triple("AUTO",
                                stringResource(R.string.connections_file_transport_auto),
                                stringResource(R.string.connections_file_transport_auto_desc)),
                            Triple("SFTP",
                                stringResource(R.string.connections_file_transport_sftp_label),
                                stringResource(R.string.connections_file_transport_sftp_desc)),
                            Triple("SCP",
                                stringResource(R.string.connections_file_transport_scp_label),
                                stringResource(R.string.connections_file_transport_scp_desc)),
                        ).forEach { (value, label, sub) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { fileTransport = value }
                                    .padding(vertical = 4.dp),
                            ) {
                                RadioButton(
                                    selected = fileTransport == value,
                                    onClick = { fileTransport = value },
                                )
                                Column(modifier = Modifier.padding(start = 8.dp)) {
                                    Text(label, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        sub,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                    // SSH engine picker (#58) — JSch / sshlib. Stored as a
                    // HavenSshEngine directive inside sshOptions, no schema bump.
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.connections_section_ssh_engine),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(R.string.connections_helper_ssh_engine),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(modifier = Modifier.fillMaxWidth()) {
                        listOf(
                            Triple("JSCH",
                                stringResource(R.string.connections_ssh_engine_jsch_label),
                                stringResource(R.string.connections_ssh_engine_jsch_desc)),
                            Triple("SSHLIB",
                                stringResource(R.string.connections_ssh_engine_sshlib_label),
                                stringResource(R.string.connections_ssh_engine_sshlib_desc)),
                        ).forEach { (value, label, sub) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { sshOptions = setSshEngineInOptions(sshOptions, value) }
                                    .padding(vertical = 4.dp),
                            ) {
                                RadioButton(
                                    selected = sshEngineFromOptions(sshOptions) == value,
                                    onClick = { sshOptions = setSshEngineInOptions(sshOptions, value) },
                                )
                                Column(modifier = Modifier.padding(start = 8.dp)) {
                                    Text(label, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        sub,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                    // Alternate screen buffer toggle
                    Spacer(Modifier.height(4.dp))
                    BooleanToggleRow(
                        label = stringResource(R.string.connections_toggle_disable_alt_screen),
                        checked = disableAltScreen,
                        onCheckedChange = { disableAltScreen = it },
                        description = stringResource(R.string.connections_helper_disable_alt_screen),
                    )

                    // Per-profile terminal colour scheme (#144). Null on the
                    // profile means "inherit the global setting"; setting a
                    // scheme here overrides only this profile so users can
                    // distinguish servers by background colour at a glance.
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.connections_field_color_scheme),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    val activeSchemeEnum = remember(terminalColorScheme) {
                        terminalColorScheme?.let { name ->
                            runCatching {
                                UserPreferencesRepository.TerminalColorScheme.valueOf(name)
                            }.getOrNull()
                        }
                    }
                    val schemeBg = when {
                        activeSchemeEnum == null -> MaterialTheme.colorScheme.surfaceVariant
                        activeSchemeEnum.isDynamic -> MaterialTheme.colorScheme.surface
                        else -> Color(activeSchemeEnum.background)
                    }
                    val schemeFg = when {
                        activeSchemeEnum == null -> MaterialTheme.colorScheme.onSurfaceVariant
                        activeSchemeEnum.isDynamic -> MaterialTheme.colorScheme.onSurface
                        else -> Color(activeSchemeEnum.foreground)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showColorSchemeDialog = true }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(schemeBg)
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.outline,
                                    RoundedCornerShape(4.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "A",
                                color = schemeFg,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(activeSchemeEnum?.label ?: stringResource(R.string.connections_color_scheme_inherit))
                    }

                    // Per-profile terminal background opacity. Off = inherit the
                    // global pref; on = explicit value. Below 100% the terminal
                    // shows the device wallpaper behind the text on this profile.
                    Spacer(Modifier.height(8.dp))
                    val opacityOverride = terminalBackgroundOpacity != null
                    BooleanToggleRow(
                        label = stringResource(R.string.connections_field_background_opacity),
                        checked = opacityOverride,
                        onCheckedChange = { on ->
                            terminalBackgroundOpacity =
                                if (on) (terminalBackgroundOpacity ?: 1f) else null
                        },
                        description = stringResource(R.string.connections_helper_background_opacity),
                    )
                    if (opacityOverride) {
                        val pct = ((terminalBackgroundOpacity ?: 1f) * 100).roundToInt()
                        Text(
                            stringResource(R.string.connections_background_opacity_value, pct),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Slider(
                            value = terminalBackgroundOpacity ?: 1f,
                            onValueChange = { terminalBackgroundOpacity = it },
                            valueRange = 0f..1f,
                        )
                    }

                    }
                    CollapsibleSection(stringResource(R.string.connections_section_authentication), secAuthExpanded, { secAuthExpanded = !secAuthExpanded }) {
                    // Identity picker (#360): a reusable credential bundle
                    // overrides the fields below at connect time. "Inherit"
                    // (null) takes the group's identity when set; "This
                    // connection's own credentials" (NONE_ID) opts out even
                    // inside a group; picking one uses its username/password/key.
                    if (identities.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        var identityExpanded by remember { mutableStateOf(false) }
                        val selectedIdentity = identities.firstOrNull { it.id == identityId }
                        val identityFieldValue = when {
                            selectedIdentity != null -> selectedIdentity.name
                            identityId == sh.haven.core.data.db.entities.SshIdentity.NONE_ID ->
                                stringResource(R.string.connections_identity_own)
                            else -> stringResource(R.string.connections_identity_inherit)
                        }
                        ExposedDropdownMenuBox(
                            expanded = identityExpanded,
                            onExpandedChange = { identityExpanded = it },
                        ) {
                            OutlinedTextField(
                                value = identityFieldValue,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(stringResource(R.string.connections_field_identity)) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(identityExpanded) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                            )
                            ExposedDropdownMenu(
                                expanded = identityExpanded,
                                onDismissRequest = { identityExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.connections_identity_inherit)) },
                                    onClick = { identityId = null; identityExpanded = false },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.connections_identity_own)) },
                                    onClick = {
                                        identityId = sh.haven.core.data.db.entities.SshIdentity.NONE_ID
                                        identityExpanded = false
                                    },
                                )
                                identities.forEach { ident ->
                                    DropdownMenuItem(
                                        text = { Text(ident.name) },
                                        onClick = { identityId = ident.id; identityExpanded = false },
                                    )
                                }
                            }
                        }
                        Text(
                            stringResource(R.string.connections_identity_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // Agent forwarding toggle (OpenSSH ForwardAgent)
                    Spacer(Modifier.height(4.dp))
                    BooleanToggleRow(
                        label = stringResource(R.string.connections_toggle_forward_agent),
                        checked = forwardAgent,
                        onCheckedChange = { forwardAgent = it },
                        description = stringResource(R.string.connections_helper_forward_agent),
                    )

                    // Address family (#137) — for networks where one of A or
                    // AAAA resolves but doesn't route. Per-connection.
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.connection_address_family_label),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    val addressFamilyOptions = listOf(
                        "AUTO" to stringResource(R.string.connection_address_family_auto),
                        "IPV4_ONLY" to stringResource(R.string.connection_address_family_ipv4_only),
                        "IPV6_ONLY" to stringResource(R.string.connection_address_family_ipv6_only),
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        addressFamilyOptions.forEachIndexed { index, (key, label) ->
                            SegmentedButton(
                                selected = addressFamily == key,
                                onClick = { addressFamily = key },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = addressFamilyOptions.size,
                                ),
                            ) { Text(label) }
                        }
                    }
                    val familyHint = when (addressFamily) {
                        "IPV4_ONLY" -> stringResource(R.string.connection_address_family_ipv4_hint)
                        "IPV6_ONLY" -> stringResource(R.string.connection_address_family_ipv6_hint)
                        else -> null
                    }
                    if (familyHint != null) {
                        Text(
                            familyHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // Ordered auth methods (#166): attempt these in order in a
                    // single connect, so a server requiring publickey+password
                    // (or PAM chains) is satisfied. A single method behaves
                    // exactly as the old key/password picker did.
                    Spacer(Modifier.height(8.dp))
                    AuthMethodsEditor(
                        specsText = authMethodsText,
                        sshKeys = sshKeys,
                        totpSecrets = totpSecrets,
                        onChange = { authMethodsText = it },
                    )
                    // TOTP auto-fill submit behaviour (#178): shown only when
                    // the chain carries a TOTP element.
                    if (ConnectionProfile.AuthMethodSpec.parseList(authMethodsText)
                            .any { it is ConnectionProfile.AuthMethodSpec.Totp }
                    ) {
                        BooleanToggleRow(
                            label = stringResource(R.string.connections_auth_confirm_otp),
                            checked = totpConfirmBeforeSend,
                            onCheckedChange = { totpConfirmBeforeSend = it },
                            description = stringResource(R.string.connections_auth_confirm_otp_desc),
                        )
                    }

                    // Password-only (#121): never offer saved SSH keys. For a
                    // password-only server, the auto-key-offer otherwise
                    // suppresses the password prompt when any key is stored.
                    BooleanToggleRow(
                        label = stringResource(R.string.connections_toggle_password_only),
                        checked = ignoreSavedKeys,
                        onCheckedChange = { ignoreSavedKeys = it },
                        description = stringResource(R.string.connections_helper_password_only),
                    )

                    // Saved VNC settings (shown when the SSH profile has had
                    // VNC configured via the terminal's VNC quick-dialog with
                    // "Save for this connection" ticked). Without this block
                    // the only way to edit was to delete and recreate the
                    // profile — #104.
                    ConnectionSection(stringResource(R.string.connections_section_port_knock))
                    portKnockBody()
                    ConnectionSection(stringResource(R.string.connections_section_spa))
                    spaBody()
                    }

                    CollapsibleSection("Reliability & MCP", secReliabilityExpanded, { secReliabilityExpanded = !secReliabilityExpanded }) {
                        // ── AI agent (MCP) ── both directions grouped together.
                        Text(
                            stringResource(R.string.connections_section_mcp),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(Modifier.height(4.dp))
                        // Inbound: can the agent read / drive this connection. Off =
                        // agent tools that target it are refused (its row robot shows a
                        // slash). Lets you pre-disable a connection the agent never touched.
                        BooleanToggleRow(
                            label = stringResource(R.string.connections_toggle_mcp_enabled),
                            checked = mcpEnabled,
                            // Turning inbound MCP off also drops the reverse exposure —
                            // exposing Haven to a host you won't let the agent drive
                            // makes no sense, so toggle 2 is gated on this one.
                            onCheckedChange = { mcpEnabled = it; if (!it) mcpReverseTunnel = false },
                            description = stringResource(R.string.connections_helper_mcp_enabled),
                        )
                        // Outbound exposure: reverse-tunnel Haven's own MCP back to the
                        // remote host. SSH only (mosh/ET can't carry an SSH -R forward);
                        // disabled while inbound MCP is off.
                        if (selectedTransport == "SSH") {
                            Spacer(Modifier.height(8.dp))
                            BooleanToggleRow(
                                label = stringResource(R.string.connections_toggle_mcp_tunnel),
                                checked = mcpReverseTunnel,
                                onCheckedChange = { mcpReverseTunnel = it },
                                description = stringResource(R.string.connections_helper_mcp_tunnel),
                                enabled = mcpEnabled,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        // ── Reliability ──
                        // Tunnel-only mode (#150): bring up the SSH transport +
                        // port forwards without opening a terminal. Pair with
                        // unlimited reconnect for autossh-style keepalive.
                        BooleanToggleRow(
                            label = stringResource(R.string.connections_toggle_tunnel_only),
                            checked = tunnelOnly,
                            onCheckedChange = { tunnelOnly = it },
                            description = stringResource(R.string.connections_helper_tunnel_only),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.connections_section_reconnect),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(Modifier.height(4.dp))
                        BooleanToggleRow(
                            label = stringResource(R.string.connections_toggle_auto_reconnect),
                            checked = autoReconnect,
                            onCheckedChange = { autoReconnect = it },
                        )
                        if (autoReconnect) {
                            Spacer(Modifier.height(4.dp))
                            OutlinedTextField(
                                value = reconnectMaxAttempts,
                                onValueChange = { v -> reconnectMaxAttempts = v.filter { c -> c.isDigit() }.take(4) },
                                label = { Text(stringResource(R.string.connections_field_reconnect_max_attempts)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        BooleanToggleRow(
                            label = stringResource(R.string.connections_toggle_reconnect_on_network_change),
                            checked = reconnectOnNetworkChange,
                            onCheckedChange = { reconnectOnNetworkChange = it },
                        )
                    }

                    if (vncSettingsStored) {
                        CollapsibleSection(stringResource(R.string.connections_section_embedded_vnc), secEmbeddedVncExpanded, { secEmbeddedVncExpanded = !secEmbeddedVncExpanded }) {
                        OutlinedTextField(
                            value = vncSavedPort,
                            onValueChange = { vncSavedPort = it.filter { c -> c.isDigit() } },
                            label = { Text(stringResource(R.string.connections_field_vnc_port)) },
                            placeholder = { Text("5900") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(120.dp),
                        )
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = vncUsername,
                            onValueChange = { vncUsername = it },
                            label = { Text(stringResource(R.string.connections_field_username_vnc)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = vncPassword,
                            onValueChange = { vncPassword = it },
                            label = { Text(stringResource(R.string.connections_field_vnc_password)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Checkbox(
                                checked = vncSavedSshForward,
                                onCheckedChange = { vncSavedSshForward = it },
                            )
                            Text(stringResource(R.string.connections_field_tunnel_through_ssh))
                        }
                        Spacer(Modifier.height(8.dp))
                        // Colour-depth picker — same shape as the dedicated
                        // VNC-profile path. Was missing here, so users with
                        // an SSH profile that stored VNC settings via the
                        // terminal's quick dialog had no way to switch to
                        // 256-colour mode (#107 follow-up from Nesos-ita).
                        val savedDepthOptions = listOf(
                            "BPP_24_TRUE" to stringResource(R.string.connections_vnc_depth_24),
                            "BPP_16_TRUE" to stringResource(R.string.connections_vnc_depth_16),
                            "BPP_8_INDEXED" to stringResource(R.string.connections_vnc_depth_8),
                        )
                        var savedDepthExpanded by remember { mutableStateOf(false) }
                        val selectedSavedDepth = savedDepthOptions.firstOrNull { it.first == vncColorDepth }
                            ?: savedDepthOptions.first()
                        ExposedDropdownMenuBox(
                            expanded = savedDepthExpanded,
                            onExpandedChange = { savedDepthExpanded = it },
                        ) {
                            OutlinedTextField(
                                value = selectedSavedDepth.second,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(stringResource(R.string.connections_field_vnc_color_depth)) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(savedDepthExpanded) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                            )
                            ExposedDropdownMenu(
                                expanded = savedDepthExpanded,
                                onDismissRequest = { savedDepthExpanded = false },
                            ) {
                                savedDepthOptions.forEach { (value, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            vncColorDepth = value
                                            savedDepthExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        TextButton(onClick = { vncSettingsStored = false }) {
                            Text(stringResource(R.string.connections_action_clear_vnc))
                        }
                        }
                    }

                } else {
                    // --- Reticulum connection form ---
                    // Order: gateway config → scan → destination hash
                    ConnectionSection(stringResource(R.string.connections_section_reticulum))

                    // 1. Gateway configuration
                    BooleanToggleRow(
                        label = stringResource(R.string.connections_toggle_local_sideband),
                        checked = localSideband,
                        onCheckedChange = { newValue ->
                            localSideband = newValue
                            if (newValue) {
                                rnsHost = "127.0.0.1"
                                rnsPort = "37428"
                            }
                        },
                    )
                    if (!localSideband) {
                        Spacer(Modifier.height(4.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = rnsHost,
                                onValueChange = { rnsHost = it },
                                label = { Text(stringResource(R.string.connections_field_gateway_host)) },
                                placeholder = { Text("192.168.0.2") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = rnsPort,
                                onValueChange = { rnsPort = it.filter { c -> c.isDigit() } },
                                label = { Text(stringResource(R.string.connections_field_port)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.width(80.dp),
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = rnsNetworkName,
                            onValueChange = { rnsNetworkName = it },
                            label = { Text(stringResource(R.string.connections_field_network_name)) },
                            placeholder = { Text(stringResource(R.string.connections_helper_network_name)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(4.dp))
                        sh.haven.core.ui.PasswordField(
                            value = rnsPassphrase,
                            onValueChange = { rnsPassphrase = it },
                            label = stringResource(R.string.connections_field_passphrase),
                            placeholder = stringResource(R.string.connections_hint_ifac_passphrase),
                            modifier = Modifier.fillMaxWidth(),
                            onRevealRequest = onRevealSavedSecret,
                        )
                    }

                    // 2. Scan for destinations (uses gateway config above)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            val scanHost = if (localSideband) "127.0.0.1" else rnsHost
                            val scanPort = if (localSideband) 37428 else (rnsPort.toIntOrNull() ?: 4242)
                            onScanReticulum(
                                scanHost,
                                scanPort,
                                rnsNetworkName.ifBlank { null },
                                rnsPassphrase.ifBlank { null },
                            )
                        },
                        enabled = !reticulumScanning,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (reticulumScanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.connections_scanning) + "…")
                        } else {
                            Text(stringResource(R.string.connections_action_scan_rnsh))
                        }
                    }

                    // 3. Discovered destinations (tapping a chip fills the hash)
                    val filtered = remember(discoveredDestinations, destinationHash) {
                        val prefix = destinationHash.lowercase()
                        discoveredDestinations
                            .filter { prefix.isEmpty() || it.hash.startsWith(prefix) }
                            .take(8)
                    }
                    if (filtered.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        val hiddenCount = discoveredDestinations.size - filtered.size
                        Text(
                            text = if (hiddenCount > 0) {
                                stringResource(R.string.connections_discovered_count_filtered, filtered.size, discoveredDestinations.size)
                            } else {
                                stringResource(R.string.connections_discovered_count, filtered.size)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            filtered.forEach { dest ->
                                val hopsLabel = if (dest.hops >= 0) " (${dest.hops}h)" else ""
                                SuggestionChip(
                                    onClick = { destinationHash = dest.hash },
                                    label = {
                                        Text(
                                            text = dest.hash.take(12) + ".." + hopsLabel,
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    },
                                )
                            }
                        }
                    }

                    // 4. Destination hash (auto-filled by chip tap, or manual entry)
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = destinationHash,
                        onValueChange = {
                            destinationHash = it.filter { c -> c in "0123456789abcdefABCDEF" }
                                .take(32)
                        },
                        label = { Text(stringResource(R.string.connections_field_destination_hash)) },
                        placeholder = { Text(stringResource(R.string.connections_helper_destination_hash)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // Post-login command
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = postLoginCommand,
                        onValueChange = { postLoginCommand = it },
                        label = { Text(stringResource(R.string.connections_field_post_login)) },
                        placeholder = { Text("cd /app && clear") },
                        supportingText = { Text(stringResource(R.string.connections_helper_post_login)) },
                        singleLine = false,
                        minLines = 1,
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // Port knocking. Visible for any profile with a remote
                // TCP host — skipped for LOCAL (no host), RCLONE (its own
                // protocol), and RETICULUM (mesh, not TCP).
                if (connectionType in setOf("VNC", "RDP", "SPICE", "SMB", "EMAIL")) {
                    ConnectionSection(stringResource(R.string.connections_section_routing))
                    routingBody()
                }
                if (connectionType in setOf("VNC", "RDP", "SPICE", "SMB", "EMAIL")) {
                    ConnectionSection(stringResource(R.string.connections_section_port_knock))
                    portKnockBody()
                    ConnectionSection(stringResource(R.string.connections_section_spa))
                    spaBody()
                }

                // Route through — shared picker for SOCKS / HTTP proxy and
                // WireGuard / Tailscale tunnel. Was SSH-only until #149;
                // VNC, RDP, and SMB now also honour profile.tunnelConfigId
                // so the picker has to surface for them too. LOCAL has no
                // network; RCLONE and Reticulum manage their own transport.
                // Mutually exclusive at the UI layer: picking any tunnel
                // clears proxy fields; picking any proxy clears
                // tunnelConfigId. The connect path enforces tunnel >
                // jump > proxy precedence.

            }
        },
        confirmButton = {
            val knockOk = KnockSequence.parse(
                portKnockSequence,
                portKnockDelayMs.toIntOrNull() ?: KnockSequence.DEFAULT_DELAY_MS,
            ).isSuccess
            val canSave = knockOk && when (connectionType) {
                "LOCAL" -> true // No host/auth needed
                "BTSERIAL" -> btDevice.isNotBlank() // a paired device must be picked
                "BLESERIAL" -> bleDevice.isNotBlank() // a scanned device must be picked
                "USBSERIAL" -> usbDevice.isNotBlank() && (usbBaud.toIntOrNull() ?: 0) > 0
                "SSH" -> host.isNotBlank()
                "VNC" -> host.isNotBlank() && tunnelComplete(vncSshForward, vncSshProfileId)
                "RDP" -> host.isNotBlank() && rdpUsername.isNotBlank() && tunnelComplete(rdpSshForward, rdpSshProfileId)
                "SPICE" -> host.isNotBlank() && tunnelComplete(spiceSshForward, spiceSshProfileId)
                "SMB" -> host.isNotBlank() && smbShare.isNotBlank() && tunnelComplete(smbSshForward, smbSshProfileId)
                // OAuth providers authenticate on connect (no Configure step); every
                // other provider must have its remote written via Configure first (#295).
                "RCLONE" -> rcloneProvider.isNotBlank() &&
                    (rcloneProvider in sh.haven.core.rclone.RCLONE_OAUTH_PROVIDERS || rcloneConfigured)
                "EMAIL" -> emailUsername.isNotBlank() && emailPassword.isNotBlank() &&
                    (!emailProvider.equals("imap", ignoreCase = true) || emailServer.isNotBlank())
                else -> destinationHash.length == 32 && (localSideband || rnsHost.isNotBlank())
            }
            TextButton(
                onClick = {
                    val etPortInt = etPort.toIntOrNull() ?: 2022
                    val profile = if (connectionType == "LOCAL") {
                        (existing ?: ConnectionProfile(
                            label = label,
                            host = "localhost",
                            username = "",
                        )).copy(
                            label = label.ifBlank { if (useAndroidShell) "Android Shell" else "Local Shell" },
                            host = "localhost",
                            port = 0,
                            username = "",
                            connectionType = "LOCAL",
                            useAndroidShell = useAndroidShell,
                            prootDistroId = if (useAndroidShell) null else prootDistroId,
                            colorTag = colorTag,
                            groupId = groupId,
                            identityId = identityId,
                        )
                    } else if (connectionType == "BTSERIAL") {
                        // The paired device MAC lives in `host` (no new column).
                        (existing ?: ConnectionProfile(
                            label = label,
                            host = btDevice,
                            username = "",
                        )).copy(
                            label = label.ifBlank { "Bluetooth: $btDevice" },
                            host = btDevice,
                            port = 0,
                            username = "",
                            connectionType = "BTSERIAL",
                            colorTag = colorTag,
                            groupId = groupId,
                            identityId = identityId,
                        )
                    } else if (connectionType == "BLESERIAL") {
                        // The BLE device MAC lives in `host` (no new column); the
                        // GATT UUIDs are auto-detected (NUS / HM-10) at connect.
                        (existing ?: ConnectionProfile(
                            label = label,
                            host = bleDevice,
                            username = "",
                        )).copy(
                            label = label.ifBlank { "BLE: $bleDevice" },
                            host = bleDevice,
                            port = 0,
                            username = "",
                            connectionType = "BLESERIAL",
                            colorTag = colorTag,
                            groupId = groupId,
                            identityId = identityId,
                        )
                    } else if (connectionType == "USBSERIAL") {
                        // vid:pid → `host`, baud → `port`, the rest of the line
                        // format (data/parity/stop/flow) → `sshOptions`. No new
                        // column, mirroring how host/port are repurposed. #408
                        val usbLineConfig = sh.haven.core.usbserial.UsbSerialParams(
                            dataBits = usbDataBits.toIntOrNull() ?: 8,
                            parity = sh.haven.core.usbserial.UsbSerialParams.Parity.valueOf(usbParity),
                            stopBits = sh.haven.core.usbserial.UsbSerialParams.StopBits.valueOf(usbStopBits),
                            flowControl = sh.haven.core.usbserial.UsbSerialParams.FlowControl.valueOf(usbFlow),
                        ).toConfigString()
                        (existing ?: ConnectionProfile(
                            label = label,
                            host = usbDevice,
                            username = "",
                        )).copy(
                            label = label.ifBlank { "USB: $usbDevice" },
                            host = usbDevice,
                            port = usbBaud.toIntOrNull() ?: 115200,
                            sshOptions = usbLineConfig,
                            username = "",
                            connectionType = "USBSERIAL",
                            colorTag = colorTag,
                            groupId = groupId,
                            identityId = identityId,
                        )
                    } else if (connectionType == "VNC") {
                        val vncPortInt = port.toIntOrNull() ?: 5900
                        (existing ?: ConnectionProfile(
                            label = label,
                            host = host,
                            username = "",
                        )).copy(
                            label = label.ifBlank { "VNC: $host" },
                            host = host,
                            port = vncPortInt,
                            username = "",
                            connectionType = "VNC",
                            vncPort = vncPortInt,
                            vncUsername = vncUsername.ifBlank { null },
                            vncPassword = vncPassword.ifBlank { null },
                            vncSshForward = vncSshForward,
                            vncSshProfileId = tunnelCarrierForSave(vncSshForward, vncSshProfileId),
                            vncColorDepth = vncColorDepth,
                            colorTag = colorTag,
                            groupId = groupId,
                            identityId = identityId,
                            portKnockSequence = portKnockSequence.ifBlank { null },
                            portKnockDelayMs = portKnockDelayMs.toIntOrNull()
                                ?.coerceAtLeast(0) ?: KnockSequence.DEFAULT_DELAY_MS,
                            spaKey = spaKey.ifBlank { null },
                            spaKeyBase64 = spaKeyBase64,
                            spaHmacKey = spaHmacKey.ifBlank { null },
                            spaHmacKeyBase64 = spaHmacKeyBase64,
                            spaAccessSpec = spaAccessSpec.ifBlank { null },
                            spaAllowMode = spaAllowMode,
                            spaExplicitIp = spaExplicitIp.ifBlank { null },
                            spaPort = spaPort.toIntOrNull()?.takeIf { it in 1..65535 }
                                ?: SpaConfig.DEFAULT_SPA_PORT,
                        ).withRoutingSelection(
                            proxyType, proxyHost, proxyPort, proxyUser, proxyPassword, tunnelConfigId,
                        )
                    } else if (connectionType == "RDP") {
                        val rdpPortInt = port.toIntOrNull() ?: 3389
                        (existing ?: ConnectionProfile(
                            label = label,
                            host = host,
                            username = rdpUsername,
                        )).copy(
                            label = label.ifBlank { "RDP: $rdpUsername@$host" },
                            host = host,
                            port = rdpPortInt,
                            username = rdpUsername,
                            connectionType = "RDP",
                            rdpPort = rdpPortInt,
                            rdpUsername = rdpUsername.ifBlank { null },
                            rdpPassword = rdpPassword.ifBlank { null },
                            rdpDomain = rdpDomain.ifBlank { null },
                            rdpSshForward = rdpSshForward,
                            rdpSshProfileId = tunnelCarrierForSave(rdpSshForward, rdpSshProfileId),
                            rdpUseNla = rdpUseNla,
                            rdpColorDepth = rdpColorDepth,
                            colorTag = colorTag,
                            groupId = groupId,
                            identityId = identityId,
                            portKnockSequence = portKnockSequence.ifBlank { null },
                            portKnockDelayMs = portKnockDelayMs.toIntOrNull()
                                ?.coerceAtLeast(0) ?: KnockSequence.DEFAULT_DELAY_MS,
                            spaKey = spaKey.ifBlank { null },
                            spaKeyBase64 = spaKeyBase64,
                            spaHmacKey = spaHmacKey.ifBlank { null },
                            spaHmacKeyBase64 = spaHmacKeyBase64,
                            spaAccessSpec = spaAccessSpec.ifBlank { null },
                            spaAllowMode = spaAllowMode,
                            spaExplicitIp = spaExplicitIp.ifBlank { null },
                            spaPort = spaPort.toIntOrNull()?.takeIf { it in 1..65535 }
                                ?: SpaConfig.DEFAULT_SPA_PORT,
                        ).withRoutingSelection(
                            proxyType, proxyHost, proxyPort, proxyUser, proxyPassword, tunnelConfigId,
                        )
                    } else if (connectionType == "SPICE") {
                        val spicePortInt = port.toIntOrNull() ?: 5900
                        (existing ?: ConnectionProfile(
                            label = label,
                            host = host,
                            username = "",
                        )).copy(
                            label = label.ifBlank { "SPICE: $host" },
                            host = host,
                            port = spicePortInt,
                            username = "",
                            connectionType = "SPICE",
                            spicePort = spicePortInt,
                            spicePassword = spicePassword.ifBlank { null },
                            spiceSshForward = spiceSshForward,
                            spiceSshProfileId = tunnelCarrierForSave(spiceSshForward, spiceSshProfileId),
                            colorTag = colorTag,
                            groupId = groupId,
                            identityId = identityId,
                            portKnockSequence = portKnockSequence.ifBlank { null },
                            portKnockDelayMs = portKnockDelayMs.toIntOrNull()
                                ?.coerceAtLeast(0) ?: KnockSequence.DEFAULT_DELAY_MS,
                            spaKey = spaKey.ifBlank { null },
                            spaKeyBase64 = spaKeyBase64,
                            spaHmacKey = spaHmacKey.ifBlank { null },
                            spaHmacKeyBase64 = spaHmacKeyBase64,
                            spaAccessSpec = spaAccessSpec.ifBlank { null },
                            spaAllowMode = spaAllowMode,
                            spaExplicitIp = spaExplicitIp.ifBlank { null },
                            spaPort = spaPort.toIntOrNull()?.takeIf { it in 1..65535 }
                                ?: SpaConfig.DEFAULT_SPA_PORT,
                        ).withRoutingSelection(
                            proxyType, proxyHost, proxyPort, proxyUser, proxyPassword, tunnelConfigId,
                        )
                    } else if (connectionType == "RCLONE") {
                        (existing ?: ConnectionProfile(
                            label = label,
                            host = "",
                            username = "",
                        )).copy(
                            label = label.ifBlank {
                                val providerLabel = when (rcloneProvider) {
                                    "drive" -> "Google Drive"
                                    "dropbox" -> "Dropbox"
                                    "onedrive" -> "OneDrive"
                                    "s3" -> "Amazon S3"
                                    "b2" -> "Backblaze B2"
                                    "mega" -> "MEGA"
                                    "pcloud" -> "pCloud"
                                    "box" -> "Box"
                                    "filen" -> "Filen"
                                    else -> rcloneProvider
                                }
                                providerLabel
                            },
                            host = "",
                            port = 0,
                            username = "",
                            connectionType = "RCLONE",
                            rcloneRemoteName = rcloneRemoteName.ifBlank { "$rcloneProvider-$rcloneRemoteToken" },
                            rcloneProvider = rcloneProvider,
                            colorTag = colorTag,
                            groupId = groupId,
                            identityId = identityId,
                        )
                    } else if (connectionType == "EMAIL") {
                        (existing ?: ConnectionProfile(
                            label = label,
                            host = host,
                            username = emailUsername,
                        )).copy(
                            label = label.ifBlank {
                                emailUsername.ifBlank {
                                    if (emailProvider.equals("imap", true)) "Email" else "Proton Mail"
                                }
                            },
                            // host carries the optional tunnel-ingress/bastion that
                            // SPA/knock guards; for Proton the mail server is Proton's,
                            // for IMAP it's emailServer (reached through the tunnel).
                            host = host,
                            port = 0,
                            username = emailUsername,
                            connectionType = "EMAIL",
                            emailProvider = emailProvider,
                            emailUsername = emailUsername,
                            emailPassword = emailPassword.ifBlank { null },
                            emailMailboxPassword = emailMailboxPassword.ifBlank { null },
                            emailServer = emailServer.ifBlank { null },
                            emailSmtpServer = emailSmtpServer.ifBlank { null },
                            emailPort = emailPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: 993,
                            emailSmtpPort = emailSmtpPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: 465,
                            emailTls = emailTls,
                            colorTag = colorTag,
                            groupId = groupId,
                            identityId = identityId,
                            tunnelConfigId = tunnelConfigId,
                            portKnockSequence = portKnockSequence.ifBlank { null },
                            portKnockDelayMs = portKnockDelayMs.toIntOrNull()
                                ?.coerceAtLeast(0) ?: KnockSequence.DEFAULT_DELAY_MS,
                            spaKey = spaKey.ifBlank { null },
                            spaKeyBase64 = spaKeyBase64,
                            spaHmacKey = spaHmacKey.ifBlank { null },
                            spaHmacKeyBase64 = spaHmacKeyBase64,
                            spaAccessSpec = spaAccessSpec.ifBlank { null },
                            spaAllowMode = spaAllowMode,
                            spaExplicitIp = spaExplicitIp.ifBlank { null },
                            spaPort = spaPort.toIntOrNull()?.takeIf { it in 1..65535 }
                                ?: SpaConfig.DEFAULT_SPA_PORT,
                        )
                    } else if (connectionType == "SMB") {
                        val smbPortInt = port.toIntOrNull() ?: 445
                        (existing ?: ConnectionProfile(
                            label = label,
                            host = host,
                            username = username,
                        )).copy(
                            label = label.ifBlank { "SMB: \\\\$host\\$smbShare" },
                            host = host,
                            port = smbPortInt,
                            username = username,
                            connectionType = "SMB",
                            smbPort = smbPortInt,
                            smbShare = smbShare.ifBlank { null },
                            smbPassword = smbPassword.ifBlank { null },
                            smbDomain = smbDomain.ifBlank { null },
                            smbSshForward = smbSshForward,
                            smbSshProfileId = tunnelCarrierForSave(smbSshForward, smbSshProfileId),
                            colorTag = colorTag,
                            groupId = groupId,
                            identityId = identityId,
                            portKnockSequence = portKnockSequence.ifBlank { null },
                            portKnockDelayMs = portKnockDelayMs.toIntOrNull()
                                ?.coerceAtLeast(0) ?: KnockSequence.DEFAULT_DELAY_MS,
                            spaKey = spaKey.ifBlank { null },
                            spaKeyBase64 = spaKeyBase64,
                            spaHmacKey = spaHmacKey.ifBlank { null },
                            spaHmacKeyBase64 = spaHmacKeyBase64,
                            spaAccessSpec = spaAccessSpec.ifBlank { null },
                            spaAllowMode = spaAllowMode,
                            spaExplicitIp = spaExplicitIp.ifBlank { null },
                            spaPort = spaPort.toIntOrNull()?.takeIf { it in 1..65535 }
                                ?: SpaConfig.DEFAULT_SPA_PORT,
                        ).withRoutingSelection(
                            proxyType, proxyHost, proxyPort, proxyUser, proxyPassword, tunnelConfigId,
                        )
                    } else if (connectionType == "SSH") {
                        // CF tunnel transport forces port 22 (the tunnel
                        // dial ignores port; this keeps downstream consumers
                        // that read `profile.port` sensible).
                        val portInt = if (useCloudflareTunnel) 22 else (port.toIntOrNull() ?: 22)
                        // Saved VNC settings round-trip: if the section was
                        // visible and the user didn't hit "Clear", persist the
                        // edited values; if they cleared it, null everything
                        // out so the quick-dialog reprompts next time.
                        val vncPortInt = if (vncSettingsStored) vncSavedPort.toIntOrNull() else null
                        (existing ?: ConnectionProfile(
                            label = label,
                            host = host,
                            port = portInt,
                            username = username,
                        )).copy(
                            label = label.ifBlank { "$username@$host" },
                            host = host,
                            port = portInt,
                            username = username,
                            connectionType = "SSH",
                            destinationHash = null,
                            jumpProfileId = jumpProfileId,
                            proxyType = if (useCloudflareTunnel) null else proxyType,
                            proxyHost = if (useCloudflareTunnel) null else proxyHost.ifBlank { null },
                            proxyPort = proxyPort.toIntOrNull() ?: 1080,
                            proxyUser = if (useCloudflareTunnel || proxyType == null) null
                                else proxyUser.ifBlank { null },
                            proxyPassword = if (useCloudflareTunnel || proxyType == null || proxyType == "SOCKS4") null
                                else proxyPassword.ifBlank { null },
                            // #166: persist the ordered method list, and keep
                            // the legacy authType/keyId in sync with the
                            // primary (first) method so code paths that still
                            // read them (list_connections, audit labels) stay
                            // correct.
                            authMethods = authMethodsText,
                            // Reflect a key in the legacy authType when ANY method
                            // is a key (not just the first) — a [Password, Key]
                            // chain previously reported authType=PASSWORD/keyId=null,
                            // hiding the pinned key from list_connections and the
                            // legacy resolve path.
                            authType = if (ConnectionProfile.AuthMethodSpec.parseList(authMethodsText)
                                    .any { it is ConnectionProfile.AuthMethodSpec.Key }
                            ) ConnectionProfile.AuthType.KEY else ConnectionProfile.AuthType.PASSWORD,
                            keyId = ConnectionProfile.AuthMethodSpec.parseList(authMethodsText)
                                .filterIsInstance<ConnectionProfile.AuthMethodSpec.Key>()
                                .firstOrNull()?.keyId,
                            totpConfirmBeforeSend = totpConfirmBeforeSend,
                            ignoreSavedKeys = ignoreSavedKeys,
                            // tunnelConfigId is owned by the ViewModel save
                            // helper when the user picks the inline CF
                            // transport — it overwrites this with the
                            // upserted embedded tunnel id. Anything else
                            // (shared standalone tunnel) is persisted as-is.
                            tunnelConfigId = if (useCloudflareTunnel) null else tunnelConfigId,
                            sshOptions = sshOptions.ifBlank { null },
                            moshServerCommand = moshServerCommand.ifBlank { null },
                            postLoginCommand = postLoginCommand.ifBlank { null },
                            postLoginBeforeSessionManager = postLoginBeforeSessionManager,
                            remoteCommand = remoteCommand.ifBlank { null },
                            requestPty = requestPty,
                            usbForwardVidPid = usbForwardVidPid,
                            disableAltScreen = disableAltScreen,
                            terminalColorScheme = terminalColorScheme,
                            terminalBackgroundOpacity = terminalBackgroundOpacity,
                            useAndroidShell = useAndroidShell,
                            prootDistroId = if (useAndroidShell) null else prootDistroId,
                            forwardAgent = forwardAgent,
                            addressFamily = addressFamily,
                            autoReconnect = autoReconnect,
                            reconnectMaxAttempts = reconnectMaxAttempts.toIntOrNull()?.coerceAtLeast(0) ?: 5,
                            reconnectOnNetworkChange = reconnectOnNetworkChange,
                            tunnelOnly = tunnelOnly,
                            mcpEnabled = mcpEnabled,
                            sessionManager = selectedSessionManager,
                            useMosh = selectedTransport == "MOSH",
                            useEternalTerminal = selectedTransport == "ET",
                            etPort = etPortInt,
                            fileTransport = fileTransport,
                            vncPort = vncPortInt,
                            vncUsername = if (vncSettingsStored) vncUsername.ifBlank { null } else null,
                            vncPassword = if (vncSettingsStored) vncPassword.ifBlank { null } else null,
                            vncSshForward = if (vncSettingsStored) vncSavedSshForward else true,
                            vncColorDepth = if (vncSettingsStored) vncColorDepth else "BPP_24_TRUE",
                            colorTag = colorTag,
                            groupId = groupId,
                            identityId = identityId,
                            portKnockSequence = portKnockSequence.ifBlank { null },
                            portKnockDelayMs = portKnockDelayMs.toIntOrNull()
                                ?.coerceAtLeast(0) ?: KnockSequence.DEFAULT_DELAY_MS,
                            spaKey = spaKey.ifBlank { null },
                            spaKeyBase64 = spaKeyBase64,
                            spaHmacKey = spaHmacKey.ifBlank { null },
                            spaHmacKeyBase64 = spaHmacKeyBase64,
                            spaAccessSpec = spaAccessSpec.ifBlank { null },
                            spaAllowMode = spaAllowMode,
                            spaExplicitIp = spaExplicitIp.ifBlank { null },
                            spaPort = spaPort.toIntOrNull()?.takeIf { it in 1..65535 }
                                ?: SpaConfig.DEFAULT_SPA_PORT,
                        )
                    } else {
                        val savedHost = if (localSideband) "127.0.0.1" else rnsHost
                        val savedPort = if (localSideband) 37428 else (rnsPort.toIntOrNull() ?: 4242)
                        (existing ?: ConnectionProfile(
                            label = label,
                            host = "",
                            port = 0,
                            username = "",
                        )).copy(
                            label = label.ifBlank { "RNS:${destinationHash.take(12)}" },
                            host = "",
                            port = 0,
                            username = "",
                            connectionType = "RETICULUM",
                            destinationHash = destinationHash.lowercase(),
                            reticulumHost = savedHost,
                            reticulumPort = savedPort,
                            reticulumNetworkName = rnsNetworkName.ifBlank { null },
                            reticulumPassphrase = rnsPassphrase.ifBlank { null },
                            postLoginCommand = postLoginCommand.ifBlank { null },
                            postLoginBeforeSessionManager = postLoginBeforeSessionManager,
                            colorTag = colorTag,
                            groupId = groupId,
                            identityId = identityId,
                        )
                    }
                    // Embedded Cloudflare Tunnel transport input — only
                    // non-null for SSH profiles that opted in via the
                    // route-through dropdown. Hostname mirrors the SSH
                    // host field; the ViewModel save helper trims/normalises.
                    val cfInput = if (useCloudflareTunnel && connectionType == "SSH") {
                        EmbeddedCloudflareTunnelInput(
                            hostname = host,
                            teamDomain = cfTeamDomain,
                            jwt = cfJwt,
                            jwtExpiresAt = cfExpiresAt,
                            jumpDestination = cfJumpDestination,
                        )
                    } else null
                    // MCP reverse tunnel only applies to plain SSH, and only
                    // when inbound MCP is allowed for this connection (the
                    // toggle is gated on mcpEnabled). For other transports or
                    // when MCP is off, report false so the screen tears down
                    // any stale rule.
                    onSave(profile, cfInput, mcpReverseTunnel && mcpEnabled && selectedTransport == "SSH")
                },
                enabled = canSave,
            ) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )

    if (showColorSchemeDialog) {
        ProfileColorSchemeDialog(
            current = terminalColorScheme,
            onDismiss = { showColorSchemeDialog = false },
            onSelect = { selected ->
                terminalColorScheme = selected
                showColorSchemeDialog = false
            },
        )
    }
}

/**
 * Section heading inside [ConnectionEditDialog]. Mirrors
 * `feature.settings.SettingsScreen`'s `SettingsSection` — uppercase
 * `labelMedium` in the primary colour — so the connection form reads as
 * labelled groups rather than one long scroll.
 */
@Composable
private fun ConnectionSection(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

/**
 * A [ConnectionSection] header that folds its [content] away. Tapping the
 * header toggles [expanded] via [onToggle]; a chevron rotates to show
 * state. Used to declutter the (long) connection editor so new users see
 * only the essential sections expanded and can reveal the rest on demand.
 */
@Composable
private fun CollapsibleSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) { onToggle() }
            .padding(top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = if (expanded) "Collapse $title" else "Expand $title",
            tint = MaterialTheme.colorScheme.primary,
        )
    }
    if (expanded) {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

/**
 * Dialog for picking a per-profile terminal colour-scheme override.
 * Mirrors the global [feature.settings.ColorSchemeDialog] but adds a
 * "Use default" sentinel at the top — selecting it stores `null` on the
 * profile so the terminal falls back to the global preference.
 */
@Composable
private fun ProfileColorSchemeDialog(
    current: String?,
    onDismiss: () -> Unit,
    onSelect: (String?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.connections_field_color_scheme)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                ListItem(
                    headlineContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .border(
                                        1.dp,
                                        MaterialTheme.colorScheme.outline,
                                        RoundedCornerShape(4.dp),
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "—",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(R.string.connections_color_scheme_inherit))
                        }
                    },
                    leadingContent = {
                        RadioButton(selected = current == null, onClick = null)
                    },
                    modifier = Modifier.clickable(role = Role.RadioButton) { onSelect(null) },
                )
                UserPreferencesRepository.TerminalColorScheme.entries.forEach { scheme ->
                    val previewBg = if (scheme.isDynamic) MaterialTheme.colorScheme.surface
                        else Color(scheme.background)
                    val previewFg = if (scheme.isDynamic) MaterialTheme.colorScheme.onSurface
                        else Color(scheme.foreground)
                    ListItem(
                        headlineContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(previewBg)
                                        .border(
                                            1.dp,
                                            MaterialTheme.colorScheme.outline,
                                            RoundedCornerShape(4.dp),
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "A",
                                        color = previewFg,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Text(scheme.label)
                            }
                        },
                        leadingContent = {
                            RadioButton(
                                selected = current == scheme.name,
                                onClick = null,
                            )
                        },
                        modifier = Modifier.clickable(role = Role.RadioButton) {
                            onSelect(scheme.name)
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/**
 * User-facing label for a tunnel type, shown under each tunnel entry in
 * the Route-through dropdown. Kept in sync with `tunnelTypeLabel` in
 * `feature:tunnel` — duplicated here rather than re-exported because
 * exposing the private helper across module boundaries would surface a
 * UI string from another feature's translation unit.
 */
private fun friendlyTunnelTypeLabel(t: sh.haven.core.data.db.entities.TunnelConfigType): String =
    when (t) {
        sh.haven.core.data.db.entities.TunnelConfigType.WIREGUARD -> "WireGuard"
        sh.haven.core.data.db.entities.TunnelConfigType.TAILSCALE -> "Tailscale"
        sh.haven.core.data.db.entities.TunnelConfigType.CLOUDFLARE_ACCESS -> "Cloudflare Tunnel"
    }

/**
 * Boolean toggle row using a Material 3 [Switch]. Used in place of
 * [FilterChip] for on/off settings — FilterChip's selected/unselected
 * states are visually subtle (a tonal background shade), so a `Switch`
 * reads ON/OFF unambiguously. Enum-style chip groups (sync mode,
 * file-transport selector) keep using FilterChip.
 *
 * The whole row is clickable so the tap target isn't limited to the
 * switch thumb; an optional [description] line renders directly below
 * the label when the toggle is on.
 */
/**
 * The shared "Tunnel through SSH" editor block: toggle row plus the SSH
 * carrier dropdown (or an "add an SSH connection first" error when no SSH
 * profiles exist). The dropdown sits right next to the toggle so the two
 * fields read as one decision — "tunnel through [this SSH connection]".
 * VNC / RDP / SPICE / SMB render this at their existing form positions;
 * host-field relabelling stays at the call site because labels,
 * placeholders and helper text are protocol-specific. Host rewrites on
 * toggle go through [tunnelHostOnToggle] in the caller's onEnabledChange.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SshTunnelBlock(
    enabled: Boolean,
    carrierId: String?,
    sshProfiles: List<ConnectionProfile>,
    onEnabledChange: (Boolean) -> Unit,
    onCarrierChange: (String) -> Unit,
) {
    BooleanToggleRow(
        label = stringResource(R.string.connections_field_tunnel_through_ssh),
        checked = enabled,
        onCheckedChange = onEnabledChange,
    )
    if (!enabled) return
    val sshCandidates = sshProfiles.filter { it.isSsh }
    if (sshCandidates.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        var sshExpanded by remember { mutableStateOf(false) }
        val selectedSsh = sshCandidates.firstOrNull { it.id == carrierId }
        ExposedDropdownMenuBox(
            expanded = sshExpanded,
            onExpandedChange = { sshExpanded = it },
        ) {
            OutlinedTextField(
                value = selectedSsh?.label ?: stringResource(R.string.connections_dropdown_select_ssh),
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.connections_field_ssh_connection)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(sshExpanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(
                expanded = sshExpanded,
                onDismissRequest = { sshExpanded = false },
            ) {
                sshCandidates.forEach { candidate ->
                    DropdownMenuItem(
                        text = { Text(candidate.label) },
                        onClick = {
                            onCarrierChange(candidate.id)
                            sshExpanded = false
                        },
                    )
                }
            }
        }
    } else {
        Text(
            stringResource(R.string.connections_helper_add_ssh_first),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/**
 * Read the SSH-engine choice out of the sshOptions text (#58): "SSHLIB" when
 * a `HavenSshEngine sshlib` line is present, "JSCH" otherwise. Mirrors
 * `ConnectionConfig.sshEngine`'s safe-fallback parse.
 */
internal fun sshEngineFromOptions(options: String): String {
    val sshlib = options.lineSequence().any { line ->
        val trimmed = line.trim()
        if (trimmed.startsWith("#")) return@any false
        val sep = trimmed.indexOfFirst { it == ' ' || it == '=' }
        sep > 0 &&
            trimmed.substring(0, sep).trim().equals("HavenSshEngine", ignoreCase = true) &&
            trimmed.substring(sep + 1).trim().equals("sshlib", ignoreCase = true)
    }
    return if (sshlib) "SSHLIB" else "JSCH"
}

/** Rewrite the sshOptions text with the given engine choice ("JSCH" drops the directive). */
internal fun setSshEngineInOptions(options: String, engine: String): String {
    val kept = options.lines().filterNot { line ->
        val trimmed = line.trim()
        val sep = trimmed.indexOfFirst { it == ' ' || it == '=' }
        sep > 0 && trimmed.substring(0, sep).trim().equals("HavenSshEngine", ignoreCase = true)
    }
    val base = kept.joinToString("\n").trim('\n')
    return when {
        engine != "SSHLIB" -> base
        base.isBlank() -> "HavenSshEngine sshlib"
        else -> "$base\nHavenSshEngine sshlib"
    }
}

@Composable
private fun BooleanToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String? = null,
    enabled: Boolean = true,
) {
    androidx.compose.foundation.layout.Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (enabled) Modifier.clickable { onCheckedChange(!checked) } else Modifier)
                .padding(vertical = 4.dp),
        ) {
            Text(
                label,
                modifier = Modifier.weight(1f),
                color = if (enabled) androidx.compose.ui.graphics.Color.Unspecified
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            )
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
        if (checked && description != null) {
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Ordered editor for a profile's auth methods (#166). Operates on the
 * serialised [specsText] (so the caller can hold it in rememberSaveable)
 * and emits the new serialisation via [onChange]. Reorder with the
 * up/down arrows, remove with ✕ (the last method can't be removed), and
 * add Password / SSH key / Keyboard-interactive from the menu. A single
 * method behaves exactly as the old key/password picker.
 */
@Composable
private fun AuthMethodsEditor(
    specsText: String,
    sshKeys: List<sh.haven.core.data.db.entities.SshKey>,
    totpSecrets: List<sh.haven.core.data.db.entities.TotpSecret>,
    onChange: (String) -> Unit,
) {
    val specs = ConnectionProfile.AuthMethodSpec.parseList(specsText)
    fun emit(newList: List<ConnectionProfile.AuthMethodSpec>) =
        onChange(ConnectionProfile.AuthMethodSpec.serializeList(newList))
    fun swap(i: Int, j: Int) {
        val m = specs.toMutableList()
        val t = m[i]; m[i] = m[j]; m[j] = t
        emit(m)
    }

    Text(stringResource(R.string.connections_auth_methods_title), style = MaterialTheme.typography.bodyMedium)
    Text(
        stringResource(R.string.connections_auth_methods_description),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))

    specs.forEachIndexed { index, spec ->
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        ) {
            Text("${index + 1}.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(8.dp))
            when (spec) {
                ConnectionProfile.AuthMethodSpec.Password ->
                    Text(stringResource(R.string.common_password), modifier = Modifier.weight(1f))
                ConnectionProfile.AuthMethodSpec.KeyboardInteractive ->
                    Text(stringResource(R.string.connections_auth_method_keyboard_interactive), modifier = Modifier.weight(1f))
                ConnectionProfile.AuthMethodSpec.AnyHardwareKey ->
                    Text(stringResource(R.string.connections_auth_any_hardware_key), modifier = Modifier.weight(1f))
                is ConnectionProfile.AuthMethodSpec.Key -> {
                    var expanded by remember { mutableStateOf(false) }
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedButton(onClick = { expanded = true }) {
                            // Read as a picker ("Key: Any ▾" / "Key: yubi nano ▾"),
                            // not a bare "Any" that doesn't look tappable — a
                            // hardware-key user has no reason to guess otherwise.
                            Text(
                                stringResource(
                                    R.string.connections_auth_key_picker,
                                    sshKeys.firstOrNull { it.id == spec.keyId }?.label
                                        ?: stringResource(R.string.connections_auth_key_any),
                                ),
                            )
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.connections_auth_key_any_menu)) },
                                onClick = {
                                    emit(specs.toMutableList().also { it[index] = ConnectionProfile.AuthMethodSpec.Key(null) })
                                    expanded = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.connections_auth_any_hardware_key)) },
                                onClick = {
                                    emit(specs.toMutableList().also { it[index] = ConnectionProfile.AuthMethodSpec.AnyHardwareKey })
                                    expanded = false
                                },
                            )
                            sshKeys.forEach { key ->
                                DropdownMenuItem(
                                    text = { Text(key.label) },
                                    onClick = {
                                        emit(specs.toMutableList().also { it[index] = ConnectionProfile.AuthMethodSpec.Key(key.id) })
                                        expanded = false
                                    },
                                )
                            }
                            // One-tap exclusivity: clear the other methods so
                            // ONLY this key is offered (e.g. require a YubiKey).
                            // Only meaningful when there's more than one method.
                            if (specs.size > 1) {
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.connections_auth_key_only)) },
                                    onClick = {
                                        emit(listOf(spec))
                                        expanded = false
                                    },
                                )
                            }
                        }
                    }
                }
                is ConnectionProfile.AuthMethodSpec.Totp -> {
                    var expanded by remember { mutableStateOf(false) }
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedButton(onClick = { expanded = true }) {
                            Text(
                                totpSecrets.firstOrNull { it.id == spec.secretId }?.label
                                    ?: stringResource(R.string.connections_auth_totp_any),
                            )
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.connections_auth_totp_any_menu)) },
                                onClick = {
                                    emit(specs.toMutableList().also { it[index] = ConnectionProfile.AuthMethodSpec.Totp(null) })
                                    expanded = false
                                },
                            )
                            totpSecrets.forEach { secret ->
                                DropdownMenuItem(
                                    text = { Text(secret.label) },
                                    onClick = {
                                        emit(specs.toMutableList().also { it[index] = ConnectionProfile.AuthMethodSpec.Totp(secret.id) })
                                        expanded = false
                                    },
                                )
                            }
                        }
                    }
                }
                // ProtonSrp is EMAIL-only (emailAuthMethods), not part of the SSH
                // auth chain this editor manages; render a read-only label so the
                // when stays exhaustive.
                ConnectionProfile.AuthMethodSpec.ProtonSrp ->
                    Text(
                        stringResource(R.string.connections_auth_method_proton_srp),
                        modifier = Modifier.weight(1f),
                    )
            }
            IconButton(onClick = { swap(index, index - 1) }, enabled = index > 0) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.connections_auth_move_up))
            }
            IconButton(onClick = { swap(index, index + 1) }, enabled = index < specs.size - 1) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.connections_auth_move_down))
            }
            IconButton(
                onClick = { emit(specs.toMutableList().also { it.removeAt(index) }) },
                enabled = specs.size > 1,
            ) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.connections_auth_remove))
            }
        }
    }

    var addExpanded by remember { mutableStateOf(false) }
    // When the user picks "SSH key", switch the same menu to a key picker so
    // they choose WHICH key in one gesture, instead of dropping a generic
    // "Any" row they then have to tap again.
    var addKeyPicker by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { addKeyPicker = false; addExpanded = true }) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.connections_auth_add_method))
        }
        DropdownMenu(
            expanded = addExpanded,
            onDismissRequest = { addExpanded = false; addKeyPicker = false },
        ) {
            if (!addKeyPicker) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.common_password)) },
                    onClick = { emit(specs + ConnectionProfile.AuthMethodSpec.Password); addExpanded = false },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.connections_auth_method_ssh_key)) },
                    trailingIcon = { Icon(Icons.Default.KeyboardArrowRight, contentDescription = null) },
                    onClick = { addKeyPicker = true },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.connections_auth_method_keyboard_interactive)) },
                    onClick = { emit(specs + ConnectionProfile.AuthMethodSpec.KeyboardInteractive); addExpanded = false },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.connections_auth_method_totp)) },
                    onClick = { emit(specs + ConnectionProfile.AuthMethodSpec.Totp(null)); addExpanded = false },
                )
            } else {
                // Step 2: choose which key (or "Any saved key") to add.
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.connections_auth_key_any_menu)) },
                    onClick = {
                        emit(specs + ConnectionProfile.AuthMethodSpec.Key(null))
                        addExpanded = false; addKeyPicker = false
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.connections_auth_any_hardware_key)) },
                    onClick = {
                        emit(specs + ConnectionProfile.AuthMethodSpec.AnyHardwareKey)
                        addExpanded = false; addKeyPicker = false
                    },
                )
                sshKeys.forEach { key ->
                    DropdownMenuItem(
                        text = { Text(key.label) },
                        onClick = {
                            emit(specs + ConnectionProfile.AuthMethodSpec.Key(key.id))
                            addExpanded = false; addKeyPicker = false
                        },
                    )
                }
            }
        }
    }
}

/**
 * Paired-Bluetooth-device picker for a BTSERIAL connection (#406). Lists bonded
 * Classic devices (the SPP driver connects only to already-paired ones); requests
 * BLUETOOTH_CONNECT on demand (Android 12+). Reports (address, name) on selection.
 */
@android.annotation.SuppressLint("MissingPermission") // bonded-device reads are guarded by `granted`
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BtSerialDeviceField(
    selectedAddress: String,
    onSelect: (address: String, name: String) -> Unit,
) {
    val context = LocalContext.current
    val needsRuntimePermission = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S

    fun hasPermission(): Boolean = !needsRuntimePermission ||
        androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.BLUETOOTH_CONNECT,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    var granted by remember { mutableStateOf(hasPermission()) }

    fun bondedDevices(): List<Pair<String, String>> {
        if (!granted) return emptyList()
        val adapter = (context.getSystemService(android.content.Context.BLUETOOTH_SERVICE)
            as? android.bluetooth.BluetoothManager)?.adapter ?: return emptyList()
        return runCatching {
            adapter.bondedDevices.orEmpty().map { it.address to (it.name ?: it.address) }
        }.getOrDefault(emptyList())
    }

    var devices by remember { mutableStateOf(bondedDevices()) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ok ->
        granted = ok
        if (ok) devices = bondedDevices()
    }

    ConnectionSection(stringResource(R.string.connections_section_btserial))
    Text(
        stringResource(R.string.connections_btserial_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))

    if (!granted) {
        OutlinedButton(onClick = { permLauncher.launch(android.Manifest.permission.BLUETOOTH_CONNECT) }) {
            Text(stringResource(R.string.connections_btserial_permission))
        }
        return
    }
    if (devices.isEmpty()) {
        Text(
            stringResource(R.string.connections_btserial_no_devices),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = devices.firstOrNull { it.first == selectedAddress }
        ?.let { "${it.second} (${it.first})" } ?: ""
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.connections_btserial_device)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            devices.forEach { (address, name) ->
                DropdownMenuItem(
                    text = { Text("$name  ($address)") },
                    onClick = {
                        onSelect(address, name)
                        expanded = false
                    },
                )
            }
        }
    }
}

/**
 * BLE-serial device picker. A BLE-UART peripheral (an nRF board, an HM-10, a BLE
 * RS232 adapter) usually isn't bonded, so it must be discovered by an active
 * scan rather than read from bondedDevices. Requests BLUETOOTH_SCAN (+ pre-31
 * location) on demand, scans while the button is pressed (auto-stopping after a
 * timeout), and reports (address, name) on selection.
 */
@android.annotation.SuppressLint("MissingPermission") // scan/connect guarded by `granted`
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BleSerialDeviceField(
    selectedAddress: String,
    onSelect: (address: String, name: String) -> Unit,
) {
    val context = LocalContext.current
    val scanPerms = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        arrayOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
    }
    fun hasPerms(): Boolean = scanPerms.all {
        androidx.core.content.ContextCompat.checkSelfPermission(context, it) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    var granted by remember { mutableStateOf(hasPerms()) }
    var scanning by remember { mutableStateOf(false) }
    val found = remember { mutableStateMapOf<String, String>() } // MAC -> name

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result -> granted = result.values.all { it } }

    val scanner = remember {
        (context.getSystemService(android.content.Context.BLUETOOTH_SERVICE)
            as? android.bluetooth.BluetoothManager)?.adapter?.bluetoothLeScanner
    }
    val callback = remember {
        object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                val dev = result.device
                val name = result.scanRecord?.deviceName
                    ?: runCatching { dev.name }.getOrNull()
                    ?: dev.address
                found[dev.address] = name
            }
        }
    }

    // Scan only while `scanning`; always stop on leaving composition.
    androidx.compose.runtime.DisposableEffect(scanning, granted) {
        if (scanning && granted && scanner != null) {
            val settings = android.bluetooth.le.ScanSettings.Builder()
                .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            runCatching { scanner.startScan(null, settings, callback) }
        }
        onDispose { runCatching { scanner?.stopScan(callback) } }
    }
    LaunchedEffect(scanning) {
        if (scanning) {
            kotlinx.coroutines.delay(12_000)
            scanning = false
        }
    }

    ConnectionSection(stringResource(R.string.connections_section_bleserial))
    Text(
        stringResource(R.string.connections_bleserial_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))

    if (!granted) {
        OutlinedButton(onClick = { permLauncher.launch(scanPerms) }) {
            Text(stringResource(R.string.connections_bleserial_permission))
        }
        return
    }

    OutlinedButton(onClick = { found.clear(); scanning = true }, enabled = !scanning) {
        Text(
            stringResource(
                if (scanning) R.string.connections_bleserial_scanning
                else R.string.connections_bleserial_scan,
            ),
        )
    }
    Spacer(Modifier.height(8.dp))

    val entries = found.entries.map { it.key to it.value }.sortedBy { it.second }
    if (entries.isEmpty()) {
        if (!scanning) {
            Text(
                stringResource(R.string.connections_bleserial_no_devices),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = entries.firstOrNull { it.first == selectedAddress }
        ?.let { "${it.second} (${it.first})" }
        ?: selectedAddress
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.connections_bleserial_device)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            entries.forEach { (address, name) ->
                DropdownMenuItem(
                    text = { Text("$name  ($address)") },
                    onClick = {
                        onSelect(address, name)
                        expanded = false
                    },
                )
            }
        }
    }
}

/**
 * USB-serial device + baud picker for a USBSERIAL connection (#408). Lists the
 * currently-attached USB devices (listing needs no permission — only opening the
 * port does, which happens at connect via the Android USB prompt). Reports the
 * vendorId:productId key and a display name on selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UsbSerialDeviceField(
    selectedKey: String,
    baud: String,
    dataBits: String,
    parity: String,
    stopBits: String,
    flowControl: String,
    onSelectDevice: (key: String, name: String) -> Unit,
    onBaudChange: (String) -> Unit,
    onDataBitsChange: (String) -> Unit,
    onParityChange: (String) -> Unit,
    onStopBitsChange: (String) -> Unit,
    onFlowControlChange: (String) -> Unit,
) {
    val context = LocalContext.current

    fun usbDevices(): List<Pair<String, String>> {
        val mgr = context.getSystemService(android.content.Context.USB_SERVICE)
            as? android.hardware.usb.UsbManager ?: return emptyList()
        return runCatching {
            mgr.deviceList.values.map { d ->
                "%04x:%04x".format(d.vendorId, d.productId) to (d.productName ?: d.deviceName)
            }
        }.getOrDefault(emptyList())
    }

    val devices by remember { mutableStateOf(usbDevices()) }

    ConnectionSection(stringResource(R.string.connections_section_usbserial))
    Text(
        stringResource(R.string.connections_usbserial_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))

    if (devices.isEmpty()) {
        Text(
            stringResource(R.string.connections_usbserial_no_devices),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        var expanded by remember { mutableStateOf(false) }
        val selectedLabel = devices.firstOrNull { it.first == selectedKey }
            ?.let { "${it.second} (${it.first})" } ?: selectedKey
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = selectedLabel,
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.connections_usbserial_device)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                devices.forEach { (key, name) ->
                    DropdownMenuItem(
                        text = { Text("$name  ($key)") },
                        onClick = {
                            onSelectDevice(key, name)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = baud,
        onValueChange = { onBaudChange(it.filter(Char::isDigit)) },
        label = { Text(stringResource(R.string.connections_usbserial_baud)) },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    SerialOptionDropdown(
        label = stringResource(R.string.connections_usbserial_databits),
        options = listOf("5" to "5", "6" to "6", "7" to "7", "8" to "8"),
        selectedValue = dataBits,
        onSelect = onDataBitsChange,
    )
    Spacer(Modifier.height(8.dp))
    SerialOptionDropdown(
        label = stringResource(R.string.connections_usbserial_parity),
        options = listOf(
            "NONE" to stringResource(R.string.connections_usbserial_none),
            "ODD" to stringResource(R.string.connections_usbserial_parity_odd),
            "EVEN" to stringResource(R.string.connections_usbserial_parity_even),
            "MARK" to stringResource(R.string.connections_usbserial_parity_mark),
            "SPACE" to stringResource(R.string.connections_usbserial_parity_space),
        ),
        selectedValue = parity,
        onSelect = onParityChange,
    )
    Spacer(Modifier.height(8.dp))
    SerialOptionDropdown(
        label = stringResource(R.string.connections_usbserial_stopbits),
        options = listOf("ONE" to "1", "ONE_POINT_FIVE" to "1.5", "TWO" to "2"),
        selectedValue = stopBits,
        onSelect = onStopBitsChange,
    )
    Spacer(Modifier.height(8.dp))
    SerialOptionDropdown(
        label = stringResource(R.string.connections_usbserial_flowcontrol),
        options = listOf(
            "NONE" to stringResource(R.string.connections_usbserial_none),
            "RTS_CTS" to stringResource(R.string.connections_usbserial_flow_rtscts),
            "XON_XOFF" to stringResource(R.string.connections_usbserial_flow_xonxoff),
        ),
        selectedValue = flowControl,
        onSelect = onFlowControlChange,
    )
}

/**
 * A compact labelled dropdown for one serial line-format option. [options] maps
 * a stored value (enum name / number) to its display label; [selectedValue] is
 * the stored value, [onSelect] reports the newly chosen stored value.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SerialOptionDropdown(
    label: String,
    options: List<Pair<String, String>>,
    selectedValue: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selectedValue }?.second ?: selectedValue
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    },
                )
            }
        }
    }
}
