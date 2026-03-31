# Haven Product Vision

## What Haven is

Haven is a **mobile gateway to computing resources** — SSH, VNC, RDP, SFTP, SMB, 60+ cloud storage providers via rclone, and a local PRoot Linux, all wrapped in one app with strong credential security. The core thesis: *your phone is a thin client to any machine and any cloud, including itself.*

## Identity

The strongest identity Haven has is: **the open-source, privacy-first mobile workspace.** JuiceSSH is dead. Termius went proprietary. ConnectBot is unmaintained. Haven is the only active GPL terminal app with modern Compose UI, hardware key support, and a local Linux environment.

The GPL/privacy audience chooses Haven *because* it's open source. Every security feature reinforces this identity.

## Use cases

Three concentric circles, in priority order:

1. **Terminal via VPN to workstation** — SSH, session persistence (tmux/zellij), reliable reconnect with session restore. Running Claude Code, development tools, system administration.
2. **File management across boundaries** — SFTP, SMB, and 60+ cloud providers (Google Drive, Dropbox, S3, OneDrive, etc.) in a unified browser. Cross-filesystem copy/move between any combination.
3. **Remote graphical desktop** — VNC/RDP to remote machines.
4. **Native local desktop** — GPU-accelerated Wayland compositor with window management, keyboard, mouse, zoom, fullscreen. A real Linux desktop on your phone.
5. **Local PRoot development** — portable Linux environment on the phone itself.

## Cohesion assessment

The feature set is mostly cohesive around "access any machine from your phone." Where it frays:

- **Cloud storage via rclone** reinforces the "access anything from your phone" identity. The rclone Go bridge adds binary size (~90MB/ABI) but brings 60+ providers without per-provider API keys or proprietary SDKs. Cross-filesystem copy ties it all together.
- **SMB and RDP** serve a Windows-centric user who probably isn't the GPL/privacy audience. They're maintenance weight on a small project.
- **Reticulum** is visionary but niche — mesh networking for a handful of users.
- **Five terminal transports** (SSH, Mosh, ET, Reticulum, Local) means every terminal feature must work across all five. The SessionManagerRegistry exists to manage this complexity.

## Development priorities

### 1. Workflow continuity and connection management (highest leverage)

The fundamental challenge of mobile work: connections drop, the OS kills the app, you have 110 servers to manage. Haven needs to be the best place to organize and maintain persistent access to infrastructure.

- **Connection groups/folders** — collapsible groups for organizing large server fleets. The colorTag and search/filter (v3.12.0) are quick wins; full group hierarchy is the next step.
- **Workspace profiles** — "Work" auto-opens SSH tabs + port forwards + SFTP sidebar. One tap to resume full working context.
- ~~**Network transition**~~ — shipped. NetworkMonitor detects WiFi/cellular/VPN changes and triggers immediate reconnect via SshConnectionService instead of waiting for TCP timeout.
- **Background keepalive resilience** — Android Doze mode and app standby break long SSH sessions. Document best practices (battery exemption) and add reconnect actions to the persistent notification.

### 2. Native Wayland desktop (most differentiated)

Haven embeds a GPU-accelerated Wayland compositor (labwc/wlroots) running natively in the app process. This is a full window manager with server-side decorations, not a VNC mirror.

- ~~**Keyboard interaction**~~ — shipped in v4.10.0–v4.15.0. Full IME support with Shift/symbol mapping, shared keyboard toolbar (Esc, Tab, Ctrl, Alt, arrows, F1–F12).
- ~~**GPU acceleration**~~ — shipped in v4.13.0–v4.14.0. GLES2 compositing via AHardwareBuffer, zero-copy display via ASurfaceControl.
- ~~**Window management**~~ — shipped in v4.11.0. SSD theme, titlebar buttons (close/maximize/minimize), auto-maximize, mouse interaction.
- ~~**Fullscreen + overlay menu**~~ — shipped in v4.16.0. NoMachine-style corner hotspot.
- **Configurable shell** — shipped in v4.16.0. /bin/sh, bash, zsh, fish selectable from Settings.
- **Multiple windows** — launch additional Wayland clients (GUI apps, second terminal) within the compositor.
- **GL client passthrough** — virgl/venus for GPU-accelerated graphical apps inside PRoot.
- **Standalone compositor** — expose socket for external clients (Termux, chroot). Partially shipped (works on rooted/PRoot, SELinux limits cross-app on non-rooted).

### 3. PRoot as development environment

No other Android app ships a full Linux userland. This is Haven's unique wedge.

- **Curated development stacks** — "Install Python" / "Install Node.js" / "Install Rust" one-tap setup. Pre-tested, pre-configured.
- **sshfs mounts** — mount remote filesystems inside PRoot so local tools can edit remote files transparently.
- **Storage management** — PRoot rootfs images grow. Show disk usage, offer cleanup, support external storage.
- **Friction-free transition** — make it easy to start in PRoot and graduate to remote when you need power.

### 4. Security as brand

- ~~**Tor/SOCKS proxy support**~~ — shipped in v3.11.0. SOCKS5/SOCKS4/HTTP proxy per profile, .onion address detection.
- ~~**Rootfs integrity verification**~~ — shipped in v3.12.2. SHA-256 checksum for Alpine minirootfs downloads.
- **Per-profile biometric unlock** — high-security connections require biometric each time, not just at app launch.
- **Audit log** — surface the existing ConnectionLog entity in the UI for security-conscious users.

### 5. Terminal polish (ongoing)

Split panes, scrollback search, and session persistence are provided by tmux/zellij/screen — Haven delegates to these via SessionManagerRegistry rather than reimplementing them. Terminal work focuses on the touch interface layer:

- ~~**URL detection**~~ — shipped. Clickable URLs and OSC 8 hyperlinks.
- ~~**Copy/paste reliability**~~ — fixed in v3.12.1. Smart copy with TUI border stripping.
- ~~**Selection/gesture stability**~~ — fixed in v3.12.5. Pager snap-back, drag reorder with children.
- **Semantic shell integration** — OSC 133 prompt markers enable "copy last command output" and per-command navigation. The `getLastCommandOutput()` method exists in termlib, not yet wired to UI.

## What to defer

- **Terminal split panes / scrollback search** — provided by session managers (tmux, zellij, screen). Reimplementing these in Haven would duplicate functionality and conflict with the session managers users already rely on.
- **More cloud provider-specific features** — rclone handles the abstraction. Don't build Google Drive-specific sharing or Dropbox-specific versioning. Let rclone be the backend.
- **File editing** — building an editor inside Haven is a rabbit hole. Make PRoot's vim/nano work well and focus on file transfer.
- **Collaboration features** — shared sessions, screen sharing. Out of scope for a single-developer GPL project.
- **Tablet/ChromeOS optimization** — get the phone experience perfect first.

## Architectural direction

A public library succeeds not by having every book, but by having the right books, organized well, in a building that's pleasant to be in. Haven's "books" (protocols) are sufficient. The work now is in the "organization" (workflow continuity, workspaces, connection groups) and the "building" (touch interface polish, gesture reliability). The PRoot environment is the maker space in the basement — unique, powerful, and the reason some people choose this library over any other.

**Connect the workflows, keep the security story clean, polish the touch layer.** Width is sufficient. Depth is the opportunity. The native Wayland compositor opens a new axis — Haven evolves from a thin client into a portable OS environment where terminal, desktop, and file management are unified.
