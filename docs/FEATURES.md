---
layout: default
title: Features
---

# Features

Full feature detail for Haven. The [landing page](index.html) has a short summary.

## Terminal

VT100/xterm emulator with multi-tab sessions, [Mosh](https://mosh.org) (Mobile Shell) for roaming connections and [Eternal Terminal](https://eternalterminal.dev) (ET) for persistent sessions — both with pure Kotlin protocol implementations (no native binaries), tmux/zellij/screen auto-attach with **session restore** (remembers previously open sessions and offers to reopen them), tab reordering via long-press menu, color-coded tabs matching connection profiles, mouse mode for TUI apps, configurable keyboard toolbar (Esc, Tab, Ctrl, Alt, AltGr, arrows, Delete, Insert, Home/End/PgUp/PgDn, F1–F12, custom macro keys with presets for common combos like Ctrl+C/D/Z and Ctrl+Alt+Delete), text selection with copy and Open URL, OSC 133 shell integration with one-tap "copy last command output", configurable font size, and six color schemes.

### OSC escape sequences

Remote programs can interact with Android through standard terminal escape sequences:

| OSC | Function | Example |
|-----|----------|---------|
| 52 | Set clipboard | `printf '\e]52;c;%s\a' "$(echo -n text \| base64)"` |
| 8 | Hyperlinks | `printf '\e]8;;https://example.com\aClick\e]8;;\a'` |
| 9 | Notification | `printf '\e]9;Build complete\a'` |
| 777 | Notification (with title) | `printf '\e]777;notify;CI;Pipeline green\a'` |
| 7 | Working directory | `printf '\e]7;file:///home/user\a'` |

Notifications appear as a toast in the foreground or as an Android notification in the background.

## Desktop (VNC)

Remote desktop viewer with RFB 3.8 protocol support. Pinch-to-zoom, two-finger pan and scroll, single-finger drag for window management, soft keyboard with X11 KeySym mapping. Fullscreen mode with NoMachine-style corner hotspot for session controls. Connect directly or tunnel through SSH. Supports Raw, CopyRect, RRE, Hextile, and ZLib encodings. Security types: **None**, classic **VncAuth** (DES), and **VeNCrypt** (security type 19) with TLSPlain/X509Plain/TLSVnc/X509Vnc/TLSNone/X509None/Plain sub-types — connects to wayvnc, TigerVNC, libvncserver, x11vnc and other servers that require a username alongside a password, wrapping the socket in TLS after sub-type negotiation.

## Native Wayland Desktop

GPU-accelerated Wayland compositor (labwc) running natively inside Haven. Full interactive terminal with keyboard input, mouse interaction, server-side window decorations, pinch-to-zoom, and fullscreen mode with corner overlay menu. The GPU pipeline renders via GLES2 on the device's GPU (AHardwareBuffer allocator, ASurfaceControl zero-copy presentation). Native Wayland clients can render 3D content — includes a built-in GLES2 benchmark (rotating lit cube at 60fps on Mali-G715). Configurable shell (/bin/sh, bash, zsh, fish) and shared keyboard toolbar (Esc, Tab, Ctrl, Alt, arrows, function keys). External Wayland clients can connect via Shizuku (symlinks the socket to `/data/local/tmp/haven-wayland/`). No root required — runs in PRoot with an Alpine Linux rootfs.

## Local Desktop (X11)

One-tap desktop running on-device via PRoot. Choose from Xfce4 or Openbox with X11/Xvnc. For Wayland, use the Native Wayland Desktop above.

## Desktop (RDP)

Remote Desktop Protocol client built on [IronRDP](https://github.com/Devolutions/IronRDP) via UniFFI Kotlin bindings. Connects to Windows Remote Desktop, xrdp (Linux), and GNOME Remote Desktop. Pinch-to-zoom, pan, keyboard with scancode mapping, mouse input. SSH tunnel support with auto-connect through saved SSH profiles. Saved connection profiles with optional stored password.

## Files

Unified file browser with SFTP, SMB, and cloud storage tabs. Browse remote directories, upload files or entire folders, download, delete, rename, create directories, copy path, toggle hidden files, sort by name/size/date. **Multi-select** — long-press or tap **Select** to enter selection mode with a contextual action bar (copy, cut, permissions, delete). **Permissions editor** — octal field plus a 3×3 rwx checkbox grid, supported on SFTP/SCP/local (not SMB/rclone). **Built-in text editor** with syntax highlighting, find/replace, and terminal-matched theme. **Image tools** — view, crop, rotate, perspective-correct. **Cross-filesystem copy/move** — copy files between any backends (e.g. Google Drive → SFTP server) with clipboard model: long-press → Copy/Cut, switch tab, Paste. Conflict resolution (skip/replace) for existing files. Path preserved when switching between tabs.

## Media Convert

Convert media files between formats directly on-device using a custom [FFmpeg 8.0](https://ffmpeg.org) build with the full codec/format/filter set. Long-press any media file and tap Convert. Separate dropdown selectors for container format (MP4, MKV, WebM, MOV, AVI, MPEG-TS, MP3, WAV, OGG, Opus, FLAC, M4A), video encoder (H.264, H.265, VP9, VP8, MPEG-4, stream copy), and audio encoder (AAC, MP3, Opus, Vorbis, FLAC, PCM, FLAC, stream copy). **Copy-remux by default** — container auto-matches the source extension so tapping Convert on most files gives an instant lossless remux. **Frame preview** — see filter effects on a single frame before committing, with seek slider and tap-to-fullscreen. **Audio preview** — play a 5-second clip with current filters applied. Video filters: brightness, contrast, saturation, gamma, sharpen, denoise, stabilize (deshake), auto color correction, speed, rotation. Audio filters: volume, loudness normalization (EBU R128). One-tap presets (Stabilize, Fix Colors, Enhance, Normalize Audio). Live CLI preview shows the exact ffmpeg command being built. Audio-only files auto-detected — video UI hidden, only audio formats shown. **Save to** picker: Downloads folder or back to the source folder (uploads to cloud/SFTP/SMB with live progress). **Works on cloud files without downloading** — for rclone profiles, ffmpeg streams the source over HTTP via the rclone VFS so transcode starts in seconds regardless of file size (falls back to full download for offline/reliability via a toggle). **HLS streaming** — stream any local or rclone media file to other devices on the network via an HTML5 player; URL auto-copied to clipboard and opened at the device's LAN IP for easy sharing.

## Cloud Storage

Browse, upload, download, and manage files on 60+ cloud providers via [rclone](https://rclone.org) — Google Drive, Dropbox, OneDrive, Amazon S3, Backblaze B2, and more. OAuth authentication with automatic browser flow. Server-side copy between cloud remotes (no temp file needed). **Share link** — generate public URLs for files on supported backends. **Folder size** — fast recursive size calculation. **Folder sync** — copy, mirror, or move between remotes with include/exclude filters, size limits, bandwidth throttling, and dry-run preview. **Media streaming** — stream audio/video to VLC or any player via local HTTP server with M3U playlists and seeking. **DLNA server** — stream cloud media to smart TVs and Chromecast on the local network.

## SSH Keys

Generate Ed25519, RSA, and ECDSA keys on-device. Import keys from file (PEM/OpenSSH/Dropbear format) or paste from clipboard. FIDO2/SK hardware key support (ed25519-sk, ecdsa-sk) via NFC or USB security keys. One-tap public key copy and deploy key dialog for `authorized_keys` setup. Assign specific keys to individual connections.

## SMB

Browse Windows/Samba file shares with optional SSH tunneling for secure access over the internet.

## Connections

Saved profiles with transport selection (SSH, Mosh, Eternal Terminal, VNC, RDP, SMB, Cloud Storage, Reticulum), host key TOFU verification, fingerprint change detection, auto-reconnect with backoff, password fallback, local/remote/**dynamic** port forwarding (-L/-R/-D — the dynamic type runs a built-in SOCKS5 proxy that tunnels traffic through the SSH session), ProxyJump multi-hop tunneling (-J) with tree view, SOCKS5/SOCKS4/HTTP proxy support (Tor .onion compatible), RDP-over-SSH tunnel profiles, DNS resolution with 5s timeout, and connection error safety nets (20s UI watchdog, post-connect shell verification, session manager detection).

## Local Shell (PRoot)

Run a real Linux terminal directly on your phone, no root required. Select "Local Shell (PRoot)" when creating a connection and Haven downloads a minimal [Alpine Linux](https://alpinelinux.org/) rootfs (~4 MB) on first use. From there you have a full `apk` package manager — install Python, Node.js, git, build tools, or anything in Alpine's [package repository](https://pkgs.alpinelinux.org/packages).

PRoot works by intercepting system calls in userspace (no kernel modifications), so it runs on **any unrooted Android device**. It does not require or use root access — the name "PRoot" stands for "ptrace-based root", meaning it *emulates* a root filesystem without actual superuser privileges. Think of it as a lightweight container that runs entirely within Haven's app sandbox.

How it compares to alternatives:

- **Rooted phones (Magisk/su)**: Root gives full system access. PRoot is sandboxed — it can't modify your system, but it also doesn't need root to work.
- **[Android Terminal VM](https://developer.android.com/studio/run/managing-avds)** (Pixel 8+): Google's official Linux VM runs a full kernel via [pKVM](https://source.android.com/docs/core/virtualization). It's more capable but only available on Pixel 8 and newer. PRoot runs on any device back to Android 8. Haven can SSH into an Android Terminal VM if you have one — see the connection settings.
- **[Termux](https://termux.dev/)**: A standalone terminal emulator with its own package ecosystem. PRoot is lighter (4 MB vs ~100 MB) and integrated into Haven alongside your SSH/cloud sessions.

See [PRoot documentation](https://proot-me.github.io/) for technical details.

## Reticulum

Connect over [Reticulum](https://reticulum.network) mesh networks with native Kotlin transport (reticulum-kt + rnsh-kt). Two-way terminal sessions over IFAC-protected TCP gateways, announce-based rnsh node discovery via scan button, configurable IFAC network name and passphrase. No Python runtime or Chaquopy dependency — pure Kotlin implementation with Flow-based I/O.

## Security

Screen lock with biometric or device PIN/password/pattern, configurable timeout (immediate/30s/1m/5m/never), no telemetry or analytics, local storage only. Keyboard security: all credential fields set `IME_FLAG_NO_PERSONALIZED_LEARNING` to prevent keyboard apps from recording passwords, with a warning when the active keyboard has internet access. Encrypted backup/restore with AES-256-GCM. See the [privacy policy](privacy-policy.html).
