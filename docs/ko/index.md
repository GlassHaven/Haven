---
layout: default
title: Haven
---

<p align="center" style="font-size:.9em">
  <a href="../">English</a> · <a href="../zh/">简体中文</a> · <a href="../es/">Español</a> · <a href="../fr/">Français</a> · <a href="../de/">Deutsch</a> · <a href="../pt/">Português</a> · <a href="../ru/">Русский</a> · <a href="../ja/">日本語</a> · <b>한국어</b> · <a href="../ar/">العربية</a> · <a href="../hi/">हिन्दी</a> · <a href="../bn/">বাংলা</a>
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
  AGPLv3로 공개된 오픈 소스. 텔레메트리 없음. 광고 없음. 계정 없음.
</p>

## 다운로드

<table>
<thead><tr><th>채널</th><th>참고</th></tr></thead>
<tbody>
<tr><td><a href="https://f-droid.org/en/packages/sh.haven.app"><b>F-Droid</b></a></td><td>소스에서 빌드되고 자동 업데이트되며, 대부분의 사용자에게 권장합니다.</td></tr>
<tr><td><a href="https://github.com/GlassHaven/Haven/releases/latest"><b>GitHub Releases</b></a></td><td>서명된 APK(arm64, armv7 &amp; x86_64), 가장 먼저 출시되며 F-Droid와 동일한 기능을 제공합니다.</td></tr>
</tbody>
</table>

어느 쪽이든 기능은 동일합니다. 최소 Android 8.0(API 26)이 필요합니다.

**채널 하나를 선택하고 계속 그 채널을 사용하세요.** GitHub Releases와 F-Droid는
**서로 다른 키**로 서명됩니다(GitHub은 Haven 자체 릴리스 키를 사용하고, F-Droid는
앱별 키로 서명함). 그래서 Android는 둘을 별개의 앱으로 취급하며 — 한쪽에서 다른 쪽으로
제자리 업데이트를 할 수 없습니다. 채널을 전환하려면 제거 후 재설치가 필요하고, 이는
앱 데이터를 지우므로 먼저 **설정 → 백업**으로 백업하세요. Obtainium과 직접
사이드로드는 GitHub Releases 키를 따릅니다.

## 스크린샷

<p align="center">
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/1_terminal.png" width="160" alt="Terminal" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/2_connections.png" width="160" alt="Connections" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/3_wayland_desktop.png" width="160" alt="Wayland desktop" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/4_cloud_storage.png" width="160" alt="Cloud storage" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/6_vnc_desktop.png" width="160" alt="VNC desktop" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/7_keys.png" width="160" alt="Keys" />
</p>

## 한눈에 보기

- **[터미널](../features/terminal.md)** — Mosh / Eternal Terminal / SSH, tmux 세션 복원, 구성 가능한 키보드 툴바, OSC 7/8/9/52/133/777 통합.
- **[데스크톱](../features/desktops.md)** — VNC(RFB 3.8 / VeNCrypt), RDP(IronRDP + EGFX), SPICE(QEMU/KVM, GLZ/QUIC), GPU 가속 네이티브 Wayland 컴포지터, 그리고 다중 배포판 로컬 데스크톱 관리자.
- **[파일 & 클라우드](../features/files-and-cloud.md)** — SFTP/SCP, SMB, 60개 이상의 클라우드 제공업체(rclone), 파일시스템 간 복사/이동; 그리고 FFmpeg 트랜스코딩, HLS, DLNA.
- **[연결](../features/connections.md)** — 포트 포워딩(-L/-R/-D/-J), SOCKS/HTTP/Tor 프록시, 앱별 WireGuard & Tailscale 터널, 포트 노킹 + fwknop SPA, SSH 키 & FIDO2.
- **[이메일](../features/email.md)** — ProtonMail + IMAP/SMTP, 작성/회신/전달, 다중 계정, 메일 규칙 자동화.
- **[로컬 Linux](../features/local-linux.md)** — PRoot를 통한 Alpine / Debian / Arch / Void, 나란히 실행, root 불필요.
- **[USB 포워딩](../features/usb.md)** — USB/IP를 통해 USB 장치를 에이전트, Linux 게스트, 또는 원격 호스트에 중개.
- **[Reticulum](../features/reticulum.md)** — 메시 위에서 rnsh 셸, 파일 전송, 그리고 `-L`/`-D` 포워딩. 인터넷이 전혀 없어도 계속 작동하는 유일한 전송 수단.
- **[에이전트 전송(MCP)](../mcp-tools.md)** — 동의 게이트가 적용된 약 130개의 도구; 에이전트가 Haven 자체 UI를 조작할 수도 있습니다.
- **[보안](../features/security.md)** — 생체 인증 잠금, 텔레메트리 없음, 암호화된 백업/복원(AES-256-GCM).

[전체 기능 색인](../FEATURES.md)을 둘러보세요.

## 왜 하나의 앱인가?

위 목록은 부품이고, 핵심은 그것들이 어떻게 조합되느냐입니다. 아래 각각은 Haven 안에서 이루어지는 하나의 흐름입니다 — 두 번째 앱도, `curl | ssh` 같은 주문도 필요 없습니다:

- Google Drive의 4K MKV를 탭 → FFmpeg가 HTTP로 트랜스코딩하고 결과는 로컬 디스크를 전혀 거치지 않고 같은 Drive 폴더로 돌아옵니다.
- 한 머신에 SSH로 접속하고 그 포트를 포워딩한 뒤 `localhost`를 가리키는 VNC 프로필을 탭하면 — 데스크톱이 같은 앱에서 열리고 키보드와 클립보드를 공유합니다.
- S3 버킷에서 로그 디렉터리를 잘라내 탭을 바꿔 SFTP 서버에 붙여넣으면 — rclone이 가능할 때 서버 측 복사를 하고, 아니면 Haven이 중계합니다.
- 기기의 Linux 셸에서 에이전트 CLI를 실행하면, 노트북에서 포워딩한 SSH 에이전트를 통해 `push`하며, 당신은 같은 화면에서 그 과정을 지켜봅니다.
- 클라우드 영상을 HLS로 방 건너편 TV에 캐스팅하고, 스낵바에서 LAN URL을 복사해 친구에게 보내면 친구도 함께 볼 수 있습니다.

휴대폰은 신 클라이언트이고, Haven은 신 클라이언트 운영체제이며, 클라우드와 당신의 서버, 당신의 에이전트가 바로 그 컴퓨터입니다. 폭은 충분합니다; 핵심은 조합입니다. ([비전](https://github.com/GlassHaven/Haven/blob/main/VISION.md).)

## 언어

12개 언어로 제공됩니다: 영어, 중국어(간체), 스페인어, 힌디어, 아랍어(RTL 지원 포함), 포르투갈어, 벵골어, 러시아어, 일본어, 한국어, 프랑스어, 독일어. UI는 기기 언어를 따릅니다.

**[🌍 Haven 번역 돕기 →](../translate.html)** — 모든 앱 내 문자열을 맥락과 함께 둘러보고, 사용하는 언어에서 빠진 부분을 확인하고, GitHub 풀 리퀘스트로 번역을 제안하세요.

## 왜 Haven인가?

- **하나의 앱이 전체 흐름을 아우릅니다.** SSH + Mosh + ET + VNC + RDP + SFTP + 클라우드 스토리지 + 온디바이스 Linux + 미디어 트랜스코딩, 단일 탭 바에서.
- **텔레메트리 없음, 광고 없음, 계정 없음.** 외부로 전송되는 것은 아무것도 없습니다. [개인정보 처리방침](../privacy-policy.html)을 참조하세요.
- **앱별 터널.** Android의 단일 VPN 슬롯을 차지하지 *않고* 개별 SSH 프로필을 WireGuard나 Tailscale을 통해 라우팅하세요 — 다른 앱들은 직접 네트워크를 계속 사용합니다.
- **모든 것이 네이티브.** FFmpeg, labwc, IronRDP, rclone, 그리고 Kotlin Reticulum 전송은 모두 소스에서 컴파일됩니다 — Python 런타임 없음, Chaquopy 없음.
- **자주 출시됩니다.** 릴리스는 자동화된 MR을 통해 24시간 이내에 F-Droid에 도달합니다. [릴리스 기록](https://github.com/GlassHaven/Haven/releases)을 참조하세요.

## 소스에서 빌드하기

Android 타깃이 포함된 [Rust](https://rustup.rs/), `cargo-ndk`, [Go](https://go.dev/dl/) 1.26+, 그리고 `gomobile`이 필요합니다:

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

결과물은 `app/build/outputs/apk/debug/haven-*-debug.apk`에 생성됩니다.

## 이슈, 요청, 피드백

- [GitHub 이슈](https://github.com/GlassHaven/Haven/issues) — 버그 및 기능 요청.
- [Ko-fi](https://ko-fi.com/glassontin) — 감사를 전하고 싶다면.

## 문서

- [기능](../FEATURES.md) — 자세한 기능 참조.
- [개인정보 처리방침](../privacy-policy.html).
- [소스 코드](https://github.com/GlassHaven/Haven).

---

<p align="center">
  <sub>
    Haven은 <a href="https://github.com/GlassHaven/Haven/blob/main/LICENSE">AGPLv3</a>로 공개된 오픈 소스입니다.
    &middot; Android는 Google LLC의 상표입니다.
  </sub>
</p>
