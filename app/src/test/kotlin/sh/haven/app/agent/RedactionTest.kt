package sh.haven.app.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pin the secret-redaction surface used by [AgentAuditRecorder]. The
 * audit table is the only on-disk record of agent activity (VISION §85),
 * so any plaintext credential leaking through here would land
 * permanently. These tests guard the field-name allow/deny list and the
 * recursion through nested structures.
 */
class RedactionTest {

    @Test
    fun `password field is redacted`() {
        val out = redactJson(JSONObject().put("password", "hunter2"))
        assertEquals("<redacted>", out.optString("password"))
    }

    @Test
    fun `case insensitive across the secret patterns`() {
        val keys = listOf(
            "password", "Password", "passwd",
            "secret", "SECRET", "userSecret",
            "token", "accessToken", "API_TOKEN",
            "credential", "credentials",
            "apiKey", "api_key", "APIKEY",
            "privateKey", "private_key",
            "passphrase",
        )
        for (k in keys) {
            val out = redactJson(JSONObject().put(k, "should-not-leak"))
            assertEquals(
                "key '$k' must be redacted",
                "<redacted>",
                out.optString(k),
            )
        }
    }

    @Test
    fun `safe identifiers are preserved`() {
        // The bare word "key" is intentionally allowed — keyId, publicKey,
        // and fingerprint are common safe identifiers.
        val args = JSONObject()
            .put("profileId", "abc-123")
            .put("sessionId", "sess-7")
            .put("keyId", "k-9")
            .put("publicKey", "ssh-ed25519 AAAA…")
            .put("fingerprint", "SHA256:xyz")
            .put("url", "https://example.com")
            .put("mimeType", "video/mp4")
            .put("ruleId", "r-42")
            .put("path", "/tmp/x")

        val out = redactJson(args)
        assertEquals("abc-123", out.optString("profileId"))
        assertEquals("sess-7", out.optString("sessionId"))
        assertEquals("k-9", out.optString("keyId"))
        assertEquals("ssh-ed25519 AAAA…", out.optString("publicKey"))
        assertEquals("SHA256:xyz", out.optString("fingerprint"))
        assertEquals("https://example.com", out.optString("url"))
        assertEquals("video/mp4", out.optString("mimeType"))
        assertEquals("r-42", out.optString("ruleId"))
        assertEquals("/tmp/x", out.optString("path"))
    }

    @Test
    fun `redaction recurses into nested objects`() {
        val args = JSONObject()
            .put("outer", JSONObject().put("password", "leak"))
            .put("nested", JSONObject()
                .put("safe", "ok")
                .put("apiKey", "leak-2"))

        val out = redactJson(args)
        assertEquals("<redacted>", out.optJSONObject("outer")?.optString("password"))
        assertEquals("ok", out.optJSONObject("nested")?.optString("safe"))
        assertEquals("<redacted>", out.optJSONObject("nested")?.optString("apiKey"))
    }

    @Test
    fun `redaction recurses into arrays of objects`() {
        val args = JSONObject().put(
            "items",
            JSONArray()
                .put(JSONObject().put("password", "p1").put("name", "alice"))
                .put(JSONObject().put("password", "p2").put("name", "bob")),
        )
        val out = redactJson(args)
        val arr = out.optJSONArray("items")!!
        assertEquals("<redacted>", arr.getJSONObject(0).optString("password"))
        assertEquals("alice", arr.getJSONObject(0).optString("name"))
        assertEquals("<redacted>", arr.getJSONObject(1).optString("password"))
        assertEquals("bob", arr.getJSONObject(1).optString("name"))
    }

    @Test
    fun `original input is not mutated`() {
        val args = JSONObject().put("password", "stays-here")
        redactJson(args)
        assertEquals(
            "redactJson must return a copy, not mutate input",
            "stays-here",
            args.optString("password"),
        )
    }

    @Test
    fun `realistic upload_file_to_sftp args pass through cleanly`() {
        val args = JSONObject()
            .put("profileId", "ssh-prod")
            .put("localPath", "/data/data/sh.haven.app/cache/agent/x.bin")
            .put("remotePath", "/var/spool/incoming/x.bin")

        val out = redactJson(args)
        assertEquals("ssh-prod", out.optString("profileId"))
        assertFalse(
            "localPath must not be redacted (it's a path under cacheDir, not a secret)",
            out.optString("localPath").contains("redacted"),
        )
        assertFalse(out.optString("remotePath").contains("redacted"))
    }

    @Test
    fun `realistic add_port_forward args pass through cleanly`() {
        val args = JSONObject()
            .put("profileId", "ssh-prod")
            .put("type", "LOCAL")
            .put("bindAddress", "127.0.0.1")
            .put("bindPort", 8730)
            .put("targetHost", "10.0.0.5")
            .put("targetPort", 22)

        val out = redactJson(args)
        assertEquals("LOCAL", out.optString("type"))
        assertEquals(8730, out.optInt("bindPort"))
        assertEquals(22, out.optInt("targetPort"))
        assertEquals("10.0.0.5", out.optString("targetHost"))
    }

    @Test
    fun `send_terminal_input text is preserved (the whole point of audit)`() {
        // We deliberately do NOT redact terminal input — the entire reason
        // EVERY_CALL consent shows the literal payload is so the user
        // sees what they're authorising. The audit row should preserve
        // that same text so it can be reviewed later.
        val args = JSONObject()
            .put("sessionId", "sess-42")
            .put("text", "rm -rf /tmp/build\n")

        val out = redactJson(args)
        assertEquals("rm -rf /tmp/build\n", out.optString("text"))
        assertEquals("sess-42", out.optString("sessionId"))
    }

    @Test
    fun `null and empty values do not crash`() {
        val args = JSONObject()
            .put("password", JSONObject.NULL)
            .put("secret", "")
            .put("safe", JSONArray())

        val out = redactJson(args)
        assertTrue(
            "null secret value is replaced with the redacted marker, not preserved as null",
            out.optString("password") == "<redacted>",
        )
        assertEquals("<redacted>", out.optString("secret"))
        assertEquals(0, out.optJSONArray("safe")?.length() ?: -1)
    }
}
