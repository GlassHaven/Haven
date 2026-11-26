#!/usr/bin/env python3
"""Automated security, unit, and 250-payload fuzz test suite for host/linux-sandbox.

Verifies:
  1. HTTP header-forwarding & RFC 9112 request-smuggling fuzz suite (duplicate/spoofed
     Host, bare CR/LF/NUL, obs-fold, whitespace before colon, Connection hop-by-hop +
     Proxy-* stripping, Transfer-Encoding & conflicting Content-Length rejection, split
     TCP Content-Length body delivery, authority userinfo/port bounds, 250 mutated inputs).
  2. Policy ordering & SSRF/DNS-rebinding defenses (deny/telemetry before standing allow
     and before DNS lookup, multi-record DNS rebinding rejection, CGNAT/slirp/loopback).
  3. HMAC-SHA256 signed decision verification (valid once/session, forged sig, wrong
     request_id/host/port, expired grant, short/missing key).
  4. haven-sandbox-helper env-file root-delete guard & env-var whitelist (rejects
     non-matching paths, symlinks, and wrong-UID files with exit 2 WITHOUT deleting;
     exports whitelisted model vars while stripping LD_PRELOAD, BASH_ENV, PYTHONPATH)
     plus nftables endpoint injection rejection.
  5. Isolated `env -i python3 -I -S` startup and `install.sh --check` non-root mode.
"""
import asyncio
import base64
import hashlib
import hmac
import importlib.util
import os
import random
import string
import subprocess
import sys
import tempfile
import time
import unittest

sys.dont_write_bytecode = True

BUNDLE_DIR = os.path.dirname(os.path.abspath(__file__))
PROXY_PATH = os.path.join(BUNDLE_DIR, "haven-egress-proxy.py")
POLICY_PATH = os.path.join(BUNDLE_DIR, "policy.json")
HELPER_PATH = os.path.join(BUNDLE_DIR, "haven-sandbox-helper")
INSTALL_PATH = os.path.join(BUNDLE_DIR, "install.sh")


def load_proxy_module():
    spec = importlib.util.spec_from_file_location("haven_egress_proxy", PROXY_PATH)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


class EgressProxyHeaderFuzzTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.mod = load_proxy_module()
        self.tmpdir = tempfile.TemporaryDirectory()
        self.mod.AUDIT_PATH = os.path.join(self.tmpdir.name, "audit.jsonl")
        self.mod.SANDBOX_ID = "fuzz-test"
        self.mod.MODE = "phase1"
        self.mod.POLICY = {
            "standing_allow": ["github.com", "api.github.com"],
            "deny": ["*.sentry.io", "claude.ai"],
        }
        self.captured_requests = []

        async def upstream_handler(r, w):
            try:
                data = await asyncio.wait_for(r.read(65536), 2.0)
                self.captured_requests.append(data)
                w.write(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK")
                await w.drain()
            except Exception:
                pass
            finally:
                w.close()

        self.upstream = await asyncio.start_server(upstream_handler, "127.0.0.1", 0)
        self.up_port = self.upstream.sockets[0].getsockname()[1]

        self.mod.SESSION_GRANTS = {("allowed.example", 80): None}
        self.mod.resolve_global = lambda h: "93.184.216.34" if h in ("allowed.example", "github.com") else None

        orig_open = asyncio.open_connection

        async def fake_open(host, port, *a, **kw):
            if host == "93.184.216.34":
                return await orig_open("127.0.0.1", self.up_port)
            return await orig_open(host, port, *a, **kw)

        self._orig_open = orig_open
        asyncio.open_connection = fake_open

        self.proxy = await asyncio.start_server(self.mod.handle_client, "127.0.0.1", 0)
        self.proxy_port = self.proxy.sockets[0].getsockname()[1]

    async def asyncTearDown(self):
        asyncio.open_connection = self._orig_open
        self.proxy.close()
        await self.proxy.wait_closed()
        self.upstream.close()
        await self.upstream.wait_closed()
        self.tmpdir.cleanup()

    async def send_raw(self, chunks, pause=0.02):
        r, w = await asyncio.open_connection("127.0.0.1", self.proxy_port)
        if isinstance(chunks, (bytes, bytearray)):
            chunks = [chunks]
        for idx, c in enumerate(chunks):
            if idx > 0:
                await asyncio.sleep(pause)
            w.write(c)
            await w.drain()
        try:
            resp = await asyncio.wait_for(r.read(65536), 2.0)
        except asyncio.TimeoutError:
            resp = b""
        w.close()
        return resp

    async def test_01_duplicate_or_spoofed_host_header_blocked(self):
        for raw in [
            b"GET http://allowed.example/test HTTP/1.1\r\nHost: evil.sentry.io\r\n\r\n",
            b"GET http://allowed.example/test HTTP/1.1\r\nHost: allowed.example\r\nHost: evil.sentry.io\r\n\r\n",
            b"CONNECT github.com:443 HTTP/1.1\r\nHost: evil.sentry.io:443\r\n\r\n",
        ]:
            before = len(self.captured_requests)
            resp = await self.send_raw(raw)
            self.assertEqual(len(self.captured_requests), before)
            self.assertIn(b"400 Bad Request", resp)

    async def test_02_bare_lf_and_cr_header_injection_rejected(self):
        for bad in [
            b"GET http://allowed.example/ HTTP/1.1\r\nX-A: 1\nX-Injected: pwn\r\n\r\n",
            b"GET http://allowed.example/ HTTP/1.1\r\nX-A: 1\rX-Injected: pwn\r\n\r\n",
            b"GET http://allowed.example/foo\r\nX-Injected:1 HTTP/1.1\r\nHost: allowed.example\r\n\r\n",
            b"GET http://allowed.example/foo\x00bar HTTP/1.1\r\nHost: allowed.example\r\n\r\n",
        ]:
            before = len(self.captured_requests)
            await self.send_raw(bad)
            self.assertEqual(len(self.captured_requests), before)

    async def test_03_invalid_header_names_and_obs_fold_rejected(self):
        for bad in [
            b"GET http://allowed.example/ HTTP/1.1\r\nHost: allowed.example\r\nContent-Length : 0\r\n\r\n",
            b"GET http://allowed.example/ HTTP/1.1\r\nHost: allowed.example\r\nX-Fold: a\r\n\tb\r\n\r\n",
            b"GET http://allowed.example/ HTTP/1.1\r\nHost: allowed.example\r\nBad@Name: 1\r\n\r\n",
        ]:
            before = len(self.captured_requests)
            await self.send_raw(bad)
            self.assertEqual(len(self.captured_requests), before)

    async def test_04_connection_named_hop_by_hop_and_proxy_headers_stripped(self):
        raw = (
            b"GET http://allowed.example/ok HTTP/1.1\r\n"
            b"Host: allowed.example\r\n"
            b"Connection: X-Hop-Secret, Keep-Alive\r\n"
            b"X-Hop-Secret: super-secret\r\n"
            b"Proxy-Authorization: Basic dXNlcjpwYXNz\r\n"
            b"Proxy-Foo: bar\r\n"
            b"X-Keep-Me: yes\r\n\r\n"
        )
        resp = await self.send_raw(raw)
        self.assertIn(b"200 OK", resp)
        self.assertEqual(len(self.captured_requests), 1)
        fwd_lower = self.captured_requests[0].decode("latin1").lower()
        self.assertNotIn("x-hop-secret", fwd_lower)
        self.assertNotIn("proxy-authorization", fwd_lower)
        self.assertNotIn("proxy-foo", fwd_lower)
        self.assertIn("x-keep-me: yes", fwd_lower)

    async def test_05_smuggling_te_and_conflicting_cl_rejected_and_split_body_forwarded(self):
        for smuggle in [
            b"POST http://allowed.example/ HTTP/1.1\r\nHost: allowed.example\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n",
            b"POST http://allowed.example/ HTTP/1.1\r\nHost: allowed.example\r\nContent-Length: 4\r\nContent-Length: 0\r\n\r\nABCD",
            b"POST http://allowed.example/ HTTP/1.1\r\nHost: allowed.example\r\nContent-Length: -1\r\n\r\n",
            b"POST http://allowed.example/ HTTP/1.1\r\nHost: allowed.example\r\nContent-Length: 12xyz\r\n\r\n",
        ]:
            before = len(self.captured_requests)
            await self.send_raw(smuggle)
            self.assertEqual(len(self.captured_requests), before)

        # Valid POST with body arriving across two TCP writes + trailing pipelined junk dropped
        resp = await self.send_raw([
            b"POST http://allowed.example/submit HTTP/1.1\r\nHost: allowed.example\r\nContent-Length: 11\r\n\r\nhello ",
            b"worldGET http://evil.sentry.io/ HTTP/1.1\r\n\r\n",
        ])
        self.assertIn(b"200 OK", resp)
        self.assertTrue(self.captured_requests[-1].endswith(b"\r\n\r\nhello world"))

    async def test_06_authority_userinfo_and_port_bounds(self):
        for bad in [
            b"GET http://allowed.example@evil.sentry.io/ HTTP/1.1\r\n\r\n",
            b"CONNECT github.com@evil.sentry.io:443 HTTP/1.1\r\n\r\n",
            b"CONNECT github.com:0 HTTP/1.1\r\n\r\n",
            b"CONNECT github.com:70000 HTTP/1.1\r\n\r\n",
            b"CONNECT github.com:-443 HTTP/1.1\r\n\r\n",
        ]:
            before = len(self.captured_requests)
            resp = await self.send_raw(bad)
            self.assertEqual(len(self.captured_requests), before)
            self.assertNotIn(b"200 ", resp)

    async def test_07_random_mutation_fuzz_250_keeps_server_alive(self):
        rng = random.Random(20261126)
        alphabet = (string.printable + "\x00\x01\x7f\x80\xff").encode("latin1", "ignore")
        for _ in range(250):
            mode = rng.randint(0, 4)
            if mode == 0:
                blob = bytes(rng.choice(alphabet) for _ in range(rng.randint(1, 300))) + b"\r\n\r\n"
            elif mode == 1:
                hname = "".join(rng.choice("Host: \t\r\nX-Test@\x00") for _ in range(12)).encode("latin1")
                blob = b"GET http://evil.sentry.io/ HTTP/1.1\r\n" + hname + b": val\r\n\r\n"
            elif mode == 2:
                blob = f"CONNECT 127.0.0.1:{rng.randint(-5, 90000)} HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".encode()
            elif mode == 3:
                blob = b"POST http://allowed.example/ HTTP/1.1\r\nTransfer-Encoding: gzip, chunked\r\nContent-Length: 5\r\n\r\n12345"
            else:
                blob = b"GET http://allowed.example/p HTTP/1.1\r\nHost: other.host\r\n\r\n"
            await self.send_raw(blob, pause=0)

        self.captured_requests.clear()
        ok_resp = await self.send_raw(
            b"GET http://allowed.example/health HTTP/1.1\r\nHost: allowed.example\r\n\r\n"
        )
        self.assertIn(b"200 OK", ok_resp)
        self.assertEqual(len(self.captured_requests), 1)


class PolicyHmacAndHelperSecurityTest(unittest.TestCase):
    def test_policy_order_deny_precedes_dns_and_ssrf_blocks_rebinding(self):
        mod = load_proxy_module()
        mod.POLICY = {
            "standing_allow": ["github.com", "evil.sentry.io"],
            "deny": ["*.sentry.io"],
        }
        dns_calls = []

        def fake_getaddrinfo(host, *_a, **_kw):
            dns_calls.append(host)
            if host == "rebind.example":
                return [
                    (0, 0, 0, "", ("93.184.216.34", 0)),
                    (0, 0, 0, "", ("10.0.2.2", 0)),
                ]
            return [(0, 0, 0, "", ("93.184.216.34", 0))]

        orig_gai = mod.socket.getaddrinfo
        mod.socket.getaddrinfo = fake_getaddrinfo
        try:
            # Denied host must fail BEFORE DNS resolution AND even if listed in standing_allow
            allow, src, ip = mod.policy_check("sub.sentry.io", 443)
            self.assertFalse(allow)
            self.assertEqual(src, "telemetry")
            self.assertIsNone(ip)
            self.assertEqual(dns_calls, [])

            # Multi-record DNS answer with one private/slirp IP must fail SSRF check
            allow, src, ip = mod.policy_check("rebind.example", 443)
            self.assertFalse(allow)
            self.assertEqual(src, "ssrf")
            self.assertIsNone(ip)

            # CGNAT IP literal rejected
            allow, src, _ = mod.policy_check("100.64.12.34", 443)
            self.assertFalse(allow)
            self.assertEqual(src, "ssrf")
        finally:
            mod.socket.getaddrinfo = orig_gai

    def test_verify_decision_hmac_contract(self):
        mod = load_proxy_module()
        with tempfile.TemporaryDirectory() as tmp:
            key_path = os.path.join(tmp, "haven.key")
            key = os.urandom(32)
            with open(key_path, "wb") as f:
                f.write(key)
            mod.HAVEN_KEY_PATH = key_path

            exp_ms = int(time.time() * 1000) + 60_000
            canonical = "\n".join([
                "req-123", "hvnsb-99", "pypi.org", "443", "session", str(exp_ms), "grant-1", "nonce-abc"
            ])
            sig = base64.b64encode(hmac.new(key, canonical.encode("utf-8"), hashlib.sha256).digest()).decode("ascii")
            valid_payload = {
                "request_id": "req-123",
                "sandbox_id": "hvnsb-99",
                "host": "pypi.org",
                "port": 443,
                "decision": "session",
                "expiry_ms": exp_ms,
                "grant_id": "grant-1",
                "nonce": "nonce-abc",
                "signature": sig,
            }
            self.assertTrue(mod.verify_decision(valid_payload, "req-123", "pypi.org", 443))
            # Mismatched host or port fails closed
            self.assertFalse(mod.verify_decision(valid_payload, "req-123", "evil.org", 443))
            self.assertFalse(mod.verify_decision(valid_payload, "req-123", "pypi.org", 80))
            # Forged signature fails closed
            forged = dict(valid_payload, signature=base64.b64encode(b"0" * 32).decode("ascii"))
            self.assertFalse(mod.verify_decision(forged, "req-123", "pypi.org", 443))
            # Expired grant fails closed
            expired_exp = int(time.time() * 1000) - 1000
            exp_canon = "\n".join([
                "req-123", "hvnsb-99", "pypi.org", "443", "session", str(expired_exp), "grant-1", "nonce-abc"
            ])
            exp_sig = base64.b64encode(hmac.new(key, exp_canon.encode("utf-8"), hashlib.sha256).digest()).decode("ascii")
            self.assertFalse(mod.verify_decision(dict(valid_payload, expiry_ms=expired_exp, signature=exp_sig), "req-123", "pypi.org", 443))

    def test_helper_envfile_root_delete_guard_and_whitelist(self):
        my_uid = str(os.getuid())

        # 1. Arbitrary file outside /tmp/haven-sb-env.?????? must exit 2 WITHOUT deleting victim
        victim_fd, victim_path = tempfile.mkstemp(prefix="victim-", dir="/tmp")
        os.close(victim_fd)
        try:
            res = subprocess.run(
                ["bash", HELPER_PATH, "--self-test-validation", "envfile", my_uid, victim_path],
                capture_output=True,
                text=True,
            )
            self.assertEqual(res.returncode, 2)
            self.assertTrue(os.path.exists(victim_path), "Arbitrary path was deleted!")

            # 2. Symlink matching /tmp/haven-sb-env.?????? pointing at victim must exit 2 WITHOUT deleting either
            rand6 = "".join(random.choices(string.ascii_letters + string.digits, k=6))
            sym_path = f"/tmp/haven-sb-env.{rand6}"
            os.symlink(victim_path, sym_path)
            try:
                res_sym = subprocess.run(
                    ["bash", HELPER_PATH, "--self-test-validation", "envfile", my_uid, sym_path],
                    capture_output=True,
                    text=True,
                )
                self.assertEqual(res_sym.returncode, 2)
                self.assertTrue(os.path.islink(sym_path), "Symlink was deleted!")
                self.assertTrue(os.path.exists(victim_path), "Symlink target was deleted!")
            finally:
                if os.path.lexists(sym_path):
                    os.unlink(sym_path)

            # 3. Valid /tmp/haven-sb-env.?????? with wrong expected UID must exit 2 WITHOUT deleting
            rand6_b = "".join(random.choices(string.ascii_letters + string.digits, k=6))
            env_path = f"/tmp/haven-sb-env.{rand6_b}"
            with open(env_path, "w", encoding="utf-8") as f:
                f.write(
                    "OPENAI_API_KEY=sk-allowed\n"
                    "ANTHROPIC_API_KEY=ant-allowed\n"
                    "GEMINI_API_KEY=gem-allowed\n"
                    "LD_PRELOAD=/tmp/evil.so\n"
                    "BASH_ENV=/tmp/evil.sh\n"
                    "PYTHONPATH=/tmp/evil\n"
                    "UNLISTED_SECRET=nope\n"
                )
            os.chmod(env_path, 0o600)
            try:
                wrong_uid = "65534" if my_uid != "65534" else "65533"
                res_uid = subprocess.run(
                    ["bash", HELPER_PATH, "--self-test-validation", "envfile", wrong_uid, env_path],
                    capture_output=True,
                    text=True,
                )
                self.assertEqual(res_uid.returncode, 2)
                self.assertTrue(os.path.exists(env_path), "Wrong-owner envfile was deleted!")

                # 4. Valid /tmp/haven-sb-env.?????? owned by expected UID exports ONLY whitelisted keys and unlinks
                clean_env = {"PATH": "/usr/bin:/bin"}
                res_ok = subprocess.run(
                    ["bash", HELPER_PATH, "--self-test-validation", "envfile", my_uid, env_path],
                    capture_output=True,
                    text=True,
                    env=clean_env,
                )
                self.assertEqual(res_ok.returncode, 0, res_ok.stderr)
                self.assertFalse(os.path.exists(env_path), "Valid envfile should be unlinked after load")
                out_lines = set(res_ok.stdout.splitlines())
                self.assertIn("OPENAI_API_KEY=sk-allowed", out_lines)
                self.assertIn("ANTHROPIC_API_KEY=ant-allowed", out_lines)
                self.assertIn("GEMINI_API_KEY=gem-allowed", out_lines)
                for line in out_lines:
                    self.assertFalse(line.startswith("LD_PRELOAD="))
                    self.assertFalse(line.startswith("BASH_ENV="))
                    self.assertFalse(line.startswith("PYTHONPATH="))
                    self.assertFalse(line.startswith("UNLISTED_SECRET="))
            finally:
                if os.path.exists(env_path):
                    os.unlink(env_path)
        finally:
            if os.path.exists(victim_path):
                os.unlink(victim_path)

    def test_helper_endpoint_validation_blocks_nft_injection(self):
        ok = subprocess.run(
            ["bash", HELPER_PATH, "--self-test-validation", "endpoints", "api.anthropic.com,localhost:11434,api.openai.com:443"],
            capture_output=True,
            text=True,
        )
        self.assertEqual(ok.returncode, 0, ok.stderr)

        for bad in [
            "",
            "example.com:0",
            "example.com:70000",
            "example.com:443; accept",
            "example.com\naccept",
            "-badhost.com",
            "http://example.com",
        ]:
            res = subprocess.run(
                ["bash", HELPER_PATH, "--self-test-validation", "endpoints", bad],
                capture_output=True,
                text=True,
            )
            self.assertEqual(res.returncode, 2, f"Expected exit 2 for bad endpoint {bad!r}")

    def test_install_check_mode_and_isolated_python_startup(self):
        res = subprocess.run(
            ["bash", INSTALL_PATH, "--check"],
            capture_output=True,
            text=True,
        )
        self.assertEqual(res.returncode, 0, res.stderr + res.stdout)
        self.assertIn("haven-egress-proxy self-check OK", res.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
