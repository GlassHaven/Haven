# Changelog

Release notes for Haven, newest first. Each `## vX.Y.Z` section is the body of
the corresponding GitHub Release; a release can't ship without its section
(enforced by `scripts/check-changelog.sh` in CI). The GitHub "Full Changelog"
compare link is appended automatically — don't add it here.

## v5.87.48

- Fixed a crash that occurred when saving a password or passphrase on devices where the secure keystore had lost Haven's credential key. The app now reports the problem instead of closing, and Settings offers to repair the keystore when this has happened (#579, thanks Slayerx96).

- **Saving a credential no longer closes the app when the device keystore has failed** (#579, thanks Slayerx96). Haven encrypts every stored password against a key held in the device's secure keystore. On some devices, that key becomes unusable. Previously, the failure had no error handling, so typing a passphrase and pressing save caused the app to crash. The app now displays a message stating that the keystore would not unlock and that nothing was saved. This is accurate because encryption happens before any data is written.

  The issue was reported on a Reticulum IFAC passphrase, but it was not specific to that field. The same key protects every password Haven stores, so that field was simply where the error was encountered first. Leaving the field empty avoided the crash only because there was nothing to encrypt.

- **There is now a recovery option.** An error message instead of a crash still leaves the app unable to save passwords. Therefore, when the key is permanently gone, Settings → Security & privacy offers to rebuild it.

  All previously stored credentials must be entered again after the repair. This is less severe than it appears because the key that protected those values is gone, making them unreadable already. The repair restores the app's ability to store data rather than losing data that was still accessible.

  This option appears only when it is the correct solution. Some keystore failures are temporary, such as a locked device. In those cases, the credentials are fine and will be readable once the phone is unlocked. Rebuilding the key in that scenario would destroy the existing data, so the repair is offered only for permanent loss.

🔐 **The distinction between "gone" and "locked" is the core of the fix.** Android reports both conditions through the same type of error, but they require opposite responses. One requires the key to be rebuilt, while the other requires waiting until the phone is unlocked. Treating the second case as the first would have turned a lock screen into permanent data loss.

## v5.87.47

- The network scan on a connection can now scan through that connection's jump host. This allows it to find machines on the far side rather than the ones next to your phone.

- **The host scan works through a jump host**. When a connection has one selected, a second button appears next to "Scan Network". This button sweeps the jump host's own network instead of yours. This feature is most useful in the case where the local scan is least useful, such as on a VPN or anywhere the machines you want are not on the network your phone is currently connected to.

  The system asks the jump host which networks it is on and sweeps the one carrying its default route. This detail is more important than it may seem. A developer machine is routinely on four networks at once, including a docker bridge, a libvirt bridge, a compose network, and the real LAN. Sweeping all of them would turn one 254-address scan into over a thousand probes across three networks that were not requested.

  Results are addresses only, by design. Resolving names would ask your phone's resolver about a network it cannot see. A confidently wrong hostname is worse than no hostname at all.

  The jump host needs to be connected first. Dialling it from inside the edit dialog would cause host-key prompts and passphrases to appear on top of whatever you were typing. Therefore, the system asks you to connect it rather than attempting to do so poorly.

🛰️ **The scan probes with an ordinary TCP connect, which is why this was a small change.** Pointing that probe at a SOCKS proxy moves where it originates without altering what it does. Haven already spoke SOCKS over SSH on both of its engines. The work was not in the tunnel. It was in asking the right machine which network to sweep and in not hiding the answers behind a filter meant for something else.

## v5.87.46

- Haven can now pair a computer for wireless adb debugging on its own. It finds the pairing port and asks for the six digits in a notification. This keeps the system's pairing dialog on screen while you read them (#575).

- **Pairing a computer over wireless debugging no longer means hunting for a port** (#575). Android advertises its pairing listener only while the pairing dialog is open. It uses a fresh port every time. This is why a port written down once never works twice. Haven now discovers it live over mDNS while it opens the dialog for you. It asks for the six digits in a notification reply field rather than in a window of its own.

  That reply field matters more than it sounds. The first version of this drew an overlay over the pairing dialog. That approach cannot work. Android's Settings windows set an anti-tapjacking flag that hides every third-party overlay while they are in front. The overlay sat in the window list the whole time and was simply never drawn. Hiding it is precisely what the flag exists to do. This means there was no workaround worth looking for. A notification reply field is drawn by the system instead. The flag does not apply to it. It needs no "display over other apps" permission. It arrives at the top of the screen above the dialog without taking focus away from it.

  What this does not do yet: Haven hands the code to the agent, which runs the pairing command from the computer. Completing the pairing on the device itself needs a key exchange Haven has no implementation of. That is a separate piece of work.

🧭 **The overlay design was ruled out by a single command, and that command should have been the first one run.** Establishing that a permission can be granted is not the same as establishing that the platform permits the thing the permission enables. The first question was asked for days. The second took one line and ended the design immediately.

## v5.87.45

- The terminal keyboard could stop reaching the session after a reconnect, until you disconnected and reconnected by hand. This was a regression from v5.87.43.
- A screen rotation while an agent permission prompt was open counted as a refusal (#576).

- **The terminal keyboard no longer goes deaf after a reconnect.** This is a regression from v5.87.43's HyperOS crash fix, reported the same day, and it is the reason for this release. That fix deliberately takes the hidden input view out of its parents' focus record during Compose's teardown. It relies on the view being re-added, or on the next recomposition, to put it back. When a teardown did neither, the result was silent and self-sustaining. The view believed it held focus, so nothing re-requested it. The parent chain did not record it, so the keyboard delivered to nobody. Rebuilding the session was the only way out, which is exactly the workaround the reporter had found. The view now repairs that disagreement itself, on window-visible and when the keyboard is asked for. The repair is targeted at its own parent rather than a search from the top of the window. This ensures it cannot re-enter the disposing hierarchy that v5.87.43 exists to protect.

  Stated plainly: the sequence that produces the broken state has not been reproduced on a device. This closes the state rather than the path into it, and logs the repair so the next occurrence names its own cause.

- **A rotation is no longer an answer** (#576). Rotating the device while an agent was asking permission denied the request, and the sheet vanished mid-read. Every dismissal was treated as a refusal. This is right for a swipe or a tap outside, but wrong for a configuration change. Android recreates the screen and the sheet goes with it, through the same code path. Worse than the interruption, that phantom refusal was recorded as a deliberate one. This armed the cooldown that suppresses repeated prompts. Consequently, a decision nobody made could silence the retry that followed. Genuine dismissals still refuse.

- **Agent permission prompts now say who asked and why**, and the adb pairing flow can find its own pairing port instead of sending you to hunt for one (#575). This is groundwork. The on-device code box still needs a permission Android withholds from sideloaded apps, and the release notes will say so when it lands.

🪞 **Every defect fixed here was found by someone using the app, not by the tests that shipped alongside it.** The focus regression, the rotation denial, and four more in the pairing work were all reported from a device within hours of shipping. None were logic errors. They were assumptions about the environment and about what the person on the other end is told. The tests pinned the behaviour that was imagined. The device supplied the behaviour that was real.

## v5.87.44

- Terminal crash on HyperOS/Android 16 warm return, round two. The v5.86.8 fix relied on a 100ms assumption that a Xiaomi Mi 17 does not honour (thanks Maksimus).
- The file editor now carries the same Compose interop-teardown guard, on the same code path.

- **The HyperOS warm-return crash fix now holds, regardless of how late the device performs the teardown** (thanks Maksimus, whose second line-by-line trace of the disposal path was as precise as the first). v5.86.8 stopped the terminal's hidden input view from holding focus while the app was away. It handed focus back 100ms after the return, which was intended to be past the frame where Compose flushes its deferred composition teardown. On a Xiaomi Mi 17, that flush arrives later than 100ms. Consequently, the view took focus back before the flush landed, and the crash remained unchanged. Compose runs an `onReset` callback on the same stack frame as the view removal that triggers the fault. The guard now sits there and depends on no timing at all.

  Investigating the report revealed a second defect underneath. The flag used by the July fix to relinquish focus is not safe to touch during a teardown. Android's `View.setFlags` surrenders focus by calling the public `clearFocus()` method. This starts a focus search from the root of the window. This is precisely the call that re-enters the half-disposed Compose hierarchy and throws an exception. The guard now uses the one primitive that does not search. It removes the view from its parents' record of who holds focus and leaves the view's own focus flag alone. Android then re-establishes the chain by itself when Compose puts the view back.

- **The file editor received the same guard.** It is a page of the same pager. Its editor view holds focus for the entire time the app is backgrounded. It had no protection at all. Nobody has reported a crash there. This is the same mechanism on the same code path, fixed before it is reported.

🧿 **Neither defect was found by reading the code.** The timing hole came from a reporter who traced the disposal path line by line on the failing device. The unsafe flag came from a regression test that failed for a reason the fix had not anticipated. Both guards are pinned by tests that fail when the guard is removed. However, those tests do not include a Compose hierarchy. Therefore, they establish that no focus search runs during teardown, not the absence of the exception on a Mi 17. The final step is the reporter's retest.

## v5.87.43

- Fixed a black patch flashing under the incoming screen when swiping away from a terminal with a dark colour scheme and a light app theme (#574 — thanks a8645322)

- **Swiping off the terminal no longer flashes the terminal's background** (#574). To let a dark terminal scheme reach behind the status bar, the app paints the whole window container with the terminal's background while the terminal screen shows. During a swipe that container was still visible under the incoming screen until the transition settled, which with a light app theme read as a black block for the length of the swipe. Non-terminal screens now paint their own app-theme background so nothing shows through mid-transition; the wallpaper see-through setting keeps its translucent pages.

## v5.87.42

- New setting: hide Haven's bottom tab bar on the terminal screen, leaving the Android status and navigation bars visible (#521 — thanks a8645322)

- **The terminal screen can now shed the app's own tab bar** (#521). The existing fullscreen toggle bundled two decisions — hide Haven's chrome and hide Android's chrome — that have no business being the same switch: wanting the clock and notifications while working in a terminal is entirely reasonable. The new toggle (Settings → Terminal → "Hide app tab bar in terminal", off by default) hides only Haven's bottom tab bar while the terminal screen is selected. The swipe gestures that switch screens keep working, the bar returns as soon as the pager settles anywhere else, and the ≥600dp side rail is unaffected. Translated into all 11 shipped locales. Not yet exercised on a device; the reporter's retest is the closing verification.

## v5.87.41

- SPICE mouse motion is paced against the server's acknowledgements, fixing the lag and intermittent stalls left after v5.87.40 made the pointer work (#572 — thanks empanadablues)

- **SPICE pointer messages are now flow-controlled** (#572). The moment v5.87.40 made the relative mouse work, the reporter found the next layer: lag, and stalls that only extra movement cleared. The client had been sending pointer messages as fast as the finger moved and discarding the server's acknowledgements as unknown messages — an unpaced stream into QEMU's tiny PS/2 packet queue that the guest replays late, then chokes on. Sends now claim a slot in an eight-message window; when it fills, the newest position is parked and the acknowledgement handler flushes it, so a drag's final position always lands. Verified against a live PS/2-only QEMU: the server acknowledges every fourth motion and the client now consumes and paces on those acknowledgements.

## v5.87.40

- The SPICE mouse fix, round three — now verified against a live PS/2-only QEMU guest down to the guest's mouse device (#549)
- SPICE sessions now record input events in the connection log, closing the "No detailed log for this entry" gap (#549)

- **The SPICE relative mouse now works, verified end to end** (#549). v5.87.39's parse fix was correct but repaired a message QEMU never sends at connect: the server delivers the initial mouse mode *inside* its INIT message and no separate announcement follows, so the corrected parser sat waiting for bytes that never came — the reporter's immediate "no changes" was accurate, and this time the investigation ran against a live QEMU with a PS/2-only guest instead of stopping at unit tests. Two layers were wrong: the init-carried mode was parsed and dropped, and even stored it would have been lost, because init is handled before the input path wires up its view of the mode. The mode is now cached and replayed when the input path attaches. Verification went to the bottom: the client's relative motion messages were captured hitting QEMU's PS/2 device byte-identically to QEMU's own native input injection. A one-command probe now reproduces that whole check against any PS/2-only QEMU, so this path can never again ship untested against the thing it talks to.

🪞 **The second fix failing the same way as the first would have been unforgivable; the difference is where verification stopped.** Round one verified mode plumbing against a hand-fed mode. Round two verified parsing against hand-fed bytes. Round three put a real server on the wire and read the guest's device driver — and only that level found both remaining defects. The probe stays in the tree because the lesson has now been paid for twice.

## v5.87.39

- The mouse now works over SPICE on PS/2-only guests, for real this time (#549 — thanks empanadablues for the three-device retest that proved the last fix never worked)
- SFTP checks the connection before listing a directory, so a dead link reconnects instead of hanging the browser (#537 — thanks kanazawahere)

- **The SPICE mouse-mode fix now actually reaches the wire** (#549 — thanks empanadablues, whose retest across three devices was the evidence that v5.87.33 had changed nothing). That release taught the client to honour the pointer mode the server announces, and the plumbing was correct — but the announcement itself was being misread. The protocol sends two 16-bit numbers, the modes the server supports and the mode in effect; the client read them as one 32-bit number, fusing a Windows 98 VM's announcement into a value that matched no mode at all. The mode check fell through to absolute positions, which a PS/2 guest silently discards, on every device and every setting the reporter tried. The parse is corrected and a regression test pins the exact bytes that VM produces.
- **SFTP probes the connection before listing** (#537 — thanks kanazawahere, whose PR this is, from diagnosis to the profile-scoped final shape). Opening a directory on a connection that had silently died — a NAT mapping expired while the phone slept, say — hung the file browser until a long timeout instead of reconnecting. The browser now probes the profile's own session first and reconnects a dead one before listing, and the probe is scoped to the profile being opened rather than every connected session, so one unhealthy connection cannot slow the others' listings. This lands alongside the earlier half of the same PR: per-profile keepalive overrides reaching JSch's real setters instead of the config map it never reads.

🔬 **A fix that changes nothing observable was never tested against the thing it fixed.** The v5.87.33 change was verified to select the right message type for a given mode value — but not that the mode value itself was ever right. The reporter's patience bought the second look; the lesson is that "honours the announced mode" needed a test that started from the announcement's bytes, not from a mode already in hand.

## v5.87.38

- YubiKey ECDSA SSH keys (sk-ecdsa-sha2-nistp256) now authenticate instead of failing after the touch (#531 — thanks pixel4696)

- **Security-key ECDSA SSH auth fixed** (#531 — thanks pixel4696, whose "touch works, then publickey fails" report pointed straight at the signature leaving the phone malformed). The two FIDO key types hand back their signatures in different shapes: an ed25519 assertion is already the raw 64 bytes SSH wants, but an ECDSA assertion arrives as a DER structure that OpenSSH expects re-encoded as two SSH mpints. Haven passed the DER through untouched, so every sk-ecdsa signature failed server-side verification while sk-ed25519 sailed through the identical code path. The conversion now happens (and rejects malformed input outright), with the padding edge cases pinned by unit tests. The live round trip against a real server with a hardware key is the reporter's retest.

🔐 **Two key types, one code path, one silent divergence.** The ed25519 flow working made the shared path look proven, but "shared" only covered the framing; the signature payload inside it had a per-algorithm shape nobody was converting. A path is only as tested as its least-tested branch.

## v5.87.37

- SSH "Address family: Auto" now falls back across all resolved addresses instead of gambling on the first (#566)
- The System VM import form no longer clips its architecture chips in landscape (#558)

- **SSH now tries every address a hostname resolves to** (#566). Auto mode used to take whichever address the resolver listed first and hand it to the SSH engine — so a dead AAAA record on a dual-stack mobile network, or one stale entry in a round-robin A set, produced a connect timeout while a working address sat unused in the same DNS answer. Auto now probes each resolved address in order with a short TCP handshake budget and connects to the first one that answers; a single-address answer is passed straight through, the explicit IPv4-only/IPv6-only settings keep their exact old meaning, and if nothing answers the probe the engine still gets the first address so you see its normal connection error, not a fake resolution failure. Both SSH engines share the path.
- **The System VM import form fits landscape now** (#558). A Material dialog window caps its own height on a short screen no matter what the content asks for — six different in-dialog layout attempts are catalogued on the issue, all rendering identically — so the guest-architecture chips clipped in half. On height-compact windows (landscape phones) the form now opens as a bottom sheet, the app's existing pattern for content that owns the short axis; portrait keeps the dialog it always had.

🧭 **When six modifiers change nothing, the constraint is outside the box they modify.** The dialog's height ceiling belonged to its window, not its content — no amount of arranging furniture recovers space the room doesn't have. The fix was never a seventh modifier; it was a different room.

## v5.87.36

- RDP no longer logs every keystroke to logcat (#504 — thanks pawlosck for calling it out)
- Per-profile SSH keepalive settings now actually take effect (#537 — thanks kanazawahere)
- Korean translations polished and missing content restored (#561 — thanks Nergis0318)

- **RDP no longer logs every keystroke** (#504 — thanks pawlosck, who rightly called it out). The per-key wire logging was added as a diagnostic for the stuck-modifier investigation, and it did its job: the log it produced exonerated Haven's input path conclusively, with every modifier combination leaving the wire correctly ordered and every press paired with its release. A diagnostic that logs each key you type is a keylogger the moment the investigation ends, so it is removed outright rather than hidden behind a setting.
- **Per-profile SSH keepalive settings now actually take effect** (#537 — thanks kanazawahere for the find). Setting `ServerAliveInterval` or `ServerAliveCountMax` in a profile's SSH options has been silently inert since the option parser was added: the values were forwarded into JSch's config map, which JSch never consults for those two — they live as session fields behind setters. The overrides now reach the real setters with OpenSSH semantics: the interval is in seconds, 0 disables keepalive, and a value that does not parse leaves the default untouched.
- **Korean translations polished** (#561 — thanks Nergis0318): more natural phrasing throughout, and missing content restored, with key and placeholder parity verified against the English source.

🔑 **A diagnostic earns its keep once, then becomes a liability.** The keystroke log existed to answer exactly one question, and the first complete capture answered it. Everything it could record after that point is cost without return — and this kind of cost lands on users who never saw the issue it was for.

## v5.87.35

- Returning to zellij or tmux no longer repaints the whole screen when the keyboard was open (#554 — thanks paour)
- Dependency updates: Compose BOM 2026.08.00, AppCompat 1.8.0, org.json 20260814, golang.org/x/crypto 0.55.0

- **Returning to a full-screen TUI no longer repaints it** (#554 — thanks paour, whose keyboard-open/keyboard-closed comparison identified the trigger). Backgrounding Haven with the soft keyboard up made zellij visibly redraw every tab on return. The cause was a pair of resizes nobody asked for: Android takes the keyboard down a moment *before* the app is paused, so the terminal briefly became full height and the guest was told to grow; on return Haven restores the keyboard and the guest was told to shrink back. Both signals now cancel out — a grow that looks like a keyboard hide waits long enough to see whether the app is being backgrounded, and one caught mid-backgrounding is held until the return settles, by which point there is nothing to resize. The earlier theory on that issue (a stray refresh keystroke) was wrong, and the evidence that killed it — a byte-level trace showing nothing injected — came from the reporter.

⌛ **The two halves of a round trip each looked correct alone.** Reflowing to the keyboard is right while the user watches; restoring the keyboard on return is right too. Composed across a backgrounding, they became grow-then-shrink — two truthful size reports whose net effect was zero, except that the guest repainted for each. The fix is not to report less truthfully but to wait out the moment when the truth is about to reverse itself.

## v5.87.34

- Declining a key's biometric prompt now stops the connection (#559 — thanks andar1an)
- Connection failures say which half failed — reaching the host, or SSH itself — and against which address (#557)
- The rootfs import field accepts `file://` paths and says so when handed a bare one (#560)

- **Declining a key's biometric prompt now stops the connection** (#559 — thanks andar1an). The prompt itself was honest: press back and the key is never unlocked, never offered to the server. But every kind of failure was flattened into the same "no key" value on its way up, so the connect could not tell your refusal from a key that does not exist — and it did what it does for a missing key, which is carry on with whatever else the profile had: another key, a stored password, keyboard-interactive. On the "try every key" path a decline just removed that one key from the list. A refusal is now its own outcome all the way up, and every connect path — SSH, Mosh, Eternal Terminal, and the jump host under a VNC/RDP/SMB tunnel — reports it and stops before a socket is opened. The near-miss version is guarded too: the first fix's message contained the word "authentication", which the error classifier read as an auth failure and answered by offering the password prompt — a politer form of the same mistake, now pinned by tests on all four paths.
- **Connection failures name which half failed** (#557). "Socket error" covered everything from a typo in the hostname to a firewall dropping the port to the SSH handshake dying. Failures now say whether Haven couldn't reach the host or the SSH exchange itself failed, and name the address it was trying — so a wrong port and a wrong password no longer look identical.
- **The rootfs import field takes `file://` paths** (#560). Pasting a `file://` URI — the form most file managers put on the clipboard — was rejected with a message that named neither what was wrong nor what would work. It's accepted now, and the error for a bare content URI says what kind of path it wants.

🔐 **A "no" that only removes one option is indistinguishable from bad luck.** Haven's biometric gate did refuse — the key stayed locked — but refusal was encoded as absence, and absence already meant "try the next thing". Consent that matters has to be a first-class value, not a gap where a credential used to be; otherwise every layer above helpfully routes around it, each one sure it is being resilient.

## v5.87.33

- **The mouse works over SPICE on older guests** (#549, #543 — thanks empanadablues, who kept testing after the connection itself was fixed). SPICE lets the *server* choose how the pointer is described: a guest with a USB tablet is sent absolute positions, while a guest with only a PS/2 mouse — a Windows 98 VM, say — expects relative movements and silently discards absolute ones. Haven only ever sent absolute, so on those guests the pointer sat perfectly still while the keyboard worked fine. Haven now honours the mode the server asks for, which it had been reading and then ignoring.
- **A failed port-knock or SPA packet is no longer invisible** (#557). These run just before a connection to open a firewall port, and they are deliberately non-fatal — so when one failed to send, the connection carried on and died later as an ordinary timeout, indistinguishable from the network being down. The failure now appears in the connection log with its reason, instead of only in verbose logging that you had to know to switch on first.

🖱️ **Both of tonight's bugs were a message nobody was listening to.** The SPICE server announced how it wanted the mouse described and Haven parsed the announcement, logged it, and dropped it. The SPA packet reported that it never left, and that report went somewhere nobody reads. Neither was a hard problem once seen; both were invisible for months because the thing that knew was not talking to the thing that decided.

## v5.87.32

- **Tapping a wrapped URL now opens the whole URL.** A login link long enough to wrap across nine rows of a narrow terminal opened truncated when tapped on most of its rows, and opened nothing at all on the last few — a URL that looks plausible and then fails at the server. Two internal limits on how far a wrapped link may be followed were both smaller than nine. They exist for a good reason (they stop unrelated lines being glued into an invented link), so rather than raising them, following now continues past them while each row is filled edge to edge with URL characters — something ordinary prose cannot be, since a single space disqualifies a row.
- Dialogs in the desktop manager no longer lose what you typed when the screen rotates. Previously a rotation closed them outright and discarded the contents: a half-filled rootfs import, an app-window definition, custom mount paths, a desktop's setup password and port. The one deliberate exception is the drive-unlock dialog — losing a half-typed encryption passphrase is the safer outcome.

🔗 **A link that is nearly right is worse than one that is obviously broken.** The truncated URL carried a valid scheme, host and path, and failed only at the server on a missing parameter — so the terminal looked fine and the website looked broken. The rows that opened nothing were the honest failure; the rows that opened something were the bug.

## v5.87.31

- **Fixed the F-Droid build**, which has failed at clone time since 2026-08-16 and left that channel stuck on v5.87.22 (#525, thanks connesc — the build-monitor link found it twice now). A submodule of `wayland-android` was pinned to a commit that exists only on a personal mirror, and F-Droid's build server reuses its build directory, where `git submodule init` never overwrites an already-registered submodule URL — so it kept fetching from the original remote, which legitimately does not have that commit. The pin now points at upstream, which is reachable whichever of the two URLs a cached clone happens to use.
- System VMs can now boot **arm64 guests**, not just x86_64 (#326). `-M virt` is a different machine rather than a flag: it needs UEFI firmware (installed automatically), virtio-gpu instead of VGA, and USB HID — without which a VNC viewer connects to a guest it cannot type into. The guest architecture is chosen when you import an image and shown beside it in the list, because a disk image does not record what CPU it is for, and the wrong target does not error, it simply never boots.
- Acceleration is now **reported rather than assumed**: Haven probes `/dev/kvm` and says in plain words why a VM is emulated. On ordinary arm64 phones there is no `/dev/kvm` at all — the vendor hypervisor owns EL2, and rooting does not change that — so guests run under emulation. KVM also cannot accelerate a foreign-architecture guest, so an x86_64 image on an arm64 phone is emulated no matter what.
- The system-VM import dialog no longer loses what you typed when the screen rotates.

🧱 **A pin that only one machine can resolve is not a pin.** The broken submodule passed every local check and every CI run, because a fresh clone reads the current URL and a fresh clone was all anyone ever did. The one machine that reuses its checkout — the one that actually ships the app to F-Droid users — kept the old address and quietly failed for a day. Reproducibility is not "it builds here"; it is "it builds somewhere that does not already have your assumptions cached".

## v5.87.30

- Mail-rule actions queued for approval no longer bury the notification shade. Instead of one card per queued action — ten emails meant ten stacked notifications, and tapping any of them did nothing, because they carried no tap action at all — there is now a single "N mail actions awaiting approval" notification on a dedicated Mail rules channel. It updates its count in place, opens the approval queue when tapped, and clears itself when the queue drains.
- The approval queue gained **Approve all** and **Reject all** with a progress bar. Approving previously cost one tap and one IMAP round trip per action, which didn't scale past a handful; the bulk run executes the same per-action path sequentially, leaves failures queued, and reports the split.
- Notifications raised by MCP agents now open Haven when tapped instead of ignoring the touch.
- Zellij sessions no longer receive an injected Ctrl+L when the keyboard hides or the app goes to background (#554, thanks paour). This was a redraw workaround from before zellij repainted resizes properly; testing on-device against zellij 0.44 showed the bare resize repaints every row cleanly, so the injection — which echoed `^L` into running commands and force-cleared the screen on every background/foreground cycle — is gone entirely. The toolbar's `^L` macro preset remains for a manual redraw.
- RDP logs every slow-path keyboard event as it is sent, to pin down keystrokes that only register after Tab/Caps Lock/Shift on VirtualBox guests (#504, thanks pawlosck) — this is the diagnostic build for that investigation.

📵 **An unprompted byte is never helpful twice.** The Ctrl+L was sent with good intent — repaint the rows the keyboard uncovered — but software that types into your terminal uninvited becomes indistinguishable from a bug the moment conditions drift. The workaround's reason retired versions of zellij ago; nobody told the workaround.

## v5.87.29

- Fixed the unstoppable "zombie" desktop session (#550, #551, thanks sugerpersion — the screen recording made this findable). A Custom-command desktop whose command died at startup kept a green "running" row that Stop couldn't clear, while the actual failure vanished without a word. Four layered causes, all fixed: the dead command no longer leaves the VNC server holding the session open; the container now tears down every process when the session script ends (previously a leaked dbus-daemon kept it alive invisibly — one leaked per desktop start, forever); the orphan cleanup on Stop had never actually matched container-hosted processes; and a command that dies moments after the port check now reports as a startup failure carrying its own error output instead of silently disappearing.
- Stop is now unconditional: whatever happens during cleanup, the session entry always clears — force-killing Haven is never the way out again.
- Agents can now set the Custom (X11) desktop command via MCP (`custom_desktop_command`), which is how these fixes were verified end-to-end on a real device.

🧟 **A session that can't die is worse than one that can't start.** Every one of the four bugs was invisible alone; stacked, they made a corpse with a green light. The fix that matters most is the boring one: when the thing you started ends — however it ends — say so, and let go of everything it held.

## v5.87.28

- Fixed SPICE connections in release builds (#549, thanks empanadablues — first to report it). Every release build's code shrinking was mangling the generated SPICE bindings, killing the connection 7 milliseconds in, before a single byte reached the server — SPICE has likely been broken in every release build for months while debug builds worked perfectly. One missing keep rule (its RDP twin existed, which is why RDP worked). Reproduced against a local QEMU, fixed, and re-verified on-device: connected, all channels up.
- SPICE connection failures now log the exception class and stack trace to the connection log, not just a message — this bug's only symptom was the word "null", which is what made it invisible for so long.
- For Windows 9x-era VMs (#543): with SPICE now working, its native relative-pointer mode is the recommended path — VNC's protocol can only carry absolute positions, which old guests' mouse acceleration desyncs.

🧪 **A debug build is not the product.** The product is what the shrinker ships. This failure lived exclusively in release builds, where reporters live and test rigs usually don't — the fix rides with a release-build smoke gap now visibly on the list.

## v5.87.27

- Fixed runaway scrolling when swiping up in the terminal (#542, thanks a8645322 — whose exact repro recipe and "it feels time-based, not distance-based" observation made this findable). Two integer round-downs were feeding each other: the drag converts pixels to lines by flooring, and a sync effect then snapped the pixels back down to that floored line — so on an upward drag, every ~4 pixels of finger cost a full line, about ten times the intended speed, until the view slammed to the bottom. Downward the flooring is conservative, which is why only one direction ran away. The sync now only reconciles genuinely external moves.
- Measured before/after on-device: a 100px swipe-up used to empty 16 lines of scrollback and snap to the live edge; it now moves 3 lines, symmetric with swiping down.
- RDP now follows server redirection, which makes GNOME Remote Desktop's Remote Login connect instead of dying with a redirect error (#117, thanks MrTomiCZ — whose captures from a real GRD handover supplied the entire wire recipe). The redirect hands the client a routing token and one-time credentials; Haven reconnects to the same endpoint replaying both. One hop only, and servers that forbid following are respected. Live acceptance against a real GRD is still pending — this is the build to test.
- Typing on German QWERTZ, French AZERTY, and UK keyboards now reaches scancode-only RDP servers (VirtualBox VRDP) correctly (#504 follow-up). Haven announces the phone's keyboard layout, and the server interprets key positions through it — but keys were being sent at their US positions, so German phones typed y for z and punctuation scattered. Each of these layouts now carries its own full key map, including the AltGr rows (@, €, brackets) and the 102nd key; characters only reachable through dead-key composition fall back to the unicode path rather than silently poisoning the next keystroke.

🔁 **Two round-downs make a ratchet.** Each floor was individually harmless — one quantizes a position, one tidies a sync. Composed in a feedback loop they turned every pointer event into a full-line click downward, and pointer events arrive at 90 per second.

## v5.87.26

- Two diagnostics that turn open bug reports into readable data. For GNOME Remote Desktop's session-redirection black screen (#117, thanks MrTomiCZ), the redirect error now logs the packet's parsed structure — which fields the server populated and the routing token the eventual reconnect must replay, with the one-time password reduced to a length. For the runaway-scroll report (#524 follow-up in #542), the gesture classifier's one-line-per-swipe outcome record now survives release builds, so `adb logcat -s HavenGesture` shows exactly which path claimed a swipe.
- Fixed the F-Droid build of the two previous releases: the new Reticulum engine's JVM bindings demanded a specific Java 17 toolchain, which F-Droid's single-JDK build server cannot satisfy (fdroiddata!45740). The bindings already target JVM 1.8, so the pin bought nothing — dropped.
- Otherwise no behaviour changes — the rest is log lines.

🔬 **A bug report is a measurement problem.** Both of these issues stalled at the same wall: the reporter could see the symptom, but the build they were holding couldn't record the cause. The cheapest fix Haven can ship is the instrument.

## v5.87.25

- RDP to a VirtualBox VM no longer starts as a permanent black screen after the guest has sat idle (#422, thanks pawlosck). The cause was hiding in plain sight for months: an idle Windows guest turns its virtual display *off*, VirtualBox's RDP server only ever transmits changed regions, and the one kind of input that wakes the display is a keyboard event — pointer motion is filtered as noise. Touch input is all pointer-class, so on Android there was no way to wake it. Haven now watches the first 1.5 seconds of a session; if nothing at all has been painted, it sends a single silent Ctrl tap and the full screen streams in. If the server painted anything, the nudge never fires.

🖥️ **The screen wasn't broken — it was asleep.** Ten seconds of continuous mouse movement: nothing. One Ctrl tap: full repaint in 150 milliseconds. Every desktop RDP client accidentally sends that key-shaped "wake up" via its connect-time input burst, which is why only touch users ever saw the void.

## v5.87.24

- The toolbar's Swipe mode now repeats: hold a swipe and the arrow key repeats until you let go — drag a bit further to speed through, reverse direction mid-hold without lifting (#524, thanks a8645322). Before, each swipe sent exactly one arrow, which made cursoring across a long shell line an exercise in wrist endurance. Tab-switch gestures are suppressed while Swipe mode is latched, so a horizontal swipe means ← / → and never "jump to the Files tab".
- RDP now types accented characters on AltGr-overlay layouts by synthesising real AltGr scancode sequences (#504, thanks pawlosck). Polish programmers layout ships first: ą ć ę ł ń ó ś ź ż (both cases) reach servers that only accept scancodes — VirtualBox's VRDP being the reporter's case, where these letters previously vanished. QWERTZ/AZERTY-style base-remapped layouts are a separate, tracked follow-up.

⌨️ **Input is a conversation with a stubborn listener.** Both fixes are about meeting the other side where it is: a VRDP server that refuses Unicode gets the raw scancode dance a physical Polish keyboard would send; a touchscreen that has no key-repeat hardware gets repeat synthesised from what a finger naturally does — staying put.

## v5.87.23

- VNC through an SSH tunnel now connects to the VNC host you configured (#538, thanks connesc). The tunnel's remote target was hardcoded to the SSH server's own loopback — fine when the VNC server *is* the jump host, wrong for every server behind it. The field is now honoured verbatim, with loopback kept only when it names the jump host itself.
- Mosh over a Tailscale or WireGuard tunnel now works with hostnames and MagicDNS names, not just literal IPs (#539, thanks drauh — who arrived with the complete root-cause analysis and the fix design). The UDP stream's destination is now the address the tunnel actually connected to during the SSH bootstrap; and when no usable address exists, the connect fails with a clear message instead of retrying forever while mosh-server times out on the other side.
- Groundwork for a second, Rust-based Reticulum engine (Prns) landed in the build — inert in this release, nothing user-visible yet.

🕳️ **Two tunnels, one lesson.** Both fixes are the same bug wearing different hats: code answering a question ("where do I connect?") from the wrong namespace — the phone's resolver instead of the tunnel's, the jump host's loopback instead of the profile's field. A tunnel is its own little world with its own names; this release makes Haven ask the tunnel.

## v5.87.22

- Hard links inside local Linux environments now survive and behave (#536 discussion). The emulation used to park a link's real data next to the *source* file, so when a program deleted its temp directory — the standard lockfile dance of git, dpkg, and Nix — the data vanished and the other name was left dangling. Payloads now live in a per-environment store that outlives any directory you delete, links are readable through every name (they previously failed with "No such file or directory" while `ls` looked fine), and failures report their real errno instead of a blanket "Operation not permitted" — the mystery behind years of intermittent dpkg errors (#324, #328, #329).
- Concretely: Nix's flake commands now work out of the box on a fresh install — no more `could not find repository at ~/.cache/nix/tarball-cache`, no manual `git init` workaround. Verified on-device end to end, and guarded by a new emulation test suite that runs on every CI build.

🔗 **An emulated hard link is a promise about durability.** Android denies apps real hard links, so Haven's proot emulates them with symlink chains — and a chain is only as durable as its weakest directory. Moving the real bytes into a stable per-environment store (guest path `/.l2s`) makes the promise hold: any name you keep is a name that still opens tomorrow.

## v5.87.21

- Attach → "Send a file" now reliably pastes the uploaded path at the terminal cursor (#535, thanks kanazawahere). The upload itself always worked, but the path injection raced the navigation back to the terminal screen and usually lost; it now rides the same injection channel the QR-scan paste uses, bracket-paste wrapped when the shell has it enabled.
- If no terminal tab is active when a paste lands (file path or QR scan), Haven now says so in a toast instead of silently dropping the payload.

## v5.87.20

- Fixed a crash on connect (#533): when a long output line soft-wraps and its first row scrolls into scrollback, any terminal resize could read memory from before the screen buffer and kill the app — on some devices reliably, on most silently. Reproduced under AddressSanitizer with the reporter's exact backtrace; the repro now runs as a permanent regression gate on every build.

## v5.87.19

- Haven now reads its own death certificates (#494). When Android kills Haven in the background — a force stop, the app freezer, low memory — the next launch says so on the Connections screen: when, and by which mechanism, including the sneaky one where Developer options' "Select debug app" points at Haven and Android force-stops it. Agents get the same records via a new `get_process_exits` MCP verb; no entry at the disconnect time means the process survived and the network was cut instead.
- If your battery-optimization exemption for Haven gets switched off behind your back — some ROMs quietly reset it when an app updates — Haven notices the change and re-offers the exemption with an explanation, instead of staying silent because you once tapped "Not now". A new "Don't ask again" makes the quiet permanent if that's what you want.

🪦 **You can't fix what you can't attribute.** Every "my sessions disconnect in the background" report starts with the same three-way ambiguity: the process was killed, or the network was cut under a live process, or only a listener died. They look identical from the outside and need three different fixes. Android has handed apps their own exit records since Android 11; Haven read them only for native crash tombstones. Now it reads the kills too, tells you about the ones that took your sessions with them, and exposes the history to agents — turning the opening question of every such report into data the app answers itself.

## v5.87.18

- The Swipe key's arrows now follow your finger literally: swipe up sends ↑ (walks command history back), swipe down sends ↓ — reversed from v5.87.14, where the mapping came from scrolling conventions and felt inverted (#524). The automatic swipe-to-arrows behaviour inside full-screen apps like vim and less is unchanged.
- Each arrow now takes a deliberate ~9mm of swipe travel in that mode (4× the scrolling quantum), so history steps one entry at a time instead of flashing past (#524).

## v5.87.17

- Your saved SSH key works again after opening USB drives. Haven mints a private key for each drive it opens in a VM, and those keys wrongly joined the "try any saved key" pool — a handful of stale drive keys would burn through a server's auth-attempt limit ("Too many authentication failures") before your real key was ever offered. Drive keys now stay out of the pool, and ones minted by earlier versions are cleaned up automatically on launch.
- FIDO2 security keys (`sk-ssh-ed25519@openssh.com`, e.g. YubiKey resident keys) can now be offered for connections like any other key (#531): the per-key "Offer for connections" menu entry appears for them, and the automatic key offer includes them alongside your software keys.
- The Keys screen groups those per-drive VM keys under their own "USB drive keys" section, collapsed by default, instead of interleaving them with the keys you created.

🔑 **Every key you offer costs an auth attempt.** An SSH server counts each offered-and-declined public key against its attempt limit (OpenSSH defaults to six), so what sits in the auto-offer pool matters. This release fixes both directions at once: keys that should never have been offered (the ephemeral per-drive VM keys, useful only to their own drive's loopback session) are out, and keys that should have been offerable but weren't (FIDO2 security keys, which sign on the token itself) are in. Software keys are offered first, and a security key's touch/PIN prompt only appears once a server has actually accepted that key's offer — so adding a YubiKey to the pool doesn't nag you on servers that don't know it.

## v5.87.16

- A USB device that stops responding — a flaky card reader, or one drawing more power than the phone's port can supply — no longer freezes Haven when you plug it in or unplug it. The stuck device is now handled off the UI thread, so the app stays responsive even while the device itself has wedged.
- Physically unplugging an open USB drive now tears down its background VM and export on its own, instead of leaving them running until you close the drive by hand.

🔌 **When a USB drive stops answering, the app shouldn't freeze with it.** Haven opens a plugged-in USB drive by handing it to a small Linux VM, and it used to close the device connection on the UI thread when the drive was pulled. If the drive had stopped responding at the kernel level — a failing reader, or a high-capacity card browning out an unpowered USB-C port — that close would block, and the whole app would hang (an ANR) on both plug and unplug. All of that USB lifecycle work now runs off the UI thread: the app stays responsive and simply reports the drive as gone, even while the device is wedged. A physical unplug now also unwinds the drive's VM and export by itself, and the keep-alive that pokes an open drive backs off after repeated failures instead of hammering a dead device. A device that has stopped answering is a hardware problem Haven can't fix — but it no longer takes the app down with it.

## v5.87.15

- RDP now tells the server your keyboard layout (from the phone's language) instead of always claiming US English — Windows, xrdp and KRDP set the session layout from it. VirtualBox-style servers ignore it either way.
- RDP video decoding now asks the phone's chipset for its vendor low-latency mode — on chipsets that honour it, this can cut the per-frame wait substantially. An experiment in the open #477 investigation; the frame numbers in your logs will say whether your chip is one of them.

⌨️ **The client that always claimed a US keyboard.** Every RDP connect announced keyboard layout 0x0409, US English. Servers that build the session's input layout from that announcement — Windows, xrdp, KRDP — would hand a Polish user a US layout no matter what the guest was configured for. Haven now maps the device's language to the matching Windows layout identifier (Polish, UK English, pt-BR vs pt-PT, Swiss/Belgian/Canadian French and more); anything unlisted still announces US, exactly as before. Surfaced by the #504 investigation: the reporter's VirtualBox guest was innocent (it ignores the announcement), but the announcement itself was wrong for every server that doesn't. (#504, @pawlosck)

🎞️ **Asking the chipset for its fast lane.** #477's diagnosis found the display lag is pipeline *wait*, not decode work — and the standard Android low-latency request was already on, included in the reporter's measured 88–118 ms per frame. Many chipsets ignore the standard request and honour only their own vendor switch, so those are now set too, along with realtime codec priority. Harmless where unrecognised; measurable where honoured — the per-frame numbers in the EGFX log line are the verdict. The structural fix (decoding off the session loop so input stops queueing behind frames) is the next stage. (#477, from @skeezmoe's measured logs)

## v5.87.14

- Zooming or rotating no longer silently destroys scrollback content — wide history lines now wrap when pulled back onto a narrower screen instead of losing their tails.
- An RDP/VNC/SPICE/SMB profile's "Route via" tunnel or proxy choice now actually saves — it used to revert to "None (direct)" every time.
- A locked Ctrl or Alt now unlocks itself when your last session ends, instead of ambushing the next connection.
- New optional "Swipe" toolbar key: while on, swiping sends ↑/↓ even at a plain shell prompt — command history without arrow keys on the bar.

📜 **The scrollback that quietly stopped existing.** When the terminal gains rows — a zoom out, a rotation — it pulls lines back out of the scrollback to fill them. The engine asked for each line at the old width, deleted it from the store on handover, then kept only what fit the new width: on any resize where rows grew while columns shrank, every wide line's tail was destroyed, permanently and invisibly. "Scrollback isn't fully scrollable" was literal — the content was gone. The engine now asks at the width it can accept, and the store hands back one row's worth while re-queuing the rest as a soft-wrapped continuation, so wide lines wrap across the boundary instead of ceasing to exist. Reproduced and fixed under test against the real terminal engine: a 70-character line survives where it previously came back as its first 40. Not yet re-verified on a device — that retest is what #478 is waiting on. (#478, reopened by @skeezmoe's "the issue still exists" — they were right)

🔀 **The routing picker that never saved.** The "Route via" picker (WireGuard/Tailscale tunnel or SOCKS/HTTP proxy) is shown for six connection types, but only the SSH and EMAIL save paths ever persisted what it wrote — for VNC, RDP, SPICE and SMB a picked tunnel or proxy silently reverted to "None (direct)" on save, every time. All four now go through one shared save helper. (#527, @VaneEcho)

🔒 **The lock that outlived its sessions.** Lock Ctrl, exit your last session with Ctrl+D, connect somewhere else later — and the new session started with Ctrl still locked. The lock now dies with the last tab; closing one tab among several leaves it alone, since the toolbar state is shared and a surviving session may be mid-use of it. (#522, @a8645322's exact sign-off sequence is the regression test)

👆 **Swipe as arrow keys, everywhere.** Haven already turns swipes into arrow keys inside full-screen apps and scrolls its own scrollback at the shell — automatically. But no automation can know you want *command history* at a plain prompt: the application state is identical either way. The new Swipe toolbar key (add it from toolbar customisation) latches that choice on, freeing the four arrow-key slots for other keys. Vertical only for now — horizontal swipes still switch tabs. (#524, argued for and won by @a8645322)

## v5.87.13

- With a physical keyboard on an RDP session, letters, digits and punctuation reach the remote again — v5.87.11's focus fix had quietly cut the only path that carried them.
- The fullscreen menu chip can now be held and dragged to any edge of the screen, and it remembers where you parked it.

⌨️ **The letters the focus fix left behind.** v5.87.11 fixed hardware Enter and Space driving Haven's own menus by taking keyboard focus away from a hidden text field — but that field's input path was also the only thing converting letter and digit presses, so those started reaching the remote as nothing. The bug report was the key table read back verbatim: numpad, F-keys, modifiers, Enter, Esc and Backspace (all explicitly mapped) kept working, while letters, digits and Space (unmapped) died. Every main-row key is now sent as its own scancode, with your real Shift and AltGr forwarded as keys so the remote composes shifted — and AltGr — characters itself. The new tests fail against v5.87.12's mapping and pass against this one; it has not yet been watched against a real VirtualBox guest, which is the retest #504 is waiting on. (#504, diagnosed from @pawlosck's logcat — the decisive clue was a drop counter reading zero)

🖥️ **Park the menu chip where you like.** v5.87.12 moved the fullscreen chip to the top centre and out of the way of the remote's window controls; now you can hold and drag it to any edge anchor — corners included — and it stays there for that connection across restarts. (#528, @pawlosck)

## v5.87.12

- The fullscreen menu button moved to the top centre, so it no longer covers the remote's own minimise/restore/close buttons — and it fades out when you're not using it.

🖥️ **The menu button that sat on the remote's close button.** In a fullscreen RDP session, Haven's menu chip lived in the top-right corner — exactly where Windows keeps minimise, restore and close — so the remote's own window controls were unreachable underneath it. It now sits top-centre, the spot desktop RDP clients reserve for their connection bar for precisely this reason, and after five idle seconds it fades to a ghost so it stops competing with the remote's content. It returns to full strength the moment you open it. Watched working on a device: chip top-centre with the corner clear, dimmed on idle, menu opens and exits fullscreen. (#528, from @pawlosck's "move or hide hamburger menu")

## v5.87.11

- With a physical keyboard on an RDP session, Enter and Space now go to the remote machine instead of opening Haven's own menus — one stray Enter could previously even drop you out of the session.

⌨️ **The keyboard that drove the wrong computer.** Attach a real keyboard to an RDP session and some keys acted on Haven itself: Enter opened the navigation menu, Space activated buttons, and in the worst case a keystroke threw you out of the session entirely. The cause was that nothing on the session screen ever claimed keyboard focus unless you toggled the *soft* keyboard — which is the one thing you never do with a real keyboard attached — so every keypress went to whatever the system had focused instead. The session screen now takes focus the moment it opens, keeps hardware keys for the remote no matter which of its own controls is focused, and re-takes focus when you tap the screen or dismiss the soft keyboard. Reproduced and verified on a device against a test server: before, Enter+Space kicked Haven off the session with nothing reaching the remote; after, the session stays put and both keys arrive. (#507, pinned down by @pawlosck's "when I can't write, the menu opens every time")

## v5.87.10

- A locked Ctrl or Alt now stays on until you tap it off — it no longer dies after one keypress while still showing as locked.
- The "delete key?" title now shows the count in Bengali, French, Hindi and Portuguese instead of assuming "one" means exactly one.

🔒 **The lock that spent itself.** v5.87.8's double-tap Ctrl lock worked for exactly one keypress: the first Ctrl+C landed, the second sent a bare `c`, and the key sat there blue and inert. Keyboard input passes through two hand-off points and only one of them knew the lock existed — the other cleared Ctrl unconditionally after use, which is also why the indicator (a separate flag) never noticed. Fixing it also settled what "locked" means: v5.87.8 released the lock by itself after two keystrokes; the requester's follow-up said it plainly — locked means every keypress carries the modifier until you tap it off — and that is now the behaviour. Tap for one keypress, double-tap to hold, tap again to release. Watched working on a device: two Ctrl+C's, then a plain `c` after unlock. (#522, caught the day it shipped by @a8645322)

🌐 **Plural titles that assumed "one" means 1.** In Bengali, French, Hindi and Portuguese the "one" plural category covers more than the number 1, so the bulk-delete confirmation title ("Delete this key?") could sit over a count it didn't show. Those four now say the number. (found by the nightly lint run)

## v5.87.9

- The keyboard now really does come back when you return to Haven — the previous fix remembered correctly but Android refused the request; this one was watched working on a device before shipping.

⌨️ **The keyboard restore, round three — witnessed working this time.** v5.87.8 fixed the *recording* half of this bug: Haven correctly remembered that a keyboard was up when you switched away. But the restore it then issued was refused. Android ignores a keyboard request from a window whose input field isn't focused, and the terminal's input view deliberately gives up focus while Haven is in the background (a guard against an Android 16 crash on warm returns) — so the request had nothing to attach to. The half-second flash @paour reported was that refusal happening in real time. The restore now goes through the terminal's own show path, which takes its focus back before asking. Confirmed over adb against both reported gestures — app switcher and straight back, another app and back — with the keyboard still up seconds later, and a keyboard you dismissed yourself staying down. (#515, pinned by @paour's retest and one "tantalizing half second")

## v5.87.8

- The on-screen keyboard comes back when you return to Haven, instead of staying away and leaving the arrow buttons doing nothing.
- Ctrl and Alt now apply to the extra keys above the keyboard, so Ctrl+End, Ctrl+Home and Ctrl+arrow finally do what they say.
- Tap Ctrl twice to lock it on, for shortcuts you need to press twice.
- The address of the machine you connect to over RDP no longer reaches the system log.

⌨️ **The keyboard that didn't come back.** Switch away from Haven and switch back, and the keyboard stayed down — and with it the arrow and control buttons above it, which is how this was first reported. The fix shipped in v5.87.2 asked "is the keyboard up?" at the moment Haven was moved to the background, on the assumption that the answer was still intact there. It isn't: Android takes the keyboard away *as part of* moving the app out, so the answer was always "no" and there was never anything to restore. Haven now records *when* the keyboard went away instead, and treats a disappearance in the last fraction of a second before backgrounding as the system's doing rather than yours. (#515, diagnosed from @paour's logcat)

⌃ **Ctrl that didn't reach half the keyboard.** The extra keys above the keyboard — End, Home, Page Up/Down, the arrows — sent themselves with no modifier attached, whatever Ctrl or Alt you had tapped. Ctrl worked for letters typed on the keyboard proper and nowhere else, so Ctrl+End did nothing but End. All of those combinations now work.

🔒 **Ctrl that stays on.** Tap Ctrl twice and it locks: it applies to the next two keypresses instead of just one, then releases itself. A third tap releases it early, and a locked key is outlined so you can see it's on. Ctrl+C twice to get out of something — the case this was asked for — is exactly one lock. (#522, specified in detail by @a8645322)

🛡️ **One address the log scrubber missed.** v5.87.7 stopped connection details reaching the system log, but an RDP server's address still got through, because the library underneath prints an address as a list of numbers rather than in the usual dotted form — and a scrubber looking for `192.168.1.100` cannot match `[192, 168, 1, 100]`. Both spellings are now scrubbed. (#477)

## v5.87.7

- Fixed the crash where Haven closes itself a second or two after connecting, or when a full-screen program starts.
- Your keystrokes are no longer written to the system log — including anything typed into a `sudo` prompt.
- Clipboard contents are no longer written to the system log either.
- Crash reports now say what actually went wrong, instead of arriving empty or corrupted.

💥 **Haven closing itself.** Two reports, one cause. The terminal library Haven is built on calls `abort()` when it cannot work out where the cursor should go while re-laying out the screen — and `abort()` ends the app instantly, with no error and nothing to catch. It happens during a resize, which is why it struck about a second after connecting, or as a full-screen program laid out its interface, and why it looked like one particular machine was to blame when it was really the size the terminal settled at. (#517 and #526, diagnosed from @Test-Account666's crash dump and @Bearmancer's logcat)

🛡️ **Five more of those, and worse.** The same library aborts on unexpected text, control characters, malformed escape sequences and nonsensical scroll regions — all of it data the *server* sends. Any server, malicious or merely buggy, could have ended your session by emitting the wrong bytes. All six now recover and carry on.

🔑 **Keystrokes and clipboard in the system log.** Every character typed was written to the device log, so a password typed at a `sudo` prompt was captured in plain text; the same went for 50 characters of anything copied in a remote desktop session, the text queued for the AI agent to type, and the arguments of guest commands. All fixed. Logs that people paste into bug reports were the exposure. (#518, found by @skeezmoe)

🔍 **Crash reports that say something.** One reporter's showed ten crashes with no detail at all; another's arrived with a third of it destroyed, because Haven read the system's binary crash record as if it were text. Both fixed: reports now name the signal, and carry the abort message, the app version and the device. (#526, #517)

## v5.87.6

- The status bar now matches the terminal instead of clashing with it — no more white strip above a dark terminal.
- Fixed a stray "%" in the background-opacity help text.
- Groundwork for the keyboard-closing-on-return bug: Haven now records why, so a log can say which cause it is.

🎨 **A white strip above a dark terminal.** If your app theme is light but your terminal colours are dark — Classic Green, Ocean, anything with a dark background — the top of the screen showed a band of app-theme colour with the terminal starting abruptly beneath it. The status bar was following the *theme*, while the terminal follows its own colour scheme, so the two disagreed by design. The status bar now takes its colour from the terminal that's actually on screen, and picks light or dark icons from how bright that colour really is — so a light scheme like Solarized Light gets dark icons rather than invisible ones. (#523, reported by @a8645322)

🔤 **"Below 100%%" in the opacity help text.** A stray escape that was never being processed, in English and all eleven translations.

⌨️ **Why the keyboard closes when you come back to Haven.** Not fixed yet — and worth being straight about that, because the fix I shipped for this in v5.87.2 has been running ever since and doing nothing. Rather than guess a second time, Haven now records whether the keyboard was up when it was sent to the background and whether it asked for it back, which separates the two possible causes. They need opposite fixes, so this is the step that decides which one to write. (#515, from @paour's testing)

## v5.87.5

- Number-pad keys now work in remote desktop sessions — previously they did nothing at all.
- If SSH connections feel slow, Haven now records where the time went, so a log can say which part is slow.

⌨️ **The number pad did nothing in remote desktop sessions.** Not the digits, not the arrows, not Enter, not the operators — every key on the pad was dropped before it left the phone. Invisible if you use a touchscreen, which is why it lasted; obvious the moment you attach a real keyboard. Fixed. (#507, from a report by @pawlosck)

⏱️ **Haven now records how long each part of an SSH connection takes.** This is a diagnostic, not a fix: @frebib reported connections that used to take under a tenth of a second now reliably taking more than one, and there was no way to tell which part had got slow. (#519)

Each connection now writes one line saying how long it spent looking up the address, preparing the session, and doing the SSH handshake — so a log can point at the culprit instead of just saying "it was slow". It deliberately contains no hostnames or usernames, so it is safe to attach to a bug report.

**If SSH connecting feels slow to you**, updating and sending a log would genuinely help. The line begins `connect timing:`.


- Haven no longer writes hostnames, usernames or clipboard contents to the device log.
- If you have shared a log with anyone, it may contain more than you intended — worth checking.

🔒 **Haven was writing private details into the device log.** Connection details — username, hostname, IP address, port — and, worse, **anything copied to the clipboard from a remote session** were being recorded in Android's log. A password taken from a password manager, an API token, a private key: if it went through the clipboard in a terminal session, it was written down in plain text. (#518, thanks @skeezmoe)

**What this does and does not mean.** Since Android 4.1 no other app on your phone can read Haven's log, so nothing was quietly harvesting this. The exposure is **sharing**: the log is captured by `adb logcat` and by system bug reports, Haven displays it in Settings, and Haven asks you to send logs when reporting a problem — including through the crash-report feature added in v5.87.3. The person who reported this had to edit their own details out of a log before it was safe to attach.

**If you have shared a Haven log with anyone** — attached one to a bug report, sent one to someone helping you — it is worth going back and checking what was in it. Sorry; it should not have been there.

Haven still logs enough to diagnose problems. Hostnames and names you chose are replaced with a short marker that stays consistent within one run, so a support log still shows which connection did what without saying where it went. Clipboard contents are simply never logged.

A test now fails the build if anyone reintroduces this, in any of the fifty-odd places it could happen.


- If Haven closes unexpectedly, the crash report is now waiting for you in Settings → Connection log.
- Fixed a crash that a remote program could trigger just by setting a window title.

🐞 **Haven can finally tell you why it crashed.** Two open bug reports have been stuck for days on the same missing piece: the crash log stops at the moment of the crash, right before the part that says *where*. That was Haven's fault. It recorded its log from inside itself, so when it died the recorder died too, and the report the system writes afterwards arrived when Haven was no longer there to read it.

Haven now picks that report up the next time it starts, and shows it in **Settings → Connection log** with a **Copy report** button. If Haven closes unexpectedly, please open that screen and paste what you find into a bug report — it names the exact place the crash happened, which is usually the difference between a fix and a guess. (#509, #517)

Two caveats worth stating. It needs **Android 11 or newer**; on older versions the system simply doesn't offer this, and Haven says so rather than showing you an empty screen and letting you think nothing happened. And it only covers crashes of this specific low-level kind — the sort that close the app instantly, which is exactly the sort that has been hardest to diagnose.

🔡 **Fixed a crash triggerable by a remote program.** When a program on the far end set a window title — or sent one of several other routine terminal messages — containing text Haven couldn't interpret as valid Unicode, Haven closed instantly, with no error and nothing to catch. A window title from a Windows console not set to UTF-8 does this as a matter of course.

Such text is now shown as `�` instead. A terminal that displays a replacement character for a mis-encoded title is behaving correctly; one that vanishes is not.

This was found while investigating #517 and is **not** that crash — that one is still open, and the report above is now the fastest way to solve it.


- Toolbar arrows and other repeating keys no longer stop working after you hold one.
- The keyboard comes back when you switch back to Haven, instead of always hiding.
- Three new terminal colour schemes: Campbell, Modern Dark and Modern Light.

⌨️ **Toolbar arrows could stop working until you closed the session.** Hold an arrow to repeat, tap another one, and the first arrow was dead — it still lit up when you touched it, but nothing reached the shell. Only closing and reopening the session brought it back. (#515, thanks @paour)

Two separate faults caused it, either one on its own enough. Haven was reading the wrong field from the touch event, so once a second finger was involved it never saw the release; and the flag that tells a tap from the tail of a hold was cleared in a place that a fast tap could skip entirely. Both are fixed, and the key-repeat logic now has tests covering the exact sequence that was reported — it had none before, which is how this shipped.

Arrows are what got reported, but Home, End, PgUp, PgDn and custom symbol keys behaved the same way, as did the equivalent buttons on the VNC and RDP screens. All fixed together.

⌨️ **Haven remembers whether the keyboard was up.** Android hides the soft keyboard when an app goes to the background, and nothing brought it back — so returning to a session always found it down, even though you left it up. It now restores what you had, and survives Android killing Haven while it's away. (#515, thanks @paour)

🥽 **The virtual keyboard no longer covers the screen when a real keyboard is attached.** On a Meta Quest 3 with a physical keyboard, every keypress raised the on-screen keyboard over the session. Haven was explicitly overriding Android's own rule that a usable hardware keyboard suppresses the soft one. You can still raise it deliberately from the toolbar. (#511, thanks @sae13)

🎨 **Three more terminal colour schemes**, for anyone who wants a dark theme whose default text is plain grey rather than tinted: **Campbell** (Windows Terminal), **Modern Dark** and **Modern Light** (VS Code). Palettes taken from the upstream projects rather than approximated. Note that the 16 ANSI colours only apply if "Apply scheme palette" is switched on in settings — it's off by default so that full-screen terminal programs keep their own colours. (#516, thanks @connesc)

📄 **Release pages now say what the two downloads are.** Every release lists a full APK and a smaller Terminal one per CPU, and nothing on the page explained the difference. Each release now carries a short guide. (#514, thanks @jeyjai)

## v5.87.1

- Backups now include your authenticator entries. They never did before — restoring onto a new device silently lost them.
- Haven no longer shows settings for features a build doesn't include.
- The lighter Terminal download is now 35 MB, down from 54 MB.

🔐 **Your backups were missing your authenticator codes.** Haven's encrypted backup carried connections, SSH keys, known hosts, port-forwards, tunnels and settings — but not the TOTP entries from the Keys screen. Restore onto a new phone and they were simply gone, with nothing to say so.

That is the worst thing in there to lose. A connection can be retyped and a key regenerated; an authenticator secret means going back to every service and enrolling again by QR, if it even lets you.

They are included now. Old backup files still restore exactly as before — they just don't have the entries in them, because they were never written. **If you keep backups, make a fresh one.**

📦 **The lighter build is a lot lighter: 35 MB, against 54 MB last release and 75 MB for the full app.** The largest thing left in it was the Go library behind cloud storage — but the same file also carries Tailscale, WireGuard and the Proton mail bridge, so removing it would have taken three things with it. It's now built twice, and the Terminal download gets the copy without rclone.

So the Terminal build loses **cloud storage** (rclone remotes) on top of the desktop and media features. Tunnels and mail are unaffected. The full build is unchanged. (#510, thanks @paour)

🔌 **Settings stopped offering what a build can't do.** The Terminal build still listed remote-desktop resolution, GPU stack, compositor shell command and media file extensions — settings for libraries it doesn't ship. They're hidden when the feature isn't there, and the connection screen no longer offers cloud storage in a build without it.

Haven also stops claiming those features to a connected AI assistant. It answered from a fixed list before, whatever the build actually contained.

## v5.87.0

- Fixes SSH connections failing with a NullPointerException on the alternative sshlib engine.

🔑 **SSH connections that died mid-handshake on the sshlib engine now work.** A reporter on a RedMagic 11 Air found that connecting failed with a `NullPointerException` during authentication — working on v5.86.50, broken from v5.86.51. Their log had everything needed, which is the only reason this was found and fixed the same day.

The cause is a good illustration of why release builds break in ways debug builds never do. Haven's release build runs an optimiser that shrinks the app partly by flattening thousands of classes into one unnamed package. sshlib's Ed25519 component asks for its own package name while it starts up — and a class with no package has no name to give, so it got null and threw.

It only happens on devices where Android's own Ed25519 support isn't usable and sshlib falls back to its own, which is why it hit one reporter and not everyone.

The fix keeps that one class's name intact, and it has been added to the check that runs on every release build and asserts these classes survive — so a future change to the optimiser rules can't quietly bring it back. Debug builds don't run the optimiser at all, which is exactly why a check tied to the release build is the only thing that would catch it. (#513, thanks @Slayerx96)

📦 **A round number for the two-download split.** The lighter *Haven Terminal* build arrived in v5.86.53 as an ordinary patch release, which undersold it. This version number marks it. Nothing about the split changes here — the full build and the terminal build are both on the [releases page](https://github.com/GlassHaven/Haven/releases), F-Droid carries the full one, and 6.0.0 stays reserved for when the alternative SSH engine becomes the default (#58).

## v5.86.53

- Haven no longer offers connection types a build can't actually run, and reports what it supports honestly.
- GitHub now also carries a lighter 55 MB build without the desktop and media features. F-Droid keeps the full one, so nothing changes here.

📦 **There are now two Haven downloads on GitHub, and you pick.** The full one is unchanged and is what F-Droid carries. Alongside it is **Haven Terminal** — the same app with the remote-desktop clients, the native Wayland desktop and the media tools left out. It is **55 MB against 75 MB**, and if you use Haven for SSH, terminals, files and storage, you lose nothing you were using.

What it drops: RDP and SPICE, the native Wayland desktop, and media conversion, preview and streaming (which also removes video previews in the file browser). What it keeps: everything else, VNC included — that client is written in Kotlin, so a Linux desktop you run in the guest and view over VNC still works.

The files are named `haven-<version>-<abi>-terminal-release.apk`. Both are signed with the same key, so you can install either over the other without losing your connections, keys or Linux guest. They carry the same version number, though, which means an updater won't offer to move you between them — switching is a deliberate download.

If you are on F-Droid, nothing changes and nothing is asked of you. (#510)

🔌 **Haven stops offering things it can't do.** The new-connection screen listed RDP and SPICE regardless of whether that build included them, so on the lighter build you could get three screens into creating a connection before meeting "native library failed to load". Those entries are now shown only when the build can honestly run them. Connections you already have still open and still show their own type.

The same honesty reached the agent interface: a connected AI assistant asked Haven what it supports and was told "rdp, spice, ffmpeg" from a fixed list, whatever the build contained. It now answers from what actually shipped.

And an error message that was quietly wrong: a missing Wayland desktop always said it "requires an arm64 device". True when the library is missing because your phone isn't arm64 — misleading on an arm64 phone running a build that simply omits it. It now says which of the two happened.

## v5.86.52

- The download is 9.3 MB smaller — 84.4 MB to 75.0 MB on arm64.
- Release notes now start with a summary like this one, so there is something short to read.

📦 **The same app, 9.3 MB less to download.** A reporter pointed out that an 80 MB download every few days adds up, and asked for a stripped-down build. Measuring the APK first turned up something better: two of its largest files were nearly the same file twice.

Haven ships `ffmpeg` and `ffprobe`, the two programs behind media conversion, previews and streaming. They were 23.7 MB and 23.6 MB — and almost all of that was one shared body of codec, format and filter code, compiled into each of them separately. Building that code once as a library both programs link against leaves 345 KB and 167 KB of actual program.

Nothing about what the app can do changes. Every codec, filter and container is still there, and the conversion path was exercised on a phone — x264, x265, MP3, Opus, scaling and media probing all encode and read as before.

That is 11% off the download for everyone, including people who use the desktop features. The stripped-down build the reporter asked for is a bigger change and is still being looked at; this was the part worth doing first. (#510)

📄 **Release notes you can read in ten seconds.** The same reporter noted the notes are long enough that nobody reads them. They now open with a few bullets, and F-Droid's "What's New" shows those instead of the first 500 characters of an essay. The reasoning stays underneath for anyone who wants it — several bug threads link back to it. (#510)

## v5.86.51

💥 **Fixed: Haven crashed after saving in `crontab -e`** (#509, thanks @ash-945). The crash was a native one — the C++ terminal being freed by the garbage collector's finalizer while a call into it was still running. Reproduced and fixed in termlib: every native call now holds the lock for its whole duration, and taking the lock away makes the crash come back, which is the evidence that it is the right fix.

Terminals whose session has ended are now closed rather than left to the finalizer, so the race has less to happen in.

🔑 **Authenticator entries can be renamed.** Enrol one client on several routers and you get entries identical down to the issuer, identifiable only by trying a code somewhere. SSH keys have had rename since #231; TOTP now does too.

📂 **The Keys screen starts collapsed, except the codes.** Authenticator codes are what you open that screen to read; the rest is reference material. If you deliberately expand everything, that is remembered.

🐚 **"New plain shell" now works on SSH tabs, not just local ones.** It opens a shell with your session-manager preference bypassed — the point being to escape a multiplexer, so it belongs where the multiplexer is: an SSH profile wrapped in tmux.

The terminal changes are not yet exercised on a device; the unit tests for the modules they touch pass.

## v5.86.50

🔒 **Your Windows account name and your server's address are out of the remote-desktop logs.** A reporter has now redacted his own account name by hand from three separate log attachments, and asked for the address and port to go too. Haven had been redacting the username since v5.86.46 — at its own log lines. The ones that kept leaking belong to the RDP library underneath, which prints whole protocol messages when logging is turned up, and two of those messages carry the logon name and the server address verbatim.

Redacting each place a name might appear is a game you lose eventually, so this does it at the other end: every line the RDP engine writes now passes through a filter that removes the values Haven already knows — your username, domain, password, the host you typed, any proxy host, and the address it resolved to. Anything the library starts printing later is covered without anyone having to notice it.

What survives is shape rather than substance: `<ipv4>` or `<hostname>`, and a port only as "non-default". That is deliberate — a name that had to resolve versus an address that did not is the difference between a DNS failure and a routing one, and that distinction is what these logs are read for.

The reporter's log is the test. The two lines he flagged are now fixtures in the test suite, and removing the filter makes them fail. His remaining 8,407 lines were swept for anything else identifying and came back clean.

Still in the clear, and worth saying rather than leaving to be found: non-RDP connections log their host and port unredacted. Same leak, different part of the app.

🔍 **A remote-desktop log line that read like a measurement when nothing had been measured.** The performance probe added last release printed `alloc+copy of 0KB took 0us` when it had not run at all — which reads as "copying is free", the exact opposite of what it means. Against a Windows 11 server it never runs, because Windows sends ordinary desktop updates as progressive images rather than H.264 even with AVC420 switched on.

It now omits the clause instead of printing zeros. The test that was supposed to catch this only checked the path where the probe *does* fire, so it could never have failed — that is fixed too.

🔊 **Guest audio latency is now measured rather than argued about.** A reporter proposed three progressively larger rewrites of the audio path to cut delay. Before building any of them, this release logs the two numbers that decide which one is even worth building: the size of the playback buffer in milliseconds, and whether the loop spends its time waiting for the guest to produce audio or waiting for the phone to play it.

Reading the code first already turned up one number worth knowing: the playback buffer's floor is 341 ms at CD-quality stereo. No change of transport can get underneath that. No behaviour changes here — this is the measurement that picks the fix.

## v5.86.49

💾 **USB flash drives that never worked on some phones now get their VM engine installed.** A reporter's drive failed with "VM didn't reach a login prompt in 420s" — 1.7 seconds after starting — and did the same on three different drives, which is what ruled the drives out.

Haven runs a small Linux VM to talk to a USB drive, and installs that VM engine into your Linux guest the first time it needs it. There were two ways into that boot, and only one of them ran the install. His went through the other, every time, so the VM launched on a guest with no engine and died instantly — reported as a seven-minute timeout, because that is what the wait reported for everything.

The check now lives in the step that actually starts the VM, so nothing can reach it without going through the check. If the install then fails, you get your package manager's own reason instead of a timeout.

🔑 **SSH output loss on the experimental engine is fixed.** If a command's output arrived while nothing was reading it yet, it could be discarded when the channel closed — whole stdout or stderr gone, or a large transfer cut short. A shell that wrote and exited quickly could lose everything, which showed up as a blank terminal instead of the error explaining why.

Fixed upstream in the SSH library and picked up here. Measured on the same machine before and after: 1000 commands lost 11 stdouts, 12 stderrs and truncated 3 of 200 large transfers before, and none after. The workaround Haven carried for it, and the test retry that hid it, are both gone — a retry on a real defect is a temporary measure or it is a blindfold.

This affects the opt-in alternative SSH engine only; the default engine was never affected.

🔍 **Hardware-key sign-in failures now say why.** When a key held in OpenKeychain — and through it a YubiKey or similar — refused to sign, Haven's log showed the request going out and then nothing at all, followed by a generic authentication failure. Three unrelated causes look identical from inside that silence. The reason is now recorded, along with which prompt in the sequence it reached: permission, key chooser, or PIN and tap.

That is a diagnosis rather than a cure. It is what makes the next report solvable instead of another round of guessing.

## v5.86.48

⚡ **Remote desktop colour conversion is roughly three times faster, and the next log will say where the rest of the time goes.**

A reporter's KDE session at 2560x1440 was running at 6.6 fps, and his log carried the timings that split the frame up. 68 ms of every frame was going into converting the decoded picture to screen pixels — a step that had already been moved out of Kotlin and into Rust, and was still the second largest cost in the frame.

The reason turned out to be the shape of the loop rather than the arithmetic: it appended the result one byte at a time, which at his resolution is 14.7 million append operations per frame, each one re-checking whether the buffer needed to grow. Writing into the buffer directly instead measured **24.5 ms → 7.9 ms** on a desktop, built the same size-optimised way the shipped library is. The output is byte-for-byte identical — the colour tests pass unchanged.

Those are desktop numbers, and a ratio rather than a promise. What it is worth on a phone has to be measured on a phone.

🔍 **A measurement, not a fix, for the largest remaining cost.** The same log showed the hardware decoder taking 6–8 ms and the frame packing 2–5 ms, inside a round trip that Haven measured at 83 ms. So around 72 ms per frame is spent handing the frame between Haven's Kotlin and Rust halves rather than doing anything with it.

There are two plausible reasons for that, and they call for opposite fixes. Rather than guess, this release measures the one thing that separates them — what it costs *your* device to allocate and copy that many bytes — and prints it alongside. If you have been sending remote-desktop logs, the next one answers it.

## v5.86.47

⌨️ **Two keyboard and mouse bugs in remote desktop, both found by one reporter's measurements rather than his symptoms.**

**Keys repeating forever, and buttons needing twenty presses.** Every discrete input — key down, key up, button press, button release — was dispatched on its own background task from a *pool* of threads. Nothing guaranteed the order they ran in, so a key release could reach the guest ahead of its own press. A guest that receives release-then-press is left holding the key down: it auto-repeats until some other key arrives, which is why pressing Tab appeared to "fix" it. The same inversion on a mouse button gives a click the guest never sees. Pointer *movement* was unaffected — it is a stream of positions where a swapped pair is invisible — which is exactly what the reporter observed and what identified the cause. Input now goes out in the order it was made.

**AltGr typed nothing on non-English layouts.** All four right-hand modifiers — Alt, Ctrl, Shift and Win — were being sent as their left-hand twins. A reporter ran `showkey` on his guest's console and read back scancode 56 for AltGr; 56 is 0x38, which is *left* Alt. On a Polish layout that meant AltGr+o produced nothing instead of ó, because the guest had been told he pressed a modifier that composes nothing. Right Ctrl, Alt and Win are now sent E0-prefixed as the separate keys they are, and right Shift as its own code.

This fixes what Haven sends. Whether an accented character then appears still depends on the guest having that keyboard layout loaded.

## v5.86.46

🔒 **Haven's logs no longer contain your account name** — and if you have ever attached one to a bug report, it did.

A reporter on #477 deleted three log attachments mid-investigation after noticing his Windows account name was in every one. He was right, and it was in more places than he found: **six**, across remote desktop, file sharing and hardware-key authentication. Haven asks people to attach these logs to public issues, so anything written into one is published.

Remote-desktop logs now record the name's *shape* instead of the name — its length, and whether it is an email-style or domain-style login. That much is kept on purpose rather than blanked: a bug earlier this year turned out to be the authentication library cutting `me@example.com` short at the `@`, and "does this name contain an @" was the question that identified it. Two different names of the same shape now produce identical text. File-sharing and hardware-key logs drop the name entirely.

**Passwords were never in there.** They are not logged, and the underlying protocol library deliberately leaves them out of its own diagnostic output. The account name was the exposure — and it was enough to stop someone sharing the evidence needed to fix their problem, which is the part that actually cost something.

This is not a setting. An option would leave the unsafe behaviour one tap away, and someone would post a log from it. If redacting ever costs a diagnosis, the fix is to add the specific detail that case needs, the way the email-style hint above was added. (#477)

## v5.86.45

🖥️ **Windows remote desktops stop looking pixelated** — and the honest version of this is that v5.86.43 caused it.

That release stopped Windows hanging up on the modern graphics pipeline. It worked — a reporter's session went from 24201 old-style updates to none. But the modern pipeline had two defects sitting in it that nothing could reach while every Windows session was falling back to the old one. Both are now fixed, and both were found in his logs rather than here.

**Part of the picture was being thrown away.** Windows sends some regions as a *difference* against the previous version of that same region — 137500 of them in one session. Haven did not implement that and skipped them, leaving the older, coarser version of each region on screen. That is what "pixelated" was. They are now decoded.

**Cached tiles were being discarded.** When Windows reconfigures the display mid-session, Haven was throwing away its cache of tiles the server still believed it had. The server then said "redraw that cached tile here" 221 times and nothing was drawn, leaving each of those rectangles holding whatever was underneath.

The second one is worth a note on how it was found: I suspected it a day earlier and **could not confirm it** — my own Windows machine only ever reconfigures the display at the start of a session, before the cache holds anything, and a fifty-second test showed zero misses in over a thousand cache operations. It needed a *second* reconfiguration arriving mid-session with a full cache, which his log had and mine never did.

Difference decoding is checked against real Windows output, which is the only place it exists — the open-source server everything else here is tested against never produces it. The decoded frame matches a fresh full repaint to within rounding: of 7068 differing pixels, 72% differ by 8 or less, and every larger difference falls in five tiles out of 527, all in the taskbar — the clock and weather widget, which genuinely changed between the two captures. It has **not** been compared pixel-for-pixel against another decoder. (#477)

🔁 **A single network blip no longer kills a release build** — the last release failed fetching one pinned source archive:

```
curl: (35) OpenSSL SSL_connect: SSL_ERROR_SYSCALL
```

with a retry already configured on that download, having retried zero times. `curl`'s retry option covers a narrower set of failures than it reads like — timeouts and certain HTTP codes — and a TLS handshake failure is not among them. So the retry that existed specifically to absorb network flakes could not absorb this one. Measured rather than assumed: against a deliberately failing endpoint the old flags give up in 0 seconds, the new ones take 4. Applied to all three places Haven downloads pinned sources.

## v5.86.44

🎞️ **The slow remote desktops: the biggest cost is gone from where it was** — a reporter's logs finally showed where a frame's quarter-second actually went, on a 1920×1080 H.264 session:

| | |
|---|---|
| the phone's video decoder | 9–25 ms — the real work |
| converting its output to screen colours | 27–109 ms |
| handing the finished frame from Java to the native side | 87–112 ms |
| drawing it | 1–3 ms |

About 25 ms of a ~250 ms frame was doing anything useful. The two biggest items sit on the same path and shrink for the same reason, so this is one change rather than two: the colour conversion now happens on the native side, and what crosses between the two is the video decoder's own output rather than the finished picture — **3.11 MB instead of 8.29 MB per frame**, so there is 2.67× less to allocate and copy every time.

The drawing step is untouched. At 1–3 ms it was the other suspect, and measuring it is what ruled it out — worth saying, because it is the one that could have been "optimised" for no gain at all.

The bar for the change was **identical pixels**, not similar ones, so the new conversion is checked against output captured from the old one — including a full-brightness-range frame compared by digest, because the first check turned out to be too small to notice a one-off rounding difference.

**What is not established: whether this makes it faster on a phone.** It cannot be measured here — the only server that speaks this codec needs a desktop session this machine does not have. The per-frame report now breaks the conversion out separately, so the next log will say plainly whether the move paid off. If it did not, that will be visible immediately rather than assumed. (#466, #477)

🔌 **A USB drive that fails instantly no longer claims it waited seven minutes** — a reporter on GrapheneOS got "the VM didn't reach a login prompt in 420s" **1.7 seconds** after plugging a flash drive in, which sent them looking for a slow boot instead of a crash.

The wait behind that message ends for two unrelated reasons — the deadline passing, or the helper VM dying — and reported the deadline either way. Meanwhile the VM's own output, the one thing that would identify the crash, was being captured and shown to nobody.

It now says which of the two happened, how long it actually took, and quotes the VM's last words. This does not fix the underlying failure; it makes the next report able to say what the failure is. (#506)

## v5.86.43

🖥️ **A remote desktop stops throwing away part of the picture** — Haven refused any screen update whose image was *taller* than the area it was meant to fill, while happily accepting one that was wider. One VirtualBox session was discarding **53915 updates — 51% of everything the server sent**, which is enough for a window to sit there looking frozen while the clock beside it keeps ticking.

Nothing justified the difference. The code behind that check already handled a taller image correctly: it takes only as many rows as the destination needs, and for a bottom-up image it reverses first, so the rows it keeps are the top of the picture. It was ready to do the right thing and was being stopped at the door. The reference client, FreeRDP, does not check either dimension — it decodes at the image's own size and copies out just the part it wants.

Verified against the path it touches rather than only in tests: the same VirtualBox desktop paints the same 941551 pixels before and after with zero discards, and two consecutive captures differ only in the 124×72 box holding the clock.

Whether this is what that reporter was hitting is **not** established — their exact geometry has never been captured, and it could still be one of the other two rules. What is fixed is a rejection that was wrong on its own terms. The reporting added in v5.86.41 will say which. (#422)

🤖 **Two things an AI agent could not see about Haven, it now can** — the desktop-install progress it polls now says *which* desktop is installing rather than only that something is, and it can switch on the per-session transport tracing that fills the connection log.

The second one mattered more than it sounds: the decode breakdown, the negotiated graphics capabilities and the discarded-update detail behind the three open remote-desktop investigations exist *only* in that log, and an agent could read the log but had no way to turn on the thing that writes it. Every one of those diagnostics had to be run by hand. (#502, #466, #477, #422)

## v5.86.42

🖥️ **The stop button on a VNC desktop actually stops it now** — stopping a desktop kills the launcher and then sweeps up the VNC server, which survives on its own. That sweep looked for the server by asking the system for a process list and filtering on the display number — but that listing prints process *names* and no arguments, and the display number is only ever an argument. So it matched nothing, on every device, on every run, since the day it was written. It found nothing to kill and killed nothing, which is exactly the "I press terminate and nothing happens" reported.

The equivalent sweep for the other desktop type had already been moved off process names for a related reason; the same reasoning had simply never been carried across. Both now look in the same place. Stopping display 1 also no longer risks taking 10 and 11 with it. Raised by @sugerpersion on #501. (#501)

🐧 **A shell in the Linux guest is bash, if the guest has bash** — it was always the minimal shell, which on Debian is dash and on Alpine is busybox, even on a guest with bash sitting right there. Haven now asks the guest which it has. Distros without bash are unaffected and behave exactly as before. Raised by @sugerpersion on #501. (#501)

🖥️ **Starting one Linux desktop no longer makes all the others look like they started too** — the install progress said which *step* was running but not which desktop it belonged to, so every row in the list showed the same spinner and it read as all of them having been launched at once. Each row now answers for itself. Install buttons still go inactive across the board while any install runs, because there is only one package database and a second install would collide with the first — but that is now a separate thing from the spinner rather than the same flag doing both jobs. Raised by @sugerpersion on #502. (#502)

⏱️ **A desktop install can no longer wedge forever** — one step compiles a VNC server from source in the guest, and it waited for that with no limit of any kind. Worse, it waited for the *output pipe* to close rather than for the command to finish, so a stray background process still holding that pipe kept Haven waiting long after the work itself was over — which matches the report exactly: the install sat there while nothing at all was running inside the guest.

It now waits on the command, with a 20-minute limit. Long, deliberately: on a phone this genuinely is a multi-minute compile and cutting a working build short would be worse than the bug. Every other step is untouched and still waits as long as it takes — putting a limit on a package install would turn a slow connection into a failure. This step was always best-effort, so a limit costs nothing. Raised by @sugerpersion on #503. (#503)

🔑 **A key held in OpenKeychain is no longer offered where it cannot be used** — its stored bytes point at a key inside OpenKeychain rather than being key material, so handing them to the SSH library could only ever fail, and did, once per connection. The rule that should have caught it existed and was right; a second copy of it elsewhere had lost half its meaning. Found by @onatio22 on #487, whose log showed the rejection sitting between two connection attempts. The reconnect problem reported alongside it is not fixed. (#487)

## v5.86.41

🖥️ **Windows remote desktops get their modern graphics pipeline back** — with H.264 turned on, which has been the default since 22 July, Haven told a Windows server it could do H.264 and nothing else about how it wanted the screen sent. Windows responded by hanging up on the modern graphics pipeline half a second into every session, and the whole connection then fell back to the old bitmap method for as long as it lasted. No H.264, no progressive refinement, no ClearCodec — the very things the last several releases have been improving.

Nothing announced this. The picture still arrived, just by the slowest route available, which is why it read as "Windows is noticeably slower than other clients" rather than as a fault.

Found in a pair of logs from @skeezmoe on #477: same phone, same computer, two sessions minutes apart — one ran at 33 to 51 frames per second, the other sent twenty-four thousand old-style bitmap updates instead. Reproduced against a Windows 11 machine here and narrowed to a single bit in a single capability word: asking for H.264 *on its own* gets the channel closed, and asking for it alongside any other option is accepted. Why Windows refuses the one and not the other is genuinely unexplained — the message is byte-identical apart from that word — so this ships as a measured behaviour, not as a theory.

This may also be the reason @ZGLinus saw an entirely black screen with H.264 on back in July (#418). Unconfirmed on their Windows version. KDE and other Linux servers were never affected — they always accepted it. (#477, #418, #425)

🔎 **A remote desktop that discards half the picture now says so** — a VirtualBox session on #422 threw away 53915 screen updates, 51% of everything the server sent, and the log recorded only "invalid declared destination" over and over. It named neither the region nor which of three rules rejected it, and over half of that reporter's 24 MB log was that one line repeated, burying everything else in it.

It now reports the region, the sizes involved and the rule that failed, once and then every five hundredth. No visible change — this is what makes the next report solvable. Haven also now records which graphics settings a session actually ran with, after two logs turned out to be labelled one way and negotiated another. (#422, #477)

## v5.86.40

🖥️ **Windows remote desktops look right at last — the haloing around text is gone** — with H.264 turned off, a Windows desktop sends each part of the picture roughly first and then sharpens it over the next few messages. Haven drew the rough version and threw every sharpening pass away, so text kept a dark halo around every letter and smooth areas came out blotchy. That is the "lots of artifacts" reported on #496.

Haven now decodes the sharpening passes, and this is on by default.

It was off because nobody had ever checked it against a real Windows desktop, and the fear was that decoding it wrongly would look worse than not decoding it at all. So it was checked: connected to a Windows 11 machine, an *idle* desktop turned out to send more than a thousand sharpening messages in thirty seconds, and dropping them is exactly what produced the haloing. Decoding them gives clean text, no errors, and a picture that matches what the desktop actually looks like.

The cost was measured rather than hoped for: about twice the decoding work — from a small base — and roughly 12 MB more memory at this screen size. Along the way this also fixed a leak that would have arrived with it, where that memory was never released when a remote desktop changed resolution. (#496, #418)

⌨️ **Haven no longer pretends to send characters a server won't take** — VirtualBox's remote desktop accepts only ordinary key presses, not the separate mechanism Haven used for characters with no key of their own, like accented letters and emoji. It was discarding them, and Haven had no idea: nothing failed, nothing was logged, the characters simply vanished.

Normal typing was never affected — letters, digits and punctuation go the other way and always worked. But now the unusable ones are recognised and reported instead of disappearing silently. They still cannot be delivered to that kind of server; Haven just stops pretending otherwise. (#422)

🔎 **Groundwork for the slow remote desktops** — a reporter's measurements finally showed where the time goes on a laggy 1080p connection: decoding each frame, not drawing it. Drawing takes under 3 milliseconds; decoding takes 120 to 240. So the drawing-speed work of the last few releases was never going to help them, which is worth saying plainly.

This release adds the measurement that narrows it further — separating the video decoder's own time from the colour conversion afterwards — so the next fix can be the right one rather than a guess. No change you will notice yet. (#466, #477)

🖥️ **A remote-desktop compatibility fix, corrected** — v5.86.38 taught Haven to accept a message whose stated length is smaller than the message itself, which is what VirtualBox sends. Review of the same change upstream found it was too permissive: it also accepted a message claiming *zero* length, which is malformed rather than merely miscounted. Narrowed. VirtualBox is unaffected. (#422)

🖥️ **Linux desktop graphics keep working after a system update** — Haven builds a patched graphics driver inside the Linux guest for GPU acceleration. A guest system update cannot delete it, but it could leave it stale — silently paired with a newer driver it no longer matches — and nothing rebuilt it. Now the version is recorded and a mismatch rebuilds. Raised by @sugerpersion on #441, whose objection was half right and found a real bug. (#441)

## v5.86.39

🔑 **Keys from OpenKeychain can be imported again** — a reporter with a YubiKey could not add one: "The key provider refused the request", a couple of seconds after the prompt opened, and nothing in the message to act on. Nothing was wrong with their key, their card, or their permissions — they had already checked all three, which is what made this findable.

Two faults, both Haven's.

**Haven was throwing away the answer.** When OpenKeychain needs to ask you something, it hands Haven a prompt to show, and when you have answered it, it hands back Haven's own request *with what it learned added to it* — the key you picked, or the result of unlocking the card. That returned request is the answer, and it is what the next call has to carry. Haven kept only "did that come back OK?" and dropped the rest. So it asked again with the same empty request, and OpenKeychain, quite correctly, asked the same question again.

**And Haven only ever answered one question.** It handled a single prompt and treated the next one as a refusal — though OpenKeychain routinely asks more than once, and asks something different each time: permission to talk to Haven, then which key you want, then the PIN and a tap on the card.

Together those made the import impossible to complete, and made the failure look like a flat refusal with no explanation: a "please ask the user" reply carries no error with it, so once Haven had decided it was a failure there was nothing to report and it fell back to a generic sentence.

Both are fixed, and not just for picking a key — the passphrase and card-unlock prompts answer by the same route, so signing needed it too.

Separately, when a provider does refuse for a real reason, Haven now says what it was — "that key has no authentication subkey", "no key with that id", "incompatible API version" — instead of the same generic sentence every time, and an unrecognised reason prints its code rather than disappearing.

Honest limit: **not verified on a device.** The sequence is read off OpenKeychain's own source rather than guessed, which is better than it was, but nobody here has run it against a real card. (#487)

🖥️ **A remote-desktop warning pointed at a settings screen that does not exist** — when Haven skips picture-refinement data it cannot decode, it says so, and tells you which setting turns that decoding on. It named "Settings → RDP". There is no RDP section — the switch lives under Diagnostics. Anyone who hit the warning and went looking would not have found it. (#496, #418)

📊 **Verbose connection logging now admits it covers RDP** — the setting's description listed SSH, Mosh and ET. It has covered RDP since March, and RDP is now where it matters most, because that is where the frame timings for a slow remote desktop turn up. (#477)

## v5.86.38

🖥️ **RDP on VirtualBox: the connection now completes instead of failing at the last step** — a reporter on v5.86.37 could not connect at all. The session got all the way through the handshake and then stopped on the final message of it, with "not enough bytes: received 18, expected 26".

Nothing was missing. That message is the tail end of a check comparing the size a message *claims* against the size it *actually decoded to* — the same misleading error that sent the last two releases down the wrong path. VirtualBox's server states the length of that final message as 18, which counts its two headers and forgets the 8 bytes of content following them. All 26 bytes had arrived.

v5.86.36 taught Haven to skip a message like that and carry on, but only once a session was running. This one arrives while the connection is still being set up, before there is a session to carry on with — so the connect failed outright, every time. The fix therefore sits in the RDP library itself, where it covers both: a length that under-states a message is now believed no more than it deserves to be, and the message is used as received. A genuinely short message is still rejected, because that fails earlier for a different reason.

Confirmed by rebuilding both reporters' exact messages — this one and the 8565-byte one from the earlier report — and watching them go from their reported errors to decoding cleanly. Both ship as tests. (#422)

📡 **Mosh no longer gives up on reconnecting after one bad moment** — a reporter's log showed Haven correctly noticing a dead session, scheduling a reconnect, trying it fifteen seconds after the network came back, failing to look up the host name, and then never trying again.

The host name was a router-local one that had worked when the session was first opened. It failed once, most likely because Android had not yet picked up the DNS server from the network it had just rejoined. One transient miss ended automatic reconnection for good.

The reason it was permanent: the retry schedule could only be started by a session *dying*. When the reconnect's own attempt failed, that was logged and dropped — and there was no session left to die a second time, so nothing could restart the sequence. A failed attempt now re-enters the same bounded schedule as before: three tries, backing off, then it stops and waits for you.

This makes Haven recover from that stall. It does not stop the stall happening — that half of the report is still open, and needs a capture from the server side. (#421)

📊 **Remote desktop timings you can actually send** — when a remote desktop is laggy, the numbers that say *why* — frame rate, and how long each frame spends being decompressed, decoded and drawn — were written only to the Android system log, which needs a cable and a terminal to read. So the people best placed to report a problem were the least able to measure it.

Those numbers now also appear in the connection's verbose log, which is already in the Audit Log and already copyable. If you have reported a laggy RDP session, this is the thing worth pasting next. (#477)

⚡ **A wasteful copy removed from the H.264 remote desktop path** — every decoded frame was being duplicated on its way out of the decoder: about 8 MB per frame at 1080p, roughly 250 MB a second of throwaway work at 30 frames per second.

Being honest about what this is: real waste that is now gone, and almost certainly not what anyone reporting remote desktop lag is actually seeing. Expect to notice nothing. (#477)

## v5.86.37

🖥️ **Remote desktop picks up a batch of upstream fixes, including one that could close the app** — Haven's RDP engine is the IronRDP library, and Haven had been using its published releases. The current published release has a fault where a picture update whose stated size disagrees with the area it is painting into writes past the end of the display buffer, which stops the app rather than the connection. That is one of the two crashes reported in #422. It is fixed upstream, but there is no published release carrying the fix, and waiting for one meant knowingly shipping a crash — so Haven now builds against the fixed upstream code directly, at a fixed point that cannot move underneath it.

The same change removes a private copy of one IronRDP component that Haven had been carrying for months to hold three local patches. Two of those are now upstream — one of them replaced by a better version than ours, which correctly refuses a malformed message our own patch would have accepted. Only one patch remains, and it is an open pull request.

Also fixed here: the x86-64 build of the video engine failed to compile, because the tool it uses to assemble that processor's code was missing from the build machine. Only x86-64 was affected — phones and tablets were never at risk — and it was caught before release. (#422, #493)

🖥️ **RDP on VirtualBox: the last two releases fixed the wrong thing, and one of them made it worse** — v5.86.34 told you Haven had learned to "rejoin a large message that VirtualBox had split across two network reads". That was wrong. Nothing was ever split.

The error Haven was reading says "not enough bytes", and it does not mean what it sounds like. It is the end of a check that compares the size a message *claims* to be against the size it *actually decoded to*, and the two numbers in it are those two sizes — not an amount received and an amount still expected. VirtualBox sends a complete message that under-states its own length. Everything had already arrived.

So Haven spent two releases waiting for a second half that did not exist, and then stitching the next unrelated message onto the end of it. That stitched-together result was handed to the decoder labelled as the wrong kind of message, which produced a fresh error and ended the session — five thousandths of a second after the "fix" ran. A reporter's log caught it doing exactly that.

Haven now skips the one message it cannot read and keeps the session, which is what going fullscreen on a VirtualBox desktop needed all along. You may see a brief cursor glitch where a message was dropped. If a hundred messages in a row fail, it still stops with an error, because a frozen picture and no explanation is the worse outcome.

The wrong reading was settled by rebuilding the reporter's exact 8565-byte message by hand and confirming it reproduces their error with nothing missing. That reconstruction ships as a test, so this cannot quietly regress. (#422)

🛡️ **A fault in the remote-desktop decoder no longer closes Haven** — a second crash in the same reports had no error message at all: Haven simply vanished. The remote-desktop engine was built so that any internal fault stopped the whole app instantly, which is why it took Haven down rather than the connection. That is now contained — a fault of this kind ends the affected connection and tells you, and everything else keeps running.

Two honest limits. This costs about a megabyte of app size, which is the price of being able to recover at all. And it is a safety net, not a repair: the specific fault suspected here is in the shared RDP library, upstream have since fixed it, and Haven will pick that up when they publish it. (#422)

📦 **Everything in the app is now built from its source, not shipped as a pre-built file** — Haven used to carry twenty-five pre-built binaries in its source code, 206 MB of them, including the entire video engine. Nothing rebuilt those files, so what you installed from GitHub could quietly drift from what the source said. F-Droid never had this problem, because F-Droid always rebuilds from source — which meant their builds and ours were genuinely different artefacts, and theirs were missing pieces ours had.

All of it is now compiled during the build, from pinned sources with verified checksums. One file remains, and it is tracked. What kept this undone was a cost estimate that turned out to be about twenty times too high: the video engine takes under three minutes to build, not the hour it was assumed to. Nobody had measured it. (#493, #469)

📶 **Haven tells you when your phone, not Haven, cut the connection** — some phones stop background apps from using the network while leaving them apparently running, so a session died seconds after you switched away with no explanation and nothing in the logs to blame. When a connection drops right after Haven goes to the background, the connection log now says so and points at the specific setting on your make of phone. Haven cannot see those vendor switches directly, so this is worded as the likely cause rather than a certainty. (#495)

🔗 **Opening a connection link twice in a row works** — a link handled while Haven was already running could be delivered along two paths at once and open two sessions, and a link naming a connection that no longer exists no longer fails outright when it also names a host. (#305, #486)

## v5.86.35

🖥️ **App windows on a Debian 13 guest stop crashing** — opening an app in its own window failed roughly one time in three, and when it failed there was nothing on screen to say why. The cause was not in Haven: Debian 13 ships wayvnc 0.9.1, the piece that turns the guest's screen into something Haven can show, and that version crashes. Upstream declined to fix a version that old, and 0.10.1 does not crash.

Debian 13 will not move to 0.10, and the newer version is packaged only in Debian's testing archive — which is a moving target, so pulling one package out of it onto a stable guest can leave that guest unusable later. Haven now compiles wayvnc 0.10.1 from source inside the guest instead, when it sets up a nested-Wayland desktop and finds the installed version too old. It touches no distro package, so there is no way for it to damage a guest.

This costs a few minutes and a compiler the first time, on guests that need it — and nothing at all on guests that already have a new enough wayvnc, where the check takes under a second. Measured on a phone: eight app-window launches, eight connected, no crashes, against roughly one failure in three before.

Two limits worth stating. **An existing desktop keeps its old wayvnc** until you reinstall it — or run `sh /usr/local/share/haven/wayvnc-build/build.sh` in the guest terminal yourself. And this fixes the crash; the freeze some people saw on the first frame was never reproduced here, so if video still freezes without a crash, that is a separate problem and worth reporting. (#473)

🤖 **The agent endpoint repairs itself without you switching back to Haven** — if the MCP endpoint's listener died, Haven only noticed when you returned to the app or the network changed. For an AI app running on the same phone that is exactly backwards: switching to that app *is* what puts Haven in the background, and a connection over the phone's own loopback never causes a network change. So the one setup that needed the repair most could never trigger it. Haven now re-checks on a timer for as long as the connection notification is showing.

Being straight about the limit: this recovers an endpoint whose listener has died. It cannot help when the phone's system has frozen Haven outright, because the timer is frozen with it. (#494)

🔒 **An approval you gave after the agent gave up waiting is no longer wasted** — when a tool needs your approval every time, and the AI app's own timeout is shorter than the time you take to answer, the approval was meant to be held so the app's retry of the *same* action goes through instead of asking again. It never was: the approval was filed under one key and looked up under another, so it never matched, and every retry asked again. This has been broken since it was added. It matters most with an agent on the phone itself, where the approval cannot even be shown until you switch back, so the app is always the one to give up first. (#494)

## v5.86.34

🖱️ **RDP: the pointer moves on the server the way it moves under your finger** — while the remote screen was busy, a drag reached the server as a handful of jumps rather than a continuous movement. Input was sent once per pass of the session loop, and every pass also decoded a frame, so input left only as often as frames arrived. Measured against a test server, sixty pointer positions a second were arriving in three and a half bursts — about seventeen at once, then a quarter-second of nothing.

Sending input no longer waits for decoding. On the same measurement, all sixty positions a second now leave as they are made, and the picture arrives no more slowly for it. The effect is largest exactly where it was worst: the heavier the remote screen is to decode, the more this was costing you, which is why it was more noticeable with H.264/AVC420 switched on.

Worth being straight about what was checked: this was measured against a FreeRDP test server on a desktop, not on a phone against Windows or KDE, and not through the phone's hardware video decoder. If pointer movement still differs between AVC420 on and off for you, that is worth reporting — it would mean some of the coupling remains. (#477)

🖥️ **RDP: a fix for sessions dropping on VirtualBox now also covers reconnection** — Haven already knew how to rejoin a large message that VirtualBox had split across two network reads, but only during a settled session. The same split during a *reconnection* — which is what happens when the remote desktop changes size or 3D acceleration is switched on — still ended the session. That path is now handled too. This one is reasoned from the crash reports rather than reproduced here, since it needs a VirtualBox host to trigger. (#422)

## v5.86.33

🔗 **Terminal links: a fix from the last release was joining lines it shouldn't** — v5.86.32 taught Haven to reassemble a URL that a program had split across two lines inside a filename. It was too eager about it, and would also join a line that merely *began* with a filename: a URL at the end of one line followed by `README.md and 3 other files changed` on the next came out as one address ending `barREADME.md`. Tapping such a link opened the wrong page.

A wrapped line is one that ran out of room, so Haven now only joins the two halves when the second one could not have fitted on the end of the first. That is the actual difference between a URL the terminal had to break and two lines that happen to sit next to each other, and it needs nothing the terminal doesn't already know. Links genuinely broken across lines still open in full; a filename on the line below a link is left alone.

This was found by trying realistic pairs of lines rather than by reading the code — the tests that shipped with the original fix all missed it, because none of them put a real filename underneath a link. (#491)

## v5.86.32

🗂️ **Connections can be dragged past a collapsed folder** — with a collapsed folder that had connections in it, anything below could not be dragged above it or into it, however far you pulled. When a folder is collapsed, the list being reordered and the list on screen stop agreeing: its contents are still in the first and absent from the second. The drag chose what to step over from one and measured it against the other, found nothing to measure, and skipped the step — silently, on every frame. A collapsed folder was a wall. Dragging *into* one also used to strand the connection there, since the row disappeared from under your finger the moment it crossed the boundary; it now stays put until you let go. Two earlier attempts at this fixed real bugs but not this one, because this one is not a matter of timing — it happens every time. Checked on a phone against the previous build with the same list and the same gesture. (#488)

Dragging a connection down over folder headers also used to leave it lagging behind your finger, a little further with each header, because the drag corrected its position by the height of the row being moved rather than the height of the one it moved past.

🔗 **A link split across two lines now opens in full** — when a program running in the terminal wraps its own output at a width of its choosing, it can break a URL in the middle of a filename, and tapping it opened only the part before the break. The two halves are now joined when the tail looks like a filename. Being straight about the limit: a link broken inside a hostname, or inside a path with no file extension, is still not joined — there is nothing left on the screen that tells those apart from ordinary prose on the next line. (#491)

🖥️ **RDP: picture updates that cannot be decoded now say so** — these were skipped at debug level, so the only sign was a rectangle of the remote screen that never repainted, with nothing in the log to point at. They are now warnings and appear in the connection log. This does not repair the missing regions, which is still open; it makes them traceable when you report one. (#462)

## v5.86.31

🖥️ **Remote desktop: the RDP engine is up to date with the current IronRDP release** — a dependency refresh rather than a feature, and deliberately a boring one. Frame rate and decoding are unchanged, measured before and after against the same server rather than assumed: about 15 frames a second either way on the same 1080p session. The engine was checked against three different RDP servers (FreeRDP, xrdp, and KDE's KRDP) before shipping. Windows Server and GNOME Remote Desktop were not among them, so if you use either and something looks different, that is worth reporting. (#117)

🔎 **A remote desktop that drops the connection now says why** — when a server ends the session itself, for example after a failed login, Haven reported a bare "decode error" with nothing to act on. The reason the server gave is now shown instead, and the internal file paths that used to crowd these messages are gone. Found while testing the engine update against a third server.

## v5.86.30

🗝️ **An SSH key can now live in OpenKeychain instead of in Haven** — including an OpenPGP authentication subkey held on a YubiKey or Nitrokey. Keys → + → "Use a key from OpenKeychain" picks one; OpenKeychain signs each authentication itself, handling its own passphrase, PIN or tap, and the private key never reaches Haven. The option only appears if a provider app is installed. Currently supported on the default SSH engine; a profile set to the alternative engine falls back rather than failing. Not yet verified against a real token — no provider is installed on my own devices, so this is tested at the mechanism level only. (#487)

🖱️ **A remote desktop should no longer give up when the mouse pointer arrives in two pieces** — sessions were ending with a certificate-sounding error that had nothing to do with certificates. A large custom mouse pointer can be big enough that the server sends it split across two messages, and Haven was trying to make sense of each half on its own; the half was always incomplete, so it ended the session. It now waits for the rest before deciding. This was reported as happening when switching the remote machine to fullscreen, which fits — going fullscreen is what changes the pointer. I have not been able to reproduce it here, so this one is reasoned from crash logs rather than watched working. (#422)

🔒 **Connections stay put while you scroll** — the drag grip on the left of each row sat right where your thumb passes, and it started moving a connection almost as soon as you touched it, so flinging through a long list could quietly reorder it. Moving a connection now needs a brief press and hold first, with a small vibration when it takes hold. (#489)

📁 **Groups can be reordered** — long-press a group header and there are now Move up and Move down entries in its menu. A group takes its connections with it, and the menu stays open so you can move it several places without reopening it each time. (#490)

## v5.86.29

🧲 **Dragging a connection to a new position or group now sticks** — some drags simply undid themselves, and which ones seemed arbitrary. It was a race: the new order was saved in the background, but the list refreshed itself from the saved order a moment too early, while that was still the *old* one, and put everything back. Whether a drag survived came down to which finished first. Groups could also end up reordered on their own, from a second and unrelated slip in the same code. (#488)

## v5.86.28

🖥️ **You can now tell Haven what screen size to ask a remote desktop for** — under Settings → Desktop. Haven always asked for 1920×1080 and gave no way to change it, which is fine until the machine at the other end draws something else: a VirtualBox VM defaults to 2560×1600 and simply paints at that size without saying so, and everything Haven had not made room for was quietly thrown away. That looks like a screen that stops refreshing, or one with stale patches that never update. Set this to match the resolution the remote machine actually uses. 1920×1080, 2560×1600 and 1280×720 are one tap. (#422)

## v5.86.27

🖵️ **Remote desktops are markedly faster** — the frame rate over a 1080p session roughly doubled in testing, from about 6.6 to between 9.6 and 11.4 frames a second on the same connection and the same content. Every frame used to be copied three times on its way to the screen, including building a fresh 8 MB buffer for it in the app on each one; the decoded picture is now written straight into the image being displayed, once. The step that did all that copying went from 72 milliseconds a frame to about 1. What remains is the decoding itself, which is where any further gains have to come from. (#466)

🏷️ **Terminal tab names are back** — v5.86.24 made the tabs share the width evenly, and once you had enough tabs for that to stop fitting, every tab lost its name and shrank onto its close button. The two cases behave differently under the hood and only the roomy one was checked. (#479)

**If your terminal looks short and you used v5.86.23 to v5.86.25 with tmux:** those versions set a tmux option that limits a shared session to the smaller of your devices in each direction, and it stays set on the tmux server even after updating Haven. Run `tmux set -g window-size latest` on that machine to restore it. Sorry — that one was ours.

## v5.86.26

📐 **Terminal sessions use the full height of your screen again** — v5.86.23 changed how Haven sizes a tmux session that a computer is also connected to, so that the phone was no longer cut off on the right. That change fixed the width and quietly cost you height: the setting it used takes the smaller of the two devices in *each direction separately*, so the phone got its own width but the computer's height, losing rows it had room for. Reverted to the previous behaviour, where the session matches whichever device you last used — connecting, rotating and opening the keyboard all count, so the phone gets its exact size whenever you pick it up. (#479)

## v5.86.25

🖥️ **Remote desktops stay responsive when the server draws outside the agreed screen size** — a reporter's VirtualBox session ran slowly and then died within a minute. The server was painting a desktop larger than the size the two ends had agreed on, so thousands of its updates fell outside the screen Haven had set up and drew nothing at all — yet each one still made Haven rebuild the entire screen image from scratch. In a 38-second session that came to roughly 36 GB of pointless copying on the phone. Haven now updates only the part of the screen an update actually touches, so updates that land outside it cost nothing. The content those updates carried is still missing — that is a separate problem, still open — but it no longer takes the app down with it. (#422)

🔁 **Mosh sessions that stall mid-scroll now reconnect by themselves** — reported as a session freezing while scrolling back through history, showing "reconnecting", and never coming back until it was closed and reopened by hand. There are two ways a mosh session can go wrong: it can go quiet, which Haven already noticed and recovered from, or it can keep receiving perfectly good packets that it has no way to use. The second kind looked completely healthy to the watchdog — packets were arriving the whole time — so the recovery that would have fixed it was never triggered. It now spots a session that is receiving but getting nowhere and reconnects it. Sessions that are simply idle are left alone, as is a phone that has gone offline. (#421)

## v5.86.24

🗂️ **Terminal tabs are wider and easier to hit** — the tab strip sized each tab to its label, so a short name like "cctv" gave a target barely wider than the four letters. Tabs now share the strip evenly, so on a three-tab strip each one is well over twice the width it used to be. Once there are more tabs than will fit at a comfortable size the strip goes back to scrolling, rather than shrinking every tab to a sliver.

↔️ **Reordering a tab no longer closes the menu each time** — moving a tab several places meant long-pressing it again after every single step. The move arrows now leave the menu open, and it follows the tab as it moves, so you can walk a tab across the strip in one go.

## v5.86.23

📐 **Terminal text no longer runs off the right edge when a computer is attached to the same session** — reported as text still being cut off with no way to scroll across to it, even after the zoom fix in v5.86.21. This was a second, unrelated cause. When a phone and a computer are attached to the same tmux session, tmux sizes the shared view for whichever device was used most recently. Use the computer, and the view becomes as wide as its screen — far wider than the phone can show — and the phone renders only the left-hand portion, with no way to reach the rest. Nothing was lost; those columns were simply never sent to the phone. It came and went depending on which device you last touched, which is what made it look intermittent. Haven now asks tmux to fit the shared view to the smallest attached screen, so the phone always sees the whole width. The computer is letterboxed to match, which is the deliberate trade: a desktop can resize its own window, a phone cannot recover what was never sent. (#479)

📜 **The About Haven page scrolls again** — its text was cut off at the bottom with no way to scroll down to the rest, so the end of the open-source library list was unreachable. Whether it happened depended on your screen size and text size, which is why it affected some phones and not others. Reported on a Samsung S25+. (#485)

🔀 **Picking a session from the remote-session list attaches to it properly** — choosing an existing tmux/zellij/screen session could drop you into a plain shell instead, with the attach command turning up in that shell some time later. Haven types that command once it spots the shell prompt, and it recognised prompts by their last character — so a prompt ending in anything unusual (a powerline glyph, a bracket, a non-Latin character) was never spotted and the command waited. It now also sends the command once the shell falls quiet, which works whatever the prompt looks like. Reported for zellij; tmux and screen shared the same path. (#482)

## v5.86.22

🪟 **Linux desktops and single-app windows work again** — starting a desktop, or opening a guest app in its own window, failed outright on every install: the feature died the moment it was asked for, and over the agent bridge it took the connection down with it. The compositor library Haven ships had been built before part of its link step ran, leaving 51 internal references unresolved, so Android refused to load it at all. Nothing detected this at build time — the app compiled, packaged and installed perfectly, and only failed on a real device when the feature was first used. The library is rebuilt, and the build now refuses to package one in that state, so this class of failure cannot ship silently again. (#469)

## v5.86.21

✂️ **Pinch-zoom no longer destroys the right-hand side of the terminal** — reported as text being clipped when zooming in and out, with no way to scroll across to reach it. The text was not off-screen; it had been deleted. Zooming changes the font size, which changes how many columns fit, which resizes the terminal — and the terminal core was configured to truncate lines on a resize rather than re-wrap them. So zooming in cut every visible line to the narrower width and threw the remainder away, and zooming back out left blank space where the words used to be, with nothing to scroll to. A resize is now a re-layout: lines re-wrap to the new width and come back whole when you zoom out again. (#479)

## v5.86.20

📜 **Terminal scrollback is fully reachable again after pinch-zooming** — reported as not being able to scroll all the way back once you had zoomed, most of the time but not always. A zoom changes how far there is to scroll twice over: the characters get taller and the reflow pushes more lines into the scrollback. The gesture handler had measured that limit once, when it started, and does not restart when the font size changes — so it kept clamping every scroll to the pre-zoom limit, putting the scrollback the zoom had just created out of reach. Raising the scrollback-lines setting could not help, because the limit was frozen regardless of how much history existed. The intermittency was the giveaway: anything that rebuilds the gesture setup — entering mouse mode, or a full-screen app taking over — restarted the handler and quietly picked up the current limit until the next zoom. The limit is now read live, the same way the character measurements beside it already were. (#478)

## v5.86.19

🪟 **Windows RDP no longer dies mid-logon with H.264 enabled** — reported as needing "H.264/AVC420 decoding" switched off globally to connect to Windows at all, while Linux servers needed it on. The reporter's screenshot carried the real error: `Fast-Path: unexpected codec ID: 1`. Haven was advertising NSCodec among the bitmap codecs it claims to support, so Windows took it up and started sending screen updates encoded with it — down a path that can only decode three codecs, none of them that one, where an unrecognised codec is a fatal error rather than a skipped region. Haven does have NSCodec support, but it decodes a different container on the graphics channel, so the claim was simply wrong. It is no longer advertised, and Windows falls back to RemoteFX or plain bitmaps, both of which decode properly. That also explains why the H.264 switch appeared to matter: the graphics capabilities Haven advertises change how much Windows sends down the legacy path, so turning H.264 on pushed more content into the trap. Not yet confirmed against the reporter's Windows 11 host. (#461)

## v5.86.18

🖱️ **Remote mouse movement reaches the server smoothly** — reported as a cursor that looks fine in Haven but moves in jumps and skips on the server itself. Haven can only hand queued input to the connection once per pass of its session loop, and each pass waits on the network for up to 100ms, so whenever the server had nothing to send your finger drag arrived as a burst of positions followed by nothing. Haven now checks far more often while you are actually interacting, and falls back to the relaxed timing shortly after you stop, so an idle session is not woken needlessly. This is the input half of #477 only — video playback still lags behind a native client, for a different reason tracked in #466. (#477)

📄 **Importing an rclone.conf tells you when it goes wrong** — picking a file could fail completely silently: a cancelled pick, an unreadable file and an empty file all did nothing and said nothing, so a button that was failing looked exactly like one that was ignoring you. Every case now says what happened, launching the picker is guarded for devices where no working file picker is available, and the message appears above the pick-and-paste controls rather than replacing them, so you can paste the file without reopening the dialog. Note for the reporter's case specifically: another installed app was intercepting the system file picker, which Haven cannot override — but it will now say so instead of appearing dead, and pasting the config imports identically. (#468)

## v5.86.17

🔑 **Long-pressing a key no longer opens two menus at once** — on the Keys screen, holding a key to enter multi-select also triggered Android's own mark/copy/paste popup, so both appeared together. The fingerprint line was selectable text, which hands long-press over it to the system's text-selection handling, on a row that already treats long-press as "open this key's menu" — two owners for one gesture. In a list, long-press belongs to the item, so the fingerprint is now plain text and only Haven's menu appears. Copying is unaffected ("Copy public key" is on that menu); if copying the fingerprint itself is wanted it will be added there rather than as text selection. Reported from testing on a Samsung S25+, where the sorting and collapsible sections were confirmed working. (#460)

## v5.86.16

🖼️ **Rectangular patches of a Windows desktop no longer stay blank** — reported as a line of text with whole chunks missing, a square notch out of the account avatar, and a black rectangle on the desktop, all on Windows 11 and unaffected by colour depth or H.264. The shape was the clue: everything absent was an axis-aligned rectangle while the content around it drew correctly, which is a region being discarded rather than decoded wrongly. Checking the graphics dispatch against the codecs the protocol defines turned up three that Haven silently threw away, including the *uncompressed* one — the plain-pixels encoding a server may fall back to for any region at all, so dropping it could blank an arbitrary rectangle of any desktop. It is now decoded, with the colour channel order pinned by a test that fails if red and blue are swapped. The two remaining gaps (RemoteFX and the alpha codec) now log a warning naming the codec and the exact rectangle they left unpainted, instead of being invisible at debug level — so if this is still happening for anyone, one log will say precisely which codec to implement next. Whether this was the reporter's specific case is not yet confirmed. (#462)

## v5.86.15

⌨️ **Taps in terminal apps no longer turn into text selections out of nowhere** — reported against zellij, and separately against a plain shell, where a short tap would pop up the copy UI instead of reaching the app, and it then took two or three taps to hit anything. The cause was an event being eaten: after your finger lands, the terminal spends 40ms watching for a second finger so it can tell a tap from a pinch, and that check swallowed the one event it waited for. When that event was your finger lifting, the release was thrown away, the gesture sat waiting for a lift that had already happened, and the long-press timer later fired into a finger that was no longer on the screen — starting a selection, and leaving the stale gesture to absorb your next tap. It also explains why three previous attempts at this (all adjusting how long you must hold) changed nothing: the timer was firing after your finger had gone, so a longer threshold only delayed the phantom selection. Whether a real tap lands inside that 40ms window comes down to delivery timing, which is why it hit some people constantly and others never — an accessibility service re-injecting touches delivers press and release much closer together than a finger can, and a mouse (via scrcpy) takes a different path entirely and always worked. Pinned by three tests that fail without the fix. (#435, #440)

## v5.86.14

🖥️ **Remote desktops repaint far less work per update** — a VirtualBox user's UEFI menu was taking about five seconds per keypress and eventually wedging. Their logs showed why: 7,736 screen updates over nine minutes, every one tiny (most commonly 127x82 pixels), against a 1920x1080 desktop. For each one Haven copied the whole 8.29 MB framebuffer out of the native RDP code, allocated a brand-new 8.29 MB bitmap, and copied into it again — roughly 400 times more work than the update actually contained, sustained at 14–21 updates per second, which buries the garbage collector. The screen bitmap is now kept for the life of a session and only the rows the server actually changed are fetched and painted, so a typical update moves about 41 KB instead of 25 MB. This is not specific to VirtualBox: any server sending small targeted updates was penalised hardest, and the same waste was the throughput ceiling behind the 4K KRDP report. Pinned by tests that count real draw operations on a device, plus native tests for the region extraction. The end-to-end improvement has not yet been confirmed on a live session by the reporters. (#422, #466)

## v5.86.13

🎞️ **H.264 remote desktops decode measurably faster** — a KRDP session at 3840x2160 was dropping after about a minute. The reporter's logs show why: Haven managed 1.78 frames per second against a server producing about 12, so it fell roughly 18x behind until the connection died. The cost was ~499ms per frame and, tellingly, the same for a 143-byte frame as for a 348KB one — fixed work proportional to the pixel count, namely an 8.3-megapixel colour conversion done per frame in software. Each chroma sample is shared by two pixels, so its scaled terms are now computed once per pair instead of twice, and the per-component clamp is a table lookup: 90ms → 35ms per 4K frame on a desktop JVM, with bit-identical output pinned by seven equivalence tests against the previous code. That is a ratio measured off-device, not a device timing, and it does not on its own make 4K comfortable — the remaining per-frame full-frame copies are the next target, tracked on the issue. Until then, configuring the server for a smaller stream (KRDP's `--virtual-monitor 1920x1080@1`) is the effective workaround. (#466)

## v5.86.12

📁 **"Pick rclone.conf" opened a strange system screen on some devices** — the file picker used `ACTION_GET_CONTENT`, which Android resolves to whatever handler the ROM happens to ship, so the same unchanged code could land on an OEM file manager, a chooser, or an error screen depending on the device and its system updates — reported as "a strange screen appears (something about intents)" on a flow that worked when it shipped. It now uses `ACTION_OPEN_DOCUMENT`, which always opens the system file picker. Verified end-to-end on an emulator: picking a real rclone.conf from Downloads imports its remotes. The reporter's own device is still unconfirmed. (#468)

## v5.86.11

⌨️ **The keyboard could refuse to open in the terminal after returning to the app** — last night's HyperOS crash fix (v5.86.8) makes the terminal's input view unfocusable while the app is backgrounded, restoring it 100ms after return. But the show-keyboard call bails out silently when the view is unfocusable, and nothing retries it — so a show request landing in that window did nothing at all, leaving a terminal that simply refused input. The race is exact: the auto-show on resume and the refocus were both on 100ms timers. The show path now restores focusability itself when the window is back (and still refuses to take focus while it is hidden, which is what crashed HyperOS). Pinned by a test that fails on the old code. Possible cause of #476 — unconfirmed against that reporter's device, who hasn't yet supplied their version. (#476)

## v5.86.10

🔍 **`get_app_info` now tells the truth about the native Wayland desktop** — the MCP capability list advertised `wayland` unconditionally, even on a device where `liblabwc_android.so` fails to load (the undiagnosed root cause behind #469's cage/present_app crash, contained since v5.86.7). The capability is now gated on the library actually having loaded, and when it hasn't, a new `waylandLoadError` field carries the recorded failure reason — so one MCP call answers what previously needed Shizuku-gated logcat access within hours of the failure. Diagnostic groundwork for #469's remaining root-cause question; the crash containment itself already shipped. (#469)

## v5.86.9

🖥️ **High-resolution RDP desktops no longer render top-left-only** — KRDP (KDE Plasma's RDP server) agrees to the client's requested resolution during the handshake but then streams the physical monitor over the graphics channel; the real size only arrives in the EGFX ResetGraphics message, which Haven ignored. The framebuffer stayed at the handshake size, so on a 2560x1440 or 4K desktop everything beyond that was silently clipped — only the top-left crop was visible, with black filling the rest when zoomed out. The framebuffer now follows the size the server announces, and the viewer's existing unzoomed state fit-scales the whole desktop into view. Pinned by a regression test that replays the KRDP sequence (handshake at 1280x800, ResetGraphics at 2560x1440, fill outside the old bounds) and fails without the fix. Not yet re-verified against a live KRDP stream — reporters on #474/#467, a re-test would be appreciated. (#474, #467)

## v5.86.8

📱 **Terminal crash on HyperOS/Android 16 warm return** — on Xiaomi's HyperOS, backgrounding the app with an SSH session open and the soft keyboard up, waiting a few minutes, and returning crashed instantly, every time: HyperOS freezes the Activity, and on the way back Compose flushes a deferred composition teardown while the terminal's hidden IME view still holds focus — Android's view-removal path then routes a focus request straight back into the half-disposed hierarchy ("Searching for active node in inactive hierarchy"). The IME view now surrenders focus for as long as its window is invisible and re-takes it just after return, past the frame where that flush runs — with the keyboard hidden nothing was ever focused, which is exactly why that case never crashed. Diagnosed from a reporter's excellent line-by-line trace of the disposal path; the fix is pinned by regression tests that caught two subtler focus-bounce behaviours of Android's own focus machinery along the way.

🚀 **Pack-installed apps launch with their saved configuration** — the Installed Apps screen lists both the guest's .desktop scan and saved app-window defs, so a pack-installed app appeared twice under one name, and the .desktop row launched a generic, broken variant (no multi-window, no placement, no platform env) — reported as "only the equalizer appeared and menus don't work". A .desktop launch whose label matches a saved def now delegates to the def. (#470)

## v5.86.7

🎛️ **qmmp pack: deck size now comes from qmmp itself, not the compositor — fixing the context-menu crash** — v5.86.6 sized the deck with a fractional wlroots output scale, which silently breaks Xwayland's coordinate space: the X screen stays in physical pixels while the output is logical, so Qt dutifully places cascading submenus into space that lies outside the output, and the compositor dies without a word — reported live as "the context menu crashes it", and wrongly chased through two sway versions before the coordinate mismatch emerged. The pack now keeps the output at scale 1 (menus always land inside) and gets the big deck from the skin engine's native pixel-double mode, with placement updated for the 550px windows and the playlist/EQ opened on first run. Also learned on the way, for the record: Winamp title-bar double-click is shade, not maximize, and Debian Trixie's wayvnc 0.9 freezes app-window video against the 0.8-built shim (#473) — the pack targets Noble until that's version-aware. (#470)

## v5.86.6

📦 **Guest app catalog: one-tap installer packs for app windows** — recreating a working guest app used to mean knowing the whole recipe: which distro family packages it, the config defaults that fail silently in proot, the audio bridge, the app-window registration. A curated pack now collates all of it, and two new agent verbs (`list_app_packs`, `install_app_pack`) run the lot as one background job: package install, idempotent config drops, sha256-pinned asset fetches, verify-binary check, app-window registration, audio bridge. Ships with the device-verified qmmp pack (Winamp-style player: the pulse output fix, a starter skin, fullscreen app window — install was verified end-to-end from a clean guest, and re-running it doesn't duplicate config) plus a declared-but-unverified Audacious pack. The catalog is compiled into the release — packs execute shell in the guest, so trust rides on the APK signature. The "Add from catalog" screen is the next #470 phase; agents can drive it today. (#470)

🪟 **Multi-window apps work in app windows** — the kiosk config force-fullscreened every window, so a multi-window app like qmmp's skinned deck (main + playlist + EQ) stacked into a single visible window, and enabling the skinned UI as a native Wayland client took the compositor down entirely. App-window defs (and packs) now carry `multiWindow` — float instead of fullscreen — plus per-title sway placement rules and an output `scale`, and `present_app` accepts the same. The qmmp pack puts it together: Xwayland platform (skin-safe), the three deck windows placed as the classic Winamp stack, output scaled so the deck fills the phone's width on a screen-aspect canvas that fullscreen can't crop, and the playlist/EQ opened on first run. Device-verified through five iterations of exactly these failure modes, live with the reporting user.

🚪 **App windows tear down when their app exits** — the generated sway kiosk config's `exec <app>; swaymsg exit` line was split at the `;` by sway's parser, which rejected the `swaymsg exit` half as an unknown config command. The app half still ran, so nobody noticed the exit hook had never once registered: every app window whose app closed itself left its compositor, wayvnc, and session running until manually dismissed. The app and exit hook now run via a launcher script (which also makes arbitrary commands quoting-proof). Verified both ways on-device: before, a `sleep 5` window's compositor was still alive 10 seconds after the app exited; after, it's gone and the session drops cleanly. (#471)

🖥️ **VNC: an out-of-bounds CopyRect is clamped instead of erroring the tab** — a stale CopyRect racing a desktop resize (or a server bug) could reference geometry beyond the framebuffer, and the raw `y + height must be <= bitmap.height()` exception surfaced verbatim as the connection's error. The blit is now clamped to the live frame — copying the in-bounds portion, dropping the rest — and the copy itself switched from a bitmap-allocating canvas draw to a buffered pixel copy that's also safe for overlapping scroll regions. Reported live minutes after it happened; pinned by a decoder test that replays the exact out-of-bounds rect.

## v5.86.5

🔳 **The app-window toolbar no longer hides its tail on portrait phones** — the popup viewer's bottom toolbar packs ~10 controls into one row (close, keyboard, orientation, the L/M/R mouse toggles, input mode, minimize, PiP, fullscreen), which is wider than a portrait phone. A plain Row silently clipped whatever fell off the right edge — most visibly the fullscreen button, reported missing while driving qmmp in a `present_app` popup minutes after v5.86.4 landed. The toolbar now wraps onto a second line when it runs out of width; verified on-device that "Enter fullscreen" is back in the sheet.

## v5.86.4

🪟 **Cage app windows and `present_app` popups work again** — starting the cage desktop or a `present_app` window crashed on devices where the labwc native library fails to load: the GPU-acceleration step added in v5.86.1 called straight into JNI without checking the library had actually loaded, throwing `UnsatisfiedLinkError` and aborting the launch. The call is now gated on the library the way `WaylandBridge`'s own contract demands, and a skipped virgl just leaves the app on the software renderer — verified on the failing device that a cage on llvmpipe runs fine. The skip is logged with the library's real load-failure reason, so the next report from a device in this state carries its own diagnosis. Found live while driving qmmp into a cage popup over MCP. (#469)

🔌 **An MCP tool crash no longer takes the whole agent session with it** — the same error exposed a second hole: the JSON-RPC dispatcher only caught `Exception`, so a JNI `Error` unwound past the response writer and the client's socket simply died — no error reply, session over, twice in one afternoon. Dispatch now contains any `Throwable` and answers with a normal JSON-RPC error instead, pinned by a test that replays the exact on-device failure and fails on the old code. (#469)

## v5.86.3

🛟 **Backup: a restored phone no longer crash-loops the app** — the auto-pull passphrase is stored encrypted with a key in the Android Keystore, and Keystore keys deliberately don't survive an app reinstall or a device-to-device restore. Android's own backup would faithfully restore the *encrypted* passphrase while the key to read it was gone forever — and Haven threw an uncaught error over it on every single launch, before any UI appeared, leaving the app unusable until its data was cleared. Found live on a real device by kanazawahere, who also diagnosed it: an undecryptable stored passphrase now simply means "auto-pull isn't configured", so the app starts normally and you re-enter the passphrase once in Settings if you still want it. The same treatment covers a corrupt stored value and an unreadable restored keyset — every way the stored secret can be beyond recovery, not just the one that was reported. (#464, thanks kanazawahere)

## v5.86.2

🩺 **RDP: the two NLA failure modes now explain themselves** — skeezmoe's systematic testing on #461 surfaced two failures that each ended in a wall of Rust debug text. Both now come with a plain-language diagnosis above the raw error. `NegotiationFailure(FailureCode(5))` is Windows refusing at the door because it requires Network Level Authentication while the profile has it off — the hint names both remedies (tick NLA on the profile, or untick "Require devices to use Network Level Authentication" on the PC). `invalid username … MixedFormat` is the NLA library refusing a Microsoft-account username: it won't take `MicrosoftAccount\you@example.com` and it truncates a bare e-mail at the `@` — an upstream limitation for which a fix is now proposed ([sspi-rs#719](https://github.com/Devolutions/sspi-rs/pull/719)); until it lands, the hint spells out the verified working recipe — NLA off on both ends, either username form. Tests pin both hints to the exact error strings from the reports, so they can't silently drift away from the failures they explain. (#461, thanks skeezmoe)

## v5.86.1

🖥️ **RDP: VirtualBox sessions survive key presses now, actually** — v5.85.2 claimed this and pawlosck disproved it within hours: the same arrow press still dropped the connection. The arrow-key correction in that release was real but it was not what VirtualBox objects to. VirtualBox's RDP server never advertises fast-path input, and its own log shows it terminating the connection the moment a client sends the 4-byte fast-path packet a single key press produces — "Network packet length is incorrect". Typed text and mouse input make longer packets, which is why only keys like arrows, Enter or Escape killed sessions. Clients that honour the server's declared input support fall back to the older slow-path input format, which is why every other RDP client works against VirtualBox; Haven now does the same for any server that doesn't offer fast-path input. Reproduced against a real VirtualBox VRDE server: before, the first arrow press ended the session; after, arrows in all four directions, Enter and typed text leave it connected with the screen still updating, and VirtualBox's log stays clean. (#422, thanks pawlosck — for the precision and the persistence)

## v5.86.0

🔑 **Keys tab: sorting, collapsible sections, and multi-select delete** — the Keys tab was built for someone holding a handful of credentials and stopped scaling above that. Three changes, all suggested by onatio22 after importing seven resident credentials off a YubiKey.

**Sorting.** The SSH-keys section has a sort control: by name (A–Z / Z–A), by creation date (newest / oldest first), or "My order" — the manual arrangement you build with move up / move down. Tapping the field you are already sorted by flips its direction. Sorting is a *view* on top of your manual order rather than a replacement for it, so switching to a sort and back leaves your arrangement exactly as you left it. While a sort is active, move up / move down are hidden: reordering a list whose order is computed cannot show a result, and letting it through would quietly overwrite your arrangement with the sort's.

**Collapsible sections.** Every section heading — Certificate authorities, SSH keys, Stored passwords, Authenticators, age identities, SSH identities — collapses and expands when tapped. The count stays visible while collapsed, so a folded section still tells you how much is behind it, and the "add" action on a collapsed section stays reachable. Which sections you collapsed is remembered, because reclaiming vertical space on a small phone is the point and a collapse that resets on every visit would not do that.

**Multi-select delete.** A key's ⋮ menu has a "Select…" entry that turns the section into a selection list: tap rows to add or remove them, "Select all" for the lot, then delete. Deleting several keys at once is irreversible in a way that deleting one at a time is not, so the confirmation names every key going, and separately calls out any key a saved connection authenticates with rather than removing it silently. Selection starts from the menu rather than a long-press because long-press already opens that menu — and an explicit menu item is harder to reach by accident than a gesture. (#460, suggested by onatio22)

## v5.85.2

⌨️ **RDP: arrow keys press the key you actually pressed** — the arrows, Home, End, Page Up, Page Down, Insert, Delete and the Windows key were all sent as the wrong key. On a keyboard these live in the navigation cluster and are marked differently on the wire from their numeric-keypad twins, and Haven was sending the twins: pressing Down sent numpad-2 instead. Most servers quietly accept it, which is why this went unnoticed. VirtualBox's RDP server does not — it closes the connection, so a few presses in a VirtualBox VM would drop the session entirely. Reproduced against a real VirtualBox server and confirmed fixed on the same setup: eight arrow presses used to kill the connection, thirty-three no longer do. (#422, thanks pawlosck)

🎨 **Dark theme: stored password names are readable again** — names in the Keys tab's stored-password list were drawn in black on the dark background, effectively invisible. The cause was not that list: several screens make their background transparent so the wallpaper opacity setting shows through, and that left the default text colour unset, falling back to black. Fixed on every screen that does this — Keys, Files, Connections and Mail — so any text without its own colour is readable rather than only the one place it was noticed. Light theme is unchanged.

## v5.85.1

🖥️ **RDP: a username like `MicrosoftAccount\you@example.com` now logs you in** — if you entered a qualified username, Haven sent the whole string as the username and left the domain empty. Windows accepted the credentials, then showed the lock screen and asked you to sign in again, because the logon itself never received a name it recognised. Haven now splits a qualified username at the backslash into the two fields the protocol actually carries, which is what other RDP clients do.

A plain `you@example.com` is deliberately left untouched. That form is also how Active Directory logins are written, and there is no way to tell the two apart from the text alone — so Haven changes only what you have explicitly qualified, rather than guessing and breaking domain sign-ins to fix a Microsoft-account one. If you sign in to Windows with an email address and land on the lock screen, entering the username as `MicrosoftAccount\you@example.com` now works. (#461, thanks skeezmoe)

## v5.85.0

🔄 **Backup: pull automatically, if you want it** — backup sync could already push on its own, but pulling stayed manual, because an unattended pull can overwrite work you did on this device. That trade-off is wrong for a phone that only ever *consumes* a backup written somewhere else. There is now a separate **Pull automatically** switch next to the existing push one, off by default, with an interval of 15 minutes, 1 hour, 6 hours or 24 hours. The warning is worth reading before turning it on: if you also edit connections here, unsaved local changes can be overwritten, because the remote copy wins. Enabling it stores your backup passphrase encrypted on the device, and turning it off deletes it again — unless automatic push is still using it.

🔐 **Backup: remember the restore passphrase, and a one-tap pull** — the Restore Backup dialog has a **Remember this password** checkbox, so a manual restore no longer means retyping the passphrase every time. It is unticked by default, unticking it again deletes the stored value, and restoring still requires tapping Restore, so nothing happens without you asking. Long-pressing Haven's launcher icon also gains a **Pull Backup Now** shortcut, which runs a single pull in the background and tells you how it went. If no passphrase is saved yet it opens the password dialog rather than failing quietly. (#458, thanks kanazawahere)

🌍 **The new backup settings speak every language Haven does** — all of the above, including the notification and toast text, is translated into the eleven supported languages rather than appearing in English. The description under "Push automatically" has also been corrected in every language: it still claimed that pulling always stays manual, which this release makes untrue.

🔑 **SSH: jsch updated to 2.28.5** — the default engine picks up a fix contributed upstream from Haven for accepting host certificates signed by an RSA certificate authority, along with exit-signal reporting. The experimental sshlib engine is unaffected.

## v5.84.5

📦 **F-Droid: unblock the builds that have kept that version a month behind** — installing from F-Droid has been getting 5.81.7 while more than a dozen releases went out, and the reason was not the usual queue lag. F-Droid's build server fetches FFmpeg's source straight from `git.ffmpeg.org` during the build, that host stopped answering it, and the connection timed out after more than two minutes — so the whole build failed and nothing was published. Haven now fetches FFmpeg from the official GitHub mirror first and falls back to the original host, rather than depending on one machine being reachable. Both serve the identical commit for the pinned version, so nothing about what gets built changes. If every mirror is unreachable the build now stops with a clear message instead of continuing without the source.

This only helps versions tagged after it, so this release is the first F-Droid can pick up. Nothing changes for anyone installing from GitHub releases or Obtainium.

## v5.84.4

🔐 **SSH: a second attempt at the sshlib key-exchange failure (#451)** — the fix in v5.83.20 moved Android's key store out of the way when it was answering a lookup it can only ever refuse. The reporter's log shows it ran exactly as intended and the connection failed anyway, which ruled out its own explanation: moving a provider to the end of the list cannot help when it is the *only* one offering the algorithm, because last place is still first place. Haven now detects that case and registers its bundled cryptography provider ahead of the key store, so the lookup has a working implementation to land on. That step runs only on a device that has already proved it cannot resolve the algorithm any other way — on every other device nothing is touched, as before. Haven's own hardware-backed keys are unaffected either way, since they ask for the key store by name.

This is reproduced in the test suite rather than reasoned about, including an assertion that the previous fix does *not* rescue this arrangement. It has not been confirmed on the affected hardware, so if it still fails the log now names which provider serves each algorithm after the reordering, which the previous one could not. The default JSch engine was never affected. (#451, thanks Slayerx96)

## v5.84.3

🔑 **Security keys: the resident-key list shows each key's name** — importing from a security key listed the credentials by their service and fingerprint, and the name you gave a key only appeared once you had ticked its row. Finding a particular key on a dongle holding many of them meant opening each one in turn to read its name and then closing it again. The name now leads each row, with the service and algorithm on the line beneath, so the list is readable at a glance. Clearing the label field during import also used to fall back to a generated `FIDO2: <service>` rather than to the name the row had been showing; it now restores that name. (#449, thanks onatio22)

🔌 **Agents: `get_app_info` reports which transport carried the call** — the carrier list described what was configured, which answers a different question from how the current request actually arrived. Someone seeing the near/SSH carrier reported as inactive, bound to a machine they were not connected to, alongside a call that had plainly just succeeded, had no way to tell which of the four transports had delivered it. The reply now names it: `mcpCarriers.servedVia` is one of `DEVICE`, `TUNNELED`, `LAN` or `WIREGUARD`.

## v5.84.2

🔍 **Terminal: gesture diagnostics for the zellij tap report (#435)** — a reporter sees a quick tap open the copy menu instead of clicking, on a device where none of the three fixes so far has helped, and it cannot be reproduced here. Haven now records one line per terminal gesture — how long the press really lasted, how far it moved, and what kind of pointer it was — so the next report carries measurements instead of estimates. It is a debug log, filterable with `logcat -s HavenGesture`, and does nothing for anyone not collecting one.

🌍 **Every screen is fully translated again** — 42 strings added by recent features had only ever existed in English: the guest DNS settings, the floating text input, the "Where am I?" details sheet, and the new Save connection dialog. All eleven languages — Arabic, Bengali, Chinese, French, German, Hindi, Japanese, Korean, Portuguese, Russian and Spanish — are back to complete coverage. These translations have not been reviewed by native speakers, so corrections are welcome, either here or through the translation page.

🔑 **Security keys: an imported key keeps the name you gave it** — every key imported from a security key came back labelled "FIDO2: ssh:", whatever you had called it when you created it, and that name then followed the key onto its public key. The name was never lost: Haven writes it onto the credential at creation and reads it back correctly, but the step that lists the keys was discarding it before anything could use it, so the import fell back to a generated name. Keys made before this change may carry no name and will still show the generated one; you can edit it during import either way. (#449, thanks onatio22)

## v5.84.0

📌 **Save a running session as a connection** — long-press a terminal tab and choose **Save connection** to pin the tmux, zellij, screen or byobu session you are attached to as its own entry in Connections. It copies the host, login and transport from the live session and remembers the session name, so connecting to it later drops you straight back into that session instead of a fresh shell or a list to pick from. Saving the same session again updates the existing entry rather than adding a duplicate. Previously this needed a hand-written remote command in the profile's advanced settings. (#447, thanks kanazawahere)

🔑 **Security keys: a copied public key keeps its label** — copying a public key out of Haven produced a bare `sk-ssh-ed25519@openssh.com AAAA…` with nothing on the end identifying it, so an `authorized_keys` file full of them was impossible to tell apart. Keys Haven generates itself already carried their label; imported keys and credentials read off a security key did not. The label is now added when the key has no comment of its own — and a key that already carries one is left alone, since that comment may be exactly what a server already has on file. (#449, thanks onatio22)

## v5.83.20

🔐 **SSH: the experimental sshlib engine connects again on affected devices** — on some Android builds every sshlib connection failed during key exchange with "To generate a key pair in Android Keystore…". Android offers several implementations of the same cryptography, and on those devices the system picked the key-store one for a task it can never do: reconstructing the server's temporary key, which by definition does not live in the key store. Haven now detects that and moves the key-store implementation out of the way for these unnamed lookups. Anything that genuinely wants the key store still asks for it by name, so your hardware-backed keys are unaffected, and on devices that were already choosing correctly this changes nothing. The default JSch engine was never affected. Reported upstream as connectbot/cbssh#246, where the real repair belongs. (#451, thanks Slayerx96)

## v5.83.19

🔐 **SSH: the experimental sshlib engine handles two-factor logins and jump hosts** — it previously accepted a keyboard-interactive prompter, a TOTP provider and multi-step auth chains and then quietly ignored all three: challenge rounds were answered by echoing the saved password, so a 2FA prompt could not be answered at all, and multi-method chains were refused outright. Both engines now share the same answering logic — a password round answered from the saved password, a one-time-code round from your live code provider, anything else handed to you with the generated code pre-filled. The engine also reaches hosts through a jump host or proxy now: SOCKS4, SOCKS5, HTTP CONNECT, Tailscale, WireGuard, Cloudflare Access and ProxyJump all work, via a single adapter rather than per-tunnel code. Still opt-in, JSch still the default. (#58)

🖱️ **Terminal: touch scrolling behaves the same on every screen** — the drag distance needed to send one scroll step was measured in raw pixels, so the same physical swipe produced very different amounts of scrolling depending on how dense your display is: about 1.3mm per step on a dense phone against roughly 4mm on a coarser one, meaning up to three times as many scroll steps for an identical gesture. It is now a fixed physical distance instead. Dense displays — where scrolling felt twitchy and overshot — get calmer; coarser screens that needed an unusually long drag get slightly more responsive. Measured on hardware: a 300-pixel swipe now sends exactly the twelve scroll steps it should. (#421, thanks dkoppenh)

## v5.83.18

🔑 **Security keys: listing resident keys works on older YubiKeys** — importing a resident SSH key from a YubiKey with firmware below 5.5 failed straight after the PIN was accepted, over NFC and USB-C alike. The feature that lists the keys stored on a security key exists in two versions: the one standardised in CTAP 2.1, and the earlier prototype that shipped first. Haven only ever asked for the standardised one, so a key that speaks only the prototype rejected the request outright — which is every YubiKey before firmware 5.5. Haven now asks the key which one it understands and uses that. A key that supports neither says so plainly instead of failing with a numeric code. (#449, thanks onatio22)

## v5.83.17

🖱️ **Terminal: two-finger scrolling works on OPPO/ColorOS keyboard touchpads** — on an OPPO Pad 3 Pro keyboard, two-finger scrolling did nothing in the terminal while it worked everywhere else on the tablet, and two previous attempts at this didn't help. wxjiee found why by capturing the raw input events on the affected tablet: ColorOS doesn't report that gesture as a scroll event at all. It sends a plain touch stream from the touchpad, flagged by Android as an official two-finger swipe, with every scroll value at zero — so the earlier fixes, which both waited for a scroll event, never ran. Haven now recognises that flagged gesture and scrolls from it, feeding tmux and vim wheel events as usual. A touchpad click-and-drag is flagged differently and is untouched, as are taps and press-and-hold text selection. Verified by wxjiee on the affected hardware, and checked here for no change to ordinary touchscreen use. (#419, patch by wxjiee)

## v5.83.16

🔎 **Agent API: you can now read the output of a session that has already ended** — when a connection ends the instant it starts (a remote command that returns, a shell the server refuses, a login that drops), the terminal tab closes and every way of reading it failed with "no registered terminal tab" — at exactly the moment its output is the only thing explaining why. Haven now keeps the final screen of the last few sessions to end, readable with `read_exited_session`, so "it connected and then vanished" can be diagnosed instead of guessed at. Kept in memory only, so it doesn't survive restarting Haven.

⚙️ **Agent API: an SSH profile now reports which engine it uses** — `sshOptions` could be set when creating or editing a profile, including the directive that opts it into the experimental sshlib engine, but was never reported back. An agent could switch a profile's SSH engine and then have no way to confirm which one it was on, or why one profile behaved differently from another. `list_connections` now returns the profile's `sshOptions` along with a plain `sshEngine` of "jsch" or "sshlib".

## v5.83.15

🔑 **Security keys: importing a resident SSH key over NFC works** — discovering keys already stored on a YubiKey asked you to tap the key first and *then* typed-in PIN second. You can't hold a key against the phone and type a PIN at the same time, so the key left the field while the dialog was up and the read died with Android's "Permission Denial: Tag … is out of date" — which reads like a bug in Haven's permissions rather than "the key moved". Generating a key worked, because that path already collected the PIN up front; only the import path didn't. It does now, so the whole exchange runs as one uninterrupted tap. Verified on a device: PIN entered before any tap, then tag detected to credentials listed in 2.3 seconds. (#449, thanks onatio22)

🔌 **SSH: the experimental sshlib engine handles more than one session per connection** — opening a shell and then running a command on the same connection used to fail, and take the whole connection down with it. The cause was upstream: sshlib registered a channel under the server's channel number and never released it on close, so the moment the server reused that number the client rejected its own channel. That's fixed in sshlib 0.4.1, along with an option Haven now sets — sshlib otherwise hangs up as soon as the last channel closes, which is right for a one-shot `ssh host command` but wrong for a connection Haven keeps open and multiplexes. Measured on the new version: twelve open-and-close cycles on one connection, where the first previously failed. The engine is still opt-in and JSch is still the default — this removes the main reason it had to be. (#58, connectbot/cbssh#238)

⚙️ **Connections: the experimental sshlib engine is described accurately again** — the SSH engine picker still said sshlib was "SFTP file browsing/transfer only" with "terminal and tunnels stay on JSch". That stopped being true in v5.83.8: it carries the whole connection — terminal, one-shot commands, file transfer and local/remote/dynamic port forwarding. What it still refuses is a jump host or proxy, hardware keys, OpenSSH certificates and multi-factor logins, and a second session on one connection can drop it. The picker now says that, in every language. It remains opt-in; JSch is still the default. (#58)

💬 **Security keys: a key that moves away mid-read says so** — both of Android's wordings for that ("is out of date" and "Tag was lost") now become "hold it flat against the phone without moving it until the operation completes — this can take a few seconds". Enumerating resident keys walks every service registered on the key, so it isn't a touch-and-lift.

## v5.83.14

🖱️ **Terminal: a tap no longer goes missing after the copy menu appears** — in an app that asks for the mouse (zellij, tmux, htop…), a tap that landed while Haven's text selection was showing was spent dismissing that selection and never reached the app. So once a selection appeared — say a press held slightly too long turned into one — the next tap did nothing visible, and you had to tap a second time before the app saw a click. That reads exactly like mouse reporting being broken. The click now belongs to the app: one tap dismisses the selection *and* is delivered. Outside mouse-mode apps nothing changes — a tap still just dismisses the selection.

Measured on a device rather than reasoned about: with a selection showing, two identical taps at the same cell previously put only one click on the wire. Now one tap clears the selection and forwards the click at exactly the tapped cell. (#435)

## v5.83.13

⌨️ **Terminal: selecting text in vim, less or plain tmux is responsive again** — v5.83.12 gave a press 900ms before it turns into a text selection, meant only for apps that ask for the mouse. It applied too widely: Haven also hooks the gestures of full-screen apps that take swipes as arrow keys but leave taps alone — vim, less, nano, a tmux session without mouse mode — and those got the longer hold too, so press-and-hold to select felt broken there for no benefit. The delay now follows the app's actual mouse-tracking state.

🖱️ **Tapping a zellij tab is still being looked at** — the v5.83.12 fix has not resolved it for the reporter, so if a tap still opens the copy menu instead of clicking, that case is open and being worked on. Mouse-mode detection has been ruled out as the cause: zellij's startup sequence is now pinned by a test and Haven reads it correctly. (#435)

🔌 **SSH: the experimental sshlib engine could silently lose a command's output** — running a one-shot remote command on the opt-in second SSH engine could return empty output for a command that had actually printed something. The underlying library hands output over an internal channel and discards anything still in flight when the connection closes the channel, which a short-lived command routinely triggers. Haven now starts reading both streams before the command is even sent. This only affects the experimental sshlib engine, not the default one. Reported upstream as connectbot/cbssh#245. (#448)

## v5.83.12

🖱️ **Terminal: tapping a zellij or tmux tab clicks it again, instead of opening the copy menu** — with an app that asks for the mouse (zellij, tmux, htop…), a tap only reached the app if it was *quick*. Long-press starts Haven's own text selection so the visible select-and-Copy workflow works inside a multiplexer, but that meant a click had to beat the system long-press timeout of around 400–500ms — and aiming carefully at a small target like a zellij tab routinely takes longer, so the press turned into a selection and the app never saw the click. Fast, careless taps worked, which is exactly why this looked fine. A press now gets 900ms before it becomes a selection, but only while the app has mouse mode on: a careful tap clicks, a deliberate press-and-hold still opens the copy menu, and nothing changes outside mouse-mode apps. (#435, thanks paour)

📡 **Mosh: a session that can't come back now reconnects instead of "retrying" forever** — when a mosh session went silent, Haven kept retransmitting indefinitely behind a "no server contact — retrying" banner that never resolved, and only closing the tab and reconnecting by hand recovered it. That was deliberate: silence usually means the network is away, and waiting it out is the whole point of mosh. But it is not the *only* cause — if the session is gone server-side, no amount of waiting helps. Haven now gives up after 45 seconds of silence **and** several unanswered recovery attempts **and** only when the device actually has a working network, then reconnects on its own. A phone in a pocket, a tunnel or a dead spot is still waited out indefinitely, exactly as before. Reconnects are limited to three with increasing gaps before it stops and leaves it to you, so an unreachable server isn't hammered. A session that never connects in the first place — server gone, port blocked — now recovers the same way instead of hanging silently. (#421, thanks dkoppenh)

## v5.83.11

🔑 **USB: a forwarded security key is usable on the remote host again, not just visible** — exporting a phone-attached YubiKey over USB/IP (Connection settings → USB forward device) produced a device that *enumerated* on the Linux host but never worked: no `hidraw` node appeared, so `ssh-keygen -t ed25519-sk`, `fido2-token` and browsers saw nothing, and the host's kernel log showed `usbhid: can't add hid device: -32`. The HID driver's first act is to read the report descriptor, which is an *interface*-addressed USB control request, and Android only routes those to an interface the app has claimed — Haven claimed interfaces for bulk transfers but never for control transfers, so that read stalled and the driver gave up. Haven now claims the addressed interface first. Verified end to end against a YubiKey 5 forwarded over an SSH tunnel: the key binds, and FIDO2 works with the touch happening on the phone. (Composite keys still expose FIDO only — the smartcard/CCID interface is deliberately withheld, because a host-side smartcard daemon polling it starves the FIDO side on Android's serialised USB access.)

🤖 **Agent API: the USB auto-forward device is readable and settable** — `usbForwardVidPid`, the profile field that drives the whole export-and-attach-on-connect path, was invisible to the agent API: `list_connections` omitted it and `update_connection` had no parameter for it, so an agent could see a broken forward but not diagnose or repair it without the UI. Both now cover it, and a malformed value is rejected at edit time rather than silently never matching a device at connect.

## v5.83.10

🌐 **Local Linux: the guest DNS setting now also covers package installs** — v5.83.9 made the guest's name servers configurable, but only refreshed `/etc/resolv.conf` when a *terminal* session started or a distro was installed. Installing a desktop (or anything else run inside the guest) goes down a different path, so on an existing distro those commands could still use the stale file — which is exactly the "installing Xfce silently hangs" case the setting was added to fix. Every command run in the guest now refreshes the resolver first. (#446)

## v5.83.9

🌐 **Local Linux: the guest's DNS is configurable, and now follows your network by default** — the Linux guest's `/etc/resolv.conf` was hardcoded to public name servers (Google + Cloudflare). On a network that only allows DNS to its own resolver — common on corporate and guest Wi-Fi — that fails *silently*: installing a desktop like Xfce simply hangs during background setup, with nothing pointing at DNS until you open a shell in the container. You can now pick **Network (DHCP)** — the new default, and the only option that works on every network — **Public (Google + Cloudflare)** for the old behaviour, or **Custom** name servers, under Desktop → Options & mounts → Guest DNS. It is rewritten on every guest launch, so changing it takes effect without reinstalling the distro, and it is also settable from the agent API — handy precisely when DNS is what's broken. (#446, thanks itskenny0)

## v5.83.8

🔐 **SSH: session keys rotate again on the sshlib engine** — Haven's second SSH engine had both of its rekey thresholds pinned to effectively "never", because client-initiated rekeying was broken upstream in sshlib 0.3.1: a byte-limit rekey killed an in-flight transfer, and an interval rekey silently wedged an idle session. Haven reported it (connectbot/cbssh#231) and it's fixed in sshlib 0.4.0, so that workaround is gone and connections re-key on the normal schedule (1 GiB / 1 hour) again.

🧪 **SSH: sshlib is now a full engine, opt-in and experimental** — putting `HavenSshEngine sshlib` in a profile's SSH Options now runs that profile's *whole* connection on sshlib — terminal, remote-command, one-shot commands, SFTP and port forwarding — instead of only its SFTP. JSch remains the default for every profile that doesn't opt in. The engine deliberately refuses what it can't do yet — jump hosts and proxies, FIDO2 security keys, OpenSSH certificates and multi-factor chains — with a clear error rather than quietly behaving differently, so move those profiles back to JSch. **Known limitation:** an upstream sshlib bug means a connection that opens more than one command channel can be refused and drop the connection — if you hit it, that's [connectbot/cbssh#238](https://github.com/connectbot/cbssh/issues/238), and the fix is to remove `HavenSshEngine sshlib` from that profile's SSH Options to go back to JSch. Haven names the issue in the error itself so you don't have to guess. Treat the engine as a preview rather than a daily driver; JSch remains the default and is completely unaffected. (#58)

## v5.83.7

♿ **Terminal: the keyboard-toolbar keys are now proper accessibility targets** — the configurable toolbar keys (Esc, Paste, the arrows, symbols and custom keys) were drawn with a low-level touch handler for press-and-hold key repeat that never exposed a "click" action. So screen readers like TalkBack couldn't activate them, and Haven's own agent-driving/self-hosting loop couldn't tap them either. They now expose a standard button action — keeping the press-and-hold repeat — so assistive tech and automated UI checks can operate every key. (accessibility / self-hosting loop)

🤖 **Agent API: `restart_app` finishes the self-update loop** — an agent can already stream a new build to the device (`install_apk_from_backend`), but Haven's persistent foreground service kept the old process alive, so the update only took effect after a manual Force-stop. The new `restart_app` verb kills and relaunches Haven's own process to apply a staged update (the MCP link drops on restart and reconnects), removing the last manual step from remote install-and-verify.

## v5.83.4

⌨️ **Terminal: the floating text input's selection toolbar now appears** — selecting text inside the floating text input box (v5.82.0) showed the selection handles but never the Copy / Cut / Paste / Select-all toolbar, so there was no way to act on a selection. The box is a focusable popup with no real DecorView, and Compose 1.11's new text-context-menu path silently no-ops there. Haven now renders the menu inside the popup's own window, so the toolbar shows and works. (#444, thanks kanazawahere)

⛶ **Terminal: the fullscreen button can be moved to any corner** — the ⛶ button that toggles terminal fullscreen was pinned to the top-right, where it could sit over content. Long-press and drag it, and it snaps to whichever corner you release it nearest; the choice is remembered per app. (#445, thanks PurpleMyst)

## v5.83.3

📁 **Files: the folder picker on the Local tab is no longer a trap** — the built-in "Local" Files tab showed "Upload folder" and "Upload file" buttons that couldn't work there (uploads target a connected remote, so on Local they just failed with "Not connected"). Worse, "Upload folder" sat right next to "Add folder location" and opened the same Android folder picker — so picking, say, your Termux home to *browse* it easily went through the wrong button and did nothing. Those upload buttons are now hidden on the Local tab, leaving "Add folder location" as the clear way to add a folder to browse. (#415, thanks timerloggedout-spec)

## v5.83.2

🔌 **RDP: connecting to VirtualBox works past login (empty Font Map)** — after v5.81.10 fixed the certificate handshake, RDP to a VirtualBox VM authenticated ("access granted") but then died immediately with a protocol decode error. VirtualBox's built-in RDP server sends an *empty* Server Font Map in the connection-finalisation handshake, and Haven's decoder rejected it as malformed. The Font Map is only a "you may start drawing now" signal and its contents are unused, so Haven now accepts an empty/short one and continues — the same leniency mstsc and FreeRDP have. Reported for VirtualBox; should also help other RDP servers that send a minimal Font Map. (#422, thanks pawlosck)

## v5.83.1

🔌 **Agent API: profiles' remote command is settable over MCP** — `create_connection` and `update_connection` now take `remoteCommand` (run via an SSH exec request instead of a login shell, e.g. `tmux new -A -s work`) and `requestPty`, and profiles report them back through `list_connections`. Previously the v5.83.0 remote-command field could only be set in the connection editor. (#436)

## v5.83.0

⌨️ **SSH profiles can run a remote command on connect — reliable tmux attach** — a new per-profile "Remote command (advanced)" field, with a "Request terminal (PTY)" toggle, runs a command via an SSH `exec` request instead of an interactive login shell. Because it runs *before* shell startup files (`.bashrc` and any auto-tmux hook), setting it to `tmux new -A -s work` attaches to — or creates — that exact named session every time, with no risk of racing a startup hook into the wrong or a duplicate session. It works over mosh too (passed as `mosh-server -- <command>`), so mosh's roaming resilience is preserved — unlike a server-side forced command, which mosh can't bootstrap through. The setting round-trips through encrypted backups. (#436, thanks kanazawahere)

## v5.82.0

⌨️ **Terminal: floating text input** — a new toolbar key opens a draggable, resizable text box floating over the terminal, so you can compose a whole command with your normal keyboard (autocorrect, swipe typing, voice input, cursor movement) and send it in one shot, instead of fighting the raw terminal cell character-by-character. Embedded newlines and tabs show inline as ↩ / ⇥ so you can see exactly what will be sent, and the text is bracketed-paste-wrapped on send, so a multi-line block arrives as a paste instead of executing line-by-line. Unsent drafts are kept per tab and survive rotation; the window position/size is remembered. The key sits on the default toolbar and can be moved/hidden like any other key. Ported from ConnectBot's Text Input dialog (Apache-2.0).

## v5.81.13

🔄 **RDP: sessions survive a server-side "reactivation" instead of dying** — an RDP server is allowed to renegotiate the session mid-flight (a Deactivation-Reactivation sequence): FreeRDP's shadow server does it right after connect to resize you to its display, and Windows does it when the desktop resolution changes. Haven treated it as a fatal protocol error and the session died — this is what v5.81.12 made fail *honestly*, and this release makes it not fail at all. Haven now re-runs the capability exchange, adopts the new desktop size (the viewer resizes), and carries on. Verified end-to-end against a FreeRDP shadow server whose display size differs from the phone's: the session now connects, resizes, and renders where it previously died within a second. A Windows resolution change mid-session should now survive too, though that exact scenario is untested. (#438)

## v5.81.12

🪦 **Remote desktops: a dead session no longer pretends to be connected** — when an RDP/SPICE session died right after the handshake (a protocol error, a server restart, a network drop the session loop shrugged off), the desktop tab kept claiming "connected" while showing a frozen or black frame — and worse, tapping Connect again silently did nothing until you closed the zombie tab by hand. Fatal protocol errors now surface as real errors, a server-side session end flips the tab to a new "disconnected" state (shown in the connections list and `list_desktop_sessions`), reconnecting replaces the dead tab instead of no-opping, and the session's end is written to the connection audit log. Verified on-device against a server that kills the session milliseconds after connect. (#437)

🔌 **Agent API: `disconnect_profile` now closes desktop tabs** — for directly-connected (non-SSH-tunnelled) VNC/RDP/SPICE profiles, the MCP `disconnect_profile` verb tore down the transport but left the desktop tab open. It now closes the tab too, so `list_desktop_sessions` reflects reality. (#437)

## v5.81.11

🟦 **Windows RDP: the remaining black squares are fixed (NSCodec)** — the logcat on #418 finally showed what the persisting black rectangles actually are: not the progressive "upgrade" tiles v5.81.8 targeted, but regions Windows compresses with an embedded **NSCodec** sub-stream inside ClearCodec, which Haven logged and skipped on the (wrong) assumption Windows doesn't emit it for desktop UI. Windows 10 1909 and Windows 11 25H2 both do. Haven now decodes NSCodec — plane RLE, AYCoCg colour recovery, chroma subsampling — so those regions paint instead of staying black. Verified against 7 new decoder unit tests ported from the FreeRDP reference; not yet confirmed against a live Windows desktop, so feedback on #418 is very welcome. The same logcat confirmed the v5.81.8 upgrade-tile toggle does engage — the squares it couldn't fix were these NSCodec regions. (#418, thanks ZGLinus)

## v5.81.10

🔌 **RDP: connecting to VirtualBox (and other v1-certificate servers) works again** — RDP to a headless VirtualBox VM failed at the TLS handshake with "server certificate problem (UnsupportedCertVersion)". Haven's certificate pinning ran the server's certificate through a parser that rejects the older X.509 **v1** format outright — and VirtualBox's built-in RDP server presents a v1 certificate. Haven now accepts v1 certificates: it still pins the exact certificate on first connection and still verifies the server holds the matching private key, so the man-in-the-middle protection is unchanged; only the needless version check is dropped. (#422, thanks pawlosck)

📶 **Mosh: sessions recover after a network change instead of hanging** — when the phone roamed between networks (Wi-Fi ↔ mobile, or a VPN blip), a mosh session could wedge: the red "no server contact — retrying" banner stayed up and never came back, and you had to close and reopen. The socket rebuild that recovers a roamed connection closed the old socket before making the new one, so if the network wasn't ready at that instant the session was left with a dead socket and stopped trying. It now keeps the working socket until the replacement is ready and retries on the next cycle, so the session reconnects on its own once connectivity returns. This is the real fix behind #421 — v5.81.7 only stopped the banner freezing. (#421, thanks dkoppenh)

🖱️ **Terminal: touchpad two-finger scroll now actually scrolls** — the real fix for the OPPO Pad 3 Pro report. Android delivers a physical touchpad's two-finger scroll on a separate "indirect pointer" channel, which the terminal wasn't listening on — so the gesture scrolled the app's own lists but did nothing in the terminal (a mouse wheel uses a different channel and already worked). The terminal now handles that channel, so touchpad scroll moves the scrollback and forwards to apps like tmux/vim/less. The v5.81.6 attempt (declaring scroll semantics) couldn't route it. I still can't reproduce it here without the hardware, so please confirm. (#419, thanks wxjiee)

## v5.81.9

🖥️ **RDP: KDE's KRDP server now works (H.264/AVC420)** — KRDP (the RDP server built into KDE Plasma) encodes its screen only as H.264 and won't fall back to the codecs Haven already supported, so connecting to a Plasma desktop over RDP showed a black screen. Haven now decodes H.264/AVC420 on-device using the phone's hardware video decoder (MediaCodec), so KRDP renders — verified against a live KRDP session. It's **on by default**. This also changes how Haven negotiates graphics with Windows and xrdp: they keep working, but Windows now uses AVC420 rather than the higher-quality AVC444 (that's a later addition). If a Windows or xrdp desktop looks wrong, turn it off under Settings → Diagnostics → "RDP: H.264/AVC420 decoding". (#425, thanks ysalmon)

## v5.81.8

🧪 **Windows RDP: experimental fix for the black squares (opt-in)** — the v5.81.5 colour fix left scattered black squares on some Windows RDP desktops, because Haven decodes the first pass of each RemoteFX-Progressive tile but not the later "upgrade" refinement passes. This adds that decoder — but it's **off by default and experimental**, because it isn't yet verified against real Windows and a bad decode could look worse than the black squares. To try it: Settings → Diagnostics → "RDP: decode progressive upgrade tiles". If the picture looks worse, turn it off. Feedback on #418 very welcome. (#418, thanks ZGLinus)

## v5.81.7

📶 **Mosh: the "no server contact — retrying" banner no longer gets stuck** — when a mosh session actually ended (the server exited, or a fatal transport error), the red "No server contact for Ns — retrying" banner could freeze on screen forever, reading as a reconnect that never happens. The banner now clears when the transport closes, so it only shows while a live connection is genuinely stalling. This is a first fix toward #421 (mosh disconnects when scrolling tmux scrollback); the underlying disconnect is still being investigated. (#421, thanks dkoppenh)

## v5.81.6

🖱️ **Terminal: touchpad two-finger scroll on some tablets** — on certain OEM tablets (reported on OPPO Pad 3 Pro) a Bluetooth-keyboard touchpad's two-finger scroll moved the app's own lists but did nothing in the terminal. Those touchpad drivers only send a scroll to a view that advertises itself as scrollable, which the terminal wasn't doing. The terminal now declares vertical scroll semantics (a mouse wheel already worked), which should route the gesture through — and, as a bonus, makes the terminal scrollable to TalkBack. Candidate fix, since I can't reproduce it here — feedback welcome. (#419, thanks wxjiee)

## v5.81.5

🎨 **Windows RDP: colour is back** — connecting to a Windows desktop over RDP rendered the whole screen as a flat embossed grey (looked like a relief carving, wallpaper and all). The RemoteFX-Progressive decoder was dropping the low-frequency (DC) part of every tile in the "extrapolate" mode that Windows 8/10/11 always use, so flat areas collapsed to mid-grey and only edges survived. The decoder now reconstructs the DC band in that mode too, and colour returns. (#418, thanks ZGLinus)

## v5.81.4

🗂️ **Linux desktop: your files (/sdcard) now show up** — when you launched a desktop from Haven, the file manager opened `/sdcard` (and `/storage`) to an empty list, even though the terminal could see them. Shared storage is now mounted into the desktop and into apps launched inside it, so your Downloads, DCIM and the rest are reachable there like they are in the terminal. Honours the existing "share storage with guest" setting. (#420, thanks sugerpersion)

## v5.81.3

🗂️ **Mosh & Eternal Terminal: the Files tab now actually lists** — a Mosh (or Eternal Terminal) connection showed a Files tab, but opening it just said "File browser failed" over an empty list. Browsing rides the SSH connection kept alive from the Mosh handshake, and Haven wasn't wiring that connection into the file browser, so it could never list. It now does — the tab lists the remote folder like any SSH/SFTP tab. (#413, thanks dkoppenh)

## v5.81.2

🔌 **Tasker / MacroDroid plugin: pick your variables from beside the command field** — the "run a command on a Haven server" action's edit screen now has a "…" button next to the command field. It lists the variables your automation host makes available and inserts the one you choose at the cursor, so you no longer have to type them by hand. The command is also now declared as variable-bearing, so your Tasker/MacroDroid variables are reliably substituted into it before it runs. (#367, thanks ehoeve786)

🗂️ **SFTP: no more wrong permissions on rclone connections** — rclone's SFTP backend can't report Unix file modes, so Haven had been filling the permission column with a fabricated `-rw-r--r--` — misleading for anything like a `0600` key. It now leaves that column blank for an rclone-SFTP listing instead of showing a made-up value; for real, editable permissions, use a native SSH/SFTP connection. (#413, thanks dkoppenh)

🗂️ **Mosh: correctly hide the Files tab when the bootstrap SSH has silently dropped** — the tab should show only while the underlying SSH is alive, but JSch can keep reporting a quietly-closed session as connected, leaving a Files tab that never lists. Haven now probes the transport with a bounded round-trip, so a dead connection hides the tab as intended. (#413, thanks dkoppenh)

## v5.81.1

🛠️ **Fix a rare crash when switching back to a saved SFTP tab** — restoring a cached SFTP connection pre-warmed its session in the background without error handling, so if the server tripped JSch's "inputstream is closed" glitch during the handshake, the app could crash. It now fails quietly and the next action opens a fresh session with the normal retry. (#416, thanks mintleaf84)

## v5.81.0

📁 **Files: add any folder as a browsable, editable location** — pick a folder once with Android's folder picker (your Termux home, a USB drive, the Downloads tree, a cloud provider like Nextcloud…) and it becomes its own tab in the Files screen. Browse it, and open a text file to edit it in place — make your changes, tap save, and Haven writes them straight back through the folder's provider. The access is remembered across restarts, and you can drop a location any time from the unlink button on its tab. Plain-text and extension-less files (common in a Termux home) now open in the editor with a single tap. Split out from #273. (#415, thanks timerloggedout-spec)

## v5.80.0

🔌 **Tasker / MacroDroid plugin: use a command's output in your macro** — the "run a command on a Haven server" action can now hand its result back to Tasker/MacroDroid as local variables: `%hstdout` (the output), `%hstderr` (any errors), and `%hexit` (the exit code, `-1` if the connection failed). Turn on "wait until the command finishes" and the variables are ready for the next step — branch on the exit code, show the output in a notification, or feed it into whatever comes next. (#367, thanks ehoeve786)

## v5.79.1

🗂️ **SFTP: recover from an "inputstream is closed" glitch when listing folders** — some servers (notably local VM SSH servers) could trip JSch's `inputstream is closed` error partway through a directory listing, and the browser would just fail. Haven now resets the SFTP channel and retries the listing once instead of surfacing the error. (#412, thanks mintleaf84)

## v5.79.0

🔌 **Tasker / MacroDroid plugin: run a command on a Haven server** — a native automation action. Pick one of your saved SSH connections, type a shell command (your Tasker/MacroDroid variables are substituted into it before it runs), and optionally wait for it to finish or watch it run live in a terminal. Add it as a plugin action in Tasker or MacroDroid — or drive it with a plain Send-Intent. (#367, thanks ehoeve786)

🗂️ **Mosh: no more empty Files tab** — a Mosh connection now only shows a Files tab while its underlying SSH connection is alive. Mosh's own transport can't carry file browsing, so a dropped SSH connection used to leave a Files tab that never listed anything. (thanks dkoppenh)

## v5.78.0

🔐 **rclone remotes now show real file permissions** — an SFTP (or any) connection set up through rclone used to display a generic `-rw-r--r--` for every file and `drwxr-xr-x` for every directory, regardless of the actual mode, because Haven's rclone listing dropped the per-file metadata that carries permissions. Haven now reads the real Unix mode from rclone's metadata for backends that expose it (SFTP, local); cloud backends without Unix permissions (Drive, S3, …) keep a sensible default. (#413, thanks dkoppenh)

✋ **Tap a file's permissions to change them** — the permissions editor (octal or read/write/execute checkboxes, single file or multi-selection) has been here since v5.13.0 but was only reachable by long-press. Now the `rwxr-xr-x` text on each row is a tappable link that opens the editor directly; the long-press menu still works. (#414, thanks dkoppenh)

🧱 **Dependency refresh** — Kotlin 2.4.10, rclone 1.74.4, jsch 2.28.4, sshd-core 2.19, tink-android 1.23, x509-cert 0.3, rustls 0.23.42, zxing 3.5.4, and a batch of other library updates.

## v5.77.0

🗂️ **Files: the show-hidden toggle now applies to the tab you switch to** — the eye / show-hidden button is a single global toggle, but switching between Files tabs (Local, an SFTP server, …) used to restore each tab's list exactly as it was last filtered. So turning show-hidden on in one tab and switching to another left the second tab still hiding dotfiles while the eye icon said they were showing. Each tab now re-applies the current show-hidden (and name-filter) state when you switch to it, so the list always matches the icon. Device-verified on two tabs (Local + an SFTP host): dotfiles appear and disappear on the tab you switch to, in step with the toggle.

## v5.76.0

⌨️ **Full-screen terminal apps now reflow for the on-screen keyboard automatically** — opening the keyboard used to push the top line of a full-screen app (mutt's header, vim's status line, less, htop) off-screen unless you'd turned on **Settings → Terminal → "Resize terminal for keyboard"**. Now any app on the terminal's alternate screen resizes itself to fit above the keyboard, so its header stays visible — without touching the setting. Ordinary shell prompts are unchanged (they still scroll, so long output isn't squeezed into a few rows). Device-verified: with the keyboard up, entering the alternate screen drops the terminal from 40 to 25 rows and exiting restores it. (#407, thanks gitcodeerrors)

## v5.75.0

🧹 **Closing a local Linux terminal now really stops it** — closing a local Linux (proot) terminal used to leave its background processes running: proot doesn't stop its own children when the launcher is signalled, so the guest kept holding memory until you force-killed the app (the "proot is hard to kill" complaint). Haven now reaps the whole process tree on close. And the optional **Auto-stop idle Linux guest** (v5.74.0) — which didn't actually reclaim anything on-device — now really does, including a guest whose terminal has already detached in the background. (#409, #411, thanks sugerpersion)

## v5.74.0

⏱️ **Auto-stop the idle Linux guest** — an optional timeout that stops the on-device Linux guest (its terminals and desktops) after Haven has sat in the background for a while, to reclaim memory. Off by default; turn it on in **Settings → Advanced → "Auto-stop idle Linux guest"** and pick 5, 15, 30 or 60 minutes. A running guest service keeps the guest up, and returning to Haven before the timeout cancels the stop. Note: a long job left running in a backgrounded terminal would be stopped too, which is why it's off by default. (#409, thanks sugerpersion)

## v5.73.0

🔌 **USB-serial connections are now first-class across Haven** — a USB-serial terminal (Arduino, Duet3D, ESP32, a USB-TTL adapter…) is now integrated the same way Bluetooth and BLE serial already were. It shows up in the agent's session list, the agent can type into it and read it back, and it can be exposed as a local TCP port via the serial↔TCP bridge (v5.72.0) so a tunnel or SSH port-forward carries the device off-phone. USB-serial tabs are also captured in workspaces now, and disconnecting a USB-serial connection works from every screen. Device-verified end-to-end against a USB-CDC adapter.

## v5.72.0

🧹 **Closing a serial terminal tab now actually closes it** — closing the tab of a Bluetooth, Bluetooth-LE or USB serial connection did nothing: the session stayed connected and the tab reappeared straight away. The close path didn't know about the three serial transports, so it quietly did nothing. Closing (and disconnecting from the Connections screen) now tears the serial session down properly, the same as SSH, Mosh or a local shell. Verified end-to-end over BLE against an nRF peripheral.

🔀 **Bridge a serial device to a local TCP port** — a live Bluetooth, Bluetooth-LE or USB serial session can now be exposed as a raw TCP port on the phone (127.0.0.1) through the agent (MCP), so an SSH port-forward or a tunnel can carry the device off-phone — read a sensor or drive a board remotely while the on-phone terminal keeps working. Raw bytes both ways, no framing; the terminal tab keeps running while it's bridged. Verified end-to-end: a host reached an nRF over BLE through the bridge and got its echo back byte-for-byte.

## v5.71.0

📡 **Bluetooth LE serial connections** — connect to a Bluetooth-LE UART peripheral — an nRF board, an HM-10 module, or a BLE RS232 adapter — and get a full terminal, the same as SSH, Mosh, or the Classic Bluetooth-serial console. Unlike Classic Bluetooth serial, a BLE peripheral doesn't need to be paired: add a connection, tap **Scan**, pick the advertising device, and connect. The GATT service is auto-detected (Nordic UART Service, then HM-10). Verified end-to-end against an nRF UART peripheral. (This is the BLE counterpart some Bluetooth adapters need — a WF610-style dual-mode box can now be reached over LE, not only Classic SPP.)

## v5.70.0

🔌 **USB-serial devices that gate on DTR now work** — Haven now raises the DTR and RTS lines when it opens a USB-serial connection, exactly as a desktop terminal (PuTTY, screen, minicom) does. Some devices stay silent — sending and receiving nothing — until the terminal asserts DTR: many Arduino sketches that wait on `while (!Serial)`, and boards that only start streaming once a client "opens" the port. Verified end-to-end against a USB-CDC device. (#408)

🎛️ **Full serial line settings in the USB-serial editor** — a USB-serial connection now lets you set data bits, parity, stop bits and flow control alongside the baud rate, so you can talk to a device that isn't the usual 8N1 (a 7E1 sensor, a hardware-flow-control link, and so on). Defaults stay 8N1 with no flow control. (#408)

## v5.69.0

🔌 **USB-serial terminal connections** — plug a USB-serial device into the phone — an Arduino, a Duet3D G-code board, an ESP32, or a USB-to-TTL adapter — and get a full terminal, the same as SSH, Mosh or the Bluetooth-serial console. Add one from the connection editor, pick the attached device and a baud rate, and connect; Android asks for USB permission on the first connect. Works with the common adapter chipsets (CDC-ACM, CH34x, FTDI, CP21xx, Prolific). (#408)

☁️ **Two cloud-storage connections of the same type no longer overwrite each other** — setting up, say, two SFTP connections used to save both under one internal rclone remote, so the second clobbered the first and both listed the same host's files. Each connection now gets its own unique remote, so same-provider connections stay independent. If you already have a colliding pair, re-create one after updating to give it a fresh remote. (#410, thanks dkoppenh)

🖥️ **GNOME Remote Desktop: a clear message instead of a black screen on redirect** — GRD hands a connecting client off with a server-redirection message that Haven couldn't decode, so the session went black with no explanation. Haven now recognises the redirect and reports it precisely — naming the target — instead of dying silently. (Automatically *following* the redirect is still to come.) (#117)

🌍 **Serial-connection screens fully translated** — the Bluetooth-serial and USB-serial connection editors are now available in all supported languages, not only English.

## v5.68.70

🎨 **mutt and other full-screen terminal apps get their colours back** — since v5.51.0 Haven repainted the 16 standard ANSI colours to match your chosen scheme. That's fine for most prompts, but full-screen apps like mutt pick specific ANSI colours on purpose — so mutt's background turned an unreadable yellow and its headers lost contrast. Haven now leaves the 16 ANSI colours at their standard values by default, so those apps look the way they're meant to again. If you liked the theme-matched colours, a new **Settings → Appearance → "Apply scheme's ANSI palette"** toggle turns them back on. (#407, thanks for the version bisect that pinned it to v5.51.0)

📟 **Bluetooth-serial console connections** — connect to a device's serial console over a paired Bluetooth (Classic SPP) adapter and get a full terminal, the same as SSH or Mosh. Add one from the connection editor and pick a paired device — handy for switches, routers and embedded boards with no network access. (#406)

📡 **Following a hotspot-tethered device, third revision** — when your phone *is* the hotspot, Android hands it a fresh random subnet each session and never names that network to apps, so the previous re-discovery still searched the wrong place. Haven now enumerates the phone's own network interfaces to find the tethered device directly. (#367/#376, thanks ehoeve786 — still awaiting an on-device confirm)

## v5.68.69

📡 **Following a moved device now works when your phone is the hotspot** — yesterday's fix got the re-discovery to actually run, but it then looked on the wrong network. It searched the phone's internet-facing network (your mobile data), when the device it's hunting for is on the *hotspot* the phone itself provides. It now also searches the network the device was last seen on — which, since an address change keeps a device on the same local network, is exactly where it still is. The everyday "everything on one Wi-Fi" case is unchanged. (#367/#376, thanks ehoeve786)

## v5.68.68

🖥️ **Closing a fullscreen desktop tab no longer locks you out of the app** — tap the X while a VNC/X11 tab was fullscreen and the tab closed, but Haven never came back out of fullscreen: no top bar, no bottom bar, and because the same state also switches off swiping between tabs, the way out was disabled by the very thing you needed to escape. Not even rotating helped; only force-stopping the app. Now the bars come back, and as a backstop a fullscreen state can't outlive the session it belonged to. (#386, thanks sugerpersion)

📡 **Automations follow a device that changes address again** — Haven has been able to re-find a saved host by its SSH key since v5.68.44, but in practice it never once ran. The check deciding "did the host fail to answer, or did it answer and reject me?" — the thing that stops a wrong password sending Haven hunting around your network — didn't recognise the most common way a moved device fails, because Android words that particular error differently from the ones it was looking for. So `run_command` just reported the dead address instead of going and finding the box. Tapping the profile by hand had the same blind spot. (#367/#376, thanks ehoeve786)

⌨️ **The keyboard can finally read the terminal's input line** — Haven never answered when a keyboard asked what was actually in the field; it just said "nothing". Most keyboards cope, but a predictive one that keeps its own copy of what you're typing has nothing to correct it against, so after you press Enter it can keep building on the line it already ran — typing `ls` twice sent `lsls`. It now reports the real contents, and keeps them updated. If you use SwiftKey in Standard keyboard mode, this is the one to try. (#298, thanks agross for the diagnostic log that finally pinned it)

## v5.68.67

🛠️ **F-Droid build: the last component that was still using the wrong tool** — the previous two releases pointed most of the Wayland stack at our own `wayland-scanner`, but one component (wlroots) is built from a second place in the script that never got told, so on F-Droid it went on looking for a scanner the build image doesn't have. Every component is now told once, centrally, so a new one can't be forgotten. Equally important: the check for this no longer asks "did it build on my machine" — which could never fail, because this machine happens to have the very tool F-Droid lacks — but asks *which* tool each component actually used. No change to the app itself.

## v5.68.66

🛠️ **One more F-Droid build fix, found by running their build rather than guessing at it** — the Wayland stack's symbol-stub generator sorts two lists and compares them, and the two tools disagree about ordering unless the locale is set to C. It exits with an error *after* the library has already been built, so the build fails holding a finished-looking file. Nobody had ever seen it, because since v5.68.41 the F-Droid build has died earlier and never reached this step. No change to the app itself.

## v5.68.65

🛠️ **F-Droid builds, take two** — v5.68.62 was supposed to unblock F-Droid and didn't. The Wayland desktop stack needs a `wayland-scanner` that runs on the build machine and matches our vendored Wayland exactly; v5.68.62 started building one from our own source, but the cross build never actually *used* it — it went on quietly resolving the build image's copy, which is the very thing that was breaking. Once the now-unnecessary system package was dropped from the F-Droid recipe there was nothing left to mask it, and the build stopped at "wayland-scanner not found". Our scanner is now the one it finds, and an image that ships a different version can no longer hijack the lookup. No change to the app itself.

## v5.68.64

🔗 **The terminal stopped mistaking filenames for web links** — a tap on ordinary text like `nginx.conf`, `php.ini` or a line of a Java stack trace would underline it as a link and throw you out to a browser at an invented address such as `https://nginx.co`. Two things were wrong with the link detector: it matched the *start* of any word whose dotted tail happened to begin with a domain ending, and it counted `.in`, `.cc` and `.app` as domain endings even though in a terminal those are almost always `Makefile.in`, `main.cc` or a package name. A detected link now has to end where the word ends. Real links are untouched, bare ones like `google.com` included. (#385, thanks sugerpersion)

## v5.68.63

🔗 **Android binaries in the guest no longer moan about the linker config** — with "Expose Android system to guest" on, everything you ran from `/system/bin` opened with a "failed to find generated linker configuration" warning, because Haven wasn't exposing Android's `/linkerconfig/ld.config.txt`. It couldn't simply mount the folder — Android won't let the app look at it — but it can read the file inside, and mounting that is enough. The warning is gone and nothing else changes. (#384, thanks sugerpersion)

## v5.68.62

🛠️ **F-Droid builds work again** — every F-Droid build since v5.68.41 has failed before compiling a single file, so none of the releases after it ever reached F-Droid users. The Wayland desktop stack is cross-compiled here, and that needs a `wayland-scanner` that runs on the build machine and matches our vendored Wayland exactly; we were borrowing the build image's copy, and F-Droid's image moved to a newer one than we vendor. Haven now builds the scanner from its own source, so the image's version no longer matters and this can't break again on their next update. No change to the app itself.

## v5.68.61

⏱️ **A jump-host connection that goes quiet now fails instead of spinning forever** — a connection through a jump host runs over a tunnelling channel rather than a socket, so the connect timeout never applied to it: if the machine on the far side accepted the channel but said nothing, Haven waited indefinitely, showed no error, and wrote nothing to the connection log. It now gives the far side a deadline to say hello, and reports what happened — naming the hop that went quiet — instead of leaving a spinner turning. Only that first hello is on the clock; typing a password, entering a TOTP code or touching a security key is never rushed. (#383)

## v5.68.60

🖥️ **The terminal can no longer lose a shell's first output** — the same JSch trap fixed for jump hosts in v5.68.59 also sat on the interactive shell: Haven opened the shell channel and only bound its streams afterwards, and anything the remote sent in that gap (login banner, MOTD, first prompt) was silently discarded. It bit far more rarely there than on a jump host — a shell's first output waits for the remote shell to start, while a jump target's SSH banner is already in flight — so no one reported it; it was found while fixing #381 and is closed the same way, by binding the channel's streams before it is opened. (#382)

## v5.68.59

🔗 **Connecting through a jump host no longer stalls on the first attempt** — with `ssh -J`, the first tap connected the jump host and then hung: the target's spinner span forever, nothing opened, and no connection log was written. Haven was opening the tunnelling channel before binding its streams, so the target's SSH banner — sent the instant the channel opens — landed in a gap where JSch silently discards incoming bytes, and the connect then waited forever for a greeting that was never resent. Tapping again usually won the race, which is why it "worked the second time" while leaving a dead session behind. The channel's streams are now bound before it is opened. (#381, thanks BlackDex)

## v5.68.58

🖱️ **The mouse wheel works in the terminal** — with a hardware mouse, the scroll wheel did nothing; only click-drag scrolled. Wheel events carry no pressed pointer, so the terminal's gesture handler never woke for them and they were dropped. Each notch now goes wherever a swipe would: to an app that asked for the mouse (tmux mouse mode, vim, less), as arrow keys on a full-screen TUI, or through Haven's own scrollback.

🔁 **Reconnecting drops you back into the session you left** — a Mosh or Eternal Terminal profile that uses a session manager (tmux/zellij/screen) now re-attaches to the session it was last on instead of stopping to ask. Your shell comes back exactly where it was, even after the app restarts. The picker still appears when there's a real choice to make: nothing remembered, the remembered session gone, or several to pick from.

🔗 **Copying a folder no longer follows symlinks into a loop** — a symlink met while copying is copied as a link rather than descended into, so a link pointing back up its own tree can't spin forever.

Also in this release: SFTP and SMB now create missing parent directories when asked to make a nested path (they previously created a single level and failed), and a batch of internal tidying — shared shell-quoting, the file-browser copy/paste routed through one backend interface, and the connection editor's four SSH-tunnel blocks collapsed into one.

## v5.68.57

🔑 **Passphrase-protected keys work without pinning them to every profile** (#381) — when a connection had no specific key assigned, Haven's "try any saved key" fallback only offered plaintext keys and silently skipped passphrase-protected ones. So if your only key has a passphrase (with the passphrase stored in Haven), it wasn't offered unless you explicitly assigned it to each host — which is why a jump host could fail "Auth fail for methods publickey" until the key was pinned to it. The auto-selection now offers a stored-passphrase key too. Thanks to BlackDex for the thorough testing that isolated this.

## v5.68.56

🔑 **Jump-host connections with a passphrase-protected key work again** (#381) — if a jump host had both a saved password and a key whose passphrase is stored in Haven, connecting *through* it failed with "Auth fail for methods publickey" (while connecting to the same host directly worked). The jump leg was handing the saved login password to the key as its passphrase, so the key couldn't be decrypted and was never offered. The jump now uses the key's own stored passphrase, matching a direct connect. Thanks to BlackDex for the diagnostic logs.

## v5.68.55

🔬 **Terminal input diagnostics for the SwiftKey composition bug** (#298) — added detailed InputConnection logging (what the on-screen keyboard reads back from the terminal field, plus the line-boundary reset) so the long-standing "a word from the previous command sticks to the next one" problem with prediction-heavy keyboards can finally be diagnosed from a captured log rather than a video. No behaviour change. If you hit this, turn on Settings → Diagnostics → Logcat Capture (or Verbose connection logging), reproduce it, and the log now shows exactly what the keyboard did.

## v5.68.54

🔎 **Jump-host connection failures are now diagnosable** (#381) — a connection made through a jump host (ProxyJump) used to record nothing in the connection log when the jump leg failed, and captured no verbose SSH detail, so an auth failure on the jump host left you with no way to see *why*. Jump-host connects now log their result to the connection log, and — with Verbose connection logging on (Settings → Diagnostics) — capture the full SSH protocol trace, so a failed jump shows which key and signature algorithm the server rejected. (Diagnostics for the jump-host auth reports in #381.)

## v5.68.53

🩹 **A session manager that fails to start no longer kills the tab** (#294) — if you pick tmux/zellij and it's installed but can't start (for example tmux failing to create its socket under proot, or an option an older build rejects), the local shell used to exit instantly with the tab dying and no clue why. Now the failure is left on screen (and in the connection log), and you drop into a normal login shell instead of being ejected. A clean detach or quit still ends the tab as before.

## v5.68.52

❌ **One-tap close on the active terminal tab** (#306) — the selected tab now shows a close (×) button, so ending a session no longer needs a long-press to reach the tab menu. A tab whose session manager (tmux/zellij) is still running keeps its session alive as before — this just makes closing the tab you're looking at a single tap. Thanks to sugerpersion for the nudge.

## v5.68.51

🛡️ **Confirm before deleting a distro's rootfs** (#379) — in the Desktop distro menu the delete button sat one tap from Open-shell, so a mistap could silently wipe a whole rootfs. Deleting now asks first, naming the distro and warning that its installed packages and files are lost (it re-provisions the next time you open it). Thanks to the reporter for flagging the easy mistap.

## v5.68.50

🤖 **Agent endpoint: manage trusted SSH host CAs** (#133) — new tools `list_trusted_host_cas`, `add_trusted_host_ca`, and `delete_trusted_host_ca` let an automation add or remove a trusted host CA (the certificate authority that lets a server connect without a fingerprint prompt) without touching the Keys screen — the same data plane the known-hosts tools already had. Adding or removing a trust anchor prompts for consent.

🔎 **Agent endpoint: the UI tools now see and drive dialogs and menus** — `dump_haven_ui` and `tap_haven_ui` previously reached only the main screen, so an automation couldn't read or tap a dropdown menu or dialog that popped over it. They now cover those pop-up windows too. (The on-screen consent prompt stays a human-only gate — injected input can never reach it.)

## v5.68.49

🔐 **Trust SSH hosts by their CA** (#133) — Haven now honours OpenSSH host certificates (`@cert-authority`). Add a trusted SSH host-CA public key and any server that presents a valid certificate signed by that CA connects with no per-host fingerprint prompt — signature, validity window, principals and revocation are all checked during the handshake. Hosts without a valid CA-signed certificate fall back to the usual trust-on-first-use prompt, unchanged. A trusted host CA is added under Keys → Certificate authorities, and now saves on its own without needing a full OIDC provisioner set up (#380). Known limitation: RSA host CAs aren't validated by the current SSH library (a signature-algorithm quirk) — Ed25519 and ECDSA host CAs work.

## v5.68.48

⌨️ **Optional Termux-style key grid for the toolbar** (#372) — a new "Uniform key grid" switch in Settings → Keyboard & input → Keyboard toolbar lays every key out in equal-width cells: the whole row fits on screen with no side-scrolling, columns line up across both rows, the entire cell is the tap target, and longer labels wrap inside their cell. Arrow keys join the grid as ordinary cells. Off by default — the classic adaptive-width layout is unchanged. Thanks to sugerpersion for the suggestion.

## v5.68.47

📦 **`fakeroot` and `makepkg` work out of the box in proot distros** (#375) — Android kernels ship without SysV IPC, so fakeroot's default transport died with "Function not implemented" (Arch `makepkg` being the usual casualty). It turns out the bundled proot has carried a SysV IPC emulation extension all along — it was just never switched on. All proot launch paths (shells, one-shot commands, desktops) now run with `--sysvipc`: message queues, semaphores and shared memory are emulated inside the guest. Device-verified with `ipcmk` and `fakeroot true` on an emulated-architecture guest, the most demanding configuration. The stock Arch fakeroot now works without the AUR `fakeroot-tcp` bootstrap; the v5.68.42 shim remains for guests that prefer the TCP variant. Thanks to sugerpersion for pushing on this.

## v5.68.46

🔑 **SSH agent forwarding now works with passphrase-protected keys** (#377) — the stored passphrase decrypts the key as it's added to the in-app agent, so forwarded identities actually authenticate on the far side. Automations get matching agent-endpoint controls: `storedPassphrase` and a per-connection `forwardAgent` toggle on `update_connection`. Thanks to BlackDex for the report.

🤖 **Agent endpoint: terminal feed/snapshot no longer bind a stale emulator** (#378) — opening a local shell over the agent endpoint just as the Terminal tab was being built could wire `feed_terminal_output`/`read_terminal_snapshot` to an invisible 24×80 emulator while the on-screen tab ran its own; fed bytes never rendered and snapshots reported frozen geometry. The session registry now converges every ordering on the visible tab's terminal — device-verified: snapshot geometry matches the tab and fed markers render on screen.

⌨️ **Compose (中) mode can be switched off again** — the 0.1.1 terminal-engine merge dropped the exit path, so once compose mode was on, neither the toolbar toggle nor the agent verb could leave it. Toggle-off is restored and commits any pending composition into the terminal instead of dropping it.

🧹 **Agent endpoint: `list_known_hosts` / `forget_known_host` verbs** — inspect and prune trusted SSH host keys from automations, e.g. after redeploying a server changes its identity.

## v5.68.45

🔄 **Terminal engine synced with upstream connectbot/termlib 0.1.0** — 89 upstream commits merged into Haven's fork: vsync-aligned damage batching with less redraw work, scroll position preserved across snapshot updates, a public URL-scanning API, and the Kotlin 2.3.21 toolchain. Haven's device-verified IME, gesture, and keyboard-reflow stacks carry over unchanged.

🔗 **Better URL taps** — trailing sentence punctuation is trimmed before opening (a URL ending "…/issues/78." no longer takes the dot into the browser), URLs are underlined from their first row (previously only wrapped continuation rows drew underlines), and a screen stacking several URLs on adjacent lines can no longer glue them into one giant link. Wrapped-URL handling — Claude Code's `⎿` decorations, markdown tables, hanging-indent tails, column-boundary wraps — re-verified on device with real taps, 5/5 cases opening exactly the right URL.

⌨️ **Compose (中) mode is now sticky** — Enter commits the line and Escape cancels the buffer, but the mode itself stays active until you toggle it off. Previously every Enter dropped you back to direct input mid-conversation.

## v5.68.44

📍 **New: connections follow a device when its address changes** (#376) — a device on a phone hotspot (or any DHCP network) can get a different IP every time it connects, leaving the saved connection pointing at a dead address. When a connect now fails on a private address, Haven sweeps the local network on the profile's port and — only when exactly one machine presents the profile's already-trusted SSH host key — updates the saved address and retries. The host key is the device's identity; the IP was only ever a hint. Fails closed on any ambiguity, and never applies to profiles you haven't trusted interactively first. Works for taps in the app and for automations using the agent endpoint's `run_command`/`connect_profile` (the MacroDroid case from #367). Thanks to ehoeve786 for the use case.

🤖 **Fixed: agent input reaches Mosh, Eternal Terminal, and Reticulum terminals** (#366) — `send_terminal_input` only knew SSH and local sessions, so typing into a mosh session via the agent endpoint failed with "No local session" even though snapshots of the same session worked. Input now routes to whichever transport owns the session, and the error when none does names all five transports.

## v5.68.43

🖥️ **Fixed: desktop sessions get a clean Linux environment** (#373) — X11/VNC, nested-Wayland, and native desktops inherited the Android app process's environment (`BOOTCLASSPATH`, `ANDROID_*`, zygote sockets — 13 stray variables measured on device) and carried no `LANG`. Desktop sessions now start from a clean guest environment matching the terminal path — which also means desktops follow your chosen terminal locale from now on (#374's fix, extended to desktops). Session variables (DISPLAY, dbus, XDG runtime) are layered on top as before. Thanks to sugerpersion for pressing on this — it was a real bug.

## v5.68.42

🌐 **Fixed: your chosen terminal locale actually applies** (#374) — picking a locale (e.g. `zh_CN.UTF-8`) set `LANG` but left `LC_ALL` pinned to `C.UTF-8`, and `LC_ALL` outranks `LANG` for every category — so the choice silently didn't take. Local sessions now export `LC_ALL` alongside `LANG` (which also overrides the stale default in already-installed guests' `.profile`), and freshly installed guests default `LC_ALL` to follow `LANG`. glibc distros still need the locale generated (`dpkg-reconfigure locales`) before programs render it. The locale is also settable via the agent endpoint now (`terminal_locale` preference). Thanks to sugerpersion for the report.

☁️ **Fixed: disconnecting rclone cloud storage works** (#363) — tapping Disconnect on an rclone storage connection (or disconnecting via the agent endpoint) silently did nothing and the card stayed "connected" forever; the rclone session type had been left out of the central disconnect path. Disconnecting mid-OAuth now also cancels the pending auth attempt instead of leaving it running to its timeout. Thanks to hung319 for the report.

🛠️ **Fixed: fakeroot in new proot guests** (#375) — Android kernels lack SysV IPC, so stock `fakeroot` dies with "Function not implemented" under proot (breaking package-build tools that wrap it). Freshly installed guests now prefer the TCP variant via a small `fakeroot` shim where the distro ships one (Debian/Ubuntu do). Existing guests can switch by hand: `update-alternatives --set fakeroot /usr/bin/fakeroot-tcp`. Arch packages only the SysV build, so this can't help there yet. Thanks to sugerpersion for the report.

## v5.68.41

🖥️ **Fixed: X11 desktops now start a dbus session bus** (#370) — launching an X11-over-VNC desktop (Xfce4, or a Custom command (X11) session) left `DBUS_SESSION_BUS_ADDRESS` unset, so desktop components that need a session bus failed to come up — you'd get a bare grey Xvnc screen instead of your desktop. Startup now establishes a dbus session bus before running the session command, so Xfce4's daemons and whatever your custom command launches find the bus. This is also the likely cause of the "Custom command (X11) ignores my command and just starts VNC" report (#361): the command was running but its session couldn't start without dbus. Best-effort — desktops that ship no `dbus-launch` (e.g. Openbox, which needs none) are unaffected. Thanks to sugerpersion for the report.

## v5.68.40

🤖 **New: `run_command` — one-shot SSH commands for automations** (#367) — MacroDroid, Tasker, and cron-style agents can now run a command on a saved SSH connection and read the output back in the same HTTP response, no plugin required: point the automation's HTTP Request action at Haven's agent endpoint (`http://127.0.0.1:8730/mcp` — the URL is shown in Settings → Agent endpoint) with your pairing token and a single `tools/call` POST, and the response body carries `{ exitCode, stdout, stderr }` ready to parse into a variable. It reuses the live connection when the profile is already connected (which is also how FIDO2/encrypted-key profiles work with it); otherwise it makes a one-shot headless connect using the profile's stored password or an unencrypted key. Safety unchanged from the rest of the agent surface: pairing + per-call consent by default, a standing policy scoped to the tool and a specific connection makes it run unattended, host keys are fail-closed TOFU (an unknown or changed host key is refused — connect interactively once to establish trust), and every call is audited. Commands are bounded by a timeout (default 30 s) that returns partial output instead of hanging the macro. Thanks to ehoeve786 for the request.

🖥️ **Fixed: uninstalling one desktop no longer breaks the others** (#368) — with two X11 desktops installed (Custom command (X11) and Native X11 (GPU)), uninstalling one wrongly removed the shared `xterm` package the other relies on, so the desktops' install states fought each other in a loop. Uninstalling a desktop now keeps any package another installed desktop still needs — so removing one leaves the rest intact. This also fixes the same latent hazard for the VNC desktops, which share `tigervnc`. Thanks to sugerpersion for the report.

## v5.68.39

🔌 **Mosh sessions survive network outages of any length** — the client no longer kills a session after 8 seconds of silence. It turns out mosh-server explicitly announces shutdown when your shell exits; Haven's client now listens for that announcement, so silence only ever means the network is away. The transport keeps retrying with the same session key until connectivity returns (exactly like desktop mosh), rebinds its socket periodically so IP changes — Wi-Fi to mobile data, switching networks — recover on their own, and the old "Disconnected — closing in Ns" countdown is now a calmer "No server contact for Ns — retrying" indicator that clears the moment the server answers. Typing `exit` still closes the tab promptly, because that's the announced shutdown, not a guess. Thanks to Biotoza for the report and the groundwork in #365.

## v5.68.38

🧰 **Dependency and toolchain updates** — no user-facing changes, just keeping the build current and secure: Kotlin 2.4.0 (with its matching KSP), OkHttp 5.4.0, the RDP transport's uniffi bindings to 0.32, and sha2 0.11 in the RDP native library. The RDP native library (`librdp_transport.so`) was rebuilt for all three ABIs against the updated bindings, and the whole app was re-verified green (build, unit tests, lint). If your RDP connections behaved before, they behave the same now.

## v5.68.37

🗂️ **Reopening a workspace is reliable now** — restoring a saved workspace with several terminals no longer stalls behind the session picker or stacks duplicate tabs. Each host comes up once and its tmux/zellij/screen sessions attach over that single connection, with all hosts brought up in parallel — so one slow or password-prompting connection no longer holds up the rest of the workspace. Relaunching a workspace you've already opened reuses what's live instead of duplicating tabs, and a session that has since been closed on the host is reattached (and noted as recreated) rather than coming back empty. Workspaces saved before the session-name update heal themselves: the first successful restore pins each tab's session, so the next restore is exact.

🏷️ **Save current** lists each open session as `‹host› tmux ‹name›` (host, session manager, session name) instead of an opaque id, so you can tell which session each row is before you save it.

## v5.68.36

🗂️ **Workspaces reopen your tmux sessions automatically** — restoring a saved workspace now reattaches each terminal to the exact tmux/zellij/screen session it was on, instead of dropping you into the session picker for every tab. Haven records each tab's session when you **Save current** and, on restore, dials the connection and attaches straight to that session by name — so a workspace with four terminals comes back as your four sessions with no prompting. Re-save any workspace made before this update once, so its tabs pick up their session names.

## v5.68.35

🔑 **The Keys tab is always available** (#360) — key and identity management no longer hides on a fresh install. Previously the Keys tab appeared only once you had a key, a step-ca CA, or an SSH connection, so a first-run user who prefers to add keys *before* their first connection had to find key management buried in Settings. It's now always present, like the Desktop and Files tabs.

## v5.68.34

🪪 **New: reusable SSH identities** (#360) — a named identity bundles a username, an optional password, and an optional SSH key, entered once and assignable anywhere. Manage identities on the Keys tab (**Add identity**); assign one per host from the new **Identity** picker in the connection editor's Authentication section, or per group via long-press → **Set identity** — hosts inherit the group's identity unless they override it, including an explicit "use this connection's own credentials" opt-out. The chosen identity's credentials are applied at connect time, so nothing about how you connect changes. Fully opt-in: existing per-host logins keep working untouched. Translated into all 12 UI languages.

🔧 **Fixed: tapping an identity-only host now connects** (#360) — a connection whose username comes from an assigned identity (so its own username is blank) was diverted into the password prompt, whose Connect button stayed disabled until you typed a username — trapping the connection. Tapping such a host now connects straight away, applying the identity's username, key, and password. Verified on-device: an identity-only host authenticates with the identity's key.

🔧 **Fixed: reopening a saved workspace now restores the connection** — launching a workspace from a cold start (nothing connected yet) silently failed to bring up its SSH terminals: each tab needs a live session to attach to, and none was dialled, so it no-opped. The launcher now dials a workspace's SSH hosts that aren't up, waits for the connection, then opens the terminals on it — so "one tap reopens them all" works from a fresh launch, not only when the host was already connected.

## v5.68.32

🖥️ **New: Custom command (X11) desktop** (#361) — termux-x11-style custom sessions for the Linux VM. The Desktops view gains a **Custom command (X11)** entry: Haven still runs the X server and the VNC display, but the session command is yours — e.g. `dbus-launch startxfce4` — instead of a fixed catalog desktop. It installs only what it needs (~15 MB: X server, dbus, xterm); your WM/DE comes from the distro's own package manager. Edit the command any time from the desktop row — changes apply on the next start, no reinstall — and a blank command routes into the editor instead of launching an empty screen. The usual X11 constraint applies: software rendering, so desktops that require a GL compositor (GNOME Shell, KWin) still won't start (#261).

## v5.68.31

🔄 **New: automatic backup push** (#359) — the encrypted backup can now keep itself current on your remote. Turn on **Push automatically** under Settings → Backup → Sync to a remote: Haven re-pushes the encrypted backup a couple of minutes after settings change (a burst of edits collapses into one push), plus a daily catch-up, with each result recorded in the connection audit log. Enabling asks for your backup password once and stores it encrypted on the device so background pushes can run without prompting — the dialog says exactly that, and turning auto-push off deletes it. Push-only by design: restoring (Pull) stays a manual action, so automatic sync can't silently overwrite your local config. SFTP destinations connect on demand and suit background sync best; SMB/rclone destinations still need to be connected. Translated into all 12 UI languages.

## v5.68.30

📜 **Fixed: scrolling fought tmux/nano/vim and painted stale history over them** (#255) — with a full-screen app (tmux without `mouse on`, nano, vim, less) on screen, a one-finger swipe scrolled Haven's local scrollback — which for these apps is the frozen pre-app history, so the display showed old content and rubber-banded on every redraw. Swipes over a full-screen app now send arrow keys to the app itself (as Termux does), so tmux, nano, vim, and less scroll their own content smoothly. Normal shell scrollback, two-finger local scroll, long-press selection, and `mouse on` behaviour are unchanged; profiles that disable the alternate screen keep local scrolling.

## v5.68.29

📟 **New: "Agent log" button on the ongoing notification** (#239) — while the agent (MCP) endpoint is running, the persistent notification carries an **Agent log** action that jumps straight to the Agent Activity screen, including from a cold start or from behind the biometric lock. The expanded notification also now lists each session on its own line, so the MCP status line (`running <tool>…` / last error) is no longer truncated behind other connection names.

## v5.68.28

🖥️ **Fixed: tmux session manager failed on hosts addressed by IP** (#358) — sessions are auto-named after `user@host`, so an IP host produced a name like `user-10.0.0.5`; tmux treats `.` and `:` as pane separators and refused to attach, and the connection closed with the misleading *"Shell closed — is your session manager installed on this host?"* error. Dots and colons in session names are now replaced with dashes (`user-10-0-0-5`). Thanks @Panthaaaa for the precise diagnosis.

## v5.68.27

🛟 **Fixed: restoring a backup could leave the app crashing on launch** — if your backup was taken after you'd changed the terminal background opacity, mail font size, or app-window scale, restoring it corrupted that setting and the app then crash-looped on every open, recoverable only by clearing all app data. Restore now keeps those settings the right type, **and this update self-heals an install already stuck this way** — just update and reopen; your connections and keys are intact. (Affected the existing Restore, not only the new remote sync below.)

☁️ **New: sync your encrypted backup to a connection you already have** (#323) — Settings → Backup → "Sync to a remote": pick an existing SFTP/SMB/rclone connection and a file path, then Push (encrypt + upload) or Pull (download + restore). Same AES-256-GCM encryption as the file backup — the remote never sees your config in the clear. Manual push/pull for now; connect the destination first.

## v5.68.26

🔧 **Build/CI reliability** — raised the Gradle build heap so the release build stops intermittently failing while packaging the 32-bit ARM app (a bundletool out-of-memory that wasted release runs on reruns) (#356). No app-facing change.

## v5.68.25

🔎 **The AI-assistant screen-reader tool worked only in debug builds — now it works in the shipped app** — `dump_haven_ui`, which lets a paired assistant read Haven's on-screen controls, returned "No Compose view in the foreground window" in every release build (the code optimiser renamed the classes it looks for). It never worked for anyone running a real build. Fixed, with a CI guard so it can't silently break again.

👁️ **An assistant can now see approval sheets and dialogs, not just the main screen** (#355) — the consent/pairing sheet renders in its own window that the screen-reader tool couldn't see, so the app's most safety-critical prompt was invisible to an assistant (it could be waiting on your approval without being able to tell you what for). It's now readable, tagged by which window it belongs to. Strictly read-only: an assistant can see the sheet but cannot tap it — approval stays with you, on the device.

## v5.68.24

📁 **Uploading a folder with sub-folders to an SMB share now works** (#273) — v5.68.23 fixed this for SFTP and missed the identical bug in the SMB path, where the first file inside a sub-folder failed because its parent directory was never created. (Local and cloud/rclone destinations were never affected.)

🛡️ **An agent can now see that its action is waiting for your approval, instead of guessing** (#355) — since v5.68.22 a request that arrives while Haven is in the background waits for you rather than being denied outright, but nothing told the AI assistant that: a waiting request and a refused one looked identical from its side, so it would give up or retry pointlessly. A new read-only tool lets it observe the approval queue — what's being asked, by which client, and since when. It cannot answer a prompt: only you can, on the device. This also fixes the waiting request being invisible internally, which is what made the old behaviour indistinguishable.

## v5.68.23

📁 **Uploading a folder that contains sub-folders now works** (#273) — it didn't. Folder upload asked the destination to create each file's parent directory one level at a time, so the very first file inside a sub-folder failed ("No such file") because its grandparent didn't exist yet, and the whole upload aborted. Only completely flat folders ever uploaded successfully. Parent directories are now created properly, once per directory. Found by testing a real Termux folder on a phone rather than trusting the code comment, which claimed this already worked.

⏱️ **Measured: the v5.68.22 folder-scan speed-up, on a real Termux folder** — 400 files: 1.6 s → 0.16 s. 4,000 files: **14.4 s → 0.29 s**. The old scan cost grew with every file; the new one barely moves. If a folder upload used to sit on a blank screen for half a minute, that was roughly 8,000 files' worth of waiting.

## v5.68.22

🛡️ **An agent action that arrives while Haven is in the background now waits for you instead of being denied on the spot** (#337) — previously, if an AI agent called a tool needing your approval while you were in another app (even one Haven itself had launched, like the system installer), the call failed immediately with "denied", and nothing you'd done said no. Now the call holds, the heads-up notification tells you it's waiting, and opening Haven shows the approval sheet for that *same, still-live* call — tap Allow and it proceeds. It's still denied automatically if you never answer, and it can never be approved without you: Haven does not become a silent automation channel. Device-verified end to end, along with the pairing-loop guards from v5.68.21 (a spamming client can't stack duplicate prompts, gets a two-minute cooldown after a Deny, is rate-limited across renamed retries, and can be silenced with one tap on **Block**).

📁 **Uploading a folder from another app's storage is dramatically faster and no longer looks hung** (#273) — picking a folder from, say, Termux could sit on a blank screen for half a minute even for a few megabytes: Haven asked that app for each file's name, type and size in separate cross-process queries, roughly three round trips per file. Each folder is now read in a single query, and the scan shows "Scanning… N files" while it works, so a slow source looks busy rather than dead. It can also be cancelled mid-scan now. (Measured against a simulated provider — if you hit this with a real Termux folder, please say whether it's fixed for you.)

👻 **New "Hide from recent apps" toggle** (Settings → Advanced, off by default) (#239) — hides Haven's card in the recents screen while sessions keep running; reopen from the app icon.

🔧 **Dependency and CI maintenance** — appcompat 1.7.1, hilt 2.60.1, datastore 1.2.1, smbj 0.14.0, xz 1.12, androidx.browser 1.10.0, hilt-work 1.4.0, mockk 1.14.11, and rustls 0.23.41 (the RDP native library is rebuilt for all three architectures). Two CI flake sources removed.

## v5.68.21

🖥️ **Boot a full QEMU system VM and drive it from Haven's own viewer** (#326) — alongside the proot desktops there's now a "System VM" manager (Desktop → Manage): import a raw/qcow2 x86_64 disk image, and Haven boots it with `qemu-system-x86_64` inside the active proot, real kernel and all, with the display on a loopback VNC port rendered by the existing VNC viewer — keyboard input included. The agent gets the same lifecycle over MCP (system-VM `list/import/start/stop/delete` verbs). Honest caveats, by design: unrooted Android has no `/dev/kvm`, so the VM runs under TCG emulation — around two minutes to a login prompt and a visibly slow framebuffer (fine for a boot-once console or light desktop; heavy desktops will drag) — it needs a distro whose qemu ships VNC support (Debian's does; Alpine's stripped build doesn't), one VM at a time, and x86_64 guests only so far. This is what #325's qemu-user can't do: a real foreign-arch kernel with its own block/net stack, not just translated binaries.

🛡️ **A misbehaving MCP client can no longer trap you in a pairing loop** (#337) — a client re-sending a stale token in a retry loop used to raise a fresh "Pair?" sheet per attempt (tap Pair, it asks again — four times in a row in the reproduced case) while real consent prompts starved behind them. Retries for a name already on screen now join that sheet instead of stacking duplicates, an explicit Deny silences that client's re-asks for two minutes, at most three pairing sheets can be raised per minute across *all* client names (the reproduced spammer rotated its name per call), and the pairing sheet gains a one-tap **Block** button that silences the client for the session (undo via Settings → Forget remembered allows). The consent sheet also now says when more requests are waiting behind the visible one, so a second client's prompt isn't mistaken for the action you just triggered. Block is translated in all 11 languages.

🔧 **Agent-created VNC/RDP/SPICE connections land on the right port** (#353) — `create_connection` advertised `vncPort`/`rdpPort`/`spicePort` arguments but only honoured the generic `port`, so e.g. a SPICE profile aimed at 5930 silently landed on 5900.

📦 **Debian 13 (Trixie) — the current Debian stable — joins the distro picker** (#253) — offered alongside Debian 12 (Bookworm), which stays for anyone who wants oldstable. Same proot-distro tarball lineage as Bookworm, all three architectures (arm64/x86_64/armv7), checksums verified by download against proot-distro's published values. The mirror picker works on it unchanged.

## v5.68.20

🖥️ **Foreign-architecture Linux distros are now offered in the distro picker** (#325) — the "＋ Add another distro" list only ever showed distros built for your phone's own CPU, so running, say, an x86_64 Debian on an arm64 phone meant knowing to hand-type a rootfs URL into Import rootfs. Every built-in distro now also appears as an emulated variant for each foreign architecture this build can run ("＋ Debian 12 (Bookworm) x86_64 — emulated, slower"), installed through the same path as before (the download is arch-detected and transparently run under qemu-user). It's discovery, not a new mechanism — and it's clearly labelled "emulated, slower" so the speed trade-off is obvious. Verified on an arm64 device: all five built-ins offered as x86_64 variants.

🔌 **Opening a USB drive with several attached now lets you pick which one** (#287) — "Open USB drive…" assumed a single drive and errored out ("pass deviceName") when more than one mass-storage device was plugged in; only the agent (MCP) path could choose. It now shows a short picker listing the attached drives. Opening a single drive, or none, is unchanged.

## v5.68.19

⌨️ **Local shells now track bracketed-paste and mouse modes** (#336) — an agent-opened local shell had no DECSET scan at all, and a local shell adopted into the Terminal UI got dead stub flows installed over the working ones, so `bracketPasteMode` read false forever. Two consequences fixed: `send_to_agent` now bracket-pastes multi-line messages to a REPL running in a local shell (verified on-device against bash 5.2 in the Alpine proot — a two-line message lands as one submitted paste, and drops back to plain input when the REPL exits), and an adopted local tab's own paste-wrapping now follows the live stream instead of never wrapping.

## v5.68.18

🔒 **Remote-desktop connections now pin the server's TLS certificate on first use** — a security review of every connection type found the VNC (VeNCrypt/X509) and RDP TLS paths accepted *any* certificate, leaving both open to man-in-the-middle interception. Both now record the certificate the first time you connect and refuse a changed one until you explicitly accept it — the same trust-on-first-use model as SSH host keys, sharing one trust store. From the same review: silent SSH connects (background and agent paths) fail closed on an unknown host key instead of proceeding; mail accounts configured for TLS now *require* STARTTLS rather than silently falling back when the server doesn't offer it; VNC remote→local clipboard sync is opt-in (default off), so a compromised server can't quietly read what lands on the phone's clipboard; backup encryption moved from 100k to 600k PBKDF2 iterations under a versioned envelope; rootfs archive extraction guards against zip-slip path traversal and deletes symlink-safely; and the terminal's native JNI layer got bounds/overflow hardening. The new host-key and clipboard strings are translated in all 11 languages.

🤖 **`send_to_agent` no longer garbles messages to plain shells** — it always wrapped the message in bracketed-paste markers, so a target that never enabled bracketed paste (a plain busybox/dash shell) received the markers as literal text and mangled the command. It now wraps only when the target has actually turned bracketed paste on (#226).

🔌 **The agent (MCP) endpoint now self-heals and no longer stalls on non-ASCII** — two fixes to the agent transport that the SSH terminal beside it already got right. The endpoint's accept loop is a background thread that the OS can kill when it trims the app; nothing was restarting it, so a still-up carrier could sit in front of a dead endpoint until you toggled the setting. It now revives on the same triggers the SSH sessions use (return-to-foreground and network-available). Separately, the request parser counted characters against a byte length, so any tool call carrying a multibyte character (an emoji, CJK, an accented letter) stalled until a 70-second timeout — it now reads the body by byte length and returns instantly. Both were reproduced and fixed on-device.

🔒 **Agent endpoint security hardening** — a staged review of the agent (MCP) transport, bringing it to the same bar as the SSH stack (`docs/design/mcp-backbone.md`). The request parser now caps body and header size (an oversized `Content-Length` could exhaust memory) and rejects cross-origin browser requests to the loopback endpoint. Beyond that, three changes tighten *who* an agent client is and *what* it may do:

- **Pairing tokens.** A paired client is now identified by a 256-bit secret it was issued when you approved it — not by the name it claims. It sends that token on every request; only its SHA-256 is stored on the device. A client that presents no token (or a wrong one) is treated as new and must be approved again. Approving a client on the phone is the one and only way in.
- **Un-pairing takes effect immediately.** Removing a client (from the tool or Settings → Agent endpoint → Paired clients) now revokes its token on the spot, not just after the next app restart.
- **Tunnelled traffic is never treated as on-device.** The "trust local clients" option applies only to genuine on-device clients; a client reaching the endpoint through a reverse tunnel still has to be approved, even though it arrives on `127.0.0.1`. Loopback trust is also **off by default** now.

Each of these was verified on-device against the live endpoint. Existing clients re-approve once on first connect after updating; configure your client (or the failover proxy) to send the token it's issued to stay paired across reconnects.

📖 **The agent's full tool surface is documented** — `docs/mcp-tools.md` lists every tool an agent can call, its arguments, and exactly when Haven asks for your approval (every call / once per session / never), generated from the live code so it can't drift. Under the hood the transport was re-architected into a shared protocol module and per-domain tool providers, with no change to how any tool behaves.

## v5.68.17

🖼️ **Agent-presented web pages, images and PDFs get a fullscreen view and clearer window controls** — pages the AI opens on screen now run with JavaScript and DOM storage enabled (previously many rendered as a blank white view), and the window chrome matches Haven's other windowed apps: an explicit ✕ closes the window (the old top-bar affordance read as "minimise"), and web/image content has a fullscreen toggle beside it that opens an immersive view with an exit control, rather than overlaying the content.

## v5.68.16

🖥️ **RDP to Linux xrdp servers now renders — previously a blank screen** — two long-standing gaps found by smoke-testing against a modern EGFX-capable xrdp: Haven never registered the DisplayControl virtual channel (xrdp aborts *all* channel processing when it's refused, so no frame ever arrived), and xrdp's Planar-codec tiles — which it uses for the greeter and much session content — were silently ignored. Both fixed: DisplayControl is registered and answered with the session's monitor layout, and Planar tiles decode through the RDP 6.0 bitmap decoder. Verified host-side (framebuffer dump) and on-device: the xrdp login greeter renders pixel-perfect. Windows RDP is unaffected.

⌨️ **Keyboard works immediately when switching Desktop → Terminal** — the terminal only claimed the keyboard on first composition, but adjacent screens stay composed while swiping, so returning to the terminal showed the keyboard while the keys still went to the desktop tab until you switched away and back again. The terminal now re-claims input every time its screen becomes active.

✂️ **Selection handles follow reading order** — after a backward drag (right-to-left or bottom-to-top), the *start* handle now sits on the top-left-most character as a left-to-right reader expects, instead of wherever the finger first went down. (The copied text was always correct; only the handles were swapped.)

🔧 **Toolchain and dependency refresh** — Android Gradle Plugin 9.2.1 + compileSdk 37 (unlocking hilt 2.59.2 and lifecycle 2.11), ironrdp 0.9-era stack (session 0.10, connector 0.9, input 0.6, new egfx crate), Robolectric 4.16.1, tailscale 1.100. No user-visible behaviour change expected from these beyond the fixes above.

## v5.68.15

🎯 **Pinch-zoom no longer breaks touch→text mapping in the terminal** — the gesture handler kept the pre-zoom character metrics captured in its closure (its `pointerInput` never restarts on a font change), so after any pinch-zoom every tap, long-press selection, handle drag and forwarded mouse click mapped through the old cell size while the screen drew at the new one: selecting a line grabbed the wrong text, worsening away from the top-left. Metrics are now read through `State` inside the handler, so touch mapping always matches what's rendered. Reported live while trying to copy a command after zooming out. (A crash reported around the same interaction is still under investigation — a stack trace is needed; if you hit it, logcat output on the issue tracker helps.)

🌍 **110 untranslated strings localised in all 11 languages** — everything added since the last i18n pass had shipped English-only: the desktop options/custom mounts/rootfs-import/USB-drive screens, rclone config import, deeplink connect confirmations, age encrypt/decrypt actions and identities, audio bridge and prompt-character settings, and the two-finger gesture accessibility descriptions.

📦 **Dependency refresh** — jsch 2.28.3, JavaMail 1.6.8, Navigation 2.9.8, tink 1.22.0, Compose BOM 2026.06, biometric alpha07, ironrdp-cliprdr 0.6 + ironrdp-tls 0.2.1, tailscale 1.100.0, and the GitHub Actions pins (checkout v7, artifact v7/v8, gh-release v3).

## v5.68.14

Follow-ups to the v5.68.13 rootfs-import work (#328) and the self-update path (#331), both found while verifying on-device.

📦 **Imported proot-built rootfs: long symlink targets no longer truncated** (#328) — v5.68.13 flattened the `.l2s.` link2symlink artifacts a Termux/proot-built rootfs carries, but the import's tar reader only handled GNU long *names* (type `L`), not long *link targets* (type `K`). Every Termux `.l2s.` target is an absolute path well over 100 characters, so it was read from the truncated 100-byte header field, lost its `.l2s.` basename, and slipped past the flattening. Type `K` is now parsed like `L`; a GNU-format tar with a 105-char link target was verified to import to a clean tree on-device. (Long link targets in the rarer PAX tar format are still unhandled — use GNU `tar`, which is the default for `tar(1)` and busybox.)

📥 **Truncated APK downloads are rejected, not staged** (#331) — a dropped connection mid-download produced a partial APK that still started with the zip magic bytes, so it passed the sanity check and was handed to the installer, which failed on-device with "problem parsing the package" — while the install had already reported success. Downloads (URL and backend) are now checked against the advertised length; a short read is deleted and surfaced as a failure with the byte counts, so it's retryable rather than a mystery.

## v5.68.13

Agent↔agent turn primitives over MCP, plus terminal-agent plumbing and port-forward fixes found while verifying them on-device (#226).

🤖 **New MCP tools: `await_turn` / `read_last_turn` / `isAgentRepl`** — turn-based conversation with whatever runs in a terminal session: block until the session is idle at a prompt (OSC 133 segments when the shell emits them, Claude-Code-aware screen heuristics otherwise — including NUL-padded fresh screens, stale shell-integration rows left under a running REPL, and ASCII spinner frames), then read the last completed turn (semantic command output, or the last bulleted block scraped above the REPL's input box with dividers and tmux status lines stripped). `list_sessions` marks which session is an agent REPL. Device-verified against plain busybox shells, an OSC 133-integrated prompt, and a live Claude Code session (#226).

🔧 **Agent-opened shells: the agent's view stays live** — `feed_terminal_output` injected into the UI tab's emulator instead of the one the agent tools read (silent no-op), and any Terminal-tab rebuild silently disconnected the agent emulator from the PTY (frozen snapshots while raw scrollback kept flowing). The agent tee now lives beside the scrollback ring and survives tab adoption and reattach.

🔌 **Local port forwards: honest activation and TIME_WAIT-proof rebind** — re-binding a `-L` forward right after a bulk transfer through it could fail against TIME_WAIT sockets (jsch binds without SO_REUSEADDR) yet still be reported active, leaving a listed-but-dead tunnel. Binds now retry "already in use" for ~2.3s, and `add_port_forward` reports `activated:false` with the reason when the bind genuinely failed (the rule stays saved for the next connect).

📦 **Imported rootfs: proot build artifacts flattened** (#328) — a rootfs built under a proot (Termux proot-distro, or a Haven guest) carries `.l2s.` symlink chains wherever dpkg hard-linked its database backups, with absolute paths of the *build* system baked in (e.g. `/data/data/com.termux/…`). Imported verbatim those links dangle — the root of the "`dpkg-divert: error creating new backup file … Operation not permitted`" reports. Import now materializes each such link from its sibling payload, so debootstrap/proot-built tarballs come in clean.

## v5.68.12

Keyboard fixes for SwiftKey and the compose overlay, plus a usable prompt on Void (#298, #253).

⌨️ **SwiftKey: lines no longer concatenate with the previous command** — SwiftKey keeps its internal composition context across Enter, so after one executed command the next word could reach the shell prefixed with the dead line (`nasls`, `naslsls` — with the old text visibly still in SwiftKey's suggestion bar). Haven now restarts the IME session at the Enter line boundary in Standard keyboard mode, the canonical signal for the keyboard to drop its prediction context. Regression-checked on-device with SwiftKey, Gboard and HeliBoard (no flicker or lost keystrokes); the failing configuration itself is reporter-specific, so confirmation on #298 is still open.

📝 **Compose (中) overlay no longer hides earlier words while you type the next** — the accumulated buffer and the keyboard's in-flight word were drawn at the same spot, so keyboards that compose every word (HeliBoard) appeared to erase the previous word until Enter. Both now render as one run. Device-verified with HeliBoard.

🐧 **Void Linux: usable shell prompt out of the box** — the seeded `/root/.profile` used bash-only prompt syntax, which Void's dash shell printed literally (`\[\033[32m\]\u@proot…`) with no line editing (arrow keys echoing `^[[H`). New installs/imports get a shell-aware prompt, and if bash is installed (`xbps-install -S bash`) the login shell hands over to it automatically (#253).

## v5.68.11

Mosh startup failures now show their real error instead of the install guide (#297).

📡 **Mosh: the real `mosh-server` error is finally surfaced** — when `mosh-server` was installed but refused to start (most commonly the server's UTF-8 locale isn't generated), Haven still showed the "mosh isn't installed" setup guide. The v5.60.7 fix for this defeated itself: its missing-binary check matched the phrase "No such file" anywhere in the output — and the locale error's own text contains exactly that phrase, so the most common failure was re-classified as "not installed" and the detailed error never appeared. The check now only matches output lines that actually name `mosh-server`, so a locale failure shows the real message (including the `locale-gen` hint). The reporter's exact error output is now a regression test.

## v5.68.10

Two fixes: compose (中) mode swallowed Enter, and imported rootfs lost hard-linked files.

⌨️ **Compose (中) mode: Enter now executes the line** — with the compose overlay active, Enter committed the buffered text but silently dropped the newline (and with an empty buffer, dropped the keypress entirely), so composed lines echoed concatenated on one prompt and never ran. Enter now submits the committed text like a normal keystroke. Device-verified with Gboard and HeliBoard: `ls` ⏎ executes every time. (Same #298 symptom family as v5.68.8's Standard-mode fix, different layer.)

📦 **Imported rootfs tarballs: hard-linked files no longer vanish** — in a wrapped tarball (the shape of every proot-distro-style import), hard-link entries pointed at an unstripped archive path, so the linked file was silently missing from the extracted rootfs; hard links that did extract came out with the app's umask instead of the archive's mode (losing exec bits on linked binaries). Both fixed (#328); the v5.68.5 mode restore itself was also verified exact on-device. If a previously imported rootfs behaves oddly, re-import it.

## v5.68.9

Agent-driven APK installs no longer time out on slow links (#331).

📲 **`install_apk_from_url` now stages in the background with pollable progress** — downloading a large APK over a slow link (e.g. updating Haven remotely while traveling) held the MCP request open for the whole transfer, so the call timed out while the install silently continued, leaving the agent blind. The tool now validates the URL synchronously, then downloads and installs in the background, returning `{pending, staging}` immediately. Both install tools (`install_apk_from_url`, `install_apk_from_backend`) publish a live `activeInstall` snapshot in `get_app_info` — phase (connecting / downloading / installing) plus bytes transferred — and the terminal outcome lands in `lastInstall`, including download failures that were previously invisible. Device-verified over a WireGuard-jump travel network, including a real mid-download network abort surfacing as a clean pollable error.

## v5.68.8

Fixes lines being lost or concatenated on Enter with the Standard keyboard (#298).

⌨️ **Enter now reliably executes the line in Standard keyboard mode** — with a composing keyboard (HeliBoard, swipe/gesture input, and others) in Standard mode, pressing Enter could fail to submit the typed line: the shell either executed an empty line while the text stayed stuck in the composition (Enter "only drew a new prompt"), or received the text with the Enter silently dropped — so successive commands concatenated on one prompt (`ls` ⏎ `ls` ⏎ `exit` arrived as `lslsexit`). Two distinct holes fixed: an accepted composition that was never sent to the shell, and newlines being stripped from keyboards that submit the line as a single commit (which also broke multi-line clipboard paste). Device-verified before/after with HeliBoard: the same key sequence that produced `lslsexit` now executes three lines and exits the shell. Known remaining gap: in the opt-in 中 compose-overlay mode, Enter can still be swallowed — tracked separately.

## v5.68.7

Optional: run Android's own native binaries inside a Local Linux shell (#304).

📦 **New "Expose Android system to guest" toggle (off by default)** — when enabled (Desktop → Manage → Options, or MCP `bind_android_system`), Haven binds Android's read-only `/system`, `/vendor`, `/apex`, `/product`, `/system_ext` and `/odm` into the proot guest, so the local Linux environment can run Android's own native tools — e.g. `/system/bin/getprop`, `toybox`. Left off by default because it exposes device and vendor internals; the partitions are mounted read-only, so the guest can't modify them. Device-verified: with the toggle on, `getprop ro.product.model` and `toybox uname` run inside the guest. (`/linkerconfig` isn't bound — it's SELinux-blocked for the app and unnecessary; Android's linker falls back to a default and still resolves its libraries.)

## v5.68.6

Shows your device model as the terminal "Host" line in neofetch/fastfetch (#304).

📟 **`neofetch`/`fastfetch` now show your device model instead of a blank Host** — inside a Local Linux (proot) shell these tools couldn't determine the hardware "Host", so the line came out empty: under proot there's no DMI, the device-tree model node is SELinux-unreadable, and `getprop` isn't reachable. Haven now seeds the device model (from `Build.MANUFACTURER`/`Build.MODEL`) where both tools look — `/tmp/sysinfo/model` for neofetch, and a bind over `/sys/firmware/devicetree/base/model` for fastfetch — across every proot launch path (terminal, one-shot, and desktop). Device-verified: Host now reads e.g. `OnePlus CPH2655`. (The separate request to bind Android's `/system`/`/vendor` into the guest is not included — it carries real SELinux/cross-device risk and needs a concrete use-case first.)

## v5.68.5

Fixes rootfs file permissions being lost on extraction (#328).

🔧 **Custom rootfs imports and distro installs now keep their file permissions** — Haven's tarball extractor was applying the Android app's restrictive umask (0077) instead of the permissions stored in the tar, so extracted directories came out `0700` and files `0600` regardless of the archive. On a custom-imported rootfs this could leave `/var/lib/dpkg` under-permissioned and make `apt`/`dpkg` fail with `error creating new backup file … Operation not permitted`. The extractor now restores each entry's exact mode from the tar. Re-import an affected rootfs to pick up correct permissions; an existing install can be repaired with `chmod 755 /var /var/lib /var/lib/dpkg`.

## v5.68.4

Two fixes: SSH hardware-key auth ordering, and a Native X11 desktop teardown crash.

🔑 **"Any hardware key" no longer prompts ahead of a primary software key (SSH)** — with a software key as your primary auth method and "Any hardware key" as a secondary fallback (and 2+ hardware keys enrolled), Haven asked you to present a hardware key during connect setup — *before* it even tried the primary software key that would have logged you in. It now defers that prompt: the software key is offered first, and the hardware-key pool is only exercised if it fails. When "Any hardware key" is your primary/only method, the present-your-key prompt is unchanged.

🖥️ **Native X11 (labwc) desktop no longer crashes the app on teardown** — stopping or restarting the on-device GPU desktop could crash Haven with a native use-after-free in the Wayland compositor (`liblabwc_android.so`) as it tore down. Fixed by clearing a dangling scene-node reference the moment the surface is destroyed. (Separate from backgrounding, which keeps the compositor alive.)

## v5.68.3

Fixes duplicated terminal input on the default keyboard for some IMEs (#298).

⌨️ **Typed input no longer doubles in the terminal (Secure keyboard mode)** — with the default (Secure) keyboard, a keyboard that *composes* words as you type — swipe/gesture typing, Samsung Keyboard, and some third-party IMEs — could send each word to the shell twice: you'd type `ls` and the shell would receive `lsls`. The duplicated characters also left the prompt non-empty, so Ctrl+D wouldn't exit the shell. The word is now sent once, reconciling only autocorrect/trailing-space differences. The earlier #298 fix covered only Standard keyboard mode; Secure — the default — had a separate gap. Verified with new regression tests; on-device confirmation for specific keyboards is still welcome.

## v5.68.2

Build-only fix so Haven builds from source on F-Droid (#327). No app-facing change from v5.68.1.

🔧 **Fixes the from-source FFmpeg build on F-Droid** — `build-ffmpeg/build.sh`'s SDK-CMake auto-detection ran `ls .../cmake/3.31.* .../cmake/3.22.*` and, when only one version is installed (as on F-Droid's buildserver, which installs just CMake 3.31.x), `ls` exits non-zero and `set -euo pipefail` silently aborted the whole FFmpeg build. This only affected builds that compile FFmpeg from source (F-Droid / reproducible builds); the GitHub release APKs ship the committed `.so` and were never affected, so v5.68.2's binaries are equivalent to v5.68.1's.

## v5.68.1

Brings the nested-Wayland desktops to 32-bit ARM (#327).

🖥️ **Nested-Wayland desktops (Sway, Cage, Hyprland, niri) now work on armv7** — the `libhaven_wayvnc_shim.so` capture-fallback shim (which runs inside the distro alongside `wayvnc`) is now cross-built for `armeabi-v7a` too, so those compositors render instead of grey-screening on the 32-bit build. Combined with the already-working VNC desktops (Openbox, Xfce4), the only local desktop still arm64-only on armv7 is the on-device GPU compositor (*Native X11 (GPU)*, which needs `liblabwc_android.so`). Not yet tested on real 32-bit hardware.

## v5.68.0

Adds a 32-bit ARM (armv7) build so Haven runs on older / 32-bit-only Android devices (#327).

📱 **New `armeabi-v7a` (armv7) flavor** — Haven previously shipped only `arm64-v8a` and `x86_64`, so 32-bit-only devices couldn't install it. There's now an `armv7` build with the full 32-bit native stack: PRoot + loader, the Go bridge (`libgojni` — WireGuard/MCP, rclone, mail), the Rust RDP and SPICE transports, the terminal (`termlib`), the OCR/scan libs (tesseract/leptonica), and FFmpeg (media conversion — now built for `x86_64` too, not just arm64). All five proot distros (Alpine, Debian, Ubuntu, Arch Linux ARM, Void) have armv7 rootfs images wired in. Local Linux desktops **over VNC (Openbox, Xfce4)** work on armv7 too — the X server and desktop run inside the distro, so they need no arm64-only libs. What's **arm64-only** on armv7 (same as the x86_64 build) is the on-device GPU compositor (the *Native X11 (GPU)* desktop — `liblabwc_android.so` + virgl + Xwayland) and the nested-Wayland compositors (Sway, Cage — their VNC shim isn't built for 32-bit in this release); remote desktops (RDP/VNC/SPICE) and everything else work. The x86_64 and app-level paths are verified in an emulator (app launches, proot/`apt` run, FFmpeg encodes H.264); not yet tested on real 32-bit hardware.

## v5.67.2

Fixes undeletable files inside proot distros (#329).

🧹 **A broken `link2symlink` stub no longer becomes un-removable** — PRoot's `--link2symlink` extension represents hard links as hidden `.l2s.` symlink chains. If those hidden backing files went missing (an interrupted `dpkg` run, or a rootfs copied/tarred without the `.l2s.*` files), the leftover stub could no longer be `stat`'d — so `ls`, `rm`, and `find` all failed on it with `Operation not permitted`, and `rm -rf` of any directory containing one aborted with "Directory not empty". PRoot now fails soft on a broken chain: the stub behaves like an ordinary dangling symlink and can be removed normally. Verified on host and on-device (arm64): a stub whose `.l2s.` files were deleted now `lstat`s cleanly (was EPERM) and `rm` removes it.

## v5.67.1

Candidate fix for `apt`/`dpkg` failing inside proot distros with a backup-file permission error (#328, #324) — pending confirmation from the reporter.

📦 **PRoot prefers a real hard link for `dpkg`'s `-old` database backups** — `dpkg` backs up its database files (`/var/lib/dpkg/status`, `/var/lib/dpkg/diversions`) by hard-linking them to a `-old` copy, which surfaced for one reporter as `dpkg-divert: error: error creating new backup file '/var/lib/dpkg/diversions-old': Operation not permitted`. PRoot's bundled `--link2symlink` extension was rewriting *every* hard link into its symlink-emulation unconditionally; PRoot now attempts a real hard link first and only falls back to that emulation when the underlying `link()` fails. Where a real hard link succeeds, `dpkg`'s backups match a normal Linux system (no `.l2s.` artifacts). **Caveat:** on the test device (OnePlus, Android 15) native hard links are denied to the app by the platform, so the change falls back to the existing emulation there and has no observable effect — the reporter's failure could not be reproduced, so this is not yet confirmed to fix it. The earlier #324 rootfs-import hardening (v5.66.3) was based on a wrong diagnosis (a directory at a dpkg DB path fails earlier with "Is a directory", not this backup error); it was left in place as harmless hardening. The change is verified to not regress `apt`/`dpkg` (host + on-device).

## v5.67.0

Encrypted (LUKS), writable, and multi-drive USB support (#287); MCP reconnects reliably; five reliability fixes found along the way.

🔐 **Encrypted (LUKS) drives now unlock** — a locked partition (previously reported read-only-locked with no way in) can be unlocked with its passphrase and mounted, against the drive's already-running helper VM — no reboot needed.

✏️ **Writable mounts** — "Open USB drive (writable)" mounts read-write instead of read-only, for edits and repairs. Every write flushes immediately (no write-back cache to lose to an unexpected kill), and closing a drive explicitly syncs and unmounts before the VM shuts down.

🗄️ **Multiple drives at once** — open more than one USB drive concurrently (up to a phone-resource limit), each in its own helper VM.

🧰 **Recovery/forensics toolset** — the helper Linux now bundles `testdisk`/`photorec`, `gdisk`/`sgdisk`, `parted`, `smartmontools`, and `ddrescue`, usable from a terminal into the drive's VM.

🔌 **MCP reconnects reliably without a force-stop** — MCP used to carry its tunnel over its own separately-authenticated background SSH session, whose headless auth can't use FIDO2 keys and would retry a doomed login forever once its other keys stopped working. MCP now rides your already-connected interactive SSH session instead — a Settings status row and `get_app_info`'s new `mcpCarriers` field show which route is actually carrying it.

🔧 **Five reliability fixes found during testing**: a keep-alive against Android suspending an idle USB device mid-session (which used to block all further enumeration until a physical replug), de-duplicated "USB: …" bookmarks by the drive's serial number instead of piling up a new one on every replug, a mount fallback that only exists for ext4/xfs was being tried on every filesystem (wasting the one fallback a non-ext4/xfs stick gets), raw USB transfers weren't synchronized against each other and could silently truncate above ~16KB — surfacing as the device resetting mid-write, most visibly formatting a LUKS header — and unlocking a LUKS partition could report success even when the mount had actually failed, because the guest's terminal echoes a command back as it's typed and that echo alone could satisfy the "done" check before the command had run; unlocking now verifies the real mount state before reporting back.

Drivable over MCP (`unlock_usb_drive_partition`, `open_usb_drive`'s `writable` param, `list_usb_drives`' `vms[]` array). Updated guide: [Reading USB drives](https://github.com/GlassHaven/Haven/blob/main/docs/features/usb-drives.md).

## v5.66.3

Hardens the custom rootfs importer against malformed tarballs (#324).

🛡️ **Rootfs import handles a path reused with the wrong type** — Haven's tarball extractor (used for both built-in distro downloads and "Import rootfs…") now clears a path first if a later tar entry reuses it as a different type than an earlier one (e.g. a leftover directory placeholder later replaced by a regular file). Previously this could silently no-op or throw instead of letting the later entry win, potentially leaving an extracted file as the wrong type. Found while investigating a report of `dpkg` failing with "Operation not permitted" creating `/var/lib/dpkg/status-old` on a custom-imported rootfs — proot's hardlink emulation returns exactly that error when asked to link a directory instead of a file. Not yet confirmed as the root cause of that specific report (still waiting on the reporter to check whether `/var/lib/dpkg/status` is a directory on their rootfs), but it's a real correctness gap in the importer either way. Covered by 5 new unit tests.

## v5.66.2

Fixes importing a rootfs from a plain-HTTP LAN mirror (#284).

🌐 **Import a custom rootfs from a self-hosted HTTP mirror** — importing from an `http://` URL on your local network (a home-lab package/rootfs mirror without TLS) failed with "Cleartext HTTP traffic ... not permitted". Android blocks plain HTTP by default, and the declarative allowlist can only name specific domains, not an arbitrary LAN IP. Haven now falls back to a direct download for this one explicit, user-typed URL when that happens — every other network request in the app is unaffected. Verified against a real local HTTP mirror serving a real Alpine rootfs, end to end (download → extract → a working guest shell).

## v5.66.1

USB-drive connections reopen with a tap, and a CI fix.

🔖 **"USB: …" connections reopen with a tap** — a USB-drive connection used to go dead as soon as its little Linux VM stopped (eject, phone sleep, app restart), leaving a bookmark that just failed. Tap it again (with the drive still plugged in) and Haven now reboots the VM and reconnects automatically — no need to go back through "Open USB drive…" each time. If the drive isn't plugged in, Haven tells you to plug it back in instead of failing silently.

🔧 **Fixed a broken CI check** — a set of unit tests hadn't been updated for a recent internal change and were failing to compile, which had started blocking the automated test run on `main`. Fixed; no user-facing change.

## v5.66.0

The "Open USB drive" feature (#287), made faster and more reliable from a round of testing.

💾 **The USB helper Linux is kept, so later opens are quick** — the small on-device Linux that reads your drive is now **set up once and kept**, instead of rebuilt on every open. The **first** open still takes a few minutes (it downloads and installs the helper); every open after that skips straight to booting it — much quicker. A new **"Delete USB helper Linux"** option (**Desktop → Manage**) reclaims the ~280 MB if you want it back (it rebuilds itself, once, next time).

📂 **The drive opens in Files by itself** — when a drive is ready, Haven now switches to **Files** and lands on its contents (under `/mnt`, e.g. `/mnt/sda1`), instead of leaving you to find it.

🔌 **High-speed drives now mount reliably** — the USB/IP layer was reporting **every** exported device as *full-speed* (an old assumption from FIDO keys). A high-speed flash drive imported as full-speed makes the VM mis-read it, so it sometimes wouldn't appear. Haven now reports the drive's **real speed**, and **waits for the drive to actually enumerate** rather than assuming a fixed time — so a slower phone or a bigger/slower drive still mounts.

Drivable over MCP (adds `delete_usb_appliance`; `list_usb_drives` now reports `applianceProvisioned`). Updated guide: [Reading USB drives](https://github.com/GlassHaven/Haven/blob/main/docs/features/usb-drives.md).

## v5.65.0

A new way to read USB drives the phone can't open, a fuller email tool surface, and a USB/IP fix.

💾 **Read USB drives the phone can't open (#287)** — plug in a USB flash drive or SSD and open its files, **even Linux-formatted (ext4/GPT) or other drives Android can't read itself**. Just go to **Desktop → Manage → "Open USB drive…"** — no setting to enable first; the open is a deliberate, read-only action. Haven hands the drive to a small on-device Linux virtual machine (which *does* have the kernel drivers a phone lacks), mounts it **read-only**, and surfaces the files as an ordinary **"USB: …" connection** — so the normal file browser, a terminal into the drive, and the MCP file tools all work unchanged. Because an un-rooted phone has no hardware virtualisation, the VM is emulated and **slow** (it's for pulling files off a drive, not a daily-driver desktop) — a live progress line shows what it's doing while it boots. Drivable over MCP (`open_usb_drive` / `list_usb_drives` / `close_usb_drive`). New guide: [Reading USB drives](https://github.com/GlassHaven/Haven/blob/main/docs/features/usb-drives.md). *Read-only, one drive at a time, no encrypted (LUKS) drives yet; webcams/microphones still can't pass.*

📧 **More email tools for the agent (MCP)** — the IMAP/Gmail mail surface is filled out: `search_mail` (server-side IMAP SEARCH), `save_mail_draft`, `create_mail_folder` / `delete_mail_folder`, and `modify_mail_message` (mark read/unread, flag, move, copy/apply-label, delete). `send_mail` now threads replies correctly (`In-Reply-To`/`References`), `list_mail_messages` paginates and reads Cc, and a dropped IMAP idle socket reconnects instead of erroring. Device-verified on Gmail.

🔑 **USB/IP re-export fix** — exporting a phone USB device over USB/IP (to a remote host *or* the new on-device VM), stopping, and re-exporting within one app session failed with "Address already in use". The server now releases its port cleanly on stop, so re-export works.

## v5.63.0

More reporter-requested local-Linux-desktop control, a bring-your-own-rootfs path, and a security-key fix.

🔗 **Custom mounts (#301)** — **Desktop → Manage → Custom mounts** lets you expose extra Android paths inside a distro's guest, on top of the system mounts. Per-distro, read-write, any path (so a work-profile user can mount `/storage/emulated/9/…`, or share a folder between two distros). Picks up in the interactive shell, desktop sessions, and `run_in_proot`. Also drivable over MCP (`get_custom_binds` / `set_custom_binds`).

📦 **Import a custom rootfs (#284)** — **Desktop → Manage → Import rootfs…** brings your own rootfs tarball (`.tar.gz` or `.tar.xz`) — a proot-distro image, a `docker export`, or a second copy of a distro you already have. It's extracted and registered as a first-class distro (appears in the picker, `set_active_distro`, desktop installs) and used as-is (no packages forced). Also drivable over MCP (`import_distro`).

🧬 **Multiple instances of a distro (#302)** — falls out of the import path: give the import a new id and you get a second, isolated Ubuntu/Debian/… alongside your working one (clean vs. tinkered, per-project, testing). Each instance is a full rootfs.

🔑 **Security keys ignore non-FIDO USB devices** — FIDO2 auth no longer breaks when a USB audio dongle (or any non-FIDO USB device) is attached. Haven now matches only a real CTAPHID interface, so an audio dongle's volume-button HID is ignored, and a USB device that turns out to hold no usable key falls through to your NFC key instead of failing the connection.

## v5.62.0

Three reporter-requested additions for the local Linux desktop and the connect flow.

🔌 **Remap low ports (#300)** — a new toggle in **Desktop → Manage** lets guest services bind privileged ports (below 1024). With it on, a service on port N inside the guest becomes reachable at **N+2000** (e.g. `80` → `2080`), working around Android blocking the app from binding low ports directly. Off by default; it affects every privileged port, including a guest sshd.

🗂️ **Share device storage toggle (#301)** — also in **Desktop → Manage**, and on by default. Turn it off to stop a local session mounting your shared storage (`/storage` and `/sdcard`) into the guest, keeping your photos and downloads hidden from the local Linux environment.

🔗 **`haven://connect` deep link (#305)** — a new link to launch a connection, e.g. `haven://connect?host=<h>&user=<u>&port=<p>&transport=mosh&session=<s>`. If it matches one saved connection it asks for confirmation, then connects and attaches the named session; otherwise it opens the New Connection form pre-filled. Nothing is connected or saved without a tap (links carry no credentials). Useful for a self-hosted dashboard that drops you straight into a host or tmux session in one tap.

## v5.61.1

Fixes and refinements to the SPICE viewer and desktop gestures, from the first round of testing.

🎨 **SPICE colours corrected** — red and blue were swapped on-device; SPICE frames now render with correct colours. (Known issue still under investigation: colours can briefly flip during video streaming.)

👌 **Two-finger desktop gestures** — viewport control now lives on two fingers across SPICE, RDP and VNC: pinch to zoom, drag to pan the view **or** scroll the remote (toggle in the viewer toolbar), and a two-finger tap for a middle click. The previous three-finger gesture didn't work on OnePlus/OxygenOS, which intercepts three-finger touches system-wide before they reach the app.

🔑 **Security-key SSH tunnels** — a SPICE, VNC, RDP or SMB connection tunnelled through a jump host set to **"Any hardware key"** now fires the FIDO touch prompt instead of falling back to a password prompt.

⌨️ **Standard keyboard (#298)** — characters composed by an IME / gesture typing now flush to the shell when you press Enter.

The SPICE decoder is now developed in its own repository — [GlassOnTin/spice-kotlin](https://github.com/GlassOnTin/spice-kotlin) (AGPL-3.0) — and pulled into Haven as a submodule.

## v5.61.0

🖥️ **SPICE remote desktop (#286)** — a native SPICE client for QEMU/KVM and libvirt VMs, alongside the existing VNC and RDP viewers. The display channel decodes the SPICE image codecs (raw, **LZ**, **GLZ**, **ZLIB-GLZ**, and **QUIC**) plus the image cache, server draw operations, hardware-cursor shape/position, and multiple display surfaces. Input covers keyboard, absolute pointer, mouse buttons and the scroll wheel; the viewer shares the VNC/RDP gestures (two-finger pinch-zoom and pan). SSH tunnelling is supported. Add SPICE connections in the Connections tab or via the agent (MCP). Decoding is verified pixel-correct against QEMU and Windows Server 2025 guests.

🔑 **"Any hardware key" authentication** — a new authentication method that lets a connection authenticate with **any** enrolled FIDO2/security key (touch whichever one is present), instead of pinning to a single key. Edit Connection → Authentication methods → add **Any hardware key (FIDO)**.

🔒 **Listing several keys now requires _all_ of them** — adding more than one key to a connection's authentication methods now means "present every listed key" (AND), not "any one of them". Use **Any hardware key** for the either-of behaviour. (A true "must present both" challenge also depends on the server's `AuthenticationMethods publickey,publickey`.)

Also: the FIDO security-key path is now honoured on **jump hosts** (`-J`); a heads-up notification appears while a connect waits on a security-key touch; and a dark-theme readability sweep across the VNC/RDP/SPICE viewer toolbars, SSH key names, and a couple of black-on-dark text spots.

## v5.60.7

Two terminal fixes.

**Standard keyboard mode (#298):** a toolbar Ctrl/Alt tapped before a typed character could get stuck, so Ctrl+D didn't work and Enter was sent as Ctrl+Enter (showing up as garbage like `^[[13;5u`, breaking zsh's first-run wizard). Control combos now reach the shell immediately and the modifier is consumed. Secure mode (the default) was never affected.

**Local proot shell (#299):** bash/zsh process substitution — `diff <(cmd) <(cmd)` and `echo <(cmd)` — failed with "write error: Broken pipe". Fixed `/dev/fd` handling in the bundled proot.

## v5.60.6

🔑 **Security-key SSH reconnect no longer crashes** — a FIDO2/YubiKey (SK) SSH profile that re-authenticated after a dropped connection could crash with a `NullPointerException`: the auto-reconnect path rebuilt the SSH client without carrying over the security-key authenticator. Reconnect now re-authenticates correctly. As a safety net, any other connect path missing the authenticator now fails with a clear, diagnosable error instead of a bare crash.

## v5.60.5

🔎 **Local shell exits are now diagnosable (#294)** — a local Linux (proot) shell that exits immediately, e.g. a session manager like tmux/zellij that won't start, now records its exit code and last output in the connection log (Settings → View connection log) instead of vanishing without a trace.

**For agents/MCP:** `set_preference` / `get_preference` gain `connection_logging_enabled`, so connection logging can be toggled over MCP to diagnose a failing connection (enable → reproduce → `get_connection_log`).

## v5.60.4

🪟 **Terminal background transparency** — a new background-opacity setting (global, with a per-connection override). Set it below 100% and your device wallpaper shows through the terminal and every other Haven screen instead of a solid background.

🔐 **Fix #296** — post-quantum SSH key exchange (ML-KEM / `mlkem768x25519-sha256`) stopped working after the previous update's jsch bump, so connections to servers that require PQ KEX failed with "Algorithm negotiation fail". Restored by updating the bundled cryptography library (BouncyCastle → 1.84).

## v5.60.3

☁️ **Fix #295** — the rclone cloud-remote editor no longer pre-fills option fields with a bogus `[]` default, and **Save** is now gated on running **Configure** first, so a remote can't be saved half-configured.

## v5.60.2

Cleanup of the MCP / agent settings screen.

## v5.60.1

🔑 **Security-key SSH + a new multiplexer tab no longer hangs** — opening a second tmux/screen session on a FIDO2/YubiKey (SK) profile re-dialed and re-authenticated instead of reusing the live connection, which crashed (NPE) and then hung because the touch prompt never surfaced on the Terminal screen. The new tab now reuses the already-authenticated connection (no second touch), and the FIDO touch/PIN prompt is shown on the Terminal screen when a connect does need it.

⌨️ The extra keyboard rows now stay visible in fullscreen.

## v5.60.0

🪟 **tmux/screen session restore over SSH (#290)** — two fixes for profiles that attach to a multiplexer:

- The terminal emulator is now created at connect time, so a multiplexer's startup probes (DA2 / XTVERSION) are answered live instead of leaking escape sequences like `0c)\` onto your prompt.
- A remembered key passphrase is now stored against the key, not the host — so it's correctly reused on the session-picker path instead of being dropped.

**For agents/MCP:** the tmux/screen session picker can be observed and answered over MCP, and `create_connection` accepts a `sessionManager` field.
