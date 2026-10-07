---
layout: default
title: Haven
---

<p align="center" style="font-size:.9em">
  <a href="../">English</a> · <a href="../zh/">简体中文</a> · <a href="../es/">Español</a> · <b>Français</b> · <a href="../de/">Deutsch</a> · <a href="../pt/">Português</a> · <a href="../ru/">Русский</a> · <a href="../ja/">日本語</a> · <a href="../ko/">한국어</a> · <a href="../ar/">العربية</a> · <a href="../hi/">हिन्दी</a> · <a href="../bn/">বাংলা</a>
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
  Open source sous AGPLv3. Pas de télémétrie. Pas de publicité. Pas de compte.
</p>

## Téléchargement

<table>
<thead><tr><th>Canal</th><th>Remarques</th></tr></thead>
<tbody>
<tr><td><a href="https://f-droid.org/en/packages/sh.haven.app"><b>F-Droid</b></a></td><td>Compilé depuis les sources, mis à jour automatiquement, recommandé pour la plupart des utilisateurs.</td></tr>
<tr><td><a href="https://github.com/GlassHaven/Haven/releases/latest"><b>Versions GitHub</b></a></td><td>APK signés (arm64, armv7 &amp; x86_64), publiés en premier, mêmes fonctionnalités que F-Droid.</td></tr>
</tbody>
</table>

Mêmes fonctionnalités dans les deux cas. Android 8.0 (API 26) minimum.

**Choisissez un seul canal et restez-y.** Les versions GitHub et F-Droid sont signées avec
**des clés différentes** (GitHub utilise la propre clé de publication de Haven ; F-Droid signe avec sa
clé par application), donc Android les traite comme des applications distinctes — vous ne pouvez pas mettre à jour sur place
de l'une à l'autre. Changer de canal nécessite une désinstallation + réinstallation, ce qui
efface les données de l'application, alors faites d'abord une sauvegarde via **Paramètres → Sauvegarde**. Obtainium et les
installations manuelles directes suivent la clé des versions GitHub.

## Captures d'écran

<p align="center">
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/1_terminal.png" width="160" alt="Terminal" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/2_connections.png" width="160" alt="Connections" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/3_wayland_desktop.png" width="160" alt="Wayland desktop" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/4_cloud_storage.png" width="160" alt="Cloud storage" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/6_vnc_desktop.png" width="160" alt="VNC desktop" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/7_keys.png" width="160" alt="Keys" />
</p>

## En un coup d'œil

- **[Terminal](../features/terminal.md)** — Mosh / Eternal Terminal / SSH, restauration de session tmux, barre d'outils clavier configurable, intégration OSC 7/8/9/52/133/777.
- **[Bureaux](../features/desktops.md)** — VNC (RFB 3.8 / VeNCrypt), RDP (IronRDP + EGFX), SPICE (QEMU/KVM, GLZ/QUIC), un compositeur Wayland natif accéléré par GPU, et un gestionnaire de bureaux locaux multi-distributions.
- **[Fichiers & cloud](../features/files-and-cloud.md)** — SFTP/SCP, SMB, plus de 60 fournisseurs cloud (rclone), copie/déplacement entre systèmes de fichiers ; plus le transcodage FFmpeg, HLS et DLNA.
- **[Connexions](../features/connections.md)** — redirection de ports (-L/-R/-D/-J), proxys SOCKS/HTTP/Tor, tunnels WireGuard & Tailscale par application, port knocking + SPA fwknop, clés SSH & FIDO2.
- **[E-mail](../features/email.md)** — ProtonMail + IMAP/SMTP, rédaction/réponse/transfert, multi-comptes, automatisation par règles de courrier.
- **[Linux local](../features/local-linux.md)** — Alpine / Debian / Arch / Void via PRoot, côte à côte, sans accès root requis.
- **[Redirection USB](../features/usb.md)** — partagez un périphérique USB avec l'agent, l'invité Linux ou un hôte distant via USB/IP.
- **[Reticulum](../features/reticulum.md)** — shell rnsh, transfert de fichiers, et redirection `-L`/`-D` sur le maillage. Le seul transport qui continue de fonctionner sans aucune connexion internet.
- **[Transport agent (MCP)](../mcp-tools.md)** — environ 130 outils soumis à consentement ; l'agent peut même piloter l'interface de Haven elle-même.
- **[Sécurité](../features/security.md)** — verrouillage biométrique, pas de télémétrie, sauvegarde/restauration chiffrée (AES-256-GCM).

Parcourez l'[index complet des fonctionnalités](../FEATURES.md).

## Pourquoi une seule application ?

La liste ci-dessus, ce sont les pièces ; l'intérêt, c'est la façon dont elles se composent. Chacun de ces scénarios est un seul flux au sein de Haven — sans seconde application, sans incantation `curl | ssh` :

- Touchez un MKV 4K sur Google Drive → FFmpeg le transcode via HTTP et le résultat revient dans le même dossier Drive, sans jamais toucher le disque local.
- Connectez-vous en SSH à une machine, transférez son port, touchez le profil VNC qui pointe vers `localhost` : le bureau s'ouvre dans la même application, clavier et presse-papiers partagés.
- Coupez un répertoire de journaux depuis un bucket S3, changez d'onglet, collez-le sur un serveur SFTP — rclone fait la copie côté serveur quand c'est possible, sinon Haven la relaie en flux.
- Lancez la CLI de votre agent dans le shell Linux embarqué ; elle fait un `push` via l'agent SSH que vous avez transféré depuis votre ordinateur portable, pendant que vous regardez sur le même écran.
- Diffusez une vidéo du cloud vers le téléviseur à l'autre bout de la pièce en HLS, copiez l'URL du réseau local depuis le snackbar et envoyez-la à un ami pour qu'il puisse regarder aussi.

Le téléphone est le client léger, Haven est le système d'exploitation client léger, et le cloud, vos serveurs et vos agents sont l'ordinateur. La largeur suffit ; l'essentiel, c'est la composition. ([Vision](https://github.com/GlassHaven/Haven/blob/main/VISION.md).)

## Langues

Disponible en 12 langues : anglais, chinois (simplifié), espagnol, hindi, arabe (avec prise en charge RTL), portugais, bengali, russe, japonais, coréen, français et allemand. L'interface suit la langue de l'appareil.

**[🌍 Aidez à traduire Haven →](../translate.html)** — parcourez chaque chaîne de l'application avec son contexte, voyez ce qui manque dans votre langue, et proposez une traduction sous forme de pull request GitHub.

## Pourquoi Haven ?

- **Une seule application couvre toute la boucle.** SSH + Mosh + ET + VNC + RDP + SFTP + stockage cloud + Linux sur l'appareil + transcodage multimédia, depuis une unique barre d'onglets.
- **Pas de télémétrie, pas de publicité, pas de compte.** Rien n'est renvoyé à distance. Consultez la [politique de confidentialité](../privacy-policy.html).
- **Tunnels par application.** Acheminez des profils SSH individuels via WireGuard ou Tailscale *sans* occuper l'unique emplacement VPN d'Android — les autres applications continuent d'utiliser le réseau direct.
- **Tout en natif.** FFmpeg, labwc, IronRDP, rclone et le transport Reticulum en Kotlin sont tous compilés depuis les sources — pas d'environnement d'exécution Python, pas de Chaquopy.
- **Publié souvent.** Les versions arrivent sur F-Droid en moins de 24 heures via une MR automatisée. Consultez l'[historique des versions](https://github.com/GlassHaven/Haven/releases).

## Compiler depuis les sources

Nécessite [Rust](https://rustup.rs/) avec les cibles Android, `cargo-ndk`, [Go](https://go.dev/dl/) 1.26+ et `gomobile` :

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

Le résultat se trouve dans `app/build/outputs/apk/debug/haven-*-debug.apk`.

## Problèmes, demandes, retours

- [Issues GitHub](https://github.com/GlassHaven/Haven/issues) — bogues et demandes de fonctionnalités.
- [Ko-fi](https://ko-fi.com/glassontin) — si vous voulez dire merci.

## Documentation

- [Fonctionnalités](../FEATURES.md) — référence détaillée des fonctionnalités.
- [Politique de confidentialité](../privacy-policy.html).
- [Code source](https://github.com/GlassHaven/Haven).

---

<p align="center">
  <sub>
    Haven est open source sous <a href="https://github.com/GlassHaven/Haven/blob/main/LICENSE">AGPLv3</a>.
    &middot; Android est une marque de Google LLC.
  </sub>
</p>
