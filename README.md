<p align="center">
  <img src="fastlane/metadata/android/en-US/images/icon.png" width="80" alt="Haven icon" />
</p>

<h1 align="center">Haven</h1>

<p align="center">
  Free, open-source remote access &amp; mobile workspace for Android —<br/>
  SSH · Mosh · VNC · RDP · SFTP · SMB · email · cloud storage, a local Linux shell, mesh networking, and a consent-gated AI-agent endpoint
</p>

> *"Haven is an interesting vibe coding experiment. Let's see what comes out of it."* — DBP

<p align="center">
  <a href="https://github.com/GlassHaven/Haven/releases/latest"><img src="https://img.shields.io/github/v/release/GlassHaven/Haven?style=flat-square&label=release&color=blue&sort=date" alt="Release" /></a>
  <a href="https://f-droid.org/en/packages/sh.haven.app"><img src="https://img.shields.io/f-droid/v/sh.haven.app?style=flat-square" alt="F-Droid" /></a>
  <a href="https://github.com/GlassHaven/Haven/actions/workflows/ci.yml?query=branch%3Amain"><img src="https://github.com/GlassHaven/Haven/actions/workflows/ci.yml/badge.svg?branch=main" alt="Build" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-AGPL--3.0-orange?style=flat-square" alt="License" /></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-3ddc84?style=flat-square&logo=android&logoColor=white" alt="Android 8.0+" />
  <a href="https://github.com/GlassHaven/Haven/releases"><img src="https://img.shields.io/github/downloads/GlassHaven/Haven/total?style=flat-square&label=downloads&cacheSeconds=3600" alt="Downloads" /></a>
  <a href="https://ko-fi.com/glassontin"><img src="https://img.shields.io/badge/Ko--fi-support-ff5e5b?style=flat-square&logo=ko-fi&logoColor=white" alt="Ko-fi" /></a>
</p>

<p align="center">
  <a href="https://github.com/GlassHaven/Haven/releases/latest">GitHub Releases</a> &bull;
  <a href="https://f-droid.org/en/packages/sh.haven.app">F-Droid</a>
</p>

<p align="center">
  <a href="https://github.com/GlassHaven/Haven/releases/download/v5.60.4/haven-transparency.mp4">
    <img src="docs/haven-transparency.webp" width="300" alt="Terminal transparency over a live device wallpaper — click for full-quality video" />
  </a>
</p>

<p align="center">
  <sub>▶ <a href="https://github.com/GlassHaven/Haven/releases/download/v5.60.4/haven-transparency.mp4">Watch</a>: the terminal — and every Haven screen — made translucent over the device wallpaper (new in 5.60.4). Live wallpaper: <a href="https://magicfluids.com/">MagicFluids</a>.</sub>
</p>

---

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3_wayland_desktop.png" width="140" />
  &nbsp;
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1_terminal.png" width="140" />
  &nbsp;
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2_connections.png" width="140" />
  &nbsp;
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/6_vnc_desktop.png" width="140" />
  &nbsp;
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4_cloud_storage.png" width="140" />
  &nbsp;
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/7_keys.png" width="140" />
</p>

---

## At a glance

- **[Terminal](docs/features/terminal.md)** — Mosh / Eternal Terminal / SSH, tmux-aware session restore, configurable keyboard toolbar, OSC 7/8/9/52/133/777 integration.
- **[Desktops](docs/features/desktops.md)** — VNC (RFB 3.8 / VeNCrypt), RDP (IronRDP + EGFX), a GPU-accelerated native Wayland compositor, and a multi-distro local-desktop manager.
- **[Files & cloud](docs/features/files-and-cloud.md)** — unified browser for SFTP/SCP, SMB, and 60+ cloud providers; cross-filesystem copy/move, editor and image tools; plus on-device FFmpeg transcode, HLS streaming, and DLNA.
- **[Connections](docs/features/connections.md)** — port forwarding (-L/-R/-D/-J), SOCKS/HTTP/Tor proxies, per-app WireGuard & Tailscale tunnels, port knocking and fwknop SPA, and SSH keys (incl. FIDO2/SK).
- **[Email](docs/features/email.md)** — ProtonMail (bridge protocol) and any IMAP/SMTP mailbox; compose / reply / forward, multi-account, attachments; plus **Mail Rules** inbound automation.
- **[Local Linux](docs/features/local-linux.md)** — a Linux userland via PRoot (no root, any Android 8+ device): Alpine, Debian, Arch, or Void, side-by-side.
- **[USB forwarding](docs/features/usb.md)** — broker an attached USB device through Android and re-expose it to the agent, into the Linux guest, or over USB/IP to a **remote host** (e.g. a phone-hosted YubiKey, touch on the phone).
- **[Reticulum](docs/features/reticulum.md)** — rnsh shell, file transfer, and `-L`/`-D` port forwarding over Reticulum mesh, pure Kotlin. The one transport that keeps working with no internet at all.
- **[Agent transport (MCP)](docs/mcp-tools.md)** — an optional MCP server exposing ~130 consent-gated, audited tools; the agent can even **see and operate Haven itself** for a self-hosting build → install → verify loop.
- **[Security](docs/features/security.md)** — biometric lock, no telemetry, encrypted backup/restore (AES-256-GCM).

See [docs/FEATURES.md](docs/FEATURES.md) for the full feature index.

## Why one app

The list above is the parts; the point is how they compose. Each of these is one
flow inside Haven — no second app, no `curl | ssh` incantation:

- Tap a 4K MKV in Google Drive → FFmpeg transcodes it over HTTP and the result
  lands back in the same Drive folder, never touching local disk.
- SSH to a box, forward its port, tap the VNC profile that targets `localhost` —
  the desktop opens in the same app, keyboard and clipboard shared.
- Cut a log directory from an S3 bucket, switch tabs, paste it onto an SFTP
  server — rclone does the server-side copy when it can, otherwise Haven streams
  it through.
- Run your agent CLI in the on-device Linux shell; it pushes over the SSH agent
  you forwarded from your laptop while you watch on the same screen.
- Cast a cloud video to the TV across the room over HLS, copy the LAN URL from
  the snackbar, send it to a friend so they can watch too.

The phone is the thin client, Haven is the thin-client OS, and the cloud, your
servers, and your agents are the computer. Width is sufficient; composition is
the point. ([Vision](VISION.md).)

## Languages

Available in 12 languages: English, Chinese (simplified), Spanish, Hindi, Arabic (with RTL support), Portuguese, Bengali, Russian, Japanese, Korean, French, and German. The UI follows the device language.

**Want to help translate?** Improve a translation or add a new language straight from the [web translation page](https://glasshaven.github.io/Haven/translate.html) — no cloning or setup; your changes become a GitHub pull request. See [Languages & translation](docs/features/i18n.md) for details.

## Install

| Channel | |
|---|---|
| [GitHub Releases](https://github.com/GlassHaven/Haven/releases/latest) | Signed APK, all features |
| [F-Droid](https://f-droid.org/en/packages/sh.haven.app) | Built from source, all features |

Both builds have the same features — SSH, Mosh, Eternal Terminal, VNC, RDP, SFTP, SMB, email, and cloud storage. IronRDP (Rust) is built from source via `cargo-ndk`. rclone (Go) is built from source via `gomobile`.

> **Pick one channel and stay on it.** The two channels are signed with
> **different keys** — GitHub Releases use Haven's own release key; F-Droid
> builds from source and signs with F-Droid's per-app key. Android treats
> different signing keys as different apps, so you **can't update in place from
> one channel to the other** — switching means uninstall + reinstall, which
> clears app data (back up first via **Settings → Backup**). Direct sideloads
> and Obtainium track the GitHub Releases key.
>
> Signing certificate SHA-256 (to verify a sideloaded APK with
> `apksigner verify --print-certs`):
> - GitHub Releases: `ea03a3a70e1c11d0a78932f959b21f20d8735d9cd750997657cb7f7d7c2b90b3`
> - F-Droid: `ea05a89431961b8ac53c36725452673c2be1c2d7b6e48771617b974e6092b332`

## Build from source

Requires [Rust](https://rustup.rs/) with Android targets, `cargo-ndk`, [Go](https://go.dev/dl/) 1.26+, and `gomobile`:

```bash
# Rust (for RDP)
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk

# Go (for rclone cloud storage)
go install golang.org/x/mobile/cmd/gomobile@latest
go install golang.org/x/mobile/cmd/gobind@latest

git clone --recurse-submodules https://github.com/GlassHaven/Haven.git
cd Haven
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/haven-*-debug.apk`

## Documentation

- [Features](docs/FEATURES.md) — detailed feature descriptions.
- [Backup file format](docs/backup-format.md) — wire format, the
  PBKDF2/AES-GCM envelope, and a Python recipe for manual decryption
  if the in-app importer fails.
- [Maintainer Protocol](.claude/skills/maintain/SKILL.md) — the `/maintain`
  skill driving Haven's maintenance loop (CI triage, release readiness, issue
  response, and dependency tracking) run by [Qwen3.8-Flash running locally via llama-claude](https://github.com/GlassOnTin/llama-claude), with
  daily supervisory audit passes performed by Gemini 3.7 Flash.
- [Release process](RELEASE.md) — versioning, tagging, and F-Droid steps.
- [Privacy policy](PRIVACY_POLICY.md).
- [Vision](VISION.md).

## Third-party libraries

| Library | Purpose | License |
|---------|---------|---------|
| [rclone](https://rclone.org) | Cloud storage engine (60+ providers) | MIT |
| [IronRDP](https://github.com/Devolutions/IronRDP) | RDP protocol (Rust/UniFFI) | MIT / Apache-2.0 |
| [JSch](https://github.com/mwiede/jsch) | SSH/SFTP protocol | BSD |
| [smbj](https://github.com/hierynomus/smbj) | SMB/CIFS protocol | Apache-2.0 |
| [ConnectBot termlib](https://github.com/connectbot/connectbot) | Terminal emulator | Apache-2.0 |
| [reticulum-kt](https://github.com/GlassOnTin/reticulum-kt) | Reticulum mesh network transport (Kotlin) | MPL-2.0 |
| [rnsh-kt](https://github.com/GlassOnTin/rnsh-kt) | Reticulum remote shell client (Kotlin) | AGPL-3.0 |
| [FFmpeg](https://ffmpeg.org) | Media conversion and streaming | LGPL-2.1 / GPL-2.0 |
| [PRoot](https://proot-me.github.io) | Local Linux shell (userspace chroot) | GPL-2.0 |
| [labwc](https://labwc.github.io) | Wayland compositor (native desktop) | GPL-2.0 |
| [wlroots](https://gitlab.freedesktop.org/wlroots/wlroots) | Wayland compositor library | MIT |
| [virglrenderer](https://gitlab.freedesktop.org/virgl/virglrenderer) | GPU virtualization (OpenGL passthrough to PRoot apps) | MIT |
| [Jetpack Compose](https://developer.android.com/jetpack/compose) | UI toolkit | Apache-2.0 |

## Backing

Haven sits on top of the projects listed in the table above — the heavy
lifting was done long before this repo existed.

Most of the direction Haven has taken has come from the user base, not
from a roadmap: bug reports, screenshots of edge cases, "have you
tried…” comments on long issue threads. [Qwen3.8-Flash running locally](https://github.com/GlassOnTin/llama-claude) writes most
of the actual code; the maintainer's role is closer to that of a
messenger between the user group and the model: listening, setting
the agenda, and quality-checking.

A small recurring amount comes in via [Ko-fi](https://ko-fi.com/glassontin)
and [Liberapay](https://liberapay.com/GlassOnTin). It helps offset the
electricity costs of running the work above, and it's a clear
signal that the work is useful to people. The project continues
regardless of donations.

## License

[AGPLv3](LICENSE)
