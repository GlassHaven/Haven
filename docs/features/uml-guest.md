---
layout: default
title: Linux Guest (UML)
---

# Linux Guest (UML)

Create a connection with the **Linux Guest (UML)** transport and Haven boots a
whole Linux kernel — user-mode Linux — as one of its own processes. Unlike the
[Local Shell (PRoot)](local-linux.md), which emulates a root filesystem by
intercepting syscalls, the guest runs a real kernel: real `ptrace`, real
`/proc`, real block devices, and its own network stack. The connection editor
asks only for a name; the kernel arguments are fixed.

## What it's good for

- **A normal Linux box in your pocket.** The guest runs an Alpine aarch64
  userland with [apk](https://wiki.alpinelinux.org/wiki/Alpine_Package_Keeper)
  and the standard Alpine 3.22 repositories, so `apk add python3 gcc git nodejs`
  (and thousands of other packages) install straight into the guest. Everything
  runs as root inside the guest, and the guest's filesystem is its own ext4
  image that survives reboots.
- **When PRoot isn't enough.** PRoot emulates a root filesystem by intercepting
  syscalls, and some software notices or breaks under that: process tracers and
  debuggers, programs that inspect `/proc` in unusual ways, and anything that
  needs to mount filesystems. The guest sidesteps all of it by running an
  actual kernel, with no interception layer between your programs and Linux.
- **A scratch box for untrusted commands.** The guest sees only its own disk.
  It cannot read the phone's storage, contacts, or anything else Haven can
  reach, and it holds no privileges on the device — closing the tab powers it
  off. That makes it a reasonable place to run scripts you don't fully trust.
- **An agent workbench.** Haven's MCP tools (terminal input, snapshot,
  scrollback) work on a guest session like any other terminal, so an agent can
  drive a full root Linux on the phone: install packages, run builds, iterate
  on scripts — while you watch the same console.

## Getting started

1. **Connections → + → Linux Guest (UML)**. The editor asks for a name only.
   The picker only offers the guest on arm64 devices running the full flavour;
   if it's missing, your build doesn't carry the payload.
2. **Connect.** The first connect unpacks a ~512 MB rootfs image into app
   storage (needs ~600 MB free) and then boots; later connects skip the unpack.
3. Wait for the boot messages to settle into a `uml:~#` prompt — typically
   10–20 s on a recent phone, most of it the network bring-up.
4. Update the package index once: `apk update`.

Closing the guest's tab (or tapping the stop button) sends `poweroff` into the
guest. Files you created stay on the image for next time.

## What to expect

- **Fixed resources**: 384 MB of RAM and a ~536 MB ext4 disk (which shares the
  space `apk add` fills up). There is no snapshotting and no way to enlarge
  either from the UI.
- **No shared folders.** The phone's files are invisible to the guest and the
  guest's files are invisible to the phone's file browser; move data over the
  network (the guest has full outbound connectivity) or treat it as a
  self-contained box.
- **Memory adds up.** Each running guest occupies its 384 MB for as long as
  its tab is open, and you can have several open at once. Close guest tabs
  you aren't using.
- **A console, not a desktop.** The guest surface is a serial-style console.
  For graphical Linux, use the [Local Desktops](desktops.md) manager instead.

## How it compares

- **Local Shell (PRoot)** is the lighter tool: ~4 MB download, runs on Android
  8+, and starts instantly. It emulates rather than runs a kernel, which is
  where its limits come from. Use PRoot for everyday shell work; use the guest
  when you hit those limits.
- **SSH to a server** gives you a machine that stays up when you close the app
  and usually more CPU and RAM, but needs the server and a connection. The
  guest is on-device and needs nothing but the phone.
- **Android Terminal VM** (Pixel 8 and newer) virtualizes with pKVM and is a
  more complete VM. The guest runs on any arm64 Android device and needs no
  virtualization support from the SoC, because user-mode Linux is just a
  process.

## Requirements

- arm64 device. Only `arm64-v8a` builds ship the guest payload.
- The full flavour. The payload is four native files, ~13 MB in the APK — the
  kernel (`libvmlinux.so`) is ~79 MB as built but AGP's debug-symbol strip
  drops it to ~8 MB (the bytes outside the loadable segment are ELF metadata
  only; the loaded image is unchanged). The connection picker hides the guest
  transport when any of the four is missing.
- ~600 MB of free app storage. The rootfs image is unpacked (~512 MB) into app
  storage on first connect. It stays there afterwards; deleting the connection
  does not delete it.

## How it works

- **Kernel**: a bionic-static UML kernel built from the [Linux UML tree](https://github.com/zalexdev/linux-um-arm64) (branch `um-arm64`) with the `stub-execve-fallback.patch` and `android-app-compat.patch` on top, so the kernel runs as an ordinary Android app process — no root, no `/dev/kvm`, no privileged setup.
- **Network**: [passt](https://passt.top) runs as a sibling of the kernel, connected over a `SOCK_SEQPACKET` socketpair; the kernel's UML vector transport (`vec0`) uses that pair as its NIC, and passt forwards to the app's own network context. DNS is set to 1.1.1.1 by default. No VPN permission is needed because everything stays inside the app.
- **Console**: the guest's stdio console is the terminal tab's pty, so the boot messages and shell appear as they happen.
- **Boot time**: the rootfs is a ~536 MB ext4 image (a minimal aarch64 rootfs with an init, busybox, and a network bring-up). The kernel prints its first boot messages within a couple of seconds; the shell prompt appears once the inittab's `ifup -a` finishes its DHCP round on `vec0`, which adds a few more seconds on top (measured ~10–20 s total on an OPPO CPH2655, MCP round-trips included).

## Closing

Closing the guest's terminal tab sends `poweroff` into the guest and waits up
to 5 s for it to exit before killing the process. The ext4 rootfs is
journaled, and this keeps unmounts clean even if Haven is killed outright.

The guest does not persist state across `poweroff` beyond what is written into
the image; there is no snapshotting, and Haven does not ship multiple rootfs
variants.

## Source availability

The guest kernel and passt are GPL-2.0 (passt under its upstream
GPL-2.0-or-later identifier). Their complete corresponding source, the two
kernel patches, the passt patch and the build recipes are published in the
[uml-transport repository](https://github.com/GlassOnTin/uml-transport);
the binaries Haven ships are the pinned `uml-guest-1` release of that
project. The pinned binaries and their sha256 checksums are in
[`core/local/fetch-uml.sh`](https://github.com/GlassOnTin/haven/blob/master/core/local/fetch-uml.sh);
the fetch fails the build loudly if a pinned artifact disappears, and each
artifact's checksum is verified before it is placed in the APK.

---

[← All features](../FEATURES.md)