---
layout: default
title: Reading USB drives
---

# Reading USB drives (flash drives, SSDs, memory cards)

**The short version:** plug a USB drive into your phone, and Haven can show you
its files — even drives formatted for Linux that your phone normally can't read.
Tap once, wait a minute, and the drive's folders appear in Haven's file browser
like any other connection.

This page assumes no background knowledge. If you just want the steps, jump to
[How to use it](#how-to-use-it). If you're curious *why* a phone struggles with
some drives, read [Why this is hard](#why-this-is-hard) at the end.

## The problem this solves

You plug a USB flash drive or portable SSD into your phone (with a USB-C or
USB-OTG adapter). Sometimes the phone shows the files straight away — great. But
often it shows **nothing**, even though the drive is fine and full of files.

That usually means the drive is formatted in a way your phone's built-in file
support doesn't understand. The most common cases:

- The drive was **formatted on a Linux PC** (a format called *ext4*). Android
  can read the simple formats cameras and Windows use (FAT32, exFAT), but not the
  Linux ones.
- The drive is **partitioned** in a layout (called *GPT*) the phone won't open.
- It's an **internal disk pulled out of a computer**, a Raspberry Pi SD card, a
  NAS disk, and so on.

In all of these, the files are perfectly readable — your phone just doesn't have
the right "driver" built in, and on a normal (un-rooted) phone you can't add one.

## What Haven does

Haven runs a **tiny, throwaway Linux computer inside the app** (a *virtual
machine*) that *does* have those drivers. It hands the USB drive to that little
Linux, which reads the drive and shares the files back to Haven. To you it just
looks like a new connection named after your drive — open it in **Files** and
browse, copy things off, or open a terminal into it.

Nothing is written to your drive (it's mounted **read-only** by default), nothing
leaves your phone, and the little Linux is deleted as soon as you eject the drive.

> **In a hurry, or the drive is failing?** Haven also has a fast route that
> skips the VM and puts the *raw card* in a Linux console within seconds —
> that's what you want for a `ddrescue` rescue of a dying SD card. It can't
> browse files (no filesystem drivers), it's for rescue work. See
> [USB card rescue console](usb-recovery-live.md).

> You don't need to know anything about virtual machines to use this — that's the
> whole point. The word "QEMU" you might see elsewhere is just the engine doing
> the work behind the scenes.

## How to use it

There's nothing to turn on first — just open the drive when you want it.

**1. Plug in the drive.** Connect your USB drive to the phone with a USB-C or OTG
adapter. (If nothing happens at all, try a different adapter or cable — a flaky
adapter is the most common reason a drive isn't detected.)

**2. Open it.** Go to the **Desktop** tab → **Manage** → the menu next to the
distro picker → **"Open USB drive…"**. (Opening a drive is always a deliberate
tap like this, so it never happens behind your back.) Haven shows *"Opening the
USB drive in a Linux VM — this can take a few minutes…"*

**3. Wait.** The **first** time, this takes a few minutes: Haven downloads a
small Linux image once and builds a little helper system from it. That helper is
**kept**, so every open after the first skips all of that and just boots it —
much quicker. A **live progress line** under Manage shows what it's doing —
*"Building the USB helper Linux…"* (first time only), then *"Booting the USB
helper…", "Setting up the VM and mounting your drive…"* — so you can see it's
working, not stuck.

**4. Browse.** When it's ready, Haven **opens the drive in Files for you**,
showing its contents (your drive's partitions live under `/mnt`, e.g.
`/mnt/sda1`). It also stays saved as a **"USB: …"** connection in **Connections**
— copy files off, preview them, or open a terminal into the drive.

**5. Eject when done.** Back in **Desktop → Manage**, the same menu now says
**"Eject USB drive"** — tap it to shut the little Linux down and free the drive.
(It also closes by itself if you don't.) The **"USB: …"** connection stays put —
it's a bookmark to the drive, not a live session. **Tap it again any time** (with
the drive still plugged in) and Haven reopens the little Linux and reconnects
automatically — you don't need to go back through "Open USB drive…". If the
drive isn't plugged in when you tap it, Haven tells you to plug it back in
first. The helper system itself stays installed between opens; if you'd rather
have the ~280 MB back, the same Manage menu has **"Delete USB helper Linux"** —
it just rebuilds itself, once, the next time you open a drive.

## What works, and what doesn't

**Works:**

- **Linux drives** — ext4, and most partition layouts including GPT.
- **Ordinary drives** — FAT32 / exFAT work here too (though your phone can
  usually read those directly without this feature).
- A full **terminal into the drive**, if you want to poke around with Linux
  commands — it comes free with the connection.
- **Recovery and forensics tooling, pre-installed** — the VM ships `testdisk`
  (partition-table + deleted-file recovery, bundles `photorec`), `gdisk`/
  `sgdisk` (GPT repair), `parted` (MBR/GPT editing), `smartctl` (drive
  health), `ddrescue` (imaging a failing drive), and `fsck.ext4`/`ntfsfix`/
  `fsck.vfat` — because this is a *real* Linux kernel with real block-device
  access, not proot. Run them from the terminal like you would on any Linux
  box; open the drive **writable** first if a repair needs to write.

**Also works:**

- **Encrypted drives (LUKS)** — a locked partition mounts read-only-locked
  alongside any plain ones; the Desktop → Manage card shows an "Unlock…"
  action per locked partition (or the `unlock_usb_drive_partition` MCP verb)
  once you enter its passphrase. Runs against the already-booted VM, no reboot.
- **Writing to the drive** — "Open USB drive (writable)" mounts read-write
  instead of the default read-only, with a confirmation first. Writes are
  flushed immediately (mounted with `sync`) rather than cached, and ejecting
  explicitly syncs + unmounts before the VM powers off — both specifically to
  limit what an unexpected kill (app backgrounded under memory pressure,
  crash, battery pull) can corrupt. Read-only stays the safer default.

**Also works: multiple drives at once** — open a second (or third, up to a
phone-resource limit) drive without ejecting the first. They share one
running VM rather than each getting their own (the small helper Linux is
booted once; every additional drive is just another attach inside it),
each with independent files, mounts, and its own Eject.

**Doesn't work (and never will):**

- **Webcams and USB microphones** — these can *never* work through any of Haven's
  USB features, due to a hard limit in Android's USB support (see
  [USB device forwarding](usb.md) for the details).

## Trade-offs and limits

Because your phone isn't "rooted," the little Linux can't use the phone's CPU
directly — it has to be *emulated*, which is **slow**. That's fine for what this
feature is for: **getting files off a drive**. It is *not* meant to be a fast,
everyday Linux desktop. Expect the **first** open to take a few minutes (it
builds the helper once); later opens are quicker, and file copies are unhurried
either way.

If all you need is a normal Linux *desktop* on your phone (not a USB drive),
Haven has a much faster option that doesn't involve a virtual machine — see
[Local Linux on-device](local-linux.md).

## For the agent (MCP)

If you drive Haven with the built-in AI-agent bridge, these tools cover this.
There's no setting to enable first — `open_usb_drive` is consent-gated per
session (approve it once, since it mounts your disk):

- `open_usb_drive` — start the VM for an attached drive (returns immediately;
  the boot continues in the background).
- `list_usb_drives` — list attached USB drives and the VM's progress. While it's
  booting, `vm.stage` carries a human-readable line ("Building the USB helper
  Linux…", "Booting the USB helper…", etc.) you can relay; poll until
  `vm.phase: ready`, which includes the loopback **`profileId`** and the mounted
  paths. `applianceProvisioned` tells you whether the one-time setup is already
  done (so the next open is quick).
- `close_usb_drive` — eject and tear everything down.
- `delete_usb_appliance` — delete the kept helper Linux (reclaims ~280 MB); the
  next `open_usb_drive` re-provisions it once.

Once it's ready, browse the drive with the normal file tools (`list_directory`,
`serve_file`) using the returned `profileId`, exactly like any SFTP connection.

## Why this is hard

*(Optional — skip this unless you're curious.)*

Reading a drive like an ext4 disk needs a piece of software called a **kernel
driver**, which only a real operating-system **kernel** can load. A normal phone
app — including Haven's lightweight "Linux on the phone" mode
([proot](local-linux.md)) — has no kernel of its own, so it can never grow that
ability, no matter what you install.

A **virtual machine** is different: it's a complete, real (if pretend) computer,
*with its own kernel*. So it can load the driver and read the drive. The catch is
that running a whole computer-inside-a-computer is expensive, which is why it's
slow on a phone.

Haven already knew how to lend a phone's USB device to a *real* Linux machine over
the network (the [USB/IP feature](usb.md#to-a-remote-host-over-usbip)). This
feature reuses exactly that — except the "real Linux machine" is the little one
running on your own phone, a few inches from the drive. Same plumbing, no network.

Originally requested in
[issue #287](https://github.com/GlassHaven/Haven/issues/287).

---

[← All features](../FEATURES.md) · [USB device forwarding](usb.md) ·
[USB card rescue console](usb-recovery-live.md) ·
[Local Linux on-device](local-linux.md)
