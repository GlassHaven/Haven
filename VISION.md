# Haven Product Vision

## Thesis

Haven is a **thin-client operating system for distributed compute, storage, and presence**. The pocket device in your hand is not the computer — it's the point of presence from which you reach the computers, the files, and the agents that do the work. Those computers and files are scattered: a workstation under the desk, a VM in a datacenter, a folder in Google Drive, a camera feed at home, a screen sharing something on the other side of the room. Haven is the single lens through which you see and operate on all of it.

The last decade convinced us that "desktop" and "files" are local nouns. The next one undoes that. Storage lives on whatever is cheapest. Compute runs wherever the data is. Humans, phones, laptops, and AI agents all take turns driving the same shared workflow. Haven treats that world as the default case instead of the exception.

## Identity

The strongest identity Haven has is: **the open-source, privacy-first mobile workspace.** JuiceSSH is dead. Termius went proprietary. ConnectBot is unmaintained. Haven is the only active GPL-licensed terminal app with modern Compose UI, hardware key support, a local Linux environment, a unified cloud file browser, a real media toolchain, and a native GPU-accelerated Wayland desktop — in one APK, no accounts, no telemetry.

The GPL/privacy audience chooses Haven *because* it's open source. Every security choice — encrypted credentials, biometric lock, TOFU host keys, FIDO2 support, local storage only — reinforces this identity. That is the moat and the brand.

## The three primitives

A coherent OS abstraction reduces to three things: a place to keep stuff, a place to run stuff, and a way to move bits between them. Haven is organized around those three primitives. Presentation — the touchable surface that lets a human operate them — is a fourth concern that cuts across all three, and it has its own rule (below).

### 1. Namespace — one filesystem across the universe

The file browser is the **unified namespace**. Local storage, SFTP, SMB, 60+ cloud providers via rclone, and a PRoot-mounted Alpine rootfs are all surfaced as tabs in the same UI, with the same operations: copy, move, rename, delete, open, stream, convert, share. Cross-filesystem copy/move is a first-class operation — drag a file from Google Drive into an SFTP server and it goes through Haven without a local round-trip if the backends support it.

Where files live is an implementation detail. Actions — convert, encrypt, stream, share — apply wherever the file is, not only to local copies. Rclone's HTTP serve + Range requests mean a 5 GB cloud file can be transcoded, previewed, and streamed without ever touching the phone's disk.

### 2. Runtime — any shell, any window, anywhere

The **runtime** is the place you actually run things. Haven exposes four kinds:

- **Local shell** — a full Alpine Linux userland on the phone via PRoot, with a package manager and dev tools. Anything that runs on Linux arm64 — language runtimes, build toolchains, AI/agent CLIs, ad-hoc scripts — runs here without Haven knowing or caring what it is.
- **Remote shell** — SSH, Mosh, Eternal Terminal, and Reticulum transports, with session persistence via tmux/zellij/screen, auto-reconnect, session restore, and a color-coded tabbed terminal that treats all four the same way.
- **Remote desktop** — VNC (with VeNCrypt/TLS), RDP (via IronRDP), tunneled through SSH when you want the wire encrypted.
- **Local desktop** — a native GPU-accelerated Wayland compositor (labwc/wlroots) running in Haven's own process, GLES2-composited via AHardwareBuffer. A real Linux desktop on the phone, distinct from any remote screen.

All four are runtimes in the OS sense: processes with input, output, a filesystem, and a network. Haven's job is to make switching between them feel like switching between terminal tabs on a desktop OS.

### 3. Gateway — the network is the substrate

The **gateway** is how runtimes talk to each other and to the outside world. Haven bundles the primitives of a programmable network:

- **Port forwarding** — Local (`-L`), Remote (`-R`), and Dynamic (`-D`, SOCKS5 proxy server) over any active SSH session, managed live from the UI.
- **ProxyJump** — multi-hop tunnels through bastions and jump hosts, visualised as a tree.
- **Transport proxies** — SOCKS5/SOCKS4/HTTP for reaching `.onion` or corp-restricted endpoints.
- **Network-aware reconnect** — NetworkMonitor triggers an immediate reconnect when WiFi/cellular/VPN flips, instead of waiting for a TCP timeout.
- **Mesh** — Reticulum transport for off-grid connectivity.
- **Service publishing** — the rclone media server, the HLS streaming server, and the Wayland socket (via Shizuku) turn the phone itself into a host that other devices on the LAN can reach.

You should be able to reach any service from any runtime with one configuration step, and the connection should survive network transitions without your workflow fraying.

## Presentation — build where composition matters, delegate where it doesn't

Every primitive needs a touchable surface, and the decision of whether Haven builds that surface or hands off to the host OS is not aesthetic — it's structural. The rule:

> **Build the presentation where the user needs to compose that primitive with another one inside Haven. Delegate to the OS where the handoff is clean and the user just wants a destination.**

That rule produces a sharp dividing line:

**Haven builds its own presentation for:**

- **The terminal** (termlib fork with touch-first gesture layer, smart clipboard, OSC 8/9/52/133 wiring, toolbar, shell-integration features). There is no Android terminal primitive, and a terminal that can't be composed with SSH sessions, port forwards, file browser actions, and Wayland tabs in the same app isn't worth much.
- **The file browser** (Compose-based, tabbed, unified across six backends). Android has DocumentsProvider but it's fragmented, read-mostly, and doesn't support SFTP/SMB/rclone as first-class peers. Haven's file browser is where every other primitive becomes actionable — the single most important composition surface in the app.
- **The VNC and RDP clients** (JSch-tunneled VNC with VeNCrypt, IronRDP-backed RDP). Standalone VNC/RDP apps exist but can't see Haven's SSH sessions, port forwards, or connection profiles. Composability forces them in-app.
- **The native Wayland desktop** (labwc/wlroots compositor running in-process, GPU-composited via AHardwareBuffer). This is the most technically novel surface — there is no Android primitive for a Linux desktop. It's also the surface that lets Haven double as a workstation on big-screen outputs.
- **The convert dialog, the port forward dialog, the preview/filter UI, the connection edit dialog.** These are pure composition surfaces — they wire two or three primitives together and only exist because the primitives live in one process.

**Haven delegates to the OS for:**

- **Media playback.** `ACTION_VIEW` + MIME type hands the file to VLC, MX Player, or whatever the user prefers. Android's intent contract is solid, the ecosystem is strong, and users already have opinions about players. Haven is the transport and transformation layer; playback is downstream. The rclone HTTP server URL, the local file URI, and the HLS playlist URL all survive the handoff.
- **HTML5 HLS playback.** Chrome + hls.js via `ACTION_VIEW` on the server URL. Writing a video element wrapper in-app would buy nothing.
- **File opening for non-media.** Tap a PDF → system PDF viewer. Tap an image → system gallery. Tap a text file → whatever the user installed. FileProvider + intent is the contract.
- **System authentication.** BiometricPrompt for app lock; the OS credential dialog for FIDO2.
- **Notifications, shortcuts, share targets.** All the places Android provides a standard surface.

The build-vs-delegate rule has a useful test: *if the user would want to invoke a second primitive while looking at this view, we have to own the view.* You want to copy a line from a terminal into the port-forward dialog → same app. You want to open a media file in a player → different app is fine. You want to drag a file from one backend tab to another → same app. You want to watch the file → different app is fine.

This is also why certain features are explicitly not going to be built (see "Scope boundaries" below): a text editor, a media player, a chat UI. Those would be presentations of nothing — they don't compose with Haven's primitives, they just duplicate work the OS already supports well.

### Presentation is shared with agents

The surfaces Haven builds are not only for human eyes. An AI agent operating alongside the user — whether running in PRoot on the device or in a remote shell Haven is connected to — needs to **work with and within what the user sees**. That imposes two concrete requirements on every presentation surface Haven owns:

**Observable state.** Every view has state: which backend and path the file browser is on, what's in the terminal's current scrollback and which command wrote it, which file is loaded in the convert dialog with what filters, which port forwards are active and bound to which ports, what the connection status of each session is. That state must be exposed in a form an agent can read — the same StateFlow pattern the Compose UI already subscribes to. If a human can see it, an agent must be able to query it.

**Actionable API.** Every action a tap can perform must also be a function an agent can call. Haven's ViewModel methods — `convertFile`, `streamFile`, `playMediaFile`, `navigateTo`, `setPortForwardingDynamic`, `connect`, and their peers — are the vocabulary that both humans and agents use to operate the primitives. There is no "agent-only" bypass layer. If the agent can do it, the user can watch it happen in the UI. If the user can do it, the agent gets a tool-use handle for it.

This rules out two failure modes:

1. **Silent automation** — the agent acting through a hidden channel the user can't see. Every agent operation is reflected in the same UI surface a human would use, so the user can observe, interrupt, and take over mid-workflow. "Haven is doing something" and "the screen is showing something" must always be the same thing.
2. **Divergent state** — the agent's model of "what's happening" drifting from the user's. Both subscribe to the same state flows, so what Haven shows is ground truth for both.

The practical consequence is that the agent transport — a tool-use server (MCP or equivalent) that exposes Haven's ViewModels as callable tools over a local loopback socket — is not an optional add-on. It falls out of the build-vs-delegate rule directly: if we build a presentation surface because it needs to compose with other primitives, we also need to make that surface addressable from outside the UI process, because an agent is just another caller that needs the same composition. Humans tap, agents call, both observe — same surface, same state, same truth.

## The integration thesis — composition is the product

The three primitives are not the product. What you can *do by composing them* is the product. A coherent OS is one where the three compose without friction. Haven's design target: every workflow below should be one flow inside the app.

- Tap a 4K MKV sitting in Google Drive → ffmpeg reads it over HTTP from rclone VFS → the frame preview appears in 3 seconds → tweak brightness → pick "H.264, back to cloud" → the converted file appears in the same Drive folder without ever touching local disk.
- SSH to the workstation → forward port 5901 → tap the VNC profile that targets `localhost:5901` → desktop opens in the same app, keyboard and clipboard shared.
- In the local PRoot shell, start whatever agent CLI you use, pointed at a project directory. It reads files, runs `git push` over the SSH agent you forwarded from your laptop, and you watch it work on the same screen where the SSH tab lives.
- Copy a log directory from an S3 bucket to an SFTP server: long-press → cut → switch tab → paste. Rclone does the server-side copy when possible; otherwise Haven streams it through.
- Stream a video from a cloud folder to the Chromecast across the room via HLS, copy the LAN URL from the snackbar, paste it into a message to your partner's phone so they can watch too.

None of these require leaving Haven, installing a second app, or running a `curl | ssh` incantation. Each of them uses two or more of the three primitives. This is the thesis.

## The agent era — agents are a first-class user

LLM agents are a new class of actor, and they need the same primitives a human does: credentials, shell access, a filesystem, a network. An agent is just a very persistent process that wants to SSH, read files, run commands, and transform media — exactly what Haven already mediates for a human. Haven has no opinion on which agent you run: any CLI that speaks Linux conventions is as welcome as any other, and the app ships no vendor-specific integrations, installers, or brand surfaces.

What this means in practice:

- **Agents in the local runtime**: PRoot is a first-class place to run whatever AI CLI, local dev tools, and language runtimes the user wants. No root, no Termux, no rebuild needed. Haven neither knows nor advertises which CLI you're running.
- **Agents in a remote runtime**: SSH to the workstation where your real agent lives, keep the session alive across network drops, and come back to a session that remembers what the agent was doing.
- **Credentials that humans and agents share**: one encrypted keystore, SSH agent forwarding so the remote agent can use keys that never leave the phone, host key TOFU so a compromised gateway can't silently inject itself.
- **Files that humans and agents both operate on**: the unified namespace means "the project folder" is one path whether the agent is running in PRoot, on the workstation, or pulling from cloud storage.
- **Shared presentation**: agents address Haven's ViewModels through a tool-use transport (see "Presentation is shared with agents" above) — they can open the convert dialog, toggle a port forward, start a stream, or change the active SFTP path, and the user sees every action happen on the screen they're already looking at. No hidden automation; no divergent state. The UI is ground truth for both.
- **Observation**: the terminal tab, the file browser, and the persistent notification give a human operator a clear view of what their agent is doing and where. Haven is the dashboard, not a black box.

Haven is *not* building an AI assistant. It's building the layer that makes AI assistants useful wherever a human wants to point them — mobile, distributed, multi-backend. The agent sees the same OS abstraction the human does, and operates through the same surfaces the human uses, so the human always keeps the wheel.

## Development priorities

The thesis is clear; the work is making it feel seamless. Priorities are ordered by leverage, not by effort.

### Growth diagnostic — where each primitive sits today

A growth plan needs a diagnosis. Mapped onto the three primitives and the seams between them, Haven currently looks like this:

- **Heart — local-tier namespace (SSH, SFTP, rclone): three productive organs, two of them fused.** SSH and SFTP share a JSch session per profile (`SshSessionManager.SessionState` reuses the SSH client and exposes `sftpChannel` alongside the shell channel) — closing one closes the other; the seam is load-bearing. Rclone is the workhorse for 60+ cloud providers and carries its own HTTP-serve bridge for conversion and streaming, but its file browser and operation paths sit alongside SFTP/SMB rather than sharing code with them — adding a new universal action (encrypt, inspect, hash) lights up one tab at a time, not all of them. The leverage move is fusing rclone into the same operation code path the other backends use, so future backend additions and future actions both compose for free.
- **Shoulders — VNC, RDP, SMB, i18n: exercising well.** Tunnel-aware protocols open SSH port-forwards on demand and a `tunnelDependents` set tears the shared tunnel down cleanly when the last consumer leaves. Localisation reaches ten locales. Work here is reach (EGFX, more codecs, per-locale changelogs) rather than missing seams.
- **Extremities — Reticulum: cold.** `ReticulumTransport` returns only an `RnshShellSession`. No SFTP-over-Reticulum, no tunnelable port, no file sync. Warming the extremity means giving Reticulum the same surface area SSH already has, not a parallel one.
- **Smile — MCP: shipping, not yet opinionated.** Eighteen tools across read and write paths, all dispatching into the same repositories the UI uses (load-bearing). What's missing is a tool that *only Haven* could expose — a cross-protocol verb no single-purpose client has the substrate for. Until that verb exists, the MCP surface is a competent bridge, not a moat.

Priorities below are ordered by what this diagnostic implies.

### Juxtapositions — places that look unified but aren't

Several Haven features present a unified surface (one Connections list, one file browser, one keystore icon, one terminal) but underneath are parallel implementations that share no operation code. Each instance taxes future work: a new universal action takes N implementations to land, a new backend has to plug into N call sites, and the tax compounds. Naming the pattern as a class lets future commits collapse instances deliberately rather than discovering each one fresh.

Verified by code audit:

- **File browser per-backend dispatch (largest instance).** `SftpViewModel` carries the `when { isLocal -> … isRclone -> … isSmb -> … else -> … }` shape across roughly fifty call sites: list, copy, paste, delete, rename, probe-destination, metadata. `RemoteFileTransport` unifies SFTP and SCP only. Collapsing this into one backend-agnostic operation interface plus per-backend drivers is the single largest architectural lever in the heart.
- **MCP tools per backend.** `McpTools.kt` defines `list_sftp_directory` and `list_rclone_directory` as distinct tools; the same shape repeats for upload/delete and friends. One `list_directory` tool with a backend argument removes the duplication and is what an agent would prefer regardless.
- **VNC and RDP as parallel remote-desktop stacks.** `VncSession` and `RdpSession` share no base interface; clipboard, framebuffer scaling, reconnect, and tunnel attachment are reimplemented in each. A `RemoteDesktopSession` abstraction lights up universal verbs (record, paste, scale-to-fit, attach-tunnel) for both at once.
- **Keystore as three stores.** SSH keys live behind `KeyEncryption` (Tink AEAD), FIDO2 credentials behind `FidoIdentity`, encrypted profile credentials elsewhere. No unified key-material API, so audit/export/migration is per-type. A thin `Keystore` interface over the three makes security rent — TOFU, biometric unlock, per-profile auth — cheaper to charge consistently.
- **Foreground service hardcoded to known transports.** `SshConnectionService` calls `disconnectAll()` on each session manager it knows about. Adding a new transport requires editing the service. A `ForegroundSessionParticipant` interface that managers register against removes the per-transport hardcoding.
- **Session managers as dispatch, not abstraction.** `SessionManagerRegistry` injects all seven managers (SSH, Mosh, ET, Reticulum, SMB, Local, RDP) and dispatches by type rather than over a common base interface. Session restore, scrollback persistence, and input dispatch are each reinvented. Any future "remember and restore every session on app launch" feature has to integrate with all seven.

Audited and **already unified** — no work needed: network discovery (`NetworkDiscovery` merges mDNS, ARP, Tailscale, and SMB scan into one `StateFlow<List<DiscoveredHost>>`); the ffmpeg convert pipeline (URL-driven, no per-backend special-case); `ConnectionLogRepository` writes (one Room table, callers populate per-layer, which is fine).

The unifying move in every verified case is the same shape: one shared interface plus N drivers, replacing a switch statement plus N parallel implementations. None of these are quick refactors — the file-browser case alone is the largest in the codebase — but the cumulative payoff is "every new universal action lands once, every new backend lights up everywhere."

### 1. Composition polish — make the three primitives snap together

Whenever two primitives meet, there should be zero friction. Current gaps:

- **SFTP/SMB media** should work through the same HTTP-streaming trick as rclone so convert/preview/stream/tap-to-play work for every backend, not just rclone + local. Building an ffmpeg-with-libssh would unlock this in one move.
- **Agent forwarding UX** — the plumbing exists; the story of "forward my phone's keys to the remote agent and be able to trust it" needs to be a dialog, not a config file.
- **Workspace profiles** — "Work" opens SSH tab + port forwards + SFTP sidebar + Wayland tab + a PRoot shell tab in one tap, resumes to the same composition next launch.
- **Desktop ↔ file browser ↔ terminal** — cross-tab actions (drag a file from the SFTP tab into the native Wayland compositor, copy output from a terminal into the convert dialog).
- **rclone fused into shared operation code** — rclone is fully productive on its own, but its browser routes through `RcloneClient` while SFTP/SMB go through `FileBrowserViewModel`. Cross-tab paste works, but lookup, metadata, and operation code aren't shared. Routing all rclone calls through the same actions the SFTP tab uses means a new backend or a new universal action (encrypt, inspect, hash) lights every tab up at once.
- **Reticulum carrying more than shell** — today `ReticulumTransport` returns only `RnshShellSession`. SFTP-over-Reticulum and Reticulum-as-tunnelable-port are the moves that turn the extremity into a working limb. The Reticulum mesh is the only transport in Haven that survives full internet loss, so anything load-bearing routed through it is a unique capability, not a duplicate path.

### 1a. Agent transport — shipped (v5.24.81)

The shared-viewport idea is now a concrete transport. Haven's local
loopback MCP server exposes 18 tools across read and write paths, and
every non-read call surfaces a non-skippable bottom-sheet consent
prompt before the action runs. What landed:

- **Tool-use server** — MCP / JSON-RPC over HTTP loopback (port range 8730–8739), Streamable HTTP stateless transport. Disabled by default; toggled in Settings → Agent endpoint.
- **State inspection tools** (no prompt) — `list_connections`, `list_sessions`, `list_rclone_directory`, `list_sftp_directory`, `stream_sftp_file`, `read_terminal_scrollback`, `play_file`, `get_app_info`, `list_rclone_remotes`, `stop_stream`.
- **Action tools** (per-action consent) — `open_local_shell`, `send_terminal_input`, `add_port_forward`, `remove_port_forward`, `upload_file_to_sftp`, `delete_sftp_file`, `disconnect_profile`, `convert_file`, `set_terminal_font_from_url`, `open_developer_settings`, `enable_wireless_adb` (Shizuku-gated).
- **Audit and consent** — `AgentConsentManager` with foreground fail-closed semantics; `AgentAuditRecorder` writes every call to a Room table with redacted args; in-app "agent active" chip on the Connections top bar lights up on recent activity; `AgentActivityScreen` is the dashboard.
- **Discovery** — Settings exposes the endpoint URL, an MCP-config JSON snippet, and a "Tunnel through SSH profile…" shortcut that adds a `-R 8730` rule on the chosen profile so a remote MCP client reaches Haven via `localhost` through the existing SSH session.

What's still ahead in this lane:

- **`connect_profile`** — the one deferred verb. Lifting `ConnectionsViewModel.connectSshSilent` into a singleton needs the FIDO / jump-host / verbose-logger plumbing untangled. Best done after the SSH-proto migration (#58) settles.
- **Cross-tab agent verbs** — opening the convert dialog with prefilled args, navigating the file browser to a path, etc. Dialog state is Composable-scoped today; this needs a state-promotion refactor and is its own arc.
- **MCP `resources/*` capability** — file-shaped resources for "what's on the screen right now" so an agent can pull a snapshot without polling.

### 1b. MCP as a bidirectional protocol — Haven as host *and* client

Section 1a covers Haven publishing an MCP server: agents reach into Haven's primitives. The mirror image is Haven becoming an MCP *client* — discovering and consuming capabilities published by other Android apps that speak the protocol. Together, the two halves turn MCP from a single-direction agent transport into a generic capability bus, with Haven as one peer among others.

This is not the same as shipping an AI assistant or bundling a vendor's agent CLI (both excluded under "Scope boundaries"). Haven still has no model, no chat UI, no prompt language — only the primitives, the surfaces, and the ability to compose those primitives with capabilities that other locally installed apps choose to expose. The user is still the one pointing the agent at things; the agent is still operating through observable UI; the trust model is still per-action consent. What changes is what's in the agent's vocabulary.

Concrete shape:

- **Discovery without dynamic code.** Installed apps that publish MCP servers register via a documented Android `<intent-filter>` plus manifest `meta-data`. Haven enumerates them, surfaces them in Settings → Agent endpoints, and lets the user enable specific capability bundles. No bytecode is loaded from outside the APK; the F-Droid reproducible-build rule stays satisfied because each plugin is a separately source-built APK.
- **Composition with Haven primitives.** When external MCP capabilities are present, Haven's MCP server can advertise them as part of its own capability list (or proxy them transparently) so an agent connected to Haven sees one tool surface that combines Haven primitives with external capabilities. Example workflows: *Reticulum-fallback file sync* — Haven's SFTP plus an external "watch this directory" capability; *RDP clipboard ↔ SFTP path* — Haven's RDP clipboard plus an external "translate path" capability; *agent journal* — Haven's audit log plus an external "summarise" capability.
- **The first cross-protocol verb.** The king-making call is picking a single verb only Haven's substrate can implement, exposing it through the existing MCP server, and letting it grow teeth before the plugin scaffolding is built. Candidates: `mirror_directory_with_fallback` — SFTP primary, rclone secondary, Reticulum tertiary, single transactional API; `move_session` — hand a live SSH/RDP session between transports without dropping; `compose_workspace` — open a multi-tab workspace from a single agent-supplied descriptor (workspace profiles done agent-first).
- **Audit and consent extend unchanged.** External capabilities go through the same `AgentConsentManager` per-action prompt and the same `AgentAuditRecorder` Room table. The user can't tell from the consent sheet whether a tool is a Haven primitive or a plugin's; they don't need to.

The bidirectional direction is consistent with the three-primitives frame: Haven's primitives stay scoped, but the *agent vocabulary* over those primitives grows. The plugin system lives at the orchestration layer, never at the bytecode layer.

### 2. The namespace as the action surface

The file browser is Haven's highest-leverage surface because it's where every backend converges. Every action that applies to a file should be available on every file, regardless of where it lives.

- **Universal action set** — convert, preview, stream, play, encrypt, share link, copy path, inspect metadata — consistent across local, rclone, SFTP, SMB, PRoot. Gaps (e.g. encrypt isn't implemented yet, stream doesn't work on SFTP/SMB, metadata inspection is shallow) are bugs against the thesis.
- **age file encryption** — end-to-end encryption for files in any backend; keys live in Haven's keystore, operate wherever the ciphertext lives.
- **Trim / cut / resolution picker** — finish the media toolchain so the convert dialog is a real mobile NLE, not just a transcoder.

### 3. The runtime story — PRoot is the agent host

The local PRoot rootfs is the differentiator nobody else ships. It's where an agent can run persistently on the phone without leaving Haven.

- **Curated dev stacks** — one-tap Python/Node.js/Rust/Go, pre-tested.
- **Curated dev stacks** — one-tap installers for common language runtimes (Python, Node.js, Rust, Go) that the user's own tooling — agent CLIs included — can be built on top of. Haven ships the runtime, the user brings whatever runs on it.
- **sshfs inside PRoot** — mount remote filesystems so local tools (and local agents) operate transparently on remote files.
- **Storage management** — rootfs images grow; show disk usage, offer cleanup, support external storage.

### 4. The Wayland desktop — a second runtime

Haven's native Wayland compositor is the most technically differentiated piece of work. Now that keyboard, GPU, and window management are shipped, the next step is populating it:

- **Multiple windows** — launch additional Wayland clients concurrently.
- **GL client passthrough** — virgl/venus so GPU-accelerated apps in PRoot render natively.
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
- **Optimise for tablets/desktops first** — get the phone-in-one-hand experience right before chasing form factors.
- **Ship an AI assistant** — Haven provides the substrate agents run on; it does not ship its own model, API, or chat UI. The user brings the agent.
- **Bundle, advertise, or name any specific vendor's agent CLI** — no "Install Claude Code" button, no "Configure OpenAI" section, no Anthropic/OpenAI/Google branding or endorsements anywhere in the app, the docs, the ROADMAP, or the README. Haven is a generic Linux environment plus a generic MCP endpoint; what you point at it is your private choice and none of the app's business. Corporate neutrality is part of the GPL/privacy identity and is non-negotiable.

## Architectural direction

Think of Haven as three layers, each of which must remain small and sharp:

1. **Primitives** (namespace, runtime, gateway). Each has a clean Kotlin API and wraps exactly one underlying technology per function. No cross-layer leakage: the file browser doesn't know ffmpeg exists; ffmpeg doesn't know rclone exists; they meet through HTTP URLs and process stdin/stdout.

2. **Presentation surfaces** built in-app where composition demands it: the terminal (termlib fork), the file browser (Compose), the VNC/RDP clients, the native Wayland compositor, and the dialog surfaces (convert, port forward, connection edit, key deploy, preview/filter) that wire primitives together. These are where primitives combine into workflows. Adding a new primitive — say, a new backend or a new runtime — should light up every composition surface for free; if it doesn't, the primitive or the surface is wrong.

3. **Identity and trust** (keystore, screen lock, TOFU, secrets hygiene). This is the cross-cutting layer that earns users' confidence in putting their credentials on the phone in the first place. Every feature must pay its security rent.

Outside those three layers is the Android host, which provides media playback, PDF/image viewing, notifications, share sheet, biometric prompts, and web rendering. Haven explicitly delegates to it for anything covered by the build-vs-delegate rule above. The phone OS is a free lower layer we don't need to rebuild.

A public library succeeds not by having every book, but by having the right books, organized well, in a building that's pleasant to be in. Haven's books — protocols, backends, codecs — are sufficient. The work now is in the organisation (composition surfaces, workspace profiles, cross-tab actions) and the building (touch interface polish, gesture reliability, battery-friendliness).

**Width is sufficient. Composition is the opportunity.** The phone is the thin client; Haven is the thin-client OS; the cloud and your servers and your agents are the computer.
