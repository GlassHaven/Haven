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
   `Xvnc` + WM launched from the guest console (stage 0: by hand). Stage
   1 (shipped in `uml-guest-9`): `/etc/inittab` gains the devpts and shm
   sysinit mounts, and `agent-launcher.sh` parks the agent TUI whenever
   `/proc/cmdline` mentions `haven.desktop=` — the console then execs a
   login shell, which the desktop recipe drives itself. No value is
   parsed, only the marker's presence.
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
the staged rootfs — the uml-transport `uml-guest-8` asset — and fixed by
hand during this test):

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

## Stage-1 verification (2026-10-08, build 8731)

Rootfs asset bumped to uml-guest-9 (sha256 `4bc520ea…`, 77,282,923 bytes gz,
flat ext4 exactly 2,147,483,648 bytes; `UmlGuestManager` ROOTFS_VERSION 11),
installed on the OnePlus CPH2655, then verified:

- **Plain boot** (GUEST connect, no desktop args): staging gate saw 284 GB
  free and re-staged (rootfs.ext4 exactly 2,147,483,648 bytes,
  `rootfs.version` rewritten); boot clean, no `DEVPTSFAIL` / `SHMFAIL` /
  `HOSTFSFAIL` markers — the inittab sysinit hooks are live; the launcher
  ran and (missing endpoint.env after the re-stage, pre-existing guest-8
  parity — the share still carries `nexos.env.bak` and the migration fell
  through silently) stopped at the endpoint prompt as before.
- **Connect-path find**: the MCP/connect-button session create is
  `ConnectionsViewModel.kt:3527`, not `TerminalViewModel.addGuestTabForProfile`
  — a temp hook on only the latter let the first desktop-boot attempt boot
  without `haven.desktop=` (launcher showed the endpoint prompt). Both call
  `UmlGuestManager.registerSession(pid, label)`; stage-2 must hook (or
  parameterise) the ConnectionsViewModel one.
- **Desktop boot** (drive the console over the GUEST session's serial, then
  watch through Haven's VNC tab): the gate fired on a boot launched through
  the Connections connect path — `/proc/cmdline` carries `haven.desktop=1`,
  the console parks at a login shell (`uml:~#`). From the console: the
  package set for the Alpine 3.22 rootfs is
  `tigervnc openbox xterm font-noto mesa-gl sway wayvnc foot
  font-misc-misc xsetroot xwininfo jq` — no `mesa-osmesa` in Alpine 3.22
  (APK_EXIT=1), and the xorg tools are bare names
  (`xorg-xsetroot`/`xorg-xwininfo` do not exist — a redirected `apk add`
  fails silently on them). `font-misc-misc` must be installed BEFORE Xvnc
  starts: Xvnc snapshots its font path at startup, and an xterm started
  against a server booted without the bitmap fonts maps nothing
  (`cannot load font "-misc-fixed-medium-r-semicondensed--13-120-75-75-c-60-iso10646-1"`).
  Both stacks verified in Haven's VNC tab:
  - **X11**: `Xvnc :51` (5951, port = 5900+display confirmed again) +
    openbox + xterm; after the font fix and a clean client reconnect the tab
    renders openbox-managed xterms, and clicking a title bar flips the
    focus (mouse path Haven → passt → guest → openbox).
  - **Wayland**: headless sway (`WLR_BACKENDS=headless
    WLR_LIBINPUT_NO_DEVICES=1 WLR_RENDERER=pixman`,
    `XDG_RUNTIME_DIR=/tmp/wr`) + `wayvnc 0.0.0.0:5952` + two `foot`s
    tiled 640×668 each on the 1280×720 output. Interactivity proven at IPC
    level — a tab click on the left/right half flips sway's focused
    container (checked with `swaymsg -t get_tree`, jq) and re-paints the
    focus colour. Notes: `swaymsg` 1.10.1 ignores the
    XDG_RUNTIME_DIR/WAYLAND_DISPLAY pair here ("Unable to retrieve socket
    path") and needs `SWAYSOCK=/tmp/wr/sway-ipc*.sock`; benign sway noise:
    swaybg and Xwayland absent, no user bus.
  - **Verify trap**: a desktop tab that went stale mid-test (frames frozen
    on its last frame while the guest clock ran on) is Haven's backgrounded
    viewer pausing, not wayvnc dropping — input injected via tap_desktop_tab
    still reached sway while the picture was minutes old, and a clean
    disconnect/connect_profile of the VNC profile restored live frames.
    Prove Wayland interactivity at the IPC level and only then trust a
    capture.

## Stage-2 integration surface (code survey, 2026-10-08)

Read-only survey of the desktop stack, to be built on (facts, with
file:line refs):

- The Manage screen's desktop list is the hardcoded
  `ProotManager.DesktopEnvironment` enum filtered by
  `spec.packagesPerFamily.containsKey(activeDistro.family)`
  (DesktopManagerScreen.kt:1762-1776, Manifest.kt:637-1035); "installed"
  is a marker-file scan of the proot rootfs (ProotManager.kt:1057-1086).
  A UML recipe cannot ride that enum — it would drive ProotManager
  setup. Precedent for a beside-section in the same screen:
  `SystemVmSection` / `AppWindowsSection` (DesktopManagerScreen.kt:218-241).
- The proot VNC viewer opens inside the start action:
  `DesktopViewModel.startDesktop` (:436-520) polls
  `Socket(127.0.0.1, port)` for ≤8 s, then
  `addVncSession(host="localhost", port, profileId=null, password =
  prootManager.storedVncPassword ?: a pre-existing isVnc && localhost
  profile)` (DesktopViewModel.kt:505-518). This is the shape the UML
  start action reuses; no stored VNC profile involved.
- Port space: `DesktopManager` alone owns 5901..5999
  (allocateDisplay :85, prefs :99-112, running set :118); its
  `suggestNextVncPort` cannot see UML consumers. Uncoordinated
  precedent: `SystemVmManager` grabs any free loopback port and derives
  the display (SystemVmManager.kt:140-146). UML's channel is per-session
  `extraEnv`/`PASST_TFWD` at `registerSession` time; the desktop boot
  therefore picks the port first and passes it as env — and Manage-list
  coordination (whether UML takes ports from the 5901..5999 allocator or
  mirrors SystemVmManager) is the main open design decision.
- `NativeFeatures.uml` exists (NativeFeatures.kt:79-85, all four UML
  .so) and `TransportAvailability` already gates GUEST on it; the Manage
  section gates on the same probe. `UmlGuestManager.ensureRootfs()` +
  its `SetupState` flow (NotStaged/Unpacking/Ready/Error,
  UmlGuestManager.kt:101-162) is the staging half; the guest-rootfs
  `apk add` of the desktop packages is the install half (persists in the
  guest's ext4).
- DesktopTab has no UML member today; UML sessions surface only as
  GUEST terminal tabs (TerminalViewModel.kt:1102-1152,
  TransportSessionManagerModule.kt:151-162).

## Stage-2 design (decided 2026-10-08, on the survey above)

- **Recipes** (stage-1-verified commands, parameterised): `X11` = Xvnc on
  `:N` (N = port − 5900) + openbox + xterm; `Wayland` = headless sway
  (`WLR_BACKENDS=headless WLR_LIBINPUT_NO_DEVICES=1 WLR_RENDERER=pixman`,
  `XDG_RUNTIME_DIR=/tmp/wr`) + `wayvnc 0.0.0.0:<port>` + foot. One desktop
  at a time: a UML desktop runs inside its own kernel (2 GiB per session),
  not as processes beside others.
- **Port space: shared with `DesktopManager`.** UML stores port prefs in
  the same `desktop-port-prefs` store under keys `uml_<kind>`. Two
  `DesktopManager` edits close the cross-family gaps (the pref store
  is shared today but `suggestNextVncPort` only scans its own distro's
  keys, and `allocateDisplay` doesn't consult prefs at all):
  `suggestNextVncPort` drops the distro-prefix filter (considers every
  family's prefs), and `allocateDisplay` skips candidates whose
  `5900+N` is pinned by another family's pref. `UmlDesktopManager` pins
  `PASST_TFWD=<port>` before boot and leaves it sticky, exactly like a
  proot install-time pin.
- **Session plumbing**: new `UmlDesktopManager` (core/local/uml) drives
  `UmlGuestManager`: `registerSession` under a synthetic profileId
  (`uml-desktop-<kind>` — no ConnectionProfile row, so the session never
  surfaces as a GUEST tab), `haven.desktop=1` kernel arg,
  `PASST_TFWD=<port>` env, then a headless tee session
  (`startHeadlessShell(sessionId, extraOnData)`). The recipe is written
  by the app into the hostfs share (`files/uml/share/haven-desktop.sh`);
  the console side of the recipe is ONE typed command (`sh
  /host/haven-desktop.sh <verb> <kind> <port>`), everything else is
  script. The recipe backgrounds its servers and exits — the console
  returns to the login-shell prompt, where `poweroff` is meaningful
  (guest ISIG is off: the stage-1 ^C-as-literal quirk makes an interrupt
  path useless, so every stop path goes through init).
- **Lifecycle actions**:
  - *Install*: headless boot (no forward) → `apk add <pkgset>` → marker
    `/host/haven-desktop-<kind>.ok` → poweroff. The Manage list reads the
    markers from the share dir host-side; re-installing overwrites.
  - *Start*: boot with the forward → run the recipe → Ready only on an
    RFB-banner probe (`Socket` to 127.0.0.1:<port>, expect the "RFB "
    prefix) — passt's listener accepts connects before the guest server
    is up, so a bare connect (what the proot path polls) proves nothing
    here. Timeout → ERROR with the console-log tail as the message
    (error-quality rule: the log travels with the state). Xvnc is
    started with `-SecurityTypes None` explicitly and wayvnc runs
    unconfigured (no user/pass) — both loopback-only listeners behind
    the passt forward.
  - *Stop*: `poweroff` typed at the idle login-shell prompt; kernel exit
    tears down passt and frees the listeners; hard-kill fallback via
    `closeGuest` after 8 s.
- **VNC tab**: `DesktopViewModel` opens it on the RUNNING transition with
  the same `addVncSession(host="127.0.0.1", port, password=null,
  colorDepth="BPP_24_TRUE")` shape proot's start uses (dedupe built into
  addVncSession covers double-taps).
- **No MCP endpoint for the three actions in v1**: the guest console
  remains fully MCP-drivable (that is how stages 0–1 were verified), and
  state is observable on the Manage screen. Noted, not hidden.

## Risks / open questions

- Forwarded connections arrive from the passt gateway IP
  (169.254.2.2) — VNC auth is per-listener, so no auth implications; note it
  for any future app-level ACL.
- `fetch-uml.sh`'s NAME-tag pins the kernel/binaries at `uml-guest-4`, a
  different pin than the rootfs tag (`uml-guest-9`); keep the two separate
  when bumping. Stage-1 shipped the rootfs bump only.