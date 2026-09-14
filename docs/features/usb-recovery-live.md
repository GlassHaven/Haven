---
layout: default
title: USB card rescue console
---

# USB card rescue console (live route)

**The short version:** plug a USB card reader into your phone and Haven can
open the card's *raw contents* in a small Linux guest within seconds — fast
enough to start a rescue (`ddrescue`) of a failing card while it still reads at
all. The existing [USB-drive route](usb-drives.md) boots a full virtual machine
and can take minutes; this route skips the VM entirely.

## When to use which

| | Rescue console (this page) | USB drive (VM route) |
|---|---|---|
| Time to usable | ~10–30 s | a few minutes |
| What you get | the **raw card** as `/dev/nbd0` in a Linux console | the card's **files** as a browsable connection |
| Read filesystems (ext4, GPT, LUKS…) | no — the guest has no filesystem drivers | yes |
| Typical use | `ddrescue` a failing card; read a FAT card with `mdir` | copy files off a drive Android can't read |

Both routes are **read-only by default** and can be open at the same time on
different cards.

## How to use it

1. **Plug the reader in** (USB-C or OTG adapter, card inserted).
2. Go to the **Desktop** tab → **Manage** → the menu next to the distro picker
   → **"Open USB card directly (rescue console)…"**.
3. A connection named **"USB: *your reader* (live)"** appears in Connections
   after a moment. Open it — that's the rescue console, already attached to
   the card.
4. Work in the console. The prompt prints the two commands you'll usually want:

```
ddrescue -f /dev/nbd0 /host/sdcard.img /host/sdcard.log
mdir -i /dev/nbd0p1 ::
```

- `ddrescue` copies the whole card (or with `ddrescue -i0 -s…` a range) to
  `sdcard.img`. Anything it couldn't read is retried and logged — run it again
  and it picks up where it stopped.
- `mdir` lists a FAT partition without mounting it (`/dev/nbd0p1`,
  `p2`, … for later partitions).

5. **Get the rescued image out.** Files the guest writes to `/host/` land in
   Haven's private `uml/share` folder on the phone. Pull it from a PC over
   adb, or copy it onward from a terminal on the phone.

## What "live" means here

The card is never mounted and never copied ahead of time. Haven exports the
card's sectors over a local socket to the guest, which sees the device as a
normal block disk (`/dev/nbd0`). Every read goes to the physical card, through
the reader, at the moment you run the command — which is what a rescue needs:
read the real card, retries and all, while it is still readable.

Because the guest kernel carries no FAT/ext4/exFAT drivers, it cannot mount
the card. That is deliberate: a rescue image should be taken from the raw
device, and a write into a mounted (and failing) filesystem is how damaged
cards get worse. Browse FAT with `mdir`; do everything else on the image you
rescued.

## Read-only by default

The export advertises itself read-only, and Haven refuses write commands
before they reach the card. The MCP route (`open_usb_drive` with
`route:"guest"`) can open it writable for the rare case you want to write
*back* to a card from the guest (e.g. burn a tested image back). The Desktop
tab menu item is read-only only.

## Closing

Press the **Close live session** button on the Desktop tab (or eject the
reader). Haven powers the guest off, stops the export, and releases the card.
The "USB: … (live)" connection stays in your list for next time.

## Requirements and limits

- Same requirements as the [guest](uml-guest.md): arm64 device, full flavour,
  ~600 MB free app storage for the rootfs image (shared with the guest — it's
  the same image).
- Throughput is bounded by the reader, the card, and the phone; a healthy card
  over a decent reader copies in the same order of magnitude as a PC. A dying
  card will be slow regardless — that's the card.
- If a session reports an error but the console still opens, the card likely
  stopped answering. The console stays attached so you can see what the card
  is doing.

---
[← All features](FEATURES.md)