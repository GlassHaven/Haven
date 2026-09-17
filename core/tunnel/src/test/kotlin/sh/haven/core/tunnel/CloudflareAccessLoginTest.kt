package sh.haven.core.tunnel

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class CloudflareAccessLoginTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test fun `absolute login Location is used verbatim`() {
        val loc = "https://ssh.example.com/cdn-cgi/access/login/ssh.example.com?kid=abc123&meta=%7B%22gateway_id%22%3A%22xyz%22%7D&redirect_url=%2F"
        assertEquals(loc, CloudflareAccessLogin.resolveLoginUrl(loc, "ssh.example.com"))
    }

    @Test fun `path-only Location is made absolute against the protected host`() {
        val resolved = CloudflareAccessLogin.resolveLoginUrl(
            "/cdn-cgi/access/login/ssh.example.com?kid=abc",
            "ssh.example.com",
        )
        assertEquals("https://ssh.example.com/cdn-cgi/access/login/ssh.example.com?kid=abc", resolved)
    }

    @Test fun `Location that already bounced to an IdP is rejected`() {
        // Following this would hand the WebView a URL with no Access context
        // and the user's cookie jar pointed at a third party.
        assertNull(
            CloudflareAccessLogin.resolveLoginUrl(
                "https://company.okta.com/oauth2/v1/authorize?client_id=deadbeef",
                "ssh.example.com",
            ),
        )
    }

    @Test fun `non-http schemes are rejected`() {
        assertNull(CloudflareAccessLogin.resolveLoginUrl("javascript:alert(1)", "ssh.example.com"))
        assertNull(CloudflareAccessLogin.resolveLoginUrl("file:///etc/passwd", "ssh.example.com"))
    }

    @Test fun `null and blank Locations fall back`() {
        assertNull(CloudflareAccessLogin.resolveLoginUrl(null, "ssh.example.com"))
        assertNull(CloudflareAccessLogin.resolveLoginUrl("   ", "ssh.example.com"))
    }

    @Test fun `only a redirect yields a login Location`() {
        assertEquals(
            "/cdn-cgi/access/login/host?kid=k1",
            CloudflareAccessLogin.loginLocationFromResponse(302, "/cdn-cgi/access/login/host?kid=k1"),
        )
        // 200 = not Access-protected (or already signed in): nothing to load.
        assertNull(CloudflareAccessLogin.loginLocationFromResponse(200, "/cdn-cgi/access/login/host"))
        // A 302 with no Location is malformed; the caller falls back.
        assertNull(CloudflareAccessLogin.loginLocationFromResponse(302, null))
        // 401/403/5xx are not login redirects.
        assertNull(CloudflareAccessLogin.loginLocationFromResponse(403, "/cdn-cgi/access/login/host"))
    }

    // --- fetchLoginLocation against a real (loopback) HTTP surface ---

    @Test fun `a 302 to the login path yields its Location`() = runTest {
        val location = "https://team.cloudflareaccess.com/cdn-cgi/access/login/ssh.example.com?kid=k1&meta=m1"
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", location))
        val client = OkHttpClient.Builder().build()
        val probeUrl = server.url("/").toString()
        assertEquals(location, CloudflareAccessLogin.fetchLoginLocationFrom(client, probeUrl))
    }

    @Test fun `a non-redirect answer yields nothing to load`() = runTest {
        // 200 = not Access-protected (or already signed in).
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>origin</html>"))
        val client = OkHttpClient.Builder().build()
        assertNull(CloudflareAccessLogin.fetchLoginLocationFrom(client, server.url("/").toString()))
    }

    @Test fun `an unreachable origin falls back rather than throwing`() = runTest {
        // The activity treats any probe failure as "use the constructed
        // login URL" — the call itself must never propagate.
        val client = OkHttpClient.Builder()
            .callTimeout(java.time.Duration.ofMillis(500))
            .build()
        assertNull(
            CloudflareAccessLogin.fetchLoginLocationFrom(
                client,
                // Nothing listens here; connects fail fast.
                "http://127.0.0.1:1/",
            ),
        )
    }
}
