#!/usr/bin/env python3
"""haven-egress-proxy — root-owned transparent egress gate for sandboxed agents.

Spawned per-sandbox by haven-sandbox-helper (root) on host loopback at an
ephemeral port. The jail's nftables ruleset allows ONLY the direct IP-pinned
model endpoint(s) + this proxy port + slirp4netns DNS; HTTP(S)_PROXY routes
proxy-aware tools here, while nft drops any direct-socket bypass.

Policy order (per request):
  parse guard -> SSRF/DNS-rebinding guard -> deny/telemetry list (before DNS
  resolution, so denied hosts never leak DNS queries) -> standing allow (443)
  -> in-memory session-grant cache -> Haven phone consent (phase2) / fail-closed
  deny (phase1).

Security invariants:
  - CONNECT tunnels opaque TLS (no MITM).
  - Session grants live strictly in-memory inside this root process (die with
    the sandbox); no agent-writable decision store exists on disk.
  - Phase 2 verifies HMAC-SHA256 signatures over the canonical LF-joined
    decision payload against the root-owned 0600 signing secret.
  - Plain HTTP enforces RFC 9112 request-line/header syntax, rejects obs-fold,
    bare CR/LF/CTL bytes, Transfer-Encoding, duplicate/conflicting Content-Length,
    and duplicate/mismatched Host headers, strips Connection-named hop-by-hop
    and Proxy-* headers, emits a single canonical Host header, and reads exact
    Content-Length bytes without forwarding trailing pipelined bytes.
  - Run isolated via: env -i PATH=/usr/bin:/bin python3 -I -S haven-egress-proxy.py ...
"""
import argparse
import asyncio
import base64
import hashlib
import hmac
import ipaddress
import json
import os
import socket
import sys
import time
import uuid

POLICY = {"standing_allow": [], "deny": []}
AUDIT_PATH = None
FAIL_CLOSED_AUDIT = False
SANDBOX_ID = "?"
MODE = "phase1"
HAVEN_URL = ""               # e.g. http://127.0.0.1:8788/mcp
HAVEN_KEY_PATH = "/usr/local/lib/haven-sandbox/haven.key"  # root-owned 0600 HMAC secret
SESSION_GRANTS = {}          # (host, port) -> expiry_ms|None ; in-memory, sandbox lifetime
PENDING = {}                 # (host, port) -> asyncio.Task (phase2 coalescing)
_SEQ = 0
MAX_HEADER = 16 * 1024
MAX_HTTP_BODY = 64 * 1024 * 1024
IDLE_TIMEOUT = 300
CONSENT_TIMEOUT = 70         # > Haven's 60s fail-closed timeout
MAX_PENDING = 3
_MCP_ID = 0
_TCHAR = frozenset("!#$%&'*+-.^_`|~0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz")


def _standing_allow_hosts():
    return POLICY.get("standing_allow") or POLICY.get("github") or []


def _deny_patterns():
    return POLICY.get("deny") or POLICY.get("telemetry") or []


def _is_token(s):
    return bool(s) and all(c in _TCHAR for c in s)


def _has_bad_ctl(s, allow_htab=False):
    for c in s:
        o = ord(c)
        if o == 0x7F or (o < 0x20 and not (allow_htab and c == "\t")):
            return True
    return False


def _parse_hostport(authority, default_port):
    if not authority or "@" in authority or _has_bad_ctl(authority) or " " in authority or "\t" in authority:
        return None, None
    if authority.startswith("["):
        end = authority.find("]")
        if end == -1:
            return None, None
        host = authority[1:end]
        rem = authority[end + 1:]
        if rem == "":
            port = default_port
        elif rem.startswith(":") and rem[1:].isascii() and rem[1:].isdigit():
            port = int(rem[1:])
        else:
            return None, None
    else:
        if authority.count(":") > 1:
            return None, None
        host, sep, p = authority.partition(":")
        if sep:
            if not p or not p.isascii() or not p.isdigit():
                return None, None
            port = int(p)
        else:
            port = default_port
    if not host or not (1 <= port <= 65535):
        return None, None
    return host, port


def audit(host, port, method, decision, source, bytes_in=0, bytes_out=0, grant_id=""):
    global _SEQ
    _SEQ += 1
    rec = {
        "seq": _SEQ,
        "unix_ms": int(time.time() * 1000),
        "sandbox_id": SANDBOX_ID,
        "host": host,
        "port": port,
        "method": method,
        "decision": decision,
        "source": source,
        "bytes_in": bytes_in,
        "bytes_out": bytes_out,
        "grant_id": grant_id,
    }
    if not AUDIT_PATH:
        return not FAIL_CLOSED_AUDIT
    try:
        with open(AUDIT_PATH, "a", encoding="utf-8") as f:
            f.write(json.dumps(rec) + "\n")
        return True
    except OSError as e:
        sys.stderr.write(f"[egress] audit write failed: {e}\n")
        return not FAIL_CLOSED_AUDIT


def norm_host(h):
    h = h.strip().lower().rstrip(".")
    if not h or any(c in h for c in " \t\r\n/@\\"):
        return None
    try:
        h = h.encode("idna").decode("ascii")
    except (UnicodeError, ValueError):
        return None
    return h


def suffix_match(host, patterns):
    for p in patterns:
        p = p.lower()
        if p.startswith("*."):
            base = p[2:]
            if host == base or host.endswith("." + base):
                return True
        elif host == p:
            return True
    return False


def is_global_ip(ip):
    try:
        a = ipaddress.ip_address(ip)
    except ValueError:
        return False
    if a.is_loopback or a.is_private or a.is_link_local or a.is_multicast or a.is_reserved or a.is_unspecified:
        return False
    # slirp4netns gateway/DNS + RFC 6598 CGNAT explicitly blocked
    if ip in ("10.0.2.2", "10.0.2.3"):
        return False
    if isinstance(a, ipaddress.IPv4Address) and a in ipaddress.ip_network("100.64.0.0/10"):
        return False
    return True


def resolve_global(host):
    """Resolve host; return a pinned global IP or None if ANY answer is non-global."""
    try:
        infos = socket.getaddrinfo(host, None, proto=socket.IPPROTO_TCP)
    except socket.gaierror:
        return None
    ips = {i[4][0] for i in infos}
    if not ips:
        return None
    for ip in ips:
        if not is_global_ip(ip):
            return None  # reject if ANY answer is internal (DNS rebinding / SSRF guard)
    return sorted(ips)[0]


def policy_check(host, port):
    """Return (allow: bool, source: str, pinned_ip: str | None)."""
    host = norm_host(host)
    if not host:
        return False, "parse", None
    # IP-literal target: must be global
    try:
        ipaddress.ip_address(host)
        if not is_global_ip(host):
            return False, "ssrf", None
        pinned = host
    except ValueError:
        pinned = None  # hostname; resolve only after deny list check (no DNS leak)

    # Deny / telemetry check BEFORE any allow or DNS lookup
    if suffix_match(host, _deny_patterns()):
        return False, "telemetry", None

    # Standing allow (exact host, HTTPS 443 only)
    if port == 443 and host in _standing_allow_hosts():
        ip = pinned or resolve_global(host)
        if ip is None:
            return False, "ssrf", None
        return True, "standing", ip

    # In-memory session grant cache (exact host + port, honouring expiry_ms)
    if (host, port) in SESSION_GRANTS:
        exp = SESSION_GRANTS[(host, port)]
        if exp is not None and exp <= int(time.time() * 1000):
            del SESSION_GRANTS[(host, port)]
        else:
            ip = pinned or resolve_global(host)
            if ip is None:
                return False, "ssrf", None
            return True, "session", ip

    # Otherwise: resolve + SSRF guard, then hand off to phase2 consent or phase1 deny
    ip = pinned or resolve_global(host)
    if ip is None:
        return False, "ssrf", None
    return False, "consent", ip


def verify_decision(res, request_id, host, port):
    """Verify Haven's HMAC-SHA256 signed egress decision against HAVEN_KEY_PATH.

    Canonical payload (UTF-8, LF-separated, no trailing newline):
      request_id\\nsandbox_id\\nhost\\nstr(port)\\ndecision\\nexpiry("" if null)\\ngrant_id\\nnonce
    """
    try:
        with open(HAVEN_KEY_PATH, "rb") as f:
            key = f.read()  # raw bytes, never stripped
        if len(key) < 16:
            return False
        if res.get("request_id") != request_id or res.get("host") != host or res.get("port") != port:
            return False
        decision = res.get("decision")
        if decision not in ("once", "session", "deny"):
            return False
        expiry = res.get("expiry_ms")
        now_ms = int(time.time() * 1000)
        if expiry is not None and expiry <= now_ms:
            return False
        canonical = "\n".join([
            str(res.get("request_id", "")),
            str(res.get("sandbox_id", "")),
            str(res.get("host", "")),
            str(res.get("port", "")),
            str(decision),
            "" if expiry is None else str(expiry),
            str(res.get("grant_id", "")),
            str(res.get("nonce", "")),
        ])
        sig = base64.b64decode(res.get("signature", ""), validate=True)
        expect = hmac.new(key, canonical.encode("utf-8"), hashlib.sha256).digest()
        return hmac.compare_digest(expect, sig)
    except (OSError, ValueError, TypeError):
        return False


_BEARER = None
_SESSION = None


async def mcp_request(method, params, bearer=None):
    """Haven MCP HTTP JSON-RPC call (single POST -> single JSON response)."""
    global _MCP_ID, _SESSION
    _MCP_ID += 1
    u = HAVEN_URL.split("://", 1)[-1]
    hostport, _, path = u.partition("/")
    path = "/" + path
    h, _, p = hostport.partition(":")
    port = int(p) if p else 80
    body = json.dumps({"jsonrpc": "2.0", "id": _MCP_ID, "method": method, "params": params}).encode()
    headers = [
        f"POST {path} HTTP/1.1",
        f"Host: {hostport}",
        "Content-Type: application/json",
        "Accept: application/json",
        f"Content-Length: {len(body)}",
        "Connection: close",
    ]
    if bearer:
        headers.append(f"Authorization: Bearer {bearer}")
    if _SESSION:
        headers.append(f"Mcp-Session-Id: {_SESSION}")
    reader, writer = await asyncio.wait_for(asyncio.open_connection(h, port), 10)
    writer.write(("\r\n".join(headers) + "\r\n\r\n").encode() + body)
    await writer.drain()
    head = await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), CONSENT_TIMEOUT + 5)
    htxt = head.decode("latin1", "replace")
    parts = htxt.split(" ")
    status = int(parts[1]) if len(parts) > 1 and parts[1].isdigit() else 0
    clen = 0
    for ln in htxt.split("\r\n"):
        low = ln.lower()
        if low.startswith("content-length:"):
            clen = int(ln.split(":", 1)[1].strip())
        elif low.startswith("mcp-session-id:"):
            _SESSION = ln.split(":", 1)[1].strip()
    resp = await asyncio.wait_for(reader.readexactly(clen), CONSENT_TIMEOUT + 5) if clen else await reader.read()
    writer.close()
    if status == 404:
        raise RuntimeError("mcp-404-reinit")
    if status != 200:
        raise RuntimeError(f"mcp-http-{status}")
    d = json.loads(resp.decode("utf-8", "replace"))
    if "error" in d:
        raise RuntimeError(str(d["error"]))
    return d


async def mcp_notify(method, params, bearer=None):
    """Fire-and-forget JSON-RPC notification."""
    u = HAVEN_URL.split("://", 1)[-1]
    hostport, _, path = u.partition("/")
    path = "/" + path
    h, _, p = hostport.partition(":")
    port = int(p) if p else 80
    body = json.dumps({"jsonrpc": "2.0", "method": method, "params": params}).encode()
    headers = [
        f"POST {path} HTTP/1.1",
        f"Host: {hostport}",
        "Content-Type: application/json",
        "Accept: application/json",
        f"Content-Length: {len(body)}",
        "Connection: close",
    ]
    if bearer:
        headers.append(f"Authorization: Bearer {bearer}")
    try:
        _, writer = await asyncio.wait_for(asyncio.open_connection(h, port), 10)
        writer.write(("\r\n".join(headers) + "\r\n\r\n").encode() + body)
        await writer.drain()
        writer.close()
    except Exception:
        pass


async def ensure_paired():
    global _BEARER
    if _BEARER:
        return
    d = await mcp_request(
        "initialize",
        {
            "protocolVersion": "2025-06-18",
            "capabilities": {},
            "clientInfo": {"name": "haven-egress-proxy", "version": "1.0"},
        },
    )
    _BEARER = d.get("result", {}).get("_meta", {}).get("sh.haven/pairingToken")
    if not _BEARER:
        raise RuntimeError("no-pairing-token")
    await mcp_notify("notifications/initialized", {}, bearer=_BEARER)


async def haven_request_grant(host, port, scheme, request_id):
    await ensure_paired()
    d = await mcp_request(
        "tools/call",
        {
            "name": "request_egress_grant",
            "arguments": {
                "request_id": request_id,
                "sandbox_id": SANDBOX_ID,
                "host": host,
                "port": port,
                "scheme": scheme,
                "reason": f"Sandbox egress to {host}:{port}",
            },
        },
        bearer=_BEARER,
    )
    result = d.get("result", {})
    if isinstance(result.get("structuredContent"), dict):
        return result["structuredContent"]
    for c in result.get("content", []):
        if c.get("type") == "text":
            try:
                return json.loads(c["text"])
            except Exception:
                pass
    return result


async def _grant_once(host, port, scheme, request_id):
    try:
        res = await haven_request_grant(host, port, scheme, request_id)
    except Exception as e:
        sys.stderr.write(f"[egress] haven consent unavailable: {e}\n")
        return None
    if not isinstance(res, dict):
        sys.stderr.write("[egress] grant response not a JSON object\n")
        return None
    if not verify_decision(res, request_id, host, port):
        sys.stderr.write(f"[egress] signature verification failed for rid={request_id}\n")
        audit(host, port, "CONNECT", "deny", "bad-signature")
        return None
    return res


async def resolve_consent(host, port, scheme):
    """Phase 2: request non-bypassable consent from Haven, coalescing concurrent duplicates."""
    key = (host, port)
    now_ms = int(time.time() * 1000)
    if key in SESSION_GRANTS:
        exp = SESSION_GRANTS[key]
        if exp is None or exp > now_ms:
            return True, "session"
        del SESSION_GRANTS[key]
    if key not in PENDING and len(PENDING) >= MAX_PENDING:
        return False, "rate-limit"
    if key in PENDING:
        task = PENDING[key]
    else:
        task = asyncio.ensure_future(_grant_once(host, port, scheme, uuid.uuid4().hex))
        PENDING[key] = task
    try:
        res = await asyncio.wait_for(asyncio.shield(task), CONSENT_TIMEOUT)
    except asyncio.TimeoutError:
        return False, "timeout"
    finally:
        PENDING.pop(key, None)
    if not isinstance(res, dict):
        return False, "consent-deny"
    decision = res.get("decision")
    if decision == "session":
        SESSION_GRANTS[key] = res.get("expiry_ms")
        return True, "consent"
    if decision == "once":
        return True, "consent"
    return False, "consent-deny"


async def splice(reader, writer):
    n = 0
    try:
        while True:
            data = await asyncio.wait_for(reader.read(65536), IDLE_TIMEOUT)
            if not data:
                break
            writer.write(data)
            await writer.drain()
            n += len(data)
    except (asyncio.TimeoutError, ConnectionError, OSError):
        pass
    return n


async def handle_connect(client_r, client_w, host, port):
    nhost = norm_host(host)
    if not nhost:
        audit(host, port, "CONNECT", "deny", "parse")
        client_w.write(b"HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n")
        await client_w.drain()
        return
    if port not in (443, 80):
        audit(nhost, port, "CONNECT", "deny", "port")
        client_w.write(b"HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n")
        await client_w.drain()
        return
    allow, source, ip = policy_check(nhost, port)
    if not allow and source == "consent" and MODE == "phase2":
        allow, source = await resolve_consent(nhost, port, "https")
    if not allow:
        audit(nhost, port, "CONNECT", "deny", source)
        client_w.write(b"HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n")
        await client_w.drain()
        return
    if not audit(nhost, port, "CONNECT", "allow", source):
        client_w.write(b"HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\n\r\n")
        await client_w.drain()
        return
    try:
        up_r, up_w = await asyncio.wait_for(asyncio.open_connection(ip, port), 15)
    except (OSError, asyncio.TimeoutError):
        audit(nhost, port, "CONNECT", "deny", "connect-fail")
        client_w.write(b"HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n")
        await client_w.drain()
        return
    client_w.write(b"HTTP/1.1 200 Connection established\r\n\r\n")
    await client_w.drain()
    up = asyncio.ensure_future(splice(client_r, up_w))
    down = asyncio.ensure_future(splice(up_r, client_w))
    _, pending = await asyncio.wait([up, down], return_when=asyncio.FIRST_COMPLETED)
    for t in pending:
        t.cancel()
    bin_ = up.result() if up.done() and not up.cancelled() else 0
    bout = down.result() if down.done() and not down.cancelled() else 0
    audit(nhost, port, "CONNECT", "close", source, bytes_in=bin_, bytes_out=bout)
    up_w.close()


async def _bad_request(writer, host, port, method, reason, code=b"400 Bad Request"):
    audit(host, port, method, "deny", reason)
    writer.write(b"HTTP/1.1 " + code + b"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
    await writer.drain()


async def handle_http(client_r, client_w, method, url, raw_headers, initial_body):
    # absolute-form http://host[:port]/path (RFC 9112 section 3.2.2)
    rest = url[len("http://"):]
    authority, _, path = rest.partition("/")
    path = "/" + path
    if _has_bad_ctl(path) or " " in path or "\t" in path:
        return await _bad_request(client_w, authority, 0, method, "parse")
    host, port = _parse_hostport(authority, 80)
    if host is None:
        return await _bad_request(client_w, authority, 0, method, "parse")
    nhost = norm_host(host)
    if not nhost:
        return await _bad_request(client_w, host, port, method, "parse")

    # Validate Host, Transfer-Encoding, Content-Length, and Connection hop-by-hop headers
    seen_hosts = []
    clen_vals = set()
    hop = {
        "host",
        "proxy-connection",
        "proxy-authorization",
        "proxy-authenticate",
        "connection",
        "keep-alive",
        "te",
        "trailer",
        "transfer-encoding",
        "upgrade",
    }
    for k, v in raw_headers:
        kl = k.lower()
        if kl == "transfer-encoding":
            return await _bad_request(client_w, nhost, port, method, "smuggling-te")
        if kl == "host":
            seen_hosts.append(v)
        elif kl == "content-length":
            if not v or not v.isascii() or not v.isdigit():
                return await _bad_request(client_w, nhost, port, method, "smuggling-cl")
            clen_vals.add(int(v))
        elif kl == "connection":
            for tok in v.split(","):
                t = tok.strip(" \t").lower()
                if t:
                    hop.add(t)

    if len(seen_hosts) > 1:
        return await _bad_request(client_w, nhost, port, method, "duplicate-host")
    if len(seen_hosts) == 1 and seen_hosts[0]:
        hh, hp = _parse_hostport(seen_hosts[0], 80)
        if hh is None or norm_host(hh) != nhost or hp != port:
            return await _bad_request(client_w, nhost, port, method, "host-mismatch")
    if len(clen_vals) > 1:
        return await _bad_request(client_w, nhost, port, method, "smuggling-cl")

    clen = next(iter(clen_vals), 0)
    if clen > MAX_HTTP_BODY:
        return await _bad_request(client_w, nhost, port, method, "body-too-large", b"413 Content Too Large")

    if port != 80:
        audit(nhost, port, method, "deny", "port")
        client_w.write(b"HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
        await client_w.drain()
        return

    allow, source, ip = policy_check(nhost, port)
    if not allow and source == "consent" and MODE == "phase2":
        allow, source = await resolve_consent(nhost, port, "http")
    if not allow:
        audit(nhost, port, method, "deny", source)
        client_w.write(b"HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
        await client_w.drain()
        return

    # Read exact Content-Length body (never forward trailing pipelined bytes)
    if clen == 0:
        body = b""
    elif len(initial_body) >= clen:
        body = initial_body[:clen]
    else:
        need = clen - len(initial_body)
        body = initial_body + await asyncio.wait_for(client_r.readexactly(need), 30)

    if not audit(nhost, port, method, "allow", source, bytes_in=len(body)):
        client_w.write(b"HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
        await client_w.drain()
        return

    try:
        up_r, up_w = await asyncio.wait_for(asyncio.open_connection(ip, port), 15)
    except (OSError, asyncio.TimeoutError):
        client_w.write(b"HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
        await client_w.drain()
        return

    canon_auth = f"[{nhost}]" if ":" in nhost else nhost
    fwd = [f"{method} {path} HTTP/1.1", f"Host: {canon_auth}"]
    emitted_cl = False
    for k, v in raw_headers:
        kl = k.lower()
        if kl in hop or kl.startswith("proxy-"):
            continue
        if kl == "content-length":
            if emitted_cl:
                continue
            emitted_cl = True
            fwd.append(f"Content-Length: {clen}")
        else:
            fwd.append(f"{k}: {v}")
    fwd.append("Connection: close")
    up_w.write(("\r\n".join(fwd) + "\r\n\r\n").encode("latin1") + body)
    await up_w.drain()
    resp = await up_r.read()
    client_w.write(resp)
    await client_w.drain()
    up_w.close()


async def handle_client(reader, writer):
    try:
        raw = await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), 30)
        if len(raw) > MAX_HEADER:
            writer.close()
            return
        head, _, rest = raw.partition(b"\r\n\r\n")
        lines = head.decode("latin1").split("\r\n")
        if not lines or any("\r" in ln or "\n" in ln for ln in lines):
            return await _bad_request(writer, "?", 0, "?", "parse")
        parts = lines[0].split(" ")
        if len(parts) != 3:
            return await _bad_request(writer, "?", 0, "?", "parse")
        method, target, version = parts
        if not _is_token(method) or version not in ("HTTP/1.1", "HTTP/1.0") or _has_bad_ctl(target) or "\t" in target:
            return await _bad_request(writer, target[:64], 0, method[:16], "parse")

        raw_headers = []
        for ln in lines[1:]:
            if not ln:
                continue
            if ln[0] in (" ", "\t"):  # reject obs-fold (RFC 9112 section 5.2)
                return await _bad_request(writer, target[:64], 0, method, "obs-fold")
            k, sep, v = ln.partition(":")
            if not sep or not _is_token(k):  # rejects whitespace before colon (RFC 9112 section 5.1)
                return await _bad_request(writer, target[:64], 0, method, "bad-header-name")
            v = v.strip(" \t")
            if _has_bad_ctl(v, allow_htab=True):
                return await _bad_request(writer, target[:64], 0, method, "bad-header-val")
            raw_headers.append((k, v))

        if method.upper() == "CONNECT":
            host, port = _parse_hostport(target, 443)
            if host is None:
                return await _bad_request(writer, target[:64], 0, "CONNECT", "parse")
            connect_hosts = [v for k, v in raw_headers if k.lower() == "host"]
            if len(connect_hosts) > 1:
                return await _bad_request(writer, host, port, "CONNECT", "duplicate-host")
            if len(connect_hosts) == 1 and connect_hosts[0]:
                hh, hp = _parse_hostport(connect_hosts[0], port)
                if hh is None or norm_host(hh) != norm_host(host) or hp != port:
                    return await _bad_request(writer, host, port, "CONNECT", "host-mismatch")
            await handle_connect(reader, writer, host, port)
        elif target.lower().startswith("http://"):
            await handle_http(reader, writer, method.upper(), target, raw_headers, rest)
        else:
            await _bad_request(writer, target[:64], 0, method, "parse")
    except (asyncio.IncompleteReadError, asyncio.LimitOverrunError, asyncio.TimeoutError, ConnectionError, OSError, ValueError):
        pass
    finally:
        try:
            writer.close()
        except OSError:
            pass


def run_self_check(policy_path):
    global POLICY
    with open(policy_path, encoding="utf-8") as f:
        POLICY = json.load(f)
    allow_list = _standing_allow_hosts()
    deny_list = _deny_patterns()
    if not isinstance(allow_list, list) or not allow_list:
        raise SystemExit("self-check failed: policy standing_allow/github list is empty")
    if not isinstance(deny_list, list) or not deny_list:
        raise SystemExit("self-check failed: policy deny/telemetry list is empty")
    if is_global_ip("127.0.0.1") or is_global_ip("10.0.2.2") or is_global_ip("100.64.0.1") or not is_global_ip("93.184.216.34"):
        raise SystemExit("self-check failed: is_global_ip invariant broken")
    allow, src, _ = policy_check("127.0.0.1", 443)
    if allow or src != "ssrf":
        raise SystemExit("self-check failed: loopback SSRF check broken")
    sys.stdout.write(f"haven-egress-proxy self-check OK ({len(allow_list)} standing-allow, {len(deny_list)} deny patterns)\n")


async def main():
    global POLICY, AUDIT_PATH, FAIL_CLOSED_AUDIT, SANDBOX_ID, MODE, HAVEN_URL, HAVEN_KEY_PATH
    ap = argparse.ArgumentParser(description="Haven root-owned sandbox egress gate")
    ap.add_argument("--self-check", action="store_true", help="Validate policy and core invariants, then exit")
    ap.add_argument("--port-file", default="")
    ap.add_argument("--sandbox-id", default="?")
    ap.add_argument("--audit", default="")
    ap.add_argument("--policy", required=True)
    ap.add_argument("--key-file", default="/usr/local/lib/haven-sandbox/haven.key")
    ap.add_argument("--haven", default="")
    ap.add_argument("--mode", choices=("phase1", "phase2"), default="phase1")
    ap.add_argument("--fail-closed-audit", action="store_true")
    args = ap.parse_args()
    if args.self_check:
        run_self_check(args.policy)
        return
    if not args.port_file or not args.audit:
        ap.error("--port-file and --audit are required unless --self-check is set")
    SANDBOX_ID = args.sandbox_id
    AUDIT_PATH = args.audit
    FAIL_CLOSED_AUDIT = args.fail_closed_audit
    MODE = args.mode
    HAVEN_URL = args.haven
    HAVEN_KEY_PATH = args.key_file
    with open(args.policy, encoding="utf-8") as f:
        POLICY = json.load(f)
    server = await asyncio.start_server(handle_client, "127.0.0.1", 0)
    port = server.sockets[0].getsockname()[1]
    with open(args.port_file, "w", encoding="utf-8") as f:
        f.write(str(port))
    sys.stderr.write(f"[egress] sandbox={SANDBOX_ID} mode={MODE} listening 127.0.0.1:{port}\n")
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
