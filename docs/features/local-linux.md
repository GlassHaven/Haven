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

Run a real Linux terminal directly on your phone, no root required. Select "Local Shell (PRoot)" when creating a connection and Haven downloads a minimal [Alpine Linux](https://alpinelinux.org/) rootfs (~4 MB) on first use, giving you a full `apk` package manager — install Python, Node.js, git, build tools, or anything in Alpine's [package repository](https://pkgs.alpinelinux.org/packages). Beyond Alpine, the Desktop → Manage view can install **Debian** (`apt`; 13 Trixie or 12 Bookworm), **Ubuntu 24.04 LTS** (`apt`), **Arch Linux** (`pacman`), and **Void** (`xbps`) rootfs side-by-side, each with its own package manager and a one-tap shell.

PRoot works by intercepting system calls in userspace (no kernel modifications), so it runs on **any unrooted Android device**. It does not require or use root access — the name "PRoot" stands for "ptrace-based root", meaning it *emulates* a root filesystem without actual superuser privileges. Think of it as a lightweight container that runs entirely within Haven's app sandbox.

How it compares to alternatives:

- **Rooted phones (Magisk/su)**: Root gives full system access. PRoot is sandboxed — it can't modify your system, but it also doesn't need root to work.
- **[Android Terminal VM](https://developer.android.com/studio/run/managing-avds)** (Pixel 8+): Google's official Linux VM runs a full kernel via [pKVM](https://source.android.com/docs/core/virtualization). It's more capable but only available on Pixel 8 and newer. PRoot runs on any device back to Android 8. Haven can SSH into an Android Terminal VM if you have one — see the connection settings.
- **[Termux](https://termux.dev/)**: A standalone terminal emulator with its own package ecosystem. PRoot is lighter (4 MB vs ~100 MB) and integrated into Haven alongside your SSH/cloud sessions.

See [PRoot documentation](https://proot-me.github.io/) for technical details. For desktop environments on top of these distros, see [Desktops → Local Desktops](desktops.md).

## DNS and Network Routing

Open **Desktop → Manage** and expand **Guest options** to configure DNS resolvers and per-distro network routing:

- **Guest DNS**: Android does not have `/etc/resolv.conf`, so Haven writes one inside each rootfs before launching a shell, desktop, or guest command. Choose **Network (DHCP)** (uses the active Android network's resolvers), **Public** (`8.8.8.8` + `1.1.1.1`), or **Custom** IP literals.
- **Guest routing (per distro)**: Each installed PRoot distro has its own independent routing mode:
  - **Follow active tunnel** (default): Exposes `HAVEN_TUNNEL_SOCKS5` whenever a Haven tunnel's loopback SOCKS5 listener is active, and exports `ALL_PROXY` / `HTTP_PROXY` / `HTTPS_PROXY` when **Auto-proxy guests** is enabled in **Settings → Tunnels**.
  - **Direct (no proxy)**: Suppresses `ALL_PROXY`, `HTTP_PROXY`, and `HTTPS_PROXY` for that distro even if **Auto-proxy guests** is enabled globally.
  - **Haven tunnel**: Routes that distro through a specific saved standalone tunnel (**WireGuard**, **Tailscale**, **OpenVPN**, **AnyConnect**, or **NetBird**). Haven brings up the tunnel's `127.0.0.1` SOCKS5 listener on demand when the distro starts and exports `ALL_PROXY=socks5h://127.0.0.1:<port>`, `HTTP_PROXY`, `HTTPS_PROXY`, and `NO_PROXY`.
  - **Custom SOCKS5**: Exports `socks5h://[user:pass@]host:port` (or `socks5://` if specified explicitly). Bare `host:port` defaults to `socks5h://` so guest DNS lookups also travel through the SOCKS5 proxy.
  - **Custom HTTP(S)**: Exports `http://[user:pass@]host:port` (or `https://`).
  - **Wrap with `proxychains4`**: Writes `/etc/proxychains-haven.conf` (bypassing `127.0.0.0/8` and `::1` so local X11/VNC sockets stay direct) and wraps shells and guest commands with `proxychains4` when installed inside that distro (`apt install proxychains4`, `pacman -S proxychains-ng`, or `apk add proxychains-ng`).

### Requirements and limitations

- **Userland vs kernel routing**: PRoot runs as an unprivileged Android app process without `CAP_NET_ADMIN` or `CLONE_NEWNET` network namespaces. Per-distro routing works through proxy environment variables (`curl`, `git`, `apt`, `pacman`, `apk`, `pip`, `npm`, `cargo`) and optional `LD_PRELOAD` socket interception (`proxychains4`), rather than attaching a separate kernel TUN interface to one distro. For device-wide Layer 3 routing, run an Android VPN app alongside **Network (DHCP)** DNS.
- **Static binaries and raw UDP**: `proxychains4` intercepts `connect()` on dynamically linked TCP binaries (`glibc` / `musl`). Statically linked binaries bypass `LD_PRELOAD` and rely on `HTTP_PROXY` / `ALL_PROXY` instead; raw ICMP (`ping`) and arbitrary non-DNS UDP packets are not proxied by HTTP or `proxychains4`.

---

[← All features](../FEATURES.md)
