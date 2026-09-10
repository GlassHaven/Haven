---
layout: default
title: Local Linux on-device
---

# Local Linux on-device

Run a real Linux userland directly on the phone, no root required — a shell, a
package manager, and (via the [Desktops](desktops.md) manager) full desktop
environments, all side-by-side. A newer alternative runs an actual kernel:
see [Linux Guest (UML)](uml-guest.md).

## Local Shell (PRoot)

Run a real Linux terminal directly on your phone, no root required. Select "Local Shell (PRoot)" when creating a connection and Haven downloads a minimal [Alpine Linux](https://alpinelinux.org/) rootfs (~4 MB) on first use, giving you a full `apk` package manager — install Python, Node.js, git, build tools, or anything in Alpine's [package repository](https://pkgs.alpinelinux.org/packages). Beyond Alpine, the Desktop → Manage view can install **Debian 12** (`apt`), **Arch Linux ARM** (`pacman`), and **Void** (`xbps`) rootfs side-by-side, each with its own package manager and a one-tap shell.

PRoot works by intercepting system calls in userspace (no kernel modifications), so it runs on **any unrooted Android device**. It does not require or use root access — the name "PRoot" stands for "ptrace-based root", meaning it *emulates* a root filesystem without actual superuser privileges. Think of it as a lightweight container that runs entirely within Haven's app sandbox.

How it compares to alternatives:

- **Rooted phones (Magisk/su)**: Root gives full system access. PRoot is sandboxed — it can't modify your system, but it also doesn't need root to work.
- **[Android Terminal VM](https://developer.android.com/studio/run/managing-avds)** (Pixel 8+): Google's official Linux VM runs a full kernel via [pKVM](https://source.android.com/docs/core/virtualization). It's more capable but only available on Pixel 8 and newer. PRoot runs on any device back to Android 8. Haven can SSH into an Android Terminal VM if you have one — see the connection settings.
- **[Termux](https://termux.dev/)**: A standalone terminal emulator with its own package ecosystem. PRoot is lighter (4 MB vs ~100 MB) and integrated into Haven alongside your SSH/cloud sessions.

See [PRoot documentation](https://proot-me.github.io/) for technical details. For desktop environments on top of these distros, see [Desktops → Local Desktops](desktops.md).

---

[← All features](../FEATURES.md)
