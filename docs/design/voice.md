---
layout: default
title: "Design: Voice — hands-free agent↔user comms"
---

# Voice — a modality over the existing consent and reach-back surfaces

> **Status:** design / forward-looking. Not built. Defines how an agent and its
> operator hold a spoken conversation through Haven, with driving as the sharp
> case.

## Why

Driving is the case where the current surfaces fail outright. The phone is in a
mount, the eyes are on the road, the consent sheet cannot be read and a tap is
not available — yet the agent is still working and still needs approval, and
the operator still needs to hear what happened. The same shape applies off-road
(cooking, cycling, accessibility), but driving sets the design constraints:
audio in and out, minimal attention, no fine-grained touch.

The resolution is deliberately narrow. Voice is **a modality over decision
points and tools that already exist**, not a new channel:

- The consent decision stays `AgentConsentManager`'s decision point (in
  `core:data`), with an audio renderer beside the bottom sheet.
- Terminal narration subscribes to events the UI already parses — most
  importantly the command-done markers (OSC 133, `n;D`) the termlib fork
  already handles — not to raw scrollback.
- The agent initiates speech through a reach-back tool registered in
  `McpTools.kt` beside `present_media` (:587) and `raise_notification` (:643),
  behind the same consent gate and audit table.

Humans tap or speak, agents call, both observe — the Vision's actionable-API
rule applied to a third input device. No new trust surface is created; the
audit trail is unchanged.

## Non-goals

- No assistant persona, no bundled model, no wake-word service. Summarising
  "what did the output say" is the job of whatever model the user has pointed
  their agent at; Haven renders speech and exposes verbs, it does not write the
  prose.
- No voice approval for Tier-4 actions. Those stay visual, per-action, always.
- No raw-scrollback TTS. Spoken terminal output is event-based (§2); free-form
  reading is delegated to the agent via a read-back verb.
- Speech recognition stays on-device. The cloud recognizer is not an option;
  on a device without an on-device recognizer the bundled engine is used.

## Component 1 — spoken consent renderer

`AgentConsentManager` gains an audio renderer beside the bottom sheet. The
spoken form of a request is the same content the sheet shows: calling client,
tool name, a short redacted summary of the arguments, the tier. The reply
grammar is closed and small: yes/approve, no/deny/cancel, repeat. No LLM in the
loop for a yes/no decision.

One shipped behaviour interacts badly with driving and has to be addressed
explicitly: consent currently **fails closed when the screen is off**. That is
the right default for a phone on a desk, and it would make voice consent
useless in a mount. The design therefore adds an explicit, opt-in
**hands-free approvals** mode:

- Off (default): today's behaviour, unchanged.
- On: Tier-2 requests may be approved by voice while the screen is off, with
  the spoken scope statement and a bounded re-prompt window; a request that is
  not answered inside the window still fails closed. Tier-4 actions always
  fall back to the visual sheet. The audit record is identical either way.

Engines. TTS is the system engine (`android.speech.tts`); no new permission,
offline on most devices. STT needs `RECORD_AUDIO` — a new runtime permission
for a privacy-first app, so the toggle is off by default and the permission is
requested only when hands-free mode is enabled. Recognition uses the platform
on-device recogniser where available
(`SpeechRecognizer.isOnDeviceRecognitionAvailable`); the fallback is a bundled
whisper.cpp engine through the existing native-build machinery. Availability
on de-Googled devices is unverified until prototyped on one.

## Component 2 — event narration

A small TTS announcer that speaks facts, not prose:

- Command exits (the OSC 133 markers the terminal already parses): "command
  finished, exit 1."
- Session lifecycle from the same flows the UI subscribes to: session
  connected, dropped, reconnected; port forward added or removed.
- Consent lifecycle: a request raised, granted, denied (audibly confirms the
  voice loop itself closed).

A rate limiter and per-profile loudness (errors only / lifecycle / off) keep it
from becoming a chatty interrupt source. Narration reports what happened; the
*meaning* of a long output belongs to the agent, which brings us to component
3.

## Component 3 — the `speak` reach-back tool

A new MCP verb beside `raise_notification`: the agent submits text, Haven
speaks it. Same consent gate as the other reach-back tools, same audit record.

Audio is ephemeral, and the Vision's rule that the UI is ground truth needs a
durable trace of a spoken line. Every `speak` call therefore also lands as a
transcript entry on the Agent activity screen, next to the audit record it
shares a gate with. Voice is a channel *to* the operator, never around them,
and the transcript is what makes that auditable.

A paired read-back verb (`read_terminal_output`, summarised by the agent
through its own model, spoken through `speak`) covers "read me what it said"
without Haven shipping any model.

## Voice→tool routing (later)

Spoken phrases mapped onto existing ViewModel verbs — navigation, read-backs,
session focus — routed through the same consent stack. A closed verb set first;
free-form voice command parsing is not a target.

## Driving

- Today, with zero code: `raise_notification` output is read aloud by Android
  Auto's notification reading (claimed, not device-verified).
- Later: a `CarAppService` template rendering pending consent requests as
  tappable list items, so approval is eyes-free without requiring voice
  recognition in road noise. Delegation per the build-vs-delegate rule — the
  car screen is the OS's surface; Haven builds no in-app driving UI.

## Phasing

1. **Spoken consent** — TTS renderer, closed-grammar STT, hands-free toggle,
   audit. Smallest slice; the one that matters while driving.
2. **Narration + `speak`** — event announcer, reach-back verb, transcript
   surface.
3. **Read-back verb, voice→tool routing, Auto template.**

## Open questions

- On-device STT/TTS availability across de-Googled and vendor ROMs — device
  matrix unknown until tested.
- STT accuracy in road noise — unverified; whisper.cpp small models are the
  expected workhorse.
- Whether hands-free voice approval should require the device to be unlocked
  (current assumption: yes, unless Android Auto projection implies it).