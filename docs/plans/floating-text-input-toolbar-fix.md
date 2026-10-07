# Plan — custom text-selection toolbar for the floating text input dialog

Status: **resolved.** The shipped fix is ed6082111 (#444), landed 2026-07-23:
the selection toolbar is shown by the floating text-input dialog itself, not
by a Compose `TextToolbar` override, because the platform toolbar route
described below could not be made to appear. Kept for the record.

## ⚠️ ROUND 2 (2026-07-23) — round-1 fix FAILED real-device test, new lead found

The round-1 implementation (custom `TextToolbar` via `LocalTextToolbar`, committed as
`f420edf1` on `fix/floating-text-input-selection-toolbar`) compiled clean, passed its own unit
tests, and was reasoned to be correct — but **Batin tested the real debug APK on his device and
the toolbar still does not appear.** Selection still works (handles visible), still no
Copy/Cut/Paste/Select-all menu. This is a genuine implementation failure, not a test error — do
not re-ship round 1 as-is.

**New lead, found by reading real Compose Foundation source** (`androidx/androidx` on GitHub,
`compose/foundation/foundation/.../text/selection/TextFieldSelectionManager.kt` and
`ComposeFoundationFlags.kt`): recent Compose Foundation has **two parallel context-menu systems**
gated by `ComposeFoundationFlags.isNewContextMenuEnabled` (default comes from a per-platform
`internal expect val isNewContextMenuInitiallyEnabled`, not confirmed for Android/this exact
version via web source alone — **verify the REAL compiled default by decompiling the actual
pinned `androidx.compose.foundation` AAR classes in this machine's Gradle cache, not by trusting
a web search** of the source, which hit rate limits and inconclusive results this round).

The doc comment on the flag: *"Whether to use the new context menu API and default
implementations in SelectionContainer and all BasicTextFields. If false, the previous context
menu that has no public APIs will be used instead."* `TextFieldSelectionManager.kt` branches on
this flag for how it decides the toolbar is "shown" (`textToolbarShownViaProvider` vs
`textToolbar?.status`), and toolbar display in the new-flag-true path appears to go through
`textContextMenuToolbarHandler()` / `updateFloatingToolbar()` — a **different mechanism than
`LocalTextToolbar.showMenu()`** entirely. **If this flag defaults to true for the pinned Compose
version, round 1's entire `LocalTextToolbar` override may be a no-op**, because the real UI path
never calls `showMenu` at all in that mode — this would fully explain the real-device failure
without any bug in round 1's own code.

**What round 2 must do, in order:**
1. Decompile/inspect the REAL `androidx.compose.foundation:foundation` jar for the exact version
   this repo pins (check `gradle/libs.versions.toml` → compose-bom → resolve to the exact
   foundation artifact version, same technique round 1 used successfully for the `TextToolbar`
   interface via `javap`) to get the actual compiled default of `isNewContextMenuInitiallyEnabled`
   on Android for that version. Don't guess from web search.
2. If the new system is enabled by default: find the real, current public API for customizing the
   NEW context menu (search the same real Compose Foundation source/jar for whatever composition
   local or provider interface the new system exposes — the AndroidX doc comment says the new
   system "has public APIs", implying there IS a documented customization point; find its actual
   name from the real source/jar, don't guess a name).
3. Decide and implement one of: (a) wire the fix through the new system's real customization API
   instead of `LocalTextToolbar`, or (b) if `ComposeFoundationFlags.isNewContextMenuEnabled` can be
   scoped/overridden narrowly (not just a single global static toggle affecting the whole app) —
   verify whether it's genuinely global-only or has some scoping mechanism — and only fall back to
   forcing it off globally if there's no narrower option, flagging that as an app-wide behavior
   change for the reviewer to weigh consciously (it would also change every OTHER text field in
   Haven, not just this dialog — that's a real, non-trivial trade-off to surface honestly, not
   silently take).
4. This dialog uses Material3 `TextField`, not `BasicTextField` directly — confirm which context-
   menu path Material3's `TextField` actually wires up to for the pinned Compose Material3
   version too (it may not follow `BasicTextField`'s flag-gated behavior identically; verify, don't
   assume it's the same).
5. Still no real device available in this environment — be exactly as honest as round 1 was about
   what's proven vs. reasoned. If genuinely impossible to verify visual rendering without a real
   device/emulator here, say so plainly and say what the NEXT real-device test should specifically
   check, rather than re-claiming confidence that didn't hold up last time.

## Context (round 1, still accurate)

Batin found (real device, 2026-07-23): highlighting/selecting text inside the floating Text
Input dialog (`FloatingTextInputDialog.kt`) shows the selection handles fine, but the native
Android floating action-mode toolbar (Copy / Cut / Paste / Select all) that normally appears
above a text selection never shows up. Every other native text input on the device shows it
normally — only this dialog is missing it.

Root cause (verified, not guessed): the dialog renders its `TextField` inside a Compose `Popup`
(`Popup(properties = PopupProperties(focusable = true))`, `FloatingTextInputDialog.kt` line ~263).
This is a documented Compose limitation — a `TextField` inside a focusable `Popup` has a known
focus-management conflict with the platform `ActionMode`/text-selection-toolbar machinery, so the
toolbar silently never appears (no crash, no error — it just never shows). Source:
https://github.com/JetBrains/compose-multiplatform/issues/2810 (same underlying Popup+TextField
focus conflict, filed against Compose Multiplatform but the same root mechanism affects Compose
on Android). Not switching to `Dialog` to fix this: the design doc
(`docs/plans/floating-text-input.md`, already in this repo) and the maintainer's own device-test
comment on PR #439 confirm the `Popup` was chosen deliberately (isolated pointer-input dispatch —
verified no touch double-consumption with the terminal underneath; a `Dialog` would change that
touch-dispatch behavior and the tap-outside-to-dismiss-without-clearing-draft behavior, which is
already correct and tested). Fix the toolbar without changing the window type.

## Ground truth read before writing this plan (don't skip re-verifying this against upstream/main
before implementing — Haven moves fast, this repo was at v5.83.3 when this plan was written)

- Current file: `feature/terminal/src/main/kotlin/sh/haven/feature/terminal/FloatingTextInputDialog.kt`
  (405 lines on `upstream/main` as of this writing). `TextField(...)` call is the one composable
  that needs the toolbar fix; nothing else in the file changes.
- `git grep LocalTextToolbar` across the whole repo: **zero hits** — no existing custom
  `TextToolbar` implementation anywhere in Haven to copy from. This is new ground, not a
  copy-paste-from-elsewhere job.
- Existing clipboard-access idiom already used elsewhere in this codebase (match this style, don't
  introduce Compose's `LocalClipboardManager` instead — it isn't used anywhere else here):
  `core/toolbar/src/main/kotlin/sh/haven/core/toolbar/KeyboardToolbar.kt` line ~271:
  ```kotlin
  val view = LocalView.current
  val clipboardManager = remember {
      view.context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
  }
  ```
  Use the same `LocalView.current` → platform `android.content.ClipboardManager` pattern here.

## The fix

Compose's public, documented escape hatch for exactly this situation is `TextToolbar` +
`LocalTextToolbar` (`androidx.compose.ui.platform.TextToolbar`, `LocalTextToolbar` composition
local) — verified against the real API reference:
https://developer.android.com/reference/kotlin/androidx/compose/ui/platform/TextToolbar . A custom
`TextToolbar` implementation gets exactly 4 fixed callback slots — `onCopyRequested`,
`onCutRequested`, `onPasteRequested`, `onSelectAllRequested` — the API does not support adding
extra menu items, which is fine here since those 4 are exactly what's missing.

**Verify the exact `TextToolbar` interface shape against the real installed Compose UI version
before writing code** — check `androidx.compose.ui:ui` version pinned in
`gradle/libs.versions.toml` and read the actual decompiled/sources-jar interface (`showMenu`,
`hide`, `status` members) rather than assuming from memory; the interface has changed slightly
across Compose versions in the past (parameter types on `showMenu`, e.g. `Rect` vs `Offset`-based
overloads).

Implementation shape (adjust exact signatures to whatever the verified real interface requires):

```kotlin
private class DialogTextToolbar(
    private val view: View,
    private val clipboardManager: ClipboardManager?,
) : TextToolbar {
    override var status: TextToolbarStatus = TextToolbarStatus.Hidden
        private set

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        // Build a small Compose-based floating menu (a Popup positioned at `rect`,
        // OR — simpler and known to sidestep the same Popup-in-Popup focus issue —
        // a plain Android PopupWindow anchored via `view`) with up to 4 buttons,
        // calling straight through to the passed callbacks. Verify which approach
        // actually renders/dismisses correctly inside the outer floating-text-input
        // Popup before committing to one — this is the one part of the plan that's
        // a genuine implementation risk, not a known-good recipe. Test on a real
        // device/emulator, not just "it compiles".
        status = TextToolbarStatus.Shown
    }

    override fun hide() {
        status = TextToolbarStatus.Hidden
    }
}
```

Wire it in `FloatingTextInputDialog.kt` around just the `TextField`:

```kotlin
val view = LocalView.current
val clipboardManager = remember {
    view.context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
}
val customToolbar = remember(view, clipboardManager) { DialogTextToolbar(view, clipboardManager) }

CompositionLocalProvider(LocalTextToolbar provides customToolbar) {
    TextField(
        value = text,
        onValueChange = onTextChange,
        // ...unchanged...
    )
}
```

`onCopyRequested`/`onCutRequested`/`onPasteRequested`/`onSelectAllRequested` operate on the
`TextFieldValue`'s current selection (`text`/`onTextChange` are already hoisted to the caller per
the existing design — the toolbar callbacks read/write through those, they don't need their own
state) plus `clipboardManager.setPrimaryClip(...)`/`getPrimaryClip()` for the actual clipboard I/O,
matching the existing `KeyboardToolbar.kt` idiom.

## What to verify before calling this done

1. Real device or emulator test (not just compile): open the dialog, type multi-word text, select
   a range by drag, confirm the Copy/Cut/Paste/Select-all toolbar now appears and each button
   actually works (copy → paste back in, cut removes + clipboard has it, select-all selects
   everything).
2. Confirm the toolbar's own popup/menu doesn't visually break or get clipped by the outer
   floating-text-input window's bounds — this is the actual open implementation risk flagged
   above, verify it doesn't recreate a NEW version of the same Popup-focus problem one level down.
3. Confirm existing behavior is unchanged: bracket-paste send path, drag-to-move, resize, TalkBack
   custom actions (move/resize) — this fix should touch nothing except the selection toolbar.
4. Re-run whatever this module's existing Compose UI tests are (check for a
   `FloatingTextInputDialogTest.kt` or similar under `feature/terminal/src/test` /
   `src/androidTest` — verify it exists and what it currently covers before assuming there's
   nothing to run).

## What NOT to do

- Do not switch `Popup` to `Dialog` to sidestep the issue — changes touch-dispatch behavior that's
  deliberate and already tested (see Context section above).
- Do not touch any file other than `FloatingTextInputDialog.kt` (and a new test file if one is
  warranted) — this is a scoped, single-file fix.
- Do not guess the `TextToolbar` interface shape from a general Compose blog post — verify against
  the actual pinned Compose UI version's real interface first.
- Do not commit/push — leave changes as an uncommitted diff on a fresh branch cut from
  `upstream/main` for review before anything ships.
