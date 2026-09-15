---
layout: default
title: "Design: Bridges — a unified device/capability manager"
---

# Bridges — one manager for everything Haven brokers

> **Status:** design / forward-looking. Not built. This is the binoculars view of
> where the "broker an Android capability and re-expose it" pattern is heading,
> with a grounded phased path from today's sprawl to the dream.

## 1. The pattern, named

Almost everything Haven does is one move, stated in [VISION](https://github.com/GlassHaven/Haven/blob/main/VISION.md):
**take a capability Android holds, that some downstream can't reach on its own,
broker it, and re-expose it.** A YubiKey to a server. A GPU to a proot cage. A
USB drive to a real kernel. Audio out of a headless Linux app.

We've built each as a *point solution* with its own UI, its own lifecycle, its
own MCP verbs. The capability is the same shape every time — only the endpoints
change. **Bridges** makes that shape first-class: one registry, one surface, one
consent model, for every brokered capability.

The user-facing name is the **Devices** screen. The internal concept is a
**Bridge** — and a Bridge is precisely *an instance of the host-broker the Vision
already describes*.

### Where this sits in the Vision (so we don't trip over the primitives)

This is **not a fifth primitive.** VISION.md is deliberate: the four primitives
(Namespace, Runtime, Gateway, Presence) reach *outward* to other machines'
files, shells, networks, and screens; the broker — *"The host as a privileged
peer"* — is *"the build-vs-delegate discipline pointed inward,"* reaching the
phone's *own* capabilities and feeding them to those same four (a brokered USB
device becomes usable via Gateway/USB-IP, Runtime/guest-or-VM, Presence/agent).
**Bridges is the registry and surface for that inward layer** — it names and
makes manageable a pattern the Vision already commits to, it does not invent a
new one. The sinks below are just the four primitives wearing work gloves.

The **Devices** screen earns its place by the Vision's own build-vs-delegate
test: it's a *composition* surface (you route a source *into* a runtime or out a
gateway), so Haven owns it — and, being a Presence surface, it inherits the three
agent-sharing requirements for free: observable state (`list_bridges`),
actionable API (`create_bridge`/`drop_bridge`), reachable surface (the
auto-detect notification). Humans tap, agents call, both observe.

## 2. The model

A **Bridge** is a tuple:

```
Bridge = (source, sink, transport, state, consent, lifecycle)
```

- **Source** — a capability the phone holds: a USB device, the camera, the mic,
  the speaker, motion/position/environment **sensors**, **GPS**, the screen, the
  clipboard, NFC, the secure element, the GPU, location, network, battery.
- **Sink** — who receives it: the **AI agent** (MCP), the **proot guest**, a
  **local VM** (real kernel), a **remote host**, another **app**, the **LAN**.
- **Transport** — the mechanism that carries it: the `UsbDeviceConnection`
  broker, the `haven-usb` libusb/hidraw/serial shim, **USB/IP**, a virgl/venus
  socket, PulseAudio-over-TCP, a Wayland socket, a **v4l2 camera shim**, an
  **iio/evdev sensor shim**, **gpsd/NMEA**, a reverse-adb tunnel.
- **State** — `available → bridging → active → error`, plus health: throughput,
  client count, last error.
- **Consent** — *not a parallel model; the Vision's existing tiers.* A **per-use**
  bridge (open this drive now) is **Tier 2** (act once) — its own consent, no
  pre-enable toggle (the #287 lesson). A **standing** bridge (a guest can reach
  the camera until revoked) is **Tier 3** — a `create_standing_policy` grant with
  scope, rate ceiling, expiry, and the kill-switch. Anything irreversible or
  physical is **Tier 4**, always per-action. The registry just records which tier
  a given bridge sits at; the consent rail is the one Haven already ships.

The **Bridge Registry** is the single source of truth. It *generalises*
`list_usb_exports` (which already lists USB bridges) to every capability, and the
**Devices** screen + MCP are thin views over it.

## 3. The capability matrix

The whole point is to make "what can reach what" legible. This is the
[USB device-class matrix](../features/usb.md#device-class-support) grown up to
cover all capabilities. (✅ shipped · ⚠️ feasible, unbuilt · 🔬 research · ❌ wall.)

| Source (phone holds) | → Agent (MCP) | → proot guest | → local VM | → remote host |
|---|:---:|:---:|:---:|:---:|
| USB HID / CDC-serial | ✅ | ✅ `haven-usb` | ✅ via USB/IP | ✅ USB/IP |
| USB mass storage / block | ⚠️ | ❌ no kernel | ✅ **VM (#287)** | ✅ USB/IP |
| USB other bulk (printer/SDR/MTP) | ✅ raw | ⚠️ libusb shim | ✅ VM | ✅ |
| USB webcam/audio (isochronous) | ❌ | ❌ | ❌ | ❌ *(Android API wall)* |
| Camera (phone's own, Camera2) | ✅ capture | 🔬 v4l2 shim | 🔬 v4l2→USB/IP | 🔬 |
| Microphone | ⚠️ | ⚠️ PA source | ⚠️ | ⚠️ |
| Speaker / audio out | ✅ | ✅ **PulseAudio (#257)** | ✅ | ⚠️ |
| GPS / location | ⚠️ | ✅ **gpsd/NMEA (`haven-gps`)** | ⚠️ | ⚠️ |
| Motion/environment sensors | ⚠️ | 🔬 iio/evdev shim | 🔬 | 🔬 |
| GPU | — | ✅ **virgl/venus** | 🔬 | — |
| Display / screen | ✅ capture | n/a | n/a | ⚠️ |
| Clipboard | ✅ | ✅ | ⚠️ | ✅ |
| NFC / secure element | ✅ FIDO | ⚠️ | ⚠️ | ✅ USB/IP (key) |
| Network / adb | — | — | — | ✅ reverse tunnel |

Reading the grid is the feature: it shows what's shipped, what's one shim away,
and where Android's API walls (isochronous USB, no `/dev/kvm`, no kernel modules)
genuinely stop us — so nobody re-discovers a wall by hitting it.

## 4. The surfaces

**Devices screen** (the user-facing manager). A live list grouped by source type:

```
USB
  • YubiKey FIDO+CCID            → [Agent ✓]  ⋮ attach to guest / export USB/IP
  • SanDisk 64GB (ext4)          → [VM ▸ booting 60%]  ⋮ eject
Camera / Mic
  • Back camera                  → not bridged   ⋮ to guest / to agent
Sensors
  • GPS                          → [guest gpsd ✓]   ⋮ stop
  • Motion (accel/gyro)          → not bridged   ⋮ to guest / to agent
GPU                              → [desktops: virgl ✓]
Audio                            → [guest PulseAudio ✓]
```

Each row: what it is, where it's bridged (with live state/health), and a menu of
*other* sinks it could go to. The auto-detect notification ("USB drive detected —
open it?") is just this surface reaching out when a source appears.

**MCP** (the registry, agent-drivable) — generalising `list_usb_exports`:

- `list_bridges` — every source, its current bridge(s), and what's possible.
- `create_bridge(source, sink, transport?)` — e.g. `(gps, guest)`, `(usb:1-2, vm)`.
- `drop_bridge(id)` — tear one down.
- `inspect_bridge(id)` — health, throughput, the exact transport details.

The existing `open_usb_drive` / `usb_attach_to_guest` / `start_usbip_export`
become **thin façades** over `create_bridge`, kept for ergonomics.

## 5. The walls (so the dream stays honest)

- **Isochronous USB** — UVC webcams / UAC audio over an *attached* USB device
  can't pass on *any* sink: `UsbDeviceConnection` has no isochronous API. (The
  phone's *own* camera via Camera2 is a different path that sidesteps this.)
- **No `/dev/kvm`** unrooted — local VMs are TCG (emulated, slow). Fine for files
  and peripherals, not for performance.
- **No kernel modules** in proot — kernel-object classes (block, netdev, v4l2,
  ALSA) need either a shim (userspace) or a real VM kernel.
- **Per-source Android permissions** — camera, location, sensors, USB each carry
  their own runtime grant *on top of* Haven's consent rail.
- **Exclusivity & contention** — some sources can't fan out to two sinks at once;
  `UsbAccessGate` (today's FIDO-vs-export coordinator) generalises to a
  per-source lease in the registry.
- **A bridge is a privilege path** — guest-reaches-camera is an escalation.
  Standing exposures gate hard (Tier 3); per-use actions self-consent (Tier 2).
- **Brokers access, never the device's app** (Vision boundary). A camera bridge
  feeds frames to a sink; it does not become a camera app or reimplement a
  vendor's tool. The bridge ends at the `/dev`-node-or-socket; what the sink does
  with it is the sink's business.
- **Non-root is the common case; root is a gated power-user path** (Vision
  boundary). The matrix marks root-only transports (raw bus access, udev, kernel
  modules) as the exception, never the default — the point is to work on the
  phone the user actually has.

## 6. Roadmap — from legible to dreaming

**Phase 0 — make the sprawl legible (cheap, no refactor).**
- Read-only `list_bridges` MCP that *reflects* today's bridges (USB exports,
  guest-USB proxy, audio bridge, virgl, the USB-drive VM, adb tunnel).
- A read-only **Devices** list mirroring it.
- Land the two interactive slices already scoped: **auto-detect-on-plug
  notification** + a **Files-tab "Open USB drive"** entry, built as the *seed* of
  the Devices list (a row model, not a one-off link).

**Phase 1 — the registry becomes the source of truth (USB first).**
- A `BridgeRegistry` + `Bridge` abstraction in `core`. Route the existing USB
  bridges through it; the Devices screen drives create/drop.
- **Multi-device USB/IP on `:3240`** (the hub case) + implement `OP_REQ_DEVLIST`
  (the gap the spike found) — so a hub can fan N devices to N different sinks at
  once (YubiKey→agent, stick→VM, serial→guest, simultaneously).
- Generalise `UsbAccessGate` into a per-source lease.

**Phase 2 — first new source: sensors.**
- **GPS → `gpsd`/NMEA** into the guest/VM — Linux mapping & nav apps "just see" a
  GPS. Highest value, cleanest shim. *(Guest leg shipped: the `\0haven-gps`
  abstract socket streams the chipset's NMEA into the guest, where the staged
  `haven-gps` helper (socat) materialises it as a PTY at `/run/haven/gps0`.
  Master opt-in + ONCE_PER_SESSION attach, mirroring the USB pair.)*
- Motion/environment sensors via an **iio or evdev** userspace shim (same
  `LD_PRELOAD`-+-socket trick as `haven-usb`).
- `(sensor, agent)` over MCP — the agent can read the phone's real-world state.

**Phase 3 — camera + mic.**
- A **v4l2 userspace shim** fed by **Camera2** — the guest gets a `/dev/video0`
  (proot has no kernel for `v4l2loopback`, so interpose the v4l2 ioctls). The
  phone's own camera, not a USB webcam, so the isochronous wall doesn't apply.
- Mic as a PulseAudio *source* (the audio bridge in reverse).

**Phase 4 — symmetry & the network.**
- Bridges **to a remote host** and **from the LAN in**: forward the phone's GPS,
  camera, sensors to a desktop; or accept a remote device into the phone's guest.
- The screen/display as a source; clipboard both ways. The full matrix lit up.

**Phase 5 — the dream: a composable virtual device bus.**
- A guest or VM boots seeing a **synthetic device tree** assembled à la carte from
  the phone: its GPS, cameras, sensors, attached USB peripherals, GPU, audio — all
  as *native Linux devices*. You hand a Linux environment (local cage, on-device
  VM, or a remote box) exactly the slice of the phone you choose, and it behaves
  like a laptop that happens to have a phone's sensors bolted on.
- The phone becomes a **peripheral/sensor host** for *any* Linux, anywhere — the
  natural endgame of the broker thesis. "Plug my phone's reality into this Linux."

## 7. Migration map (today → bridges)

Nothing is thrown away; each existing feature *becomes* a Bridge:

| Today | Becomes |
|---|---|
| `start_usbip_export` / `usb_attach_to_guest` | `create_bridge(usb:N-M, remote\|guest)` façade |
| `open_usb_drive` (#287) | `create_bridge(usb:N-M, vm)` façade |
| Audio bridge (#257) | `create_bridge(audio-out, guest)` |
| virgl/venus GPU | `create_bridge(gpu, guest)` |
| `expose_adb` | `create_bridge(adb, workstation)` |
| FIDO/CTAPHID over NFC/USB | `create_bridge(secure-element, agent\|remote)` |
| `list_usb_exports` | `list_bridges` (superset) |

## 8. Ponytail discipline (so this isn't a cathedral)

- The abstraction has *already* earned itself: 3+ sources, 4+ sinks, scattered
  UIs. But grow it, don't big-bang it.
- **Phase 0 is pure reflection** — no behaviour change, just legibility. Ship it
  first; it pays for itself immediately.
- **Add a transport only when a source needs it.** No speculative plugin system.
- **Per-use consent beats standing toggles** wherever the action is deliberate
  (the #287 lesson) — fewer gates, less Settings sprawl.
- **Stop at the first rung that holds.** A read-only Devices list + the auto-detect
  notification may be 80% of the felt value; the registry refactor and new sources
  earn their place one real use-case at a time.

---

[← Features](../FEATURES.md) · [USB device forwarding](../features/usb.md) ·
[Reading USB drives](../features/usb-drives.md) ·
[Vision](https://github.com/GlassHaven/Haven/blob/main/VISION.md)
