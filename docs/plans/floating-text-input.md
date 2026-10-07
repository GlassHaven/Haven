# Floating text-input dialog for terminal

Status: **shipped.** Red-team reviewed 2026-07-22 (4 independent Fable-5 passes:
gesture/UX conflict, architecture-integration correctness, multi-tab/state
risk, completeness/testing gaps). Corrections below are resolved decisions,
not open questions, unless marked otherwise. The plan was built as written:
`FloatingTextInputDialog.kt` (landed in 29460c20a), kept in sync with the
review decisions; it rides the ComposeController CJK compose wiring from
12d41f533 and the selection toolbar shows by default there (ed6082111).

<!-- Was "planning — target: the next minor after this revision is
     accepted". Marked shipped 2026-10-07 after a docs drift pass. -->

## Corrections from red-team review (2026-07-22)

1. **Send path must go through bracket-paste-aware injection, not raw
   `sendInput`.** Two independent passes flagged this. Haven's existing
   paste path checks `bracketPasteMode`/mode 2004 and wraps in
   `ESC[200~…ESC[201~` before calling `sendInput` (see
   `TerminalScreen.kt` `doPaste`, ~line 1117–1128, and the scan-injection
   comment at ~line 321–335 — read the exact lines at implementation time,
   they may drift). `sendInput` itself never wraps; callers decide. The new
   dialog is explicitly a multi-line-capable input (the `↩`/`⇥` visual
   transform is the point) — sending raw newlines unwrapped means every
   embedded `\n` executes as a literal Enter in the remote session (e.g.
   inside `vim`/`psql`), not as "paste this whole block." **Decision: reuse
   the same bracket-wrap helper the paste path uses, do not call
   `activeTab.sendInput` directly with unwrapped bytes.**

2. **Dialog must render inside `key(activeTab.sessionId)`, and draft text
   must be hoisted out per-tab.** The per-tab UI subtree in
   `TerminalScreen.kt` is wrapped in `key(activeTab.sessionId) { ... }`
   (~line 875–879, confirm exact range at implementation time). Two tabs
   can look identical UI-wise while the *active* tab changes without a
   user tap on the tab bar — a session dying (`syncTabs` clamps the index),
   an MCP-driven `selectTabBySessionId`, or a delayed `selectTabByProfileId`
   (~2s async). If the dialog is rendered **outside** that `key()` block,
   its send closure re-binds to whatever tab is active *at send time*, not
   the tab that was active when typing started — typed text can go to the
   wrong session with no visible tab-switch to notice. Rendering **inside**
   `key()` avoids this by construction (a tab-identity change tears down
   and recreates the subtree, so a send can never outlive its own tab) at
   the cost of losing an in-progress, unsent draft on tab switch. Decision:
   accept that trade — safety over convenience for a feature whose whole
   purpose is precise text delivery. Mitigate the convenience loss by NOT
   storing draft text in local Compose `remember` inside the dialog itself;
   instead keep an in-memory `Map<sessionId, String>` of drafts one level up
   (e.g. in the screen's own state or a small holder), so switching back to
   a tab restores what was typed there, even though the dialog instance
   itself was torn down and recreated.

3. **Toolbar integration must go through the `ToolbarKey` system, not a
   bare callback + hardcoded button.** Confirmed (not just "check in
   review" as originally written): the toolbar is data-driven off
   `enum class ToolbarKey`, rendered through `ToolbarItem.BuiltIn(key)`
   layouts and `when (key)` branches, and the settings editor iterates
   `ToolbarKey.entries` for the user-facing reorder/configure UI. A bare
   `IconButton` bypasses reorder mode, uniform-grid layout, and the
   settings editor entirely — inconsistent with how every other toolbar
   action in this app works. Concrete touch points (verify exact file/line
   at implementation time, this is from a point-in-time read): new
   `ToolbarKey` entry, a `KeyVisual`/icon mapping, a `when(key)` render
   branch in `KeyboardToolbar.kt`, a new `ToolbarCallbacks` field
   (mirroring `onAttachTap`), and confirming the settings-editor listing
   picks it up automatically via `ToolbarKey.entries` (it should, if the
   enum entry is added correctly — verify, don't assume).

4. **Restore focus to the terminal on dismiss.** Plan previously wrote
   `onDismiss = { showTextInputDialog = false }`. Add the terminal's own
   focus requester call on dismiss (upstream's ConsoleScreen does this;
   find Haven's equivalent terminal `FocusRequester` in `TerminalScreen.kt`
   and call `.requestFocus()` in the dismiss lambda, not just flip the
   boolean).

5. **License attribution must be file-level, not just the existing
   README dependency-table row.** Haven's own `LICENSE` is AGPL-3.0; the
   README's "ConnectBot termlib | Apache-2.0" row is a *dependency* credit
   for the separate termlib submodule, not blanket coverage for copying
   application-layer UI source out of the same upstream project. Apache-2.0
   §4 requires retaining attribution in copied source. House precedent
   exists for exactly this (an existing `.kt` file in this repo carries a
   "Ported from &lt;project&gt; (&lt;license&gt;)…" comment; there is also a
   NOTICE-style doc for asset attribution elsewhere in the repo — find and
   match that pattern, don't invent a new one). Add: (a) an Apache-2.0
   license header block at the top of the new file (matching upstream's),
   (b) a short "Ported from org.connectbot.ui.components.FloatingTextInputDialog
   (Apache-2.0)" comment, (c) keep the existing README dependency-table row
   as-is (it's still correct, just not sufficient alone).

6. **Use `rememberSaveable`, not `remember`, for in-progress draft text**
   (in whatever form the per-tab draft map from item 2 takes) — plain
   `remember` loses unsent typed text across rotation or process death from
   backgrounding. This holds user-authored content, unlike the
   `attachSheetVisible` boolean flag it was templated from (losing a
   dialog-open boolean is harmless; losing typed text is not).

7. **Add a CHANGELOG.md entry before shipping.** Confirmed CI-enforced
   (`scripts/check-changelog.sh`) — a release cannot ship without its
   section. Add this to whatever step actually ships the feature, not
   just to this plan.

8. **Accessibility: decide explicitly, don't leave silent.** The
   move/resize interactions are raw `pointerInput { detectDragGestures }`
   drags with no `semantics { customActions }` — invisible to TalkBack.
   Haven has shipped explicit TalkBack work for the terminal before
   (gesture accessibility descriptions in a recent release), so this
   project visibly cares about this class of gap. Minimum bar: either add
   semantic custom actions for move/resize, or explicitly document in the
   PR description that position is fixed (not TalkBack-adjustable) as a
   known, accepted limitation — do not ship silently unaddressed.

### Noted but deferred to implementation-time judgment (not blocking)

- The dialog's containing `Popup` covers the full screen while open
  (matches upstream), which means terminal scroll/selection/mouse-SGR
  reporting AND Haven's own swipe-to-switch-tab gesture are all
  unavailable while the dialog is open (no double-dispatch risk — Compose
  `Popup` is a genuinely separate window — but a real "everything else is
  frozen" UX trade while it's up). Decide at implementation time whether
  to keep full-screen modal (simplest, matches upstream) or size the popup
  window to its own content instead of `fillMaxSize` so a tap outside its
  bounds can still reach the terminal.
- Haven already has at least two related-but-different mechanisms for
  "phone keyboard vs. terminal" friction (a `COMPOSE` IME-composition
  toolbar mode and a `VOICE_KEYBOARD` mode). Worth one sentence in the
  eventual PR description on why this is additive rather than overlapping,
  but not a blocker.
- Rotation/split-screen: the screen-fraction position/size persistence
  (inherited from upstream) can go stale across a resize without full
  Activity recreation; this is a pre-existing upstream gap, not introduced
  by the port. Fine to inherit as-is unless it proves annoying in testing.
- Existing snippet-library injection path uses a similar send mechanism —
  worth a byte-level parity check during testing, not a design change.

## Goal

Port ConnectBot's "Text Input" floating dialog into Haven's terminal screen.
Lets the user type a full command/line into a normal Compose `TextField`
(full IME: autocorrect, cursor movement, voice input, swipe typing) instead
of fighting the raw terminal cell for text entry, then send the whole
string to the active session in one shot.

## Why

Typing directly onto the terminal surface on a phone software keyboard is
error-prone: the keyboard's autocorrect/predictive layer and the terminal's
own key-dispatch path interact badly, cursor/backspace behavior over an
already-rendered terminal cell is confusing, and there is no way to review
what you typed before it goes out keystroke-by-keystroke. Batin hit this
directly on `nec` (screenshot, 2026-07-22) and asked for ConnectBot's
existing "Text Input" feature, which solves exactly this by decoupling
"where you type" (a normal text box) from "where it's displayed" (the
terminal).

## Source

`org.connectbot.ui.components.FloatingTextInputDialog` in
[connectbot/connectbot](https://github.com/connectbot/connectbot)
(`app/src/main/java/org/connectbot/ui/components/FloatingTextInputDialog.kt`),
Apache-2.0 — same license as Haven's existing `termlib` dependency from the
same upstream, already credited in `README.md`. Confirmed present in the
current upstream tree (not from an older/incompatible architecture): it is
a Jetpack Compose `@Composable`, same UI framework Haven uses throughout
(`feature/terminal/.../TerminalScreen.kt` is Compose-based too).

Upstream shape (as read from source, not guessed):

- Draggable + resizable `Popup` positioned/sized from `SharedPreferences`
  (`floating_input_x/y/width/height`, stored as screen-fraction floats).
- One Compose `TextField` with a `VisualTransformation` that shows `↩` for
  embedded newlines and `⇥` for tabs inline while keeping the real
  characters in the underlying string.
- Header row: title + close (`X`) button, draggable to reposition.
- Footer/side: send button (paper-plane icon) and a resize handle
  (`OpenInFull` icon), both via `pointerInput { detectDragGestures { ... } }`.
- Send path: `bridge.injectString(text)` then clear the field. Field is
  **not** auto-cleared on dismiss without sending (only `X`/outside-tap
  dismisses without send).

## Integration point in Haven (found by reading the code, not assumed)

Haven's per-tab session object already exposes the exact primitive needed:

```kotlin
activeTab.sendInput(bytes: ByteArray)
```

used today for paste (`TerminalScreen.kt`, both the main toolbar and the
selection-toolbar paste path):

```kotlin
onPaste = { text -> activeTab.sendInput(text.toByteArray()) },
```

So the port is `bridge.injectString(text)` → `activeTab.sendInput(text.toByteArray())`,
not a new session-write path.

`KeyboardToolbar` (`core/toolbar/.../KeyboardToolbar.kt`) already follows a
plain callback-param pattern for toolbar actions, e.g.:

```kotlin
val onAttachTap: () -> Unit = {}
```

The new trigger button follows the same shape: `onOpenTextInput: () -> Unit = {}`,
wired from `TerminalScreen.kt` to flip a `showTextInputDialog` state flag,
same pattern as the existing `attachSheetVisible` flag for the attach sheet.

## Plan

1. **New file** `feature/terminal/.../FloatingTextInputDialog.kt` (package
   `sh.haven.feature.terminal`, not a straight file copy into
   `org.connectbot.*` — Haven has already re-namespaced its own code).
   Port the Composable body as-is; only the send call changes
   (`activeTab.sendInput(text.toByteArray())`). Reuse Haven's own
   `MaterialTheme` color roles already in use elsewhere in this file so it
   matches Haven's theme, not ConnectBot's.
2. **String resources**: add Haven-side keys (do not assume upstream's
   exact resource names carry over; Haven's `R.string.*` catalog is its
   own). Needs: dialog title, placeholder/label, close/send content
   descriptions, resize-handle content description. Add to the base
   `strings.xml`; do **not** hand-translate into every locale Haven ships —
   flag as a translation-debt item for whatever process currently handles
   Haven's i18n (fastlane/metadata shows many locales already tracked).
3. **Toolbar wiring**: add `onOpenTextInput: () -> Unit = {}` to
   `KeyboardToolbar`'s param list (mirroring `onAttachTap`), add one
   `IconButton`/`KeyCell` entry, decide **exact placement** in review (a
   free slot, or behind the existing toolbar's overflow/settings path —
   `TerminalScreen.kt` already treats the toolbar as configurable
   `toolbarLayout`/`uniformGrid`, so this may need a `ToolbarKey`-style
   entry rather than a bare hardcoded button — check `ToolbarKey` enum
   before assuming a raw button is idiomatic here).
4. **State**: `var showTextInputDialog by remember { mutableStateOf(false) }`
   in `TerminalScreen.kt`, same shape as `attachSheetVisible`; render
   `FloatingTextInputDialog(activeTab, onDismiss = { showTextInputDialog = false })`
   conditionally, same conditional-render pattern already used for the
   attach sheet / VNC dialog in this file.
5. **Persistence keys**: upstream uses bare `SharedPreferences` keys
   (`floating_input_x` etc.) with no per-profile/per-tab scoping — confirm
   in review whether Haven wants this shared across all tabs/profiles
   (upstream behavior) or scoped, since Haven supports multiple concurrent
   tabs/sessions unlike a single-session assumption this key naming might
   imply.

## Open questions for review (not decided here)

- Does the floating window's own `pointerInput { detectDragGestures }`
  (drag-to-move, drag-to-resize) fight with `TerminalScreen.kt`'s existing
  gesture stack (`combinedClickable`, mouse SGR reporting, text selection
  drag, scroll) when the dialog is shown as an overlay `Popup` on the same
  screen? Upstream renders it as a `Popup(properties = PopupProperties(focusable = true))`
  — need to confirm Compose `Popup` truly isolates its own pointer input
  from the composables underneath, not just visually.
- Toolbar placement: is there a free `ToolbarKey` slot, or does adding one
  require a settings-visible toggle (Haven's toolbar is already
  user-configurable per `toolbarLayout`/`uniformGrid`/`editModeControlsPlacement`)?
- Multi-tab semantics: should the dialog be tied to `activeTab` only (text
  vanishes if the user switches tabs mid-type), or should in-progress text
  survive a tab switch?
- i18n debt: how many locales realistically need the new strings before
  ship vs. after (Haven tracks translations in `fastlane/metadata/android/<locale>`
  for store listings; in-app `strings.xml` locale coverage is separate and
  wasn't audited here).

## Testing before ship

- Real device (not just `./gradlew assembleDebug` succeeding): open the
  dialog over a live SSH/mosh/local session, type text with autocorrect
  on, send, confirm exact bytes land in the session, correctly bracket-
  wrapped when the remote app has bracketed-paste mode on (test explicitly
  inside `vim`/`psql`: an embedded newline must not execute as Enter).
- Confirm existing terminal gestures (scroll, tap-to-position-cursor,
  text selection, mouse reporting on apps like `less`/`vim`) still work
  normally with the dialog closed, and that opening/closing the dialog
  doesn't leave the terminal's focus/keyboard state stuck (focus must
  return to the terminal on dismiss, not just close the dialog).
- **Multi-tab specific (new, from red-team review):**
  - Start typing in the dialog, switch tabs via the tab bar mid-type,
    switch back — confirm the draft text is restored for that tab (per
    the per-tab draft map) and confirm nothing was sent to the tab you
    switched to.
  - Trigger a *non-tap* active-tab change while the dialog is open (an
    MCP-driven tab select, or let a session die so the active index
    clamps to another tab) — confirm the dialog tears down safely (no
    crash, no send into the wrong session) rather than silently rebinding
    its send target.
  - Close the tab whose dialog is open (if reachable) — confirm no crash
    from a stale `sendInput` reference into a removed transport.
  - Send text into a tab whose transport has since disconnected — confirm
    this fails silently/loudly in a way consistent with how paste already
    behaves for a dead session (don't introduce a new crash path).
- Confirm dialog position/size persists across dialog close+reopen and
  across app restart (matches upstream's `SharedPreferences` contract);
  confirm draft text survives rotation (via `rememberSaveable`/hoisted
  state) rather than being silently discarded.
- Toolbar: confirm the new button appears correctly through the normal
  toolbar settings/reorder editor (drag it, hide it, restore it) like any
  other `ToolbarKey` — not just that it renders in the default layout.
- Accessibility: with TalkBack on, confirm the documented behavior (either
  working custom actions for move/resize, or the accepted "position fixed
  under TalkBack" limitation) — don't ship with an undocumented silent gap.
- Before merge: CHANGELOG.md has an entry (CI-enforced by
  `scripts/check-changelog.sh` — a release cannot ship without it).
- Rollback: this is a pure addition (new file + one new `ToolbarKey` entry
  + new strings + one focus-restore call) — revert is a single commit
  revert, no data-model or migration involved, low regression risk if
  reverted.
