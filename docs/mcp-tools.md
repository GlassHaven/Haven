---
layout: default
title: Agent transport (MCP)
---

# Haven's MCP tools — what an agent can do, and when you are asked

<!-- GENERATED FILE — do not edit. Rendered from the live tool registry by
     McpToolsConsentTest.`docs mcp-tools_md is generated from the live registry`.
     Regenerate: ./gradlew :app:testX64DebugUnitTest --tests '*.McpToolsConsentTest' -->

Haven exposes an [MCP](https://modelcontextprotocol.io) endpoint so an AI agent
(Claude Code, or anything speaking MCP over HTTP) can drive the phone side of your
sessions. This page is the complete tool surface, generated from the same registry
the server dispatches — there are no undocumented tools.

## The security model, in order

1. **Off by default.** Nothing listens until you enable Settings → Agent endpoint.
2. **Loopback only by default.** The endpoint binds 127.0.0.1 (device ports 8730–8739,
   SSH-tunneled clients 8740–8749). LAN/WireGuard exposure is a separate, explicit opt-in.
3. **Pairing.** A new client's first request raises an Allow/Deny prompt on the phone.
   Allowing mints a per-client 256-bit bearer token, shown once; Haven stores only its
   SHA-256. Unpairing (Settings → Agent endpoint → Paired clients) revokes it.
4. **Capability switches.** File reading and terminal-input queuing have their own
   Settings toggles on top of everything else; tools behind them are marked below.
5. **Per-call consent.** Every tool carries one of three consent levels — *asks every
   call*, *asks once per session*, or *no per-call prompt* — shown as a tag on each tool
   below. Consent sheets are rendered by Haven itself, and agent-injected taps are
   refused while one is showing — an agent cannot approve its own prompt.
6. **Standing policies.** An agent may request a time-boxed, rate-limited pre-approval
   for named tools (create_standing_policy) — it is itself consent-gated on every call,
   shows exactly what would be allowed, and has a kill-switch on the Agent activity screen.
7. **Audit.** Every call crossing the agent transport is recorded on-device with
   arguments redacted (Settings → Agent activity — the same screen that lists and
   revokes standing policies). Haven is the dashboard, not a black box.

## How to read this page

Tools are grouped into sections by what they touch, and each tool is collapsed —
expand one for its description and arguments. The tag after each name is its
consent level:

- **asks every call** — side-effectful or sensitive; a consent sheet describing the specific action on every call (70 tools).
- **asks once per session** — reversible actions and screen-reading; prompts the first time each session, then proceeds (51 tools).
- **no per-call prompt** — read-only queries and tap-equivalent UI actions; still behind the endpoint being enabled and the client paired (87 tools).

## Sections

- [**Connections & profiles**](#sec-connections) — 9 tools
- [**Terminal, selection & sessions**](#sec-terminal) — 27 tools
- [**Files, media & clipboard**](#sec-files) — 19 tools
- [**Cloud storage (rclone)**](#sec-rclone) — 15 tools
- [**Email**](#sec-email) — 15 tools
- [**Linux guest (proot) & desktops**](#sec-linux) — 46 tools
- [**Networking — tunnels & port forwarding**](#sec-networking) — 14 tools
- [**USB & host-device brokers**](#sec-usb) — 19 tools
- [**Security — SSH keys, host keys, TOTP & age**](#sec-security) — 15 tools
- [**Agent ↔ you (attention & self-drive)**](#sec-agent-you) — 12 tools
- [**Agent endpoint, device & diagnostics**](#sec-agent-endpoint) — 17 tools

<a id="sec-connections"></a>

## Connections & profiles (9)

The saved SSH/SFTP/RDP/VNC/… connection profiles and their live connect/disconnect state.

<details markdown="1">
<summary><code>connect_profile</code> · asks once per session</summary>

Initiate a connection for a saved profile via the same code path a UI tap uses (route-through, stored password, key auth all apply). Posts an AgentUiCommand.ConnectProfile that ConnectionsViewModel observes — the actual connect happens asynchronously. Use list_sessions afterwards to confirm the session reached CONNECTED. If the profile needs a password that isn't stored and isn't a key, the UI password prompt will surface to the user. For an SSH profile that uses a session manager (tmux/zellij/screen), pass sessionName to attach or create a named session non-interactively; without it, a connect to a profile that has existing sessions surfaces the interactive picker — poll get_pending_session_picker and resolve it with answer_session_picker (no longer stalls unanswerable).

- `profileId` (string, required) — Profile id from list_connections.
- `sessionName` (string) — Optional. SSH session-manager (tmux/zellij/screen) session to attach or create — attaches if it already exists, creates it otherwise. Skips the interactive session picker. Ignored for transports/profiles without a session manager.

</details>

<details markdown="1">
<summary><code>create_connection</code> · asks every call</summary>

Create a saved connection profile. Supports connectionType=SSH, SMB, VNC, RDP, SPICE, EMAIL. SSH-family fields: username (required), password (optional, stored), keyId (optional — references list_ssh_keys), ignoreSavedKeys (force password-only auth, never offer saved keys), useMosh (turn an SSH profile into a Mosh profile), sessionManager (optional: TMUX | ZELLIJ | SCREEN | BYOBU — attach through that multiplexer; omit for a plain shell), remoteCommand (run a command via an SSH exec request instead of a login shell — e.g. 'tmux new -A -s work' to attach-or-create that session before shell startup files run) + requestPty (PTY for it, default true). SMB: smbShare (required), username + password, smbDomain. VNC: vncUsername, vncPassword, vncPort, and vncSshForward + vncSshProfileId to tunnel VNC through a saved SSH profile. RDP: rdpUsername (required), rdpPassword, rdpDomain, rdpPort. SPICE: spicePassword (optional ticket — no username/domain), spicePort (default 5900), and spiceSshForward + spiceSshProfileId to tunnel SPICE through a saved SSH profile. EMAIL: emailProvider ("imap" default, or "proton"); username = the email address; password = the account/app-password; for IMAP set emailServer (required) + emailPort (993) + emailSmtpPort (465) + emailTls (true), plus emailSmtpServer when the SMTP host differs (e.g. smtp.gmail.com); for Proton add emailMailboxPassword if two-password mode. EMAIL host is optional (the tunnel-ingress/bastion SPA/knock guards), not the mail server. BTSERIAL (Bluetooth-serial console, #406): host = the paired device's Bluetooth MAC (from list_bluetooth_devices); no other fields. The device must already be paired in Android Settings. BLESERIAL (Bluetooth-LE-serial console — Nordic UART Service / HM-10): host = the BLE peripheral's MAC; no other fields. It needn't be paired — scan-and-pick in the editor; the GATT service/characteristics are auto-detected (NUS 6E400001…, then HM-10 FFE0/FFE1). USBSERIAL (USB-serial console, #408 — Arduino / Duet3D G-code / ESP32 / USB-TTL): host = the device's vendorId:productId hex, e.g. 1a86:7523, from list_usb_devices; usbBaudRate = baud (default 115200); usbDataBits/usbParity/usbStopBits/usbFlowControl set the rest of the line format (default 8N1, no flow control). Plug the adapter in first; connect_profile pops the Android USB-permission prompt. Chipsets: CDC-ACM, CH34x, FTDI, CP21xx, Prolific. The new profile id is returned for follow-up calls (set_profile_routing, connect_profile). For Reticulum / rclone / local create the profile in the UI — those paths need OAuth / destination-hash flows the agent can't drive.

- `connectionType` (string, required) — SSH | SMB | VNC | RDP | SPICE | EMAIL | BTSERIAL | USBSERIAL.
- `host` (string, required) — Target hostname or IP. For EMAIL this is the optional tunnel ingress/bastion (SPA/knock target), NOT the mail server — leave blank for a direct IMAP connection.
- `label` (string, required) — User-facing label.
- `authMethods` (string[]) — SSH only (#166): ordered multi-factor auth methods attempted in one connect, for servers requiring a chain like publickey,password. Each element is a token: "PASSWORD", "KEY" (any saved key), "KEY:<keyId>", "KEYBOARD_INTERACTIVE", or "TOTP:<id>" (auto-fill an OATH-TOTP code from list_totp_secrets, #178). Omit for the single-method default derived from keyId/password.
- `emailMailboxPassword` (string) — EMAIL/proton only: separate mailbox password for two-password-mode accounts.
- `emailPort` (integer) — EMAIL/imap only: IMAP port. Default 993.
- `emailProvider` (string) — EMAIL only: "imap" (generic IMAP/SMTP, default) or "proton".
- `emailServer` (string) — EMAIL/imap only: IMAP server hostname (required for imap). Reached through the tunnel when one is set.
- `emailSmtpPort` (integer) — EMAIL/imap only: SMTP port. Default 465.
- `emailSmtpServer` (string) — EMAIL/imap only: SMTP submission host, when it differs from the IMAP host (e.g. smtp.gmail.com vs imap.gmail.com). Optional — defaults to emailServer.
- `emailTls` (boolean) — EMAIL/imap only: implicit TLS (SSL). Default true.
- `ignoreSavedKeys` (boolean) — SSH-family only: when true, authenticate with password (and keyboard-interactive) only — saved keystore keys are never offered to the server. Lets a profile target a password-only server without the auto-key-offer suppressing the password prompt (#121). Default false.
- `keyId` (string) — SSH only: id of a saved SSH key (from list_ssh_keys) to authenticate with. Mutually optional with password.
- `password` (string) — Password (stored). Optional for SSH if a key is used; some VNC/SMB setups allow guest.
- `port` (integer) — TCP port. Defaults: SSH 22, SMB 445, VNC 5900, RDP 3389, SPICE 5900. Type-specific vncPort/rdpPort/spicePort override this.
- `portKnockDelayMs` (integer) — Inter-knock delay in ms (default 100). Ignored when portKnockSequence is empty.
- `portKnockSequence` (string) — Optional port-knock sequence fired before the real connect. Format: whitespace/comma-separated 'port[/proto]' tokens — e.g. '7000 8000 9000' (all TCP) or '7000/tcp 8000/udp 9000/tcp'. Empty = disabled.
- `rdpDomain` (string) — AD domain (RDP). Optional.
- `rdpPassword` (string) — Windows password (RDP).
- `rdpPort` (integer) — RDP only: TCP port (default 3389). Overrides the generic `port`.
- `rdpUsername` (string) — Windows username (RDP). Required when connectionType=RDP.
- `remoteCommand` (string) — SSH only (#436): run this command via an SSH exec request instead of opening a login shell — it executes before shell startup files, so e.g. 'tmux new -A -s work' attaches to/creates that named session without racing a .bashrc auto-tmux hook. Omit for the normal interactive shell.
- `requestPty` (boolean) — SSH only (#436): allocate a PTY for remoteCommand. Default true (tmux and other interactive programs need one); ignored when remoteCommand is omitted.
- `smbDomain` (string) — AD/workgroup domain (SMB). Optional.
- `smbShare` (string) — Share name (SMB). Required when connectionType=SMB.
- `spicePassword` (string) — SPICE only: ticket/password (stored). Optional — omit for an unticketed server.
- `spicePort` (integer) — SPICE only: TCP port (default 5900). Overrides the generic `port`.
- `spiceSshForward` (boolean) — SPICE only: tunnel through a saved SSH profile (set spiceSshProfileId). The SPICE target is reached at 127.0.0.1:<port> from the SSH server. Default false.
- `spiceSshProfileId` (string) — SPICE only: id of the SSH profile (from list_connections) to tunnel through when spiceSshForward is true.
- `sshOptions` (string) — SSH only: ssh_config-style option lines ('Key value' or 'Key=value', newline-separated) applied to this profile — e.g. 'ServerAliveInterval 60' or the Haven-internal 'HavenSshEngine sshlib' engine toggle (#58).
- `tunnelConfigId` (string) — Optional: route the new profile through this tunnel (from list_tunnels). Equivalent to follow-up set_profile_routing.
- `tunnelOnly` (boolean) — SSH only: tunnel-only mode (#150). When true, the profile brings up the SSH transport and registers port forwards but does not open a terminal. Default false. Pair with auto_reconnect for autossh-style keepalive.
- `usbBaudRate` (integer) — USBSERIAL only: serial baud rate (default 115200). Stored in `port`.
- `usbDataBits` (integer) — USBSERIAL only: data bits 5-8 (default 8).
- `usbFlowControl` (string) — USBSERIAL only: flow control none|rtscts|xonxoff (default none).
- `usbParity` (string) — USBSERIAL only: parity N|O|E|M|S (none/odd/even/mark/space, default N).
- `usbStopBits` (string) — USBSERIAL only: stop bits 1|1.5|2 (default 1).
- `useMosh` (boolean) — SSH only: when true, the profile uses Mosh on top of the SSH bootstrap. SSH execs `mosh-server new -s`, parses MOSH CONNECT, then the UDP transport takes over. Default false.
- `username` (string) — Username for SSH/SMB.
- `vncPassword` (string) — VNC password.
- `vncPort` (integer) — VNC only: TCP port (default 5900). Overrides the generic `port`.
- `vncSshForward` (boolean) — VNC only: tunnel the VNC connection through a saved SSH profile (set vncSshProfileId). The VNC target is reached at 127.0.0.1:<port> from the SSH server. Default false.
- `vncSshProfileId` (string) — VNC only: id of the SSH profile (from list_connections) to tunnel through when vncSshForward is true.
- `vncUsername` (string) — Username for VeNCrypt VNC.

</details>

<details markdown="1">
<summary><code>delete_connection</code> · asks every call</summary>

Delete a saved connection profile by id. Disconnects any live session for the profile first. Use this after integration tests to clean up agent-created profiles.

- `profileId` (string, required) — Profile id from list_connections.

</details>

<details markdown="1">
<summary><code>disconnect_profile</code> · asks once per session</summary>

Disconnect every live session for a profile across all transports (SSH, Mosh, Eternal Terminal, RDP, VNC, SMB, Reticulum, local, Bluetooth/BLE/USB serial). Use list_connections to find profileIds.

- `profileId` (string, required) — ID of the connection profile to disconnect.

</details>

<details markdown="1">
<summary><code>get_connection_log</code> · no per-call prompt</summary>

Read the most recent ConnectionLog entries for a profile. Use this to verify post-hoc what happened during a connect — including knock results (look for '[knock]' lines in verboseLog), TLS handshakes, and authentication. limit defaults to 10.

- `profileId` (string, required) — Profile id from list_connections.
- `limit` (integer) — Max entries to return (default 10, max 100).

</details>

<details markdown="1">
<summary><code>list_bluetooth_devices</code> · no per-call prompt</summary>

List Bluetooth Classic devices already paired (bonded) with the phone — { address, name } each. Use the address as `host` when creating a BTSERIAL (Bluetooth-serial console) profile with create_connection (#406). Only paired devices appear; pair a new BT-to-serial adapter in Android Settings → Bluetooth first. Returns an error hint if BLUETOOTH_CONNECT hasn't been granted (open the app's Bluetooth-serial connection editor once to grant it).

</details>

<details markdown="1">
<summary><code>list_connections</code> · no per-call prompt</summary>

List saved connection profiles (SSH, Mosh, VNC, RDP, SMB, rclone, local, Reticulum). Secrets like passwords and keys are redacted. SSH profiles also report `sshOptions` (the ssh_config-style lines set on the profile) and `sshEngine` — "jsch" (default) or "sshlib" (the experimental whole-connection engine, opted into with the 'HavenSshEngine sshlib' directive) — so an agent that sets the engine can confirm which one a profile is actually on.

</details>

<details markdown="1">
<summary><code>run_command</code> · asks every call</summary>

Run one command on a saved SSH connection over an exec channel (no terminal session, no PTY) and return { exitCode, stdout, stderr } — the automation-shaped verb (#367): a MacroDroid/Tasker HTTP Request macro or cron-style agent POSTs a single tools/call and reads the output straight from the response. Reuses the live connection when the profile is connected (also the only path for FIDO2/encrypted-key profiles); otherwise makes a one-shot headless connect, which needs a stored password or an unencrypted non-FIDO2 key AND a host key already trusted from a previous interactive connect (fail-closed TOFU — unknown/changed keys are refused, never silently accepted). Blocks until the command exits or timeoutMs elapses (then returns partial output with timedOut:true, exitCode:null). For unattended macros, create_standing_policy scoped to this tool + {"profileId": …} removes the per-call consent prompt. Headless-path limits: port-knock/SPA-guarded and tunnel-routed profiles aren't knocked/routed — connect those interactively first and let the reuse path serve them.

- `command` (string, required) — Command line passed to the remote user's shell via SSH exec, e.g. 'uptime' or 'systemctl status nginx'.
- `profileId` (string, required) — Saved SSH profile id (from list_connections).
- `maxOutputChars` (integer) — Cap stdout and stderr to their last N chars each. Default 8000.
- `timeoutMs` (integer) — Abort after this many ms, returning partial output with timedOut:true. Default 30000, range 1000–300000.

</details>

<details markdown="1">
<summary><code>update_connection</code> · asks every call</summary>

Edit fields on an existing connection profile (load → change → save). Pass profileId (required) plus only the fields you want to change — anything omitted is left as-is. Common SSH-family fields: label, host, port, username, password (stored, mapped to the profile's transport), keyId, ignoreSavedKeys (force password-only auth), useMosh, forwardAgent, remoteCommand (SSH exec instead of a login shell; empty string clears) + requestPty. Desktop tunnels: vncSshForward + vncSshProfileId, rdpSshForward + rdpSshProfileId, spiceSshForward + spiceSshProfileId, smbSshForward + smbSshProfileId. USB/IP auto-forward: usbForwardVidPid (export a phone-attached USB device to this host on every connect). Passwords are stored encrypted and never echoed back. For routing/proxy use set_profile_routing; for port-knock/SPA use set_port_knock/set_spa. Returns the updated profile (secrets redacted).

- `profileId` (string, required) — Profile id from list_connections.
- `forwardAgent` (boolean) — SSH only: enable SSH agent forwarding. Keys with a stored passphrase (or none) are exposed to the remote's ssh-agent socket (#377).
- `host` (string) — New hostname or IP.
- `ignoreSavedKeys` (boolean) — SSH-family only: force password-only auth, never offer saved keystore keys (#121).
- `jumpProfileId` (string) — SSH only: id of the SSH profile to jump through (ssh -J). The target host is dialled from the jump host, so it may be an address only the jump can reach. Empty string clears.
- `keyId` (string) — SSH only: id of a saved key (list_ssh_keys). Empty string clears.
- `label` (string) — New user-facing label.
- `moshServerCommand` (string) — Mosh only: override the command Haven runs over SSH to start mosh-server (default 'mosh-server new -s -c 256 …'). It must print a 'MOSH CONNECT <port> <key>' line, which Haven parses to find the session — so a wrapper can point Haven at a different port (e.g. scripts/mosh-fault-rig.py bootstrap, which puts a fault-injecting relay in front). Empty string restores the default.
- `password` (string) — New password (stored encrypted). Mapped to the profile's transport (SSH/VNC/RDP/SMB). Pass an empty string to clear it.
- `port` (integer) — New TCP port.
- `rdpSshForward` (boolean) — RDP only: tunnel through a saved SSH profile (set rdpSshProfileId).
- `rdpSshProfileId` (string) — RDP only: SSH profile id to tunnel through. Empty string clears.
- `remoteCommand` (string) — SSH only (#436): run this command via an SSH exec request instead of a login shell (e.g. 'tmux new -A -s work'). Empty string clears (back to the normal shell).
- `requestPty` (boolean) — SSH only (#436): allocate a PTY for remoteCommand (tmux needs one). Ignored when remoteCommand is empty.
- `smbSshForward` (boolean) — SMB only: tunnel through a saved SSH profile (set smbSshProfileId).
- `smbSshProfileId` (string) — SMB only: SSH profile id to tunnel through. Empty string clears.
- `spiceSshForward` (boolean) — SPICE only: tunnel through a saved SSH profile (set spiceSshProfileId).
- `spiceSshProfileId` (string) — SPICE only: SSH profile id to tunnel through. Empty string clears.
- `sshOptions` (string) — SSH only: replace the profile's ssh_config-style option lines (e.g. 'HavenSshEngine sshlib' opts this profile into the EXPERIMENTAL sshlib engine for the whole connection — terminal, exec, SFTP and tunnels; it refuses jump/proxy, FIDO2, OpenSSH certs and MFA chains). Empty string clears. Ignored on non-SSH profiles (USB-serial packs its line format here).
- `usbForwardVidPid` (string) — SSH only: VID:PID of a phone-attached USB device (e.g. '1050:0406' — see list_usb_devices) to auto-export over USB/IP whenever this profile connects. Haven opens the device, starts the usbip server on loopback, adds the remote forward, and runs `usbip attach` on the host, re-attaching after a tunnel drop. Empty string clears (no auto-forward).
- `useMosh` (boolean) — SSH only: use Mosh on top of the SSH bootstrap.
- `username` (string) — New username (SSH/SMB).
- `vncSshForward` (boolean) — VNC only: tunnel through a saved SSH profile (set vncSshProfileId).
- `vncSshProfileId` (string) — VNC only: SSH profile id to tunnel through. Empty string clears.

</details>

<a id="sec-terminal"></a>

## Terminal, selection & sessions (27)

Reading and driving terminal sessions: input, scrollback, text selection, snippets, and workspace layouts.

<details markdown="1">
<summary><code>answer_auth_prompt</code> · asks once per session</summary>

Answer the prompt reported by get_pending_auth_prompt — supply the secret a user would type into Haven's fallback dialog and Haven re-drives the connect through the same path a UI tap uses. Pass `password` (the host password or the encrypted key's passphrase). Optional `username` overrides the login user; `rememberPassword` stores it on the profile. Returns { answered:false } when no prompt is pending. A wrong value just re-surfaces the prompt (poll get_pending_auth_prompt; it clears on success) — confirm the result with list_sessions.

- `password` (string, required) — The host/account password or the encrypted key's passphrase to unlock the stalled connect.
- `rememberPassword` (boolean) — Optional. Persist the password on the profile (default false).
- `username` (string) — Optional. Override the login username for this attempt.

</details>

<details markdown="1">
<summary><code>answer_session_picker</code> · asks once per session</summary>

Answer the picker reported by get_pending_session_picker — attach to an existing remote session by name, or create a new one. Pass `sessionName` (must be one of the picker's sessionNames) to attach to it, or `createNew: true` to start a fresh session. Haven re-drives the attach through the same path a human tap uses (onSessionSelected). Returns { answered:false } when no picker is pending. Confirm the result with list_sessions / read_terminal_snapshot.

- `createNew` (boolean) — Create a new session instead of attaching to an existing one (default false).
- `sessionName` (string) — Existing remote session to attach to (from get_pending_session_picker.sessionNames). Omit and set createNew=true to start a new one.

</details>

<details markdown="1">
<summary><code>compose_workspace</code> · asks once per session</summary>

Launch every item of a saved workspace through the same WorkspaceLauncher the user's tap goes through — terminal sessions, file-browser tabs, remote desktops, and the Wayland tab open in dependency-friendly order (TERMINAL first so tunneled DESKTOPs attach to live SSH sessions). Returns the workspace id and item count; progress is surfaced live in the Connections screen banner.

- `workspaceId` (string, required) — Workspace id from list_workspaces.

</details>

<details markdown="1">
<summary><code>copy_selection</code> · asks every call</summary>

Copy the current selection to the system clipboard and return the copied text. Goes through Haven's smart-copy interceptor (TUI border-strip + soft-wrap rejoin). Clears the selection after copying. Returns { text }.

- `sessionId` (string, required) — Active session ID with a current selection.

</details>

<details markdown="1">
<summary><code>drag_selection_to</code> · asks once per session</summary>

Drag the selection's end anchor toward (toRow, toCol), auto-scrolling the viewport into scrollback (or back toward live) when toRow lies outside [0, rows-1]. Mirrors the Compose drag gesture that runs when the finger crosses the top or bottom edge zone: each row of out-of-viewport target is one ScrollController.scrollBy(±1) paired with one shiftSelectionStartByRows(±1) in lockstep. toRow < 0 scrolls back |toRow| rows (end anchor lands at viewport row 0). toRow >= rows scrolls forward toRow-(rows-1) rows (end anchor lands at viewport row rows-1). toRow in [0, rows-1] is equivalent to extend_selection. Clamps at the live screen and at scrollback.size; the response's `clamped` field indicates whether the full requested distance was achieved. Returns the resolved selection range, the new scrollbackPosition, the number of scroll steps actually taken (signed), the clamp flag, and the selection text via SelectionController.getSelectedText (libvterm softWrapped-aware, scrollback-aware).

- `sessionId` (string, required) — Active session ID with an attached terminal tab.
- `toCol` (integer, required) — Target column.
- `toRow` (integer, required) — Target row. May be negative (above viewport top — drag into scrollback) or >= rows (below viewport bottom — drag toward live).

</details>

<details markdown="1">
<summary><code>drag_terminal</code> · asks every call</summary>

Drive a synthetic touch-drag through the terminal's REAL pointer pipeline — the same awaitEachGesture handler a physical finger feeds. Unlike start_selection / extend_selection / drag_selection_to, which mutate the selection model directly, this exercises the actual gesture code: long-press classification, drag-extend, edge-zone detection, and the held-still auto-repeat edge-scroll ticker. The gesture is: touch-down at path[0]; hold still for pressMs (long enough for the long-press selection timeout, ~500ms, to fire); move through path[1..] one stepMs apart; hold still at path.last() for holdMs (the window the edge-scroll ticker runs in); lift. Rows are viewport-relative; out-of-viewport rows (e.g. -3, or rows beyond the bottom) still map to valid pixels so a path can target the top/bottom edge zone. Blocks until the gesture completes (~pressMs + path*stepMs + holdMs). Requires the session's terminal tab to be the foreground tab (the gesture injector mounts with the Composable). Returns { sessionId, cells, approxDurationMs }. Verify the effect with get_selection + read_terminal_snapshot afterwards.

- `path` (object[], required) — Ordered cells to traverse. Each element is { row, col } (viewport-relative; row may be negative or >= rows to reach the edge zones). path[0] = touch-down, last = lift. Must be non-empty; a single cell is a long-press with no drag.
- `sessionId` (string, required) — Active session ID; its terminal tab must be the foreground tab.
- `holdMs` (integer) — Still-hold at the final cell before lifting — the edge-scroll ticker's window. Default 1000.
- `pressMs` (integer) — Initial still-hold before the first move. Default 900. Must clear the long-press timeout (~500ms).
- `stepMs` (integer) — Delay between successive move events. Default 30.

</details>

<details markdown="1">
<summary><code>extend_selection</code> · asks once per session</summary>

Move the selection's end anchor to (row, col) in an active terminal session. Equivalent to dragging the selection handle to that cell. Pairs with start_selection — call start_selection first to set the anchor, then extend_selection to move the far end. Does NOT scroll the viewport — use drag_selection_to to extend a selection past the top or bottom of the viewport into scrollback.

- `col` (integer, required) — Column, 0 = leftmost.
- `row` (integer, required) — Viewport-relative row, 0 = top.
- `sessionId` (string, required) — Active session ID.

</details>

<details markdown="1">
<summary><code>feed_terminal_output</code> · asks every call</summary>

Inject raw bytes into a terminal session's OUTPUT stream — as if they had arrived from the remote — running the exact pipeline the live data callback uses (OSC scan → mouse-mode scan → emulator). Distinct from send_terminal_input, which sends to the PTY input as if typed. Use this to deterministically exercise output-side parsing without a cooperating remote: e.g. feed an OSC 52 sequence to test the clipboard round-trip, a DECSET 1000/1002/1003 to flip mouseMode, an OSC 8 hyperlink, or a partial escape split across two calls (the OSC scanner keeps state between calls). Provide exactly one of `text` (UTF-8) or `bytesBase64` (for control bytes / ESC). Hard cap 65536 bytes per call. On agent-opened local shells the bytes run the DECSET mode scan (mouse / bracketed paste) then go into the agent emulator — same as their live pipeline, which has no OSC scan. Errors when the session has no output pipeline. Returns { sessionId, bytesFed }.

- `sessionId` (string, required) — Active session ID with an attached terminal tab.
- `bytesBase64` (string) — Base64-encoded raw bytes to inject — use this for escape sequences (ESC = \u001b) and other control bytes. Mutually exclusive with text.
- `text` (string) — UTF-8 text to inject as output. Mutually exclusive with bytesBase64.

</details>

<details markdown="1">
<summary><code>focus_terminal_session</code> · no per-call prompt</summary>

Switch to the Terminal tab and bring the session with this sessionId to the front. Tap-equivalent — same effect as the user tapping the Terminal tab and tapping the session header. Use list_sessions to discover live sessionIds; stale IDs drop silently without error.

- `sessionId` (string, required) — Active session ID (from list_sessions). Must be a session attached to a Terminal tab.

</details>

<details markdown="1">
<summary><code>get_pending_auth_prompt</code> · no per-call prompt</summary>

Return the password / key-passphrase prompt Haven is currently waiting on for a stalled connect (its in-app fallback dialog), or { pending: false } if none. A connect started via connect_profile that needs a secret which isn't stored (a wrong/missing host password, or an assigned encrypted SSH key whose passphrase failed, #292) surfaces here instead of failing silently: { pending: true, profileId, label, requiresPassphrase } — requiresPassphrase=true means it wants the encrypted key's passphrase, false means a host/account password. Answer it with answer_auth_prompt. Read-only.

</details>

<details markdown="1">
<summary><code>get_pending_session_picker</code> · no per-call prompt</summary>

Return the session-manager picker Haven is currently waiting on, or { pending: false } if none. A connect_profile to a tmux/zellij/screen profile that has existing remote sessions (and no sessionName preselected) surfaces this picker instead of attaching: { pending: true, profileId, sessionId, sessionManager, sessionNames: [...], previousSessionNames: [...], suggestedNewName }. Answer it with answer_session_picker. Read-only. (Previously this picker was human-only and stalled the agent.)

</details>

<details markdown="1">
<summary><code>get_selection</code> · no per-call prompt</summary>

Return the current text-selection state for an active terminal session: { active, mode (NONE/CHARACTER/WORD/LINE), range: { startRow, startCol, endRow, endCol } | null }. Reads termlib's SelectionController; valid only while the session has an attached terminal tab.

- `sessionId` (string, required) — Active session ID with an attached terminal tab.

</details>

<details markdown="1">
<summary><code>list_sessions</code> · no per-call prompt</summary>

List currently registered sessions across all transports (ssh, mosh, et, reticulum, rdp, smb, local, mail, and Bluetooth/BLE/USB serial) with sessionId, profileId, label, status (connecting, connected, reconnecting, disconnected, error), transport, and isAgentRepl — a screen heuristic (Claude Code TUI chrome in the bottom lines) marking which terminal session is an agent REPL, so a conversation peer can be picked without guessing; null when the session has no attached terminal tab. SSH sessions additionally include sessionManager, chosenSessionName (the stable tmux/zellij identity that survives reconnects), channel state, jump-session linkage, and active port forwards.

</details>

<details markdown="1">
<summary><code>list_snippets</code> · no per-call prompt</summary>

List terminal toolbar snippets (custom send-key macros reachable from the scissors button). Returns each snippet's label, send (the literal text/escape sequence it types), and placement: "row1"/"row2" (has a dedicated toolbar button on that row) or "library" (in the scissors sheet only, no button). Manage them with set_snippet.

</details>

<details markdown="1">
<summary><code>list_workspaces</code> · no per-call prompt</summary>

List the user's saved workspace profiles — named bundles of terminal sessions, file-browser tabs, remote desktops, and Wayland that compose_workspace can reopen in one shot. Each entry has id, name, and itemCount. The kinds of items inside come from the same Kind enum the UI uses (TERMINAL / FILE_BROWSER / DESKTOP / WAYLAND).

</details>

<details markdown="1">
<summary><code>open_local_shell</code> · no per-call prompt</summary>

Open a fresh local Alpine PRoot shell session and return its sessionId. Equivalent to tapping the Terminal icon in the Connections top bar — creates the local shell profile if missing, registers a session, and connects it. The returned sessionId is immediately usable with send_terminal_input and read_terminal_scrollback. Use this when you need a clean bash REPL (e.g. when an existing session has Claude Code, vim, or another stdin-capturing process in front of it). Pass `plain: true` to bypass the user's session-manager preference (tmux / zellij / screen / byobu) and exec a bare login shell — required when the agent needs Haven's own scrollback ring to capture output, which doesn't happen when a multiplexer's status bar uses DECSTBM to reserve the bottom row. NOTE: if a live local shell already exists, this returns that sessionId regardless of `plain` (response `reused: true`); call disconnect_profile on the existing profile first if you need a fresh plain-shell respawn.

- `plain` (boolean) — Skip the user's sessionManager preference and exec /bin/busybox sh -l directly. Default false (UI-equivalent behaviour).

</details>

<details markdown="1">
<summary><code>queue_terminal_input</code> · asks every call</summary>

*Extra capability switch — off by default: queue_terminal_input is disabled — enable in Settings → Agent endpoint → Allow agents to queue terminal input.*

Power-user: queue text to be typed into any connected SSH session at the next matching prompt. Haven polls the session's stdout, matches `promptPattern` against the tail (ANSI escapes stripped, regex MULTILINE), then types `text + submitKey` via the session's tty when the pattern appears. Use cases include responding to interactive prompts in install scripts (`Continue? [y/N]` → `y`, or `Path:` → `/usr/local`), driving REPLs (Python, psql, Claude Code, etc.), and chaining steps where the agent waits for one prompt then types into the next. Defaults are tuned for the common "drive an interactive shell or REPL" case; supply explicit `promptPattern` / `submitKey` / `sessionId` when targeting something specific. Returns immediately with a queueId; delivery happens out-of-turn whenever the prompt appears (or the queue times out). Caveat: within one SSH session, only the *foreground* tmux pane receives the typed text — for multi-pane setups, pass `sessionId` to the right session and ensure the target pane is foreground. Gated by Settings → Agent endpoint → "Allow agents to queue terminal input" *and* per-call consent (with an "Allow for N min" option for collaborative windows).

- `text` (string, required) — Text to type. `submitKey` is appended automatically.
- `promptPattern` (string) — Regex matched against the tail of the SSH scrollback to trigger delivery. Default `[\$#%>❯]\s*$` matches the trailing prompt glyph of common interactive shells (bash `$`, root `#`, csh `%`, traditional `>`, fish/Claude Code/starship `❯`) at end-of-line. For specific programs, supply a pattern that matches their input prompt — e.g. `\[y/N\]\s*$` or `Password:\s*$` or `(?:postgres|mydb)=#\s*$`. ANSI escapes are stripped before matching; regex is MULTILINE.
- `sessionId` (string) — SSH session id from list_sessions. Optional — defaults to the SSH session carrying the MCP reverse tunnel on port 8730 (which, in the agent-drives-its-own-conversation case, is the session running the agent's REPL). For unrelated sessions, pass this explicitly.
- `submitKey` (string) — Key bytes sent after the text. Default `\r` — what TTYs in cooked mode translate to NL, and what programs in raw mode (Claude Code, vim, less, fzf, readline-based shells) read as Enter. Use `\n` for line-buffered programs reading stdin directly without a tty. Use `""` (empty) to leave the text in the input buffer without submitting (e.g. pre-fill a prompt the user will edit).
- `timeoutSeconds` (integer) — Give up if the prompt hasn't appeared in this many seconds. Default 60.

</details>

<details markdown="1">
<summary><code>read_exited_session</code> · no per-call prompt</summary>

Return the final screen of a terminal session that has already ended — the one thing every other terminal read verb cannot give you, because they need a live tab. A session that exits immediately (a remoteCommand that returns, a shell the server refuses, a connection dropped at startup) deregisters its tab, so read_terminal_snapshot and read_terminal_scrollback both fail with "no registered terminal tab" at exactly the moment its output would explain why. Pass profileId for the most recent exit on that profile, or omit it for the most recent exit overall. Returns { sessionId, profileId, label, rows, cols, exitedAtMs, screen: [lines] } with trailing blank lines trimmed, or { found: false } when nothing has exited since Haven started. The last 8 exits are kept in memory only — they do not survive an app restart.

- `profileId` (string) — Optional: only consider sessions that belonged to this profile (from list_connections). Omit for the most recent exit on any profile.

</details>

<details markdown="1">
<summary><code>read_terminal_scrollback</code> · no per-call prompt</summary>

Return the most recent bytes of raw SSH stdout for an active terminal session, exactly as the user sees them (ANSI escapes, OSC markers, control bytes preserved). Use list_sessions to discover sessionIds. The buffer is capped at 256 KiB per session and rolls older bytes off; the human terminal still keeps its own visual scrollback separately.

- `maxBytes` (integer) — Maximum bytes to return. Default 16384, hard-capped at 262144.
- `sessionId` (string) — Active session ID (from list_sessions). Optional — defaults to the sole open terminal session; required only when several are open.

</details>

<details markdown="1">
<summary><code>read_terminal_snapshot</code> · no per-call prompt</summary>

Return a structured snapshot of an active terminal session: dimensions, cursor row/col, terminal title, scrollback line count + current scrollbackPosition, the remote-driven terminal modes (mouseMode / activeMouseMode 1000|1002|1003 / bracketPasteMode), an oscEvents object with the last-seen OSC 52 clipboard-set / OSC 7 cwd / OSC 8 hyperlink / OSC 9|777 notification, and the visible-screen lines as plain text (with `softWrapped` flag per line, and optional OSC 133 semantic segments). Use list_sessions to discover sessionIds. Distinct from read_terminal_scrollback, which returns raw bytes; this is the parsed view useful for cursor-aware tooling, prompt detection, and asserting OSC/mouse-mode round-trips.

- `includeSemanticSegments` (boolean) — If true, include each line's OSC 133 prompt-marker segments (PROMPT / COMMAND_INPUT / COMMAND_OUTPUT / COMMAND_FINISHED / ANNOTATION). Default false.
- `maxLines` (integer) — Maximum number of visible-screen lines to include from the top. Default returns all visible rows. Cursor and dimensions are always present regardless.
- `sessionId` (string) — Active session ID (from list_sessions). Optional — defaults to the sole open terminal session; required only when several are open. Must have an attached terminal tab.

</details>

<details markdown="1">
<summary><code>scroll_terminal</code> · asks once per session</summary>

Scroll an active terminal session's viewport by N lines. Positive lines = back into scrollback (older content); negative lines = toward the live screen. Clamps at 0 (live) and scrollback.size. Returns { scrollbackPosition } — the new position after clamping.

- `lines` (integer, required) — Lines to scroll. Positive = into scrollback, negative = toward live.
- `sessionId` (string, required) — Active session ID with an attached terminal tab.

</details>

<details markdown="1">
<summary><code>send_terminal_input</code> · asks once per session</summary>

Send input to an active terminal session as if the user typed it. Provide `text` (UTF-8) and/or named `keys` — real control bytes (Enter/Esc/Ctrl-C/arrows) that `text` can't express (a "\r" in text arrives as literal chars; a raw-mode REPL reads "\n" as newline-insert, not submit). `text` is sent first, then `keys`, so a submit key lands after the body. Set `bracketedPaste` to wrap `text` in bracketed-paste markers so a raw-mode REPL (Claude Code, readline, vim) treats multi-line input as one paste instead of interleaved keystrokes that fight submit. Set `returnSnapshot` to get the resulting screen back without a follow-up read_terminal_snapshot. Hard cap 4096 bytes total. Returns { sessionId, delivered, transport, bytesSent }, where `transport` names which transport accepted the write — `delivered` means accepted, not echoed back.

- `bracketedPaste` (boolean) — Wrap text in bracketed-paste markers (ESC[200~ … ESC[201~). Default false. Use for multi-line input into a raw-mode REPL so it isn't folded into submit.
- `keys` (string[]) — Named keys sent after text, e.g. ["enter"], ["ctrl-c"], ["up","enter"]. Supported: enter, esc, tab, space, backspace, delete, up, down, left, right, home, end, pageup, pagedown, ctrl-a/c/d/e/l/u/w/z.
- `returnSnapshot` (boolean) — Return the terminal snapshot after sending, so you see the result without a follow-up read. Default false.
- `sessionId` (string) — Active session ID (from list_sessions). Optional — defaults to the sole open terminal session; required only when several are open. Must have an attached terminal.
- `snapshotDelayMs` (integer) — Wait this long after sending before capturing the returnSnapshot, so the target has rendered the input. Default 0, max 5000. Only meaningful with returnSnapshot=true.
- `text` (string) — UTF-8 text to send (before keys). To submit into a raw-mode REPL, prefer keys:["enter"] over a trailing \n.

</details>

<details markdown="1">
<summary><code>set_compose_mode</code> · asks once per session</summary>

Toggle or set termlib's local compose mode for a terminal session — the on-screen buffer used for CJK / accented / voice-friendly text entry. While compose mode is on, typed text (including IME-composed CJK candidates) buffers in an overlay at the cursor and the terminal hands the IME a composition-friendly InputConnection; the buffer commits to the shell on Enter, after which compose mode clears. Pass enabled=true/false to set explicitly, or omit enabled to toggle. Requires an attached terminal tab (errors for headless agent shells). Returns { sessionId, composeModeActive, composedText }.

- `sessionId` (string, required) — Active session ID with an attached terminal tab.
- `enabled` (boolean) — true = start compose mode, false = stop. Omit to toggle the current state.

</details>

<details markdown="1">
<summary><code>set_snippet</code> · asks once per session</summary>

Add, move, update or delete a terminal toolbar snippet (#244). `label` is required. To add/update: pass `send` (the text to type; JSON escapes like \n for Enter and \u001b for Esc work) and optional `placement` — "row1"/"row2" gives it a toolbar button on that row, "library" (the default) keeps it in the scissors sheet only. Re-passing an existing label moves/updates it. To remove it from both the toolbar and the library, pass `delete`:true. Returns the affected snippet's resulting placement and the full snippet list.

- `label` (string, required) — Snippet label (its name; also the button caption when placed on a row).
- `delete` (boolean) — Remove the snippet from the toolbar and the library entirely.
- `placement` (string) — "row1", "row2" (toolbar button) or "library" (scissors-only; default).
- `send` (string) — Text the snippet types. Required for a new snippet. JSON escapes work: \n = Enter, \u001b = Esc.

</details>

<details markdown="1">
<summary><code>set_terminal_font_from_url</code> · asks every call</summary>

Download a font from a URL, validate it, install it as Haven's terminal font (replacing any prior custom font), and return the saved path. The URL may point at a .ttf/.otf, or a .zip containing them (a Regular face is auto-extracted) — useful for repos like Maple/Nerd Fonts that ship only zips (#123, #177). WOFF/WOFF2 web fonts are rejected (Android can't render them). Requires the URL to be reachable from the device — use a tunneled URL (via add_port_forward LOCAL) to expose a workstation HTTP server back through the existing SSH session.

- `url` (string, required) — http(s) URL resolving to a .ttf/.otf, or a .zip of them (no HTML wrapper). WOFF/WOFF2 are not supported.

</details>

<details markdown="1">
<summary><code>start_selection</code> · asks once per session</summary>

Anchor a new text selection at (row, col) in an active terminal session. Equivalent to a long-press at that cell. Modes: CHARACTER (default), WORD (snaps to word boundaries on creation), LINE (whole-row selection). Subsequent extend_selection / copy_selection / clear_selection calls on the same session operate on this anchor. Replaces any existing selection on this session.

- `col` (integer, required) — Column, 0 = leftmost.
- `row` (integer, required) — Viewport-relative row, 0 = top.
- `sessionId` (string, required) — Active session ID with an attached terminal tab.
- `mode` (string) — CHARACTER | WORD | LINE. Default CHARACTER.

</details>

<details markdown="1">
<summary><code>tap_terminal</code> · asks every call</summary>

Simulate a tap inside an active terminal session at (row, col). When the user has Settings → Terminal → Tap to move cursor on supported prompts enabled, and the tap lands on a row carrying an OSC 133 COMMAND_INPUT segment with no matching COMMAND_FINISHED, Haven dispatches arrow keys so the readline cursor lands at the tapped column. Returns { handled, deltaCols, dispatched } describing what happened. handled=false means no OSC 133 prompt at the tap row — falls through silently.

- `col` (integer, required) — Column.
- `row` (integer, required) — Viewport-relative row.
- `sessionId` (string, required) — Active session ID.

</details>

<a id="sec-files"></a>

## Files, media & clipboard (19)

The unified file browser (local and SFTP), format conversion, media playback/streaming, encryption, and the clipboard.

<details markdown="1">
<summary><code>convert_file</code> · asks every call</summary>

Run ffmpeg to transcode a source URL (typically the playerUrl/playlistUrl from stream_sftp_file, or any URL ffmpeg can read) into a new file in Haven's app cache. Returns the cache path on success — use upload_file_to_sftp to put the result on a remote.

- `container` (string, required) — Output container, e.g. 'mp4', 'mkv', 'webm'. Determines the file extension.
- `sourceUrl` (string, required) — URL ffmpeg should read from. http(s)://, file://, or any protocol ffmpeg supports.
- `audioEncoder` (string) — Audio codec, e.g. 'aac', 'libopus', 'copy'. Default: copy.
- `outputName` (string) — Optional output filename (without extension). Default: 'agent-convert-<timestamp>'.
- `videoEncoder` (string) — Video codec, e.g. 'libx264', 'libx265', 'copy'. Default: copy.

</details>

<details markdown="1">
<summary><code>decrypt_file</code> · asks every call</summary>

Decrypt the `.age` file at `path` on `profileId` in place (strips `.age`) using any stored age identity (VISION §2). Drives the file browser's Decrypt (age) action via the UI command bus — the user sees it run. Fails to produce output if no stored identity matches the file's recipients.

- `path` (string, required) — Absolute path to the .age file.
- `profileId` (string, required) — Connection profile id (or 'local').

</details>

<details markdown="1">
<summary><code>delete_file</code> · asks every call</summary>

Delete a file (not a directory) on any connected backend (local, SSH, SMB, rclone). Replaces delete_sftp_file; that still works as a deprecated alias.

- `path` (string, required) — Path of the file to delete.
- `profileId` (string, required) — Connection profile ID, or 'local' for the device filesystem.

</details>

<details markdown="1">
<summary><code>delete_sftp_file</code> · asks every call</summary>

DEPRECATED: prefer delete_file(profileId=..., path=...). Delete a file (not directory) from a connected SFTP profile. Requires a connected SSH/SFTP session.

- `path` (string, required) — Absolute path of the file to delete.
- `profileId` (string, required) — Connected SSH/SFTP profile ID.

</details>

<details markdown="1">
<summary><code>encrypt_file</code> · asks every call</summary>

Encrypt the file at `path` on `profileId` to age recipients, producing `<name>.age` in the same folder (VISION §2 — works on every backend: local, SFTP, SMB, rclone). `recipients` (optional) is a list of `age1…` strings; omit it to encrypt to ALL of your stored age identities (so you can decrypt it back). Drives the file browser's Encrypt (age) action via the UI command bus — the user sees it run and the output appear. Non-destructive (the original is kept). Use list_age_identities for recipients and list_directory to find paths.

- `path` (string, required) — Absolute path to the file to encrypt.
- `profileId` (string, required) — Connection profile id (or 'local'). From list_connections.
- `recipients` (string[]) — age1… recipients. Omit to encrypt to all stored identities.

</details>

<details markdown="1">
<summary><code>list_directory</code> · no per-call prompt</summary>

List entries at a path on any connected backend (local, SSH/SFTP, SMB, rclone). Resolves the right driver from profileId — pass the literal string 'local' for the device filesystem, otherwise a profile ID from list_connections. Returns name, path, isDir, size, modTime, permissions, and mimeType for each entry. Replaces list_sftp_directory and list_rclone_directory; those still work as deprecated aliases.

- `profileId` (string, required) — Connection profile ID, or 'local' for the device filesystem.
- `path` (string) — Directory path to list. Default '/' (POSIX backends), '' (rclone root), '/' for local synthetic-roots view.

</details>

<details markdown="1">
<summary><code>list_sftp_directory</code> · no per-call prompt</summary>

DEPRECATED: prefer list_directory(profileId=..., path=...). List files at a path on a connected SFTP profile. Requires an already-connected SSH/SFTP session for the profile.

- `profileId` (string, required) — ID of the connected SSH/SFTP profile.
- `path` (string) — Absolute directory path to list. Defaults to '.'

</details>

<details markdown="1">
<summary><code>navigate_sftp_browser</code> · no per-call prompt</summary>

Switch to the Files tab and open the file browser at the given path on the given profile. Tap-equivalent — same effect as the user tapping into the SFTP screen and entering the path. The path is interpreted by whichever backend the profile resolves to (POSIX absolute for SSH/Local, share-relative for SMB, remote-relative for rclone).

- `profileId` (string, required) — Connection profile ID (or the literal string "local" for the device filesystem). Use list_connections to find IDs.
- `path` (string) — Directory path to open. Default '/' for SSH/Local, '' for rclone (treated as remote root).

</details>

<details markdown="1">
<summary><code>open_convert_dialog_with_args</code> · no per-call prompt</summary>

Stage a conversion in the SFTP screen's convert dialog with the given container / codec defaults. Switches to the SFTP tab and opens the dialog; the user reviews and taps Convert to actually run ffmpeg. Tap-equivalent — the agent suggests, the user confirms. Use convert_file (EVERY_CALL consent) to skip the dialog and run the conversion directly.

- `profileId` (string, required) — Connection profile ID. Use list_connections to find IDs.
- `sourcePath` (string, required) — Absolute path of the source file.
- `audioEncoder` (string) — Pre-selected audio codec, e.g. 'aac', 'libopus', 'copy'. Optional — defaults to 'copy'.
- `container` (string) — Pre-selected container, e.g. 'mp4', 'mkv', 'webm', 'mp3'. Optional — defaults to source extension.
- `videoEncoder` (string) — Pre-selected video codec, e.g. 'libx264', 'libx265', 'copy'. Optional — defaults to 'copy'.

</details>

<details markdown="1">
<summary><code>open_file_in_editor</code> · no per-call prompt</summary>

Open a text file in Haven's built-in editor (TextMate-syntax-highlighted, with Save). Routes to the SFTP/Files tab and loads the file from the given profile's backend (SSH, SMB, rclone, or the literal "local" for the device filesystem). The file is read on demand by the active backend; binary files render as garbled UTF-8 — use this for source code, config, logs, etc.

- `path` (string, required) — Absolute path to the file to open. POSIX absolute for SSH/Local, share-relative for SMB, remote-relative for rclone.
- `profileId` (string, required) — Connection profile ID (or the literal string "local" for the device filesystem). Use list_connections to find IDs.

</details>

<details markdown="1">
<summary><code>play_file</code> · no per-call prompt</summary>

Open a media URL in the system player (VLC, MX Player, Chrome, etc.) via Android's ACTION_VIEW intent. Typically the playerUrl/playlistUrl returned by stream_sftp_file, or any http/https/content URL the agent already knows. The user's preferred app picker (or default app) decides what handles it — Haven only kicks off the intent.

- `url` (string, required) — URL or content URI to open. http://, https://, file:// (rare; prefer FileProvider URIs) and content:// are accepted.
- `mimeType` (string) — Optional MIME hint, e.g. 'video/mp4', 'application/vnd.apple.mpegurl' for HLS. Auto-detected from URL extension when omitted.

</details>

<details markdown="1">
<summary><code>read_clipboard</code> · no per-call prompt</summary>

Return the system clipboard's primary plain-text content. Returns { text } where text is null when the clipboard is empty or non-text (image, intent, etc.). On Android 10+ the system enforces foreground/IME restrictions on clipboard reads; this call may return null even when the clipboard has content if Haven isn't currently focused.

</details>

<details markdown="1">
<summary><code>serve_file</code> · asks every call</summary>

*Extra capability switch — off by default: agent file read is disabled — enable in Settings → Agent endpoint.*

Publish a single file from any connected backend (local, SFTP, SMB, rclone) as a short-lived loopback HTTP URL the caller can curl to its own filesystem. Returns { url, size, mimeType }. Bytes are streamed over HTTP rather than returned inline through JSON-RPC. Gated by Settings → Agent endpoint → "Allow agents to read file contents" and confirmed per-call by a consent prompt.

- `path` (string, required) — Absolute path of the file on the chosen backend.
- `profileId` (string, required) — Connection profile ID, or 'local' for the device filesystem.

</details>

<details markdown="1">
<summary><code>stop_stream</code> · no per-call prompt</summary>

Stop any currently running HLS stream started by stream_sftp_file or the UI.

</details>

<details markdown="1">
<summary><code>stream_sftp_file</code> · no per-call prompt</summary>

Start an HLS stream for an SFTP file and return the playlist URL. Reads via a loopback HTTP bridge so no bulk download is needed. Requires a connected SSH/SFTP session. Stops any prior HLS stream.

- `path` (string, required) — Absolute path of the media file on the SFTP server.
- `profileId` (string, required) — ID of the connected SSH/SFTP profile.

</details>

<details markdown="1">
<summary><code>upload_file</code> · asks every call</summary>

Write a local file to a path on any connected backend (local, SSH, SMB, rclone). Source must live under Haven's app cache (context.cacheDir) — the agent has no other writable surface, so this constraint blocks reads of arbitrary device files via the upload destination. Currently uses small-file semantics (loads the source into memory); streaming variants ship in a later #126 stage. Replaces upload_file_to_sftp; that still works as a deprecated alias for the SSH streaming path.

- `localPath` (string, required) — Absolute path to a file under context.cacheDir on the device.
- `profileId` (string, required) — Connection profile ID, or 'local' for the device filesystem.
- `remotePath` (string, required) — Destination path on the target backend.

</details>

<details markdown="1">
<summary><code>upload_file_to_sftp</code> · asks every call</summary>

DEPRECATED: prefer upload_file(profileId=..., localPath=..., remotePath=...). Upload a local file to a path on a connected SFTP profile. Source must be a path under Haven's app cache (context.cacheDir) — the agent has no other writable surface, so this constraint blocks reads of arbitrary files via the upload destination. Requires a connected SSH/SFTP session. Uses streaming SFTP put (no in-memory buffer); use this for files larger than ~50 MiB until upload_file gains streaming support.

- `localPath` (string, required) — Absolute path to a file under context.cacheDir on the device.
- `profileId` (string, required) — Connected SSH/SFTP profile ID.
- `remotePath` (string, required) — Absolute destination path on the SFTP server.

</details>

<details markdown="1">
<summary><code>view_file</code> · no per-call prompt</summary>

Render a file from the ACTIVE proot guest to an INLINE image the agent can see directly — no desktop, X server, VNC client, or GPU needed (fully headless / GL-free). Handles .kicad_sch and .kicad_pcb (via kicad-cli), .pdf (first page, or `page`), .svg, and raster images (png/jpg/jpeg/webp/bmp/gif). The result is downscaled to maxWidth and returned as an image content block, so it works even when no VNC desktop is running. Prefer this over capture_desktop for looking at design output (schematics, PCBs, PDFs, plots).

- `path` (string, required) — Absolute path to the file inside the active proot guest (e.g. /root/proj/board.kicad_sch).
- `format` (string) — "png" (default, lossless) or "jpeg" (smaller over the tunnel).
- `maxWidth` (integer) — Downscale so the image is at most this many pixels wide. Default 1024 (clamped 160–4096).
- `page` (integer) — For multi-page PDFs, the 1-based page to render. Default 1.

</details>

<details markdown="1">
<summary><code>write_clipboard</code> · asks every call</summary>

Set the system clipboard's primary plain-text content. Replaces whatever's currently on the clipboard. Useful for priming the clipboard before triggering a terminal paste.

- `text` (string, required) — Text to place on the clipboard. Empty string is allowed and clears the clipboard's primary item.

</details>

<a id="sec-rclone"></a>

## Cloud storage (rclone) (15)

rclone remotes and the saved sync jobs that run between them.

<details markdown="1">
<summary><code>cancel_rclone_sync</code> · asks once per session</summary>

Cancel a running rclone job started by start_rclone_sync. If jobId is omitted, cancels the active job. No-op if the job is already finished or never existed.

- `jobId` (integer) — Job id to cancel. Optional — defaults to the active job.

</details>

<details markdown="1">
<summary><code>configure_rclone_remote</code> · asks every call</summary>

Create (or replace) a credentials-based rclone remote and verify it by listing the root. Pass remoteName, provider (ftp/sftp/webdav/s3/b2/mega/filen/…), and parameters — an option→value map (see list_rclone_provider_options; rclone obscures password fields server-side). For OAuth providers (drive/dropbox/…) use the in-app browser sign-in instead. Returns { created, verified, entryCount } or an error. Makes the remote usable by the rclone list/sync tools. (#181)

- `parameters` (object, required) — Option→value map for the provider (from list_rclone_provider_options), e.g. {"host":"…","user":"…","pass":"…","port":"2121"}. Password fields are obscured by rclone.
- `provider` (string, required) — rclone provider/type, e.g. 'ftp', 'sftp', 's3', 'filen'.
- `remoteName` (string, required) — Name for the remote in rclone.conf, e.g. 'myftp'. Replaces an existing remote of the same name.

</details>

<details markdown="1">
<summary><code>delete_rclone_remote</code> · asks every call</summary>

Delete an rclone remote from rclone.conf by name, and any RCLONE connection profile that references it. The inverse of configure_rclone_remote / the rclone-config import — use it to clean up a remote (e.g. a test or a failed/ghost import that left a remote with no usable profile). Returns { deleted, remoteName, removedProfiles }. deleted is false if no such remote existed (matching profiles are still removed).

- `remoteName` (string, required) — Remote name in rclone.conf (see list_rclone_remotes).

</details>

<details markdown="1">
<summary><code>delete_sync_profile</code> · asks every call</summary>

Delete a saved rclone sync configuration by id. The dialog's dropdown updates immediately. EVERY_CALL consent because it's destructive (the user's saved config is gone after).

- `id` (string, required) — Profile id from list_saved_sync_profiles.

</details>

<details markdown="1">
<summary><code>get_rclone_stats</code> · no per-call prompt</summary>

Return rclone's global transfer counters (bytes, totalBytes, speed, transfers, totalTransfers, errors, deletes, deletedDirs). These reset only when reset_rclone_stats is called or a new sync resets them at start.

</details>

<details markdown="1">
<summary><code>get_rclone_sync_status</code> · no per-call prompt</summary>

Poll the status of an async rclone job started by start_rclone_sync. Returns finished/success/error plus live transfer stats: bytes, totalBytes, speed, transfers, totalTransfers, errors, deletes, deletedDirs. If jobId is omitted, reports on the most recently started job (or returns active=false if none).

- `jobId` (integer) — Job id returned by start_rclone_sync. Optional — defaults to the active job.

</details>

<details markdown="1">
<summary><code>import_rclone_config</code> · asks every call</summary>

Import remotes from a Linux rclone.conf (the headless equivalent of the in-app Import rclone config dialog, #269). Pass configText (the file contents). Each chosen remote becomes an rclone remote (token/creds copied verbatim, non-interactively — OAuth remotes don't block on the browser flow) plus a matching RCLONE connection profile; a half-created remote is rolled back on failure so a failed import leaves no ghost. Skips typeless sections and names already configured. Optional `names` limits the import to those remote names. Returns { created, skipped, failed }. Returns an error if the config is password-encrypted.

- `configText` (string, required) — Contents of an rclone.conf to import.
- `names` (array) — Optional: only import remotes with these names. Omit to import all importable remotes.

</details>

<details markdown="1">
<summary><code>list_rclone_directory</code> · no per-call prompt</summary>

DEPRECATED: prefer list_directory(profileId=..., path=...). List files and subdirectories at a given path on an rclone remote. Returns name, isDir, size, mimeType, and modTime for each entry.

- `remote` (string, required) — Name of the rclone remote, e.g. 'gdrive'.
- `path` (string) — Path within the remote, relative to the remote root. Use '' for the root.

</details>

<details markdown="1">
<summary><code>list_rclone_provider_options</code> · no per-call prompt</summary>

List a credentials-based rclone provider's basic config fields — the non-advanced options needed to configure a non-OAuth remote (ftp, sftp, webdav, s3, b2, mega, filen, …). Each entry has name, help, required, isPassword, default, type. Feed the collected values into configure_rclone_remote's `parameters`. OAuth providers (drive, dropbox, onedrive, box, pcloud) are configured via the in-app browser sign-in, not this. (#181)

- `provider` (string, required) — rclone provider/type, e.g. 'ftp', 'sftp', 's3', 'filen'.

</details>

<details markdown="1">
<summary><code>list_rclone_remotes</code> · no per-call prompt</summary>

List rclone cloud storage remotes configured in Haven.

</details>

<details markdown="1">
<summary><code>list_saved_sync_profiles</code> · no per-call prompt</summary>

List the user's saved rclone sync configurations (#159) — the named src/dst/mode/filters bundles surfaced in the SFTP folder-sync dialog's dropdown. Returns id, name, srcFs, dstFs, mode (copy/sync/move), include/exclude patterns, optional minSize/maxSize/bandwidthLimit, createdAt, lastRunAt. Sorted most-recently-run first.

</details>

<details markdown="1">
<summary><code>reset_rclone_stats</code> · no per-call prompt</summary>

Reset rclone's global transfer counters to zero. Useful when running ad-hoc operations outside start_rclone_sync (which already resets on start).

</details>

<details markdown="1">
<summary><code>save_sync_profile</code> · asks every call</summary>

Create or update a named rclone sync configuration (#159). Pass an `id` to overwrite an existing one; omit it to create. mode accepts copy/sync/move (or "mirror" as a sync alias). includePatterns/excludePatterns are arrays of glob strings. Returns the saved profile's id and the full resolved fields. Mutates Haven state, gated by EVERY_CALL consent.

- `dstFs` (string, required) — Destination remote path.
- `mode` (string, required, one of: copy | sync | move) — copy/sync/move; "mirror" is accepted as a sync alias.
- `name` (string, required) — Display name shown in the dropdown.
- `srcFs` (string, required) — Source remote path, e.g. "gdrive:Backup/Photos".
- `bandwidthLimit` (string)
- `excludePatterns` (string[])
- `id` (string) — Existing profile id to overwrite. Omit to create a new one.
- `includePatterns` (string[])
- `maxSize` (string)
- `minSize` (string)

</details>

<details markdown="1">
<summary><code>start_rclone_sync</code> · asks every call</summary>

Start an async rclone transfer between two remote paths. mode=copy adds new/updated files (no deletes); mode=sync (a.k.a. "Mirror" in the UI) makes destination identical to source and deletes extras; mode=move copies then removes source files. srcFs/dstFs use rclone's remote-prefixed notation, e.g. "gdrive:Backup/Photos" or "gdrive:" for the remote root. Returns { jobId, mode } — poll get_rclone_sync_status to read finished/success and the transfer/delete counters. Honours the same optional filter and dryRun fields the SFTP sync dialog exposes.

- `dstFs` (string, required) — Destination remote path, e.g. "gdrive:Mirror/Photos".
- `mode` (string, required, one of: copy | sync | move) — copy = add/update only, sync = mirror (deletes extras in dst), move = copy then delete from src.
- `srcFs` (string, required) — Source remote path, e.g. "gdrive:Backup/Photos".
- `bandwidthLimit` (string) — Optional bandwidth limit, e.g. "10M".
- `dryRun` (boolean) — If true, simulate without writing.
- `excludePatterns` (string[]) — Optional exclude globs.
- `includePatterns` (string[]) — Optional include globs, e.g. ["*.jpg"].
- `maxSize` (string) — Optional maximum file size.
- `minSize` (string) — Optional minimum file size, e.g. "1K", "5M".

</details>

<details markdown="1">
<summary><code>update_rclone_remote</code> · asks every call</summary>

Update an existing rclone remote's config in place (rclone config/update) — unlike configure_rclone_remote, which replaces the whole remote. Pass remoteName and a parameters option→value map of just the fields to change (rclone obscures password fields). Returns { updated, remoteName }.

- `parameters` (object, required) — Option→value map of fields to change, e.g. {"host":"…"}.
- `remoteName` (string, required) — Existing remote name (see list_rclone_remotes).

</details>

<a id="sec-email"></a>

## Email (15)

Mailboxes, messages, drafts, and inbound Mail Rules automation.

<details markdown="1">
<summary><code>create_mail_folder</code> · asks every call</summary>

Create a new folder/label on a connected EMAIL profile (IMAP CREATE; on Gmail this is a new label). Pass profileId + name (use the server's hierarchy separator for nesting, e.g. "Work/2026"). Returns { created, folderId } — use folderId as a destination for modify_mail_message move/copy. Fails if it already exists. IMAP/Gmail only — Proton returns 501. Changes the mailbox — prompts for consent on every call.

- `name` (string, required) — New folder/label name (e.g. "Receipts" or "Work/2026").
- `profileId` (string, required) — EMAIL connection profile id.

</details>

<details markdown="1">
<summary><code>create_mail_rule</code> · asks every call</summary>

Create an inbound-email automation rule: when a message in folderId (default INBOX) of accountProfileId (omit = any connected email account) matches `criteria`, run the ordered `actions`. criteria = {combinator:"ALL"|"ANY", conditions:[{type, op, value}]} where type is from|to|subject|is_unread|body|has_attachment|attachment_name|attachment_mime|header and op is CONTAINS|EQUALS|REGEX|GLOB. actions = an ordered array of {type, …}: save_attachments{destProfileId,destDir,nameGlob?,mimeGlob?} | run_command{template,background?} | send_to_agent{messageTemplate,targetSessionId?} | notify{titleTemplate,bodyTemplate} | imap_filter{op: MARK_READ|MARK_UNREAD|SET_FLAGGED|UNSET_FLAGGED|MOVE|DELETE, destFolderId?} | forward{to[],template?} | invoke_mcp_tool{toolName,argsTemplateJson}. Templates may use {from} {fromName} {subject} {to} {uid}. Creating + enabling a rule is your standing authorization for its actions (they fire without a per-call prompt); destructive actions (move/delete/forward/run-command, or a non-NEVER MCP tool) are queued for foreground approval when Haven is backgrounded. Turn the master switch on with set_preference mail_automation_enabled=true.

- `actions` (array, required) — Ordered actions — see the tool description.
- `criteria` (object, required) — {combinator, conditions:[…]} — see the tool description.
- `name` (string, required) — Human label for the rule.
- `accountProfileId` (string) — EMAIL profile id to watch; omit for any connected email account.
- `enabled` (boolean) — Default true.
- `folderId` (string) — Folder to watch (default INBOX).
- `notifyOnFire` (boolean) — Raise a notification each time the rule fires.
- `orderIndex` (integer) — Evaluation order; lower runs first.
- `stopOnMatch` (boolean) — Stop evaluating later rules when this one matches.

</details>

<details markdown="1">
<summary><code>delete_mail_folder</code> · asks every call</summary>

Delete a folder/label on a connected EMAIL profile (IMAP DELETE). Pass profileId + folderId (from list_mail_folders). On Gmail this removes the LABEL — messages survive in All Mail; on a plain IMAP server it deletes the mailbox AND its messages (destructive). System folders (Inbox/Sent/Drafts/Trash/Spam/All Mail/…) are refused. Returns { deleted, folderId }. IMAP/Gmail only — Proton returns 501. Destructive — prompts for consent on every call.

- `folderId` (string, required) — Folder/label id to delete (from list_mail_folders). System folders are refused.
- `profileId` (string, required) — EMAIL connection profile id.

</details>

<details markdown="1">
<summary><code>delete_mail_rule</code> · asks once per session</summary>

Delete a Mail Rule by id (see list_mail_rules).

- `id` (string, required) — Rule id from list_mail_rules.

</details>

<details markdown="1">
<summary><code>get_mail_automation_status</code> · no per-call prompt</summary>

Mail-Rules automation status: master switch, rule counts, recent firings (the audit log), and destructive actions queued for foreground approval. Read-only.

</details>

<details markdown="1">
<summary><code>list_mail_folders</code> · no per-call prompt</summary>

List folders/labels for a connected EMAIL profile (IMAP/Gmail or Proton). Pass profileId (from list_connections). The profile must already be connected (connect_profile first). Returns each folder's id, name, type, and role (inbox/sent/trash/…). Read-only.

- `profileId` (string, required) — EMAIL connection profile id.

</details>

<details markdown="1">
<summary><code>list_mail_messages</code> · no per-call prompt</summary>

List message envelopes in a folder of a connected EMAIL profile. Pass profileId and folderId (default '0'/INBOX; see list_mail_folders). Returns id, subject, from, unread, time, numAttachments per message, newest first. Page with limit (default 100) + offset (skip from the newest end; offset = page*limit walks older). Read-only.

- `profileId` (string, required) — EMAIL connection profile id.
- `folderId` (string) — Folder/label id (default '0'/INBOX).
- `limit` (integer) — Max envelopes to return (default 100, 1..500).
- `offset` (integer) — Skip this many from the newest end (default 0) — page older with offset = page*limit.

</details>

<details markdown="1">
<summary><code>list_mail_rules</code> · no per-call prompt</summary>

List inbound-email automation rules (Mail Rules). Returns each rule's id, name, enabled, orderIndex, accountProfileId (null=any), folderId, criteria, actions, lastFiredAt. Read-only.

</details>

<details markdown="1">
<summary><code>modify_mail_message</code> · asks every call</summary>

Mutate one message on a connected EMAIL profile: mark read/unread, flag/unflag (star), move to another folder, copy/apply-a-label, or delete. Pass profileId + messageId (from list_mail_messages) + op (mark_read | mark_unread | flag | unflag | move | copy | delete). op=move and op=copy also require destFolderId (a folder id from list_mail_folders). IMAP/Gmail only — the Proton engine returns 501. On Gmail: move relabels (removes the source label, adds dest); copy is additive — it applies the dest label and KEEPS the message in its current folders (use copy to label without archiving from Inbox); delete moves to Trash. Returns { ok, op, messageId }. Side-effectful — prompts for consent on every call.

- `messageId` (string, required) — Message id from list_mail_messages.
- `op` (string, required) — mark_read | mark_unread | flag | unflag | move | copy | delete
- `profileId` (string, required) — EMAIL connection profile id.
- `destFolderId` (string) — Destination folder/label id (from list_mail_folders) — required when op=move or op=copy.

</details>

<details markdown="1">
<summary><code>poke_mail_watch</code> · no per-call prompt</summary>

Force a Mail-Rules poll cycle now instead of waiting for the periodic timer (for testing/immediacy). No-op when the master switch is off. Returns { poked }.

</details>

<details markdown="1">
<summary><code>read_mail_message</code> · no per-call prompt</summary>

Fetch one message from a connected EMAIL profile (IMAP/Gmail or Proton; Proton messages are decrypted), returning parsed headers (from, to[], cc[] — cc enables reply-all) and plain-text body (HTML is stripped; remote content is never loaded). Pass profileId and messageId (from list_mail_messages). Each attachment carries an { index, filename, mimeType, sizeBytes, isInline } — pass the index to save_mail_attachment to write its bytes to any connected filesystem. Read-only.

- `messageId` (string, required) — Message id from list_mail_messages.
- `profileId` (string, required) — EMAIL connection profile id.

</details>

<details markdown="1">
<summary><code>save_mail_attachment</code> · asks every call</summary>

Save one attachment from a message on a connected EMAIL profile to any connected filesystem (local, SFTP, SMB, rclone, Reticulum). Pass profileId + messageId + attachmentIndex (the index from read_mail_message), and the destination as destProfileId ("local" or any connected profile id) + destPath (a directory). Optional destFilename overrides the saved name. The file is named after the attachment (sanitised); a collision gets " (1)", " (2)", … Returns { saved, destProfileId, backend, destPath, filename, bytes }. Works for both IMAP and Proton. Writes a file — prompts for consent on every call.

- `attachmentIndex` (integer, required) — Attachment index from read_mail_message.
- `destPath` (string, required) — Destination directory on the chosen backend.
- `destProfileId` (string, required) — Destination backend profile id, or "local" for the device filesystem.
- `messageId` (string, required) — Message id from list_mail_messages.
- `profileId` (string, required) — Source EMAIL connection profile id.
- `destFilename` (string) — Optional name to save as (defaults to the attachment's own filename).

</details>

<details markdown="1">
<summary><code>save_mail_draft</code> · asks every call</summary>

Save a draft (NOT sent) to the account's Drafts folder on a connected EMAIL profile — use to compose a message for the user to review/send later. Same fields as send_mail (to/cc/bcc/subject/body, optional attachments, optional inReplyToMessageId to thread) but all are optional — a draft may be incomplete. Returns { saved, draftFolderId }. IMAP/Gmail only — Proton returns 501. Writes to the mailbox — prompts for consent on every call.

- `profileId` (string, required) — EMAIL connection profile id.
- `attachments` (object[]) — Optional files to attach, each { profileId, path }.
- `bcc` (string[]) — Optional Bcc addresses.
- `body` (string) — Plain-text body.
- `cc` (string[]) — Optional Cc addresses.
- `inReplyToMessageId` (string) — Optional: messageId this draft replies to — threads via In-Reply-To/References.
- `subject` (string) — Subject line.
- `to` (string[]) — Recipient addresses (optional for a draft).

</details>

<details markdown="1">
<summary><code>search_mail</code> · no per-call prompt</summary>

Server-side search of a folder on a connected EMAIL profile. Pass profileId, optional folderId (default INBOX; see list_mail_folders), and one or more criteria: from, to, subject, body (substring matches), unreadOnly (bool), sinceEpochSec / beforeEpochSec (Unix seconds, day granularity). Criteria are ANDed; at least one is required. Optional limit (default 100, 1..500). Returns the same envelope shape as list_mail_messages (newest first) — feed ids into read_mail_message / modify_mail_message. IMAP/Gmail only — Proton returns 501. Read-only.

- `profileId` (string, required) — EMAIL connection profile id.
- `beforeEpochSec` (integer) — On/before this Unix-seconds date (day granularity).
- `body` (string) — Match body text (substring).
- `folderId` (string) — Folder/label id to search (default INBOX).
- `from` (string) — Match sender address/name (substring).
- `limit` (integer) — Max results (default 100, 1..500).
- `sinceEpochSec` (integer) — On/after this Unix-seconds date (day granularity).
- `subject` (string) — Match subject (substring).
- `to` (string) — Match a To recipient (substring).
- `unreadOnly` (boolean) — Only unread (no \Seen) messages.

</details>

<details markdown="1">
<summary><code>send_mail</code> · asks every call</summary>

Send a plain-text email from a connected EMAIL profile. Pass profileId (from list_connections; connect_profile first), to (array of recipient addresses, at least one), subject, and body (plain text). Optional cc/bcc arrays. Optional attachments: an array of { profileId, path } files on any connected backend ("local" or a connected profile id) to attach. To reply in-thread, pass inReplyToMessageId (a messageId from list_mail_messages) — the engine sets In-Reply-To/References from that message so the reply threads (set your own "Re: …" subject). Returns { sent, messageId, appendedToSent }. IMAP/SMTP only — Proton send is not yet implemented and returns an error. Side-effectful: prompts for consent on every call and is recorded in the connection log.

- `body` (string, required) — Plain-text message body.
- `profileId` (string, required) — EMAIL connection profile id.
- `subject` (string, required) — Subject line.
- `to` (string[], required) — Recipient email addresses (at least one).
- `attachments` (object[]) — Optional files to attach, each { profileId, path } on a connected backend.
- `bcc` (string[]) — Optional Bcc addresses.
- `cc` (string[]) — Optional Cc addresses.
- `inReplyToMessageId` (string) — Optional: messageId (from list_mail_messages) this is a reply to — threads via In-Reply-To/References.

</details>

<a id="sec-linux"></a>

## Linux guest (proot) & desktops (46)

The on-device Linux distros, their desktop environments and windows, guest services, the audio bridge, and guest-file access.

<details markdown="1">
<summary><code>capture_desktop</code> · asks once per session</summary>

Capture a screenshot of a running desktop (deId) and return it INLINE as an image the agent can see directly — no second port or file download. Works for both X11/VNC desktops (via ImageMagick `import`) and nested-Wayland desktops — Sway / Hyprland / niri / cage (via `grim`, the wlroots screenshooter; auto-installed on first use). Whole screen by default; a single window via windowId (from list_desktop_windows) is X11/VNC only — nested-Wayland captures the whole output. The image is downscaled to maxWidth and JPEG-encoded by default to stay cheap over the MCP tunnel. Captures inside the guest, so it works even when the user isn't on the VNC tab. Returns the image plus { deId, width, height, format, source, windowId?, windowTitle? }.

- `deId` (string, required) — Desktop environment id (e.g. "xfce4") of a RUNNING X11/VNC desktop.
- `format` (string) — "jpeg" (default, smaller) or "png" (lossless, larger).
- `maxWidth` (integer) — Downscale so the image is at most this many pixels wide. Default 1024 (clamped 160–4096).
- `windowId` (string) — Optional X11 window id from list_desktop_windows. Captures just that window (cropped to its geometry). Omit for the whole screen.

</details>

<details markdown="1">
<summary><code>capture_desktop_tab</code> · asks once per session</summary>

Capture what a remote-desktop VIEWER tab (RDP, VNC, or SPICE) is actually rendering, INLINE as an image — the framebuffer the user sees, with the server cursor composited on top at the tracked pointer position. This is distinct from capture_desktop, which screenshots an in-guest X11/VNC desktop; this one captures the RDP/VNC/SPICE client viewer (e.g. to verify colours and the cursor against a remote Windows/Linux server). Pass profileId to pick a tab (from list_desktop_sessions); omit it when exactly one desktop tab is open. Returns the image plus { profileId, protocol, width, height, hasCursor, cursorWidth?, cursorHeight?, hotspotX?, hotspotY?, pointerX?, pointerY?, format }.

- `format` (string) — "jpeg" (default, smaller) or "png" (lossless, larger).
- `maxWidth` (integer) — Downscale so the image is at most this many pixels wide. Default 1280 (clamped 160–4096).
- `profileId` (string) — Profile id of the desktop tab (from list_desktop_sessions). Omit when exactly one tab is open.

</details>

<details markdown="1">
<summary><code>delete_distro</code> · asks every call</summary>

Wipe a distro's rootfs and remove all installed DEs on it. Stops any running DEs first. Destructive — frees the disk space and is also the recovery path when an install lands in a broken state.

- `distroId` (string, required) — Distro id to delete.

</details>

<details markdown="1">
<summary><code>delete_system_vm_image</code> · asks every call</summary>

Delete a stored system-VM image (#326) — removes its qcow2 plus its label/arch sidecars. Stops the VM first if it is the one currently running.

- `imageId` (string, required) — Image id to delete (from list_system_vm_images).

</details>

<details markdown="1">
<summary><code>detach_from_guest</code> · no per-call prompt</summary>

Stop the haven-usb guest proxy started by usb_attach_to_guest and release the brokered USB device handle (the guest's /dev/pts serial bridge or LD_PRELOAD HID routing stops working immediately). Pass keepOpen:true to leave the device handle open. The teardown counterpart to usb_attach_to_guest.

- `keepOpen` (boolean) — Leave the brokered device handle open (default false = fully release it).

</details>

<details markdown="1">
<summary><code>get_audio_bridge_status</code> · no per-call prompt</summary>

Proot audio bridge status: state (STOPPED/STARTING/RUNNING/ERROR), loopback PCM port, bytesStreamed so far, and any error. Read-only — a quick way to confirm guest audio is actually flowing.

</details>

<details markdown="1">
<summary><code>get_custom_binds</code> · no per-call prompt</summary>

List the user-defined extra proot bind mounts (#301) for a distro — the per-distro custom Android→guest mounts added on top of the fixed system binds. Pass distroId; omit to use the active distro. Returns each bind as {host, guest} plus its proot spec.

- `distroId` (string) — Distro id; omit for the active distro.

</details>

<details markdown="1">
<summary><code>get_proot_install_log</code> · no per-call prompt</summary>

Return install-log events from the Room-backed ProotInstallLog table. Survives logcat rotation and app restarts. Filter by distroId and/or sinceMs (millis since epoch) to poll incrementally. Each event: id, timestamp, distroId, phase, deId?, exit?, ok, message?, logTail?.

- `distroId` (string) — Filter to a single distro. Omit for all distros.
- `limit` (integer) — Max events. Default 100.
- `sinceMs` (integer) — Return only events with timestamp > sinceMs. Default 0 (return all).

</details>

<details markdown="1">
<summary><code>gl_smoke_test</code> · asks once per session</summary>

Launch a GL app into a RUNNING desktop on the GPU PATH (venus/virpipe — NOT the llvmpipe software fallback that launch_app_in_desktop forces), screenshot it, and heuristically report whether the frame is non-blank. A regression check for the windowed-GL-present pipeline (a blank/white frame = GL didn't present). The verdict is reliable only for a FULL-FRAME GL app (a fullscreen / cage-kiosk GL test app like 'glxgears' or 'es2gears'); for a windowed app the 2D chrome masks a blank 3D pane, so rely on the returned image. Optionally writes the gpu_use_venus pref first. Returns the screenshot plus { passed, distinctColors, topColorFraction, gpuPath, windowId? }. Detects non-blank, not correctness.

- `command` (string, required) — GL app to launch, e.g. 'glxgears' or 'es2gears'.
- `deId` (string, required) — Desktop environment id of a RUNNING desktop (prefer a fullscreen/kiosk GL surface for a clean verdict).
- `gpuUseVenus` (boolean) — If set, write the gpu_use_venus pref before launching (true = venus+zink, false = virpipe). Omit to leave it unchanged.
- `maxWidth` (integer) — Downscale the returned image to at most this width. Default 1024 (clamped 160..4096).
- `timeoutMs` (integer) — Max ms to wait for the app window before capturing. Default 12000, clamped 0..60000.

</details>

<details markdown="1">
<summary><code>import_distro</code> · asks every call</summary>

Import a custom rootfs tarball as a new distro (#284) — "bring your own rootfs". The tarball (http(s) URL or an on-device file path) is extracted to its own rootfs and registered as a first-class distro, so it then appears in list_distros / set_active_distro / install_desktop exactly like a built-in. Raw mode: no baseline packages and no distro hooks run — the rootfs is used as shipped; `family` only routes later package installs (apk/apt/pacman/xbps). Use this for proot-distro / Docker-export tarballs and for a SECOND instance of a distro you already have (give it a new id — that is how #302 multiple-instances is done). Returns immediately; poll inspect_proot.osSetupState (Downloading → Extracting → Ready/Error). Supported compression: .tar.gz and .tar.xz (zstd not yet supported — recompress first).

- `family` (string, required) — Package family for later installs: APK | APT | PACMAN | XBPS | NIX.
- `id` (string, required) — New distro id (slug: lowercase letters/digits/.-_, e.g. "ubuntu-trixie" or "debian-test2"). Must not collide with a built-in or existing custom id.
- `source` (string, required) — Rootfs tarball: an http(s):// URL, or an absolute on-device file path.
- `format` (string) — Optional compression: TAR_GZ | TAR_XZ. Auto-detected from the source extension if omitted.
- `label` (string) — Human label shown in the picker (e.g. "Ubuntu 26.04 (imported)").
- `sha256` (string) — Optional SHA-256 to verify the download against. Skipped if omitted.
- `stripComponents` (integer) — Optional leading path components to strip (proot-distro tarballs wrap in one dir → 1). Defaults to auto: tries 0, retries 1 if no bin/sh is found.

</details>

<details markdown="1">
<summary><code>import_system_vm_image</code> · asks every call</summary>

Import a bootable disk image as a system VM (#326). `source` is an http(s) URL or an on-device file path; it is downloaded/copied then normalised to qcow2 via `qemu-img convert` (raw/qcow2/vdi/vmdk in, qcow2 out) under the app cache. Provide a `label`; `id` defaults to a slug of the label. Optional `sha256` is verified against the SOURCE bytes. `arch` records which CPU the image is for — a qcow2 does not say, and booting an arm64 rootfs on the x86_64 target just hangs — and decides which qemu target is installed (Debian: qemu-system-arm for aarch64). Installs qemu in the active distro on first use (needs a VNC-capable qemu — Debian's has it, Alpine's does not). Synchronous: returns { id, label, sizeBytes, arch } when the converted image is ready. Then boot it with start_system_vm.

- `label` (string, required) — Human-readable name for the image (e.g. "Debian 12").
- `source` (string, required) — http(s) URL or on-device file path to a bootable disk image (.qcow2/.img/.iso/.vdi/.vmdk).
- `arch` (string, one of: x86_64 | aarch64) — Guest CPU architecture of the image. Default x86_64.
- `id` (string) — Image id slug (lowercase letters, digits, . _ -). Defaults to a slug of the label.
- `sha256` (string) — Optional SHA-256 of the source bytes to verify after download.

</details>

<details markdown="1">
<summary><code>inspect_proot</code> · no per-call prompt</summary>

Single rich read of the proot subsystem: active distro id, every Distro (id, label, family, installed, sizeMb, bytesOnDisk, postExtractHookIds, and installedDesktops — the desktop ids installed on THAT distro's rootfs, so the cross-distro picture is visible without switching active distro), every DesktopEnvironment with per-family Stable/Experimental/Broken compatibility and Experimental notes, current osSetupState (phase / step / progress / errorPhase / errorMessage / errorTail), current desktopSetupState (phase / errorMessage / errorTail), and the last 50 install-log events. The single endpoint to drive issue #162 verification.

- `eventLimit` (integer) — Max install-log events to return. Default 50.

</details>

<details markdown="1">
<summary><code>install_app_pack</code> · asks once per session</summary>

Install a curated guest-app pack (from list_app_packs) into the ACTIVE distro: package install via the distro's package manager, idempotent config drops, optional pinned-asset downloads, then — app-side — a verify-binary check, app-window def registration (so the app appears in Installed Apps / present_app-launchable), and audio-bridge start if the pack needs it. Long-running (a package install): returns { jobId, status: "running" } immediately; poll by calling again with that jobId — the response carries accumulated output and, once finished, the exitCode (0 = installed AND registered; non-zero = a phase failed, see output tail). Requires an installed active distro whose family the pack supports.

- `id` (string) — Pack id from list_app_packs (e.g. "qmmp"). Required unless polling via jobId.
- `includeAssets` (boolean) — Also fetch the pack's optional pinned assets (skins, sample content). Default true.
- `jobId` (string) — Poll a previously started install job. When set, `id` is ignored.

</details>

<details markdown="1">
<summary><code>install_desktop</code> · asks every call</summary>

Install a desktop environment on the active distro. Calls ProotManager.setupDesktop which downloads packages, configures VNC, and writes the launcher. Poll `inspect_proot.desktopSetupState` for progress. Failures are attributed to a DePhase (Packages / VncConfig / Marker) in both the state and the install log.

- `deId` (string, required) — Desktop environment id (e.g. "xfce4", "openbox", "labwc-native").
- `vncPassword` (string) — VNC password. Defaults to empty (SecurityTypes None).

</details>

<details markdown="1">
<summary><code>install_distro</code> · asks every call</summary>

Set the given distro as active and trigger installRootfs(). Returns immediately; poll `inspect_proot.osSetupState` for progress (Downloading → Extracting → BootstrapHook → Baseline → Ready, or Error with attribution). Idempotent: if the distro is already installed, just switches active.

- `distroId` (string, required) — Distro id from DistroCatalog (e.g. "alpine-3.21", "debian-trixie", "debian-bookworm", "ubuntu-noble", "archlinux", "void").

</details>

<details markdown="1">
<summary><code>launch_app_in_desktop</code> · asks once per session</summary>

Launch a GUI application into a RUNNING desktop (deId). X11/VNC desktops get DISPLAY/XAUTHORITY; nested-Wayland desktops (Sway/Hyprland/niri/cage) get XDG_RUNTIME_DIR/WAYLAND_DISPLAY. The software-GL fallback (LIBGL_ALWAYS_SOFTWARE=1, GALLIUM_DRIVER=llvmpipe) is exported either way, so GPU-less GL apps like KiCad/eeschema don't crash their canvas. Optionally waits for the app's window to appear and returns its windowId — pass that to capture_desktop to screenshot just that window (window-wait/windowId need enumeration: X11 and Sway; on other nested-Wayland compositors the app still launches but no windowId is returned). The app keeps running after this returns. For looking at saved design FILES prefer view_file (headless, no desktop needed); use this when you need the live interactive app.

- `command` (string, required) — Shell command to launch, e.g. 'eeschema /root/proj/board.kicad_sch'.
- `deId` (string, required) — Desktop environment id (e.g. "xfce4") of a RUNNING X11/VNC desktop.
- `timeoutMs` (integer) — Max ms to wait for the window. Default 15000, clamped 0..60000. 0 = launch and return without waiting.
- `waitForWindowTitle` (string) — If set, poll until a window whose title contains this substring (case-insensitive) appears; otherwise return the first new window seen.

</details>

<details markdown="1">
<summary><code>list_app_packs</code> · no per-call prompt</summary>

List the curated guest-app installer packs (#470) — one-tap recipes that collate everything a working cage/app-window app needs: guest packages per package family, config drops that fix silent-failure defaults (e.g. qmmp's ALSA-in-proot output), the audio-bridge requirement, the app-window def to register, and optional sha256-pinned assets (e.g. Winamp skins). Each entry reports whether it's compatible with the ACTIVE distro's family, whether it looks installed there (verify binary present), whether its app window is already registered, and which families are device-verified. Read-only; install with install_app_pack.

</details>

<details markdown="1">
<summary><code>list_desktop_environments</code> · no per-call prompt</summary>

Slim DE-only read of inspect_proot. Filters to DEs that have a package list for the active distro's family (matches the UI filter). Each entry includes per-family compatibility (Stable/Experimental/Broken), an Experimental note when relevant, installed?, and running? state.

</details>

<details markdown="1">
<summary><code>list_desktop_sessions</code> · no per-call prompt</summary>

List open remote-desktop tabs (VNC/RDP/SPICE) by connection profile, with their live status (connecting, connected, disconnected, error). These are Desktop-screen tabs, not transport sessions — a VNC/RDP/SPICE-over-SSH desktop has its SSH tunnel in list_sessions and its own connect state here. 'disconnected' means the tab is still open but its session ended (server logoff / transport death) — reconnect with connect_profile, which replaces the dead tab. Use after connect_profile to confirm a desktop reached 'connected', and after disconnect_profile to confirm the tab is gone (profile absent from the list).

</details>

<details markdown="1">
<summary><code>list_desktop_windows</code> · asks once per session</summary>

Enumerate the visible top-level windows on a running desktop (deId), so an agent can target a specific application window (e.g. KiCad's schematic editor vs. PCB editor) before capturing it. Returns { deId, count, windows:[{id,title,x,y,width,height}] }. Works on X11/VNC desktops (via xdotool) and Sway nested-Wayland desktops (via swaymsg get_tree); other nested-Wayland compositors (Hyprland/niri/cage) aren't enumerable yet — use capture_desktop for a whole-output screenshot there. Installs the X11 capture toolset (xdotool + ImageMagick) on first use.

- `deId` (string, required) — Desktop environment id (e.g. "xfce4") of a RUNNING X11/VNC desktop.

</details>

<details markdown="1">
<summary><code>list_distros</code> · no per-call prompt</summary>

Slim distro-only read of inspect_proot. Returns each Distro with installed/active/sizeMb/family. Use this when you only need the catalog and not the live state or log events.

</details>

<details markdown="1">
<summary><code>list_guest_apps</code> · no per-call prompt</summary>

List the GUI applications installed in the active proot guest, discovered from its `.desktop` files (the same source an xfce4 application menu reads). Use this to find an app to launch with `present_app` without knowing its exact command. Returns { count, iconsResolved, apps:[{ name, exec, hasIcon, categories }] } sorted by name; `exec` is the runnable guest command (field codes stripped) you pass straight to present_app's `command`. `hasIcon` indicates whether a decodable icon was resolved (icons themselves stay on-device for the launcher UI). Skips NoDisplay/Terminal/non-application entries.

</details>

<details markdown="1">
<summary><code>list_guest_services</code> · no per-call prompt</summary>

List guest services registered on the active distro with their live state (STOPPED/STARTING/RUNNING/ERROR), command, port, autostart flag, and last error/output tail. Read-only.

</details>

<details markdown="1">
<summary><code>list_system_vm_images</code> · no per-call prompt</summary>

List the stored system-VM disk images (#326) and the current VM's state. A system VM is a full QEMU Linux VM (x86_64 or aarch64) booted inside the active proot and viewed over VNC on loopback — distinct from a desktop environment (list_desktop_environments) and the USB-drive appliance (#287). Returns { vm: { status, vncPort, arch, accel, accelReason }, host: { arch, kvm: { usable, detail } }, count, images:[{ id, label, sizeBytes, arch }] }. `status` is one of stopped/starting/running/error; when running, `vncPort` is the loopback port a VNC connection points at (create_connection type=VNC host=127.0.0.1 vncPort=<port>). `host.kvm` is a live probe of /dev/kvm — on retail arm64 phones it is absent because the vendor hypervisor owns EL2, so guests run under TCG emulation. Images are qcow2, normalised on import.

</details>

<details markdown="1">
<summary><code>open_desktop_terminal</code> · no per-call prompt</summary>

Open an interactive local PRoot shell whose environment JOINS a RUNNING desktop (deId) — exports DISPLAY (X11/VNC) or WAYLAND_DISPLAY + XDG_RUNTIME_DIR (nested-Wayland / native labwc) — so you can drive the desktop's apps from the command line (e.g. launch/inspect GUI programs in the same session a user is viewing over VNC). Returns a sessionId usable with send_terminal_input / read_terminal_scrollback, plus the resolved display/waylandDisplay/xdgRuntimeDir. Always a fresh session (a reused plain shell would lack the display env). The desktop must already be RUNNING (start_desktop). Unlike launch_app_in_desktop (fire-and-forget single app), this gives you an interactive shell.

- `deId` (string, required) — Desktop environment id of a RUNNING desktop (e.g. "openbox", "xfce4", "sway").
- `plain` (boolean) — Skip the user's sessionManager preference and exec a bare login shell. Default false.

</details>

<details markdown="1">
<summary><code>read_app_window_log</code> · no per-call prompt</summary>

Read the captured output log of a present_app cage window. The cage redirects BOTH the sway compositor AND the GUI app it runs (stdout+stderr merged) into one log, so this is how the agent SEES a present_app app's own output — startup errors, GL/Mesa diagnostics, a crash trace — without wrapping the command in a logging script. Pass the sessionId returned by present_app for a live window; OMIT it to read the most-recent app-window log, which still works after the app crashed or exited (the session is gone but the log survives on disk). Returns { sessionId?, display, bytes, truncated, log }. For a GUI app that came up then died (a grey/blank or vanished window), this is the first thing to read.

- `maxBytes` (integer) — Return at most the last N bytes of the log. Default 16384, clamped 256..262144.
- `sessionId` (string) — present_app sessionId for a live window. Omit to read the newest app-window log (survives a crashed/exited app).

</details>

<details markdown="1">
<summary><code>read_desktop_log</code> · no per-call prompt</summary>

Read a running (or just-failed) desktop's RUNTIME logs — distinct from inspect_proot, which only covers install state. For nested-Wayland DEs (Sway / Hyprland / niri) returns the compositor's own stdout/stderr (compositor.log: the wlr/[ERROR] lines, output-enable, buffer-allocation failures) plus Haven's captured launch-process output (the `[haven]` progress markers + wayvnc lines). This is the diagnostic for grey-screen / no-frames / compositor-refuses-to-start issues — the data that otherwise requires opening a proot shell. Pass deId to target one DE; omit for all running desktops.

- `deId` (string) — Desktop environment id (e.g. "sway"). Omit for all running desktops.
- `maxChars` (integer) — Cap compositor.log to its last N chars. Default 4000.

</details>

<details markdown="1">
<summary><code>read_guest_file</code> · no per-call prompt</summary>

Read a small file from the ACTIVE proot guest and return its contents to the agent (UTF-8 text; set asBase64 only for small binary). The reliable agent⇄guest text channel. Reads are capped to maxBytes. For large or binary files prefer serve_file (streams over a loopback URL — no base64 through the agent); for images/PDFs/schematics use view_file (renders to an inline picture); for anything the USER should see use present_media.

- `path` (string, required) — Absolute path to the file inside the active proot guest.
- `asBase64` (boolean) — Return base64 instead of UTF-8 text — only for small binary files; prefer serve_file for larger binaries. Default false.
- `maxBytes` (integer) — Read at most this many bytes. Default 262144 (256 KiB), clamped 1..8388608.

</details>

<details markdown="1">
<summary><code>register_guest_service</code> · asks once per session</summary>

Register a long-lived helper process to run inside the ACTIVE distro's proot guest — typically an app-native MCP server (KiCad/FreeCAD/OpenSCAD) the agent drives for structured control. Haven supervises it: starts it (if autostart) when the MCP endpoint comes up, re-launches it after an app restart, and — when an MCP reverse-tunnel endpoint is configured — multiplexes its loopback `port` back to the remote MCP client alongside Haven's own endpoint (no adb forward needed). The registry is persisted per-distro. Returns the generated service id. Use start_guest_service to launch it now.

- `command` (string, required) — Shell command run via /bin/sh -lc in the guest, e.g. 'cd /root/kicad-mcp && UV_LINK_MODE=copy uv run python http_server.py'. Should run in the foreground (Haven owns the process).
- `label` (string, required) — Human-readable name, e.g. "KiCad MCP".
- `port` (integer, required) — Loopback TCP port the service listens on inside the guest (e.g. 8766). Multiplexed over the MCP reverse tunnel.
- `autostart` (boolean) — Re-launch automatically when Haven's MCP endpoint comes up / after app restart. Default true.
- `isMcp` (boolean) — True if this service is itself a streamable-HTTP MCP server. Haven then aggregates its tools into its own MCP surface, namespaced 'guest_<id>_<tool>', so you call them through Haven directly. Default false.
- `mcpPath` (string) — HTTP path of the guest MCP endpoint when isMcp=true. Default '/mcp'.

</details>

<details markdown="1">
<summary><code>run_in_proot</code> · asks once per session</summary>

Run a shell command inside the ACTIVE distro's proot guest (the same rootfs the running desktop uses) and return its combined stdout+stderr. Distro-agnostic: invokes /bin/sh -lc in whatever distro is active (check inspect_proot.activeDistroId), so it works on Debian/Arch/Void, not just Alpine like open_local_shell. For long jobs (apt-get install, pip install) you can pass background:true to get a jobId immediately, then poll by calling again with that jobId — the response carries the accumulated output and, once finished, the exitCode. Even without background, a synchronous call that runs longer than ~30s is auto-backgrounded: it returns {jobId, status:"running", note, output:<partial>} instead of blocking past the MCP request timeout, and you poll it the same way. A quick command returns inline ({exitCode, output}). This is the agent's headless way to provision the guest (install packages, run kicad-cli ERC/DRC, etc.).

- `background` (boolean) — If true, start the command and return a jobId immediately instead of blocking. Poll with that jobId. Default false.
- `command` (string) — Shell command to run via /bin/sh -lc in the active proot. Required unless polling via jobId.
- `jobId` (string) — Poll a previously started background job. When set, `command` is ignored and the current status + accumulated output are returned.
- `maxOutputChars` (integer) — Cap the returned output to its last N chars. Default 8000.

</details>

<details markdown="1">
<summary><code>scroll_desktop_tab</code> · asks once per session</summary>

Scroll a remote-desktop VIEWER tab (RDP/VNC/SPICE) by injecting mouse-wheel notches into the remote server. deltaY > 0 scrolls down, < 0 scrolls up; magnitude is the number of notches. Pass profileId to pick a tab (from list_desktop_sessions); omit when exactly one is open. Returns { profileId, protocol, deltaY }.

- `deltaY` (integer, required) — Wheel notches: >0 scrolls down, <0 scrolls up.
- `profileId` (string) — Profile id (from list_desktop_sessions). Omit when exactly one is open.

</details>

<details markdown="1">
<summary><code>send_desktop_clipboard</code> · asks once per session</summary>

Set the clipboard on a remote-desktop VIEWER tab (RDP/VNC) to the given text, so it can be pasted inside the remote server (Ctrl+V / right-click paste). This is the closest substitute for typing while keyboard injection is unsupported. Pass profileId to pick a tab (from list_desktop_sessions); omit when exactly one is open. Returns { profileId, protocol, chars }.

- `text` (string, required) — Text to place on the remote clipboard.
- `profileId` (string) — Profile id (from list_desktop_sessions). Omit when exactly one is open.

</details>

<details markdown="1">
<summary><code>set_active_distro</code> · no per-call prompt</summary>

Switch the active proot distro WITHOUT installing anything — the lightweight counterpart to install_distro (which downloads). The active distro is the rootfs that run_in_proot, install_desktop, start_desktop and the desktop/USB tools all operate on, so this is how you drive cross-distro work over MCP (e.g. run_in_proot inside Void instead of the current active distro). The distro must already be installed — call list_distros for installed ids, or install_distro to add one. Returns the new active distro id, its family, and the desktops installed on it.

- `distroId` (string, required) — Installed distro id to make active (e.g. "void", "archlinux", "alpine-3.21"). See list_distros.

</details>

<details markdown="1">
<summary><code>set_custom_binds</code> · asks every call</summary>

Replace the user-defined extra proot bind mounts (#301) for a distro — exposes arbitrary Android paths inside that distro's guest (interactive shell, desktop, and run_in_proot all pick them up). proot binds are read-write. Pass distroId (omit for active) and `binds`, an array of {host, guest?} objects (guest blank = same path as host). This REPLACES the whole list; pass [] to clear. Takes effect on the NEXT session/command, not already-running ones.

- `binds` (object[], required) — Full replacement list. Each item: {host: "/abs/android/path", guest: "/abs/guest/path" (optional)}.
- `distroId` (string) — Distro id; omit for the active distro.

</details>

<details markdown="1">
<summary><code>start_audio_bridge</code> · asks once per session</summary>

Start the proot audio bridge (#257): launches a PulseAudio daemon in the active distro and plays its output through the Android speaker — output only, no mic. Guest apps reach it via PULSE_SERVER (written to /etc/profile.d/pulse.sh and exported into desktop sessions), so apps launched from a login shell / desktop get sound. Installs pulseaudio on first use. Idempotent. Returns { state, port }.

</details>

<details markdown="1">
<summary><code>start_desktop</code> · asks every call</summary>

Start an installed desktop environment on the active distro. Calls DesktopManager.startDesktop; the launch is asynchronous. Returns the allocated display + vncPort so callers can connect a VNC client. Poll `inspect_proot.desktopEnvironments[].running` (or list_desktop_environments) to confirm RUNNING state. NestedWayland DEs (Sway, Hyprland, niri) bring up a wlroots/smithay compositor on the headless backend inside the rootfs and expose it via wayvnc on the returned port; X11Vnc DEs spawn Xvnc + the desktop; NativeCompositor runs the JNI labwc bridge.

- `deId` (string, required) — Desktop environment id to start.

</details>

<details markdown="1">
<summary><code>start_guest_service</code> · asks once per session</summary>

Start a registered guest service by id (no-op if already running). Returns its state.

- `id` (string, required) — Service id from register_guest_service / list_guest_services.

</details>

<details markdown="1">
<summary><code>start_system_vm</code> · asks every call</summary>

Boot a stored system-VM image (#326) with a VNC display on a free loopback port, and wait for the VNC server to bind (up to ~20s; a timeout means this distro's qemu has no VNC — try a Debian image/distro). The image's recorded arch picks the machine: x86_64 gets `-M pc -vga std`, aarch64 gets `-M virt` with UEFI firmware, virtio-gpu and USB HID (installing the distro's edk2 package if needed). One VM at a time — call stop_system_vm first to replace a running one. Does NOT open a viewer (MCP has no UI) — returns { imageId, status, arch, accel, accelReason, vncHost, vncPort, hint } so you connect it yourself: create_connection type=VNC host=127.0.0.1 vncPort=<vncPort>, then connect_profile. `accel` is the resolved reality, not a request: KVM needs a usable /dev/kvm AND a guest arch matching the host, which retail arm64 phones cannot offer, so expect TCG and a slow (~2 min) but usable boot. `accel=kvm` fails fast when it is impossible rather than booting slowly; `accel=auto` (default) falls back to TCG if a KVM boot does not come up.

- `imageId` (string, required) — Image id from list_system_vm_images.
- `accel` (string, one of: auto | kvm | tcg) — auto = KVM when genuinely possible else TCG (default); kvm = insist, erroring out if impossible; tcg = force emulation.
- `cpus` (integer) — Guest vCPUs. Default 2.
- `memMb` (integer) — Guest RAM in MiB. Default 2048.

</details>

<details markdown="1">
<summary><code>stop_audio_bridge</code> · no per-call prompt</summary>

Stop the proot audio bridge: kills the in-guest PulseAudio daemon and releases the Android AudioTrack.

</details>

<details markdown="1">
<summary><code>stop_desktop</code> · asks every call</summary>

Stop a running desktop environment. Tears down the compositor / Xvnc process tree and releases the display number.

- `deId` (string, required) — Desktop environment id to stop.

</details>

<details markdown="1">
<summary><code>stop_guest_service</code> · no per-call prompt</summary>

Stop a running guest service by id (leaves it registered).

- `id` (string, required) — Service id.

</details>

<details markdown="1">
<summary><code>stop_system_vm</code> · asks every call</summary>

Power off / kill the running system VM (#326) and release its loopback VNC port. Idempotent — a no-op if none is running. Kills the whole qemu process tree inside the proot.

</details>

<details markdown="1">
<summary><code>tap_desktop_tab</code> · asks every call</summary>

Click a point on a remote-desktop VIEWER tab (RDP/VNC/SPICE) — inject a mouse click into the remote server. Coordinates are in the REMOTE framebuffer's pixel space (the same space capture_desktop_tab reports: 0..width, 0..height), NOT Haven's own UI (that's tap_haven_ui). Pass profileId to pick a tab (from list_desktop_sessions); omit when exactly one desktop tab is open. Buttons follow X11: 1=left (default), 2=middle, 3=right. Keyboard typing is not yet supported (the session abstraction has no key verb). Returns { profileId, protocol, x, y, button }.

- `x` (integer, required) — Remote framebuffer X (0..width from capture_desktop_tab).
- `y` (integer, required) — Remote framebuffer Y (0..height from capture_desktop_tab).
- `button` (integer) — X11 button: 1=left (default), 2=middle, 3=right.
- `profileId` (string) — Profile id of the desktop tab (from list_desktop_sessions). Omit when exactly one is open.

</details>

<details markdown="1">
<summary><code>uninstall_desktop</code> · asks every call</summary>

Remove a desktop environment from the active distro. Stops it first if running. Calls ProotManager.uninstallDesktop.

- `deId` (string, required) — Desktop environment id to uninstall.

</details>

<details markdown="1">
<summary><code>unregister_guest_service</code> · no per-call prompt</summary>

Stop (if running) and remove a guest service from the registry by id.

- `id` (string, required) — Service id.

</details>

<details markdown="1">
<summary><code>write_guest_file</code> · asks once per session</summary>

Write a file into the ACTIVE proot guest. Supply `content` (UTF-8 text); for binary prefer upload_file (stages a device-cache file into the guest) over `contentBase64`. Parent directories are created by default. The reliable way to push agent-authored text files (scripts, generators, configs) into the guest without a terminal heredoc. For large files, send in ordered chunks: first chunk {append:false, final:false}, middle chunks {append:true, final:false}, last chunk {append:true, final:true} — the file lands in the guest only on the final chunk.

- `path` (string, required) — Absolute destination path inside the active proot guest.
- `append` (boolean) — Append this chunk to the buffered bytes for this path instead of starting fresh. Default false (truncate). Use true for chunks after the first when sending a large file in pieces.
- `content` (string) — UTF-8 text to write. Mutually exclusive with contentBase64.
- `contentBase64` (string) — Base64-encoded bytes for small binary writes; prefer upload_file for larger/binary files. Mutually exclusive with content.
- `final` (boolean) — Copy the buffered bytes into the guest now. Default true (single-call write). Set false on every chunk except the last; the file isn't written into the guest until final=true.
- `mkdirs` (boolean) — Create parent directories if missing. Default true.

</details>

<a id="sec-networking"></a>

## Networking — tunnels & port forwarding (14)

SSH tunnels, port forwards, the serial↔TCP bridge, and the port-knock / single-packet-auth gates.

<details markdown="1">
<summary><code>add_port_forward</code> · asks once per session</summary>

Save a port-forward rule on an SSH profile. If the profile is currently connected the rule is also activated immediately. Type LOCAL=`-L` (local→remote), REMOTE=`-R` (remote→local), DYNAMIC=`-D` (SOCKS5 proxy server). Returns the saved rule's id and (when activated) the actually-bound port; activated:false with an error means the bind failed on the live session (e.g. port held in TIME_WAIT by a just-closed connection) — the rule is still saved and applies on the next connect.

- `bindPort` (integer, required) — Bind port. 0 = OS picks (REMOTE only).
- `profileId` (string, required) — Owning profile ID.
- `type` (string, required) — LOCAL | REMOTE | DYNAMIC.
- `bindAddress` (string) — Bind address. Default 127.0.0.1.
- `targetHost` (string) — Target host (LOCAL/REMOTE only). Ignored for DYNAMIC.
- `targetPort` (integer) — Target port (LOCAL/REMOTE only). Ignored for DYNAMIC.

</details>

<details markdown="1">
<summary><code>bridge_serial_to_tcp</code> · asks once per session</summary>

Expose a live serial terminal session (BTSERIAL / BLESERIAL / USBSERIAL — sessionId from list_sessions) as a raw-byte TCP server on 127.0.0.1, and return the bound port. Feed that port to add_port_forward (a remote-forward) or a tunnel to reach the device off-phone — this is how a serial device joins Haven's routing fabric. The terminal tab keeps working; the bridge taps the same stream. Bytes are raw with no framing: every connected client sees the device output and any client can write back to it. Pass an explicit `port`, or omit for an OS-assigned free port. Idempotent — a second call for the same session returns the existing port. Open the device's terminal first (connect_profile / a serial tab); a session with no open terminal has nothing to bridge.

- `sessionId` (string, required) — The live serial session to expose (from list_sessions).
- `port` (integer) — Loopback TCP port to bind (1024–65535). Omit for an OS-assigned free port.

</details>

<details markdown="1">
<summary><code>create_tunnel</code> · asks every call</summary>

Add a new WireGuard, Tailscale, or Cloudflare Tunnel config. WIREGUARD: pass `configText` (wg-quick INI body). TAILSCALE: pass `tailscaleAuthKey` (and optional `tailscaleControlUrl` for Headscale). CLOUDFLARE_ACCESS: pass `accessHostname`; for Access-protected routes also pass `accessJwt` (from `cloudflared access token --app https://<host>`); optional `accessJumpDestination` for bastion-mode multi-target tunnels. Returns the new tunnel id, which can then be passed to set_profile_routing.

- `label` (string, required) — User-facing label (also used to derive the Tailscale hostname).
- `type` (string, required) — WIREGUARD, TAILSCALE, or CLOUDFLARE_ACCESS.
- `accessExpiresAt` (integer) — Optional explicit JWT expiry (Unix epoch seconds). Defaults to parsing the `exp` claim out of accessJwt.
- `accessHostname` (string) — Cloudflare Tunnel published hostname (e.g. ssh.example.com). Required when type=CLOUDFLARE_ACCESS.
- `accessJumpDestination` (string) — Optional `Cf-Access-Jump-Destination` value for bastion-mode multi-target tunnels (e.g. internal-host:22).
- `accessJwt` (string) — Cloudflare Access JWT (`CF_Authorization` value). Optional — only needed when the Tunnel route is Access-protected.
- `accessTeamDomain` (string) — Cloudflare Access team domain (myteam.cloudflareaccess.com). Optional; only meaningful for Access-protected routes.
- `configText` (string) — WireGuard wg-quick INI body. Required when type=WIREGUARD.
- `tailscaleAuthKey` (string) — Tailscale single-use authkey (tskey-auth-...). Required when type=TAILSCALE.
- `tailscaleControlUrl` (string) — Self-hosted Headscale coordination URL. Optional — empty defaults to controlplane.tailscale.com.

</details>

<details markdown="1">
<summary><code>delete_tunnel</code> · asks once per session</summary>

Delete a saved tunnel config by id. Profiles that referenced it via tunnelConfigId will fall through to direct dialling on next connect.

- `tunnelConfigId` (string, required) — Tunnel id from list_tunnels.

</details>

<details markdown="1">
<summary><code>list_live_tunnels</code> · no per-call prompt</summary>

Return the live-tunnel snapshot from TunnelManager — every tunnel currently up, paired with the set of profile ids holding it. Useful for verifying refcount semantics in #149 integration tests: confirm the tunnel stays open while a sibling transport keeps it acquired, and that it tears down on the last release.

</details>

<details markdown="1">
<summary><code>list_serial_bridges</code> · no per-call prompt</summary>

List active serial↔TCP bridges: sessionId, bound loopback host/port, and connected-client count. Read-only. Bridges whose serial session has died (device unplugged / out of range) are pruned here.

</details>

<details markdown="1">
<summary><code>list_tunnels</code> · no per-call prompt</summary>

List saved WireGuard / Tailscale tunnel configs available for Route-through on connection profiles. Returns id, label, type (WIREGUARD or TAILSCALE), and createdAt for each. The encrypted configText (wg-quick payload or Tailscale authkey blob) is NOT returned.

</details>

<details markdown="1">
<summary><code>remove_port_forward</code> · asks once per session</summary>

Delete a port-forward rule by id, and deactivate it on the live session if the owning profile is currently connected.

- `ruleId` (string, required) — ID of the rule to remove.

</details>

<details markdown="1">
<summary><code>set_port_knock</code> · asks every call</summary>

Update the port-knock fields on an existing profile. Pass portKnockSequence='' (empty) to disable knocking. Format: 'port[/proto]' tokens — e.g. '7000 8000 9000' or '7000/tcp 8000/udp'. Returns the updated profile summary.

- `portKnockSequence` (string, required) — Sequence string ('' to disable).
- `profileId` (string, required) — Profile id from list_connections.
- `portKnockDelayMs` (integer) — Inter-knock delay in ms. Optional; default 100 when sequence becomes non-empty.

</details>

<details markdown="1">
<summary><code>set_profile_routing</code> · asks once per session</summary>

Set or clear the Route-through configuration on a connection profile. Pass either tunnelConfigId (WireGuard / Tailscale tunnel from list_tunnels) OR proxyType+proxyHost+proxyPort (legacy SOCKS5 / SOCKS4 / HTTP), and the other field set is cleared — mutually exclusive at the data layer too. Pass `clear=true` to drop both and route direct.

- `profileId` (string, required) — Profile id from list_connections.
- `clear` (boolean) — If true, clear both tunnelConfigId and proxyType. Profile routes direct.
- `proxyHost` (string) — Proxy host. Required when proxyType is set.
- `proxyPassword` (string) — Proxy password for SOCKS5 (RFC 1929) / HTTP Basic auth (#227). Optional; ignored for SOCKS4.
- `proxyPort` (integer) — Proxy port. Default 1080 (SOCKS) / 8080 (HTTP).
- `proxyType` (string) — SOCKS5 | SOCKS4 | HTTP. Pair with proxyHost + proxyPort.
- `proxyUser` (string) — Proxy username, when the proxy requires authentication (#227). Optional. SOCKS4 sends userid only.
- `tunnelConfigId` (string) — Tunnel id to route through. Mutually exclusive with proxyType.

</details>

<details markdown="1">
<summary><code>set_spa</code> · asks every call</summary>

Configure fwknop Single Packet Authorization (SPA) on an existing profile — the cryptographic alternative to port knocking. Pass spaKey='' to disable. spaAccessSpec is the port(s) to open, e.g. 'tcp/22' or 'tcp/22,udp/53'. allowMode is SOURCE (default; fwknopd opens for the packet's source IP), RESOLVE (resolve public IP), or EXPLICIT (use explicitIp). Returns the updated profile summary; key material is never echoed back.

- `profileId` (string, required) — Profile id from list_connections.
- `spaKey` (string, required) — Rijndael/AES key ('' to disable SPA).
- `allowMode` (string) — SOURCE | RESOLVE | EXPLICIT. Default SOURCE.
- `explicitIp` (string) — Allow-IP/CIDR for EXPLICIT mode.
- `spaAccessSpec` (string) — proto/port token(s), e.g. 'tcp/22'.
- `spaHmacKey` (string) — Optional HMAC-SHA256 key. '' = no HMAC.
- `spaHmacKeyBase64` (boolean) — True if spaHmacKey is base64 (fwknop --key-base64-hmac).
- `spaKeyBase64` (boolean) — True if spaKey is base64 (fwknop --key-base64-rijndael).
- `spaPort` (integer) — Destination UDP port (default 62201).

</details>

<details markdown="1">
<summary><code>stop_serial_bridge</code> · asks once per session</summary>

Tear down a serial↔TCP bridge started by bridge_serial_to_tcp, closing its listen port and every connected client. The underlying serial session and its terminal tab are unaffected.

- `sessionId` (string, required) — The bridged session id (from list_serial_bridges).

</details>

<details markdown="1">
<summary><code>test_port_knock</code> · asks once per session</summary>

Send a port-knock sequence to a host without committing or connecting anything. Bypasses the saved profile state — pass host + sequence directly. Returns ok/sent/durationMs/error so an agent can verify a knockd config end-to-end without opening a real session.

- `host` (string, required) — Hostname or IP literal to knock against.
- `portKnockSequence` (string, required) — Sequence string — e.g. '7000 8000 9000' or '7000/tcp 8000/udp'.
- `portKnockDelayMs` (integer) — Inter-knock delay in ms (default 100).

</details>

<details markdown="1">
<summary><code>test_spa</code> · asks once per session</summary>

Build and send one fwknop SPA packet to a host without committing a profile or connecting. Pass the key/access directly. Returns ok/bytesSent/spaPort/error so an agent can verify a fwknopd config end-to-end. Key material is never echoed back.

- `host` (string, required) — Hostname or IP literal to send the SPA packet to.
- `spaAccessSpec` (string, required) — proto/port token(s), e.g. 'tcp/22'.
- `spaKey` (string, required) — Rijndael/AES key.
- `allowMode` (string) — SOURCE | RESOLVE | EXPLICIT. Default SOURCE.
- `explicitIp` (string) — Allow-IP/CIDR for EXPLICIT mode.
- `spaHmacKey` (string) — Optional HMAC-SHA256 key.
- `spaHmacKeyBase64` (boolean) — True if spaHmacKey is base64.
- `spaKeyBase64` (boolean) — True if spaKey is base64.
- `spaPort` (integer) — Destination UDP port (default 62201).

</details>

<a id="sec-usb"></a>

## USB & host-device brokers (19)

USB devices and drives, USB/IP export, the adb-over-VPN bridge, and adb pairing.

<details markdown="1">
<summary><code>await_adb_pairing_code</code> · no per-call prompt</summary>

Block until the user sends the 6-digit code from Haven's direct-reply pairing notification, then return it with a ready-to-run `adb pair <host>:<port> <code>` (#575). Pair this with start_adb_pairing: that verb opens the dialog, finds the ephemeral pairing port and posts the prompt; this one collects the reply, and the agent runs the resulting command from the workstation that wants adb — Haven cannot run it itself (that needs SPAKE2-EE over TLS-PSK). Returns { code, host, port, pairCommand }. Errors if no pairing is in progress or the wait elapsed; the port dies with the dialog, so restart the flow rather than retrying a stale one.

- `timeoutMs` (integer) — How long to wait for the code, 1000-300000. Default 120000 — this is a human typing.

</details>

<details markdown="1">
<summary><code>close_usb_drive</code> · no per-call prompt</summary>

Close a USB-drive VM opened by open_usb_drive: power off the VM, stop its USB/IP export, and remove the transient SSH profile + ephemeral key. Idempotent.

- `busid` (string) — Which open drive to close (see list_usb_drives' vms[].busid); optional if exactly one is open.

</details>

<details markdown="1">
<summary><code>delete_usb_appliance</code> · asks once per session</summary>

Delete the persistent USB-helper Linux appliance — the small installed Alpine VM (with usbip+ssh baked in) that open_usb_drive boots to mount drives. It's provisioned once and kept so repeat opens are fast; deleting it frees the disk (~280 MB) and forces a one-time re-provision (re-download + install) on the next open_usb_drive. Closes any live USB-drive VM first. Idempotent.

</details>

<details markdown="1">
<summary><code>enable_wireless_adb</code> · asks every call</summary>

Turn on Android's Wireless debugging (`adb connect` over WiFi) by setting `adb_wifi_enabled=1` via Shizuku. Requires Shizuku to be running and Haven to have its permission granted. NOTE: on Android 11+, a host that has never paired with this device must still complete the pairing-code flow manually — this tool cannot bypass that. For an already-paired host (the common case after a phone reboot) flipping the flag is enough.

</details>

<details markdown="1">
<summary><code>expose_adb</code> · asks every call</summary>

Make the device's adb reachable from the workstation over 4G even with a system VPN active. Enables classic adb-over-TCP on a loopback port (default 5555) via Shizuku — no per-host pairing — then reverse-forwards 127.0.0.1:<port> over the existing MCP tunnel. Because the only phone-side hop is loopback (which Android never routes through a VpnService), adb stays reachable through any VPN. On the workstation: `adb connect localhost:<port>`. Requires Shizuku running + granted. Use install_apk_from_url/_from_backend for installs that don't need a full adb connection. Tear down with unexpose_adb.

- `port` (integer) — Loopback adb-over-TCP port to expose (default 5555).

</details>

<details markdown="1">
<summary><code>list_bridges</code> · no per-call prompt</summary>

Unified view of every phone capability Haven is currently **brokering** to a sink — the 'Bridges' registry (see docs/design/bridges.md). A bridge is one Android-held capability (a USB device, audio, etc.) re-exposed to a consumer that can't reach it directly: the AI agent, the local Linux guest, a local VM, a remote host, or the workstation. Generalises list_usb_exports across all bridge types. Each entry: source, sourceKind, sink, transport, state, plus type-specific detail (busid/port/profileId/mounts). Read-only.

</details>

<details markdown="1">
<summary><code>list_usb_devices</code> · no per-call prompt</summary>

List USB devices attached to the phone (host/OTG). Each entry has deviceName (the stable /dev/bus/usb path used as the key for the other usb_* tools), vidPid, deviceClass, hasPermission, isOpen, and the interface/endpoint descriptors (id, class, endpoint address + direction + type). Manufacturer/product/serial strings are only filled once permission is held (call request_usb_permission). Read-only; never prompts.

</details>

<details markdown="1">
<summary><code>list_usb_drives</code> · no per-call prompt</summary>

List phone-attached USB mass-storage drives (the candidates for open_usb_drive) and every currently-open USB-drive VM in `vms` (up to a phone-resource concurrency limit): busid, phase (idle/opening/ready/error), the loopback SSH `profileId`, whether it's mounted read-only, any locked (LUKS) partitions awaiting unlock_usb_drive_partition, and the mounted paths once ready. Read-only — poll this after open_usb_drive until the matching vms[] entry has phase=ready.

</details>

<details markdown="1">
<summary><code>list_usb_exports</code> · no per-call prompt</summary>

List active USB exports of phone-attached devices: the USB/IP server (start_usbip_export — to remote hosts) and the guest proxy (usb_attach_to_guest — to the local proot guest). Reports the exported device, busid/bound port, and whether a remote usbip client is currently attached. Read-only.

</details>

<details markdown="1">
<summary><code>open_usb_drive</code> · asks once per session</summary>

Open a phone-attached USB drive (mass storage — flash drive, SSD, SD reader) inside an on-device QEMU Linux VM and surface its files as an ordinary connection (#287). Unlike usb_attach_to_guest (which gives the proot guest a char device), this gives the drive a REAL kernel, so ext4 / GPT / block partitions mount and their files are browseable. Flow: exports the drive over USB/IP, boots (or reuses, if another drive is already open) a small Alpine VM that imports it, mounts every partition (read-only unless `writable`), and runs sshd — then returns a loopback SSH/SFTP `profileId` you browse with list_directory / serve_file (and a terminal tab into the VM). A LUKS-encrypted partition mounts locked (reported in list_usb_drives' vm.locked) — call unlock_usb_drive_partition with its passphrase to mount it. The VM boot is slow (TCG, no KVM unrooted) + the first run installs packages, so this returns {status:"starting"} immediately — poll list_usb_drives until phase=ready (profileId set) or error. Consent-gated per session (mounting the user's disk is sensitive). Up to a phone-resource limit of concurrent drives (they share one VM, so this is a vhci-port/practical cap, not RAM); isochronous (webcam/audio) still can't pass.

- `deviceName` (string) — deviceName from list_usb_devices / list_usb_drives; optional if exactly one USB drive is attached.
- `writable` (boolean) — Mount read-write instead of the default read-only. An interrupted write (VM killed, app backgrounded under memory pressure) can corrupt the drive's filesystem — only set this when the caller genuinely needs to write.

</details>

<details markdown="1">
<summary><code>request_usb_permission</code> · asks once per session</summary>

Request the Android runtime USB permission for a device (pops the system grant dialog) and open it, caching the connection for usb_control_transfer / usb_bulk_transfer. Idempotent: a no-op if permission is already held and the device is open. Returns the device info with hasPermission/isOpen reflecting the result.

- `deviceName` (string, required) — deviceName from list_usb_devices (the /dev/bus/usb/BBB/DDD path).

</details>

<details markdown="1">
<summary><code>start_adb_pairing</code> · asks every call</summary>

Pair a NEW workstation with this device's adb, without the user hunting for a port (#575). Turns wireless debugging on, opens Android's system pairing dialog, and discovers the pairing listener over mDNS (`_adb-tls-pairing._tcp`) — that service exists ONLY while the dialog is open and gets a fresh ephemeral port every time, which is why a port noted once is always stale and why `adb connect`'s port is the wrong one to pair against. Returns { host, port, pairCommand, wirelessDebuggingScreenOpened, codePromptShown }. The 6-digit code is NOT returned and cannot be: the system generates and draws it, and no API exposes it to an app — read it off the dialog. Haven posts a direct-reply notification so the code can be typed on-device without leaving the dialog; collect it with await_adb_pairing_code, or ignore it and pair from the workstation with the returned command. A notification rather than a floating window because Settings sets HIDE_NON_SYSTEM_OVERLAY_WINDOWS, which hides any app overlay drawn over it — the reply field is system-drawn, so it is unaffected and needs no permission beyond notifications. Shizuku only helps with the wireless-debugging flag and is optional.

- `showPrompt` (boolean) — Post the direct-reply notification so the code can be typed on-device. Default true; pass false to pair from the workstation instead.
- `timeoutMs` (integer) — How long to wait for the pairing service to appear, 1000-120000. Default 30000. The clock starts before the dialog opens, since the service can appear immediately.

</details>

<details markdown="1">
<summary><code>start_usbip_export</code> · asks once per session</summary>

Start a userspace USB/IP server exporting a phone-attached USB device over TCP (default port 3240) so a remote Linux host can `usbip attach` it as a real local device node — every app there (ssh, libfido2, browsers) sees it, with the touch happening on the phone. Opens the device (requesting permission if needed) and returns the busid, bound port, and the client-side attach command. deviceName is optional when exactly one device is attached. Pass loopbackOnly:true to bind 127.0.0.1 only (for use behind an SSH/WireGuard tunnel); the default binds all interfaces for direct LAN attach. This is the remote-host counterpart to usb_attach_to_guest (which targets the local proot guest, where usbip can't run — the Android kernel has no vhci-hcd).

- `deviceName` (string) — deviceName from list_usb_devices; optional if only one device is attached.
- `loopbackOnly` (boolean) — Bind 127.0.0.1 only (use behind a tunnel). Default false = all interfaces, LAN-reachable.

</details>

<details markdown="1">
<summary><code>stop_usbip_export</code> · no per-call prompt</summary>

Stop the USB/IP server started by start_usbip_export (closes the listening socket and any active client connection) and release the brokered USB device handle. Pass keepOpen:true to leave the handle open for a fast re-export.

- `keepOpen` (boolean) — Leave the brokered device handle open for a fast re-export (default false = fully release it).

</details>

<details markdown="1">
<summary><code>unexpose_adb</code> · asks once per session</summary>

Tear down expose_adb: remove the adb reverse forward from the MCP tunnel and disable adb-over-TCP on the device (returns adbd to USB-only). Safe to call even if adb wasn't exposed.

</details>

<details markdown="1">
<summary><code>unlock_usb_drive_partition</code> · asks once per session</summary>

Unlock a LUKS-encrypted partition on an open USB-drive VM (see list_usb_drives' vms[].locked for candidates, e.g. "sdb2" → devicePath "/dev/sdb2") and mount it. Runs against the already-booted VM — no reboot. Returns the updated mount/locked lists; throws on a wrong passphrase.

- `devicePath` (string, required) — e.g. /dev/sdb2 — the locked partition's device path inside the VM.
- `passphrase` (string, required) — The LUKS passphrase.
- `busid` (string) — Which open drive's VM (see list_usb_drives' vms[].busid); optional if exactly one is open.

</details>

<details markdown="1">
<summary><code>usb_attach_to_guest</code> · asks once per session</summary>

Expose a USB device to the proot Linux guest: opens it (requesting permission if needed) and binds the haven-usb proxy on an abstract LocalSocket the guest can reach, then stages the haven-usb-probe binary into the guest. Returns the socketName, the in-guest probePath, and a probeCommand you can run via run_in_proot to verify reachability. For a CDC-ACM serial device it also returns serialBridgeCommand (the haven-usb-serial PTY bridge) so unmodified serial apps (e.g. LIRC's lircd/mode2) can open it as /dev/pts/N. deviceName is optional when exactly one device is attached. This is the entry point for the guest-side USB shim (LD_PRELOAD/DllMap for HID, a PTY bridge for serial).

- `deviceName` (string) — deviceName from list_usb_devices; optional if only one device is attached.

</details>

<details markdown="1">
<summary><code>usb_bulk_transfer</code> · asks every call</summary>

Perform a USB bulk or interrupt transfer on an opened device. Direction is taken from the endpoint descriptor. Args: deviceName, endpoint (bEndpointAddress, int), dataBase64 (OUT payload, omit for IN), length (IN read length), timeoutMs (default 1000). The owning interface is claimed automatically. Returns bytesTransferred and, for IN endpoints, dataBase64.

- `deviceName` (string, required)
- `endpoint` (integer, required) — bEndpointAddress from the interface descriptor.
- `dataBase64` (string) — Base64 OUT payload; omit for IN endpoints.
- `length` (integer) — IN read length; ignored for OUT.
- `timeoutMs` (integer)

</details>

<details markdown="1">
<summary><code>usb_control_transfer</code> · asks every call</summary>

Perform a USB endpoint-0 control transfer on an opened device. Args: deviceName, requestType (bmRequestType, int — bit 7 set = device-to-host/IN), request (bRequest), value (wValue), index (wIndex), dataBase64 (OUT payload, omit for IN), length (IN read length), timeoutMs (default 1000). Returns bytesTransferred and, for IN transfers, dataBase64. The device must already be opened via request_usb_permission.

- `deviceName` (string, required)
- `index` (integer, required) — wIndex.
- `request` (integer, required) — bRequest.
- `requestType` (integer, required) — bmRequestType. Bit 7 (0x80) set = IN.
- `value` (integer, required) — wValue.
- `dataBase64` (string) — Base64 OUT payload; omit for IN.
- `length` (integer) — IN read length; ignored for OUT.
- `timeoutMs` (integer)

</details>

<a id="sec-security"></a>

## Security — SSH keys, host keys, TOTP & age (15)

The SSH key store, pinned host keys (TOFU), trusted host CAs, TOTP secrets, and age encryption identities.

<details markdown="1">
<summary><code>add_trusted_host_ca</code> · asks every call</summary>

Trust an SSH host CA (#133): register a CA public key so any server presenting an OpenSSH host certificate signed by it connects without a TOFU prompt. `caPublicKey` is an OpenSSH public-key line ("ssh-ed25519 AAAA… [comment]") or a bare base64 blob; `name` is a label. Ed25519 and ECDSA host CAs are verified natively; RSA host CAs are stored but not yet validated by the SSH library. Establishing trust is a security boundary — gated by consent.

- `caPublicKey` (string, required) — The CA's OpenSSH public key line, or bare base64 blob.
- `name` (string, required) — Label for this trusted CA (shown under Keys → Certificate authorities).

</details>

<details markdown="1">
<summary><code>create_age_identity</code> · asks every call</summary>

Generate and store a new age X25519 encryption identity (VISION §2). Optional `label`. Returns the new id and its public `age1…` recipient. Tap-equivalent to Keys → + → Generate age identity.

- `label` (string) — Optional user-facing label. Defaults to 'age identity'.

</details>

<details markdown="1">
<summary><code>create_totp_secret</code> · asks every call</summary>

Store an OATH-TOTP secret so it can auto-fill an SSH keyboard-interactive OTP prompt (#178). Pass `otpauth` (an `otpauth://totp/...` URI) OR `secret` (a raw base32 string) plus an optional `label`. Returns the new secret id; reference it via a `TOTP:<id>` token in create_connection's authMethods.

- `label` (string) — Optional user-facing label. Defaults to the otpauth issuer/account or 'Authenticator'.
- `otpauth` (string) — An otpauth://totp/Issuer:account?secret=...&... URI. Mutually exclusive with `secret`.
- `secret` (string) — A raw base32 TOTP secret (SHA1, 6 digits, 30s period assumed). Use `otpauth` instead when you have the full URI.

</details>

<details markdown="1">
<summary><code>delete_ssh_key</code> · asks every call</summary>

Delete a saved SSH key by id. Profiles that referenced it via sshKeyId will fall through to password auth (or fail) on next connect — no cascade rewrite. Irreversible: the encrypted private key bytes are removed.

- `sshKeyId` (string, required) — SSH key id from list_ssh_keys.

</details>

<details markdown="1">
<summary><code>delete_totp_secret</code> · asks every call</summary>

Delete a saved TOTP secret by id. Profiles referencing it via a TOTP auth element fall through to a manual OTP prompt on next connect. Irreversible.

- `totpSecretId` (string, required) — TOTP secret id from list_totp_secrets.

</details>

<details markdown="1">
<summary><code>delete_trusted_host_ca</code> · asks every call</summary>

Remove a trusted SSH host CA by id (from list_trusted_host_cas). Servers signed by it fall back to the usual per-host TOFU prompt on the next connect. Deletes the whole step-ca config row for that id. No-op if none matches; returns removed=true/false. Gated by consent.

- `id` (string, required) — The config id to remove (from list_trusted_host_cas).

</details>

<details markdown="1">
<summary><code>forget_known_host</code> · asks every call</summary>

Forget a pinned SSH host key by hostname + port, so the next connect re-pins on first-use trust (TOFU). Use when a server has legitimately rotated its host key, or to clear a stale pin. `hostname` and `port` are required (from list_known_hosts). No-op if none matches; returns removed=true/false.

- `hostname` (string, required) — Host of the pinned key (from list_known_hosts).
- `port` (integer, required) — Port of the pinned key (SSH default 22).

</details>

<details markdown="1">
<summary><code>generate_totp_code</code> · asks every call</summary>

Return the code a saved TOTP secret is showing right now (#178). This is the only verb that hands a live credential to the caller, so it asks every time. For SSH, prefer a `TOTP:<id>` token in create_connection's authMethods — Haven fills the prompt itself and the code never leaves the device. Use this for the prompts that path cannot reach: a 2FA field in a guest desktop, a web login, an app. Check `secondsRemaining` before using the code — a code with two seconds left will be rejected by the time it is typed; call again after it rolls over.

- `totpSecretId` (string, required) — TOTP secret id from list_totp_secrets.

</details>

<details markdown="1">
<summary><code>import_ssh_key</code> · asks every call</summary>

Import an OpenSSH / PEM / PKCS#8 / PuTTY PPK private key into the Haven key store. Pass `privateKey` (the text body, e.g. starting with `-----BEGIN OPENSSH PRIVATE KEY-----`), `label` (user-facing name), and optional `passphrase` (only if the key is encrypted). Returns the new key id, keyType, publicKeyOpenSsh (suitable for an `authorized_keys` line), and fingerprintSha256.

- `label` (string, required) — User-facing label shown on the Keys screen and in profile pickers.
- `privateKey` (string, required) — Private key body in OpenSSH / PEM / PKCS#8 / PuTTY format. Pass the file's text contents verbatim, including BEGIN/END lines.
- `passphrase` (string) — Optional. Only required if the private key is encrypted at rest. Stored only briefly to decrypt the key for parsing; the saved entity keeps the original (still-encrypted) bytes.

</details>

<details markdown="1">
<summary><code>list_age_identities</code> · no per-call prompt</summary>

List saved age file-encryption identities (VISION §2). Returns id, label, the public `age1…` recipient (encrypt to this with encrypt_file or the file browser's Encrypt action), and createdAt. The private key (AGE-SECRET-KEY-1…) is NEVER returned — it stays encrypted at rest.

</details>

<details markdown="1">
<summary><code>list_known_hosts</code> · no per-call prompt</summary>

List pinned SSH host keys (the TOFU known_hosts store). Returns hostname, port, keyType, fingerprint (SHA-256), and firstSeen (epoch ms). Use forget_known_host to remove one.

</details>

<details markdown="1">
<summary><code>list_ssh_keys</code> · no per-call prompt</summary>

List saved SSH keys available for SSH / Mosh / SFTP profiles. Returns id, label, keyType (e.g. ed25519, rsa, sk-ssh-ed25519@openssh.com), publicKeyOpenSsh, fingerprintSha256, isEncrypted (passphrase-protected), biometricProtected, enabledForAuth (whether it's offered in 'any saved key' auto-auth), verifyRequired (FIDO2/SK keys only: requires its PIN at sign-in), and createdAt. Set enabledForAuth / verifyRequired via set_ssh_key_option. Private key bytes are NEVER returned — they stay encrypted at rest.

</details>

<details markdown="1">
<summary><code>list_totp_secrets</code> · no per-call prompt</summary>

List saved OATH-TOTP authenticator secrets (#178). Returns id, label, issuer, accountName, algorithm, digits, periodSeconds, and createdAt. The base32 secret itself is NEVER returned — it stays encrypted at rest. Reference an id as a `TOTP:<id>` token in create_connection's authMethods to auto-fill the SSH 'Verification code:' prompt.

</details>

<details markdown="1">
<summary><code>list_trusted_host_cas</code> · no per-call prompt</summary>

List the trusted SSH host-CA entries — the step-ca configs whose SSH host-CA public key is set (#133). A server presenting an OpenSSH host certificate signed by one of these CAs connects with no TOFU fingerprint prompt. Returns, per entry: id, name, keyType, and fingerprint (SHA-256, OpenSSH format). Configs with no host-CA key are omitted. Use add_trusted_host_ca / delete_trusted_host_ca to change the store.

</details>

<details markdown="1">
<summary><code>set_ssh_key_option</code> · asks once per session</summary>

Set per-key options on a saved SSH key (the toggles on the Keys screen). `keyId` (from list_ssh_keys) is required; pass either or both of: `enabledForAuth` (bool) — whether the key takes part in 'any saved key' auto-auth (off = only used when a profile pins it); `verifyRequired` (bool) — FIDO2/SK keys only — whether the key requires its PIN at every sign-in (true) or is touch-only (false); flips the SK flag in place without re-registering; `storedPassphrase` (string) — encrypted keys only — store the key's passphrase on-device (encrypted at rest) so connects don't prompt and the key can be exposed over SSH agent forwarding (#377); empty string clears a stored passphrase. Returns the key's resulting enabledForAuth and verifyRequired. Biometric-protected SK keys can't have verifyRequired changed over MCP (no prompt available).

- `keyId` (string, required) — SSH key id from list_ssh_keys.
- `enabledForAuth` (boolean) — Include this key in 'any saved key' auto-auth.
- `storedPassphrase` (string) — Encrypted keys only: passphrase to store on-device (empty string clears).
- `verifyRequired` (boolean) — FIDO2/SK only: require the key's PIN at every sign-in.

</details>

<a id="sec-agent-you"></a>

## Agent ↔ you (attention & self-drive) (12)

How an agent reaches your attention (present_*, notifications, the agent-to-agent turn tools) and drives Haven's own UI.

<details markdown="1">
<summary><code>await_turn</code> · no per-call prompt</summary>

Block until a terminal session is idle-at-prompt — the natural "wait for the other agent / command to finish" primitive for turn-based conversation (#226). Detection: OSC 133 shell-integration segments when present (idle = cursor on the newest prompt row); otherwise screen heuristics tuned for Claude Code's TUI (no busy spinner / "esc to interrupt", a prompt-looking line near the bottom, and the screen stable across polls). Idle must hold for settleMs before returning. Returns { sessionId, idle, method: "osc133"|"heuristic", waitedMs, timedOut }. idle=false + timedOut=true means the timeout elapsed first — the session may still be mid-turn. Full-screen TUIs that are neither shells nor agent REPLs (vim, htop) can read as idle once their screen stops changing; this is a turn heuristic, not a process-state probe.

- `sessionId` (string) — Active session ID (from list_sessions). Optional — defaults to the sole open terminal session. Must have an attached terminal tab.
- `settleMs` (integer) — How long idle must hold continuously before returning. Default 1500, clamped to 0–30000.
- `timeoutMs` (integer) — Give up after this long. Default 60000, clamped to 1000–300000. Keep under your MCP client's request timeout.

</details>

<details markdown="1">
<summary><code>capture_haven_ui</code> · asks once per session</summary>

Capture HAVEN'S OWN rendered screen — the app UI the user is looking at right now (Connections list, terminal tab, a dialog, the file browser, an agent overlay), NOT a remote desktop (capture_desktop_tab) or the terminal text (read_terminal_snapshot). This is the 'perceive' half of the self-hosting loop: after install_apk_from_backend deploys a build, capture_haven_ui lets the agent see the result and diff it. Returns the image plus { width, height, imageWidth, imageHeight, format }. width/height are the FULL window in pixels — pass tap_haven_ui / swipe_haven_ui coordinates in THAT space even when the returned image was downscaled via maxWidth. If Settings → screen security (FLAG_SECURE) is on, returns { secure: true } with no image (capture is intentionally blocked). Errors if Haven is not in the foreground.

- `format` (string) — "jpeg" (default, smaller) or "png" (lossless, larger).
- `maxWidth` (integer) — Downscale so the returned image is at most this many pixels wide (the reported width/height stay full-window). Default 1080 (clamped 160–4096).

</details>

<details markdown="1">
<summary><code>dump_haven_ui</code> · asks once per session</summary>

Dump Haven's OWN foreground UI as a structured element list — the in-app equivalent of `uiautomator dump`, so you get EXACT control bounds instead of estimating them off a capture_haven_ui image. Returns { width, height, count, nodes:[{text, contentDescription, editableText, role, clickable, disabled, bounds:[left,top,right,bottom], centerX, centerY, window?}] } in the SAME window-pixel space tap_haven_ui / swipe_haven_ui use — read a control's centerX/centerY and tap it directly. Nodes from the activity window have no `window` field; nodes from an overlay that renders in its OWN window (e.g. the consent sheet, `window: consent-sheet`) are labelled with it (#355) — those bounds are that window's own pixel space. capture_haven_ui still photographs the activity window only, and tap_haven_ui refuses entirely while a consent prompt is pending, so an overlay can be observed but never tapped by an agent. Returns `windowsEnumerable`: when false, the platform refused the window-list lookup and only the activity window (plus self-registered overlays) could be walked — a dialog or dropdown may be on screen and missing from `nodes`, so an empty result does NOT mean nothing is open, and tapping blind will hit whatever is behind it. FLAG_SECURE blocks it. Read-only.

</details>

<details markdown="1">
<summary><code>present_app</code> · asks once per session</summary>

Show the user a LIVE, interactive single application window inline in Haven. Launches `command` as a Wayland app under a `cage` kiosk inside the active proot guest, exposes it over VNC, and embeds the live view in a bottom sheet over whatever screen the user is on (pinch-zoom, pan, drag and fullscreen all work; the user can interact). Use this to collaborate in a real GUI app — an image viewer, a media/audio player, a PDF/whiteboard tool — rather than pushing a static image with present_media. `command` is the guest shell command cage runs (e.g. 'imv /root/board.png', 'mpv /root/clip.mp4'); the app and any Wayland deps must already be installed in the guest. Returns { presented, sessionId, vncPort, state } once the window is up. Multiple app windows can run at once: each call launches another cage; the newest is shown full-overlay and any previous one is backgrounded to a draggable edge icon (tap to bring it back). The user backgrounds a window by tapping outside it (keeps it running) and tears it down with the Dismiss button or the edge-icon close. If the window comes up grey/blank or vanishes, read the app's own stdout/stderr with read_app_window_log (works even after it crashed) instead of wrapping the command in a logging script.

- `command` (string, required) — Guest shell command for the GUI app cage runs, e.g. 'imv /root/x.png'.
- `caption` (string) — Optional one-line caption shown above the window.
- `fullscreen` (boolean) — Open the window filling the whole screen (immersive) instead of the bottom sheet. Default false.
- `multiWindow` (boolean) — Float the app's windows instead of force-fullscreening them. Required for apps that open several toplevels (qmmp's skinned main/EQ/playlist deck) — under the default kiosk rule they stack and only the last-raised window is visible. Default false.
- `resolution` (string) — Cage display resolution: 'auto' (portrait, fills the screen — default) or a 'WxH' token like '1280x720'. Lower resolution = bigger fonts.
- `runAsRoot` (boolean) — Run the app as root via fakeroot-tcp (the cage compositor itself runs non-root, so system tools like package managers go read-only otherwise). Installs fakeroot if missing. APT distros only today. Default false.
- `scale` (number) — Output scale factor (wlroots HiDPI; foot/GTK honour it). 1.0 default; 1.5/2 enlarge fonts + UI.
- `swayRules` (string[]) — Extra sway config lines appended to the kiosk config — per-title placement for multiWindow apps (sway centers every floating window, stacking a deck), e.g. 'for_window [title="^Playlist$"] move position 20 136'.

</details>

<details markdown="1">
<summary><code>present_media</code> · no per-call prompt</summary>

Show the user an image — or play a short sound — inline in Haven. A bottom sheet floats over whatever screen the user is on, rendering the image (or an audio card with a play button) plus an optional caption. The "here, look at / listen to this" channel: use it when you have something visual or audible you want the user to perceive directly. Reference the media by a file Haven can reach — `profileId` ("local" for the device / proot-guest cache, or an SSH/SMB/rclone profile id) + `path` — or by a ready `url` (e.g. a serve_file loopback URL). Haven streams the file into a local handle; the bytes never pass through the agent context. `mimeType` is inferred from the file (extension, else content sniff) when omitted; set it for audio. Only image/* and audio/* are supported. Returns immediately ({ presented }) as soon as the request is accepted — Haven fetches/stages the file and shows the sheet in the background, so a slow transfer can't turn a delivered image into a timeout; a staging failure is logged (not returned). The user dismisses the sheet at their leisure.

- `autoPlay` (boolean) — Audio only: start playback as soon as the sheet appears. Defaults to false.
- `caption` (string) — Optional one-line caption shown above the media.
- `mimeType` (string) — Optional MIME, e.g. 'image/png' or 'audio/mpeg'. Inferred from the file otherwise; set it for audio.
- `path` (string) — Absolute path of the image/audio file on that backend.
- `profileId` (string) — Backend holding the file: "local" for the device / proot-guest cache (default), or an SSH/SMB/rclone profile id. Used with `path`.
- `url` (string) — Alternative to profileId+path: an http(s) URL to fetch the media from (e.g. a serve_file loopback URL).

</details>

<details markdown="1">
<summary><code>present_web</code> · no per-call prompt</summary>

Show the user HTML, an SVG, or a PDF inline in an in-app WebView — the interactive rung between present_media (a static image) and present_app (a full live VNC app). Pass a `url` (e.g. a serve_file loopback URL or any web page), or reference a file with `profileId` ("local" for the device / proot-guest cache, or an SSH/SMB/rclone profile id) + `path`, which Haven serves over a loopback URL. A PDF is paged; HTML/SVG render live (pinch-zoom + pan). Floats in a bottom sheet over whatever screen the user is on; bytes never pass through the agent context. Returns immediately: a `url` acks with { presented, id, url }; a file reference acks with { presented } and is staged/shown in the background (a staging failure is logged, not returned). The user dismisses it at their leisure.

- `caption` (string) — Optional one-line caption shown above the view.
- `path` (string) — Absolute path of the .html/.svg/.pdf file on that backend.
- `profileId` (string) — Backend holding the file: "local" (device / proot-guest cache, default) or an SSH/SMB/rclone profile id. Used with `path`.
- `url` (string) — An http(s) URL to load (e.g. a serve_file loopback URL or any web page). Alternative to profileId+path.

</details>

<details markdown="1">
<summary><code>queue_self_message</code> · asks every call</summary>

*Extra capability switch — off by default: queue_terminal_input is disabled — enable in Settings → Agent endpoint → Allow agents to queue terminal input.*

DEPRECATED: alias for `queue_terminal_input`. Use queue_terminal_input — same arguments, same behaviour, plus a `submitKey` parameter you didn't have here.

- `text` (string, required)
- `promptPattern` (string)
- `sessionId` (string)
- `timeoutSeconds` (integer)

</details>

<details markdown="1">
<summary><code>raise_notification</code> · no per-call prompt</summary>

Post a real Android system notification on Haven's behalf so the agent can drive notification-listener / wake / DND / silencer apps during F-Droid tester reviews without needing a second device. Always posts to the dedicated 'agent.test.notifications' channel (created on first use) so the user can mute agent notifications cleanly without affecting Haven's own connection / renewal notifications. Returns { posted, id, channel } — keep the id around if you want to dismiss or replace the notification later (a future tool). Notifications use Haven's app identity, so notification-listener apps see package=sh.haven.app. Requires the POST_NOTIFICATIONS runtime grant (declared in the manifest, granted by the user on first Haven launch); the call fails with a clear error if notifications have been disabled in system settings.

- `body` (string, required) — Notification body (mandatory). Expanded into a BigTextStyle so multi-line content stays readable.
- `title` (string, required) — Notification title (mandatory). Shown on the lockscreen / shade row.
- `ongoing` (boolean) — If true, post as ongoing (non-dismissable) so the agent can test foreground-service-like notifications. Defaults to false (dismissable, auto-cancels on tap).
- `priority` (string) — One of 'min', 'low', 'default', 'high', 'max'. Maps to NotificationCompat.PRIORITY_*. Defaults to 'default'. Note: from Android 8 the channel's importance is what actually drives heads-up behaviour; priority only matters on pre-O devices and as a hint to ranking.

</details>

<details markdown="1">
<summary><code>read_last_turn</code> · no per-call prompt</summary>

Return the target session's latest completed turn as text — the receive half of agent↔agent conversation (#226). When the session is idle at an OSC 133-integrated shell prompt, returns the last command's output (semantic COMMAND_OUTPUT between the newest COMMAND_INPUT and COMMAND_FINISHED, scrollback included; source="osc133"). Otherwise (Claude Code and other REPLs that don't emit shell-style 133) falls back to scraping the last ●/⏺-bulleted block above the input box from the visible screen (source="scrape" — inherently heuristic; long replies that scrolled off-screen are truncated to what's visible). Returns { sessionId, text|null, source|null, truncated }. Call await_turn first so you read a finished turn, not a partial one.

- `maxChars` (integer) — Cap the returned text, keeping the TAIL (the end of a reply is usually the conclusion). Default 20000, clamped to 256–100000. Sets truncated=true when applied.
- `sessionId` (string) — Active session ID (from list_sessions). Optional — defaults to the sole open terminal session. Must have an attached terminal tab.

</details>

<details markdown="1">
<summary><code>send_to_agent</code> · asks once per session</summary>

Deliver one message to another agent's REPL (or any raw-mode prompt) as a single submitted turn: paste the text (bracketed-paste when the target has enabled it, plain otherwise), settle, then Enter — and return the resulting screen (last ~50 lines by default, captured after a short render delay). A convenience wrapper over send_terminal_input tuned for agent↔agent / REPL conversation, so you don't hand-assemble the body-then-Enter sequence. Use list_sessions (chosenSessionName + isAgentRepl) to pick the target; pair with await_turn + read_last_turn for the full send → wait → read loop. Returns { sessionId, delivered, transport, bytesSent, snapshot }.

- `message` (string, required) — The message to deliver as one submitted prompt.
- `maxLines` (integer) — Cap the returned snapshot to the last N lines (default 50). Keeps the ack small so a delivered message doesn't read back as a timeout behind a large scrollback over a tunnel.
- `sessionId` (string) — Active session ID (from list_sessions). Optional — defaults to the sole open terminal session.
- `snapshotDelayMs` (integer) — Wait this long after Enter before capturing the ack snapshot so it reflects the submitted turn. Default 500, max 5000.

</details>

<details markdown="1">
<summary><code>swipe_haven_ui</code> · asks every call</summary>

Inject a swipe/drag into HAVEN'S OWN UI from (fromX, fromY) to (toX, toY) in window pixels (the coordinate space capture_haven_ui reports), over durationMs split into N steps. Drives pager flings (swipe between Connections/Terminal/Files tabs), list scrolls, and bottom-sheet drags. Refused while a consent prompt is showing and when Haven is not foreground. Returns { delivered, reason?, fromX, fromY, toX, toY, durationMs, steps }. Verify with capture_haven_ui.

- `fromX` (integer, required) — Start X (window px).
- `fromY` (integer, required) — Start Y (window px).
- `toX` (integer, required) — End X (window px).
- `toY` (integer, required) — End Y (window px).
- `durationMs` (integer) — Total swipe time. Default 200 (clamped 1–10000). Longer = slower drag (less fling momentum).
- `steps` (integer) — ACTION_MOVE events between down and up. Default 16 (clamped 1–200).

</details>

<details markdown="1">
<summary><code>tap_haven_ui</code> · asks every call</summary>

Inject a tap (or, with holdMs > 0, a press-and-hold) into HAVEN'S OWN UI at window-pixel (x, y) — the same coordinate space capture_haven_ui reports in its width/height. This is the 'drive' half of the self-hosting loop: read a control's position from a capture_haven_ui image, then tap it. A plain tap activates the control under the point via its accessibility OnClick action — so it drives everything from nav tabs and dialog buttons to the keyboard-toolbar keys and toggles nested in an expanded settings panel — and falls back to a raw MotionEvent (for the terminal cursor, custom drag handles) when nothing actionable sits there; holdMs > 0 always sends a real held press. Refused while a consent prompt is showing (so an injected tap can't self-confirm) and when Haven is not foreground. Returns { delivered, reason?, x, y, holdMs }. Verify the effect with a follow-up capture_haven_ui.

- `x` (integer, required) — Window-pixel X (0..width from capture_haven_ui).
- `y` (integer, required) — Window-pixel Y (0..height from capture_haven_ui).
- `holdMs` (integer) — Press-and-hold duration. 0 (default) = a quick tap; >~500 triggers a long-press. Clamped 0–10000.

</details>

<a id="sec-agent-endpoint"></a>

## Agent endpoint, device & diagnostics (17)

Pairing, standing policies, app info/update, preferences, and device diagnostics.

<details markdown="1">
<summary><code>check_for_update</code> · asks once per session</summary>

Ask GitHub whether a newer Haven release exists, right now, and report the answer (#578). Independent of the update_check_enabled preference — that switch arms the unattended launch-time check; this is the on-demand one. Returns { channel, installedVersion, status, latestVersion?, releaseUrl?, error? }. channel is GITHUB_RELEASE when the running copy's own signing certificate matches the key that signs the release APKs, OTHER for anything else (F-Droid, a self-built or re-signed copy) — and on OTHER the status is WRONG_CHANNEL and NO network request is made, because a GitHub release cannot replace a differently-signed package. status is one of UP_TO_DATE, UPDATE_AVAILABLE, WRONG_CHANNEL, FAILED; FAILED carries `error` (GitHub's unauthenticated API is rate-limited per IP, so a shared address can 403). This is the verb to use to verify the update path end to end without driving Settings by hand.

</details>

<details markdown="1">
<summary><code>create_standing_policy</code> · asks every call</summary>

Propose a Tier-3 STANDING POLICY: a scoped, rate-capped, expiring grant that lets THIS client call the listed tools without a per-call consent prompt. The user's tap on this tool's consent sheet IS the installation — the sheet spells out the full scope. Use it when a workflow needs many consented calls in a row (e.g. a tap_haven_ui/swipe_haven_ui drive-and-verify loop) so the user grants the loop once instead of per tap. toolNames must be existing tools; some can never be covered (the policy tools themselves, install_apk_*, unpair_mcp_client). argConstraints (optional) pins arguments: every key given must exactly equal the call's argument (e.g. {"profileId":"<id>"} scopes the grant to one connection). Covered calls are still written to the audit log; the rate ceiling makes extra calls fall back to normal prompts; the policy expires on its own and can be revoked any time from Haven's Agent activity screen or via revoke_standing_policy. Returns { id, expiresAt }.

- `description` (string, required) — Short human label for what this grant is for, shown on the consent sheet and the kill-switch list. E.g. "Drive the UI to verify build 5.59.54".
- `toolNames` (string[], required) — Exact tool names the policy covers.
- `argConstraints` (object) — Optional exact-match argument pins, e.g. {"profileId":"abc"}.
- `expiresInMinutes` (integer) — Lifetime (default 60, clamped 5–1440). After expiry the policy never applies.
- `maxCallsPerMinute` (integer) — Rate ceiling per rolling minute (default 60, clamped 1–600). Beyond it, calls fall back to per-call prompts.

</details>

<details markdown="1">
<summary><code>get_app_info</code> · no per-call prompt</summary>

Return Haven version, which optional features are available in this build, and mcpCarriers — which MCP transports are actually open right now (a WireGuard-collision warning if the WG carrier is shadowed by a system VPN, and whether the near/SSH carrier is currently riding a connected interactive session — see McpNearCarrier).

</details>

<details markdown="1">
<summary><code>get_native_crashes</code> · no per-call prompt</summary>

Return native crashes (SIGSEGV, SIGABRT, …) from previous runs of Haven, newest first, each with the system's tombstone when one was kept — the backtrace that names the failing function and library. This is the diagnostic Haven could not previously produce: it records its own logcat from inside its own process, so a native signal kills the recorder too and the tombstone lands after Haven is gone. Recovered on the next launch from ActivityManager.getHistoricalProcessExitReasons. Requires Android 11 (API 30); on older releases `supported` is false and the list is empty rather than misleadingly so. Kotlin/Java exceptions are NOT here — those do not kill the process this way. Read-only.

- `includeTrace` (boolean) — Include the full tombstone text for each crash. Default true; set false for a short index when the traces are long.

</details>

<details markdown="1">
<summary><code>get_pending_consent</code> · no per-call prompt</summary>

Return the consent/pairing prompts Haven is currently showing or holding, oldest first, or { pending: false } when none. Each entry: { id, toolName, clientHint, summary, isPairing, offerTimedAllow, requestedAt }. The consent sheet renders in its own window that capture_haven_ui / dump_haven_ui cannot see (#355), so this is the only way an agent can tell "my call is waiting for the user" from "my call was denied" — a backgrounded call now HOLDS for foreground rather than failing instantly (#337). toolName '_pairing' marks a pairing request. Read-only: this cannot answer a prompt, and no tool can — only the user can, on the device.

</details>

<details markdown="1">
<summary><code>get_preference</code> · no per-call prompt</summary>

Read a Haven user preference by key. Whitelisted keys: terminal_scrollback_rows, terminal_tap_to_position_cursor, terminal_font_size, terminal_color_scheme, terminal_auto_switch_scheme, terminal_light_color_scheme, terminal_dark_color_scheme, terminal_locale, mouse_input_enabled, terminal_right_click, mcp_tunnel_endpoint_profile_id, mcp_wireguard_enabled, mcp_lan_bind_enabled, mcp_wireguard_tunnel_config_id, usb_guest_exposure_enabled, connection_logging_enabled, verbose_logging_enabled, remap_low_ports (#300 proot launch toggle), share_storage_with_guest (#301 proot launch toggle), bind_android_system (#304 proot launch toggle), proot_dns_mode (#446 - system|public|custom), proot_dns_servers (custom nameservers), toolbar_layout (string — the terminal keyboard toolbar layout as JSON; see set_preference for the shape), custom_desktop_command (string — the Custom (X11) desktop's session command), update_check_enabled (bool — #578 opt-in launch-time update check; off by default, and inert on a copy not signed with the GitHub-release key), update_check_last_run_ms (long — epoch ms of the last launch-time check; the once-a-day throttle is measured from it), update_check_last_notified_version (string — the version the user was last notified about; blank if never). Returns { key, value } where value's type follows the preference's type (int / boolean / string). Colour-scheme values are TerminalColorScheme enum names.

- `key` (string, required) — Preference key (see whitelist in description).

</details>

<details markdown="1">
<summary><code>get_process_exits</code> · no per-call prompt</summary>

Return system-initiated kills of previous Haven processes, newest first — the in-app equivalent of `adb shell dumpsys activity exit-info` (#494). Each: timestampMs; kind (DEBUG_APP_FORCE_STOP = Developer options' 'Select debug app' points at Haven and Android force-stops it, FORCE_STOPPED, FROZEN, LOW_MEMORY, ANR, SIGNALED, EXCESSIVE_RESOURCE_USAGE, OTHER_KILL); importance at death (100 foreground / 125 foreground-service / 400 cached); tookActiveWork (importance ≤ 230 — the death took user-visible sessions with it); and the system's description. Use this FIRST when diagnosing 'sessions disconnect in the background': an entry at the disconnect time = the process was killed (who and why is right here); no entry = the process survived and something cut the network instead. Crashes are NOT here — native ones are in get_native_crashes. Requires Android 11 (API 30). Read-only.

</details>

<details markdown="1">
<summary><code>install_apk_from_backend</code> · asks every call</summary>

Install an APK from a path on any connected backend (local, SSH/SFTP, SMB, rclone, Reticulum). Streams APK bytes via the existing FileBackend abstraction. Same Shizuku/system-installer fallback as install_apk_from_url. Because backend transfers can be slow (a big APK over SFTP/rclone/Reticulum), this validates synchronously (missing file, directory, size cap → immediate error) then streams + installs in the background, returning {pending:true, staging:true} right away rather than blocking past the request timeout. Poll get_app_info: `activeInstall` has live phase/bytes while in flight (#331), `lastInstall` the terminal outcome (and /mcp reconnect if Haven is updating itself). Gated by Settings → Agent endpoint → "Allow agents to read file contents" and confirmed per-call.

- `path` (string, required) — Absolute path to the APK file on the chosen backend.
- `profileId` (string, required) — Connection profile ID, or 'local' for the device filesystem.

</details>

<details markdown="1">
<summary><code>install_apk_from_url</code> · asks every call</summary>

Download an APK from a URL and install it on the device. Validates the URL synchronously, then downloads + installs in the BACKGROUND and returns {pending:true, staging:true} immediately — a large APK on a slow link would otherwise outlast the request timeout (#331). Poll get_app_info: `activeInstall` carries the live phase (connecting/downloading/installing) and bytes/totalBytes while in flight; `lastInstall` carries the terminal outcome. With Shizuku running and granted, install is silent via `pm install`; without it a 'tap to install' prompt/notification appears on-device. A truncated transfer (dropped connection) is rejected against the advertised Content-Length rather than staged — a partial APK still passes the zip-magic check and would fail on-device with 'problem parsing the package', so `lastInstall` reports the short read instead. Useful for agent-driven self-update or sideloading over VPN where wireless ADB isn't reachable. NOTE: Android's network-security policy blocks cleartext http:// to anything but localhost, so an http:// URL on the LAN (e.g. a workstation IP) is rejected — use https://, or install_apk_from_backend (SFTP/rclone/Reticulum) which carries no cleartext.

- `url` (string, required) — http(s) URL pointing at a signed APK file. Should resolve to APK bytes (no HTML wrapper).

</details>

<details markdown="1">
<summary><code>list_paired_clients</code> · no per-call prompt</summary>

List the MCP clients paired with Haven — the clientInfo.name values that passed the first-connect pairing prompt and may call tools. For each: `name`; `autoApprove` (true when the user has enabled 'Skip approval prompts' for it under Settings → Agent endpoint → Paired MCP clients, so its calls bypass per-call consent); and `isCaller` (true for the client making this request). Read-only.

</details>

<details markdown="1">
<summary><code>list_standing_policies</code> · no per-call prompt</summary>

List Tier-3 standing policies: id, client, description, covered tools, argConstraints, maxCallsPerMinute, expiresAt, remainingMinutes, enabled, active. Read-only.

</details>

<details markdown="1">
<summary><code>open_developer_settings</code> · no per-call prompt</summary>

Open Android's Developer Options screen via ACTION_APPLICATION_DEVELOPMENT_SETTINGS so the user can flip Wireless debugging or other developer toggles. Tap-equivalent — the screen opens but no setting is changed without the user touching it.

</details>

<details markdown="1">
<summary><code>read_logcat</code> · asks once per session</summary>

Read recent Android system log lines via Shizuku, so the agent can observe foreign-app behaviour (network calls, crashes, lifecycle) during F-Droid tester reviews. Requires Shizuku running + granted (no separate READ_LOGS grant on Haven — logcat is read as Shizuku's shell uid, which already has the permission). Optional `packageName` resolves to a `--uid` filter via `pm list packages -U`; combine with `filter` for tag-level narrowing. Returns the raw logcat block; the agent parses it. `lines` is capped at 5000 and the response payload is capped at 256 KiB (truncated:true when either limit hits). Use this whenever an MR review needs log-level observation of a non-Haven app — the Haven local shell's Alpine proot can't reach /system/bin/logcat.

- `filter` (string) — Standard logcat filter expression, e.g. 'NOVA:V *:S' to keep only NOVA-tagged lines, or '*:E' to keep only errors. Appended verbatim to the logcat command.
- `lines` (integer) — Number of recent lines to return (default 200, capped at 5000). Maps to logcat -t.
- `packageName` (string) — Filter to this app only. Resolved to '--uid <uid>' via 'pm list packages -U'. Errors out if the package is not installed.
- `pid` (integer) — Filter to this process pid only. Maps to logcat --pid <pid>. Use when you already have the pid (e.g. from a previous capture or pgrep).
- `since` (string) — Only return lines after this point. Pass a logcat timestamp ('MM-DD HH:MM:SS.SSS') or an epoch-ms integer. Maps to logcat -T.

</details>

<details markdown="1">
<summary><code>restart_app</code> · asks every call</summary>

Restart Haven's own process — kill it and relaunch the app. The missing rung of the self-hosting loop: install_apk_from_backend stages a self-update, but the running process is held alive by Haven's persistent foreground service, so the new APK only takes effect on a process restart (previously only a manual Force-stop could do it). Call this right after get_app_info shows lastInstall.ok but the version hasn't changed. The MCP server lives in this process, so the link DROPS on the kill and the call returns just before it — the client must reconnect (/mcp reconnect), then re-check get_app_info for the new version. Also useful to recover a wedged UI. No-op-safe: it only relaunches Haven's own launcher activity. Returns { restarting:true }.

</details>

<details markdown="1">
<summary><code>revoke_standing_policy</code> · no per-call prompt</summary>

Revoke (delete) a standing policy by id — see list_standing_policies. Pure privilege reduction, so no prompt; the user's kill-switch lives on the Agent activity screen.

- `id` (string, required) — Policy id from list_standing_policies.

</details>

<details markdown="1">
<summary><code>set_preference</code> · asks once per session</summary>

Write a Haven user preference. Whitelisted keys (and their types): terminal_scrollback_rows (int 100..25000), terminal_tap_to_position_cursor (bool), terminal_font_size (int 8..32), mouse_input_enabled (bool), terminal_right_click (bool), terminal_color_scheme (string — a TerminalColorScheme enum name, e.g. HAVEN, DRACULA, NORD, GRUVBOX; case-insensitive), terminal_auto_switch_scheme (bool — when true the active scheme follows system light/dark via the light/dark keys), terminal_light_color_scheme (string scheme name), terminal_dark_color_scheme (string scheme name), terminal_background_opacity (float 0.0..1.0 — below 1.0 the terminal renders over the device wallpaper), terminal_locale (string, e.g. zh_CN.UTF-8 — exported to local terminal sessions as LANG/LC_ALL; glibc distros need the locale generated first), mcp_tunnel_endpoint_profile_id (string SSH profile id, empty to clear), mcp_wireguard_enabled (bool), mcp_lan_bind_enabled (bool — also bind the device Wi-Fi/LAN address for direct same-network reach), mcp_wireguard_tunnel_config_id (string tunnel config id the MCP server keeps up as its WG carrier, empty to clear), usb_guest_exposure_enabled (bool — master gate for usb_attach_to_guest), connection_logging_enabled (bool — audit-log connection lifecycle events to Settings → View connection log; off by default; enable before reproducing a connection issue, then read get_connection_log), verbose_logging_enabled (bool - per-session transport tracing, captured into each ConnectionLog entry's verboseLog; off by default. Needed AS WELL AS connection_logging_enabled: the RDP decode breakdown, the negotiated graphics capabilities and the discarded-bitmap detail exist nowhere else), gpu_use_venus (bool — experimental venus+zink GPU stack for accelerated desktops; off = virgl/virpipe), remap_low_ports (bool — #300 proot launch toggle: remap guest privileged ports +2000), share_storage_with_guest (bool — #301 proot launch toggle: mount /storage + /sdcard into the local guest; default on), bind_android_system (bool — #304 proot launch toggle: bind Android's read-only /system, /vendor, /apex, /product, /system_ext, /odm into the guest so it can run Android native binaries like getprop/toybox; default off, exposes device internals), proot_dns_mode (string - #446: which resolvers the local Linux guest gets in /etc/resolv.conf. "system" (default) uses the network's own resolvers, "public" uses Google 8.8.8.8 + Cloudflare 1.1.1.1 (the old hardcoded pair), "custom" uses proot_dns_servers. Networks that block outbound port 53 to anything but their own resolver make "public" fail silently - package installs just hang), proot_dns_servers (string - comma/space separated IP literals for "custom"; hostnames are rejected because resolv.conf has no way to resolve them), toolbar_layout (string — the terminal keyboard toolbar as JSON: a 2-element array of rows, each row an array whose elements are either a built-in key id string ("esc", "paste", "text_input", "arrow_up", "ctrl", "home", … — see ToolbarKey) or a custom-key object {"label":"…","send":"…"}; set validates against ToolbarLayout and replaces the WHOLE layout, so get_preference it first, edit, and write it back — e.g. add "text_input" to a row to surface the floating-text-input key), custom_desktop_command (string — the Custom (X11) desktop session command run at its next start; the command IS the session: when it exits the desktop stops, and a command that dies at startup surfaces its output in the desktop row's error state), update_check_enabled (bool — #578: look for a newer GitHub release at launch, at most once a day. Off by default. Turning it ON is what arms the launch-time path; check_for_update runs a check right now regardless)), update_check_last_run_ms (long epoch ms — set to 0 to CLEAR the once-a-day throttle), update_check_last_notified_version (string — set to "" to CLEAR the already-told-you dedup). Those two exist so the launch path can be exercised for real: clear whichever gate you are testing, restart Haven, and watch checkOnLaunch run. check_for_update deliberately cannot substitute — it runs the on-demand check, which posts no notification and touches neither gate. Takes effect on the next local session/command. Returns { key, value }.

- `key` (string, required) — Preference key (see whitelist).
- `value` (any, required) — New value. Type must match the key's type — int for the *_rows / *_size keys, bool for the rest.

</details>

<details markdown="1">
<summary><code>unpair_mcp_client</code> · asks once per session</summary>

Remove a paired MCP client from Haven's allowlist. It must be approved again via a fresh pairing prompt the next time it connects, and any persistent auto-approval for it is revoked. Use list_paired_clients for exact names. Note: this gates *new* connections — a client with an already-established session may keep working until Haven restarts. The pairing allowlist is the trust boundary, so there is intentionally no MCP tool to *add* a client (that only happens through the on-device pairing prompt) or to grant a client auto-approval (that's UI-only). Gated by consent.

- `name` (string, required) — Exact clientInfo.name to un-pair, as shown by list_paired_clients.

</details>
