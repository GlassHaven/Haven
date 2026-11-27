---
layout: default
title: "Design: Routing a tunnel's underlay through a reachable path"
---

# Tunnel underlay routing

> **Status:** partially built. The legible-error and documentation half of
> [#876](https://github.com/GlassHaven/Haven/issues/876) shipped in
> v5.94.69/v5.94.70, and **Yggdrasil TCP/TLS and Cisco AnyConnect / ocserv
> carrier SOCKS5 underlay routing** (`Route underlay through` + `TunnelManager`
> one-hop carrier refcounting) are implemented. NetBird `nbbridge` SOCKS5
> dialing and Android network binding remain open.

## 1. The problem

Haven's per-connection tunnels (WireGuard, Tailscale, NetBird, Yggdrasil) run
in a userspace netstack and carry only the profile's connection traffic. The
tunnel's own underlay sockets — NetBird's management gRPC and signal streams,
its relays, Yggdrasil's peer links — are dialed over the phone's ordinary
network. A LAN-only management or peer address is therefore unreachable when
the phone is on a remote network, even though another live tunnel on the same
phone (for example a WireGuard carrier to the home LAN) can reach it.

Reproduced in #876: a self-hosted NetBird management server at a LAN IP timed
out from mobile data while the phone's WireGuard carrier reached the same LAN;
a Yggdrasil peer configured at a LAN IP behaved the same way.

## 2. Constraints

- **No system VPN slot.** Per-app tunnels are the product stance; a fix must
  not take the system VPN or route other apps' traffic.
- **The underlay dial lives in the Go bridges.** `nbbridge` dials management
  and signal through its own dialer (`dialer_generic.go`); on
  `GOOS=android` the standard-dialer branch is skipped when the netstack is
  enabled. Yggdrasil peers come from its config and dial TCP/UDP directly.
- **No self-routing.** A tunnel cannot carry its own underlay; the carrier
  must be a different, already-live tunnel.
- **Cross-profile data path.** Routing one profile's underlay through another
  profile's tunnel is a new trust boundary and needs explicit opt-in.

## 3. Options

**A. Underlay carrier picker (recommended primary).** A per-tunnel setting,
"route underlay through", choosing another live tunnel profile. The bridge
dials its TCP underlay endpoints through that carrier's existing SOCKS5
listener (Haven already runs one per WireGuard/Tailscale profile). NetBird
management and signal are TCP and fit directly. Yggdrasil TCP peers fit;
UDP peer discovery does not, so those keep the direct path.

- Reuses the SOCKS surface; no second netstack.
- The carrier must be up first: ordering and a clear "carrier down" error.
- Adds latency (one extra hop) to the underlay only.

**B. Bind the underlay to a chosen Android network (complement).** When the
phone is on the LAN but the default route is mobile, let the user pick the
network (Wi-Fi) for the underlay sockets via `ConnectivityManager` and bind
the Go dialer's sockets to it. Helps the "on the LAN, wrong default route"
case; does nothing when truly remote.

**C. System VPN for the underlay.** Rejected: breaks the no-VPN-slot stance.

**D. Status quo.** Errors and docs already name the limitation; the user can
join the LAN or run a full-device VPN themselves. This remains the baseline
until A/B land.

## 4. Phasing

1. SOCKS5 dial support in `nbbridge` plus the carrier picker for NetBird
   management/signal. Consent: explicit per-profile opt-in, carrier shown in
   the connection UI.
2. Yggdrasil TCP peers via the same carrier path.
3. Network binding (option B) for the local wrong-default-route case.

## 5. Open questions

- Carrier lifecycle: auto-start the carrier on demand, or require it live?
- Does Tailscale's control dial want the same treatment?
- Should the carrier picker allow a chain (carrier of a carrier), or one hop
  only? One hop is the proposed limit.
