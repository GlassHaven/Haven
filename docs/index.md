---
layout: default
title: Haven
---

<p align="center">
  <a href="https://github.com/GlassHaven/Haven/releases/latest">
    <img src="https://img.shields.io/github/v/release/GlassHaven/Haven?style=flat-square" alt="Release" />
  </a>
  <a href="https://f-droid.org/en/packages/sh.haven.app">
    <img src="https://img.shields.io/f-droid/v/sh.haven.app?style=flat-square" alt="F-Droid" />
  </a>
  <a href="https://github.com/GlassHaven/Haven/actions/workflows/ci.yml?query=branch%3Amain">
    <img src="https://img.shields.io/github/check-runs/GlassHaven/Haven/main?style=flat-square&label=build" alt="Build" />
  </a>
  <a href="https://github.com/GlassHaven/Haven/blob/main/LICENSE">
    <img src="https://img.shields.io/github/license/GlassHaven/Haven?style=flat-square" alt="License" />
  </a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-3ddc84?style=flat-square&logo=android&logoColor=white" alt="Android 8.0+" />
</p>

<p align="center">
  Open source under AGPLv3. No telemetry. No ads. No account.
</p>

## Download

<table>
<thead><tr><th>Channel</th><th>Notes</th></tr></thead>
<tbody>
<tr><td><a href="https://f-droid.org/en/packages/sh.haven.app"><b>F-Droid</b></a></td><td>Built from source, auto-updated, recommended for most users.</td></tr>
<tr><td><a href="https://github.com/GlassHaven/Haven/releases/latest"><b>GitHub Releases</b></a></td><td>Signed APKs (arm64 &amp; x86_64), released first, same binary as F-Droid.</td></tr>
</tbody>
</table>

Both builds are identical. Minimum Android 8.0 (API 26).

## Screenshots

<p align="center">
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/1_terminal.png" width="160" alt="Terminal" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/2_connections.png" width="160" alt="Connections" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/3_wayland_desktop.png" width="160" alt="Wayland desktop" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/4_cloud_storage.png" width="160" alt="Cloud storage" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/6_vnc_desktop.png" width="160" alt="VNC desktop" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/7_keys.png" width="160" alt="Keys" />
</p>

## At a glance

- **Terminal** — Mosh, Eternal Terminal, and SSH sessions with tmux/zellij/screen session restore, configurable keyboard toolbar, and OSC 52 / 8 / 9 / 777 / 7 / 133 integration.
- **Desktop** — VNC (RFB 3.8 with VeNCrypt), RDP (via IronRDP), a native Wayland compositor (labwc on GLES2), and one-tap local X11 desktop via PRoot.
- **Files** — Unified browser for SFTP/SCP, SMB, and 60+ cloud providers (rclone). Multi-select, built-in text editor, image tools, chmod, cross-filesystem copy/move, USB SD card roots.
- **Media** — Transcode and stream on-device with FFmpeg 8.0. HLS streaming to the LAN; DLNA server for cloud media.
- **Keys** — On-device Ed25519 / RSA / ECDSA generation, FIDO2/SK hardware keys (NFC + USB), deploy-key helper.
- **Connections** — Host-key TOFU, port forwarding (-L / -R / -D / -J), SOCKS / HTTP proxies, Tor, ProxyJump, **per-app WireGuard and Tailscale tunnels** (userspace, no system VPN slot).
- **Local shell** — Alpine Linux via PRoot, no root required.
- **Reticulum** — `rnsh` over Reticulum mesh networks, pure Kotlin.
- **Security** — Biometric lock, no telemetry, encrypted backup/restore (AES-256-GCM).

Full detail on the [Features page](FEATURES.md).

## Why Haven?

- **One app covers the whole loop.** SSH + Mosh + ET + VNC + RDP + SFTP + cloud storage + on-device Linux + media transcode, from a single tab bar.
- **No telemetry, no ads, no account.** Nothing is phoned home. See the [privacy policy](privacy-policy.html).
- **Per-app tunnels.** Route individual SSH profiles through WireGuard or Tailscale *without* taking Android's one VPN slot — other apps keep using the direct network.
- **Native everything.** FFmpeg, labwc, IronRDP, rclone, and the Kotlin Reticulum transport are all compiled from source — no Python runtime, no Chaquopy.
- **Ships often.** Releases reach F-Droid within 24 hours via an automated MR. See the [release history](https://github.com/GlassHaven/Haven/releases).

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

Output lands in `app/build/outputs/apk/debug/haven-*-debug.apk`.

## Issues, requests, feedback

- [GitHub issues](https://github.com/GlassHaven/Haven/issues) — bugs and feature requests.
- [Ko-fi](https://ko-fi.com/glassontin) — if you want to say thanks.

## Documentation

- [Features](FEATURES.md) — detailed feature reference.
- [Privacy policy](privacy-policy.html).
- [Source code](https://github.com/GlassHaven/Haven).

---

<p align="center">
  <sub>
    Haven is open source under <a href="https://github.com/GlassHaven/Haven/blob/main/LICENSE">AGPLv3</a>.
    &middot; Android is a trademark of Google LLC.
  </sub>
</p>
