<p align="center">
  <img src="fastlane/metadata/android/en-US/images/icon.png" width="80" alt="Haven icon" />
</p>

<h1 align="center">Haven</h1>

<p align="center">
  Free SSH, VNC, RDP, SFTP &amp; cloud storage client for Android
</p>

> *"Haven is an interesting vibe coding experiment. Let's see what comes out of it."* — DBP

<p align="center">
  <a href="https://github.com/GlassOnTin/Haven/releases/latest"><img src="https://img.shields.io/github/v/release/GlassOnTin/Haven?style=flat-square" alt="Release" /></a>
  <a href="https://f-droid.org/en/packages/sh.haven.app"><img src="https://img.shields.io/f-droid/v/sh.haven.app?style=flat-square" alt="F-Droid" /></a>
  <a href="https://github.com/GlassOnTin/Haven/actions/workflows/ci.yml?query=branch%3Amain"><img src="https://img.shields.io/github/check-runs/GlassOnTin/Haven/main?style=flat-square&label=build" alt="Build" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/GlassOnTin/Haven?style=flat-square" alt="License" /></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-3ddc84?style=flat-square&logo=android&logoColor=white" alt="Android 8.0+" />
  <a href="https://github.com/GlassOnTin/Haven/releases"><img src="https://img.shields.io/github/downloads/GlassOnTin/Haven/total?style=flat-square&label=downloads" alt="Downloads" /></a>
  <a href="https://ko-fi.com/glassontin"><img src="https://img.shields.io/badge/Ko--fi-support-ff5e5b?style=flat-square&logo=ko-fi&logoColor=white" alt="Ko-fi" /></a>
</p>

<p align="center">
  <a href="https://github.com/GlassOnTin/Haven/releases/latest">GitHub Releases</a> &bull;
  <a href="https://f-droid.org/en/packages/sh.haven.app">F-Droid</a>
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

- **Terminal** — Mosh / Eternal Terminal / SSH with tmux-aware session restore, configurable keyboard toolbar, OSC 52/8/9/777/7/133 integration.
- **Desktop** — VNC (RFB 3.8 with VeNCrypt), RDP (via IronRDP), a native Wayland compositor (labwc on GLES2), and one-tap local X11 desktop via PRoot.
- **Files** — Unified browser for SFTP/SCP, SMB, and 60+ cloud providers. Multi-select, built-in editor, image tools, chmod, cross-filesystem copy/move.
- **Media** — Transcode and stream on-device with FFmpeg 8.0; HLS streaming to the LAN; DLNA server for cloud media.
- **Keys** — On-device Ed25519/RSA/ECDSA generation, FIDO2/SK hardware keys (NFC + USB), deploy-key helper.
- **Connections** — Host-key TOFU, port forwarding (-L/-R/-D/-J), SOCKS/HTTP proxies, Tor, ProxyJump.
- **Local shell** — Alpine Linux via PRoot (no root, any Android 8+ device).
- **Reticulum** — rnsh over Reticulum mesh networks, pure Kotlin.
- **Security** — Biometric lock, no telemetry, encrypted backup/restore (AES-256-GCM).

See [docs/FEATURES.md](docs/FEATURES.md) for the full, detailed feature list.

## Languages

Available in 11 languages: English, Chinese (simplified), Spanish, Hindi, Arabic (with RTL support), Portuguese, Bengali, Russian, Japanese, French, and German. The UI follows the device language. Community translation contributions welcome.

## Install

| Channel | |
|---|---|
| [GitHub Releases](https://github.com/GlassOnTin/Haven/releases/latest) | Signed APK, all features |
| [F-Droid](https://f-droid.org/en/packages/sh.haven.app) | Built from source, all features |

Both builds are identical — SSH, Mosh, Eternal Terminal, VNC, RDP, SFTP, and Cloud Storage. IronRDP (Rust) is built from source via `cargo-ndk`. rclone (Go) is built from source via `gomobile`.

## Build from source

Requires [Rust](https://rustup.rs/) with Android targets, `cargo-ndk`, [Go](https://go.dev/dl/) 1.26+, and `gomobile`:

```bash
# Rust (for RDP)
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk

# Go (for rclone cloud storage)
go install golang.org/x/mobile/cmd/gomobile@latest
go install golang.org/x/mobile/cmd/gobind@latest

git clone --recurse-submodules https://github.com/GlassOnTin/Haven.git
cd Haven
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/haven-*-debug.apk`

## Documentation

- [Features](docs/FEATURES.md) — detailed feature descriptions.
- [Release process](RELEASE.md) — versioning, tagging, F-Droid and Play Store steps.
- [Privacy policy](PRIVACY_POLICY.md).
- [Roadmap](ROADMAP.md) and [Vision](VISION.md).

## Third-party libraries

| Library | Purpose | License |
|---------|---------|---------|
| [rclone](https://rclone.org) | Cloud storage engine (60+ providers) | MIT |
| [IronRDP](https://github.com/Devolutions/IronRDP) | RDP protocol (Rust/UniFFI) | MIT / Apache-2.0 |
| [JSch](https://github.com/mwiede/jsch) | SSH/SFTP protocol | BSD |
| [smbj](https://github.com/hierynomus/smbj) | SMB/CIFS protocol | Apache-2.0 |
| [ConnectBot termlib](https://github.com/connectbot/connectbot) | Terminal emulator | Apache-2.0 |
| [reticulum-kt](https://github.com/GlassOnTin/reticulum-kt) | Reticulum mesh network transport (Kotlin) | MIT |
| [rnsh-kt](https://github.com/GlassOnTin/rnsh-kt) | Reticulum remote shell client (Kotlin) | MIT |
| [FFmpeg](https://ffmpeg.org) | Media conversion and streaming | LGPL-2.1 / GPL-2.0 |
| [PRoot](https://proot-me.github.io) | Local Linux shell (userspace chroot) | GPL-2.0 |
| [labwc](https://labwc.github.io) | Wayland compositor (native desktop) | GPL-2.0 |
| [wlroots](https://gitlab.freedesktop.org/wlroots/wlroots) | Wayland compositor library | MIT |
| [virglrenderer](https://gitlab.freedesktop.org/virgl/virglrenderer) | GPU virtualization (OpenGL passthrough to PRoot apps) | MIT |
| [Jetpack Compose](https://developer.android.com/jetpack/compose) | UI toolkit | Apache-2.0 |

## License

[AGPLv3](LICENSE)
