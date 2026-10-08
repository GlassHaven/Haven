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
   host TCP ports, e.g. `5951`) → append one `-t 127.0.0.1/<p1>,<p2>` spec to
   the passt argv; `PASST_UFWD` → `-u` the same way. The `127.0.0.1/` address
   prefix inside the spec is what keeps the bind loopback-only — passt's `-a`
   is the *guest* address option, not a bind restrictor, and a bare
   ports-only spec binds all interfaces (`sock_l4_dualstack_any` binds `::`
   with `IPV6_V6ONLY=0`). Verified against the pinned base (passt.top
   3a890a6 + passt-uml.patch): the `-t`/`-u` specs parse after local-mode
   setup sets `ifi4/ifi6 = -1` sentinels, `fwd_rule_init` treats the
   sentinels as available (both FWD_CAP_* set), and `fwd_listen_init` opens
   the listener sockets unconditionally in fd mode. LAN exposure is a later,
   explicit opt-in. Threaded from `UmlGuestManager` into the session env;
   port chosen by the same allocator the proot X11Vnc launcher
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

## Stage-0 device verification (2026-10-08, test build 8731)

Device-verified on the OnePlus CPH2655 with the uncommitted stage-0 plumb
(TEMP test hooks set `PASST_TFWD=5951,5952` on the GUEST session env):

- passt accepted `-t 127.0.0.1/5951,5952` in `-F` fd mode and bound both
  host-side listeners; Haven's local shell read `RFB 003.008` through
  `127.0.0.1:5951` (Xvnc :51) and `127.0.0.1:5952` (wayvnc) — end-to-end,
  banner-to-banner, no relay needed. The fallback socketpair↔TCP relay in
  `libuml-net` is therefore not needed and is dropped from this plan.
- From Haven's normal VNC tab: openbox + `xterm` fully interactive (the
  openbox root menu responds to right-click), and on the second port a
  headless sway (`WLR_BACKENDS=headless WLR_LIBINPUT_NO_DEVICES=1
  WLR_RENDERER=pixman`, wayvnc bound `0.0.0.0:5952`) with two `foot`
  terminals — clicking a title bar flips sway's focus. Mouse in both
  directions, pixel-visible frame updates in the tab.
- Display/port mapping: Xvnc display `:N` binds `5900+N`, so the forward
  port is the display's port (5951 → :51). The stage-1 allocator should
  pick ports and derive displays from them.

In-guest prerequisites the stage-1 recipe must provide (found missing in
uml-guest-4 and fixed by hand during this test):

- `/dev/pts` (devpts) is not mounted — `xterm` fails with
  `get_pty: not enough ptys` until it is.
- `/dev/shm` (tmpfs, `mode=1777`) is not mounted — wlroots fails `shm_open`
  ("Failed to allocate shm file for keymap", "Failed to allocate buffer"),
  leaving sway running but rendering nothing; wayvnc shows a stale grey
  frame. Both must land in the rootfs /etc/inittab-style boot hook next to
  the existing hostfs mount.
- The `agent-launcher` respawn in `/etc/inittab` conflicts with a desktop
  session: it fills the shared console with opencode and refetches its
  185 MB bundle if missing. The stage-1 release grows the rootfs image AND
  gates the launcher (or the desktop hook suppresses the respawn when a
  `haven.desktop=` kernel arg is present).

## Risks / open questions

- The uml-guest-4 rootfs image is too small for the desktop recipe: 990 MB
  ext4 ran at 96% with only tigervnc+openbox+xterm+mesa+wayvnc+sway+foot
  (no fonts beyond the base, no swaybg). The stage-1 release ships a
  bumped image (2 GB class) together with the `fetch-uml.sh` VERSION pin
  bump.
- Forwarded connections arrive from the passt gateway IP
  (169.254.2.2) — VNC auth is per-listener, so no auth implications; note it
  for any future app-level ACL.
- The stage-1 release needs the uml-transport release + `fetch-uml.sh` pin
  bump (VERSION + sha256s together).