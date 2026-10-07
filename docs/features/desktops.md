---
layout: default
title: Desktops
---

# Desktops

Remote and on-device desktops: a VNC viewer, an RDP client, a SPICE client, a
GPU-accelerated native Wayland compositor that runs inside Haven, and a
multi-distro manager for full Linux desktops on the phone. For the local *shell*
side of the on-device distros, see [Local Linux](local-linux.md).

## Desktop (VNC)

Remote desktop viewer with RFB 3.8 protocol support. Pinch-to-zoom, two-finger pan and scroll, single-finger drag for window management, soft keyboard with X11 KeySym mapping. Fullscreen mode with NoMachine-style corner hotspot for session controls. Connect directly or tunnel through SSH. Supports Raw, CopyRect, RRE, Hextile, and ZLib encodings. Security types: **None**, classic **VncAuth** (DES), and **VeNCrypt** (security type 19) with TLSPlain/X509Plain/TLSVnc/X509Vnc/TLSNone/X509None/Plain sub-types — connects to wayvnc, TigerVNC, libvncserver, x11vnc and other servers that require a username alongside a password, wrapping the socket in TLS after sub-type negotiation.

## Desktop (RDP)

Remote Desktop Protocol client built on [IronRDP](https://github.com/Devolutions/IronRDP) via UniFFI Kotlin bindings. Connects to Windows Remote Desktop, xrdp (Linux), and GNOME Remote Desktop. **EGFX (MS-RDPEGFX) graphics-pipeline support** — ClearCodec and RemoteFX Progressive decoders light up the fast graphics path on modern Windows (verified against Windows Server 2025), with a slow-path fallback for servers that don't negotiate it. Pinch-to-zoom, pan, keyboard with scancode mapping, mouse input. SSH tunnel support with auto-connect through saved SSH profiles. Saved connection profiles with optional stored password.

## Desktop (SPICE)

[SPICE](https://www.spice-space.org/) client for QEMU/KVM and libvirt virtual machines, built on a vendored Rust SPICE core exposed through UniFFI Kotlin bindings (the same approach as the RDP client). The display channel decodes the SPICE image codecs — raw bitmap, **LZ**, **GLZ** and **ZLIB-GLZ** dictionary compression, and **QUIC** (SFALIC), plus the image cache — alongside server draw operations, hardware-cursor shape and position, and multiple display surfaces. Input covers the keyboard (scancodes), an absolute pointer, mouse buttons and the scroll wheel. The viewer shares the VNC/RDP gestures: two-finger pinch-zoom and pan, and a soft keyboard. SSH tunnelling is supported — the SPICE core dials its own TCP socket, so Haven opens a local `-L` forward through a saved SSH profile and points the client at `127.0.0.1`. Decoding is verified pixel-correct against QEMU and Windows Server 2025 guests (the GLZ and QUIC paths). Configure SPICE connections in the Connections tab or via the agent (MCP).

## Native Wayland Desktop

GPU-accelerated Wayland compositor (labwc) running natively inside Haven. Full interactive terminal with keyboard input, mouse interaction, server-side window decorations, pinch-to-zoom, and fullscreen mode with corner overlay menu. The GPU pipeline renders via GLES2 on the device's GPU (AHardwareBuffer allocator, ASurfaceControl zero-copy presentation). Native Wayland clients can render 3D content — includes a built-in GLES2 benchmark (rotating lit cube at 60fps on Mali-G715). **Display-scale / resolution control** — pick the compositor's output resolution from the toolbar or fullscreen menu; it reflows windows at the new resolution rather than just visually zooming. Configurable shell (/bin/sh, bash, zsh, fish) and shared keyboard toolbar (Esc, Tab, Ctrl, Alt, arrows, function keys, plus a Super/Mod4 key for compositor keybinds). External Wayland clients can connect via Shizuku (symlinks the socket to `/data/local/tmp/haven-wayland/`). No root required — runs in PRoot with an Alpine Linux rootfs.

## GPU-accelerated Linux GL apps

A single Linux GUI app can run in a **cage** (single-app kiosk compositor) with the device GPU passed through — no `/dev/dri`, no root. A host-side [virglrenderer](https://gitlab.freedesktop.org/virgl/virglrenderer) broker hands the **Mali** GPU to the guest:

- **virgl** (default) translates the guest's OpenGL onto the host GPU — OpenGL 2.1, on by default for cage apps (`GL_RENDERER = virgl (Mali-G715)`).
- **venus + zink** (experimental, toggled in Settings) routes the guest's Vulkan straight to the GPU and runs **zink** on top for modern OpenGL (~3.2 core), for apps that need newer GL than virgl exposes. Needs `mesa-vulkan-drivers` in the guest; off falls back to virgl. Verified on Mali-G715 with vkcube and a geometry-animating GL demo (the clip on the [landing page](../index.md)) running accelerated.

## Local Desktops (multi-distro manager)

A **Desktop → Manage** view installs and runs full Linux desktops on-device via PRoot, with no root. Pick a distro — **Alpine** (APK), **Debian** (APT; 13 Trixie or 12 Bookworm), **Ubuntu 24.04 LTS** (APT), **Arch Linux** (PACMAN), or **Void** (XBPS) — and install them side-by-side; each carries its native package manager. For each installed distro you can install, start, and stop desktop environments, open a shell into it, and read a Room-backed install log that names the layer that broke if a package install fails.

Desktop environments:

- **X11 (via Xvnc)** — Xfce4 or Openbox, each on its own VNC port, viewed through the in-app VNC client.
- **Nested Wayland (via wayvnc)** — a headless wlroots compositor inside the rootfs, surfaced over VNC. **Sway** is the supported, working option. Hyprland and niri are offered but GPU-limited: their renderers (aquamarine / smithay) are GLES-only with no software fallback, and the Android GPU isn't driveable by Mesa inside PRoot, so they can't currently initialise a backend (tracked on #162). For a GPU-accelerated local desktop, use the Native Wayland Desktop above; for a single GPU-accelerated app, use the cage path above.

---

[← All features](../FEATURES.md)
