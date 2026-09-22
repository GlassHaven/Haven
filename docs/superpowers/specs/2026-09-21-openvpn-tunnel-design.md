# OpenVPN tunnel support for Haven

Status: draft, pending user review
Date: 2026-09-21
Related: GH #102 (WireGuard), #154 (Cloudflare Access / per-profile tunnels), #492 (NetBird)

## Summary

Add OpenVPN as a fifth `TunnelConfigType`, alongside WIREGUARD, TAILSCALE,
CLOUDFLARE_ACCESS, and NETBIRD — a per-profile userspace tunnel with no
Android VpnService involvement, matching the existing architecture exactly.
A connection profile picks "OpenVPN" as its tunnel, pastes a standard
`.ovpn` config (+ optional username/password), and Haven dials SSH/VNC/RDP/
SFTP/etc. through it exactly as it does today for WireGuard.

## Why this is tractable despite OpenVPN having no WireGuard-go equivalent

`wireguard-go`'s userspace implementation plus its `tun/netstack` package is
what makes Haven's existing WireGuard/Tailscale/NetBird tunnels possible
without ever touching Android's VpnService: `netstack.CreateNetTUN(...)`
hands back a `tun.Device` (generic — not WireGuard-specific) backed by a
gVisor network stack, and callers dial through the returned `*netstack.Net`.

No pure-Go OpenVPN implementation of comparable maturity exists (`ooni/
minivpn` is real but explicitly disclaimed by its own maintainers as unsafe
for real traffic — no re-keying, no tls-crypt, pre-1.0, "please do not use
this for any real-life situation where you need to trust it with user
data"). The production implementation is OpenVPN3 Core (C++, maintained by
OpenVPN Inc.), which every serious OpenVPN client (including the official
one and ics-openvpn) embeds via its `TunBuilderBase` callback interface.

`mysteriumnetwork/go-openvpn` (AGPLv3, same license as Haven) already did
the hard CGO-bridging work: a Go `TunnelSetup` interface mirroring
`TunBuilderBase` 1:1, cgo-linked against a prebuilt OpenVPN3 Core static
library. Its `TunnelSetup.Establish() (int, error)` contract just requires
returning *a file descriptor OpenVPN3 will treat as a raw-IP-packet TUN* —
on real Android clients this comes from `VpnService.Builder.establish()`,
but nothing requires that. We supply a local `socketpair()` fd instead and
bridge the other end to `netstack.CreateNetTUN(...)`, exactly the same
approach `wgbridge.go` already uses for WireGuard. `SocketProtect()` becomes
a no-op — there's no real system routing to protect against since nothing
here ever creates a VpnService or installs system-wide routes.

The repo itself is dead (last commit Dec 2020) and its prebuilt
`libopenvpn3_android_*.a` binaries are from that snapshot — six years of
unpatched crypto/TLS and uncertain link-compatibility with NDK 29. We reuse
its Go/CGO glue code (which doesn't rot) but rebuild the OpenVPN3 Core
static libraries ourselves from current upstream source
(`OpenVPN/openvpn3`, still actively maintained by OpenVPN Inc.), adapting
`go-openvpn`'s `scripts/x-compile-android-*.sh` as a starting template
against our NDK 29 toolchain.

## Architecture

```
ConnectionProfile (tunnelConfigId) ──▶ TunnelConfig (type=OPENVPN, configText=JSON blob)
                                              │
                                              ▼
                                      TunnelManager.create()
                                              │
                                              ▼
                                     OpenVpnTunnel : Tunnel        (Kotlin, core/tunnel)
                                              │  gomobile call
                                              ▼
                                    Ovpnbridge.startTunnel(...)    (Go, rclone-android/go/ovpnbridge)
                                              │
                              ┌───────────────┴────────────────┐
                              ▼                                ▼
                     openvpn3.Session (vendored             netstack.CreateNetTUN(...)
                     go-openvpn Go/CGO glue)                 (wireguard-go's gVisor netstack,
                              │  Establish() fd                same package wgbridge uses)
                              ▼                                       ▲
                     socketpair() ───────── pump goroutines ──────────┘
                              │
                              ▼
                  OpenVPN3 Core (C++, rebuilt from current
                  upstream source via NDK 29 + CMake,
                  cgo-linked as libopenvpn3_android_<abi>.a)
                              │  real UDP/TCP socket
                              ▼
                        OpenVPN server (TLS handshake, data channel)
```

## Components

**1. Native: OpenVPN3 Core static libraries (new build step)**
Rebuild `libopenvpn3_android_{arm64-v8a,armeabi-v7a,x86,x86_64}.a` from
current `OpenVPN/openvpn3` upstream source, using `go-openvpn`'s
`scripts/x-compile-android-*.sh` as a template adapted to our already-
installed NDK 29 + CMake 3.31.6. Lives as a new `openvpn3-core` submodule
or vendored source tree (mirrors how `termlib`/`proot-termux` are vendored
today), with a Gradle `Exec` task (`buildOpenvpn3Native`, pattern-matched
against `core/local`'s `buildProot`/`buildWayvncShim` tasks) producing the
`.a` files consumed by the Go build's cgo `LDFLAGS`.

**2. Go glue: `ovpnbridge` package** (new, `rclone-android/go/ovpnbridge/`,
alongside the existing `wgbridge/`, gomobile-bound into the same
`libgojni.so` used today)
- Vendors `go-openvpn`'s `openvpn3` package (Session, Config, Credentials,
  TunnelSetup interface, CGO callback bridge) with license headers intact
  (AGPLv3, compatible with Haven's own license) — this is the part that
  doesn't need rewriting, just re-pointing its cgo LDFLAGS at our freshly
  built `.a` files instead of its stale checked-in ones.
- New code: `OvpnTunnelSetup`, implementing `openvpn3.TunnelSetup`.
  Accumulates the `SetLayer`/`SetRemoteAddress`/`AddAddress`/`SetMtu`
  callback sequence into local state; on `Establish()`, calls
  `netstack.CreateNetTUN(addresses, dns, mtu)`, creates a `socketpair()`,
  hands one fd back to OpenVPN3, and spawns two pump goroutines relaying
  raw IP packets between the socketpair's Go-side `net.Conn` and the
  netstack's `tun.Device`. `SocketProtect` returns `true` unconditionally
  (documented no-op, see Architecture section).
- `StartTunnel(configText, username, password string) (*TunnelHandle,
  error)`: parses the `.ovpn` config into `go-openvpn`'s `Config`, builds
  `UserCredentials` when username/password are non-empty (auth-user-pass
  mode), constructs a `Session` via `NewMobileSession`, calls
  `session.Start()`, then blocks on a channel fed by the `EventConsumer`
  callback until either the first successful `Establish()` (tunnel is up —
  return the handle) or a terminal error/disconnect event (return an
  error) — bridging OpenVPN3's async connect handshake into the same
  synchronous "returns once ready" contract `wgbridge.StartTunnel` already
  has, which `TunnelManager` expects.
- `TunnelHandle` exposes the same surface as `WireguardTunnel`'s native
  handle: `Dial`, `ListenTCP`, `ListenUDP`, `StartSocksListener`,
  `BindAddr`, `Close` — reusing `tnet` (`*netstack.Net`) from
  `CreateNetTUN` exactly as `wgbridge` does, so the gomobile-bound API
  shape Kotlin sees is structurally identical to `Wgbridge`.

**3. Kotlin: `OpenVpnTunnel.kt`** (new, `core/tunnel/`, mirrors
`WireguardTunnel.kt` near line-for-line): implements `Tunnel` by
delegating `dial`/`listenUdp`/`listenTcp`/`socksAddress`/`localAddress`/
`close` to the gomobile-bound `Ovpnbridge.TunnelHandle`.

**4. Config storage:** new `TunnelConfigType.OPENVPN`. `TunnelConfig
.configText` holds a JSON blob (`OpenVpnConfigBlob`, new file mirroring
`TailscaleConfigBlob`/`NetbirdConfigBlob`): `{ovpnConfigText, username?,
password?}`, encrypted at rest the same as every other tunnel type — no
change to the encryption path, `TunnelConfigRepository` is already
type-agnostic.

**5. Dispatch:** one new arm in `TunnelManager.create()`:
`TunnelConfigType.OPENVPN -> OpenVpnTunnel(blob.ovpnConfigText, blob
.username, blob.password)`.

**6. UI:** the existing tunnel-type picker (wherever WIREGUARD/TAILSCALE/
CLOUDFLARE_ACCESS/NETBIRD are offered today — `feature/settings` or
`feature/connections`, to be located precisely during implementation)
gains an "OpenVPN" entry: a multiline text field for pasting the `.ovpn`
file (inline `<ca>`/`<cert>`/`<key>` blocks supported, matching what
`go-openvpn`'s config parser already accepts) plus optional username/
password fields shown when the pasted config requests `auth-user-pass`.

## Scope (what this does and doesn't support)

In scope, driven by what `go-openvpn`'s vendored config/session code
already parses and what OpenVPN3 Core itself supports: TLS-client mode
with client certificates, `auth-user-pass`, UDP and TCP transport, NCP
cipher negotiation (AES-*-GCM, AES-*-CBC), `tls-auth` and `tls-crypt`
(OpenVPN3 Core supports these natively, unlike `minivpn`), standard
`redirect-gateway`/route-push handling captured but not applied to system
routing (nothing here touches system routes — Haven only dials through the
netstack for the owning profile, same as every other tunnel type).

Out of scope for v1: static-key mode (no TLS at all — rare, and
`go-openvpn`'s config parser doesn't appear to target it), `tls-crypt-v2`
(needs verification against current OpenVPN3 Core — flagged as an
implementation-time check, not a v1 requirement), compression (LZO/LZ4 —
deliberately excluded; compressing encrypted traffic re-opens VORACLE-style
oracle attacks, and Haven's other tunnels don't offer it either).

## Error handling

- Native build failure (OpenVPN3 Core doesn't compile against NDK 29) is
  the highest-risk unknown in this design — first implementation task
  should be proving this builds before any Go/Kotlin glue work.
- Connect failures (bad cert, auth-user-pass rejected, TLS handshake
  failure, unreachable server) surface through `go-openvpn`'s
  `EventConsumer`/`Logger` callbacks; `Ovpnbridge.startTunnel` translates
  the terminal failure event into a Go `error`, which becomes a Kotlin
  `IOException` from `OpenVpnTunnel`'s constructor — same shape as
  `WireguardTunnel`'s existing `try/catch` wrapping of `Wgbridge
  .startTunnel`.
- Mid-session disconnects (server restart, network change): `go-openvpn`'s
  `Session.Reconnect(seconds)` exists for this; whether `OpenVpnTunnel`
  surfaces reconnect-in-place or just closes and lets `TunnelManager`'s
  existing retry/reconnect policy (used today for WireGuard) handle it is
  an implementation-time decision, default to reusing the existing policy
  for consistency.

## Testing

- Go-level (`ovpnbridge_test.go`, mirrors `wgbridge_test.go`/
  `wgbridge_dial_test.go`): stand up a real local OpenVPN test server
  (system `openvpn` binary + EasyRSA-generated test certs, or a
  disposable Docker container) in the test, connect via `ovpnbridge
  .StartTunnel`, dial a known test endpoint through the returned handle,
  assert the round trip. Matches the existing house style of testing the
  bridge against a real protocol peer, not mocks.
- Kotlin-level: `OpenVpnTunnel` construction/error-path unit test
  mirroring however `WireguardTunnel` is covered today (to be confirmed
  during implementation — `core/tunnel`'s only currently-visible test is
  `TunnelProxyTest.kt`, which is tunnel-agnostic).
- Native build verification: `scripts/check-native-libs.sh`-style ELF
  sanity check (right architecture per ABI directory, JNI entry point
  present) — same pattern already used for `rdp-kotlin`/`prns` native
  libs, extended to cover the new OpenVPN3 `.a` files.

## Open questions for implementation time (not blocking this spec)

1. Does OpenVPN3 Core's current upstream source build cleanly against NDK
   29 out of the box, or does it need patches (this was true for several
   other native submodules in this repo this session — proot's `awk
   strtonum`, missing cross-gcc for the USB/wayvnc shims)? First task,
   proves or disproves the whole plan's feasibility.
2. Exact license-header/attribution handling for the vendored `go-openvpn`
   Go/CGO glue (AGPLv3 requires preserving copyright notices — mechanical,
   not a design question).
3. Where exactly the tunnel-type picker UI lives (settings vs. per-profile
   editor) — confirm by reading the existing WireGuard picker during
   implementation rather than guessing here.
