---
layout: default
title: Terminal
---

# Terminal

VT100/xterm emulator with multi-tab sessions, [Mosh](https://mosh.org) (Mobile Shell) for roaming connections and [Eternal Terminal](https://eternalterminal.dev) (ET) for persistent sessions — both with pure Kotlin protocol implementations (no native binaries), tmux/zellij/screen/byobu/psmux/Herdr auto-attach with **session restore** (remembers previously open sessions and offers to reopen them; the attach command assumes a POSIX shell as the login shell — on a stock Windows OpenSSH host the default shell is cmd or PowerShell, so the wrapper never runs there), tab reordering via long-press menu, color-coded tabs matching connection profiles, mouse mode for TUI apps, configurable keyboard toolbar (Esc, Tab, Ctrl, Alt, AltGr, arrows, Delete, Insert, Home/End/PgUp/PgDn, F1–F12, custom macro keys with presets for common combos like Ctrl+C/D/Z and Ctrl+Alt+Delete), text selection with copy and Open URL, OSC 133 shell integration with one-tap "copy last command output", configurable font size, and six color schemes. The bundled Hack Nerd Font Mono renders Powerline / Devicons / Font Awesome / Material Design glyphs in shell prompts out of the box.

## Serial consoles (Bluetooth, BLE, USB)

Haven speaks serial as well as network transports, so a microcontroller or an embedded board gets a full terminal — the same tabs, keyboard toolbar, text selection and colour schemes as SSH. Each is an ordinary saved connection profile, so reconnect, workspace capture and agent control all apply.

- **USB serial** — plug a USB-serial device into the phone over OTG: an Arduino, a Duet3D G-code board, an ESP32, or a USB-TTL adapter. Pick the attached device by its `vendor:product` id, set the baud rate and, if needed, the line format — data bits, parity, stop bits, flow control (default 8N1, no flow control). Android asks for USB permission on first connect. Common chipsets work: CDC-ACM, CH34x, FTDI, CP21xx, Prolific. DTR and RTS are asserted on open, so a device that stays silent until the port is "opened" (many Arduino sketches that wait on `while (!Serial)`) starts streaming right away.
- **Bluetooth serial (Classic SPP)** — a console over a paired Bluetooth-to-serial adapter (RFCOMM / Serial Port Profile). Pair the adapter in Android Settings → Bluetooth first, then pick it by its Bluetooth address.
- **Bluetooth LE serial (GATT UART)** — for BLE-only peripherals like an nRF board or an HM-10 module that Classic SPP can't reach. No pairing needed: add a connection, tap **Scan**, and pick the advertising device. The GATT service is auto-detected (Nordic UART Service, then HM-10), or you can supply explicit service/characteristic UUIDs.

## Serial-to-TCP bridge

A live serial session — Bluetooth, BLE, or USB — can be exposed as a raw TCP port on the phone's loopback interface, so a physically-attached device joins the rest of Haven's routing fabric. Pair that with an SSH remote-forward or a [tunnel](connections.md) and the serial device is reachable *off* the phone: read a sensor, or drive a board, from anywhere your phone can reach. The terminal tab keeps working while it's bridged, and bytes pass raw in both directions. The bridge is driven through Haven's [agent/MCP interface](../mcp-tools.md) — `bridge_serial_to_tcp` opens it and returns the port, `list_serial_bridges` shows what's active, `stop_serial_bridge` tears it down.

## OSC escape sequences

Remote programs can interact with Android through standard terminal escape sequences:

| OSC | Function | Example |
|-----|----------|---------|
| 52 | Set clipboard | `printf '\e]52;c;%s\a' "$(echo -n text \| base64)"` |
| 8 | Hyperlinks | `printf '\e]8;;https://example.com\aClick\e]8;;\a'` |
| 9 | Notification | `printf '\e]9;Build complete\a'` |
| 777 | Notification (with title) | `printf '\e]777;notify;CI;Pipeline green\a'` |
| 7 | Working directory | `printf '\e]7;file:///home/user\a'` |

Notifications appear as a toast in the foreground or as an Android notification in the background.

---

[← All features](../FEATURES.md)
