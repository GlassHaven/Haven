---
layout: default
title: Automation (Tasker / Automate / MacroDroid)
---

# Automation (Tasker / Automate / MacroDroid)

Drive Haven from your phone's automation apps. Haven ships a native Tasker /
Locale / Automate / MacroDroid plugin action (`Run command on a Haven server`)
and exposes the same capability over its local [MCP HTTP/HTTPS endpoint](../mcp-tools.md)
for scripts and hosts that prefer HTTP requests.

## Run command on a Haven server (plugin action)

A Locale-protocol plugin action built directly into Haven (no separate plugin APK):

- In Tasker, add `Action -> Plugin -> Haven`.
- In Automate, add the `Apps -> Plug-in action` block, tap `Pick plug-in`, and choose `Haven`.
- In MacroDroid, add `Actions -> Applications -> Locale/Tasker Plugin -> Haven`.

Tap to configure and set:

- **Haven server** — a dropdown of your saved SSH connections.
- **Command** — a shell command, run in the login shell. The host substitutes
  its own variables into it *before* it fires, so a one-liner like
  `cd {lv=project_dir} && git pull origin {lv=branch}`
  works with your MacroDroid/Tasker variables inline.
- **Wait until the command finishes** — hold the macro until the command exits
  (Tasker/MacroDroid's "wait for action"). Off = fire-and-forget.
- **Show the command in a live terminal** — bring Haven to the front and stream
  the command into a visible terminal tab so you can watch it run.

When the command finishes it posts a result notification (exit code + a snippet
of the output).

### Result variables

In **wait** mode the action returns the command's output to the host as local
variables, for use in later macro steps:

| Variable | Contents |
|---|---|
| `%hstdout` | the command's standard output |
| `%hstderr` | the command's standard error |
| `%hexit` | the exit code (`-1` if the connection failed) |

The action declares these variables to the host so they appear in its variable
list. Passback only happens in wait mode — a fire-and-forget host has already
moved on by the time the command finishes.

### Watching it run (live terminal)

Android's background-activity-launch rules don't let a plugin's broadcast
receiver bring an app to the foreground on its own, so "show the command in a
live terminal" posts a **"Watch <server>" notification**; tapping it foregrounds
Haven, connects (or reuses) the profile's terminal, and types the command in.
One tap, then you watch the output live.

### Action authentication

When you save an action in Haven's configuration screen, Haven mints an HMAC-SHA256 capability token bound to the target profile and execution mode inside the Locale `EXTRA_BUNDLE` (while leaving `sh.haven.tasker.COMMAND` free for host `%variable` substitution). `TaskerFireReceiver` rejects unauthenticated or flat-extra `FIRE_SETTING` broadcasts so other apps on the phone cannot run commands on your SSH servers without going through Haven's configuration UI. For scripted or CLI callers, use the paired local MCP endpoint below.

### How it runs

The command runs over SSH through Haven's headless exec path: it reuses a live
session for the profile if one is connected, otherwise it connects headlessly.
Headless connect needs a stored password or an unencrypted key — FIDO2/encrypted
keys, and port-knock / SPA / tunnel-routed profiles, must be connected
interactively in Haven at least once first, after which the reuse path serves
them. No extra setup beyond having the SSH connection saved.

## HTTP / HTTPS MCP endpoint

The same `run_command` capability is available over Haven's local agent endpoint (`http://127.0.0.1:8730/mcp`, plus optional `https://127.0.0.1:8750/mcp` with exportable `.crt` root CA or custom `.p12` server bundle under `Settings -> AI agent (MCP)`). Pair once via an on-device approval prompt to mint a bearer token, then call `run_command` from Tasker, Automate, MacroDroid, or `curl`. See [Agent transport (MCP)](../mcp-tools.md).

---

[← All features](../FEATURES.md)
