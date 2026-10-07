---
layout: default
title: Haven
---

<p align="center" style="font-size:.9em">
  <a href="../">English</a> · <b>简体中文</b> · <a href="../es/">Español</a> · <a href="../fr/">Français</a> · <a href="../de/">Deutsch</a> · <a href="../pt/">Português</a> · <a href="../ru/">Русский</a> · <a href="../ja/">日本語</a> · <a href="../ko/">한국어</a> · <a href="../ar/">العربية</a> · <a href="../hi/">हिन्दी</a> · <a href="../bn/">বাংলা</a>
</p>

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
  基于 AGPLv3 的开源软件。无遥测。无广告。无需账户。
</p>

## 下载

<table>
<thead><tr><th>渠道</th><th>说明</th></tr></thead>
<tbody>
<tr><td><a href="https://f-droid.org/en/packages/sh.haven.app"><b>F-Droid</b></a></td><td>从源码构建，自动更新，推荐给大多数用户。</td></tr>
<tr><td><a href="https://github.com/GlassHaven/Haven/releases/latest"><b>GitHub Releases</b></a></td><td>已签名的 APK（arm64, armv7 &amp; x86_64），最先发布，功能与 F-Droid 相同。</td></tr>
</tbody>
</table>

两种方式功能相同。最低要求 Android 8.0（API 26）。

**选定一个渠道并坚持使用。** GitHub Releases 与 F-Droid 使用**不同的密钥**签名（GitHub 使用 Haven 自己的发布密钥；F-Droid 使用其每个应用的专属密钥），因此 Android 会将它们视为两个独立的应用——你无法从一个就地更新到另一个。切换渠道需要卸载后重新安装，这会清除应用数据，所以请先通过 **设置 → 备份** 进行备份。Obtainium 和直接侧载跟随 GitHub Releases 的密钥。

## 截图

<p align="center">
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/1_terminal.png" width="160" alt="Terminal" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/2_connections.png" width="160" alt="Connections" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/3_wayland_desktop.png" width="160" alt="Wayland desktop" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/4_cloud_storage.png" width="160" alt="Cloud storage" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/6_vnc_desktop.png" width="160" alt="VNC desktop" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/7_keys.png" width="160" alt="Keys" />
</p>

## 概览

- **[终端](../features/terminal.md)** — Mosh / Eternal Terminal / SSH、tmux 会话恢复、可配置的键盘工具栏、OSC 7/8/9/52/133/777 集成。
- **[桌面](../features/desktops.md)** — VNC（RFB 3.8 / VeNCrypt）、RDP（IronRDP + EGFX）、SPICE（QEMU/KVM、GLZ/QUIC）、一个 GPU 加速的原生 Wayland 合成器，以及一个多发行版本地桌面管理器。
- **[文件与云](../features/files-and-cloud.md)** — SFTP/SCP、SMB、60 多个云服务商（rclone）、跨文件系统的复制/移动；外加 FFmpeg 转码、HLS 和 DLNA。
- **[连接](../features/connections.md)** — 端口转发（-L/-R/-D/-J）、SOCKS/HTTP/Tor 代理、按应用的 WireGuard 与 Tailscale 隧道、端口敲门 + fwknop SPA、SSH 密钥与 FIDO2。
- **[电子邮件](../features/email.md)** — ProtonMail + IMAP/SMTP、撰写/回复/转发、多账户、邮件规则自动化。
- **[本地 Linux](../features/local-linux.md)** — 通过 PRoot 运行 Alpine / Debian / Arch / Void，可并排运行，无需 root。
- **[USB 转发](../features/usb.md)** — 通过 USB/IP 将一个 USB 设备代理给智能体、Linux 客户机或远程主机。
- **[Reticulum](../features/reticulum.md)** — 在网状网络上的 rnsh shell、文件传输以及 `-L`/`-D` 转发。这是唯一一种在完全没有互联网时仍能继续工作的传输方式。
- **[智能体传输（MCP）](../mcp-tools.md)** — 约 130 个需经同意授权的工具；智能体甚至可以操作 Haven 自己的界面。
- **[安全](../features/security.md)** — 生物识别锁、无遥测、加密的备份/恢复（AES-256-GCM）。

浏览[完整功能索引](../FEATURES.md)。

## 为什么是一个应用？

上面的列表是各个部件；重点在于它们如何组合。下面每一项都是 Haven 内的单一流程——无需第二个应用，也无需 `curl | ssh` 之类的咒语：

- 点按 Google Drive 中的一个 4K MKV → FFmpeg 通过 HTTP 转码，结果回到同一个 Drive 文件夹，全程不碰本地磁盘。
- SSH 连接到一台机器，转发它的端口，点按指向 `localhost` 的 VNC 配置——桌面在同一个应用中打开，键盘和剪贴板共享。
- 从 S3 存储桶剪切一个日志目录，切换标签页，粘贴到 SFTP 服务器——rclone 在可能时做服务端复制，否则由 Haven 中转。
- 在设备上的 Linux shell 里运行你的智能体 CLI；它通过你从笔记本转发的 SSH agent 执行 `push`，而你在同一块屏幕上看着它工作。
- 通过 HLS 把云端视频投送到房间另一头的电视上，从 snackbar 复制局域网 URL，发给朋友，他们也能一起看。

手机是瘦客户端，Haven 是瘦客户端操作系统，而云、你的服务器和你的智能体才是那台计算机。广度已经足够；组合才是关键。（[愿景](https://github.com/GlassHaven/Haven/blob/main/VISION.md)）

## 语言

提供 12 种语言：英语、简体中文、西班牙语、印地语、阿拉伯语（支持 RTL）、葡萄牙语、孟加拉语、俄语、日语、韩语、法语和德语。界面跟随设备语言。

**[🌍 帮助翻译 Haven →](../translate.html)** — 浏览每一条带上下文的应用内文本，查看你的语言还缺少哪些内容，并以 GitHub 拉取请求的形式提交翻译。

## 为什么选择 Haven？

- **一个应用覆盖整个工作流。** SSH + Mosh + ET + VNC + RDP + SFTP + 云存储 + 设备本地 Linux + 媒体转码，全部集中在一个标签栏中。
- **无遥测、无广告、无需账户。** 不会把任何内容回传。参见[隐私政策](../privacy-policy.html)。
- **按应用隧道。** 让单个 SSH 配置文件经由 WireGuard 或 Tailscale 路由，*而无需*占用 Android 唯一的 VPN 名额——其他应用仍可继续使用直连网络。
- **全部原生。** FFmpeg、labwc、IronRDP、rclone 以及 Kotlin 编写的 Reticulum 传输全部从源码编译——没有 Python 运行时，也没有 Chaquopy。
- **频繁发布。** 新版本通过自动化 MR 在 24 小时内到达 F-Droid。参见[发布历史](https://github.com/GlassHaven/Haven/releases)。

## 从源码构建

需要[Rust](https://rustup.rs/)（含 Android 目标）、`cargo-ndk`、[Go](https://go.dev/dl/) 1.26+ 以及 `gomobile`：

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

输出会生成在 `app/build/outputs/apk/debug/haven-*-debug.apk`。

## 问题、需求、反馈

- [GitHub issues](https://github.com/GlassHaven/Haven/issues) — bug 与功能需求。
- [Ko-fi](https://ko-fi.com/glassontin) — 如果你想表示感谢。

## 文档

- [功能](../FEATURES.md) — 详细功能参考。
- [隐私政策](../privacy-policy.html)。
- [源代码](https://github.com/GlassHaven/Haven)。

---

<p align="center">
  <sub>
    Haven is open source under <a href="https://github.com/GlassHaven/Haven/blob/main/LICENSE">AGPLv3</a>.
    &middot; Android is a trademark of Google LLC.
  </sub>
</p>
