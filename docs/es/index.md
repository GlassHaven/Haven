---
layout: default
title: Haven
---

<p align="center" style="font-size:.9em">
  <a href="../">English</a> · <a href="../zh/">简体中文</a> · <b>Español</b> · <a href="../fr/">Français</a> · <a href="../de/">Deutsch</a> · <a href="../pt/">Português</a> · <a href="../ru/">Русский</a> · <a href="../ja/">日本語</a> · <a href="../ko/">한국어</a> · <a href="../ar/">العربية</a> · <a href="../hi/">हिन्दी</a> · <a href="../bn/">বাংলা</a>
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
  Código abierto bajo AGPLv3. Sin telemetría. Sin anuncios. Sin cuenta.
</p>

## Descarga

<table>
<thead><tr><th>Canal</th><th>Notas</th></tr></thead>
<tbody>
<tr><td><a href="https://f-droid.org/en/packages/sh.haven.app"><b>F-Droid</b></a></td><td>Compilado desde el código fuente, actualizado automáticamente, recomendado para la mayoría de los usuarios.</td></tr>
<tr><td><a href="https://github.com/GlassHaven/Haven/releases/latest"><b>GitHub Releases</b></a></td><td>APK firmados (arm64, armv7 &amp; x86_64), publicados primero, con las mismas funciones que F-Droid.</td></tr>
</tbody>
</table>

Las mismas funciones de cualquiera de las dos formas. Mínimo Android 8.0 (API 26).

**Elige un canal y quédate en él.** GitHub Releases y F-Droid se firman con
**claves diferentes** (GitHub usa la propia clave de publicación de Haven; F-Droid firma con su
clave por aplicación), así que Android los trata como aplicaciones distintas: no puedes actualizar en el sitio
de una a la otra. Cambiar de canal requiere desinstalar + reinstalar, lo que
borra los datos de la aplicación, así que haz primero una copia de seguridad en **Ajustes → Copia de seguridad**. Obtainium y las
instalaciones directas siguen la clave de GitHub Releases.

## Capturas de pantalla

<p align="center">
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/1_terminal.png" width="160" alt="Terminal" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/2_connections.png" width="160" alt="Connections" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/3_wayland_desktop.png" width="160" alt="Wayland desktop" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/4_cloud_storage.png" width="160" alt="Cloud storage" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/6_vnc_desktop.png" width="160" alt="VNC desktop" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/7_keys.png" width="160" alt="Keys" />
</p>

## De un vistazo

- **[Terminal](../features/terminal.md)** — Mosh / Eternal Terminal / SSH, restauración de sesiones tmux, barra de herramientas de teclado configurable, integración OSC 7/8/9/52/133/777.
- **[Escritorios](../features/desktops.md)** — VNC (RFB 3.8 / VeNCrypt), RDP (IronRDP + EGFX), SPICE (QEMU/KVM, GLZ/QUIC), un compositor Wayland nativo con aceleración por GPU y un gestor de escritorios locales multi-distribución.
- **[Archivos y nube](../features/files-and-cloud.md)** — SFTP/SCP, SMB, más de 60 proveedores en la nube (rclone), copia/movimiento entre sistemas de archivos; además de transcodificación FFmpeg, HLS y DLNA.
- **[Conexiones](../features/connections.md)** — reenvío de puertos (-L/-R/-D/-J), proxies SOCKS/HTTP/Tor, túneles WireGuard y Tailscale por aplicación, port knocking + fwknop SPA, claves SSH y FIDO2.
- **[Correo](../features/email.md)** — ProtonMail + IMAP/SMTP, redactar/responder/reenviar, multicuenta, automatización con Reglas de correo.
- **[Linux local](../features/local-linux.md)** — Alpine / Debian / Arch / Void mediante PRoot, en paralelo, sin necesidad de root.
- **[Reenvío USB](../features/usb.md)** — comparte un dispositivo USB con el agente, el invitado Linux o un host remoto mediante USB/IP.
- **[Reticulum](../features/reticulum.md)** — shell rnsh, transferencia de archivos y reenvío `-L`/`-D` sobre mesh. El único transporte que sigue funcionando sin nada de internet.
- **[Transporte de agente (MCP)](../mcp-tools.md)** — ~130 herramientas con consentimiento; el agente incluso puede manejar la propia interfaz de Haven.
- **[Seguridad](../features/security.md)** — bloqueo biométrico, sin telemetría, copia de seguridad/restauración cifrada (AES-256-GCM).

Explora el [índice completo de funciones](../FEATURES.md).

## ¿Por qué una sola aplicación?

La lista de arriba son las piezas; lo importante es cómo se componen. Cada uno de estos es un único flujo dentro de Haven, sin una segunda aplicación, sin invocaciones tipo `curl | ssh`:

- Toca un MKV 4K en Google Drive → FFmpeg lo transcodifica por HTTP y el resultado vuelve a la misma carpeta de Drive, sin tocar nunca el disco local.
- Conéctate por SSH a una máquina, reenvía su puerto, toca el perfil VNC que apunta a `localhost`: el escritorio se abre en la misma aplicación, con teclado y portapapeles compartidos.
- Corta un directorio de registros de un bucket S3, cambia de pestaña y pégalo en un servidor SFTP: rclone hace la copia del lado del servidor cuando puede; si no, Haven la transmite a través.
- Ejecuta la CLI de tu agente en la shell Linux del dispositivo; hace `push` a través del agente SSH que reenviaste desde tu portátil mientras lo ves en la misma pantalla.
- Transmite un vídeo de la nube al televisor del otro lado de la sala por HLS, copia la URL de la red local desde el snackbar y envíasela a un amigo para que también pueda verlo.

El teléfono es el cliente ligero, Haven es el sistema operativo de cliente ligero, y la nube, tus servidores y tus agentes son el ordenador. El ancho es suficiente; lo que importa es la composición. ([Visión](https://github.com/GlassHaven/Haven/blob/main/VISION.md).)

## Idiomas

Disponible en 12 idiomas: inglés, chino (simplificado), español, hindi, árabe (con soporte RTL), portugués, bengalí, ruso, japonés, coreano, francés y alemán. La interfaz sigue el idioma del dispositivo.

**[🌍 Ayuda a traducir Haven →](../translate.html)** — explora cada cadena de la aplicación con contexto, ve qué falta en tu idioma y propón una traducción como pull request en GitHub.

## ¿Por qué Haven?

- **Una sola aplicación cubre todo el ciclo.** SSH + Mosh + ET + VNC + RDP + SFTP + almacenamiento en la nube + Linux en el dispositivo + transcodificación de medios, desde una única barra de pestañas.
- **Sin telemetría, sin anuncios, sin cuenta.** Nada se envía a casa. Consulta la [política de privacidad](../privacy-policy.html).
- **Túneles por aplicación.** Enruta perfiles SSH individuales a través de WireGuard o Tailscale *sin* ocupar la única ranura de VPN de Android: las demás aplicaciones siguen usando la red directa.
- **Todo nativo.** FFmpeg, labwc, IronRDP, rclone y el transporte Reticulum en Kotlin están todos compilados desde el código fuente: sin entorno de ejecución de Python, sin Chaquopy.
- **Publica a menudo.** Las versiones llegan a F-Droid en 24 horas mediante un MR automatizado. Consulta el [historial de versiones](https://github.com/GlassHaven/Haven/releases).

## Compilar desde el código fuente

Requiere [Rust](https://rustup.rs/) con destinos Android, `cargo-ndk`, [Go](https://go.dev/dl/) 1.26+ y `gomobile`:

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

El resultado se genera en `app/build/outputs/apk/debug/haven-*-debug.apk`.

## Problemas, solicitudes, comentarios

- [GitHub issues](https://github.com/GlassHaven/Haven/issues) — errores y solicitudes de funciones.
- [Ko-fi](https://ko-fi.com/glassontin) — si quieres dar las gracias.

## Documentación

- [Funciones](../FEATURES.md) — referencia detallada de funciones.
- [Política de privacidad](../privacy-policy.html).
- [Código fuente](https://github.com/GlassHaven/Haven).

---

<p align="center">
  <sub>
    Haven es código abierto bajo <a href="https://github.com/GlassHaven/Haven/blob/main/LICENSE">AGPLv3</a>.
    &middot; Android es una marca comercial de Google LLC.
  </sub>
</p>
