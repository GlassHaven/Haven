---
layout: default
title: Haven
---

<p align="center" style="font-size:.9em">
  <a href="../">English</a> · <a href="../zh/">简体中文</a> · <a href="../es/">Español</a> · <a href="../fr/">Français</a> · <b>Deutsch</b> · <a href="../pt/">Português</a> · <a href="../ru/">Русский</a> · <a href="../ja/">日本語</a> · <a href="../ko/">한국어</a> · <a href="../ar/">العربية</a> · <a href="../hi/">हिन्दी</a> · <a href="../bn/">বাংলা</a>
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
  Open Source unter AGPLv3. Keine Telemetrie. Keine Werbung. Kein Konto.
</p>

## Download

<table>
<thead><tr><th>Kanal</th><th>Hinweise</th></tr></thead>
<tbody>
<tr><td><a href="https://f-droid.org/en/packages/sh.haven.app"><b>F-Droid</b></a></td><td>Aus dem Quellcode erstellt, automatisch aktualisiert, empfohlen für die meisten Nutzer.</td></tr>
<tr><td><a href="https://github.com/GlassHaven/Haven/releases/latest"><b>GitHub Releases</b></a></td><td>Signierte APKs (arm64, armv7 &amp; x86_64), zuerst veröffentlicht, gleiche Funktionen wie F-Droid.</td></tr>
</tbody>
</table>

Beide Wege bieten die gleichen Funktionen. Mindestens Android 8.0 (API 26).

**Wähle einen Kanal und bleibe dabei.** GitHub Releases und F-Droid werden mit
**unterschiedlichen Schlüsseln** signiert (GitHub verwendet Havens eigenen Release-Schlüssel; F-Droid signiert mit seinem
app-spezifischen Schlüssel), sodass Android sie als separate Apps behandelt — du kannst nicht direkt
von einem zum anderen aktualisieren. Ein Kanalwechsel erfordert eine Deinstallation + Neuinstallation, was
die App-Daten löscht, also sichere zuerst über **Einstellungen → Sicherung**. Obtainium und direkte
Sideloads folgen dem GitHub-Releases-Schlüssel.

## Screenshots

<p align="center">
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/1_terminal.png" width="160" alt="Terminal" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/2_connections.png" width="160" alt="Connections" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/3_wayland_desktop.png" width="160" alt="Wayland desktop" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/4_cloud_storage.png" width="160" alt="Cloud storage" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/6_vnc_desktop.png" width="160" alt="VNC desktop" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/7_keys.png" width="160" alt="Keys" />
</p>

## Auf einen Blick

- **[Terminal](../features/terminal.md)** — Mosh / Eternal Terminal / SSH, tmux-Sitzungswiederherstellung, konfigurierbare Tastatur-Werkzeugleiste, OSC 7/8/9/52/133/777-Integration.
- **[Desktops](../features/desktops.md)** — VNC (RFB 3.8 / VeNCrypt), RDP (IronRDP + EGFX), SPICE (QEMU/KVM, GLZ/QUIC), ein GPU-beschleunigter nativer Wayland-Compositor und ein Multi-Distro-Manager für lokale Desktops.
- **[Dateien & Cloud](../features/files-and-cloud.md)** — SFTP/SCP, SMB, 60+ Cloud-Anbieter (rclone), dateisystemübergreifendes Kopieren/Verschieben; dazu FFmpeg-Transcodierung, HLS und DLNA.
- **[Verbindungen](../features/connections.md)** — Portweiterleitung (-L/-R/-D/-J), SOCKS/HTTP/Tor-Proxys, App-spezifische WireGuard- & Tailscale-Tunnel, Port-Knocking + fwknop SPA, SSH-Schlüssel & FIDO2.
- **[E-Mail](../features/email.md)** — ProtonMail + IMAP/SMTP, Verfassen/Antworten/Weiterleiten, mehrere Konten, Automatisierung mit Mail-Regeln.
- **[Lokales Linux](../features/local-linux.md)** — Alpine / Debian / Arch / Void über PRoot, nebeneinander, kein Root erforderlich.
- **[USB-Weiterleitung](../features/usb.md)** — vermittle ein USB-Gerät an den Agenten, das Linux-Gastsystem oder einen entfernten Host über USB/IP.
- **[Reticulum](../features/reticulum.md)** — rnsh-Shell, Dateiübertragung und `-L`/`-D`-Weiterleitung über Mesh. Der eine Transportweg, der ganz ohne Internet weiter funktioniert.
- **[Agent-Transport (MCP)](../mcp-tools.md)** — ~130 zustimmungspflichtige Werkzeuge; der Agent kann sogar Havens eigene Oberfläche bedienen.
- **[Sicherheit](../features/security.md)** — biometrische Sperre, keine Telemetrie, verschlüsselte Sicherung/Wiederherstellung (AES-256-GCM).

Durchstöbere den [vollständigen Funktionsindex](../FEATURES.md).

## Warum eine einzige App?

Die Liste oben sind die Bausteine; worauf es ankommt, ist, wie sie sich kombinieren lassen. Jedes davon ist ein einziger Ablauf in Haven — keine zweite App, keine `curl | ssh`-Beschwörung:

- Tippe auf eine 4K-MKV in Google Drive → FFmpeg transkodiert sie über HTTP, und das Ergebnis landet wieder im selben Drive-Ordner, ohne je den lokalen Speicher zu berühren.
- Per SSH auf einen Rechner, leite dessen Port weiter, tippe auf das VNC-Profil, das auf `localhost` zeigt — der Desktop öffnet sich in derselben App, Tastatur und Zwischenablage geteilt.
- Schneide ein Log-Verzeichnis aus einem S3-Bucket aus, wechsle den Tab, füge es auf einem SFTP-Server ein — rclone kopiert serverseitig, wenn es geht, sonst leitet Haven es durch.
- Starte die CLI deines Agenten in der Linux-Shell auf dem Gerät; sie pusht über den SSH-Agent, den du von deinem Laptop weitergeleitet hast, während du auf demselben Bildschirm zusiehst.
- Streame ein Cloud-Video per HLS auf den Fernseher auf der anderen Seite des Raums, kopiere die LAN-URL aus der Snackbar und schicke sie einem Freund, damit er mitschauen kann.

Das Telefon ist der Thin Client, Haven ist das Thin-Client-Betriebssystem, und die Cloud, deine Server und deine Agenten sind der Computer. Die Breite genügt; worauf es ankommt, ist die Komposition. ([Vision](https://github.com/GlassHaven/Haven/blob/main/VISION.md).)

## Sprachen

Verfügbar in 12 Sprachen: Englisch, Chinesisch (vereinfacht), Spanisch, Hindi, Arabisch (mit RTL-Unterstützung), Portugiesisch, Bengalisch, Russisch, Japanisch, Koreanisch, Französisch und Deutsch. Die Oberfläche folgt der Gerätesprache.

**[🌍 Hilf mit, Haven zu übersetzen →](../translate.html)** — durchstöbere jeden In-App-Text mit Kontext, sieh, was in deiner Sprache fehlt, und schlage eine Übersetzung als GitHub-Pull-Request vor.

## Warum Haven?

- **Eine App deckt den gesamten Arbeitsablauf ab.** SSH + Mosh + ET + VNC + RDP + SFTP + Cloud-Speicher + Linux auf dem Gerät + Medien-Transcodierung, aus einer einzigen Tab-Leiste.
- **Keine Telemetrie, keine Werbung, kein Konto.** Nichts wird nach Hause gefunkt. Siehe die [Datenschutzerklärung](../privacy-policy.html).
- **App-spezifische Tunnel.** Leite einzelne SSH-Profile durch WireGuard oder Tailscale, *ohne* Androids einzigen VPN-Slot zu belegen — andere Apps nutzen weiterhin das direkte Netzwerk.
- **Alles nativ.** FFmpeg, labwc, IronRDP, rclone und der Kotlin-Reticulum-Transport sind alle aus dem Quellcode kompiliert — keine Python-Laufzeit, kein Chaquopy.
- **Häufige Veröffentlichungen.** Releases erreichen F-Droid innerhalb von 24 Stunden über einen automatisierten MR. Siehe den [Veröffentlichungsverlauf](https://github.com/GlassHaven/Haven/releases).

## Aus dem Quellcode erstellen

Erfordert [Rust](https://rustup.rs/) mit Android-Zielen, `cargo-ndk`, [Go](https://go.dev/dl/) 1.26+ und `gomobile`:

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

Das Ergebnis landet in `app/build/outputs/apk/debug/haven-*-debug.apk`.

## Probleme, Wünsche, Feedback

- [GitHub-Issues](https://github.com/GlassHaven/Haven/issues) — Fehler und Funktionswünsche.
- [Ko-fi](https://ko-fi.com/glassontin) — falls du Danke sagen möchtest.

## Dokumentation

- [Funktionen](../FEATURES.md) — ausführliche Funktionsreferenz.
- [Datenschutzerklärung](../privacy-policy.html).
- [Quellcode](https://github.com/GlassHaven/Haven).

---

<p align="center">
  <sub>
    Haven ist Open Source unter <a href="https://github.com/GlassHaven/Haven/blob/main/LICENSE">AGPLv3</a>.
    &middot; Android ist eine Marke von Google LLC.
  </sub>
</p>
