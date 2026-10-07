---
layout: default
title: Haven
---

<p align="center" style="font-size:.9em">
  <a href="../">English</a> · <a href="../zh/">简体中文</a> · <a href="../es/">Español</a> · <a href="../fr/">Français</a> · <a href="../de/">Deutsch</a> · <b>Português</b> · <a href="../ru/">Русский</a> · <a href="../ja/">日本語</a> · <a href="../ko/">한국어</a> · <a href="../ar/">العربية</a> · <a href="../hi/">हिन्दी</a> · <a href="../bn/">বাংলা</a>
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
  Código aberto sob a AGPLv3. Sem telemetria. Sem anúncios. Sem conta.
</p>

## Download

<table>
<thead><tr><th>Canal</th><th>Notas</th></tr></thead>
<tbody>
<tr><td><a href="https://f-droid.org/en/packages/sh.haven.app"><b>F-Droid</b></a></td><td>Compilado a partir do código-fonte, atualizado automaticamente, recomendado para a maioria dos usuários.</td></tr>
<tr><td><a href="https://github.com/GlassHaven/Haven/releases/latest"><b>GitHub Releases</b></a></td><td>APKs assinados (arm64, armv7 &amp; x86_64), lançados primeiro, com os mesmos recursos do F-Droid.</td></tr>
</tbody>
</table>

Os mesmos recursos de qualquer forma. Android 8.0 (API 26) no mínimo.

**Escolha um canal e fique com ele.** GitHub Releases e F-Droid são assinados com
**chaves diferentes** (o GitHub usa a própria chave de lançamento do Haven; o F-Droid assina com sua
chave por aplicativo), então o Android os trata como aplicativos separados — você não pode atualizar no lugar
de um para o outro. Trocar de canal exige desinstalar + reinstalar, o que
apaga os dados do aplicativo, então faça um backup primeiro em **Configurações → Backup**. O Obtainium e os
sideloads diretos seguem a chave do GitHub Releases.

## Capturas de tela

<p align="center">
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/1_terminal.png" width="160" alt="Terminal" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/2_connections.png" width="160" alt="Connections" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/3_wayland_desktop.png" width="160" alt="Wayland desktop" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/4_cloud_storage.png" width="160" alt="Cloud storage" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/6_vnc_desktop.png" width="160" alt="VNC desktop" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/7_keys.png" width="160" alt="Keys" />
</p>

## Visão geral

- **[Terminal](../features/terminal.md)** — Mosh / Eternal Terminal / SSH, restauração de sessão tmux, barra de ferramentas de teclado configurável, integração OSC 7/8/9/52/133/777.
- **[Áreas de trabalho](../features/desktops.md)** — VNC (RFB 3.8 / VeNCrypt), RDP (IronRDP + EGFX), SPICE (QEMU/KVM, GLZ/QUIC), um compositor Wayland nativo acelerado por GPU e um gerenciador de áreas de trabalho locais multi-distribuição.
- **[Arquivos e nuvem](../features/files-and-cloud.md)** — SFTP/SCP, SMB, mais de 60 provedores de nuvem (rclone), cópia/movimentação entre sistemas de arquivos; além de transcodificação com FFmpeg, HLS e DLNA.
- **[Conexões](../features/connections.md)** — encaminhamento de portas (-L/-R/-D/-J), proxies SOCKS/HTTP/Tor, túneis WireGuard e Tailscale por aplicativo, port knocking + fwknop SPA, chaves SSH e FIDO2.
- **[E-mail](../features/email.md)** — ProtonMail + IMAP/SMTP, escrever/responder/encaminhar, várias contas, automação de Regras de E-mail.
- **[Linux local](../features/local-linux.md)** — Alpine / Debian / Arch / Void via PRoot, lado a lado, sem necessidade de root.
- **[Encaminhamento USB](../features/usb.md)** — disponibilize um dispositivo USB para o agente, para o convidado Linux ou para um host remoto via USB/IP.
- **[Reticulum](../features/reticulum.md)** — shell rnsh, transferência de arquivos e encaminhamento `-L`/`-D` sobre malha (mesh). O único transporte que continua funcionando sem nenhuma internet.
- **[Transporte de agente (MCP)](../mcp-tools.md)** — cerca de 130 ferramentas protegidas por consentimento; o agente pode até controlar a própria interface do Haven.
- **[Segurança](../features/security.md)** — bloqueio biométrico, sem telemetria, backup/restauração criptografados (AES-256-GCM).

Explore o [índice completo de recursos](../FEATURES.md).

## Por que um só aplicativo?

A lista acima são as peças; o que importa é como elas se compõem. Cada um destes é um único fluxo dentro do Haven — sem um segundo aplicativo, sem encantamentos do tipo `curl | ssh`:

- Toque num MKV 4K no Google Drive → o FFmpeg o transcodifica via HTTP e o resultado volta para a mesma pasta do Drive, sem nunca tocar no disco local.
- Faça SSH para uma máquina, encaminhe a porta dela, toque no perfil VNC que aponta para `localhost` — a área de trabalho abre no mesmo aplicativo, com teclado e área de transferência compartilhados.
- Recorte um diretório de logs de um bucket S3, troque de aba e cole num servidor SFTP — o rclone faz a cópia do lado do servidor quando dá, senão o Haven a transmite.
- Execute a CLI do seu agente no shell Linux do dispositivo; ele faz `push` pelo agente SSH que você encaminhou do seu notebook enquanto você assiste na mesma tela.
- Transmita um vídeo da nuvem para a TV do outro lado da sala por HLS, copie a URL da rede local do snackbar e envie para um amigo para que ele também possa assistir.

O telefone é o cliente leve, o Haven é o sistema operacional de cliente leve, e a nuvem, seus servidores e seus agentes são o computador. A largura é suficiente; o que importa é a composição. ([Visão](https://github.com/GlassHaven/Haven/blob/main/VISION.md).)

## Idiomas

Disponível em 12 idiomas: inglês, chinês (simplificado), espanhol, hindi, árabe (com suporte a RTL), português, bengali, russo, japonês, coreano, francês e alemão. A interface segue o idioma do dispositivo.

**[🌍 Ajude a traduzir o Haven →](../translate.html)** — navegue por cada string do aplicativo com contexto, veja o que está faltando no seu idioma e proponha uma tradução como um pull request no GitHub.

## Por que o Haven?

- **Um aplicativo cobre todo o ciclo.** SSH + Mosh + ET + VNC + RDP + SFTP + armazenamento em nuvem + Linux no dispositivo + transcodificação de mídia, a partir de uma única barra de abas.
- **Sem telemetria, sem anúncios, sem conta.** Nada é enviado para casa. Veja a [política de privacidade](../privacy-policy.html).
- **Túneis por aplicativo.** Roteie perfis SSH individuais através de WireGuard ou Tailscale *sem* ocupar o único espaço de VPN do Android — os outros aplicativos continuam usando a rede direta.
- **Tudo nativo.** FFmpeg, labwc, IronRDP, rclone e o transporte Reticulum em Kotlin são todos compilados a partir do código-fonte — sem runtime Python, sem Chaquopy.
- **Lançamentos frequentes.** Os lançamentos chegam ao F-Droid em até 24 horas via um MR automatizado. Veja o [histórico de lançamentos](https://github.com/GlassHaven/Haven/releases).

## Compilar a partir do código-fonte

Requer [Rust](https://rustup.rs/) com alvos Android, `cargo-ndk`, [Go](https://go.dev/dl/) 1.26+ e `gomobile`:

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

A saída fica em `app/build/outputs/apk/debug/haven-*-debug.apk`.

## Problemas, solicitações, feedback

- [GitHub issues](https://github.com/GlassHaven/Haven/issues) — bugs e solicitações de recursos.
- [Ko-fi](https://ko-fi.com/glassontin) — se você quiser dizer obrigado.

## Documentação

- [Recursos](../FEATURES.md) — referência detalhada de recursos.
- [Política de privacidade](../privacy-policy.html).
- [Código-fonte](https://github.com/GlassHaven/Haven).

---

<p align="center">
  <sub>
    Haven is open source under <a href="https://github.com/GlassHaven/Haven/blob/main/LICENSE">AGPLv3</a>.
    &middot; Android is a trademark of Google LLC.
  </sub>
</p>
