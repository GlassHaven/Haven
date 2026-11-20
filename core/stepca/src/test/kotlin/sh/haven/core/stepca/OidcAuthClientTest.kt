package sh.haven.core.stepca

import android.content.Context
import android.net.Uri
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import sh.haven.core.data.db.entities.StepCaConfig
import java.net.URLDecoder

/**
 * Token-exchange body construction tests — driven against a real local
 * `com.sun.net.httpserver.HttpServer` so we exercise the actual HTTP
 * client, not a fake. The IdP usually rejects with `invalid_client` when
 * a confidential client forgets its secret (the bug in #133 phase 2a);
 * these tests pin the on-the-wire shape so that regression can't recur.
 *
 * Also covers the "stuck spinner" cancellation path added in phase 2c.
 */
@RunWith(RobolectricTestRunner::class)
class OidcAuthClientTest {

    private lateinit var server: MockWebServer
    private lateinit var tokenUrl: String

    @Before
    fun startTokenServer() {
        server = MockWebServer().apply { start() }
        tokenUrl = server.url("/token").toString()
    }

    @After
    fun stopTokenServer() {
        server.shutdown()
    }

    private fun enqueueTokenOk() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id_token":"eyJ.fake.token"}"""),
        )
    }

    @Test
    fun `token body includes client_secret when present`() = runTest {
        enqueueTokenOk()
        val client = OidcAuthClient(
            appContext = mockk<Context>(relaxed = true),
            foregroundWatcher = NeverReturnsForegroundWatcher,
        )
        val config = baseConfig(tokenUrl).copy(oidcClientSecret = "auth-secret-from-provisioners")

        withContext(Dispatchers.IO) {
            client.authorize(config, FakeCompletingLauncher)
        }
        val req = server.takeRequest()
        val params = parseForm(req.body.readUtf8())
        assertEquals("authorization_code", params["grant_type"])
        assertEquals("test-client", params["client_id"])
        assertEquals("auth-secret-from-provisioners", params["client_secret"])
        assertTrue(params["code"]?.isNotEmpty() == true)
        assertTrue(params["code_verifier"]?.isNotEmpty() == true)
    }

    @Test
    fun `token body omits client_secret for public PKCE clients`() = runTest {
        enqueueTokenOk()
        val client = OidcAuthClient(
            appContext = mockk<Context>(relaxed = true),
            foregroundWatcher = NeverReturnsForegroundWatcher,
        )
        val config = baseConfig(tokenUrl).copy(oidcClientSecret = null)

        withContext(Dispatchers.IO) {
            client.authorize(config, FakeCompletingLauncher)
        }
        val params = parseForm(server.takeRequest().body.readUtf8())
        assertFalse("public client must not send client_secret", params.containsKey("client_secret"))
        assertTrue(params["code_verifier"]?.isNotEmpty() == true)
    }

    @Test
    fun `token body omits client_secret when empty`() = runTest {
        // Bootstrap stores an empty string when the user clears the field; the
        // sender must treat it as "no secret" so we don't post a literal
        // empty value that IdPs may interpret as a client-auth attempt.
        enqueueTokenOk()
        val client = OidcAuthClient(
            appContext = mockk<Context>(relaxed = true),
            foregroundWatcher = NeverReturnsForegroundWatcher,
        )
        val config = baseConfig(tokenUrl).copy(oidcClientSecret = "")

        withContext(Dispatchers.IO) {
            client.authorize(config, FakeCompletingLauncher)
        }
        assertFalse(parseForm(server.takeRequest().body.readUtf8()).containsKey("client_secret"))
    }

    @Test(timeout = 10_000)
    fun `cancellation when foreground returns without redirect`() = runTest {
        // FakeNonLaunchingLauncher never triggers the redirect bus; the
        // ImmediateForegroundWatcher returns straight away, after which the
        // cancellationJob's grace delay elapses and fails the deferred. Use a
        // short injectable grace (50ms) and run on Dispatchers.IO like the
        // sibling tests so the real delay completes quickly even on a busy CI
        // runner (the production 1500ms grace timed out under load). The
        // timeout just guards a hang regression.
        val client = OidcAuthClient(
            appContext = mockk<Context>(relaxed = true),
            foregroundWatcher = ImmediateForegroundWatcher,
            cancelGraceMs = 50L,
        )
        val config = baseConfig(tokenUrl)

        val ex = runCatching {
            withContext(Dispatchers.IO) {
                client.authorize(config, FakeNonLaunchingLauncher)
            }
        }.exceptionOrNull()

        assertTrue(
            "expected cancellation error, got $ex",
            ex is IllegalStateException && ex.message?.contains("cancelled") == true,
        )
    }

    private fun baseConfig(token: String) = StepCaConfig(
        name = "test-ca",
        caUrl = "https://ca.example",
        oidcIssuer = "https://idp.example/",
        oidcAuthUrl = "https://idp.example/authorize",
        oidcTokenUrl = token,
        oidcClientId = "test-client",
        provisioner = "test",
        defaultPrincipals = "",
        rootCertPem = "",
    )

    private fun parseForm(body: String): Map<String, String> = body
        .split('&')
        .mapNotNull { kv ->
            val idx = kv.indexOf('=')
            if (idx < 0) null
            else URLDecoder.decode(kv.substring(0, idx), "UTF-8") to
                URLDecoder.decode(kv.substring(idx + 1), "UTF-8")
        }
        .toMap()

    /** Launcher that immediately resolves the OIDC bus with a canned code. */
    private object FakeCompletingLauncher : OidcAuthClient.CustomTabLauncher {
        override fun launch(uri: Uri) {
            val state = uri.getQueryParameter("state")
                ?: error("auth URL missing `state` param")
            OidcRedirectBus.complete(state, "fake-auth-code")
        }
    }

    /** Launcher that does nothing — simulates user opening the tab and closing it. */
    private object FakeNonLaunchingLauncher : OidcAuthClient.CustomTabLauncher {
        override fun launch(uri: Uri) = Unit
    }

    /** Suspends indefinitely — used for the happy-path tests where the
     *  redirect bus resolves before we'd ever need cancellation. */
    private object NeverReturnsForegroundWatcher : OidcAuthClient.ForegroundWatcher {
        override suspend fun awaitReturnToForeground() {
            // park indefinitely — the cancellation child job is cancelled on
            // success via the try/finally in authorize().
            delay(Long.MAX_VALUE)
        }
    }

    /** Returns immediately — simulates the Custom Tab closing without firing
     *  the deep-link, which is the bug we're guarding against. */
    private object ImmediateForegroundWatcher : OidcAuthClient.ForegroundWatcher {
        override suspend fun awaitReturnToForeground() = Unit
    }
}
