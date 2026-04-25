# VNC-as-child-of-SSH refactor

Status: planning. Target: v5.25.0.

## Goal

Replace the current "VNC fields bolted onto an SSH profile" + "VNC profile
with `vncSshProfileId` back-reference" dual model with a single clean
parent → child relationship. A VNC service that runs over an SSH tunnel
becomes a separate `ConnectionProfile` row whose `parentId` points at its
SSH tunnel. Standalone VNC (no tunnel) has `parentId = null`.

The same shape generalises later to RDP-over-SSH and any "service hosted
by another connection" pattern.

## Why

Today there are two ways to express "VNC reached through SSH":

1. A profile with `connectionType = "VNC"`, `vncSshForward = true`,
   `vncSshProfileId` pointing at the parent SSH profile.
2. A profile with `connectionType = "SSH"` and `vncPort` / `vncUsername` /
   `vncPassword` / `vncColorDepth` / `vncSshForward` populated — created
   by the terminal's "Save for this connection" path in the VNC quick
   dialog.

This duality has caused real bugs (e.g. v5.24.38: the colour-depth
dropdown was wired into path 1's edit dialog but missing from path 2's
"Saved VNC settings" block — Nesos-ita's #107 follow-up). It also makes
it impossible to host two VNC services on one SSH tunnel
(`:0` on 5900 + `:1` on 5901), which is a normal multi-display setup.

A single `parentId` column collapses both representations into one.

## Data model

One new column on `ConnectionProfile`:

```kotlin
val parentId: String? = null  // FK → ConnectionProfile.id, ON DELETE SET NULL
```

What this lets us delete (eventually):

- `vncSshProfileId` (subsumed by `parentId`)
- `vncSshForward` (`parentId != null` is the signal)
- The "Saved VNC settings" block on SSH profiles — those fields move to
  child VNC profiles
- The duality where a `connectionType = "SSH"` row may also carry VNC
  state

## Migration (DB v40)

Walk every existing profile:

1. **SSH with stored VNC fields** (`type=SSH && vncPort != null`):
   insert a new child `type=VNC` row with `parentId = ssh.id`, copy
   `vncPort` / `vncUsername` / `vncPassword` / `vncColorDepth` to its
   own port/username/password/colour-depth fields, then null those
   fields on the SSH parent.
2. **VNC with `vncSshProfileId` set**: `parentId = vncSshProfileId`.
   Drop the now-redundant `vncSshForward` flag.
3. **Standalone VNC** (`vncSshForward = false`, no `vncSshProfileId`):
   `parentId = null`, no other change.
4. **Orphan check**: if `parentId` references a row that doesn't exist
   (deleted at some point), `parentId = null` and keep the VNC profile
   as standalone with whatever host it has.

`BackupService` mirrors the same migration when importing old backup
files (a v5.24.x backup must restore cleanly into v5.25.x).

## UI

### Connections list

Child profiles render indented under their parent, with a connector
glyph. Long-press menu on an SSH profile gains "Add VNC service" / "Add
RDP service".

### `ConnectionEditDialog` — three modes

| Mode | Layout |
|---|---|
| **SSH (no parent)** | Only SSH fields. Bottom of dialog shows "Services on this SSH connection" with a list of children (tap to edit) and an "Add VNC / Add RDP" row. |
| **VNC with parent** | Header reads "Through: [Parent SSH name]" (non-editable, links to parent). Host field hidden (shows "Reachable from SSH server" hint with default `127.0.0.1`). Only VNC-specific fields: port, username, password, colour depth, label, color tag. |
| **VNC standalone** | Current standalone VNC layout, no changes. |

### Terminal's VNC quick dialog

When the user clicks the "VNC" affordance from inside a terminal session,
instead of stuffing fields onto the SSH profile via "Save for this
connection", we **create or update the child VNC profile**:

- look it up by `parentId == ssh.id` first;
- if exactly one exists, edit it;
- if none, create one;
- if multiple, show a picker.

## Connection launch flow

`ConnectionsViewModel.connectVnc` already navigates with the SSH session
ID + tunnel host/port. The change is just where it reads the SSH parent
from: `parentId` instead of `vncSshProfileId`. End-user behaviour
identical for one tunneled VNC; just naturally supports multiple.

## Edge cases

- **Deleting a parent SSH profile**: Room's `ON DELETE SET NULL` orphans
  the children. They become standalone VNC profiles pointing at whatever
  host they had (which is usually `127.0.0.1`, so the user will need to
  fix the host before the standalone profile becomes usable).
  Alternatives considered:
  - Refuse delete if children exist — bad for the "I'm cleaning up"
    workflow.
  - Cascade delete — silently drops the child's saved password. Worse.
- **`vncSshForward = false` on a VNC with `vncSshProfileId` set** —
  pre-existing contradiction in the data. Migration resolves by trusting
  the back-reference (sets `parentId`).
- **Multiple VNC children on one SSH** — supported with no extra UX
  work; user just adds another via the "Add VNC service" entry.

## Critical files

| File | Change |
|---|---|
| `core/data/.../entities/ConnectionProfile.kt` | add `parentId: String?` |
| `core/data/.../db/HavenDatabase.kt` | v39 → v40 + `MIGRATION_39_40` (the four-case walk above) |
| `core/data/.../db/DatabaseModule.kt` | register the migration |
| `core/data/.../db/ConnectionRepository.kt` | `childrenOf(parentId)` query |
| `core/data/.../backup/BackupService.kt` | round-trip `parentId`; same migration on import |
| `feature/connections/.../ConnectionsScreen.kt` | indent children, "Add service" entry on long-press menu |
| `feature/connections/.../ConnectionEditDialog.kt` | three-mode rework above; remove "Saved VNC settings" block |
| `feature/connections/.../ConnectionsViewModel.kt` | `VncNavigation` constructed via parent lookup |
| `feature/terminal/.../TerminalScreen.kt` | `VncSettingsDialog` writes a child profile, not SSH fields |

## Phasing

- **Phase 1** — data only (~1 day): add `parentId`, migration,
  BackupService, repository helper, migration tests. Both new and old
  code paths read from `parentId` — UI unchanged.
- **Phase 2** — edit + launch (~1 day): edit dialog three-mode rework;
  connection launch reads from `parentId`. Old code paths still work as
  a safety net via fallback to `vncSshProfileId`.
- **Phase 3** — list + terminal + cleanup (~1 day): connections list
  indenting, terminal quick-dialog rewrite, delete `vncSshProfileId` /
  `vncSshForward` / SSH-with-VNC-fields code.
- **Phase 4** — optional follow-up: apply the same to RDP
  (`rdpSshForward` path) and any future protocol that runs over SSH.

## Verification

- **Unit tests** for the migration with five fixture profiles (one of
  each scenario above + an orphan).
- **Round-trip** a v5.24.38 backup file through the new code; existing
  profiles + saved-VNC-settings appear as parent + child.
- **Manual — existing user, no regression**: a user with
  terminal-quick-dialog-saved VNC settings sees no functional change;
  the "VNC" button still launches the same target with the same colour
  depth and password.
- **Manual — new capability**: create two VNC children under one SSH
  parent (e.g. ports 5900 and 5901), both connect via the same SSH
  session.
- **Manual — orphaning**: delete an SSH profile that has VNC children;
  children become standalone, are still listable + editable, and the
  edit dialog now shows the host field.

## Out of scope

- Replacing the per-profile reachability / IP-discovery logic.
- Anything to do with how the SSH session itself is established (reuses
  existing `SshSessionManager`).
- The eventual extraction of "tunnels" as their own first-class entity
  separate from "shells you can attach to" — for now, an SSH profile is
  both.
- Promoting `parentId` to a real Room foreign-key relation; we leave it
  as a plain `String?` and rely on the migration / orphan check.
