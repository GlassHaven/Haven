<p align="center">
  <img src="fastlane/metadata/android/en-US/images/icon.png" width="80" alt="Haven icon" />
</p>

<h1 align="center">Haven</h1>

<p align="center">
  Free SSH, VNC, RDP &amp; SFTP client for Android
</p>

<p align="center">
  <a href="https://github.com/GlassOnTin/Haven/releases/latest"><img src="https://img.shields.io/github/v/release/GlassOnTin/Haven?style=flat-square" alt="Release" /></a>
  <a href="https://github.com/GlassOnTin/Haven/actions/workflows/ci.yml"><img src="https://img.shields.io/github/actions/workflow/status/GlassOnTin/Haven/ci.yml?branch=main&style=flat-square&label=CI" alt="CI" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/GlassOnTin/Haven?style=flat-square" alt="License" /></a>
  <a href="https://ko-fi.com/glassontin"><img src="https://img.shields.io/badge/Ko--fi-support-ff5e5b?style=flat-square&logo=ko-fi&logoColor=white" alt="Ko-fi" /></a>
</p>

<p align="center">
  <a href="https://github.com/GlassOnTin/Haven/releases/latest">GitHub Releases</a> &bull;
  <a href="https://play.google.com/store/apps/details?id=sh.haven.app">Google Play</a> &bull;
  <a href="https://f-droid.org">F-Droid</a>
</p>

---

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2_connections_ssh.png" width="140" />
  &nbsp;
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1_terminal.png" width="140" />
  &nbsp;
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/5_desktop.png" width="140" />
  &nbsp;
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4_host_key.png" width="140" />
  &nbsp;
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/6_keys.png" width="140" />
  &nbsp;
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3_connections_reticulum.png" width="140" />
  &nbsp;
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/7_settings.png" width="140" />
  &nbsp;
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/8_toolbar_config.png" width="140" />
</p>

---

## Features

**Terminal** — VT100/xterm emulator with multi-tab sessions, [Mosh](https://mosh.org) (Mobile Shell) for roaming connections and [Eternal Terminal](https://eternalterminal.dev) (ET) for persistent sessions — both with pure Kotlin protocol implementations (no native binaries), tmux/zellij/screen auto-attach, mouse mode for TUI apps, configurable keyboard toolbar (Esc, Tab, Ctrl, Alt, AltGr, arrows with key repeat), text selection with copy and Open URL, configurable font size, and six color schemes.

**Desktop (VNC)** — Remote desktop viewer with RFB 3.8 protocol support. Pinch-to-zoom, two-finger pan and scroll, single-finger drag for window management, soft keyboard with X11 KeySym mapping. Fullscreen mode with NoMachine-style corner hotspot for session controls. Connect directly or tunnel through SSH. Supports Raw, CopyRect, RRE, Hextile, and ZLib encodings. **Local Desktop** — one-tap Xfce4 desktop running on-device via PRoot and Xvnc, no root required.

**Desktop (RDP)** — Remote Desktop Protocol client built on [IronRDP](https://github.com/Devolutions/IronRDP) via UniFFI Kotlin bindings. Connects to Windows Remote Desktop, xrdp (Linux), and GNOME Remote Desktop. Pinch-to-zoom, pan, keyboard with scancode mapping, mouse input. SSH tunnel support with auto-connect through saved SSH profiles. Saved connection profiles with optional stored password.

**SFTP** — Browse remote directories, upload and download files, delete, copy path, toggle hidden files, sort by name/size/date, and multi-server tabs.

**SSH Keys** — Generate Ed25519, RSA, and ECDSA keys on-device. Import keys from file (PEM/OpenSSH format). One-tap public key copy and deploy key dialog for `authorized_keys` setup. Assign specific keys to individual connections.

**Connections** — Saved profiles with transport selection (SSH, Mosh, Eternal Terminal, VNC, RDP, Reticulum), host key TOFU verification, fingerprint change detection, auto-reconnect with backoff, password fallback, local/remote port forwarding (-L/-R), ProxyJump multi-hop tunneling (-J) with tree view, and RDP-over-SSH tunnel profiles.

**Reticulum** — Connect over [Reticulum](https://reticulum.network) mesh networks via [rnsh](https://github.com/acehoss/rnsh) or [Sideband](https://github.com/markqvist/Sideband) with announce-based destination discovery and hop count.

**Security** — Biometric app lock with configurable timeout (immediate/30s/1m/5m/never), no telemetry or analytics, local storage only. See [PRIVACY_POLICY.md](PRIVACY_POLICY.md).

<details>
<summary><strong>OSC escape sequences</strong></summary>

Remote programs can interact with Android through standard terminal escape sequences:

| OSC | Function | Example |
|-----|----------|---------|
| 52 | Set clipboard | `printf '\e]52;c;%s\a' "$(echo -n text \| base64)"` |
| 8 | Hyperlinks | `printf '\e]8;;https://example.com\aClick\e]8;;\a'` |
| 9 | Notification | `printf '\e]9;Build complete\a'` |
| 777 | Notification (with title) | `printf '\e]777;notify;CI;Pipeline green\a'` |
| 7 | Working directory | `printf '\e]7;file:///home/user\a'` |

Notifications appear as a toast in the foreground or as an Android notification in the background.

</details>

## Install

| Channel | |
|---|---|
| [GitHub Releases](https://github.com/GlassOnTin/Haven/releases/latest) | Free, signed APK |
| [Google Play](https://play.google.com/store/apps/details?id=sh.haven.app) | Free, auto-updates |
| [F-Droid](https://f-droid.org) | Free, built from source |

### Build from source

```bash
git clone https://github.com/GlassOnTin/Haven.git
cd Haven
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/haven-*-debug.apk`

## License

[GPLv3](LICENSE)
