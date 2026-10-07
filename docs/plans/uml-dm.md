# UML guest: mirror the proot desktop stack

Status: accepted goal (2026-10-07). The UML guest exists as the escape hatch
for GUI work PRoot's syscall emulation can't do; today it is console-only —
the Desktop → Manage stack (distro desktops, install/start/stop, VNC tab)
binds to the proot rootfs. This plan mirrors it onto the UML guest: install a
desktop in the guest, view and use it in Haven's normal VNC tab.

## Feasibility findings (verified 2026-10-07)

- `core/local/src/main/cpp/uml_net.c` execs passt with `-f -F <fd>` and no
  forwarding flags. passt's TCP/UDP forwarding (`-t`/`-u`) is separate from
  the tap framing and from fd mode; both are upstream code paths.
- The pinned `passt-uml.patch` (GlassOnTin/uml-transport) touches only:
  L2 framing on the tap fd (`passt_raw_l2`: skip the vnet length prefix,
  one frame per SEQPACKET datagram, per-datagram recv slots), interface/
  gateway discovery fallback for Android (netlink EACCES → local mode,
  `PASST_FORCE_LOCAL`), isolation/util workarounds, and an `epoll_pwait`
  swap. **No hunk touches the forwarding tables or the host-side listener
  sockets** — `tap_backend_init()` below the RAW_L2 hook proceeds into the
  standard `MODE_PASST` path that opens them per the forward table.
- Therefore: inbound reachability is a config plumb, not a transport build.

## Changes

1. **Forward plumb** (`uml_net.c`): new env var `PASST_TFWD` (comma-separated
   host TCP ports, e.g. `5951`) → append `-t <port>` to the passt execl;
   `PASST_UFWD` → `-u` the same way. Host-side binds default to loopback
   (`-a 127.0.0.1`) to match the MCP loopback-default posture; LAN exposure
   is a later, explicit opt-in. Threaded from `UmlGuestManager` into the
   session env; port chosen by the same allocator the proot X11Vnc launcher
   uses (or a distinct UML range — decided in `DesktopManager`, must not
   collide with a simultaneously-running proot desktop).
2. **In-guest recipe**: reuse the proot catalog's APK package list
   (`DesktopEnvironmentSpec.packagesPerFamily[APK]` — `tigervnc`, `openbox`,
   `xterm`, `font-noto`) installed with `apk` inside the running guest.
   The guest's `ext4` rootfs is its own disk, so installs persist. Start =
   `Xvnc` + WM launched from the guest console (stage 0: by hand) or by a
   rootfs-overlay script (stage 1: automatic, shipped in the next
   `uml-guest-*` release with an `/etc/inittab` hook reading a kernel-arg
   marker, e.g. `haven.desktop=openbox`).
3. **UI**: Desktop → Manage lists UML guest desktops alongside the proot
   entries; install/start/stop map to guest-console commands and UML session
   lifecycle (`UmlGuestManager`), not `ProotManager`. Availability stays
   behind the `NativeFeatures.uml` probe.
4. **View**: Haven's normal VNC tab at `127.0.0.1:<port>`.

## Done-when

From a UML guest: `xterm` visible and interactive, plus one Wayland app
(wayvnc + a wlroots compositor) in Haven's VNC tab — verified on-device, as
always.

## Risks / open questions

- passt's `-F` fd mode + forwarding combination is untested in the pinned
  build (stage 0 answers it; fallback = a socketpair↔TCP relay in
  `libuml-net`, which this plan then does not need).
- Forwarded connections arrive from the passt gateway IP
  (169.254.2.2) — VNC auth is per-listener, so no auth implications; note it
  for any future app-level ACL.
- The stage-1 release needs the uml-transport release + `fetch-uml.sh` pin
  bump (VERSION + sha256s together).