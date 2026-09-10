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

- **Kernel**: a bionic-static UML kernel built from the [Linux UML tree](https://github.com/zalexdev/linux-um-arm64) (branch `um-arm64`) with the `stub-execve-fallback.patch` on top, so the kernel runs as an ordinary Android app process — no root, no `/dev/kvm`, no privileged setup.
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

The guest kernel is GPL-2.0. Its complete corresponding source is published at
the [linux-um-arm64 repository](https://github.com/zalexdev/linux-um-arm64)
(branch `um-arm64`) plus the `stub-execve-fallback.patch` applied by the
build. The pinned binaries and their sha256 checksums are in
[`core/local/fetch-uml.sh`](https://github.com/GlassOnTin/haven/blob/master/core/local/fetch-uml.sh);
the fetch fails the build loudly if a pinned artifact disappears, and each
artifact's checksum is verified before it is placed in the APK.

---

[← All features](../FEATURES.md)