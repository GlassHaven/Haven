# Haven Product Vision

## Thesis

Haven is a **thin-client operating system for distributed compute, storage, and presence**. The pocket device in your hand is not the computer — it's the point of presence from which you reach the computers, the files, and the agents that do the work. Those computers and files are scattered: a workstation under the desk, a VM in a datacenter, a folder in Google Drive, a camera feed at home, a screen sharing something on the other side of the room. Haven is the single lens through which you see and operate on all of it.

The last decade convinced us that "desktop" and "files" are local nouns. The next one undoes that. Storage lives on whatever is cheapest. Compute runs wherever the data is. And a human and an AI agent now drive the same workflow together — sometimes in turn, often at once — across phones, laptops, and servers. **Haven's central design goal is to make that shared human-and-agent operation the primary case: the agent acting on the very surfaces the human is watching, through the same actions a tap performs, with the human always keeping the wheel.** Everything else — the four primitives below, the consent tiers, the self-hosting loop — exists to serve that goal. Haven treats this world as the default, not the exception.

## Identity

The strongest identity Haven has is: **the open-source, privacy-first mobile workspace.** Haven is the only active GPL-licensed terminal app with modern Compose UI, hardware key support, a local Linux environment, a unified cloud file browser, a real media toolchain, and a native GPU-accelerated Wayland desktop — in one APK, no accounts, no telemetry.

The GPL/privacy audience chooses Haven *because* it's open source. Every security choice — encrypted credentials, biometric lock, TOFU host keys, FIDO2 support, local storage only — reinforces this identity. That is the moat and the brand.

## The four primitives

A coherent OS abstraction reduces to four things: a place to keep stuff, a place to run stuff, a way to move bits between them, and a way to be present at all three — to reach in and operate them, and to be reached back by them. Haven is organized around those four primitives. The first three — **Namespace**, **Runtime**, **Gateway** — are the *substrate*: each holds state. The fourth — **Presence** — is the *surface*. It cuts across the other three, because every primitive needs a touchable surface, and it is itself **bidirectional**: humans and agents reach *in* to operate the substrate, while the substrate's surfaces — and the agents working in them — reach *back out* to the human's attention. Presence is a primitive of a different kind, and it carries its own rules — detailed as primitive 4 below.

### 1. Namespace — one filesystem across the universe

The file browser is the **unified namespace**. Local storage, SFTP, SMB, 60+ cloud providers via rclone, and PRoot-mounted Linux rootfs (Alpine / Debian / Arch / Void) are all surfaced as tabs in the same UI, with the same operations: copy, move, rename, delete, open, stream, convert, share. Cross-filesystem copy/move is a first-class operation — drag a file from Google Drive into an SFTP server and it goes through Haven without a local round-trip if the backends support it.

Where files live is an implementation detail. Actions — convert, encrypt, stream, share — apply wherever the file is, not only to local copies. Rclone's HTTP serve + Range requests mean a 5 GB cloud file can be transcoded, previewed, and streamed without ever touching the phone's disk.

### 2. Runtime — any shell, any window, anywhere

The **runtime** is the place you actually run things. Haven exposes four kinds:

- **Local shell** — a full Linux userland on the phone via PRoot — Alpine, Debian, Arch, or Void, installed side-by-side, each with its native package manager and dev tools. Anything that runs on Linux arm64 — language runtimes, build toolchains, AI/agent CLIs, ad-hoc scripts — runs here without Haven knowing or caring what it is.
- **Remote shell** — SSH, Mosh, Eternal Terminal, and Reticulum transports, with session persistence via tmux/zellij/screen, auto-reconnect, session restore, and a color-coded tabbed terminal that treats all four the same way.
- **Remote desktop** — VNC (with VeNCrypt/TLS), RDP (via IronRDP, with EGFX graphics-pipeline support), tunneled through SSH when you want the wire encrypted.
- **Local desktop** — two flavours, both running on-device. A native GPU-accelerated Wayland compositor (labwc/wlroots) in Haven's own process, GLES2-composited via AHardwareBuffer; and a multi-distro desktop manager that installs and runs full X11 (Xfce4 / Openbox) or nested-Wayland (Sway) environments inside a PRoot rootfs and surfaces them through the in-app VNC client. A real Linux desktop on the phone, distinct from any remote screen.

All four are runtimes in the OS sense: processes with input, output, a filesystem, and a network. Haven's job is to make switching between them feel like switching between terminal tabs on a desktop OS.

### 3. Gateway — the network is the substrate

The **gateway** is how runtimes talk to each other and to the outside world. Haven bundles the primitives of a programmable network:

- **Port forwarding** — Local (`-L`), Remote (`-R`), and Dynamic (`-D`, SOCKS5 proxy server) over any active SSH session, managed live from the UI.
- **ProxyJump** — multi-hop tunnels through bastions and jump hosts, visualised as a tree.
- **Transport proxies** — SOCKS5/SOCKS4/HTTP for reaching `.onion` or corp-restricted endpoints.
- **Overlay tunnels** — WireGuard, Tailscale, and Cloudflare Access let a profile reach hosts that have no public IP, without consuming the system VPN slot. Each tunnel is per-profile, ref-counted across protocols (SSH + VNC + SFTP through one handle), and survives network transitions. WireGuard is also Haven's default carrier for the agent endpoint (see §1a).
- **Single Packet Authorization** — a native fwknop SPA / port-knock client (`core/spa`: `FwknopPacket`, `SpaSender`, `SpaConfig`) sends one encrypted packet that opens a firewall port just long enough to connect. A service can stay fully port-closed to the internet until Haven knocks — reachability without exposure.
- **Network-aware reconnect** — NetworkMonitor triggers an immediate reconnect when WiFi/cellular/VPN flips, instead of waiting for a TCP timeout.
- **Mesh** — Reticulum transport for off-grid connectivity.
- **Service publishing** — the rclone media server, the HLS streaming server, and the Wayland socket (via Shizuku) turn the phone itself into a host that other devices on the LAN can reach.

You should be able to reach any service from any runtime with one configuration step, and the connection should survive network transitions without your workflow fraying.

### 4. Presence — the surface, in both directions

Every primitive needs a touchable surface, and Presence is that surface. But it runs in **two directions**, and that is what makes it a primitive rather than a coat of paint. One direction is the rule for *where Haven owns the surface at all*. The other is that the surface is *shared with agents*, who both drive it and reach back through it. A VNC session of "a screen on the other side of the room," an inline overlay an agent pushes a chart into, the terminal you read your agent's progress in — all of these are presence.

#### Build vs delegate

The decision of whether Haven builds a surface or hands off to the host OS is not aesthetic — it's structural. The rule:

> **Build the presentation where the user needs to compose that primitive with another one inside Haven. Delegate to the OS where the handoff is clean and the user just wants a destination.**

That rule produces a sharp dividing line:

**Haven builds its own presentation for:**

- **The terminal** (termlib fork with touch-first gesture layer, smart clipboard, OSC 8/9/52/133 wiring, toolbar, shell-integration features). There is no Android terminal primitive, and a terminal that can't be composed with SSH sessions, port forwards, file browser actions, and Wayland tabs in the same app isn't worth much.
- **The file browser** (Compose-based, tabbed, unified across six backends). Android has DocumentsProvider but it's fragmented, read-mostly, and doesn't support SFTP/SMB/rclone as first-class peers. Haven's file browser is where every other primitive becomes actionable — the single most important composition surface in the app.
- **The VNC and RDP clients** (JSch-tunneled VNC with VeNCrypt, IronRDP-backed RDP). Standalone VNC/RDP apps exist but can't see Haven's SSH sessions, port forwards, or connection profiles. Composability forces them in-app.
- **The native Wayland desktop** (labwc/wlroots compositor running in-process, GPU-composited via AHardwareBuffer). This is the most technically novel surface — there is no Android primitive for a Linux desktop. It's also the surface that lets Haven double as a workstation on big-screen outputs.
- **Embedded app windows** (a single guest GUI app under `cage`+wayvnc, surfaced live in an in-app overlay — `PresentationHost`, `EdgeIconDock`, `PipController` — with Picture-in-Picture, an edge-icon dock, and multiple concurrent restartable windows). One Linux app's window lives *in* Haven, composable with the terminal and file browser, instead of a tab-switch away.
- **The convert dialog, the port forward dialog, the preview/filter UI, the connection edit dialog.** These are pure composition surfaces — they wire two or three primitives together and only exist because the primitives live in one process.

**Haven delegates to the OS for:**

- **Media playback.** `ACTION_VIEW` + MIME type hands the file to VLC, MX Player, or whatever the user prefers. Android's intent contract is solid, the ecosystem is strong, and users already have opinions about players. Haven is the transport and transformation layer; playback is downstream. The rclone HTTP server URL, the local file URI, and the HLS playlist URL all survive the handoff.
- **HTML5 HLS playback.** Chrome + hls.js via `ACTION_VIEW` on the server URL. Writing a video element wrapper in-app would buy nothing.
- **File opening for non-media.** Tap a PDF → system PDF viewer. Tap an image → system gallery. Tap a text file → whatever the user installed. FileProvider + intent is the contract.
- **System authentication.** BiometricPrompt for app lock; the OS credential dialog for FIDO2.
- **Notifications, shortcuts, share targets.** All the places Android provides a standard surface.

The build-vs-delegate rule has a useful test: *if the user would want to invoke a second primitive while looking at this view, we have to own the view.* You want to copy a line from a terminal into the port-forward dialog → same app. You want to open a media file in a player → different app is fine. You want to drag a file from one backend tab to another → same app. You want to watch the file → different app is fine.

This is also why certain features are explicitly not going to be built (see "Scope boundaries" below): a text editor, a media player, a chat client for IRC/Matrix/XMPP. Those would be presentations of nothing — they don't compose with Haven's primitives, they just duplicate work the OS already supports well.

#### Shared with agents

The surfaces Haven builds are not only for human eyes. An AI agent operating alongside the user — whether running in PRoot on the device or in a remote shell Haven is connected to — needs to **work with and within what the user sees**. That imposes three concrete requirements on every presence surface Haven owns:

**Observable state.** Every view has state: which backend and path the file browser is on, what's in the terminal's current scrollback and which command wrote it, which file is loaded in the convert dialog with what filters, which port forwards are active and bound to which ports, what the connection status of each session is. That state must be exposed in a form an agent can read — the same StateFlow pattern the Compose UI already subscribes to. If a human can see it, an agent must be able to query it. The endpoint of this principle is that the screen itself becomes a readable resource: an agent that can pull *what is rendered right now* as a snapshot needs no human to describe the phone to it. Haven exposes terminal and remote-desktop snapshots today, a `capture_haven_ui` tool that returns Haven's *own* rendered screen, and that same capture as the addressable `ui://haven/screen` MCP resource (`resources/read`) — so an agent can pull what's on screen without even a tool call.

**Actionable API.** Every action a tap can perform must also be a function an agent can call. Haven's ViewModel methods — `convertFile`, `streamFile`, `playMediaFile`, `navigateTo`, `setPortForwardingDynamic`, `connect`, and their peers — are the vocabulary that both humans and agents use to operate the primitives. There is no "agent-only" bypass layer. If the agent can do it, the user can watch it happen in the UI. If the user can do it, the agent gets a tool-use handle for it.

**Reachable surface.** The sharing runs both ways. As well as reading state and calling actions, an agent can *initiate* a surface to reach the user: push an image or sound into an inline overlay (`present_media`), bring a single guest app's live window to the foreground (`present_app`), raise a system notification (`raise_notification`), queue a message into the user's terminal view (`queue_terminal_input`), or request a decision through the consent sheet. The human is present at remote screens; the agent, symmetrically, is present at the human's attention. Every reach-out is gated by the same per-action consent and rendered on a surface the user is already looking at — it is a channel *to* the user, never a channel *around* them.

These three rule out two failure modes:

1. **Silent automation** — the agent acting through a hidden channel the user can't see. Every agent operation, in either direction, is reflected in the same UI surface a human would use, so the user can observe, interrupt, and take over mid-workflow. "Haven is doing something" and "the screen is showing something" must always be the same thing.
2. **Divergent state** — the agent's model of "what's happening" drifting from the user's. Both subscribe to the same state flows, so what Haven shows is ground truth for both.

The practical consequence is that the agent transport — a tool-use server (MCP or equivalent) that exposes Haven's ViewModels as callable tools over a local loopback socket — is not an optional add-on. It falls out of the build-vs-delegate rule directly: if we build a presence surface because it needs to compose with other primitives, we also need to make that surface addressable from outside the UI process, because an agent is just another caller that needs the same composition. Humans tap, agents call, both observe, and both can be reached — same surface, same state, same truth.

## The host as a privileged peer — brokering the phone's own capabilities

The four primitives reach *outward* — to other machines' files, shells, networks, and screens. But the device in your hand is itself a computer, and it holds capabilities the runtime can't reach on its own: the USB device on the OTG port, the camera, location, sensors, the clipboard, notifications, biometric hardware, the system log. On Android these sit behind the framework's permission model and SELinux; a process inside the PRoot runtime — and therefore an agent running in it — cannot open them directly. A Linux program that expects raw `/dev` nodes and udev just hits a wall.

The resolution is a pattern, not a one-off workaround: **when a runtime or an agent is blocked on a host capability, Haven brokers it.** Haven is the one component that is a real Android app, with framework access and a path to user consent. It opens the capability the Android way, gates it behind an explicit prompt, and re-exposes it as a primitive the runtime and the agent use through the same tool-use surface as everything else — humans tap, agents call, both observe. The phone OS is therefore not only a *delegation target* (hand a file to a media player, raise a biometric prompt) but a *source of brokered capabilities* the distributed workflow composes with.

This is shipped, not hypothetical. The USB broker is a complete vertical slice: a device opened via the platform's USB API and surfaced either as a device-specific tool set or as a file descriptor bridged into the runtime, with a userspace proxy socket, hidraw/libudev shims for guest HID and Mono apps, and per-device consent. The broker now also composes with the outward Gateway primitive: a brokered device re-exposes not only into the local runtime but to a **remote host over USB/IP** — Haven runs a userspace usbip server (the Android kernel ships no `vhci-hcd`) and auto-forwards a phone-attached device on connect, so a phone-held **YubiKey** becomes a real device node on a server that has no security key of its own and enrolls an SSH key (`ssh-keygen -t ed25519-sk`) with the touch on the phone (shipped v5.59.65, device-verified end-to-end). A capability brokered once on the device is then usable anywhere the four primitives reach. The same pattern now also brokers the **system log** (`read_logcat`, routed through Shizuku because Haven holds no `READ_LOGS` grant) and **on-device adb reach** over a system VPN (`expose_adb`/`unexpose_adb`). The camera, sensors, location, and clipboard are the same shape. Framed for an agent, these brokered capabilities are its *senses*: the build output and system log it can already read (`read_logcat`), and — as the same broker pattern reaches them — the IMU, camera, and location that would let its model of *what is physically happening* come from the device itself rather than from a human's description. The boundary stays sharp — Haven brokers *access* to the device, it does not become the device's app or reimplement a vendor's tool. And it stays honest about the floor: capabilities the platform only grants with root (raw bus access, udev, kernel modules) are a power-user, root-gated path, never the default. The non-root broker is the common case, because the point is to work on the phone the user actually has.

This is the build-vs-delegate discipline pointed inward: build the broker where the runtime needs a capability the platform won't hand to a non-app process; delegate where a clean handoff already exists. Together with the four outward primitives, it is what makes Haven a *home* for an agent on the device as well as a *bridge* to everything beyond it.

As these brokers multiply — USB and the system log and adb today, the camera, sensors, and location next — they converge on a single surface: a **Devices** manager that lists every capability the phone is currently brokering and to which sink (the agent, the local runtime, a local VM, a remote host), behind one consent rail and one agent-readable registry of **Bridges** (`list_bridges`, generalising `list_usb_exports`; the point-solution verbs become façades over it). This is the inward-broker's equivalent of what the unified file browser is to the outward Namespace — one surface where a new source lights up every sink, and a new sink every source. A Bridge is simply *an instance of this inward broker, engineered across the gap between an Android capability and a Linux consumer that can't reach it.* It stays a surface over the four primitives, never a fifth. The registry ships today as the `list_bridges` tool (`list_usb_exports`, the USB-VM and live-session listings, and the serial bridges are already reported through it); the remaining surface is the Devices UI over the same registry. The design is sketched in [docs/design/bridges.md](docs/design/bridges.md).

## The integration thesis — composition is the product

The four primitives are not the product. What you can *do by composing them* is the product. A coherent OS is one where they compose without friction. Haven's design target: every workflow below should be one flow inside the app.

- Tap a 4K MKV sitting in Google Drive → ffmpeg reads it over HTTP from rclone VFS → the frame preview appears in 3 seconds → tweak brightness → pick "H.264, back to cloud" → the converted file appears in the same Drive folder without ever touching local disk.
- SSH to the workstation → forward port 5901 → tap the VNC profile that targets `localhost:5901` → desktop opens in the same app, keyboard and clipboard shared.
- In the local PRoot shell, start whatever agent CLI you use, pointed at a project directory. It reads files, runs `git push` over the SSH agent you forwarded from your laptop, and you watch it work on the same screen where the SSH tab lives.
- Copy a log directory from an S3 bucket to an SFTP server: long-press → cut → switch tab → paste. Rclone does the server-side copy when possible; otherwise Haven streams it through.
- Stream a video from a cloud folder to the Chromecast across the room via HLS, copy the LAN URL from the snackbar, paste it into a message to your partner's phone so they can watch too.

The same composition points the other way too — at Haven developing itself. These last entries are the frontier the work is heading toward, not one-tap flows today:

- The dev agent builds Haven on the workstation over SSH, pulls the fresh APK through the namespace, installs it on the very phone it is operating (`install_apk_from_backend`), then *drives the new build and diffs the screen against its expectation* to file a verdict — a release-verify cycle with no human hand on the device. The install half ships today; the drive-and-diff half is now real — `capture_haven_ui` sees Haven's own rendered screen and `tap_haven_ui` / `swipe_haven_ui` drive it (device-verified on a Pixel 8 Pro; the trail is told in the self-hosting loop section below). Text entry into Haven's own fields is deliberately *not* part of this — agents enter data through the ViewModel tools (`create_connection` and peers), not by simulating typing into the app's own chrome.
- Further out: a reflex policy holds the phone's camera on the workstation monitor through two servos on a printed dock, so the agent reads its own viewing angle through the namespace the way it reads any other stream — the point where presence stops being only software.

None of these require leaving Haven, installing a second app, or running a `curl | ssh` incantation. Each uses two or more of the four primitives — and the last two show the same primitives, composed, reaching back to build the app itself. This is the thesis.

## The agent era — agents are a first-class user

LLM agents are a new class of actor, and they need the same primitives a human does: credentials, shell access, a filesystem, a network, a surface. An agent is just a very persistent process that wants to SSH, read files, run commands, transform media, and occasionally show its operator something — exactly what Haven already mediates for a human. Haven has no opinion on which agent you run: any CLI that speaks Linux conventions is as welcome as any other, and the app ships no vendor-specific integrations, installers, or brand surfaces.

What this means in practice:

- **Agents in the local runtime**: PRoot is a first-class place to run whatever AI CLI, local dev tools, and language runtimes the user wants. No root, no Termux, no rebuild needed. Haven neither knows nor advertises which CLI you're running.
- **Agents in a remote runtime**: SSH to the workstation where your real agent lives, keep the session alive across network drops, and come back to a session that remembers what the agent was doing.
- **Credentials that humans and agents share**: one encrypted keystore, SSH agent forwarding so the remote agent can use keys that never leave the phone, host key TOFU so a compromised gateway can't silently inject itself.
- **Files that humans and agents both operate on**: the unified namespace means "the project folder" is one path whether the agent is running in PRoot, on the workstation, or pulling from cloud storage.
- **Senses that ground the agent**: build output and the system log are readable today (`read_logcat`); the IMU, camera frames, and a snapshot of Haven's own screen are the next shape of the host-broker (see "The host as a privileged peer" and §1a). The aim is that an agent's model of *what is physically happening* comes from the device itself, not from a human's description of it.
- **Shared presence (both directions)**: agents address Haven's ViewModels through a tool-use transport (see "Presence", primitive 4). They *drive* surfaces the user is watching — open the convert dialog, toggle a port forward, start a stream, change the active SFTP path — *and* they *reach back* to the user — push media into an overlay, bring a guest app's window forward, raise a notification, ask through the consent sheet. Every action and every reach-out lands on a surface the user can see; no hidden automation, no divergent state. The UI is ground truth for both.
- **Observation**: the terminal tab, the file browser, and the persistent notification give a human operator a clear view of what their agent is doing and where. Haven is the dashboard, not a black box.

Haven ships no model and no agent persona of its own. It ships a chat surface that is a client for endpoints the user configures, and the layer that makes AI assistants useful wherever a human wants to point them — mobile, distributed, multi-backend. The agent sees the same OS abstraction the human does, and operates through the same surfaces the human uses, so the human always keeps the wheel.

## Consent tiers — from per-action to standing policies

The shipped trust model has two working levels: reads are free, writes raise a non-skippable consent sheet (with an opt-in "allow for N minutes" window and a Settings-level paired-client bypass for a client the user trusts). That is right for one-shot actions and wrong for *reflexes* — a control loop ticking many times a second cannot raise a sheet per tick, and a build watchdog that needs permission to notice a crash is not a watchdog. The model therefore grows to four tiers, each fully audited:

- **Tier 1 — observe.** Read-only tools and resources. No prompt. *(✅ shipped.)*
- **Tier 2 — act once.** One-shot writes. Per-action consent sheet. *(✅ shipped.)*
- **Tier 3 — standing policy.** A declarative grant the agent proposes and the user installs once (`create_standing_policy` — the consent sheet spells out the full scope), against a declared scope (exactly which tools, optionally pinned arguments), a rate ceiling, and an expiry (≤24 h). A kill-switch lives on the Agent activity screen; every covered call is still written to the audit table; beyond the rate ceiling, calls fall back to per-action prompts. *(✅ shipped — scope-binding, rate ceilings, expiry, and the kill-switch are built; the policy binds to one named client and can never cover the policy tools themselves, `install_apk_*`, or `unpair_mcp_client`.)*
- **Tier 4 — irreversible & physical.** Anything that spends money, moves mass, replaces the running app, or edits the Memory/constitution (next section). Always per-action, never standing, no matter how often it recurs. *(Forward-looking.)*

The tier is a property of the **verb, not the caller**. A human tapping a Tier-4 action *is* the consent; an agent calling it raises the sheet. Reflexes never escalate themselves: a Tier-3 policy that wants a Tier-4 action must surface it to a human like everything else.

## Memory — the agent's persistent constitution

An agent that works on the same project across many sessions needs somewhere durable to keep what it has learned and the rules it operates under — and, consistent with "the UI is ground truth, no hidden channel," that store should be a **surfaced artifact the human can read and edit**, not state buried inside a model's context. Haven's role here is not to ship a model or a memory service; it is to host the agent's *constitution* — its notes, decisions, and standing instructions — as a human-auditable, human-editable document set the agent maintains. Because a write to it reshapes all of the agent's future behaviour, editing the constitution is a **Tier-4** action: irreversible in spirit, always surfaced. This is forward-looking; the nearest shipped substrate is turn-based agent↔agent conversation (`send_to_agent`, `await_turn`, `read_last_turn`), prototyped in `docs/haven_convo_prototype.py`.

## The self-hosting loop — Haven develops Haven

Haven began as a vibe-coding experiment: an agent on a workstation, a repo on GitHub, and a human holding the phone to see whether the build works. Two shipped verbs already let that loop reach back into the device — `install_apk_from_url` / `install_apk_from_backend` deploy a build onto the phone the agent is operating, and `enable_wireless_adb` / `expose_adb` open the door to driving it. The loop can *act on its own body* today.

That loop has since matured past "does it build?" to "does it work *on the device, over the real network*?" In one session an agent built Haven, deployed it onto the phone over the same WireGuard carrier the product targets, drove it (opened a connection, served and transcoded a file), read the device's behaviour back through brokered observability (the connection log, a queryable `lastInstall` result, even an off-device file server's logs), root-caused a defect that only surfaces on the phone — an SMB share going stale when the tunnel drops — fixed it, and redeployed; and in passing it hardened the very deploy path it rides on (a false install-timeout, reconnect-on-use, a tap-to-install notification that survives backgrounding). The human still approves the consent and install prompts and is the eye on the screen — the diagnosis and the fix are the agent's. This is the inspiration made concrete: the product is good enough to build the product.

What was missing is symmetry: the loop must also *perceive* and *drive* its own body. The first cut of both now exists as MCP tools — `capture_haven_ui` returns Haven's own rendered screen (a `PixelCopy` of the foreground window, read-only and `FLAG_SECURE`-aware), and `tap_haven_ui` / `swipe_haven_ui` inject pointer input into that window in the same pixel space the capture reports — so an agent can install a build, see the result, and drive it through the same surface a finger would. Injection is refused while a consent prompt is showing, so it can never self-confirm. Capture and the two pointer verbs are device-verified on a Pixel 8 Pro (install → capture → tap-navigate → swipe → re-capture diff). The capture is exposed both as the `capture_haven_ui` tool and the addressable `ui://haven/screen` resource (`resources/read`), sharing one consent gate. Tier-3 standing policies now close the prompting gap: a drive-and-diff loop installs one scoped, rate-capped, expiring grant (`create_standing_policy`) instead of prompting per tap, every covered call still audited, revocable from the Agent activity screen. Text entry into Haven's own fields is *not* on any list: agents enter data through the actionable-API tools (`create_connection`, `update_connection`, `set_preference`, …), not by simulating typing — and Haven's Compose fields take text through an IME `InputConnection` that raw key injection doesn't reach anyway. Until the rest lands the loop runs under Tiers 1–2 with a human eye on the screen, and any Tier-4 act — replacing the running app, editing the constitution — is surfaced for a human's tap. The agent↔agent half of the loop is shipped: `send_to_agent` delivers a turn to another agent's REPL, `await_turn` waits for it to finish, `read_last_turn` reads the result.

## Development priorities

The thesis is clear; the work is removing the seams. Priorities are ordered by leverage, not by effort.

### Next iterations

The current arc, each thread detailed in its section below:

1. ✅ **Warm the Reticulum extremity** — *shipped (v5.59.10).* The mesh now carries file transfer (browse/download/upload over the rnsh command-exec substrate) and a tunnelable port (`-L`/`-D`), not just a shell.
2. ✅ **Close the namespace gaps** — *shipped.* SFTP/SMB media streaming shipped via the `SftpStreamServer` loopback bridge (the "ffmpeg-with-libssh" framing was superseded), and age file encryption shipped (v5.59.83) — universal Encrypt/Decrypt on every backend, plus the MCP verbs (§2).
3. ✅ **Consolidate the agent-presence layer** — *shipped, and grown past the plan.* The reach-back tools run on one consent + audit surface (§1a); since this list was written the layer also gained `present_web`, the notification-listener ring, directory watches, `dump_haven_ui`, and turn-based agent↔agent conversation (`send_to_agent`/`await_turn`/`read_last_turn`).
4. **The MCP plugin bus** — a second cross-protocol verb plus discovery of external MCP-publishing Android apps (§1b). This is the open thread.

### Growth diagnostic — where each primitive sits today

A growth plan needs a diagnosis. Mapped onto the four primitives and the seams between them, Haven currently looks like this:

- **Heart — Namespace (SSH, SFTP, rclone): three organs, one operation code path.** SSH and SFTP share a JSch session per profile (`SshSessionManager.SessionState` reuses the SSH client and exposes `sftpChannel` alongside the shell channel). Rclone, SFTP, SMB and the local backend now route through one `FileBackend` interface (`#126` stages 1-3) for list/delete/mkdir/rename/readBytes/writeBytes — a new universal action (encrypt, inspect, hash) now lights up every tab at once. The remaining heart work is feature-shaped (metadata inspection is still shallow) rather than refactor-shaped.
- **Shoulders — Runtime + remote Presence (VNC, RDP, SMB, i18n): exercising well.** Tunnel-aware protocols open SSH port-forwards on demand and a `tunnelDependents` set tears the shared tunnel down cleanly when the last consumer leaves (`#121`). VNC and RDP now share a `RemoteDesktopSession` abstraction (`#128`). Localisation reaches twelve locales with the lint-warning safety net (`#125`). EGFX (ClearCodec + RemoteFX Progressive) shipped (v5.24.69) and is verified against Windows Server 2025; the remaining reach is GNOME Remote Desktop interop (it needs server-redirection following, #117), more codecs, and per-locale changelogs rather than missing seams.
- **Extremities — Gateway/Reticulum: warming.** `ReticulumTransport` now exposes `execCommand` beyond `RnshShellSession`, and the mesh carries the same load-bearing surface SSH has: the file browser does list/download/upload over Reticulum and `-L`/`-D` forwarding tunnels TCP/SOCKS through it (v5.59.10), built on the rnsh command-exec substrate rather than a parallel stack. Remaining: a persistent sftp-subsystem path for throughput (today it is one Link per directory op — small-file oriented) and file sync.
- **Face — Presence/agent (MCP): opinionated, and now bidirectional.** Per-backend tools collapsed into one tool with a backend argument (`#127`). Cross-tab verbs landed against `AgentUiCommandBus` (`navigate_sftp_browser`, `focus_terminal_session`, `open_convert_dialog_with_args`). The first cross-protocol verb shipped — **`compose_workspace`** opens an entire workspace (terminal + file-browser + desktop + Wayland items) in one call through the same `WorkspaceLauncher` the user's tap drives. And the reach-back direction is now live: `present_media`, `present_app`, `present_web`, `raise_notification`, and `queue_terminal_input` let the agent surface things *to* the user, with `send_to_agent`/`await_turn`/`read_last_turn` letting two agent REPLs hold a turn-based conversation. No single-purpose client has the substrate to compose those primitives or to reach the user through the same consent stack; this is the moat.

Priorities below are ordered by what this diagnostic implies.

### Juxtapositions — completed arc

The 2026-04-20 audit found six places where Haven's surface looked unified but the implementations underneath were parallel. Each instance taxed future work: a new universal action took N implementations to land, a new backend had to plug into N call sites, and the tax compounded. All six have now landed:

- ✅ **File browser per-backend dispatch (largest instance).** `FileBackend` interface in `core/sftp` unifies list/delete/mkdir/rename/readBytes/writeBytes across local, SMB, rclone, and SSH (`#126` stages 1-3). The `when { isLocal -> … isRclone -> … }` switch in `SftpViewModel` is gone.
- ✅ **MCP tools per backend.** Per-backend `list_sftp_directory`/`list_rclone_directory`/etc. collapsed into single tools that take a backend argument (`#127`). Agent vocabulary is shorter and uniform.
- ✅ **VNC and RDP as parallel remote-desktop stacks.** `RemoteDesktopSession` abstraction shipped (`#128`). Universal verbs (record, paste, scale-to-fit, attach-tunnel) are now one implementation, not two.
- ✅ **Keystore as three stores.** Unified `Keystore` interface over SSH keys (Tink AEAD), FIDO2 credentials, and encrypted profile credentials (`#129` stages 1-5). One audit screen, one biometric gate with 30s session-unlock window, one `Keystore.fetch()` primitive.
- ✅ **Foreground service hardcoded to known transports.** `ForegroundSessionParticipant` interface; managers register themselves (`#130`). Adding a new transport no longer requires editing `SshConnectionService`.
- ✅ **Session managers as dispatch, not abstraction.** Unified `Session` abstraction; `list_sessions` MCP tool covers all seven transports through one interface (`#131`).

Already unified before the audit (no work needed): network discovery (`NetworkDiscovery` merges mDNS, ARP, Tailscale, and SMB scan into one `StateFlow<List<DiscoveredHost>>`); the ffmpeg convert pipeline (URL-driven, no per-backend special-case); `ConnectionLogRepository` writes (one Room table, callers populate per-layer, which is fine).

The architectural payoff is now banked: every new universal action lands once, every new backend lights up everywhere. Remaining work in §1-§5 is feature-shaped, not refactor-shaped.

### 1. Composition polish — make the primitives snap together

Whenever two primitives meet, there should be zero friction. Current gaps:

- ✅ **SFTP/SMB media** — shipped. convert/preview/stream/tap-to-play work for every backend via the `SftpStreamServer` loopback bridge (ffmpeg reads over HTTP Range requests against a local socket), *not* the originally-planned ffmpeg-with-libssh — that was the wrong tool.
- **Agent forwarding UX** — the plumbing exists; the story of "forward my phone's keys to the remote agent and be able to trust it" needs to be a dialog, not a config file.
- ✅ **Workspace profiles** — shipped. "Save current as workspace" snapshots active terminal sessions (SSH/Mosh/ET/Reticulum/Local), file-browser tabs (SMB), remote desktops (RDP), and the Wayland tab into a named bundle; one tap reopens the same composition. `WorkspaceLauncher` is the orchestration substrate the matching `compose_workspace` MCP verb in §1b is built on.
- **Desktop ↔ file browser ↔ terminal** — agent-driven cross-tab verbs landed (`navigate_sftp_browser`, `focus_terminal_session`, `open_convert_dialog_with_args`). Human-driven equivalents — drag a file from the SFTP tab into the native Wayland compositor, copy output from a terminal into the convert dialog — are the next layer.
- ✅ **rclone fused into shared operation code** — done via `FileBackend` (`#126`). All four backends route through one operation interface; new universal actions land once.
- ✅ **Reticulum carrying more than shell** — shipped (v5.59.10). File transfer (browse/download/upload over the rnsh command-exec substrate) and Reticulum-as-tunnelable-port (`-L`/`-D`) turned the extremity into a working limb. The Reticulum mesh is the only transport in Haven that survives full internet loss, so this is a unique capability, not a duplicate path.

### 1a. Agent transport — shipped (v5.24.81), since broadened

The shared-surface idea is now a concrete transport. Haven's local loopback MCP server exposes tools across read, write, and reach-back paths, and every non-read call surfaces a non-skippable bottom-sheet consent prompt before the action runs. The inventory below is a point-in-time snapshot of what landed in each wave — the live, generated tool list is [docs/mcp-tools.md](docs/mcp-tools.md). What landed:

- **Tool-use server** — MCP / JSON-RPC over HTTP loopback (port range 8730–8739), Streamable HTTP stateless transport. Disabled by default; toggled in Settings → Agent endpoint.
- **State inspection tools** (no prompt) — `list_connections`, `list_sessions`, `list_directory` (one tool, backend arg), `stream_sftp_file`, `read_terminal_scrollback`, `play_file`, `get_app_info`, `list_rclone_remotes`, `stop_stream`.
- **Action tools** (per-action consent) — `open_local_shell`, `send_terminal_input`, `add_port_forward`, `remove_port_forward`, `upload_file`, `delete_file`, `disconnect_profile`, `convert_file`, `set_terminal_font_from_url`, `open_developer_settings`, `enable_wireless_adb` (Shizuku-gated), `install_apk_from_url`, `install_apk_from_backend`.
- **Cross-tab UI verbs** (against `AgentUiCommandBus`) — `navigate_sftp_browser`, `focus_terminal_session`, `open_convert_dialog_with_args`, `connect_profile`. Direct demonstration that humans tap, agents call, both observe.
- **Reach-back tools** (added since — the agent→user direction) — `present_media` (image/sound into an inline overlay), `present_app` (a single guest GUI app's live window in the overlay), `raise_notification` (a system notification on the agent's behalf), `queue_terminal_input` (a line into the user's terminal view, typed at the next matching prompt). All consent-gated; all rendered on a surface the user is already looking at.
- **Local-desktop / PRoot lifecycle** (added since) — `list_distros`, `install_distro`, `delete_distro`, `list_desktop_environments`, `install_desktop`, `uninstall_desktop`, `start_desktop`, `stop_desktop`, `read_desktop_log`, `inspect_proot`, `get_proot_install_log`, `list_desktop_sessions`, `run_in_proot`. Drives the whole multi-distro proot + desktop pipeline over MCP, so integration tests run agent-side instead of by hand.
- **rclone sync + tunnels + gateway** (added since) — `start_rclone_sync`, `save_sync_profile`, `get_rclone_sync_status`, `list_live_tunnels`, `create_tunnel`, the SPA/port-knock tools (`set_spa`, `test_spa`, `set_port_knock`), and the adb-reach broker (`expose_adb`/`unexpose_adb`), plus host-capability brokers (`read_logcat`, the USB tools).
- **Audit and consent** — `AgentConsentManager` with foreground fail-closed semantics; `AgentAuditRecorder` writes every call to a Room table with redacted args; in-app "agent active" chip on the Connections top bar lights up on recent activity; `AgentActivityScreen` is the dashboard.
- **Discovery and transport** — **WireGuard is now the default carrier for the agent endpoint**: the device serves MCP on its WireGuard LAN IP and a remote client points there directly, surviving network transitions; the SSH `-R 8730` rule is kept as a fallback. Settings exposes the endpoint URL, an MCP-config JSON snippet, and the "Tunnel through SSH profile…" shortcut for the fallback path.

What's still ahead in this lane:

- **Backbone hardening — reliability + security parity with SSH.** The transport is a hand-rolled loopback HTTP server whose trust rests on network position (`isLoopback`) and a self-asserted `clientInfo.name`, and whose lifecycle is a daemon thread started from one place and never supervised — so it is neither as resilient nor as authenticated as the SSH terminal beside it. `isLoopback` is defeated in two directions (a co-resident app shares Android's loopback; a reverse-tunnel `-R` carrier lands remote traffic on the phone's loopback), and every grant keys on the spoofable name. The re-architecture — explicit per-carrier origin trust, a real pairing token, a service-owned actively-supervised lifecycle, and one shared MCP protocol core underneath both the host and client directions — is planned in [docs/design/mcp-backbone.md](docs/design/mcp-backbone.md). This is where the moat pays its security rent.
- **Consolidate the agent-presence layer** — the reach-back tools shipped as point features and the surface has since grown far past them (`present_web`, the notification-listener ring, `watch_directory`, `dump_haven_ui`, turn-based agent↔agent conversation). They already share one consent + audit surface (`AgentConsentManager` / `AgentAuditRecorder`), so "the agent showed me something" and "the agent asked me something" run through one channel; a single *documented* bidirectional presence model is the remaining write-up.
- **Own-UI perceive + drive — landed.** `capture_haven_ui` returns Haven's own rendered screen (read-only, `FLAG_SECURE`-aware) and `tap_haven_ui` / `swipe_haven_ui` inject pointer input in the captured pixel space; the capture is also the addressable `ui://haven/screen` resource (`resources/read`), and `dump_haven_ui` returns the same foreground UI as exact control bounds. **Tier-3 standing policies** complete the lane (see "Consent tiers"). **Text entry into Haven's own fields is deliberately out of scope** — agents enter data through the actionable-API tools (`create_connection` and peers) per the actionable-API rule, and Compose's IME-mediated `InputConnection` means raw key injection wouldn't reach those fields anyway. Terminal and remote-desktop snapshots remain the other screen resources.
- **Sensor / IMU / camera brokers** — extend the USB + `read_logcat` host-broker pattern to the device's own senses, so an agent's model of physical state comes from the device (see "The host as a privileged peer"), converging on the unified **Devices/Bridges** manager ([docs/design/bridges.md](docs/design/bridges.md)) so each new sense lands once and lights up every sink. Camera, location, and sensor reads have since shipped as one-shot device reads; the standing broker path is what remains.
- **More cross-tab agent verbs** — the bus pattern is proven; remaining surfaces (port-forward dialog with args, connection-edit dialog, key-deploy flow) are mechanical extensions.
- **Voice presence** — an audio modality over the same consent and reach-back surfaces: a spoken renderer for the consent sheet (opt-in hands-free approvals, Tier 4 excluded), event-based terminal narration (command exits and session events, never raw scrollback), and a `speak` reach-back verb whose spoken lines also land as a durable transcript on the Agent activity screen. Driving is the sharp case; the design is in [docs/design/voice.md](docs/design/voice.md).

### 1b. MCP as a bidirectional protocol — Haven as host *and* client

Section 1a covers Haven publishing an MCP server: agents reach into Haven's primitives. The mirror image is Haven becoming an MCP *client* — discovering and consuming capabilities published by other Android apps that speak the protocol. Together, the two halves turn MCP from a single-direction agent transport into a generic capability bus, with Haven as one peer among others. This is the one open thread of the current arc (next-iteration #4).

This is not the same as shipping an AI assistant or bundling a vendor's agent CLI (both excluded under "Scope boundaries"). Haven has no model of its own and no prompt language — the shipped chat screen is a client for endpoints the user configures, not a bundled assistant — only the primitives, the surfaces, and the ability to compose those primitives with capabilities that other locally installed apps choose to expose. The user is still the one pointing the agent at things; the agent is still operating through observable UI; the trust model is still per-action consent. What changes is what's in the agent's vocabulary.

Concrete shape:

- **Discovery without dynamic code.** Installed apps that publish MCP servers register via a documented Android `<intent-filter>` plus manifest `meta-data`. Haven enumerates them, surfaces them in Settings → Agent endpoints, and lets the user enable specific capability bundles. No bytecode is loaded from outside the APK; the F-Droid reproducible-build rule stays satisfied because each plugin is a separately source-built APK.
- **Composition with Haven primitives.** When external MCP capabilities are present, Haven's MCP server can advertise them as part of its own capability list (or proxy them transparently) so an agent connected to Haven sees one tool surface that combines Haven primitives with external capabilities. Example workflows: *Reticulum-fallback file sync* — Haven's SFTP plus an external "watch this directory" capability; *RDP clipboard ↔ SFTP path* — Haven's RDP clipboard plus an external "translate path" capability; *agent journal* — Haven's audit log plus an external "summarise" capability.
- ✅ **The first cross-protocol verb — shipped.** `compose_workspace` was the chosen one: takes a saved workspace id, dispatches every item (TERMINAL → WAYLAND → FILE_BROWSER → DESKTOP) through the same `WorkspaceLauncher` the human tap drives. The agent's vocabulary now contains a verb no single-purpose MCP client could express, because no single-purpose client has the substrate. `mirror_directory_with_fallback` (a cross-backend tree copy with a fallback backend) has since joined it; `move_session` and further candidates stay on the list — the moat is established and the next ones grow it.
- **Audit and consent extend unchanged.** External capabilities go through the same `AgentConsentManager` per-action prompt and the same `AgentAuditRecorder` Room table. The user can't tell from the consent sheet whether a tool is a Haven primitive or a plugin's; they don't need to.

The bidirectional direction is consistent with the four-primitives frame: Haven's primitives stay scoped, but the *agent vocabulary* over those primitives grows. The plugin system lives at the orchestration layer, never at the bytecode layer.

### 2. The namespace as the action surface

The file browser is Haven's highest-leverage surface because it's where every backend converges. Every action that applies to a file should be available on every file, regardless of where it lives. (The universal actions of next-iteration #2 are shipped; what remains here is polish — see the bullets.)

- **Universal action set** — convert, preview, stream, play, encrypt, share link, copy path, inspect metadata — consistent across local, rclone, SFTP, SMB, PRoot. encrypt and SFTP/SMB stream have since landed; the remaining gap (metadata inspection is shallow) is a bug against the thesis.
- ✅ **age file encryption — shipped (v5.59.83).** End-to-end encryption for files in any backend, composed on `FileBackend` (download → age → upload) so every tab lights up at once. X25519 identities live in Haven's keystore (`age_identities`, secret encrypted at rest via `CredentialEncryption`; public recipient shown in the audit screen); per-file Encrypt/Decrypt in the file browser, identity generation in the Keys tab. Implemented on BouncyCastle — no new dependency, works on minSdk 26 where Android's JCA lacks X25519/ChaCha20-Poly1305. Validated against the C2SP/CCTV age test vectors and round-tripped with the reference `age` CLI both directions. The MCP verbs (`encrypt_file`, `decrypt_file`, `create_age_identity`, `list_age_identities`) shipped alongside. Scope: X25519 recipients only — passphrase (scrypt) and ASCII armor are follow-ups.
- **Trim / cut / resolution picker** — finish the media toolchain so the convert dialog is a real mobile NLE, not just a transcoder.

### 3. The runtime story — PRoot is the agent host

The local PRoot rootfs is the differentiator nobody else ships. It's where an agent can run persistently on the phone without leaving Haven.

- **Curated dev stacks** — one-tap installers for common language runtimes (Python, Node.js, Rust, Go), pre-tested, following the existing `DesktopEnvironment` enum pattern. Haven ships the runtime; the user brings whatever runs on it, agent CLIs included.
- **sshfs inside PRoot** — mount remote filesystems so local tools (and local agents) operate transparently on remote files.
- **Storage management** — rootfs images grow; show disk usage, offer cleanup, support external storage.

### 4. The Wayland desktop — a second runtime

Haven's native Wayland compositor is the most technically differentiated piece of work. Keyboard, GPU, window management, and virgl GL passthrough are shipped, and the multi-distro desktop manager adds a complementary path: full window-managed environments (Sway via wayvnc, plus X11 Xfce4/Openbox) running inside any installed rootfs. Remaining work:

- **Wider nested-compositor support** — Sway (wlroots/pixman) runs headless in PRoot; GLES-only compositors (Hyprland/aquamarine, niri/smithay) can't initialise a backend there because the Android GPU isn't driveable by Mesa in proot — they're offered but GPU-limited (tracked on #162). A working software/virgl GL path for the nested case is the unlock.
- **GPU acceleration — the host-broker pointed inward at the GPU (shipped, opt-in).** Guest Mesa in a proot cage can't reach Mali — `/dev/dri` is denied to the app uid and no render node is exposed — so the only GPU path is Android's own EGL/Vulkan loader. The unlock is the same host-broker pattern as USB and the system log (see "The host as a privileged peer"), not device-node passthrough: Haven launches a renderer (`virgl_test_server`, and for modern GL a venus + Mesa-zink stack) holding a real GPU context in its *Android* process, with the guest's Mesa pointed at it over a socket. Shipped today: **OpenGL 2.1** over virgl (`GL_RENDERER = virgl (Mali-G715)`), **native-Vulkan windowed** present (vkcube), and — opt-in behind `gpu_use_venus` — **windowed GL 3.2** via zink-on-venus, static *and* geometry-animating, presenting flicker-free over the `wl_shm` cage. Interactive Qt CAD 3D (OpenSCAD) runs on the GPU today; wxWidgets CAD still needs a guest wx-EGL build. Getting there meant clearing a stack of Android-specific walls — minigbm allocation, a venus fence-feedback hang, an upstream `virglrenderer` mutex-ordering crash, and host-visible-memory incoherence over vtest — each device-verified on a Pixel 8 Pro. The full R1→R8 trail is in **[docs/gpu-acceleration.md](docs/gpu-acceleration.md)**.
- **Xwayland in the cage by default** — shipped, so X11/GLX apps run at all.
- **GL client passthrough** — virgl ships for labwc-native; venus/Vulkan ships opt-in behind `gpu_use_venus` (see above).
- **Standalone socket** — let external clients (Termux, chroot, foreign runtimes) connect.

### 5. Network resilience and security as brand

- **Background keepalive resilience** — Doze mode / app standby / battery optimisation is the biggest source of "session died" complaints. Document, automate, and expose clear recovery actions.
- **Per-profile authentication** — high-security connections require auth each time.
- **Audit log UI** — surface ConnectionLog so privacy-conscious users can verify the app's behaviour.
- **Secrets-clean logs** — automate the scrub of credentials from verbose logs and crash reports.

## Scope boundaries — what Haven is not

A tight scope is how a small project stays coherent. Haven deliberately does not:

- **Build an editor** — vim/nano/micro in PRoot or on the remote shell is the editor. Building an in-app text editor is a tar pit.
- **Build a media player** — the OS already has VLC, MX Player, etc. Haven hands off via intents. We are the file transport and transformation layer, not the playback layer.
- **Reimplement tmux/zellij** — split panes, scrollback search, session persistence are session-manager features. Haven integrates with them via SessionManagerRegistry instead of competing.
- **Build provider-specific features** — rclone is the abstraction. No Google Drive-specific sharing UI, no Dropbox versioning, no S3 object lifecycle panel. The provider list stays uniform.
- **Build collaboration** — shared sessions, voice, screen sharing. Out of scope for a single-developer project and orthogonal to the identity.
- **Reimplement comms protocols as native connection types** — IRC, Matrix, XMPP and friends belong in PRoot (a `weechat`/`irssi` shell), not as first-party clients. The build-vs-delegate rule decides it: a protocol earns a native type only when native-ness *brokers a boundary the runtime can't* — credentials and message bodies kept on-device under Haven's keystore and consent/MCP surface, a brokered device cap, transport reach. Embedded email clears that bar; IRC does not — PRoot `weechat` is the same security posture minus a year of persistent-socket, `specialUse` FGS, and SASL code — so the right work is making *IRC-in-PRoot* pleasant (a one-tap install-and-autojoin snippet), not building a chat UI. The community itself lives **off-app**: a user-owned Matrix room or similar, with GitHub remaining the single source of truth for issues and development, and community links landing via PR to the README (or a `COMMUNITY.md`). This boundary came out of the community-channel request, #249.
- **Optimise for tablets/desktops first** — get the phone-in-one-hand experience right before chasing form factors.
- **Ship an AI assistant** — Haven provides the substrate agents run on. It ships no model, no API, no assistant persona, and no default endpoint; the chat screen is a client the user points at their own endpoints (OpenAI-compatible, Ollama, Anthropic, Gemini), and the model choice is stored config, not a bundled service. The user brings the agent.
- **Bundle, advertise, or name any specific vendor's agent CLI** — no "Install Claude Code" button, no vendor onboarding flow, no Anthropic/OpenAI/Google branding or endorsements anywhere in the app, the docs, or the README. Protocol names in the chat-screen endpoint picker are configuration options, not endorsements. Haven is a generic Linux environment plus a generic MCP endpoint; what you point at it is your private choice and none of the app's business. Corporate neutrality is part of the GPL/privacy identity and is non-negotiable.

## Architectural direction

Think of Haven as three layers, each of which must remain small and sharp:

1. **Substrate primitives** (Namespace, Runtime, Gateway). Each has a clean Kotlin API and wraps exactly one underlying technology per function. No cross-layer leakage: the file browser doesn't know ffmpeg exists; ffmpeg doesn't know rclone exists; they meet through HTTP URLs and process stdin/stdout.

2. **Presence** — the surface primitive. The in-app surfaces built where composition demands it (the terminal, the file browser, the VNC/RDP clients, the native Wayland compositor, embedded app windows, and the dialog surfaces that wire primitives together) *plus* the agent reach-back channel that lets a surface be initiated from outside the UI process. Presence is a primitive of a different kind: the substrate three hold state; Presence is how that state is touched and how it — and the agents working in it — reach back. Adding a new substrate primitive — a new backend or a new runtime — should light up every Presence surface for free; if it doesn't, the primitive or the surface is wrong.

3. **Identity and trust** (keystore, screen lock, TOFU, secrets hygiene). This is the cross-cutting layer that earns users' confidence in putting their credentials on the phone in the first place. Every feature must pay its security rent.

Outside those three layers is the Android host, which provides media playback, PDF/image viewing, notifications, share sheet, biometric prompts, and web rendering. Haven explicitly delegates to it for anything covered by the build-vs-delegate rule above. The phone OS is a free lower layer we don't need to rebuild — and, per "The host as a privileged peer," a layer Haven also *brokers into* the runtime: platform-gated capabilities (USB, camera, sensors, location, clipboard, system log) opened with consent and re-exposed as primitives, rather than escalating the guest's privilege.

A public library succeeds not by having every book, but by having the right books, organized well, in a building that's pleasant to be in. Haven's books — protocols, backends, codecs — are sufficient. The work now is in the organisation (composition surfaces, workspace profiles, cross-tab actions) and the building (touch interface polish, gesture reliability, battery-friendliness).

**Width is sufficient. Composition is the opportunity.** The phone is the thin client; Haven is the thin-client OS; the cloud and your servers and your agents are the computer.
