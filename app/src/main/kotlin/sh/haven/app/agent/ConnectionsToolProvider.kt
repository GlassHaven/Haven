package sh.haven.app.agent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import sh.haven.core.data.agent.AgentUiCommandBus
import sh.haven.core.data.agent.ConsentLevel
import sh.haven.core.data.agent.PendingAuthPromptHolder
import sh.haven.core.data.agent.SessionSelectionHolder
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.db.entities.PortForwardRule
import sh.haven.core.data.desktop.DesktopSessionRegistry
import sh.haven.core.data.repository.ConnectionLogRepository
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.data.repository.PortForwardRepository
import sh.haven.core.data.repository.SshKeyRepository
import sh.haven.core.data.repository.TunnelConfigRepository
import sh.haven.core.knock.PortKnocker
import sh.haven.core.mcp.McpError
import sh.haven.core.openai.AiRouteRegistry
import sh.haven.core.reticulum.ReticulumForwardServer
import sh.haven.core.reticulum.ReticulumSessionManager
import sh.haven.core.session.SessionManagerRegistry
import sh.haven.core.spa.SpaSender
import sh.haven.core.ssh.SshSessionManager
import sh.haven.core.tunnel.TunnelManager
import sh.haven.feature.terminal.agent.TerminalSessionRegistry

/**
 * Saved connection profile CRUD, routing, port-knock/SPA pre-connect gates,
 * port-forward rules, live session listing/disconnect, headless SSH `run_command`,
 * and pending auth/session-picker prompt resolution.
 */
internal class ConnectionsToolProvider(
    private val context: Context,
    private val connectionRepository: ConnectionRepository,
    private val portForwardRepository: PortForwardRepository,
    private val sshSessionManager: SshSessionManager,
    private val sessionManagerRegistry: SessionManagerRegistry,
    private val reticulumSessionManager: ReticulumSessionManager,
    private val reticulumForwardServer: ReticulumForwardServer,
    private val agentUiCommandBus: AgentUiCommandBus,
    private val tunnelConfigRepository: TunnelConfigRepository,
    private val tunnelManager: TunnelManager,
    private val terminalSessionRegistry: TerminalSessionRegistry,
    private val portKnocker: PortKnocker,
    private val spaSender: SpaSender,
    private val connectionLogRepository: ConnectionLogRepository,
    private val sshKeyRepository: SshKeyRepository,
    private val desktopSessionRegistry: DesktopSessionRegistry,
    private val aiRouteRegistry: AiRouteRegistry,
    private val headlessSshExec: HeadlessSshExec?,
    private val pendingAuthPromptHolder: PendingAuthPromptHolder,
    private val sessionSelectionHolder: SessionSelectionHolder,
) : ToolProvider {

    private fun profileLabel(profileId: String): String =
        runBlocking {
            connectionRepository.getById(profileId)?.label
        } ?: profileId

    override fun tools(): Map<String, ToolHandler> = toolMapOf(
        "list_connections" to ToolHandler(
            description = "List saved connection profiles (SSH, Mosh, VNC, RDP, SMB, rclone, local, Reticulum, OPENAI). Secrets like passwords and keys are redacted. SSH profiles also report `sshOptions` (the ssh_config-style lines set on the profile) and `sshEngine` — \"jsch\" (default) or \"sshlib\" (the experimental whole-connection engine, opted into with the 'HavenSshEngine sshlib' directive) — so an agent that sets the engine can confirm which one a profile is actually on.",
            inputSchema = emptyObjectSchema(),
        ) { _ -> listConnections() },

        "list_sessions" to ToolHandler(
            description = "List currently registered sessions across all transports (ssh, mosh, et, reticulum, rdp, smb, local, mail, openai, and Bluetooth/BLE/USB serial) with sessionId, profileId, label, status (connecting, connected, reconnecting, disconnected, error), transport, and isAgentRepl — a screen heuristic (Claude Code TUI chrome in the bottom lines) marking which terminal session is an agent REPL, so a conversation peer can be picked without guessing; null when the session has no attached terminal tab. SSH sessions additionally include sessionManager, chosenSessionName (the stable tmux/zellij identity that survives reconnects), channel state, jump-session linkage, and active port forwards.",
            inputSchema = emptyObjectSchema(),
        ) { _ -> listSessions() },

        "list_bluetooth_devices" to ToolHandler(
            description = "List Bluetooth Classic devices already paired (bonded) with the phone — { address, name } each. Use the address as `host` when creating a BTSERIAL (Bluetooth-serial console) profile with create_connection (#406). Only paired devices appear; pair a new BT-to-serial adapter in Android Settings → Bluetooth first. Returns an error hint if BLUETOOTH_CONNECT hasn't been granted (open the app's Bluetooth-serial connection editor once to grant it).",
            inputSchema = emptyObjectSchema(),
        ) { _ -> listBluetoothDevices() },

        "disconnect_profile" to ToolHandler(
            description = "Disconnect every live session for a profile across all transports (SSH, Mosh, Eternal Terminal, RDP, VNC, SMB, Reticulum, local, Bluetooth/BLE/USB serial). Use list_connections to find profileIds.",
            inputSchema = objectSchema {
                string("profileId", "ID of the connection profile to disconnect.", required = true)
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args -> "Disconnect from \"${profileLabel(args.optString("profileId"))}\"?" },
        ) { args -> disconnectProfile(args) },

        "add_port_forward" to ToolHandler(
            description = "Save a port-forward rule on an SSH profile. If the profile is currently connected the rule is also activated immediately. Type LOCAL=`-L` (local→remote), REMOTE=`-R` (remote→local), DYNAMIC=`-D` (SOCKS5 proxy server). Returns the saved rule's id and (when activated) the actually-bound port; activated:false with an error means the bind failed on the live session (e.g. port held in TIME_WAIT by a just-closed connection) — the rule is still saved and applies on the next connect.",
            inputSchema = objectSchema {
                string("profileId", "Owning profile ID.", required = true)
                string("type", "LOCAL | REMOTE | DYNAMIC.", required = true)
                string("bindAddress", "Bind address. Default 127.0.0.1.")
                integer("bindPort", "Bind port. 0 = OS picks (REMOTE only).", required = true)
                string("targetHost", "Target host (LOCAL/REMOTE only). Ignored for DYNAMIC.")
                integer("targetPort", "Target port (LOCAL/REMOTE only). Ignored for DYNAMIC.")
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                val pid = args.optString("profileId")
                val type = args.optString("type")
                val bp = args.optInt("bindPort")
                val target = if (type == "DYNAMIC") "(SOCKS)"
                    else "${args.optString("targetHost", "?")}:${args.optInt("targetPort", 0)}"
                "Add $type port forward $bp → $target on \"${profileLabel(pid)}\"?"
            },
        ) { args -> addPortForward(args) },

        "remove_port_forward" to ToolHandler(
            description = "Delete a port-forward rule by id, and deactivate it on the live session if the owning profile is currently connected.",
            inputSchema = objectSchema {
                string("ruleId", "ID of the rule to remove.", required = true)
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args -> "Remove port-forward rule ${args.optString("ruleId").take(8)}…?" },
        ) { args -> removePortForward(args) },

        "set_profile_routing" to ToolHandler(
            description = "Set or clear the Route-through configuration on a connection profile. Pass either tunnelConfigId (WireGuard / Tailscale tunnel from list_tunnels) OR proxyType+proxyHost+proxyPort (legacy SOCKS5 / SOCKS4 / HTTP), and the other field set is cleared — mutually exclusive at the data layer too. Pass `clear=true` to drop both and route direct.",
            inputSchema = objectSchema {
                string("profileId", "Profile id from list_connections.", required = true)
                string("tunnelConfigId", "Tunnel id to route through. Mutually exclusive with proxyType.")
                string("proxyType", "SOCKS5 | SOCKS4 | HTTP. Pair with proxyHost + proxyPort.")
                string("proxyHost", "Proxy host. Required when proxyType is set.")
                integer("proxyPort", "Proxy port. Default 1080 (SOCKS) / 8080 (HTTP).")
                string("proxyUser", "Proxy username, when the proxy requires authentication (#227). Optional. SOCKS4 sends userid only.")
                string("proxyPassword", "Proxy password for SOCKS5 (RFC 1929) / HTTP Basic auth (#227). Optional; ignored for SOCKS4.")
                boolean("clear", "If true, clear both tunnelConfigId and proxyType. Profile routes direct.")
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                val pid = args.optString("profileId")
                val target = when {
                    args.optBoolean("clear", false) -> "direct"
                    args.has("tunnelConfigId") -> "tunnel ${args.optString("tunnelConfigId").take(8)}…"
                    args.has("proxyType") -> "${args.optString("proxyType")} ${args.optString("proxyHost")}:${args.optInt("proxyPort")}"
                    else -> "(unchanged)"
                }
                "Route \"${profileLabel(pid)}\" through $target?"
            },
        ) { args -> setProfileRouting(args) },

        "create_connection" to ToolHandler(
            description = "Create a saved connection profile. Supports connectionType=SSH, SMB, VNC, RDP, SPICE, EMAIL, RETICULUM. SSH-family fields: username (required), password (optional, stored), keyId (optional — references list_ssh_keys), ignoreSavedKeys (force password-only auth, never offer saved keys), useMosh (turn an SSH profile into a Mosh profile), sessionManager (optional: TMUX | ZELLIJ | SCREEN | BYOBU | HERDR | PSMUX | RMUX — attach through that multiplexer; omit for a plain shell), remoteCommand (run a command via an SSH exec request instead of a login shell — e.g. 'tmux new -A -s work' to attach-or-create that session before shell startup files run) + requestPty (PTY for it, default true), bindAddress (local address the outgoing SSH socket binds to, ssh -b — direct connections only). SMB: smbShare (required), username + password, smbDomain. VNC: vncUsername, vncPassword, vncPort, and vncSshForward + vncSshProfileId to tunnel VNC through a saved SSH profile. RDP: rdpUsername (required), rdpPassword, rdpDomain, rdpPort. SPICE: spicePassword (optional ticket — no username/domain), spicePort (default 5900), and spiceSshForward + spiceSshProfileId to tunnel SPICE through a saved SSH profile. GLKVM (PiKVM-style KVM over HTTPS — kvmd API): username = the web-UI user (default admin when omitted), glkvmPassword = the web-UI password, glkvmPort (default 443), glkvmTargetLayout (\"uk\"/\"us\" — target OS keyboard layout for typed text, default uk), and glkvmSshForward + glkvmSshProfileId to tunnel through a saved SSH profile. TLS to the device is trust-on-first-use (self-signed by default). EMAIL: emailProvider (\"imap\" default, or \"proton\"); username = the email address; password = the account/app-password; for IMAP set emailServer (required) + emailPort (993) + emailSmtpPort (465) + emailTls (true), plus emailSmtpServer when the SMTP host differs (e.g. smtp.gmail.com); for Proton add emailMailboxPassword if two-password mode. EMAIL host is optional (the tunnel-ingress/bastion SPA/knock guards), not the mail server. OPENAI (OpenAI-compatible endpoint, e.g. llama-server or CLIProxyAPI): host = server IP/hostname (a full http:// URL also works), port = TCP port (default 80), optional password arg = the API key (sent as a Bearer token; omit for keyless servers), openaiPathPrefix = optional path inserted before /v1 (e.g. \"/api\"). Connect verifies via GET /v1/models; chat via the chat screen or openai_chat. BTSERIAL (Bluetooth-serial console, #406): host = the paired device's Bluetooth MAC (from list_bluetooth_devices); no other fields. The device must already be paired in Android Settings. BLESERIAL (Bluetooth-LE-serial console — Nordic UART Service / HM-10): host = the BLE peripheral's MAC; no other fields. It needn't be paired — scan-and-pick in the editor; the GATT service/characteristics are auto-detected (NUS 6E400001…, then HM-10 FFE0/FFE1). USBSERIAL (USB-serial console, #408 — Arduino / Duet3D G-code / ESP32 / USB-TTL): host = the device's vendorId:productId hex, e.g. 1a86:7523, from list_usb_devices; usbBaudRate = baud (default 115200); usbDataBits/usbParity/usbStopBits/usbFlowControl set the rest of the line format (default 8N1, no flow control). Plug the adapter in first; connect_profile pops the Android USB-permission prompt. Chipsets: CDC-ACM, CH34x, FTDI, CP21xx, Prolific. RETICULUM: destinationHash (required, 32 hex chars) is the address; reticulumHost + reticulumPort are only how this phone reaches the mesh, defaulting to 127.0.0.1:37428 which is a Sideband or Columba shared instance on this device — any other host is a TCP gateway. reticulumNetworkName + reticulumPassphrase set IFAC on an authenticated gateway. The new profile id is returned for follow-up calls (set_profile_routing, connect_profile). For rclone / local create the profile in the UI — those need an OAuth flow the agent can't drive.",
            inputSchema = objectSchema {
                string("label", "User-facing label.", required = true)
                string("connectionType", "SSH | SMB | VNC | RDP | SPICE | GLKVM | EMAIL | BTSERIAL | BLESERIAL | USBSERIAL | RETICULUM | GUEST.", required = true)
                string("host", "Target hostname or IP. For EMAIL this is the optional tunnel ingress/bastion (SPA/knock target), NOT the mail server — leave blank for a direct IMAP connection.", required = true)
                integer("port", "TCP port. Defaults: SSH 22, SMB 445, VNC 5900, RDP 3389, SPICE 5900, GLKVM 443. Type-specific vncPort/rdpPort/spicePort/glkvmPort override this.")
                string("username", "Username for SSH/SMB.")
                string("password", "Password (stored). Optional for SSH if a key is used; some VNC/SMB setups allow guest.")
                string("smbShare", "Share name (SMB). Required when connectionType=SMB.")
                string("smbDomain", "AD/workgroup domain (SMB). Optional.")
                string("vncUsername", "Username for VeNCrypt VNC.")
                string("vncPassword", "VNC password.")
                integer("vncPort", "VNC only: TCP port (default 5900). Overrides the generic `port`.")
                boolean("vncSshForward", "VNC only: tunnel the VNC connection through a saved SSH profile (set vncSshProfileId). The VNC target is reached at 127.0.0.1:<port> from the SSH server. Default false.")
                string("vncSshProfileId", "VNC only: id of the SSH profile (from list_connections) to tunnel through when vncSshForward is true.")
                string("rdpUsername", "Windows username (RDP). Required when connectionType=RDP.")
                string("rdpPassword", "Windows password (RDP).")
                string("rdpDomain", "AD domain (RDP). Optional.")
                integer("rdpPort", "RDP only: TCP port (default 3389). Overrides the generic `port`.")
                string("spicePassword", "SPICE only: ticket/password (stored). Optional — omit for an unticketed server.")
                integer("spicePort", "SPICE only: TCP port (default 5900). Overrides the generic `port`.")
                boolean("spiceSshForward", "SPICE only: tunnel through a saved SSH profile (set spiceSshProfileId). The SPICE target is reached at 127.0.0.1:<port> from the SSH server. Default false.")
                string("spiceSshProfileId", "SPICE only: id of the SSH profile (from list_connections) to tunnel through when spiceSshForward is true.")
                string("glkvmPassword", "GLKVM only: the device's web-UI password (stored encrypted).")
                integer("glkvmPort", "GLKVM only: HTTPS port (default 443). Overrides the generic `port`.")
                string("glkvmTargetLayout", "GLKVM only: target OS keyboard layout for typed text — \"uk\" (default) or \"us\".")
                boolean("glkvmSshForward", "GLKVM only: tunnel through a saved SSH profile (set glkvmSshProfileId). The GLKVM device is reached at 127.0.0.1:<port> from the SSH server. Default false.")
                string("glkvmSshProfileId", "GLKVM only: id of the SSH profile (from list_connections) to tunnel through when glkvmSshForward is true.")
                string("emailProvider", "EMAIL only: \"imap\" (generic IMAP/SMTP, default) or \"proton\".")
                string("emailServer", "EMAIL/imap only: IMAP server hostname (required for imap). Reached through the tunnel when one is set.")
                string("emailSmtpServer", "EMAIL/imap only: SMTP submission host, when it differs from the IMAP host (e.g. smtp.gmail.com vs imap.gmail.com). Optional — defaults to emailServer.")
                integer("emailPort", "EMAIL/imap only: IMAP port. Default 993.")
                integer("emailSmtpPort", "EMAIL/imap only: SMTP port. Default 465.")
                boolean("emailTls", "EMAIL/imap only: implicit TLS (SSL). Default true.")
                string("emailMailboxPassword", "EMAIL/proton only: separate mailbox password for two-password-mode accounts.")
                string("openaiPathPrefix", "OPENAI only: optional path prefix inserted before /v1 (e.g. \"/api\" for CLIProxyAPI).")
                string("protocol", "OPENAI only: wire protocol — OPENAI (default, OpenAI-compatible /v1/chat/completions), OLLAMA (native /api), ANTHROPIC (Messages API /v1/messages), or GEMINI (generativelanguage /v1beta/models/{model}:generateContent).")
                string("tunnelConfigId", "Optional: route the new profile through this tunnel (from list_tunnels). Equivalent to follow-up set_profile_routing.")
                boolean("tunnelOnly", "SSH only: tunnel-only mode (#150). When true, the profile brings up the SSH transport and registers port forwards but does not open a terminal. Default false. Pair with auto_reconnect for autossh-style keepalive.")
                boolean("useMosh", "SSH only: when true, the profile uses Mosh on top of the SSH bootstrap. SSH execs `mosh-server new -s`, parses MOSH CONNECT, then the UDP transport takes over. Default false.")
                string("keyId", "SSH only: id of a saved SSH key (from list_ssh_keys) to authenticate with. Mutually optional with password.")
                string("sshOptions", "SSH only: ssh_config-style option lines ('Key value' or 'Key=value', newline-separated) applied to this profile — e.g. 'ServerAliveInterval 60' or the Haven-internal 'HavenSshEngine sshlib' engine toggle (#58).")
                boolean("forwardX11", "SSH only (#605): one-tap X11 forwarding toggle. Tunnels remote 127.0.0.1:6010 to the phone's X11 display, auto-starts an installed X11 desktop if needed, and exports DISPLAY=localhost:10.0 in the remote shell.")
                boolean("forwardPulseAudio", "SSH only (#605): one-tap PulseAudio forwarding toggle. Starts the phone's PulseAudio bridge, tunnels remote 127.0.0.1:14713 to local port 4713, and exports PULSE_SERVER=tcp:127.0.0.1:14713 in the remote shell.")
                string("remoteCommand", "SSH only (#436): run this command via an SSH exec request instead of opening a login shell — it executes before shell startup files, so e.g. 'tmux new -A -s work' attaches to/creates that named session without racing a .bashrc auto-tmux hook. Omit for the normal interactive shell.")
                string("bindAddress", "SSH only (#636): local address the outgoing SSH socket binds to (ssh -b) — pin a multi-homed host to one interface. Direct connections only; a connect from a profile with a proxy or jump host is refused. Omit to let the system choose the source address.")
                boolean("requestPty", "SSH only (#436): allocate a PTY for remoteCommand. Default true (tmux and other interactive programs need one); ignored when remoteCommand is omitted.")
                boolean("ignoreSavedKeys", "SSH-family only: when true, authenticate with password (and keyboard-interactive) only — saved keystore keys are never offered to the server. Lets a profile target a password-only server without the auto-key-offer suppressing the password prompt (#121). Default false.")
                stringArray("authMethods", "SSH only (#166): ordered multi-factor auth methods attempted in one connect, for servers requiring a chain like publickey,password. Each element is a token: \"PASSWORD\", \"KEY\" (any saved key), \"KEY:<keyId>\", \"KEYBOARD_INTERACTIVE\", or \"TOTP:<id>\" (auto-fill an OATH-TOTP code from list_totp_secrets, #178). Omit for the single-method default derived from keyId/password.")
                string("portKnockSequence", "Optional port-knock sequence fired before the real connect. Format: whitespace/comma-separated 'port[/proto]' tokens — e.g. '7000 8000 9000' (all TCP) or '7000/tcp 8000/udp 9000/tcp'. Empty = disabled.")
                integer("portKnockDelayMs", "Inter-knock delay in ms (default 100). Ignored when portKnockSequence is empty.")
                integer("usbBaudRate", "USBSERIAL only: serial baud rate (default 115200). Stored in `port`.")
                integer("usbDataBits", "USBSERIAL only: data bits 5-8 (default 8).")
                string("usbParity", "USBSERIAL only: parity N|O|E|M|S (none/odd/even/mark/space, default N).")
                string("usbStopBits", "USBSERIAL only: stop bits 1|1.5|2 (default 1).")
                string("usbFlowControl", "USBSERIAL only: flow control none|rtscts|xonxoff (default none).")
                string("destinationHash", "RETICULUM only: the rnsh destination hash to address, 32 hex characters. Required for RETICULUM. Discoverable by scanning in the UI, or take it from the server's `rnsh -l` output.")
                string("reticulumHost", "RETICULUM only: how this phone reaches the mesh. Default 127.0.0.1, meaning a Sideband or Columba shared instance on this device; any other host is a TCP gateway.")
                integer("reticulumPort", "RETICULUM only: port for reticulumHost. Default 37428, the shared-instance port. A TCP gateway is usually 4242.")
                string("reticulumNetworkName", "RETICULUM only: IFAC network name, when the gateway is authenticated. Optional.")
                string("reticulumPassphrase", "RETICULUM only: IFAC passphrase that goes with reticulumNetworkName. Optional, stored.")
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val type = args.optString("connectionType")
                val label = args.optString("label", "(unnamed)")
                // GUEST has no host field (rootfs is staged locally, kernel
                // args fixed) — an empty host here would render as a blank
                // target in the consent sheet.
                val host = if (type == "GUEST") "on-device UML guest"
                    else args.optString("host", "?")
                val tunnelTag = if (args.optBoolean("tunnelOnly", false)) " [tunnel-only]" else ""
                val knockTag = args.optString("portKnockSequence").let {
                    if (it.isNotBlank()) " [knock: $it]" else ""
                }
                "Create $type profile \"$label\" → $host$tunnelTag$knockTag?"
            },
        ) { args -> createConnection(args) },

        "update_connection" to ToolHandler(
            description = "Edit fields on an existing connection profile (load → change → save). Pass profileId (required) plus only the fields you want to change — anything omitted is left as-is. Common SSH-family fields: label, host, port, username, password (stored, mapped to the profile's transport), keyId, ignoreSavedKeys (force password-only auth), useMosh, forwardAgent, remoteCommand (SSH exec instead of a login shell; empty string clears) + requestPty, bindAddress (ssh -b; direct connections only, empty string clears). Desktop tunnels: vncSshForward + vncSshProfileId, rdpSshForward + rdpSshProfileId, spiceSshForward + spiceSshProfileId, smbSshForward + smbSshProfileId, glkvmSshForward + glkvmSshProfileId. USB/IP auto-forward: usbForwardVidPid (export a phone-attached USB device to this host on every connect). Passwords are stored encrypted and never echoed back. OPENAI: password maps to the API key (empty string clears). For routing/proxy use set_profile_routing; for port-knock/SPA use set_port_knock/set_spa. Returns the updated profile (secrets redacted).",
            inputSchema = objectSchema {
                string("profileId", "Profile id from list_connections.", required = true)
                string("label", "New user-facing label.")
                string("host", "New hostname or IP.")
                integer("port", "New TCP port.")
                string("username", "New username (SSH/SMB).")
                string("password", "New password (stored encrypted). Mapped to the profile's transport (SSH/VNC/RDP/SMB). Pass an empty string to clear it.")
                string("keyId", "SSH only: id of a saved key (list_ssh_keys). Empty string clears.")
                string("sshOptions", "SSH only: replace the profile's ssh_config-style option lines (e.g. 'HavenSshEngine sshlib' opts this profile into the EXPERIMENTAL sshlib engine for the whole connection — terminal, exec, SFTP and tunnels; it refuses jump/proxy, FIDO2, OpenSSH certs and MFA chains). Empty string clears. Ignored on non-SSH profiles (USB-serial packs its line format here).")
                string("remoteCommand", "SSH only (#436): run this command via an SSH exec request instead of a login shell (e.g. 'tmux new -A -s work'). Empty string clears (back to the normal shell).")
                string("bindAddress", "SSH only (#636): local address the outgoing SSH socket binds to (ssh -b). Direct connections only — refused on profiles with a proxy or jump host. Empty string clears.")
                boolean("requestPty", "SSH only (#436): allocate a PTY for remoteCommand (tmux needs one). Ignored when remoteCommand is empty.")
                string("jumpProfileId", "SSH only: id of the SSH profile to jump through (ssh -J). The target host is dialled from the jump host, so it may be an address only the jump can reach. Empty string clears.")
                boolean("ignoreSavedKeys", "SSH-family only: force password-only auth, never offer saved keystore keys (#121).")
                boolean("useMosh", "SSH only: use Mosh on top of the SSH bootstrap.")
                boolean("forwardAgent", "SSH only: enable SSH agent forwarding. Keys with a stored passphrase (or none) are exposed to the remote's ssh-agent socket (#377).")
                boolean("vncSshForward", "VNC only: tunnel through a saved SSH profile (set vncSshProfileId).")
                string("vncSshProfileId", "VNC only: SSH profile id to tunnel through. Empty string clears.")
                string("openaiPathPrefix", "OPENAI only: path prefix inserted before /v1 (e.g. \"/api\"). Empty string clears.")
                string("protocol", "OPENAI only: wire protocol — OPENAI (default), OLLAMA, ANTHROPIC, or GEMINI. Empty string clears (back to OPENAI).")
                boolean("rdpSshForward", "RDP only: tunnel through a saved SSH profile (set rdpSshProfileId).")
                string("rdpSshProfileId", "RDP only: SSH profile id to tunnel through. Empty string clears.")
                boolean("smbSshForward", "SMB only: tunnel through a saved SSH profile (set smbSshProfileId).")
                string("smbSshProfileId", "SMB only: SSH profile id to tunnel through. Empty string clears.")
                boolean("spiceSshForward", "SPICE only: tunnel through a saved SSH profile (set spiceSshProfileId).")
                string("spiceSshProfileId", "SPICE only: SSH profile id to tunnel through. Empty string clears.")
                string("glkvmPassword", "GLKVM only: web-UI password (stored encrypted). Empty string clears.")
                integer("glkvmPort", "GLKVM only: HTTPS port (default 443).")
                string("glkvmTargetLayout", "GLKVM only: target OS keyboard layout, \"uk\" or \"us\". Empty string clears.")
                boolean("glkvmSshForward", "GLKVM only: tunnel through a saved SSH profile (set glkvmSshProfileId).")
                string("glkvmSshProfileId", "GLKVM only: SSH profile id to tunnel through. Empty string clears.")
                string("moshServerCommand", "Mosh only: override the command Haven runs over SSH to start mosh-server (default 'mosh-server new -s -c 256 …'). It must print a 'MOSH CONNECT <port> <key>' line, which Haven parses to find the session — so a wrapper can point Haven at a different port (e.g. scripts/mosh-fault-rig.py bootstrap, which puts a fault-injecting relay in front). Empty string restores the default.")
                string("usbForwardVidPid", "SSH only: VID:PID of a phone-attached USB device (e.g. '1050:0406' — see list_usb_devices) to auto-export over USB/IP whenever this profile connects. Haven opens the device, starts the usbip server on loopback, adds the remote forward, and runs `usbip attach` on the host, re-attaching after a tunnel drop. Empty string clears (no auto-forward).")
                boolean("forwardX11", "SSH only (#605): enable or disable one-tap X11 forwarding (DISPLAY=localhost:10.0 over -R 6010).")
                boolean("forwardPulseAudio", "SSH only (#605): enable or disable one-tap PulseAudio forwarding (PULSE_SERVER=tcp:127.0.0.1:14713 over -R 14713:127.0.0.1:4713).")
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val pid = args.optString("profileId")
                val changed = args.keys().asSequence()
                    .filter { it != "profileId" }
                    .toList()
                val fields = if (changed.isEmpty()) "(no changes)" else changed.joinToString(", ")
                "Update \"${profileLabel(pid)}\": $fields?"
            },
        ) { args -> updateConnection(args) },

        "set_port_knock" to ToolHandler(
            description = "Update the port-knock fields on an existing profile. Pass portKnockSequence='' (empty) to disable knocking. Format: 'port[/proto]' tokens — e.g. '7000 8000 9000' or '7000/tcp 8000/udp'. Returns the updated profile summary.",
            inputSchema = objectSchema {
                string("profileId", "Profile id from list_connections.", required = true)
                string("portKnockSequence", "Sequence string ('' to disable).", required = true)
                integer("portKnockDelayMs", "Inter-knock delay in ms. Optional; default 100 when sequence becomes non-empty.")
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val seq = args.optString("portKnockSequence")
                val target = profileLabel(args.optString("profileId"))
                if (seq.isBlank()) "Disable port knock on \"$target\"?"
                else "Set port knock on \"$target\" to: $seq?"
            },
        ) { args -> setPortKnock(args) },

        "test_port_knock" to ToolHandler(
            description = "Send a port-knock sequence to a host without committing or connecting anything. Bypasses the saved profile state — pass host + sequence directly. Returns ok/sent/durationMs/error so an agent can verify a knockd config end-to-end without opening a real session.",
            inputSchema = objectSchema {
                string("host", "Hostname or IP literal to knock against.", required = true)
                string("portKnockSequence", "Sequence string — e.g. '7000 8000 9000' or '7000/tcp 8000/udp'.", required = true)
                integer("portKnockDelayMs", "Inter-knock delay in ms (default 100).")
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                "Send test knock '${args.optString("portKnockSequence")}' to ${args.optString("host")}?"
            },
        ) { args -> testPortKnock(args) },

        "set_spa" to ToolHandler(
            description = "Configure fwknop Single Packet Authorization (SPA) on an existing profile — the cryptographic alternative to port knocking. Pass spaKey='' to disable. spaAccessSpec is the port(s) to open, e.g. 'tcp/22' or 'tcp/22,udp/53'. allowMode is SOURCE (default; fwknopd opens for the packet's source IP), RESOLVE (resolve public IP), or EXPLICIT (use explicitIp). Returns the updated profile summary; key material is never echoed back.",
            inputSchema = objectSchema {
                string("profileId", "Profile id from list_connections.", required = true)
                string("spaKey", "Rijndael/AES key ('' to disable SPA).", required = true)
                boolean("spaKeyBase64", "True if spaKey is base64 (fwknop --key-base64-rijndael).")
                string("spaHmacKey", "Optional HMAC-SHA256 key. '' = no HMAC.")
                boolean("spaHmacKeyBase64", "True if spaHmacKey is base64 (fwknop --key-base64-hmac).")
                string("spaAccessSpec", "proto/port token(s), e.g. 'tcp/22'.")
                string("allowMode", "SOURCE | RESOLVE | EXPLICIT. Default SOURCE.")
                string("explicitIp", "Allow-IP/CIDR for EXPLICIT mode.")
                integer("spaPort", "Destination UDP port (default 62201).")
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val target = profileLabel(args.optString("profileId"))
                if (args.optString("spaKey").isBlank()) "Disable SPA on \"$target\"?"
                else "Set SPA (${args.optString("spaAccessSpec").ifBlank { "tcp/?" }}) on \"$target\"?"
            },
        ) { args -> setSpa(args) },

        "test_spa" to ToolHandler(
            description = "Build and send one fwknop SPA packet to a host without committing a profile or connecting. Pass the key/access directly. Returns ok/bytesSent/spaPort/error so an agent can verify a fwknopd config end-to-end. Key material is never echoed back.",
            inputSchema = objectSchema {
                string("host", "Hostname or IP literal to send the SPA packet to.", required = true)
                string("spaKey", "Rijndael/AES key.", required = true)
                boolean("spaKeyBase64", "True if spaKey is base64.")
                string("spaHmacKey", "Optional HMAC-SHA256 key.")
                boolean("spaHmacKeyBase64", "True if spaHmacKey is base64.")
                string("spaAccessSpec", "proto/port token(s), e.g. 'tcp/22'.", required = true)
                string("allowMode", "SOURCE | RESOLVE | EXPLICIT. Default SOURCE.")
                string("explicitIp", "Allow-IP/CIDR for EXPLICIT mode.")
                integer("spaPort", "Destination UDP port (default 62201).")
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                "Send test SPA (${args.optString("spaAccessSpec")}) to ${args.optString("host")}?"
            },
        ) { args -> testSpa(args) },

        "get_connection_log" to ToolHandler(
            description = "Read the most recent ConnectionLog entries for a profile. Use this to verify post-hoc what happened during a connect — including knock results (look for '[knock]' lines in verboseLog), TLS handshakes, and authentication. limit defaults to 10.",
            inputSchema = objectSchema {
                string("profileId", "Profile id from list_connections.", required = true)
                integer("limit", "Max entries to return (default 10, max 100).")
            },
        ) { args -> getConnectionLog(args) },

        "delete_connection" to ToolHandler(
            description = "Delete a saved connection profile by id. Disconnects any live session for the profile first. Use this after integration tests to clean up agent-created profiles.",
            inputSchema = objectSchema {
                string("profileId", "Profile id from list_connections.", required = true)
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args -> "Delete profile \"${profileLabel(args.optString("profileId"))}\"?" },
        ) { args -> deleteConnection(args) },

        "connect_profile" to ToolHandler(
            description = "Initiate a connection for a saved profile via the same code path a UI tap uses (route-through, stored password, key auth all apply). Posts an AgentUiCommand.ConnectProfile that ConnectionsViewModel observes — the actual connect happens asynchronously. Use list_sessions afterwards to confirm the session reached CONNECTED. If the profile needs a password that isn't stored and isn't a key, the UI password prompt will surface to the user. For an SSH profile that uses a session manager (tmux/zellij/screen), pass sessionName to attach or create a named session non-interactively; without it, a connect to a profile that has existing sessions surfaces the interactive picker — poll get_pending_session_picker and resolve it with answer_session_picker (no longer stalls unanswerable).",
            inputSchema = objectSchema {
                string("profileId", "Profile id from list_connections.", required = true)
                string("sessionName", "Optional. SSH session-manager (tmux/zellij/screen) session to attach or create — attaches if it already exists, creates it otherwise. Skips the interactive session picker. Ignored for transports/profiles without a session manager.")
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args -> "Connect to \"${profileLabel(args.optString("profileId"))}\"?" },
        ) { args -> connectProfile(args) },

        "run_command" to ToolHandler(
            description = "Run one command on a saved SSH connection over an exec channel (no terminal session, no PTY) and return { exitCode, stdout, stderr } — the automation-shaped verb (#367): a MacroDroid/Tasker HTTP Request macro or cron-style agent POSTs a single tools/call and reads the output straight from the response. Reuses the live connection when the profile is connected (also the only path for FIDO2/encrypted-key profiles); otherwise makes a one-shot headless connect, which needs a stored password or an unencrypted non-FIDO2 key AND a host key already trusted from a previous interactive connect (fail-closed TOFU — unknown/changed keys are refused, never silently accepted). Blocks until the command exits or timeoutMs elapses (then returns partial output with timedOut:true, exitCode:null). For unattended macros, create_standing_policy scoped to this tool + {\"profileId\": …} removes the per-call consent prompt. Headless-path limits: port-knock/SPA-guarded and tunnel-routed profiles aren't knocked/routed — connect those interactively first and let the reuse path serve them.",
            inputSchema = objectSchema {
                string("profileId", "Saved SSH profile id (from list_connections).", required = true)
                string("command", "Command line passed to the remote user's shell via SSH exec, e.g. 'uptime' or 'systemctl status nginx'.", required = true)
                integer("timeoutMs", "Abort after this many ms, returning partial output with timedOut:true. Default 30000, range 1000–300000.")
                integer("maxOutputChars", "Cap stdout and stderr to their last N chars each. Default 8000.")
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                "Run on \"${profileLabel(args.optString("profileId"))}\": ${args.optString("command").take(120)}"
            },
        ) { args -> runCommand(args) },

        "get_pending_auth_prompt" to ToolHandler(
            description = "Return the password / key-passphrase prompt Haven is currently waiting on for a stalled connect (its in-app fallback dialog), or { pending: false } if none. A connect started via connect_profile that needs a secret which isn't stored (a wrong/missing host password, or an assigned encrypted SSH key whose passphrase failed, #292) surfaces here instead of failing silently: { pending: true, profileId, label, requiresPassphrase } — requiresPassphrase=true means it wants the encrypted key's passphrase, false means a host/account password. Answer it with answer_auth_prompt. Read-only.",
            inputSchema = emptyObjectSchema(),
        ) { _ -> getPendingAuthPrompt() },

        "answer_auth_prompt" to ToolHandler(
            description = "Answer the prompt reported by get_pending_auth_prompt — supply the secret a user would type into Haven's fallback dialog and Haven re-drives the connect through the same path a UI tap uses. Pass `password` (the host password or the encrypted key's passphrase). Optional `username` overrides the login user; `rememberPassword` stores it on the profile. Returns { answered:false } when no prompt is pending. A wrong value just re-surfaces the prompt (poll get_pending_auth_prompt; it clears on success) — confirm the result with list_sessions.",
            inputSchema = objectSchema {
                string("password", "The host/account password or the encrypted key's passphrase to unlock the stalled connect.", required = true)
                string("username", "Optional. Override the login username for this attempt.")
                boolean("rememberPassword", "Optional. Persist the password on the profile (default false).")
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { _ -> "Answer Haven's pending connection password prompt?" },
        ) { args -> answerAuthPrompt(args) },

        "get_pending_session_picker" to ToolHandler(
            description = "Return the session-manager picker Haven is currently waiting on, or { pending: false } if none. A connect_profile to a tmux/zellij/screen profile that has existing remote sessions (and no sessionName preselected) surfaces this picker instead of attaching: { pending: true, profileId, sessionId, sessionManager, sessionNames: [...], previousSessionNames: [...], suggestedNewName }. Answer it with answer_session_picker. Read-only. (Previously this picker was human-only and stalled the agent.)",
            inputSchema = emptyObjectSchema(),
            consentLevel = ConsentLevel.NEVER,
        ) { _ -> getPendingSessionPicker() },

        "answer_session_picker" to ToolHandler(
            description = "Answer the picker reported by get_pending_session_picker — attach to an existing remote session by name, or create a new one. Pass `sessionName` (must be one of the picker's sessionNames) to attach to it, or `createNew: true` to start a fresh session. Haven re-drives the attach through the same path a human tap uses (onSessionSelected). Returns { answered:false } when no picker is pending. Confirm the result with list_sessions / read_terminal_snapshot.",
            inputSchema = objectSchema {
                string("sessionName", "Existing remote session to attach to (from get_pending_session_picker.sessionNames). Omit and set createNew=true to start a new one.")
                boolean("createNew", "Create a new session instead of attaching to an existing one (default false).")
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                if (args.optBoolean("createNew", false)) "Create a new remote session?"
                else "Attach to remote session \"${args.optString("sessionName")}\"?"
            },
        ) { args -> answerSessionPicker(args) },
    )

    private suspend fun listConnections(): JSONObject {
        val profiles = connectionRepository.getAll()
        val arr = JSONArray()
        for (p in profiles) arr.put(profileToJson(p))
        return JSONObject().apply {
            put("count", profiles.size)
            put("connections", arr)
        }
    }

    private fun profileToJson(p: ConnectionProfile): JSONObject = JSONObject().apply {
        put("id", p.id)
        put("label", p.label)
        put("connectionType", p.connectionType)
        put("host", p.host)
        put("port", p.port)
        put("username", p.username)
        put("groupId", p.groupId ?: JSONObject.NULL)
        put("jumpProfileId", p.jumpProfileId ?: JSONObject.NULL)
        put("authType", p.authType.name)
        val storedPw = when (p.connectionType) {
            "VNC" -> p.vncPassword
            "RDP" -> p.rdpPassword
            "SPICE" -> p.spicePassword
            "GLKVM" -> p.glkvmPassword
            "SMB" -> p.smbPassword
            "EMAIL" -> p.emailPassword
            else -> p.sshPassword
        }
        put("hasStoredPassword", !storedPw.isNullOrEmpty())
        put("hasKey", p.keyId != null)
        if (p.ignoreSavedKeys) put("ignoreSavedKeys", true)
        if (p.forwardAgent) put("forwardAgent", true)
        if (p.authMethodSpecs.size > 1) {
            put("authMethods", org.json.JSONArray(p.authMethodSpecs.map { it.serialize() }))
        }
        put("lastConnected", p.lastConnected ?: JSONObject.NULL)
        if (p.useMosh) put("useMosh", true)
        if (!p.remoteCommand.isNullOrBlank()) {
            put("remoteCommand", p.remoteCommand)
            put("requestPty", p.requestPty)
        }
        if (p.useEternalTerminal) put("useEternalTerminal", true)
        if (!p.usbForwardVidPid.isNullOrBlank()) put("usbForwardVidPid", p.usbForwardVidPid)
        if (!p.moshServerCommand.isNullOrBlank()) put("moshServerCommand", p.moshServerCommand)
        // sshOptions was settable but never readable, so an agent could opt a
        // profile into the sshlib engine and then have no way to confirm which
        // engine it was actually on — the same write-only asymmetry
        // usbForwardVidPid had. Report the raw lines and the engine they
        // resolve to, since the engine is the actionable part (#58).
        if (!p.sshOptions.isNullOrBlank()) put("sshOptions", p.sshOptions)
        if (p.connectionType == "SSH") {
            put("sshEngine", sh.haven.core.ssh.sshEngineFromOptionsText(p.sshOptions).name.lowercase())
            put("forwardX11", sh.haven.core.ssh.sshForwardX11FromOptionsText(p.sshOptions))
            put("forwardPulseAudio", sh.haven.core.ssh.sshForwardPulseAudioFromOptionsText(p.sshOptions))
            if (!p.bindAddress.isNullOrBlank()) put("bindAddress", p.bindAddress)
        }
        // VNC-specific fields
        if (p.vncPort != null) put("vncPort", p.vncPort)
        if (!p.vncUsername.isNullOrEmpty()) put("vncUsername", p.vncUsername)
        // RDP
        if (!p.rdpUsername.isNullOrEmpty()) put("rdpUsername", p.rdpUsername)
        if (!p.rdpDomain.isNullOrEmpty()) put("rdpDomain", p.rdpDomain)
        // SMB
        if (!p.smbShare.isNullOrEmpty()) put("smbShare", p.smbShare)
        // OPENAI — baseUrl is the dial target (host[+port] + prefix); the key
        // itself is never echoed back, only its presence.
        if (p.isOpenai) {
            put("baseUrl", p.openaiBaseUrl)
            if (!p.openaiPathPrefix.isNullOrEmpty()) put("pathPrefix", p.openaiPathPrefix)
            put("hasApiKey", !p.openaiApiKey.isNullOrEmpty())
            put("protocol", p.aiProtocol?.takeIf { it.isNotBlank() } ?: "OPENAI")
        }
        // Reticulum — without these a profile the agent just created reads back
        // as an address-less RETICULUM row it cannot tell apart from any other.
        if (p.isReticulum) {
            put("destinationHash", p.destinationHash ?: JSONObject.NULL)
            put("reticulumHost", p.reticulumHost)
            put("reticulumPort", p.reticulumPort)
            if (!p.reticulumNetworkName.isNullOrEmpty()) {
                put("reticulumNetworkName", p.reticulumNetworkName)
            }
        }
        // Rclone
        if (!p.rcloneRemoteName.isNullOrEmpty()) put("rcloneRemote", p.rcloneRemoteName)
        if (!p.rcloneProvider.isNullOrEmpty()) put("rcloneProvider", p.rcloneProvider)
        // Proxy
        if (!p.proxyType.isNullOrEmpty()) {
            put("proxyType", p.proxyType)
            put("proxyHost", p.proxyHost ?: JSONObject.NULL)
            put("proxyPort", p.proxyPort)
        }
        // WireGuard / Tailscale routing — tunnel config id (resolve via list_tunnels).
        if (!p.tunnelConfigId.isNullOrEmpty()) {
            put("tunnelConfigId", p.tunnelConfigId)
        }
        // #150 — non-default reconnect / tunnel-only flags. Defaults
        // (autoReconnect=true, max=5, networkChange=true, tunnelOnly=false)
        // are omitted to keep the JSON terse for the common case.
        if (!p.autoReconnect) put("autoReconnect", false)
        if (p.reconnectMaxAttempts != 5) put("reconnectMaxAttempts", p.reconnectMaxAttempts)
        if (!p.reconnectOnNetworkChange) put("reconnectOnNetworkChange", false)
        if (p.tunnelOnly) put("tunnelOnly", true)
        // Per-profile session manager (tmux / zellij / screen / null = bare
        // shell). Surfaced so agents picking a clean-bash test target can
        // filter on it without first connecting.
        if (!p.sessionManager.isNullOrEmpty()) put("sessionManager", p.sessionManager)
        if (p.disableAltScreen) put("disableAltScreen", true)
        if (!p.portKnockSequence.isNullOrEmpty()) {
            put("portKnockSequence", p.portKnockSequence)
            put("portKnockDelayMs", p.portKnockDelayMs)
        }
        // SPA presence only — never surface the key/HMAC-key material.
        if (!p.spaKey.isNullOrEmpty() && !p.spaAccessSpec.isNullOrEmpty()) {
            put("spaEnabled", true)
            put("spaAccessSpec", p.spaAccessSpec)
            put("spaAllowMode", p.spaAllowMode)
            put("spaPort", p.spaPort)
        }
    }

    @android.annotation.SuppressLint("MissingPermission") // permission checked before reading bondedDevices
    private fun listBluetoothDevices(): JSONObject {
        val needsPerm = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
        if (needsPerm && androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.BLUETOOTH_CONNECT,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            throw IllegalStateException(
                "BLUETOOTH_CONNECT not granted — open a Bluetooth-serial connection in the app once to grant it, then retry.",
            )
        }
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE)
            as? android.bluetooth.BluetoothManager)?.adapter
            ?: throw IllegalStateException("This device has no Bluetooth adapter")
        val devices = JSONArray()
        adapter.bondedDevices.orEmpty().forEach { d ->
            devices.put(JSONObject().put("address", d.address).put("name", d.name ?: d.address))
        }
        return JSONObject().put("devices", devices).put("count", devices.length())
    }

    private fun listSessions(): JSONObject {
        val sessions = sessionManagerRegistry.allSessions
        val sshStates = sshSessionManager.sessions.value
        val arr = JSONArray()
        for (s in sessions) {
            arr.put(JSONObject().apply {
                put("sessionId", s.sessionId)
                put("profileId", s.profileId)
                put("label", s.label)
                put("status", s.status.name)
                put("transport", s.transport.name)
                // Heuristic (#226): does this session's screen look like an
                // agent REPL (Claude Code TUI chrome in the bottom lines)?
                // Lets a caller pick the conversation peer without the wrong
                // "the 8730 tunnel session == the REPL" guess. Null when the
                // session has no attached terminal tab to inspect.
                val termEntry = terminalSessionRegistry.get(s.sessionId)
                put(
                    "isAgentRepl",
                    termEntry?.let {
                        runCatching {
                            looksLikeAgentRepl(it.emulator.buildAgentSnapshot().lines.map { l -> l.text })
                        }.getOrNull()
                    } ?: JSONObject.NULL,
                )
                if (s.transport == sh.haven.core.ssh.Transport.SSH) {
                    val sshState = sshStates[s.sessionId]
                    if (sshState != null) {
                        put("sessionManager", sshState.sessionManager.name)
                        put("chosenSessionName", sshState.chosenSessionName ?: JSONObject.NULL)
                        put("hasShell", sshState.shellChannel != null)
                        put("hasSftp", sshState.sftpSession != null)
                        put("connectedViaProxy", sshState.client.connectedViaProxy)
                        put("jumpSessionId", sshState.jumpSessionId ?: JSONObject.NULL)
                        put("activeForwards", JSONArray().apply {
                            for (f in sshState.activeForwards) {
                                put(JSONObject().apply {
                                    put("ruleId", f.ruleId)
                                    put("type", f.type.name)
                                    put("bindAddress", f.bindAddress)
                                    put("bindPort", f.bindPort)
                                    put("actualBoundPort", f.actualBoundPort)
                                    put("targetHost", f.targetHost)
                                    put("targetPort", f.targetPort)
                                })
                            }
                        })
                    }
                }
            })
        }
        return JSONObject().apply {
            put("count", sessions.size)
            put("sessions", arr)
        }
    }

    private suspend fun disconnectProfile(args: JSONObject): JSONObject {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        // AI route teardown FIRST, matching ConnectionsViewModel.disconnect's
        // order: release the routes this profile owns (it's the endpoint) and
        // cascade the ones it carries (it's the carrier) while the carrier's
        // SSH client is still alive to remove the LOCAL forward from. Coming
        // after the registry disconnect would leave the forward LISTENing on
        // a session that survives (#observed on the routed-chat verification).
        aiRouteRegistry.teardownFor(profileId)
        // Cross-transport hammer; the registry already knows which
        // transports have sessions for this profile and only acts where
        // there's something to do, so it's safe to call unconditionally.
        sessionManagerRegistry.disconnectProfile(profileId)
        // Match ConnectionsViewModel.disconnect — close the profile's VNC/RDP
        // Desktop tab (via the lease) and release any auto-opened SSH-tunnel
        // dependent (#121) plus the WG / Tailscale refcount (#149). Bypassing
        // these from the MCP path was the original gap — without them, a
        // profile disconnected through the agent path left its tunnel handle
        // live, and live_tunnels surfaced the bogus "still depending" state.
        sshSessionManager.teardownTunnelDependent(profileId)
        tunnelManager.release(profileId)
        // Direct (non-tunnelled) VNC/RDP/SPICE tabs have no lease to cascade
        // through — close them via the tab's registered close handle, so the
        // tab (and its status in list_desktop_sessions) actually goes away (#437).
        desktopSessionRegistry.closeHandle(profileId)?.invoke()
        return JSONObject().apply {
            put("profileId", profileId)
            put("disconnected", true)
        }
    }

    private suspend fun addPortForward(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val typeStr = args.optString("type").ifEmpty {
            throw McpError(-32602, "Missing required argument: type")
        }
        val type = try {
            PortForwardRule.Type.valueOf(typeStr.uppercase())
        } catch (e: IllegalArgumentException) {
            throw McpError(-32602, "Invalid type '$typeStr' — expected LOCAL, REMOTE, or DYNAMIC")
        }
        val bindPort = args.optInt("bindPort", -1).takeIf { it >= 0 }
            ?: throw McpError(-32602, "Missing required argument: bindPort")
        val rule = PortForwardRule(
            profileId = profileId,
            type = type,
            bindAddress = args.optString("bindAddress", "127.0.0.1"),
            bindPort = bindPort,
            targetHost = args.optString("targetHost", "localhost"),
            targetPort = args.optInt("targetPort", 0),
            enabled = true,
        )
        portForwardRepository.save(rule)

        // Reticulum profiles tunnel over the rnsh exec substrate (nc), not
        // an SSH session — dispatch to the forward server instead.
        if (reticulumSessionManager.isProfileConnected(profileId)) {
            val destHash = reticulumSessionManager.getSessionsForProfile(profileId)
                .firstOrNull { it.status == sh.haven.core.reticulum.ReticulumSessionManager.SessionState.Status.CONNECTED }
                ?.destinationHash
            if (destHash != null) {
                val bound = when (rule.type) {
                    PortForwardRule.Type.LOCAL -> reticulumForwardServer.startLocalForward(
                        profileId, destHash, rule.bindAddress, rule.bindPort, rule.targetHost, rule.targetPort)
                    PortForwardRule.Type.DYNAMIC -> reticulumForwardServer.startDynamicForward(
                        profileId, destHash, rule.bindAddress, rule.bindPort)
                    PortForwardRule.Type.REMOTE -> throw McpError(-32602,
                        "Remote (-R) forwarding is not supported over Reticulum yet")
                }
                return@withContext JSONObject().apply {
                    put("ruleId", rule.id)
                    put("activated", true)
                    put("actualBoundPort", bound)
                }
            }
        }

        // Activate immediately if the profile has a connected session.
        // Mirrors the UI's savePortForwardRule path. Multiple connected
        // sessions per profile are unusual but possible (multi-tab); we
        // pick the first connected one rather than fanning out.
        val session = sshSessionManager.getSessionsForProfile(profileId)
            .firstOrNull { it.status == SshSessionManager.SessionState.Status.CONNECTED }
        var actualBoundPort: Int? = null
        var activatedLive = false
        if (session != null) {
            val info = SshSessionManager.PortForwardInfo(
                ruleId = rule.id,
                type = when (rule.type) {
                    PortForwardRule.Type.LOCAL -> SshSessionManager.PortForwardType.LOCAL
                    PortForwardRule.Type.REMOTE -> SshSessionManager.PortForwardType.REMOTE
                    PortForwardRule.Type.DYNAMIC -> SshSessionManager.PortForwardType.DYNAMIC
                },
                bindAddress = rule.bindAddress,
                bindPort = rule.bindPort,
                targetHost = rule.targetHost,
                targetPort = rule.targetPort,
            )
            sshSessionManager.applyPortForwards(session.sessionId, listOf(info))
            // Trust only the live session's activeForwards, not the apply
            // call having run: a failed bind is swallowed for non-critical
            // rules, and reporting activated:true for a rule that never
            // bound sent agents into dead-tunnel debugging (found live —
            // TIME_WAIT from a just-closed download blocked the rebind).
            val live = sshSessionManager.getSession(session.sessionId)
                ?.activeForwards
                ?.firstOrNull { it.ruleId == rule.id }
            activatedLive = live != null
            actualBoundPort = live?.actualBoundPort
        }
        JSONObject().apply {
            put("ruleId", rule.id)
            put("activated", session != null && activatedLive)
            actualBoundPort?.let { put("actualBoundPort", it) }
            if (session != null && !activatedLive) {
                put("error", "Rule saved but the live bind failed (port busy? — a just-closed connection holds it in TIME_WAIT for ~60s). Retry add_port_forward, or use a different bindPort.")
            }
        }
    }

    private suspend fun removePortForward(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val ruleId = args.optString("ruleId").ifEmpty {
            throw McpError(-32602, "Missing required argument: ruleId")
        }
        // Look up the rule to find the owning profile so we know which
        // session (if any) currently holds the live forward. Repository
        // doesn't expose getById today; iterate everyone's enabled rules
        // — list is tiny in practice (<10 rules per profile).
        var owningProfileId: String? = null
        var owningRule: PortForwardRule? = null
        for (p in connectionRepository.getAll()) {
            val r = portForwardRepository.getEnabledForProfile(p.id).firstOrNull { it.id == ruleId }
            if (r != null) { owningProfileId = p.id; owningRule = r; break }
        }
        if (owningProfileId != null) {
            if (reticulumSessionManager.isProfileConnected(owningProfileId)) {
                // Reticulum forwards key on the bound port (bindPort=0 OS-pick
                // not deactivatable by id in v1).
                owningRule?.let { reticulumForwardServer.stopForward(owningProfileId, it.bindPort) }
            } else {
                val session = sshSessionManager.getSessionsForProfile(owningProfileId)
                    .firstOrNull { it.status == SshSessionManager.SessionState.Status.CONNECTED }
                if (session != null) {
                    val forward = session.activeForwards.firstOrNull { it.ruleId == ruleId }
                    if (forward != null) {
                        sshSessionManager.removePortForward(session.sessionId, forward)
                    }
                }
            }
        }
        portForwardRepository.delete(ruleId)
        JSONObject().apply {
            put("ruleId", ruleId)
            put("deactivated", owningProfileId != null)
            put("deleted", true)
        }
    }

    private suspend fun setProfileRouting(args: JSONObject): JSONObject {
        val profileId = args.optString("profileId").ifBlank {
            throw IllegalArgumentException("profileId required")
        }
        val profile = connectionRepository.getById(profileId)
            ?: throw IllegalArgumentException("profile $profileId not found")
        val clear = args.optBoolean("clear", false)
        val tunnelConfigId = if (args.has("tunnelConfigId")) args.optString("tunnelConfigId") else null
        val proxyType = if (args.has("proxyType")) args.optString("proxyType") else null
        val proxyHost = if (args.has("proxyHost")) args.optString("proxyHost") else null
        val proxyPort = if (args.has("proxyPort")) args.optInt("proxyPort", 1080) else null
        val proxyUser = if (args.has("proxyUser")) args.optString("proxyUser") else null
        val proxyPassword = if (args.has("proxyPassword")) args.optString("proxyPassword") else null

        val updated = when {
            clear -> profile.copy(
                tunnelConfigId = null,
                proxyType = null,
                proxyHost = null,
                proxyUser = null,
                proxyPassword = null,
            )
            !tunnelConfigId.isNullOrBlank() -> {
                tunnelConfigRepository.getById(tunnelConfigId)
                    ?: throw IllegalArgumentException("tunnel config $tunnelConfigId not found")
                profile.copy(
                    tunnelConfigId = tunnelConfigId,
                    proxyType = null,
                    proxyHost = null,
                    proxyUser = null,
                    proxyPassword = null,
                )
            }
            !proxyType.isNullOrBlank() -> {
                if (proxyType !in setOf("SOCKS5", "SOCKS4", "HTTP")) {
                    throw IllegalArgumentException("proxyType must be SOCKS5, SOCKS4, or HTTP")
                }
                if (proxyHost.isNullOrBlank()) {
                    throw IllegalArgumentException("proxyHost required when proxyType is set")
                }
                profile.copy(
                    tunnelConfigId = null,
                    proxyType = proxyType,
                    proxyHost = proxyHost,
                    proxyPort = proxyPort ?: profile.proxyPort,
                    // #227: only touch creds when the caller passed them, so
                    // re-routing without creds keeps any previously set ones.
                    proxyUser = if (proxyUser != null) proxyUser.ifBlank { null } else profile.proxyUser,
                    proxyPassword = if (proxyType == "SOCKS4") null
                        else if (proxyPassword != null) proxyPassword.ifBlank { null } else profile.proxyPassword,
                )
            }
            else -> throw IllegalArgumentException(
                "set_profile_routing needs one of: tunnelConfigId, proxyType+proxyHost, or clear=true"
            )
        }
        connectionRepository.save(updated)
        return JSONObject().apply {
            put("profileId", updated.id)
            put("tunnelConfigId", updated.tunnelConfigId ?: JSONObject.NULL)
            put("proxyType", updated.proxyType ?: JSONObject.NULL)
            put("proxyHost", updated.proxyHost ?: JSONObject.NULL)
            put("proxyPort", updated.proxyPort)
            // Never echo the password; just report whether proxy auth is set.
            put("proxyUser", updated.proxyUser ?: JSONObject.NULL)
            put("proxyAuth", !updated.proxyUser.isNullOrEmpty())
        }
    }

    /**
     * Edit fields on an existing profile (load → copy changed → save). Only
     * keys present in [args] change; everything else is preserved. Password
     * maps to the transport-specific column based on the profile's type, and
     * is never echoed back. Referenced ids (keyId, *SshProfileId) are validated.
     */
    private suspend fun updateConnection(args: JSONObject): JSONObject {
        val profileId = args.optString("profileId").ifBlank {
            throw IllegalArgumentException("profileId required")
        }
        val existing = connectionRepository.getById(profileId)
            ?: throw IllegalArgumentException("profile $profileId not found")

        fun str(key: String, current: String?): String? =
            if (args.has(key)) args.optString(key).ifBlank { null } else current
        fun bool(key: String, current: Boolean): Boolean =
            if (args.has(key)) args.optBoolean(key) else current

        // Validate referenced ids before saving so a typo fails here, not at connect.
        val newKeyId = str("keyId", existing.keyId)
        if (args.has("keyId") && !newKeyId.isNullOrBlank() && sshKeyRepository.getById(newKeyId) == null) {
            throw IllegalArgumentException("key $newKeyId not found")
        }
        for (refKey in listOf("vncSshProfileId", "rdpSshProfileId", "smbSshProfileId", "spiceSshProfileId", "glkvmSshProfileId")) {
            if (args.has(refKey)) {
                val ref = args.optString(refKey).ifBlank { null }
                if (ref != null && connectionRepository.getById(ref) == null) {
                    throw IllegalArgumentException("$refKey $ref not found")
                }
            }
        }

        // A malformed VID:PID would never match a live device, and the auto-forward
        // would just log "no device attached" on every connect — fail here instead.
        if (args.has("usbForwardVidPid")) {
            val vidPid = args.optString("usbForwardVidPid").ifBlank { null }
            if (vidPid != null && !Regex("^[0-9a-fA-F]{4}:[0-9a-fA-F]{4}$").matches(vidPid)) {
                throw IllegalArgumentException(
                    "usbForwardVidPid must be VID:PID hex (e.g. 1050:0406), got \"$vidPid\"",
                )
            }
        }

        // Password maps to the transport-specific column; accept either the
        // transport-specific key (matching create_connection) or generic `password`.
        fun newPassword(specificKey: String, current: String?): String? = when {
            args.has(specificKey) -> args.optString(specificKey).ifBlank { null }
            args.has("password") -> args.optString("password").ifBlank { null }
            else -> current
        }

        // `port` maps to the base port AND the transport-specific port column —
        // VNC/RDP/SMB dial vncPort/rdpPort/smbPort, not the base port (mirrors
        // create_connection, which sets both).
        val portChanged = args.has("port")
        val newPort = if (portChanged) args.optInt("port", existing.port) else existing.port

        val updated = existing.copy(
            label = if (args.has("label")) args.optString("label").ifBlank { existing.label } else existing.label,
            host = if (args.has("host")) args.optString("host").ifBlank { existing.host } else existing.host,
            port = newPort,
            vncPort = if (portChanged && existing.connectionType == "VNC") newPort else existing.vncPort,
            rdpPort = if (portChanged && existing.connectionType == "RDP") newPort else existing.rdpPort,
            smbPort = if (portChanged && existing.connectionType == "SMB") newPort else existing.smbPort,
            spicePort = if (portChanged && existing.connectionType == "SPICE") newPort else existing.spicePort,
            username = if (args.has("username")) args.optString("username") else existing.username,
            sshPassword = if (existing.connectionType == "SSH") newPassword("sshPassword", existing.sshPassword) else existing.sshPassword,
            vncPassword = if (existing.connectionType == "VNC") newPassword("vncPassword", existing.vncPassword) else existing.vncPassword,
            rdpPassword = if (existing.connectionType == "RDP") newPassword("rdpPassword", existing.rdpPassword) else existing.rdpPassword,
            smbPassword = if (existing.connectionType == "SMB") newPassword("smbPassword", existing.smbPassword) else existing.smbPassword,
            spicePassword = if (existing.connectionType == "SPICE") newPassword("spicePassword", existing.spicePassword) else existing.spicePassword,
            openaiApiKey = if (existing.connectionType == "OPENAI") newPassword("openaiApiKey", existing.openaiApiKey) else existing.openaiApiKey,
            openaiPathPrefix = if (existing.connectionType == "OPENAI") str("openaiPathPrefix", existing.openaiPathPrefix) else existing.openaiPathPrefix,
            aiProtocol = if (existing.connectionType == "OPENAI") {
                // Absent = unchanged; empty string clears (back to OPENAI).
                if (args.has("protocol")) {
                    args.optString("protocol").trim().uppercase().takeIf { it.isNotBlank() }
                        ?.let { p ->
                            if (p == "OPENAI" || p == "OLLAMA" || p == "ANTHROPIC" || p == "GEMINI") p
                            else throw IllegalArgumentException("protocol must be OPENAI, OLLAMA, ANTHROPIC, or GEMINI")
                        }
                } else existing.aiProtocol
            } else existing.aiProtocol,
            keyId = newKeyId,
            sshOptions = if (existing.connectionType == "SSH") {
                var opts = str("sshOptions", existing.sshOptions).orEmpty()
                if (args.has("forwardX11")) {
                    opts = sh.haven.core.ssh.setForwardX11InOptions(opts, args.optBoolean("forwardX11"))
                }
                if (args.has("forwardPulseAudio")) {
                    opts = sh.haven.core.ssh.setForwardPulseAudioInOptions(opts, args.optBoolean("forwardPulseAudio"))
                }
                opts.ifBlank { null }
            } else existing.sshOptions,
            remoteCommand = if (existing.connectionType == "SSH") str("remoteCommand", existing.remoteCommand) else existing.remoteCommand,
            bindAddress = if (existing.connectionType == "SSH") str("bindAddress", existing.bindAddress) else existing.bindAddress,
            requestPty = if (existing.connectionType == "SSH") bool("requestPty", existing.requestPty) else existing.requestPty,
            jumpProfileId = str("jumpProfileId", existing.jumpProfileId),
            ignoreSavedKeys = bool("ignoreSavedKeys", existing.ignoreSavedKeys),
            useMosh = bool("useMosh", existing.useMosh),
            forwardAgent = bool("forwardAgent", existing.forwardAgent),
            vncSshForward = bool("vncSshForward", existing.vncSshForward),
            vncSshProfileId = str("vncSshProfileId", existing.vncSshProfileId),
            rdpSshForward = bool("rdpSshForward", existing.rdpSshForward),
            rdpSshProfileId = str("rdpSshProfileId", existing.rdpSshProfileId),
            smbSshForward = bool("smbSshForward", existing.smbSshForward),
            smbSshProfileId = str("smbSshProfileId", existing.smbSshProfileId),
            spiceSshForward = bool("spiceSshForward", existing.spiceSshForward),
            spiceSshProfileId = str("spiceSshProfileId", existing.spiceSshProfileId),
            glkvmPort = if (portChanged && existing.connectionType == "GLKVM") newPort else existing.glkvmPort,
            glkvmPassword = if (existing.connectionType == "GLKVM") newPassword("glkvmPassword", existing.glkvmPassword) else existing.glkvmPassword,
            glkvmTargetLayout = str("glkvmTargetLayout", existing.glkvmTargetLayout),
            glkvmSshForward = bool("glkvmSshForward", existing.glkvmSshForward),
            glkvmSshProfileId = str("glkvmSshProfileId", existing.glkvmSshProfileId),
            moshServerCommand = str("moshServerCommand", existing.moshServerCommand),
            usbForwardVidPid = if (existing.connectionType == "SSH") {
                str("usbForwardVidPid", existing.usbForwardVidPid)
            } else {
                existing.usbForwardVidPid
            },
        )
        connectionRepository.save(updated)
        return profileToJson(updated)
    }

    private suspend fun createConnection(args: JSONObject): JSONObject {
        val label = args.optString("label").ifBlank { throw IllegalArgumentException("label required") }
        val type = args.optString("connectionType").uppercase().ifBlank {
            throw IllegalArgumentException("connectionType required")
        }
        if (type !in setOf("SSH", "SMB", "VNC", "RDP", "SPICE", "GLKVM", "EMAIL", "OPENAI", "BTSERIAL", "BLESERIAL", "USBSERIAL", "RETICULUM", "GUEST")) {
            throw IllegalArgumentException("connectionType must be SSH, SMB, VNC, RDP, SPICE, EMAIL, OPENAI, BTSERIAL, BLESERIAL, USBSERIAL, or RETICULUM (use the UI for LOCAL / RCLONE / GUEST)")
        }
        // EMAIL's host is the optional tunnel-ingress/bastion (SPA/knock target),
        // not the mail server — so it may be blank; every other type requires it.
        // RETICULUM addresses a destination hash, not a host: the gateway or
        // shared instance it reaches the mesh through is `reticulumHost`, and
        // it has a working default, so `host` may be blank here too.
        // GUEST has no fields at all — kernel args are fixed, the rootfs is
        // staged locally — so its host is blank as well.
        val host = args.optString("host")
        if (type !in setOf("EMAIL", "RETICULUM", "GUEST") && host.isBlank()) {
            throw IllegalArgumentException("host required")
        }
        val username = args.optString("username")
        val password = args.optString("password")
        val tunnelConfigId = if (args.has("tunnelConfigId")) args.optString("tunnelConfigId") else null
        if (!tunnelConfigId.isNullOrBlank()) {
            tunnelConfigRepository.getById(tunnelConfigId)
                ?: throw IllegalArgumentException("tunnel config $tunnelConfigId not found")
        }

        val defaultPort = when (type) {
            "SSH" -> 22
            "SMB" -> 445
            "VNC" -> 5900
            "RDP" -> 3389
            "SPICE" -> 5900
            "GLKVM" -> 443
            "OPENAI" -> 0
            "EMAIL" -> 0
            "BTSERIAL" -> 0
            "BLESERIAL" -> 0
            "USBSERIAL" -> 115200 // serial baud, stored in `port` (#408)
            "RETICULUM" -> 0 // the mesh port lives in reticulumPort
            else -> 22
        }
        // Honor the type-specific port field (vncPort/rdpPort/spicePort) the
        // description advertises, preferring it over the generic `port`, then
        // the per-type default. Previously only `port` was read, so a caller
        // following the docs and passing spicePort silently got 5900 (#353).
        val typePortArg = when (type) {
            "VNC" -> "vncPort"
            "RDP" -> "rdpPort"
            "SPICE" -> "spicePort"
            "GLKVM" -> "glkvmPort"
            "USBSERIAL" -> "usbBaudRate" // baud lives in `port` (#408)
            else -> null
        }
        val port = when {
            typePortArg != null && args.has(typePortArg) -> args.optInt(typePortArg, defaultPort)
            args.has("port") -> args.optInt("port", defaultPort)
            else -> defaultPort
        }

        val tunnelOnly = args.optBoolean("tunnelOnly", false)
        if (tunnelOnly && type != "SSH") {
            throw IllegalArgumentException("tunnelOnly is only meaningful for SSH connections")
        }

        // #121: password-only — never offer saved keys. Belongs on the SSH
        // profile (for VNC-over-SSH set it on the referenced SSH profile, since
        // the VNC profile itself does no SSH key auth).
        val ignoreSavedKeys = args.optBoolean("ignoreSavedKeys", false)

        // #166: optional ordered auth-method list. Each element is a token:
        // "PASSWORD", "KEY" (any saved key), "KEY:<keyId>", or
        // "KEYBOARD_INTERACTIVE". Serialised newline-per-token to match
        // ConnectionProfile.authMethods. Empty/absent = derive from keyId.
        val authMethodsArr = args.optJSONArray("authMethods")
        val authMethodsText = if (authMethodsArr != null) {
            (0 until authMethodsArr.length())
                .joinToString("\n") { authMethodsArr.getString(it).trim() }
        } else ""

        // Validate the knock sequence eagerly so an invalid string is
        // rejected at create time rather than silently saved and only
        // surfaced when the user (or agent) tries to connect. Empty/blank
        // means "no knock configured" — equivalent to omitting the field.
        val rawKnock = args.optString("portKnockSequence")
        val knockDelay = if (args.has("portKnockDelayMs")) args.optInt("portKnockDelayMs", 100) else 100
        val parsedKnock = sh.haven.core.knock.KnockSequence.parse(rawKnock, knockDelay)
            .getOrElse { throw IllegalArgumentException("portKnockSequence: ${it.message}") }
        val knockSequence = if (parsedKnock != null) rawKnock.trim() else null

        val profile = when (type) {
            // No fields to read: the kernel args are fixed and the rootfs is
            // staged locally on first connect.
            "GUEST" -> ConnectionProfile(
                label = label,
                host = "",
                port = 0,
                username = "",
                connectionType = "GUEST",
            )
            "SSH" -> ConnectionProfile(
                label = label,
                host = host,
                port = port,
                username = username,
                sshPassword = password.ifBlank { null },
                connectionType = "SSH",
                useMosh = args.optBoolean("useMosh", false),
                keyId = args.optString("keyId").ifBlank { null },
                sshOptions = run {
                    var opts = args.optString("sshOptions")
                    if (args.has("forwardX11")) {
                        opts = sh.haven.core.ssh.setForwardX11InOptions(opts, args.optBoolean("forwardX11"))
                    }
                    if (args.has("forwardPulseAudio")) {
                        opts = sh.haven.core.ssh.setForwardPulseAudioInOptions(opts, args.optBoolean("forwardPulseAudio"))
                    }
                    opts.ifBlank { null }
                },
                remoteCommand = args.optString("remoteCommand").ifBlank { null },
                bindAddress = args.optString("bindAddress").ifBlank { null },
                requestPty = args.optBoolean("requestPty", true),
                authMethods = authMethodsText,
                ignoreSavedKeys = ignoreSavedKeys,
                sessionManager = parseSessionManager(args.optString("sessionManager")),
                tunnelConfigId = tunnelConfigId,
                tunnelOnly = tunnelOnly,
                portKnockSequence = knockSequence,
                portKnockDelayMs = knockDelay,
            )
            "RETICULUM" -> ConnectionProfile(
                // The destination hash is the address; reticulumHost/Port is
                // only how this phone reaches the mesh — 127.0.0.1:37428 is a
                // Sideband or Columba shared instance on the same device, any
                // other host is a TCP gateway.
                label = label,
                host = "",
                port = 0,
                username = "",
                connectionType = "RETICULUM",
                destinationHash = normaliseReticulumDestinationHash(args.optString("destinationHash")),
                reticulumHost = args.optString("reticulumHost")
                    .ifBlank { host.ifBlank { "127.0.0.1" } },
                reticulumPort = if (args.has("reticulumPort")) {
                    args.optInt("reticulumPort", 37428)
                } else {
                    37428
                },
                reticulumNetworkName = args.optString("reticulumNetworkName").ifBlank { null },
                reticulumPassphrase = args.optString("reticulumPassphrase").ifBlank { null },
                tunnelConfigId = tunnelConfigId,
            )
            "BTSERIAL" -> ConnectionProfile(
                // host carries the paired device's Bluetooth MAC (#406).
                label = label,
                host = host,
                port = 0,
                username = "",
                connectionType = "BTSERIAL",
            )
            "BLESERIAL" -> ConnectionProfile(
                // host carries the BLE device MAC; GATT UUIDs auto-detected (NUS/HM-10).
                label = label,
                host = host,
                port = 0,
                username = "",
                connectionType = "BLESERIAL",
            )
            "USBSERIAL" -> ConnectionProfile(
                // host carries the vendorId:productId hex (e.g. 1a86:7523), port
                // carries the baud rate; the rest of the line format is packed
                // into sshOptions (#408). All default to 8N1, no flow control.
                label = label,
                host = host,
                port = port,
                // Packed line format "<dataBits>,<parity>,<stopBits>,<flow>" —
                // the canonical form UsbSerialParams reads back. Validated
                // inline (string constants) rather than via the typed enum.
                sshOptions = run {
                    val db = args.optInt("usbDataBits", 8).coerceIn(5, 8)
                    val par = args.optString("usbParity", "N").uppercase()
                        .takeIf { it in setOf("N", "O", "E", "M", "S") } ?: "N"
                    val sb = args.optString("usbStopBits", "1")
                        .takeIf { it in setOf("1", "1.5", "2") } ?: "1"
                    val fl = args.optString("usbFlowControl", "none").lowercase()
                        .takeIf { it in setOf("none", "rtscts", "xonxoff") } ?: "none"
                    "$db,$par,$sb,$fl"
                },
                username = "",
                connectionType = "USBSERIAL",
            )
            "SMB" -> {
                val share = args.optString("smbShare").ifBlank {
                    throw IllegalArgumentException("smbShare required for SMB")
                }
                ConnectionProfile(
                    label = label,
                    host = host,
                    port = port,
                    username = username,
                    connectionType = "SMB",
                    smbPort = port,
                    smbShare = share,
                    smbDomain = args.optString("smbDomain").ifBlank { null },
                    smbPassword = password.ifBlank { null },
                    tunnelConfigId = tunnelConfigId,
                    portKnockSequence = knockSequence,
                    portKnockDelayMs = knockDelay,
                )
            }
            "VNC" -> {
                // VNC-over-SSH-tunnel: forward the VNC connection through a
                // saved SSH profile (vncSshProfileId). Validate the referenced
                // profile exists so a typo fails at create time, not connect.
                val vncSshForward = args.optBoolean("vncSshForward", false)
                val vncSshProfileId = args.optString("vncSshProfileId").ifBlank { null }
                if (vncSshForward && vncSshProfileId != null &&
                    connectionRepository.getById(vncSshProfileId) == null
                ) {
                    throw IllegalArgumentException("vncSshProfileId $vncSshProfileId not found")
                }
                ConnectionProfile(
                    label = label,
                    host = host,
                    port = port,
                    username = "",
                    connectionType = "VNC",
                    vncPort = port,
                    vncUsername = args.optString("vncUsername").ifBlank { null },
                    vncPassword = args.optString("vncPassword").ifBlank { args.optString("password") }.ifBlank { null },
                    vncSshForward = vncSshForward,
                    vncSshProfileId = vncSshProfileId,
                    tunnelConfigId = tunnelConfigId,
                    portKnockSequence = knockSequence,
                    portKnockDelayMs = knockDelay,
                )
            }
            "RDP" -> {
                val rdpUser = args.optString("rdpUsername").ifBlank {
                    throw IllegalArgumentException("rdpUsername required for RDP")
                }
                ConnectionProfile(
                    label = label,
                    host = host,
                    port = port,
                    username = rdpUser,
                    connectionType = "RDP",
                    rdpPort = port,
                    rdpUsername = rdpUser,
                    rdpPassword = args.optString("rdpPassword").ifBlank { args.optString("password") }.ifBlank { null },
                    rdpDomain = args.optString("rdpDomain").ifBlank { null },
                    rdpSshForward = false,
                    tunnelConfigId = tunnelConfigId,
                    portKnockSequence = knockSequence,
                    portKnockDelayMs = knockDelay,
                )
            }
            "SPICE" -> {
                // SPICE-over-SSH-tunnel: forward through a saved SSH profile.
                val spiceSshForward = args.optBoolean("spiceSshForward", false)
                val spiceSshProfileId = args.optString("spiceSshProfileId").ifBlank { null }
                if (spiceSshForward && spiceSshProfileId != null &&
                    connectionRepository.getById(spiceSshProfileId) == null
                ) {
                    throw IllegalArgumentException("spiceSshProfileId $spiceSshProfileId not found")
                }
                ConnectionProfile(
                    label = label,
                    host = host,
                    port = port,
                    username = "",
                    connectionType = "SPICE",
                    spicePort = port,
                    spicePassword = args.optString("spicePassword").ifBlank { args.optString("password") }.ifBlank { null },
                    spiceSshForward = spiceSshForward,
                    spiceSshProfileId = spiceSshProfileId,
                    tunnelConfigId = tunnelConfigId,
                    portKnockSequence = knockSequence,
                    portKnockDelayMs = knockDelay,
                )
            }
            "GLKVM" -> {
                val glkvmSshForward = args.optBoolean("glkvmSshForward", false)
                val glkvmSshProfileId = args.optString("glkvmSshProfileId").ifBlank { null }
                if (glkvmSshForward && glkvmSshProfileId != null &&
                    connectionRepository.getById(glkvmSshProfileId) == null
                ) {
                    throw IllegalArgumentException("glkvmSshProfileId $glkvmSshProfileId not found")
                }
                ConnectionProfile(
                    label = label,
                    host = host,
                    port = port,
                    username = args.optString("username").ifBlank { "admin" },
                    connectionType = "GLKVM",
                    glkvmPort = port,
                    glkvmPassword = args.optString("glkvmPassword").ifBlank { args.optString("password") }.ifBlank { null },
                    glkvmTargetLayout = args.optString("glkvmTargetLayout").ifBlank { null },
                    glkvmSshForward = glkvmSshForward,
                    glkvmSshProfileId = glkvmSshProfileId,
                    tunnelConfigId = tunnelConfigId,
                    portKnockSequence = knockSequence,
                    portKnockDelayMs = knockDelay,
                )
            }
            "EMAIL" -> {
                val provider = args.optString("emailProvider").ifBlank { "imap" }.lowercase()
                val emailUser = username.ifBlank {
                    throw IllegalArgumentException("username (email address) required for EMAIL")
                }
                val server = args.optString("emailServer").ifBlank { null }
                if (provider == "imap" && server == null) {
                    throw IllegalArgumentException("emailServer required for an IMAP EMAIL profile")
                }
                ConnectionProfile(
                    label = label,
                    host = host, // optional tunnel ingress that SPA/knock guards
                    port = 0,
                    username = emailUser,
                    connectionType = "EMAIL",
                    emailProvider = provider,
                    emailUsername = emailUser,
                    emailPassword = password.ifBlank { null },
                    emailMailboxPassword = args.optString("emailMailboxPassword").ifBlank { null },
                    emailServer = server,
                    emailSmtpServer = args.optString("emailSmtpServer").ifBlank { null },
                    emailPort = if (args.has("emailPort")) args.optInt("emailPort", 993) else 993,
                    emailSmtpPort = if (args.has("emailSmtpPort")) args.optInt("emailSmtpPort", 465) else 465,
                    emailTls = args.optBoolean("emailTls", true),
                    tunnelConfigId = tunnelConfigId,
                    portKnockSequence = knockSequence,
                    portKnockDelayMs = knockDelay,
                )
            }
            "OPENAI" -> {
                ConnectionProfile(
                    label = label,
                    host = host,
                    port = port,
                    username = "",
                    connectionType = "OPENAI",
                    openaiApiKey = password.ifBlank { null },
                    openaiPathPrefix = args.optString("openaiPathPrefix").ifBlank { null },
                    aiProtocol = args.optString("protocol").trim().uppercase().takeIf { it.isNotBlank() && it != "OPENAI" }
                        ?.also { p ->
                            if (p !in listOf("OLLAMA", "ANTHROPIC", "GEMINI")) {
                                throw IllegalArgumentException("protocol must be OPENAI, OLLAMA, ANTHROPIC, or GEMINI")
                            }
                        },
                    tunnelConfigId = tunnelConfigId,
                    portKnockSequence = knockSequence,
                    portKnockDelayMs = knockDelay,
                )
            }
            else -> error("unreachable")
        }
        connectionRepository.save(profile)
        return JSONObject().apply {
            put("id", profile.id)
            put("label", profile.label)
            put("connectionType", profile.connectionType)
            put("host", profile.host)
            put("port", profile.port)
            put("tunnelConfigId", profile.tunnelConfigId ?: JSONObject.NULL)
            if (profile.tunnelOnly) put("tunnelOnly", true)
            if (knockSequence != null) {
                put("portKnockSequence", knockSequence)
                put("portKnockDelayMs", knockDelay)
            }
        }
    }

    private suspend fun setPortKnock(args: JSONObject): JSONObject {
        val profileId = args.optString("profileId").ifBlank {
            throw IllegalArgumentException("profileId required")
        }
        val existing = connectionRepository.getById(profileId)
            ?: throw IllegalArgumentException("profile $profileId not found")
        val rawSeq = args.optString("portKnockSequence")
        val delay = if (args.has("portKnockDelayMs")) {
            args.optInt("portKnockDelayMs", existing.portKnockDelayMs)
        } else {
            existing.portKnockDelayMs
        }
        val parsed = sh.haven.core.knock.KnockSequence.parse(rawSeq, delay)
            .getOrElse { throw IllegalArgumentException("portKnockSequence: ${it.message}") }
        val newSeq = if (parsed != null) rawSeq.trim() else null
        val updated = existing.copy(
            portKnockSequence = newSeq,
            portKnockDelayMs = delay,
        )
        connectionRepository.save(updated)
        return JSONObject().apply {
            put("id", updated.id)
            put("label", updated.label)
            put("portKnockSequence", newSeq ?: JSONObject.NULL)
            put("portKnockDelayMs", delay)
            put("steps", parsed?.steps?.size ?: 0)
        }
    }

    private suspend fun testPortKnock(args: JSONObject): JSONObject {
        val host = args.optString("host").ifBlank {
            throw IllegalArgumentException("host required")
        }
        val rawSeq = args.optString("portKnockSequence").ifBlank {
            throw IllegalArgumentException("portKnockSequence required")
        }
        val delay = if (args.has("portKnockDelayMs")) args.optInt("portKnockDelayMs", 100) else 100
        val seq = sh.haven.core.knock.KnockSequence.parse(rawSeq, delay)
            .getOrElse { throw IllegalArgumentException("portKnockSequence: ${it.message}") }
            ?: throw IllegalArgumentException("portKnockSequence parsed to empty")
        val result = portKnocker.knock(host, seq)
        return JSONObject().apply {
            put("host", host)
            put("sequence", seq.format())
            put("ok", result.ok)
            put("sentSteps", result.sentSteps)
            put("totalSteps", seq.steps.size)
            put("durationMs", result.totalDurationMs)
            put("error", result.error?.message ?: JSONObject.NULL)
        }
    }

    private suspend fun setSpa(args: JSONObject): JSONObject {
        val profileId = args.optString("profileId").ifBlank {
            throw IllegalArgumentException("profileId required")
        }
        val existing = connectionRepository.getById(profileId)
            ?: throw IllegalArgumentException("profile $profileId not found")
        val rawKey = args.optString("spaKey")
        val accessSpec = if (args.has("spaAccessSpec")) args.optString("spaAccessSpec") else existing.spaAccessSpec
        // Validate the resulting config (unless disabling).
        val parsed = sh.haven.core.spa.SpaConfig.parse(
            key = rawKey,
            keyIsBase64 = args.optBoolean("spaKeyBase64", existing.spaKeyBase64),
            hmacKey = if (args.has("spaHmacKey")) args.optString("spaHmacKey") else existing.spaHmacKey,
            hmacKeyIsBase64 = args.optBoolean("spaHmacKeyBase64", existing.spaHmacKeyBase64),
            accessSpec = accessSpec,
            allowMode = if (args.has("allowMode")) args.optString("allowMode") else existing.spaAllowMode,
            explicitIp = if (args.has("explicitIp")) args.optString("explicitIp") else existing.spaExplicitIp,
            spaPort = if (args.has("spaPort")) args.optInt("spaPort", existing.spaPort) else existing.spaPort,
        ).getOrElse { throw IllegalArgumentException("spa: ${it.message}") }
        val updated = if (parsed == null) {
            existing.copy(spaKey = null, spaAccessSpec = null)
        } else {
            existing.copy(
                spaKey = rawKey.trim(),
                spaKeyBase64 = parsed.keyIsBase64,
                spaHmacKey = parsed.hmacKey,
                spaHmacKeyBase64 = parsed.hmacKeyIsBase64,
                spaAccessSpec = parsed.accessSpec,
                spaAllowMode = parsed.allowMode.name,
                spaExplicitIp = parsed.explicitIp,
                spaPort = parsed.spaPort,
            )
        }
        connectionRepository.save(updated)
        return JSONObject().apply {
            put("id", updated.id)
            put("label", updated.label)
            put("spaEnabled", parsed != null)
            put("spaAccessSpec", updated.spaAccessSpec ?: JSONObject.NULL)
            put("spaAllowMode", updated.spaAllowMode)
            put("spaPort", updated.spaPort)
            put("hasHmacKey", !updated.spaHmacKey.isNullOrEmpty())
        }
    }

    private suspend fun testSpa(args: JSONObject): JSONObject {
        val host = args.optString("host").ifBlank {
            throw IllegalArgumentException("host required")
        }
        val config = sh.haven.core.spa.SpaConfig.parse(
            key = args.optString("spaKey"),
            keyIsBase64 = args.optBoolean("spaKeyBase64", false),
            hmacKey = args.optString("spaHmacKey").ifBlank { null },
            hmacKeyIsBase64 = args.optBoolean("spaHmacKeyBase64", false),
            accessSpec = args.optString("spaAccessSpec"),
            allowMode = args.optString("allowMode").ifBlank { "SOURCE" },
            explicitIp = args.optString("explicitIp").ifBlank { null },
            spaPort = if (args.has("spaPort")) args.optInt("spaPort", 62201) else 62201,
        ).getOrElse { throw IllegalArgumentException("spa: ${it.message}") }
            ?: throw IllegalArgumentException("spaKey and spaAccessSpec are required")
        val result = spaSender.send(host, config)
        return JSONObject().apply {
            put("host", host)
            put("accessSpec", config.accessSpec)
            put("spaPort", config.spaPort)
            put("ok", result.ok)
            put("bytesSent", result.packetLen)
            put("durationMs", result.totalDurationMs)
            put("error", result.error?.message ?: JSONObject.NULL)
        }
    }

    private suspend fun getConnectionLog(args: JSONObject): JSONObject {
        val profileId = args.optString("profileId").ifBlank {
            throw IllegalArgumentException("profileId required")
        }
        val limit = args.optInt("limit", 10).coerceIn(1, 100)
        // ConnectionLogRepository observes a Flow — take the latest snapshot
        // and slice it down to the requested limit. Newest first.
        val entries = connectionLogRepository.observeForProfile(profileId, limit)
            .first()
        val arr = JSONArray()
        for (e in entries) {
            arr.put(JSONObject().apply {
                put("timestamp", e.timestamp)
                put("status", e.status.name)
                put("durationMs", e.durationMs)
                put("details", e.details ?: JSONObject.NULL)
                put("verboseLog", e.verboseLog ?: JSONObject.NULL)
            })
        }
        return JSONObject().apply {
            put("profileId", profileId)
            put("count", entries.size)
            put("entries", arr)
        }
    }

    private suspend fun deleteConnection(args: JSONObject): JSONObject {
        val profileId = args.optString("profileId").ifBlank {
            throw IllegalArgumentException("profileId required")
        }
        val existing = connectionRepository.getById(profileId)
            ?: return JSONObject().apply { put("deleted", false); put("reason", "not found") }
        // Tear down any live session for this profile (incl. its VNC/RDP
        // Desktop tab via the lease), then drop tunnel refcount, then delete.
        sessionManagerRegistry.disconnectProfile(profileId)
        sshSessionManager.teardownTunnelDependent(profileId)
        tunnelManager.release(profileId)
        connectionRepository.delete(profileId)
        return JSONObject().apply {
            put("deleted", true)
            put("id", profileId)
            put("label", existing.label)
        }
    }

    private suspend fun connectProfile(args: JSONObject): JSONObject {
        val profileId = args.optString("profileId").ifBlank {
            throw IllegalArgumentException("profileId required")
        }
        val sessionName = args.optString("sessionName").ifBlank { null }
        val profile = connectionRepository.getById(profileId)
            ?: throw IllegalArgumentException("profile $profileId not found")
        agentUiCommandBus.emit(
            sh.haven.core.data.agent.AgentUiCommand.ConnectProfile(profileId, sessionName)
        )
        return JSONObject().apply {
            put("profileId", profileId)
            put("label", profile.label)
            put("dispatched", true)
            put(
                "note",
                "Connect dispatched asynchronously through the same path a UI tap uses. Poll list_sessions for status."
            )
        }
    }

    private suspend fun runCommand(args: JSONObject): JSONObject {
        val exec = headlessSshExec
            ?: throw McpError(-32603, "run_command is unavailable in this build")
        val profileId = args.optString("profileId").ifBlank {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val command = args.optString("command").ifBlank {
            throw McpError(-32602, "Missing required argument: command")
        }
        val timeoutMs = args.optLong("timeoutMs", 30_000L).coerceIn(1_000L, 300_000L)
        val cap = args.optInt("maxOutputChars", 8_000).coerceAtLeast(256)
        val outcome = exec.run(profileId, command, timeoutMs)
        return JSONObject().apply {
            // Flat on purpose: MacroDroid/Tasker macros parse this straight
            // out of the HTTP response body (#367).
            put("exitCode", if (outcome.exec.timedOut) JSONObject.NULL else outcome.exec.exitStatus)
            put("stdout", outcome.exec.stdout.takeLast(cap))
            put("stderr", outcome.exec.stderr.takeLast(cap))
            put("timedOut", outcome.exec.timedOut)
            put("reusedLiveConnection", outcome.reusedLiveConnection)
        }
    }

    private fun getPendingAuthPrompt(): JSONObject {
        val p = pendingAuthPromptHolder.prompt.value
            ?: return JSONObject().put("pending", false)
        return JSONObject().apply {
            put("pending", true)
            put("profileId", p.profileId)
            put("label", p.label)
            put("requiresPassphrase", p.requiresPassphrase)
        }
    }

    private suspend fun answerAuthPrompt(args: JSONObject): JSONObject {
        val pending = pendingAuthPromptHolder.prompt.value
            ?: return JSONObject().apply {
                put("answered", false)
                put("note", "No auth prompt is pending. Start a connect with connect_profile first.")
            }
        val password = args.optString("password")
        if (password.isEmpty()) throw IllegalArgumentException("password required")
        val username = args.optString("username").ifBlank { null }
        val remember = args.optBoolean("rememberPassword", false)
        agentUiCommandBus.emit(
            sh.haven.core.data.agent.AgentUiCommand.AnswerAuthPrompt(password, username, remember)
        )
        return JSONObject().apply {
            put("answered", true)
            put("profileId", pending.profileId)
            put(
                "note",
                "Answer dispatched. get_pending_auth_prompt clears on success and re-surfaces on a wrong secret; confirm with list_sessions."
            )
        }
    }

    /**
     * Validate + normalise a session-manager name for create_profile. Stored
     * uppercase to match SessionManager.valueOf in ConnectionsViewModel. Blank
     * => null (no session manager). Throws on an unknown name.
     */
    private fun parseSessionManager(raw: String?): String? {
        val v = raw?.trim()?.uppercase()?.ifBlank { null } ?: return null
        val valid = setOf("TMUX", "ZELLIJ", "SCREEN", "BYOBU", "HERDR", "PSMUX", "RMUX")
        if (v !in valid) {
            throw IllegalArgumentException("sessionManager must be one of ${valid.joinToString(", ")} (or omit for none)")
        }
        return v
    }

    private fun getPendingSessionPicker(): JSONObject {
        val s = sessionSelectionHolder.selection.value
            ?: return JSONObject().put("pending", false)
        return JSONObject().apply {
            put("pending", true)
            put("profileId", s.profileId)
            put("sessionId", s.sessionId)
            put("sessionManager", s.managerLabel)
            put("sessionNames", JSONArray().apply { s.sessionNames.forEach { put(it) } })
            put("previousSessionNames", JSONArray().apply { s.previousSessionNames.forEach { put(it) } })
            put("suggestedNewName", s.suggestedNewName)
        }
    }

    private suspend fun answerSessionPicker(args: JSONObject): JSONObject {
        val pending = sessionSelectionHolder.selection.value
            ?: return JSONObject().apply {
                put("answered", false)
                put("note", "No session picker is pending. connect_profile (without sessionName) to a session-manager profile that has existing sessions surfaces it.")
            }
        val createNew = args.optBoolean("createNew", false)
        val name = args.optString("sessionName").ifBlank { null }
        val chosen: String? = when {
            createNew -> null // null sessionName => onSessionSelected creates a new uniquely-named session
            name != null -> {
                if (name !in pending.sessionNames) {
                    throw McpError(-32602, "sessionName \"$name\" is not in the picker (${pending.sessionNames}); pass createNew=true to make a new one")
                }
                name
            }
            else -> throw McpError(-32602, "pass sessionName=<existing> (from get_pending_session_picker) or createNew=true")
        }
        agentUiCommandBus.emit(
            sh.haven.core.data.agent.AgentUiCommand.AnswerSessionSelection(pending.sessionId, chosen)
        )
        return JSONObject().apply {
            put("answered", true)
            put("sessionId", pending.sessionId)
            put("attached", chosen ?: "(new session)")
            put("note", "Attach dispatched via onSessionSelected. Confirm with list_sessions / read_terminal_snapshot.")
        }
    }
}
