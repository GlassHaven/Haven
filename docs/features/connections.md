---
layout: default
title: Connections & networking
---

# Connections & networking

Saved connection profiles, the transports they ride, the firewall front-doors
that open before a connect, and on-device SSH key management.

## Connections

Saved profiles with transport selection (SSH, Mosh, Eternal Terminal, VNC, RDP, SMB, Email, Cloud Storage, Reticulum, AI endpoints ([OpenAI-compatible / Ollama / Anthropic / Gemini](chat.md)), and [Bluetooth / BLE / USB serial consoles](terminal.md#serial-consoles-bluetooth-ble-usb)), host key TOFU verification, fingerprint change detection, auto-reconnect with backoff, password fallback, local/remote/**dynamic** port forwarding (-L/-R/-D — the dynamic type runs a built-in SOCKS5 proxy that tunnels traffic through the SSH session), ProxyJump multi-hop tunneling (-J) with tree view, SOCKS5/SOCKS4/HTTP proxy support (Tor .onion compatible), RDP-over-SSH tunnel profiles, DNS resolution with 5s timeout, and connection error safety nets (20s UI watchdog, post-connect shell verification, session manager detection).

**Per-app WireGuard and Tailscale tunnels** — each profile can route its connection through its own userspace tunnel, with no system VPN slot taken, so other apps keep using the direct network.

## Port knocking

Optional per-profile knock sequence sent immediately before the real socket open. Format is whitespace- or comma-separated `port[/proto]` tokens — e.g. `7000 8000 9000` (all TCP) or `7000/tcp 8000/udp 9000/tcp` for mixed sequences that match a `knockd`/`fwknop`-style configuration. The sequence is fired from the device using ordinary TCP `Socket.connect()` and `DatagramSocket.send()` calls (no root, no raw sockets), with a configurable inter-knock delay (default 100 ms) and a fixed 200 ms post-knock settle so the firewall has time to install its rule before SSH/VNC/RDP/SMB connects. Wired into all direct-dial paths; skipped on SSH-tunneled, WireGuard/Tailscale, and SOCKS-routed paths since the knock packet wouldn't reach the right firewall from the device. Each knock attempt — success or failure — appears in the Connection Log entry's verbose output as a `[knock] ... -> ok in Nms` line so post-hoc debugging is possible. The connection-edit dialog includes a **Test knock** button that runs the sequence once without committing or connecting, returning the result inline.

## Single Packet Authorization (fwknop)

The cryptographic alternative to port knocking, interoperable with [fwknop](https://github.com/mrash/fwknop)/`fwknopd`. Where a knock sequence is a series of cleartext SYNs that a passive observer can record and replay in order, SPA is a single encrypted, HMAC-authenticated UDP packet — it can't be read, and the per-packet HMAC plus random/timestamp fields defeat replay. Haven builds the packet itself, natively in Kotlin (no `fwknop` binary), and sends it on the same pre-connect hook as the knock; if both are configured the SPA goes first.

The implementation follows the fwknop wire format: an SHA-256-digested message encrypted with AES-256-CBC (OpenSSL `Salted__` / EVP-BytesToKey-MD5 key derivation) and authenticated with encrypt-then-MAC HMAC-SHA256 — the modern fwknop defaults. The Kotlin builder is verified byte-for-byte against `fwknop 2.6.11`, and a live `fwknopd` accepts Haven's packets and opens the firewall. Per profile you set the Rijndael key and (recommended) HMAC key — each as a passphrase or base64 (`--key-base64-rijndael` / `--key-base64-hmac`) — the access spec (`tcp/22`, or comma-separated for multiple ports), the destination UDP port (default 62201), and the allow-IP mode: **Source IP** (sends `0.0.0.0`, so `fwknopd` opens for the packet's own source IP — the right default for a phone behind changing/CGNAT addresses, with no extra round-trip), **Resolve public IP** (looks up the egress IP first, like `fwknop -R`), or **Explicit IP**. Like the knock it runs only on direct-dial paths, logs a `[spa] ... -> sent NB in Nms` line to the Connection Log, and has a **Test SPA** button that sends one packet without committing or connecting. Keys are stored encrypted at rest (Android Keystore-backed AEAD), and the `set_spa`/`test_spa` MCP tools never echo key material back. v1 is AES-CBC + HMAC-SHA256 over UDP only — no GPG mode, alternate cipher/digest modes, or NAT-access.

## SSH Keys

Generate Ed25519, RSA, and ECDSA keys on-device. Import keys from file (PEM/OpenSSH/Dropbear format) or paste from clipboard. FIDO2/SK hardware key support (ed25519-sk, ecdsa-sk) via NFC or USB security keys — and a phone-hosted security key can be forwarded to a remote host (see [USB device forwarding](usb.md)). One-tap public key copy and deploy key dialog for `authorized_keys` setup. Assign specific keys to individual connections.

---

[← All features](../FEATURES.md)
