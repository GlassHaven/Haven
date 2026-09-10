---
layout: default
title: Features
---

# Features

Haven is one app for SSH/terminal, remote and local desktops, files and cloud
storage, email, on-device Linux, and an AI-agent bridge. Each area has its own
page below; the [landing page](index.md) has a short summary.

- **[Terminal](features/terminal.md)** — Mosh / Eternal Terminal / SSH, USB / Bluetooth / BLE serial consoles with a serial↔TCP bridge, tmux-aware session restore, configurable keyboard toolbar, OSC 7/8/9/52/133/777 integration.
- **[Desktops](features/desktops.md)** — VNC (RFB 3.8 / VeNCrypt), RDP (IronRDP + EGFX), SPICE (QEMU/KVM, GLZ/QUIC), a GPU-accelerated native Wayland compositor, and a multi-distro local-desktop manager.
- **[GPU acceleration](gpu-acceleration.md)** — how guest Linux GL/Vulkan apps reach the phone's real Mali GPU from a proot cage (no `/dev/dri`): the host-brokered virgl/venus renderer and the R1→R8 deep-dive behind it.
- **[Files & cloud storage](features/files-and-cloud.md)** — unified browser for SFTP/SCP, SMB, and 60+ cloud providers; cross-filesystem copy/move, editor and image tools, folder sync; plus on-device FFmpeg transcode, HLS streaming, and a DLNA server.
- **[Connections & networking](features/connections.md)** — host-key TOFU, port forwarding (-L/-R/-D/-J), SOCKS/HTTP/Tor proxies, per-app WireGuard & Tailscale tunnels, port knocking and fwknop SPA, and on-device SSH key management (incl. FIDO2/SK).
- **[Email & Mail Rules](features/email.md)** — read ProtonMail (bridge protocol) and any IMAP/SMTP mailbox; compose/reply/forward, multi-account, attachments; plus inbound automation with an approval queue.
- **[Local Linux on-device](features/local-linux.md)** — a real Linux userland via PRoot (no root): Alpine, Debian, Arch, or Void side-by-side, each with its native package manager.
- **[Linux Guest (UML)](features/uml-guest.md)** — a whole Linux kernel (user-mode Linux) booted as an app process on any arm64 device, no root: Alpine with `apk`, its own ext4 disk and network stack, for when PRoot's syscall emulation isn't enough.
- **[USB device forwarding](features/usb.md)** — broker an attached USB device through Android and re-expose it to the agent, into the Linux guest, or over USB/IP to a **remote host** (e.g. forward a phone-hosted YubiKey, touch on the phone); includes a device-class support matrix for what works where.
- **[Reading USB drives](features/usb-drives.md)** — plug in a USB flash drive or SSD and read its files, even Linux-formatted (ext4/GPT) drives the phone can't open itself; Haven mounts them in a small on-device Linux VM and surfaces the files in the normal file browser. No jargon required.
- **[Reticulum mesh](features/reticulum.md)** — rnsh shell, file transfer, and `-L`/`-D` port forwarding over Reticulum, pure Kotlin. The one transport that keeps working with no internet at all.
- **[Agent transport (MCP)](mcp-tools.md)** — an optional MCP server exposing ~130 consent-gated, audited tools, including ones that drive Haven's own UI for a self-hosting build → install → verify loop.
- **[Automation (Tasker / MacroDroid)](features/automation.md)** — a native Tasker/Locale/MacroDroid plugin action to run a command on a saved SSH server, with host-variable substitution, wait-until-finished, output returned as `%hstdout`/`%hstderr`/`%hexit` variables, and an optional live-terminal watch.
- **[Security & privacy](features/security.md)** — biometric lock, no telemetry, encrypted backup/restore (AES-256-GCM).
- **[Languages & translation](features/i18n.md)** — 12 languages, follows the device locale, in-app language picker; contribute corrections or whole new languages from the web translation page, no setup required.

**Languages** — the UI is translated into 12 languages (English, Chinese, Spanish, Hindi, Arabic with RTL, Portuguese, Bengali, Russian, Japanese, Korean, French, German) and follows the device language. See [Languages & translation](features/i18n.md), or jump straight to the [translation page](translate.html).

---

[Vision](https://github.com/GlassHaven/Haven/blob/main/VISION.md) · [Translate](translate.html) · [Backup file format](backup-format.md) · [Privacy policy](privacy-policy.html)
