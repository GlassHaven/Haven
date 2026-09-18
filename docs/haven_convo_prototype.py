#!/usr/bin/env python3
"""haven_convo — a prototype of flowing, turn-based agent↔agent conversation
on top of Haven's existing terminal MCP tools.

Three primitives:
    send_to_agent(session, message)   # deliver ONE turn atomically + submit
    await_turn(session)               # block until that agent is idle-at-prompt
    read_last_turn(session)           # the other agent's latest reply, as text

It speaks MCP JSON-RPC directly to the Haven endpoint (the reverse-tunnelled
http://127.0.0.1:8730/mcp). Speaking the wire protocol directly is the whole
trick: a JSON-RPC client can put *real control bytes* in send_terminal_input —
the bracketed-paste wrapper (ESC[200~ … ESC[201~) and a real carriage return
(0x0D) — which the agent-facing tool layer can't express. That single fact is
what turns last night's 8-call keystroke-archaeology into send → await → read.

This is a CLIENT-SIDE prototype: it shows the shape the native tools should
take. The production version lives in the Haven app (OSC 133 turn events, a
real receive channel), but everything here runs against the live server today.

Provenance: written 2026-06-04 by the "downstream" agent (a Claude Code session
in a sibling tmux) while dog-fooding the Haven terminal MCP to hold a live
conversation with another Claude Code agent in the `near` session. It is a
reference sketch, not wired into the build.

Findings this encodes — for whoever builds the native send_to_agent /
await_turn / read_last_turn:
  1. Named keys. send_terminal_input must accept real Enter/Esc/Ctrl-C, not a
     text param: a passed "\\r" arrives as the two literal chars, a real "\\n"
     is newline-insert in a raw-mode REPL, and there is no way to send control
     bytes (Esc/Ctrl-U) to clear a line. Add keys:["enter","esc","ctrl-c",...].
  2. Separate the paste body from the submit key. If the CR rides the same
     burst as the body, the REPL's paste-detection folds it INTO the paste — you
     get a staged "[Pasted text]" block that never submits. send_to_agent sends
     body, lets the paste window close (settle / detect ESC[201~), THEN Enter.
  3. Honest results. send_terminal_input timed out *while succeeding*; make
     delivered / awaiting-consent / timeout distinct, and consent gate the
     conversation window, not each keystroke.
  4. returnSnapshot on send, so a send returns the resulting screen instead of
     forcing a follow-up read every time.
  5. Turn boundaries from OSC 133, not regex. await_turn here polls the rendered
     screen for "no spinner + a prompt"; the real thing consumes COMMAND_FINISHED
     events. read_last_turn here falls back to a '●'-block scrape for Claude Code
     (its TUI doesn't emit shell-style 133); the real thing returns the
     COMMAND_OUTPUT segment — a genuine receive channel, not a scrape.
  6. Identity. list_sessions surfaced three sessions all labelled "near"; the
     real id was only in chosenSessionName, and the "8730 tunnel == the agent's
     own REPL" heuristic was wrong here (8730 was on `near`, the REPL was
     `pilz`). Surface chosenSessionName + an isAgentRepl flag.

Run (with the Haven MCP endpoint reverse-tunnelled to 127.0.0.1:8730):
    python3 docs/haven_convo_prototype.py      # self-test: sessions, a near
                                               # read, and a shell round-trip
"""
from __future__ import annotations

import json
import re
import time
import urllib.request

ENDPOINT = "http://127.0.0.1:8730/mcp"
PROTO = "2025-06-18"

ESC = "\x1b"
BPASTE_ON, BPASTE_OFF = f"{ESC}[200~", f"{ESC}[201~"
CR = "\r"

# Heuristic markers that a Claude Code REPL is mid-turn (still working).
_BUSY = re.compile(r"esc to interrupt|esc to interru|[·↑↓]\s*\d+(\.\d+)?k?\s*tokens"
                   r"|\b\d+s\b.*(tokens|·)|[✶✻✢✳✽✺✹]\s*\w+…")
# A line that looks like an input prompt waiting for me.
_PROMPT = re.compile(r"(^|\s)[❯➜]\s*$|[\$#%>]\s*$")


class HavenMCP:
    """Minimal MCP Streamable-HTTP client (JSON responses)."""

    def __init__(self, endpoint: str = ENDPOINT):
        self.endpoint = endpoint
        self.session_id: str | None = None
        self._id = 0
        self._initialize()

    def _post(self, payload: dict, notify: bool = False):
        data = json.dumps(payload).encode()
        headers = {
            "Content-Type": "application/json",
            "Accept": "application/json, text/event-stream",
            "MCP-Protocol-Version": PROTO,
        }
        if self.session_id:
            headers["Mcp-Session-Id"] = self.session_id
        req = urllib.request.Request(self.endpoint, data=data, headers=headers, method="POST")
        with urllib.request.urlopen(req, timeout=40) as r:
            sid = r.headers.get("Mcp-Session-Id")
            if sid:
                self.session_id = sid
            body = r.read().decode()
        if notify:
            return None
        return self._parse(body)

    @staticmethod
    def _parse(body: str) -> dict:
        body = body.strip()
        if body.startswith(("event:", "data:")):           # SSE framing, just in case
            datas = [ln[5:].strip() for ln in body.splitlines() if ln.startswith("data:")]
            body = datas[-1] if datas else "{}"
        return json.loads(body or "{}")

    def _initialize(self):
        self._id += 1
        self._post({"jsonrpc": "2.0", "id": self._id, "method": "initialize",
                    "params": {"protocolVersion": PROTO, "capabilities": {},
                               "clientInfo": {"name": "haven-convo", "version": "0.1"}}})
        self._post({"jsonrpc": "2.0", "method": "notifications/initialized"}, notify=True)

    def call(self, tool: str, **args):
        self._id += 1
        res = self._post({"jsonrpc": "2.0", "id": self._id, "method": "tools/call",
                          "params": {"name": tool, "arguments": args}})
        if "error" in res:
            raise RuntimeError(res["error"])
        result = res.get("result", {})
        if "structuredContent" in result:
            return result["structuredContent"]
        text = "".join(c.get("text", "") for c in result.get("content", [])
                       if c.get("type") == "text")
        try:
            return json.loads(text)
        except Exception:
            return text


class HavenConvo:
    def __init__(self, mcp: HavenMCP):
        self.mcp = mcp

    # ── helpers ──────────────────────────────────────────────────────────
    def _snap(self, session: str, semantic: bool = True) -> dict:
        return self.mcp.call("read_terminal_snapshot", sessionId=session,
                             includeSemanticSegments=semantic)

    @staticmethod
    def _lines(snap: dict) -> list[str]:
        return [ln.get("text", "").rstrip() for ln in snap.get("lines", [])]

    # ── primitive 1: send a whole turn, atomically, and submit ───────────
    def send_to_agent(self, session: str, message: str, *, submit: bool = True,
                      bracket: str = "auto", settle: float = 0.4) -> dict:
        """Deliver `message` as ONE unit and (optionally) press a real Enter.

        bracket: "auto" wraps in bracketed-paste only if the target currently
        has paste-mode on; "on"/"off" force it.

        The submit CR is sent as a SEPARATE keystroke after a short settle. If
        it rides the same burst as the body, the REPL's paste-detection folds it
        into the paste (you get a staged block that never submits) — learned the
        hard way relaying this very feedback. Body lands, paste closes, Enter.
        """
        use_bracket = bracket == "on" or (bracket == "auto"
                                          and self._snap(session, semantic=False)
                                          .get("bracketPasteMode"))
        body = f"{BPASTE_ON}{message}{BPASTE_OFF}" if use_bracket else message
        res = self.mcp.call("send_terminal_input", sessionId=session, text=body)
        if submit:
            time.sleep(settle)
            res = self.mcp.call("send_terminal_input", sessionId=session, text=CR)  # real 0x0D, own keystroke
        return res

    # ── primitive 2: block until it's my turn ────────────────────────────
    def await_turn(self, session: str, *, timeout: float = 120, poll: float = 1.0,
                   settle: float = 1.5) -> bool:
        """Return True once the target is idle-at-prompt for `settle` seconds.

        Production version would consume OSC 133 COMMAND_FINISHED events instead
        of polling; this heuristic reads the rendered screen (no busy spinner +
        an input prompt present) and is good enough to demonstrate the shape.
        """
        deadline = time.time() + timeout
        idle_since: float | None = None
        while time.time() < deadline:
            snap = self._snap(session, semantic=False)
            if self._idle(snap):
                idle_since = idle_since or time.time()
                if time.time() - idle_since >= settle:
                    return True
            else:
                idle_since = None
            time.sleep(poll)
        return False

    @staticmethod
    def _idle(snap: dict) -> bool:
        lines = [ln.get("text", "") for ln in snap.get("lines", [])]
        if any(_BUSY.search(ln) for ln in lines):
            return False
        # idle iff some line near the bottom looks like a waiting prompt
        return any(_PROMPT.search(ln) for ln in lines[-8:])

    # ── primitive 3: read the other agent's latest reply as a message ────
    def read_last_turn(self, session: str) -> str:
        """The most recent *output* turn as text — not a screen scrape.

        Prefers OSC 133 semantic segments (COMMAND_OUTPUT since the last
        PROMPT). Falls back to a Claude-Code-aware scrape: the assistant's
        last '●'-bulleted block above the input box.
        """
        snap = self._snap(session, semantic=True)
        seg = self._from_semantic(snap)
        if seg:
            return seg
        return self._scrape(snap)

    @staticmethod
    def _from_semantic(snap: dict) -> str:
        out: list[str] = []
        for ln in snap.get("lines", []):
            segs = ln.get("segments") or ln.get("semanticSegments") or []
            kinds = {s.get("type") or s.get("kind") for s in segs}
            if "PROMPT" in kinds or "COMMAND_INPUT" in kinds:
                out = []                                # reset at each new prompt
            elif "COMMAND_OUTPUT" in kinds:
                out.append(ln.get("text", "").rstrip())
        return "\n".join(out).strip()

    @staticmethod
    def _scrape(snap: dict) -> str:
        lines = [ln.get("text", "").rstrip() for ln in snap.get("lines", [])]
        # drop the input box + status bar (everything from the last prompt down)
        cut = len(lines)
        for i in range(len(lines) - 1, -1, -1):
            if _PROMPT.search(lines[i]) or set(lines[i]) <= set("─╌ "):
                cut = i
                break
        body = lines[:cut]
        # keep the last contiguous assistant block (Claude Code marks it '●')
        starts = [i for i, ln in enumerate(body) if ln.startswith("●")]
        if starts:
            body = body[starts[-1]:]
        return "\n".join(ln for ln in body).strip()


# ── demo / self-test ──────────────────────────────────────────────────────
def _find(sessions: list[dict], tmux: str) -> str | None:
    for s in sessions:
        if s.get("chosenSessionName") == tmux:
            return s.get("sessionId")
    return None


def demo():
    mcp = HavenMCP()
    convo = HavenConvo(mcp)
    print("== sessions ==")
    sessions = mcp.call("list_sessions").get("sessions", [])
    for s in sessions:
        print(f"  {s.get('chosenSessionName'):8} {s.get('sessionId')}  {s.get('status')}")

    near = _find(sessions, "near")
    if near:
        print("\n== read_last_turn(near) — the other agent's latest reply ==")
        print(convo.read_last_turn(near)[:900] or "  (empty)")
        print("\n== await_turn(near, 4s) — is it my turn? ==")
        print("  idle:", convo.await_turn(near, timeout=4, settle=1.0))

    print("\n== round-trip on a throwaway local shell ==")
    sh = mcp.call("open_local_shell").get("sessionId")
    print("  shell:", sh)
    convo.await_turn(sh, timeout=8, settle=0.6)
    token = f"HAVEN_CONVO_OK_{int(time.time())}"
    convo.send_to_agent(sh, f"echo {token}")
    convo.await_turn(sh, timeout=8, settle=0.6)
    reply = convo.read_last_turn(sh)
    print("  reply:", repr(reply[-200:]))
    print("  PASS" if token in reply else "  (token not echoed back)")


if __name__ == "__main__":
    demo()
